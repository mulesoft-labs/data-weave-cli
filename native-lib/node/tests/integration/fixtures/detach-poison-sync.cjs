"use strict";

const path = require("node:path");
const { once } = require("node:events");
const { Worker } = require("node:worker_threads");

const addonPath = path.resolve(process.cwd(), process.argv[2]);
const libPath = process.argv[3];
const mode = process.argv[4];
const addon = require(addonPath);

const POISONED_MESSAGE =
  "DataWeave isolate is unavailable after a thread detach failure; clean up and initialize again.";
const DETACH_HOOKS = [
  "__test_forceDetachFailureOnce",
  "__test_isolatePoisoned",
  "__test_isolateCreationCount",
  "__test_teardownCallCount",
  "__test_abandonedIsolateCount",
  "__test_forcedDetachFailureCount",
];
const TEST_HOOKS = [
  ...DETACH_HOOKS,
  "__test_failNextEngineRecordAllocation",
  "__test_setNextEngineHandle",
  "__test_setIsolateGeneration",
  "__test_isolateGeneration",
];
const MAX_SAFE_HANDLE = Number.MAX_SAFE_INTEGER;
const UINT64_MAX = 18_446_744_073_709_551_615n;

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function expectThrow(invoke, message) {
  try {
    invoke();
  } catch (error) {
    assert(error instanceof Error, "expected an Error");
    assert(error.message === message, `expected ${JSON.stringify(message)}, got ${JSON.stringify(error.message)}`);
    return;
  }
  throw new Error(`expected synchronous throw: ${message}`);
}

function count(name) {
  const value = addon[name]();
  assert(typeof value === "bigint", `${name} must return a lossless bigint`);
  return value;
}

function successfulResult(raw) {
  const result = JSON.parse(raw);
  assert(result.success === true, `expected successful result, got ${raw}`);
  return Buffer.from(result.result, "base64").toString("utf8");
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

async function recovery() {
  for (const hook of DETACH_HOOKS) {
    assert(typeof addon[hook] === "function", `${hook} is undefined`);
  }

  addon.initialize(libPath);
  const firstHandle = addon.createEngine();
  const creationBefore = count("__test_isolateCreationCount");
  const teardownBefore = count("__test_teardownCallCount");
  const abandonedBefore = count("__test_abandonedIsolateCount");
  const forcedBefore = count("__test_forcedDetachFailureCount");

  addon.__test_forceDetachFailureOnce("synchronous-run");
  const otherHandle = addon.createEngine();
  assert(addon.__test_isolatePoisoned() === false, "a different detach site consumed the arm");
  assert(count("__test_forcedDetachFailureCount") === forcedBefore, "forced count changed at a different site");

  const firstResult = successfulResult(
    addon.runScriptEngine(firstHandle, "output application/json --- 6 * 7", "{}")
  );
  assert(firstResult === "42", `unexpected first result: ${firstResult}`);
  assert(addon.__test_isolatePoisoned() === true, "the isolate was not poisoned");
  assert(count("__test_forcedDetachFailureCount") === forcedBefore + 1n, "forced failure was not one-shot");

  let rejectedAdmissions = 0;
  const liveFlowsBefore = addon.__test_outputStats().liveFlows;
  const reject = (invoke) => {
    expectThrow(invoke, POISONED_MESSAGE);
    rejectedAdmissions++;
  };
  reject(() => addon.runScriptEngine(firstHandle, "output application/json --- 1", "{}"));
  reject(() => addon.createEngine());
  reject(() => addon.createEngineWithResolver(() => null));
  reject(() => addon.runScriptStreamingEngine(firstHandle, "output application/json --- []", "{}", () => {}));
  reject(() => addon.runScriptTransformEngine(
    firstHandle,
    "output application/json --- payload",
    "{}",
    "payload",
    "application/json",
    "UTF-8",
    () => null,
    () => {}
  ));
  reject(() => addon.initialize(libPath));
  assert(addon.__test_outputStats().liveFlows === liveFlowsBefore, "rejected async work allocated an output flow");

  // Engine destruction remains permitted after poison. Final cleanup abandons
  // the generation without attempting isolate teardown.
  addon.destroyEngine(otherHandle);
  addon.destroyEngine(firstHandle);
  await withTimeout(addon.cleanup(), "poisoned isolate cleanup");
  assert(count("__test_teardownCallCount") === teardownBefore, "poisoned cleanup invoked teardown");
  assert(count("__test_abandonedIsolateCount") === abandonedBefore + 1n, "poisoned cleanup did not abandon once");

  addon.initialize(libPath);
  assert(count("__test_isolateCreationCount") === creationBefore + 1n, "recovery did not create one fresh isolate");
  const freshHandle = addon.createEngine();
  const freshResult = successfulResult(
    addon.runScriptEngine(freshHandle, "output application/json --- 6 * 7", "{}")
  );
  assert(freshResult === "42", `unexpected fresh result: ${freshResult}`);
  assert(addon.__test_isolatePoisoned() === false, "fresh isolate inherited poison");
  addon.destroyEngine(freshHandle);
  await withTimeout(addon.cleanup(), "fresh isolate cleanup");
  assert(count("__test_teardownCallCount") === teardownBefore + 1n, "fresh isolate was not torn down once");
  assert(count("__test_abandonedIsolateCount") === abandonedBefore + 1n, "fresh cleanup changed abandon count");

  return {
    firstResult,
    rejectedAdmissions,
    forcedFailures: Number(count("__test_forcedDetachFailureCount") - forcedBefore),
    abandoned: Number(count("__test_abandonedIsolateCount") - abandonedBefore),
    freshResult,
  };
}

async function staleHandle() {
  addon.initialize(libPath);
  const oldHandle = addon.createEngine();
  addon.__test_forceDetachFailureOnce("synchronous-run");
  successfulResult(addon.runScriptEngine(oldHandle, "output application/json --- 6 * 7", "{}"));
  await withTimeout(addon.cleanup(), "old isolate cleanup");

  addon.initialize(libPath);
  const freshHandle = addon.createEngine();
  addon.destroyEngine(oldHandle);
  const freshResult = successfulResult(
    addon.runScriptEngine(freshHandle, "output application/json --- 6 * 7", "{}")
  );
  addon.destroyEngine(freshHandle);
  await withTimeout(addon.cleanup(), "fresh isolate cleanup");
  return { handlesDiffer: oldHandle !== freshHandle, freshResult };
}

async function staleFinalizer() {
  const worker = new Worker(`
    "use strict";
    const { parentPort, workerData } = require("node:worker_threads");
    const addon = require(workerData.addonPath);
    function successful(raw) {
      const result = JSON.parse(raw);
      if (result.success !== true) throw new Error(\`worker run failed: \${raw}\`);
    }
    (async () => {
      addon.initialize(workerData.libPath);
      const handle = addon.createEngine();
      addon.__test_forceDetachFailureOnce("synchronous-run");
      successful(addon.runScriptEngine(handle, "output application/json --- 6 * 7", "{}"));
      await addon.cleanup();
      parentPort.postMessage({ handle });
      parentPort.once("message", () => parentPort.close());
    })().catch((error) => {
      parentPort.postMessage({ error: error.stack || String(error) });
    });
  `, { eval: true, workerData: { addonPath, libPath } });

  const [message] = await once(worker, "message");
  assert(message.error === undefined, message.error ?? "worker failed");
  addon.initialize(libPath);
  const freshHandle = addon.createEngine();
  const exit = once(worker, "exit");
  worker.postMessage("exit");
  const [exitCode] = await exit;
  assert(exitCode === 0, `worker exited with ${exitCode}`);

  const freshResult = successfulResult(
    addon.runScriptEngine(freshHandle, "output application/json --- 6 * 7", "{}")
  );
  addon.destroyEngine(freshHandle);
  await withTimeout(addon.cleanup(), "fresh isolate cleanup after stale finalizer");
  return { handlesDiffer: message.handle !== freshHandle, freshResult };
}

function validateSite(site) {
  assert(typeof addon.__test_forceDetachFailureOnce === "function", "detach failure hook is undefined");
  addon.__test_forceDetachFailureOnce(site);
  return { site };
}

function invalidArguments() {
  let invalidArguments = 0;
  for (const value of [
    undefined,
    null,
    42,
    "not-a-detach-site",
    "synchronous-run\0unknown",
    "x".repeat(64),
  ]) {
    try {
      if (value === undefined) addon.__test_forceDetachFailureOnce();
      else addon.__test_forceDetachFailureOnce(value);
    } catch (error) {
      assert(error instanceof Error, "invalid detach site did not throw Error");
      invalidArguments++;
    }
  }
  addon.__test_forceDetachFailureOnce("synchronous-run");
  let duplicateArmRejected = false;
  try {
    addon.__test_forceDetachFailureOnce("create-engine");
  } catch (error) {
    duplicateArmRejected = error instanceof Error;
  }
  assert(duplicateArmRejected, "a second arm silently replaced the first");
  return { invalidArguments, duplicateArmRejected };
}

async function exerciseCreateRollback() {
  assert(
    typeof addon.__test_failNextEngineRecordAllocation === "function",
    "engine record allocation hook is undefined"
  );
  addon.initialize(libPath);
  const forcedBefore = count("__test_forcedDetachFailureCount");
  const abandonedBefore = count("__test_abandonedIsolateCount");
  addon.__test_forceDetachFailureOnce("create-rollback");
  addon.__test_failNextEngineRecordAllocation();
  expectThrow(() => addon.createEngine(), "Failed to allocate engine record");
  assert(addon.__test_isolatePoisoned() === true, "create rollback did not poison isolate");
  await withTimeout(addon.cleanup(), "create rollback cleanup");

  addon.initialize(libPath);
  const fresh = addon.createEngine();
  const freshResult = successfulResult(
    addon.runScriptEngine(fresh, "output application/json --- 6 * 7", "{}")
  );
  addon.destroyEngine(fresh);
  await withTimeout(addon.cleanup(), "create rollback fresh cleanup");
  return {
    forcedFailures: Number(count("__test_forcedDetachFailureCount") - forcedBefore),
    abandoned: Number(count("__test_abandonedIsolateCount") - abandonedBefore),
    freshResult,
  };
}

async function handleExhaustion() {
  assert(typeof addon.__test_setNextEngineHandle === "function", "handle setter is undefined");
  addon.initialize(libPath);
  addon.__test_setNextEngineHandle(MAX_SAFE_HANDLE - 1);
  const firstHandle = addon.createEngine();
  const secondHandle = addon.createEngineWithResolver(() => null);
  assert(firstHandle === MAX_SAFE_HANDLE - 1, `unexpected first handle: ${firstHandle}`);
  assert(secondHandle === MAX_SAFE_HANDLE, `unexpected second handle: ${secondHandle}`);
  assert(Number.isSafeInteger(firstHandle), "first handle is not a safe integer");
  assert(Number.isSafeInteger(secondHandle), "second handle is not a safe integer");
  const firstResult = successfulResult(
    addon.runScriptEngine(firstHandle, "output application/json --- 6 * 7", "{}")
  );
  const secondResult = successfulResult(
    addon.runScriptEngine(secondHandle, "output application/json --- 6 * 7", "{}")
  );
  addon.destroyEngine(firstHandle);
  addon.destroyEngine(secondHandle);

  const forcedBefore = count("__test_forcedDetachFailureCount");
  const abandonedBefore = count("__test_abandonedIsolateCount");
  addon.__test_forceDetachFailureOnce("create-rollback");
  let exhaustionRejected = false;
  try {
    addon.createEngine();
  } catch (error) {
    exhaustionRejected = error instanceof Error && error.message === "Engine handle space exhausted";
  }
  assert(exhaustionRejected, "handle exhaustion was not rejected");
  const forcedRollbackDetach = Number(count("__test_forcedDetachFailureCount") - forcedBefore);
  const isolatePoisoned = addon.__test_isolatePoisoned();
  await withTimeout(addon.cleanup(), "handle exhaustion cleanup");
  const abandoned = Number(count("__test_abandonedIsolateCount") - abandonedBefore);
  return {
    firstHandle,
    secondHandle,
    firstResult,
    secondResult,
    exhaustionRejected,
    forcedRollbackDetach,
    isolatePoisoned,
    abandoned,
  };
}

async function allocationFaultReset(poison) {
  addon.initialize(libPath);
  addon.__test_failNextEngineRecordAllocation();
  if (poison) {
    const handle = addon.createEngineWithResolver(() => null);
    addon.__test_forceDetachFailureOnce("synchronous-run");
    successfulResult(addon.runScriptEngine(handle, "output application/json --- 6 * 7", "{}"));
  }
  await withTimeout(addon.cleanup(), poison ? "poisoned fault reset" : "normal fault reset");

  addon.initialize(libPath);
  let freshCreateFailed = false;
  let fresh;
  try {
    fresh = addon.createEngine();
  } catch (error) {
    freshCreateFailed = error instanceof Error && error.message === "Failed to allocate engine record";
    if (!freshCreateFailed) throw error;
  }
  assert(!freshCreateFailed, "fresh generation consumed the stale allocation fault");
  const freshResult = successfulResult(
    addon.runScriptEngine(fresh, "output application/json --- 6 * 7", "{}")
  );
  addon.destroyEngine(fresh);
  await withTimeout(addon.cleanup(), "fresh allocation fault reset cleanup");
  return { freshResult, freshCreateFailed };
}

async function generationExhaustion() {
  assert(typeof addon.__test_setIsolateGeneration === "function", "generation setter is undefined");
  assert(typeof addon.__test_isolateGeneration === "function", "generation counter is undefined");
  addon.initialize(libPath);
  const creationBefore = count("__test_isolateCreationCount");
  const teardownBefore = count("__test_teardownCallCount");
  addon.__test_setIsolateGeneration(UINT64_MAX);
  await withTimeout(addon.cleanup(), "generation max cleanup");

  let exhaustionRejected = false;
  try {
    addon.initialize(libPath);
  } catch (error) {
    exhaustionRejected = error instanceof Error && error.message === "DataWeave isolate generation space exhausted";
  }
  assert(exhaustionRejected, "generation exhaustion was not rejected");
  return {
    exhaustionRejected,
    creationDelta: Number(count("__test_isolateCreationCount") - creationBefore),
    teardownDelta: Number(count("__test_teardownCallCount") - teardownBefore),
    generation: addon.__test_isolateGeneration().toString(),
  };
}

async function identityHookValidation() {
  let fractionalHandleRejected = false;
  try {
    addon.__test_setNextEngineHandle(1.5);
  } catch (error) {
    fractionalHandleRejected = error instanceof Error;
  }
  let nanHandleRejected = false;
  try {
    addon.__test_setNextEngineHandle(Number.NaN);
  } catch (error) {
    nanHandleRejected = error instanceof Error;
  }
  let armBeforeInitializeRejected = false;
  try {
    addon.__test_failNextEngineRecordAllocation();
  } catch (error) {
    armBeforeInitializeRejected = error instanceof Error;
  }
  addon.initialize(libPath);
  addon.__test_failNextEngineRecordAllocation();
  let duplicateArmRejected = false;
  try {
    addon.__test_failNextEngineRecordAllocation();
  } catch (error) {
    duplicateArmRejected = error instanceof Error;
  }
  expectThrow(() => addon.createEngine(), "Failed to allocate engine record");

  const handle = addon.createEngine();
  let handleMutationWithBridgeRejected = false;
  try {
    addon.__test_setNextEngineHandle(MAX_SAFE_HANDLE - 1);
  } catch (error) {
    handleMutationWithBridgeRejected = error instanceof Error;
  }
  let generationMutationWithBridgeRejected = false;
  try {
    addon.__test_setIsolateGeneration(UINT64_MAX);
  } catch (error) {
    generationMutationWithBridgeRejected = error instanceof Error;
  }
  addon.__test_forceDetachFailureOnce("synchronous-run");
  successfulResult(addon.runScriptEngine(handle, "output application/json --- 6 * 7", "{}"));
  let armAfterPoisonRejected = false;
  try {
    addon.__test_failNextEngineRecordAllocation();
  } catch (error) {
    armAfterPoisonRejected = error instanceof Error;
  }
  await withTimeout(addon.cleanup(), "identity hook validation cleanup");
  return {
    fractionalHandleRejected,
    nanHandleRejected,
    armBeforeInitializeRejected,
    duplicateArmRejected,
    handleMutationWithBridgeRejected,
    generationMutationWithBridgeRejected,
    armAfterPoisonRejected,
  };
}

async function handleExhaustionRollbackStrand() {
  addon.initialize(libPath);
  addon.__test_setNextEngineHandle(MAX_SAFE_HANDLE);
  const finalHandle = addon.createEngine();
  addon.destroyEngine(finalHandle);

  const strandedBefore = addon.__test_strandedCount();
  addon.__test_forceStrandOnce();
  let exhaustionRejected = false;
  try {
    addon.createEngineWithResolver(() => null);
  } catch (error) {
    exhaustionRejected = error instanceof Error && error.message === "Engine handle space exhausted";
  }
  assert(exhaustionRejected, "handle exhaustion was not rejected");
  const strandedDelta = addon.__test_strandedCount() - strandedBefore;
  await withTimeout(addon.cleanup(), "handle exhaustion rollback strand cleanup");
  return { exhaustionRejected, strandedDelta };
}

async function oneShotSite() {
  addon.initialize(libPath);
  const first = addon.createEngine();
  const second = addon.createEngine();
  const forcedBefore = count("__test_forcedDetachFailureCount");
  addon.__test_forceDetachFailureOnce("bridge-finalize");
  addon.destroyEngine(first);
  assert(addon.__test_isolatePoisoned() === true, "selected bridge detach did not poison");
  addon.destroyEngine(second);
  const forcedFailures = Number(count("__test_forcedDetachFailureCount") - forcedBefore);
  const poisonPersisted = addon.__test_isolatePoisoned();
  assert(forcedFailures === 1, "the same detach site consumed more than once");
  assert(poisonPersisted === true, "a later successful detach cleared poison early");
  await withTimeout(addon.cleanup(), "one-shot isolate cleanup");
  return { forcedFailures, poisonPersisted };
}

async function exerciseSite(site) {
  addon.initialize(libPath);
  const handle = addon.createEngine();
  const forcedBefore = count("__test_forcedDetachFailureCount");
  const abandonedBefore = count("__test_abandonedIsolateCount");
  addon.__test_forceDetachFailureOnce(site);
  let triggeringResult;

  if (site === "bridge-finalize") {
    addon.destroyEngine(handle);
  } else if (site === "stream-worker") {
    const operation = addon.runScriptStreamingEngine(
      handle,
      "output application/json --- []",
      "{}",
      (_chunk, sequence) => operation.acknowledge(sequence, _chunk.length)
    );
    const result = JSON.parse(await withTimeout(operation.completion, "stream detach completion"));
    assert(result.success === true, `stream detach operation failed: ${JSON.stringify(result)}`);
    addon.destroyEngine(handle);
  } else if (site === "create-engine") {
    const created = addon.createEngine();
    addon.destroyEngine(created);
    addon.destroyEngine(handle);
  } else if (site === "resolver-create") {
    const created = addon.createEngineWithResolver(() => null);
    addon.destroyEngine(created);
    addon.destroyEngine(handle);
  } else if (site === "unknown-destroy") {
    addon.destroyEngine(handle + 1_000_000);
    addon.destroyEngine(handle);
  } else if (site === "synchronous-run") {
    triggeringResult = successfulResult(
      addon.runScriptEngine(handle, "output application/json --- 6 * 7", "{}")
    );
    addon.destroyEngine(handle);
  } else {
    throw new Error(`exercise-site does not support ${site}`);
  }

  assert(addon.__test_isolatePoisoned() === true, `${site} did not poison isolate`);
  await withTimeout(addon.cleanup(), `${site} cleanup`);
  addon.initialize(libPath);
  const fresh = addon.createEngine();
  const freshResult = successfulResult(
    addon.runScriptEngine(fresh, "output application/json --- 6 * 7", "{}")
  );
  addon.destroyEngine(fresh);
  await withTimeout(addon.cleanup(), `${site} fresh cleanup`);
  return {
    site,
    forcedFailures: Number(count("__test_forcedDetachFailureCount") - forcedBefore),
    abandoned: Number(count("__test_abandonedIsolateCount") - abandonedBefore),
    triggeringResult,
    freshResult,
  };
}

function hooksAbsent() {
  return { hooksAbsent: TEST_HOOKS.every((hook) => addon[hook] === undefined) };
}

async function main() {
  let result;
  if (mode === "recovery") result = await recovery();
  else if (mode === "stale-handle") result = await staleHandle();
  else if (mode === "stale-finalizer") result = await staleFinalizer();
  else if (mode === "validate-site") result = validateSite(process.argv[5]);
  else if (mode === "invalid-arguments") result = invalidArguments();
  else if (mode === "one-shot-site") result = await oneShotSite();
  else if (mode === "exercise-site") result = await exerciseSite(process.argv[5]);
  else if (mode === "exercise-create-rollback") result = await exerciseCreateRollback();
  else if (mode === "handle-exhaustion") result = await handleExhaustion();
  else if (mode === "allocation-fault-normal-reset") result = await allocationFaultReset(false);
  else if (mode === "allocation-fault-poison-reset") result = await allocationFaultReset(true);
  else if (mode === "generation-exhaustion") result = await generationExhaustion();
  else if (mode === "identity-hook-validation") result = await identityHookValidation();
  else if (mode === "handle-exhaustion-rollback-strand") result = await handleExhaustionRollbackStrand();
  else if (mode === "hooks-absent") result = hooksAbsent();
  else throw new Error(`unknown fixture mode: ${mode}`);
  process.stdout.write(`${JSON.stringify(result)}\n`);
}

main().catch((error) => {
  process.stderr.write(`${error.stack ?? error}\n`);
  process.exitCode = 99;
});
