import { parseStreamingResult } from "./result";
import type { NativeStreamingOperation } from "./ffi";
import type { StreamingResult } from "./types";

/**
 * Starts a native streaming call, wiring its chunk callback to `chunkCb` and
 * returning its controller once native admission succeeds.
 */
export type StartStreaming = (chunkCb: (chunk: Buffer) => void) => NativeStreamingOperation;

/**
 * Bridges a native push-based streaming call into a pull-based async generator.
 *
 * The native side pushes output chunks through the callback while
 * {@link StartStreaming} runs; this generator buffers them and yields in order,
 * parking the consumer when no chunk is ready and waking it on the next push or
 * on completion. Consumer dequeues return native byte credits before yielding
 * each chunk. After all chunks drain, it returns the parsed
 * {@link StreamingResult}.
 *
 * @param start - Launches the native call and returns its operation controller.
 * @param onStart - Called once after native admission with the managed operation.
 * @param onClose - Called once when the managed operation closes.
 * @returns An async generator of output chunks whose return value is the terminal metadata.
 */
export async function* streamFromNative(
  start: StartStreaming,
  onStart?: (operation: NativeStreamingOperation) => void,
  onClose?: (operation: NativeStreamingOperation) => void
): AsyncGenerator<Buffer, StreamingResult, undefined> {
  const chunks: Buffer[] = [];
  const pendingResolves: Array<() => void> = [];
  let operation: NativeStreamingOperation | undefined;
  let nativeSettled = false;
  let finalized = false;
  let cancellationRequested = false;
  let metaRaw: string | null = null;
  let settlementCloseError: unknown;

  const chunkCb = (chunk: Buffer) => {
    if ((finalized || cancellationRequested) && operation) {
      try {
        operation.acknowledge(chunk.length);
      } catch {
        // Late callback credit is best-effort after the consumer has abandoned
        // the stream; cleanup must still be able to cancel and close it.
      }
      return;
    }
    chunks.push(chunk);
    // Resolve one waiting consumer if any
    const resolve = pendingResolves.shift();
    if (resolve) {
      resolve();
    }
  };

  let startError: unknown;
  let startRejected = false;
  const wakeAll = () => {
    while (pendingResolves.length > 0) {
      const resolve = pendingResolves.shift();
      if (resolve) resolve();
    }
  };
  const acknowledgeBufferedChunks = () => {
    while (chunks.length > 0) {
      operation!.acknowledge(chunks.shift()!.length);
    }
  };

  try {
    const nativeOperation = start(chunkCb);
    let cancelCalled = false;
    let closeCalled = false;
    let registered = false;
    operation = {
      completion: nativeOperation.completion,
      acknowledge: (bytes) => nativeOperation.acknowledge(bytes),
      cancel: () => {
        if (cancelCalled) return;
        cancelCalled = true;
        cancellationRequested = true;
        try {
          acknowledgeBufferedChunks();
        } finally {
          wakeAll();
          if (!nativeSettled) nativeOperation.cancel();
          else operation!.close();
        }
      },
      close: () => {
        if (closeCalled) return;
        closeCalled = true;
        try {
          nativeOperation.close();
        } finally {
          if (registered) onClose?.(operation!);
        }
      },
    };

    let completionHandled: Promise<void>;
    try {
      // Handle both settlement branches so rejected native completion cannot
      // become unhandled or leave a parked consumer asleep. Chunks already in
      // the JS queue still drain before the rejection is surfaced.
      completionHandled = operation.completion.then(
        (raw) => {
          metaRaw = raw;
          nativeSettled = true;
          wakeAll();
          if (cancellationRequested) {
            try {
              operation!.close();
            } catch (error) {
              settlementCloseError = error;
            }
          }
        },
        (error) => {
          startError = error;
          startRejected = true;
          nativeSettled = true;
          wakeAll();
          if (cancellationRequested) {
            try {
              operation!.close();
            } catch (closeError) {
              settlementCloseError = closeError;
            }
          }
        }
      );

      onStart?.(operation);
      registered = true;
    } catch (error) {
      operation.cancel();
      await Promise.allSettled([operation.completion]);
      throw error;
    }

    while (true) {
      if (chunks.length > 0) {
        const chunk = chunks.shift()!;
        operation.acknowledge(chunk.length);
        yield chunk;
        continue;
      }
      if (nativeSettled) break;
      await new Promise<void>((resolve) => { pendingResolves.push(resolve); });
    }

    await completionHandled;
    // Track rejection by settlement state, not value: Promise.reject(undefined)
    // is valid and must not be mistaken for a successful empty response.
    if (startRejected) throw startError;
    if (settlementCloseError !== undefined) throw settlementCloseError;
    return parseStreamingResult(metaRaw ?? "");
  } finally {
    finalized = true;
    if (operation) {
      try {
        acknowledgeBufferedChunks();
      } finally {
        try {
          if (!nativeSettled) operation.cancel();
        } finally {
          operation.close();
        }
      }
    }
    wakeAll();
  }
}
