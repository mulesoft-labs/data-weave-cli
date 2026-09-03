import json
import os
from pathlib import Path
import subprocess
import sys
import textwrap

import pytest

import dataweave


def _run_raw_abi_child(code, extra_environment=None):
    source_dir = Path(__file__).resolve().parents[2] / "src"
    environment = os.environ.copy()
    environment["DATAWEAVE_NATIVE_LIB"] = os.environ["DATAWEAVE_NATIVE_LIB"]
    environment["PYTHONPATH"] = (
        str(source_dir) + os.pathsep + environment.get("PYTHONPATH", "")
    )
    environment.update(extra_environment or {})

    return subprocess.run(
        [sys.executable, "-c", textwrap.dedent(code)],
        capture_output=True,
        check=False,
        env=environment,
        text=True,
        timeout=30,
    )


def _build_destroy_observer(directory):
    source = directory / "destroy_observer.c"
    source.write_text(
        textwrap.dedent(
            """
            #ifdef _WIN32
            #include <windows.h>
            #define EXPORT __declspec(dllexport)
            static volatile LONG destroy_returned;
            static volatile LONG resolver_released;
            #define STORE_VALUE(value, new_value) \\
                InterlockedExchange(&(value), (new_value))
            #define LOAD_VALUE(value) InterlockedCompareExchange(&(value), 0, 0)
            #else
            #define EXPORT __attribute__((visibility("default")))
            static int destroy_returned;
            static int resolver_released;
            #define STORE_VALUE(value, new_value) \\
                __atomic_store_n(&(value), (new_value), __ATOMIC_RELEASE)
            #define LOAD_VALUE(value) __atomic_load_n(&(value), __ATOMIC_ACQUIRE)
            #endif

            typedef void (*destroy_engine_fn)(void *, long long);

            EXPORT void reset_destroy_returned(void) {
                STORE_VALUE(destroy_returned, 0);
                STORE_VALUE(resolver_released, 0);
            }

            EXPORT int has_destroy_returned(void) {
                return LOAD_VALUE(destroy_returned);
            }

            EXPORT void mark_resolver_released(void) {
                STORE_VALUE(resolver_released, 1);
            }

            EXPORT int observe_destroy_return(
                    destroy_engine_fn destroy_engine, void *thread, long long handle) {
                int released_at_return;
                destroy_engine(thread, handle);
                released_at_return = LOAD_VALUE(resolver_released);
                STORE_VALUE(destroy_returned, 1);
                return released_at_return;
            }
            """
        ),
        encoding="ascii",
    )

    if os.name == "nt":
        library = directory / "destroy_observer.dll"
        command = [
            os.environ.get("CC", "cl"),
            "/nologo",
            "/LD",
            "/O2",
            str(source),
            f"/Fe:{library}",
        ]
    elif sys.platform == "darwin":
        library = directory / "libdestroy_observer.dylib"
        command = [
            os.environ.get("CC", "cc"),
            "-dynamiclib",
            "-O2",
            str(source),
            "-o",
            str(library),
        ]
    else:
        library = directory / "libdestroy_observer.so"
        command = [
            os.environ.get("CC", "cc"),
            "-shared",
            "-fPIC",
            "-O2",
            str(source),
            "-o",
            str(library),
        ]

    completed = subprocess.run(
        command,
        capture_output=True,
        check=False,
        cwd=directory,
        text=True,
        timeout=30,
    )
    assert completed.returncode == 0, completed.stdout + completed.stderr
    return library


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
def test_raw_abi_destroy_waits_for_resolver_context_to_drain(tmp_path):
    destroy_observer = _build_destroy_observer(tmp_path)
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
        observer = ctypes.CDLL(os.environ["DATAWEAVE_DESTROY_OBSERVER"])
        observer.reset_destroy_returned.argtypes = []
        observer.reset_destroy_returned.restype = None
        observer.has_destroy_returned.argtypes = []
        observer.has_destroy_returned.restype = ctypes.c_int
        observer.mark_resolver_released.argtypes = []
        observer.mark_resolver_released.restype = None
        observer.observe_destroy_return.argtypes = [
            ctypes.c_void_p,
            GraalIsolateThreadPointer,
            ctypes.c_int64,
        ]
        observer.observe_destroy_return.restype = ctypes.c_int
        isolate = GraalIsolatePointer()
        bootstrap = GraalIsolateThreadPointer()
        assert lib.graal_create_isolate(
            None, ctypes.byref(isolate), ctypes.byref(bootstrap)
        ) == 0
        assert lib.graal_detach_thread(bootstrap) == 0

        resolver_entered = Event()
        release_resolver = Event()
        probe_ready = Event()
        start_probe = Event()
        run_returned = Event()
        errors = []
        run_result = None
        probe_result = None
        resolver_released_at_destroy_return = None
        destroy_returned_before_release = None
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

        def probe_admission():
            global destroy_returned_before_release, probe_result
            thread = GraalIsolateThreadPointer()
            attached = False
            try:
                assert lib.graal_attach_thread(
                    isolate, ctypes.byref(thread)
                ) == 0
                attached = True
                probe_ready.set()
                assert start_probe.wait(5)
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
                        return_deadline = time.monotonic() + 1
                        while time.monotonic() < return_deadline:
                            if observer.has_destroy_returned():
                                destroy_returned_before_release = True
                                break
                            time.sleep(0.001)
                        else:
                            destroy_returned_before_release = False
                        observer.mark_resolver_released()
                        release_resolver.set()
                        return
                    time.sleep(0.01)
                errors.append("probe: admission did not close")
            except BaseException as error:
                errors.append("probe: " + repr(error))
            finally:
                release_resolver.set()
                if attached and lib.graal_detach_thread(thread) != 0:
                    errors.append("probe: failed to detach")

        run_thread = Thread(target=run_script, daemon=True)
        probe_thread = Thread(target=probe_admission, daemon=True)
        run_thread.start()
        controller_thread = GraalIsolateThreadPointer()
        controller_attached = False
        try:
            assert resolver_entered.wait(5)
            probe_thread.start()
            assert probe_ready.wait(5)
            assert lib.graal_attach_thread(
                isolate, ctypes.byref(controller_thread)
            ) == 0
            controller_attached = True
            observer.reset_destroy_returned()
            start_probe.set()
            resolver_released_at_destroy_return = bool(
                observer.observe_destroy_return(
                    ctypes.cast(lib.destroy_engine, ctypes.c_void_p),
                    controller_thread,
                    handle,
                )
            )
        finally:
            release_resolver.set()
            if (
                controller_attached
                and lib.graal_detach_thread(controller_thread) != 0
            ):
                errors.append("controller: failed to detach")

        run_thread.join(5)
        probe_thread.join(5)
        assert not run_thread.is_alive()
        assert not probe_thread.is_alive()
        assert run_returned.is_set()

        teardown_thread = GraalIsolateThreadPointer()
        assert lib.graal_attach_thread(
            isolate, ctypes.byref(teardown_thread)
        ) == 0
        assert lib.graal_tear_down_isolate(teardown_thread) == 0
        print(json.dumps({
            "context_matched": context_matched,
            "destroy_returned_before_release": destroy_returned_before_release,
            "native_destroy_return_observed": bool(
                observer.has_destroy_returned()
            ),
            "resolver_released_at_destroy_return": (
                resolver_released_at_destroy_return
            ),
            "probe_result": probe_result,
            "run_result": run_result,
            "errors": errors,
        }))
        """,
        {"DATAWEAVE_DESTROY_OBSERVER": str(destroy_observer)},
    )

    response = _raw_abi_response(completed)
    assert response["context_matched"] is True
    assert response["destroy_returned_before_release"] is False
    assert response["native_destroy_return_observed"] is True
    assert response["resolver_released_at_destroy_return"] is True
    assert response["probe_result"] == {
        "success": False,
        "error": "Unknown engine handle",
    }
    assert response["run_result"]["success"] is True
    assert response["errors"] == []
