"use strict";

const path = require("node:path");

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

  // A poisoned generation is abandoned as a unit. destroyEngine is safe but
  // intentionally performs no further Graal attachment on it.
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

function validateSite(site) {
  assert(typeof addon.__test_forceDetachFailureOnce === "function", "detach failure hook is undefined");
  addon.__test_forceDetachFailureOnce(site);
  return { site };
}

function invalidArguments() {
  let invalidArguments = 0;
  for (const value of [undefined, null, 42, "not-a-detach-site"]) {
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
  return { hooksAbsent: DETACH_HOOKS.every((hook) => addon[hook] === undefined) };
}

async function main() {
  let result;
  if (mode === "recovery") result = await recovery();
  else if (mode === "validate-site") result = validateSite(process.argv[5]);
  else if (mode === "invalid-arguments") result = invalidArguments();
  else if (mode === "one-shot-site") result = await oneShotSite();
  else if (mode === "exercise-site") result = await exerciseSite(process.argv[5]);
  else if (mode === "hooks-absent") result = hooksAbsent();
  else throw new Error(`unknown fixture mode: ${mode}`);
  process.stdout.write(`${JSON.stringify(result)}\n`);
}

main().catch((error) => {
  process.stderr.write(`${error.stack ?? error}\n`);
  process.exitCode = 99;
});
