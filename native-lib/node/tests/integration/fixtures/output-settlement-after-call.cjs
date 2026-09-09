"use strict";

const path = require("node:path");

const addonPath = path.resolve(process.cwd(), process.argv[2]);
const libPath = process.argv[3];
const fault = process.argv[4];
const addon = require(addonPath);

function main() {
  addon.initialize(libPath);
  const handle = addon.createEngine();
  addon.__test_failNextOutputSettlement(fault);
  addon.runScriptStreamingEngine(
    handle,
    "output application/json\n---\n[]",
    "{}",
    () => {}
  );
}

main();
