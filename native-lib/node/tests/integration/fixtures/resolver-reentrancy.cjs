const path = require("node:path");

const ROOT = path.join(__dirname, "..", "..", "..");
const MODULE_SOURCE = "%dw 2.0\nfun answer() = 42";
const OUTER_SCRIPT = [
  "%dw 2.0",
  "import org::test::reentrant",
  "output application/json",
  "---",
  "reentrant::answer()",
].join("\n");

async function runFacade() {
  const { DataWeave } = require(path.join(ROOT, "dist", "index.js"));
  const inner = new DataWeave();
  let nestedErrorName = null;
  const outer = new DataWeave({
    resolveModule: () => {
      try {
        inner.run("21 * 2");
      } catch (error) {
        nestedErrorName = error && error.name;
      }
      return MODULE_SOURCE;
    },
  });

  try {
    inner.initialize();
    outer.initialize();
    const result = outer.run(OUTER_SCRIPT);
    console.log(JSON.stringify({ nestedErrorName, outerResult: result.getString() }));
  } finally {
    try {
      await outer.cleanup();
    } finally {
      await inner.cleanup();
    }
  }
}

async function runRaw() {
  const addon = require(path.join(ROOT, "build", "Release", "dwlib_addon.node"));
  const { findLibrary } = require(path.join(ROOT, "dist", "utils.js"));
  let innerHandle = null;
  let outerHandle = null;
  let nestedErrorCode = null;

  addon.initialize(findLibrary());
  try {
    innerHandle = addon.createEngine();
    outerHandle = addon.createEngineWithResolver(() => {
      try {
        addon.runScriptEngine(innerHandle, "21 * 2", "{}");
      } catch (error) {
        nestedErrorCode = error && error.code;
      }
      return MODULE_SOURCE;
    });
    const raw = addon.runScriptEngine(outerHandle, OUTER_SCRIPT, "{}");
    const result = JSON.parse(raw);
    console.log(JSON.stringify({
      nestedErrorCode,
      outerResult: Buffer.from(result.result, "base64").toString("utf-8"),
    }));
  } finally {
    try {
      if (outerHandle !== null) addon.destroyEngine(outerHandle);
    } finally {
      try {
        if (innerHandle !== null) addon.destroyEngine(innerHandle);
      } finally {
        await addon.cleanup();
      }
    }
  }
}

const mode = process.argv[2];
const run = mode === "facade" ? runFacade : mode === "raw" ? runRaw : null;
if (run === null) {
  console.error(`unknown mode: ${mode}`);
  process.exit(2);
}

run().catch((error) => {
  console.error(error && error.stack ? error.stack : error);
  process.exitCode = 99;
});
