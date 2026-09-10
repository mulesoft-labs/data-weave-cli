import { describe, expect, it } from "vitest";
import { spawnSync } from "node:child_process";
import { join } from "node:path";
import { findLibrary } from "../../src/utils";

const ADDON_PATH = join(__dirname, "..", "..", "build", "Release", "dwlib_addon.node");
const LIB_PATH = findLibrary();
const SYNC_FIXTURE = join(__dirname, "fixtures", "detach-poison-sync.cjs");
const TRANSFORM_FIXTURE = join(__dirname, "fixtures", "detach-poison-transform.cjs");
const FATAL_STDERR =
  /Must either be at a safepoint or in native mode|fatal error|SIGSEGV|segmentation fault|SIGABRT|\babort(?:ed|ing)?\b/i;

const DETACH_SITES = [
  "bridge-finalize",
  "stream-worker",
  "transform-worker",
  "create-engine",
  "create-rollback",
  "resolver-create",
  "unknown-destroy",
  "synchronous-run",
] as const;

function runFixture(
  fixture: string,
  args: string[],
  hooks = true
): Record<string, unknown> {
  const env = { ...process.env };
  if (hooks) env.DATAWEAVE_TEST_HOOKS = "1";
  else delete env.DATAWEAVE_TEST_HOOKS;

  const child = spawnSync(
    process.execPath,
    [fixture, ADDON_PATH, LIB_PATH, ...args],
    {
      cwd: __dirname,
      encoding: "utf-8",
      timeout: 30_000,
      env,
    }
  );

  expect(child.error, child.error?.message).toBeUndefined();
  expect(child.signal, child.stderr).toBeNull();
  expect(child.status, child.stderr).not.toBe(99);
  expect(child.status, child.stderr).toBe(0);
  expect(child.stderr, child.stderr).not.toMatch(FATAL_STDERR);
  expect(child.stdout.trim(), child.stderr).not.toBe("");
  return JSON.parse(child.stdout.trim()) as Record<string, unknown>;
}

describe("detach failure poisoning and recovery", () => {
  it("preserves a completed synchronous result, fails later admission closed, and recovers with a fresh isolate", () => {
    expect(runFixture(SYNC_FIXTURE, ["recovery"])).toMatchObject({
      firstResult: "42",
      rejectedAdmissions: 6,
      forcedFailures: 1,
      abandoned: 1,
      freshResult: "42",
    });
  });

  it("prevents an old-generation handle from destroying a fresh engine with the same native handle", () => {
    expect(runFixture(SYNC_FIXTURE, ["stale-handle"])).toMatchObject({
      handlesDiffer: true,
      freshResult: "42",
    });
  });

  it("prevents an old-generation env finalizer from destroying a fresh engine", () => {
    expect(runFixture(SYNC_FIXTURE, ["stale-finalizer"])).toMatchObject({
      handlesDiffer: true,
      freshResult: "42",
    });
  });

  it("poisons a transform worker during an active final cleanup without hanging or tearing down the old isolate", () => {
    expect(runFixture(TRANSFORM_FIXTURE, [])).toMatchObject({
      transformResult: "detach poison transform",
      forcedFailures: 1,
      abandoned: 1,
      freshResult: "42",
    });
  });

  it.each(DETACH_SITES)("accepts the exact detach site name %s", (site) => {
    expect(runFixture(SYNC_FIXTURE, ["validate-site", site])).toEqual({ site });
  });

  it.each([
    "bridge-finalize",
    "stream-worker",
    "create-engine",
    "resolver-create",
    "unknown-destroy",
  ] as const)("forces and recovers from the ordinary detach site %s", (site) => {
    expect(runFixture(SYNC_FIXTURE, ["exercise-site", site])).toMatchObject({
      site,
      forcedFailures: 1,
      abandoned: 1,
      freshResult: "42",
    });
  });

  it("forces a synchronous-run detach only after the successful result is copied", () => {
    expect(runFixture(SYNC_FIXTURE, ["exercise-site", "synchronous-run"])).toMatchObject({
      site: "synchronous-run",
      forcedFailures: 1,
      abandoned: 1,
      triggeringResult: "42",
      freshResult: "42",
    });
  });

  it("executes and recovers from the create-rollback detach site", () => {
    expect(runFixture(SYNC_FIXTURE, ["exercise-create-rollback"])).toMatchObject({
      forcedFailures: 1,
      abandoned: 1,
      freshResult: "42",
    });
  });

  it("rejects invalid sites and refuses to silently replace an armed failure", () => {
    expect(runFixture(SYNC_FIXTURE, ["invalid-arguments"])).toEqual({
      invalidArguments: 6,
      duplicateArmRejected: true,
    });
  });

  it("consumes only the selected site once while poison remains set", () => {
    expect(runFixture(SYNC_FIXTURE, ["one-shot-site"])).toEqual({
      forcedFailures: 1,
      poisonPersisted: true,
    });
  });

  it("does not export detach-poison hooks without DATAWEAVE_TEST_HOOKS", () => {
    expect(runFixture(SYNC_FIXTURE, ["hooks-absent"], false)).toEqual({
      hooksAbsent: true,
    });
  });
});
