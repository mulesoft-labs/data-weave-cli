"use strict";

const path = require("node:path");

const addonPath = path.resolve(process.cwd(), process.argv[2]);
const libPath = process.argv[3];
const addon = require(addonPath);

async function timeout(promise, label) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(`${label} timed out`)), 10000);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

const immediate = () => new Promise((resolve) => setImmediate(resolve));

async function main() {
  if (typeof global.gc !== "function") throw new Error("global.gc is unavailable");
  addon.initialize(libPath);
  const handle = addon.createEngine();

  const completion = (() => {
    const payload = Buffer.alloc(2 * 1024 * 1024 + 32771, 65).toString("base64");
    const operation = addon.runScriptStreamingEngine(
      handle,
      "output application/octet-stream deferred=true\n---\npayload",
      JSON.stringify({
        payload: { content: payload, mimeType: "application/octet-stream" },
      }),
      () => {}
    );
    return operation.completion;
  })();

  const deadline = Date.now() + 10000;
  while (addon.__test_outputStats().liveFlows !== 0) {
    global.gc();
    await immediate();
    if (Date.now() >= deadline) throw new Error("controller finalizer timed out");
  }

  await timeout(completion, "finalized controller completion");
  const completionSettled = true;
  addon.destroyEngine(handle);
  await timeout(addon.cleanup(), "finalized controller cleanup");

  addon.initialize(libPath);
  const reuseHandle = addon.createEngine();
  const reused = JSON.parse(addon.runScriptEngine(
    reuseHandle,
    "output application/json --- 6 * 7",
    "{}"
  ));
  addon.destroyEngine(reuseHandle);
  await timeout(addon.cleanup(), "reuse cleanup");

  process.stdout.write(JSON.stringify({
    completionSettled,
    liveFlows: addon.__test_outputStats().liveFlows,
    reusedResult: Buffer.from(reused.result, "base64").toString("utf-8"),
  }));
}

main().catch((error) => {
  process.stderr.write(`${error.stack ?? error}\n`);
  process.exitCode = 1;
});
