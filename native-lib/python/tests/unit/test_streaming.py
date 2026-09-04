import ctypes
from queue import Full, Queue
import sys
from threading import Condition, Event, Lock, Thread
from time import sleep

import pytest

import dataweave
from dataweave import runtime as runtime_module


class FakeNative:
    def __init__(self, metadata=None, attach_code=0, emit=b"", consume_input=False):
        self.metadata = metadata
        self.attach_code = attach_code
        self.emit = emit
        self.consume_input = consume_input
        self.attach_count = 0
        self.detached = []
        self.freed = []
        self._buffers = []
        self.detached_event = Event()

    def graal_attach_thread(self, _isolate, _thread):
        self.attach_count += 1
        return self.attach_code

    def graal_detach_thread(self, thread):
        self.detached.append(thread)
        self.detached_event.set()
        return 0

    def free_cstring(self, thread, ptr):
        self.freed.append((thread, ptr))

    def _response_pointer(self):
        if self.metadata is None:
            return None
        buffer = ctypes.create_string_buffer(self.metadata.encode("utf-8"))
        self._buffers.append(buffer)
        return ctypes.addressof(buffer)

    def run_script_callback_engine(self, _thread, _handle, _script, _inputs, write_callback, _context):
        if self.emit:
            buffer = ctypes.create_string_buffer(self.emit)
            self.write_status = write_callback(None, ctypes.addressof(buffer), len(self.emit))
        return self._response_pointer()

    def run_script_input_output_callback_engine(
        self, _thread, _handle, _script, _inputs, _input_name, _mime_type, _charset, read_callback, write_callback, _context,
    ):
        if self.consume_input:
            buffer = ctypes.create_string_buffer(3)
            read = []
            while True:
                size = read_callback(None, ctypes.addressof(buffer), len(buffer))
                self.read_status = size
                if size == 0:
                    break
                if size < 0:
                    return self._response_pointer()
                read.append(bytes(buffer.raw[:size]))
            self.read_input = b"".join(read)
        if self.emit:
            buffer = ctypes.create_string_buffer(self.emit)
            self.write_status = write_callback(None, ctypes.addressof(buffer), len(self.emit))
            if self.write_status != 0:
                return self._response_pointer()
        return self._response_pointer()

    def destroy_engine(self, _thread, _handle):
        self.destroyed_handle = _handle


class CallbackBaseException(BaseException):
    pass


class UnraisableRecorder:
    def __init__(self):
        self.unraisable = []

    def __call__(self, unraisable):
        self.unraisable.append(unraisable)


class ReentrantWriteStatus:
    def __init__(self, runtime):
        self.runtime = runtime
        self.index_calls = 0

    def __index__(self):
        self.index_calls += 1
        self.runtime.run("nested")
        return 0


class IndexOnlyWriteStatus:
    def __init__(self, value):
        self.value = value
        self.index_calls = 0

    def __index__(self):
        self.index_calls += 1
        return self.value


def configured_runtime(native):
    runtime = dataweave.DataWeave.__new__(dataweave.DataWeave)
    native_runtime = runtime_module.NativeRuntime.__new__(runtime_module.NativeRuntime)
    native_runtime.initialized = True
    native_runtime.has_callback_streaming = True
    native_runtime.has_callback_input_output = True
    native_runtime.lib = native
    native_runtime.isolate = object()
    # No persistent attachment (attach-on-demand): every synchronous call --
    # including cleanup() -- attaches its own thread via the FakeNative's
    # graal_attach_thread/graal_detach_thread.
    native_runtime.thread = None
    native_runtime.handle = 1
    native_runtime._resolver = None
    native_runtime._resolver_callback = None
    native_runtime._resolver_token = 0
    native_runtime._resolver_buffers = []
    native_runtime._resolver_active = False
    native_runtime._resolver_active_ident = None
    native_runtime._operation_lock = Condition(Lock())
    native_runtime._execution_owner = None
    runtime._native = native_runtime
    return runtime


@pytest.mark.unit
def test_run_callback_converts_write_callback_exception_to_abort_result():
    native = FakeNative('{"success": false, "error": "callback aborted"}', emit=b"chunk")
    runtime = configured_runtime(native)

    result = runtime.run_callback("script", lambda _chunk: (_ for _ in ()).throw(RuntimeError("stop")))

    assert result == dataweave.StreamingResult(False, "callback aborted", None, None, False)
    assert native.write_status == -1


@pytest.mark.unit
def test_run_input_output_callback_converts_read_exception_to_abort_result():
    native = FakeNative('{"success": false, "error": "read aborted"}', consume_input=True)
    runtime = configured_runtime(native)

    result = runtime.run_input_output_callback(
        "script", "payload", "application/json", lambda _size: (_ for _ in ()).throw(RuntimeError("stop")), lambda _data: 0,
    )

    assert result == dataweave.StreamingResult(False, "read aborted", None, None, False)
    assert native.read_status == -1


@pytest.mark.unit
def test_run_callback_contains_write_callback_base_exception(monkeypatch):
    native = FakeNative('{"success": false, "error": "write aborted"}', emit=b"chunk")
    runtime = configured_runtime(native)
    recorder = UnraisableRecorder()
    monkeypatch.setattr(sys, "unraisablehook", recorder)

    result = runtime.run_callback(
        "script",
        lambda _chunk: (_ for _ in ()).throw(CallbackBaseException("stop")),
    )

    assert native.write_status == -1
    assert recorder.unraisable == []
    assert result == dataweave.StreamingResult(False, "write aborted", None, None, False)


@pytest.mark.unit
@pytest.mark.parametrize(
    "invoke",
    [
        lambda runtime, write_callback: runtime.run_callback("script", write_callback),
        lambda runtime, write_callback: runtime.run_input_output_callback(
            "script", "payload", "application/json", lambda _size: b"", write_callback,
        ),
    ],
)
def test_public_write_callback_normalizes_invalid_status_to_abort_without_unraisable(monkeypatch, invoke):
    native = FakeNative('{"success": false, "error": "write aborted"}', emit=b"chunk")
    runtime = configured_runtime(native)
    recorder = UnraisableRecorder()
    monkeypatch.setattr(sys, "unraisablehook", recorder)

    result = invoke(runtime, lambda _data: None)

    assert native.write_status == -1
    assert recorder.unraisable == []
    assert result == dataweave.StreamingResult(False, "write aborted", None, None, False)


@pytest.mark.unit
@pytest.mark.parametrize(
    "invoke",
    [
        lambda runtime, write_callback: runtime.run_callback("script", write_callback),
        lambda runtime, write_callback: runtime.run_input_output_callback(
            "script", "payload", "application/json", lambda _size: b"", write_callback,
        ),
    ],
)
def test_public_write_callback_preserves_integer_status(invoke):
    native = FakeNative('{"success": false, "error": "write aborted"}', emit=b"chunk")
    runtime = configured_runtime(native)

    result = invoke(runtime, lambda _data: -7)

    assert native.write_status == -7
    assert result == dataweave.StreamingResult(False, "write aborted", None, None, False)


@pytest.mark.unit
@pytest.mark.parametrize(
    "status, expected",
    [
        ((1 << 200) + 23, 23),
        (-((1 << 200) + 23), -23),
        (IndexOnlyWriteStatus((1 << 80) + 23), 23),
    ],
    ids=["large-positive", "large-negative", "index-only"],
)
@pytest.mark.parametrize(
    "invoke",
    [
        lambda runtime, write_callback: runtime.run_callback("script", write_callback),
        lambda runtime, write_callback: runtime.run_input_output_callback(
            "script", "payload", "application/json", lambda _size: b"", write_callback,
        ),
    ],
)
def test_public_write_callback_uses_c_int_status_semantics_without_unraisable(monkeypatch, invoke, status, expected):
    native = FakeNative('{"success": false, "error": "write aborted"}', emit=b"chunk")
    runtime = configured_runtime(native)
    recorder = UnraisableRecorder()
    monkeypatch.setattr(sys, "unraisablehook", recorder)

    result = invoke(runtime, lambda _data: status)

    assert native.write_status == expected
    assert recorder.unraisable == []
    assert result == dataweave.StreamingResult(False, "write aborted", None, None, False)


@pytest.mark.unit
@pytest.mark.parametrize(
    "status, expected",
    [
        ((1 << 200) + 23, 23),
        (-((1 << 200) + 23), -23),
        (IndexOnlyWriteStatus((1 << 80) + 23), 23),
    ],
    ids=["large-positive", "large-negative", "index-only"],
)
@pytest.mark.parametrize(
    "invoke",
    [
        lambda runtime, write_callback: runtime.run_callback("script", write_callback),
        lambda runtime, write_callback: runtime.run_input_output_callback(
            "script", "payload", "application/json", lambda _size: b"", write_callback,
        ),
    ],
)
def test_public_write_callback_normalizes_status_before_ctypes_return(monkeypatch, invoke, status, expected):
    native = FakeNative('{"success": false, "error": "write aborted"}', emit=b"chunk")
    runtime = configured_runtime(native)
    recorder = UnraisableRecorder()
    monkeypatch.setattr(sys, "unraisablehook", recorder)
    monkeypatch.setattr(runtime_module, "WRITE_CALLBACK", lambda callback: callback)

    result = invoke(runtime, lambda _data: status)

    assert native.write_status == expected
    assert recorder.unraisable == []
    assert result == dataweave.StreamingResult(False, "write aborted", None, None, False)


@pytest.mark.unit
@pytest.mark.parametrize(
    "invoke",
    [
        lambda runtime, write_callback: runtime.run_callback("script", write_callback),
        lambda runtime, write_callback: runtime.run_input_output_callback(
            "script", "payload", "application/json", lambda _size: b"", write_callback,
        ),
    ],
)
def test_public_write_callback_converts_custom_status_inside_native_callback_scope(monkeypatch, invoke):
    native = FakeNative('{"success": false, "error": "write aborted"}', emit=b"chunk")
    runtime = configured_runtime(native)
    status = ReentrantWriteStatus(runtime)
    recorder = UnraisableRecorder()
    monkeypatch.setattr(sys, "unraisablehook", recorder)

    result = invoke(runtime, lambda _data: status)

    assert native.write_status == -1
    assert status.index_calls == 1
    assert native.attach_count == 1  # Only the outer callback invocation attached.
    assert recorder.unraisable == []
    assert result == dataweave.StreamingResult(False, "write aborted", None, None, False)


@pytest.mark.unit
def test_run_input_output_callback_contains_read_callback_base_exception(monkeypatch):
    native = FakeNative('{"success": false, "error": "read aborted"}', consume_input=True)
    runtime = configured_runtime(native)
    recorder = UnraisableRecorder()
    monkeypatch.setattr(sys, "unraisablehook", recorder)

    result = runtime.run_input_output_callback(
        "script",
        "payload",
        "application/json",
        lambda _size: (_ for _ in ()).throw(CallbackBaseException("stop")),
        lambda _data: 0,
    )

    assert native.read_status == -1
    assert recorder.unraisable == []
    assert result == dataweave.StreamingResult(False, "read aborted", None, None, False)


@pytest.mark.unit
def test_run_input_output_callback_contains_write_callback_base_exception(monkeypatch):
    native = FakeNative('{"success": false, "error": "write aborted"}', emit=b"chunk", consume_input=True)
    runtime = configured_runtime(native)
    recorder = UnraisableRecorder()
    monkeypatch.setattr(sys, "unraisablehook", recorder)

    result = runtime.run_input_output_callback(
        "script",
        "payload",
        "application/json",
        lambda _size: b"",
        lambda _data: (_ for _ in ()).throw(CallbackBaseException("stop")),
    )

    assert native.write_status == -1
    assert recorder.unraisable == []
    assert result == dataweave.StreamingResult(False, "write aborted", None, None, False)


@pytest.mark.unit
def test_transform_contains_input_iterator_base_exception(monkeypatch):
    native = FakeNative('{"success": false, "error": "read aborted"}', consume_input=True)
    runtime = configured_runtime(native)
    recorder = UnraisableRecorder()
    monkeypatch.setattr(sys, "unraisablehook", recorder)

    def input_stream():
        raise CallbackBaseException("stop")
        yield b""  # pragma: no cover

    stream = runtime.run_transform("script", input_stream())

    assert list(stream) == []
    assert native.read_status == -1
    assert recorder.unraisable == []
    assert stream.metadata == dataweave.StreamingResult(False, "read aborted", None, None, False)


@pytest.mark.unit
def test_write_callback_reentry_is_translated_to_abort_without_deadlocking():
    outer_native = FakeNative('{"success": false, "error": "write aborted"}', emit=b"chunk")
    inner_native = FakeNative('{"success": true}')
    outer = configured_runtime(outer_native)
    inner = configured_runtime(inner_native)

    lock_available = []

    def reenter(_chunk):
        acquired = outer._native._operation_lock.acquire(blocking=False)
        lock_available.append(acquired)
        if acquired:
            outer._native._operation_lock.release()
        return inner.run_callback("nested", lambda _data: 0)

    result = outer.run_callback("outer", reenter)

    assert outer_native.write_status == -1
    assert inner_native.attach_count == 0
    assert lock_available == [True]
    assert result == dataweave.StreamingResult(False, "write aborted", None, None, False)


@pytest.mark.unit
def test_read_callback_reentry_is_translated_to_abort_without_deadlocking():
    outer_native = FakeNative('{"success": false, "error": "read aborted"}', consume_input=True)
    inner_native = FakeNative('{"success": true}')
    outer = configured_runtime(outer_native)
    inner = configured_runtime(inner_native)

    lock_available = []

    def reenter(_size):
        acquired = outer._native._operation_lock.acquire(blocking=False)
        lock_available.append(acquired)
        if acquired:
            outer._native._operation_lock.release()
        return inner.run("nested").get_bytes()

    result = outer.run_input_output_callback(
        "outer",
        "payload",
        "application/json",
        reenter,
        lambda _data: 0,
    )

    assert outer_native.read_status == -1
    assert inner_native.attach_count == 0
    assert lock_available == [True]
    assert result == dataweave.StreamingResult(False, "read aborted", None, None, False)


@pytest.mark.unit
def test_transform_input_iterator_reentry_is_translated_to_abort_without_native_attach():
    outer_native = FakeNative('{"success": false, "error": "read aborted"}', consume_input=True)
    inner_native = FakeNative('{"success": true}')
    outer = configured_runtime(outer_native)
    inner = configured_runtime(inner_native)
    lock_available = []

    def input_stream():
        acquired = outer._native._operation_lock.acquire(blocking=False)
        lock_available.append(acquired)
        if acquired:
            outer._native._operation_lock.release()
        yield inner.run("nested").get_bytes()

    stream = outer.run_transform("outer", input_stream())

    assert list(stream) == []
    assert outer_native.read_status == -1
    assert inner_native.attach_count == 0
    assert lock_available == [True]
    assert stream.metadata == dataweave.StreamingResult(False, "read aborted", None, None, False)


@pytest.mark.unit
@pytest.mark.parametrize(
    "create_stream",
    [
        lambda runtime: runtime.run_streaming("script"),
        lambda runtime: runtime.run_transform("script", [b"null"]),
    ],
)
def test_precreated_stream_rejects_callback_time_first_consumption_before_worker_admission(monkeypatch, create_stream):
    native = FakeNative('{"success": true}')
    runtime = configured_runtime(native)
    stream = create_stream(runtime)
    registrations = []
    starts = []
    original_register = runtime._register_stream_worker

    def record_registration(worker):
        registrations.append(worker)
        return original_register(worker)

    class UnexpectedThread:
        def __init__(self, *_args, **_kwargs):
            starts.append("constructed")

    monkeypatch.setattr(runtime, "_register_stream_worker", record_registration)
    monkeypatch.setattr(runtime_module, "Thread", UnexpectedThread)

    with runtime_module._native_callback_scope(), pytest.raises(dataweave.DataWeaveError, match="native callback"):
        next(stream)

    assert registrations == []
    assert starts == []
    assert native.attach_count == 0


@pytest.mark.unit
def test_internal_streaming_write_trampoline_contains_base_exception(monkeypatch):
    recorder = UnraisableRecorder()
    monkeypatch.setattr(sys, "unraisablehook", recorder)

    class RaisingCancel(Event):
        def __init__(self):
            super().__init__()
            self.raise_once = True

        def is_set(self):
            if self.raise_once:
                self.raise_once = False
                raise CallbackBaseException("cancel check failed")
            return super().is_set()

    native = FakeNative('{"success": false, "error": "write aborted"}', emit=b"chunk")
    runtime = configured_runtime(native)
    monkeypatch.setattr(runtime_module, "Event", RaisingCancel)
    stream = runtime.run_streaming("script")

    assert list(stream) == []

    assert native.write_status == -1
    assert recorder.unraisable == []
    assert stream.metadata == dataweave.StreamingResult(False, "write aborted", None, None, False)


@pytest.mark.unit
@pytest.mark.parametrize(
    "invoke",
    [
        lambda runtime: runtime.run_callback("script", lambda _data: 0),
        lambda runtime: runtime.run_input_output_callback(
            "script", "payload", "application/json", lambda _size: b"", lambda _data: 0,
        ),
    ],
)
def test_callback_apis_translate_malformed_native_metadata(invoke):
    runtime = configured_runtime(FakeNative("not-json"))

    with pytest.raises(dataweave.DataWeaveError, match="Failed to execute callback"):
        invoke(runtime)


@pytest.mark.unit
def test_run_transform_preserves_remainder_of_large_input_chunk():
    native = FakeNative('{"success": true, "mimeType": "application/json", "charset": "utf-8"}', consume_input=True)
    runtime = configured_runtime(native)
    stream = runtime.run_transform("script", [b"abcdefgh"], input_mime_type="application/json")

    assert list(stream) == []
    assert native.read_input == b"abcdefgh"
    assert stream.metadata == dataweave.StreamingResult(True, None, "application/json", "utf-8", False)


@pytest.mark.unit
def test_streaming_explicit_worker_thread_is_not_attached_twice():
    native = FakeNative('{"success": true}')
    runtime = configured_runtime(native)

    assert list(runtime.run_streaming("script")) == []

    assert native.attach_count == 1
    assert len(native.detached) == 1


@pytest.mark.unit
def test_run_streaming_returns_failure_metadata_when_worker_produces_no_metadata(monkeypatch):
    class MetadataDroppingQueue(Queue):
        def put(self, item, *args, **kwargs):
            if isinstance(item, dict):
                return None
            return super().put(item, *args, **kwargs)

    monkeypatch.setattr(runtime_module, "Queue", MetadataDroppingQueue)
    runtime = configured_runtime(FakeNative('{"success": true}'))
    stream = runtime.run_streaming("script")

    assert list(stream) == []
    assert stream.metadata == dataweave.StreamingResult(False, "No metadata received from native call", None, None, False)


@pytest.mark.unit
def test_run_streaming_returns_attach_failure_without_detaching_unattached_thread():
    native = FakeNative(attach_code=9)
    runtime = configured_runtime(native)
    stream = runtime.run_streaming("script")

    assert list(stream) == []
    assert stream.metadata == dataweave.StreamingResult(False, "Failed to attach worker thread to isolate (code 9)", None, None, False)
    assert native.detached == []


@pytest.mark.unit
def test_stream_public_close_aborts_worker_and_detaches_after_consumer_abandons_output():
    class BlockingFakeNative(FakeNative):
        def __init__(self):
            super().__init__('{"success": false, "error": "aborted"}')
            self.first_chunk_written = Event()
            self.cancelled = None

        def run_script_callback_engine(self, _thread, _handle, _script, _inputs, write_callback, _context):
            first = ctypes.create_string_buffer(b"first")
            assert write_callback(None, ctypes.addressof(first), 5) == 0
            self.first_chunk_written.set()
            assert self.cancelled.wait(1)
            second = ctypes.create_string_buffer(b"second")
            self.write_status = write_callback(None, ctypes.addressof(second), 6)
            return self._response_pointer()

    native = BlockingFakeNative()
    runtime = configured_runtime(native)
    stream = runtime.run_streaming("script")
    native.cancelled = stream._cancelled

    assert next(stream) == b"first"
    assert native.first_chunk_written.wait(1)
    stream.close()

    assert native.detached_event.wait(1)
    assert native.write_status == -1


@pytest.mark.unit
def test_run_input_output_callback_rejects_oversized_read_chunk_without_truncating():
    class OversizedInputNative(FakeNative):
        def run_script_input_output_callback_engine(
            self, _thread, _handle, _script, _inputs, _input_name, _mime_type, _charset, read_callback, _write_callback, _context,
        ):
            buffer = ctypes.create_string_buffer(3)
            self.read_status = read_callback(None, ctypes.addressof(buffer), len(buffer))
            self.read_input = bytes(buffer.raw[:max(self.read_status, 0)])
            return self._response_pointer()

    native = OversizedInputNative('{"success": false, "error": "read aborted"}')
    runtime = configured_runtime(native)

    result = runtime.run_input_output_callback(
        "script", "payload", "application/json", lambda _size: b"oversized", lambda _data: 0,
    )

    assert result == dataweave.StreamingResult(False, "read aborted", None, None, False)
    assert native.read_status == -1
    assert native.read_input == b""


@pytest.mark.unit
def test_stream_early_close_does_not_block_terminal_publication_on_full_queue(monkeypatch):
    cancelled = []
    terminal_blocked = Event()

    class CancellationAwareQueue(Queue):
        def put(self, item, block=True, timeout=None):
            if not isinstance(item, bytes) and cancelled[0].is_set():
                if timeout is None:
                    terminal_blocked.set()
                raise Full
            return super().put(item, block, timeout)

    class FullQueueFakeNative(FakeNative):
        def __init__(self):
            super().__init__('{"success": false, "error": "aborted"}')
            self.queue_full = Event()
            self.cancelled = None

        def run_script_callback_engine(self, _thread, _handle, _script, _inputs, write_callback, _context):
            first = ctypes.create_string_buffer(b"first")
            assert write_callback(None, ctypes.addressof(first), 5) == 0
            second = ctypes.create_string_buffer(b"second")
            assert write_callback(None, ctypes.addressof(second), 6) == 0
            self.queue_full.set()
            assert self.cancelled.wait(1)
            third = ctypes.create_string_buffer(b"third")
            self.write_status = write_callback(None, ctypes.addressof(third), 5)
            return self._response_pointer()

    monkeypatch.setattr(runtime_module, "Queue", CancellationAwareQueue)
    monkeypatch.setattr(runtime_module, "_OUTPUT_QUEUE_MAXSIZE", 1)
    native = FullQueueFakeNative()
    runtime = configured_runtime(native)
    stream = runtime.run_streaming("script")
    cancelled.append(stream._cancelled)
    native.cancelled = stream._cancelled

    assert next(stream) == b"first"
    assert native.queue_full.wait(1)
    stream._close()

    assert native.write_status == -1
    assert native.detached_event.wait(1)
    assert terminal_blocked.is_set() is False


@pytest.mark.unit
def test_runtime_module_owns_dataweave_orchestration():
    assert dataweave.DataWeave is runtime_module.DataWeave


@pytest.mark.unit
def test_run_streaming_reports_worker_timeout_when_native_call_produces_no_output(monkeypatch):
    class BlockingFakeNative(FakeNative):
        def run_script_callback_engine(self, _thread, _handle, _script, _inputs, _write_callback, _context):
            sleep(0.05)
            return self._response_pointer()

    monkeypatch.setattr(runtime_module, "_WORKER_TIMEOUT_SECONDS", 0.01)
    runtime = configured_runtime(BlockingFakeNative('{"success": true}'))

    with pytest.raises(dataweave.DataWeaveError, match="Worker thread timeout after 0.01 seconds"):
        list(runtime.run_streaming("script"))


@pytest.mark.unit
def test_stream_worker_start_failure_does_not_block_cleanup(monkeypatch):
    native = FakeNative('{"success": true}')
    runtime = configured_runtime(native)

    def fail_start(_worker):
        raise RuntimeError("cannot start")

    monkeypatch.setattr(runtime_module.Thread, "start", fail_start)

    with pytest.raises(RuntimeError, match="cannot start"):
        list(runtime.run_streaming("script"))
    runtime.cleanup()


@pytest.mark.unit
def test_stream_finalization_does_not_raise_when_a_native_worker_cannot_cancel(monkeypatch):
    class UncancellableNative(FakeNative):
        def run_script_callback_engine(self, _thread, _handle, _script, _inputs, _write_callback, _context):
            sleep(0.1)
            return self._response_pointer()

    monkeypatch.setattr(runtime_module, "_WORKER_JOIN_TIMEOUT_SECONDS", 0.001)
    runtime = configured_runtime(UncancellableNative('{"success": true}'))
    stream = runtime.run_streaming("script")

    stream.close()


@pytest.mark.unit
def test_cleanup_refuses_to_tear_down_isolate_while_stream_worker_is_active(monkeypatch):
    class BlockingNative(FakeNative):
        def __init__(self):
            super().__init__('{"success": true}')
            self.started = Event()
            self.release = Event()
            self.torn_down = False

        def run_script_callback_engine(self, _thread, _handle, _script, _inputs, _write_callback, _context):
            self.started.set()
            self.release.wait()
            return self._response_pointer()

        def destroy_engine(self, _thread, _handle):
            self.torn_down = True

    monkeypatch.setattr(runtime_module, "_WORKER_JOIN_TIMEOUT_SECONDS", 0.001)
    native = BlockingNative()
    runtime = configured_runtime(native)
    stream = runtime.run_streaming("script")
    consumer_error = []

    def consume():
        try:
            next(stream, None)
        except dataweave.DataWeaveError as error:
            consumer_error.append(error)

    consumer = Thread(target=consume)
    consumer.start()
    assert native.started.wait(timeout=1)
    stream.close()

    with pytest.raises(dataweave.DataWeaveError, match="active streaming worker"):
        runtime.cleanup()
    assert native.torn_down is False

    native.release.set()
    consumer.join(timeout=1)
    assert consumer_error == []
    assert native.detached_event.wait(timeout=1)
    runtime.cleanup()
    assert native.torn_down is True


@pytest.mark.unit
def test_stream_worker_cannot_register_after_isolate_teardown_starts():
    class BlockingCleanupNative(FakeNative):
        def __init__(self):
            super().__init__('{"success": true}')
            self.cleanup_started = Event()
            self.release_cleanup = Event()

        def destroy_engine(self, _thread, _handle):
            self.cleanup_started.set()
            self.release_cleanup.wait()

    native = BlockingCleanupNative()
    runtime = configured_runtime(native)
    cleanup = Thread(target=runtime.cleanup)
    cleanup.start()
    assert native.cleanup_started.wait(timeout=1)

    with pytest.raises(dataweave.DataWeaveError, match="being cleaned up"):
        runtime._register_stream_worker(Thread())

    native.release_cleanup.set()
    cleanup.join(timeout=1)


@pytest.mark.unit
def test_run_streaming_surfaces_detach_failure_without_primary_execution_failure():
    class DetachFailingNative(FakeNative):
        def graal_detach_thread(self, thread):
            super().graal_detach_thread(thread)
            return 7

    runtime = configured_runtime(DetachFailingNative('{"success": true}'))

    with pytest.raises(dataweave.DataWeaveError, match="Failed to detach worker thread from isolate. Error code: 7"):
        list(runtime.run_streaming("script"))


@pytest.mark.unit
def test_run_streaming_preserves_unsuccessful_metadata_when_detach_fails():
    class DetachFailingNative(FakeNative):
        def graal_detach_thread(self, thread):
            super().graal_detach_thread(thread)
            return 7

    runtime = configured_runtime(DetachFailingNative('{"success": false, "error": "script failed"}'))
    stream = runtime.run_streaming("script")

    assert list(stream) == []
    assert stream.metadata == dataweave.StreamingResult(False, "script failed", None, None, False)
