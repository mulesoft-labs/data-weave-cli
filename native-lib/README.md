# native-lib

## Overview

`native-lib` builds a **GraalVM native shared library** that embeds the MuleSoft **DataWeave runtime** and exposes a small C-compatible API.

The main purpose is to allow non-JVM consumers (most notably the Python package in `native-lib/python`) to execute DataWeave scripts **without running a JVM**, while still using the official DataWeave runtime.

## Architecture (GraalVM + FFI)

```
┌─────────────────────────────────────────────┐
│              Python Process                 │
│                                             │
│  ┌────────────────────────────────────────┐ │
│  │  Application Script                    │ │
│  │  - Python: ctypes                      │ │
│  └──────────────┬─────────────────────────┘ │
│                 │                           │
│                 │ FFI Call                  │
│                 ▼                           │
│  ┌────────────────────────────────────────┐ │
│  │  Native Shared Library (dwlib)         │ │
│  │  ┌──────────────────────────────────┐  │ │
│  │  │  GraalVM Isolate (process-wide)  │  │ │
│  │  │  - create_engine /                │  │ │
│  │  │    create_engine_with_resolver    │  │ │
│  │  │  - run_script_engine /            │  │ │
│  │  │    run_script_callback_engine /   │  │ │
│  │  │    run_script_input_output_       │  │ │
│  │  │    callback_engine                │  │ │
│  │  │  - destroy_engine                 │  │ │
│  │  │  - DataWeave script execution    │  │ │
│  │  └──────────────────────────────────┘  │ │
│  └────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

Each engine is a handle-addressed object created with `create_engine` (or
`create_engine_with_resolver`, which additionally registers a module-resolve
callback) and run via `run_script_engine`, `run_script_callback_engine`, or
`run_script_input_output_callback_engine`, then released with `destroy_engine`.

**Raw C ABI (caller-managed isolate).** At the C level the isolate lifecycle is
the caller's responsibility. A direct consumer creates and attaches the GraalVM
isolate itself via `graal_create_isolate` / `graal_attach_thread`, creates and
destroys any number of engines within it (`create_engine` /
`create_engine_with_resolver` … `destroy_engine`), and tears the isolate down
with `graal_tear_down_isolate` when done. `destroy_engine` only unregisters that
engine from the runtime; it never tears down the isolate. There is no built-in
reference counting at the ABI — the C consumer decides when the isolate is no
longer needed.

**Node / Python bindings (reference-counted isolate).** The bindings layer this
policy on top of the raw ABI: each maintains a single process-wide GraalVM
isolate, reference-counted by the number of live engines across all instances.
The isolate is created and attached on first use and torn down via
`graal_tear_down_isolate` only after the final engine in the process has been
released. Teardown failure has two contracts. An **ordinary** failure (teardown
fails but the worker thread detaches cleanly) retains the live isolate and
retries teardown later, with a binding-specific trigger: **Node** retries at the
next initialization or when an in-flight operation finishes draining (async
op-completion); **Python** retries synchronously at the next initialization. A
**teardown-plus-detach double failure** (in Python, also a bootstrap
detach-plus-teardown double failure) is treated as **unrecoverable** — the
binding resets its published state, emits a diagnostic, deliberately leaks the
isolate for the process lifetime, and lets a future initialization build a fresh
isolate. This ref-counting and teardown policy lives in the binding code, not in
the dwlib engine ABI.

## Raw engine ABI contract

The exported engine entrypoints are `create_engine`,
`create_engine_with_resolver`, `destroy_engine`, `run_script_engine`,
`run_script_callback_engine`, and `run_script_input_output_callback_engine`.
`create_engine` and `create_engine_with_resolver` return `0` when their Java C
entrypoint fails. The three `run_*_engine` entrypoints return `NULL` when their
Java C entrypoint fails; callers must not pass `NULL` to `free_cstring`. Those
sentinels are distinct from normal DataWeave failures: a script error is a
non-`NULL` JSON envelope with `success:false`, and that allocated result must be
freed normally.

`destroy_engine` closes admission for the handle and blocks until operations
already admitted to that engine drain. Resolver, read, and write callback
contexts must remain valid until `destroy_engine` returns. A callback must not
call `destroy_engine` synchronously for the engine invoking it: that operation
holds an admitted lease and would wait for itself to finish.

## Building with Gradle

### Prerequisites

- A GraalVM distribution installed that includes `native-image`.
- Enough memory for native-image (this build config uses `-J-Xmx6G`).

### Build the shared library

From the repository root:

```bash
./gradlew :native-lib:nativeCompile
```

The shared library is produced under:

- `native-lib/build/native/nativeCompile/`

and is named:

- macOS: `dwlib.dylib`
- Linux: `dwlib.so`
- Windows: `dwlib.dll`

### Stage the library into the Python package (dev workflow)

```bash
./gradlew :native-lib:stagePythonNativeLib
```

This copies `dwlib.*` into:

- `native-lib/python/src/dataweave/native/`

### Build a Python wheel (bundles the native library)

```bash
./gradlew :native-lib:buildPythonWheel
```

The wheel will be created in:

- `native-lib/python/dist/`

## Installing for use in a Python project

### Option A: Install the produced wheel (recommended)

After `:native-lib:buildPythonWheel`:

```bash
python3 -m pip install native-lib/python/dist/dataweave_native-0.0.1-*.whl
```

This wheel includes the `dwlib.*` shared library inside the Python package.

### Option B: Editable install for development

1. Stage the native library:

```bash
./gradlew :native-lib:stagePythonNativeLib
```

2. Install the Python package in editable mode:

```bash
python3 -m pip install -e native-lib/python
```

### Option C: Use an externally-built library via an environment variable

If you want to point Python at a specific built artifact, set:

- `DATAWEAVE_NATIVE_LIB=/absolute/path/to/dwlib.(dylib|so|dll)`

The Python module will also try a few fallbacks (including the wheel-bundled location).

## Using the library (Python examples)

All examples below assume:

```python
import dataweave
```

### 1) Simple script

```python
result = dataweave.run("2 + 2")
assert result.success is True
print(result.get_string())  # "4"
```

### 2) Script with inputs (auto-detected types)

Inputs can be plain Python values. The module auto-encodes them as JSON or text.

```python
result = dataweave.run(
    "num1 + num2",
    {"num1": 25, "num2": 17},
)
print(result.get_string())  # "42"
```

### 3) Script with inputs (explicit mime type, charset, properties)

Use an explicit input dict when you need full control over how DataWeave interprets bytes.

```python
script = "payload.person"
xml_bytes = b"<?xml version=\"1.0\" encoding=\"UTF-16\"?><person><name>Billy</name><age>31</age></person>".decode("utf-8").encode("utf-16")

result = dataweave.run(
    script,
    {
        "payload": {
            "content": xml_bytes,
            "mimeType": "application/xml",
            "charset": "UTF-16",
            "properties": {
                "nullValueOn": "empty",
                "maxAttributeSize": 256
            },
        }
    },
)

if result.success:
    print(result.get_string())
else:
    print(result.error)
```

You can also use `InputValue` for the same purpose:

```python
input_value = dataweave.InputValue(
    content="1234567",
    mime_type="application/csv",
    properties={"header": False, "separator": "4"},
)

result = dataweave.run("in0.column_1[0]", {"in0": input_value})
print(result.get_string())  # '"567"'
```

### 4) Context manager (explicit lifecycle)

The module-level API (`dataweave.run(...)`) uses a shared singleton. Use `DataWeave` directly when you need explicit control over isolate lifecycle or want multiple independent instances:

```python
with dataweave.DataWeave() as dw:
    r1 = dw.run("2 + 2")
    r2 = dw.run("x + y", {"x": 10, "y": 32})

    print(r1.get_string())  # "4"
    print(r2.get_string())  # "42"
```

### 5) Error handling

There are three error types:

- `DataWeaveLibraryNotFoundError` — the native library cannot be located/loaded.
- `DataWeaveScriptError` — script compilation or runtime error (subclass of `DataWeaveError`). Carries the full result on `.result`.
- `DataWeaveError` — FFI-level failures (isolate creation, library calls).

**Option A: Use `raise_on_error=True` for a single try/except (recommended)**

```python
try:
    result = dataweave.run("invalid syntax here", raise_on_error=True)
    print(result.get_string())

except dataweave.DataWeaveScriptError as e:
    print(f"Script error: {e.result.error}")

except dataweave.DataWeaveLibraryNotFoundError:
    # Build it first: ./gradlew :native-lib:nativeCompile
    raise
```

**Option B: Check `result.success` manually (default, backward-compatible)**

```python
result = dataweave.run("invalid syntax here")

if not result.success:
    print(f"Error: {result.error}")
else:
    print(result.get_string())
```

### 6) Output streaming

Use `run_streaming` to execute a script and receive output chunks as they are produced, without buffering the entire result in memory.

```python
with dataweave.DataWeave() as dw:
    stream = dw.run_streaming("output application/json --- (1 to 10000) map {id: $}")
    for chunk in stream:
        sys.stdout.buffer.write(chunk)
    metadata = stream.metadata  # StreamingResult with mime_type, charset, etc.
    print(f"\nDone: {metadata.mime_type}, {metadata.charset}")
```

Or with the module-level API:

```python
stream = dataweave.run_streaming("output application/csv --- payload", {"payload": [1, 2, 3]})
output = b"".join(stream)
```

### 7) Input and output streaming

Use `run_transform` to stream both input and output — feed an iterable of bytes in, receive a generator of bytes out. Ideal for processing large files or network streams with constant memory.

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
```

Works with any iterable — generators, lists, network sockets:

```python
# From an in-memory list
stream = dataweave.run_transform(
    "output application/json --- payload map ($ * $)",
    input_stream=[b"[1,2,3,4,5]"],
    input_mime_type="application/json",
)
print(b"".join(stream))  # [1,4,9,16,25]
```

```python
# From a generator producing chunks
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

### 8) I/O streaming with callbacks (low-level)

Use `run_input_output_callback` when you need direct callback control (e.g. integration with event-driven frameworks). For most use cases, prefer `run_transform` above.

```python
json_input = b'[1,2,3,4,5]'
pos = 0

def read_cb(buf_size):
    nonlocal pos
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
print(b"".join(chunks))  # [1,4,9,16,25]
```

---

## Installing for use in a Node.js project

### Option A: Install the package (recommended)

From npm:

```bash
npm install dataweave-native
```

Supported platform packages are `dataweave-native-linux-x64`,
`dataweave-native-win32-x64`, and `dataweave-native-darwin-arm64`.

After `:native-lib:buildNodePackage`, install the local meta-package tarball and the
matching platform tarball:

```bash
npm install ./native-lib/node/dataweave-native-<ver>.tgz ./native-lib/node/dataweave-native-<platform>-<ver>.tgz
```

### Option B: Development install (link)

1. Stage the native library:

```bash
./gradlew :native-lib:stageNodeNativeLib
```

2. Build the Node package:

```bash
cd native-lib/node
npm install
npx node-gyp rebuild
npx tsc
```

3. Link into your project:

```bash
npm link native-lib/node
```

### Option C: Use an externally-built library via an environment variable

Set `DATAWEAVE_NATIVE_LIB=/absolute/path/to/dwlib.(dylib|so|dll)` before running your application.

The module also searches:
1. `<package>/native/dwlib.*`
2. `<repo>/native-lib/build/native/nativeCompile/dwlib.*` (dev fallback)
3. Current working directory

### Building with Gradle

```bash
# Stage native library into node/native/
./gradlew :native-lib:stageNodeNativeLib

# Build the full .tgz package (stage + compile addon + tsc + npm pack)
./gradlew :native-lib:buildNodePackage

# Run Node.js tests
./gradlew :native-lib:nodeTest

# Skip Node tests in CI: -PskipNodeTests=true
```

### Requirements

- Node.js >= 18
- A C compiler (for `node-gyp` to build the native addon)
- The `dwlib` shared library (staged by Gradle or pointed to via env var)

## Using the library (Node.js examples)

All examples below assume:

```typescript
import { run, runStreaming, runTransform, cleanup } from "dataweave-native";
```

### 1) Simple script

```typescript
const result = run("2 + 2");
console.log(result.getString()); // "4"
```

### 2) Script with inputs (auto-detected types)

Inputs can be plain JS values. The module auto-encodes them as JSON.

```typescript
const result = run("num1 + num2", { num1: 25, num2: 17 });
console.log(result.getString()); // "42"
```

### 3) Script with inputs (explicit mime type, charset, properties)

Use an explicit input object when you need full control over how DataWeave interprets bytes.

```typescript
import { readFileSync } from "fs";

const xmlBytes = readFileSync("person.xml");

const result = run("payload.person", {
  payload: {
    content: xmlBytes,
    mimeType: "application/xml",
    charset: "UTF-16",
    properties: {
      nullValueOn: "empty",
      maxAttributeSize: 256,
    },
  },
});

if (result.success) {
  console.log(result.getString());
} else {
  console.error(result.error);
}
```

### 4) Explicit instance lifecycle

The module-level API (`run(...)`) uses a shared singleton. Use the `DataWeave` class directly when you need explicit control over isolate lifecycle:

```typescript
import { DataWeave } from "dataweave-native";

const dw = new DataWeave();
dw.initialize();
try {
  const r1 = dw.run("2 + 2");
  const r2 = dw.run("x + y", { x: 10, y: 32 });

  console.log(r1.getString()); // "4"
  console.log(r2.getString()); // "42"
} finally {
  // cleanup() returns a Promise; await it. When this releases the FINAL shared
  // native reference in the process, it drains any in-flight streaming/transform
  // op, attempts isolate teardown, and resolves once that attempt completes --
  // guaranteeing logical release, not necessarily physical reclamation: an
  // ordinary teardown failure retains the live isolate and retries where safe,
  // and an unrecoverable teardown-plus-detach double failure intentionally
  // leaks it until process exit (with a diagnostic on stderr). When other
  // initialized instances remain, it resolves as soon as this instance is
  // released, leaving the shared isolate live for them.
  await dw.cleanup();
}
```

### 5) Error handling

There are two error classes:

- `DataWeaveError` — library/isolate-level failures (library not found, initialization failed).
- `DataWeaveScriptError` — script compilation or runtime error (subclass of `DataWeaveError`). Carries the full result on `.result`.

**Option A: Use `raiseOnError: true` for try/catch (recommended)**

```typescript
import { run, DataWeaveScriptError } from "dataweave-native";

try {
  const result = run("invalid syntax here", {}, { raiseOnError: true });
  console.log(result.getString());
} catch (e) {
  if (e instanceof DataWeaveScriptError) {
    console.error(`Script error: ${e.result.error}`);
  } else {
    throw e;
  }
}
```

**Option B: Check `result.success` manually (default)**

```typescript
const result = run("invalid syntax here");

if (!result.success) {
  console.error(`Error: ${result.error}`);
} else {
  console.log(result.getString());
}
```

### 6) Output streaming

Use `runStreaming` to execute a script and receive output chunks as they are produced, without buffering the entire result in memory. Returns an `AsyncGenerator<Buffer, StreamingResult>`.

```typescript
const gen = runStreaming(
  'output application/json --- (1 to 10000) map {id: $, name: "item_" ++ $}'
);

let result = await gen.next();
while (!result.done) {
  process.stdout.write(result.value);
  result = await gen.next();
}

const metadata = result.value; // StreamingResult
console.log(`\nDone: ${metadata.mimeType}, ${metadata.charset}`);
```

Or with `for await`:

```typescript
const gen = runStreaming("output application/csv --- payload", {
  payload: [1, 2, 3],
});

const chunks: Buffer[] = [];
for await (const chunk of gen) {
  chunks.push(chunk);
}
const output = Buffer.concat(chunks).toString("utf-8");
```

### 7) Input and output streaming (bidirectional)

Use `runTransform` to stream both input and output — feed an `Iterable<Buffer>` or `AsyncIterable<Buffer>` in, receive an `AsyncGenerator<Buffer>` out.

**Important: sync vs async input and memory usage**

The native read callback is invoked synchronously on the JS main thread, which means:

- **Synchronous iterables** (arrays, generators) are consumed **on-demand** — only one chunk is held in memory at a time. This gives constant-memory streaming, comparable to the Python API.
- **Async iterables** (e.g. `fs.createReadStream()`) **must be fully pre-buffered** into memory before the transform starts, because their `.next()` returns a Promise that cannot be awaited inside a synchronous callback.

For large inputs, prefer a **synchronous generator** to get true streaming with minimal memory:

```typescript
import { readFileSync } from "fs";

// Good: sync generator → constant memory (~150 MB for 50M elements)
function* chunked(data: Buffer, size = 8192): Generator<Buffer> {
  for (let i = 0; i < data.length; i += size) {
    yield data.subarray(i, i + size);
  }
}
const gen = runTransform("output csv --- payload", chunked(readFileSync("large.json")), {
  mimeType: "application/json",
});
```

Using an async readable stream still works but will buffer the entire input first:

```typescript
import { createReadStream } from "fs";
import { createWriteStream } from "fs";

// Works but pre-buffers the full input into memory
const input = createReadStream("large.json");
const gen = runTransform("output application/csv --- payload", input, {
  mimeType: "application/json",
});

const out = createWriteStream("output.csv");
for await (const chunk of gen) {
  out.write(chunk);
}
out.end();
```

Works with any iterable — arrays, generators, streams:

```typescript
// From an in-memory array
const input = [Buffer.from("[1,2,3,4,5]")];
const gen = runTransform(
  "output application/json --- payload map ($ * $)",
  input,
  { mimeType: "application/json" }
);

const chunks: Buffer[] = [];
for await (const chunk of gen) {
  chunks.push(chunk);
}
console.log(Buffer.concat(chunks).toString()); // [1,4,9,16,25]
```

```typescript
// From a generator producing chunks
function* chunked(data: Buffer, size = 4096): Generator<Buffer> {
  for (let i = 0; i < data.length; i += size) {
    yield data.subarray(i, i + size);
  }
}

const largeJson = Buffer.from(JSON.stringify(Array.from({ length: 1000 }, (_, i) => ({ id: i }))));
const gen = runTransform(
  "output application/json --- sizeOf(payload)",
  chunked(largeJson),
  { mimeType: "application/json" }
);

for await (const chunk of gen) {
  process.stdout.write(chunk); // "1000"
}
```

### 8) Transform with additional inputs

Pass extra named inputs alongside the streamed input:

```typescript
const input = [Buffer.from('[{"price": 100}, {"price": 200}]')];
const gen = runTransform(
  "output application/json --- payload map ($.price * rate)",
  input,
  {
    mimeType: "application/json",
    inputs: { rate: 1.5 },
  }
);

for await (const chunk of gen) {
  process.stdout.write(chunk); // [150.0, 300.0]
}
```

### 9) Cleanup

The module registers two process hooks to clean up automatically: `beforeExit`
(async — it awaits cleanup so an in-flight streaming/transform op drains before
the process exits normally) and `exit` (a synchronous best-effort fallback for
`process.exit()` and uncaught exceptions, which cannot await the drain). Neither
hook fires on `SIGTERM`/`SIGINT`/`SIGKILL`, so install your own signal handler
that awaits `cleanup()` if you need a graceful drain on termination. For explicit
control:

```typescript
import { cleanup } from "dataweave-native";

// When done with all DataWeave operations. cleanup() returns a Promise; await it.
// Draining in-flight streaming/transform work and tearing down the isolate happen
// only when this releases the final shared native reference; if other initialized
// instances remain, it resolves as soon as this instance is released.
await cleanup();
```
