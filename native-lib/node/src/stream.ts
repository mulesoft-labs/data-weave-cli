import { parseStreamingResult } from "./result";
import type { NativeStreamingOperation } from "./ffi";
import type { StreamingResult } from "./types";

/**
 * Starts a native streaming call, wiring its chunk callback to `chunkCb` and
 * returning its controller once native admission succeeds.
 */
export type StartStreaming = (chunkCb: (chunk: Buffer) => void) => NativeStreamingOperation;

type ErrorState = { readonly hasError: false } | { readonly hasError: true; readonly error: unknown };

const NO_ERROR: ErrorState = { hasError: false };

/**
 * Bridges a native push-based streaming call into a pull-based async generator.
 *
 * Native async generators serialize `return()` behind an outstanding `next()`.
 * This wrapper intercepts `return()` and `throw()` so they can request native
 * cancellation and wake a parked pull before delegating generator finalization.
 *
 * @param start - Launches the native call and returns its operation controller.
 * @param onStart - Called once after native admission with the managed operation.
 * @param onClose - Called once after the native controller closes successfully.
 * @returns An async generator of output chunks whose return value is terminal metadata.
 */
export function streamFromNative(
  start: StartStreaming,
  onStart?: (operation: NativeStreamingOperation) => void,
  onClose?: (operation: NativeStreamingOperation) => void
): AsyncGenerator<Buffer, StreamingResult, undefined> {
  const chunks: Buffer[] = [];
  const pendingResolves: Array<() => void> = [];
  let operation: NativeStreamingOperation | undefined;
  let nativeSettled = false;
  let nativeSettlementHandled: Promise<void> | undefined;
  let nativeRejected = false;
  let nativeError: unknown;
  let metaRaw: string | null = null;
  let cancellationRequested = false;
  let cancelSucceeded = false;
  let nativeCloseSucceeded = false;
  let closeFinalized = false;
  let registered = false;
  let finalized = false;
  let finalizationError: ErrorState = NO_ERROR;
  let nativeOperation: NativeStreamingOperation | undefined;
  let cancelInProgress = false;

  const wakeAll = () => {
    while (pendingResolves.length > 0) {
      pendingResolves.shift()!();
    }
  };

  const acknowledgeBufferedChunks = () => {
    while (chunks.length > 0) {
      operation!.acknowledge(chunks.shift()!.length);
    }
  };

  const close = () => {
    if (!nativeOperation || closeFinalized) return;
    if (!nativeCloseSucceeded) {
      nativeOperation.close();
      nativeCloseSucceeded = true;
    }
    if (registered) onClose?.(operation!);
    closeFinalized = true;
  };

  const cancel = () => {
    cancellationRequested = true;
    let lifecycleError: ErrorState = NO_ERROR;
    try {
      acknowledgeBufferedChunks();
    } catch (error) {
      lifecycleError = { hasError: true, error };
    } finally {
      wakeAll();
    }

    if (nativeOperation && !nativeSettled && !cancelSucceeded && !cancelInProgress) {
      cancelInProgress = true;
      try {
        nativeOperation.cancel();
        cancelSucceeded = true;
      } catch (error) {
        if (!lifecycleError.hasError) lifecycleError = { hasError: true, error };
      } finally {
        cancelInProgress = false;
      }
    }

    if (nativeOperation && (nativeSettled || cancelSucceeded)) {
      try {
        close();
      } catch (error) {
        if (!lifecycleError.hasError) lifecycleError = { hasError: true, error };
      }
    }

    if (lifecycleError.hasError) throw lifecycleError.error;
  };

  const chunkCb = (chunk: Buffer) => {
    if (finalized || cancellationRequested) {
      try {
        operation?.acknowledge(chunk.length);
      } catch {
        // No consumer remains to observe a late callback failure. Ownership is
        // retained unless close succeeds, so DataWeave cleanup can still retry.
      }
      return;
    }
    chunks.push(chunk);
    pendingResolves.shift()?.();
  };

  const generator = (async function* (): AsyncGenerator<Buffer, StreamingResult, undefined> {
    let primaryError = false;
    try {
      nativeOperation = start(chunkCb);
      const startedOperation = nativeOperation;
      operation = {
        completion: startedOperation.completion,
        acknowledge: (bytes) => startedOperation.acknowledge(bytes),
        cancel,
        close,
      };

      nativeSettlementHandled = operation.completion.then(
        (raw) => {
          metaRaw = raw;
          nativeSettled = true;
          wakeAll();
        },
        (error) => {
          nativeError = error;
          nativeRejected = true;
          nativeSettled = true;
          wakeAll();
        }
      );

      onStart?.(operation);
      registered = true;

      while (true) {
        if (!cancellationRequested && chunks.length > 0) {
          const chunk = chunks.shift()!;
          operation.acknowledge(chunk.length);
          yield chunk;
          continue;
        }
        if (nativeSettled) break;
        if (cancellationRequested) {
          return undefined as unknown as StreamingResult;
        }
        await new Promise<void>((resolve) => { pendingResolves.push(resolve); });
      }

      await nativeSettlementHandled;
      if (nativeRejected) throw nativeError;
      if (cancellationRequested) return undefined as unknown as StreamingResult;
      return parseStreamingResult(metaRaw ?? "");
    } catch (error) {
      primaryError = true;
      if (operation && !registered) {
        try {
          cancel();
        } catch {
          // Preserve the registration failure as primary.
        }
      }
      throw error;
    } finally {
      finalized = true;
      let lifecycleError: ErrorState = NO_ERROR;
      if (operation && registered) {
        if (!nativeSettled && !cancelSucceeded && registered) {
          try {
            cancel();
          } catch (error) {
            lifecycleError = { hasError: true, error };
          }
        }
        if ((nativeSettled || cancelSucceeded) && !closeFinalized) {
          try {
            close();
          } catch (error) {
            if (!lifecycleError.hasError) lifecycleError = { hasError: true, error };
          }
        }
      }
      wakeAll();
      if (!primaryError && lifecycleError.hasError) {
        finalizationError = lifecycleError;
        throw lifecycleError.error;
      }
    }
  })();

  const requestCancellation = (): ErrorState => {
    cancellationRequested = true;
    wakeAll();
    try {
      cancel();
      return NO_ERROR;
    } catch (error) {
      return { hasError: true, error };
    }
  };

  const iterator: AsyncGenerator<Buffer, StreamingResult, undefined> = {
    next(...args: [] | [undefined]) {
      return generator.next(...args);
    },
    return(value) {
      const lifecycleError = requestCancellation();
      return generator.return(value).then(
        (result) => {
          if (lifecycleError.hasError) throw lifecycleError.error;
          if (finalizationError.hasError) throw finalizationError.error;
          return result;
        },
        (error) => {
          // A retry failure from generator finalization cannot replace the
          // lifecycle failure observed by this cancellation request.
          if (lifecycleError.hasError) throw lifecycleError.error;
          throw error;
        }
      );
    },
    throw(error?: unknown) {
      const lifecycleError = requestCancellation();
      return generator.throw(error).then(
        (result) => {
          if (lifecycleError.hasError) throw lifecycleError.error;
          if (finalizationError.hasError) throw finalizationError.error;
          return result;
        },
        (primary) => { throw primary; }
      );
    },
    [Symbol.asyncIterator]() {
      return this;
    },
  };
  return iterator;
}
