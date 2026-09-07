import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const FIXTURE = join(__dirname, "fixtures", "resolver-reentrancy.cjs");
const DIST_ENTRY = join(__dirname, "..", "..", "dist", "index.js");
const ADDON_PATH = join(__dirname, "..", "..", "build", "Release", "dwlib_addon.node");
const FATAL_GRAAL_ERROR = /Fatal error|Must either be at a safepoint or in native mode/i;

function runFixture(mode: "facade" | "raw") {
  const child = spawnSync(process.execPath, [FIXTURE, mode], {
    encoding: "utf-8",
    timeout: 30_000,
  });

  expect(child.error, child.error?.message).toBeUndefined();
  expect(child.signal, child.stderr).toBeNull();
  expect(child.status, child.stderr).toBe(0);
  expect(child.stderr).not.toMatch(FATAL_GRAAL_ERROR);

  return JSON.parse(child.stdout.trim()) as Record<string, unknown>;
}

describe("resolver callback reentrancy guard", () => {
  it("maps nested public DataWeave calls to DataWeaveError and preserves the outer run", () => {
    expect(existsSync(DIST_ENTRY), `built entry missing at ${DIST_ENTRY} - run \`npm run build:ts\``).toBe(true);

    expect(runFixture("facade")).toEqual({
      nestedErrorName: "DataWeaveError",
      outerResult: "42",
    });
  });

  it("exposes a stable raw-addon error code for nested native admission", () => {
    expect(existsSync(ADDON_PATH), `native addon missing at ${ADDON_PATH} - run \`npm run build:addon\``).toBe(true);

    expect(runFixture("raw")).toEqual({
      nestedErrorCode: "ERR_DATAWEAVE_CALLBACK_REENTRANCY",
      outerResult: "42",
    });
  });
});
