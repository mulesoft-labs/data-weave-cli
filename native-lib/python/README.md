# DataWeave Python Bindings

Python FFI bindings for the DataWeave native library.

## Prerequisites

1. Build the native library:
   ```bash
   ./gradlew :native-lib:nativeCompile
   ```

2. The shared library will be at:
   - macOS: `native-lib/build/native/nativeCompile/dwlib.dylib`
   - Linux: `native-lib/build/native/nativeCompile/dwlib.so`
   - Windows: `native-lib/build/native/nativeCompile/dwlib.dll`

## Installation

### Option A: Install the wheel (recommended)

After building:

```bash
./gradlew :native-lib:buildPythonWheel
python3 -m pip install native-lib/python/dist/dataweave_native-0.0.1-*.whl
```

### Option B: Editable install for development

```bash
./gradlew :native-lib:stagePythonNativeLib
python3 -m pip install -e native-lib/python
```

### Test dependencies

Install the pytest test extra before running the Python test lanes locally:

```bash
cd native-lib/python
python3 -m pip install '.[test]'
```

### Option C: Use externally-built library via environment variable

```bash
export DATAWEAVE_NATIVE_LIB=/path/to/dwlib.dylib
python3 -m pip install -e native-lib/python
```

## Usage

### Basic Script Execution

```python
import dataweave

result = dataweave.run("2 + 2")
if result.success:
    print(result.get_string())  # "4"
else:
    print(f"Error: {result.error}")
```

### Script with Inputs

Inputs can be plain Python values (auto-encoded):

```python
result = dataweave.run(
    "num1 + num2",
    {"num1": 25, "num2": 17}
)
print(result.get_string())  # "42"
```

### Script with Explicit Input Configuration

Use a dict with `content`, `mimeType`, and `properties` for full control:

```python
xml_bytes = b"<?xml version='1.0'?><person><name>Alice</name></person>"

result = dataweave.run(
    "payload.person.name",
    {
        "payload": {
            "content": xml_bytes,
            "mimeType": "application/xml",
            "charset": "UTF-8",
            "properties": {
                "nullValueOn": "empty"
            }
        }
    }
)
print(result.get_string())  # '"Alice"'
```

Or use the `InputValue` helper:

```python
input_value = dataweave.InputValue(
    content="123,456,789",
    mime_type="application/csv",
    properties={"header": False, "separator": ","}
)

result = dataweave.run("payload.column_1[0]", {"payload": input_value})
print(result.get_string())  # '"123"'
```

### Context Manager (Explicit Lifecycle)

The module-level API uses a shared singleton. Use `DataWeave` directly for explicit control:

```python
with dataweave.DataWeave() as dw:
    r1 = dw.run("2 + 2")
    r2 = dw.run("x + y", {"x": 10, "y": 32})
    
    print(r1.get_string())  # "4"
    print(r2.get_string())  # "42"
```

### External DataWeave Modules

Custom module resolution is available to synchronous `DataWeave.run()` calls on
an explicit `DataWeave` instance. Resolver keys use `/` separators and include
the `.dwl` suffix; for example, the DataWeave import `org::company::lib` requests
the module key `org/company/lib.dwl` without a leading path separator.

```python
from dataweave import DataWeave, modules_from_map

resolver = modules_from_map({
    "org/company/lib.dwl": '%dw 2.0\nfun greet(name) = "Hello " ++ name',
})

with DataWeave(resolve_module=resolver) as dw:
    result = dw.run("""
        %dw 2.0
        import org::company::lib
        output application/json
        ---
        lib::greet("World")
    """)

assert result.get_string() == '"Hello World"'
```

The `ModuleResolver` contract is a synchronous callable from a module key to
the module source string or `None`. Custom resolver configuration is available
only on an explicit `DataWeave` instance; the module-level `dataweave.run()`
singleton does not accept `resolve_module`. `run_streaming()`,
`run_transform()`, and the low-level callback streaming API do not use custom
resolvers and can import only built-in modules.

Use `modules_from_directory()` for a source tree:

```python
from dataweave import DataWeave, modules_from_directory

with DataWeave(resolve_module=modules_from_directory("modules")) as dw:
    result = dw.run(script)
```

Use `modules_from_jars()` to load `.dwl` entries from one or more JAR or ZIP
archives. Later archives replace duplicate module keys from earlier archives:

```python
from dataweave import DataWeave, modules_from_jars

resolver = modules_from_jars(["base-modules.jar", "application-modules.jar"])
with DataWeave(resolve_module=resolver) as dw:
    result = dw.run(script)
```

Use `compose_resolvers()` for ordered fallback. The first resolver returning a
source wins:

```python
from dataweave import (
    DataWeave,
    compose_resolvers,
    modules_from_directory,
    modules_from_jars,
    modules_from_map,
)

resolver = compose_resolvers(
    modules_from_map({"org/company/config.dwl": config_source}),
    modules_from_directory("modules"),
    modules_from_jars(["dependencies.jar"]),
)
with DataWeave(resolve_module=resolver) as dw:
    result = dw.run(script)
```

Resolvers execute arbitrary Python with the application's process permissions.
Use only trusted resolver functions and trusted module sources. By default,
callback failures write a fixed, content-free diagnostic to stderr so module
source, credentials, and local paths are not exposed. Set
`DATAWEAVE_RESOLVER_DEBUG=1` only in a trusted debugging environment to include
the exception type, message, and traceback.

There is a single process-wide GraalVM isolate, reference-counted by the
number of live engines across all `DataWeave` instances; it is created on the
first engine and torn down when the last one is released. If that final
teardown fails but the worker thread detaches cleanly, the live isolate is
retained and teardown is retried on the next initialization; if teardown and
detachment both fail (or the bootstrap attach path hits a detach-plus-teardown
double failure), the isolate is unrecoverable — the binding clears its module
state, leaks the isolate for the process lifetime, and a later initialization
builds a fresh one. Each `DataWeave` instance
owns its own handle-addressed engine within that shared isolate.
Initialization binds the resolver to that engine — `DataWeave.initialize()`
creates the engine via `create_engine_with_resolver`, so the resolver is
installed once, up front, not on first use. Subsequent runs reuse the same
resolver and its compiled-module cache. The instance retains the resolver
callback until its engine is destroyed, then releases callback references
during `cleanup()`. Different live instances can therefore use different
resolvers.

### Callback and stream lifecycle

Resolver, read, and write callbacks must not call DataWeave lifecycle or
execution APIs on the same thread. The binding rejects that reentry with the
public `DataWeaveError` message `DataWeave lifecycle and execution are not
allowed from a native callback on the same thread.` rather than recursively
entering the native runtime.

Streaming and transform work captures its initialized `{handle, generation}`
operation identity. Cleanup or reinitialization before a captured operation is
consumed or registered rejects it with `DataWeaveError: DataWeave operation
belongs to a stale engine generation.`; it never runs on the replacement
engine. Cleanup differs from Node: it refuses with `DataWeaveError` while an
active streaming worker is attached. Safe worker registration validates the
captured operation while holding the worker registry lock, so cleanup cannot
admit work for an old generation.

### Custom module resolution scope

- A `resolve_module` you configure applies to `run()`.
- Built-in modules (e.g. `dw::core::*`) resolve everywhere — `run()`, `run_streaming()`, `run_transform()`, and the low-level callback APIs.
- Custom modules do **not** resolve inside `run_streaming()`, `run_transform()`, or the low-level callback APIs: those execute on a background thread that must not call back into your resolver, so such a script fails closed (reports the module as not found) rather than making an unsafe cross-thread call. If you need a custom module in a streamed/transform/callback script, resolve it via `run()` instead, or inline the module into the script.

### Error Handling

**Option A: Use `raise_on_error=True` (recommended)**

```python
try:
    result = dataweave.run("invalid syntax", raise_on_error=True)
    print(result.get_string())

except dataweave.DataWeaveScriptError as e:
    print(f"Script error: {e.result.error}")

except dataweave.DataWeaveLibraryNotFoundError:
    print("Native library not found. Build it first: ./gradlew :native-lib:nativeCompile")
    raise
```

**Option B: Check `result.success` manually**

```python
result = dataweave.run("invalid syntax")

if not result.success:
    print(f"Error: {result.error}")
else:
    print(result.get_string())
```

### Output Streaming

Stream output chunks as they're produced, without buffering the entire result:

```python
import sys

stream = dataweave.run_streaming("output application/json --- (1 to 10000) map {id: $}")
with stream:
    for chunk in stream:
        sys.stdout.buffer.write(chunk)

metadata = stream.metadata
print(f"\nDone: {metadata.mime_type}, {metadata.charset}")
```

Call `stream.close()` when stopping consumption early. `Stream` also supports a
context manager, as above. Closing requests cancellation and waits only briefly
for the daemon worker. A native call cannot be forcibly cancelled by Python;
`DataWeave.cleanup()` instead refuses while an active streaming worker remains
attached, preventing destruction of its engine until the worker unregisters.

Or with explicit context:

```python
with dataweave.DataWeave() as dw:
    stream = dw.run_streaming("output application/csv --- payload", {"payload": [1, 2, 3]})
    output = b"".join(stream)
    print(output.decode('utf-8'))
```

### Bidirectional Streaming (Input + Output)

Stream both input and output with bounded Python-side queueing. Memory usage is
bounded by the queue capacity plus the input and output chunk sizes; DataWeave
itself can still buffer while parsing or evaluating a transform.

```python
# Stream a file through DataWeave
with open("large.json", "rb") as f:
    stream = dataweave.run_transform(
        "output application/csv --- payload",
        input_stream=iter(lambda: f.read(8192), b""),
        input_mime_type="application/json",
    )
    
    with open("output.csv", "wb") as out:
        for chunk in stream:
            out.write(chunk)
    
    metadata = stream.metadata
    print(f"Converted: {metadata.mime_type}")
```

Works with any iterable:

```python
# From an in-memory list
stream = dataweave.run_transform(
    "output application/json --- payload map ($ * $)",
    input_stream=[b"[1,2,3,4,5]"],
    input_mime_type="application/json",
)
print(b"".join(stream))  # b'[1,4,9,16,25]'
```

```python
# From a generator
def read_from_network(sock):
    while chunk := sock.recv(4096):
        yield chunk

stream = dataweave.run_transform(
    "output application/json --- sizeOf(payload)",
    input_stream=read_from_network(conn),
    input_mime_type="application/json",
)
for chunk in stream:
    process(chunk)
```

### Low-Level Callback API

For direct callback control (advanced use cases):

```python
json_input = b'[1,2,3,4,5]'
pos = 0

def read_cb(buf_size):
    global pos
    chunk = json_input[pos:pos + buf_size]
    pos += len(chunk)
    return chunk  # return b"" when done

chunks = []
def write_cb(data):
    chunks.append(data)
    return 0  # 0 = success

result = dataweave.run_input_output_callback(
    "output application/json deferred=true --- payload map ($ * $)",
    input_name="payload",
    input_mime_type="application/json",
    read_callback=read_cb,
    write_callback=write_cb,
)

print(result)            # StreamingResult(success=True, ...)
print(b"".join(chunks))  # b'[1,4,9,16,25]'
```

Read callbacks return bytes and are called with the native buffer size. Return
`b""` for EOF; an exception is translated to `-1`, which aborts the native
operation. Write callbacks return `0` on success. Any nonzero return value, or
an exception, aborts the operation and returns unsuccessful `StreamingResult`
metadata rather than unwinding a Python exception through the native callback.
Low-level read callbacks must return no more than the requested buffer size;
oversized callback data is rejected with `-1` rather than silently truncated.

## Running Tests

```bash
cd native-lib/python
python3 -m pip install '.[test]'
python3 -m pytest -m "unit or integration" -v
```

Or via Gradle:

```bash
./gradlew :native-lib:pythonTest
```

`pytest.ini` registers `unit`, `integration`, and `tck` markers. Normal pytest
runs exclude `tck`; use `-m "unit or integration"` to run the lanes used by
`pythonTest`.

To stage and run the Python conformance suite, use:

```bash
./gradlew :native-lib:stageTckSuites :native-lib:pythonTck
```

`pythonTck` is intentionally separate from normal testing and runs only in the
master-only CI lane. It reuses the corpus and shared module fixture staged for
Node TCK. The fixture resolver recovered six import scenarios. The
`runtime/module-singleton-out.json` exclusion remains because the shared fixture
does not contain its three singleton modules. All 17 cases that bundle adjacent
`.dwl` files beside `transform.dwl` remain structural skips rather than active
exclusions.

The observed conformance accounting is 729 selected scenarios, 193 structural
skips, 17 structural module cases, 679 executed passes, 31 active exclusions,
19 strict xfails, 0 failures, and 0 unaccounted scenarios. Active exclusions
cover only directly observed binding or environment capability gaps, including
unavailable modules, Java modules, and classpath test resources. Accepted
runtime/output baseline mismatches are strict xfails: a new mismatch fails the
lane and a repaired baseline mismatch XPASSes and also fails. The deferred-writer
TCK scenario runs in a subprocess because that runtime's isolate teardown may
block; the main TCK session runtime is always cleaned up.

## Running Examples

```bash
python3 native-lib/python/examples/simple_demo.py
python3 native-lib/python/examples/streaming_demo.py
```

## API Reference

### Module-Level Functions

#### `run(script, inputs=None, raise_on_error=False) -> ExecutionResult`

Execute a DataWeave script with the given inputs.

**Parameters:**
- `script` (str): DataWeave script source code
- `inputs` (dict, optional): Map of binding names to values (auto-encoded)
- `raise_on_error` (bool): If True, raises `DataWeaveScriptError` on script errors

**Returns:** `ExecutionResult` with output and metadata

#### `run_streaming(script, inputs=None) -> Stream`

Execute a script and stream the output.

**Returns:** `Stream` iterator yielding chunks, with `.metadata` attribute

#### `run_transform(script, input_stream, input_name="payload", input_mime_type="application/json", input_charset=None, inputs=None) -> Stream`

Execute a script with streaming input and output.

**Parameters:**
- `input_stream`: Iterable of bytes (file, generator, list)
- `input_mime_type`: MIME type of the input stream
- `input_charset`: Optional charset for the streamed input
- `inputs`: Optional additional DataWeave input bindings

**Returns:** `Stream` iterator yielding output chunks

#### `run_input_output_callback(script, input_name, input_mime_type, read_callback, write_callback, input_charset=None, inputs=None) -> StreamingResult`

Low-level callback API for advanced use cases.

**Returns:** `StreamingResult` with success/error/metadata

### `DataWeave` Class

#### `DataWeave(lib_path=None, *, resolve_module=None)`

Context manager for explicit lifecycle control. `resolve_module` accepts a
synchronous `ModuleResolver` for `run()` calls.

**Methods:**
- `run(...)` - Same as module-level `run()`
- `run_streaming(...)` - Same as module-level `run_streaming()`
- `run_transform(...)` - Same as module-level `run_transform()`
- `run_input_output_callback(...)` - Same as module-level API

**Usage:**
```python
with DataWeave() as dw:
    result = dw.run("2 + 2")
```

### Module Resolvers

- `ModuleResolver` - Synchronous callable receiving a module key and returning
  source text or `None`
- `modules_from_map` - Copy a mapping and resolve exact module keys
- `modules_from_directory` - Resolve UTF-8 `.dwl` files beneath a
  traversal-protected directory root
- `modules_from_jars` - Load `.dwl` entries from JAR or ZIP archives
  in caller order
- `compose_resolvers` - Return the first non-`None` resolver result

### `ExecutionResult`

```python
class ExecutionResult:
    success: bool
    result: str              # Base64-encoded output
    error: Optional[str]     # Error message if !success
    binary: bool
    mime_type: str
    charset: str
```

**Methods:**
- `get_bytes() -> bytes` - Decode result to bytes
- `get_string() -> str` - Decode result to UTF-8 string

### `Stream`

Iterator that yields output chunks through a bounded queue.

**Attributes:**
- `metadata: StreamingResult` - Available only after the iterator completes;
  check `success` and `error` after consuming the stream

**Methods:**
- `close() -> None` - Stop consuming early and request bounded worker cleanup

### `StreamingResult`

```python
class StreamingResult:
    success: bool
    error: Optional[str]
    mime_type: str
    charset: str
    binary: bool
```

### `InputValue`

```python
class InputValue:
    def __init__(self, content, mime_type="text/plain", charset=None, properties=None):
        ...
```

Helper for constructing explicit input values.

### Exceptions

- `DataWeaveError` - Base exception for FFI-level errors
- `DataWeaveLibraryNotFoundError` - Native library not found
- `DataWeaveScriptError` - Script compilation or runtime error (subclass of `DataWeaveError`)
  - Has `.result` attribute with the full `ExecutionResult`

## Error Handling Guide

### FFI-Level Errors vs Script Errors

**FFI-level errors** occur before script execution:
- Library not found (`DataWeaveLibraryNotFoundError`)
- Input marshaling failure
- Isolate creation failure

These raise exceptions immediately.

**Script errors** occur during DataWeave execution:
- Syntax errors
- Runtime exceptions
- Type errors

These set `result.success = False` and populate `result.error`.

### Recommended Pattern

```python
try:
    result = dataweave.run(user_script, user_inputs, raise_on_error=True)
    output = result.get_string()
    # Process output...

except dataweave.DataWeaveScriptError as e:
    # Handle script error
    log_error(f"Script failed: {e.result.error}")

except dataweave.DataWeaveLibraryNotFoundError:
    # Handle missing library
    print("Build the native library first: ./gradlew :native-lib:nativeCompile")
    raise

except dataweave.DataWeaveError as e:
    # Handle other FFI errors
    log_error(f"FFI error: {e}")
    raise
```

## Troubleshooting

### "Library not found" Error

**Symptom**: `DataWeaveLibraryNotFoundError: Could not find native library`

**Solutions:**

1. **Build the library first:**
   ```bash
   ./gradlew :native-lib:nativeCompile
   ```

2. **Set environment variable:**
   ```bash
   export DATAWEAVE_NATIVE_LIB=/path/to/native-lib/build/native/nativeCompile/dwlib.dylib
   ```

3. **For wheel installation**, the library is bundled - no environment variable needed

### Import Error

**Symptom**: `ModuleNotFoundError: No module named 'dataweave'`

**Solution**: Install the package:
```bash
python3 -m pip install -e native-lib/python
```

### Streaming Errors

**Symptom**: Script fails mid-stream

**Solution**: Streaming errors appear in `stream.metadata` after iteration:
```python
stream = dataweave.run_streaming(script, inputs)
for chunk in stream:
    process(chunk)

if not stream.metadata.success:
    print(f"Error: {stream.metadata.error}")
```

## Performance Considerations

- **Buffered execution** (`run()`) - Best for small outputs (<1MB)
- **Output streaming** (`run_streaming()`) - Use for large outputs (>10MB)
- **Bidirectional streaming** (`run_transform()`) - Use for large inputs AND outputs
- **Memory usage**: Streaming uses a bounded queue and native callback-sized
  chunks. It avoids accumulating output in the Python binding, but does not
  guarantee fixed or constant memory for the DataWeave runtime or transform.

## Environment Variables

- `DATAWEAVE_NATIVE_LIB` - Path to `dwlib.{dylib,so,dll}` (if not using wheel)
- `DW_HOME` - DataWeave home directory (default: `~/.dw`)
- `DW_DEFAULT_INPUT_MIMETYPE` - Default input MIME type (default: `application/json`)
- `DW_DEFAULT_OUTPUT_MIMETYPE` - Default output MIME type (default: `application/json`)
- `DATAWEAVE_RESOLVER_DEBUG` - Set to `1` to include resolver exception details
  in callback diagnostics; use only in trusted debugging environments

## See Also

- [Node.js bindings](../node/README.md) - Node.js FFI bindings
- [Main README](../README.md) - Native library overview
- [DataWeave Documentation](https://docs.mulesoft.com/dataweave/latest/) - Language reference
