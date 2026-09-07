import ctypes
import inspect
import threading
import time

import pytest

import dataweave
from dataweave import native, runtime


class FakeNativeRuntime:
    def __init__(self):
        self.initialized = True
        self.thread = "thread"
        self.operation = native._EngineOperation(handle=7, generation=1)
        self.calls = []

    def capture_operation(self):
        return self.operation

    def run_engine_and_decode(self, script, inputs, *, operation):
        assert operation is self.operation
        assert operation.handle == 7
        self.calls.append(("run_engine_and_decode", script, inputs, operation))
        return self._result()

    @staticmethod
    def _result():
        return '{"success": true, "result": "SGVsbG8=", "binary": false, "mimeType": "text/plain", "charset": "utf-8"}'


def configured_runtime(resolve_module=None):
    instance = dataweave.DataWeave.__new__(dataweave.DataWeave)
    instance._native = FakeNativeRuntime()
    instance._resolve_module = resolve_module
    return instance


@pytest.mark.unit
def test_facade_preserves_fixed_legacy_public_exports():
    legacy_exports = [
        "DataWeave",
        "DataWeaveError",
        "DataWeaveLibraryNotFoundError",
        "DataWeaveScriptError",
        "ExecutionResult",
        "InputValue",
        "ReadCallback",
        "Stream",
        "StreamingResult",
        "WriteCallback",
        "READ_CALLBACK",
        "WRITE_CALLBACK",
        "run",
        "run_callback",
        "run_input_output_callback",
        "run_streaming",
        "run_transform",
        "cleanup",
    ]

    for name in legacy_exports:
        assert name in dataweave.__all__
        getattr(dataweave, name)


@pytest.mark.unit
def test_facade_exports_module_resolver_factories():
    resolver_exports = [
        "ModuleResolver",
        "RESOLVE_MODULE_CALLBACK",
        "compose_resolvers",
        "modules_from_directory",
        "modules_from_jars",
        "modules_from_map",
    ]

    for name in resolver_exports:
        assert name in dataweave.__all__
        getattr(dataweave, name)


@pytest.mark.unit
def test_dataweave_constructor_stores_keyword_only_module_resolver(monkeypatch):
    resolver = lambda _path: "source"
    native_runtime = FakeNativeRuntime()
    monkeypatch.setattr(runtime, "NativeRuntime", lambda _lib_path: native_runtime)

    instance = dataweave.DataWeave(resolve_module=resolver)

    assert instance._resolve_module is resolver
    assert inspect.signature(dataweave.DataWeave).parameters[
        "resolve_module"
    ].kind is inspect.Parameter.KEYWORD_ONLY


@pytest.mark.unit
def test_run_uses_engine_execution_regardless_of_resolver():
    resolver = lambda _path: "source"
    instance = configured_runtime(resolver)

    result = instance.run("payload", {"value": 1})

    assert result == dataweave.ExecutionResult(
        True, "SGVsbG8=", None, False, "text/plain", "utf-8"
    )
    assert instance._native.calls == [
        (
            "run_engine_and_decode",
            b"payload",
            b'{"value": {"content": "MQ==", "mimeType": "application/json", "charset": "utf-8"}}',
            instance._native.operation,
        )
    ]


@pytest.mark.unit
def test_run_without_resolver_routes_through_engine():
    instance = configured_runtime()

    result = instance.run("payload")

    assert result == dataweave.ExecutionResult(
        True, "SGVsbG8=", None, False, "text/plain", "utf-8"
    )
    assert instance._native.calls == [
        (
            "run_engine_and_decode",
            b"payload",
            b"{}",
            instance._native.operation,
        )
    ]


@pytest.mark.unit
def test_module_level_run_does_not_accept_module_resolver():
    assert "resolve_module" not in inspect.signature(dataweave.run).parameters


@pytest.mark.unit
def test_global_facade_initializes_once_and_cleanup_allows_recreation(monkeypatch):
    created = []
    registered = []

    class FakeRuntime:
        def __init__(self):
            self.cleaned = False
            created.append(self)

        def initialize(self):
            pass

        def cleanup(self):
            self.cleaned = True

    monkeypatch.setattr(dataweave, "DataWeave", FakeRuntime)
    monkeypatch.setattr("atexit.register", registered.append)

    first = dataweave._get_global_instance()
    second = dataweave._get_global_instance()
    dataweave.cleanup()
    third = dataweave._get_global_instance()

    assert first is second
    assert first.cleaned is True
    assert third is not first
    assert registered == [dataweave.cleanup, dataweave.cleanup]


@pytest.mark.unit
def test_module_cleanup_from_native_callback_preserves_published_instance(monkeypatch):
    instance = configured_runtime()
    cleanup_calls = []
    instance.cleanup = lambda: cleanup_calls.append(True)
    monkeypatch.setattr(dataweave, "_global_instance", instance)

    with native._native_callback_scope(), pytest.raises(dataweave.DataWeaveError, match="native callback"):
        dataweave.cleanup()

    assert dataweave._global_instance is instance
    assert cleanup_calls == []
    assert dataweave.run("payload").get_string() == "Hello"


@pytest.mark.unit
def test_module_execution_from_native_callback_rejects_before_global_lock_or_native_run(monkeypatch):
    instance = configured_runtime()
    monkeypatch.setattr(dataweave, "_global_instance", instance)
    lock_calls = []

    class UnexpectedLock:
        def __enter__(self):
            lock_calls.append("enter")
            raise AssertionError("global lock acquired")

        def __exit__(self, _exc_type, _exc_value, _traceback):
            pass

    monkeypatch.setattr(dataweave, "_global_lock", UnexpectedLock())

    with native._native_callback_scope():
        with pytest.raises(dataweave.DataWeaveError, match="native callback"):
            dataweave.run("payload")

    assert lock_calls == []
    assert instance._native.calls == []


@pytest.mark.unit
def test_cleanup_is_noop_without_global_runtime():
    dataweave.cleanup()

    assert dataweave._global_instance is None


@pytest.mark.unit
def test_global_cleanup_clears_global_before_reraising_on_failure(monkeypatch):
    created = []

    class FakeRuntime:
        def __init__(self):
            created.append(self)

        def initialize(self):
            pass

        def cleanup(self):
            raise dataweave.DataWeaveError("teardown failed")

    monkeypatch.setattr(dataweave, "DataWeave", FakeRuntime)
    monkeypatch.setattr("atexit.register", lambda _fn: None)
    first = dataweave._get_global_instance()

    # cleanup() nulls the global under _global_lock *before* running the
    # (potentially slow) instance.cleanup() outside the lock, so a failing
    # teardown does not strand the lock held nor leave a half-torn-down
    # instance published. The instance identity is not retained for retry --
    # that's fine because isolate-level teardown retry lives one layer down
    # in dataweave.native (_teardown_needed), independent of which Python
    # DataWeave wrapper object is holding the reference.
    with pytest.raises(dataweave.DataWeaveError, match="teardown failed"):
        dataweave.cleanup()

    assert dataweave._global_instance is None

    second = dataweave._get_global_instance()
    assert second is not first
    assert len(created) == 2
    dataweave._global_instance = None


@pytest.mark.unit
def test_get_global_instance_publishes_exactly_one_instance_under_concurrent_first_use(monkeypatch):
    thread_count = 8
    barrier = threading.Barrier(thread_count)
    counts_lock = threading.Lock()
    counts = {"created": 0, "initialized": 0}

    class SlowRuntime:
        def __init__(self):
            with counts_lock:
                counts["created"] += 1
            # Widen the window between the "is it published yet" check and
            # publication so concurrent first-callers are very likely to
            # overlap while racing to construct+initialize a candidate.
            time.sleep(0.05)

        def initialize(self):
            with counts_lock:
                counts["initialized"] += 1

        def cleanup(self):
            pass

    monkeypatch.setattr(dataweave, "DataWeave", SlowRuntime)
    monkeypatch.setattr("atexit.register", lambda _fn: None)

    results = [None] * thread_count
    errors = []

    def worker(index):
        barrier.wait()
        try:
            results[index] = dataweave._get_global_instance()
        except Exception as exc:  # pragma: no cover - defensive, surfaced via `errors`
            errors.append(exc)

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(thread_count)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    try:
        assert not errors
        # Exactly one instance is ever constructed and initialized: creation,
        # initialization, and publication all happen under _global_lock, so a
        # losing thread never builds (and leaks) a candidate engine.
        assert counts["created"] == 1
        assert counts["initialized"] == 1
        assert len({id(result) for result in results}) == 1
        assert results[0] is dataweave._global_instance
    finally:
        dataweave._global_instance = None


class _FakeCallable:
    """A settable-attribute stand-in for a ctypes function pointer: plain
    objects (unlike bound methods) accept `.argtypes`/`.restype` assignment,
    which `native._bind_abi` performs on every ABI export it binds."""

    def __init__(self, callback=None):
        self._callback = callback

    def __call__(self, *args):
        return self._callback(*args) if self._callback else 0


class FakeLifecycleLibrary:
    """Minimal ctypes-library stand-in that lets `NativeRuntime.initialize()`/
    `install_resolver()`/`cleanup()` run for real -- exercising the actual
    module-global `_resolver_registry` / `_isolate_ref_count` bookkeeping in
    `dataweave.native` -- without touching a real native library."""

    def __init__(self):
        self._next_handle = 1
        self.graal_create_isolate = _FakeCallable(lambda _params, _isolate, _thread: 0)
        self.graal_attach_thread = _FakeCallable(self._attach_thread)
        self.graal_detach_thread = _FakeCallable(lambda _thread: 0)
        self.graal_tear_down_isolate = _FakeCallable(lambda _thread: 0)
        self.free_cstring = _FakeCallable()
        self.create_engine = _FakeCallable(self._create_engine)
        self.create_engine_with_resolver = _FakeCallable(self._create_engine_with_resolver)
        self.destroy_engine = _FakeCallable()
        self.run_script_engine = _FakeCallable()
        self.run_script_callback_engine = _FakeCallable()
        self.run_script_input_output_callback_engine = _FakeCallable()

    @staticmethod
    def _attach_thread(_isolate, thread_out):
        ctypes.cast(thread_out, ctypes.POINTER(native.GraalIsolateThreadPointer))[0] = (
            native.GraalIsolateThreadPointer()
        )
        return 0

    def _create_engine(self, _thread):
        handle = self._next_handle
        self._next_handle += 1
        return handle

    def _create_engine_with_resolver(self, _thread, _callback, _ctx):
        handle = self._next_handle
        self._next_handle += 1
        return handle


@pytest.mark.unit
def test_concurrent_initialize_installs_exactly_one_resolver_token(monkeypatch):
    # review #12 finding #2 (Medium): DataWeave.initialize() called
    # self._native.install_resolver(...) then self._native.initialize() with no
    # instance-level lock. Concurrent initialize() calls on the SAME instance
    # can each pass the `if self._native.initialized: return` fast-path and
    # each call install_resolver(), which allocates a fresh token and
    # registers it in the module-global registry before any of them reaches
    # NativeRuntime's own _init_lock-guarded engine creation. Only the last
    # writer's token survives on `self._native._resolver_token`, so cleanup()
    # (which only pops that one token) leaks every earlier registration.
    monkeypatch.setattr(native.ctypes, "CDLL", lambda _path: FakeLifecycleLibrary())

    before = set(native._resolver_registry.keys())
    dw = dataweave.DataWeave(lib_path="/tmp/dwlib", resolve_module=lambda _path: None)

    thread_count = 8

    # A thread_count-party barrier with a timeout, patched into
    # NativeRuntime.initialize (the real engine-creation call, invoked AFTER
    # install_resolver() in DataWeave.initialize()). On the UNFIXED code every
    # thread passes the `if self._native.initialized: return` fast-path
    # concurrently (none of them has finished a full initialize() yet, so the
    # flag is still False for all), so every thread reaches this point having
    # ALREADY called install_resolver() -- reproducing thread_count
    # independent install_resolver() calls, each minting and registering its
    # own token, before any of them performs the real (locked) engine
    # creation. On the FIXED (per-instance-locked) code only one thread is
    # ever inside initialize() at a time, so it is the only caller that ever
    # reaches this barrier; the other threads see `initialized` already True
    # once they acquire the lock and never call install_resolver or this
    # method at all. The lone caller's wait times out, the barrier breaks,
    # and it proceeds normally -- this must NOT deadlock the fixed code.
    native_initialize_barrier = threading.Barrier(thread_count)
    orig_native_initialize = dw._native.initialize

    def synchronized_native_initialize():
        try:
            native_initialize_barrier.wait(timeout=0.5)
        except threading.BrokenBarrierError:
            pass
        return orig_native_initialize()

    monkeypatch.setattr(dw._native, "initialize", synchronized_native_initialize)

    barrier = threading.Barrier(thread_count)
    errors = []

    def go():
        barrier.wait()
        try:
            dw.initialize()
        except Exception as exc:  # noqa: BLE001
            errors.append(exc)

    threads = [threading.Thread(target=go) for _ in range(thread_count)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    try:
        assert not errors
        new_tokens = set(native._resolver_registry.keys()) - before
        assert len(new_tokens) == 1  # exactly one token, no orphan
        assert native._isolate_ref_count == 1  # exactly one engine reference
    finally:
        dw.cleanup()
