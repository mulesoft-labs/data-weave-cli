import * as ffi from "./ffi";
import { resolveAddonPath } from "./addon-path";
import { findLibrary, buildInputsJson } from "./utils";
import { parseNativeResponse } from "./result";
import { createChunkReader } from "./reader";
import { streamFromNative } from "./stream";
import { DataWeaveError, DataWeaveScriptError } from "./errors";
import type { NativeStreamingOperation } from "./ffi";
import type { ExecutionResult, StreamingResult, Inputs, TransformOptions } from "./types";
import type { ModuleResolver } from "./resolver";

interface EngineOperationToken {
  readonly handle: number;
  readonly generation: number;
}

/**
 * Constructor options for {@link DataWeave}.
 */
export interface DataWeaveOptions {
  /**
   * Path to dwlib native library.
   * If not provided, uses default location.
   */
  libPath?: string;

  /**
   * Module resolver for external DataWeave modules.
   * Optional. If not provided, only built-in modules are available.
   *
   * MUST be synchronous (cannot return Promise).
   *
   * Each DataWeave instance owns an independent native engine, so multiple
   * instances with different resolvers coexist in one process with no
   * cross-talk. Streaming/transform still resolve only built-in modules for a
   * resolver-backed engine (custom modules fail closed); see external-modules.md.
   *
   * Security: the resolver runs with full process permissions and no
   * sandboxing (same trust model as the CLI resolving `.dwl` files from
   * disk) — only use resolvers pointed at trusted sources.
   */
  resolveModule?: ModuleResolver;
}

/**
 * A handle to the DataWeave native runtime for executing scripts.
 *
 * Wraps the native `dwlib` shared library via the FFI addon. Call
 * {@link DataWeave.initialize} once before running scripts and
 * {@link DataWeave.cleanup} when done. For most callers the module-level
 * {@link run} / {@link runStreaming} / {@link runTransform} functions — backed
 * by a lazily-initialized singleton — are more convenient than constructing an
 * instance directly.
 */
export class DataWeave {
  private readonly addonPath: string;
  private readonly libPath: string;
  private readonly resolveModule?: ModuleResolver;
  private state: "uninitialized" | "ready" | "cleaning-up" = "uninitialized";
  private engineHandle: number | null = null;
  private engineGeneration = 0;
  private readonly activeStreams = new Set<NativeStreamingOperation>();
  private cleanupPromise: Promise<void> | null = null;

  /**
   * @param options - Configuration options or a legacy libPath string.
   *   When a string is provided, it is treated as {@link DataWeaveOptions.libPath}.
   */
  constructor(options?: DataWeaveOptions | string) {
    this.addonPath = resolveAddonPath();
    if (typeof options === "string") {
      // Legacy constructor signature: DataWeave(libPath)
      this.libPath = options;
      this.resolveModule = undefined;
    } else {
      this.libPath = options?.libPath ?? findLibrary(this.addonPath);
      this.resolveModule = options?.resolveModule;
    }
  }

  /**
   * Loads and initializes the native runtime. Idempotent — a no-op if already
   * initialized.
   *
   * @throws DataWeaveError if the native library fails to load or initialize.
   * @throws DataWeaveError if called while a `cleanup()` is still in progress
   *   — await the cleanup first.
   */
  initialize(): void {
    if (this.state === "ready") return;
    if (this.state === "cleaning-up") {
      throw new DataWeaveError(
        "Cannot initialize while cleanup is in progress; await cleanup() first."
      );
    }
    let libRefAcquired = false;
    try {
      ffi.initialize(this.libPath, this.addonPath);
      libRefAcquired = true;
      const engineHandle = this.resolveModule
        ? ffi.createEngineWithResolver(this.resolveModule)
        : ffi.createEngine();
      this.engineHandle = engineHandle;
    } catch (e: unknown) {
      // If ffi.initialize() already succeeded but engine creation then threw, we
      // already hold an increment of the native library's ref-counted handle and
      // must release it (ffi.cleanup()), or it leaks for the process lifetime.
      // ffi.cleanup() is async, so model the rollback as PENDING state instead of
      // firing-and-forgetting it (review #7 #3): (1) an un-awaited rejection must
      // not become an unhandledRejection, and (2) a concurrent initialize()/run()
      // must not race a fresh graal_create_isolate against the in-flight release.
      // Reuse the same cleanupPromise/"cleaning-up" machinery cleanup() uses:
      // hold state "cleaning-up" until the release settles (so initialize()'s own
      // "cleaning-up" guard rejects a concurrent retry deterministically, and a
      // concurrent cleanup() coalesces onto this same promise), then return to
      // "uninitialized". The synchronous throw to THIS caller is preserved.
      this.engineHandle = null;
      if (libRefAcquired) {
        this.state = "cleaning-up";
        // ffi.cleanup() can fail synchronously (throw) as well as asynchronously
        // (reject a returned promise). Calling it inside a try/catch -- rather
        // than eagerly as the argument to Promise.resolve(ffi.cleanup()) -- lets
        // a synchronous throw be caught and normalized into a rejected promise
        // BEFORE cleanupPromise is assigned, so it still flows through the same
        // .finally() state reset instead of escaping here and stranding this
        // instance in "cleaning-up" forever (review #8 #2). Existing callers
        // still observe ffi.cleanup() invoked synchronously, in the same tick as
        // this catch block, exactly as before this fix.
        let releaseResult: Promise<void> | void;
        try {
          releaseResult = ffi.cleanup();
        } catch (cleanupError) {
          releaseResult = Promise.reject(cleanupError);
        }
        this.cleanupPromise = Promise.resolve(releaseResult).finally(() => {
          this.state = "uninitialized";
          this.cleanupPromise = null;
        });
        // Never let an un-awaited rollback surface as an unhandledRejection. A
        // caller that awaits cleanup() (which coalesces onto cleanupPromise)
        // still observes the rejection; this handler only covers the un-awaited
        // path.
        this.cleanupPromise.catch(() => {});
      }
      throw new DataWeaveError(`Failed to initialize: ${e instanceof Error ? e.message : e}`);
    }
    this.state = "ready";
    this.engineGeneration++;
  }

  /**
   * Releases the native runtime. Idempotent — a no-op if not initialized. After
   * cleanup the instance can be re-initialized via {@link DataWeave.initialize}.
   *
   * Resolution depends on whether this call releases the FINAL shared native
   * reference in the process. When it does, it first drains any in-flight
   * streaming/transform operation on this or any other instance, then attempts
   * isolate teardown and resolves once that attempt completes. The promise thus
   * guarantees logical release and that teardown was attempted — not
   * necessarily physical reclamation of the isolate: an ordinary teardown
   * failure retains the live isolate and is retried where safe (at a later
   * initialization or async op-completion drain), and an unrecoverable
   * teardown-plus-detach double failure intentionally leaks the isolate until
   * process exit (with a diagnostic on stderr). Awaiting this rather than
   * firing-and-forgetting lets the drain complete before a subsequent
   * {@link initialize}. When other initialized instances remain, it resolves as
   * soon as this instance's engine is released, leaving the shared isolate live
   * for them.
   */
  async cleanup(): Promise<void> {
    // Coalesce first: doCleanup() flips `state` to "cleaning-up" synchronously
    // as its first statement, so by the time a second overlapping call runs,
    // `state` has already left "ready". If the not-ready guard below ran
    // first, that second caller would resolve immediately instead of
    // awaiting the first caller's in-flight native teardown -- contradicting
    // this method's contract of resolving only once the in-flight native
    // teardown attempt has completed (round-6 review, task-1 fix round 1).
    // Checking
    // `cleanupPromise` first ensures every concurrent caller that overlaps
    // with an in-flight doCleanup() awaits that SAME promise, so the native
    // teardown (ffi.destroyEngine/ffi.cleanup) still happens exactly once.
    if (this.cleanupPromise) return this.cleanupPromise;
    // Not coalescing with an in-flight cleanup: nothing to do unless we're
    // "ready" (covers both never-initialized and already-settled cleanup).
    if (this.state !== "ready") return;
    this.cleanupPromise = this.doCleanup();
    try {
      await this.cleanupPromise;
    } finally {
      // Clear on both fulfilment and rejection so a later cleanup() (after a
      // re-initialize, or a retry of a rejected cleanup) can run again.
      this.cleanupPromise = null;
    }
  }

  private async doCleanup(): Promise<void> {
    // Transition BEFORE releasing the engine so run()/initialize() called
    // during the async teardown window are rejected deterministically rather
    // than seeing a stale "ready" state with a null engineHandle (round-6 #1/#3).
    this.state = "cleaning-up";
    const activeStreams = [...this.activeStreams];
    for (const operation of activeStreams) {
      try {
        operation.cancel();
      } catch {
        // A controller failure must not prevent the remaining streams from
        // being canceled or the engine reference from being released.
      }
    }
    // Cancellation may reject completion as its normal terminal signal. Wait
    // for every snapshotted operation, but do not let those rejections mask an
    // engine destruction or native cleanup failure.
    if (activeStreams.length > 0) {
      await Promise.allSettled(activeStreams.map((operation) => operation.completion));
    }

    let destroyError: unknown;
    try {
      if (this.engineHandle !== null) {
        try {
          ffi.destroyEngine(this.engineHandle);
        } catch (e) {
          // Round-14 (#6): a throwing destroyEngine() (e.g. wrong-thread
          // destruction) must NOT skip ffi.cleanup() -- that would strand this
          // env's native init reference and block isolate teardown. Capture the
          // primary error, clear the handle so a retry does not double-destroy,
          // and fall through to release the reference below.
          destroyError = e;
        } finally {
          this.engineHandle = null;
        }
      }
      await ffi.cleanup();
    } finally {
      this.state = "uninitialized";
    }
    // Surface the primary destruction error after the reference was released. If
    // ffi.cleanup() itself rejected, its error already propagated from the await
    // (the more actionable reference-release failure wins; the destroy error is
    // then suppressed).
    if (destroyError !== undefined) throw destroyError;
  }

  /**
   * Executes a script and returns its result in a single (non-streaming) call.
   *
   * @param script - The DataWeave script to run.
   * @param inputs - Named inputs made available to the script (e.g. `payload`).
   * @param opts - When `raiseOnError` is set, an unsuccessful result is thrown
   *   as a {@link DataWeaveScriptError} instead of being returned.
   * @returns The {@link ExecutionResult} carrying the output payload or error.
   * @throws DataWeaveError if the runtime is not initialized.
   * @throws DataWeaveScriptError if the script fails and `opts.raiseOnError` is set.
   */
  run(script: string, inputs?: Inputs, opts?: { raiseOnError?: boolean }): ExecutionResult {
    this.ensureReady();
    const inputsJson = buildInputsJson(inputs ?? {});

    const raw = ffi.runScriptEngine(this.engineHandle!, script, inputsJson);

    const result = parseNativeResponse(raw);

    if (opts?.raiseOnError && !result.success) {
      throw new DataWeaveScriptError(result);
    }
    return result;
  }

  /**
   * Executes a script and streams its output as it is produced.
   *
   * Yields output chunks in order; the generator's return value is the terminal
   * {@link StreamingResult} with the final status and content metadata.
   *
   * @param script - The DataWeave script to run.
   * @param inputs - Named inputs made available to the script.
   * @returns An async generator of output chunks, returning the streaming metadata.
   * @throws DataWeaveError if the runtime is not initialized.
   */
  runStreaming(script: string, inputs?: Inputs): AsyncGenerator<Buffer, StreamingResult, undefined> {
    const token = this.captureOperationToken();
    return this.runStreamingInternal(token, script, inputs);
  }

  private async *runStreamingInternal(
    token: EngineOperationToken,
    script: string,
    inputs?: Inputs
  ): AsyncGenerator<Buffer, StreamingResult, undefined> {
    this.assertCurrentOperation(token);
    const inputsJson = buildInputsJson(inputs ?? {});
    this.assertCurrentOperation(token);
    return yield* streamFromNative(
      (chunkCb) => ffi.runScriptStreamingEngine(token.handle, script, inputsJson, chunkCb),
      (operation) => { this.activeStreams.add(operation); },
      (operation) => { this.activeStreams.delete(operation); }
    );
  }

  /**
   * Executes a script over a streamed primary input, streaming the output.
   *
   * The `input` chunks are fed to the script as the primary input (named
   * `opts.inputName`, default `payload`); output chunks are yielded as they are
   * produced and the generator returns the terminal {@link StreamingResult}.
   * Sync iterables are consumed on demand; async iterables are pre-buffered (see
   * {@link createChunkReader}).
   *
   * @param script - The DataWeave script to run.
   * @param input - A sync or async iterable of byte chunks for the primary input.
   * @param opts - Primary-input framing (`inputName`, `mimeType`, `charset`) and
   *   any additional named `inputs`.
   * @returns An async generator of output chunks, returning the streaming metadata.
   * @throws DataWeaveError if the runtime is not initialized.
   */
  runTransform(
    script: string,
    input: AsyncIterable<Buffer | Uint8Array> | Iterable<Buffer | Uint8Array>,
    opts?: TransformOptions
  ): AsyncGenerator<Buffer, StreamingResult, undefined> {
    const token = this.captureOperationToken();
    return this.runTransformInternal(token, script, input, opts);
  }

  private async *runTransformInternal(
    token: EngineOperationToken,
    script: string,
    input: AsyncIterable<Buffer | Uint8Array> | Iterable<Buffer | Uint8Array>,
    opts?: TransformOptions
  ): AsyncGenerator<Buffer, StreamingResult, undefined> {
    this.assertCurrentOperation(token);

    const inputName = opts?.inputName ?? "payload";
    const inputMimeType = opts?.mimeType ?? "application/json";
    const inputCharset = opts?.charset ?? null;
    const extraInputs = opts?.inputs ?? {};
    const inputsJson = Object.keys(extraInputs).length > 0 ? buildInputsJson(extraInputs) : "{}";

    const readCb = await createChunkReader(input);

    this.assertCurrentOperation(token);

    return yield* streamFromNative(
      (writeCb) =>
        ffi.runScriptTransformEngine(
          token.handle,
          script,
          inputsJson,
          inputName,
          inputMimeType,
          inputCharset,
          readCb,
          writeCb
        ),
      (operation) => { this.activeStreams.add(operation); },
      (operation) => { this.activeStreams.delete(operation); }
    );
  }

  private captureOperationToken(): EngineOperationToken {
    this.ensureReady();
    return { handle: this.engineHandle!, generation: this.engineGeneration };
  }

  private assertCurrentOperation(token: EngineOperationToken): void {
    if (
      this.state !== "ready" ||
      this.engineHandle !== token.handle ||
      this.engineGeneration !== token.generation
    ) {
      throw new DataWeaveError("DataWeave operation belongs to a stale engine generation.");
    }
  }

  private ensureReady(): void {
    if (this.state === "ready") return;
    if (this.state === "cleaning-up") {
      throw new DataWeaveError(
        "DataWeave runtime is cleaning up; await cleanup() before running again."
      );
    }
    throw new DataWeaveError("DataWeave runtime not initialized. Call initialize() first.");
  }
}

// Module-level convenience API with lazy singleton
let globalInstance: DataWeave | null = null;
// Guards against beforeExit and exit both driving cleanup for the same
// shutdown. Belt-and-suspenders on top of cleanup()'s own idempotency.
let cleanupStarted = false;
// Coalesces overlapping module-level cleanup() calls, mirroring the
// instance-level DataWeave.cleanupPromise. Without it, the second of two
// overlapping module cleanup() calls sees globalInstance already nulled and
// resolves immediately -- before the first call's native teardown finishes,
// violating cleanup()'s "resolves once the native teardown attempt has
// completed" contract
// for the last reference. (round 12 #5)
let cleanupPromise: Promise<void> | null = null;
// The instance that `cleanupPromise` is currently draining. Needed because
// coalescing must NOT be keyed on the module-global promise alone: if a
// caller revives the singleton (via run()/getGlobalInstance()) while a prior
// drain is still in flight, a subsequent cleanup() must clean the freshly
// revived instance rather than returning the stale promise as if it had
// covered it too -- otherwise the revived instance's native ref is silently
// leaked (final-review round 12 #1, fixing round 12 Task 6's regression).
let cleaningInstance: DataWeave | null = null;
// Process exit hooks are registered exactly once for the lifetime of the
// module, NOT per singleton. Re-creating the singleton after cleanup() must
// not attach a second pair of listeners (that accumulates until Node emits
// MaxListenersExceededWarning). The listeners tolerate a null globalInstance:
// cleanup() no-ops when there is nothing to release, and cleanupStarted
// coalesces beforeExit/exit for a given shutdown. Unlike cleanupStarted, this
// guard is never reset — that is the whole point.
let exitHooksRegistered = false;

/**
 * Registers the process-wide exit-cleanup hooks exactly once for this
 * module. Subsequent calls (e.g. from a revived singleton after cleanup())
 * are no-ops: the hooks registered on first use are reused for the rest of
 * the process's lifetime, which is safe because they tolerate a null
 * `globalInstance` and `cleanupStarted` coalesces beforeExit/exit for a
 * given shutdown.
 *
 * Two hooks are registered, covering complementary cases:
 * - `beforeExit` fires when the event loop is about to drain naturally and
 *   CAN run async work (Node keeps the loop alive until it settles), so it
 *   drains any in-flight streaming/transform operation gracefully. This is
 *   the common case.
 * - `exit` runs strictly synchronously and is only a best-effort fallback for
 *   the paths that skip `beforeExit` — `process.exit()`, an uncaught
 *   exception, and normal process termination. Because it is synchronous it
 *   can only run the fast cleanup path, so an in-flight async operation may be
 *   abandoned. Node does NOT emit `exit` (nor `beforeExit`) for termination
 *   signals such as SIGTERM/SIGINT/SIGKILL, nor for every fatal failure mode,
 *   so this is not a guarantee: callers that require graceful shutdown must
 *   register and await their own handlers for the catchable signals (e.g.
 *   `process.on("SIGTERM", async () => { await cleanup(); process.exit(0); })`);
 *   SIGKILL cannot be caught, so no in-process cleanup can run for it.
 * The `cleanupStarted` guard ensures only one of the two hooks actually
 * runs cleanup for a given shutdown.
 */
function registerExitHooksOnce(): void {
  if (exitHooksRegistered) return;
  exitHooksRegistered = true;
  process.on("beforeExit", async () => {
    if (cleanupStarted) return;
    cleanupStarted = true;
    await cleanup(); // beforeExit can await: drains in-flight ops
  });
  process.on("exit", () => {
    if (cleanupStarted) return; // beforeExit already handled it
    cleanup(); // fallback: best-effort sync fast path
  });
}

/**
 * Returns the process-wide {@link DataWeave} singleton, creating and
 * initializing it on first use (or after a prior {@link cleanup}).
 *
 * The exit-cleanup hooks are registered exactly once for the process via
 * {@link registerExitHooksOnce}, not once per singleton: a singleton revived
 * after cleanup() reuses the same pair of listeners rather than adding new
 * ones, which would otherwise accumulate a pair per init/cleanup cycle until
 * Node emits `MaxListenersExceededWarning`. Reuse is safe because the
 * listeners tolerate a null `globalInstance` and `cleanupStarted` coalesces
 * beforeExit/exit for a given shutdown.
 */
function getGlobalInstance(): DataWeave {
  if (!globalInstance) {
    // Initialize a LOCAL candidate first; publish the singleton only after
    // initialize() succeeds. A failed first init (bad DATAWEAVE_NATIVE_LIB
    // path / transient native failure) must NOT leave a poisoned, uninitialized
    // singleton that makes every later run*() fail "not initialized" even after
    // the fault is fixed (review #6 #1). On throw, globalInstance stays null and
    // the next call retries cleanly with a fresh instance.
    const candidate = new DataWeave();
    candidate.initialize();
    globalInstance = candidate;
    registerExitHooksOnce();
  }
  return globalInstance;
}

/**
 * Executes a script on the shared {@link DataWeave} singleton.
 * @see DataWeave.run
 */
export function run(script: string, inputs?: Inputs, opts?: { raiseOnError?: boolean }): ExecutionResult {
  return getGlobalInstance().run(script, inputs, opts);
}

/**
 * Streams a script's output on the shared {@link DataWeave} singleton.
 * @see DataWeave.runStreaming
 */
export function runStreaming(
  script: string,
  inputs?: Inputs
): AsyncGenerator<Buffer, StreamingResult, undefined> {
  return getGlobalInstance().runStreaming(script, inputs);
}

/**
 * Streams a transform over a streamed input on the shared {@link DataWeave} singleton.
 * @see DataWeave.runTransform
 */
export function runTransform(
  script: string,
  input: AsyncIterable<Buffer | Uint8Array> | Iterable<Buffer | Uint8Array>,
  opts?: TransformOptions
): AsyncGenerator<Buffer, StreamingResult, undefined> {
  return getGlobalInstance().runTransform(script, input, opts);
}

/**
 * Releases the shared {@link DataWeave} singleton, if one was created. A fresh
 * singleton is created lazily on the next convenience-API call.
 */
export async function cleanup(): Promise<void> {
  // Coalesce overlapping calls onto one drain (round 12 #5) -- but ONLY when
  // nothing new has been revived since that drain started. If `globalInstance`
  // is still the same instance the in-flight promise is draining, or is null
  // (nobody has revived since), it's safe to piggyback on the existing
  // promise. If a DIFFERENT instance is now the singleton (a caller called
  // run() and revived it while the old drain was still in flight), that new
  // instance has never been handed to a cleanup() call -- returning the old
  // promise here would resolve as if it had been cleaned when it hasn't,
  // leaking its native ref for the rest of the process (final-review round 12
  // #1). Fall through and drain the current instance instead.
  if (cleanupPromise && (globalInstance === null || globalInstance === cleaningInstance)) {
    return cleanupPromise;
  }
  if (!globalInstance) return;
  const instance = globalInstance;
  globalInstance = null;
  // Chosen semantics for overlapping different-instance drains: coalescing
  // tracks only the MOST RECENT drain. An older drain that is still in flight
  // when a newer one starts is not stomped -- it keeps running against its own
  // promise, which whoever started it already holds and will await -- but it
  // stops being the thing later cleanup() calls coalesce onto. Two distinct
  // instances tearing down concurrently is fine: each owns its own engine
  // handle and native ref, exactly like two DataWeave instances calling
  // .cleanup() independently. This keeps the invariant that matters: no
  // cleanup() call ever returns as if it drained an instance it didn't.
  cleaningInstance = instance;
  cleanupPromise = instance.cleanup();
  try {
    await cleanupPromise;
  } finally {
    // Only clear the shared coalescing state if it's still ours to clear --
    // i.e. nobody has started a newer drain (for a newer revived instance)
    // that has since taken over `cleanupPromise`/`cleaningInstance`. Guards
    // against this drain's finally clobbering a later drain's in-flight state.
    if (cleaningInstance === instance) {
      cleanupPromise = null;
      cleaningInstance = null;
    }
    // Reset the exit-hook guard only after THIS drain has fully completed, so
    // a revived singleton gets its own live hooks for the next real exit.
    // Must stay last: resetting earlier could let a concurrent `exit` firing
    // on this same shutdown re-enter cleanup while the async drain above is
    // in flight.
    cleanupStarted = false;
  }
}
