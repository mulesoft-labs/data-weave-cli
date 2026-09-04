import ctypes
import json
from queue import Empty, Full, Queue
from threading import Event, Lock, Thread, current_thread
from typing import Any, Dict, Generator, Iterable, Optional

from .encoding import normalize_input_value, parse_native_encoded_response, parse_streaming_result
from .models import (
    READ_CALLBACK,
    WRITE_CALLBACK,
    DataWeaveError,
    DataWeaveScriptError,
    ExecutionResult,
    ReadCallback,
    Stream,
    StreamingResult,
    WriteCallback,
)
from .native import _native_callback_scope, _raise_if_native_callback_active, NativeRuntime
from .resolver import ModuleResolver


_OUTPUT_QUEUE_MAXSIZE = 512
_WORKER_TIMEOUT_SECONDS = 30
_WORKER_JOIN_TIMEOUT_SECONDS = 0.1


class DataWeave:
    """High-level execution API backed by a :class:`NativeRuntime`."""

    def __init__(
        self,
        lib_path: Optional[str] = None,
        *,
        resolve_module: Optional[ModuleResolver] = None,
    ):
        self._native = NativeRuntime(lib_path)
        self._resolve_module = resolve_module
        self._stream_workers = set()
        self._stream_workers_lock = Lock()
        self._cleaning_up = False
        self._lifecycle_lock = Lock()

    def initialize(self):
        _raise_if_native_callback_active()
        # Holds the lock across the whole install-resolver + native-init
        # transition so two concurrent initialize() calls on this instance
        # cannot both pass the `initialized` guard and both call
        # install_resolver(), which would mint and register a second resolver
        # token in the module-global registry and orphan it (review #12 #2).
        # Always the outermost lock: NativeRuntime._init_lock and the module-
        # global _resolver_lock_global are only ever taken INSIDE
        # self._native.initialize()/cleanup(), nested within this one.
        with self._lifecycle():
            if self._native.initialized:
                return
            if self._resolve_module is not None:
                self._native.install_resolver(self._resolve_module)
            self._native.initialize()

    def cleanup(self):
        _raise_if_native_callback_active()
        # Symmetric with initialize(): install_resolver() and
        # NativeRuntime.cleanup() both mutate the module-global resolver
        # registry, so cleanup() takes the same instance-level lock.
        with self._lifecycle():
            workers, lock = self._worker_registry()
            with lock:
                if workers:
                    raise DataWeaveError("Cannot clean up DataWeave runtime while an active streaming worker is attached.")
                self._cleaning_up = True
            try:
                self._native.cleanup()
            finally:
                with lock:
                    self._cleaning_up = False

    def _lifecycle(self):
        if not hasattr(self, "_lifecycle_lock"):
            self._lifecycle_lock = Lock()
        return self._lifecycle_lock

    def _worker_registry(self):
        if not hasattr(self, "_stream_workers"):
            self._stream_workers = set()
            self._stream_workers_lock = Lock()
        return self._stream_workers, self._stream_workers_lock

    def _register_stream_worker(self, worker: Thread) -> None:
        workers, lock = self._worker_registry()
        with lock:
            if getattr(self, "_cleaning_up", False):
                raise DataWeaveError("Cannot start a streaming worker while the DataWeave runtime is being cleaned up.")
            workers.add(worker)

    def _unregister_stream_worker(self, worker: Optional[Thread] = None) -> None:
        workers, lock = self._worker_registry()
        with lock:
            workers.discard(worker or current_thread())

    def _require_initialized(self, supported: bool, api_name: str) -> None:
        _raise_if_native_callback_active()
        if not self._native.initialized:
            raise DataWeaveError("DataWeave runtime not initialized. Call initialize() first.")
        if not supported:
            raise DataWeaveError(f"Native library does not support {api_name}.")

    @staticmethod
    def _inputs_json(inputs: Optional[Dict[str, Any]]) -> bytes:
        return json.dumps({key: normalize_input_value(value) for key, value in (inputs or {}).items()}).encode("utf-8")

    def run(self, script: str, inputs: Optional[Dict[str, Any]] = None, raise_on_error: bool = False) -> ExecutionResult:
        self._require_initialized(True, "script execution")
        try:
            raw = self._native.run_engine_and_decode(
                script.encode("utf-8"), self._inputs_json(inputs)
            )
            result = parse_native_encoded_response(raw)
        except Exception as error:
            raise DataWeaveError(f"Failed to execute script: {error}")
        if raise_on_error and not result.success:
            raise DataWeaveScriptError(result)
        return result

    def run_callback(self, script: str, write_callback: WriteCallback, inputs: Optional[Dict[str, Any]] = None) -> StreamingResult:
        self._require_initialized(self._native.has_callback_streaming, "callback streaming API (run_script_callback not found)")
        @WRITE_CALLBACK
        def write_cb(_context, buffer, length):
            try:
                data = ctypes.string_at(buffer, length)
                with _native_callback_scope():
                    return int(write_callback(data))
            except BaseException:
                return -1
        try:
            raw = self._native.run_callback_engine_and_decode(self._native.thread, script.encode("utf-8"), self._inputs_json(inputs), write_cb)
            return parse_streaming_result(json.loads(raw) if raw else {"success": False, "error": "Empty response"})
        except Exception as error:
            raise DataWeaveError(f"Failed to execute callback streaming: {error}")

    def _stream_worker(self, invoke, cancelled: Event) -> Generator[bytes, None, StreamingResult]:
        _raise_if_native_callback_active()
        sentinel = object()
        queue: Queue = Queue(maxsize=_OUTPUT_QUEUE_MAXSIZE)

        class CleanupFailure:
            def __init__(self, error: Exception):
                self.error = error

        def publish(item) -> None:
            while not cancelled.is_set():
                try:
                    queue.put(item, timeout=0.1)
                    return
                except Full:
                    pass

        @WRITE_CALLBACK
        def write_cb(_context, buffer, length):
            try:
                if cancelled.is_set():
                    return -1
                queue.put(ctypes.string_at(buffer, length), timeout=_WORKER_TIMEOUT_SECONDS)
                return 0
            except BaseException:
                return -1

        def worker_main():
            worker_thread = None
            primary_error = None
            primary_outcome = False
            try:
                worker_thread = self._native.attach_thread()
                raw = invoke(worker_thread, write_cb)
                metadata = json.loads(raw) if raw else {"success": False, "error": "Empty response"}
                primary_outcome = not metadata.get("success", False)
                publish(metadata)
            except Exception as error:
                primary_error = error
                publish({"success": False, "error": str(error)})
            finally:
                if worker_thread is not None:
                    try:
                        self._native.detach_thread(worker_thread)
                    except Exception as error:
                        if primary_error is None and not primary_outcome:
                            publish(CleanupFailure(error))
                publish(sentinel)
                self._unregister_stream_worker()

        # Python cannot cancel a native call. Daemon workers keep an abandoned
        # call from extending interpreter lifetime after bounded cancellation.
        worker = Thread(target=worker_main, name="dw-streaming-worker", daemon=True)
        self._register_stream_worker(worker)
        try:
            worker.start()
        except Exception:
            self._unregister_stream_worker(worker)
            raise
        metadata = None
        try:
            while True:
                try:
                    item = queue.get(timeout=_WORKER_TIMEOUT_SECONDS)
                except Empty:
                    cancelled.set()
                    worker.join(timeout=_WORKER_JOIN_TIMEOUT_SECONDS)
                    raise DataWeaveError(f"Worker thread timeout after {_WORKER_TIMEOUT_SECONDS} seconds")
                if item is sentinel:
                    break
                if isinstance(item, CleanupFailure):
                    raise item.error
                if isinstance(item, dict):
                    metadata = item
                else:
                    yield item
        finally:
            cancelled.set()
            worker.join(timeout=_WORKER_JOIN_TIMEOUT_SECONDS)
        return parse_streaming_result(metadata or {"success": False, "error": "No metadata received from native call"})

    def run_streaming(self, script: str, inputs: Optional[Dict[str, Any]] = None) -> Stream:
        self._require_initialized(self._native.has_callback_streaming, "callback streaming API (run_script_callback not found)")
        cancelled = Event()
        encoded_inputs = self._inputs_json(inputs)
        stream = Stream(self._stream_worker(lambda thread, write_cb: self._native.run_callback_engine_and_decode(thread, script.encode("utf-8"), encoded_inputs, write_cb), cancelled))
        stream._on_close = cancelled.set
        stream._cancelled = cancelled
        return stream

    @staticmethod
    def _chunk_reader(input_stream: Iterable[bytes]):
        iterator = iter(input_stream)
        state = {"chunk": b"", "offset": 0, "done": False}
        @READ_CALLBACK
        def read_cb(_context, buffer, buffer_size):
            try:
                while True:
                    chunk = state["chunk"]
                    if state["offset"] < len(chunk):
                        size = min(len(chunk) - state["offset"], buffer_size)
                        ctypes.memmove(buffer, chunk[state["offset"]:state["offset"] + size], size)
                        state["offset"] += size
                        return size
                    if state["done"]:
                        return 0
                    with _native_callback_scope():
                        chunk = next(iterator, None)
                    if not chunk:
                        state["done"] = True
                        return 0
                    state["chunk"] = chunk
                    state["offset"] = 0
            except BaseException:
                return -1
        return read_cb

    def run_transform(self, script: str, input_stream: Iterable[bytes], input_name: str = "payload", input_mime_type: str = "application/json", input_charset: Optional[str] = None, inputs: Optional[Dict[str, Any]] = None) -> Stream:
        self._require_initialized(self._native.has_callback_input_output, "callback input/output API (run_script_input_output_callback not found)")
        cancelled = Event()
        read_cb = self._chunk_reader(input_stream)
        encoded_inputs = self._inputs_json(inputs)
        def invoke(thread, write_cb):
            return self._native.run_input_output_callback_engine_and_decode(thread, script.encode("utf-8"), encoded_inputs, input_name.encode("utf-8"), input_mime_type.encode("utf-8"), input_charset.encode("utf-8") if input_charset else None, read_cb, write_cb)
        stream = Stream(self._stream_worker(invoke, cancelled))
        stream._on_close = cancelled.set
        stream._cancelled = cancelled
        return stream

    def run_input_output_callback(self, script: str, input_name: str, input_mime_type: str, read_callback: ReadCallback, write_callback: WriteCallback, input_charset: Optional[str] = None, inputs: Optional[Dict[str, Any]] = None) -> StreamingResult:
        self._require_initialized(self._native.has_callback_input_output, "callback input/output API (run_script_input_output_callback not found)")
        @READ_CALLBACK
        def read_cb(_context, buffer, buffer_size):
            try:
                with _native_callback_scope():
                    data = read_callback(buffer_size)
                if not data:
                    return 0
                if len(data) > buffer_size:
                    return -1
                ctypes.memmove(buffer, data, len(data))
                return len(data)
            except BaseException:
                return -1
        @WRITE_CALLBACK
        def write_cb(_context, buffer, length):
            try:
                data = ctypes.string_at(buffer, length)
                with _native_callback_scope():
                    return int(write_callback(data))
            except BaseException:
                return -1
        try:
            raw = self._native.run_input_output_callback_engine_and_decode(self._native.thread, script.encode("utf-8"), self._inputs_json(inputs), input_name.encode("utf-8"), input_mime_type.encode("utf-8"), input_charset.encode("utf-8") if input_charset else None, read_cb, write_cb)
            return parse_streaming_result(json.loads(raw) if raw else {"success": False, "error": "Empty response"})
        except Exception as error:
            raise DataWeaveError(f"Failed to execute callback input/output streaming: {error}")

    def __enter__(self):
        self.initialize()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        try:
            self.cleanup()
        except Exception:
            if exc_type is None:
                raise
        return False
