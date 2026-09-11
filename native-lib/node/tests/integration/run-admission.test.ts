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
// lifecycle regression below: lifecycle and execution entry from a native
// callback are rejected before cleanup can queue teardown or run can attach to
// the isolate. Drive the real addon through ffi so this also verifies TypeScript
// error normalization, while the successful outer transform proves callback
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

describe("run() admission rejected while teardown pending (round 7 #1)", () => {
  it("rejects a synchronous run instead of attaching to a tearing-down isolate", async () => {
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

      let runErr: unknown;
      let ran = false;
      try {
        ffi.runScriptEngine(
          handle,
          "%dw 2.0\noutput application/json\n---\n1 + 1",
          buildInputsJson({})
        );
        ran = true;
      } catch (error) {
        runErr = error;
      }

      expect(runErr).toBeTruthy();
      expect(ran).toBe(false);

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
