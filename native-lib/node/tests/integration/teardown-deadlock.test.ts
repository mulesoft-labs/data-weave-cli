import { describe, it, expect } from "vitest";
import { run, runTransform, cleanup } from "../../src/dataweave";
import { DataWeaveError } from "../../src/errors";

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
