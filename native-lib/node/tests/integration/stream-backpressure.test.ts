import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { DataWeave } from "../../src/dataweave";
import { buildInputsJson, findLibrary } from "../../src/utils";

interface NativeStreamingOperation {
  readonly completion: Promise<string>;
  acknowledge(sequence: bigint, bytes: number): void;
  cancel(): void;
  close(): void;
  then<TResult1 = string, TResult2 = never>(
    onfulfilled?: ((value: string) => TResult1 | PromiseLike<TResult1>) | null,
    onrejected?: ((reason: unknown) => TResult2 | PromiseLike<TResult2>) | null
  ): Promise<TResult1 | TResult2>;
  catch<TResult = never>(
    onrejected?: ((reason: unknown) => TResult | PromiseLike<TResult>) | null
  ): Promise<string | TResult>;
  finally(onfinally?: (() => void) | null): Promise<string>;
}

interface OutputFlowStats {
  operationId: number;
  outstandingBytes: number;
  outstandingChunks: number;
  peakBufferedBytes: number;
  peakBufferedChunks: number;
  largestChunkBytes: number;
  highBytes: number;
  lowBytes: number;
  highChunks: number;
  lowChunks: number;
  paused: boolean;
  cancelled: boolean;
  done: boolean;
  liveFlows: number;
}

interface TestAddon {
  initialize(libPath: string): void;
  createEngine(): number;
  destroyEngine(handle: number): void;
  runScriptStreamingEngine(
    handle: number,
    script: string,
    inputsJson: string,
    chunkCb: (chunk: Buffer, sequence: bigint) => void
  ): NativeStreamingOperation;
  runScriptTransformEngine(
    handle: number,
    script: string,
    inputsJson: string,
    inputName: string,
    inputMimeType: string,
    inputCharset: string | null,
    readCb: (bufSize: number) => Buffer | null,
    writeCb: (chunk: Buffer, sequence: bigint) => void
  ): NativeStreamingOperation;
  cleanup(): Promise<void>;
  __test_outputStats(operationId?: number): OutputFlowStats;
  __test_outputOperationId(operation: NativeStreamingOperation): number;
  __test_createForeignWrappedObject(): object;
  __test_failNextOutputSettlement(): void;
}

interface PendingChunk {
  readonly chunk: Buffer;
  readonly sequence: bigint;
}

const ADDON_PATH = "../../build/Release/dwlib_addon.node";
// eslint-disable-next-line @typescript-eslint/no-var-requires
const addon = require(ADDON_PATH) as TestAddon;
const LIB_PATH = findLibrary();
const LARGE_SIZE = 2 * 1024 * 1024 + 32771;
const PASSTHROUGH_SCRIPT =
  "output application/octet-stream deferred=true\n---\npayload";

function patternedBytes(size = LARGE_SIZE): Buffer {
  const bytes = Buffer.allocUnsafe(size);
  for (let i = 0; i < size; i++) bytes[i] = 32 + (i % 95);
  return bytes;
}

function octetStreamInputs(payload: Buffer): string {
  return buildInputsJson({
    payload: {
      content: payload,
      mimeType: "application/octet-stream",
    },
  });
}

function immediate(): Promise<void> {
  return new Promise((resolve) => setImmediate(resolve));
}

async function withTimeout<T>(promise: Promise<T>, label: string, timeoutMs = 10000): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      promise,
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error(`${label} timed out`)), timeoutMs);
      }),
    ]);
  } finally {
    if (timer !== undefined) clearTimeout(timer);
  }
}

async function waitForStats(
  predicate: (stats: OutputFlowStats) => boolean,
  label: string,
  operationId?: number,
  timeoutMs = 10000
): Promise<OutputFlowStats> {
  const deadline = Date.now() + timeoutMs;
  while (true) {
    const stats = operationId === undefined
      ? addon.__test_outputStats()
      : addon.__test_outputStats(operationId);
    if (predicate(stats)) return stats;
    if (Date.now() >= deadline) {
      throw new Error(`${label} timed out; last stats: ${JSON.stringify(stats)}`);
    }
    await immediate();
  }
}

function expectBounded(stats: OutputFlowStats): void {
  expect(stats.peakBufferedChunks).toBeLessThanOrEqual(stats.highChunks + 1);
  expect(stats.peakBufferedBytes).toBeLessThanOrEqual(
    stats.highBytes + stats.largestChunkBytes
  );
}

async function waitForPendingChunk(pending: PendingChunk[], label: string): Promise<PendingChunk> {
  const deadline = Date.now() + 10000;
  while (pending.length === 0) {
    if (Date.now() >= deadline) throw new Error(`${label} timed out`);
    await immediate();
  }
  return pending.shift()!;
}

async function drainAfterPause(
  operation: NativeStreamingOperation,
  operationId: number,
  pending: PendingChunk[],
  received: Buffer[],
  settled: () => boolean
): Promise<string> {
  const paused = await waitForStats(
    (stats) => stats.paused && stats.outstandingChunks === pending.length,
    "producer pause with every admitted callback delivered",
    operationId
  );
  expectBounded(paused);

  const deliveredAtPause = received.length;
  let projectedBytes = paused.outstandingBytes;
  let projectedChunks = paused.outstandingChunks;
  while (projectedBytes > paused.lowBytes || projectedChunks > paused.lowChunks) {
    const { chunk, sequence } = await waitForPendingChunk(pending, "low-water acknowledgement");
    operation.acknowledge(sequence, chunk.length);
    projectedBytes -= chunk.length;
    projectedChunks--;
  }

  expect(projectedBytes).toBeLessThanOrEqual(paused.lowBytes);
  expect(projectedChunks).toBeLessThanOrEqual(paused.lowChunks);
  await waitForStats(
    () => received.length > deliveredAtPause,
    "producer wake below both low watermarks",
    operationId
  );

  const deadline = Date.now() + 15000;
  while (
    !settled() ||
    pending.length > 0 ||
    addon.__test_outputStats(operationId).outstandingChunks > 0
  ) {
    if (pending.length > 0) {
      const { chunk, sequence } = pending.shift()!;
      operation.acknowledge(sequence, chunk.length);
    }
    if (Date.now() >= deadline) {
      throw new Error(
        `stepwise drain timed out; stats: ${JSON.stringify(addon.__test_outputStats(operationId))}`
      );
    }
    await immediate();
  }

  return withTimeout(operation.completion, "native completion after drain");
}

async function runWithEngine(
  body: (handle: number) => Promise<void>
): Promise<void> {
  const handle = addon.createEngine();
  try {
    await body(handle);
  } finally {
    addon.destroyEngine(handle);
  }
}

beforeAll(() => {
  addon.initialize(LIB_PATH);
});

afterAll(async () => {
  await addon.cleanup();
});

describe.sequential("native Node output flow control", () => {
  it("returns a validated, idempotent operation controller", async () => {
    await runWithEngine(async (handle) => {
      const chunks: Buffer[] = [];
      let operation: NativeStreamingOperation | undefined;
      try {
        operation = addon.runScriptStreamingEngine(
          handle,
          "output application/json deferred=true --- [1, 2, 3]",
          "{}",
          (chunk) => chunks.push(chunk)
        );

        expect(operation).toEqual(
          expect.objectContaining({
            completion: expect.any(Promise),
            acknowledge: expect.any(Function),
            cancel: expect.any(Function),
            close: expect.any(Function),
          })
        );

        for (const invalid of [-1, 0.5, Number.NaN, Number.POSITIVE_INFINITY, "1"]) {
          expect(() => operation!.acknowledge(1n, invalid as number)).toThrow();
        }

        for (const invalid of [
          -1,
          0,
          0.5,
          Number.NaN,
          Number.POSITIVE_INFINITY,
          "1",
          -1n,
          0n,
          1n << 64n,
        ]) {
          expect(() => operation!.acknowledge(invalid as bigint, 1)).toThrow();
        }

        const raw = await withTimeout(operation.completion, "controller completion");
        expect(JSON.parse(raw).success).toBe(true);
        expect(() => operation.cancel()).not.toThrow();
        expect(() => operation.cancel()).not.toThrow();
        expect(() => operation.close()).not.toThrow();
        expect(() => operation.close()).not.toThrow();
        expect(() => operation.acknowledge(1n, 1)).not.toThrow();
      } finally {
        if (operation !== undefined) {
          operation.cancel?.();
          operation.close?.();
          const completion = operation.completion ?? (operation as unknown as Promise<string>);
          await withTimeout(Promise.resolve(completion), "controller test cleanup");
        }
      }
    });
  });

  it("rejects borrowed controller methods and test introspection on a foreign wrapper", async () => {
    await runWithEngine(async (handle) => {
      const operation = addon.runScriptStreamingEngine(
        handle,
        "%dw 2.0\noutput application/json\n---\n[]",
        "{}",
        () => {}
      );
      const foreign = addon.__test_createForeignWrappedObject();

      try {
        for (const method of [operation.acknowledge, operation.cancel, operation.close]) {
          expect(() => method.call(foreign, 1n, 1)).toThrow(TypeError);
        }
        expect(() => operation.then.call(foreign, () => {})).toThrow(TypeError);
        expect(() => operation.catch.call(foreign, () => {})).toThrow(TypeError);
        expect(() => operation.finally.call(foreign, () => {})).toThrow(TypeError);
        expect(() => addon.__test_outputOperationId(foreign as NativeStreamingOperation)).toThrow(
          TypeError
        );

        await withTimeout(operation.completion, "receiver-tag operation completion");
      } finally {
        operation.cancel();
        operation.close();
      }
    });
  });

  it("bounds a paused streaming producer and resumes only after low-water credit", async () => {
    await runWithEngine(async (handle) => {
      expect(typeof addon.__test_outputStats).toBe("function");
      const expected = patternedBytes();
      const received: Buffer[] = [];
      const pending: PendingChunk[] = [];
      let operation!: NativeStreamingOperation;
      let operationId!: number;
      let completionSettled = false;

      operation = addon.runScriptStreamingEngine(
        handle,
        PASSTHROUGH_SCRIPT,
        octetStreamInputs(expected),
        (chunk, sequence) => {
          received.push(chunk);
          pending.push({ chunk, sequence });
          if (received.length === 1) {
            const first = pending.shift()!;
            operation.acknowledge(first.sequence, first.chunk.length);
          }
        }
      );
      operationId = addon.__test_outputOperationId(operation);
      operation.completion.finally(() => { completionSettled = true; }).catch(() => {});

      try {
        const paused = await waitForStats(
          (stats) => stats.paused,
          "streaming producer pause",
          operationId
        );
        expect(completionSettled).toBe(false);
        expectBounded(paused);

        const raw = await drainAfterPause(
          operation,
          operationId,
          pending,
          received,
          () => completionSettled
        );
        expect(Buffer.concat(received)).toEqual(expected);
        expect(JSON.parse(raw)).toMatchObject({
          success: true,
          mimeType: "application/octet-stream",
        });

        const drained = addon.__test_outputStats(operationId);
        expect(drained).toMatchObject({
          outstandingBytes: 0,
          outstandingChunks: 0,
          done: true,
        });
      } finally {
        operation.cancel();
        operation.close();
        await withTimeout(operation.completion, "streaming test cleanup");
      }
    });
  }, 30000);

  it("rejects duplicate, early, out-of-order, and mismatched acknowledgements without releasing credit", async () => {
    await runWithEngine(async (handle) => {
      const pending: PendingChunk[] = [];
      let earlyAcknowledgementRejected = false;
      let operation!: NativeStreamingOperation;
      operation = addon.runScriptStreamingEngine(
        handle,
        PASSTHROUGH_SCRIPT,
        octetStreamInputs(Buffer.alloc(LARGE_SIZE, 65)),
        (chunk, sequence) => {
          if (pending.length === 0) {
            try {
              operation.acknowledge(sequence + 1n, chunk.length);
            } catch {
              earlyAcknowledgementRejected = true;
            }
          }
          pending.push({ chunk, sequence });
        }
      );
      const operationId = addon.__test_outputOperationId(operation);

      try {
        const paused = await waitForStats(
          (stats) => stats.paused && pending.length >= 2,
          "equal-sized acknowledgement validation window",
          operationId
        );
        const first = pending[0];
        const second = pending[1];
        expect(first.chunk.length).toBe(second.chunk.length);
        expect(earlyAcknowledgementRejected).toBe(true);

        const expectRejectedWithoutAccountingChange = (
          invoke: () => void,
          before: OutputFlowStats
        ): void => {
          expect(invoke).toThrow();
          expect(addon.__test_outputStats(operationId)).toMatchObject({
            outstandingBytes: before.outstandingBytes,
            outstandingChunks: before.outstandingChunks,
            paused: true,
          });
        };

        expectRejectedWithoutAccountingChange(
          () => operation.acknowledge(second.sequence, second.chunk.length),
          paused
        );
        expectRejectedWithoutAccountingChange(
          () => operation.acknowledge(first.sequence + 1000000n, first.chunk.length),
          paused
        );
        expectRejectedWithoutAccountingChange(
          () => operation.acknowledge(first.sequence, first.chunk.length - 1),
          paused
        );

        operation.acknowledge(first.sequence, first.chunk.length);
        pending.shift();
        const afterFirst = addon.__test_outputStats(operationId);
        expect(afterFirst.outstandingChunks).toBe(paused.outstandingChunks - 1);
        expectRejectedWithoutAccountingChange(
          () => operation.acknowledge(first.sequence, first.chunk.length),
          afterFirst
        );

        operation.cancel();
        expect(() => operation.acknowledge(first.sequence, first.chunk.length)).not.toThrow();
        await withTimeout(operation.completion, "invalid acknowledgement cleanup");
      } finally {
        operation.cancel();
        operation.close();
        await withTimeout(operation.completion, "acknowledgement test cleanup");
      }
    });
  }, 30000);

  it("bounds transform output without changing the transform read bridge", async () => {
    await runWithEngine(async (handle) => {
      const expected = patternedBytes();
      const received: Buffer[] = [];
      const pending: PendingChunk[] = [];
      let offset = 0;
      let operation!: NativeStreamingOperation;
      let operationId!: number;
      let completionSettled = false;

      operation = addon.runScriptTransformEngine(
        handle,
        PASSTHROUGH_SCRIPT,
        "{}",
        "payload",
        "application/octet-stream",
        null,
        (bufSize) => {
          if (offset >= expected.length) return null;
          const chunk = expected.subarray(offset, Math.min(offset + bufSize, expected.length));
          offset += chunk.length;
          return chunk;
        },
        (chunk, sequence) => {
          received.push(chunk);
          pending.push({ chunk, sequence });
          if (received.length === 1) {
            const first = pending.shift()!;
            operation.acknowledge(first.sequence, first.chunk.length);
          }
        }
      );
      operationId = addon.__test_outputOperationId(operation);
      operation.completion.finally(() => { completionSettled = true; }).catch(() => {});

      try {
        const paused = await waitForStats(
          (stats) => stats.paused,
          "transform producer pause",
          operationId
        );
        expect(completionSettled).toBe(false);
        expectBounded(paused);

        const raw = await drainAfterPause(
          operation,
          operationId,
          pending,
          received,
          () => completionSettled
        );
        expect(offset).toBe(expected.length);
        expect(Buffer.concat(received)).toEqual(expected);
        expect(JSON.parse(raw)).toMatchObject({
          success: true,
          mimeType: "application/octet-stream",
        });
      } finally {
        operation.cancel();
        operation.close();
        await withTimeout(operation.completion, "transform test cleanup");
      }
    });
  }, 30000);

  it("settles and closes when an iterator returns while the producer is paused", async () => {
    const dw = new DataWeave();
    dw.initialize();
    const stream = dw.runStreaming(PASSTHROUGH_SCRIPT, {
      payload: {
        content: patternedBytes(),
        mimeType: "application/octet-stream",
      },
    });

    try {
      const first = await withTimeout(stream.next(), "first iterator chunk");
      expect(first.done).toBe(false);
      await waitForStats((stats) => stats.paused, "iterator producer pause");

      await withTimeout(stream.return(undefined), "paused iterator return");
      await withTimeout(dw.cleanup(), "cleanup after paused iterator return");
      await waitForStats(
        (stats) => stats.cancelled && stats.done && stats.outstandingBytes === 0,
        "cancelled iterator settlement"
      );
      expect(addon.__test_outputStats().liveFlows).toBe(0);

      await expect(stream.next()).resolves.toEqual({ done: true, value: undefined });
      dw.initialize();
      expect(dw.run("output application/json --- 6 * 7").getString()).toBe("42");
    } finally {
      await withTimeout(dw.cleanup(), "iterator test final cleanup");
    }
  }, 30000);

  it("DataWeave.cleanup cancels a paused producer and deterministically settles its iterator", async () => {
    const dw = new DataWeave();
    dw.initialize();
    const anchor = new DataWeave();
    anchor.initialize();
    const stream = dw.runStreaming(PASSTHROUGH_SCRIPT, {
      payload: {
        content: patternedBytes(),
        mimeType: "application/octet-stream",
      },
    });

    try {
      const first = await withTimeout(stream.next(), "first cleanup-test chunk");
      expect(first.done).toBe(false);
      await waitForStats((stats) => stats.paused, "cleanup-test producer pause");

      await withTimeout(dw.cleanup(), "DataWeave.cleanup while producer paused");
      await waitForStats(
        (stats) => stats.cancelled && stats.done && stats.outstandingChunks === 0,
        "cleanup cancellation settlement"
      );
      expect(addon.__test_outputStats().liveFlows).toBe(0);
      await expect(stream.next()).resolves.toEqual({ done: true, value: undefined });

      dw.initialize();
      expect(dw.run("output application/json --- 20 + 22").getString()).toBe("42");
    } finally {
      await withTimeout(dw.cleanup(), "cleanup test final cleanup");
      await withTimeout(anchor.cleanup(), "cleanup test anchor cleanup");
    }
  }, 30000);

  it.each([
    {
      name: "streaming",
      start: (handle: number) => addon.runScriptStreamingEngine(
        handle,
        "%dw 2.0\noutput application/json\n---\n[]",
        "{}",
        () => {}
      ),
    },
    {
      name: "transform",
      start: (handle: number) => addon.runScriptTransformEngine(
        handle,
        "%dw 2.0\noutput application/json\n---\npayload",
        "{}",
        "payload",
        "application/json",
        "UTF-8",
        () => null,
        () => {}
      ),
    },
  ])("settles $name completion when the normal terminal resolution fails", async ({ start }) => {
    await runWithEngine(async (handle) => {
      addon.__test_failNextOutputSettlement();
      const operation = start(handle);

      try {
        await expect(
          withTimeout(operation.completion, "fault-injected terminal settlement")
        ).resolves.toContain("Failed to settle native output completion");
      } finally {
        operation.cancel();
        operation.close();
      }
    });
  });
});
