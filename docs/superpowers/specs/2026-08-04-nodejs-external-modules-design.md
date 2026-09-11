# Design: External DataWeave Module Support in Node.js Binding

**Date:** 2026-08-04
**Status:** Superseded
**Related Proposal:** [docs/proposals/nodejs-external-modules.md](../../proposals/nodejs-external-modules.md)

> **⚠️ Superseded (2026-08-31).** This document describes the original
> single-resolver Node external-module design. It is retained for historical
> context only. The shipped design is
> [`2026-08-07-native-lib-multi-engine-design.md`](2026-08-07-native-lib-multi-engine-design.md).
> The following assertions below are stale and no longer accurate:
> - **Resolver scope.** A custom resolver does *not* apply to all three
>   execution APIs. It resolves custom modules only for `run()`; `runStreaming()`
>   and `runTransform()` execute on a background worker that must not call back
>   into the resolver, so custom-module imports there fail closed (report the
>   module as not found) rather than routing to the callback. Built-in modules
>   still resolve everywhere.
> - **One resolver per process.** Resolvers are no longer process-global. Each
>   `DataWeave` instance owns its own handle-addressed engine with its own
>   resolver; multiple independent resolver-backed engines can coexist in one
>   process (each bound to the thread that created it).
> - **Resolver ABI and lifecycle.** The old process-wide resolver ABI is
>   replaced by the handle-based `create_engine_with_resolver` + `run_script_engine`
>   ABI; a resolver is bound at engine creation and released with that engine's
>   `cleanup()`, not installed process-wide.

## Goal

Enable Node.js applications to use external DataWeave modules (reusable libraries) that are not compiled into the native image. Scripts can import modules from directories, JAR files, or in-memory maps, composed with fallback chains.

## Scope

**In scope:**
- Callback-based module resolver infrastructure (native + Node layers)
- Four resolver factories: `modulesFromMap`, `modulesFromDirectory`, `modulesFromJars`, `composeResolvers`
- Integration with all three execution APIs: `run`, `runStreaming`, `runTransform`
- Re-enable excluded TCK tests for module imports

**Out of scope:**
- Maven/coursier dependency resolution (users provide JAR paths manually or via future tooling)
- Python binding (native changes are generic enough to support later)
- Multiple isolated engines per process (remains one resolver per process)

## Architecture

Three-layer design following the existing `ReadCallback` pattern:

### Layer 1: Native Core (`native-lib/src/main/java`)

Callback infrastructure that bridges the DataWeave `WeaveResourceResolver` SPI to a C function pointer.

**Components:**
- `NativeCallbacks.java` — add `ResolveModuleCallback` function pointer type
- `CallbackWeaveResourceResolver.java` (new) — implements `WeaveResourceResolver`, delegates to callback
- `ScriptRuntime.java` — modified to accept resolver, build engine with `CompositeWeaveResourceResolver`
- `NativeLib.java` — new `@CEntryPoint` methods: `run_script_with_resolver`, `run_script_callback_with_resolver`, `run_script_input_output_callback_with_resolver`

**Key design point:** Engine is built once per process with resolver at construction. `ScriptRuntime.setResolver()` is called on first execution with a resolver and rebuilds the engine with `CompositeWeaveResourceResolver(ClassLoader, Callback)` to preserve built-in modules while adding user modules.

### Layer 2: Native Bridge (`native-lib/node/src/addon.c`)

FFI binding that bridges Node.js callbacks to native code using `napi_threadsafe_function`.

**Mechanism (reuses existing read callback pattern):**
1. Script execution runs on background thread
2. When resolver is invoked, background thread blocks on mutex/condition
3. `napi_threadsafe_function` triggers JS callback on main thread
4. JS resolver returns source string (or null)
5. N-API marshals result back to background thread
6. Background thread unblocks, native code receives source

**Components:**
- Load new native symbols (`run_script_with_resolver`, etc.)
- `resolve_module_callback()` — C callback invoked by native code
- `resolver_js_callback()` — N-API bridge to JS
- `run_with_resolver()` — N-API method exposed to JS

### Layer 3: Node API (`native-lib/node/src/`)

TypeScript resolver implementations and DataWeave class integration.

**Components:**
- `resolver.ts` — `ModuleResolver` type and four factory functions
- `ffi.ts` — TypeScript bindings for new FFI entrypoints
- `dataweave.ts` — accept `resolveModule` in constructor, route to appropriate FFI call

## API Design

### Core Type

```typescript
export type ModuleResolver = (modulePath: string) => string | null;
```

Module path arrives as the DataWeave namespace as a path (e.g., `"org/mule/weave/v2/libs/lib.dwl"`). Return `.dwl` source or `null` if not found.

### DataWeave Constructor

```typescript
export interface DataWeaveOptions {
  libPath?: string;
  resolveModule?: ModuleResolver;  // Optional
}

const dw = new DataWeave({
  resolveModule: composeResolvers(
    modulesFromDirectory('./my-modules'),
    modulesFromJars(['./libs/dw-strings.jar'])
  )
});
```

### Resolver Factories

**`modulesFromMap(modules: Record<string, string>): ModuleResolver`**

In-memory map of path → source. Simplest resolver, useful for tests and small inline modules.

```typescript
const resolver = modulesFromMap({
  'org/test/lib.dwl': '%dw 2.0\nfun greet(n) = "Hello " ++ n',
});
```

**`modulesFromDirectory(baseDir: string): ModuleResolver`**

Scans directory recursively for `.dwl` files, reads from disk on each resolution (no caching). Handles nested namespace structures like `org/mule/weave/v2/`.

```typescript
const resolver = modulesFromDirectory('./my-modules');
// Resolves "org/test/lib.dwl" → reads "./my-modules/org/test/lib.dwl"
```

**`modulesFromJars(jarPaths: string[]): Promise<ModuleResolver>`**

Async function that unzips JARs and returns a synchronous resolver backed by an in-memory map. Extracts all `.dwl` files from each JAR.

```typescript
const resolver = await modulesFromJars([
  './libs/dw-strings-1.0.jar',
  './libs/dw-dates-2.1.jar'
]);
```

**Note:** Returns a `Promise` because JAR extraction must complete before the resolver is used. The returned resolver itself is synchronous.

**`composeResolvers(...resolvers: ModuleResolver[]): ModuleResolver`**

Tries each resolver in order, returns first non-null result (first-match-wins). Mirrors the CLI's `CompositeWeaveResourceResolver` behavior.

```typescript
const resolver = composeResolvers(
  modulesFromMap({ 'override.dwl': '...' }),  // Checked first
  modulesFromDirectory('./shared-modules'),
  modulesFromJars(['./vendor/lib.jar'])
);
```

### Execution APIs

All three execution methods honor the resolver:

```typescript
// Synchronous
dw.run(script, inputs, mimeType);

// Streaming
dw.runStreaming(script, chunkReader, chunkWriter, mimeType);

// Transform
dw.runTransform(script, inputs, inputMimeType, outputMimeType);
```

If `resolveModule` was provided in constructor, these route to `*_with_resolver` FFI entrypoints. Otherwise, they use existing resolver-less entrypoints (backward compatible).

## Data Flow

### Module Resolution Flow

```
Script compilation starts
  ↓
Engine encounters `import org::mule::weave::v2::libs::lib`
  ↓
WeaveResourceResolver.resolve("org/mule/weave/v2/libs/lib.dwl")
  ↓
CompositeWeaveResourceResolver tries:
  1. ClassLoaderWeaveResourceResolver (built-in modules)
  2. CallbackWeaveResourceResolver (user modules)
  ↓
CallbackWeaveResourceResolver invokes C callback
  ↓
[Background thread blocks on mutex]
  ↓
napi_threadsafe_function triggers JS on main thread
  ↓
JS: resolveModule("org/mule/weave/v2/libs/lib.dwl")
  ↓
composeResolvers tries each resolver:
  - modulesFromDirectory → null (not found)
  - modulesFromJars → "%dw 2.0\n..." (found!)
  ↓
Result flows back through N-API
  ↓
[Background thread unblocks, receives source]
  ↓
Native code copies source, compiles module
  ↓
Script compilation continues
```

### Memory Management

**C string lifetime contract:**
- JS allocates result string when resolver succeeds
- Native code copies string immediately in `CallbackWeaveResourceResolver.resolve()`
- C addon frees the JS-allocated string after native call returns
- Module path strings are stack-allocated or freed per-call

**Resolver lifecycle:**
- Resolver callback set once during first `run()` call with a resolver
- Remains active for process lifetime (one engine → one resolver)
- Subsequent `DataWeave` instances with different resolvers log warning and keep first

## Error Handling

### Resolver Errors

**Module not found (expected case):**
- Resolver returns `null`
- Logged at debug level: `"Module not found in [source]: [path]"`
- Engine receives `Option.empty()`, reports standard DataWeave error: `"Unable to resolve module with identifier [name]"`
- Composite resolver tries next resolver in chain

**File I/O errors (malformed file, corrupt ZIP, unreadable file):**
- Resolver throws JavaScript `Error` with details
- Error propagates through N-API boundary
- Native code catches, wraps in DataWeave error context
- User sees: `"Module resolution failed for [path]: [original error message]"`

Examples that throw:
- `fs.readFileSync()` fails (permissions, disk error)
- JAR file is corrupt or invalid ZIP
- `.dwl` file contains invalid UTF-8

**Invalid UTF-8:**
- `fs.readFileSync(path, 'utf-8')` throws
- Treated as file I/O error (throw, not return null)

### FFI Boundary Errors

**Callback invocation fails:**
- N-API catches JavaScript exceptions during resolver callback
- Returns `NULL` to native code (treated as "not found")
- Logs error to console with stack trace

**Null pointer from JS:**
- C code checks for null before calling `CTypeConversion.toJavaString()`
- Null treated as "module not found"

**Memory allocation failure:**
- `malloc()` for result string checked; if null, return null to native
- Native code handles null gracefully

### One-Resolver-Per-Process Enforcement

```java
// In ScriptRuntime.setResolver():
if (resolver != null) {
  logger.warn("Module resolver already set for this process. " +
              "Only one resolver configuration is supported. Ignoring new resolver.");
  return;  // Keep existing resolver, don't rebuild engine
}
```

**User-facing guidance:**
- Documentation states: "Create one DataWeave instance with all modules in the resolver"
- Example shows `composeResolvers()` combining multiple sources
- Error message suggests: "Use composeResolvers() to combine multiple module sources"

## Testing Strategy

### Unit Tests (TypeScript, no FFI)

**`resolver.test.ts`** — test each factory in isolation:

```typescript
describe('modulesFromMap', () => {
  it('returns source when module exists');
  it('returns null when module not found');
});

describe('modulesFromDirectory', () => {
  it('reads file from disk');
  it('returns null when file not found');
  it('throws on corrupt/unreadable file');
  it('handles nested namespace paths');
});

describe('modulesFromJars', () => {
  it('extracts .dwl files from JAR');
  it('handles multiple JARs');
  it('throws on invalid ZIP');
  it('ignores non-.dwl files');
});

describe('composeResolvers', () => {
  it('returns first match');
  it('falls through to next resolver on null');
  it('returns null when all resolvers return null');
});
```

### Integration Tests (Full FFI round-trip)

**`dataweave-resolver.test.ts`** — test through DataWeave class:

```typescript
describe('DataWeave with resolver', () => {
  it('resolves imported module from map');
  it('resolves from directory');
  it('resolves from JAR');
  it('falls back through composite resolver');
  it('works with streaming API');
  it('works with runTransform');
  it('throws when module not found');
  it('throws on file I/O error');
  it('built-in modules still resolve');
});
```

### TCK Tests (Re-enable excluded tests)

**Update `native-lib/node/tests/tck/ignore-list.ts`:**

Remove these entries (currently excluded with reason "unresolved-module"):
- `import-lib-out.json`
- `import-star`
- `import-named-lib`
- `import-lib-with-alias`
- `full-qualified-name-ref`

**TCK harness setup:**

Extract modules from TCK suite zips, set up resolver before running tests:
```typescript
const tckModules = extractModulesFromTckSuites();  // Scan TCK zips for .dwl files
const dw = new DataWeave({
  resolveModule: modulesFromMap(tckModules),
});
```

## Implementation Details

### Native Core Changes

**`NativeCallbacks.java`:**
```java
public interface ResolveModuleCallback extends CFunctionPointer {
    @InvokeCFunctionPointer
    CCharPointer invoke(IsolateThread thread, CCharPointer modulePath);
}
```

**`CallbackWeaveResourceResolver.java` (new):**
```java
public class CallbackWeaveResourceResolver implements WeaveResourceResolver {
    private final ResolveModuleCallback callback;

    public CallbackWeaveResourceResolver(ResolveModuleCallback callback) {
        this.callback = callback;
    }

    @Override
    public Option<WeaveResource> resolve(ResourceDescriptor descriptor) {
        CCharPointer pathPtr = CTypeConversion.toCString(descriptor.path()).get();
        CCharPointer resultPtr = callback.invoke(CurrentIsolate.getCurrentThread(), pathPtr);

        if (resultPtr.isNull()) {
            return Option.empty();  // Resolver returned null
        }

        String source = CTypeConversion.toJavaString(resultPtr);
        // Note: host must free resultPtr after this returns
        return Option.apply(new StringWeaveResource(descriptor.path(), source));
    }
}
```

**`ScriptRuntime.java` modifications:**
```java
private static CallbackWeaveResourceResolver resolver = null;

public static void setResolver(ResolveModuleCallback callback) {
    if (resolver != null) {
        // Log warning: resolver already set
        return;
    }
    resolver = new CallbackWeaveResourceResolver(callback);
    // Rebuild engine with new resolver
    engine = new DataWeaveScriptingEngine(
        ModuleComponentsFactory.apply(compositeResolver()),
        ParserConfiguration(),
        new Properties()
    );
}

private static WeaveResourceResolver compositeResolver() {
    if (resolver == null) {
        return ClassLoaderWeaveResourceResolver.apply();
    }
    return CompositeWeaveResourceResolver.apply(
        ClassLoaderWeaveResourceResolver.apply(),  // Built-ins first
        resolver                                    // User modules second
    );
}
```

**`NativeLib.java` new entrypoints:**
```java
@CEntryPoint(name = "run_script_with_resolver")
public static CCharPointer runScriptWithResolver(
    IsolateThread thread,
    CCharPointer script,
    CCharPointer inputs,
    CCharPointer mimeType,
    ResolveModuleCallback resolverCallback
) {
    ScriptRuntime.setResolver(resolverCallback);
    return ScriptRuntime.getInstance().run(
        CTypeConversion.toJavaString(script),
        CTypeConversion.toJavaString(inputs),
        CTypeConversion.toJavaString(mimeType)
    );
}

// Similar for run_script_callback_with_resolver (streaming output)
// Similar for run_script_input_output_callback_with_resolver (streaming input+output)
```

### Native Bridge Changes

**`addon.c` resolver callback bridge:**

Reuses existing `ReadCallback` infrastructure pattern:
- `napi_threadsafe_function` for cross-thread JS calls
- Mutex/condition synchronization
- Background thread blocking while main thread executes JS

```c
typedef struct {
    napi_threadsafe_function tsfn;
    uv_mutex_t mutex;
    uv_cond_t cond;
    char* module_path;      // input
    char* result_source;    // output
    int ready;
} resolver_callback_data_t;

static char* resolve_module_callback(void* thread, const char* module_path) {
    // Lock, set module_path, call threadsafe function, wait on condition
    // Return result_source (or NULL)
}

static void resolver_js_callback(napi_env env, napi_value js_callback,
                                  void* context, void* data) {
    // Call JS: result = resolveModule(modulePath)
    // Extract result string or null
    // Signal condition to wake background thread
}
```

### Node API Changes

**`resolver.ts`:**
- Export `ModuleResolver` type
- Implement four factory functions as described in API Design section
- Add `adm-zip` dependency for JAR extraction

**`ffi.ts`:**
- Add `ResolveModuleCallbackFn` type
- Add bindings for `run_script_with_resolver`, `run_script_input_output_callback_with_resolver`, `run_transform_with_resolver`

**`dataweave.ts`:**
- Add `resolveModule?: ModuleResolver` to `DataWeaveOptions`
- Store resolver in constructor
- Route `run()`, `runStreaming()`, `runTransform()` to `*_with_resolver` variants when resolver is present

**`package.json`:**
- Add `adm-zip` dependency (for JAR extraction)

## Backward Compatibility

**Fully backward compatible:**
- `resolveModule` is optional; omit it and behavior is identical to today
- Module-level `run()` singleton stays no-resolver by default
- Existing tests pass unchanged

**Migration path:**
- Users with no external modules: no changes needed
- Users needing external modules: switch to `new DataWeave({ resolveModule })` pattern

## Known Limitations

**One resolver per process:**
- Multiple `DataWeave` instances with different resolvers will log warning and keep first
- Workaround: use `composeResolvers()` to combine all module sources
- Rationale: engine and GraalVM isolate are process-wide singletons (see proposal for detailed explanation)

**Resolver must be synchronous:**
- No `async`/`await` inside resolver function (cannot return `Promise`)
- Workaround: async preprocessing (e.g., `await modulesFromJars()` before passing to constructor)
- Rationale: DataWeave module resolution happens synchronously at compile time

**JAR dependency resolution outside binding:**
- No built-in Maven/coursier support
- Users provide JAR paths manually or via external tooling
- Future enhancement: npm task for dependency resolution

## Risks & Mitigation

**Script cache coherence:**
- Risk: engine caches compiled scripts; different resolver states could cause cache misses or stale lookups
- Mitigation: binding resolver to engine construction (one engine → one resolver) keeps cache coherent
- Verification: test that recompiling same script with same resolver uses cache

**C-string lifetime:**
- Risk: native code uses string after JS frees it, or JS frees before native copies
- Mitigation: explicit contract — native copies immediately, C addon frees after native call returns
- Verification: test with large modules to catch use-after-free

**Native-image build:**
- Risk: adding Scala engine constructor imports may trigger new `--initialize-at-*` requirements
- Mitigation: review native-image build output, update build args if needed
- Verification: `native-lib:nativeCompile` stays green

**Trust model:**
- Risk: resolver has no sandboxing; malicious resolver could return arbitrary code
- Mitigation: same trust model as CLI's file-based resolution (user controls module source)
- Documentation: clarify that resolver runs with full process permissions

## Future Enhancements

**Python binding:**
- Native changes are generic (C callback mechanism)
- Python can follow with thin binding layer implementing resolver factories

**Dependency resolution tooling:**
- npm task to download Maven dependencies (coursier wrapper)
- `dependencies.dwl` support in Node.js (mirrors CLI spell dependencies)

**Multiple isolated engines:**
- Support multiple resolvers per process via isolate handles
- Requires dropping singleton pattern in `ScriptRuntime` and `addon.c`
- Only pursue if concrete need (e.g., multi-tenant isolation) appears

## References

| Item | Location |
|------|----------|
| Proposal | `docs/proposals/nodejs-external-modules.md` |
| Root cause (no resolver) | `native-lib/src/main/java/org/mule/weave/lib/ScriptRuntime.java:42-44` |
| CLI resolver pattern | `native-cli/src/main/scala/org/mule/weave/dwnative/NativeRuntime.scala:54-60` |
| Existing callback infrastructure | `native-lib/src/main/java/org/mule/weave/lib/NativeCallbacks.java:16-49` |
| Callback bridge | `native-lib/node/src/addon.c:381-478` |
| Excluded TCK tests | `native-lib/node/tests/tck/ignore-list.ts:31-36` |
