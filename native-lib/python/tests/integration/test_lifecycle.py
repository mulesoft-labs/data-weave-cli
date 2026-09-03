import json
import os
from pathlib import Path
import subprocess
import sys
import textwrap

import pytest

import dataweave


def _run_raw_abi_child(code):
    source_dir = Path(__file__).resolve().parents[2] / "src"
    environment = os.environ.copy()
    environment["DATAWEAVE_NATIVE_LIB"] = os.environ["DATAWEAVE_NATIVE_LIB"]
    environment["PYTHONPATH"] = (
        str(source_dir) + os.pathsep + environment.get("PYTHONPATH", "")
    )

    return subprocess.run(
        [sys.executable, "-c", textwrap.dedent(code)],
        capture_output=True,
        check=False,
        env=environment,
        text=True,
        timeout=30,
    )


def _raw_abi_response(completed):
    assert completed.returncode == 0, completed.stderr
    assert "Fatal error" not in completed.stderr
    return json.loads(completed.stdout)


@pytest.mark.integration
def test_context_manager_runs_multiple_scripts():
    with dataweave.DataWeave() as dw:
        assert dw.run("sqrt(144)").get_string() == "12"
        assert dw.run("sqrt(10000)").get_string() == "100"


@pytest.mark.unit
def test_context_exit_preserves_body_exception_when_cleanup_fails(monkeypatch):
    runtime = dataweave.DataWeave.__new__(dataweave.DataWeave)
    monkeypatch.setattr(runtime, "initialize", lambda: None)
    monkeypatch.setattr(runtime, "cleanup", lambda: (_ for _ in ()).throw(dataweave.DataWeaveError("cleanup failed")))

    with pytest.raises(ValueError, match="body failed"):
        with runtime:
            raise ValueError("body failed")


@pytest.mark.unit
def test_context_exit_surfaces_cleanup_failure_without_body_exception(monkeypatch):
    runtime = dataweave.DataWeave.__new__(dataweave.DataWeave)
    monkeypatch.setattr(runtime, "cleanup", lambda: (_ for _ in ()).throw(dataweave.DataWeaveError("cleanup failed")))

    with pytest.raises(dataweave.DataWeaveError, match="cleanup failed"):
        runtime.__exit__(None, None, None)


@pytest.mark.integration
def test_raw_abi_contains_null_arguments_without_terminating_process():
    completed = _run_raw_abi_child(
        """
        import ctypes
        import json
        import os

        from dataweave.models import RESOLVE_MODULE_CALLBACK
        from dataweave.native import (
            GraalIsolatePointer,
            GraalIsolateThreadPointer,
            _bind_abi,
        )

        lib = ctypes.CDLL(os.environ["DATAWEAVE_NATIVE_LIB"])
        _bind_abi(lib)
        isolate = GraalIsolatePointer()
        bootstrap = GraalIsolateThreadPointer()
        assert lib.graal_create_isolate(
            None, ctypes.byref(isolate), ctypes.byref(bootstrap)
        ) == 0
        assert lib.graal_detach_thread(bootstrap) == 0

        thread = GraalIsolateThreadPointer()
        attached = False
        null_resolver_handle = 0
        healthy_handle = 0
        null_script_result = None
        try:
            assert lib.graal_attach_thread(isolate, ctypes.byref(thread)) == 0
            attached = True
            null_resolver = ctypes.cast(None, RESOLVE_MODULE_CALLBACK)
            null_resolver_handle = lib.create_engine_with_resolver(
                thread, null_resolver, None
            )
            assert null_resolver_handle == 0
            healthy_handle = lib.create_engine(thread)
            assert healthy_handle > 0
            result_pointer = lib.run_script_engine(
                thread, healthy_handle, None, None
            )
            if result_pointer:
                try:
                    null_script_result = json.loads(
                        ctypes.string_at(result_pointer).decode("utf-8")
                    )
                finally:
                    lib.free_cstring(thread, result_pointer)
        finally:
            if attached:
                if healthy_handle > 0:
                    lib.destroy_engine(thread, healthy_handle)
                if null_resolver_handle > 0:
                    lib.destroy_engine(thread, null_resolver_handle)
                assert lib.graal_detach_thread(thread) == 0

        teardown_thread = GraalIsolateThreadPointer()
        assert lib.graal_attach_thread(
            isolate, ctypes.byref(teardown_thread)
        ) == 0
        assert lib.graal_tear_down_isolate(teardown_thread) == 0
        print(json.dumps({
            "null_resolver_handle": null_resolver_handle,
            "healthy_handle": healthy_handle,
            "null_script_result": null_script_result,
        }))
        """
    )

    response = _raw_abi_response(completed)
    assert response["null_resolver_handle"] == 0
    assert response["healthy_handle"] > 0
    assert response["null_script_result"] == {
        "success": False,
        "error": "Script cannot be null",
    }


@pytest.mark.integration
def test_raw_abi_destroy_waits_for_resolver_context_to_drain():
    completed = _run_raw_abi_child(
        """
        import ctypes
        import json
        import os
        from threading import Event, Thread
        import time

        from dataweave.models import RESOLVE_MODULE_CALLBACK
        from dataweave.native import (
            GraalIsolatePointer,
            GraalIsolateThreadPointer,
            _bind_abi,
        )

        lib = ctypes.CDLL(os.environ["DATAWEAVE_NATIVE_LIB"])
        _bind_abi(lib)
        isolate = GraalIsolatePointer()
        bootstrap = GraalIsolateThreadPointer()
        assert lib.graal_create_isolate(
            None, ctypes.byref(isolate), ctypes.byref(bootstrap)
        ) == 0
        assert lib.graal_detach_thread(bootstrap) == 0

        resolver_entered = Event()
        release_resolver = Event()
        destroy_ready = Event()
        admission_closed = Event()
        run_returned = Event()
        destroy_returned = Event()
        errors = []
        run_result = None
        probe_result = None
        context_matched = False
        module_source = ctypes.create_string_buffer(
            b"%dw 2.0\\nfun answer() = 42"
        )
        module_source_address = ctypes.addressof(module_source)
        resolver_context = ctypes.c_int(157)
        resolver_context_address = ctypes.addressof(resolver_context)

        @RESOLVE_MODULE_CALLBACK
        def resolver(_thread, ctx, _path):
            global context_matched
            context_matched = ctx == resolver_context_address
            resolver_entered.set()
            if not release_resolver.wait(5):
                return 0
            return module_source_address

        creator_thread = GraalIsolateThreadPointer()
        assert lib.graal_attach_thread(
            isolate, ctypes.byref(creator_thread)
        ) == 0
        handle = lib.create_engine_with_resolver(
            creator_thread,
            resolver,
            ctypes.cast(ctypes.pointer(resolver_context), ctypes.c_void_p),
        )
        assert handle > 0
        assert lib.graal_detach_thread(creator_thread) == 0

        def run_script():
            global run_result
            thread = GraalIsolateThreadPointer()
            attached = False
            try:
                assert lib.graal_attach_thread(
                    isolate, ctypes.byref(thread)
                ) == 0
                attached = True
                result_pointer = lib.run_script_engine(
                    thread,
                    handle,
                    b"%dw 2.0\\n"
                    b"import org::test::lib\\n"
                    b"output application/json\\n"
                    b"---\\n"
                    b"lib::answer()",
                    None,
                )
                run_returned.set()
                assert result_pointer
                try:
                    run_result = json.loads(
                        ctypes.string_at(result_pointer).decode("utf-8")
                    )
                finally:
                    lib.free_cstring(thread, result_pointer)
            except BaseException as error:
                errors.append("run: " + repr(error))
            finally:
                if attached and lib.graal_detach_thread(thread) != 0:
                    errors.append("run: failed to detach")

        def destroy_engine():
            thread = GraalIsolateThreadPointer()
            attached = False
            try:
                assert lib.graal_attach_thread(
                    isolate, ctypes.byref(thread)
                ) == 0
                attached = True
                destroy_ready.set()
                lib.destroy_engine(thread, handle)
                destroy_returned.set()
            except BaseException as error:
                errors.append("destroy: " + repr(error))
            finally:
                if attached and lib.graal_detach_thread(thread) != 0:
                    errors.append("destroy: failed to detach")

        def probe_admission():
            global probe_result
            thread = GraalIsolateThreadPointer()
            attached = False
            try:
                assert lib.graal_attach_thread(
                    isolate, ctypes.byref(thread)
                ) == 0
                attached = True
                assert destroy_ready.wait(5)
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline:
                    result_pointer = lib.run_script_engine(
                        thread, handle, b"1", None
                    )
                    assert result_pointer
                    try:
                        result = json.loads(
                            ctypes.string_at(result_pointer).decode("utf-8")
                        )
                    finally:
                        lib.free_cstring(thread, result_pointer)
                    if result == {
                        "success": False,
                        "error": "Unknown engine handle",
                    }:
                        probe_result = result
                        admission_closed.set()
                        return
                    time.sleep(0.01)
                errors.append("probe: admission did not close")
            except BaseException as error:
                errors.append("probe: " + repr(error))
            finally:
                if attached and lib.graal_detach_thread(thread) != 0:
                    errors.append("probe: failed to detach")

        run_thread = Thread(target=run_script, daemon=True)
        destroy_thread = Thread(target=destroy_engine, daemon=True)
        probe_thread = Thread(target=probe_admission, daemon=True)
        run_thread.start()
        try:
            assert resolver_entered.wait(5)
            destroy_thread.start()
            probe_thread.start()
            admission_closed_while_resolver_blocked = (
                admission_closed.wait(5) and not release_resolver.is_set()
            )
            destroy_blocked_with_admission_closed = (
                admission_closed_while_resolver_blocked
                and not destroy_returned.wait(0.1)
            )
        finally:
            release_resolver.set()

        run_thread.join(5)
        destroy_thread.join(5)
        probe_thread.join(5)
        assert not run_thread.is_alive()
        assert not destroy_thread.is_alive()
        assert not probe_thread.is_alive()
        assert run_returned.is_set()
        assert destroy_returned.is_set()

        teardown_thread = GraalIsolateThreadPointer()
        assert lib.graal_attach_thread(
            isolate, ctypes.byref(teardown_thread)
        ) == 0
        assert lib.graal_tear_down_isolate(teardown_thread) == 0
        print(json.dumps({
            "admission_closed_while_resolver_blocked": (
                admission_closed_while_resolver_blocked
            ),
            "destroy_blocked_with_admission_closed": (
                destroy_blocked_with_admission_closed
            ),
            "destroy_returned": destroy_returned.is_set(),
            "context_matched": context_matched,
            "probe_result": probe_result,
            "run_result": run_result,
            "errors": errors,
        }))
        """
    )

    response = _raw_abi_response(completed)
    assert response["admission_closed_while_resolver_blocked"] is True
    assert response["destroy_blocked_with_admission_closed"] is True
    assert response["destroy_returned"] is True
    assert response["context_matched"] is True
    assert response["probe_result"] == {
        "success": False,
        "error": "Unknown engine handle",
    }
    assert response["run_result"]["success"] is True
    assert response["errors"] == []
