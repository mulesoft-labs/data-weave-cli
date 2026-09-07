import { describe, it, expect } from "vitest";
import * as ffi from "../../src/ffi";
import { DataWeaveError } from "../../src/errors";
import { findLibrary, buildInputsJson } from "../../src/utils";

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
