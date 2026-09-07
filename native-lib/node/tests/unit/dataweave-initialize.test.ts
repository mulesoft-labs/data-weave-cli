import { describe, it, expect, vi, beforeEach } from "vitest";

// Pure-logic test of DataWeave.initialize()'s lifecycle/error handling, with
// the native addon mocked out entirely -- no dwlib required (see the "unit"
// project in vitest.config.ts). This covers a ref-count leak that is only
// observable in the sequencing of calls into ffi.ts, not in any externally
// visible native state, so a real end-to-end native failure isn't a
// practical way to assert on it.
vi.mock("../../src/ffi", () => ({
  initialize: vi.fn(),
  createEngine: vi.fn(),
  createEngineWithResolver: vi.fn(),
  destroyEngine: vi.fn(),
  runScriptEngine: vi.fn(),
  runScriptStreamingEngine: vi.fn(),
  runScriptTransformEngine: vi.fn(),
  cleanup: vi.fn(),
}));

import * as ffi from "../../src/ffi";
import { DataWeave, run, cleanup } from "../../src/dataweave";
import { DataWeaveError } from "../../src/errors";

describe("DataWeave.initialize() native ref-count safety", () => {
  beforeEach(() => {
    vi.mocked(ffi.initialize).mockReset();
    vi.mocked(ffi.createEngine).mockReset();
    vi.mocked(ffi.createEngineWithResolver).mockReset();
    vi.mocked(ffi.destroyEngine).mockReset();
    vi.mocked(ffi.runScriptStreamingEngine).mockReset();
    vi.mocked(ffi.runScriptTransformEngine).mockReset();
    vi.mocked(ffi.cleanup).mockReset();
  });

  it("releases the native library ref-count if engine creation fails after ffi.initialize() succeeded", () => {
    vi.mocked(ffi.initialize).mockImplementation(() => {});
    vi.mocked(ffi.createEngineWithResolver).mockImplementation(() => {
      throw new Error("native engine creation boom");
    });

    const dw = new DataWeave({ libPath: "mock-lib-path", resolveModule: () => null });

    expect(() => dw.initialize()).toThrow(DataWeaveError);

    // ffi.initialize() already succeeded, incrementing the native library's
    // ref count. Since the instance never reached the "ready" state,
    // cleanup()'s early-return guard means nothing else would ever call
    // ffi.cleanup() --
    // initialize()'s own catch block must have released it.
    expect(ffi.cleanup).toHaveBeenCalledTimes(1);
  });

  it("does not call ffi.cleanup() when ffi.initialize() itself is what fails", () => {
    vi.mocked(ffi.initialize).mockImplementation(() => {
      throw new Error("library not found");
    });

    const dw = new DataWeave({ libPath: "mock-lib-path" });

    expect(() => dw.initialize()).toThrow(DataWeaveError);

    // No ref count was ever acquired, so there is nothing to release.
    expect(ffi.cleanup).not.toHaveBeenCalled();
  });

  it("leaves engineHandle unset and the instance cleanly re-initializable after the failed attempt's rollback settles", async () => {
    vi.mocked(ffi.initialize).mockImplementation(() => {});
    vi.mocked(ffi.createEngine)
      .mockImplementationOnce(() => {
        throw new Error("transient native failure");
      })
      .mockImplementationOnce(() => 42);

    const dw = new DataWeave({ libPath: "mock-lib-path" });

    expect(() => dw.initialize()).toThrow(DataWeaveError);
    expect(ffi.cleanup).toHaveBeenCalledTimes(1);

    // The rollback release is modeled as pending state (review #7 #3): until it
    // settles the instance is "cleaning-up" and re-init is deliberately rejected
    // rather than racing the in-flight release. Let the rollback settle first.
    await new Promise<void>((r) => setImmediate(r));

    // A later initialize() call (e.g. once the transient failure clears)
    // must succeed cleanly -- the failed attempt must not have left the
    // instance permanently "half-initialized" (state stuck "ready" without an
    // engine handle, or vice versa).
    vi.mocked(ffi.cleanup).mockClear();
    dw.initialize();
    expect(ffi.createEngine).toHaveBeenCalledTimes(2);

    await dw.cleanup();
    expect(ffi.destroyEngine).toHaveBeenCalledWith(42);
    expect(ffi.cleanup).toHaveBeenCalledTimes(1);
  });

  it("does not call ffi.cleanup() from initialize() on the successful path", () => {
    vi.mocked(ffi.initialize).mockImplementation(() => {});
    vi.mocked(ffi.createEngine).mockImplementation(() => 7);

    const dw = new DataWeave({ libPath: "mock-lib-path" });
    dw.initialize();

    expect(ffi.cleanup).not.toHaveBeenCalled();

    dw.cleanup();
    expect(ffi.destroyEngine).toHaveBeenCalledWith(7);
    expect(ffi.cleanup).toHaveBeenCalledTimes(1);
  });

  it("still resets state to \"uninitialized\" when ffi.cleanup() rejects, so the instance is re-initializable", async () => {
    vi.mocked(ffi.initialize).mockImplementation(() => {});
    vi.mocked(ffi.createEngine).mockImplementation(() => 7);
    vi.mocked(ffi.cleanup).mockRejectedValueOnce(new Error("native cleanup boom"));

    const dw = new DataWeave({ libPath: "mock-lib-path" });
    dw.initialize();

    await expect(dw.cleanup()).rejects.toThrow("native cleanup boom");

    // Even though ffi.cleanup() rejected, the engine handle was already
    // destroyed and nulled -- state must not stay stuck "ready", or a
    // later initialize() call becomes a permanent no-op (the early-return
    // guard `if (this.state === "ready") return;`) and the instance is stranded
    // with a null engineHandle.
    vi.mocked(ffi.initialize).mockClear();
    vi.mocked(ffi.createEngine).mockClear();
    vi.mocked(ffi.createEngine).mockImplementation(() => 9);

    dw.initialize();

    expect(ffi.initialize).toHaveBeenCalledTimes(1);
    expect(ffi.createEngine).toHaveBeenCalledTimes(1);
  });

  it("coalesces concurrent cleanup() calls into a single native teardown", async () => {
    vi.mocked(ffi.createEngine).mockReturnValue(1);
    let resolveNative!: () => void;
    vi.mocked(ffi.cleanup).mockReturnValue(
      new Promise<void>((resolve) => {
        resolveNative = resolve;
      })
    );

    const dw = new DataWeave("/fake/lib");
    dw.initialize();

    // Two overlapping cleanup() calls while ffi.cleanup() is still pending.
    const p1 = dw.cleanup();
    const p2 = dw.cleanup();
    resolveNative();
    await Promise.all([p1, p2]);

    // The native ref-count decrement (ffi.cleanup) and destroyEngine each run
    // exactly once, not once per caller -- this is the double-decrement fix.
    expect(ffi.cleanup).toHaveBeenCalledTimes(1);
    expect(ffi.destroyEngine).toHaveBeenCalledTimes(1);
  });

  it("second overlapping cleanup() call awaits the SAME in-flight native teardown, not an early resolution", async () => {
    // Regression test for task-1 fix round 1: doCleanup() flips `state` to
    // "cleaning-up" synchronously as its first statement (an async function
    // body runs synchronously up to its first await). If cleanup()'s
    // not-ready guard (`if (this.state !== "ready") return;`) ran BEFORE the
    // `cleanupPromise` coalescing check, a second overlapping call would see
    // state already left "ready" and resolve immediately -- never actually
    // awaiting the first call's in-flight native teardown. That would
    // contradict cleanup()'s documented contract ("resolves once that
    // [teardown] attempt completes") and
    // silently regress round-4's coalescing timing. This test asserts the
    // second call's promise has NOT settled while ffi.cleanup() is still
    // pending, by racing it against a marker that only resolves after
    // ffi.cleanup() is allowed to settle.
    vi.mocked(ffi.createEngine).mockReturnValue(1);
    let resolveNative!: () => void;
    vi.mocked(ffi.cleanup).mockReturnValue(
      new Promise<void>((resolve) => {
        resolveNative = resolve;
      })
    );

    const dw = new DataWeave("/fake/lib");
    dw.initialize();

    const p1 = dw.cleanup();
    const p2 = dw.cleanup(); // overlaps while doCleanup() is in flight

    const SETTLED = Symbol("settled");
    const PENDING = Symbol("pending");
    // A same-tick race: if p2 resolved early (the regression), it wins;
    // Promise.resolve() flushes on the same microtask queue, so this
    // reliably distinguishes "already settled" from "still pending" without
    // relying on real timers.
    const raceResult = await Promise.race([
      p2.then(() => SETTLED),
      Promise.resolve().then(() => PENDING),
    ]);
    expect(raceResult).toBe(PENDING);

    resolveNative();
    await Promise.all([p1, p2]);

    expect(ffi.cleanup).toHaveBeenCalledTimes(1);
    expect(ffi.destroyEngine).toHaveBeenCalledTimes(1);
  });

  it("does not accumulate process exit listeners across init/cleanup cycles", async () => {
    // The module-level `run`/`cleanup` convenience API drives the lazily
    // created singleton through `getGlobalInstance()`, which is what
    // registers the process-wide beforeExit/exit hooks (registerExitHooksOnce
    // in src/dataweave.ts). Unlike the other tests in this file, this doesn't
    // construct DataWeave directly, so it hits DataWeave's default
    // `findLibrary()` lookup. Point DATAWEAVE_NATIVE_LIB at this test file
    // (guaranteed to exist) so that lookup succeeds without depending on a
    // real built dwlib -- ffi.initialize() is mocked, so the path's contents
    // are never touched.
    const prevEnvLib = process.env.DATAWEAVE_NATIVE_LIB;
    process.env.DATAWEAVE_NATIVE_LIB = __filename;
    try {
      const before = process.listenerCount("exit") + process.listenerCount("beforeExit");
      // Drive several singleton create -> cleanup cycles via the module API.
      for (let i = 0; i < 5; i++) {
        run("%dw 2.0\noutput application/json\n---\n1 + 1"); // creates the singleton (+ hooks on first)
        await cleanup(); // releases the singleton
      }
      const after = process.listenerCount("exit") + process.listenerCount("beforeExit");
      // Register-once: at most the single pair added on the very first create,
      // never one pair per cycle.
      expect(after - before).toBeLessThanOrEqual(2);
    } finally {
      if (prevEnvLib === undefined) delete process.env.DATAWEAVE_NATIVE_LIB;
      else process.env.DATAWEAVE_NATIVE_LIB = prevEnvLib;
    }
  });

  it("still calls ffi.cleanup() (releasing the native init reference) when destroyEngine() throws", async () => {
    // Real path: wrong-thread destroyEngine() throws synchronously. If cleanup()
    // skipped ffi.cleanup() on that throw, the native init reference for this env
    // would leak and block isolate teardown. cleanup() must release it anyway and
    // still surface the primary destruction error.
    vi.mocked(ffi.initialize).mockImplementation(() => {});
    vi.mocked(ffi.createEngine).mockReturnValue(7);
    vi.mocked(ffi.destroyEngine).mockImplementation(() => {
      throw new Error("wrong-thread destroy boom");
    });
    vi.mocked(ffi.cleanup).mockResolvedValue(undefined);

    const dw = new DataWeave("/fake/lib");
    dw.initialize();

    await expect(dw.cleanup()).rejects.toThrow("wrong-thread destroy boom");

    // The native init reference was still released despite the destroy throw.
    expect(ffi.cleanup).toHaveBeenCalledTimes(1);

    // The instance is not stranded "ready": a later initialize() works.
    vi.mocked(ffi.destroyEngine).mockReset();
    vi.mocked(ffi.createEngine).mockReturnValue(9);
    vi.mocked(ffi.createEngine).mockClear(); // ignore the first init's call
    dw.initialize();
    // Prove the re-init genuinely created a fresh engine (not a no-op that
    // false-passes toHaveBeenLastCalledWith because the FIRST init already
    // called createEngine() with the same args -- review #6 #8).
    expect(ffi.createEngine).toHaveBeenCalledTimes(1);
  });

  it("does not publish a poisoned singleton when the first module-level init fails", async () => {
    // This drives the module-level run() through getGlobalInstance(), which
    // constructs a DataWeave directly and so hits the real findLibrary() lookup
    // (findLibrary is in the DataWeave constructor and is NOT mocked by the
    // vi.mock("../../src/ffi") at the top of this file). Point
    // DATAWEAVE_NATIVE_LIB at this test file (guaranteed to exist) so that
    // lookup succeeds without depending on a staged/built dwlib -- ffi is
    // mocked, so the path's contents are never touched. Without this the test
    // fails with "Could not find DataWeave native library" whenever no dwlib is
    // present (review #14 #2).
    const prevEnvLib = process.env.DATAWEAVE_NATIVE_LIB;
    process.env.DATAWEAVE_NATIVE_LIB = __filename;
    try {
      // Isolate module state: a fresh import gives a null globalInstance so this
      // test controls the very first getGlobalInstance() call.
      vi.resetModules();
      const ffiMod = await import("../../src/ffi");
      const dwMod = await import("../../src/dataweave");

      // First module-level run(): ffi.initialize() throws (e.g. bad lib path).
      vi.mocked(ffiMod.initialize).mockImplementationOnce(() => {
        throw new Error("library not found");
      });
      expect(() => dwMod.run("%dw 2.0\noutput application/json\n---\n1")).toThrow();

      // The fault is corrected; the NEXT module-level run() must build a fresh,
      // working singleton -- not reuse a poisoned, uninitialized one that fails
      // "not initialized" forever (review #6 #1).
      vi.mocked(ffiMod.initialize).mockImplementation(() => {});
      vi.mocked(ffiMod.createEngine).mockReturnValue(1);
      vi.mocked(ffiMod.runScriptEngine).mockReturnValue(
        JSON.stringify({
          success: true,
          result: Buffer.from("1").toString("base64"),
          mimeType: "application/json",
          charset: "utf-8",
          binary: false,
        })
      );
      const result = dwMod.run("%dw 2.0\noutput application/json\n---\n1");
      expect(result.success).toBe(true);
    } finally {
      if (prevEnvLib === undefined) delete process.env.DATAWEAVE_NATIVE_LIB;
      else process.env.DATAWEAVE_NATIVE_LIB = prevEnvLib;
    }
  });

  it("gates re-initialization on the in-flight rollback when engine creation fails", async () => {
    // Engine creation fails after ffi.initialize() succeeded. The rollback
    // ffi.cleanup() is async; until it settles the instance must be in the
    // "cleaning-up" state so a concurrent initialize() is rejected deterministically
    // rather than racing a fresh isolate against the in-flight release (review #7 #3).
    vi.mocked(ffi.initialize).mockImplementation(() => {});
    vi.mocked(ffi.createEngine).mockImplementation(() => {
      throw new Error("native engine creation boom");
    });
    let resolveRollback!: () => void;
    vi.mocked(ffi.cleanup).mockReturnValue(
      new Promise<void>((resolve) => { resolveRollback = resolve; })
    );

    const dw = new DataWeave("/fake/lib");
    expect(() => dw.initialize()).toThrow(DataWeaveError);

    // Rollback is still in flight: a concurrent initialize() must be rejected,
    // not allowed to race a fresh isolate against the pending native release.
    expect(() => dw.initialize()).toThrow(/cleanup is in progress/i);

    // Once the rollback settles, the instance is cleanly re-initializable.
    resolveRollback();
    await new Promise<void>((r) => setImmediate(r)); // let the .finally run
    vi.mocked(ffi.createEngine).mockReturnValue(5);
    dw.initialize();
    dw.cleanup();
    expect(ffi.destroyEngine).toHaveBeenCalledWith(5);
  });

  it("does not emit an unhandled rejection when the rollback ffi.cleanup() rejects", async () => {
    // The rollback release can itself reject; initialize() must observe it (via
    // the stored cleanupPromise) so it never becomes an unhandledRejection, while
    // still surfacing the ORIGINAL engine-creation error synchronously (review #7 #3).
    vi.mocked(ffi.initialize).mockImplementation(() => {});
    vi.mocked(ffi.createEngine).mockImplementation(() => {
      throw new Error("native engine creation boom");
    });
    vi.mocked(ffi.cleanup).mockRejectedValueOnce(new Error("rollback release boom"));

    const dw = new DataWeave("/fake/lib");
    expect(() => dw.initialize()).toThrow(/native engine creation boom/);

    // Give the rejected rollback promise a tick to settle; the .catch() attached
    // in initialize() must have consumed it (no unhandledRejection), and the
    // instance must be re-initializable afterward.
    await new Promise<void>((r) => setImmediate(r));
    // Clear so the assertion below proves the RETRY re-invoked createEngine,
    // not the earlier failed attempt's stale no-arg call (review #9 #1).
    vi.mocked(ffi.createEngine).mockClear();
    vi.mocked(ffi.createEngine).mockReturnValue(6);
    dw.initialize();
    expect(ffi.createEngine).toHaveBeenCalledTimes(1);
    expect(ffi.createEngine).toHaveBeenLastCalledWith();
  });

  it("does not strand the instance in cleaning-up when the rollback ffi.cleanup() throws synchronously", async () => {
    // ffi.cleanup() can fail SYNCHRONOUSLY (throw) rather than returning a
    // rejected promise. The rollback must still settle its pending state and the
    // instance must stay re-initializable; the ORIGINAL engine-creation error is
    // what surfaces synchronously to this caller (review #8 #2).
    vi.mocked(ffi.initialize).mockImplementation(() => {});
    vi.mocked(ffi.createEngine).mockImplementationOnce(() => {
      throw new Error("native engine creation boom");
    });
    vi.mocked(ffi.cleanup).mockImplementationOnce(() => {
      throw new Error("synchronous cleanup boom");
    });

    const dw = new DataWeave("/fake/lib");
    // The synchronous throw to THIS caller is the ORIGINAL engine-creation error,
    // not the cleanup throw.
    expect(() => dw.initialize()).toThrow(/native engine creation boom/);

    // Let the deferred rollback settle; state must return to "uninitialized" so a
    // later initialize() is not permanently rejected with "cleanup is in progress".
    await new Promise<void>((r) => setImmediate(r));
    // Clear so the assertion proves the retry actually re-invoked createEngine
    // rather than passing on the failed attempt's stale call (review #9 #1).
    vi.mocked(ffi.createEngine).mockClear();
    vi.mocked(ffi.createEngine).mockReturnValue(11);
    dw.initialize();
    expect(ffi.createEngine).toHaveBeenCalledTimes(1);
    expect(ffi.createEngine).toHaveBeenLastCalledWith();

    await dw.cleanup();
    expect(ffi.destroyEngine).toHaveBeenCalledWith(11);
  });

  describe("stale engine generation", () => {
    const staleGenerationMessage = "DataWeave operation belongs to a stale engine generation.";

    it("rejects a lazy runStreaming operation after replacement with a different handle before native admission", async () => {
      vi.mocked(ffi.createEngine).mockReturnValueOnce(2).mockReturnValueOnce(3);

      const dw = new DataWeave("/fake/lib");
      dw.initialize();
      const stream = dw.runStreaming("output application/json --- [1, 2, 3]");

      await dw.cleanup();
      dw.initialize();

      const firstPull = stream.next();
      await firstPull.catch(() => undefined);
      expect(ffi.runScriptStreamingEngine).not.toHaveBeenCalled();
      await expect(firstPull).rejects.toBeInstanceOf(DataWeaveError);
      await expect(firstPull).rejects.toThrow(staleGenerationMessage);

      await dw.cleanup();
    });

    it("rejects a lazy runStreaming operation when the replacement reuses its handle before native admission", async () => {
      vi.mocked(ffi.createEngine).mockReturnValue(2);

      const dw = new DataWeave("/fake/lib");
      dw.initialize();
      const stream = dw.runStreaming("output application/json --- [1, 2, 3]");

      await dw.cleanup();
      dw.initialize();

      const firstPull = stream.next();
      await firstPull.catch(() => undefined);
      expect(ffi.runScriptStreamingEngine).not.toHaveBeenCalled();
      await expect(firstPull).rejects.toBeInstanceOf(DataWeaveError);
      await expect(firstPull).rejects.toThrow(staleGenerationMessage);

      await dw.cleanup();
    });

    it("rejects a lazy runTransform operation after replacement with a different handle before native admission", async () => {
      vi.mocked(ffi.createEngine).mockReturnValueOnce(2).mockReturnValueOnce(3);

      const dw = new DataWeave("/fake/lib");
      dw.initialize();
      const transform = dw.runTransform(
        "output application/json --- payload",
        [Buffer.from("[1, 2, 3]")],
        { mimeType: "application/json" }
      );

      await dw.cleanup();
      dw.initialize();

      const firstPull = transform.next();
      await firstPull.catch(() => undefined);
      expect(ffi.runScriptTransformEngine).not.toHaveBeenCalled();
      await expect(firstPull).rejects.toBeInstanceOf(DataWeaveError);
      await expect(firstPull).rejects.toThrow(staleGenerationMessage);

      await dw.cleanup();
    });

    it("rejects a lazy runTransform operation when the replacement reuses its handle before native admission", async () => {
      vi.mocked(ffi.createEngine).mockReturnValue(2);

      const dw = new DataWeave("/fake/lib");
      dw.initialize();
      const transform = dw.runTransform(
        "output application/json --- payload",
        [Buffer.from("[1, 2, 3]")],
        { mimeType: "application/json" }
      );

      await dw.cleanup();
      dw.initialize();

      const firstPull = transform.next();
      await firstPull.catch(() => undefined);
      expect(ffi.runScriptTransformEngine).not.toHaveBeenCalled();
      await expect(firstPull).rejects.toBeInstanceOf(DataWeaveError);
      await expect(firstPull).rejects.toThrow(staleGenerationMessage);

      await dw.cleanup();
    });

    it("revalidates a runTransform operation after async input pre-buffering before native admission", async () => {
      vi.mocked(ffi.createEngine).mockReturnValue(2);

      let markInputStarted!: () => void;
      const inputStarted = new Promise<void>((resolve) => { markInputStarted = resolve; });
      let resumeInput!: () => void;
      const inputPaused = new Promise<void>((resolve) => { resumeInput = resolve; });
      async function* slowInput(): AsyncGenerator<Buffer> {
        markInputStarted();
        await inputPaused;
        yield Buffer.from("[1, 2, 3]");
      }

      const dw = new DataWeave("/fake/lib");
      dw.initialize();
      const transform = dw.runTransform(
        "output application/json --- payload",
        slowInput(),
        { mimeType: "application/json" }
      );
      const firstPull = transform.next();
      await inputStarted;

      await dw.cleanup();
      dw.initialize();
      resumeInput();

      await firstPull.catch(() => undefined);
      expect(ffi.runScriptTransformEngine).not.toHaveBeenCalled();
      await expect(firstPull).rejects.toBeInstanceOf(DataWeaveError);
      await expect(firstPull).rejects.toThrow(staleGenerationMessage);

      await dw.cleanup();
    });
  });
});
