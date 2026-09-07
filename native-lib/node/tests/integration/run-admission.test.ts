import { describe, it, expect } from "vitest";
import * as ffi from "../../src/ffi";
import { DataWeaveError } from "../../src/errors";
import { findLibrary, buildInputsJson } from "../../src/utils";

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
