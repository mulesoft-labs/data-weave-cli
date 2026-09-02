# PR #157 Review 22 Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve all eight PR #157 review-22 findings and deliver a verified PR from `w-23692110-review-22-fixes` to `w-23692110-multi-engine-design`.

**Architecture:** The Java engine registry owns `LIVE -> CLOSING -> DESTROYED` records and leases so the raw C ABI is lifetime-safe. Node and Python add same-OS-thread native-callback guards and immutable `{handle, generation}` operation tokens; Node additionally uses one credit/ack flow-control object per asynchronous stream to bound native and JavaScript buffering and to cancel abandoned consumers safely.

**Tech Stack:** Java 17, GraalVM Community Java 24 Native Image, C11/N-API 8/libuv, TypeScript 5.5/Node.js 18+, Python 3.9+/ctypes, Gradle, JUnit 5, Vitest 3, pytest.

**Spec:** `docs/superpowers/specs/2026-09-02-pr157-review-22-remediation-design.md`

## Global Constraints

- Use the checked-in `./gradlew` wrapper and GraalVM Community Java 24 for native verification.
- Java remains source/target 17; Scala remains 2.12; Python remains 3.9+; Node remains 18+.
- Preserve exported C names, argument order, callback semantics, and existing JSON wire fields.
- Normal script failures continue to return non-null `{"success":false,...}` envelopes.
- Never let Java, JavaScript, or Python exceptions unwind across C callbacks.
- Every OS thread calling Graal attaches its own isolate thread and detaches afterward unless a successful teardown has invalidated the attachment.
- Node shared C lifecycle state remains guarded by `g_mutex`; per-stream flow state uses its own mutex with explicit lock ordering.
- Python module isolate state remains guarded by `_isolate_lock`; user callbacks run without module locks held.
- Use test-first red-green cycles for every behavior change. Run the named failing test before editing production code and record the expected failure.
- Work only in `/private/var/folders/n2/069kfxz14k3dg0dctt0gblm80000gn/T/opencode/data-weave-cli-review-22-fixes` on `w-23692110-review-22-fixes`.
- Do not modify unrelated untracked or generated artifacts. Do not commit `node_modules`, staged `native/`, `dist`, native build outputs, coverage, wheels, or downloaded TCK suites.

## File Map

- `native-lib/src/main/java/org/mule/weave/lib/ScriptRuntime.java`: owns the Java engine registry, lifecycle records, and operation leases.
- `native-lib/src/main/java/org/mule/weave/lib/CEntryPointExceptionHandlers.java`: contains allocation-free GraalVM exception handlers by ABI return category.
- `native-lib/src/main/java/org/mule/weave/lib/NativeLib.java`: validates exported entrypoint inputs, acquires core leases, and applies exception sentinels.
- `native-lib/src/test/java/org/mule/weave/lib/ScriptRuntimeLifecycleTest.java`: exercises hosted Java lease and destroy concurrency.
- `native-lib/src/test/java/org/mule/weave/lib/NativeLibEntryPointContractTest.java`: verifies every exported entrypoint declares the intended exception handler.
- `native-lib/python/src/dataweave/native.py`: owns Python immutable operation tokens, serialized admission, and callback-thread-local state.
- `native-lib/python/src/dataweave/runtime.py`: captures operation generations at public API entry and binds stream workers to them.
- `native-lib/python/tests/unit/test_native.py`: tests immutable native admission and callback TLS.
- `native-lib/python/tests/unit/test_streaming.py`: tests stale stream rejection and callback wrappers.
- `native-lib/python/tests/integration/test_module_resolver.py`: isolates resolver reentrancy so a regression cannot kill pytest.
- `native-lib/python/tests/integration/test_lifecycle.py`: holds direct ctypes ABI sentinel and lease/drain subprocess tests.
- `native-lib/node/src/addon.c`: owns authoritative callback TLS, asynchronous stream flow control, cancellation, and detach fault injection.
- `native-lib/node/src/ffi.ts`: types and wraps the internal native streaming operation controller.
- `native-lib/node/src/stream.ts`: acknowledges chunks at dequeue and cancels abandoned generators.
- `native-lib/node/src/dataweave.ts`: owns Node generations, token validation, public error mapping, and active-stream cleanup.
- `native-lib/node/tests/unit/dataweave-initialize.test.ts`: tests Node generation capture, handle reuse, and active-stream cleanup.
- `native-lib/node/tests/unit/stream.test.ts`: tests credit acknowledgment and cancellation semantics without native code.
- `native-lib/node/tests/integration/resolver-reentrancy.test.ts`: child-process proof that nested resolver execution no longer exits 99.
- `native-lib/node/tests/integration/stream-backpressure.test.ts`: real-addon bounded-buffer and cancellation tests.
- `native-lib/node/tests/integration/detach-poison-hook.test.ts`: child-process detach-poison and fresh-isolate recovery tests.
- `native-lib/README.md`, `native-lib/node/README.md`, `native-lib/python/README.md`, and the consolidated design: public and maintainer-facing contract updates.

---

### Task 1: Core Java Engine Leases

**Files:**
- Create: `native-lib/src/test/java/org/mule/weave/lib/ScriptRuntimeLifecycleTest.java`
- Modify: `native-lib/src/test/java/org/mule/weave/lib/ScriptRuntimeTest.java:629-691`
- Modify: `native-lib/src/main/java/org/mule/weave/lib/ScriptRuntime.java:40-59`

**Interfaces:**
- Consumes: existing `ScriptRuntime.register(ScriptRuntime)` and `ScriptRuntime.destroy(long)` semantics.
- Produces: `ScriptRuntime.EngineLease acquire(long handle)`, `EngineLease.runtime()`, `EngineLease.close()`, and blocking/idempotent `destroy(long handle)`.

- [ ] **Step 1: Write lifecycle tests that expose the missing lease**

Create `ScriptRuntimeLifecycleTest` with deterministic latches. The central test shape is:

```java
@Test
void destroyWaitsForAnAdmittedLeaseAndRejectsNewAdmission() throws Exception {
    long handle = ScriptRuntime.register(new ScriptRuntime());
    ScriptRuntime.EngineLease lease = ScriptRuntime.acquire(handle);
    assertNotNull(lease);

    CountDownLatch destroyStarted = new CountDownLatch(1);
    AtomicBoolean destroyReturned = new AtomicBoolean(false);
    Thread destroyer = new Thread(() -> {
        destroyStarted.countDown();
        ScriptRuntime.destroy(handle);
        destroyReturned.set(true);
    });
    destroyer.start();

    assertTrue(destroyStarted.await(1, TimeUnit.SECONDS));
    awaitCondition(() -> ScriptRuntime.acquire(handle) == null);
    assertFalse(destroyReturned.get());

    lease.close();
    destroyer.join(1_000);
    assertFalse(destroyer.isAlive());
    assertTrue(destroyReturned.get());
    assertNull(ScriptRuntime.acquire(handle));
}
```

Add focused cases for:

```java
multipleLeasesMustAllDrainBeforeDestroyReturns();
concurrentDestroyCallsCoordinateAndComplete();
closingAnEngineLeaseTwiceIsHarmless();
interruptedDestroyRestoresInterruptAfterTheLeaseDrains();
unknownHandleCannotAcquireALease();
```

Use a local polling helper with a one-second deadline instead of sleeps. Every test must close admitted leases in `finally`.

- [ ] **Step 2: Run the lifecycle test and verify RED**

Run:

```bash
./gradlew native-lib:test --tests "org.mule.weave.lib.ScriptRuntimeLifecycleTest" -PskipNodeTests=true -PskipPythonTests=true
```

Expected: compilation fails because `ScriptRuntime.EngineLease` and `ScriptRuntime.acquire(long)` do not exist.

- [ ] **Step 3: Implement the lifecycle record and lease**

Replace `ConcurrentHashMap<Long, ScriptRuntime>` with `ConcurrentHashMap<Long, EngineRecord>`. Keep all lifecycle types in `ScriptRuntime.java`:

```java
public static final class EngineLease implements AutoCloseable {
    private final EngineRecord record;
    private final ScriptRuntime runtime;
    private boolean closed;

    private EngineLease(EngineRecord record, ScriptRuntime runtime) {
        this.record = record;
        this.runtime = runtime;
    }

    public ScriptRuntime runtime() {
        return runtime;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        record.release();
    }
}
```

The record owns `State { LIVE, CLOSING, DESTROYED }`, `activeLeases`, `tryAcquire()`, `closeAndAwait()`, and `release()`. The state check and increment are one synchronized action. `closeAndAwait()` keeps waiting after `InterruptedException`, sets `DESTROYED` only after `activeLeases == 0`, and restores the interrupt bit after leaving the monitor.

`destroy(handle)` obtains the record, invokes `closeAndAwait()`, and removes exactly that record with `REGISTRY.remove(handle, record)`. Do not remove the record before draining because concurrent destroy callers need the same coordination object.

Make `register` reject null runtimes and ensure generated handles remain positive. If `NEXT_HANDLE.getAndIncrement()` returns a non-positive value after overflow, fail registration rather than publishing an invalid ABI handle.

- [ ] **Step 4: Update existing registry tests to use leases**

Replace every `ScriptRuntime.get(handle)` in `ScriptRuntimeTest` with scoped acquisition:

```java
try (ScriptRuntime.EngineLease lease = ScriptRuntime.acquire(hA)) {
    assertNotNull(lease);
    assertEquals("\"A:X\"", Result.parse(lease.runtime().run(IMPORT_A)).result);
}
```

Use `assertNull(ScriptRuntime.acquire(handle))` for absent or destroyed handles. Delete the Javadoc claim that checking `UNKNOWN_ENGINE_HANDLE_JSON` and `get()` verifies the complete entrypoint contract; retain only the exact-string assertion.

- [ ] **Step 5: Run focused and module Java tests and verify GREEN**

Run:

```bash
./gradlew native-lib:test --tests "org.mule.weave.lib.ScriptRuntimeLifecycleTest" -PskipNodeTests=true -PskipPythonTests=true
./gradlew native-lib:test --tests "org.mule.weave.lib.ScriptRuntimeTest" -PskipNodeTests=true -PskipPythonTests=true
```

Expected: both commands exit `0`; the new lifecycle class reports all tests passed.

- [ ] **Step 6: Commit the core lease**

```bash
git add native-lib/src/main/java/org/mule/weave/lib/ScriptRuntime.java \
  native-lib/src/test/java/org/mule/weave/lib/ScriptRuntimeLifecycleTest.java \
  native-lib/src/test/java/org/mule/weave/lib/ScriptRuntimeTest.java
git commit -m "fix(native-lib): lease engines across admitted operations"
```

### Task 2: C Entrypoint Exception Sentinels and Lease Adoption

**Files:**
- Create: `native-lib/src/main/java/org/mule/weave/lib/CEntryPointExceptionHandlers.java`
- Create: `native-lib/src/test/java/org/mule/weave/lib/NativeLibEntryPointContractTest.java`
- Modify: `native-lib/src/main/java/org/mule/weave/lib/NativeLib.java:39-45,540-665`
- Modify: `native-lib/node/src/addon.c:2269-2273,2409-2412`

**Interfaces:**
- Consumes: `ScriptRuntime.acquire(long)` and `EngineLease` from Task 1.
- Produces: explicit `ReturnZero`, `ReturnNullPointer`, and `ReturnVoid` C-entrypoint handlers; every run entrypoint is leased.

- [ ] **Step 1: Write reflection tests for all exported handlers**

Create `NativeLibEntryPointContractTest`. Use `NativeLib.class.getDeclaredMethod(...)` and `getAnnotation(CEntryPoint.class)` to assert exact handlers:

```java
assertEquals(
        CEntryPointExceptionHandlers.ReturnZero.class,
        annotation("createEngine", IsolateThread.class).exceptionHandler());
assertEquals(
        CEntryPointExceptionHandlers.ReturnNullPointer.class,
        annotation("runScriptEngine", IsolateThread.class, long.class,
                CCharPointer.class, CCharPointer.class).exceptionHandler());
assertEquals(
        CEntryPointExceptionHandlers.ReturnVoid.class,
        annotation("destroyEngine", IsolateThread.class, long.class).exceptionHandler());
```

Cover all seven exports, including `freeCString` and both callback run entrypoints.

- [ ] **Step 2: Run the handler contract test and verify RED**

Run:

```bash
./gradlew native-lib:test --tests "org.mule.weave.lib.NativeLibEntryPointContractTest" -PskipNodeTests=true -PskipPythonTests=true
```

Expected: compilation fails because `CEntryPointExceptionHandlers` does not exist.

- [ ] **Step 3: Add allocation-free GraalVM handlers**

Create the support class using `com.oracle.svm.core.Uninterruptible`:

```java
final class CEntryPointExceptionHandlers {
    private CEntryPointExceptionHandlers() {
    }

    static final class ReturnZero implements CEntryPoint.ExceptionHandler {
        @Uninterruptible(reason = "Return an ABI sentinel after an entrypoint exception")
        static long handle(Throwable ignored) {
            return 0L;
        }
    }

    static final class ReturnNullPointer implements CEntryPoint.ExceptionHandler {
        @Uninterruptible(reason = "Return an ABI sentinel after an entrypoint exception")
        static CCharPointer handle(Throwable ignored) {
            return WordFactory.nullPointer();
        }
    }

    static final class ReturnVoid implements CEntryPoint.ExceptionHandler {
        @Uninterruptible(reason = "Contain an exception at the C ABI boundary")
        static void handle(Throwable ignored) {
        }
    }
}
```

Each nested handler class must declare exactly one method. Do not allocate, log, or throw in these methods.

- [ ] **Step 4: Apply handlers and leases to all entrypoints**

Annotate every export with the intended `exceptionHandler`. Change each run entrypoint from `ScriptRuntime.get(handle)` to:

```java
try (ScriptRuntime.EngineLease lease = ScriptRuntime.acquire(handle)) {
    if (lease == null) {
        return toUnmanagedCString(UNKNOWN_ENGINE_HANDLE_JSON);
    }
    // Convert required pointers and execute through lease.runtime().
}
```

Validate required pointer and callback arguments before dereference. Return a non-null `success:false` envelope for expected invalid arguments when possible. Keep null-pointer result only as the exception-handler fallback.

The lease must include the callback loop, feeder join, and final result allocation. Keep `destroyEngine` idempotent and blocking through `ScriptRuntime.destroy(handle)`.

Update the two addon comments so they state that `0` is the explicit Java ABI exception sentinel, not GraalVM default-value behavior.

- [ ] **Step 5: Run Java tests and verify GREEN**

Run:

```bash
./gradlew native-lib:test --tests "org.mule.weave.lib.NativeLibEntryPointContractTest" -PskipNodeTests=true -PskipPythonTests=true
./gradlew native-lib:test --tests "org.mule.weave.lib.ScriptRuntimeLifecycleTest" -PskipNodeTests=true -PskipPythonTests=true
```

Expected: both commands exit `0`.

- [ ] **Step 6: Build the native library and inspect generated exports**

Run with the checked-in GraalVM:

```bash
export GRAALVM_HOME="/Users/lmariano/dev/mulesoft/data-weave-cli/.graalvm/graalvm-community-openjdk-24.0.2+11.1/Contents/Home"
export JAVA_HOME="$GRAALVM_HOME"
./gradlew native-lib:nativeCompile -PskipStripDebug=true
```

Expected: exit `0`; Native Image accepts all custom handlers. Verify `native-lib/build/native/nativeCompile/dwlib.h` still exports the same seven C names with unchanged signatures.

- [ ] **Step 7: Commit the ABI containment**

```bash
git add native-lib/src/main/java/org/mule/weave/lib/CEntryPointExceptionHandlers.java \
  native-lib/src/main/java/org/mule/weave/lib/NativeLib.java \
  native-lib/src/test/java/org/mule/weave/lib/NativeLibEntryPointContractTest.java \
  native-lib/node/src/addon.c
git commit -m "fix(native-lib): contain C entrypoint exceptions"
```

### Task 3: Raw ABI Native Regression Tests

**Files:**
- Modify: `native-lib/python/tests/integration/test_lifecycle.py`

**Interfaces:**
- Consumes: native sentinels from Task 2 and blocking `destroy_engine` lease semantics from Task 1.
- Produces: isolated subprocess tests for process survival and resolver-context drain.

- [ ] **Step 1: Add null-resolver subprocess coverage**

Add a helper that runs Python code with `DATAWEAVE_NATIVE_LIB` and the package source on `PYTHONPATH`. In the child, bind the raw ABI with `ctypes`, create an isolate, detach bootstrap, attach the current thread, and call:

```python
null_resolver = ctypes.cast(None, RESOLVE_MODULE_CALLBACK)
handle = lib.create_engine_with_resolver(thread, null_resolver, None)
assert handle == 0
healthy = lib.create_engine(thread)
assert healthy > 0
null_result = lib.run_script_engine(thread, healthy, None, None)
assert not null_result
lib.destroy_engine(thread, healthy)
```

The child must not call `free_cstring` for the null result pointer. It then cleans up/detaches correctly and prints one JSON object. Parent assertions require exit `0`, `handle == 0`, a valid later handle, a null run result, and no `Fatal error` in stderr.

- [ ] **Step 2: Add a resolver-context lease/drain subprocess test**

Use two OS threads and direct ctypes, bypassing `NativeRuntime` serialization:

```python
resolver_entered = Event()
release_resolver = Event()
destroy_returned = Event()

@RESOLVE_MODULE_CALLBACK
def resolver(_thread, _ctx, _path):
    resolver_entered.set()
    assert release_resolver.wait(5)
    return module_source_address
```

Thread A attaches and calls `run_script_engine` on a resolver-backed engine. Thread B attaches only after `resolver_entered`, calls `destroy_engine`, and sets `destroy_returned` afterward. Assert in the parent process logic that `destroy_returned.wait(0.1)` is false before releasing the callback, then true after run completion. Retain the resolver source buffer until destroy returns.

- [ ] **Step 3: Prove the new tests fail against an unmodified base-branch native library**

Create a disposable verification worktree at the base branch and build its native library:

```bash
git worktree add --detach \
  "/var/folders/n2/069kfxz14k3dg0dctt0gblm80000gn/T/opencode/data-weave-cli-review22-red" \
  w-23692110-multi-engine-design
GRAALVM_HOME="/Users/lmariano/dev/mulesoft/data-weave-cli/.graalvm/graalvm-community-openjdk-24.0.2+11.1/Contents/Home" \
JAVA_HOME="/Users/lmariano/dev/mulesoft/data-weave-cli/.graalvm/graalvm-community-openjdk-24.0.2+11.1/Contents/Home" \
  ./gradlew native-lib:nativeCompile -PskipStripDebug=true
```

Run the fix branch's new tests against that base library:

```bash
cd native-lib/python
DATAWEAVE_NATIVE_LIB="/var/folders/n2/069kfxz14k3dg0dctt0gblm80000gn/T/opencode/data-weave-cli-review22-red/native-lib/build/native/nativeCompile/dwlib.dylib" \
  python3 -m pytest tests/integration/test_lifecycle.py -k "raw_abi" -q
```

Expected on the old implementation: the null-resolver child exits `99`, and the destroy-drain assertion reports that destroy returned while the resolver remained blocked. Remove the disposable worktree afterward with `git worktree remove "/var/folders/n2/069kfxz14k3dg0dctt0gblm80000gn/T/opencode/data-weave-cli-review22-red"`; do not edit or commit from it.

- [ ] **Step 4: Run the native tests against the fixed library and verify GREEN**

Run:

```bash
export GRAALVM_HOME="/Users/lmariano/dev/mulesoft/data-weave-cli/.graalvm/graalvm-community-openjdk-24.0.2+11.1/Contents/Home"
export JAVA_HOME="$GRAALVM_HOME"
./gradlew native-lib:nativeCompile -PskipStripDebug=true
cd native-lib/python
DATAWEAVE_NATIVE_LIB="../build/native/nativeCompile/dwlib.dylib" \
  python3 -m pytest tests/integration/test_lifecycle.py -k "raw_abi" -q
```

Expected: child processes exit `0`; destroy remains blocked until the callback is released.

- [ ] **Step 5: Commit the raw ABI regressions**

```bash
git add native-lib/python/tests/integration/test_lifecycle.py
git commit -m "test(native-lib): cover C ABI failure and lease contracts"
```

### Task 4: Python Callback Reentrancy Guard

**Files:**
- Modify: `native-lib/python/src/dataweave/native.py:1-12,344-623`
- Modify: `native-lib/python/src/dataweave/runtime.py:44-75,122-134,225-249,263-287`
- Modify: `native-lib/python/tests/unit/test_native.py`
- Modify: `native-lib/python/tests/unit/test_streaming.py:98-175`
- Modify: `native-lib/python/tests/integration/test_module_resolver.py:240-323`

**Interfaces:**
- Consumes: existing `DataWeaveError` and callback wrappers.
- Produces: `_native_callback_scope()`, `_raise_if_native_callback_active()`, and process-wide thread-local callback depth.

- [ ] **Step 1: Add isolated cross-engine resolver reentry test**

Add a child-process test next to `test_overlapping_resolver_aware_runs_are_serialized`. The child initializes `inner` and resolver-backed `outer`; the outer resolver calls `inner.run("40 + 2")`, catches `DataWeaveError`, and returns `%dw 2.0\nfun answer() = 42`. Print JSON containing nested error type/message and outer result.

Parent assertions:

```python
assert completed.returncode == 0
assert response["nested_type"] == "DataWeaveError"
assert "native callback" in response["nested_error"].lower()
assert response["outer"] == {"success": True, "value": "42"}
assert "Fatal error" not in completed.stderr
```

- [ ] **Step 2: Run the integration test and verify RED**

Run against the staged native library:

```bash
cd native-lib/python
python3 -m pytest tests/integration/test_module_resolver.py -k "cross_engine_resolver_reentry" -q
```

Expected: child exits `99` with GraalVM thread-state fatal stderr.

- [ ] **Step 3: Add unit tests for shared thread-local guard behavior**

Test that callback scope on one thread rejects `capture_operation`, `initialize`, and `cleanup` on any instance on that same thread, but does not reject another Python thread. Extend write/read callback tests so reentry through a second `DataWeave` instance returns callback status `-1` and never calls its native attach function.

- [ ] **Step 4: Implement callback TLS and guard all isolate-touching public paths**

Import `local` and define:

```python
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
```

Wrap only the direct user callback invocation, not parsing or diagnostics. Apply it to resolver, public write callback, and public read callback wrappers. Check `_raise_if_native_callback_active()` before lifecycle locks and before serialized native admission. Do not hold `_isolate_lock`, `_resolver_lock_global`, or an instance operation lock while user code runs.

- [ ] **Step 5: Run focused Python tests and verify GREEN**

```bash
cd native-lib/python
python3 -m pytest tests/unit/test_native.py -k "native_callback" -q
python3 -m pytest tests/unit/test_streaming.py -k "reentry" -q
python3 -m pytest tests/integration/test_module_resolver.py -k "cross_engine_resolver_reentry" -q
```

Expected: all selected tests pass; the child process remains alive.

- [ ] **Step 6: Commit the Python callback guard**

```bash
git add native-lib/python/src/dataweave/native.py \
  native-lib/python/src/dataweave/runtime.py \
  native-lib/python/tests/unit/test_native.py \
  native-lib/python/tests/unit/test_streaming.py \
  native-lib/python/tests/integration/test_module_resolver.py
git commit -m "fix(python): reject native callback reentrancy"
```

### Task 5: Python Atomic Admission and Generation-Bound Streams

**Files:**
- Modify: `native-lib/python/src/dataweave/native.py:344-492,552-600`
- Modify: `native-lib/python/src/dataweave/runtime.py:87-103,109-223,251-287`
- Modify: `native-lib/python/tests/unit/test_native.py`
- Modify: `native-lib/python/tests/unit/test_streaming.py`
- Modify: `native-lib/python/tests/integration/test_streaming.py`

**Interfaces:**
- Consumes: `_raise_if_native_callback_active()` from Task 4.
- Produces: frozen `_EngineOperation(handle, generation)`, `capture_operation()`, `validate_operation()`, and token-taking native run methods.

- [ ] **Step 1: Write deterministic paused-admission test**

Initialize a wrapper with `FakeLibrary`. Patch `_require_initialized` so it captures and returns generation A, signals an event, and waits. While the run thread is paused, cleanup and reinitialize to generation B, then resume. Assert `DataWeaveError` contains `stale engine generation` and the fake `run_script_engine` was never called with B.

Add a complementary test that blocks after serialized admission, starts cleanup, and proves the admitted operation uses A before cleanup destroys A.

- [ ] **Step 2: Write stale stream tests before implementation**

Parameterize `run_streaming` and `run_transform`:

```python
stream = create_stream(runtime)
old_handle = runtime._native.handle
runtime.cleanup()
runtime.initialize()
assert runtime._native.handle != old_handle
with pytest.raises(dataweave.DataWeaveError, match="stale engine generation"):
    next(stream)
```

Assert no worker registered, no attach occurred for the stale stream, and no callback entrypoint received the replacement handle. Add a handle-reuse variant where the fake returns the same numeric handle for both generations.

- [ ] **Step 3: Run focused tests and verify RED**

```bash
cd native-lib/python
python3 -m pytest tests/unit/test_native.py -k "admission_generation" -q
python3 -m pytest tests/unit/test_streaming.py -k "stale_generation" -q
```

Expected: the paused run executes replacement handle B, and old streams execute instead of raising.

- [ ] **Step 4: Implement immutable operation tokens**

Add:

```python
@dataclass(frozen=True)
class _EngineOperation:
    handle: int
    generation: int
```

`NativeRuntime` keeps monotonic `_generation` and canonical `_engine_operation`. Publish a new token only after successful engine creation. Clear `_engine_operation` during cleanup but never reset `_generation`.

`capture_operation()` fails when uninitialized and returns the immutable token. `_serialized_native_operation(expected)` checks callback TLS before waiting, acquires the existing per-instance lock, validates exact token equality, and yields the token. All native run methods accept `operation` explicitly and call `operation.handle`; none reads `self.handle` after admission.

- [ ] **Step 5: Bind public calls and worker registration to captured tokens**

Change `_require_initialized` to return `_EngineOperation`. Capture it in `run`, `run_callback`, `run_input_output_callback`, `run_streaming`, and `run_transform` before constructing callbacks or generators.

Change `_stream_worker(operation, invoke, cancelled)` and `_register_stream_worker(worker, operation)`. Under `_stream_workers_lock`, reject `_cleaning_up`, validate the operation, then register. Native worker closures carry `operation` into the token-taking native method.

Keep lock order `_stream_workers_lock -> brief token validation`; native execution must release its operation lock before `_unregister_stream_worker` takes the worker lock.

- [ ] **Step 6: Run unit and real-native stale-stream tests and verify GREEN**

```bash
cd native-lib/python
python3 -m pytest tests/unit/test_native.py -k "admission_generation" -q
python3 -m pytest tests/unit/test_streaming.py -k "stale_generation" -q
python3 -m pytest tests/integration/test_streaming.py -k "stale_generation" -q
```

Expected: stale operations fail before replacement-handle invocation; admitted work stays on its captured engine.

- [ ] **Step 7: Run the complete Python unit lane**

```bash
cd native-lib/python
python3 -m pytest -m unit -q
```

Expected: all unit tests pass. Update `configured_runtime` test helpers to initialize `_generation` and `_engine_operation` explicitly rather than weakening production fallbacks.

- [ ] **Step 8: Commit Python generations**

```bash
git add native-lib/python/src/dataweave/native.py \
  native-lib/python/src/dataweave/runtime.py \
  native-lib/python/tests/unit/test_native.py \
  native-lib/python/tests/unit/test_streaming.py \
  native-lib/python/tests/integration/test_streaming.py
git commit -m "fix(python): bind operations to engine generations"
```

### Task 6: Node Callback Reentrancy Guard

**Files:**
- Modify: `native-lib/node/src/addon.c:31-59,2094-2231,2235-2719,3373-3453`
- Modify: `native-lib/node/src/ffi.ts`
- Modify: `native-lib/node/src/dataweave.ts:73-139,229-323`
- Create: `native-lib/node/tests/integration/resolver-reentrancy.test.ts`
- Create: `native-lib/node/tests/integration/fixtures/resolver-reentrancy.cjs`

**Interfaces:**
- Consumes: existing N-API methods and `DataWeaveError`.
- Produces: addon error code `ERR_DATAWEAVE_CALLBACK_REENTRANCY` and TypeScript `callNative()` error mapping.

- [ ] **Step 1: Write the child-process resolver reentry regression**

The fixture creates inner and outer `DataWeave` instances. The resolver attempts `inner.run`, catches the error, returns a valid module, prints JSON, and cleans both instances in `finally`. Parent asserts exit `0`, nested error name `DataWeaveError`, outer result `42`, and no Graal fatal stderr.

Add a raw-addon case in the same fixture that recursively calls `runScriptEngine` from `createEngineWithResolver` and records the addon's stable error code.

- [ ] **Step 2: Run the resolver test and verify RED**

```bash
cd native-lib/node
npm run test:integration -- tests/integration/resolver-reentrancy.test.ts
```

Expected: child exits `99` with `Must either be at a safepoint or in native mode`.

- [ ] **Step 3: Implement OS-thread-local callback depth in the addon**

Initialize a `uv_key_t` in the existing `uv_once` initializer. Add helpers:

```c
static unsigned native_callback_depth(void);
static void native_callback_enter(void);
static void native_callback_exit(void);
static bool native_callback_active(void);
static napi_value throw_callback_reentrancy(napi_env env);
```

Wrap `napi_call_function` in `resolve_module_callback`, `call_js_read`, and output callback bridges with enter/exit on all statuses. Reject callback reentry at the start of create, create-with-resolver, destroy, synchronous run, stream start, transform start, and cleanup before any state mutation or attachment. Set JavaScript error property `code` to `ERR_DATAWEAVE_CALLBACK_REENTRANCY` before throwing.

- [ ] **Step 4: Map the native error to DataWeaveError**

In `ffi.ts`, normalize calls through:

```ts
function callNative<T>(invoke: () => T): T {
  try {
    return invoke();
  } catch (error) {
    if (error && typeof error === "object" &&
        "code" in error && error.code === "ERR_DATAWEAVE_CALLBACK_REENTRANCY") {
      throw new DataWeaveError(String((error as Error).message));
    }
    throw error;
  }
}
```

Apply it to every isolate-touching wrapper. Avoid wrapping promise rejections twice; only normalize synchronous native admission errors.

- [ ] **Step 5: Build and run resolver tests and verify GREEN**

```bash
cd native-lib/node
npm run build:addon
npm run build:ts
npm run test:integration -- tests/integration/resolver-reentrancy.test.ts
```

Expected: build exits `0`; child processes survive and return the intended typed errors.

- [ ] **Step 6: Commit the Node callback guard**

```bash
git add native-lib/node/src/addon.c native-lib/node/src/ffi.ts \
  native-lib/node/src/dataweave.ts \
  native-lib/node/tests/integration/resolver-reentrancy.test.ts \
  native-lib/node/tests/integration/fixtures/resolver-reentrancy.cjs
git commit -m "fix(node): reject native callback reentrancy"
```

### Task 7: Node Generation-Bound Lazy Streams

**Files:**
- Modify: `native-lib/node/src/dataweave.ts:49-55,81-139,186-216,229-323`
- Modify: `native-lib/node/tests/unit/dataweave-initialize.test.ts`
- Modify: `native-lib/node/tests/integration/instance-lifecycle.test.ts`

**Interfaces:**
- Consumes: existing handle-based FFI and `DataWeaveError`.
- Produces: internal `EngineOperationToken`, `captureOperationToken()`, `assertCurrentOperation(token)`, and private token-taking async generators.

- [ ] **Step 1: Add unit tests for stale streams and handle reuse**

Mock FFI to return handle `2`, create `runStreaming()` without iterating, cleanup, initialize with handle `3`, and assert first pull rejects before `runScriptStreamingEngine` is called. Repeat with handle `2` reused; generation must still reject. Mirror both cases for `runTransform`.

Add an async transform-input test that pauses `createChunkReader` consumption, performs cleanup/reinitialize, resumes input, and asserts no transform native call.

- [ ] **Step 2: Run the Node generation tests and verify RED**

```bash
cd native-lib/node
npm run test:unit -- tests/unit/dataweave-initialize.test.ts -t "stale engine generation"
```

Expected: old streams call FFI with the replacement handle or otherwise fail the no-call assertion.

- [ ] **Step 3: Implement immutable Node tokens**

Add:

```ts
interface EngineOperationToken {
  readonly handle: number;
  readonly generation: number;
}
```

Increment `engineGeneration` only after successful handle publication. Never reset it in cleanup. `captureOperationToken()` checks readiness and returns current identity. `assertCurrentOperation()` requires state ready, exact handle, and exact generation; stale work throws `DataWeaveError("DataWeave operation belongs to a stale engine generation.")`.

Convert public stream APIs to ordinary methods:

```ts
runStreaming(...): AsyncGenerator<Buffer, StreamingResult, undefined> {
  const token = this.captureOperationToken();
  return this.runStreamingInternal(token, script, inputs);
}
```

Private async generators validate immediately before native admission and use `token.handle`. `runTransformInternal` validates before and after async input pre-buffering.

- [ ] **Step 4: Add real-native stale stream coverage**

In `instance-lifecycle.test.ts`, hold an anchor instance initialized so Java handles do not reset with isolate teardown. Create target stream under old handle, cleanup/reinitialize target, then consume old stream and assert `DataWeaveError`; a new stream must still succeed. Cover stream and transform.

- [ ] **Step 5: Run focused tests and typecheck and verify GREEN**

```bash
cd native-lib/node
npm run test:unit -- tests/unit/dataweave-initialize.test.ts
npm run test:integration -- tests/integration/instance-lifecycle.test.ts
npm run build:ts
```

Expected: all commands exit `0` and FFI receives captured handles only.

- [ ] **Step 6: Commit Node generations**

```bash
git add native-lib/node/src/dataweave.ts \
  native-lib/node/tests/unit/dataweave-initialize.test.ts \
  native-lib/node/tests/integration/instance-lifecycle.test.ts
git commit -m "fix(node): bind lazy streams to engine generations"
```

### Task 8: TypeScript Streaming Operation and Consumer Credits

**Files:**
- Modify: `native-lib/node/src/ffi.ts:4-27,58-87`
- Modify: `native-lib/node/src/stream.ts`
- Modify: `native-lib/node/src/dataweave.ts:49-56,160-216,254-313`
- Modify: `native-lib/node/tests/unit/stream.test.ts`
- Modify: `native-lib/node/tests/unit/dataweave-initialize.test.ts`

**Interfaces:**
- Consumes: token-taking stream methods from Task 7.
- Produces: internal `NativeStreamingOperation` with `completion`, `acknowledge`, `cancel`, and `close`; active-operation cleanup tracking.

- [ ] **Step 1: Rewrite unit fakes around a streaming controller and add credit assertions**

Define a test helper:

```ts
function operation(completion: Promise<string>) {
  return {
    completion,
    acknowledge: vi.fn(),
    cancel: vi.fn(),
    close: vi.fn(),
  };
}
```

Add tests proving:

- pushing a chunk into the JS queue does not acknowledge it;
- the first `.next()` acknowledges exactly that chunk's byte length before yielding;
- draining buffered chunks acknowledges each once;
- `generator.return(undefined)` cancels and closes once;
- native rejection still acknowledges already-buffered chunks before throwing;
- zero-chunk completion closes without cancellation;
- cancel/close paths are idempotent.

Rename the current test containing `(backpressure)` to describe parking/wakeup only.

- [ ] **Step 2: Run stream unit tests and verify RED**

```bash
cd native-lib/node
npm run test:unit -- tests/unit/stream.test.ts
```

Expected: TypeScript compilation fails because the start callback still returns `Promise<string>` and has no credit methods.

- [ ] **Step 3: Implement the internal controller contract**

Export from `ffi.ts`:

```ts
export interface NativeStreamingOperation {
  readonly completion: Promise<string>;
  acknowledge(bytes: number): void;
  cancel(): void;
  close(): void;
}
```

Change addon method typings and wrappers to return this object for stream and transform. Change `StartStreaming` accordingly.

In `streamFromNative`, call `start` once, attach completion handlers, acknowledge immediately when dequeuing, and use `try/finally` to cancel only when iteration ended before native completion. Always `close()` exactly once after settlement/finalization. When abandoning buffered chunks, acknowledge their lengths before clearing them.

- [ ] **Step 4: Track and cancel active operations during DataWeave cleanup**

Add `activeStreams: Set<NativeStreamingOperation>`. Register an operation when native start returns and unregister it from `streamFromNative`'s close callback. In `doCleanup()`, synchronously cancel a snapshot before `ffi.destroyEngine`; await their completion settlements without masking the primary destroy/cleanup error.

Do not start native work at stream method call. Registration still occurs on first iteration after generation validation.

- [ ] **Step 5: Run TypeScript tests and verify GREEN**

```bash
cd native-lib/node
npm run test:unit -- tests/unit/stream.test.ts
npm run test:unit -- tests/unit/dataweave-initialize.test.ts
npm run build:ts
```

Expected: all commands exit `0`.

- [ ] **Step 6: Commit the TypeScript flow contract**

```bash
git add native-lib/node/src/ffi.ts native-lib/node/src/stream.ts \
  native-lib/node/src/dataweave.ts \
  native-lib/node/tests/unit/stream.test.ts \
  native-lib/node/tests/unit/dataweave-initialize.test.ts
git commit -m "fix(node): propagate streaming consumer credits"
```

### Task 9: Native Node Output Flow Control

**Files:**
- Modify: `native-lib/node/src/addon.c:1124-2092,3378-3453`
- Create: `native-lib/node/tests/integration/stream-backpressure.test.ts`

**Interfaces:**
- Consumes: TypeScript controller contract from Task 8.
- Produces: `output_flow_t`, finite TSFN queues, native acknowledge/cancel/close methods, and test-only flow statistics.

- [ ] **Step 1: Add integration tests that inspect a paused producer**

Extend the test-only addon interface with flow stats and fixed watermarks. Start a large `deferred=true` output, consume one chunk, pause, and poll until `paused` is true. Assert:

```ts
expect(stats.peakBufferedChunks).toBeLessThanOrEqual(stats.highChunks + 1);
expect(stats.peakBufferedBytes).toBeLessThanOrEqual(
  stats.highBytes + stats.largestChunkBytes
);
expect(completionSettled).toBe(false);
```

Resume slowly and verify complete ordered output and successful metadata. Mirror the pause/drain assertion for `runTransform`. Add early-return and cleanup-while-paused tests with bounded timeouts.

- [ ] **Step 2: Run the backpressure test and verify RED**

```bash
cd native-lib/node
npm run test:integration -- tests/integration/stream-backpressure.test.ts
```

Expected: native controller/stat hooks are missing, or peak buffering exceeds the intended bound.

- [ ] **Step 3: Implement `output_flow_t` and lifetime rules**

Add fixed constants:

```c
#define OUTPUT_HIGH_BYTES (1024 * 1024)
#define OUTPUT_LOW_BYTES (512 * 1024)
#define OUTPUT_HIGH_CHUNKS 128
#define OUTPUT_LOW_CHUNKS 64
#define OUTPUT_TSFN_QUEUE_SIZE 129
```

`output_flow_t` contains mutex, condition, outstanding/peak counters, largest chunk, paused/cancelled/done flags, and refcount. Implement create/retain/release/reserve/acknowledge/cancel/mark_done. `reserve` waits only on the native producer thread and admits one oversized chunk when the window is empty.

Each `chunk_data` records its flow pointer and accounted length. Reserve before allocation/enqueue; on OOM or TSFN enqueue failure, release the reserved credit. Non-sentinel JS callbacks keep credit outstanding after copying into a Buffer. Sentinel and env-dead paths cancel/settle and release ownership exactly once.

- [ ] **Step 4: Return native controller objects**

Instead of returning the bare promise, create an object with named properties/methods:

```text
completion: Promise<string>
acknowledge(bytes): void
cancel(): void
close(): void
```

Each method resolves the operation flow through N-API external data or a finalizer-safe holder. Validate bytes as a non-negative integer and make cancel/close idempotent. The holder keeps the flow alive until both worker and JS controller release it.

Use finite output TSFN queues for streaming and transform writes. Keep transform read TSFN behavior unchanged.

- [ ] **Step 5: Make cancellation unblock every producer path**

Cancellation broadcasts the flow condition. Write callbacks return `-1` after cancellation. Generator abandonment, cleanup, env teardown, JS callback allocation/call failure, and controller finalization all route through the same idempotent cancel. Never wait on a flow condition while holding `g_mutex`; release the flow mutex before bridge/global completion accounting.

- [ ] **Step 6: Run addon, focused unit, and integration tests and verify GREEN**

```bash
cd native-lib/node
npm run build:addon
npm run build:ts
npm run test:unit -- tests/unit/stream.test.ts
npm run test:integration -- tests/integration/stream-backpressure.test.ts
npm run test:integration -- tests/integration/teardown-deadlock.test.ts
```

Expected: all commands exit `0`; paused producer counters remain bounded; early return and cleanup do not hang.

- [ ] **Step 7: Commit native backpressure**

```bash
git add native-lib/node/src/addon.c \
  native-lib/node/tests/integration/stream-backpressure.test.ts
git commit -m "fix(node): bound asynchronous output buffering"
```

### Task 10: Detach-Poison Failure Injection and Fail-Closed Admission

**Files:**
- Modify: `native-lib/node/src/addon.c:135-154,237-297,455-505,1241-1341,1767-1866,2235-2719,2761-2876,3378-3453`
- Create: `native-lib/node/tests/integration/detach-poison-hook.test.ts`
- Create: `native-lib/node/tests/integration/fixtures/detach-poison-sync.cjs`
- Create: `native-lib/node/tests/integration/fixtures/detach-poison-transform.cjs`
- Modify: `native-lib/node/vitest.config.ts:27-33`

**Interfaces:**
- Consumes: existing `g_isolate_poisoned` cleanup behavior.
- Produces: `detach_thread_checked(detach_site_t, void*)`, one-shot fault hooks, counters, and rejection of new work on a poisoned isolate.

- [ ] **Step 1: Add child-process poison tests**

Synchronous fixture sequence:

```text
initialize -> create engine -> arm sync-run detach failure -> run succeeds
-> poison is true -> new run/create rejects -> destroy + cleanup settle
-> teardown count unchanged, abandon count +1 -> initialize fresh -> run succeeds
```

Transform fixture arms the transform-worker site, starts final cleanup while the operation is active, completes the worker, and asserts cleanup skips teardown and resolves. Parent tests enforce timeouts and reject any exit `99`, signal, or fatal stderr.

- [ ] **Step 2: Run the poison tests and verify RED**

```bash
cd native-lib/node
npm run test:integration -- tests/integration/detach-poison-hook.test.ts
```

Expected: required test hooks are undefined.

- [ ] **Step 3: Centralize ordinary detach calls**

Add `detach_site_t` entries for bridge finalize, stream worker, transform worker, create engine, create rollback, resolver create, unknown destroy, and sync run. Replace the eight ordinary detach sites with `detach_thread_checked(site, thread)`. Leave teardown-helper follow-up detach calls direct because they classify teardown-plus-detach double failure rather than ordinary operation poison.

Under test hooks, call real detach first and substitute nonzero only when the selected one-shot site is armed and real detach succeeded. Count forced failures, isolate creates, teardown calls, and abandon operations under `g_mutex`.

- [ ] **Step 4: Reject new admission after poison**

In create/run/stream/transform admission critical sections, add `g_isolate_poisoned` to the rejection condition. Use a stable error `DataWeave isolate is unavailable after a thread detach failure; clean up and initialize again.` Existing admitted work continues draining; final cleanup follows `CLEANUP_UNRECOVERABLE` and abandons published state.

- [ ] **Step 5: Export test-only hooks and stats**

When `DATAWEAVE_TEST_HOOKS` is enabled, export:

```text
__test_forceDetachFailureOnce(site)
__test_isolatePoisoned()
__test_isolateCreationCount()
__test_teardownCallCount()
__test_abandonedIsolateCount()
```

Reject unknown site strings synchronously. Update the Vitest comment listing enabled hooks.

- [ ] **Step 6: Build and run poison/lifecycle tests and verify GREEN**

```bash
cd native-lib/node
npm run build:addon
npm run test:integration -- tests/integration/detach-poison-hook.test.ts
npm run test:integration -- tests/integration/engine-strand-hook.test.ts
npm run test:integration -- tests/integration/instance-lifecycle.test.ts
```

Expected: all commands exit `0`; cleanup never hangs; fresh isolate recovery succeeds.

- [ ] **Step 7: Commit detach-poison coverage**

```bash
git add native-lib/node/src/addon.c native-lib/node/vitest.config.ts \
  native-lib/node/tests/integration/detach-poison-hook.test.ts \
  native-lib/node/tests/integration/fixtures/detach-poison-sync.cjs \
  native-lib/node/tests/integration/fixtures/detach-poison-transform.cjs
git commit -m "test(node): inject detach failures across isolate recovery"
```

### Task 11: Documentation and Whitespace Contract

**Files:**
- Modify: `docs/superpowers/specs/2026-08-04-nodejs-external-modules-design.md:3-4,369-382,465`
- Modify: `native-lib/python/src/dataweave/native.py:624`
- Modify: `native-lib/README.md`
- Modify: `native-lib/node/README.md`
- Modify: `native-lib/python/README.md`
- Modify: `docs/superpowers/specs/2026-08-07-native-lib-multi-engine-design.md`

**Interfaces:**
- Consumes: final behavior from Tasks 1-10.
- Produces: accurate public ABI, lifecycle, reentrancy, streaming, cancellation, and failure-injection documentation.

- [ ] **Step 1: Remove only the reported whitespace errors**

Remove trailing spaces from the superseded Node design and remove the extra final blank line in `native.py`. Do not run a repository-wide formatter.

- [ ] **Step 2: Update raw ABI documentation**

In `native-lib/README.md`, document:

```text
create_engine* returns 0 on entrypoint failure.
run_*_engine returns NULL on entrypoint-level failure; do not free NULL.
Normal script failures remain non-NULL JSON envelopes.
destroy_engine closes admission and blocks until admitted operations drain.
Resolver/read/write ctx storage must remain valid through destroy_engine return.
destroy_engine must not be called synchronously from that engine's callback.
```

- [ ] **Step 3: Update binding documentation**

In both binding READMEs, document same-thread callback reentrancy rejection and stale-generation stream behavior. In Node docs, document internal byte/chunk watermarks as implementation details, cleanup cancellation of abandoned streams, and the fact that yielded buffers retained by user code are outside the bound.

Change the large-file writable example to await `drain`:

```ts
if (!output.write(chunk)) {
  await once(output, "drain");
}
```

Import `once` from `node:events` and preserve existing ESM/CommonJS style in that example.

- [ ] **Step 4: Update the consolidated design**

Replace `ConcurrentHashMap<Long, ScriptRuntime>`/`ScriptRuntime.get` descriptions with core lifecycle records and leases. Add binding generations, callback TLS, bounded output flow, cancel-on-cleanup, explicit exception sentinels, and detach test hooks. Correct Python stream cleanup wording to match active-worker refusal plus generation-safe registration.

- [ ] **Step 5: Verify documentation claims against symbols and tests**

Search all documented C names against `NativeLib.java`, `_bind_abi`, and `addon.c`; remove stale or invented names. Confirm every new error phrase matches production source exactly.

- [ ] **Step 6: Run whitespace verification and commit**

```bash
git diff --check w-23692110-multi-engine-design...HEAD
git add docs/superpowers/specs/2026-08-04-nodejs-external-modules-design.md \
  docs/superpowers/specs/2026-08-07-native-lib-multi-engine-design.md \
  native-lib/README.md native-lib/node/README.md native-lib/python/README.md \
  native-lib/python/src/dataweave/native.py
git commit -m "docs(native-lib): document hardened multi-engine contracts"
```

Expected: `git diff --check` exits `0` before commit.

### Task 12: Full Verification, Review, Push, and PR

**Files:**
- Modify only if verification or review reveals a defect in files already in scope.

**Interfaces:**
- Consumes: every preceding task.
- Produces: clean, reviewed branch and PR to `w-23692110-multi-engine-design`.

- [ ] **Step 1: Inspect branch state and commit range**

```bash
git status --short
git log --oneline --decorate w-23692110-multi-engine-design..HEAD
git diff --stat w-23692110-multi-engine-design...HEAD
git diff --check w-23692110-multi-engine-design...HEAD
```

Expected: no unintended tracked changes, focused commits only, and no whitespace errors.

- [ ] **Step 2: Run complete hosted Java verification**

```bash
./gradlew native-lib:test -PskipNodeTests=true -PskipPythonTests=true
```

Expected: exit `0`. If this task still triggers `nativeCompile` through plugin wiring, use the GraalVM environment from the next step rather than falling back to JDK 17.

- [ ] **Step 3: Build native library with GraalVM 24**

```bash
export GRAALVM_HOME="/Users/lmariano/dev/mulesoft/data-weave-cli/.graalvm/graalvm-community-openjdk-24.0.2+11.1/Contents/Home"
export JAVA_HOME="$GRAALVM_HOME"
./gradlew native-lib:nativeCompile -PskipStripDebug=true
```

Expected: exit `0` with `dwlib.dylib` generated.

- [ ] **Step 4: Run complete Python unit and integration lane**

```bash
cd native-lib/python
DATAWEAVE_NATIVE_LIB="../build/native/nativeCompile/dwlib.dylib" \
  python3 -m pytest -m "unit or integration" -q
```

Expected: all selected tests pass with no hangs or fatal child exits.

- [ ] **Step 5: Build and run complete Node unit/integration lanes**

```bash
cd native-lib/node
npm install
npm run build:addon
npm run build:ts
npm run test:unit
npm run test:integration
```

Expected: all commands exit `0`; no unhandled rejections, Worker nonzero exits, or timeout hangs.

- [ ] **Step 6: Run normal native-lib Gradle verification**

```bash
export GRAALVM_HOME="/Users/lmariano/dev/mulesoft/data-weave-cli/.graalvm/graalvm-community-openjdk-24.0.2+11.1/Contents/Home"
export JAVA_HOME="$GRAALVM_HOME"
./gradlew native-lib:test -PskipNodeTests=true -PskipPythonTests=true
./gradlew build -PskipNodeTests=true
```

Expected: both commands exit `0`. The second command includes the repository's configured Python lane; Node was already run directly to preserve focused output.

- [ ] **Step 7: Perform three-lens code review**

Review the complete PR diff for:

```text
General correctness: stale state, exception masking, duplicate cleanup, error timing.
Native/concurrency: lock order, lease/flow refcounts, callback thread affinity, env death, cancellation.
Security: pointer validation, callback input lengths, tenant data in logs, raw ABI nullability.
```

For each accepted finding, add a failing regression test first, implement the smallest correction, rerun the focused test, then rerun the affected module lane. Commit review fixes separately with a concise message.

- [ ] **Step 8: Verify final clean evidence**

```bash
git status --short
git diff --check w-23692110-multi-engine-design...HEAD
git log --oneline --decorate w-23692110-multi-engine-design..HEAD
git diff --stat w-23692110-multi-engine-design...HEAD
```

Expected: only ignored build artifacts may exist; no uncommitted source/doc changes; diff check exits `0`.

- [ ] **Step 9: Push the branch**

```bash
git push -u origin w-23692110-review-22-fixes
```

Expected: remote tracking branch created without force.

- [ ] **Step 10: Create the PR targeting the multi-engine branch**

Before creation, inspect remote tracking and the full range:

```bash
git status --short
git branch -vv
git log --oneline origin/w-23692110-multi-engine-design..HEAD
git diff --stat origin/w-23692110-multi-engine-design...HEAD
```

Create the PR:

```bash
gh pr create \
  --base w-23692110-multi-engine-design \
  --head w-23692110-review-22-fixes \
  --title "@W-23692110: Harden multi-engine lifecycle and streaming" \
  --body-file /tmp/pr157-review22-body.md
```

The body must summarize all eight resolved findings, list exact verification commands/results, call out the intentional destroy-blocking and callback-reentrancy contracts, and state that this PR layers onto PR #157 rather than targeting `master`.

- [ ] **Step 11: Report the PR URL and residual risks**

Return the PR URL, commit count, final test counts, and any unrun platform-only checks. Do not claim hosted CI passes until GitHub reports it.
