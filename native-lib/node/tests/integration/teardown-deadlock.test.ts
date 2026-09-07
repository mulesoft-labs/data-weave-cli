import { describe, it, expect } from "vitest";
import { run, runTransform, cleanup } from "../../src/dataweave";
import { DataWeaveError } from "../../src/errors";

// Regression test for W-23692110 round 5 (Task 1 fix in native-lib/node/src/addon.c).
//
// Bug: napi_initialize used to block the JS thread forever whenever it ran
// while a teardown was pending on the shared native isolate and a
// streaming/transform op was still active elsewhere -- because draining that
// active op can need the very same JS thread napi_initialize was blocking.
// The fix makes napi_initialize adopt the still-live isolate instead of
// waiting, in the window before the teardown waiter thread commits to
// physical teardown.
//
// This loads the REAL native addon (no `vi.mock` of ffi) -- the deadlock is
// entirely in C and cannot be reproduced at the mocked-ffi layer.
//
// Why runTransform (not runStreaming) drives this repro: runStreaming's
// output-chunk delivery uses an unbounded napi_threadsafe_function queue, and
// g_active_ops is decremented on the background worker thread right after it
// detaches from the isolate -- independent of whether the JS event loop ever
// turns. So a blocked JS thread does NOT stop a runStreaming() op from
// draining; there is no genuine circular wait on that path (verified
// empirically: the brief's originally-suggested runStreaming shape resolves
// promptly even against pre-Task-1 addon.c, because an earlier round already
// moved that decrement off the JS thread -- see commit ac8d520).
//
// runTransform's INPUT side is different: transform_read_cb (addon.c) calls
// napi_call_threadsafe_function(w->read_tsfn, &req, napi_tsfn_blocking) and
// then genuinely blocks the background worker thread on a condition variable
// until call_js_read runs on the JS thread and signals it. That JS-thread
// callback synchronously invokes our JS read callback (a plain
// Iterable<Buffer> consumed by a sync generator) via napi_call_function --
// so firing cleanup() and a concurrent run() from *inside* that generator
// deterministically executes them while the background worker is attached
// and blocked waiting for this exact call to return. No timing assumptions
// (no setTimeout/microtask races) are needed: the call graph itself
// guarantees the ordering "worker attached and mid-read" -> "cleanup()
// fired" -> "run() fired", all on the JS thread, before the generator call
// returns and the worker can proceed.
// Task 6 replaces the old callback-triggered pending-teardown scenario with a
// stronger contract: public DataWeave execution is rejected while native code
// is invoking the transform input callback. The real-addon test still proves
// the worker and outer transform drain without a deadlock after that rejection.
describe("public API transform callback reentrancy guard", () => {
  it(
    "rejects a nested module-level run and lets the outer transform drain",
    async () => {
      let fired = false;
      let runError: unknown;

      // Large enough that, at the moment of the very first read pull, the
      // vast majority of reads (and thus the transform op) are still
      // genuinely ahead -- not a timing-sensitive assumption, since the
      // trigger below fires unconditionally on the first pull regardless of
      // how many total reads there are.
      const totalReads = 200000;

      function* input(): Generator<Buffer> {
        for (let i = 0; i < totalReads; i++) {
          if (!fired) {
            fired = true;
            try {
              run('%dw 2.0\noutput application/json\n---\n1 + 1');
            } catch (e) {
              runError = e;
            }
          }
          yield Buffer.from("x");
        }
      }

      const gen = runTransform(
        "output application/octet-stream\n---\npayload",
        input(),
        { mimeType: "application/octet-stream" }
      );

      // Drain the whole transform. On unfixed code, execution never reaches
      // here: the trigger inside input() already froze the JS thread
      // forever before the first read even returns.
      let result = await gen.next();
      while (!result.done) {
        result = await gen.next();
      }

      expect(fired).toBe(true);
      expect(runError).toBeInstanceOf(DataWeaveError);
      expect(result.value.success).toBe(true);

      await cleanup();
    },
    20000
  );
});
