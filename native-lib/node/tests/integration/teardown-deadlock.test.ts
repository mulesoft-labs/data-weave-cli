import { describe, it, expect } from "vitest";
import { run, runStreaming, runTransform, cleanup } from "../../src/dataweave";
import { DataWeaveError } from "../../src/errors";

interface TestAddon {
  __test_holdNextAsyncOp(): void;
  __test_asyncOpHeld(): boolean;
  __test_releaseAsyncOp(): void;
}

// eslint-disable-next-line @typescript-eslint/no-var-requires
const testAddon = require("../../build/Release/dwlib_addon.node") as TestAddon;

async function waitForAsyncOpGate(): Promise<void> {
  const deadline = Date.now() + 10000;
  while (!testAddon.__test_asyncOpHeld()) {
    if (Date.now() >= deadline) throw new Error("async operation did not reach test gate");
    await new Promise<void>((resolve) => setImmediate(resolve));
  }
}

// Task 6 adds this callback-specific contract alongside the adoption regression
// below: public DataWeave execution is rejected while native code is invoking
// the transform input callback. The real-addon test also proves the worker and
// outer transform drain without a deadlock after that rejection.
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

      try {
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
      } finally {
        await cleanup();
      }
    },
    20000
  );
});

describe("re-init during pending teardown (W-23692110, round 5 P1)", () => {
  it("adopts the live isolate instead of blocking initialize behind an active op", async () => {
    let cleanupPromise: Promise<void> | undefined;
    let gateArmed = false;
    let gateReleased = false;

    try {
      expect(run("%dw 2.0\noutput application/json\n---\n6 * 7").success).toBe(true);
      testAddon.__test_holdNextAsyncOp();
      gateArmed = true;
      const outer = runStreaming("%dw 2.0\noutput application/json\n---\n[1,2,3]");
      const firstNext = outer.next();
      await waitForAsyncOpGate();
      cleanupPromise = cleanup();

      const result = run("%dw 2.0\noutput application/json\n---\n1 + 1");
      expect(result.success).toBe(true);
      expect(JSON.parse(result.getString()!)).toBe(2);

      testAddon.__test_releaseAsyncOp();
      gateReleased = true;
      await firstNext;
      await expect(outer.next()).resolves.toEqual({ done: true, value: undefined });
      await cleanupPromise;
    } finally {
      if (gateArmed && !gateReleased) testAddon.__test_releaseAsyncOp();
      if (cleanupPromise) await cleanupPromise;
      await cleanup();
    }
  }, 20000);
});
