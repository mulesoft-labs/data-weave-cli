import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // Groups are built out incrementally, so a lane with no test files yet
    // (e.g. tck) must not fail the run.
    passWithNoTests: true,
    reporters: ["default", "./tests/tck/reporter.ts"],
    // Three test groups. Run all with `vitest run`, or one lane with
    // `vitest run --project unit` (etc.). Coverage merges across whichever ran.
    //   - unit:        pure TS logic, no native library (dwlib) required.
    //   - integration: exercises the real dwlib via the native addon.
    //   - tck:         DataWeave runtime conformance suite against dwlib.
    projects: [
      {
        test: {
          name: "unit",
          include: ["tests/unit/**/*.test.ts"],
          testTimeout: 10000,
        },
      },
      {
        test: {
          name: "integration",
          include: ["tests/integration/**/*.test.ts"],
          testTimeout: 30000,
          // Opt the integration lane into the addon's test-only entrypoints
          // (__test_forceStrandOnce / __test_strandedCount /
          // __test_resolverRefDeleteCount and detach-poison fault/counter hooks,
          // including __test_forcedDetachFailureCount). Set before any
          // integration worker loads the addon, so its Init() getenv() sees it;
          // inert in every other lane and in production. Workers spawned by a
          // test inherit this env, so the addon Init() in a worker sees it too.
          env: { DATAWEAVE_TEST_HOOKS: "1" },
        },
      },
      {
        test: {
          name: "tck",
          include: ["tests/tck/**/*.test.ts"],
          testTimeout: 30000,
        },
      },
    ],
    coverage: {
      provider: "v8",
      include: ["src/**/*.ts"],
      // ffi.ts is a thin require() wrapper over the native addon; nothing to cover.
      exclude: ["src/ffi.ts"],
      reporter: ["text", "lcov", "html"],
    },
  },
});
