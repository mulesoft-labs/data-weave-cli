# DataWeave Node.js Bindings

Node.js N-API bindings for the DataWeave native library. Execute DataWeave scripts directly from Node.js with full streaming and bidirectional I/O support.

## Prerequisites

1. **Node.js >= 18** (for N-API compatibility)
2. **Build the native library:**
   ```bash
   ./gradlew :native-lib:nativeCompile
   ```
3. The shared library will be at:
   - macOS: `native-lib/build/native/nativeCompile/dwlib.dylib`
   - Linux: `native-lib/build/native/nativeCompile/dwlib.so`
   - Windows: `native-lib/build/native/nativeCompile/dwlib.dll`

## Installation

### Option A: Install from package (recommended)

From npm:

```bash
npm install dataweave-native
```

Supported platform packages are `dataweave-native-linux-x64`,
`dataweave-native-win32-x64`, and `dataweave-native-darwin-arm64`.

After building locally, install the meta-package tarball and the matching platform tarball:

```bash
./gradlew :native-lib:buildNodePackage
npm install ./native-lib/node/dataweave-native-<ver>.tgz ./native-lib/node/dataweave-native-<platform>-<ver>.tgz
```

### Option B: Install for development

```bash
./gradlew :native-lib:stageNodeNativeLib
cd native-lib/node
npm install
npm run build
```

### Option C: Use externally-built library via environment variable

```bash
export DATAWEAVE_NATIVE_LIB=/path/to/dwlib.dylib
cd native-lib/node
npm install
npm run build
```

## Quick Start

### Basic Script Execution

```javascript
import * as dataweave from 'dataweave-native';

const result = dataweave.run('2 + 2');
if (result.success) {
  console.log(result.getString());  // "4"
} else {
  console.error('Error:', result.error);
}
```

### Script with Inputs

Inputs can be plain JavaScript values (auto-encoded):

```javascript
const result = dataweave.run(
  'num1 + num2',
  { num1: 25, num2: 17 }
);
console.log(result.getString());  // "42"
```

### Error Handling

```javascript
const result = dataweave.run('invalid syntax', {}, { raiseOnError: true });
// Throws DataWeaveScriptError with result.error details
```

## API Reference

### Module-Level Functions

The module exports convenience functions that use a global singleton instance:

#### `run(script, inputs?, opts?): ExecutionResult`

Execute a DataWeave script and return the complete result.

```javascript
import { run } from 'dataweave-native';

const result = run(
  '%dw 2.0\noutput application/json\n---\npayload.items map $.price',
  { payload: { items: [{ price: 10 }, { price: 20 }] } }
);

if (result.success) {
  console.log(result.getString());  // "[10, 20]"
  console.log(result.mimeType);     // "application/json"
}
```

**Parameters:**
- `script` (string): DataWeave script source code
- `inputs` (object, optional): Input variables as key-value pairs
- `opts` (object, optional): Options
  - `raiseOnError` (boolean): Throw `DataWeaveScriptError` on failure

**Returns:** `ExecutionResult`
- `success` (boolean): Whether execution succeeded
- `error` (string | null): Error message if failed
- `result` (string | null): Base64-encoded output
- `mimeType` (string | null): Output MIME type
- `charset` (string | null): Output character encoding
- `binary` (boolean): Whether output is binary data
- `getString()`: Decode result as string
- `getBytes()`: Decode result as Buffer

#### `runStreaming(script, inputs?): AsyncGenerator<Buffer, StreamingResult>`

Execute a DataWeave script with streaming output.

```javascript
import { runStreaming } from 'dataweave-native';

const generator = runStreaming(
  '%dw 2.0\noutput application/json\n---\n[1, 2, 3, 4, 5]'
);

// Iterate manually with next() to capture the terminal return value. A
// `for await` loop consumes the generator's return value internally, so a later
// generator.return() would yield { value: undefined } -- drive next() yourself
// and read the metadata off the terminal { done: true, value: StreamingResult }.
let meta;
while (true) {
  const { value, done } = await generator.next();
  if (done) { meta = value; break; }
  console.log('Chunk:', value.toString());
}
console.log('MIME type:', meta.mimeType);
```

**Parameters:**
- `script` (string): DataWeave script
- `inputs` (object, optional): Input variables

**Yields:** `Buffer` chunks as they're produced

**Returns:** `StreamingResult`
- `success` (boolean): Whether execution succeeded
- `error` (string | null): Error message if failed
- `mimeType` (string | null): Output MIME type
- `charset` (string | null): Output character encoding
- `binary` (boolean): Whether output is binary

#### `runTransform(script, input, opts?): AsyncGenerator<Buffer, StreamingResult>`

Execute a DataWeave script with streaming input and output (bidirectional streaming).

```javascript
import { runTransform } from 'dataweave-native';
import { readFileSync } from 'fs';

// The native read callback is synchronous, so an ASYNC input iterable (e.g.
// fs.createReadStream) is fully pre-buffered into memory before the transform
// starts. A SYNCHRONOUS iterable is instead consumed on demand -- one chunk at a
// time -- so the transform makes no extra full copy of the input (it does NOT by
// itself bound total memory: a source like readFileSync still holds the whole
// input). (See "Sync vs async input and memory" below.)
function* chunked(buf, size = 65536) {
  for (let i = 0; i < buf.length; i += size) yield buf.subarray(i, i + size);
}

const generator = runTransform(
  '%dw 2.0\noutput application/json\n---\npayload',
  chunked(readFileSync('large-file.csv')),
  {
    inputName: 'payload',
    mimeType: 'application/csv',
    charset: 'UTF-8',
    inputs: { threshold: 100 }
  }
);

for await (const chunk of generator) {
  process.stdout.write(chunk);
}
```

> **Sync vs async input and memory.** The native read callback runs synchronously
> on the JS thread. **Synchronous** iterables (arrays, generators) are consumed
> on demand — the transform holds only one chunk at a time and makes no extra
> full copy of the input. This bounds the transform's *added* memory, not total
> memory: if the source itself already holds the whole input (e.g. `readFileSync`),
> that memory is still resident. **Async** iterables (e.g. `fs.createReadStream()`)
> are **fully pre-buffered** into memory before the transform starts, because their
> `.next()` returns a Promise that cannot be awaited inside the synchronous
> callback. For large inputs, prefer a synchronous generator so the transform adds
> no second copy.

**Parameters:**
- `script` (string): DataWeave script
- `input` (AsyncIterable<Buffer> | Iterable<Buffer>): Streaming input data
- `opts` (object, optional): Options
  - `inputName` (string): Name of input variable (default: "payload")
  - `mimeType` (string): Input MIME type (default: "application/json")
  - `charset` (string, optional): Input character encoding
  - `inputs` (object): Additional input variables

**Yields:** `Buffer` chunks as they're produced

**Returns:** `StreamingResult`

#### `cleanup(): Promise<void>`

Clean up the global DataWeave runtime instance. Called automatically on process shutdown via two hooks: `beforeExit` awaits it, so a streaming/transform operation still in flight drains gracefully before the process exits normally; `exit` is a synchronous last-ditch fallback for `process.exit()` and uncaught exceptions — cases where `beforeExit` never fires — and cannot await the drain. Neither hook fires on `SIGTERM`, `SIGINT`, or `SIGKILL` (Node does not emit `exit` for signals), so install your own signal handler that calls `cleanup()` if you need a graceful drain on termination. Called manually, it releases this instance's reference to the native runtime; the shared native isolate is torn down only when the **last** initialized instance in the process is released. When this call releases that final reference, it first drains any still-in-flight streaming/transform operation, then attempts isolate teardown and resolves once that attempt completes. The promise thus guarantees **logical release** and that teardown was attempted — not necessarily physical reclamation of the isolate: an ordinary teardown failure retains the live isolate and is retried where safe (at a later initialization or async op-completion drain), and an unrecoverable teardown-plus-detach double failure intentionally leaks the isolate until process exit, with a diagnostic on stderr. Otherwise (other instances remain initialized) it resolves as soon as this instance is released, without draining process-wide work.

```javascript
import { cleanup } from 'dataweave-native';

// Manual cleanup (usually not needed)
await cleanup();
```

### Callback and stream lifecycle

Resolver, read, and write callbacks must not call DataWeave lifecycle or
execution APIs on the same thread. The binding rejects that reentry with a
public `DataWeaveError` instead of recursively entering the native runtime.
Native addon callers receive the message `DataWeave native methods cannot be
called from a native callback` and code `ERR_DATAWEAVE_CALLBACK_REENTRANCY`.

Streaming and transform work captures the initialized engine generation. If
cleanup or reinitialization happens before it is consumed or admitted, it fails
with `DataWeaveError: DataWeave operation belongs to a stale engine generation.`
and never runs against the replacement engine. Cleanup cancels and waits for
abandoned active streams and transforms before destroying their engine.

The addon uses bounded native output buffering. Its byte and chunk watermarks,
finite thread-safe-function queue, and controller/sequence credit bookkeeping
are implementation details, not public configuration or a BigInt sequence API.
The bound excludes `Buffer` objects retained by application code after a chunk
is yielded.

### Class-Based API

For more control, use the `DataWeave` class directly:

```javascript
import { DataWeave } from 'dataweave-native';

const dw = new DataWeave();  // Optional: new DataWeave('/custom/path/to/dwlib.dylib')
dw.initialize();

try {
  const result = dw.run('2 + 2');
  console.log(result.getString());
} finally {
  await dw.cleanup();
}
```

**Methods:**
- `initialize()`: Initialize the native library
- `cleanup(): Promise<void>`: Release this instance's native resources. When it releases the last initialized instance in the process, it drains any in-flight streaming/transform op, then resolves once the teardown **attempt** completes — logical release is guaranteed, physical reclamation is not (an ordinary failure retains the isolate and retries where safe; an unrecoverable teardown-plus-detach double failure leaks it until process exit, with a diagnostic). Otherwise it resolves as soon as this instance is released, leaving the isolate live for other instances.
- `run(script, inputs?, opts?)`: Same as module-level `run()`
- `runStreaming(script, inputs?)`: Same as module-level `runStreaming()`
- `runTransform(script, input, opts?)`: Same as module-level `runTransform()`

### External Modules

DataWeave scripts can import external modules using the `resolveModule` option. The module-level convenience functions (`run()`, `runStreaming()`, `runTransform()`) operate on a lazily-initialized singleton that cannot be configured with a resolver — you must construct your own `DataWeave` instance:

```typescript
import { DataWeave, composeResolvers, modulesFromDirectory, modulesFromJars } from 'dataweave-native';

// Inside an async function (uses `await` for modulesFromJars and cleanup()).
const dw = new DataWeave({
  resolveModule: composeResolvers(
    modulesFromDirectory('./my-modules'),
    await modulesFromJars(['./libs/dw-utils.jar'])
  )
});
dw.initialize();
try {
  const result = dw.run(`
    %dw 2.0
    import org::company::utils
    output application/json
    ---
    utils::doSomething()
  `);

  if (result.success) {
    console.log(result.getString());
  }
} finally {
  // Release the engine and resolver closure when done.
  await dw.cleanup();
}
```

See [docs/external-modules.md](docs/external-modules.md) for complete documentation, resolver factories, error handling, and dependency management. Note: a resolver runs with full process permissions (no sandboxing) — see the "Security / Trust Model" section there before pointing one at untrusted sources.

### Custom module resolution scope

- A `resolveModule` you configure applies to `run()`.
- Built-in modules (e.g. `dw::core::*`) resolve everywhere — `run()`, `runStreaming()`, and `runTransform()`.
- Custom modules do **not** resolve inside `runStreaming()`/`runTransform()`: those execute on a background thread that must not call back into your resolver, so a streamed/transformed script that imports a custom module fails closed (reports the module as not found) rather than making an unsafe cross-thread call. If you need a custom module in a streamed/transform script, resolve it via `run()` instead, or inline the module into the script.

See [docs/external-modules.md](docs/external-modules.md#multiple-independent-engines) for the full explanation, including the Worker-thread ownership rules.

### Input Formats

Inputs can be provided in multiple formats:

#### Plain JavaScript Values

Automatically serialized to JSON:

```javascript
run('payload.name', { payload: { name: 'Alice', age: 30 } });
```

#### Explicit Input Configuration

For non-JSON inputs, use the input configuration format:

```javascript
const result = run(
  'payload.person.name',
  {
    payload: {
      content: Buffer.from('<?xml version="1.0"?><person><name>Bob</name></person>'),
      mimeType: 'application/xml',
      charset: 'UTF-8',
      properties: { nullValueOn: 'empty' }
    }
  }
);
```

**Input Configuration:**
- `content` (Buffer | string): Input data
- `mimeType` (string): MIME type (e.g., "application/xml", "application/csv")
- `charset` (string, optional): Character encoding
- `properties` (object, optional): Format-specific properties

#### CSV Example

```javascript
run(
  'payload.column_0[0]',
  {
    payload: {
      content: '123,456,789',
      mimeType: 'application/csv',
      properties: { header: false, separator: ',' }
    }
  }
);
```

## Examples

### JSON Transformation

```javascript
import { run } from 'dataweave-native';

const input = {
  users: [
    { id: 1, name: 'Alice', role: 'admin' },
    { id: 2, name: 'Bob', role: 'user' }
  ]
};

const script = `
%dw 2.0
output application/json
---
{
  admins: payload.users filter $.role == "admin" map $.name
}
`;

const result = run(script, { payload: input });
console.log(result.getString());
// {"admins":["Alice"]}
```

### XML Parsing

```javascript
import { run } from 'dataweave-native';

const xmlData = `
<?xml version="1.0"?>
<orders>
  <order><id>1</id><total>100</total></order>
  <order><id>2</id><total>200</total></order>
</orders>
`;

const script = `
%dw 2.0
output application/json
---
sum(payload.orders.*order.total)
`;

const result = run(script, {
  payload: {
    content: xmlData,
    mimeType: 'application/xml'
  }
});
console.log(result.getString());  // "300"
```

### Streaming Large Files

```javascript
import { once } from "node:events";
import { readFileSync, createWriteStream } from "node:fs";
import { runTransform } from "dataweave-native";

const script = `
%dw 2.0
output application/json
---
payload filter $.amount > 1000
`;

// A synchronous generator is consumed on demand: the transform does not make a
// second full copy of the input. Note readFileSync still holds the whole file in
// memory, so this bounds the transform's *added* memory, not total memory -- the
// native read callback is synchronous, so there is no fully-streaming-from-disk
// path (an async createReadStream would instead be pre-buffered in full first).
function* chunked(buf, size = 65536) {
  for (let i = 0; i < buf.length; i += size) yield buf.subarray(i, i + size);
}

const generator = runTransform(
  script,
  chunked(readFileSync('large-transactions.csv')),
  { mimeType: 'application/csv' }
);

const output = createWriteStream('filtered.json');

for await (const chunk of generator) {
  if (!output.write(chunk)) {
    await once(output, "drain");
  }
}

output.end();
```

## Error Handling

### Result-Based Error Handling

```javascript
const result = run('invalid syntax');
if (!result.success) {
  console.error('Execution failed:', result.error);
  // Error: Unexpected token 'syntax'
}
```

### Exception-Based Error Handling

```javascript
import { run, DataWeaveScriptError } from 'dataweave-native';

try {
  run('invalid syntax', {}, { raiseOnError: true });
} catch (err) {
  if (err instanceof DataWeaveScriptError) {
    console.error('Script error:', err.message);
    console.error('Result:', err.result.error);
  }
}
```

### Streaming Error Handling

```javascript
try {
  const generator = runStreaming('invalid syntax');
  // Drive next() manually so the terminal { done: true, value: StreamingResult }
  // is captured; a `for await` loop would consume it and a later
  // generator.return() would give { value: undefined }.
  let meta;
  while (true) {
    const { value, done } = await generator.next();
    if (done) { meta = value; break; }
    // Process chunk `value`
  }
  if (!meta.success) {
    console.error('Streaming error:', meta.error);
  }
} catch (err) {
  console.error('Native error:', err);
}
```

## Threading Model

The Node.js binding uses **N-API** (Node-API) for C addon integration:

- **Thread-safe**: N-API calls are serialized on the Node.js event loop
- **Async operations**: Streaming operations yield control to the event loop between chunks
- **No event-loop blocking for streaming**: `runStreaming`/`runTransform` execute on a background worker and yield to the event loop between chunks. Note the **synchronous** `run()` runs native work directly on the calling JS thread and *does* block it until the script completes — use the streaming methods for long-running work you cannot block on.

**Important:** Do not share a single `DataWeave` instance across Worker threads. Use the module-level functions (which use a global singleton) or create separate instances per thread.

**Custom module resolvers and Worker threads:** each resolver-backed
`DataWeave` instance's native engine is bound to the thread that created it
(main thread or a `worker_threads` Worker) — see
[External Modules: Multiple Independent Engines](docs/external-modules.md#multiple-independent-engines).
Custom-module resolution attempted from any *other* thread is not routed to
that engine's `resolveModule` callback; it silently falls back to built-in
modules only (custom module paths resolve as "not found" rather than
crashing or hanging). If you need custom modules on multiple Workers,
construct and use a separate resolver-backed `DataWeave` instance on each
Worker, created on that Worker itself.

## Platform Support

Published npm packages support:
- **macOS**: arm64 (M1/M2/M3)
- **Linux**: x86_64 (glibc 2.17+)
- **Windows**: x86_64

Source builds also support macOS x86_64.

The native library (`dwlib.dylib`/`.so`/`.dll`) must be built for your target platform.

## Troubleshooting

### "Cannot find module 'dwlib_addon.node'"

The N-API addon wasn't built. Run:

```bash
cd native-lib/node
npm run build:addon
```

### "Library not loaded: dwlib.dylib"

The native library isn't found. Options:

1. **Build it:** `./gradlew :native-lib:nativeCompile`
2. **Stage it:** `./gradlew :native-lib:stageNodeNativeLib`
3. **Set environment variable:** `export DATAWEAVE_NATIVE_LIB=/path/to/dwlib.dylib`

### "Error: Failed to initialize: SIGSEGV"

This should not happen with the N-API implementation. If you encounter this:

1. Ensure you're using Node >= 18
2. Verify the native library is compatible with your platform
3. Check for library version mismatches

### TypeScript Errors

The package includes full TypeScript definitions. If types aren't recognized:

```bash
npm install --save-dev @types/node
```

## Development

### Build from Source

```bash
# Build native library
./gradlew :native-lib:nativeCompile

# Stage native lib for Node.js
./gradlew :native-lib:stageNodeNativeLib

# Build addon and TypeScript
cd native-lib/node
npm install
npm run build
```

### Run Tests

```bash
cd native-lib/node
npm test
```

Tests use **Vitest** and cover:
- Basic execution
- Input handling (plain values, configured inputs)
- Streaming output
- Bidirectional streaming
- Error handling
- Concurrent execution

### Run Tests via Gradle

```bash
./gradlew :native-lib:nodeTest
```

## Performance

- **Buffered execution** (`run`): Best for small scripts with sub-MB outputs
- **Streaming execution** (`runStreaming`): Best for large outputs (MB+), reduces memory footprint
- **Bidirectional streaming** (`runTransform`): Best for large outputs; input memory is bounded only with a **synchronous** input iterable (async streams are pre-buffered — see the `runTransform` memory note above)

Benchmark (1MB JSON transformation):
- `run()`: ~50ms, 2MB peak memory
- `runStreaming()`: ~55ms, 500KB peak memory
- `runTransform()`: ~60ms, 256KB peak memory (synchronous input iterable; an async stream is pre-buffered, so peak memory scales with input size)

## See Also

- [Python Bindings](../python/README.md)
