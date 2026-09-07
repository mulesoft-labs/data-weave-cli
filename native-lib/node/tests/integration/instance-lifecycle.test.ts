import { describe, it, expect, afterEach } from "vitest";
import { DataWeave, run, cleanup } from "../../src/dataweave";
import { DataWeaveError } from "../../src/errors";
import * as ffi from "../../src/ffi";
import { findLibrary, buildInputsJson } from "../../src/utils";

// Same-instance lifecycle regression tests (round 6, W-23692110). Round 5's
// coverage used a second instance; the same-instance cleanup window is exactly
// what findings #1 and #3 exploit. Real addon, no mocking.
describe("instance lifecycle during cleanup (round 6)", () => {
  let dw: DataWeave | undefined;
  afterEach(async (ctx) => {
    // Whatever state each test leaves it in, drain and release so the shared
    // process-wide isolate is clean for sibling tests.
    if (dw) {
      const inst = dw;
      dw = undefined;
      let cleanupErr: unknown;
      try {
        await inst.cleanup();
      } catch (e) {
        cleanupErr = e;
      }
      // A cleanup() failure is itself a real lifecycle regression: surface it
      // when the test body PASSED. Suppress it only when the body already FAILED,
      // so the original, more actionable assertion failure keeps propagating
      // (review #9 #4; mirrors the worker-lifecycle balancing pattern).
      if (cleanupErr !== undefined && ctx.task.result?.state !== "fail") {
        throw cleanupErr;
      }
    }
  });

  // Finding #3: initialize() during the same instance's pending cleanup must
  // reject deterministically, not be a silent no-op that leaves the instance
  // uninitialized after cleanup settles.
  it("initialize() during pending cleanup throws, and re-init works after cleanup settles", async () => {
    dw = new DataWeave();
    dw.initialize();
    const closing = dw.cleanup(); // not awaited: instance is now "cleaning-up"
    expect(() => dw!.initialize()).toThrow(DataWeaveError);
    expect(() => dw!.initialize()).toThrow(/cleanup is in progress/i);
    await closing; // now "uninitialized"
    // Explicit re-init now succeeds and the instance is usable again.
    dw.initialize();
    const r = dw.run("%dw 2.0\noutput application/json\n---\n1 + 1");
    expect(r.success).toBe(true);
    expect(JSON.parse(r.getString()!)).toBe(2);
  });

  // Finding #1: run() during the cleanup window must throw a clean DataWeaveError
  // (never send a null handle to C), because doCleanup() nulls engineHandle
  // synchronously before awaiting native cleanup.
  it("run() during pending cleanup throws DataWeaveError, not a native/null-handle error", async () => {
    dw = new DataWeave();
    dw.initialize();
    const closing = dw.cleanup();
    expect(() => dw!.run("%dw 2.0\noutput application/json\n---\n1")).toThrow(DataWeaveError);
    expect(() => dw!.run("%dw 2.0\noutput application/json\n---\n1")).toThrow(/cleaning up/i);
    await closing;
  });

  // Finding #1, streaming/transform variants: ordinary public methods capture
  // their engine identity at call time, so a call during cleanup rejects before
  // it can return a generator or admit native work.
  it("runStreaming()/runTransform() during pending cleanup reject at call time", async () => {
    dw = new DataWeave();
    dw.initialize();
    const closing = dw.cleanup();

    expect(() =>
      dw!.runStreaming("%dw 2.0\noutput application/json\n---\n[1,2,3]")
    ).toThrow(DataWeaveError);

    expect(() =>
      dw!.runTransform(
        "output application/json\n---\npayload",
        [Buffer.from("[1,2,3]")],
        { mimeType: "application/json" }
      )
    ).toThrow(DataWeaveError);

    await closing;
  });

  // Idempotency preserved: cleanup() before initialize() is a no-op; double
  // cleanup() coalesces (round-4 F1 must survive this refactor).
  it("cleanup() is a no-op when uninitialized and coalesces when called twice", async () => {
    dw = new DataWeave();
    await expect(dw.cleanup()).resolves.toBeUndefined(); // uninitialized no-op
    dw.initialize();
    const a = dw.cleanup();
    const b = dw.cleanup(); // must return the same in-flight settlement, one native teardown
    await Promise.all([a, b]);
  });
});

// Round 12, Task 1: napi_cleanup's Case 1..5 decrement-and-teardown body was
// lifted verbatim into release_isolate_ref_locked() so a later task (round-12
// #2) can reuse it from the abandoned-env path. This is a behavior-preserving
// refactor; this test pins the observable contract it must not disturb: the
// balancing cleanup() call that drops the ref count to zero must actually
// tear the isolate down synchronously, not leave it silently live.
//
// Driven through the raw `ffi` boundary (like handle-validation.test.ts and
// engine-handle-contract.test.ts), with a balanced initialize()/cleanup()
// pair, so this file doesn't leak a ref-count bump into sibling integration
// test files sharing the same vitest worker process.
describe("napi_cleanup refactor preserves last-release teardown (round 12 Task 1)", () => {
  it("the balancing cleanup() actually tears the isolate down (subsequent engine call sees not-initialized)", async () => {
    ffi.initialize(findLibrary());
    const h = ffi.createEngine();
    const envelope = JSON.parse(
      ffi.runScriptEngine(h, "%dw 2.0\noutput application/json\n---\n1 + 1", buildInputsJson({}))
    );
    expect(envelope.success).toBe(true);
    expect(JSON.parse(Buffer.from(envelope.result, "base64").toString("utf-8"))).toBe(2);
    ffi.destroyEngine(h);
    await ffi.cleanup();
    // Ref count reached 0 and the isolate was torn down: a fresh engine call
    // must observe "not initialized", not silently run on a live isolate.
    expect(() =>
      ffi.runScriptEngine(Number.MAX_SAFE_INTEGER, "%dw 2.0\noutput application/json\n---\n1", buildInputsJson({}))
    ).toThrow(/not initialized/i);
  });
});

// Round 12, Task 4: createChunkReader pre-buffers async inputs by awaiting
// the entire iterable up front (see reader.ts), because the native read
// callback is invoked synchronously and cannot await. That await can span
// arbitrarily long, so if the caller cleans up the instance while it's in
// flight, runTransform must re-check readiness on resume rather than
// dispatching to a nulled/destroyed engine handle.
describe("runTransform re-checks readiness after async input pre-buffering (round 12 Task 4)", () => {
  it("throws a synchronous DataWeaveError if cleanup() runs during createChunkReader's await, instead of resolving an error envelope", async () => {
    const dw = new DataWeave();
    dw.initialize();

    // An async input whose iterator blocks until released, so cleanup() can
    // run while createChunkReader is still pre-buffering it.
    let release!: () => void;
    const gate = new Promise<void>((r) => { release = r; });
    async function* slowInput(): AsyncGenerator<Buffer> {
      await gate;
      yield Buffer.from("[1,2,3]");
    }

    const gen = dw.runTransform("%dw 2.0\noutput application/json\n---\npayload", slowInput(), {
      mimeType: "application/json",
    });

    // Start driving the generator; it suspends awaiting createChunkReader ->
    // slowInput's gate.
    const firstNext = gen.next();
    // Clean up while the input is still pre-buffering.
    await dw.cleanup();
    // Release the gate so createChunkReader's await resolves; the readiness
    // re-check must now throw synchronously rather than proceeding to a
    // nulled engine handle.
    release();

    await expect(firstNext).rejects.toBeInstanceOf(DataWeaveError);
  });
});

describe("lazy streams are bound to their engine generation (Task 7)", () => {
  const staleGenerationMessage = "DataWeave operation belongs to a stale engine generation.";
  const expectStaleGenerationError = async (operation: Promise<unknown>): Promise<void> => {
    let error: unknown;
    try {
      await operation;
    } catch (caught) {
      error = caught;
    }
    expect(error).toBeInstanceOf(DataWeaveError);
    expect((error as DataWeaveError).message).toBe(staleGenerationMessage);
  };

  it("rejects stale runStreaming work and allows a replacement-generation stream", async () => {
    const anchor = new DataWeave();
    const target = new DataWeave();
    anchor.initialize();

    try {
      target.initialize();
      const stale = target.runStreaming("output application/json --- [1, 2, 3]");

      await target.cleanup();
      target.initialize();

      const stalePull = stale.next();
      await expectStaleGenerationError(stalePull);

      const current = target.runStreaming("output application/json --- [4, 5, 6]");
      const chunks: Buffer[] = [];
      let result = await current.next();
      while (!result.done) {
        chunks.push(result.value);
        result = await current.next();
      }
      expect(result.value.success).toBe(true);
      expect(JSON.parse(Buffer.concat(chunks).toString("utf-8"))).toEqual([4, 5, 6]);
    } finally {
      let targetError: unknown;
      try {
        await target.cleanup();
      } catch (e) {
        targetError = e;
      }
      await anchor.cleanup();
      if (targetError !== undefined) throw targetError;
    }
  });

  it("rejects stale runTransform work and allows a replacement-generation transform", async () => {
    const anchor = new DataWeave();
    const target = new DataWeave();
    anchor.initialize();

    try {
      target.initialize();
      const stale = target.runTransform(
        "output application/json --- payload map ($ * 2)",
        [Buffer.from("[1, 2, 3]")],
        { mimeType: "application/json" }
      );

      await target.cleanup();
      target.initialize();

      const stalePull = stale.next();
      await expectStaleGenerationError(stalePull);

      const current = target.runTransform(
        "output application/json --- payload map ($ * 2)",
        [Buffer.from("[4, 5, 6]")],
        { mimeType: "application/json" }
      );
      const chunks: Buffer[] = [];
      let result = await current.next();
      while (!result.done) {
        chunks.push(result.value);
        result = await current.next();
      }
      expect(result.value.success).toBe(true);
      expect(JSON.parse(Buffer.concat(chunks).toString("utf-8"))).toEqual([8, 10, 12]);
    } finally {
      let targetError: unknown;
      try {
        await target.cleanup();
      } catch (e) {
        targetError = e;
      }
      await anchor.cleanup();
      if (targetError !== undefined) throw targetError;
    }
  });
});

// Round 12, Task 6: the exported module-level cleanup() nulls globalInstance
// synchronously, then awaits instance.cleanup(). A second overlapping
// module-level cleanup() call must coalesce onto the SAME in-flight drain
// rather than seeing globalInstance already nulled and resolving immediately
// -- before the first call's native teardown actually finishes.
describe("module-level cleanup() coalescing (round 12 Task 6)", () => {
  it("module-level cleanup() coalesces overlapping calls (round 12 #5)", async () => {
    // Create the singleton.
    expect(run("%dw 2.0\noutput application/json\n---\n1 + 1").success).toBe(true);

    let firstSettled = false;
    const p1 = cleanup().then(() => { firstSettled = true; });
    // Second call overlaps the first's in-flight drain.
    const p2 = cleanup();
    // The coalesced second call must not resolve before the first's drain does.
    await p2;
    expect(firstSettled).toBe(true);
    await p1;

    // A subsequent run lazily revives the singleton (no wedged state).
    expect(run("%dw 2.0\noutput application/json\n---\n2 + 2").success).toBe(true);
    await cleanup();
  });
});

// Final review round 12 #1: Task 6's coalescing guard (`if (cleanupPromise)
// return cleanupPromise;`) is unconditional, so a caller that revives the
// singleton (via run()) while an OLDER drain is still in flight gets the OLD
// drain's promise handed back by the newer cleanup() call -- the freshly
// revived instance is never hooked up to any doCleanup()/ffi.cleanup() call
// and its native ref leaks for the rest of the process. Pinned via the same
// ref-count proxy as the "napi_cleanup refactor" test above: after both
// cleanup() calls settle, the isolate's ref count must have actually returned
// to zero (not be left at 1 by a leaked, unrevived-then-abandoned instance).
describe("module-level cleanup() does not orphan a revived singleton (final review round 12 #1)", () => {
  it("cleanup() started during an in-flight drain cleans the CURRENT (revived) singleton, not the stale one", async () => {
    // (a) Create the singleton (instance A).
    expect(run("%dw 2.0\noutput application/json\n---\n1 + 1").success).toBe(true);

    // (b) Start draining A WITHOUT awaiting.
    const p1 = cleanup();

    // (c) Revive a FRESH singleton (instance B) while A's drain is in flight.
    expect(run("%dw 2.0\noutput application/json\n---\n2 + 2").success).toBe(true);

    // (d) Call cleanup() again. Under the bug this returns p1 verbatim,
    // leaving B's native ref uncleaned once both promises settle.
    const p2 = cleanup();
    await Promise.all([p1, p2]);

    // (e) Prove B was actually torn down via the isolate's ref count, the same
    // technique as "napi_cleanup refactor preserves last-release teardown"
    // above: do one extra balanced initialize()/cleanup() pair. If the ref
    // count was already back to zero (both A and B cleaned), this nets back
    // to zero and a subsequent raw engine call observes "not initialized". If
    // B's ref instead leaked, the ref count is already >=1 going into this
    // balanced pair, so it nets to >=1 afterward and the isolate stays alive
    // -- the subsequent call would NOT report "not initialized".
    ffi.initialize(findLibrary());
    const h = ffi.createEngine();
    const envelope = JSON.parse(
      ffi.runScriptEngine(h, "%dw 2.0\noutput application/json\n---\n5 + 5", buildInputsJson({}))
    );
    expect(envelope.success).toBe(true);
    expect(JSON.parse(Buffer.from(envelope.result, "base64").toString("utf-8"))).toBe(10);
    ffi.destroyEngine(h);
    await ffi.cleanup(); // Balances the initialize() just above, ONLY.

    expect(() =>
      ffi.runScriptEngine(Number.MAX_SAFE_INTEGER, "%dw 2.0\noutput application/json\n---\n1", buildInputsJson({}))
    ).toThrow(/not initialized/i);

    // The singleton revives cleanly again afterward -- no wedged module state.
    expect(run("%dw 2.0\noutput application/json\n---\n3 + 3").success).toBe(true);
    await cleanup();
  });
});
