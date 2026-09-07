import { resolveAddonPath } from "./addon-path";
import { DataWeaveError } from "./errors";
import type { ModuleResolver } from "./resolver";

export interface NativeStreamingOperation {
  readonly completion: Promise<string>;
  acknowledge(bytes: number): void;
  cancel(): void;
  close(): void;
}

interface NativeAddon {
  initialize(libPath: string): void;
  createEngine(): number;
  createEngineWithResolver(resolver: ModuleResolver): number;
  destroyEngine(handle: number): void;
  runScriptEngine(handle: number, script: string, inputsJson: string): string;
  runScriptStreamingEngine(
    handle: number,
    script: string,
    inputsJson: string,
    chunkCb: (chunk: Buffer) => void
  ): NativeStreamingOperation;
  runScriptTransformEngine(
    handle: number,
    script: string,
    inputsJson: string,
    inputName: string,
    inputMimeType: string,
    inputCharset: string | null,
    readCb: (bufSize: number) => Buffer | null,
    writeCb: (chunk: Buffer) => void
  ): NativeStreamingOperation;
  cleanup(): Promise<void>;
}

let addon: NativeAddon | null = null;

function callNative<T>(invoke: () => T): T {
  try {
    return invoke();
  } catch (error) {
    if (
      error &&
      typeof error === "object" &&
      "code" in error &&
      error.code === "ERR_DATAWEAVE_CALLBACK_REENTRANCY"
    ) {
      const message = "message" in error ? String(error.message) : String(error);
      throw new DataWeaveError(message);
    }
    throw error;
  }
}

function getAddon(addonPath?: string): NativeAddon {
  if (!addon) {
    addon = require(addonPath ?? resolveAddonPath()) as NativeAddon;
  }
  return addon;
}

export function initialize(libPath: string, addonPath?: string): void {
  callNative(() => getAddon(addonPath).initialize(libPath));
}

export function createEngine(): number {
  return callNative(() => getAddon().createEngine());
}

export function createEngineWithResolver(resolver: ModuleResolver): number {
  return callNative(() => getAddon().createEngineWithResolver(resolver));
}

export function destroyEngine(handle: number): void {
  callNative(() => getAddon().destroyEngine(handle));
}

export function runScriptEngine(handle: number, script: string, inputsJson: string): string {
  return callNative(() => getAddon().runScriptEngine(handle, script, inputsJson));
}

export function runScriptStreamingEngine(
  handle: number,
  script: string,
  inputsJson: string,
  chunkCb: (chunk: Buffer) => void
): NativeStreamingOperation {
  return callNative(() =>
    getAddon().runScriptStreamingEngine(handle, script, inputsJson, chunkCb)
  );
}

export function runScriptTransformEngine(
  handle: number,
  script: string,
  inputsJson: string,
  inputName: string,
  inputMimeType: string,
  inputCharset: string | null,
  readCb: (bufSize: number) => Buffer | null,
  writeCb: (chunk: Buffer) => void
): NativeStreamingOperation {
  return callNative(() =>
    getAddon().runScriptTransformEngine(
      handle,
      script,
      inputsJson,
      inputName,
      inputMimeType,
      inputCharset,
      readCb,
      writeCb
    )
  );
}

export function cleanup(): Promise<void> {
  return callNative(() => getAddon().cleanup());
}
