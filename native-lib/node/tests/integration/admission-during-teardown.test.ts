import { describe, it, expect } from "vitest";
import * as ffi from "../../src/ffi";
import { DataWeaveError } from "../../src/errors";
import { findLibrary, buildInputsJson } from "../../src/utils";

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

// Task 6 adds this callback-specific contract alongside the pending-teardown
// lifecycle regression below. While native code invokes a transform read
// callback, all isolate-touching methods are rejected before lifecycle mutation
// or worker admission. Use cleanup and streaming start together to cover both
// guards and the TypeScript mapping.
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

describe("admission rejected while teardown pending (round 6 #2)", () => {
  it("rejects a streaming op on the same handle while teardown is pending", async () => {
    ffi.initialize(findLibrary());
    const handle = ffi.createEngine();
    let cleanupPromise: Promise<void> | undefined;
    let gateArmed = false;
    let gateReleased = false;
    let handleDestroyed = false;
    const chunks: Buffer[] = [];

    try {
      testAddon.__test_holdNextAsyncOp();
      gateArmed = true;
      const outerPromise = ffi.runScriptStreamingEngine(
        handle,
        "%dw 2.0\noutput application/json\n---\n[1,2,3]",
        buildInputsJson({}),
        (chunk) => chunks.push(chunk)
      );
      await waitForAsyncOpGate();
      cleanupPromise = ffi.cleanup();

      let admitErr: unknown;
      let admitted = false;
      let secondOpSettled: Promise<void> = Promise.resolve();
      try {
        secondOpSettled = ffi.runScriptStreamingEngine(
          handle,
          "%dw 2.0\noutput application/json\n---\n[1,2,3]",
          buildInputsJson({}),
          () => {}
        ).then(
          () => { admitted = true; },
          (error) => { admitErr = error; }
        );
      } catch (error) {
        admitErr = error;
      }

      await secondOpSettled;
      expect(admitErr).toBeTruthy();
      expect(admitted).toBe(false);

      ffi.destroyEngine(handle);
      handleDestroyed = true;
      testAddon.__test_releaseAsyncOp();
      gateReleased = true;
      const outerResult = JSON.parse(await outerPromise);
      expect(outerResult.success).toBe(true);
      expect(JSON.parse(Buffer.concat(chunks).toString("utf-8"))).toEqual([1, 2, 3]);
      await cleanupPromise;
    } finally {
      if (gateArmed && !gateReleased) testAddon.__test_releaseAsyncOp();
      try {
        if (!handleDestroyed) ffi.destroyEngine(handle);
      } finally {
        if (cleanupPromise) await cleanupPromise;
        await ffi.cleanup();
      }
    }
  }, 20000);
});
