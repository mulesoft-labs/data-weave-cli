import ctypes
from contextlib import contextmanager
from dataclasses import dataclass
import os
from pathlib import Path
import sys
from threading import Condition, get_ident, local, Lock
import traceback
from typing import Optional

from .models import DataWeaveError, DataWeaveLibraryNotFoundError, READ_CALLBACK, RESOLVE_MODULE_CALLBACK, WRITE_CALLBACK
from .resolver import ModuleResolver


_ENV_NATIVE_LIB = "DATAWEAVE_NATIVE_LIB"
_native_callback_state = local()


def _raise_if_native_callback_active() -> None:
    if getattr(_native_callback_state, "depth", 0) > 0:
        raise DataWeaveError(
            "DataWeave lifecycle and execution are not allowed from a native callback on the same thread."
        )


@contextmanager
def _native_callback_scope():
    previous = getattr(_native_callback_state, "depth", 0)
    _native_callback_state.depth = previous + 1
    try:
        yield
    finally:
        if previous == 0:
            del _native_callback_state.depth
        else:
            _native_callback_state.depth = previous


class graal_isolate_t(ctypes.Structure):
    pass


class graal_isolatethread_t(ctypes.Structure):
    pass


GraalIsolatePointer = ctypes.POINTER(graal_isolate_t)
GraalIsolateThreadPointer = ctypes.POINTER(graal_isolatethread_t)


# ── Process-wide shared isolate (one per process, N handle-addressed engines) ──
# All mutations happen under _isolate_lock. Invariant: _isolate_ref_count equals
# the number of outstanding ownership/init references (one per live engine across
# all DataWeave instances). A positive count requires a live _isolate; a zero
# count normally means _isolate is None, but may temporarily retain a live one
# pending a teardown retry (_teardown_needed, see _release_isolate /
# _retry_pending_teardown_locked), or leak one for the process lifetime after an
# unrecoverable teardown path (double detach+teardown failure, or the bootstrap
# double failure in _acquire_isolate). The count is proof of outstanding
# ownership, not proof of physical isolate existence.
_isolate_lock = Lock()
_lib = None
_lib_path = None
_isolate = None
_isolate_ref_count = 0
# Set when a final graal_tear_down_isolate (or the attach immediately before
# it) failed. The isolate is still live in that case; teardown must be
# retried -- and must succeed -- before any new isolate is created. Mirrors
# Node's g_teardown_needed retryable-teardown model. Only read/written while
# holding _isolate_lock.
_teardown_needed = False


# Per-engine resolver dispatch. The ctx passed to create_engine_with_resolver is
# a Python-allocated monotonic token (NOT the Java handle) so it is known before
# the engine exists -- no resolve callback can fire for an unregistered ctx.
_resolver_lock_global = Lock()
_resolver_registry = {}          # token(int) -> NativeRuntime
_resolver_token_seq = 0


def _next_resolver_token() -> int:
    global _resolver_token_seq
    with _resolver_lock_global:
        _resolver_token_seq += 1
        return _resolver_token_seq


def _bind_abi(lib) -> None:
    """Binds argtypes/restypes for the engine ABI and lifecycle exports (once)."""
    for name in ("graal_create_isolate", "graal_attach_thread", "graal_detach_thread",
                 "graal_tear_down_isolate", "free_cstring",
                 "create_engine", "create_engine_with_resolver", "destroy_engine",
                 "run_script_engine", "run_script_callback_engine",
                 "run_script_input_output_callback_engine"):
        if not hasattr(lib, name):
            raise DataWeaveError(f"Native library does not export {name}")

    lib.graal_create_isolate.argtypes = [
        ctypes.c_void_p,
        ctypes.POINTER(GraalIsolatePointer),
        ctypes.POINTER(GraalIsolateThreadPointer),
    ]
    lib.graal_create_isolate.restype = ctypes.c_int
    lib.graal_attach_thread.argtypes = [GraalIsolatePointer, ctypes.POINTER(GraalIsolateThreadPointer)]
    lib.graal_attach_thread.restype = ctypes.c_int
    lib.graal_detach_thread.argtypes = [GraalIsolateThreadPointer]
    lib.graal_detach_thread.restype = ctypes.c_int
    lib.graal_tear_down_isolate.argtypes = [GraalIsolateThreadPointer]
    lib.graal_tear_down_isolate.restype = ctypes.c_int
    lib.free_cstring.argtypes = [GraalIsolateThreadPointer, ctypes.c_void_p]
    lib.free_cstring.restype = None

    lib.create_engine.argtypes = [GraalIsolateThreadPointer]
    lib.create_engine.restype = ctypes.c_int64
    lib.create_engine_with_resolver.argtypes = [
        GraalIsolateThreadPointer, RESOLVE_MODULE_CALLBACK, ctypes.c_void_p,
    ]
    lib.create_engine_with_resolver.restype = ctypes.c_int64
    lib.destroy_engine.argtypes = [GraalIsolateThreadPointer, ctypes.c_int64]
    lib.destroy_engine.restype = None
    lib.run_script_engine.argtypes = [
        GraalIsolateThreadPointer, ctypes.c_int64, ctypes.c_char_p, ctypes.c_char_p,
    ]
    lib.run_script_engine.restype = ctypes.c_void_p
    lib.run_script_callback_engine.argtypes = [
        GraalIsolateThreadPointer, ctypes.c_int64, ctypes.c_char_p, ctypes.c_char_p,
        WRITE_CALLBACK, ctypes.c_void_p,
    ]
    lib.run_script_callback_engine.restype = ctypes.c_void_p
    lib.run_script_input_output_callback_engine.argtypes = [
        GraalIsolateThreadPointer, ctypes.c_int64, ctypes.c_char_p, ctypes.c_char_p,
        ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p,
        READ_CALLBACK, WRITE_CALLBACK, ctypes.c_void_p,
    ]
    lib.run_script_input_output_callback_engine.restype = ctypes.c_void_p


def _retry_pending_teardown_locked() -> None:
    """If a prior final teardown failed, retry it now (caller holds _isolate_lock).
    On success, clears the flag and nulls the isolate globals so the caller may
    build fresh. On failure, leaves the isolate live and the flag armed, and
    propagates the failure so the caller does not proceed to build a second,
    racing isolate.

    The retry ALWAYS attaches a FRESH worker on the CURRENT OS thread. GraalVM
    IsolateThread handles are OS-thread-affine, so a thread attached on one OS
    thread must never be reused to tear down from another (review #15 #1). This
    is sound because the only path that arms a retry is the last-release path
    (_release_isolate), which leaves NO thread persistently attached -- the
    bootstrap was detached at create and every op detaches its own thread -- so a
    fresh attach on the current thread is always a valid, sole attachment."""
    global _lib, _lib_path, _isolate, _teardown_needed
    if not _teardown_needed:
        return
    lib, isolate = _lib, _isolate
    worker = GraalIsolateThreadPointer()
    if lib.graal_attach_thread(isolate, ctypes.byref(worker)) != 0:
        raise DataWeaveError("Failed to attach thread to retry isolate teardown")
    try:
        _tear_down(lib, worker)  # raises on failure -> flag stays armed
    except BaseException:
        # Teardown failed again. Detach the fresh worker so it does not stay
        # attached and block the NEXT retry. graal_detach_thread returns a nonzero
        # STATUS on failure (it does not raise), so inspect it.
        detach_failed = False
        try:
            detach_failed = lib.graal_detach_thread(worker) != 0
        except Exception:
            detach_failed = True
        if detach_failed:
            # Double failure: another retry would stack a second stuck worker and
            # block teardown forever. Treat the isolate as UNRECOVERABLE (review
            # #16 #2, leak-and-continue): null the globals, disarm the retry, retain
            # no thread. A later initialize() builds a fresh isolate.
            _lib = _lib_path = _isolate = None
            _teardown_needed = False
            print(
                "DataWeave: GraalVM isolate teardown retry failed and the worker "
                "could not be detached; the isolate is unrecoverable and is leaked "
                "(a later initialize() will build a fresh one).",
                file=sys.stderr,
            )
            raise
        # Detach succeeded: leave the flag armed and the globals intact, and
        # propagate so the caller does not build a second racing isolate.
        raise
    # Success: the isolate (and every thread pointer into it) is now invalid, so
    # the worker must NOT be detached.
    _lib = _lib_path = _isolate = None
    _teardown_needed = False


def _acquire_isolate(lib_path: str):
    """Returns (lib, isolate), creating the shared isolate on the first reference.
    Increments the refcount only on success."""
    global _lib, _lib_path, _isolate, _isolate_ref_count, _teardown_needed
    with _isolate_lock:
        _retry_pending_teardown_locked()
        if _isolate is None:
            try:
                lib = ctypes.CDLL(lib_path)
            except OSError as error:
                raise DataWeaveError(f"Failed to load library from {lib_path}: {error}")
            _bind_abi(lib)
            isolate = GraalIsolatePointer()
            thread = GraalIsolateThreadPointer()
            try:
                result = lib.graal_create_isolate(None, ctypes.byref(isolate), ctypes.byref(thread))
            except Exception as error:
                raise DataWeaveError(f"Failed to create GraalVM isolate: {error}") from error
            if result != 0:
                raise DataWeaveError(f"Failed to create GraalVM isolate. Error code: {result}")
            # Detach the bootstrap thread immediately. A thread left attached to the
            # isolate blocks graal_tear_down_isolate forever when the last release
            # runs on a different OS thread (e.g. the atexit cleanup thread). Every
            # subsequent native call attaches its own thread on demand and detaches
            # when done; teardown attaches a fresh thread. Mirrors the Node/Go bindings.
            detach_result = lib.graal_detach_thread(thread)
            if detach_result != 0:
                # The bootstrap thread could not be detached. The isolate is
                # created but not yet published (globals unset, refcount not
                # bumped), so tear it down here rather than leak an unreachable
                # live isolate. Reuse the same still-attached bootstrap thread to
                # tear down -- valid because this runs on the OS thread that
                # created it (IsolateThread values are OS-thread-affine).
                try:
                    _tear_down(lib, thread)
                except BaseException:
                    # Double failure: the bootstrap thread could be neither
                    # detached nor used to tear the isolate down. Only THIS OS
                    # thread could ever tear this isolate down (GraalVM teardown
                    # needs the sole attached thread, on its own OS thread), and
                    # we are about to return an error with no guarantee this
                    # thread re-enters. Retaining the bootstrap IsolateThread for
                    # a later retry would risk handing an OS-thread-affine pointer
                    # to graal_tear_down_isolate from a DIFFERENT thread (review
                    # #15 #1: wrong-thread fatal path). So treat the isolate as
                    # UNRECOVERABLE: leave the globals unset (this isolate leaks
                    # until process exit) so a later initialize() -- on any thread
                    # -- builds a fresh isolate.
                    print(
                        "DataWeave: bootstrap-thread detach and isolate teardown "
                        "both failed; the isolate is unrecoverable and is leaked "
                        "(a later initialize() will build a fresh one).",
                        file=sys.stderr,
                    )
                raise DataWeaveError(
                    f"Failed to detach GraalVM isolate bootstrap thread. Error code: {detach_result}"
                )
            _lib = lib
            _lib_path = lib_path
            _isolate = isolate
        _isolate_ref_count += 1
        return _lib, _isolate


def _release_isolate() -> None:
    """Decrements the refcount; tears the isolate down on 0. A failed teardown
    (or a failed attach immediately before it) retains the isolate live and
    arms _teardown_needed for a retry at the next acquire, instead of nulling
    the globals -- nulling would let the next initialize() build a second live
    isolate while the first is still alive."""
    global _lib, _lib_path, _isolate, _isolate_ref_count, _teardown_needed
    with _isolate_lock:
        if _isolate_ref_count == 0:
            return
        _isolate_ref_count -= 1
        if _isolate_ref_count > 0:
            return
        # Last release: no thread is persistently attached (the bootstrap was
        # detached at create and every op detaches its own thread), so attach a
        # fresh thread and tear down.
        lib, isolate = _lib, _isolate
        worker = GraalIsolateThreadPointer()
        if lib.graal_attach_thread(isolate, ctypes.byref(worker)) != 0:
            # Cannot even attach to tear down; retain the isolate and arm a retry.
            _teardown_needed = True
            print(
                "DataWeave: could not attach a thread to tear down the GraalVM "
                "isolate; teardown will be retried on the next initialize().",
                file=sys.stderr,
            )
            raise DataWeaveError("Failed to attach thread for isolate teardown")
        try:
            _tear_down(lib, worker)
        except BaseException:
            # Teardown failed. Detach the worker we just attached so it does not
            # stay attached and block a later retry. graal_detach_thread returns a
            # nonzero STATUS on failure (it does not raise), so inspect it: a
            # still-attached worker permanently blocks any future teardown, which
            # needs the SOLE attached thread.
            detach_failed = False
            try:
                detach_failed = lib.graal_detach_thread(worker) != 0
            except Exception:
                detach_failed = True
            if detach_failed:
                # Teardown AND detach both failed. Arming a retry would attach yet
                # another worker on top of the stuck one and block teardown forever,
                # so treat the isolate as UNRECOVERABLE (review #16 #2, leak-and-
                # continue): null the globals, do NOT arm a retry, retain no thread.
                # A later initialize() builds a fresh isolate. Mirrors the bootstrap
                # double-failure policy in _acquire_isolate.
                _lib = _lib_path = _isolate = None
                _teardown_needed = False
                print(
                    "DataWeave: GraalVM isolate teardown failed and the teardown "
                    "worker could not be detached; the isolate is unrecoverable and "
                    "is leaked (a later initialize() will build a fresh one).",
                    file=sys.stderr,
                )
                raise
            # Detach succeeded: keep the isolate live, arm a retry, do NOT null the
            # globals (nulling would let the next initialize() build a second, racing
            # isolate). On the SUCCESS path below the worker is intentionally left
            # undetached -- after _tear_down returns the isolate and worker are gone.
            _teardown_needed = True
            print(
                "DataWeave: GraalVM isolate teardown failed; the isolate is "
                "retained and teardown will be retried on the next initialize().",
                file=sys.stderr,
            )
            raise
        _lib = _lib_path = _isolate = None


def _tear_down(lib, thread) -> None:
    if thread is None:
        return
    result = lib.graal_tear_down_isolate(thread)
    if result != 0:
        raise DataWeaveError(f"Failed to tear down GraalVM isolate. Error code: {result}")


def candidate_library_paths() -> list[Path]:
    paths: list[Path] = []
    env_value = (os.environ.get(_ENV_NATIVE_LIB) or "").strip()
    if env_value:
        paths.append(Path(env_value))

    pkg_dir = Path(__file__).resolve().parent
    native_dir = pkg_dir / "native"
    paths.extend(native_dir / name for name in ("dwlib.dylib", "dwlib.so", "dwlib.dll"))

    for parent in pkg_dir.parents:
        build_dir = parent / "build" / "native" / "nativeCompile"
        if build_dir.exists():
            paths.extend(build_dir / name for name in ("dwlib.dylib", "dwlib.so", "dwlib.dll"))
            break

    paths.extend(Path(name) for name in ("dwlib.dylib", "dwlib.so", "dwlib.dll"))
    return paths


def find_library() -> str:
    for path in candidate_library_paths():
        if path.exists() and path.is_file():
            return str(path)
    raise DataWeaveLibraryNotFoundError(
        "Could not find DataWeave native library (dwlib). "
        f"Set {_ENV_NATIVE_LIB} to an absolute path or install a wheel that bundles the native library."
    )


@dataclass(frozen=True)
class _EngineOperation:
    handle: int
    generation: int


class NativeRuntime:
    """Owns the native library handle, isolate lifecycle, and ctypes ABI."""

    def __init__(self, lib_path: Optional[str] = None):
        self.lib_path = lib_path or find_library()
        self.lib = None
        self.isolate = None
        self.thread = None
        self.handle = 0
        self.initialized = False
        self._generation = 0
        self._engine_operation = None
        # Every engine supports every API now (single unified ABI).
        self.has_callback_streaming = True
        self.has_callback_input_output = True
        self.has_module_resolver = True
        self._resolver = None
        self._resolver_callback = None
        self._resolver_token = 0
        self._resolver_buffers = []
        self._resolver_active = False
        self._resolver_active_ident = None
        self._operation_lock = Condition(Lock())
        self._operation_active = False
        self._execution_owner = None
        # Guards this instance's initialize()/cleanup() lifecycle transitions
        # (the initialized-check -> acquire -> create-engine -> publish
        # sequence, and cleanup()'s initialized-clearing prologue) so two
        # threads calling initialize() on the SAME instance cannot both pass
        # the check, both acquire (refcount over-count), and both create an
        # engine. Never held across a long native execution call -- only
        # instance lifecycle transitions.
        self._init_lock = Lock()

    def initialize(self) -> None:
        _raise_if_native_callback_active()
        with self._serialized_native_operation():
            with self._init_lock:
                if self.initialized:
                    return
                acquired = False
                try:
                    self.lib, self.isolate = _acquire_isolate(self.lib_path)
                    acquired = True
                    handle = self._create_engine()
                except Exception:
                    # Roll back the ref we just took (if any) so a failed init leaks
                    # nothing.
                    self.lib = self.isolate = None
                    # Finding #2: install_resolver() registered a token BEFORE this call.
                    # A failed init must unregister it, or it leaks: self.initialized stays
                    # False, so a later cleanup() returns early and never reaches the pop.
                    if self._resolver_token:
                        with _resolver_lock_global:
                            _resolver_registry.pop(self._resolver_token, None)
                        self._resolver_token = 0
                    # Release the ref only if _acquire_isolate actually incremented it
                    # (a library-load / isolate-create / bootstrap-detach failure inside
                    # _acquire_isolate never increments the refcount, so releasing here
                    # unconditionally would decrement someone else's live reference).
                    if acquired:
                        _release_isolate()
                    raise
                self.handle = handle
                self._generation += 1
                self._engine_operation = _EngineOperation(handle, self._generation)
                self.initialized = True

    def _create_engine(self) -> int:
        with self._current_thread_attachment(self.thread) as thread:
            try:
                if self._resolver is not None:
                    # Pass the bare int token; the declared c_void_p argtype on the
                    # real ABI call converts it automatically. (Wrapping it in
                    # ctypes.c_void_p(...) here would produce an unhashable Python
                    # object, breaking the FakeLibrary-recorded ctx round-trip used
                    # in tests -- and offers no benefit for the real ctypes call.)
                    handle = self.lib.create_engine_with_resolver(
                        thread, self._resolver_callback, self._resolver_token
                    )
                else:
                    handle = self.lib.create_engine(thread)
            except Exception as error:
                raise DataWeaveError(f"Failed to create DataWeave engine: {error}") from error
        if not handle:
            raise DataWeaveError("Native create_engine returned a null handle")
        return handle

    def attach_thread(self):
        _raise_if_native_callback_active()
        worker_thread = GraalIsolateThreadPointer()
        try:
            result = self.lib.graal_attach_thread(self.isolate, ctypes.byref(worker_thread))
        except Exception as error:
            raise DataWeaveError(f"Failed to attach worker thread to isolate: {error}") from error
        if result != 0:
            raise DataWeaveError(f"Failed to attach worker thread to isolate (code {result})")
        return worker_thread

    def detach_thread(self, thread) -> None:
        _raise_if_native_callback_active()
        try:
            result = self.lib.graal_detach_thread(thread)
        except Exception as error:
            raise DataWeaveError(f"Failed to detach worker thread from isolate: {error}") from error
        if result != 0:
            raise DataWeaveError(f"Failed to detach worker thread from isolate. Error code: {result}")

    def decode_and_free(self, ptr, thread=None) -> str:
        if not ptr:
            return ""
        primary_error = None
        try:
            return ctypes.string_at(ptr).decode("utf-8")
        except Exception as error:
            primary_error = error
            raise
        finally:
            try:
                if self.lib is not None:
                    self.lib.free_cstring(thread or self.thread, ptr)
            except Exception:
                if primary_error is None:
                    raise

    def run_engine_and_decode(self, script: bytes, inputs: bytes, operation: _EngineOperation) -> str:
        with self._serialized_native_operation(operation) as operation_lock:
            with self._current_thread_attachment(self.thread) as thread:
                with self._resolver_scope():
                    try:
                        operation_lock.release()
                        return self.decode_and_free(
                            self.lib.run_script_engine(thread, operation.handle, script, inputs),
                            thread,
                        )
                    finally:
                        operation_lock.acquire()

    def run_callback_engine_and_decode(
        self, thread, script: bytes, inputs: bytes, write_callback, operation: _EngineOperation,
    ) -> str:
        with self._serialized_native_operation(operation) as operation_lock:
            with self._current_thread_attachment(thread) as current:
                try:
                    operation_lock.release()
                    return self.decode_and_free(
                        self.lib.run_script_callback_engine(
                            current, operation.handle, script, inputs, write_callback, None
                        ),
                        current,
                    )
                finally:
                    operation_lock.acquire()

    def run_input_output_callback_engine_and_decode(
        self, thread, script: bytes, inputs: bytes, input_name: bytes,
        input_mime_type: bytes, input_charset: Optional[bytes], read_callback,
        write_callback, operation: _EngineOperation,
    ) -> str:
        with self._serialized_native_operation(operation) as operation_lock:
            with self._current_thread_attachment(thread) as current:
                try:
                    operation_lock.release()
                    return self.decode_and_free(
                        self.lib.run_script_input_output_callback_engine(
                            current, operation.handle, script, inputs, input_name,
                            input_mime_type, input_charset, read_callback, write_callback, None,
                        ),
                        current,
                    )
                finally:
                    operation_lock.acquire()

    def install_resolver(self, resolver: ModuleResolver) -> None:
        """Binds a module resolver to this engine. Must be called before initialize()."""
        _raise_if_native_callback_active()
        if self.initialized:
            raise DataWeaveError("Cannot install a resolver after initialize().")
        self._resolver = resolver
        self._resolver_token = _next_resolver_token()
        self._resolver_callback = self._make_trampoline()
        with _resolver_lock_global:
            _resolver_registry[self._resolver_token] = self

    def _make_trampoline(self):
        token = self._resolver_token
        def resolve(_thread, _ctx, module_path):
            try:
                entry = _resolver_registry.get(token)
                if entry is None:
                    return None
                # Fail-closed guard: resolve only during a synchronous run on the
                # thread that installed the scope. Streaming workers run on other
                # threads and never enter the scope -> return None without calling
                # the Python resolver (preserves calls == calls_after_install).
                if not entry._resolver_active or get_ident() != entry._resolver_active_ident:
                    return None
                path = module_path.decode("utf-8")
                if path.startswith("/"):
                    path = path[1:]
                with _native_callback_scope():
                    source = entry._resolver(path)
                if not isinstance(source, str):
                    return None
                buffer = ctypes.create_string_buffer(source.encode("utf-8"))
                entry._resolver_buffers.append(buffer)
                return ctypes.addressof(buffer)
            except BaseException:
                try:
                    if os.environ.get("DATAWEAVE_RESOLVER_DEBUG") == "1":
                        traceback.print_exc()
                    else:
                        print("DataWeave module resolver callback failed.", file=sys.stderr)
                except BaseException:
                    pass
                return None
        return RESOLVE_MODULE_CALLBACK(resolve)

    @contextmanager
    def _resolver_scope(self):
        if self._resolver is None:
            yield
            return
        self._resolver_buffers = []
        self._resolver_active = True
        self._resolver_active_ident = get_ident()
        try:
            yield
        finally:
            self._resolver_active = False
            self._resolver_active_ident = None
            self._resolver_buffers = []

    def cleanup(self) -> None:
        _raise_if_native_callback_active()
        with self._serialized_native_operation():
            # _init_lock is nested INSIDE _operation_lock in both initialize()
            # and cleanup(), so lifecycle transitions use one lock order. Only
            # the initialized/token clearing needs _init_lock; destroy/release
            # stays guarded by _serialized_native_operation as before.
            # Mirrors _serialized_native_operation's own hasattr guard below:
            # some unit tests build a NativeRuntime via __new__ and set
            # attributes directly, bypassing __init__.
            if not hasattr(self, "_init_lock"):
                self._init_lock = Lock()
            with self._init_lock:
                if not self.initialized:
                    return
                operation = self._engine_operation
                self.initialized = False
                self._engine_operation = None
            try:
                if operation is not None:
                    with self._current_thread_attachment(self.thread) as thread:
                        self.lib.destroy_engine(thread, operation.handle)
            finally:
                # Release the isolate ref even if destroy_engine throws, so a
                # throwing destroy cannot strand the isolate.
                self.lib = self.isolate = self.thread = None
                self._resolver = None
                self._resolver_callback = None
                self._resolver_buffers = []
                self._resolver_active = False
                self._resolver_active_ident = None
                if self._resolver_token:
                    with _resolver_lock_global:
                        _resolver_registry.pop(self._resolver_token, None)
                    self._resolver_token = 0
                _release_isolate()

    @contextmanager
    def _serialized_native_operation(self, expected: Optional[_EngineOperation] = None):
        _raise_if_native_callback_active()
        owner = get_ident()
        if getattr(self, "_execution_owner", None) == owner:
            raise DataWeaveError("Reentrant DataWeave execution is not supported.")
        if not hasattr(self, "_operation_lock"):
            self._operation_lock = Condition(Lock())
            self._operation_active = False
        with self._operation_lock:
            while getattr(self, "_operation_active", False):
                self._operation_lock.wait()
            if expected is not None:
                self._validate_operation_locked(expected)
            self._operation_active = True
            self._execution_owner = owner
            try:
                yield self._operation_lock
            finally:
                self._execution_owner = None
                self._operation_active = False
                self._operation_lock.notify()

    def _validate_operation_locked(self, expected: _EngineOperation) -> None:
        if self._engine_operation != expected:
            raise DataWeaveError("DataWeave operation belongs to a stale engine generation.")

    def validate_operation(self, expected: _EngineOperation) -> None:
        _raise_if_native_callback_active()
        if not hasattr(self, "_operation_lock"):
            self._operation_lock = Condition(Lock())
            self._operation_active = False
        with self._operation_lock:
            self._validate_operation_locked(expected)

    def capture_operation(self) -> _EngineOperation:
        _raise_if_native_callback_active()
        if not hasattr(self, "_operation_lock"):
            self._operation_lock = Condition(Lock())
            self._operation_active = False
        with self._operation_lock:
            operation = self._engine_operation
            if not self.initialized or operation is None:
                raise DataWeaveError("DataWeave runtime not initialized. Call initialize() first.")
            return operation

    @contextmanager
    def _current_thread_attachment(self, thread):
        # A non-None thread is one the caller already attached (a streaming worker
        # passes its own); use it as-is. Otherwise (self.thread is None for every
        # synchronous call) attach a fresh thread on demand and detach when done --
        # no thread is persistently attached, so cross-thread teardown never blocks.
        if thread is not None:
            yield thread
            return
        attached_thread = self.attach_thread()
        primary_error = None
        try:
            yield attached_thread
        except BaseException as error:
            primary_error = error
            raise
        finally:
            try:
                self.detach_thread(attached_thread)
            except Exception:
                if primary_error is None:
                    raise
