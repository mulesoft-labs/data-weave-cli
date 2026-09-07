import { describe, it, expect, vi } from "vitest";
import { streamFromNative } from "../../src/stream";
import type { NativeStreamingOperation } from "../../src/ffi";
import type { StreamingResult } from "../../src/types";

const okMeta = (extra: Record<string, unknown> = {}) =>
  JSON.stringify({ success: true, mimeType: "application/json", charset: "utf-8", binary: false, ...extra });

/** Fully consumes a generator, returning its yielded chunks and terminal return value. */
async function collect(
  gen: AsyncGenerator<Buffer, StreamingResult, undefined>
): Promise<{ chunks: Buffer[]; result: StreamingResult }> {
  const chunks: Buffer[] = [];
  let r = await gen.next();
  while (!r.done) {
    chunks.push(r.value);
    r = await gen.next();
  }
  return { chunks, result: r.value };
}

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

function operation(completion: Promise<string>): NativeStreamingOperation {
  return {
    completion,
    acknowledge: vi.fn(),
    cancel: vi.fn(),
    close: vi.fn(),
  };
}

describe("streamFromNative", () => {
  it("yields chunks pushed before completion, in order", async () => {
    const nativeOperation = operation(Promise.resolve(okMeta()));
    const { chunks, result } = await collect(
      streamFromNative((cb) => {
        cb(Buffer.from("a"));
        cb(Buffer.from("b"));
        cb(Buffer.from("c"));
        return nativeOperation;
      })
    );
    expect(chunks.map((c) => c.toString())).toEqual(["a", "b", "c"]);
    expect(nativeOperation.acknowledge).toHaveBeenNthCalledWith(1, 1);
    expect(nativeOperation.acknowledge).toHaveBeenNthCalledWith(2, 1);
    expect(nativeOperation.acknowledge).toHaveBeenNthCalledWith(3, 1);
    expect(result.success).toBe(true);
    expect(result.mimeType).toBe("application/json");
    expect(nativeOperation.cancel).not.toHaveBeenCalled();
    expect(nativeOperation.close).toHaveBeenCalledTimes(1);
  });

  it("returns success metadata with no chunks", async () => {
    const nativeOperation = operation(Promise.resolve(okMeta()));
    const { chunks, result } = await collect(streamFromNative(() => nativeOperation));
    expect(chunks).toEqual([]);
    expect(result.success).toBe(true);
    expect(nativeOperation.acknowledge).not.toHaveBeenCalled();
    expect(nativeOperation.cancel).not.toHaveBeenCalled();
    expect(nativeOperation.close).toHaveBeenCalledTimes(1);
  });

  it("propagates a synchronous start failure without registering an operation", async () => {
    const start = vi.fn((): NativeStreamingOperation => {
      throw new Error("start boom");
    });
    const onStart = vi.fn();
    const gen = streamFromNative(start, onStart);

    expect(start).not.toHaveBeenCalled();
    await expect(gen.next()).rejects.toThrow("start boom");

    expect(start).toHaveBeenCalledTimes(1);
    expect(onStart).not.toHaveBeenCalled();
    await expect(gen.return(undefined)).resolves.toEqual({ done: true, value: undefined });
    expect(start).toHaveBeenCalledTimes(1);
  });

  it("parks the consumer until a chunk arrives, then wakes it", async () => {
    const meta = deferred<string>();
    const nativeOperation = operation(meta.promise);
    let push!: (chunk: Buffer) => void;
    const gen = streamFromNative((cb) => {
      push = cb;
      return nativeOperation;
    });

    // First pull starts the generator and parks — no chunk is ready yet.
    const pending = gen.next();
    // Producing a chunk should wake the parked consumer.
    push(Buffer.from("late"));
    // Callback enqueue alone does not return native credit.
    expect(nativeOperation.acknowledge).not.toHaveBeenCalled();
    const first = await pending;
    expect(first.done).toBe(false);
    expect(first.value!.toString()).toBe("late");
    expect(nativeOperation.acknowledge).toHaveBeenCalledTimes(1);
    expect(nativeOperation.acknowledge).toHaveBeenCalledWith(4);

    // Completing the stream ends the generator with the parsed metadata.
    meta.resolve(okMeta({ mimeType: "text/plain" }));
    const last = await gen.next();
    expect(last.done).toBe(true);
    expect((last.value as StreamingResult).mimeType).toBe("text/plain");
  });

  it("return cancels and settles a pending first pull", async () => {
    const completion = deferred<string>();
    const nativeOperation = operation(completion.promise);
    nativeOperation.cancel = vi.fn(() => completion.resolve(okMeta()));
    const gen = streamFromNative(() => nativeOperation);

    let firstPullSettled = false;
    const firstPull = gen.next().finally(() => { firstPullSettled = true; });
    await vi.waitFor(() => expect(nativeOperation.cancel).not.toHaveBeenCalled());
    expect(firstPullSettled).toBe(false);

    const returned = gen.return(undefined);
    await Promise.resolve();
    const cancelCallsAfterReturn = vi.mocked(nativeOperation.cancel).mock.calls.length;
    // Let the pre-fix serialized async generator settle so RED does not leave
    // dangling promises after recording that return() could not cancel it.
    if (cancelCallsAfterReturn === 0) completion.resolve(okMeta());

    const [firstResult, returnResult] = await Promise.allSettled([firstPull, returned]);
    expect(cancelCallsAfterReturn).toBe(1);
    expect(firstResult.status).toBe("fulfilled");
    expect(returnResult).toEqual({
      status: "fulfilled",
      value: { done: true, value: undefined },
    });
    expect(nativeOperation.close).toHaveBeenCalledTimes(1);
  });

  it("drains chunks that arrive together with completion", async () => {
    const nativeOperation = operation(Promise.resolve(okMeta()));
    const { chunks, result } = await collect(
      streamFromNative((cb) => {
        // Chunks buffered but not yet consumed when the native call resolves.
        cb(Buffer.from("x"));
        cb(Buffer.from("yy"));
        return nativeOperation;
      })
    );
    expect(chunks.map((c) => c.toString())).toEqual(["x", "yy"]);
    expect(nativeOperation.acknowledge).toHaveBeenNthCalledWith(1, 1);
    expect(nativeOperation.acknowledge).toHaveBeenNthCalledWith(2, 2);
    expect(nativeOperation.acknowledge).toHaveBeenCalledTimes(2);
    expect(result.success).toBe(true);
  });

  it("acknowledges a late callback after completion and close", async () => {
    const nativeOperation = operation(Promise.resolve(okMeta()));
    let push!: (chunk: Buffer) => void;
    const gen = streamFromNative((cb) => {
      push = cb;
      return nativeOperation;
    });

    await collect(gen);
    expect(nativeOperation.close).toHaveBeenCalledTimes(1);

    push(Buffer.from("late"));
    expect(nativeOperation.acknowledge).toHaveBeenCalledTimes(1);
    expect(nativeOperation.acknowledge).toHaveBeenCalledWith(4);
  });

  it("propagates a failure envelope as the terminal result", async () => {
    const nativeOperation = operation(
      Promise.resolve(JSON.stringify({ success: false, error: "stream boom" }))
    );
    const { chunks, result } = await collect(
      streamFromNative(() => nativeOperation)
    );
    expect(chunks).toEqual([]);
    expect(result.success).toBe(false);
    expect(result.error).toBe("stream boom");
  });

  it("treats empty terminal metadata as a failure", async () => {
    const { result } = await collect(streamFromNative(() => operation(Promise.resolve(""))));
    expect(result.success).toBe(false);
    expect(result.error).toBe("Empty response");
  });

  it("rejects a parked consumer when native start() rejects (no hang)", async () => {
    const startGate = deferred<string>();
    const nativeOperation = operation(startGate.promise);
    const gen = streamFromNative(() => nativeOperation);

    // Park a consumer in next() BEFORE the start promise settles: no chunk is
    // ready and done is false, so next() awaits on pendingResolves.
    const pending = gen.next();

    // Now reject the native start. The parked consumer must be woken and see a
    // rejection -- on the pre-fix code done never flips and this hangs forever.
    startGate.reject(new Error("native start boom"));

    await expect(pending).rejects.toThrow("native start boom");
    expect(nativeOperation.cancel).not.toHaveBeenCalled();
    expect(nativeOperation.close).toHaveBeenCalledTimes(1);
  });

  it("drains buffered chunks, then throws, when start() rejects after pushing chunks", async () => {
    const nativeOperation = operation(Promise.reject(new Error("late boom")));
    const gen = streamFromNative((cb) => {
      cb(Buffer.from("x"));
      cb(Buffer.from("yy"));
      return nativeOperation;
    });

    // Buffered chunks yield first...
    const a = await gen.next();
    const b = await gen.next();
    expect([a.value?.toString(), b.value?.toString()]).toEqual(["x", "yy"]);
    expect(nativeOperation.acknowledge).toHaveBeenNthCalledWith(1, 1);
    expect(nativeOperation.acknowledge).toHaveBeenNthCalledWith(2, 2);

    // ...then the drained generator surfaces the start error.
    await expect(gen.next()).rejects.toThrow("late boom");
    expect(nativeOperation.cancel).not.toHaveBeenCalled();
    expect(nativeOperation.close).toHaveBeenCalledTimes(1);
  });

  it("preserves native rejection when close also throws", async () => {
    const nativeOperation = operation(Promise.reject(new Error("native boom")));
    nativeOperation.close = vi.fn(() => { throw new Error("close boom"); });
    const gen = streamFromNative(() => nativeOperation);

    await expect(gen.next()).rejects.toThrow("native boom");
    expect(nativeOperation.close).toHaveBeenCalledTimes(1);
  });

  it("surfaces close failure when completion has no primary error", async () => {
    const nativeOperation = operation(Promise.resolve(okMeta()));
    nativeOperation.close = vi.fn(() => { throw new Error("close boom"); });
    const gen = streamFromNative(() => nativeOperation);

    await expect(gen.next()).rejects.toThrow("close boom");
    expect(nativeOperation.close).toHaveBeenCalledTimes(1);
  });

  it("retries close when close finalization tracking fails", async () => {
    const nativeOperation = operation(Promise.resolve(okMeta()));
    const onClose = vi.fn()
      .mockImplementationOnce(() => { throw new Error("unregister boom"); })
      .mockImplementationOnce(() => {});
    let managedOperation!: NativeStreamingOperation;
    const gen = streamFromNative(
      () => nativeOperation,
      (started) => { managedOperation = started; },
      onClose
    );

    await expect(gen.next()).rejects.toThrow("unregister boom");
    expect(nativeOperation.close).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalledTimes(1);

    managedOperation.close();
    expect(nativeOperation.close).toHaveBeenCalledTimes(2);
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it("propagates a native start() rejection of undefined instead of returning empty metadata", async () => {
    // Promise.reject(undefined) is valid JS. The old value-sentinel
    // (startError !== undefined) treated it as 'never rejected' and returned the
    // normal empty-metadata result; a settlement-state flag must propagate it (review #7 #6).
    const gen = streamFromNative(() => operation(Promise.reject(undefined)));
    await expect(
      (async () => {
        // Drain fully: iterate to completion so the post-drain re-throw runs.
        for await (const _ of gen) { /* no chunks */ }
      })()
    ).rejects.toBeUndefined();
  });

  it("acknowledges abandoned buffered chunks, cancels, and closes on generator return", async () => {
    const completion = deferred<string>();
    const nativeOperation = operation(completion.promise);
    const gen = streamFromNative((cb) => {
      cb(Buffer.from("x"));
      cb(Buffer.from("yy"));
      return nativeOperation;
    });

    const first = await gen.next();
    expect(first.value?.toString()).toBe("x");
    expect(nativeOperation.acknowledge).toHaveBeenCalledWith(1);

    await expect(gen.return(undefined)).resolves.toEqual({ done: true, value: undefined });
    expect(nativeOperation.acknowledge).toHaveBeenNthCalledWith(2, 2);
    expect(nativeOperation.acknowledge).toHaveBeenCalledTimes(2);
    expect(nativeOperation.cancel).toHaveBeenCalledTimes(1);
    expect(nativeOperation.close).toHaveBeenCalledTimes(1);
  });

  it("keeps cancel and close idempotent when cancellation settles native completion", async () => {
    const completion = deferred<string>();
    const nativeOperation = operation(completion.promise);
    nativeOperation.cancel = vi.fn(() => completion.resolve(okMeta()));
    const gen = streamFromNative((cb) => {
      cb(Buffer.from("x"));
      return nativeOperation;
    });

    await gen.next();
    await gen.return(undefined);
    await gen.return(undefined);
    await gen.next();

    expect(nativeOperation.cancel).toHaveBeenCalledTimes(1);
    expect(nativeOperation.close).toHaveBeenCalledTimes(1);
  });

  it("closes once when registration finalization competes with generator finalization", async () => {
    const completion = deferred<string>();
    const nativeOperation = operation(completion.promise);
    const gen = streamFromNative(
      (cb) => {
        cb(Buffer.from("x"));
        return nativeOperation;
      },
      (managedOperation) => managedOperation.cancel()
    );

    const pending = gen.next();
    completion.resolve(okMeta());
    await expect(pending).resolves.toEqual({ done: true, value: undefined });

    expect(nativeOperation.acknowledge).toHaveBeenCalledWith(1);
    expect(nativeOperation.cancel).toHaveBeenCalledTimes(1);
    expect(nativeOperation.close).toHaveBeenCalledTimes(1);
  });

  it("does not cancel when native completion wins before finalization", async () => {
    const completion = deferred<string>();
    const nativeOperation = operation(completion.promise);
    const gen = streamFromNative((cb) => {
      cb(Buffer.from("x"));
      return nativeOperation;
    });

    await gen.next();
    completion.resolve(okMeta());
    await Promise.resolve();
    await gen.return(undefined);

    expect(nativeOperation.cancel).not.toHaveBeenCalled();
    expect(nativeOperation.close).toHaveBeenCalledTimes(1);
  });

  it("retries cancel once without closing an operation whose cancellation failed", async () => {
    const nativeOperation = operation(new Promise<string>(() => {}));
    nativeOperation.cancel = vi.fn(() => {
      throw new Error("cancel boom");
    });
    const gen = streamFromNative((cb) => {
      cb(Buffer.from("x"));
      return nativeOperation;
    });

    await gen.next();
    await expect(gen.return(undefined)).rejects.toThrow("cancel boom");

    expect(nativeOperation.cancel).toHaveBeenCalledTimes(2);
    expect(nativeOperation.close).not.toHaveBeenCalled();
  });

  it("preserves a consumer error when close also throws", async () => {
    const completion = deferred<string>();
    const nativeOperation = operation(completion.promise);
    nativeOperation.cancel = vi.fn(() => completion.resolve(okMeta()));
    nativeOperation.close = vi.fn(() => { throw new Error("close boom"); });
    const gen = streamFromNative((cb) => {
      cb(Buffer.from("x"));
      return nativeOperation;
    });

    await gen.next();
    await expect(gen.throw(new Error("consumer boom"))).rejects.toThrow("consumer boom");
    expect(nativeOperation.cancel).toHaveBeenCalledTimes(1);
    expect(nativeOperation.close).toHaveBeenCalledTimes(2);
  });

  it("still cancels and closes when abandoned-chunk acknowledgement throws", async () => {
    const nativeOperation = operation(new Promise<string>(() => {}));
    vi.mocked(nativeOperation.acknowledge)
      .mockImplementationOnce(() => {})
      .mockImplementationOnce(() => {
        throw new Error("ack boom");
      });
    const gen = streamFromNative((cb) => {
      cb(Buffer.from("x"));
      cb(Buffer.from("y"));
      return nativeOperation;
    });

    await gen.next();
    await expect(gen.return(undefined)).rejects.toThrow("ack boom");

    expect(nativeOperation.cancel).toHaveBeenCalledTimes(1);
    expect(nativeOperation.close).toHaveBeenCalledTimes(1);
  });

  it("still reaches native cancellation when external cancellation credit return throws", async () => {
    const completion = deferred<string>();
    const nativeOperation = operation(completion.promise);
    vi.mocked(nativeOperation.acknowledge).mockImplementation(() => {
      throw new Error("ack boom");
    });
    nativeOperation.cancel = vi.fn(() => completion.resolve(okMeta()));
    let managedOperation!: NativeStreamingOperation;
    let push!: (chunk: Buffer) => void;
    const gen = streamFromNative(
      (cb) => {
        push = cb;
        return nativeOperation;
      },
      (started) => { managedOperation = started; }
    );
    const firstPull = gen.next();
    await vi.waitFor(() => expect(managedOperation).toBeDefined());
    push(Buffer.from("x"));

    expect(() => managedOperation.cancel()).toThrow("ack boom");

    expect(nativeOperation.cancel).toHaveBeenCalledTimes(1);
    await expect(firstPull).resolves.toEqual({ done: true, value: undefined });
    expect(nativeOperation.close).toHaveBeenCalledTimes(1);
  });

  it("cancels and closes when operation registration throws", async () => {
    const completion = deferred<string>();
    const nativeOperation = operation(completion.promise);
    nativeOperation.cancel = vi.fn(() => completion.resolve(okMeta()));
    const gen = streamFromNative(
      () => nativeOperation,
      () => { throw new Error("registration boom"); }
    );

    await expect(gen.next()).rejects.toThrow("registration boom");

    expect(nativeOperation.cancel).toHaveBeenCalledTimes(1);
    expect(nativeOperation.close).toHaveBeenCalledTimes(1);
  });

  it("preserves registration failure when cancel and close also throw", async () => {
    const nativeOperation = operation(new Promise<string>(() => {}));
    nativeOperation.cancel = vi.fn(() => { throw new Error("cancel boom"); });
    nativeOperation.close = vi.fn(() => { throw new Error("close boom"); });
    const gen = streamFromNative(
      () => nativeOperation,
      () => { throw new Error("registration boom"); }
    );

    await expect(gen.next()).rejects.toThrow("registration boom");
    expect(nativeOperation.cancel).toHaveBeenCalledTimes(1);
    expect(nativeOperation.close).not.toHaveBeenCalled();
  });

  it("preserves registration failure when successful cancel is followed by close failure", async () => {
    const completion = deferred<string>();
    const nativeOperation = operation(completion.promise);
    nativeOperation.cancel = vi.fn(() => completion.resolve(okMeta()));
    nativeOperation.close = vi.fn(() => { throw new Error("close boom"); });
    const gen = streamFromNative(
      () => nativeOperation,
      () => { throw new Error("registration boom"); }
    );

    await expect(gen.next()).rejects.toThrow("registration boom");
    expect(nativeOperation.cancel).toHaveBeenCalledTimes(1);
    expect(nativeOperation.close).toHaveBeenCalledTimes(1);
  });

  it("preserves a for-await body error when close also throws", async () => {
    const completion = deferred<string>();
    const nativeOperation = operation(completion.promise);
    nativeOperation.cancel = vi.fn(() => completion.resolve(okMeta()));
    nativeOperation.close = vi.fn(() => { throw new Error("close boom"); });
    const gen = streamFromNative((cb) => {
      cb(Buffer.from("x"));
      return nativeOperation;
    });

    await expect((async () => {
      for await (const _chunk of gen) {
        throw new Error("body boom");
      }
    })()).rejects.toThrow("body boom");
    expect(nativeOperation.cancel).toHaveBeenCalledTimes(1);
    expect(nativeOperation.close).toHaveBeenCalledTimes(2);
  });
});
