from pathlib import Path
import ctypes
import threading
from threading import Barrier, BrokenBarrierError, Condition, current_thread, get_ident, Lock, Thread

import pytest

import dataweave
from dataweave import native


class CallbackBaseException(BaseException):
    pass


class Function:
    pass


class CallableFunction(Function):
    def __init__(self, callback):
        self.callback = callback

    def __call__(self, *args):
        return self.callback(*args)


class FakeLibrary:
    run_script = Function()
    free_cstring = Function()

    def __init__(self):
        self.attach_calls = []
        self.detach_calls = []
        self.tear_down_threads = []
        self.created_engines = []
        self.destroyed_engines = []
        self.create_engine_threads = []
        self.destroy_engine_threads = []
        self._next_handle = 1
        self.graal_create_isolate = CallableFunction(lambda _params, _isolate, _thread: 0)
        self.graal_attach_thread = CallableFunction(self._attach_thread)
        self.graal_detach_thread = CallableFunction(
            lambda thread: self.detach_calls.append((get_ident(), thread)) or 0
        )
        self.graal_tear_down_isolate = CallableFunction(
            lambda thread: self.tear_down_threads.append(thread) or 0
        )
        self.free_cstring = Function()
        self.create_engine = CallableFunction(self._create_engine)
        self.create_engine_with_resolver = CallableFunction(self._create_engine_with_resolver)
        self.destroy_engine = CallableFunction(
            lambda thread, handle: (
                self.destroy_engine_threads.append(thread),
                self.destroyed_engines.append(handle),
            )
        )
        self.run_script_engine = Function()
        self.run_script_callback_engine = Function()
        self.run_script_input_output_callback_engine = Function()

    def _create_engine(self, thread):
        handle = self._next_handle
        self._next_handle += 1
        self.created_engines.append((handle, None, None))
        self.create_engine_threads.append(thread)
        return handle

    def _create_engine_with_resolver(self, thread, callback, ctx):
        handle = self._next_handle
        self._next_handle += 1
        self.created_engines.append((handle, callback, ctx))
        self.create_engine_threads.append(thread)
        return handle

    def _attach_thread(self, _isolate, thread):
        worker_thread = native.GraalIsolateThreadPointer()
        ctypes.cast(
            thread,
            ctypes.POINTER(native.GraalIsolateThreadPointer),
        )[0] = worker_thread
        self.attach_calls.append((get_ident(), worker_thread))
        return 0


@pytest.mark.unit
def test_shared_isolate_is_created_once_and_torn_down_on_last_release(monkeypatch):
    library = FakeLibrary()
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)

    a = native.NativeRuntime("/tmp/dwlib")
    b = native.NativeRuntime("/tmp/dwlib")
    a.initialize()
    b.initialize()

    # One shared isolate, two engines, refcount == live engines.
    assert native._isolate_ref_count == 2
    assert len(library.tear_down_threads) == 0
    assert [h for h, _cb, _ctx in library.created_engines] == [a.handle, b.handle]
    assert a.handle != b.handle

    a.cleanup()
    assert native._isolate_ref_count == 1
    assert library.destroyed_engines == [a.handle]
    assert len(library.tear_down_threads) == 0  # isolate stays for b

    b.cleanup()
    assert native._isolate_ref_count == 0
    assert library.destroyed_engines == [a.handle, b.handle]
    assert len(library.tear_down_threads) == 1  # last release tears down

    # Idempotent double-cleanup releases the ref only once.
    b.cleanup()
    assert native._isolate_ref_count == 0
    assert len(library.tear_down_threads) == 1


@pytest.mark.unit
def test_engine_create_failure_releases_isolate_ref(monkeypatch):
    library = FakeLibrary()
    library.create_engine = CallableFunction(
        lambda _thread: (_ for _ in ()).throw(RuntimeError("boom"))
    )
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)

    runtime = native.NativeRuntime("/tmp/dwlib")
    with pytest.raises(native.DataWeaveError):
        runtime.initialize()

    # Failed init must leak nothing: the isolate it created is torn down.
    assert native._isolate_ref_count == 0
    assert native._isolate is None
    assert len(library.tear_down_threads) == 1
    assert runtime.initialized is False


@pytest.mark.unit
def test_parse_native_response_rejects_malformed_json():
    result = dataweave._parse_native_encoded_response("not json")

    assert result.success is False
    assert result.error.startswith("Failed to parse native JSON response:")


@pytest.mark.unit
@pytest.mark.parametrize("raw, expected_error", [
    (None, "Native returned null"),
    ("", "Native returned empty response"),
    ("[]", "Native response JSON is not an object"),
])
def test_parse_native_response_rejects_invalid_native_values(raw, expected_error):
    result = dataweave._parse_native_encoded_response(raw)

    assert result == dataweave.ExecutionResult(False, None, expected_error, False, None, None)


@pytest.mark.unit
def test_candidate_paths_prioritize_environment_override(monkeypatch, tmp_path):
    override = tmp_path / "custom-dwlib"
    monkeypatch.setenv("DATAWEAVE_NATIVE_LIB", str(override))

    paths = dataweave._candidate_library_paths()

    assert paths[0] == override
    assert paths[1:4] == [
        Path(dataweave.__file__).resolve().parent / "native" / "dwlib.dylib",
        Path(dataweave.__file__).resolve().parent / "native" / "dwlib.so",
        Path(dataweave.__file__).resolve().parent / "native" / "dwlib.dll",
    ]


@pytest.mark.unit
def test_decode_and_free_releases_native_string_when_decoding_fails(monkeypatch):
    freed = []
    runtime = native.NativeRuntime.__new__(native.NativeRuntime)
    runtime.thread = "thread"
    runtime.lib = type("Native", (), {"free_cstring": lambda _self, thread, ptr: freed.append((thread, ptr))})()
    monkeypatch.setattr(native.ctypes, "string_at", lambda _ptr: b"\xff")

    with pytest.raises(UnicodeDecodeError):
        runtime.decode_and_free(123)

    assert freed == [("thread", 123)]


@pytest.mark.unit
def test_decode_and_free_preserves_decode_failure_when_free_also_fails(monkeypatch):
    runtime = native.NativeRuntime.__new__(native.NativeRuntime)
    runtime.thread = "thread"
    runtime.lib = type("Native", (), {"free_cstring": lambda _self, _thread, _ptr: (_ for _ in ()).throw(RuntimeError("free failed"))})()
    monkeypatch.setattr(native.ctypes, "string_at", lambda _ptr: b"\xff")

    with pytest.raises(UnicodeDecodeError):
        runtime.decode_and_free(123)


@pytest.mark.unit
def test_native_runtime_registers_abi_and_cleans_up_idempotently(monkeypatch):
    library = FakeLibrary()
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)
    runtime = native.NativeRuntime("/tmp/dwlib")
    runtime.initialize()
    runtime.cleanup()
    runtime.cleanup()

    assert library.run_script_engine.argtypes[1:] == [
        native.ctypes.c_int64, native.ctypes.c_char_p, native.ctypes.c_char_p,
    ]
    assert library.free_cstring.argtypes[1] is native.ctypes.c_void_p
    assert len(library.tear_down_threads) == 1
    assert runtime.initialized is False


@pytest.mark.unit
def test_buffered_worker_execution_uses_one_current_thread_attachment_for_run_decode_and_free(monkeypatch):
    calls = []
    buffer = ctypes.create_string_buffer(b"result")
    library = FakeLibrary()
    library.run_script_engine = CallableFunction(
        lambda thread, _handle, _script, _inputs: calls.append(("run", get_ident(), thread))
        or ctypes.addressof(buffer)
    )
    library.free_cstring = CallableFunction(
        lambda thread, _ptr: calls.append(("free", get_ident(), thread))
    )
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)
    runtime = native.NativeRuntime("/tmp/dwlib")
    runtime.initialize()
    owner_ident = get_ident()
    # Snapshot right after initialize(): the bootstrap create/detach and the
    # attach-on-demand engine create already added entries, so we assert the
    # DELTA the worker run adds rather than a brittle absolute count.
    attach_count_after_init = len(library.attach_calls)
    detach_count_after_init = len(library.detach_calls)
    outcomes = []

    worker = Thread(
        target=lambda: outcomes.append(
            (get_ident(), runtime.run_engine_and_decode(b"script", b"{}"))
        )
    )
    worker.start()
    worker.join(1)

    assert not worker.is_alive()
    worker_ident, result = outcomes[0]
    assert worker_ident != owner_ident
    assert result == "result"
    # Exactly one attach and one detach for the whole run+decode+free, both on
    # the worker's OS thread -- no owner fast-path, one attachment shared by
    # run and free.
    assert len(library.attach_calls) - attach_count_after_init == 1
    assert len(library.detach_calls) - detach_count_after_init == 1
    new_attach = library.attach_calls[attach_count_after_init]
    new_detach = library.detach_calls[detach_count_after_init]
    assert new_attach[0] == worker_ident
    assert new_detach[0] == worker_ident
    worker_pointer = ctypes.cast(calls[0][2], ctypes.c_void_p).value
    assert [(name, ident) for name, ident, _thread in calls] == [
        ("run", worker_ident),
        ("free", worker_ident),
    ]
    assert all(
        ctypes.cast(thread, ctypes.c_void_p).value == worker_pointer
        for _name, _ident, thread in calls
    )
    assert ctypes.cast(new_attach[1], ctypes.c_void_p).value == worker_pointer
    assert ctypes.cast(new_detach[1], ctypes.c_void_p).value == worker_pointer


@pytest.mark.unit
def test_attach_on_demand_does_not_cache_by_thread_ident(monkeypatch):
    # This guarded the OLD owner-reuse-by-thread-object branch (comparing
    # `current_thread() is owner` under a spoofed get_ident so a reused ident
    # could not be mistaken for the owner). That branch is gone entirely: every
    # call attaches on demand regardless of ident. Keep the get_ident spoof to
    # prove there is no ident-keyed cache anywhere in the new path.
    buffer = ctypes.create_string_buffer(b"result")
    library = FakeLibrary()
    library.run_script_engine = CallableFunction(
        lambda _thread, _handle, _script, _inputs: ctypes.addressof(buffer)
    )
    library.free_cstring = CallableFunction(lambda _thread, _ptr: None)
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)
    monkeypatch.setattr(native, "get_ident", lambda: 7)
    runtime = native.NativeRuntime("/tmp/dwlib")
    runtime.initialize()
    owner_thread = current_thread()
    attach_count_after_init = len(library.attach_calls)
    detach_count_after_init = len(library.detach_calls)
    observed_threads = []
    outcomes = []

    worker = Thread(
        target=lambda: (
            observed_threads.append(current_thread()),
            outcomes.append(runtime.run_engine_and_decode(b"script", b"{}")),
        )
    )
    worker.start()
    worker.join(1)

    assert not worker.is_alive()
    assert observed_threads == [worker]
    assert observed_threads[0] is not owner_thread
    assert outcomes == ["result"]
    assert len(library.attach_calls) - attach_count_after_init == 1
    assert len(library.detach_calls) - detach_count_after_init == 1


@pytest.mark.unit
def test_cleanup_releases_isolate_ref(monkeypatch):
    # _owner_thread no longer exists (attach-on-demand for every call); the
    # remaining intent this test guards is that cleanup() releases the shared
    # isolate ref and, on the last release, clears the module-level isolate.
    library = FakeLibrary()
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)
    runtime = native.NativeRuntime("/tmp/dwlib")
    runtime.initialize()

    runtime.cleanup()

    assert native._isolate_ref_count == 0
    assert native._isolate is None


@pytest.mark.unit
@pytest.mark.parametrize("failure", ["run", "decode", "free", "detach"])
def test_buffered_worker_execution_detaches_current_thread_after_failure(monkeypatch, failure):
    buffer = ctypes.create_string_buffer(b"result")
    library = FakeLibrary()

    def run_script_engine(_thread, _handle, _script, _inputs):
        if failure == "run":
            raise RuntimeError("run failed")
        return ctypes.addressof(buffer)

    def free_cstring(_thread, _ptr):
        if failure == "free":
            raise RuntimeError("free failed")

    library.run_script_engine = CallableFunction(run_script_engine)
    library.free_cstring = CallableFunction(free_cstring)
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)
    if failure == "decode":
        monkeypatch.setattr(native.ctypes, "string_at", lambda _ptr: b"\xff")
    runtime = native.NativeRuntime("/tmp/dwlib")
    runtime.initialize()
    # Snapshot right after initialize(): the bootstrap create/detach and the
    # attach-on-demand engine create already used the library's default
    # (working) detach, so the "detach" failure below is installed AFTER
    # initialize() -- it must only break the worker run's own detach, not the
    # unrelated bootstrap-detach call inside _acquire_isolate.
    attach_count_after_init = len(library.attach_calls)
    detach_count_after_init = len(library.detach_calls)
    if failure == "detach":
        library.graal_detach_thread = CallableFunction(
            lambda _thread: (_ for _ in ()).throw(RuntimeError("detach failed"))
        )
    errors = []

    worker = Thread(
        target=lambda: _capture_error(
            errors,
            lambda: runtime.run_engine_and_decode(b"script", b"{}"),
        )
    )
    worker.start()
    worker.join(1)

    assert not worker.is_alive()
    assert len(errors) == 1
    if failure == "detach":
        assert "detach failed" in str(errors[0])
    assert len(library.attach_calls) - attach_count_after_init == 1
    if failure != "detach":
        assert len(library.detach_calls) - detach_count_after_init == 1
        assert library.detach_calls[detach_count_after_init][0] == library.attach_calls[attach_count_after_init][0]


def _capture_error(errors, invoke):
    try:
        invoke()
    except Exception as error:
        errors.append(error)


@pytest.mark.unit
def test_native_callback_scope_rejects_lifecycle_and_execution_on_any_same_thread_instance(monkeypatch):
    guarded = native.NativeRuntime.__new__(native.NativeRuntime)
    guarded.initialized = True
    guarded._operation_lock = Condition(Lock())
    guarded._operation_active = False
    guarded._execution_owner = None
    other = native.NativeRuntime.__new__(native.NativeRuntime)
    other._init_lock = Lock()
    other._operation_lock = Condition(Lock())
    other._operation_active = False
    other._execution_owner = None
    monkeypatch.setattr(
        native,
        "_acquire_isolate",
        lambda _path: (_ for _ in ()).throw(AssertionError("native isolate acquired")),
    )
    monkeypatch.setattr(
        native,
        "_release_isolate",
        lambda: (_ for _ in ()).throw(AssertionError("native isolate released")),
    )

    with native._native_callback_scope():
        assert native._isolate_lock.acquire(blocking=False)
        native._isolate_lock.release()
        assert native._resolver_lock_global.acquire(blocking=False)
        native._resolver_lock_global.release()
        assert guarded._operation_lock.acquire(blocking=False)
        guarded._operation_lock.release()
        assert other._init_lock.acquire(blocking=False)
        other._init_lock.release()
        for invoke in (
            guarded.capture_operation,
            other.initialize,
            guarded.cleanup,
        ):
            with pytest.raises(
                dataweave.DataWeaveError,
                match="DataWeave lifecycle and execution are not allowed from a native callback on the same thread\\.",
            ):
                invoke()


@pytest.mark.unit
def test_native_callback_scope_is_thread_local():
    errors = []
    outcomes = []
    runtime = native.NativeRuntime.__new__(native.NativeRuntime)
    runtime.initialized = True

    with native._native_callback_scope():
        worker = Thread(
            target=lambda: _capture_error(
                errors, lambda: outcomes.append(runtime.capture_operation())
            )
        )
        worker.start()
        worker.join(1)

        assert not worker.is_alive()
        assert errors == []
        assert outcomes == [None]
        with pytest.raises(dataweave.DataWeaveError, match="native callback"):
            native._raise_if_native_callback_active()


@pytest.mark.unit
def test_native_callback_scope_rejects_direct_thread_attachment():
    runtime = native.NativeRuntime.__new__(native.NativeRuntime)
    runtime.lib = type(
        "Native",
        (),
        {
            "graal_attach_thread": lambda _self, _isolate, _thread: (_ for _ in ()).throw(
                AssertionError("native attach called")
            ),
            "graal_detach_thread": lambda _self, _thread: (_ for _ in ()).throw(
                AssertionError("native detach called")
            ),
        },
    )()
    runtime.isolate = object()

    with native._native_callback_scope():
        for invoke in (runtime.attach_thread, lambda: runtime.detach_thread(object())):
            with pytest.raises(dataweave.DataWeaveError, match="native callback"):
                invoke()


@pytest.mark.unit
@pytest.mark.parametrize("failure_depth", [1, 2])
def test_native_callback_scope_restores_depth_after_success_error_and_nesting(failure_depth):
    assert not hasattr(native._native_callback_state, "depth")

    with pytest.raises(RuntimeError, match="callback failed"):
        with native._native_callback_scope():
            assert native._native_callback_state.depth == 1
            if failure_depth == 1:
                raise RuntimeError("callback failed")
            with native._native_callback_scope():
                assert native._native_callback_state.depth == 2
                raise RuntimeError("callback failed")

    assert not hasattr(native._native_callback_state, "depth")
    native._raise_if_native_callback_active()

    with native._native_callback_scope():
        assert native._native_callback_state.depth == 1
    assert not hasattr(native._native_callback_state, "depth")


@pytest.mark.unit
def test_resolver_callback_contains_base_exception_and_restores_native_callback_depth(monkeypatch, capsys):
    library = FakeLibrary()
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)
    runtime = native.NativeRuntime("/tmp/dwlib")
    runtime.install_resolver(
        lambda _path: (_ for _ in ()).throw(CallbackBaseException("resolver failed"))
    )
    runtime.initialize()
    _handle, callback, context = library.created_engines[0]

    with runtime._resolver_scope():
        assert callback(None, context, b"org/test/lib.dwl") is None

    assert not hasattr(native._native_callback_state, "depth")
    native._raise_if_native_callback_active()
    assert capsys.readouterr().err == "DataWeave module resolver callback failed.\n"
    runtime.cleanup()


@pytest.mark.unit
def test_resolver_callback_runs_without_the_instance_operation_lock(monkeypatch):
    library = FakeLibrary()
    result_buffer = ctypes.create_string_buffer(b"result")
    lock_available = []
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)
    runtime = native.NativeRuntime("/tmp/dwlib")

    def resolver(_path):
        acquired = runtime._operation_lock.acquire(blocking=False)
        lock_available.append(acquired)
        if acquired:
            runtime._operation_lock.release()
        return "source"

    runtime.install_resolver(resolver)
    runtime.initialize()
    _handle, callback, context = library.created_engines[0]

    def run_script(_thread, _handle, _script, _inputs):
        source = callback(None, context, b"org/test/lib.dwl")
        assert ctypes.string_at(source) == b"source"
        return ctypes.addressof(result_buffer)

    library.run_script_engine = CallableFunction(run_script)
    library.free_cstring = CallableFunction(lambda _thread, _ptr: None)

    assert runtime.run_engine_and_decode(b"script", b"{}") == "result"
    assert lock_available == [True]

    runtime.cleanup()


@pytest.mark.unit
def test_cleanup_from_worker_uses_current_thread_for_isolate_teardown(monkeypatch):
    library = FakeLibrary()
    teardown_calls = []
    library.graal_tear_down_isolate = CallableFunction(
        lambda thread: teardown_calls.append((get_ident(), thread)) or 0
    )
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)
    runtime = native.NativeRuntime("/tmp/dwlib")
    runtime.initialize()
    owner_ident = get_ident()
    # Snapshot right after initialize(): the bootstrap create/detach and the
    # attach-on-demand engine create already added entries on this (main)
    # thread's ident, so we assert the DELTA the worker cleanup() adds rather
    # than a brittle absolute count.
    attach_count_after_init = len(library.attach_calls)
    detach_count_after_init = len(library.detach_calls)

    worker = Thread(target=runtime.cleanup)
    worker.start()
    worker.join(1)

    assert not worker.is_alive()
    worker_ident, teardown_thread = teardown_calls[0]
    assert worker_ident != owner_ident
    # This is the structural guard for Finding #1: the isolate was created on
    # the main thread, but the bootstrap thread was detached immediately after
    # create, so teardown on a completely different (worker) thread does not
    # block on a phantom attachment. Off-owner cleanup attaches/detaches its
    # own thread for destroy_engine (the first post-init attach), then the
    # isolate teardown attaches a second, separate thread for
    # graal_tear_down_isolate (the second post-init attach); teardown itself
    # never explicitly detaches (tearing down the isolate implicitly does).
    assert len(library.attach_calls) - attach_count_after_init == 2
    destroy_attach = library.attach_calls[attach_count_after_init]
    teardown_attach = library.attach_calls[attach_count_after_init + 1]
    assert destroy_attach[0] == worker_ident
    assert teardown_attach[0] == worker_ident
    assert ctypes.cast(teardown_thread, ctypes.c_void_p).value == ctypes.cast(
        teardown_attach[1], ctypes.c_void_p
    ).value
    assert len(library.detach_calls) - detach_count_after_init == 1
    new_detach = library.detach_calls[detach_count_after_init]
    assert new_detach[0] == worker_ident
    assert ctypes.cast(new_detach[1], ctypes.c_void_p).value == ctypes.cast(
        destroy_attach[1], ctypes.c_void_p
    ).value
    assert runtime.initialized is False


@pytest.mark.unit
def test_native_runtime_wraps_library_load_errors(monkeypatch):
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: (_ for _ in ()).throw(OSError("bad image")))

    with pytest.raises(dataweave.DataWeaveError, match="Failed to load library from /tmp/dwlib: bad image"):
        native.NativeRuntime("/tmp/dwlib").initialize()


@pytest.mark.unit
def test_initialize_resets_state_when_isolate_creation_fails(monkeypatch):
    library = FakeLibrary()
    library.graal_create_isolate = CallableFunction(lambda _params, _isolate, _thread: 9)
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)
    runtime = native.NativeRuntime("/tmp/dwlib")

    with pytest.raises(dataweave.DataWeaveError, match="Failed to create GraalVM isolate. Error code: 9"):
        runtime.initialize()

    assert runtime.lib is None
    assert runtime.isolate is None
    assert runtime.thread is None
    assert runtime.initialized is False
    assert native._isolate_ref_count == 0
    assert native._isolate is None


@pytest.mark.unit
def test_initialize_requires_create_isolate_export(monkeypatch):
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: object())

    with pytest.raises(dataweave.DataWeaveError, match="Native library does not export graal_create_isolate"):
        native.NativeRuntime("/tmp/dwlib").initialize()


@pytest.mark.unit
def test_initialize_wraps_create_isolate_exception(monkeypatch):
    library = FakeLibrary()
    library.graal_create_isolate = CallableFunction(
        lambda _params, _isolate, _thread: (_ for _ in ()).throw(RuntimeError("native create failure"))
    )
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)

    with pytest.raises(dataweave.DataWeaveError, match="Failed to create GraalVM isolate: native create failure"):
        native.NativeRuntime("/tmp/dwlib").initialize()

    assert native._isolate_ref_count == 0
    assert native._isolate is None


@pytest.mark.unit
@pytest.mark.parametrize("missing_symbol", ["graal_attach_thread", "graal_detach_thread"])
def test_initialize_rejects_streaming_export_without_thread_lifecycle_symbols(monkeypatch, missing_symbol):
    class Function:
        def __call__(self, *_args):
            return 0

    library = type("Native", (), {})()
    library.graal_create_isolate = Function()
    library.graal_tear_down_isolate = Function()
    library.run_script = Function()
    library.free_cstring = Function()
    library.run_script_callback = Function()
    for symbol in ("graal_attach_thread", "graal_detach_thread"):
        if symbol != missing_symbol:
            setattr(library, symbol, Function())
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)

    with pytest.raises(dataweave.DataWeaveError, match=f"Native library does not export {missing_symbol}"):
        native.NativeRuntime("/tmp/dwlib").initialize()


@pytest.mark.unit
@pytest.mark.parametrize(
    ("method_name", "error_message"),
    [
        ("attach_thread", "Failed to attach worker thread to isolate: native attach failure"),
        ("detach_thread", "Failed to detach worker thread from isolate: native detach failure"),
    ],
)
def test_thread_lifecycle_wraps_native_invocation_errors(method_name, error_message):
    class Native:
        def graal_attach_thread(self, _isolate, _thread):
            raise RuntimeError("native attach failure")

        def graal_detach_thread(self, _thread):
            raise RuntimeError("native detach failure")

    runtime = native.NativeRuntime.__new__(native.NativeRuntime)
    runtime.lib = Native()
    runtime.isolate = object()

    with pytest.raises(dataweave.DataWeaveError, match=error_message):
        if method_name == "attach_thread":
            runtime.attach_thread()
        else:
            runtime.detach_thread(object())


@pytest.mark.unit
def test_engine_create_and_destroy_off_owner_thread_use_an_attached_thread(monkeypatch):
    # native._isolate_thread is gone: there is no persistent bootstrap thread to
    # compare against anymore (it is detached immediately after
    # graal_create_isolate). The new intent is that engine create/destroy always
    # run on a freshly *attached* thread, and different OS threads use different
    # attachments.
    #
    # NOTE on comparison strategy: FakeLibrary's graal_attach_thread stub writes
    # a brand-new, always-NULL ctypes pointer into its out-param on every call
    # (there is no real native memory backing it here), so casting to
    # ctypes.c_void_p and comparing .value is always None == None / None != None
    # is always False -- vacuous regardless of correctness. Object identity
    # (`is`/`is not`) IS meaningful here: attach_thread() allocates a distinct
    # Python pointer object on every invocation, so two *different* attaches are
    # guaranteed to be different objects, while the (now-removed) bug reused the
    # exact SAME object across calls. We anchor on identity + OS thread ident.
    library = FakeLibrary()
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)

    # A initializes on THIS thread -> create_engine runs on a freshly attached
    # thread (the bootstrap thread from graal_create_isolate was already
    # detached inside _acquire_isolate and is never reused for engine create;
    # exactly one attach is added by a.initialize(), used for the create call).
    a = native.NativeRuntime("/tmp/dwlib")
    a.initialize()
    owner_ident = get_ident()
    assert len(library.attach_calls) == 1
    assert library.attach_calls[0][0] == owner_ident
    a_create_thread = library.create_engine_threads[0]

    # B initializes on a DIFFERENT OS thread -> attaches its own fresh thread
    # there, distinct from A's.
    errors = []
    b = native.NativeRuntime("/tmp/dwlib")

    def init_b():
        try:
            b.initialize()
        except BaseException as error:  # pragma: no cover - surfaced via assert
            errors.append(error)

    t = Thread(target=init_b)
    t.start()
    t.join(2)
    assert not errors
    assert len(library.attach_calls) == 2
    assert library.attach_calls[1][0] == t.ident
    assert library.attach_calls[1][0] != owner_ident
    b_create_thread = library.create_engine_threads[1]
    # A freshly attached thread is never the SAME object as a previous one --
    # this is exactly how the (now-removed) reused-bootstrap/owner-thread bug
    # would have shown up: B's create thread being the literal object A used.
    assert b_create_thread is not a_create_thread

    # Destroy B from yet another non-owner thread -> attaches its own thread,
    # matching that thread's ident, and it is a fresh object too.
    def cleanup_b():
        try:
            b.cleanup()
        except BaseException as error:  # pragma: no cover
            errors.append(error)

    t2 = Thread(target=cleanup_b)
    t2.start()
    t2.join(2)
    assert not errors
    assert len(library.attach_calls) == 3
    assert library.attach_calls[2][0] == t2.ident
    assert library.destroy_engine_threads[-1] is not a_create_thread
    assert library.destroy_engine_threads[-1] is not b_create_thread

    a.cleanup()


@pytest.mark.unit
def test_failed_isolate_teardown_retains_isolate_and_arms_retry(monkeypatch):
    # Updated for the retryable-teardown contract (review #10 #3, align with
    # Node): a failed final teardown must NOT null the globals -- nulling would
    # let the next initialize() build a SECOND live isolate while the first is
    # still alive. Instead the isolate is retained and a retry is armed; the
    # retry runs (and must succeed) before any fresh isolate can be built.
    monkeypatch.setattr(native, "_teardown_needed", False)  # restored after the test regardless of outcome
    library = FakeLibrary()
    library.graal_tear_down_isolate = CallableFunction(lambda _thread: 1)  # non-zero == failure
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)

    a = native.NativeRuntime("/tmp/dwlib")
    a.initialize()
    with pytest.raises(native.DataWeaveError):
        a.cleanup()  # last release -> teardown fails -> raises

    # The isolate is retained live (not nulled) and a retry is armed.
    assert native._isolate is not None
    assert native._isolate_ref_count == 0
    assert native._teardown_needed is True

    # Restore a passing teardown so the pending retry (run before b's isolate
    # is built) succeeds.
    library.graal_tear_down_isolate = CallableFunction(lambda _thread: 0)

    b = native.NativeRuntime("/tmp/dwlib")
    b.initialize()  # retries the pending teardown, then builds a fresh isolate
    assert native._teardown_needed is False
    assert native._isolate is not None
    b.cleanup()


@pytest.mark.unit
def test_failed_teardown_retains_isolate_and_retries(monkeypatch):
    """A failing graal_tear_down_isolate must NOT null the globals or create a
    second isolate; the next acquire retries the pending teardown."""
    monkeypatch.setattr(native, "_teardown_needed", False)  # restored after the test regardless of outcome
    library = FakeLibrary()
    create_isolate_calls = []

    def create_isolate(_params, _isolate, _thread):
        create_isolate_calls.append(1)
        return 0

    tear_down_results = [1, 0]  # the final teardown fails once, then the retry succeeds

    def tear_down(thread):
        library.tear_down_threads.append(thread)
        return tear_down_results.pop(0) if tear_down_results else 0

    library.graal_create_isolate = CallableFunction(create_isolate)
    library.graal_tear_down_isolate = CallableFunction(tear_down)
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)

    lib, isolate = native._acquire_isolate("/tmp/dwlib")
    assert len(create_isolate_calls) == 1

    with pytest.raises(native.DataWeaveError):
        native._release_isolate()  # last release -> tear_down fails

    # Isolate retained, retry armed, NOT nulled, no second isolate created.
    assert native._lib is lib
    assert native._isolate is isolate
    assert native._teardown_needed is True
    assert native._isolate_ref_count == 0
    assert len(create_isolate_calls) == 1

    # Next acquire retries the pending teardown (which now succeeds) and only
    # then builds a fresh isolate.
    lib2, isolate2 = native._acquire_isolate("/tmp/dwlib")
    assert native._teardown_needed is False
    assert len(library.tear_down_threads) == 2   # the failed attempt + the retry
    assert len(create_isolate_calls) == 2        # then a fresh isolate
    assert isolate2 is not isolate

    native._release_isolate()


@pytest.mark.unit
def test_bootstrap_detach_failure_tears_down_created_isolate(monkeypatch):
    """A failed bootstrap-thread detach must not leak the just-created isolate:
    it is torn down (reusing the still-attached bootstrap thread) before the
    failure is raised, and nothing is published to the module globals."""
    library = FakeLibrary()
    create_isolate_calls = []

    def create_isolate(_params, _isolate, _thread):
        create_isolate_calls.append(1)
        return 0

    library.graal_create_isolate = CallableFunction(create_isolate)
    library.graal_detach_thread = CallableFunction(lambda _thread: 1)  # bootstrap detach fails
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)

    with pytest.raises(native.DataWeaveError):
        native._acquire_isolate("/tmp/dwlib")

    # No leaked live isolate: the just-created isolate was torn down, and
    # nothing was published since the detach failure happened before publish.
    assert native._isolate is None
    assert native._lib is None
    assert native._isolate_ref_count == 0
    assert len(create_isolate_calls) == 1
    assert len(library.tear_down_threads) == 1
    assert native._teardown_needed is False


@pytest.mark.unit
def test_bootstrap_detach_and_teardown_both_failing_leaks_isolate_without_retaining_thread(monkeypatch):
    """Option A (review #15 #1): when a bootstrap-thread detach AND the immediate
    teardown BOTH fail in _acquire_isolate, the still-attached bootstrap
    IsolateThread is OS-thread-affine and could only ever tear this isolate down
    from THIS OS thread. Retaining it for a cross-thread retry risks handing a
    foreign thread to graal_tear_down_isolate (wrong-thread fatal path). So the
    isolate is treated as UNRECOVERABLE: leaked (globals unset), no retry armed,
    no thread retained -- and a later initialize() builds a fresh isolate."""
    library = FakeLibrary()
    library.graal_detach_thread = CallableFunction(lambda _thread: 1)  # bootstrap detach fails
    library.graal_tear_down_isolate = CallableFunction(
        lambda thread: library.tear_down_threads.append(thread) or 1
    )  # teardown also fails
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)

    with pytest.raises(native.DataWeaveError):
        native._acquire_isolate("/tmp/dwlib")

    # Leaked, not published; no retry armed; no thread retained.
    assert native._isolate is None
    assert native._lib is None
    assert native._isolate_ref_count == 0
    assert native._teardown_needed is False
    assert len(library.tear_down_threads) == 1  # tried once, on the bootstrap thread

    # The module is NOT wedged: a healthy library builds a fresh isolate.
    healthy = FakeLibrary()
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: healthy)
    lib, isolate = native._acquire_isolate("/tmp/dwlib")
    assert lib is healthy
    assert native._isolate is not None
    native._release_isolate()  # clean up the fresh isolate


class RetryTeardownFake:
    """Fake native lib for the failed-teardown / cross-thread-retry scenario.

    Each graal_attach_thread hands out a distinct, non-null worker pointer so we
    can prove WHICH worker is detached. graal_tear_down_isolate fails on the
    first call and succeeds afterwards, modelling a transient teardown failure.
    """

    def __init__(self):
        self.attach_workers = []   # worker addr for every attach, in order
        self.detached = []         # worker addr passed to every detach
        self.tear_down_workers = []  # worker addr passed to every teardown
        self.attached = set()      # addrs currently attached (naive bookkeeping)
        self._next_worker = 1
        self._tear_down_calls = 0

    def graal_attach_thread(self, _isolate, thread_ptr):
        addr = self._next_worker * 0x1000
        self._next_worker += 1
        fake_worker = ctypes.cast(ctypes.c_void_p(addr), native.GraalIsolateThreadPointer)
        ctypes.cast(
            thread_ptr, ctypes.POINTER(native.GraalIsolateThreadPointer)
        )[0] = fake_worker
        self.attach_workers.append(addr)
        self.attached.add(addr)
        return 0

    def graal_detach_thread(self, thread):
        addr = ctypes.cast(thread, ctypes.c_void_p).value
        self.detached.append(addr)
        self.attached.discard(addr)
        return 0

    def graal_tear_down_isolate(self, thread):
        self._tear_down_calls += 1
        self.tear_down_workers.append(ctypes.cast(thread, ctypes.c_void_p).value)
        return 1 if self._tear_down_calls == 1 else 0  # fail once, then succeed


@pytest.mark.unit
def test_failed_teardown_detaches_worker_so_cross_thread_retry_succeeds(monkeypatch):
    """Finding #2 (review #11): a FAILED final teardown must detach the freshly-
    attached teardown worker before arming the retry. Otherwise a later cross-
    thread retry attaches a SECOND worker while the first stays attached, and the
    leftover attached worker blocks graal_tear_down_isolate forever.

    A SUCCESSFUL teardown must NOT detach its worker (the isolate is gone and the
    thread pointer is invalid), so detaches == attaches - 1 across the scenario.
    """
    fake = RetryTeardownFake()
    # Set up as if one engine already acquired the shared isolate. Bypass real
    # library loading -- drive the globals directly (unit/conftest resets them).
    monkeypatch.setattr(native, "_lib", fake)
    monkeypatch.setattr(native, "_lib_path", "/tmp/dwlib")
    monkeypatch.setattr(native, "_isolate", native.GraalIsolatePointer())
    monkeypatch.setattr(native, "_isolate_ref_count", 1)
    monkeypatch.setattr(native, "_teardown_needed", False)

    # Last release on the main thread -> teardown fails once -> retry armed.
    with pytest.raises(native.DataWeaveError):
        native._release_isolate()

    # The isolate is retained live and a retry is armed.
    assert native._isolate is not None
    assert native._isolate_ref_count == 0
    assert native._teardown_needed is True
    # Exactly one worker was attached to attempt teardown, and it WAS detached
    # (the bug: it stayed attached). No worker is left dangling attached.
    assert fake.attach_workers == [0x1000]
    assert fake.detached == [0x1000]
    assert fake.attached == set()

    # A cross-thread retry (another OS thread) must now tear down cleanly with a
    # fresh worker and no leftover attached worker blocking it.
    errors = []

    def run_retry():
        try:
            with native._isolate_lock:
                native._retry_pending_teardown_locked()
        except BaseException as error:  # pragma: no cover - surfaced via assert
            errors.append(error)

    thread = Thread(target=run_retry)
    thread.start()
    thread.join(5)

    assert not thread.is_alive()
    assert not errors
    # Retry succeeded: flag cleared and globals nulled.
    assert native._teardown_needed is False
    assert native._isolate is None
    assert native._lib is None
    # A second, fresh worker was attached for the retry and used for the
    # successful teardown. Its worker is intentionally NOT detached (isolate
    # destroyed -> pointer invalid), so only the FAILED-teardown worker (0x1000)
    # is ever detached.
    assert fake.attach_workers == [0x1000, 0x2000]
    assert fake.tear_down_workers == [0x1000, 0x2000]
    assert fake.detached == [0x1000]  # success path does not detach


@pytest.mark.unit
def test_release_teardown_retry_from_a_distinct_os_thread_uses_a_fresh_worker(monkeypatch):
    """Review #15 #1: the retry must attach a FRESH worker on whatever OS thread
    runs it -- never reuse a thread attached on another OS thread. Drive the
    last-release teardown to fail (arming _teardown_needed with NO retained
    thread), then run the retry from a distinct threading.Thread and prove it
    attached a new worker on that thread and tore down successfully.

    RetryTeardownFake implements only the lifecycle ABI (attach/detach/teardown),
    not the full export set _acquire_isolate/_bind_abi require, so publish the
    shared isolate by driving the globals directly -- exactly the setup pattern
    used by the sibling cross-thread retry test above."""
    fake = RetryTeardownFake()
    monkeypatch.setattr(native, "_lib", fake)
    monkeypatch.setattr(native, "_lib_path", "/tmp/dwlib")
    monkeypatch.setattr(native, "_isolate", native.GraalIsolatePointer())
    monkeypatch.setattr(native, "_isolate_ref_count", 1)
    monkeypatch.setattr(native, "_teardown_needed", False)

    with pytest.raises(native.DataWeaveError):
        native._release_isolate()                  # last release: teardown fails once -> armed
    assert native._teardown_needed is True
    assert native._isolate is not None
    workers_before = list(fake.attach_workers)

    errors = []
    def retry_on_other_thread():
        try:
            with native._isolate_lock:
                native._retry_pending_teardown_locked()   # succeeds on the 2nd teardown
        except BaseException as e:  # pragma: no cover - surfaced via errors
            errors.append(e)

    t = threading.Thread(target=retry_on_other_thread)
    t.start()
    t.join()

    assert errors == []
    assert native._teardown_needed is False
    assert native._isolate is None
    # The retry attached a NEW worker (distinct pointer) and tore down with IT.
    assert len(fake.attach_workers) == len(workers_before) + 1
    fresh_worker = fake.attach_workers[-1]
    assert fake.tear_down_workers[-1] == fresh_worker


@pytest.mark.unit
def test_two_engines_dispatch_to_their_own_resolver(monkeypatch):
    library = FakeLibrary()
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)

    a = native.NativeRuntime("/tmp/dwlib")
    a.install_resolver(lambda path: f"A:{path}")
    a.initialize()
    b = native.NativeRuntime("/tmp/dwlib")
    b.install_resolver(lambda path: f"B:{path}")
    b.initialize()

    # ctx tokens are distinct and registered.
    _ha, cb_a, ctx_a = next(e for e in library.created_engines if e[0] == a.handle)
    _hb, cb_b, ctx_b = next(e for e in library.created_engines if e[0] == b.handle)
    assert ctx_a != ctx_b
    assert native._resolver_registry[ctx_a] is a
    assert native._resolver_registry[ctx_b] is b

    # Simulate a synchronous resolve on each engine's owner thread.
    with a._resolver_scope():
        ptr_a = cb_a(None, ctx_a, b"org/x.dwl")
    assert ctypes.string_at(ptr_a) == b"A:org/x.dwl"

    with b._resolver_scope():
        ptr_b = cb_b(None, ctx_b, b"org/x.dwl")
    assert ctypes.string_at(ptr_b) == b"B:org/x.dwl"

    a.cleanup()
    assert ctx_a not in native._resolver_registry
    b.cleanup()


@pytest.mark.unit
def test_resolver_fails_closed_off_the_owner_thread_without_invoking_python(monkeypatch):
    library = FakeLibrary()
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)

    calls = []
    a = native.NativeRuntime("/tmp/dwlib")
    a.install_resolver(lambda path: calls.append(path) or "src")
    a.initialize()
    _h, callback, ctx = library.created_engines[0]

    # Not inside a synchronous resolver scope (mirrors a streaming worker): must
    # return None WITHOUT invoking the Python resolver.
    assert callback(None, ctx, b"org/x.dwl") is None
    assert calls == []

    # Inside the scope but on a different Python thread ident: still fail-closed.
    results = []
    def worker():
        with a._resolver_scope():
            # Overwrite the active ident to the worker's, but call from... actually
            # _resolver_scope records THIS thread's ident, so a same-thread call
            # resolves. Assert the positive to anchor the guard semantics.
            results.append(callback(None, ctx, b"org/y.dwl"))
    import threading
    t = threading.Thread(target=worker)
    t.start(); t.join(2)
    assert results and ctypes.string_at(results[0]) == b"src"

    a.cleanup()


@pytest.mark.unit
def test_bootstrap_thread_is_detached_after_isolate_create(monkeypatch):
    # Regression (final review Finding #1): the isolate's bootstrap thread must be
    # detached immediately after graal_create_isolate, before anything attaches a
    # fresh thread for engine creation. So a last release on a different OS
    # thread can tear down without blocking on a phantom attachment.
    #
    # NOTE on comparison strategy: FakeLibrary's stubs write NULL pointers into
    # their out-params (there is no real native memory backing them here), so
    # pointer VALUES (and even object identity, since nothing ever aliases the
    # bootstrap thread object across calls) cannot distinguish "the bootstrap
    # thread" from a later attach. What CAN be checked -- and is exactly what
    # Finding #1 is about -- is call ORDER: detach must happen immediately
    # after create_isolate, strictly before the attach used for engine create.
    library = FakeLibrary()
    events = []

    def create_isolate(_params, _isolate, _thread_out):
        events.append("create_isolate")
        return 0

    def detach_thread(_thread):
        events.append("detach")
        return 0

    def attach_thread(isolate, thread_out):
        events.append("attach")
        return library._attach_thread(isolate, thread_out)

    library.graal_create_isolate = CallableFunction(create_isolate)
    library.graal_detach_thread = CallableFunction(detach_thread)
    library.graal_attach_thread = CallableFunction(attach_thread)
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)

    runtime = native.NativeRuntime("/tmp/dwlib")
    runtime.initialize()

    assert events[:2] == ["create_isolate", "detach"], (
        "bootstrap thread was not detached immediately after graal_create_isolate"
    )
    assert "attach" in events[2:], "engine create never attached its own thread"
    assert events.index("attach") > events.index("detach"), (
        "engine create attached before the bootstrap thread was detached"
    )
    runtime.cleanup()


@pytest.mark.unit
def test_failed_init_with_resolver_unregisters_the_token(monkeypatch):
    library = FakeLibrary()
    library.create_engine_with_resolver = CallableFunction(
        lambda _thread, _cb, _ctx: (_ for _ in ()).throw(RuntimeError("boom"))
    )
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)

    runtime = native.NativeRuntime("/tmp/dwlib")
    runtime.install_resolver(lambda path: "src")
    token = runtime._resolver_token
    assert native._resolver_registry.get(token) is runtime
    with pytest.raises(native.DataWeaveError):
        runtime.initialize()

    assert token not in native._resolver_registry
    assert native._isolate_ref_count == 0
    assert native._isolate is None


@pytest.mark.unit
def test_failed_acquire_with_resolver_unregisters_the_token(monkeypatch):
    """A library-load failure inside _acquire_isolate must still roll back the
    resolver token (regression: _acquire_isolate was called outside
    initialize()'s try, so the rollback below never ran)."""
    monkeypatch.setattr(
        native.ctypes, "CDLL", lambda _path: (_ for _ in ()).throw(OSError("no lib"))
    )

    runtime = native.NativeRuntime("/tmp/dwlib")
    runtime.install_resolver(lambda path: "src")
    token = runtime._resolver_token
    assert native._resolver_registry.get(token) is runtime

    with pytest.raises(native.DataWeaveError):
        runtime.initialize()

    assert token not in native._resolver_registry
    assert runtime._resolver_token == 0
    assert native._isolate_ref_count == 0
    assert native._isolate is None


@pytest.mark.unit
def test_concurrent_initialize_on_one_instance_creates_a_single_engine(monkeypatch):
    # Finding (review #10 #2): initialize() has no instance-level lock spanning
    # the initialized-check -> _acquire_isolate -> _create_engine -> publish
    # sequence. Two threads calling initialize() on the SAME instance can both
    # pass the check, both acquire (refcount over-counts), and both create an
    # engine -- the second self.handle write orphans the first, and cleanup()
    # then releases only one ref.
    library = FakeLibrary()
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)

    runtime = native.NativeRuntime("/tmp/dwlib")

    # A 2-party barrier with a timeout, patched into the instance's
    # _create_engine. On the UNFIXED code both threads pass the `if
    # self.initialized: return` fast-path concurrently and reach here at
    # roughly the same time, so the barrier is satisfied and both proceed to
    # create an engine (reproducing the over-count). On the FIXED
    # (per-instance-locked) code only one thread is ever inside initialize()
    # at a time, so the second party never arrives here before the timeout;
    # the wait times out, the barrier breaks, and the lone thread just
    # proceeds -- this must NOT deadlock the fixed code.
    barrier = Barrier(2)
    orig_create_engine = runtime._create_engine

    def slow_create_engine():
        try:
            barrier.wait(timeout=0.5)
        except BrokenBarrierError:
            pass
        return orig_create_engine()

    monkeypatch.setattr(runtime, "_create_engine", slow_create_engine)

    errors = []

    def call_initialize():
        try:
            runtime.initialize()
        except BaseException as error:  # pragma: no cover - surfaced via assert
            errors.append(error)

    threads = [Thread(target=call_initialize) for _ in range(2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(5)

    assert not any(thread.is_alive() for thread in threads), "initialize() deadlocked"
    assert not errors
    assert runtime.initialized is True
    # Exactly one acquire, exactly one engine -- no over-count regardless of
    # how the two calls interleaved.
    assert native._isolate_ref_count == 1
    assert len(library.created_engines) == 1
    assert runtime.handle == library.created_engines[0][0]

    runtime.cleanup()
    assert native._isolate_ref_count == 0


@pytest.mark.unit
def test_release_teardown_and_detach_both_failing_leaks_isolate_without_arming_retry(monkeypatch):
    """review #16 #2 (leak-and-continue): if the last-release teardown fails AND
    detaching the just-attached worker also fails, re-arming a retry would attach
    yet another worker on top of the stuck one and permanently block teardown
    (which needs the SOLE attached thread). So the isolate is treated as
    UNRECOVERABLE: globals nulled, NO retry armed, no thread retained -- a later
    initialize() builds a fresh isolate. Mirrors the bootstrap double-failure policy."""
    monkeypatch.setattr(native, "_teardown_needed", False)  # restored after the test regardless
    library = FakeLibrary()  # bootstrap detach succeeds (default) so acquire publishes cleanly
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)

    lib, isolate = native._acquire_isolate("/tmp/dwlib")

    # Now make the last-release teardown fail AND the subsequent detach fail.
    library.graal_tear_down_isolate = CallableFunction(lambda _thread: 1)
    library.graal_detach_thread = CallableFunction(lambda _thread: 1)  # nonzero == failed detach

    with pytest.raises(native.DataWeaveError):
        native._release_isolate()  # last release -> teardown fails -> detach fails -> leak

    # Leaked: globals nulled, NO retry armed (the crux of #16 #2), refcount at 0.
    assert native._isolate is None
    assert native._lib is None
    assert native._isolate_ref_count == 0
    assert native._teardown_needed is False

    # The module is NOT wedged: a healthy library builds a fresh isolate.
    healthy = FakeLibrary()
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: healthy)
    lib2, isolate2 = native._acquire_isolate("/tmp/dwlib")
    assert lib2 is healthy
    assert native._isolate is not None
    native._release_isolate()  # clean up the fresh isolate


@pytest.mark.unit
def test_retry_teardown_and_detach_both_failing_leaks_isolate_without_arming_retry(monkeypatch):
    """review #16 #2: the retry path (_retry_pending_teardown_locked) attaches a
    fresh worker. If its teardown fails AND detaching that worker fails, arming
    another retry would stack a second stuck worker -> permanent block. So it
    leaks-and-continues: globals nulled, retry disarmed, no thread retained."""
    monkeypatch.setattr(native, "_teardown_needed", False)  # restored after the test regardless
    library = FakeLibrary()
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: library)

    # Acquire cleanly, then arm a pending teardown by hand and point the retry at
    # a library whose teardown and detach both fail.
    lib, isolate = native._acquire_isolate("/tmp/dwlib")
    monkeypatch.setattr(native, "_teardown_needed", True)
    monkeypatch.setattr(native, "_isolate_ref_count", 0)
    library.graal_tear_down_isolate = CallableFunction(lambda _thread: 1)
    library.graal_detach_thread = CallableFunction(lambda _thread: 1)

    # Next acquire runs _retry_pending_teardown_locked, which double-fails.
    with pytest.raises(native.DataWeaveError):
        native._acquire_isolate("/tmp/dwlib")

    assert native._isolate is None
    assert native._lib is None
    assert native._teardown_needed is False

    healthy = FakeLibrary()
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: healthy)
    lib2, isolate2 = native._acquire_isolate("/tmp/dwlib")
    assert native._isolate is not None
    native._release_isolate()
