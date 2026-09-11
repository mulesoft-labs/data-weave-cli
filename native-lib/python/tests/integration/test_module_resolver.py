import io
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
from zipfile import ZipFile

import pytest

import dataweave


IMPORT_LIB_SCRIPT = """%dw 2.0
import org::test::lib
output application/json
---
lib::answer()
"""


def _import_script(module_name):
    return f"""%dw 2.0
import org::test::{module_name}
output application/json
---
{module_name}::answer()
"""


@pytest.mark.integration
def test_run_resolves_module_from_map():
    resolver = dataweave.modules_from_map({
        "org/test/lib.dwl": "%dw 2.0\nfun answer() = 42",
    })

    with dataweave.DataWeave(resolve_module=resolver) as dw:
        result = dw.run(IMPORT_LIB_SCRIPT)

    assert result.success is True
    assert result.get_string() == "42"


@pytest.mark.integration
def test_missing_module_returns_unsuccessful_result():
    with dataweave.DataWeave(
        resolve_module=dataweave.modules_from_map({}),
    ) as dw:
        result = dw.run(IMPORT_LIB_SCRIPT)

    assert result.success is False
    assert "resolve" in (result.error or "").lower()


@pytest.mark.integration
def test_raise_on_error_promotes_missing_module_result():
    with dataweave.DataWeave(
        resolve_module=dataweave.modules_from_map({}),
    ) as dw:
        with pytest.raises(dataweave.DataWeaveScriptError) as error:
            dw.run(IMPORT_LIB_SCRIPT, raise_on_error=True)

    assert error.value.result.success is False
    assert "resolve" in (error.value.result.error or "").lower()


def _write_transitive_modules(module_root):
    module_dir = module_root / "org" / "test"
    module_dir.mkdir(parents=True)
    (module_dir / "base.dwl").write_text(
        "%dw 2.0\nfun value() = 40",
        encoding="utf-8",
    )
    (module_dir / "lib.dwl").write_text(
        "%dw 2.0\nimport org::test::base\nfun answer() = base::value() + 2",
        encoding="utf-8",
    )


@pytest.mark.integration
def test_directory_resolver_supports_transitive_imports(tmp_path):
    _write_transitive_modules(tmp_path)

    with dataweave.DataWeave(
        resolve_module=dataweave.modules_from_directory(tmp_path),
    ) as dw:
        result = dw.run(IMPORT_LIB_SCRIPT)

    assert result.success is True
    assert result.get_string() == "42"


@pytest.mark.integration
def test_jar_resolver_supports_transitive_imports(tmp_path):
    module_root = tmp_path / "modules"
    _write_transitive_modules(module_root)
    jar_path = tmp_path / "modules.jar"
    with ZipFile(jar_path, "w") as jar:
        jar.write(module_root / "org" / "test" / "base.dwl", "org/test/base.dwl")
        jar.write(module_root / "org" / "test" / "lib.dwl", "org/test/lib.dwl")

    with dataweave.DataWeave(
        resolve_module=dataweave.modules_from_jars([jar_path]),
    ) as dw:
        result = dw.run(IMPORT_LIB_SCRIPT)

    assert result.success is True
    assert result.get_string() == "42"


@pytest.mark.integration
def test_repeated_runs_reuse_the_instances_resolver():
    resolver = dataweave.modules_from_map({
        "org/test/lib.dwl": "%dw 2.0\nfun answer() = 42",
    })

    with dataweave.DataWeave(resolve_module=resolver) as dw:
        first = dw.run(IMPORT_LIB_SCRIPT)
        second = dw.run(IMPORT_LIB_SCRIPT)

    assert first.success is True
    assert first.get_string() == "42"
    assert second.success is True
    assert second.get_string() == "42"


@pytest.mark.integration
def test_cleanup_of_one_instance_preserves_another_instances_resolver():
    first = dataweave.DataWeave(resolve_module=dataweave.modules_from_map({
        "org/test/lib.dwl": "%dw 2.0\nfun answer() = 41",
    }))
    second = dataweave.DataWeave(resolve_module=dataweave.modules_from_map({
        "org/test/lib.dwl": "%dw 2.0\nfun answer() = 42",
    }))
    first_initialized = False
    second_initialized = False
    try:
        first.initialize()
        first_initialized = True
        second.initialize()
        second_initialized = True

        first_result = first.run(IMPORT_LIB_SCRIPT)
        second_result = second.run(IMPORT_LIB_SCRIPT)
        assert first_result.success is True
        assert first_result.get_string() == "41"
        assert second_result.success is True
        assert second_result.get_string() == "42"

        first.cleanup()
        first_initialized = False

        result = second.run(IMPORT_LIB_SCRIPT)
        assert result.success is True
        assert result.get_string() == "42"
    finally:
        try:
            if first_initialized:
                first.cleanup()
        finally:
            if second_initialized:
                second.cleanup()


@pytest.mark.integration
def test_streaming_builtin_import_does_not_invoke_unsupported_external_resolver(
    collect_stream,
):
    calls = []

    def resolver(module_path):
        calls.append(module_path)
        return None

    script = """%dw 2.0
import fromBase64 from dw::core::Binaries
output application/json
---
sizeOf(fromBase64("aGk="))
"""
    with dataweave.DataWeave(resolve_module=resolver) as dw:
        output, metadata = collect_stream(dw.run_streaming(script))

    assert metadata.success is True
    assert output.decode(metadata.charset or "utf-8") == "2"
    assert calls == []


@pytest.mark.integration
def test_resolver_is_inactive_for_resolver_less_apis_after_synchronous_install(
    collect_stream,
):
    calls = []

    def resolver(module_path):
        calls.append(module_path)
        return "%dw 2.0\nfun answer() = 42"

    with dataweave.DataWeave(resolve_module=resolver) as dw:
        installed = dw.run(IMPORT_LIB_SCRIPT)
        calls_after_install = list(calls)

        _output, streaming = collect_stream(dw.run_streaming(_import_script("streaming")))
        _output, transform = collect_stream(
            dw.run_transform(
                _import_script("transform"),
                [b"null"],
                input_mime_type="application/json",
            )
        )

        callback_chunks = []
        callback = dw.run_callback(
            _import_script("callback"),
            lambda chunk: callback_chunks.append(chunk) or 0,
        )

        source = io.BytesIO(b"null")
        input_output_chunks = []
        input_output = dw.run_input_output_callback(
            _import_script("inputOutput"),
            input_name="payload",
            input_mime_type="application/json",
            read_callback=source.read,
            write_callback=lambda chunk: input_output_chunks.append(chunk) or 0,
        )

    assert installed.success is True
    assert calls_after_install
    assert streaming.success is False
    assert transform.success is False
    assert callback.success is False
    assert input_output.success is False
    assert callback_chunks == []
    assert input_output_chunks == []
    assert calls == calls_after_install


@pytest.mark.integration
def test_overlapping_resolver_aware_runs_are_serialized():
    source_dir = Path(__file__).resolve().parents[2] / "src"
    code = f"""
import json
from threading import Event, Lock, Thread

import dataweave

script = {IMPORT_LIB_SCRIPT!r}
first_resolver_call = Event()
release_first = Event()
second_resolver_call = Event()
results = []
errors = []
calls = 0
calls_lock = Lock()

def resolver(_module_path):
    global calls
    with calls_lock:
        calls += 1
        current_call = calls
    if current_call == 1:
        first_resolver_call.set()
        if not release_first.wait(2):
            raise RuntimeError("first resolver call was not released")
    else:
        second_resolver_call.set()
    return "%dw 2.0\\nfun answer() = 42"

with dataweave.DataWeave(resolve_module=resolver) as dw:
    def run():
        try:
            result = dw.run(script)
            results.append({{"success": result.success, "value": result.get_string()}})
        except Exception as error:
            errors.append(str(error))

    first = Thread(target=run)
    second = Thread(target=run)
    first.start()
    if not first_resolver_call.wait(2):
        raise RuntimeError("first resolver was not called")
    second.start()
    serialized = not second_resolver_call.wait(0.1)
    release_first.set()
    first.join(2)
    second.join(2)
    if first.is_alive() or second.is_alive():
        raise RuntimeError("resolver worker did not finish")

print(json.dumps({{
    "serialized": serialized,
    "second_called": second_resolver_call.is_set(),
    "results": results,
    "errors": errors,
}}))
"""
    environment = os.environ.copy()
    environment["PYTHONPATH"] = str(source_dir) + os.pathsep + environment.get("PYTHONPATH", "")

    completed = subprocess.run(
        [sys.executable, "-c", code],
        capture_output=True,
        check=False,
        env=environment,
        text=True,
        timeout=30,
    )

    assert completed.returncode == 0, completed.stderr
    response = json.loads(completed.stdout)
    assert response == {
        "serialized": True,
        "second_called": True,
        "results": [
            {"success": True, "value": "42"},
            {"success": True, "value": "42"},
        ],
        "errors": [],
    }


@pytest.mark.integration
def test_cross_engine_resolver_reentry_is_rejected_without_terminating_process():
    source_dir = Path(__file__).resolve().parents[2] / "src"
    code = f"""
import json

import dataweave

script = {IMPORT_LIB_SCRIPT!r}
nested = {{}}
inner = dataweave.DataWeave()

def resolver(_module_path):
    try:
        inner.run("40 + 2")
    except dataweave.DataWeaveError as error:
        nested["type"] = type(error).__name__
        nested["error"] = str(error)
    return "%dw 2.0\\nfun answer() = 42"

outer = dataweave.DataWeave(resolve_module=resolver)
inner.initialize()
outer.initialize()
try:
    result = outer.run(script)
    response = {{
        "nested_type": nested.get("type"),
        "nested_error": nested.get("error"),
        "outer": {{"success": result.success, "value": result.get_string()}},
    }}
finally:
    outer.cleanup()
    inner.cleanup()

print(json.dumps(response))
"""
    environment = os.environ.copy()
    environment["PYTHONPATH"] = str(source_dir) + os.pathsep + environment.get("PYTHONPATH", "")

    completed = subprocess.run(
        [sys.executable, "-c", code],
        capture_output=True,
        check=False,
        env=environment,
        text=True,
        timeout=30,
    )

    assert completed.returncode == 0, completed.stderr
    response = json.loads(completed.stdout)
    assert response["nested_type"] == "DataWeaveError"
    assert "native callback" in response["nested_error"].lower()
    assert response["outer"] == {"success": True, "value": "42"}
    assert "Fatal error" not in completed.stderr


@pytest.mark.integration
def test_shared_isolate_survives_until_the_last_instance_cleans_up():
    from dataweave import native

    a = dataweave.DataWeave(resolve_module=dataweave.modules_from_map({
        "org/test/lib.dwl": "%dw 2.0\nfun answer() = 1",
    }))
    b = dataweave.DataWeave(resolve_module=dataweave.modules_from_map({
        "org/test/lib.dwl": "%dw 2.0\nfun answer() = 2",
    }))
    a.initialize()
    b.initialize()
    try:
        assert native._isolate is not None
        assert native._isolate_ref_count == 2
        assert a.run(IMPORT_LIB_SCRIPT).get_string() == "1"
        assert b.run(IMPORT_LIB_SCRIPT).get_string() == "2"

        a.cleanup()
        assert native._isolate is not None          # b still holds a ref
        assert native._isolate_ref_count == 1
        assert b.run(IMPORT_LIB_SCRIPT).get_string() == "2"  # b unaffected
    finally:
        b.cleanup()
    assert native._isolate_ref_count == 0
    assert native._isolate is None                   # last release tore it down


@pytest.mark.integration
def test_last_release_on_a_foreign_thread_does_not_hang():
    from dataweave import native

    # Initialize on a worker thread so the isolate's creating thread differs from
    # the main thread that runs the final cleanup (the atexit-style ordering that
    # deadlocked before Finding #1's fix).
    holder = {}
    def make():
        dw = dataweave.DataWeave()
        dw.initialize()
        holder["dw"] = dw
    t = threading.Thread(target=make)
    t.start()
    t.join(30)
    assert not t.is_alive()
    dw = holder["dw"]
    try:
        assert native._isolate_ref_count == 1
        assert dw.run("%dw 2.0\noutput application/json\n---\n1 + 1").get_string() == "2"
    finally:
        # Last release runs HERE on the main thread (foreign to the creating
        # worker). On the unfixed code graal_tear_down_isolate blocks forever;
        # run the cleanup on a joinable thread with a timeout so a regression is a
        # bounded failure instead of hanging the suite.
        done = threading.Thread(target=dw.cleanup)
        done.start()
        done.join(30)
        assert not done.is_alive(), "cleanup() hung: last-release teardown blocked on a foreign thread"
    assert native._isolate_ref_count == 0
    assert native._isolate is None
