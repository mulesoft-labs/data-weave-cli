import { describe, it, expect } from "vitest";
import * as ffi from "../../src/ffi";
import { DataWeaveError } from "../../src/errors";
import { findLibrary, buildInputsJson } from "../../src/utils";

// Round-7 finding #1: the synchronous napi_run_script_engine touched the
// isolate (fn_attach_thread -> fn_run_script_engine -> fn_detach_thread) with
// only a top-of-function !g_initialized fast-path and NO g_active_ops
// reservation under g_mutex. A second Worker's last cleanup() (napi_cleanup
// Case 4) could observe g_active_ops == 0 and tear down g_isolate while this
// op was attaching/executing -- a use-after-free.
//
// The genuine cross-Worker TOCTOU is not reliably forceable from single-thread
// JS (same limitation the round-6 #2 admission-during-teardown test documents:
// re-init would trigger the adoption path and cancel the pending teardown
// before the admission check runs). What we CAN assert deterministically is
// the admission-rejection path the fix introduces: once a teardown is pending
// (g_teardown_state != TEARDOWN_NONE), a freshly started run() is rejected with
// a synchronous throw rather than attaching to an isolate a concurrent teardown
// could pull out from under it. The C-level reasoning -- check-and-reserve is
// now one atomic critical section on the run() path -- is what covers the race
// itself.
//
// We drive the addon through the raw `ffi` module (not the module-level
// singleton) so the second op runs against the SAME still-live handle/isolate
// with no intervening ffi.initialize() call to trigger adoption. Calling
// ffi.cleanup() directly triggers napi_cleanup Case 5 and sets
// g_teardown_state = TEARDOWN_PENDING_WAIT synchronously, before its Promise is
// returned; the immediately-following ffi.runScriptEngine re-enters native code
// synchronously on the same callstack and deterministically observes it.
//
// Task 6 supersedes this test's old callback-based pending-teardown trigger:
// lifecycle and execution entry from a native callback are now rejected before
// cleanup can queue teardown or run can attach to the isolate. Drive the real
// addon through ffi so this also verifies TypeScript error normalization for a
// transform read callback, while the successful outer transform proves callback
// depth is restored afterward.
describe("transform read callback reentrancy guard", () => {
  it("rejects cleanup and run before native admission and preserves the outer transform", async () => {
    ffi.initialize(findLibrary());
    const handle = ffi.createEngine();
    let cleanupErr: unknown;
    let runErr: unknown;
    let ran = false;

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
          ffi.runScriptEngine(
            handle,
            "%dw 2.0\noutput application/json\n---\n1 + 1",
            buildInputsJson({})
          );
          ran = true;
        } catch (e) {
          runErr = e;
        }
        return Buffer.from("[1,2,3]");
      }
      return null;
    };

    const writeCb = (_chunk: Buffer) => {};

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
      expect(runErr).toBeInstanceOf(DataWeaveError);
      expect(ran).toBe(false);
    } finally {
      ffi.destroyEngine(handle);
      await ffi.cleanup();
    }
  }, 20000);
});
