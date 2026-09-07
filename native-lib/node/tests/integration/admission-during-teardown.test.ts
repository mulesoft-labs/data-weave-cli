import { describe, it, expect } from "vitest";
import * as ffi from "../../src/ffi";
import { DataWeaveError } from "../../src/errors";
import { findLibrary, buildInputsJson } from "../../src/utils";

// Round-6 finding #2: napi_run_script_streaming_engine/napi_run_script_transform_engine
// used to read g_initialized outside g_mutex, then reserve g_active_ops in a
// LATER, separate critical section right before spawning the worker thread --
// with no reference to g_teardown_state at all. The fix folds the lifecycle
// check (including g_teardown_state) and the g_active_ops reservation into one
// atomic critical section, before any work/tsfn/promise/bridge is allocated,
// and rejects admission once a teardown is queued/underway
// (g_teardown_state != TEARDOWN_NONE), not just when the isolate is fully gone.
//
// Why this test drives the addon through the raw `ffi` module instead of the
// module-level `run`/`runStreaming`/`runTransform`/`cleanup` singleton (as the
// original brief sketch does): the module-level `cleanup()` nulls the
// singleton, so a later module-level `runStreaming()`/`runTransform()` call
// re-creates a fresh `DataWeave` instance and calls `initialize()` again.
// `napi_initialize`'s TEARDOWN_PENDING_WAIT branch (round-5's deadlock fix)
// treats that as a legitimate ADOPTION of the still-live isolate: it sets
// g_teardown_cancelled = true and cancels the pending teardown *before* the
// second op's admission check ever runs -- so by the time streaming/transform
// admission is checked, g_teardown_state is already back to TEARDOWN_NONE
// (verified empirically while developing this test: the brief's literal shape
// resolves the second op cleanly on both pre-fix and post-fix code, so it
// cannot distinguish them -- it never reaches the vulnerable window because
// the intervening initialize() call cancels the teardown as a side effect).
//
// To actually observe admission-during-pending-teardown, the second op must
// run against the SAME still-live handle/isolate WITHOUT any intervening
// ffi.initialize() call. Calling `ffi.cleanup()` directly (skipping
// `destroyEngine`) triggers exactly napi_cleanup's Case 5 (last ref release
// with an active op) and sets g_teardown_state = TEARDOWN_PENDING_WAIT
// synchronously, under g_mutex, before napi_cleanup returns its Promise to
// JS -- with no adoption path involved, since nothing calls initialize()
// afterward.
//
// Determinism: `ffi.cleanup()`'s synchronous prefix (native napi_cleanup body)
// runs entirely synchronously up to the point where it returns a Promise; the
// TEARDOWN_PENDING_WAIT transition happens on that same synchronous call, not
// after an await. The immediately-following `ffi.runScriptStreamingEngine`
// call re-enters native code synchronously (it's a plain N-API call), on the
// very same JS callstack, so it deterministically observes
// g_teardown_state == TEARDOWN_PENDING_WAIT with no timing assumptions --
// mirroring the round-5 teardown-deadlock test's use of a synchronous native
// read-callback to force deterministic ordering instead of timers.
//
// Task 6 supersedes this test's callback-based pending-teardown trigger. While
// native code invokes a transform read callback, all isolate-touching methods
// are rejected before lifecycle mutation or worker admission. Use cleanup and
// streaming start together to cover both guards and the TypeScript mapping.
describe("transform read callback admission guard", () => {
  it("rejects cleanup and streaming start before native admission", async () => {
    ffi.initialize(findLibrary());
    const handle = ffi.createEngine();

    let cleanupErr: unknown;
    let admitErr: unknown;
    let admitted = false;

    let firstRead = true;
    const readCb = (_bufSize: number): Buffer | null => {
      if (firstRead) {
        firstRead = false;
        try {
          ffi.cleanup();
        } catch (e) {
          cleanupErr = e;
        }
        try {
          ffi.runScriptStreamingEngine(
            handle,
            "%dw 2.0\noutput application/json\n---\n[1,2,3]",
            buildInputsJson({}),
            () => {}
          );
          admitted = true;
        } catch (e) {
          admitErr = e;
        }

        return Buffer.from("[1,2,3]");
      }
      return null; // EOF after the first chunk
    };

    const chunks: Buffer[] = [];
    const writeCb = (chunk: Buffer) => { chunks.push(chunk); };

    try {
      const resultRaw = await ffi.runScriptTransformEngine(
        handle,
        "output application/json\n---\npayload",
        "{}",
        "payload",
        "application/json",
        null,
        readCb,
        writeCb
      );
      const result = JSON.parse(resultRaw);
      expect(result.success).toBe(true);
      expect(cleanupErr).toBeInstanceOf(DataWeaveError);
      expect(admitErr).toBeInstanceOf(DataWeaveError);
      expect(admitted).toBe(false);
    } finally {
      ffi.destroyEngine(handle);
      await ffi.cleanup();
    }
  }, 20000);
});
