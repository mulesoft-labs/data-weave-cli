"use strict";

const path = require("node:path");

const addonPath = path.resolve(process.cwd(), process.argv[2]);
const libPath = process.argv[3];
const addon = require(addonPath);
const INPUT = Buffer.from("detach poison transform");

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function count(name) {
  const value = addon[name]();
  assert(typeof value === "bigint", `${name} must return a lossless bigint`);
  return value;
}

function successfulResult(raw) {
  const result = JSON.parse(raw);
  assert(result.success === true, `expected successful result, got ${raw}`);
  return result;
}

function immediate() {
  return new Promise((resolve) => setImmediate(resolve));
}

async function waitFor(predicate, label) {
  const deadline = Date.now() + 10_000;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error(`${label} timed out`);
    await immediate();
  }
}

function withTimeout(promise, label) {
  let timer;
  return Promise.race([
    promise,
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`${label} timed out`)), 10_000);
    }),
  ]).finally(() => clearTimeout(timer));
}

async function main() {
  addon.initialize(libPath);
  const handle = addon.createEngine();
  const creationBefore = count("__test_isolateCreationCount");
  const teardownBefore = count("__test_teardownCallCount");
  const abandonedBefore = count("__test_abandonedIsolateCount");
  const forcedBefore = count("__test_forcedDetachFailureCount");

  addon.__test_forceDetachFailureOnce("transform-worker");
  addon.__test_holdNextAsyncOp();
  const chunks = [];
  let read = false;
  let operation;
  operation = addon.runScriptTransformEngine(
    handle,
    "output application/octet-stream deferred=true --- payload",
    "{}",
    "payload",
    "application/octet-stream",
    null,
    () => {
      if (read) return null;
      read = true;
      return INPUT;
    },
    (chunk, sequence) => {
      chunks.push(chunk);
      operation.acknowledge(sequence, chunk.length);
    }
  );

  await waitFor(() => addon.__test_asyncOpHeld(), "transform admission barrier");
  addon.destroyEngine(handle);
  const cleanup = addon.cleanup();
  addon.__test_releaseAsyncOp();

  const [raw] = await withTimeout(
    Promise.all([operation.completion, cleanup]),
    "transform completion and poisoned cleanup"
  );
  successfulResult(raw);
  const transformResult = Buffer.concat(chunks).toString("utf8");
  assert(transformResult === INPUT.toString("utf8"), `unexpected transform output: ${transformResult}`);
  assert(count("__test_forcedDetachFailureCount") === forcedBefore + 1n, "transform detach was not forced once");
  assert(count("__test_teardownCallCount") === teardownBefore, "poisoned transform cleanup invoked teardown");
  assert(count("__test_abandonedIsolateCount") === abandonedBefore + 1n, "poisoned transform cleanup did not abandon once");

  addon.initialize(libPath);
  assert(count("__test_isolateCreationCount") === creationBefore + 1n, "transform recovery did not create a fresh isolate");
  const freshHandle = addon.createEngine();
  const freshResultRaw = addon.runScriptEngine(
    freshHandle,
    "output application/json --- 6 * 7",
    "{}"
  );
  const freshEnvelope = successfulResult(freshResultRaw);
  const freshResult = Buffer.from(freshEnvelope.result, "base64").toString("utf8");
  assert(freshResult === "42", `unexpected fresh result: ${freshResult}`);
  assert(addon.__test_isolatePoisoned() === false, "fresh isolate inherited poison");
  addon.destroyEngine(freshHandle);
  await withTimeout(addon.cleanup(), "fresh transform isolate cleanup");
  assert(count("__test_teardownCallCount") === teardownBefore + 1n, "fresh transform isolate was not torn down");
  assert(count("__test_abandonedIsolateCount") === abandonedBefore + 1n, "fresh cleanup changed abandon count");

  process.stdout.write(`${JSON.stringify({
    transformResult,
    forcedFailures: Number(count("__test_forcedDetachFailureCount") - forcedBefore),
    abandoned: Number(count("__test_abandonedIsolateCount") - abandonedBefore),
    freshResult,
  })}\n`);
}

main().catch((error) => {
  addon.__test_releaseAsyncOp?.();
  process.stderr.write(`${error.stack ?? error}\n`);
  process.exitCode = 99;
});
