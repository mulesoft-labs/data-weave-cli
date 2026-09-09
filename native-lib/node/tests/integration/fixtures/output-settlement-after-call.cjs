"use strict";

const path = require("node:path");

const addonPath = path.resolve(process.cwd(), process.argv[2]);
const libPath = process.argv[3];
const fault = process.argv[4];
const mode = process.argv[5] ?? "streaming";
const clearFault = process.argv[6];
const addon = require(addonPath);

function main() {
  addon.initialize(libPath);
  const handle = addon.createEngine();
  addon.__test_failNextOutputSettlement(fault);
  if (clearFault) addon.__test_failNextOutputExceptionClear(clearFault);
  if (mode === "streaming") {
    addon.runScriptStreamingEngine(
      handle,
      "output application/json\n---\n[]",
      "{}",
      () => {}
    );
  } else if (mode === "transform") {
    addon.runScriptTransformEngine(
      handle,
      "output application/json\n---\npayload",
      "{}",
      "payload",
      "application/json",
      "UTF-8",
      () => null,
      () => {}
    );
  } else {
    throw new Error(`Unknown output mode: ${mode}`);
  }
}

main();
