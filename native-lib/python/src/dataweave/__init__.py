"""Public facade for the DataWeave Python native binding."""

import ctypes
import threading

from typing import Any, Dict, Iterable, Optional

from .encoding import normalize_input_value as _normalize_input_value
from .encoding import parse_native_encoded_response as _parse_native_encoded_response
from .encoding import parse_streaming_result as _parse_streaming_result
from .models import (
    READ_CALLBACK,
    RESOLVE_MODULE_CALLBACK,
    WRITE_CALLBACK,
    DataWeaveError,
    DataWeaveLibraryNotFoundError,
    DataWeaveScriptError,
    ExecutionResult,
    InputValue,
    ReadCallback,
    Stream,
    StreamingResult,
    WriteCallback,
)
from .native import candidate_library_paths as _candidate_library_paths
from .native import find_library as _find_library
from .native import _raise_if_native_callback_active
from .resolver import (
    ModuleResolver,
    compose_resolvers,
    modules_from_directory,
    modules_from_jars,
    modules_from_map,
)
from .runtime import DataWeave


_global_instance: Optional[DataWeave] = None
_global_lock = threading.Lock()


def _get_global_instance() -> DataWeave:
    global _global_instance
    _raise_if_native_callback_active()
    with _global_lock:
        if _global_instance is None:
            import atexit
            candidate = DataWeave()
            candidate.initialize()
            _global_instance = candidate
            atexit.register(cleanup)
        return _global_instance


def run(script: str, inputs: Optional[Dict[str, Any]] = None, raise_on_error: bool = False) -> ExecutionResult:
    return _get_global_instance().run(script, inputs, raise_on_error=raise_on_error)


def run_streaming(script: str, inputs: Optional[Dict[str, Any]] = None) -> Stream:
    return _get_global_instance().run_streaming(script, inputs)


def run_callback(script: str, write_callback: WriteCallback, inputs: Optional[Dict[str, Any]] = None) -> StreamingResult:
    return _get_global_instance().run_callback(script, write_callback, inputs)


def run_transform(script: str, input_stream: Iterable[bytes], input_name: str = "payload", input_mime_type: str = "application/json", input_charset: Optional[str] = None, inputs: Optional[Dict[str, Any]] = None) -> Stream:
    return _get_global_instance().run_transform(script, input_stream, input_name, input_mime_type, input_charset, inputs)


def run_input_output_callback(script: str, input_name: str, input_mime_type: str, read_callback: ReadCallback, write_callback: WriteCallback, input_charset: Optional[str] = None, inputs: Optional[Dict[str, Any]] = None) -> StreamingResult:
    return _get_global_instance().run_input_output_callback(script, input_name, input_mime_type, read_callback, write_callback, input_charset, inputs)


def cleanup() -> None:
    global _global_instance
    _raise_if_native_callback_active()
    with _global_lock:
        if _global_instance is not None:
            instance, _global_instance = _global_instance, None
        else:
            return
    instance.cleanup()


__all__ = [
    "DataWeave", "DataWeaveError", "DataWeaveLibraryNotFoundError", "DataWeaveScriptError",
    "ExecutionResult", "InputValue", "ReadCallback", "Stream", "StreamingResult", "WriteCallback",
    "READ_CALLBACK", "RESOLVE_MODULE_CALLBACK", "WRITE_CALLBACK", "run", "run_callback", "run_input_output_callback",
    "run_streaming", "run_transform", "cleanup", "ModuleResolver", "compose_resolvers",
    "modules_from_directory", "modules_from_jars", "modules_from_map",
]
