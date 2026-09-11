import { describe, it, expect, afterAll } from "vitest";
import { DataWeave, cleanup } from '../../src/dataweave';
import { DataWeaveError } from '../../src/errors';
import { modulesFromMap } from '../../src/resolver';

// Every test below constructs its own explicit DataWeave instance (rather
// than the module-level singleton) so each can configure its own resolver.
// `cleanup()` above only releases the *singleton* (`globalInstance`), which
// nothing in this file ever creates -- so without this tracking, every
// explicit instance's native library reference (and its own engine handle,
// see addon.c's create_engine/destroy_engine) would leak for the lifetime of
// the test process. Track every instance created in this file and release
// them all in afterAll.
const instances: DataWeave[] = [];
function trackedDataWeave(...args: ConstructorParameters<typeof DataWeave>): DataWeave {
  const dw = new DataWeave(...args);
  instances.push(dw);
  return dw;
}

afterAll(async () => {
  for (const dw of instances) {
    await dw.cleanup();
  }
  await cleanup();
});

describe('DataWeave with resolver', () => {
  it('resolves imported module from map', () => {
    const dw = trackedDataWeave({
      resolveModule: modulesFromMap({
        'org/test/lib.dwl': '%dw 2.0\nfun greet(n: String) = "Hello " ++ n',
      }),
    });
    dw.initialize();

    const result = dw.run(`
      %dw 2.0
      import org::test::lib
      output application/json
      ---
      lib::greet("World")
    `);

    expect(result.success).toBe(true);
    expect(JSON.parse(result.getString()!)).toBe("Hello World");
  });

  it('works without resolver (backward compatible)', () => {
    const dw = trackedDataWeave();  // No resolver
    dw.initialize();

    const result = dw.run(`
      %dw 2.0
      output application/json
      ---
      { message: "no imports" }
    `);

    expect(result.success).toBe(true);
    expect(JSON.parse(result.getString()!)).toEqual({ message: "no imports" });
  });

  it('throws when module not found', () => {
    const dw = trackedDataWeave({
      resolveModule: modulesFromMap({ 'a.dwl': '...' }),
    });
    dw.initialize();

    expect(() => dw.run(`
      %dw 2.0
      import missing::mod
      output application/json
      ---
      {}
    `, undefined, { raiseOnError: true })).toThrow();
  });

  it('built-in modules still resolve with resolver', () => {
    const dw = trackedDataWeave({
      resolveModule: modulesFromMap({ 'custom.dwl': '...' }),
    });
    dw.initialize();

    // Built-in modules should still work (CompositeResolver: ClassLoader + Callback)
    const result = dw.run(`
      %dw 2.0
      import dw::core::Strings
      output application/json
      ---
      Strings::capitalize("hello")
    `);

    expect(result.success).toBe(true);
    expect(JSON.parse(result.getString()!)).toBe("Hello");
  });

  // Regression test for the cross-thread resolver hazard: each DataWeave
  // instance now owns its own native engine (see engine_bridge_t in addon.c),
  // but a resolver-backed engine's runStreaming()/runTransform() still
  // executes the native call on a background uv_thread (see addon.c's
  // streaming_thread_fn/transform_thread_fn), not the JS thread that created
  // the engine and its resolver bridge. resolve_module_callback detects that
  // thread-identity mismatch and fails closed (reports "not found" instead of
  // calling back into JS) rather than making an unsafe cross-thread napi
  // call, so the script fails cleanly with a compile error and the process
  // survives.
  it('runStreaming fails cleanly for a custom module on its own resolver-backed engine', async () => {
    const dw = trackedDataWeave({
      resolveModule: modulesFromMap({
        'org/test/resolverGuardStreamed.dwl': '%dw 2.0\nfun greet(n: String) = "Hello " ++ n',
      }),
    });
    dw.initialize();

    // Stream a script that imports a non-built-in module. This engine's
    // composite resolver (ClassLoader + Callback) misses in the ClassLoader
    // half (not a built-in) and falls through to the Callback half, invoking
    // resolve_module_callback from runStreaming's background thread.
    const chunks: Buffer[] = [];
    const gen = dw.runStreaming(`
      %dw 2.0
      import org::test::resolverGuardStreamed
      output application/json
      ---
      resolverGuardStreamed::greet("Streaming")
    `);
    let result = await gen.next();
    while (!result.done) {
      chunks.push(result.value);
      result = await gen.next();
    }
    const metadata = result.value;

    // Fails cleanly (built-ins-only fallback), rather than crashing the process.
    expect(metadata.success).toBe(false);
    expect(metadata.error).toBeTruthy();
    expect(chunks.length).toBe(0);
  });

  // resolve_module_callback in addon.c catches a JS exception thrown by the
  // user-supplied resolver (napi_call_function returning napi_pending_exception),
  // clears it via napi_get_and_clear_last_exception, logs a content-free
  // diagnostic (see the DATAWEAVE_RESOLVER_DEBUG gating), and reports "not
  // found" back to the DataWeave runtime -- rather than letting the pending
  // exception leak into a later napi call or crash the process. This is a
  // synchronous run() on the JS thread that created the bridge (the "owner"
  // thread check in resolve_module_callback passes), so the callback is
  // actually invoked, unlike the streaming/transform cross-thread case above.
  it('throwing resolver makes run() fail cleanly instead of crashing the process', () => {
    const dw = trackedDataWeave({
      resolveModule: () => {
        throw new Error('resolver blew up');
      },
    });
    dw.initialize();

    const result = dw.run(`
      %dw 2.0
      import org::test::throwingResolverLib
      output application/json
      ---
      {}
    `);

    // The test itself completing (no uncaught exception / segfault) is the
    // crash-check. We also assert an error message is surfaced -- but not its
    // wording, which is an internal detail.
    expect(result.success).toBe(false);
    expect(result.error).toBeTruthy();
  });

  // Regression test for a resolver-backed engine's initialize -> cleanup ->
  // initialize cycle. Unlike the resolver-less reinit test in
  // edge-cases.test.ts, this exercises createEngineWithResolver's bridge
  // (engine_bridge_t) lifecycle: cleanup() destroys the bridge and its engine
  // handle, and the following initialize() must build a brand new bridge
  // (new napi_ref on the resolver, new owner-thread record) that resolves
  // custom modules again, not a stale or dangling one.
  it('resolver-backed instance resolves a custom module again after initialize -> cleanup -> initialize', async () => {
    const dw = trackedDataWeave({
      resolveModule: modulesFromMap({
        'org/test/reinitLib.dwl': '%dw 2.0\nfun greet(n: String) = "Hello " ++ n',
      }),
    });
    dw.initialize();
    await dw.cleanup();
    dw.initialize();

    const result = dw.run(`
      %dw 2.0
      import org::test::reinitLib
      output application/json
      ---
      reinitLib::greet("Reinit")
    `);

    expect(result.success).toBe(true);
    expect(JSON.parse(result.getString()!)).toBe("Hello Reinit");
  });

  // Regression test for the F1 use-after-free fix: a resolver-backed engine's
  // engine_bridge_t used to be freed by destroy_engine (called from cleanup())
  // even while a background uv_thread (streaming_thread_fn) was still
  // mid-flight and could call resolve_module_callback with that bridge as
  // ctx -- a use-after-free. The fix adds in-flight accounting under g_mutex:
  // destroy_engine now defers the actual free until the background operation
  // decrements in_flight back to zero in its completion sentinel.
  //
  // To race cleanup() against the in-flight operation deterministically, we
  // start the generator's *first* `.next()` call but do not await it before
  // calling cleanup(). Calling an async generator's .next() runs its body
  // synchronously up to the first suspension point (an `await`); by that
  // point runStreaming's synchronous prefix -- including the native
  // runScriptStreamingEngine call that hands the operation to a libuv
  // worker-pool thread -- has already executed. cleanup() is then called
  // from the JS thread while that native call may already be running
  // concurrently on the worker thread, which is exactly the race the F1 fix
  // guards against. Before that fix this was a real crash/UAF risk; after it,
  // this must complete cleanly (settle, not crash, not hang) regardless of
  // which side of the race wins.
  it('cleanup() racing an in-flight resolver-backed runStreaming() does not crash (F1 regression)', async () => {
    const dw = trackedDataWeave({
      resolveModule: modulesFromMap({
        'org/test/cleanupDuringStream.dwl': '%dw 2.0\nfun greet(n: String) = "Hello " ++ n',
      }),
    });
    dw.initialize();

    const gen = dw.runStreaming(`
      %dw 2.0
      import org::test::cleanupDuringStream
      output application/json
      ---
      cleanupDuringStream::greet("Streaming")
    `);

    // Start the native call without awaiting it, then immediately race
    // cleanup() against it.
    const firstNext = gen.next();
    // Retain the cleanup promise so its rejection cannot escape as an unhandled
    // rejection and so native teardown is actually awaited before the test ends
    // (review #9 #3). It is awaited in the finally below.
    const cleanupPromise = dw.cleanup();

    try {
      // The outcome (a settled chunk, the terminal metadata, or a rejection)
      // doesn't matter -- what matters is that it settles instead of crashing
      // the process or hanging, and that no unhandled rejection escapes this
      // test. We explicitly catch here (rather than asserting a specific
      // resolution) and prove settlement, one way or the other.
      let settled = false;
      try {
        await firstNext;
        settled = true;
      } catch (err) {
        settled = true;
        expect(err).toBeDefined();
      }
      expect(settled).toBe(true);

      // Drain whatever remains so no background callback fires after this test
      // (and this file's process) moves on.
      try {
        let result = await gen.next();
        while (!result.done) {
          result = await gen.next();
        }
      } catch {
        // Draining after a mid-stream cleanup may itself reject; that's fine.
      }
    } finally {
      // Always await the retained cleanup so native teardown finishes before the
      // test returns; a cleanup rejection here surfaces rather than dangling, but
      // it does not mask a primary assertion failure thrown from the try above.
      await cleanupPromise;
    }
  });

  // Deadlock regression: unlike the F1 test above (which races cleanup()
  // against a stream that fails before emitting data), this test uses a
  // script that produces real output with enough volume that the worker
  // thread is genuinely attached and mid-delivery -- blocked in
  // napi_call_threadsafe_function(..., napi_tsfn_blocking) -- when cleanup()
  // drops the last native reference. Before the fix (napi_cleanup's
  // synchronous uv_thread_join), this scenario hung the process; after the
  // fix, cleanup() defers teardown to a waiter thread until this op drains,
  // so both the cleanup() promise and the streaming generator settle.
  it('cleanup() during an active, output-producing runStreaming() does not deadlock', async () => {
    const dw = trackedDataWeave();
    dw.initialize();

    const gen = dw.runStreaming(
      'output application/json --- (1 to 5000) map {id: $, name: "item_" ++ $}'
    );

    // Pin the operation without draining it: exactly one .next() call runs
    // the generator's synchronous prefix (including the native call that
    // hands the op to a background thread) up to its first await.
    const firstNext = gen.next();

    const cleanupPromise = dw.cleanup();

    await expect(
      Promise.race([
        cleanupPromise,
        new Promise((_, reject) => setTimeout(() => reject(new Error('cleanup() timed out')), 10000)),
      ])
    ).resolves.toBeUndefined();

    // cleanup cancels the iterator; Task 8 defines cancellation as a terminal
    // undefined return rather than native metadata from the abandoned run.
    await firstNext;
    await expect(gen.next()).resolves.toEqual({ done: true, value: undefined });
  }, 15000);

  // Same deadlock regression as above, for runTransform() -- the design doc
  // notes the same problem applies to transform's write_tsfn delivery path.
  it('cleanup() during an active, output-producing runTransform() does not deadlock', async () => {
    const dw = trackedDataWeave();
    dw.initialize();

    const parts: Buffer[] = [Buffer.from("[")];
    for (let i = 1; i <= 2000; i++) {
      if (i > 1) parts.push(Buffer.from(","));
      parts.push(Buffer.from(`{"id":${i}}`));
    }
    parts.push(Buffer.from("]"));
    const inputData = [Buffer.concat(parts)];

    const gen = dw.runTransform(
      "output application/json\n---\npayload map $",
      inputData,
      { mimeType: "application/json" }
    );

    const firstNext = gen.next();

    // Wait until the native transform is admitted, not merely one microtask.
    // Input preparation has multiple async boundaries and cleanup before the
    // admission boundary correctly invalidates the operation generation.
    const firstChunk = await firstNext;
    expect(firstChunk.done).toBe(false);

    const cleanupPromise = dw.cleanup();

    await expect(
      Promise.race([
        cleanupPromise,
        new Promise((_, reject) => setTimeout(() => reject(new Error('cleanup() timed out')), 10000)),
      ])
    ).resolves.toBeUndefined();

    await expect(gen.next()).resolves.toEqual({ done: true, value: undefined });
  }, 15000);

  // Fast-path regression guard: cleanup() called once a stream has already
  // fully drained (g_active_ops back to 0 by the time the last reference is
  // released) must still resolve via the original, unchanged inline fast
  // path -- confirming the new deferred-teardown branch didn't silently
  // become the only path through napi_cleanup.
  it('cleanup() after a stream has already fully drained resolves via the fast path', async () => {
    const dw = trackedDataWeave();
    dw.initialize();

    const gen = dw.runStreaming('output application/json --- {a: 1}');
    let result = await gen.next();
    while (!result.done) {
      result = await gen.next();
    }
    expect(result.value.success).toBe(true);

    await expect(dw.cleanup()).resolves.toBeUndefined();
  });

  // Idempotency / re-entrant cleanup: two cleanup() calls that both arrive
  // while a stream is active must both resolve off the same underlying
  // teardown -- without spawning a second waiter thread, throwing, or
  // decrementing g_ref_count below 0.
  it('two concurrent cleanup() calls during an active stream both resolve cleanly', async () => {
    const dw = trackedDataWeave();
    dw.initialize();

    const gen = dw.runStreaming(
      'output application/json --- (1 to 3000) map {id: $}'
    );
    const firstNext = gen.next();

    const [r1, r2] = await Promise.all([
      Promise.race([
        dw.cleanup(),
        new Promise((_, reject) => setTimeout(() => reject(new Error('first cleanup() timed out')), 10000)),
      ]),
      Promise.race([
        dw.cleanup(),
        new Promise((_, reject) => setTimeout(() => reject(new Error('second cleanup() timed out')), 10000)),
      ]),
    ]);
    expect(r1).toBeUndefined();
    expect(r2).toBeUndefined();

    let result = await firstNext;
    while (!result.done) {
      result = await gen.next();
    }
  }, 15000);

  // Re-initialize during pending teardown: starting a stream, calling
  // cleanup() without awaiting it, then immediately calling initialize()
  // again must block (at the native layer, inside napi_initialize) until the
  // pending teardown finishes, rather than racing a second
  // graal_create_isolate against an isolate that is still tearing down. The
  // instance must be fully usable afterward.
  it('initialize() called during a pending teardown waits for it and then works', async () => {
    const dw = trackedDataWeave();
    dw.initialize();

    const gen = dw.runStreaming(
      'output application/json --- (1 to 3000) map {id: $}'
    );
    const firstNext = gen.next();

    // Deliberately not awaited -- this is the pending-teardown state under test.
    const cleanupPromise = dw.cleanup();

    // dw.cleanup() already set dw's own initialized flag false only after its
    // internal await resolves; to exercise the *native* pending-teardown path
    // independent of this specific instance's TS-level guard, drive a second,
    // fresh instance's initialize() concurrently -- it shares the same
    // process-global isolate/g_ref_count.
    const dw2 = trackedDataWeave();
    const secondInitDone = new Promise<void>((resolve) => {
      dw2.initialize();
      resolve();
    });

    await Promise.race([
      Promise.all([cleanupPromise, secondInitDone]),
      new Promise((_, reject) => setTimeout(() => reject(new Error('initialize()-during-teardown timed out')), 10000)),
    ]);

    expect(dw2.run("6 * 7").getString()).toBe("42");

    let result = await firstNext;
    while (!result.done) {
      result = await gen.next();
    }
  }, 15000);

  // Node-layer contract (F4-adjacent): once cleanup() has torn an instance
  // down, run() must be rejected by dataweave.ts's own ensureInitialized()
  // guard -- a DataWeaveError with a "not initialized" message -- rather than
  // reaching the native addon at all with a handle that no longer refers to a
  // live engine. This is the TS-level half of the destroyed/unknown-handle
  // contract; the native "Unknown engine handle" string is the deeper
  // contract the addon enforces if it were ever called with a stale handle,
  // which this guard prevents from happening via the public API.
  it('run() after cleanup() throws a DataWeaveError via the TS-level ensureInitialized guard', async () => {
    const dw = trackedDataWeave({
      resolveModule: modulesFromMap({
        'org/test/destroyedHandleLib.dwl': '...',
      }),
    });
    dw.initialize();
    await dw.cleanup();

    expect(() => dw.run('1 + 1')).toThrow(DataWeaveError);
    expect(() => dw.run('1 + 1')).toThrow(/DataWeave runtime not initialized/);
  });
});
