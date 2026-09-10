#include <node_api.h>
#include <uv.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <stdint.h>
#ifndef _WIN32
#include <pthread.h>
#endif

// GraalVM function pointer types
typedef int (*graal_create_isolate_fn)(void*, void**, void**);
typedef int (*graal_attach_thread_fn)(void*, void**);
typedef int (*graal_detach_thread_fn)(void*);
typedef int (*graal_tear_down_isolate_fn)(void*);
typedef void (*free_cstring_fn)(void*, void*);
typedef int (*write_callback_t)(void* ctx, const char* buf, int len);
typedef int (*read_callback_t)(void* ctx, char* buf, int buf_size);
typedef char* (*resolve_module_callback_t)(void* thread, void* ctx, const char* module_path);

// Per-engine entrypoint types. Handles are Java long values and MUST be C
// long long everywhere (plain long is 32-bit on Windows LLP64 and would
// truncate a 64-bit handle).
typedef long long (*create_engine_fn)(void*);
typedef long long (*create_engine_with_resolver_fn)(void*, resolve_module_callback_t, void*);
typedef void (*destroy_engine_fn)(void*, long long);
typedef void* (*run_script_engine_fn)(void*, long long, const char*, const char*);
typedef void* (*run_script_callback_engine_fn)(void*, long long, const char*, const char*, write_callback_t, void*);
typedef void* (*run_script_input_output_callback_engine_fn)(void*, long long, const char*, const char*, const char*, const char*, const char*, read_callback_t, write_callback_t, void*);

// Global state
static uv_lib_t g_lib;
static int g_lib_loaded = 0;
static void* g_isolate = NULL;
static void* g_thread = NULL;
static int g_initialized = 0;
static int g_ref_count = 0;
static uv_mutex_t g_mutex;
static uv_mutex_t g_test_output_mutex;
static uv_key_t g_native_callback_depth;
static int g_native_callback_depth_status;
// Guards initialization of the process-global g_mutex. Init() runs once per
// Worker environment that loads this addon, but g_mutex is process-global —
// re-running uv_mutex_init() on an already-initialized mutex from a second
// Worker's Init() call is undefined behavior (and can corrupt the mutex for
// every other thread already relying on it). uv_once ensures the real init
// body runs exactly once per process regardless of how many Workers load us.
static uv_once_t g_mutex_once = UV_ONCE_INIT;

#define CALLBACK_REENTRANCY_CODE "ERR_DATAWEAVE_CALLBACK_REENTRANCY"
#define ISOLATE_POISONED_MESSAGE \
    "DataWeave isolate is unavailable after a thread detach failure; clean up and initialize again."
#define MAX_SAFE_ENGINE_HANDLE 9007199254740991LL

static unsigned native_callback_depth(void) {
    return (unsigned)(uintptr_t)uv_key_get(&g_native_callback_depth);
}

static void native_callback_enter(void) {
    uv_key_set(&g_native_callback_depth,
               (void*)(uintptr_t)(native_callback_depth() + 1));
}

static void native_callback_exit(void) {
    unsigned depth = native_callback_depth();
    uv_key_set(&g_native_callback_depth,
               depth > 1 ? (void*)(uintptr_t)(depth - 1) : NULL);
}

static bool native_callback_active(void) {
    return native_callback_depth() != 0;
}

static napi_value throw_callback_reentrancy(napi_env env) {
    napi_value message;
    napi_value error;
    napi_value code;
    if (napi_create_string_utf8(
            env,
            "DataWeave native methods cannot be called from a native callback",
            NAPI_AUTO_LENGTH,
            &message) != napi_ok ||
        napi_create_error(env, NULL, message, &error) != napi_ok ||
        napi_create_string_utf8(env, CALLBACK_REENTRANCY_CODE, NAPI_AUTO_LENGTH, &code) != napi_ok ||
        napi_set_named_property(env, error, "code", code) != napi_ok ||
        napi_throw(env, error) != napi_ok) {
        napi_throw_error(env, CALLBACK_REENTRANCY_CODE,
                         "DataWeave native methods cannot be called from a native callback");
    }
    return NULL;
}

static graal_create_isolate_fn fn_create_isolate = NULL;
static graal_attach_thread_fn fn_attach_thread = NULL;
static graal_detach_thread_fn fn_detach_thread = NULL;
static graal_tear_down_isolate_fn fn_tear_down_isolate = NULL;
static free_cstring_fn fn_free_cstring = NULL;

// Per-engine entrypoints
static create_engine_fn fn_create_engine = NULL;
static create_engine_with_resolver_fn fn_create_engine_with_resolver = NULL;
static destroy_engine_fn fn_destroy_engine = NULL;
static run_script_engine_fn fn_run_script_engine = NULL;
static run_script_callback_engine_fn fn_run_script_callback_engine = NULL;
static run_script_input_output_callback_engine_fn fn_run_script_input_output_callback_engine = NULL;

// A single run may trigger resolve_module_callback multiple times (one script
// can import several modules). Native copies each returned buffer immediately,
// but the copy is made *after* our callback returns — we don't get a per-call
// "done freeing" signal, only "the whole run finished". So track every buffer
// allocated during one run and free them all once the native call returns.
typedef struct resolver_result_node {
    char* buf;
    struct resolver_result_node* next;
} resolver_result_node_t;

// Per-engine resolver bridge: one node per resolver-backed engine, passed to
// Java as the callback ctx word and forwarded back to resolve_module_callback.
//
// Unlike the streaming/transform entrypoints, runScriptEngine's native call
// executes synchronously on the very thread that invoked it from JS — no
// background uv_thread is spawned. So when native code calls back into
// resolve_module_callback(), we are already on the correct (JS) thread and
// can call directly into V8/napi. Do NOT use napi_threadsafe_function here:
// that pattern queues work for "the" JS thread to pick up and blocks the
// caller on a condition variable until it's serviced — but if the caller
// *is* the JS thread, it can never service its own queued item, causing a
// deadlock (a real bug fixed in this codebase — see Task 11 report).
//
// napi_env/napi_ref are thread-affine; each bridge records the JS thread that
// created it (owner) so resolve_module_callback can detect a mismatch — e.g. a
// streamed/transform custom-module lookup arriving on the background uv_thread
// — and fail closed (return "not found") instead of crashing.
typedef struct engine_bridge {
    // Public handles stay unique for the process lifetime. native_handle is
    // isolate-local and may be reused after an abandoned isolate is replaced.
    long long handle;
    long long native_handle;
    uint64_t isolate_generation;
    napi_env env;
    napi_ref resolver_js;             // NULL => resolver-less engine (no bridge created)
    uv_thread_t owner;                // JS thread that created and must run this engine
    resolver_result_node_t* results;  // buffers to free after each run on this engine
    // Lifecycle accounting, mutated only under g_mutex. A streaming/transform op
    // runs the native call on a background uv_thread that can still call back into
    // resolve_module_callback with this bridge as ctx, so the bridge must outlive
    // every in-flight op. in_flight counts ops that can still dereference this
    // bridge; destroy_pending marks that destroyEngine ran while in_flight > 0 and
    // freeing was deferred to the last op draining on the owner thread.
    int in_flight;
    bool destroy_pending;
    // True when a destroy (via destroyEngine OR the env cleanup hook) was
    // deferred because in_flight > 0; gates the deferred fn_destroy_engine
    // registry removal in bridge_end_op. round-9 (#1) introduced this for the
    // destroyEngine path; round-10 (#1) extended it to bridge_env_cleanup, which
    // must ALSO remove the Java registry entry when its free is deferred --
    // otherwise a resolver-backed engine's ScriptRuntime is left registered with
    // a CallbackWeaveResourceResolver whose ctx points at the freed bridge (UAF).
    bool deferred_registry_remove;
    // True while THIS bridge's napi_add_env_cleanup_hook(bridge_env_cleanup) is
    // registered. The env cleanup hook is the only owner-thread finalizer that may
    // delete resolver_js, so a strand taken on the owner thread (env alive) keeps
    // the hook instead of enqueuing on g_stranded_bridges (whose off-thread drain
    // skips napi_delete_reference and would leak the ref). Mutated only on the
    // owner thread (creation, destroyEngine, bridge_env_cleanup) under the usual
    // owner-thread-serialization contract.
    bool hook_registered;
    // Resolver refs and bridge memory have two independent owners once native
    // registry removal strands: the Java callback ctx and the owner Node env.
    // The env hook drops owner_alive/resolver_js; the native drain drops
    // native_alive. The bridge is freed only after both have released it.
    bool native_alive;
    bool owner_alive;
    // Every resolver bridge owns an unreferenced TSFN. A native-side drain can
    // queue its callback onto the owner env to delete resolver_js legally; the
    // TSFN finalizer drops owner ownership when that env dies instead.
    napi_threadsafe_function owner_cleanup_tsfn;
    bool owner_cleanup_queued;
    bool owner_cleanup_released;
    struct engine_bridge* next;
} engine_bridge_t;
static engine_bridge_t* g_bridges = NULL;  // linked list, guarded by g_mutex
static uint64_t g_isolate_generation = 0;  // incremented for each fresh isolate
static long long g_next_engine_handle = 1; // process-unique public handle

// Round-15 (svacas P1): bridges whose engine destroy was SKIPPED because
// fn_attach_thread failed while the isolate was STILL LIVE. Such a bridge must
// NOT be freed: the Java-side CallbackWeaveResourceResolver still holds it as
// its ctx word, so freeing it would leave a dangling ctx that a later
// run_script_engine -> resolve_module_callback dereferences (UAF). Retain the
// bridge here (linked via its own `next`, which is free once the bridge is
// unlinked from g_bridges -- every bridge_finalize call site unlinks first) so
// its ctx stays valid, and retry the destroy + free at the next drain point
// (top of napi_initialize, or an op-completion path) once the isolate is
// confirmed live and attachable -- or, if the isolate went away, free it then
// (the Java registry died with the isolate). All access under g_mutex.
static engine_bridge_t* g_stranded_bridges = NULL;  // linked list, guarded by g_mutex

// --- Test-only fault injection & introspection (review #12 #3 / #13) ---
//
// These are INERT in production: the __test_* N-API functions are registered
// only when the process sets DATAWEAVE_TEST_HOOKS to a non-empty value (checked
// once in Init on the main JS thread, before any engine exists). g_test_hooks
// gates the two extra branches in the finalize path so a production build never
// takes an extra lock or check. g_test_force_strand_once starts false and can
// only be armed via __test_forceStrandOnce().
//
// The Node strand regression test uses these to deterministically force a SINGLE
// live-isolate strand (an fn_attach_thread failure while the isolate is live)
// inside bridge_finalize_registry and observe the outcome: pre-fix the bridge is
// enqueued on g_stranded_bridges (resolver_js ref leaked / drained undeleted);
// post-fix it is kept by its owner-env cleanup hook and the ref is deleted on the
// owner thread at env teardown (g_test_resolver_ref_deletes counts those deletes).
// g_test_hooks is written once in Init before any reader runs; g_test_force_strand_once
// and the remaining test state are accessed only under g_mutex.
static bool g_test_hooks = false;
static bool g_test_force_strand_once = false;
static long long g_test_resolver_ref_deletes = 0;
static bool g_test_hold_next_async_op = false;
static bool g_test_async_op_held = false;
static bool g_test_release_async_op = false;
static uint64_t g_test_engine_record_allocation_failure_generation = 0;
static uint64_t g_detach_in_progress_generation = 0;
static unsigned g_detach_in_progress = 0;
static bool g_test_hold_next_detach_publication = false;
static bool g_test_detach_publication_held = false;
static bool g_test_release_detach_publication = false;
static unsigned g_detach_publication_waiters = 0;
static uint64_t g_test_live_resolver_refs = 0;

static void bridge_env_cleanup(void* arg);
static void bridge_finalize_free(engine_bridge_t* b, bool env_still_alive);

typedef enum {
  OUTPUT_SETTLEMENT_FAULT_NONE = 0,
  OUTPUT_SETTLEMENT_FAULT_INITIAL_CREATE_GENERIC,
  OUTPUT_SETTLEMENT_FAULT_INITIAL_PENDING_EXCEPTION,
  OUTPUT_SETTLEMENT_FAULT_INITIAL_CALL_GENERIC_AFTER_CALL,
  OUTPUT_SETTLEMENT_FAULT_INITIAL_CALL_PENDING_AFTER_CALL,
  OUTPUT_SETTLEMENT_FAULT_FALLBACK_CALL_GENERIC,
  OUTPUT_SETTLEMENT_FAULT_FALLBACK_PENDING_EXCEPTION,
  OUTPUT_SETTLEMENT_FAULT_FALLBACK_CALL_GENERIC_AFTER_CALL,
} output_settlement_fault_t;

typedef enum {
  OUTPUT_EXCEPTION_CLEAR_FAULT_NONE = 0,
  OUTPUT_EXCEPTION_CLEAR_FAULT_IS_PENDING,
  OUTPUT_EXCEPTION_CLEAR_FAULT_GET_AND_CLEAR,
} output_exception_clear_fault_t;

static output_settlement_fault_t g_test_next_output_settlement_fault =
  OUTPUT_SETTLEMENT_FAULT_NONE;
static output_exception_clear_fault_t g_test_next_output_exception_clear_fault =
  OUTPUT_EXCEPTION_CLEAR_FAULT_NONE;
static bool g_test_hold_next_output_delivery = false;
static bool g_test_output_delivery_held = false;
static bool g_test_release_output_delivery = false;
static uint64_t g_test_held_output_sequence = 0;
static size_t g_test_held_output_bytes = 0;

typedef enum {
  DETACH_SITE_NONE = 0,
  DETACH_SITE_BRIDGE_FINALIZE,
  DETACH_SITE_STREAM_WORKER,
  DETACH_SITE_TRANSFORM_WORKER,
  DETACH_SITE_CREATE_ENGINE,
  DETACH_SITE_CREATE_ROLLBACK,
  DETACH_SITE_RESOLVER_CREATE,
  DETACH_SITE_UNKNOWN_DESTROY,
  DETACH_SITE_SYNCHRONOUS_RUN,
} detach_site_t;

// Process-lifetime test statistics and the one-shot detach fault arm. Every
// read/write is under g_mutex; only the N-API accessors are test-only exports.
static detach_site_t g_test_detach_failure_site = DETACH_SITE_NONE;
static uint64_t g_test_forced_detach_failures = 0;
static uint64_t g_test_isolate_creations = 0;
static uint64_t g_test_teardown_calls = 0;
static uint64_t g_test_abandoned_isolates = 0;

// One record per napi_env that has ever taken an init reference (via
// initialize()). init_refs is that env's net initialize()-minus-cleanup()
// balance. Created lazily on the env's first initialize(); registers exactly
// one env-death hook (env_init_cleanup) at creation; freed by that hook when
// its env dies (after releasing every reference the env still holds). All
// fields mutated ONLY under g_mutex.
//
// INVARIANT: g_ref_count == sum of init_refs over all records in g_env_recs.
// This is the round-13 (#5) fix: the isolate's reference count is owned per
// env, so an abandoned env (or a raw multi-engine-per-initialize() consumer)
// can only release the references IT holds -- it can never drive g_ref_count
// to zero and tear the isolate down while ANOTHER env's engines are live.
typedef struct env_init_rec {
    napi_env env;
    int init_refs;
    struct env_init_rec* next;
} env_init_rec_t;
static env_init_rec_t* g_env_recs = NULL;  // linked list, guarded by g_mutex

// --- Teardown-vs-active-ops coordination (deadlock fix) ---
//
// napi_cleanup's last-release path used to synchronously join a thread that
// calls graal_tear_down_isolate(), which blocks until every GraalVM-attached
// thread detaches. A runStreaming()/runTransform() background worker stays
// attached and can be mid-delivery in napi_call_threadsafe_function(...,
// napi_tsfn_blocking), which needs the JS thread to run its callback -- but
// the JS thread is the one blocked in the join. g_active_ops tracks every
// in-flight streaming/transform op (resolver-backed or not, since teardown
// blocks on ANY attached worker) so napi_cleanup can wait for them to drain
// on a dedicated thread instead of blocking the calling JS thread.
static int g_active_ops = 0;
// Teardown lifecycle, all transitions under g_mutex:
//   NONE         -> no teardown queued or in progress.
//   PENDING_WAIT -> napi_cleanup Case 5 queued a teardown; the waiter thread is
//                   blocked waiting for g_active_ops to drain. The isolate is
//                   STILL LIVE and un-torn-down here, so a fresh initialize()
//                   may ADOPT it (cancel the teardown) instead of blocking the
//                   JS thread -- this is the round-5 deadlock fix.
//   TEARING_DOWN -> the waiter has passed the point of no return and is calling
//                   graal_tear_down_isolate(). Adoption is unsafe; initialize()
//                   must block here, which is deadlock-free because g_active_ops
//                   is already 0 (nothing depends on the JS event loop).
typedef enum {
  TEARDOWN_NONE = 0,
  TEARDOWN_PENDING_WAIT,
  TEARDOWN_TEARING_DOWN,
} teardown_state_t;
// Outcome of an attempted reached-zero isolate teardown, reported by
// cleanup_thread_fn to its synchronous callers and computed inline by
// teardown_waiter_thread_fn. Three-way (review #17 #1) so the callers can
// distinguish the unrecoverable double failure from an ordinary retryable one:
//   CLEANUP_TORN_DOWN     -- isolate destroyed (or nothing to tear down):
//                            clear g_thread/g_isolate/g_initialized/g_ref_count.
//   CLEANUP_RETAIN        -- teardown could not run or failed but the worker
//                            detached cleanly (or the spawn never happened): the
//                            isolate is still live AND reachable -- retain the
//                            globals and arm the retry (g_teardown_needed).
//   CLEANUP_UNRECOVERABLE -- graal_tear_down_isolate AND the follow-up detach
//                            BOTH failed: an exiting worker is stuck attached, so
//                            this isolate can never be torn down. Leak it -- see
//                            abandon_unrecoverable_isolate_locked(). Mirrors
//                            Python native.py's double-failure leak-and-continue.
typedef enum {
  CLEANUP_TORN_DOWN = 0,
  CLEANUP_RETAIN,
  CLEANUP_UNRECOVERABLE,
} cleanup_result_t;
typedef struct cleanup_thread_result {
  cleanup_result_t outcome;
  bool teardown_callable;
} cleanup_thread_result_t;
static teardown_state_t g_teardown_state = TEARDOWN_NONE;
// Set by an adopting initialize() to tell the waiter thread to abort its
// queued teardown and leave the live isolate intact. Read/reset by the waiter.
static bool g_teardown_cancelled = false;
// Round-14 (#2/#3): set under g_mutex when a reached-zero teardown could NOT be
// carried out (teardown-waiter alloc/spawn failed, or cleanup_thread_fn attach
// failed) and the isolate was therefore left LIVE with g_ref_count == 0 and no
// pending teardown. This is a RETRY SIGNAL, not an ownership reference:
// g_ref_count stays 0, so the invariant g_ref_count == sum(init_refs) is
// unaffected. It is cleared when the isolate is (a) actually torn down by a
// retry, or (b) adopted by a later initialize() (a new owner wants it kept).
// While set with g_active_ops > 0, the op-completion drain point retries the
// teardown once ops reach 0 (retry_stranded_teardown_locked).
static bool g_teardown_needed = false;
// review #21 #1: set under g_mutex when an ORDINARY operation's
// graal_detach_thread() returns nonzero, leaving a phantom OS thread attached to
// the live isolate. Unlike g_teardown_needed (a retryable "teardown couldn't run
// yet" signal), this is TERMINAL for this isolate: graal_tear_down_isolate()
// would block forever waiting for the phantom to reach a safepoint, so any later
// teardown must SKIP the attempt and leak-and-continue (CLEANUP_UNRECOVERABLE)
// instead of hanging. The isolate stays fully usable for running more ops
// (GraalVM tolerates many attached threads); only its teardown is doomed. Reset
// to false when a FRESH isolate is created (init_thread_fn) and by
// abandon_unrecoverable_isolate_locked().
static bool g_isolate_poisoned = false;
static uv_cond_t g_teardown_cond;

// Test-only one-shot gate for a real streaming/transform worker. Admission has
// already reserved g_active_ops before the worker reaches this point, so holding
// it here lets tests drive cleanup() into TEARDOWN_PENDING_WAIT without entering
// through a native JS callback. Inert unless DATAWEAVE_TEST_HOOKS is enabled and
// __test_holdNextAsyncOp() armed the gate.
static void test_hold_async_op_if_armed(void) {
  if (!g_test_hooks) return;
  uv_mutex_lock(&g_mutex);
  if (g_test_hold_next_async_op) {
    g_test_hold_next_async_op = false;
    g_test_async_op_held = true;
    uv_cond_broadcast(&g_teardown_cond);
    while (!g_test_release_async_op) {
      uv_cond_wait(&g_teardown_cond, &g_mutex);
    }
    g_test_release_async_op = false;
    g_test_async_op_held = false;
    uv_cond_broadcast(&g_teardown_cond);
  }
  uv_mutex_unlock(&g_mutex);
}

// teardown+detach double failure (review #17 #1): an exiting worker is stuck
// attached to this isolate, so graal_tear_down_isolate can never again get the
// sole-attached, current-OS-thread IsolateThread it requires -- retrying is
// futile and would only attach MORE stuck workers. Abandon the isolate: clear
// the PUBLISHED globals so the next initialize() builds a FRESH isolate (GraalVM
// allows multiple isolates per process; the stuck worker is bound to the OLD,
// leaked isolate and never impedes the new one), do NOT arm g_teardown_needed,
// and leak the old isolate for the process lifetime. Emit a diagnostic so the
// leak is observable. Mirrors Python native.py's leak-and-continue
// (_release_isolate / _retry_pending_teardown_locked). Caller holds g_mutex.
static void abandon_unrecoverable_isolate_locked(void) {
  if (g_test_hooks && g_isolate != NULL) g_test_abandoned_isolates++;
  if (g_test_engine_record_allocation_failure_generation == g_isolate_generation) {
    g_test_engine_record_allocation_failure_generation = 0;
  }
  g_thread = NULL;
  g_isolate = NULL;
  g_initialized = 0;
  g_ref_count = 0;
  g_teardown_needed = false;
  g_isolate_poisoned = false;
  g_detach_in_progress = 0;
  g_detach_in_progress_generation = 0;
  fprintf(stderr,
          "[DataWeave Node addon] GraalVM isolate teardown AND worker detach both "
          "failed; the isolate can never be torn down and is being leaked for the "
          "process lifetime. Binding state was reset so a later initialize() "
          "builds a fresh isolate.\n");
}

// review #21 #1: mark the shared isolate un-tear-down-able because an ordinary
// op's graal_detach_thread() failed (a phantom thread is now stuck attached).
// Emits a one-time stderr diagnostic on the false->true transition. Caller holds
// g_mutex. Teardown paths consult g_isolate_poisoned and leak-and-continue
// (CLEANUP_UNRECOVERABLE) instead of calling graal_tear_down_isolate(), which
// would hang. The triggering op still delivers its (valid) result -- only the
// isolate's eventual teardown is affected.
static void poison_isolate_detach_failure_locked(int detach_rc) {
  if (!g_isolate_poisoned) {
    fprintf(stderr,
            "[DataWeave Node addon] graal_detach_thread failed (code %d) after an "
            "operation; a thread is stuck attached to the isolate, so it can never "
            "be torn down. Teardown will leak the isolate for the process lifetime "
            "instead of hanging, and a later initialize() builds a fresh one.\n",
            detach_rc);
  }
  g_isolate_poisoned = true;
}

// Wait while this generation has an ordinary detach whose result has not yet
// been published. Callers hold g_mutex. Each detach is covered by an active-op
// reservation, so waiting cannot let teardown overtake the detaching thread.
static void wait_for_detach_publication_locked(void) {
  bool counted = false;
  while (g_detach_in_progress > 0 &&
         g_detach_in_progress_generation == g_isolate_generation) {
    if (g_test_hooks && !counted) {
      g_detach_publication_waiters++;
      counted = true;
      uv_cond_broadcast(&g_teardown_cond);
    }
    uv_cond_wait(&g_teardown_cond, &g_mutex);
  }
  if (counted) {
    g_detach_publication_waiters--;
    uv_cond_broadcast(&g_teardown_cond);
  }
}

// Centralized policy for every ordinary operation detach. The real Graal
// detach always runs first. Test injection may turn only a successful detach
// at the selected site into one synthetic failure; real failures never consume
// the arm. Any nonzero result poisons this published isolate before later work
// can be admitted.
static int detach_thread_checked(detach_site_t site, void* thread) {
  uv_mutex_lock(&g_mutex);
  uint64_t generation = g_isolate_generation;
  if (g_detach_in_progress == 0) {
    g_detach_in_progress_generation = generation;
  }
  g_detach_in_progress++;
  uv_mutex_unlock(&g_mutex);

  int detach_rc = fn_detach_thread(thread);
  uv_mutex_lock(&g_mutex);
  bool current_generation = generation == g_isolate_generation;
  if (detach_rc == 0 && current_generation && g_test_hooks &&
      g_test_detach_failure_site == site) {
    g_test_detach_failure_site = DETACH_SITE_NONE;
    g_test_forced_detach_failures++;
    detach_rc = -1;
  }
  if (current_generation && g_test_hooks &&
      g_test_hold_next_detach_publication) {
    g_test_hold_next_detach_publication = false;
    g_test_detach_publication_held = true;
    uv_cond_broadcast(&g_teardown_cond);
    while (!g_test_release_detach_publication) {
      uv_cond_wait(&g_teardown_cond, &g_mutex);
    }
    g_test_release_detach_publication = false;
    g_test_detach_publication_held = false;
  }
  if (detach_rc != 0 && current_generation) {
    poison_isolate_detach_failure_locked(detach_rc);
  }
  if (generation == g_detach_in_progress_generation && g_detach_in_progress > 0) {
    g_detach_in_progress--;
    if (g_detach_in_progress == 0) g_detach_in_progress_generation = 0;
  }
  uv_cond_broadcast(&g_teardown_cond);
  uv_mutex_unlock(&g_mutex);
  return detach_rc;
}

// One node per cleanup() call that arrived while a teardown was already
// pending. napi_env/napi_deferred/napi_threadsafe_function are thread-affine,
// so a second cleanup() call from a different Worker's env cannot have its
// promise resolved via another env's tsfn -- each waiting caller gets its own
// node, created on its own env, resolved by the waiter thread on completion.
typedef struct teardown_waiter {
    napi_env env;
    napi_deferred deferred;
    napi_threadsafe_function tsfn;
    struct teardown_waiter* next;
} teardown_waiter_t;
static teardown_waiter_t* g_teardown_waiters = NULL;  // linked list, guarded by g_mutex

// Returns true if the buffer is now tracked (or there was nothing to track).
// Returns false only when a buffer was supplied but the tracking node could
// not be allocated — in that case the caller owns `buf` again and MUST free
// it itself, since it will never be reachable from b->results.
static bool resolver_results_track(engine_bridge_t* b, char* buf) {
    if (b == NULL || buf == NULL) return true;
    resolver_result_node_t* node = (resolver_result_node_t*)malloc(sizeof(resolver_result_node_t));
    if (node == NULL) return false;  // OOM: caller must free buf to avoid leaking it untracked.
    node->buf = buf;
    node->next = b->results;
    b->results = node;
    return true;
}

static void resolver_results_free_all(engine_bridge_t* b) {
    if (b == NULL) return;
    resolver_result_node_t* node = b->results;
    while (node != NULL) {
        resolver_result_node_t* next = node->next;
        free(node->buf);
        free(node);
        node = next;
    }
    b->results = NULL;
}

static void bridge_release_if_unowned_locked(engine_bridge_t* b) {
    if (b == NULL || b->native_alive || b->owner_alive ||
        b->owner_cleanup_tsfn != NULL) return;
    resolver_results_free_all(b);
    free(b);
}

static void bridge_owner_cleanup_finalize(
    napi_env env, void* finalize_data, void* finalize_hint) {
    (void)env;
    (void)finalize_hint;
    engine_bridge_t* b = (engine_bridge_t*)finalize_data;
    if (b == NULL) return;
    uv_mutex_lock(&g_mutex);
    b->owner_cleanup_tsfn = NULL;
    if (b->owner_alive && b->resolver_js != NULL && g_test_hooks &&
        g_test_live_resolver_refs > 0) {
        g_test_live_resolver_refs--;
    }
    b->owner_alive = false;
    b->resolver_js = NULL;
    bridge_release_if_unowned_locked(b);
    uv_mutex_unlock(&g_mutex);
}

static void call_js_bridge_owner_cleanup(
    napi_env env, napi_value js_callback, void* context, void* data) {
    (void)js_callback;
    (void)data;
    engine_bridge_t* b = (engine_bridge_t*)context;
    if (b == NULL || env == NULL) return;
    if (b->hook_registered) {
        napi_remove_env_cleanup_hook(env, bridge_env_cleanup, b);
        b->hook_registered = false;
    }
    bridge_finalize_free(b, /*env_still_alive=*/true);
}

// Called on the bridge owner thread after its resolver reference is created.
// The unreferenced TSFN is an owner-env cleanup handoff: native drains may queue
// its JS callback from any thread, while env teardown invokes its finalizer.
static bool bridge_register_owner_cleanup(engine_bridge_t* b) {
    if (b == NULL || b->resolver_js == NULL || b->env == NULL) return true;
    napi_value resource_name;
    if (napi_create_string_utf8(
            b->env, "dwResolverOwnerCleanup", NAPI_AUTO_LENGTH, &resource_name) != napi_ok ||
        napi_create_threadsafe_function(
            b->env, NULL, NULL, resource_name, 0, 1, b,
            bridge_owner_cleanup_finalize, b, call_js_bridge_owner_cleanup,
            &b->owner_cleanup_tsfn) != napi_ok) {
        return false;
    }
    if (napi_unref_threadsafe_function(b->env, b->owner_cleanup_tsfn) != napi_ok) {
        napi_release_threadsafe_function(
            b->owner_cleanup_tsfn, napi_tsfn_abort);
        b->owner_cleanup_released = true;
        return false;
    }
    return true;
}

static void bridge_release_native_and_handoff(engine_bridge_t* b) {
    if (b == NULL) return;
    uv_mutex_lock(&g_mutex);
    if (b->owner_alive && b->resolver_js != NULL &&
        b->owner_cleanup_tsfn != NULL && !b->owner_cleanup_queued) {
        b->owner_cleanup_queued = true;
        // Queueing is thread-safe and never invokes the JS callback inline. Keep
        // native ownership until the enqueue returns, so an env finalizer cannot
        // free b between the ownership transition and this handoff.
        napi_status status = napi_call_threadsafe_function(
            b->owner_cleanup_tsfn, b, napi_tsfn_nonblocking);
        if (status != napi_ok) b->owner_cleanup_queued = false;
    }
    b->native_alive = false;
    bridge_release_if_unowned_locked(b);
    uv_mutex_unlock(&g_mutex);
}

// Call under g_mutex. Stale bridges stay owned by their env cleanup hooks, but
// only records from the currently published isolate may admit operations.
static engine_bridge_t* bridge_find_current(long long handle) {
    for (engine_bridge_t* b = g_bridges; b != NULL; b = b->next) {
        if (b->handle == handle && b->isolate_generation == g_isolate_generation) return b;
    }
    return NULL;
}

// Destruction may reclaim a stale bridge, provided finalization does not touch
// the replacement isolate. Call under g_mutex.
static engine_bridge_t* bridge_find_any(long long handle) {
    for (engine_bridge_t* b = g_bridges; b != NULL; b = b->next) {
        if (b->handle == handle) return b;
    }
    return NULL;
}

// Allocate the public handle while g_mutex is held. A zero result means the JS
// safe-integer handle space is exhausted.
static long long next_engine_handle_locked(void) {
    if (g_next_engine_handle <= 0 || g_next_engine_handle > MAX_SAFE_ENGINE_HANDLE) return 0;
    long long handle = g_next_engine_handle;
    g_next_engine_handle = handle == MAX_SAFE_ENGINE_HANDLE ? 0 : handle + 1;
    return handle;
}

// Round-15 (svacas P1): retain a bridge whose engine destroy was skipped while
// the isolate was still live (see g_stranded_bridges). The ctx word Java holds
// stays valid until a later drain retries the destroy and frees it. Takes
// g_mutex; the caller MUST have already unlinked `b` from g_bridges (its `next`
// is reused for the stranded list) and MUST NOT hold g_mutex.
//
// Round-10 review note (parked from Task 6): unlike the normal admitted-op path
// in §6.3/above, a stranded bridge is NOT `in_flight`-pinned while it sits on
// g_stranded_bridges. That is safe under the supported single-owner-thread
// contract: a bridge only reaches here via bridge_finalize (destroyEngine, the
// owner-thread-only call, or the env cleanup hook on the owner env's death), and
// both of those already require in_flight == 0 to have run at all (see the
// deferred-destroy comment above bridge_finalize_registry) -- so in_flight is
// already drained to zero by construction before a bridge is ever stranded, and
// bridge_find_current() can no longer look it up by handle (it's unlinked from
// g_bridges), so no new op can be admitted against it. The only way a
// drained-then-freed stranded bridge could still be dereferenced is unsupported
// cross-Worker handle sharing or other API misuse that starts a background
// operation against a handle after it has already been unlinked here --
// outside the documented single-owner-thread usage this addon supports. Even in
// that unsupported scenario this is strictly better than the pre-fix behavior
// (an unconditional free on every skipped-destroy path).
static void bridge_retain_stranded(engine_bridge_t* b) {
    if (b == NULL) return;
    uv_mutex_lock(&g_mutex);
    b->next = g_stranded_bridges;
    g_stranded_bridges = b;
    uv_mutex_unlock(&g_mutex);
}

// Find this env's init record, or NULL. Caller MUST hold g_mutex.
static env_init_rec_t* env_init_rec_find_locked(napi_env env) {
    for (env_init_rec_t* r = g_env_recs; r != NULL; r = r->next) {
        if (r->env == env) return r;
    }
    return NULL;
}

// Find-or-create this env's init record and increment its init_refs. Sets
// *is_new = true iff a record was just allocated (the caller must then register
// the env-death hook on its own thread). Returns the record, or NULL only on
// calloc failure (caller must NOT bump g_ref_count in that case). Caller MUST
// hold g_mutex.
static env_init_rec_t* env_init_rec_acquire_locked(napi_env env, bool* is_new) {
    *is_new = false;
    env_init_rec_t* r = env_init_rec_find_locked(env);
    if (r == NULL) {
        r = (env_init_rec_t*)calloc(1, sizeof(env_init_rec_t));
        if (r == NULL) return NULL;
        r->env = env;
        r->init_refs = 0;
        r->next = g_env_recs;
        g_env_recs = r;
        *is_new = true;
    }
    r->init_refs++;
    return r;
}

// Sum of live per-env init references. Caller holds g_mutex. Establishes the
// value g_ref_count must equal (invariant g_ref_count == sum of init_refs); used
// to restore g_ref_count coherently when a deferred teardown cannot be spawned.
static int env_init_refs_total_locked(void) {
    int total = 0;
    for (env_init_rec_t* r = g_env_recs; r != NULL; r = r->next) total += r->init_refs;
    return total;
}

// Fully dispose of a bridge: delete its napi_ref (if the owning env is still
// alive), free tracked result buffers, free the struct. napi_ref/napi_env are
// thread-affine, so napi_delete_reference MUST run on the bridge's owner
// thread (the JS/Worker thread that created it) while that env is still
// alive -- `env_still_alive` must be false whenever the caller knows the
// owning env is tearing down/dead (e.g. the env == NULL sentinel path in
// call_js_write/call_js_transform_write), even though b->env itself is never
// cleared and stays non-NULL. When env_still_alive is false the napi_ref is
// simply skipped -- Node auto-reclaims refs when their env is destroyed, so
// nothing leaks. The bridge must already be unlinked from g_bridges. Do NOT
// hold g_mutex across this call — it invokes N-API. Callers that freed a
// bridge *early* (destroyEngine / streaming completion) must first drop the
// env cleanup hook via napi_remove_env_cleanup_hook so Node never invokes it
// on freed memory; the hook path itself (bridge_env_cleanup) must not remove
// itself and calls this directly.
// `do_registry_remove` is true when the caller must remove the Java registry
// entry (fn_destroy_engine) for this handle before freeing the record: the
// immediate destroyEngine path, or the deferred drain of either destroyEngine
// (round-9 #1) or the env cleanup hook (round-10 #1). fn_destroy_engine is
// called at most once per handle because destroyEngine and bridge_env_cleanup
// are mutually exclusive (destroyEngine removes the hook). It runs on whichever
// thread finalizes (the owner JS thread from the completion sentinel,
// destroyEngine's thread, or the env-cleanup hook thread); fn_destroy_engine
// attaches its own isolate thread, so it is not JS-thread-affine. Must be
// called WITHOUT g_mutex held (it enters GraalVM and, for env_still_alive,
// calls N-API).
// #3 (round 12): the isolate-touching registry removal. Takes a TRANSIENT
// g_active_ops reservation so graal_tear_down_isolate() cannot run across the
// attach. The teardown-state check and the g_active_ops++ are ONE critical
// section: no teardown path can interleave between "isolate is live" and
// "reservation taken". Callable from any thread NOT holding g_mutex.
//
// `detach_site` identifies the operation whose successful attach/destroy is
// being detached; normal finalization uses bridge-finalize, while creation
// rollback keeps the distinct create-rollback fault-injection contract.
//
// Returns TRUE when the caller may safely free the bridge: the engine was
// actually destroyed (registry entry removed), OR the whole isolate is going
// away (TEARING_DOWN / g_isolate == NULL) so the Java registry -- and the
// CallbackWeaveResourceResolver holding this bridge as its ctx -- dies with it.
// Returns FALSE only when the destroy was SKIPPED while the isolate is still
// live (fn_attach_thread failed): the Java registry still holds this bridge as a
// resolver ctx, so freeing it now would be a UAF. The caller must instead retain
// the bridge (bridge_retain_stranded) and retry later (round-15, svacas P1).
static bool bridge_finalize_registry_at_site(engine_bridge_t* b, detach_site_t detach_site) {
    if (b == NULL || fn_destroy_engine == NULL) return true;
    uv_mutex_lock(&g_mutex);
    bool stale_generation = g_isolate == NULL ||
        b->isolate_generation != g_isolate_generation;
    uv_mutex_unlock(&g_mutex);
    if (stale_generation) {
        // The bridge's Java registry died with its old isolate. Never attach to
        // a replacement isolate, where native_handle may identify a new engine.
        return true;
    }
    // Test-only: force ONE live-isolate strand (simulate fn_attach_thread failing
    // while the isolate is live -> destroy SKIPPED). Inert unless a test both
    // enabled the hooks (DATAWEAVE_TEST_HOOKS) and armed it via
    // __test_forceStrandOnce(); one-shot, so exactly one finalize is diverted.
    if (g_test_hooks) {
        uv_mutex_lock(&g_mutex);
        if (g_test_force_strand_once) {
            g_test_force_strand_once = false;
            uv_mutex_unlock(&g_mutex);
            return false;  // caller must retain/keep the bridge (ctx still live in Java)
        }
        uv_mutex_unlock(&g_mutex);
    }
    uv_mutex_lock(&g_mutex);
    wait_for_detach_publication_locked();
    // If the waiter already committed to physical teardown (TEARING_DOWN) or the
    // isolate is already gone, the Java registry died/dies with it -- nothing to
    // remove, and attaching would race graal_tear_down_isolate. Skip, but report
    // "safe to free": the registry entry is (being) reclaimed with the isolate,
    // so the resolver ctx can no longer be dereferenced. Because the waiter
    // publishes TEARING_DOWN (and Case 4 holds g_mutex across its g_active_ops==0
    // check + teardown) under this same lock, this check plus the increment below
    // cannot be split by a teardown.
    if (g_teardown_state == TEARDOWN_TEARING_DOWN || g_isolate == NULL ||
        b->isolate_generation != g_isolate_generation) {
        uv_mutex_unlock(&g_mutex);
        return true;
    }
    g_active_ops++;  // pins the live isolate against teardown for this attach
    uv_mutex_unlock(&g_mutex);

    void* thread = NULL;
    bool destroyed = false;
    if (fn_attach_thread(g_isolate, &thread) == 0 && thread != NULL) {
        fn_destroy_engine(thread, b->native_handle);
        detach_thread_checked(detach_site, thread);
        destroyed = true;  // registry entry removed -> resolver ctx is now dead
    }
    // else: attach failed while the isolate is STILL LIVE -- destroy was skipped,
    // the Java registry still holds this bridge as a resolver ctx. Report FALSE so
    // the caller retains (does NOT free) the bridge.

    // Verbatim g_active_ops release pattern.
    uv_mutex_lock(&g_mutex);
    g_active_ops--;
    uv_cond_broadcast(&g_teardown_cond);
    uv_mutex_unlock(&g_mutex);

    return destroyed;
}

static bool bridge_finalize_registry(engine_bridge_t* b) {
    return bridge_finalize_registry_at_site(b, DETACH_SITE_BRIDGE_FINALIZE);
}

// The non-isolate finalize phase: delete the resolver napi_ref (owner JS thread
// only, and only while its env is alive -- resolver-gated), free tracked result
// buffers, free the record. Touches no GraalVM isolate state, so it is safe to
// run after the g_active_ops reservation above is released.
static void bridge_finalize_free(engine_bridge_t* b, bool env_still_alive) {
    if (b == NULL) return;
    if (env_still_alive && b->resolver_js != NULL && b->env != NULL) {
        napi_delete_reference(b->env, b->resolver_js);
        // Test-only: count owner-thread resolver-ref deletions so the strand
        // regression test can prove the ref was finalized (not leaked / not
        // drained undeleted). Inert unless DATAWEAVE_TEST_HOOKS is set.
        if (g_test_hooks) {
            uv_mutex_lock(&g_mutex);
            g_test_resolver_ref_deletes++;
            if (g_test_live_resolver_refs > 0) g_test_live_resolver_refs--;
            uv_mutex_unlock(&g_mutex);
        }
    }
    uv_mutex_lock(&g_mutex);
    if (env_still_alive || b->env == NULL || !b->owner_alive) {
        b->resolver_js = NULL;
        b->owner_alive = false;
    }
    b->native_alive = false;
    bridge_release_if_unowned_locked(b);
    uv_mutex_unlock(&g_mutex);
    if (env_still_alive && b->owner_cleanup_tsfn != NULL &&
        !b->owner_cleanup_released) {
        b->owner_cleanup_released = true;
        napi_release_threadsafe_function(
            b->owner_cleanup_tsfn, napi_tsfn_release);
    }
}

// Thin wrapper preserving the original signature and every call site. Registry
// removal (if requested) runs first under its transient reservation, then the
// record is freed -- but round-15 (svacas P1) makes the free CONDITIONAL on the
// registry removal succeeding. If do_registry_remove is requested and the
// destroy was SKIPPED while the isolate is still live, bridge_finalize_registry
// returns false: the Java registry still holds this bridge as a resolver ctx, so
// we must NOT free it. Retain it (bridge_retain_stranded) so the ctx stays valid
// and a later drain retries the destroy and frees it. When do_registry_remove is
// false there is nothing registered (handle <= 0 construction failures), so the
// free is unconditional as before.
// `may_rehook` is true only when the caller is on the bridge's OWNER thread with
// the env alive and continuing (destroyEngine's immediate path, bridge_end_op on
// the owner env). On a live-isolate strand there, ownership of resolver_js's
// deletion stays with the env cleanup hook: keep (or re-register) the hook and
// return WITHOUT enqueuing on g_stranded_bridges, so the OWNER thread deletes the
// ref and frees the record at env teardown -- never the off-thread drain (which
// skips napi_delete_reference and would leak the ref). When may_rehook is false
// (env tearing down, or a creation abort) there is no live owner hook to keep, so
// a strand falls back to bridge_retain_stranded and the drain frees it later.
static void bridge_finalize(engine_bridge_t* b, bool env_still_alive,
                            bool do_registry_remove, bool may_rehook) {
    if (b == NULL) return;
    if (do_registry_remove && !bridge_finalize_registry(b)) {
        // Strand: isolate live, attach failed, registry entry NOT removed.
        if (may_rehook && env_still_alive && b->env != NULL) {
            // On the owner thread with the env alive & continuing. Give the bridge
            // to its env cleanup hook (still registered here, since the strand
            // paths no longer pre-remove it) so the OWNER thread deletes
            // resolver_js and frees at env teardown -- never the off-thread drain.
            if (!b->hook_registered
                && napi_add_env_cleanup_hook(b->env, bridge_env_cleanup, b) == napi_ok) {
                b->hook_registered = true;
            }
            if (b->hook_registered) {
                return;  // single owner = the hook; NOT on g_stranded_bridges
            }
            // hook unavailable: fall through to drain (best effort).
        }
        bridge_retain_stranded(b);  // env dead / hook gone: drain frees (ref auto-reclaimed or none)
        return;
    }
    // Free path: remove the hook first (owner thread only) so Node never invokes
    // it on freed memory, then delete the ref (env alive) + free.
    if (env_still_alive && b->hook_registered && b->env != NULL) {
        napi_remove_env_cleanup_hook(b->env, bridge_env_cleanup, b);
        b->hook_registered = false;
    }
    bridge_finalize_free(b, env_still_alive);
}

// Round-15 (svacas P1): retry destroy for every bridge stranded because its
// engine destroy was skipped on a transient fn_attach_thread failure while the
// isolate was live (see g_stranded_bridges). Detach the whole list under g_mutex,
// then for each bridge retry the isolate registry removal via
// bridge_finalize_registry: on success (or the isolate having since gone away)
// free the record; on repeated failure re-retain it for the next drain. Does
// ONLY GraalVM calls (attach/destroy/detach, inside bridge_finalize_registry) +
// list manipulation + free -- NO napi env-affine calls. In particular the free
// passes env_still_alive=false: this drain may run on a thread that is NOT the
// bridge's owner (e.g. another env's napi_initialize, or a background worker),
// so it must not touch the thread-affine napi_ref; Node reclaims that ref when
// the owner env is destroyed. Safe to call from any thread NOT holding g_mutex.
static void drain_stranded_bridges(void) {
    uv_mutex_lock(&g_mutex);
    engine_bridge_t* list = g_stranded_bridges;
    g_stranded_bridges = NULL;
    uv_mutex_unlock(&g_mutex);

    while (list != NULL) {
        engine_bridge_t* b = list;
        list = list->next;  // snapshot the link before b is freed or re-retained
        b->next = NULL;
        if (bridge_finalize_registry(b)) {
            // Registry entry removed (or isolate gone): release native ownership.
            // The owner env hook remains responsible for deleting resolver_js and
            // releasing owner ownership on its Node thread.
            bridge_release_native_and_handoff(b);
        } else {
            // Still could not attach (isolate live, transient failure): keep the
            // ctx valid and retry at the next drain.
            bridge_retain_stranded(b);
        }
    }
}

// Env cleanup hook (F2): registered per resolver-backed bridge at creation via
// napi_add_env_cleanup_hook, so each Worker/main env disposes its OWN bridges on
// its OWN thread when that env tears down — instead of napi_cleanup deleting
// refs from whichever thread happens to release the last DataWeave instance,
// which is undefined behavior for thread-affine napi_env/napi_ref. Runs on the
// owner thread with the env still alive, which is exactly where napi_ref deletion
// is legal.
static void bridge_env_cleanup(void* arg) {
    engine_bridge_t* b = (engine_bridge_t*)arg;
    if (b == NULL) return;

    // Node auto-removes this hook as it fires it, so it is no longer registered.
    // Clear the flag first so bridge_finalize (may_rehook=false below, but also
    // the deferred bridge_end_op path) never tries to remove an already-gone hook.
    b->hook_registered = false;

    uv_mutex_lock(&g_mutex);
    // Unlink from g_bridges if still present (destroyEngine may have already
    // unlinked it while deferring a free — see below).
    engine_bridge_t** pp = &g_bridges;
    while (*pp != NULL) {
        if (*pp == b) { *pp = b->next; break; }
        pp = &(*pp)->next;
    }
    engine_bridge_t** stranded_pp = &g_stranded_bridges;
    while (*stranded_pp != NULL) {
        if (*stranded_pp == b) {
            *stranded_pp = b->next;
            b->next = NULL;
            break;
        }
        stranded_pp = &(*stranded_pp)->next;
    }
    // An in-flight streaming/transform op holds a live threadsafe function that
    // keeps this env's event loop alive, so the env should never tear down while
    // in_flight > 0. Guard defensively anyway: mark destroy_pending and let the
    // op's completion path drain and finalize it (do NOT finalize here, the op's
    // background thread could still dereference this bridge).
    if (b->in_flight > 0) {
        b->destroy_pending = true;
        // round-10 (#1): the draining op must ALSO remove the Java registry
        // entry (like destroyEngine's deferred path), or the resolver engine's
        // ScriptRuntime is left registered with a resolver ctx pointing at the
        // freed bridge. Set the deferred-registry-removal flag here.
        b->deferred_registry_remove = true;
        uv_mutex_unlock(&g_mutex);
        return;
    }
    // in_flight == 0: finalize now. The abandoned engine's init reference is
    // NOT released here (round-13 #5) -- it is released by the env-death hook
    // (env_init_cleanup) when this env dies, which owns the whole per-env
    // balance. There is nothing left to do under the lock before unlocking in
    // this branch. bridge_finalize_registry inside finalize checks teardown
    // state under g_mutex, so a torn-down/TEARING_DOWN isolate makes the
    // registry removal a correct no-op (the Java registry died with the
    // isolate).
    uv_mutex_unlock(&g_mutex);

    // We are inside Node's invocation of this hook, so we must not (and need not)
    // call napi_remove_env_cleanup_hook for ourselves here. The env is still
    // alive here -- that is the whole point of this hook's design (see above) --
    // so the napi_ref deletion in bridge_finalize is legal.
    // round-10 (#1): remove the Java registry entry too (do_registry_remove=true).
    // This hook only ever fires for a resolver-backed engine that was never
    // passed to destroyEngine (destroyEngine removes this hook), so its
    // initialize() ref was never released either -> the isolate is still live
    // and fn_destroy_engine's fresh-thread attach is legal (bridge_finalize
    // guards on g_isolate for the main-env-after-isolate-teardown corner). Not
    // removing it would leave a CallbackWeaveResourceResolver whose ctx is the
    // freed bridge -> UAF on a later invocation of this handle.
    // may_rehook=false: the env is tearing down, so do NOT re-register the hook on
    // a strand -- a strand here falls back to g_stranded_bridges (Node reclaims the
    // ref at env teardown; the off-thread drain frees the record later).
    if (b->resolver_js != NULL && b->env != NULL) {
        napi_delete_reference(b->env, b->resolver_js);
        b->resolver_js = NULL;
        if (g_test_hooks) {
            uv_mutex_lock(&g_mutex);
            g_test_resolver_ref_deletes++;
            if (g_test_live_resolver_refs > 0) g_test_live_resolver_refs--;
            uv_mutex_unlock(&g_mutex);
        }
    }
    b->owner_alive = false;
    if (b->native_alive) {
        if (bridge_finalize_registry(b)) {
            uv_mutex_lock(&g_mutex);
            b->native_alive = false;
            bridge_release_if_unowned_locked(b);
            uv_mutex_unlock(&g_mutex);
        } else {
            bridge_retain_stranded(b);
        }
    } else {
        uv_mutex_lock(&g_mutex);
        bridge_release_if_unowned_locked(b);
        uv_mutex_unlock(&g_mutex);
    }
}

// Increment this engine's in_flight while g_mutex is ALREADY held. Used by the
// run/streaming/transform admission paths so the per-engine pin is taken in the
// SAME critical section as the g_active_ops reservation and the lifecycle check
// -- closing the round-11 window where a concurrent destroyEngine could observe
// in_flight == 0 and free the bridge under an already-admitted op. Returns the
// record, or NULL for an unknown handle (nothing to pin; the worker/native call
// surfaces "Unknown engine handle"). Caller MUST hold g_mutex.
static engine_bridge_t* bridge_begin_op_locked(long long handle) {
    engine_bridge_t* b = bridge_find_current(handle);
    if (b != NULL) b->in_flight++;
    return b;
}

// A streaming/transform/run op marks one op in flight on the engine's record so
// the record (and, for resolver-backed engines, its napi_ref) cannot be freed
// while the background uv_thread runs -- and, since round-9 (#1), so that
// destroyEngine defers the Java registry removal until this op drains. Every
// engine (resolver-backed or resolver-less) now has a record, so
// bridge_begin_op_locked returns a non-NULL pointer for any known handle; the
// completion sentinel MUST call bridge_end_op on it to balance in_flight and
// run any deferred destroy. Returns NULL only for an unknown handle (nothing to
// protect, no bridge_end_op needed). The returned pointer is stable for the
// op's lifetime because in_flight > 0 blocks both destroyEngine and the env
// cleanup hook from freeing the record. Since round-11 (#2), every call site
// takes the pin atomically with its g_mutex-guarded admission check via
// bridge_begin_op_locked directly (no self-locking wrapper) -- see
// napi_run_script_streaming_engine / napi_run_script_transform_engine.

// End a streaming/transform op. Runs on the owner (JS) thread from the completion
// sentinel. If destroyEngine (or the env cleanup hook) ran while this op was in
// flight, it deferred the free — already unlinked from g_bridges — so the last op
// to drain finalizes the bridge here, on the legal (owner) thread. `env_still_alive`
// must be false when the caller is running the env == NULL sentinel path (the
// owning env is tearing down/dead), so a finalize triggered from here does not
// call napi_delete_reference on a dead env.
static void bridge_end_op(engine_bridge_t* b, bool env_still_alive) {
    if (b == NULL) return;
    uv_mutex_lock(&g_mutex);
    b->in_flight--;
    bool finalize = (b->destroy_pending && b->in_flight == 0);
    bool remove_registry = finalize && b->deferred_registry_remove;
    uv_mutex_unlock(&g_mutex);
    // remove_registry is true when either destroyEngine (round-9 #1) or the env
    // cleanup hook (round-10 #1) deferred the registry removal while this op was
    // in flight; the draining op performs it exactly once here. bridge_finalize
    // guards the call on g_isolate, so a teardown that raced ahead is a no-op.
    // env_still_alive here means we are draining on the owner thread with the env
    // alive, so a live-isolate strand may keep the env cleanup hook (may_rehook).
    // When env_still_alive is false (env == NULL sentinel path) a strand falls back
    // to the drain, which is correct: the owner env is gone.
    if (finalize) bridge_finalize(b, env_still_alive, /*do_registry_remove=*/remove_registry,
                                  /*may_rehook=*/env_still_alive);
}

// --- Initialization ---

struct init_args {
  const char* lib_path;
  int result;
  char error[512];
};

static void init_thread_fn(void* arg) {
  struct init_args* args = (struct init_args*)arg;

  int rc = uv_dlopen(args->lib_path, &g_lib);
  if (rc != 0) {
    snprintf(args->error, sizeof(args->error), "Failed to load library: %s", uv_dlerror(&g_lib));
    args->result = -1;
    return;
  }
  g_lib_loaded = 1;

  uv_dlsym(&g_lib, "graal_create_isolate", (void**)&fn_create_isolate);
  uv_dlsym(&g_lib, "graal_attach_thread", (void**)&fn_attach_thread);
  uv_dlsym(&g_lib, "graal_detach_thread", (void**)&fn_detach_thread);
  uv_dlsym(&g_lib, "graal_tear_down_isolate", (void**)&fn_tear_down_isolate);
  uv_dlsym(&g_lib, "free_cstring", (void**)&fn_free_cstring);

  // Load per-engine entrypoints. Every initialize() call creates an engine via
  // create_engine/create_engine_with_resolver (see dataweave.ts), so these are
  // load-time required, not optional.
  uv_dlsym(&g_lib, "create_engine", (void**)&fn_create_engine);
  uv_dlsym(&g_lib, "create_engine_with_resolver", (void**)&fn_create_engine_with_resolver);
  uv_dlsym(&g_lib, "destroy_engine", (void**)&fn_destroy_engine);
  uv_dlsym(&g_lib, "run_script_engine", (void**)&fn_run_script_engine);
  uv_dlsym(&g_lib, "run_script_callback_engine", (void**)&fn_run_script_callback_engine);
  uv_dlsym(&g_lib, "run_script_input_output_callback_engine", (void**)&fn_run_script_input_output_callback_engine);

  // graal_attach_thread, graal_detach_thread and graal_tear_down_isolate are
  // required, not optional. graal_attach_thread is called UNCONDITIONALLY on
  // every engine-creation, execution, and teardown path (e.g. createEngine at
  // fn_attach_thread(g_isolate, &thread) with no NULL guard), so a dwlib missing
  // it would pass init and then invoke a NULL function pointer on the first
  // createEngine() (review #21 #2). graal_detach_thread/graal_tear_down_isolate
  // back the bootstrap-detach failure path below (review #20 #1), whose guard
  // short-circuits when those pointers are NULL. Gating all three here makes
  // those guarantees unconditional -- a dwlib missing any of them fails init
  // fast with a clear message instead of crashing or publishing an isolate whose
  // bootstrap thread was never detached.
  if (!fn_create_isolate || !fn_free_cstring || !fn_attach_thread ||
      !fn_detach_thread || !fn_tear_down_isolate) {
    snprintf(args->error, sizeof(args->error), "Missing required symbols in library");
    args->result = -2;
    return;
  }

  // Fail fast, with a clear message, if the loaded dwlib predates the
  // per-engine ABI (W-23692110). Without this check, the library would load
  // "successfully" here and every initialize() call would still fail later
  // deep inside createEngine()/createEngineWithResolver() with a confusing
  // "not available in native library" error instead of this one.
  if (!fn_create_engine || !fn_create_engine_with_resolver || !fn_destroy_engine ||
      !fn_run_script_engine || !fn_run_script_callback_engine ||
      !fn_run_script_input_output_callback_engine) {
    snprintf(args->error, sizeof(args->error),
             "dwlib is missing required per-engine symbols (expected in dwlib "
             "built with W-23692110 or later) - rebuild/upgrade the native library");
    args->result = -2;
    return;
  }

  void* boot_thread = NULL;
  rc = fn_create_isolate(NULL, &g_isolate, &boot_thread);
  if (rc != 0) {
    snprintf(args->error, sizeof(args->error), "graal_create_isolate failed with code %d", rc);
    args->result = rc;
    return;
  }

  if (g_isolate_generation == UINT64_MAX) {
    if (g_test_hooks) {
      g_test_isolate_creations++;
      g_test_teardown_calls++;
    }
    int td_rc = fn_tear_down_isolate(boot_thread);
    g_isolate = NULL;
    g_thread = NULL;
    if (td_rc != 0) {
      fprintf(stderr,
              "[DataWeave Node addon] isolate generation space was exhausted and "
              "the unpublishable isolate could not be torn down; it is being leaked "
              "for the process lifetime.\n");
    }
    snprintf(args->error, sizeof(args->error),
             "DataWeave isolate generation space exhausted");
    args->result = -4;
    return;
  }
  g_isolate_generation++;
  if (g_test_hooks) g_test_isolate_creations++;

  // review #21 #1: a brand-new isolate starts un-poisoned. Any poison flag left
  // over from a previously abandoned/leaked isolate must not carry onto this
  // fresh one. Runs under the init caller's g_mutex (see the g_mutex discipline
  // note for init_thread_fn).
  g_isolate_poisoned = false;

  // Detach the bootstrap thread immediately. This init OS thread is joined and
  // exits right after, so leaving it attached would leave a phantom attached
  // thread on the isolate — and graal_tear_down_isolate() blocks forever waiting
  // for every other attached thread to reach a safepoint (the dead init thread
  // never will). Subsequent calls (run/streaming/transform, and cleanup) attach
  // their own OS thread on demand and detach when done. Mirrors the Go binding,
  // which likewise detaches the bootstrap thread after graal_create_isolate.
  //
  // If the detach FAILS, boot_thread stays attached while this init OS thread is
  // about to be joined and exit -- a phantom attached thread that would wedge a
  // later graal_tear_down_isolate() forever (review #20 #1). We must not publish
  // such a poisoned isolate. boot_thread is still valid and current here, so use
  // it to tear the isolate down immediately and fail initialization. If teardown
  // ALSO fails, the isolate can never be reclaimed: leak it, emit the diagnostic,
  // and still fail without publishing. Either way we leave g_isolate == NULL so
  // the caller's `args->result != 0` path (addon.c ~955) sees the recoverable
  // "no isolate" state, exactly like every other init failure path.
  if (fn_detach_thread && fn_detach_thread(boot_thread) != 0) {
    int td_rc = -1;
    if (fn_tear_down_isolate) {
      if (g_test_hooks) g_test_teardown_calls++;
      td_rc = fn_tear_down_isolate(boot_thread);
    }
    if (td_rc != 0) {
      fprintf(stderr,
              "[DataWeave Node addon] bootstrap thread detach AND isolate "
              "teardown both failed during initialize(); the isolate can never "
              "be torn down and is being leaked for the process lifetime. "
              "Initialization was aborted.\n");
    }
    g_isolate = NULL;  // preserve the "nonzero result => g_isolate == NULL" contract
    g_thread = NULL;
    snprintf(args->error, sizeof(args->error),
             "graal_detach_thread failed after isolate creation; "
             "initialization aborted to avoid a poisoned isolate");
    args->result = -3;
    return;
  }
  g_thread = NULL;

  args->result = 0;
}

// Forward declaration: the env-death hook that reclaims an abandoned env's
// init references. Defined below (round-13 #5); registered here (in
// env_init_acquire_and_hook) because napi_add_env_cleanup_hook is only legal
// while the env is alive on its own JS thread, which napi_initialize is.
static void env_init_cleanup(void* arg);  // defined below (round-13 #5)

// Acquire one init reference for `env` under g_mutex, registering the env-death
// hook on first use. Returns true on success (caller then does g_ref_count++);
// on failure the caller must NOT bump g_ref_count -- it unlocks and throws.
// Caller MUST hold g_mutex; this function keeps it held on success and on the
// calloc-failure return. On hook-registration failure it rolls back the
// just-acquired init_refs (freeing the record if it drops to 0) so no orphan
// record without a death hook survives.
static bool env_init_acquire_and_hook(napi_env env) {
    bool is_new = false;
    env_init_rec_t* rec = env_init_rec_acquire_locked(env, &is_new);
    if (rec == NULL) return false;  // calloc failed
    if (is_new) {
        napi_status hs = napi_add_env_cleanup_hook(env, env_init_cleanup, rec);
        if (hs != napi_ok) {
            // Roll back: this record has no death hook, so its references would
            // never be reclaimed. Drop the one we just took; free if now empty.
            rec->init_refs--;
            if (rec->init_refs == 0) {
                env_init_rec_t** pp = &g_env_recs;
                while (*pp != NULL) { if (*pp == rec) { *pp = rec->next; break; } pp = &(*pp)->next; }
                free(rec);
            }
            return false;
        }
    }
    return true;
}

// Forward declaration: tears down g_isolate on a dedicated attached thread.
// Defined below; used here (napi_initialize's create-path acquire-failure
// recovery) and further down by isolate_ref_release_n_locked.
// arg is a cleanup_result_t* out-param (review #17 #1); see the definition.
static void cleanup_thread_fn(void* arg);

// Forward declaration: retries a stranded teardown (round-14 #2/#3). Defined
// further below; used by the streaming/transform op-completion drain points,
// which run earlier in this file than the definition.
static void retry_stranded_teardown_locked(void);

static napi_value napi_initialize(napi_env env, napi_callback_info info) {
  if (native_callback_active()) return throw_callback_reentrancy(env);
  size_t argc = 1;
  napi_value argv[1];
  // Review #10 #5 (svacas P2): check napi_get_cb_info's status too, not just
  // argc -- mirrors every other validated entrypoint in this file (e.g.
  // napi_run_script_engine), which never assumes an N-API call succeeded.
  if (napi_get_cb_info(env, info, &argc, argv, NULL, NULL) != napi_ok || argc < 1) {
    napi_throw_error(env, NULL, "initialize requires a library path argument");
    return NULL;
  }

  // Reject a non-string argv[0] before touching the stack lib_path buffer
  // below. Without this, a non-string argument left napi_get_value_string_utf8's
  // status ignored and lib_path uninitialized/partially-written before
  // uv_dlopen read it (garbage path, occasionally UB).
  napi_valuetype vt;
  if (napi_typeof(env, argv[0], &vt) != napi_ok || vt != napi_string) {
    napi_throw_error(env, NULL, "initialize: library path must be a string");
    return NULL;
  }

  char lib_path[4096];
  size_t len;
  if (napi_get_value_string_utf8(env, argv[0], lib_path, sizeof(lib_path), &len) != napi_ok) {
    napi_throw_error(env, NULL, "initialize: failed to read library path");
    return NULL;
  }

  uv_mutex_lock(&g_mutex);
  wait_for_detach_publication_locked();
  bool poisoned_before_drain = g_isolate_poisoned;
  uv_mutex_unlock(&g_mutex);
  if (poisoned_before_drain) {
    napi_throw_error(env, NULL, ISOLATE_POISONED_MESSAGE);
    return NULL;
  }

  // Round-15 (svacas P1): retry any bridge whose engine destroy was skipped on a
  // transient attach failure (g_stranded_bridges). Drain before taking g_mutex
  // (drain_stranded_bridges locks internally). If a live isolate survives from a
  // prior init the retry destroys + frees it now; if the isolate is gone the
  // stranded bridges are freed (their Java registry died with it). Cheap no-op
  // when nothing is stranded.
  drain_stranded_bridges();

  uv_mutex_lock(&g_mutex);
  wait_for_detach_publication_locked();

  // A detach failure is terminal for the currently published isolate. It may
  // not be adopted or reused; explicit cleanup must abandon it first.
  if (g_isolate_poisoned) {
    uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, ISOLATE_POISONED_MESSAGE);
    return NULL;
  }

  // A prior last-release could not tear the isolate down and armed the retry
  // signal (review #6 #3/#4). Because retries otherwise fire only at op
  // completion (the streaming/transform drains), a zero-op stranded isolate
  // would never be reclaimed and the adoption/fast paths below would silently
  // discard the pending teardown (review #6 #5). Drive the pending teardown to
  // completion here first: on success g_isolate/g_initialized are cleared and we
  // build a fresh isolate below; on repeated failure the live isolate is adopted
  // by the fast path (safe -- the teardown was resource reclamation, not a
  // malfunction). No-ops cheaply when nothing is stranded (flag clear -> return).
  retry_stranded_teardown_locked();

  // After the retry above, a PERSISTENTLY failing teardown leaves the isolate
  // live but unusable: g_isolate != NULL, g_initialized == 0, and
  // g_teardown_state == TEARDOWN_NONE (no teardown thread exists). The wait loop
  // below would treat `g_isolate != NULL && !g_initialized` as "a teardown is in
  // flight" and block on uv_cond_wait -- but nothing remains to broadcast
  // g_teardown_cond, so it would hang forever holding g_mutex and freeze every
  // future initialize()/cleanup() (review #8 #1). This state is not recoverable
  // by waiting; fail deterministically instead. g_teardown_needed stays armed so
  // a later op-completion drain can still reclaim the isolate; we neither clear
  // it nor touch g_ref_count (still 0 == sum(init_refs), invariant intact).
  if (g_isolate != NULL && !g_initialized && g_teardown_state == TEARDOWN_NONE) {
    uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL,
                     "DataWeave native runtime is stranded: a prior isolate "
                     "teardown failed and could not be reclaimed");
    return NULL;
  }

  // If a teardown from a prior cleanup() is still draining (the isolate is
  // being torn down on the waiter thread from Task 2), do not race a fresh
  // graal_create_isolate against it -- wait until the isolate is fully gone
  // before proceeding. This is a narrow, rare path (re-initializing mid-drain),
  // not a fast path, so a blocking wait here is acceptable and matches this
  // function's existing fully-synchronous contract -- except in
  // TEARDOWN_PENDING_WAIT (see below), where blocking would deadlock.
  while (g_teardown_state != TEARDOWN_NONE || (g_isolate != NULL && !g_initialized)) {
    if (g_teardown_state == TEARDOWN_PENDING_WAIT) {
      // A teardown is queued but the waiter has NOT begun physical teardown
      // (that transition to TEARING_DOWN happens under this same g_mutex), so
      // g_isolate/g_initialized are still valid. Blocking here would freeze the
      // JS event loop that an active streaming/transform worker needs in order
      // to drain g_active_ops -- the waiter would then wait forever and this
      // wait would never end (the P1 deadlock). Instead, ADOPT the live isolate:
      // cancel the queued teardown, take a fresh ref, and wake the waiter so it
      // aborts without tearing down. g_initialized is already 1, so fall through
      // to the ref-count path below is unnecessary -- return directly.
      if (g_isolate_poisoned) {
        uv_mutex_unlock(&g_mutex);
        napi_throw_error(env, NULL, ISOLATE_POISONED_MESSAGE);
        return NULL;
      }
      if (!env_init_acquire_and_hook(env)) {
        uv_mutex_unlock(&g_mutex);
        napi_throw_error(env, NULL, "Failed to allocate/register env init record");
        return NULL;
      }
      g_teardown_cancelled = true;
      g_ref_count++;
      g_teardown_needed = false;  // round-14: a new owner wants the isolate kept
      uv_cond_broadcast(&g_teardown_cond);
      uv_mutex_unlock(&g_mutex);
      return NULL;
    }
    // TEARDOWN_TEARING_DOWN (or a transient g_isolate!=NULL && !g_initialized):
    // g_active_ops has already reached 0, so nothing depends on the JS event
    // loop -- this blocking wait is deadlock-free and preserves the original
    // "don't race graal_create_isolate against graal_tear_down_isolate"
    // guarantee that round 3's Task 3 added.
    uv_cond_wait(&g_teardown_cond, &g_mutex);
  }

  if (g_initialized) {
    if (g_isolate_poisoned) {
      uv_mutex_unlock(&g_mutex);
      napi_throw_error(env, NULL, ISOLATE_POISONED_MESSAGE);
      return NULL;
    }
    if (!env_init_acquire_and_hook(env)) {
      uv_mutex_unlock(&g_mutex);
      napi_throw_error(env, NULL, "Failed to allocate/register env init record");
      return NULL;
    }
    g_ref_count++;
    g_teardown_needed = false;  // round-14: a new owner wants the isolate kept
    uv_mutex_unlock(&g_mutex);
    return NULL;
  }

  struct init_args args;
  args.lib_path = lib_path;
  args.result = -1;
  args.error[0] = '\0';

  uv_thread_t tid;
  uv_thread_options_t opts;
  opts.flags = UV_THREAD_HAS_STACK_SIZE;
  opts.stack_size = 16 * 1024 * 1024;
  int spawn_rc = uv_thread_create_ex(&tid, &opts, init_thread_fn, &args);
  if (spawn_rc != 0) {
    uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "Failed to spawn initialization thread");
    return NULL;
  }
  uv_thread_join(&tid);

  if (args.result != 0) {
    uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, args.error[0] ? args.error : "Initialization failed");
    return NULL;
  }

  if (!env_init_acquire_and_hook(env)) {
    // init_thread_fn already built the isolate (g_isolate != NULL) but we have
    // not yet set g_initialized = 1. If we just unlock and throw here, we leave
    // g_isolate != NULL && g_initialized == 0 -- the exact condition the wait
    // loop above (`g_isolate != NULL && !g_initialized`) treats as "a teardown
    // is in flight". With g_teardown_state == TEARDOWN_NONE that loop cannot
    // take the TEARDOWN_PENDING_WAIT adoption branch, so it falls into
    // uv_cond_wait(&g_teardown_cond, ...) with nothing left to ever broadcast --
    // every subsequent initialize() on any env hangs forever. Every sibling
    // error path (args.result != 0 above, and the spawn-failure path before it)
    // leaves g_isolate == NULL instead, which is the recoverable state. Tear
    // the just-built isolate back down before throwing so we restore that same
    // recoverable g_isolate == NULL state.
    //
    // g_ref_count is still 0 here (we never got past this check to bump it),
    // and env_init_acquire_and_hook leaves no orphan record behind on failure
    // (calloc failure never created one; hook-registration failure rolls its
    // own record back) -- so the invariant g_ref_count == sum(init_refs) holds
    // with both sides at 0 both before and after this block.
    uv_thread_t cleanup_tid;
    uv_thread_options_t cleanup_opts;
    cleanup_opts.flags = UV_THREAD_HAS_STACK_SIZE;
    cleanup_opts.stack_size = 2 * 1024 * 1024;
    cleanup_thread_result_t result = {CLEANUP_RETAIN, false};
    int cleanup_spawn_rc = uv_thread_create_ex(&cleanup_tid, &cleanup_opts, cleanup_thread_fn, &result);
    if (cleanup_spawn_rc == 0) {
      uv_thread_join(&cleanup_tid);
    }
    if (g_test_hooks && result.teardown_callable) g_test_teardown_calls++;
    if (result.outcome == CLEANUP_TORN_DOWN) {
      // Teardown ran (or there was nothing to tear down) -- clear the globals
      // so the next initialize() sees a clean slate. g_ref_count is already 0.
      if (g_test_engine_record_allocation_failure_generation == g_isolate_generation) {
        g_test_engine_record_allocation_failure_generation = 0;
      }
      g_thread = NULL;
      g_isolate = NULL;
      g_initialized = 0;
    } else if (result.outcome == CLEANUP_UNRECOVERABLE) {
      // teardown+detach double failure (review #17 #1): abandon the isolate and
      // reset published state so this same initialize() failure path throws
      // below and a LATER initialize() builds a fresh isolate. Does NOT arm the
      // retry. g_ref_count is already 0, so the helper's g_ref_count = 0 is a
      // no-op and the invariant g_ref_count == sum(init_refs) still holds.
      abandon_unrecoverable_isolate_locked();
    } else {
      // Spawn failed, or cleanup_thread_fn's attach/teardown to the isolate
      // failed. The isolate is genuinely still alive with g_initialized == 0.
      // Without a retry signal the next initialize() would reach the wait loop's
      // `g_isolate != NULL && !g_initialized` condition with TEARDOWN_NONE (so no
      // adoption branch) and block on uv_cond_wait forever -- nothing left to
      // broadcast (review #7 #2). Arm the stranded-teardown retry so the
      // retry_stranded_teardown_locked() at the top of the next napi_initialize
      // reclaims the isolate (teardown succeeds -> fresh build). This path leaves
      // g_initialized == 0, so -- unlike the release-path twin in
      // isolate_ref_release_n_locked, which leaves g_initialized == 1 and is
      // adopted by the g_initialized-gated fast path -- recovery here relies on
      // the retry actually tearing down: it recovers the realistic TRANSIENT
      // failure, but a truly PERSISTENT graal_tear_down_isolate failure would
      // re-arm and retry each time and ultimately leave the isolate stranded
      // until process exit (best-effort degradation, not a wedge of new work).
      // g_ref_count is still 0 here, so g_teardown_needed (a retry SIGNAL, not a
      // reference) keeps the invariant g_ref_count == sum(init_refs) intact.
      // Mirrors the twin arm in teardown_waiter_thread_fn.
      g_teardown_needed = true;
    }
    uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "Failed to allocate/register env init record");
    return NULL;
  }
  g_initialized = 1;
  g_ref_count++;
  // Round-14: defensive clear. A brand-new isolate can never carry a stale
  // stranded-teardown signal for itself (a new graal_create_isolate only runs
  // when g_isolate == NULL, so this path cannot reuse a surviving stranded
  // isolate) -- but clear it here anyway at the single create-path success
  // point so no later drain retries a teardown against the isolate this
  // initialize() just created and now owns.
  g_teardown_needed = false;
  uv_mutex_unlock(&g_mutex);
  return NULL;
}

// --- Streaming output ---

#define OUTPUT_HIGH_BYTES (1024 * 1024)
#define OUTPUT_LOW_BYTES (512 * 1024)
#define OUTPUT_HIGH_CHUNKS 128
#define OUTPUT_LOW_CHUNKS 64
#define OUTPUT_TSFN_QUEUE_SIZE 129

typedef struct output_credit {
  size_t bytes;
  uint64_t sequence;
  bool delivered;
  bool acknowledged;
  struct output_credit* previous;
  struct output_credit* next;
} output_credit_t;

typedef struct output_flow {
  uv_mutex_t mutex;
  uv_cond_t cond;
  size_t outstanding_bytes;
  size_t outstanding_chunks;
  size_t peak_buffered_bytes;
  size_t peak_buffered_chunks;
  size_t largest_chunk_bytes;
  bool paused;
  bool cancelled;
  bool done;
  unsigned int refs;
  uint64_t operation_id;
  uint64_t next_sequence;
  output_credit_t* credit_head;
  output_credit_t* credit_tail;
  napi_ref thenable_ref;
  napi_ref settlement_fallback_ref;
  bool settlement_started;
} output_flow_t;

typedef struct output_flow_stats {
  uint64_t operation_id;
  size_t outstanding_bytes;
  size_t outstanding_chunks;
  size_t peak_buffered_bytes;
  size_t peak_buffered_chunks;
  size_t largest_chunk_bytes;
  bool paused;
  bool cancelled;
  bool done;
  long long live_flows;
} output_flow_stats_t;

// Test hooks store a value snapshot, never a flow pointer, so close/finalize
// cannot leave introspection pointing at freed operation state.
static output_flow_stats_t g_test_last_output_stats;
static uint64_t g_test_next_output_operation_id = 1;
static long long g_test_live_output_flows = 0;

static void output_flow_record_stats_locked(output_flow_t* flow) {
  if (!g_test_hooks || flow == NULL) return;
  uv_mutex_lock(&g_test_output_mutex);
  g_test_last_output_stats.operation_id = flow->operation_id;
  g_test_last_output_stats.outstanding_bytes = flow->outstanding_bytes;
  g_test_last_output_stats.outstanding_chunks = flow->outstanding_chunks;
  g_test_last_output_stats.peak_buffered_bytes = flow->peak_buffered_bytes;
  g_test_last_output_stats.peak_buffered_chunks = flow->peak_buffered_chunks;
  g_test_last_output_stats.largest_chunk_bytes = flow->largest_chunk_bytes;
  g_test_last_output_stats.paused = flow->paused;
  g_test_last_output_stats.cancelled = flow->cancelled;
  g_test_last_output_stats.done = flow->done;
  g_test_last_output_stats.live_flows = g_test_live_output_flows;
  uv_mutex_unlock(&g_test_output_mutex);
}

static output_flow_t* output_flow_create(void) {
  output_flow_t* flow = (output_flow_t*)calloc(1, sizeof(output_flow_t));
  if (flow == NULL) return NULL;
  if (uv_mutex_init(&flow->mutex) != 0) {
    free(flow);
    return NULL;
  }
  if (uv_cond_init(&flow->cond) != 0) {
    uv_mutex_destroy(&flow->mutex);
    free(flow);
    return NULL;
  }
  flow->refs = 1;
  if (g_test_hooks) {
    uv_mutex_lock(&g_test_output_mutex);
    flow->operation_id = g_test_next_output_operation_id++;
    g_test_live_output_flows++;
    memset(&g_test_last_output_stats, 0, sizeof(g_test_last_output_stats));
    g_test_last_output_stats.operation_id = flow->operation_id;
    g_test_last_output_stats.live_flows = g_test_live_output_flows;
    uv_mutex_unlock(&g_test_output_mutex);
  }
  return flow;
}

static void output_flow_retain(output_flow_t* flow) {
  if (flow == NULL) return;
  uv_mutex_lock(&flow->mutex);
  flow->refs++;
  uv_mutex_unlock(&flow->mutex);
}

static void output_flow_release(output_flow_t* flow, napi_env env) {
  if (flow == NULL) return;
  bool destroy = false;
  uv_mutex_lock(&flow->mutex);
  if (flow->refs > 0) {
    flow->refs--;
    destroy = flow->refs == 0;
  }
  uv_mutex_unlock(&flow->mutex);
  if (!destroy) return;
  // A dead env auto-reclaims N-API references. Live-env terminal paths delete
  // them before releasing the final native owner.
  flow->thenable_ref = NULL;
  if (env != NULL && flow->settlement_fallback_ref != NULL) {
    napi_delete_reference(env, flow->settlement_fallback_ref);
  }
  flow->settlement_fallback_ref = NULL;

  if (g_test_hooks) {
    uv_mutex_lock(&g_test_output_mutex);
    g_test_live_output_flows--;
    if (g_test_last_output_stats.operation_id == flow->operation_id) {
      g_test_last_output_stats.live_flows = g_test_live_output_flows;
    }
    uv_mutex_unlock(&g_test_output_mutex);
  }
  output_credit_t* credit = flow->credit_head;
  while (credit != NULL) {
    output_credit_t* next = credit->next;
    free(credit);
    credit = next;
  }
  uv_cond_destroy(&flow->cond);
  uv_mutex_destroy(&flow->mutex);
  free(flow);
}

// Only the native producer waits here. No caller holds g_mutex while waiting.
static bool output_flow_reserve(
    output_flow_t* flow, size_t bytes, uint64_t* sequence_out) {
  if (flow == NULL) return false;
  output_credit_t* credit = (output_credit_t*)calloc(1, sizeof(output_credit_t));
  if (credit == NULL) return false;
  credit->bytes = bytes;

  uv_mutex_lock(&flow->mutex);
  if (flow->cancelled || flow->done) {
    flow->paused = false;
    output_flow_record_stats_locked(flow);
    uv_mutex_unlock(&flow->mutex);
    free(credit);
    return false;
  }
  bool empty = flow->outstanding_bytes == 0 && flow->outstanding_chunks == 0;
  bool oversized = bytes > OUTPUT_HIGH_BYTES;
  bool oversized_empty = empty && oversized;
  bool over_high =
    flow->outstanding_chunks + 1 > OUTPUT_HIGH_CHUNKS ||
    bytes > SIZE_MAX - flow->outstanding_bytes ||
    flow->outstanding_bytes + bytes > OUTPUT_HIGH_BYTES;
  if (over_high && !oversized_empty) {
    flow->paused = true;
    output_flow_record_stats_locked(flow);
    while (!flow->cancelled && !flow->done &&
           ((oversized &&
             (flow->outstanding_bytes > 0 || flow->outstanding_chunks > 0)) ||
            flow->outstanding_bytes > OUTPUT_LOW_BYTES ||
            flow->outstanding_chunks > OUTPUT_LOW_CHUNKS)) {
      uv_cond_wait(&flow->cond, &flow->mutex);
    }
  }

  if (flow->cancelled || flow->done) {
    flow->paused = false;
    output_flow_record_stats_locked(flow);
    uv_mutex_unlock(&flow->mutex);
    free(credit);
    return false;
  }

  flow->paused = false;
  // The overflow check above routes a huge reservation through the oversized
  // wait, which drains prior credit; ordinary reservations are already bounded.
  flow->outstanding_bytes += bytes;
  flow->outstanding_chunks++;
  credit->sequence = ++flow->next_sequence;
  credit->previous = flow->credit_tail;
  if (flow->credit_tail != NULL) flow->credit_tail->next = credit;
  else flow->credit_head = credit;
  flow->credit_tail = credit;
  if (flow->outstanding_bytes > flow->peak_buffered_bytes) {
    flow->peak_buffered_bytes = flow->outstanding_bytes;
  }
  if (flow->outstanding_chunks > flow->peak_buffered_chunks) {
    flow->peak_buffered_chunks = flow->outstanding_chunks;
  }
  if (bytes > flow->largest_chunk_bytes) flow->largest_chunk_bytes = bytes;
  output_flow_record_stats_locked(flow);
  *sequence_out = credit->sequence;
  uv_mutex_unlock(&flow->mutex);
  return true;
}

typedef enum {
  OUTPUT_ACK_IGNORED = 0,
  OUTPUT_ACK_ACCEPTED,
  OUTPUT_ACK_INVALID_SEQUENCE,
  OUTPUT_ACK_NOT_DELIVERED,
  OUTPUT_ACK_OUT_OF_ORDER,
  OUTPUT_ACK_BYTES_MISMATCH,
  OUTPUT_ACK_DUPLICATE,
} output_ack_result_t;

static output_ack_result_t output_flow_acknowledge(
    output_flow_t* flow, uint64_t sequence, size_t bytes) {
  if (flow == NULL) return OUTPUT_ACK_IGNORED;
  uv_mutex_lock(&flow->mutex);
  if (flow->cancelled) {
    output_flow_record_stats_locked(flow);
    uv_mutex_unlock(&flow->mutex);
    return OUTPUT_ACK_IGNORED;
  }

  output_credit_t* credit = flow->credit_head;
  output_credit_t* requested = credit;
  while (requested != NULL && requested->sequence != sequence) {
    requested = requested->next;
  }
  output_ack_result_t result;
  if (requested == NULL) {
    result = flow->done
      ? OUTPUT_ACK_IGNORED
      : sequence <= flow->next_sequence
      ? OUTPUT_ACK_DUPLICATE
      : OUTPUT_ACK_INVALID_SEQUENCE;
  } else if (!requested->delivered) {
    result = OUTPUT_ACK_NOT_DELIVERED;
  } else if (requested != credit) {
    result = OUTPUT_ACK_OUT_OF_ORDER;
  } else if (requested->bytes != bytes) {
    result = OUTPUT_ACK_BYTES_MISMATCH;
  } else if (requested->acknowledged) {
    result = OUTPUT_ACK_DUPLICATE;
  } else if (bytes <= flow->outstanding_bytes && flow->outstanding_chunks > 0) {
    requested->acknowledged = true;
    flow->outstanding_bytes -= bytes;
    flow->outstanding_chunks--;
    flow->credit_head = requested->next;
    if (flow->credit_head != NULL) flow->credit_head->previous = NULL;
    else flow->credit_tail = NULL;
    free(requested);
    if (flow->paused &&
        flow->outstanding_bytes <= OUTPUT_LOW_BYTES &&
        flow->outstanding_chunks <= OUTPUT_LOW_CHUNKS) {
      uv_cond_broadcast(&flow->cond);
    }
    result = OUTPUT_ACK_ACCEPTED;
  } else {
    result = OUTPUT_ACK_INVALID_SEQUENCE;
  }
  output_flow_record_stats_locked(flow);
  uv_mutex_unlock(&flow->mutex);
  return result;
}

static bool output_flow_mark_delivered(output_flow_t* flow, uint64_t sequence) {
  if (flow == NULL) return false;
  uv_mutex_lock(&flow->mutex);
  output_credit_t* credit = flow->credit_head;
  while (credit != NULL && credit->sequence != sequence) credit = credit->next;
  bool delivered = !flow->cancelled && !flow->done && credit != NULL;
  if (delivered) credit->delivered = true;
  output_flow_record_stats_locked(flow);
  uv_mutex_unlock(&flow->mutex);
  return delivered;
}

static bool output_flow_is_cancelled(output_flow_t* flow);

static bool test_hold_output_delivery_if_armed(
    output_flow_t* flow, uint64_t sequence, size_t bytes) {
  if (!g_test_hooks) return true;
  bool cancelled = false;
  uv_mutex_lock(&g_test_output_mutex);
  if (g_test_hold_next_output_delivery) {
    g_test_hold_next_output_delivery = false;
    g_test_output_delivery_held = true;
    g_test_held_output_sequence = sequence;
    g_test_held_output_bytes = bytes;
    while (!g_test_release_output_delivery) {
      uv_mutex_unlock(&g_test_output_mutex);
      cancelled = output_flow_is_cancelled(flow);
      if (!cancelled) uv_sleep(1);
      uv_mutex_lock(&g_test_output_mutex);
      if (cancelled) break;
    }
    g_test_release_output_delivery = false;
    g_test_output_delivery_held = false;
    g_test_held_output_sequence = 0;
    g_test_held_output_bytes = 0;
  }
  uv_mutex_unlock(&g_test_output_mutex);
  return !cancelled && !output_flow_is_cancelled(flow);
}

// Enqueue/allocation rollback always targets the newest reservation because
// callbacks reserve and enqueue serially on the sole producer worker.
static void output_flow_rollback(
    output_flow_t* flow, uint64_t sequence, size_t bytes) {
  if (flow == NULL) return;
  uv_mutex_lock(&flow->mutex);
  output_credit_t* credit = flow->credit_tail;
  if (credit != NULL && credit->sequence == sequence && credit->bytes == bytes &&
      bytes <= flow->outstanding_bytes && flow->outstanding_chunks > 0) {
    flow->outstanding_bytes -= bytes;
    flow->outstanding_chunks--;
    flow->credit_tail = credit->previous;
    if (flow->credit_tail != NULL) flow->credit_tail->next = NULL;
    else flow->credit_head = NULL;
    free(credit);
  }
  uv_cond_broadcast(&flow->cond);
  output_flow_record_stats_locked(flow);
  uv_mutex_unlock(&flow->mutex);
}

static void output_flow_cancel_locked(output_flow_t* flow) {
  if (!flow->cancelled) {
    flow->cancelled = true;
    flow->paused = false;
    flow->outstanding_bytes = 0;
    flow->outstanding_chunks = 0;
    output_credit_t* credit = flow->credit_head;
    while (credit != NULL) {
      output_credit_t* next = credit->next;
      free(credit);
      credit = next;
    }
    flow->credit_head = NULL;
    flow->credit_tail = NULL;
  }
  uv_cond_broadcast(&flow->cond);
}

static void output_flow_cancel(output_flow_t* flow) {
  if (flow == NULL) return;
  uv_mutex_lock(&flow->mutex);
  output_flow_cancel_locked(flow);
  output_flow_record_stats_locked(flow);
  uv_mutex_unlock(&flow->mutex);
}

static void output_flow_cancel_if_running(output_flow_t* flow) {
  if (flow == NULL) return;
  uv_mutex_lock(&flow->mutex);
  if (!flow->done) output_flow_cancel_locked(flow);
  output_flow_record_stats_locked(flow);
  uv_mutex_unlock(&flow->mutex);
}

static bool output_flow_is_cancelled(output_flow_t* flow) {
  if (flow == NULL) return true;
  uv_mutex_lock(&flow->mutex);
  bool cancelled = flow->cancelled;
  uv_mutex_unlock(&flow->mutex);
  return cancelled;
}

static void output_flow_retain_thenable(
    output_flow_t* flow, napi_env env, napi_value controller) {
  if (flow == NULL || env == NULL) return;
  uv_mutex_lock(&flow->mutex);
  if (!flow->done && flow->thenable_ref == NULL) {
    napi_ref thenable_ref = NULL;
    if (napi_create_reference(env, controller, 1, &thenable_ref) == napi_ok) {
      flow->thenable_ref = thenable_ref;
    }
  }
  uv_mutex_unlock(&flow->mutex);
}

static bool output_flow_begin_settlement(output_flow_t* flow) {
  if (flow == NULL) return false;
  uv_mutex_lock(&flow->mutex);
  bool begin = !flow->settlement_started;
  if (begin) flow->settlement_started = true;
  uv_mutex_unlock(&flow->mutex);
  return begin;
}

static void output_flow_release_settlement_ref(output_flow_t* flow, napi_env env) {
  if (flow == NULL || env == NULL) return;
  uv_mutex_lock(&flow->mutex);
  napi_ref fallback_ref = flow->settlement_fallback_ref;
  flow->settlement_fallback_ref = NULL;
  uv_mutex_unlock(&flow->mutex);
  if (fallback_ref != NULL) napi_delete_reference(env, fallback_ref);
}

static void output_flow_mark_done(output_flow_t* flow, napi_env env) {
  if (flow == NULL) return;
  napi_ref thenable_ref = NULL;
  uv_mutex_lock(&flow->mutex);
  flow->done = true;
  flow->paused = false;
  thenable_ref = flow->thenable_ref;
  flow->thenable_ref = NULL;
  uv_cond_broadcast(&flow->cond);
  output_flow_record_stats_locked(flow);
  uv_mutex_unlock(&flow->mutex);
  // The reference is only created on this env's JS thread. env == NULL means
  // teardown owns automatic N-API reference reclamation.
  if (env != NULL && thenable_ref != NULL) napi_delete_reference(env, thenable_ref);
}

typedef struct output_controller {
  uv_mutex_t mutex;
  output_flow_t* flow;
  bool closed;
  uint64_t operation_id;
} output_controller_t;

static const napi_type_tag OUTPUT_CONTROLLER_TAG = {
  0x9d29436c7a6848e1ULL,
  0xa8e6af21e52d879bULL,
};

static void output_controller_cancel(output_controller_t* holder) {
  if (holder == NULL) return;
  uv_mutex_lock(&holder->mutex);
  output_flow_t* flow = holder->flow;
  if (flow != NULL) output_flow_retain(flow);
  uv_mutex_unlock(&holder->mutex);
  if (flow != NULL) {
    output_flow_cancel(flow);
    output_flow_release(flow, NULL);
  }
}

static void output_controller_close(output_controller_t* holder, napi_env env) {
  if (holder == NULL) return;
  uv_mutex_lock(&holder->mutex);
  output_flow_t* flow = NULL;
  if (!holder->closed) {
    holder->closed = true;
    flow = holder->flow;
    holder->flow = NULL;
  }
  uv_mutex_unlock(&holder->mutex);
  if (flow != NULL) {
    output_flow_cancel_if_running(flow);
    output_flow_release(flow, env);
  }
}

static void output_controller_finalize(napi_env env, void* data, void* hint) {
  (void)hint;
  output_controller_t* holder = (output_controller_t*)data;
  if (holder == NULL) return;
  output_controller_cancel(holder);
  output_controller_close(holder, env);
  uv_mutex_destroy(&holder->mutex);
  free(holder);
}

static output_controller_t* output_controller_unwrap(
    napi_env env, napi_callback_info info, size_t expected_argc, napi_value* argv) {
  napi_value this_arg;
  size_t actual = expected_argc;
  if (napi_get_cb_info(env, info, &actual, argv, &this_arg, NULL) != napi_ok) {
    napi_throw_type_error(env, NULL, "Invalid output controller invocation");
    return NULL;
  }
  if (actual < expected_argc) {
    napi_throw_type_error(env, NULL, "Missing output controller argument");
    return NULL;
  }
  bool tagged = false;
  if (napi_check_object_type_tag(env, this_arg, &OUTPUT_CONTROLLER_TAG, &tagged) != napi_ok ||
      !tagged) {
    napi_throw_type_error(env, NULL, "Invalid output controller receiver");
    return NULL;
  }
  output_controller_t* holder = NULL;
  if (napi_unwrap(env, this_arg, (void**)&holder) != napi_ok || holder == NULL) {
    napi_throw_type_error(env, NULL, "Invalid output controller receiver");
    return NULL;
  }
  return holder;
}

static napi_value napi_output_acknowledge(napi_env env, napi_callback_info info) {
  napi_value argv[2];
  output_controller_t* holder = output_controller_unwrap(env, info, 2, argv);
  if (holder == NULL) return NULL;
  napi_valuetype type;
  uint64_t sequence;
  double value;
  bool lossless = false;
  if (napi_typeof(env, argv[0], &type) != napi_ok || type != napi_bigint ||
      napi_get_value_bigint_uint64(env, argv[0], &sequence, &lossless) != napi_ok ||
      !lossless || sequence == 0) {
    napi_throw_range_error(env, NULL,
                           "acknowledge(sequence, bytes) requires a positive uint64 BigInt sequence");
    return NULL;
  }
  if (napi_typeof(env, argv[1], &type) != napi_ok || type != napi_number ||
      napi_get_value_double(env, argv[1], &value) != napi_ok ||
      value != value || value < 0 || value > 9007199254740991.0 ||
      value > (double)SIZE_MAX || value != (double)(size_t)value) {
    napi_throw_range_error(env, NULL,
                           "acknowledge(sequence, bytes) requires finite non-negative safe integer bytes");
    return NULL;
  }
  uv_mutex_lock(&holder->mutex);
  output_flow_t* flow = holder->flow;
  if (flow != NULL) output_flow_retain(flow);
  uv_mutex_unlock(&holder->mutex);
  if (flow != NULL) {
    output_ack_result_t result = output_flow_acknowledge(flow, sequence, (size_t)value);
    output_flow_release(flow, NULL);
    switch (result) {
      case OUTPUT_ACK_IGNORED:
      case OUTPUT_ACK_ACCEPTED:
        return NULL;
      case OUTPUT_ACK_INVALID_SEQUENCE:
        napi_throw_range_error(env, NULL, "Unknown output sequence");
        return NULL;
      case OUTPUT_ACK_NOT_DELIVERED:
        napi_throw_error(env, NULL, "Output sequence has not been delivered");
        return NULL;
      case OUTPUT_ACK_OUT_OF_ORDER:
        napi_throw_error(env, NULL, "Output acknowledgement is out of order");
        return NULL;
      case OUTPUT_ACK_BYTES_MISMATCH:
        napi_throw_range_error(env, NULL, "Output acknowledgement byte count does not match");
        return NULL;
      case OUTPUT_ACK_DUPLICATE:
        napi_throw_error(env, NULL, "Output sequence was already acknowledged");
        return NULL;
    }
  }
  return NULL;
}

static napi_value napi_output_cancel(napi_env env, napi_callback_info info) {
  output_controller_t* holder = output_controller_unwrap(env, info, 0, NULL);
  if (holder == NULL) return NULL;
  output_controller_cancel(holder);
  return NULL;
}

static napi_value napi_output_close(napi_env env, napi_callback_info info) {
  output_controller_t* holder = output_controller_unwrap(env, info, 0, NULL);
  if (holder == NULL) return NULL;
  output_controller_close(holder, env);
  return NULL;
}

static napi_value napi_output_promise_method(napi_env env, napi_callback_info info) {
  size_t argc = 2;
  napi_value argv[2];
  napi_value controller;
  void* data;
  if (napi_get_cb_info(env, info, &argc, argv, &controller, &data) != napi_ok) {
    napi_throw_type_error(env, NULL, "Invalid output controller promise method");
    return NULL;
  }
  bool tagged = false;
  if (napi_check_object_type_tag(env, controller, &OUTPUT_CONTROLLER_TAG, &tagged) != napi_ok ||
      !tagged) {
    napi_throw_type_error(env, NULL, "Invalid output controller receiver");
    return NULL;
  }
  const char* name = (const char*)data;
  napi_value completion;
  napi_value method;
  napi_value result;
  if (napi_get_named_property(env, controller, "completion", &completion) != napi_ok ||
      napi_get_named_property(env, completion, name, &method) != napi_ok ||
      napi_call_function(env, completion, method, argc, argv, &result) != napi_ok) {
    return NULL;
  }

  output_controller_t* holder = NULL;
  if (napi_unwrap(env, controller, (void**)&holder) != napi_ok || holder == NULL) {
    napi_throw_type_error(env, NULL, "Invalid output controller receiver");
    return NULL;
  }
  uv_mutex_lock(&holder->mutex);
  output_flow_t* flow = holder->flow;
  if (flow != NULL) output_flow_retain(flow);
  uv_mutex_unlock(&holder->mutex);
  if (flow != NULL) {
    // Retain only after the Promise method call succeeds. Failed assimilation
    // must not root a controller that no caller can use to close the flow.
    output_flow_retain_thenable(flow, env, controller);
    output_flow_release(flow, NULL);
  }
  return result;
}

static napi_value output_controller_create(
    napi_env env, napi_value completion, output_flow_t* flow) {
  output_controller_t* holder = (output_controller_t*)calloc(1, sizeof(output_controller_t));
  if (holder == NULL || uv_mutex_init(&holder->mutex) != 0) {
    free(holder);
    napi_throw_error(env, NULL, "OOM");
    return NULL;
  }
  holder->flow = flow;
  holder->operation_id = flow->operation_id;
  output_flow_retain(flow);  // JS ownership, released once by close/finalizer.

  napi_value controller;
  napi_value method;
  if (napi_create_object(env, &controller) != napi_ok ||
      napi_set_named_property(env, controller, "completion", completion) != napi_ok ||
      napi_create_function(env, "acknowledge", NAPI_AUTO_LENGTH,
                           napi_output_acknowledge, NULL, &method) != napi_ok ||
      napi_set_named_property(env, controller, "acknowledge", method) != napi_ok ||
      napi_create_function(env, "cancel", NAPI_AUTO_LENGTH,
                           napi_output_cancel, NULL, &method) != napi_ok ||
      napi_set_named_property(env, controller, "cancel", method) != napi_ok ||
      napi_create_function(env, "close", NAPI_AUTO_LENGTH,
                           napi_output_close, NULL, &method) != napi_ok ||
      napi_set_named_property(env, controller, "close", method) != napi_ok ||
      napi_create_function(env, "then", NAPI_AUTO_LENGTH,
                           napi_output_promise_method, (void*)"then", &method) != napi_ok ||
      napi_set_named_property(env, controller, "then", method) != napi_ok ||
      napi_create_function(env, "catch", NAPI_AUTO_LENGTH,
                           napi_output_promise_method, (void*)"catch", &method) != napi_ok ||
      napi_set_named_property(env, controller, "catch", method) != napi_ok ||
      napi_create_function(env, "finally", NAPI_AUTO_LENGTH,
                           napi_output_promise_method, (void*)"finally", &method) != napi_ok ||
      napi_set_named_property(env, controller, "finally", method) != napi_ok) {
    output_controller_cancel(holder);
    output_controller_close(holder, env);
    uv_mutex_destroy(&holder->mutex);
    free(holder);
    napi_throw_error(env, NULL, "Failed to create output controller");
    return NULL;
  }
  if (napi_wrap(env, controller, holder, output_controller_finalize, NULL, NULL) != napi_ok) {
    output_controller_cancel(holder);
    output_controller_close(holder, env);
    uv_mutex_destroy(&holder->mutex);
    free(holder);
    napi_throw_error(env, NULL, "Failed to wrap output controller");
    return NULL;
  }
  if (napi_type_tag_object(env, controller, &OUTPUT_CONTROLLER_TAG) != napi_ok) {
    void* removed = NULL;
    napi_remove_wrap(env, controller, &removed);
    output_controller_cancel(holder);
    output_controller_close(holder, env);
    uv_mutex_destroy(&holder->mutex);
    free(holder);
    napi_throw_error(env, NULL, "Failed to tag output controller");
    return NULL;
  }
  return controller;
}

// Round-9 (#2): static terminal-error JSON used when a worker thread cannot
// even strdup its result string (OOM). It is a file-scope constant, never
// heap-allocated, so any code path that would free a sentinel/chunk buffer
// must first check `buf != OOM_JSON` -- freeing a static pointer is UB. The
// wording matches the existing terse worker error style ("Empty response").
static const char OOM_JSON[] = "{\"success\":false,\"error\":\"Out of memory\"}";
static const char SETTLEMENT_ERROR_JSON[] =
  "{\"success\":false,\"error\":\"Failed to settle native output completion\"}";

static output_settlement_fault_t test_consume_output_settlement_fault(void) {
  if (!g_test_hooks) return OUTPUT_SETTLEMENT_FAULT_NONE;
  uv_mutex_lock(&g_test_output_mutex);
  output_settlement_fault_t fault = g_test_next_output_settlement_fault;
  g_test_next_output_settlement_fault = OUTPUT_SETTLEMENT_FAULT_NONE;
  uv_mutex_unlock(&g_test_output_mutex);
  return fault;
}

typedef enum {
  OUTPUT_EXCEPTION_CLEARED,
  OUTPUT_EXCEPTION_NOT_PENDING,
  OUTPUT_EXCEPTION_CLEAR_FAILED,
} output_exception_clear_result_t;

static output_exception_clear_result_t clear_pending_exception(napi_env env) {
  output_exception_clear_fault_t fault = OUTPUT_EXCEPTION_CLEAR_FAULT_NONE;
  if (g_test_hooks) {
    uv_mutex_lock(&g_test_output_mutex);
    fault = g_test_next_output_exception_clear_fault;
    g_test_next_output_exception_clear_fault = OUTPUT_EXCEPTION_CLEAR_FAULT_NONE;
    uv_mutex_unlock(&g_test_output_mutex);
  }
  bool pending = false;
  if (fault == OUTPUT_EXCEPTION_CLEAR_FAULT_IS_PENDING ||
      napi_is_exception_pending(env, &pending) != napi_ok) {
    return OUTPUT_EXCEPTION_CLEAR_FAILED;
  }
  if (!pending) {
    return OUTPUT_EXCEPTION_NOT_PENDING;
  }
  napi_value exception;
  if (fault == OUTPUT_EXCEPTION_CLEAR_FAULT_GET_AND_CLEAR ||
      napi_get_and_clear_last_exception(env, &exception) != napi_ok) {
    fprintf(stderr,
            "[DataWeave Node addon] Failed to clear an output settlement exception.\n");
    return OUTPUT_EXCEPTION_CLEAR_FAILED;
  }
  return OUTPUT_EXCEPTION_CLEARED;
}

static napi_status settle_output_fallback(
    napi_env env, napi_deferred deferred, napi_ref fallback_ref,
    bool* conclude_called) {
  *conclude_called = false;
  napi_value holder;
  napi_status status = napi_get_reference_value(env, fallback_ref, &holder);
  if (status != napi_ok) return status;
  napi_value fallback;
  status = napi_get_named_property(env, holder, "value", &fallback);
  if (status != napi_ok) return status;
  *conclude_called = true;
  return napi_resolve_deferred(env, deferred, fallback);
}

static void output_settlement_fail_closed(napi_status status) {
  // Node frees a deferred whenever napi_resolve_deferred/reject_deferred is
  // invoked, including failure returns. Continuing would either reuse freed
  // memory or leave callers waiting forever, so terminate deterministically.
  char message[128];
  int length = snprintf(
    message, sizeof(message),
    "Output completion settlement failed after deferred consumption (napi status %d)",
    (int)status
  );
  size_t message_length = length > 0
    ? ((size_t)length < sizeof(message) ? (size_t)length : sizeof(message) - 1)
    : 0;
  static const char location[] = "DataWeave Node addon";
  napi_fatal_error(
    location, sizeof(location) - 1, message, message_length
  );
}

static napi_status settle_output_deferred(
    napi_env env, napi_deferred deferred, output_flow_t* flow,
    const char* result_json) {
  if (env == NULL || flow == NULL || !output_flow_begin_settlement(flow)) {
    return napi_ok;
  }

  output_settlement_fault_t fault = test_consume_output_settlement_fault();
  napi_value result;
  bool fail_initial =
    fault == OUTPUT_SETTLEMENT_FAULT_INITIAL_CREATE_GENERIC ||
    fault == OUTPUT_SETTLEMENT_FAULT_FALLBACK_CALL_GENERIC ||
    fault == OUTPUT_SETTLEMENT_FAULT_FALLBACK_PENDING_EXCEPTION ||
    fault == OUTPUT_SETTLEMENT_FAULT_FALLBACK_CALL_GENERIC_AFTER_CALL;
  napi_status status = fail_initial
    ? napi_generic_failure
    : napi_create_string_utf8(env, result_json, strlen(result_json), &result);
  if (status == napi_ok) {
    if (fault == OUTPUT_SETTLEMENT_FAULT_INITIAL_PENDING_EXCEPTION) {
      napi_throw_error(env, NULL, "Injected initial output settlement exception");
      status = napi_pending_exception;
    } else {
      status = napi_resolve_deferred(env, deferred, result);
      if (status == napi_ok &&
          fault == OUTPUT_SETTLEMENT_FAULT_INITIAL_CALL_GENERIC_AFTER_CALL) {
        status = napi_generic_failure;
      } else if (status == napi_ok &&
                 fault == OUTPUT_SETTLEMENT_FAULT_INITIAL_CALL_PENDING_AFTER_CALL) {
        napi_throw_error(env, NULL, "Injected consumed output settlement exception");
        status = napi_pending_exception;
      }
      if (status != napi_ok) {
        if (status == napi_pending_exception) {
          output_exception_clear_result_t clear_result =
            clear_pending_exception(env);
          if (clear_result == OUTPUT_EXCEPTION_CLEAR_FAILED) {
            output_settlement_fail_closed(status);
          }
          if (clear_result == OUTPUT_EXCEPTION_NOT_PENDING) {
            output_flow_release_settlement_ref(flow, env);
            return status;
          }
        }
        output_settlement_fail_closed(status);
      }
      output_flow_release_settlement_ref(flow, env);
      return napi_ok;
    }
  }
  if (status == napi_pending_exception) {
    output_exception_clear_result_t clear_result = clear_pending_exception(env);
    if (clear_result == OUTPUT_EXCEPTION_CLEAR_FAILED) {
      output_settlement_fail_closed(status);
    }
    if (clear_result == OUTPUT_EXCEPTION_NOT_PENDING) {
      output_flow_release_settlement_ref(flow, env);
      return status;
    }
  }

  bool conclude_called = false;
  if (fault == OUTPUT_SETTLEMENT_FAULT_FALLBACK_PENDING_EXCEPTION) {
    napi_throw_error(env, NULL, "Injected fallback output settlement exception");
    status = napi_pending_exception;
  } else if (fault == OUTPUT_SETTLEMENT_FAULT_FALLBACK_CALL_GENERIC) {
    status = napi_generic_failure;
  } else {
    status = settle_output_fallback(
      env, deferred, flow->settlement_fallback_ref, &conclude_called
    );
  }
  if (status == napi_ok &&
      fault == OUTPUT_SETTLEMENT_FAULT_FALLBACK_CALL_GENERIC_AFTER_CALL) {
    status = napi_generic_failure;
  }
  if (status == napi_ok) {
    output_flow_release_settlement_ref(flow, env);
    return napi_ok;
  }
  if (status == napi_pending_exception) {
    output_exception_clear_result_t clear_result = clear_pending_exception(env);
    if (clear_result == OUTPUT_EXCEPTION_CLEAR_FAILED) {
      output_settlement_fail_closed(status);
    }
    if (clear_result == OUTPUT_EXCEPTION_NOT_PENDING) {
      output_flow_release_settlement_ref(flow, env);
      return status;
    }
  }
  if (conclude_called) output_settlement_fail_closed(status);

  status = settle_output_fallback(
    env, deferred, flow->settlement_fallback_ref, &conclude_called
  );
  if (status != napi_ok) {
    if (status == napi_pending_exception) {
      output_exception_clear_result_t clear_result = clear_pending_exception(env);
      if (clear_result == OUTPUT_EXCEPTION_CLEAR_FAILED) {
        output_settlement_fail_closed(status);
      }
      if (clear_result == OUTPUT_EXCEPTION_NOT_PENDING) {
        output_flow_release_settlement_ref(flow, env);
        return status;
      }
    }
    output_settlement_fail_closed(status);
  }
  output_flow_release_settlement_ref(flow, env);
  return napi_ok;
}

static bool prepare_output_settlement(napi_env env, output_flow_t* flow) {
  napi_value fallback;
  if (napi_create_string_utf8(
        env, SETTLEMENT_ERROR_JSON, NAPI_AUTO_LENGTH, &fallback) != napi_ok) {
    return false;
  }
  // N-API v8 cannot retain a primitive string directly, so keep it reachable
  // through a referenced object before the asynchronous operation starts.
  napi_value holder;
  if (napi_create_object(env, &holder) != napi_ok ||
      napi_set_named_property(env, holder, "value", fallback) != napi_ok) {
    return false;
  }
  return napi_create_reference(env, holder, 1, &flow->settlement_fallback_ref) == napi_ok;
}

// chunk_data with len == -1 is a sentinel indicating completion (buf holds meta JSON)
struct chunk_data {
  char* buf;
  int len;
  output_flow_t* flow;
  size_t accounted_bytes;
  uint64_t sequence;
};

struct streaming_work {
  uv_thread_t tid;
  napi_threadsafe_function tsfn;
  napi_deferred deferred;
  long long handle;
  char* script;
  char* inputs_json;
  // The engine's record whose in_flight count this op holds. Since round-9 (#1)
  // every engine has a record, so this is non-NULL for any known handle (NULL only
  // for an unknown handle). The completion sentinel calls bridge_end_op on it to
  // balance in_flight and run any deferred destroy (F1).
  engine_bridge_t* bridge;
  // review #10 (svacas P2): the completion sentinel, pre-allocated in the
  // synchronous setup path (napi_run_script_streaming_engine) so the worker's
  // terminal path is allocation-free and can ALWAYS enqueue completion. If it
  // were malloc'd on the worker instead, a NULL return there forced a return
  // WITHOUT enqueuing -- but the env is alive on OOM, so the promise would
  // never settle and the tsfn would never be released: a permanent hang.
  struct chunk_data* sentinel;
  output_flow_t* flow;
};

static void output_chunk_release(struct chunk_data* chunk, bool rollback) {
  if (chunk == NULL) return;
  if (chunk->flow != NULL) {
    if (rollback) {
      output_flow_rollback(chunk->flow, chunk->sequence, chunk->accounted_bytes);
    }
    output_flow_release(chunk->flow, NULL);
  }
  free(chunk->buf);
  free(chunk);
}

static void call_js_write(napi_env env, napi_value js_callback, void* context, void* data) {
  // data == NULL: nothing was queued, nothing to free or finalize.
  if (data == NULL) return;
  struct chunk_data* chunk = (struct chunk_data*)data;
  struct streaming_work* w = (struct streaming_work*)context;

  if (chunk->len == -1) {
    // Completion sentinel. env == NULL means the environment is tearing down
    // (e.g. a Worker terminating mid-op): we must not call any napi value or
    // JS-calling API (napi_create_string_utf8/napi_resolve_deferred need a
    // live env), but we must still perform every bit of native finalization
    // -- join the worker, release the tsfn, drop the bridge in-flight hold,
    // and free every heap field -- exactly once. Skipping this on env == NULL
    // would leak `w` and could strand a bridge marked for deferred destruction
    // indefinitely.
    if (env != NULL) {
      settle_output_deferred(env, w->deferred, w->flow, chunk->buf);
    }

    output_flow_mark_done(w->flow, env);
    if (chunk->buf != OOM_JSON) free(chunk->buf);
    free(chunk);
    free(w->script);
    free(w->inputs_json);

    uv_thread_join(&w->tid);
    napi_release_threadsafe_function(w->tsfn, napi_tsfn_release);
    // Drop the in-flight hold last, on this owner thread: if destroyEngine ran
    // during the op it deferred the free to here (F1). After this the bridge may
    // be freed, so touch nothing on it afterward. env == NULL means this env is
    // dead/tearing down -- tell bridge_end_op (and any bridge_finalize it
    // triggers) not to touch the napi_ref, since b->env is this same dead env.
    bridge_end_op(w->bridge, /*env_still_alive=*/env != NULL);
    output_flow_release(w->flow, NULL);
    free(w);
    return;
  }

  // Non-sentinel data chunk. If env == NULL the environment is gone and we
  // cannot deliver it to JS; free it and return without touching `w` (its
  // finalization happens only on the sentinel, above).
  if (env == NULL) {
    output_flow_cancel(chunk->flow);
    output_chunk_release(chunk, /*rollback=*/true);
    return;
  }
  // cancel() already released this payload's credit. Drop any TSFN payload
  // that was queued before cancellation instead of calling JavaScript again.
  if (output_flow_is_cancelled(chunk->flow)) {
    output_chunk_release(chunk, /*rollback=*/false);
    return;
  }

  napi_value buffer;
  napi_value sequence;
  void* buf_data;
  napi_status status = napi_create_buffer_copy(
    env, chunk->len, chunk->buf, &buf_data, &buffer
  );
  if (status == napi_ok) {
    status = napi_create_bigint_uint64(env, chunk->sequence, &sequence);
  }
  if (status == napi_ok) {
    napi_value global;
    status = napi_get_global(env, &global);
    if (status == napi_ok) {
      if (!output_flow_mark_delivered(chunk->flow, chunk->sequence)) {
        output_chunk_release(chunk, /*rollback=*/false);
        return;
      }
      native_callback_enter();
      napi_value argv[2] = {buffer, sequence};
      status = napi_call_function(env, global, js_callback, 2, argv, NULL);
      native_callback_exit();
    }
  }
  if (status != napi_ok) {
    output_flow_cancel(chunk->flow);
    if (status == napi_pending_exception) {
      napi_value exception;
      napi_get_and_clear_last_exception(env, &exception);
    }
  }
  output_chunk_release(chunk, /*rollback=*/status != napi_ok);
}

static int streaming_write_cb(void* ctx, const char* buf, int len) {
  struct streaming_work* w = (struct streaming_work*)ctx;
  if (len < 0 || output_flow_is_cancelled(w->flow)) return -1;
  uint64_t sequence;
  if (!output_flow_reserve(w->flow, (size_t)len, &sequence)) return -1;
  if (!test_hold_output_delivery_if_armed(w->flow, sequence, (size_t)len)) {
    return -1;
  }
  // Round-9 (#2): OOM here must not deref NULL / memcpy into NULL. Returning -1
  // aborts the native run cleanly (write-callback contract: non-zero stops the
  // DataWeave run); the worker then still produces a terminal meta_result and
  // sentinel, so the op resolves.
  struct chunk_data* chunk = malloc(sizeof(struct chunk_data));
  if (chunk == NULL) {
    output_flow_rollback(w->flow, sequence, (size_t)len);
    output_flow_cancel(w->flow);
    return -1;
  }
  chunk->buf = len == 0 ? NULL : malloc((size_t)len);
  if (len > 0 && chunk->buf == NULL) {
    free(chunk);
    output_flow_rollback(w->flow, sequence, (size_t)len);
    output_flow_cancel(w->flow);
    return -1;
  }
  if (len > 0) memcpy(chunk->buf, buf, (size_t)len);
  chunk->len = len;
  chunk->flow = w->flow;
  chunk->accounted_bytes = (size_t)len;
  chunk->sequence = sequence;
  output_flow_retain(w->flow);

  napi_status status = napi_call_threadsafe_function(
    w->tsfn, chunk, napi_tsfn_nonblocking
  );
  if (status != napi_ok) {
    output_chunk_release(chunk, /*rollback=*/true);
    output_flow_cancel(w->flow);
    return -1;
  }
  return 0;
}

static void streaming_thread_fn(void* arg) {
  struct streaming_work* w = (struct streaming_work*)arg;
  test_hold_async_op_if_armed();

  void* worker_thread = NULL;
  int rc = fn_attach_thread(g_isolate, &worker_thread);

  // Round-9 (#2): strdup can fail under OOM. meta_result must still be a valid
  // C string so the sentinel path below can deliver a terminal result -- fall
  // back to the OOM_JSON static (which must never be freed; see the guarded
  // frees below and in call_js_write).
  char* meta_result = NULL;
  if (rc != 0) {
    char err[256];
    snprintf(err, sizeof(err), "{\"success\":false,\"error\":\"Failed to attach thread (code %d)\"}", rc);
    meta_result = strdup(err);
    if (meta_result == NULL) meta_result = (char*)OOM_JSON;
  } else {
    void* result_ptr = fn_run_script_callback_engine(
      worker_thread, w->handle, w->script, w->inputs_json, streaming_write_cb, (void*)w
    );
    if (result_ptr) {
      meta_result = strdup((const char*)result_ptr);
      if (meta_result == NULL) meta_result = (char*)OOM_JSON;
      fn_free_cstring(worker_thread, result_ptr);
    } else {
      meta_result = strdup("{\"success\":false,\"error\":\"Empty response\"}");
      if (meta_result == NULL) meta_result = (char*)OOM_JSON;
    }
    detach_thread_checked(DETACH_SITE_STREAM_WORKER, worker_thread);
  }

  // Decrement here, once this thread has fully detached from the isolate --
  // not in call_js_write's completion branch. call_js_write only runs when
  // the JS thread's event loop turns, and napi_initialize's pending-teardown
  // wait (Task 3) can block that same event loop indefinitely; decrementing
  // from the JS-thread callback made the two waits circular. Decrementing
  // here ties g_active_ops to the actual invariant isolate teardown needs
  // (no GraalVM-attached thread remains), independent of the event loop.
  uv_mutex_lock(&g_mutex);
  g_active_ops--;
  uv_cond_broadcast(&g_teardown_cond);
  // Round-14 (#2/#3): if a prior last-release could not tear the isolate down
  // and left it stranded (g_teardown_needed), retry now that this op has drained.
  retry_stranded_teardown_locked();
  uv_mutex_unlock(&g_mutex);

  // Round-15 (svacas P1): op-completion drain point -- retry destroy for any
  // bridge stranded on a transient attach failure. Graal-only + free, no napi
  // env call, so it is safe on this background worker thread.
  drain_stranded_bridges();

  // review #10 (svacas P2): the completion sentinel was pre-allocated in the
  // synchronous setup path (napi_run_script_streaming_engine) and carried on
  // w->sentinel, so this terminal path is ALLOCATION-FREE and the completion
  // enqueue + tsfn release always run. The old code malloc'd the sentinel HERE
  // and, on NULL, freed w and returned WITHOUT enqueuing -- but the env is
  // alive on OOM (not the napi_closing case), so the promise never settled and
  // the tsfn was never released: a permanent hang. Pre-allocating removes that
  // failure mode entirely. (meta_result above uses the OOM_JSON static fallback
  // on strdup failure, so it is always a valid C string and never gates the
  // enqueue either.)
  struct chunk_data* sentinel = w->sentinel;
  sentinel->buf = meta_result;
  sentinel->len = -1;
  sentinel->flow = NULL;
  sentinel->accounted_bytes = 0;
  sentinel->sequence = 0;
  napi_status enq = napi_call_threadsafe_function(w->tsfn, sentinel, napi_tsfn_blocking);
  if (enq != napi_ok) {
    // The env is tearing down (napi_closing): the sentinel was dropped and
    // call_js_write will never run, so finalize here instead -- the exact same
    // native cleanup as call_js_write's sentinel branch, minus the things
    // that are illegal, impossible, or already done on this worker thread:
    //   - no napi value / deferred call (env is dead; those are env-affine)
    //   - no uv_thread_join(&w->tid): we ARE w->tid; a thread cannot join
    //     itself. The handle goes unreaped -- an unavoidable, negligible leak
    //     during a Worker teardown that is already discarding this env.
    //   - no napi_release_threadsafe_function(w->tsfn, ...): this tsfn was
    //     created with initial_thread_count = 1 and this worker is its sole
    //     producer, so Node's internal thread_count for it is exactly 1 on
    //     entry to this Push call. Node's ThreadSafeFunction::Push (the
    //     implementation behind napi_call_threadsafe_function) decrements
    //     thread_count for the calling thread BEFORE returning napi_closing,
    //     and -- if that decrement brings thread_count to 0 while the
    //     internal state is already kClosed -- Push runs `delete this` on
    //     the tsfn right there. So receiving napi_closing here already IS
    //     this thread's discharge of the tsfn (matches the doc's "destroyed
    //     when every thread ... has called napi_release_threadsafe_function()
    //     or has received a return status of napi_closing"); calling release
    //     again afterward would be a double-discharge and, whenever Push
    //     already deleted the object, a use-after-free. Omit it.
    // End the bridge op with env_still_alive=false so bridge_finalize skips
    // the thread-affine napi_delete_reference (Node auto-reclaims the ref
    // when the dead env is destroyed).
    if (sentinel->buf != OOM_JSON) free(sentinel->buf);
    free(sentinel);
    free(w->script);
    free(w->inputs_json);
    bridge_end_op(w->bridge, /*env_still_alive=*/false);
    output_flow_mark_done(w->flow, NULL);
    output_flow_release(w->flow, NULL);
    free(w);
  }
}

static napi_value napi_run_script_streaming_engine(napi_env env, napi_callback_info info) {
  if (native_callback_active()) return throw_callback_reentrancy(env);
  if (!g_initialized) {
    napi_throw_error(env, NULL, "Not initialized. Call initialize() first.");
    return NULL;
  }
  if (!fn_run_script_callback_engine) {
    napi_throw_error(env, NULL, "run_script_callback_engine not available in native library");
    return NULL;
  }

  size_t argc = 4;
  napi_value argv[4];
  napi_get_cb_info(env, info, &argc, argv, NULL, NULL);

  if (argc < 4) {
    napi_throw_error(env, NULL, "runScriptStreamingEngine requires (handle, script, inputsJson, chunkCallback)");
    return NULL;
  }

  // Validate the handle before admission (round-6 #1, defense-in-depth): a
  // non-integer handle must be rejected before g_active_ops is ever reserved,
  // so there is nothing to unwind here -- simpler than reserving first and
  // unwinding on failure.
  int64_t handle64;
  if (napi_get_value_int64(env, argv[0], &handle64) != napi_ok) {
    napi_throw_error(env, NULL, "runScriptStreamingEngine: handle must be an integer");
    return NULL;
  }

  // Atomic admission: check lifecycle state and reserve the op in ONE critical
  // section, before allocating any work/tsfn/promise/bridge. Reading
  // g_initialized outside the lock and reserving g_active_ops later (the old
  // shape) let a second Worker's napi_cleanup Case-4 tear the isolate down in
  // the gap, so a freshly spawned worker attached to a dead isolate (round-6
  // #2). Rejecting on g_teardown_state != TEARDOWN_NONE also refuses new ops
  // once a teardown is queued/underway. Admit an ADOPTED isolate:
  // napi_initialize's adoption branch sets g_teardown_cancelled = true on a
  // still-live PENDING_WAIT isolate but does not reset g_teardown_state (only
  // the async waiter does), so a merely-cancelled teardown must not reject
  // here -- otherwise a valid post-adoption op throws "Not initialized". A
  // genuine (non-cancelled) PENDING_WAIT or a committed TEARING_DOWN still
  // rejects.
  uv_mutex_lock(&g_mutex);
  wait_for_detach_publication_locked();
  if (g_isolate_poisoned) {
    uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, ISOLATE_POISONED_MESSAGE);
    return NULL;
  }
  if (!g_initialized || (g_teardown_state != TEARDOWN_NONE && !g_teardown_cancelled)) {
    uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "Not initialized. Call initialize() first.");
    return NULL;
  }
  g_active_ops++;
  // Round-11 (#2): pin the engine in the SAME critical section as the
  // g_active_ops reservation, before any window a concurrent destroyEngine
  // could use. NULL for an unknown handle (the worker surfaces "Unknown engine
  // handle"). Stashed on w->bridge once w is allocated; every early-return
  // below releases it via bridge_end_op alongside g_active_ops.
  engine_bridge_t* pinned = bridge_begin_op_locked((long long)handle64);
  uv_mutex_unlock(&g_mutex);

  // Conversions run after the admission reservation above, so any throw here
  // must release g_active_ops before returning (round-7 #2).
  size_t script_len, inputs_len;
  if (napi_get_value_string_utf8(env, argv[1], NULL, 0, &script_len) != napi_ok) {
    bridge_end_op(pinned, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "runScriptStreamingEngine: script must be a string");
    return NULL;
  }
  if (napi_get_value_string_utf8(env, argv[2], NULL, 0, &inputs_len) != napi_ok) {
    bridge_end_op(pinned, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "runScriptStreamingEngine: inputsJson must be a string");
    return NULL;
  }

  // OOM safety (round-8): every allocation is NULL-checked before it is
  // dereferenced, and every failure path releases the g_active_ops reservation
  // taken above (mirroring napi_run_script_engine's "OOM" throw). Without this
  // an allocation failure segfaults the host process AND strands g_active_ops.
  struct streaming_work* w = calloc(1, sizeof(struct streaming_work));
  if (w == NULL) {
    // w is NULL -- do not touch w->script/w->inputs_json here.
    bridge_end_op(pinned, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "OOM");
    return NULL;
  }
  w->handle = pinned != NULL ? pinned->native_handle : 0;
  w->script = malloc(script_len + 1);
  w->inputs_json = malloc(inputs_len + 1);
  if (w->script == NULL || w->inputs_json == NULL) {
    free(w->script); free(w->inputs_json); free(w);
    bridge_end_op(pinned, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "OOM");
    return NULL;
  }
  if (napi_get_value_string_utf8(env, argv[1], w->script, script_len + 1, NULL) != napi_ok ||
      napi_get_value_string_utf8(env, argv[2], w->inputs_json, inputs_len + 1, NULL) != napi_ok) {
    free(w->script); free(w->inputs_json); free(w);
    bridge_end_op(pinned, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "runScriptStreamingEngine: failed to read script/inputsJson");
    return NULL;
  }
  w->flow = output_flow_create();
  if (w->flow == NULL) {
    free(w->script); free(w->inputs_json); free(w);
    bridge_end_op(pinned, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "OOM");
    return NULL;
  }
  if (!prepare_output_settlement(env, w->flow)) {
    output_flow_release(w->flow, NULL);
    free(w->script); free(w->inputs_json); free(w);
    bridge_end_op(pinned, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "runScriptStreamingEngine: failed to create settlement fallback");
    return NULL;
  }

  // Round-9 (#3, updated round-11 #2): the resource creations below run AFTER
  // g_active_ops was reserved (and after w + its buffers were allocated), and
  // the engine pin (`pinned`) was already taken at admission. A failed create
  // must release both the pin (bridge_end_op) and g_active_ops (verbatim
  // pattern), free any tsfn already created, free w + buffers, and throw --
  // otherwise the worker sees a zeroed w->tsfn/w->deferred (crash), the pin is
  // stranded (blocks destroyEngine forever), or g_active_ops is stranded
  // (teardown wedge).
  napi_value resource_name;
  if (napi_create_string_utf8(env, "dwStreaming", NAPI_AUTO_LENGTH, &resource_name) != napi_ok) {
    output_flow_release(w->flow, env);
    free(w->script); free(w->inputs_json); free(w);
    bridge_end_op(pinned, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "runScriptStreamingEngine: failed to create resource name");
    return NULL;
  }
  if (napi_create_threadsafe_function(env, argv[3], NULL, resource_name,
                                      OUTPUT_TSFN_QUEUE_SIZE, 1, NULL, NULL,
                                      w, call_js_write, &w->tsfn) != napi_ok) {
    output_flow_release(w->flow, env);
    free(w->script); free(w->inputs_json); free(w);
    bridge_end_op(pinned, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "runScriptStreamingEngine: failed to create threadsafe function");
    return NULL;
  }

  // review #10 (svacas P2): pre-allocate the completion sentinel HERE, in the
  // synchronous setup path on the owner JS thread, before the worker is
  // spawned -- so the worker's terminal completion path is allocation-free and
  // can ALWAYS enqueue completion + release the tsfn. On NULL, unwind exactly
  // like the promise-creation path below (release the tsfn, which holds w as
  // its context; free w + buffers; release the pin and g_active_ops) and throw
  // synchronously. This mirrors napi_run_script_engine's "OOM" throw.
  w->sentinel = malloc(sizeof(struct chunk_data));
  if (w->sentinel == NULL) {
    napi_release_threadsafe_function(w->tsfn, napi_tsfn_release);
    output_flow_release(w->flow, env);
    free(w->script); free(w->inputs_json); free(w);
    bridge_end_op(pinned, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "OOM");
    return NULL;
  }

  napi_value promise;
  if (napi_create_promise(env, &w->deferred, &promise) != napi_ok) {
    // The tsfn was created above; release it before freeing w (it holds w as
    // its context). No worker exists yet, so this release is the sole discharge.
    napi_release_threadsafe_function(w->tsfn, napi_tsfn_release);
    output_flow_release(w->flow, env);
    free(w->sentinel); free(w->script); free(w->inputs_json); free(w);
    bridge_end_op(pinned, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "runScriptStreamingEngine: failed to create promise");
    return NULL;
  }

  napi_value controller = output_controller_create(env, promise, w->flow);
  if (controller == NULL) {
    napi_release_threadsafe_function(w->tsfn, napi_tsfn_release);
    output_flow_release(w->flow, env);
    free(w->sentinel); free(w->script); free(w->inputs_json); free(w);
    bridge_end_op(pinned, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    return NULL;
  }

  // Round-11 (#2): the pin was taken at admission (bridge_begin_op_locked) in
  // the same critical section as g_active_ops, so a concurrent destroyEngine
  // could never free this bridge under the admitted op. Just record it on w;
  // the completion sentinel releases it via bridge_end_op. NULL for a
  // resolver-less/unknown engine, handled everywhere as a no-op.
  w->bridge = pinned;

  uv_thread_options_t opts;
  opts.flags = UV_THREAD_HAS_STACK_SIZE;
  opts.stack_size = 2 * 1024 * 1024;
  int spawn_rc = uv_thread_create_ex(&w->tid, &opts, streaming_thread_fn, w);

  if (spawn_rc != 0) {
    // The worker never ran, so nothing will ever decrement g_active_ops,
    // release the bridge hold, or resolve the promise -- unwind everything
    // committed above ourselves, in reverse order, mirroring call_js_write's
    // completion branch (minus uv_thread_join: there is no thread to join).
    uv_mutex_lock(&g_mutex);
    g_active_ops--;
    uv_cond_broadcast(&g_teardown_cond);
    uv_mutex_unlock(&g_mutex);

    // Synchronous call on the JS thread -- env is live here.
    bridge_end_op(w->bridge, /*env_still_alive=*/true);
    napi_release_threadsafe_function(w->tsfn, napi_tsfn_release);

    settle_output_deferred(
      env, w->deferred, w->flow,
      "{\"success\":false,\"error\":\"Failed to spawn streaming worker thread\"}"
    );

    free(w->sentinel);
    free(w->script);
    free(w->inputs_json);
    output_flow_mark_done(w->flow, env);
    output_flow_release(w->flow, env);
    free(w);
  }

  return controller;
}

// --- Bidirectional streaming ---

struct transform_work {
  uv_thread_t tid;
  napi_threadsafe_function read_tsfn;
  napi_threadsafe_function write_tsfn;
  napi_deferred deferred;
  long long handle;
  char* script;
  char* inputs_json;
  char* input_name;
  char* input_mime_type;
  char* input_charset;
  // The engine's record whose in_flight count this op holds. Since round-9 (#1)
  // every engine has a record, so this is non-NULL for any known handle (NULL only
  // for an unknown handle). The completion sentinel calls bridge_end_op on it to
  // balance in_flight and run any deferred destroy (F1).
  engine_bridge_t* bridge;
  // review #10 (svacas P2): the completion sentinel, pre-allocated in the
  // synchronous setup path (napi_run_script_transform_engine) so the worker's
  // terminal path is allocation-free and can ALWAYS enqueue completion. See
  // the same field on struct streaming_work for the hang this prevents.
  struct chunk_data* sentinel;
  output_flow_t* flow;
};

struct read_request {
  char* buffer;
  int buffer_size;
  int bytes_read;
  uv_mutex_t mutex;
  uv_cond_t cond;
  int ready;
};

static void call_js_read(napi_env env, napi_value js_callback, void* context, void* data) {
  if (data == NULL) return;  // nothing to signal
  struct read_request* req = (struct read_request*)data;

  if (env == NULL) {
    // N-API can invoke a threadsafe-function callback with env == NULL when
    // the environment is tearing down with items still queued (e.g. a Worker
    // terminating mid-transform). transform_read_cb is synchronously blocked
    // on req->cond waiting for this callback to signal it -- unlike
    // call_js_write/call_js_transform_write, there is no sentinel-driven path
    // that would otherwise unblock it. Treat this as a terminal read error so
    // the blocked thread wakes up, detects the failure via bytes_read == -1,
    // and the worker can detach from the isolate instead of hanging forever.
    req->bytes_read = -1;
  } else {
    napi_value buf_size_val;
    napi_create_int32(env, req->buffer_size, &buf_size_val);

    napi_value global;
    napi_get_global(env, &global);

    napi_value result;
    native_callback_enter();
    napi_status status = napi_call_function(env, global, js_callback, 1, &buf_size_val, &result);
    native_callback_exit();

    if (status == napi_ok && result != NULL) {
      bool is_buffer;
      napi_is_buffer(env, result, &is_buffer);
      if (is_buffer) {
        void* buf_data;
        size_t buf_len;
        napi_get_buffer_info(env, result, &buf_data, &buf_len);
        int n = (int)buf_len < req->buffer_size ? (int)buf_len : req->buffer_size;
        if (n > 0) memcpy(req->buffer, buf_data, n);
        req->bytes_read = n;
      } else {
        req->bytes_read = 0;
      }
    } else {
      // Clear pending exception to prevent propagation
      if (status == napi_pending_exception) {
        napi_value exception;
        napi_get_and_clear_last_exception(env, &exception);

        // Extract and log exception details before discarding
        napi_value message_prop, stack_prop;
        char message_buf[512] = {0};
        char stack_buf[2048] = {0};
        size_t message_len = 0, stack_len = 0;

        // Try to get the message property
        if (napi_get_named_property(env, exception, "message", &message_prop) == napi_ok) {
          napi_get_value_string_utf8(env, message_prop, message_buf, sizeof(message_buf), &message_len);
        }

        // Try to get the stack property
        if (napi_get_named_property(env, exception, "stack", &stack_prop) == napi_ok) {
          napi_get_value_string_utf8(env, stack_prop, stack_buf, sizeof(stack_buf), &stack_len);
        }

        // Log the exception to stderr for diagnostics
        fprintf(stderr, "[DataWeave Node addon] Read callback threw exception:\n");
        if (message_len > 0) {
          fprintf(stderr, "  Message: %s\n", message_buf);
        }
        if (stack_len > 0) {
          fprintf(stderr, "  Stack:\n%s\n", stack_buf);
        }
        if (message_len == 0 && stack_len == 0) {
          fprintf(stderr, "  (Unable to extract exception details)\n");
        }
      }
      req->bytes_read = -1;  // Signal error
    }
  }

  uv_mutex_lock(&req->mutex);
  req->ready = 1;
  uv_cond_signal(&req->cond);
  uv_mutex_unlock(&req->mutex);
}

static int transform_read_cb(void* ctx, char* buf, int buf_size) {
  struct transform_work* w = (struct transform_work*)ctx;

  struct read_request req;
  req.buffer = buf;
  req.buffer_size = buf_size;
  req.bytes_read = 0;
  req.ready = 0;
  uv_mutex_init(&req.mutex);
  uv_cond_init(&req.cond);

  napi_status status = napi_call_threadsafe_function(w->read_tsfn, &req, napi_tsfn_blocking);
  if (status != napi_ok) {
    uv_mutex_destroy(&req.mutex);
    uv_cond_destroy(&req.cond);
    return -1;
  }

  uv_mutex_lock(&req.mutex);
  while (!req.ready) {
    uv_cond_wait(&req.cond, &req.mutex);
  }
  uv_mutex_unlock(&req.mutex);

  int n = req.bytes_read;
  uv_mutex_destroy(&req.mutex);
  uv_cond_destroy(&req.cond);
  return n;
}

static int transform_write_cb(void* ctx, const char* buf, int len) {
  struct transform_work* w = (struct transform_work*)ctx;
  if (len < 0 || output_flow_is_cancelled(w->flow)) return -1;
  uint64_t sequence;
  if (!output_flow_reserve(w->flow, (size_t)len, &sequence)) return -1;
  if (!test_hold_output_delivery_if_armed(w->flow, sequence, (size_t)len)) {
    return -1;
  }
  // Round-9 (#2): OOM-safe, mirrors streaming_write_cb. Return -1 to abort the
  // native run cleanly; the worker still delivers a terminal sentinel.
  struct chunk_data* chunk = malloc(sizeof(struct chunk_data));
  if (chunk == NULL) {
    output_flow_rollback(w->flow, sequence, (size_t)len);
    output_flow_cancel(w->flow);
    return -1;
  }
  chunk->buf = len == 0 ? NULL : malloc((size_t)len);
  if (len > 0 && chunk->buf == NULL) {
    free(chunk);
    output_flow_rollback(w->flow, sequence, (size_t)len);
    output_flow_cancel(w->flow);
    return -1;
  }
  if (len > 0) memcpy(chunk->buf, buf, (size_t)len);
  chunk->len = len;
  chunk->flow = w->flow;
  chunk->accounted_bytes = (size_t)len;
  chunk->sequence = sequence;
  output_flow_retain(w->flow);

  napi_status status = napi_call_threadsafe_function(
    w->write_tsfn, chunk, napi_tsfn_nonblocking
  );
  if (status != napi_ok) {
    output_chunk_release(chunk, /*rollback=*/true);
    output_flow_cancel(w->flow);
    return -1;
  }
  return 0;
}

static void call_js_transform_write(napi_env env, napi_value js_callback, void* context, void* data) {
  // data == NULL: nothing was queued, nothing to free or finalize.
  if (data == NULL) return;
  struct chunk_data* chunk = (struct chunk_data*)data;
  struct transform_work* w = (struct transform_work*)context;

  if (chunk->len == -1) {
    // Completion sentinel. env == NULL means the environment is tearing down
    // (e.g. a Worker terminating mid-op): we must not call any napi value or
    // JS-calling API (napi_create_string_utf8/napi_resolve_deferred need a
    // live env), but we must still perform every bit of native finalization
    // -- join the worker, release both tsfns, drop the bridge in-flight hold,
    // and free every heap field -- exactly once. Skipping this on env == NULL
    // would leak `w` and could strand a bridge marked for deferred destruction
    // indefinitely.
    if (env != NULL) {
      settle_output_deferred(env, w->deferred, w->flow, chunk->buf);
    }

    output_flow_mark_done(w->flow, env);
    if (chunk->buf != OOM_JSON) free(chunk->buf);
    free(chunk);
    free(w->script);
    free(w->inputs_json);
    free(w->input_name);
    free(w->input_mime_type);
    free(w->input_charset);

    uv_thread_join(&w->tid);
    napi_release_threadsafe_function(w->read_tsfn, napi_tsfn_release);
    napi_release_threadsafe_function(w->write_tsfn, napi_tsfn_release);
    // Drop the in-flight hold last, on this owner thread: if destroyEngine ran
    // during the op it deferred the free to here (F1). After this the bridge may
    // be freed, so touch nothing on it afterward. env == NULL means this env is
    // dead/tearing down -- tell bridge_end_op (and any bridge_finalize it
    // triggers) not to touch the napi_ref, since b->env is this same dead env.
    bridge_end_op(w->bridge, /*env_still_alive=*/env != NULL);
    output_flow_release(w->flow, NULL);
    free(w);
    return;
  }

  // Non-sentinel data chunk. If env == NULL the environment is gone and we
  // cannot deliver it to JS; free it and return without touching `w` (its
  // finalization happens only on the sentinel, above).
  if (env == NULL) {
    output_flow_cancel(chunk->flow);
    output_chunk_release(chunk, /*rollback=*/true);
    return;
  }
  // cancel() already released this payload's credit. Drop any TSFN payload
  // that was queued before cancellation instead of calling JavaScript again.
  if (output_flow_is_cancelled(chunk->flow)) {
    output_chunk_release(chunk, /*rollback=*/false);
    return;
  }

  napi_value buffer;
  napi_value sequence;
  void* buf_data;
  napi_status status = napi_create_buffer_copy(
    env, chunk->len, chunk->buf, &buf_data, &buffer
  );
  if (status == napi_ok) {
    status = napi_create_bigint_uint64(env, chunk->sequence, &sequence);
  }
  if (status == napi_ok) {
    napi_value global;
    status = napi_get_global(env, &global);
    if (status == napi_ok) {
      if (!output_flow_mark_delivered(chunk->flow, chunk->sequence)) {
        output_chunk_release(chunk, /*rollback=*/false);
        return;
      }
      native_callback_enter();
      napi_value argv[2] = {buffer, sequence};
      status = napi_call_function(env, global, js_callback, 2, argv, NULL);
      native_callback_exit();
    }
  }
  if (status != napi_ok) {
    output_flow_cancel(chunk->flow);
    if (status == napi_pending_exception) {
      napi_value exception;
      napi_get_and_clear_last_exception(env, &exception);
    }
  }
  output_chunk_release(chunk, /*rollback=*/status != napi_ok);
}

static void transform_thread_fn(void* arg) {
  struct transform_work* w = (struct transform_work*)arg;
  test_hold_async_op_if_armed();

  void* worker_thread = NULL;
  int rc = fn_attach_thread(g_isolate, &worker_thread);

  // Round-9 (#2): strdup can fail under OOM; fall back to the OOM_JSON static
  // so the sentinel below still delivers a terminal result. Mirrors
  // streaming_thread_fn.
  char* meta_result = NULL;
  if (rc != 0) {
    char err[256];
    snprintf(err, sizeof(err), "{\"success\":false,\"error\":\"Failed to attach thread (code %d)\"}", rc);
    meta_result = strdup(err);
    if (meta_result == NULL) meta_result = (char*)OOM_JSON;
  } else {
    void* result_ptr = fn_run_script_input_output_callback_engine(
      worker_thread, w->handle, w->script, w->inputs_json,
      w->input_name, w->input_mime_type, w->input_charset,
      transform_read_cb, transform_write_cb, (void*)w
    );

    if (result_ptr) {
      meta_result = strdup((const char*)result_ptr);
      if (meta_result == NULL) meta_result = (char*)OOM_JSON;
      fn_free_cstring(worker_thread, result_ptr);
    } else {
      meta_result = strdup("{\"success\":false,\"error\":\"Empty response\"}");
      if (meta_result == NULL) meta_result = (char*)OOM_JSON;
    }
    detach_thread_checked(DETACH_SITE_TRANSFORM_WORKER, worker_thread);
  }

  // See streaming_thread_fn's comment: decrement here (after detach), not in
  // call_js_transform_write's completion branch, to avoid the same
  // circular-wait deadlock against napi_initialize's pending-teardown wait.
  uv_mutex_lock(&g_mutex);
  g_active_ops--;
  uv_cond_broadcast(&g_teardown_cond);
  // Round-14 (#2/#3): retry a stranded teardown now that this op has drained.
  retry_stranded_teardown_locked();
  uv_mutex_unlock(&g_mutex);

  // Round-15 (svacas P1): op-completion drain point -- retry destroy for any
  // bridge stranded on a transient attach failure. Graal-only + free, no napi
  // env call, so it is safe on this background worker thread.
  drain_stranded_bridges();

  // review #10 (svacas P2): the completion sentinel was pre-allocated in the
  // synchronous setup path (napi_run_script_transform_engine) and carried on
  // w->sentinel, so this terminal path is ALLOCATION-FREE and the completion
  // enqueue + tsfn release always run. The old code malloc'd the sentinel HERE
  // and, on NULL, freed w and returned WITHOUT enqueuing -- but the env is
  // alive on OOM (not the napi_closing case), so the promise never settled and
  // the tsfn was never released: a permanent hang. Removing the allocation
  // (rather than releasing the tsfn here, which the enq-failure branch below
  // documents as unsafe) is what makes the enqueue unconditional. (meta_result
  // above uses the OOM_JSON static fallback on strdup failure, so it is always
  // a valid C string and never gates the enqueue either.)
  struct chunk_data* sentinel = w->sentinel;
  sentinel->buf = meta_result;
  sentinel->len = -1;
  sentinel->flow = NULL;
  sentinel->accounted_bytes = 0;
  sentinel->sequence = 0;
  napi_status enq = napi_call_threadsafe_function(w->write_tsfn, sentinel, napi_tsfn_blocking);
  if (enq != napi_ok) {
    // See streaming_thread_fn: env tearing down, sentinel dropped, finalize
    // here. No self-join, no env-affine napi call.
    //
    // Do NOT release write_tsfn: this worker is its sole producer
    // (initial_thread_count = 1), so receiving napi_closing from this same
    // Push call already decremented Node's internal thread_count for it to 0
    // and, if the tsfn's internal state was already kClosed, already ran
    // `delete this` on it inside Push -- see streaming_thread_fn's comment
    // for the full citation. Releasing it again here would be a
    // double-discharge and potentially a use-after-free.
    //
    // Do NOT release read_tsfn either, even though this same worker is also
    // its sole producer: whether *it* has already received napi_closing (and
    // so already discharged/deleted itself the same way) depends on whether
    // the script issued reads during teardown, which this code path has no
    // way to know. We cannot prove read_tsfn's discharge state here, so --
    // consistent with the env == NULL dead-env handling elsewhere in this
    // file -- we accept the small leak of an already-tearing-down tsfn
    // rather than risk a use-after-free on an object whose state is unknown.
    //
    // End the bridge op with env_still_alive=false so bridge_finalize skips
    // the thread-affine napi_delete_reference (Node auto-reclaims the ref
    // when the dead env is destroyed).
    if (sentinel->buf != OOM_JSON) free(sentinel->buf);
    free(sentinel);
    free(w->script);
    free(w->inputs_json);
    free(w->input_name);
    free(w->input_mime_type);
    free(w->input_charset);
    bridge_end_op(w->bridge, /*env_still_alive=*/false);
    output_flow_mark_done(w->flow, NULL);
    output_flow_release(w->flow, NULL);
    free(w);
  }
}

static napi_value napi_run_script_transform_engine(napi_env env, napi_callback_info info) {
  if (native_callback_active()) return throw_callback_reentrancy(env);
  if (!g_initialized) {
    napi_throw_error(env, NULL, "Not initialized. Call initialize() first.");
    return NULL;
  }
  if (!fn_run_script_input_output_callback_engine) {
    napi_throw_error(env, NULL, "run_script_input_output_callback_engine not available in native library");
    return NULL;
  }

  size_t argc = 8;
  napi_value argv[8];
  napi_get_cb_info(env, info, &argc, argv, NULL, NULL);

  if (argc < 8) {
    napi_throw_error(env, NULL, "runScriptTransformEngine requires 8 arguments");
    return NULL;
  }

  // Validate the handle before admission (round-6 #1, defense-in-depth): a
  // non-integer handle must be rejected before g_active_ops is ever reserved,
  // so there is nothing to unwind here -- simpler than reserving first and
  // unwinding on failure. Keep this consistent with
  // napi_run_script_streaming_engine's ordering.
  int64_t handle64;
  if (napi_get_value_int64(env, argv[0], &handle64) != napi_ok) {
    napi_throw_error(env, NULL, "runScriptTransformEngine: handle must be an integer");
    return NULL;
  }

  // Atomic admission (see napi_run_script_streaming_engine for the full
  // rationale, round-6 #2): check lifecycle + reserve g_active_ops in one
  // critical section, before any work/tsfn/promise/bridge is committed.
  // Admit an ADOPTED isolate: napi_initialize's adoption branch sets
  // g_teardown_cancelled = true on a still-live PENDING_WAIT isolate but does
  // not reset g_teardown_state (only the async waiter does), so a
  // merely-cancelled teardown must not reject here -- otherwise a valid
  // post-adoption op throws "Not initialized". A genuine (non-cancelled)
  // PENDING_WAIT or a committed TEARING_DOWN still rejects.
  uv_mutex_lock(&g_mutex);
  wait_for_detach_publication_locked();
  if (g_isolate_poisoned) {
    uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, ISOLATE_POISONED_MESSAGE);
    return NULL;
  }
  if (!g_initialized || (g_teardown_state != TEARDOWN_NONE && !g_teardown_cancelled)) {
    uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "Not initialized. Call initialize() first.");
    return NULL;
  }
  g_active_ops++;
  // Round-11 (#2): pin the engine in the SAME critical section as the
  // g_active_ops reservation, before any window a concurrent destroyEngine
  // could use. NULL for an unknown handle (the worker surfaces "Unknown engine
  // handle"). Stashed on w->bridge once w is allocated; every early-return
  // below releases it via bridge_end_op alongside g_active_ops.
  engine_bridge_t* pinned = bridge_begin_op_locked((long long)handle64);
  uv_mutex_unlock(&g_mutex);

  // Conversions run after the admission reservation above, so any throw here
  // must free the partially-populated work struct AND release g_active_ops
  // before returning (round-7 #2). calloc zeroed w, so free() on an unset
  // field pointer is a safe free(NULL). TRANSFORM_FAIL centralizes the
  // unwind.
  // OOM safety (round-8): NULL-check the work struct before dereferencing it,
  // releasing the g_active_ops reservation taken above. The per-field malloc
  // checks below reuse TRANSFORM_FAIL (which frees all fields + w and unwinds);
  // this standalone branch cannot use it (the macro dereferences w).
  struct transform_work* w = calloc(1, sizeof(struct transform_work));
  if (w == NULL) {
    bridge_end_op(pinned, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "OOM");
    return NULL;
  }
  size_t len;
  w->handle = pinned != NULL ? pinned->native_handle : 0;

  #define TRANSFORM_FAIL(msg) do { \
      bridge_end_op(pinned, /*env_still_alive=*/true); \
      free(w->script); free(w->inputs_json); free(w->input_name); \
      free(w->input_mime_type); free(w->input_charset); free(w); \
      uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex); \
      napi_throw_error(env, NULL, (msg)); \
      return NULL; \
  } while (0)

  if (napi_get_value_string_utf8(env, argv[1], NULL, 0, &len) != napi_ok) TRANSFORM_FAIL("runScriptTransformEngine: script must be a string");
  w->script = malloc(len + 1);
  if (w->script == NULL) TRANSFORM_FAIL("OOM");
  if (napi_get_value_string_utf8(env, argv[1], w->script, len + 1, NULL) != napi_ok) TRANSFORM_FAIL("runScriptTransformEngine: failed to read script");

  if (napi_get_value_string_utf8(env, argv[2], NULL, 0, &len) != napi_ok) TRANSFORM_FAIL("runScriptTransformEngine: inputsJson must be a string");
  w->inputs_json = malloc(len + 1);
  if (w->inputs_json == NULL) TRANSFORM_FAIL("OOM");
  if (napi_get_value_string_utf8(env, argv[2], w->inputs_json, len + 1, NULL) != napi_ok) TRANSFORM_FAIL("runScriptTransformEngine: failed to read inputsJson");

  if (napi_get_value_string_utf8(env, argv[3], NULL, 0, &len) != napi_ok) TRANSFORM_FAIL("runScriptTransformEngine: inputName must be a string");
  w->input_name = malloc(len + 1);
  if (w->input_name == NULL) TRANSFORM_FAIL("OOM");
  if (napi_get_value_string_utf8(env, argv[3], w->input_name, len + 1, NULL) != napi_ok) TRANSFORM_FAIL("runScriptTransformEngine: failed to read inputName");

  if (napi_get_value_string_utf8(env, argv[4], NULL, 0, &len) != napi_ok) TRANSFORM_FAIL("runScriptTransformEngine: inputMimeType must be a string");
  w->input_mime_type = malloc(len + 1);
  if (w->input_mime_type == NULL) TRANSFORM_FAIL("OOM");
  if (napi_get_value_string_utf8(env, argv[4], w->input_mime_type, len + 1, NULL) != napi_ok) TRANSFORM_FAIL("runScriptTransformEngine: failed to read inputMimeType");

  napi_valuetype type;
  if (napi_typeof(env, argv[5], &type) != napi_ok) TRANSFORM_FAIL("runScriptTransformEngine: invalid inputCharset argument");
  if (type == napi_string) {
    if (napi_get_value_string_utf8(env, argv[5], NULL, 0, &len) != napi_ok) TRANSFORM_FAIL("runScriptTransformEngine: inputCharset must be a string");
    w->input_charset = malloc(len + 1);
    if (w->input_charset == NULL) TRANSFORM_FAIL("OOM");
    if (napi_get_value_string_utf8(env, argv[5], w->input_charset, len + 1, NULL) != napi_ok) TRANSFORM_FAIL("runScriptTransformEngine: failed to read inputCharset");
  } else if (type == napi_null || type == napi_undefined) {
    // inputCharset is nullable: null/undefined mean "no charset". This is the
    // only non-string form the JS binding ever sends (dataweave.ts normalizes
    // opts?.charset ?? null).
    w->input_charset = NULL;
  } else {
    // Any other type (object, number, boolean, ...) is a caller error, not
    // "no charset". Fail closed like the four non-nullable string args above
    // rather than silently coercing to NULL (review #9 #6).
    TRANSFORM_FAIL("runScriptTransformEngine: inputCharset must be a string, null, or undefined");
  }
  #undef TRANSFORM_FAIL
  w->flow = output_flow_create();
  if (w->flow == NULL) {
    free(w->script); free(w->inputs_json); free(w->input_name);
    free(w->input_mime_type); free(w->input_charset); free(w);
    bridge_end_op(pinned, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "OOM");
    return NULL;
  }
  if (!prepare_output_settlement(env, w->flow)) {
    output_flow_release(w->flow, env);
    free(w->script); free(w->inputs_json); free(w->input_name);
    free(w->input_mime_type); free(w->input_charset); free(w);
    bridge_end_op(pinned, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "runScriptTransformEngine: failed to create settlement fallback");
    return NULL;
  }

  // Round-9 (#3, updated round-11 #2): check each resource creation; on
  // failure release the engine pin (`pinned`, taken at admission) via
  // bridge_end_op, release g_active_ops (verbatim), release any tsfn already
  // created, free w + all five string buffers, and throw. read_tsfn has no
  // context (NULL); write_tsfn holds w as context, so release write_tsfn
  // before freeing w if it was created.
  napi_value resource_name;
  if (napi_create_string_utf8(env, "dwTransform", NAPI_AUTO_LENGTH, &resource_name) != napi_ok) {
    output_flow_release(w->flow, env);
    free(w->script); free(w->inputs_json); free(w->input_name); free(w->input_mime_type); free(w->input_charset); free(w);
    bridge_end_op(pinned, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "runScriptTransformEngine: failed to create resource name");
    return NULL;
  }

  if (napi_create_threadsafe_function(env, argv[6], NULL, resource_name, 0, 1, NULL, NULL, NULL, call_js_read, &w->read_tsfn) != napi_ok) {
    output_flow_release(w->flow, env);
    free(w->script); free(w->inputs_json); free(w->input_name); free(w->input_mime_type); free(w->input_charset); free(w);
    bridge_end_op(pinned, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "runScriptTransformEngine: failed to create read threadsafe function");
    return NULL;
  }
  if (napi_create_threadsafe_function(env, argv[7], NULL, resource_name,
                                      OUTPUT_TSFN_QUEUE_SIZE, 1, NULL, NULL,
                                      w, call_js_transform_write, &w->write_tsfn) != napi_ok) {
    napi_release_threadsafe_function(w->read_tsfn, napi_tsfn_release);
    output_flow_release(w->flow, env);
    free(w->script); free(w->inputs_json); free(w->input_name); free(w->input_mime_type); free(w->input_charset); free(w);
    bridge_end_op(pinned, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "runScriptTransformEngine: failed to create write threadsafe function");
    return NULL;
  }

  // review #10 (svacas P2): pre-allocate the completion sentinel HERE, in the
  // synchronous setup path on the owner JS thread, before the worker is
  // spawned -- so the worker's terminal completion path is allocation-free and
  // can ALWAYS enqueue completion + release the tsfn. On NULL, unwind exactly
  // like the promise-creation path below (release both tsfns; free w + all five
  // string buffers; release the pin and g_active_ops) and throw synchronously.
  // This mirrors napi_run_script_engine's "OOM" throw.
  w->sentinel = malloc(sizeof(struct chunk_data));
  if (w->sentinel == NULL) {
    napi_release_threadsafe_function(w->read_tsfn, napi_tsfn_release);
    napi_release_threadsafe_function(w->write_tsfn, napi_tsfn_release);
    output_flow_release(w->flow, env);
    free(w->script); free(w->inputs_json); free(w->input_name); free(w->input_mime_type); free(w->input_charset); free(w);
    bridge_end_op(pinned, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "OOM");
    return NULL;
  }

  napi_value promise;
  if (napi_create_promise(env, &w->deferred, &promise) != napi_ok) {
    napi_release_threadsafe_function(w->read_tsfn, napi_tsfn_release);
    napi_release_threadsafe_function(w->write_tsfn, napi_tsfn_release);
    output_flow_release(w->flow, env);
    free(w->sentinel); free(w->script); free(w->inputs_json); free(w->input_name); free(w->input_mime_type); free(w->input_charset); free(w);
    bridge_end_op(pinned, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "runScriptTransformEngine: failed to create promise");
    return NULL;
  }

  napi_value controller = output_controller_create(env, promise, w->flow);
  if (controller == NULL) {
    napi_release_threadsafe_function(w->read_tsfn, napi_tsfn_release);
    napi_release_threadsafe_function(w->write_tsfn, napi_tsfn_release);
    output_flow_release(w->flow, env);
    free(w->sentinel); free(w->script); free(w->inputs_json);
    free(w->input_name); free(w->input_mime_type); free(w->input_charset); free(w);
    bridge_end_op(pinned, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    return NULL;
  }

  // Round-11 (#2): the pin was taken at admission (bridge_begin_op_locked) in
  // the same critical section as g_active_ops, so a concurrent destroyEngine
  // could never free this bridge under the admitted op. Just record it on w;
  // the completion sentinel releases it via bridge_end_op. NULL for a
  // resolver-less/unknown engine, handled everywhere as a no-op.
  w->bridge = pinned;

  uv_thread_options_t opts;
  opts.flags = UV_THREAD_HAS_STACK_SIZE;
  opts.stack_size = 2 * 1024 * 1024;
  int spawn_rc = uv_thread_create_ex(&w->tid, &opts, transform_thread_fn, w);

  if (spawn_rc != 0) {
    // The worker never ran, so nothing will ever decrement g_active_ops,
    // release the bridge hold, or resolve the promise -- unwind everything
    // committed above ourselves, in reverse order, mirroring
    // call_js_transform_write's completion branch (minus uv_thread_join:
    // there is no thread to join).
    uv_mutex_lock(&g_mutex);
    g_active_ops--;
    uv_cond_broadcast(&g_teardown_cond);
    uv_mutex_unlock(&g_mutex);

    // Synchronous call on the JS thread -- env is live here.
    bridge_end_op(w->bridge, /*env_still_alive=*/true);
    napi_release_threadsafe_function(w->read_tsfn, napi_tsfn_release);
    napi_release_threadsafe_function(w->write_tsfn, napi_tsfn_release);

    settle_output_deferred(
      env, w->deferred, w->flow,
      "{\"success\":false,\"error\":\"Failed to spawn transform worker thread\"}"
    );

    free(w->sentinel);
    free(w->script);
    free(w->inputs_json);
    free(w->input_name);
    free(w->input_mime_type);
    free(w->input_charset);
    output_flow_mark_done(w->flow, NULL);
    output_flow_release(w->flow, NULL);
    free(w);
  }

  return controller;
}

// --- Resolver callback bridge ---

// Called by native code, synchronously, on the same JS thread that invoked
// runScriptEngine for a resolver-backed engine (see the comment on
// engine_bridge_t above for why this must NOT hop through
// napi_threadsafe_function). The ctx word is the engine's own engine_bridge_t*,
// passed to Java in create_engine_with_resolver and forwarded back here. Calls
// the JS resolver directly and returns its result copied onto the heap; the
// caller frees the tracked buffers after the native side has copied them.
static char* resolve_module_callback(void* thread, void* ctx, const char* module_path) {
    (void)thread;

    engine_bridge_t* bridge = (engine_bridge_t*)ctx;
    if (bridge == NULL || bridge->env == NULL || bridge->resolver_js == NULL) {
        return NULL;  // No resolver for this engine
    }

    // Guard against cross-thread napi calls. Streaming and transform execute
    // their native call on a background uv_thread (streaming_thread_fn/
    // transform_thread_fn), not the JS thread that created this bridge. If we're
    // not on the thread that owns this napi_env, calling napi_get_reference_value
    // or napi_call_function here is undefined behavior (typically a crash).
    // Fail closed instead: report "not found", which matches the documented
    // built-ins-only fallback for streaming/transform.
    uv_thread_t current = uv_thread_self();
    if (!uv_thread_equal(&current, &bridge->owner)) {
        return NULL;
    }

    napi_env env = bridge->env;

    napi_value js_callback;
    if (napi_get_reference_value(env, bridge->resolver_js, &js_callback) != napi_ok) {
        return NULL;
    }

    // NameIdentifierHelper.toWeaveFilePath (Java side, via CallbackWeaveResourceResolver)
    // always renders paths with a leading separator, e.g. "/org/test/lib.dwl". Every
    // resolver factory in resolver.ts (modulesFromMap, modulesFromDirectory, ...) and
    // their documented examples key/join on the separator-less form ("org/test/lib.dwl"),
    // so strip exactly one leading '/' here before handing the path to JS.
    const char* js_module_path = module_path;
    if (js_module_path[0] == '/') {
        js_module_path++;
    }

    napi_value module_path_str;
    if (napi_create_string_utf8(env, js_module_path, NAPI_AUTO_LENGTH, &module_path_str) != napi_ok) {
        return NULL;
    }

    napi_value undefined, result;
    napi_get_undefined(env, &undefined);
    native_callback_enter();
    napi_status status = napi_call_function(env, undefined, js_callback, 1, &module_path_str, &result);
    native_callback_exit();
    if (status != napi_ok) {
        // JS resolver threw — clear the pending exception so it doesn't leak
        // into the next napi call, extract and log its message/stack for
        // diagnostics (same pattern as the read-callback bridge above), and
        // report "not found".
        if (status == napi_pending_exception) {
            napi_value exception;
            napi_get_and_clear_last_exception(env, &exception);

            // The resolver is user-provided code; its exception message/stack
            // can carry module source, file paths, credentials, or other
            // tenant data. Logging that to stderr by default risks leaking it
            // into aggregated log systems. Only log a fixed, content-free
            // diagnostic unless the caller has opted in via
            // DATAWEAVE_RESOLVER_DEBUG=1 (checked once and cached, since
            // getenv() is not safe to call from arbitrary threads on all
            // platforms and this callback can run off the JS thread).
            static int debug_checked = 0;
            static int debug_enabled = 0;
            if (!debug_checked) {
                const char* debug_env = getenv("DATAWEAVE_RESOLVER_DEBUG");
                debug_enabled = (debug_env != NULL && strcmp(debug_env, "1") == 0);
                debug_checked = 1;
            }

            if (!debug_enabled) {
                fprintf(stderr,
                    "[DataWeave Node addon] Resolver callback threw an exception "
                    "(details suppressed; set DATAWEAVE_RESOLVER_DEBUG=1 to log "
                    "message/stack — may expose resolver-controlled data).\n");
            } else {
                napi_value message_prop, stack_prop;
                char message_buf[512] = {0};
                char stack_buf[2048] = {0};
                size_t message_len = 0, stack_len = 0;

                if (napi_get_named_property(env, exception, "message", &message_prop) == napi_ok) {
                    napi_get_value_string_utf8(env, message_prop, message_buf, sizeof(message_buf), &message_len);
                }

                if (napi_get_named_property(env, exception, "stack", &stack_prop) == napi_ok) {
                    napi_get_value_string_utf8(env, stack_prop, stack_buf, sizeof(stack_buf), &stack_len);
                }

                fprintf(stderr, "[DataWeave Node addon] Resolver callback threw exception:\n");
                if (message_len > 0) {
                    fprintf(stderr, "  Message: %s\n", message_buf);
                }
                if (stack_len > 0) {
                    fprintf(stderr, "  Stack:\n%s\n", stack_buf);
                }
                if (message_len == 0 && stack_len == 0) {
                    fprintf(stderr, "  (Unable to extract exception details)\n");
                }
            }
        } else {
            fprintf(stderr, "Resolver callback threw exception\n");
        }
        return NULL;
    }

    napi_valuetype result_type;
    napi_typeof(env, result, &result_type);

    char* result_source = NULL;
    if (result_type == napi_string) {
        size_t len;
        napi_get_value_string_utf8(env, result, NULL, 0, &len);
        result_source = (char*)malloc(len + 1);
        if (result_source != NULL) {
            napi_get_value_string_utf8(env, result, result_source, len + 1, NULL);
        }
    }
    // null/undefined/other → not found (result_source stays NULL)

    if (!resolver_results_track(bridge, result_source)) {
        // Tracking-node allocation failed (OOM): result_source would otherwise
        // be an untracked buffer that nothing ever frees. Free it here and
        // report "unresolved" instead of leaking it.
        free(result_source);
        return NULL;
    }
    return result_source;  // Native copies this immediately; we free the original after the call.
}

// --- Per-engine N-API methods ---

// createEngine() -> number
static napi_value napi_create_engine(napi_env env, napi_callback_info info) {
    if (native_callback_active()) return throw_callback_reentrancy(env);
    (void)info;
    if (!fn_create_engine) { napi_throw_error(env, NULL, "create_engine not available in native library"); return NULL; }

    // Round-14 (#1): admission in ONE g_mutex critical section (mirrors
    // bridge_finalize_registry). Require (a) a live isolate not past the point
    // of no return, (b) that THIS env owns an init reference (round-13 ownership
    // model: an env with no reference must not create engines on the shared
    // isolate -- it could otherwise attach to an isolate another env is tearing
    // down), and (c) pin the isolate with a g_active_ops reservation so
    // graal_tear_down_isolate() cannot run across the attach/create below. The
    // check and the g_active_ops++ cannot be split by a teardown because every
    // teardown transition and the g_active_ops==0 fast path also hold g_mutex.
    uv_mutex_lock(&g_mutex);
    wait_for_detach_publication_locked();
    env_init_rec_t* self = env_init_rec_find_locked(env);
    if (g_isolate_poisoned) {
        uv_mutex_unlock(&g_mutex);
        napi_throw_error(env, NULL, ISOLATE_POISONED_MESSAGE);
        return NULL;
    }
    if (!g_initialized || g_isolate == NULL ||
        g_teardown_state == TEARDOWN_TEARING_DOWN ||
        self == NULL || self->init_refs == 0) {
        uv_mutex_unlock(&g_mutex);
        napi_throw_error(env, NULL, "Not initialized. Call initialize() first.");
        return NULL;
    }
    g_active_ops++;  // pins the live isolate against teardown across the attach
    uv_mutex_unlock(&g_mutex);

    void* thread = NULL;
    if (fn_attach_thread(g_isolate, &thread) != 0) {
        uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
        napi_throw_error(env, NULL, "Failed to attach thread"); return NULL;
    }
    long long handle = fn_create_engine(thread);
    detach_thread_checked(DETACH_SITE_CREATE_ENGINE, thread);
    // The Java @CEntryPoint exception handler explicitly returns 0 as its ABI
    // exception sentinel when engine construction throws. The real handle
    // registry only ever hands out handles >= 1, so any handle <= 0 means
    // construction failed; never hand that back to JS as if it were usable.
    if (handle <= 0) {
        uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
        napi_throw_error(env, NULL, "create_engine returned an invalid handle"); return NULL;
    }

    // Round-9 (#1): every engine -- resolver-backed or not -- gets a per-engine
    // record so destroyEngine can defer the registry removal (fn_destroy_engine)
    // until this engine's in-flight streaming/transform ops drain. A resolver-less
    // record leaves resolver_js/results NULL. Round-11 (#1): it now ALSO registers
    // an env cleanup hook (mirroring napi_create_engine_with_resolver), because
    // without one a Worker that creates a resolver-less engine and exits without
    // destroyEngine() would strand this record, the Java registry entry, and the
    // native-lib reference. Round-12 (#2) closed the record/registry gap via
    // bridge_finalize; round-13 (#5) moved ownership of the native-lib
    // initialize() reference to the env itself (env_init_rec), released by the
    // env-death hook env_init_cleanup, not per-engine.
    // owner is recorded for symmetry but is NOT used to restrict destruction based
    // on resolver state (see the owner guard in napi_destroy_engine, which now
    // fires for any record).
    bool fail_record_allocation = false;
    if (g_test_hooks) {
        uv_mutex_lock(&g_mutex);
        fail_record_allocation =
            g_test_engine_record_allocation_failure_generation == g_isolate_generation;
        if (fail_record_allocation) {
            g_test_engine_record_allocation_failure_generation = 0;
        }
        uv_mutex_unlock(&g_mutex);
    }
    engine_bridge_t* rec = fail_record_allocation
        ? NULL
        : (engine_bridge_t*)calloc(1, sizeof(engine_bridge_t));
    if (rec == NULL) {
        // Roll back the engine we just created so we don't leak a registered but
        // unrecorded handle. fn_destroy_engine attaches its own thread.
        if (fn_destroy_engine) {
            void* t2 = NULL;
            if (fn_attach_thread(g_isolate, &t2) == 0) {
                fn_destroy_engine(t2, handle);
                detach_thread_checked(DETACH_SITE_CREATE_ROLLBACK, t2);
            }
        }
        // review #21 #1 (final-review completeness): this OOM-rollback detach is an
        // ordinary detach too -- a failure here strands a phantom thread and would
        // wedge a later teardown, so poison in the same critical section as the
        // g_active_ops-- (before the decrement/broadcast), matching the other sites.
        uv_mutex_lock(&g_mutex);
        g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
        napi_throw_error(env, NULL, "Failed to allocate engine record");
        return NULL;
    }
    rec->native_handle = handle;
    rec->native_alive = true;
    rec->owner_alive = true;
    rec->owner = uv_thread_self();
    rec->env = env;
    uv_mutex_lock(&g_mutex);
    rec->handle = next_engine_handle_locked();
    rec->isolate_generation = g_isolate_generation;
    if (rec->handle > 0) {
        rec->next = g_bridges;
        g_bridges = rec;
    }
    uv_mutex_unlock(&g_mutex);
    if (rec->handle <= 0) {
        // No public handle or cleanup hook was published. Use the normal
        // generation-aware finalizer so a transient attach failure retains the
        // native registry record instead of freeing a resolver ctx still held
        // by Java. may_rehook=false because no cleanup hook was registered.
        if (bridge_finalize_registry_at_site(rec, DETACH_SITE_CREATE_ROLLBACK)) {
            bridge_finalize_free(rec, /*env_still_alive=*/true);
        } else {
            bridge_retain_stranded(rec);
        }
        uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
        napi_throw_error(env, NULL, "Engine handle space exhausted");
        return NULL;
    }
    // Round-11 (#1): register an env cleanup hook for EVERY engine, not just
    // resolver-backed ones. Without it, a Worker that creates a resolver-less
    // engine and exits without destroyEngine() would strand this record, the Java
    // ScriptRuntime registry entry, and the native-lib reference -- leaking
    // engines and blocking isolate teardown across Worker churn. bridge_env_cleanup
    // + bridge_finalize already handle a resolver-less record (resolver_js == NULL):
    // skip the napi_ref delete, still unlink, remove the registry entry (round-10
    // do_registry_remove=true), and free. Round-13 (#5) moved ownership of the
    // native-lib initialize() reference to the env itself (env_init_rec): this
    // per-engine hook no longer touches g_ref_count -- the reference is released
    // by the env-death hook env_init_cleanup (or by cleanup()), so an abandoned
    // env releases exactly one reference regardless of how many engines it made.
    // destroyEngine removes this hook before an early free so Node never invokes
    // it on freed memory.
    napi_status hook_st = napi_add_env_cleanup_hook(env, bridge_env_cleanup, rec);
    if (hook_st == napi_ok) {
        rec->hook_registered = true;
    } else {
        // Creation must be all-or-nothing (round-12 #6): without a cleanup hook a
        // Worker that abandons this engine would strand the record and the Java
        // registry entry. Unlink, remove the registry entry, free, and throw --
        // no usable handle escapes. The record was just linked on this thread
        // with in_flight==0 and its handle was never returned to JS, so no op
        // can be in flight against it.
        // Do NOT release the init reference here (fix round 1): this throw
        // propagates to initialize()'s TS catch (dataweave.ts), which sees
        // libRefAcquired==true and calls ffi.cleanup() -- that is the ONE
        // release for this creation's ref, matching every sibling
        // creation-failure path (invalid-handle guard, alloc failure) that also
        // leaves the release to the TS catch. Releasing natively here too would
        // double-decrement g_ref_count -- masked in a single-instance process
        // (the guard no-ops a second release at 0) but a live UAF hazard with a
        // second engine instance still holding a reference.
        uv_mutex_lock(&g_mutex);
        engine_bridge_t** pp = &g_bridges;
        while (*pp != NULL) { if (*pp == rec) { *pp = rec->next; break; } pp = &(*pp)->next; }
        uv_mutex_unlock(&g_mutex);
        // round-15 (svacas P1): go through bridge_finalize (do_registry_remove=true)
        // so a destroy skipped on a transient attach failure retains the record for
        // retry instead of freeing it while the Java registry still references it.
        // may_rehook=false: this hook never registered (hook_registered stayed
        // false), and creation is aborting all-or-nothing -- do not (re-)hook.
        bridge_finalize(rec, /*env_still_alive=*/true, /*do_registry_remove=*/true, /*may_rehook=*/false);
        uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
        napi_throw_error(env, NULL, "Failed to register engine cleanup hook");
        return NULL;
    }

    napi_value out; napi_create_int64(env, (int64_t)rec->handle, &out);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    return out;
}

// createEngineWithResolver(resolver) -> number
static napi_value napi_create_engine_with_resolver(napi_env env, napi_callback_info info) {
    if (native_callback_active()) return throw_callback_reentrancy(env);
    if (!fn_create_engine_with_resolver) { napi_throw_error(env, NULL, "create_engine_with_resolver not available in native library"); return NULL; }
    size_t argc = 1; napi_value argv[1];
    napi_get_cb_info(env, info, &argc, argv, NULL, NULL);
    if (argc < 1) { napi_throw_error(env, NULL, "createEngineWithResolver requires (resolverCallback)"); return NULL; }

    engine_bridge_t* bridge = (engine_bridge_t*)calloc(1, sizeof(engine_bridge_t));
    if (bridge == NULL) { napi_throw_error(env, NULL, "Failed to allocate engine bridge"); return NULL; }
    if (napi_create_reference(env, argv[0], 1, &bridge->resolver_js) != napi_ok) {
        free(bridge); napi_throw_error(env, NULL, "Failed to reference resolver callback"); return NULL;
    }
    if (g_test_hooks) {
        uv_mutex_lock(&g_mutex);
        g_test_live_resolver_refs++;
        uv_mutex_unlock(&g_mutex);
    }
    bridge->env = env; bridge->owner = uv_thread_self(); bridge->results = NULL;
    bridge->owner_alive = true;
    if (!bridge_register_owner_cleanup(bridge)) {
        bridge_finalize_free(bridge, /*env_still_alive=*/true);
        napi_throw_error(env, NULL, "Failed to register resolver owner cleanup");
        return NULL;
    }

    // Round-14 (#1): same admission block as napi_create_engine. Taken AFTER the
    // bridge/resolver-ref allocation (those failures touch no isolate state and
    // must not decrement a reservation not yet held) and BEFORE fn_attach_thread.
    uv_mutex_lock(&g_mutex);
    wait_for_detach_publication_locked();
    env_init_rec_t* self = env_init_rec_find_locked(env);
    if (g_isolate_poisoned) {
        uv_mutex_unlock(&g_mutex);
        bridge_finalize_free(bridge, /*env_still_alive=*/true);
        napi_throw_error(env, NULL, ISOLATE_POISONED_MESSAGE);
        return NULL;
    }
    if (!g_initialized || g_isolate == NULL ||
        g_teardown_state == TEARDOWN_TEARING_DOWN ||
        self == NULL || self->init_refs == 0) {
        uv_mutex_unlock(&g_mutex);
        bridge_finalize_free(bridge, /*env_still_alive=*/true);
        napi_throw_error(env, NULL, "Not initialized. Call initialize() first.");
        return NULL;
    }
    g_active_ops++;  // pins the live isolate against teardown across the attach
    uv_mutex_unlock(&g_mutex);

    void* thread = NULL;
    if (fn_attach_thread(g_isolate, &thread) != 0) {
        uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
        bridge_finalize_free(bridge, /*env_still_alive=*/true);
        napi_throw_error(env, NULL, "Failed to attach thread"); return NULL;
    }
    long long handle = fn_create_engine_with_resolver(thread, resolve_module_callback, (void*)bridge);
    detach_thread_checked(DETACH_SITE_RESOLVER_CREATE, thread);

    // Same invalid-handle guard as napi_create_engine: the Java @CEntryPoint
    // exception handler explicitly returns handle == 0 as its ABI sentinel,
    // and any handle <= 0 is never valid. Reject before this bridge
    // is linked into g_bridges or a cleanup hook is registered for it — at this
    // point neither has happened, so there's nothing to unlink/unhook. Still use
    // bridge_finalize (not a manual napi_delete_reference+free) because the failed
    // construction may have called resolve_module_callback (e.g. during eager
    // module setup) before ultimately failing, which can have already populated
    // bridge->results via resolver_results_track; bridge_finalize frees those
    // tracked buffers too, so nothing is dropped on the floor.
    if (handle <= 0) {
        uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
        // Synchronous call on the JS thread -- env is live here. may_rehook=false:
        // no hook was ever registered for this bridge and creation is aborting; with
        // do_registry_remove=false there is nothing registered to strand on anyway.
        bridge_finalize(bridge, /*env_still_alive=*/true, /*do_registry_remove=*/false, /*may_rehook=*/false);
        napi_throw_error(env, NULL, "create_engine_with_resolver returned an invalid handle");
        return NULL;
    }

    bridge->native_handle = handle;
    bridge->native_alive = true;
    uv_mutex_lock(&g_mutex);
    bridge->handle = next_engine_handle_locked();
    bridge->isolate_generation = g_isolate_generation;
    if (bridge->handle > 0) {
        bridge->next = g_bridges;
        g_bridges = bridge;
    }
    uv_mutex_unlock(&g_mutex);
    if (bridge->handle <= 0) {
        if (bridge_finalize_registry_at_site(bridge, DETACH_SITE_CREATE_ROLLBACK)) {
            bridge_finalize_free(bridge, /*env_still_alive=*/true);
        } else {
            bridge_retain_stranded(bridge);
        }
        uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
        napi_throw_error(env, NULL, "Engine handle space exhausted");
        return NULL;
    }
    // Register a per-env cleanup hook so THIS Worker/main thread disposes this
    // bridge's napi_ref on its own thread when its env tears down (F2). napi_cleanup
    // no longer touches bridge refs. destroyEngine removes this hook before an
    // early free so Node never calls it on freed memory.
    napi_status hook_st = napi_add_env_cleanup_hook(env, bridge_env_cleanup, bridge);
    if (hook_st == napi_ok) {
        bridge->hook_registered = true;
    } else {
        // Creation must be all-or-nothing (round-12 #6): without a cleanup hook a
        // Worker that abandons this engine would strand the record and the Java
        // registry entry. Unlink, remove the registry entry, free, and throw --
        // no usable handle escapes. The record was just linked on this thread
        // with in_flight==0 and its handle was never returned to JS, so no op
        // can be in flight against it.
        // Do NOT release the init reference here (fix round 1): this throw
        // propagates to initialize()'s TS catch (dataweave.ts), which sees
        // libRefAcquired==true and calls ffi.cleanup() -- that is the ONE
        // release for this creation's ref, matching every sibling
        // creation-failure path (resolver invalid-handle guard uses
        // bridge_finalize with do_registry_remove=false and also does NOT
        // release) that also leaves the release to the TS catch. Releasing
        // natively here too would double-decrement g_ref_count -- masked in a
        // single-instance process (the guard no-ops a second release at 0) but
        // a live UAF hazard with a second engine instance still holding a
        // reference.
        uv_mutex_lock(&g_mutex);
        engine_bridge_t** pp = &g_bridges;
        while (*pp != NULL) { if (*pp == bridge) { *pp = bridge->next; break; } pp = &(*pp)->next; }
        uv_mutex_unlock(&g_mutex);
        // round-15 (svacas P1): go through bridge_finalize (do_registry_remove=true)
        // so a destroy skipped on a transient attach failure retains the bridge for
        // retry instead of freeing it while the Java registry still references it.
        // may_rehook=false: this hook never registered (hook_registered stayed
        // false), and creation is aborting all-or-nothing -- do not (re-)hook.
        bridge_finalize(bridge, /*env_still_alive=*/true, /*do_registry_remove=*/true, /*may_rehook=*/false);
        uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
        napi_throw_error(env, NULL, "Failed to register engine cleanup hook");
        return NULL;
    }
    napi_value out; napi_create_int64(env, (int64_t)bridge->handle, &out);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
    return out;
}

// destroyEngine(handle) -> void
static napi_value napi_destroy_engine(napi_env env, napi_callback_info info) {
    if (native_callback_active()) return throw_callback_reentrancy(env);
    if (!g_initialized) return NULL;
    size_t argc = 1; napi_value argv[1];
    napi_get_cb_info(env, info, &argc, argv, NULL, NULL);
    if (argc < 1) { napi_throw_error(env, NULL, "destroyEngine requires (handle)"); return NULL; }
    int64_t handle64;
    if (napi_get_value_int64(env, argv[0], &handle64) != napi_ok) {
        napi_throw_error(env, NULL, "destroyEngine: handle must be an integer");
        return NULL;
    }
    long long handle = (long long)handle64;

    // F2: a resolver-backed engine's bridge owns thread-affine N-API state --
    // a napi_ref and an env cleanup hook, both created on the engine's owning
    // JS thread. Deleting that ref (bridge_finalize) or removing that hook
    // (napi_remove_env_cleanup_hook) from another Worker's thread is undefined
    // behavior. Reject cross-thread destruction, mirroring the fail-closed
    // owner check in resolve_module_callback; the owner env's cleanup hook
    // disposes the bridge when that Worker tears down. We are on the owner
    // thread past this point, so the env cannot be concurrently tearing down
    // and the bridge stays stable between this check and the unlink below.
    // Owner-thread guard: round-11 (#1) registers an env cleanup hook for EVERY
    // engine (resolver-backed or not), so every record now carries env-affine
    // N-API state -- napi_remove_env_cleanup_hook (called below before an early
    // free) can only be invoked legally on the owner thread. The guard
    // therefore fires for any record (owned != NULL), not just resolver-backed
    // ones. bridge_finalize's napi_ref deletion stays resolver-gated
    // (resolver_js != NULL && env != NULL) -- that part is unchanged.
    uv_mutex_lock(&g_mutex);
    engine_bridge_t* owned = bridge_find_any(handle);
    if (owned != NULL) {
        uv_thread_t self = uv_thread_self();
        if (!uv_thread_equal(&self, &owned->owner)) {
            uv_mutex_unlock(&g_mutex);
            napi_throw_error(env, NULL,
                "destroyEngine must be called from the thread that created the engine");
            return NULL;
        }
    }

    // Round-9 (#1): unlink the record and decide, under the lock, whether the
    // registry removal (fn_destroy_engine) and the record free must be DEFERRED.
    // If an op is in flight, its worker may not yet have called
    // ScriptRuntime.get(handle) (the first statement of the Java entrypoint) --
    // removing the registry entry now would make that lookup fail with
    // "Unknown engine handle". So defer BOTH the registry removal and the free
    // to the last op draining (bridge_end_op -> bridge_finalize with
    // do_registry_remove=true), which runs on this same owner thread. When no op
    // is in flight, remove the registry entry and finalize immediately, as
    // before. Every engine now has a record, so `found` is non-NULL for both
    // resolver-backed and resolver-less engines.
    engine_bridge_t** pp = &g_bridges; engine_bridge_t* found = NULL;
    while (*pp != NULL) { if ((*pp)->handle == handle) { found = *pp; *pp = found->next; break; } pp = &(*pp)->next; }
    bool defer = false;
    // deferred_registry_remove gates the deferred registry removal in
    // bridge_end_op; set it together with destroy_pending here.
    if (found != NULL && found->in_flight > 0) { found->destroy_pending = true; found->deferred_registry_remove = true; defer = true; }
    uv_mutex_unlock(&g_mutex);

    if (found != NULL) {
        if (!defer) {
            // Not in flight: remove the registry entry AND finalize now, on this
            // owner thread (env live). do_registry_remove=true folds the
            // fn_destroy_engine call into bridge_finalize so it happens exactly
            // once regardless of path. may_rehook=true: we are on the owner thread
            // with the env alive, so a live-isolate strand keeps the hook (owner
            // env finalizes resolver_js later) rather than enqueuing on the drain.
            // Do NOT pre-remove the hook here (review #12 #3, #13): bridge_finalize
            // owns it -- its FREE path removes it before freeing (so Node never
            // invokes it on freed memory), and its STRAND path KEEPS it so the owner
            // env deletes resolver_js at teardown instead of the off-thread drain
            // (which skips napi_delete_reference and would leak the ref).
            bridge_finalize(found, /*env_still_alive=*/true, /*do_registry_remove=*/true, /*may_rehook=*/true);
        } else {
            // In flight -> DEFER the finalize to the draining op's bridge_end_op.
            // Remove the env cleanup hook NOW (round-1 fix to reviews #12 #3/#13):
            // this is legal here (owner thread, env alive) and makes bridge_end_op
            // the SOLE finalizer after this destroy. Leaving the hook registered
            // reopens a double-owner window: destroy_pending only keeps
            // bridge_env_cleanup and bridge_end_op mutually exclusive for ABANDONED
            // (never-destroyed) engines, because bridge_env_cleanup's in_flight==0
            // branch finalizes WITHOUT checking destroy_pending. So if the env is
            // torn down while this op is still in flight (e.g. worker.terminate()),
            // the op's bridge_end_op(env_still_alive=false) frees the bridge on its
            // FREE path (which skips the hook-remove -- gated on env_still_alive),
            // and the later env-cleanup-hook fire would run bridge_env_cleanup on
            // freed memory -> UAF / double-free / double fn_destroy_engine.
            // bridge_end_op re-registers the hook (may_rehook=env_still_alive) only
            // if its later finalize STRANDS on the owner thread with the env alive,
            // keeping resolver_js deletion on the owner thread -> the leak fix holds.
            napi_remove_env_cleanup_hook(env, bridge_env_cleanup, found);
            found->hook_registered = false;
        }
    } else {
        // No record found (should not happen now that every engine has one, but
        // stay robust to a double-destroy or an unknown handle): fall back to
        // removing the Java registry entry directly. That removal requires
        // attaching to the live isolate, so guard the attach EXACTLY like
        // bridge_finalize_registry (review #10 #5): read g_isolate/g_teardown_state
        // under g_mutex and, if the isolate is live, pin it with a TRANSIENT
        // g_active_ops reservation so graal_tear_down_isolate() cannot run across
        // the attach (the state check + the g_active_ops++ are one critical
        // section). If the isolate is already gone (g_isolate == NULL) or the
        // waiter has committed to physical teardown (TEARDOWN_TEARING_DOWN), the
        // Java registry died/dies with the isolate -- there is nothing to remove
        // and attaching would race the teardown, so return early / no-op safely.
        // Without this guard an unknown-handle (or double-)destroyEngine racing a
        // concurrent cleanup() teardown could call fn_attach_thread on a NULL or
        // being-torn-down isolate. The unlocked g_initialized check at the top of
        // this function is a stale read under concurrency and does NOT close this
        // window; only the g_mutex-guarded read here does.
        if (fn_destroy_engine && fn_attach_thread) {
            uv_mutex_lock(&g_mutex);
            wait_for_detach_publication_locked();
            if (g_teardown_state == TEARDOWN_TEARING_DOWN || g_isolate == NULL) {
                uv_mutex_unlock(&g_mutex);  // isolate gone/tearing down -> nothing to remove
            } else {
                g_active_ops++;  // pins the live isolate against teardown for this attach
                uv_mutex_unlock(&g_mutex);
                void* thread = NULL;
                if (fn_attach_thread(g_isolate, &thread) == 0 && thread != NULL) {
                    // Public handles are addon-local and cannot be passed to
                    // the isolate registry when no current bridge maps them.
                    // Preserve the native unknown-destroy call with the ABI's
                    // guaranteed-invalid zero handle.
                    fn_destroy_engine(thread, 0);
                    detach_thread_checked(DETACH_SITE_UNKNOWN_DESTROY, thread);
                }
                // Verbatim g_active_ops release pattern.
                uv_mutex_lock(&g_mutex);
                g_active_ops--;
                uv_cond_broadcast(&g_teardown_cond);
                uv_mutex_unlock(&g_mutex);
            }
        }
    }
    return NULL;
}

// runScriptEngine(handle, script, inputsJson) -> string
static napi_value napi_run_script_engine(napi_env env, napi_callback_info info) {
    if (native_callback_active()) return throw_callback_reentrancy(env);
    if (!g_initialized) { napi_throw_error(env, NULL, "Not initialized. Call initialize() first."); return NULL; }
    if (!fn_run_script_engine) { napi_throw_error(env, NULL, "run_script_engine not available in native library"); return NULL; }
    size_t argc = 3; napi_value argv[3];
    napi_get_cb_info(env, info, &argc, argv, NULL, NULL);
    if (argc < 3) { napi_throw_error(env, NULL, "runScriptEngine requires (handle, script, inputsJson)"); return NULL; }
    int64_t handle64;
    if (napi_get_value_int64(env, argv[0], &handle64) != napi_ok) {
        napi_throw_error(env, NULL, "runScriptEngine: handle must be an integer");
        return NULL;
    }
    long long handle = (long long)handle64;

    size_t script_len, inputs_len;
    if (napi_get_value_string_utf8(env, argv[1], NULL, 0, &script_len) != napi_ok) {
        napi_throw_error(env, NULL, "runScriptEngine: script must be a string");
        return NULL;
    }
    if (napi_get_value_string_utf8(env, argv[2], NULL, 0, &inputs_len) != napi_ok) {
        napi_throw_error(env, NULL, "runScriptEngine: inputsJson must be a string");
        return NULL;
    }
    char* script = (char*)malloc(script_len + 1);
    char* inputs = (char*)malloc(inputs_len + 1);
    if (script == NULL || inputs == NULL) { free(script); free(inputs); napi_throw_error(env, NULL, "OOM"); return NULL; }
    if (napi_get_value_string_utf8(env, argv[1], script, script_len + 1, NULL) != napi_ok ||
        napi_get_value_string_utf8(env, argv[2], inputs, inputs_len + 1, NULL) != napi_ok) {
        free(script); free(inputs);
        napi_throw_error(env, NULL, "runScriptEngine: failed to read script/inputsJson");
        return NULL;
    }

    // Round-7 #1: reserve an active op across the isolate-touching window
    // (attach -> run -> detach) so a concurrent Worker's last cleanup()
    // (napi_cleanup Case 4) cannot observe g_active_ops == 0 and tear down
    // g_isolate while this synchronous op is attaching to or executing in it.
    // Reserve LATE (here, not at the top): the malloc/arg-extraction above do
    // not touch the isolate, so the reservation only needs to span attach..
    // detach -- giving exactly two unwind sites (attach-failure and normal
    // completion) instead of also unwinding the OOM path. Rejecting on
    // g_teardown_state != TEARDOWN_NONE also refuses to start once a teardown
    // is queued/underway. run() is fully synchronous on the JS thread, so the
    // reserve and release both happen inline (no worker thread). Admit an
    // ADOPTED isolate: napi_initialize's adoption branch sets
    // g_teardown_cancelled = true on a still-live PENDING_WAIT isolate but
    // does not reset g_teardown_state (only the async waiter does), so a
    // merely-cancelled teardown must not reject here -- otherwise a valid
    // post-adoption op throws "Not initialized". A genuine (non-cancelled)
    // PENDING_WAIT or a committed TEARING_DOWN still rejects.
  uv_mutex_lock(&g_mutex);
  wait_for_detach_publication_locked();
  if (g_isolate_poisoned) {
      uv_mutex_unlock(&g_mutex);
      free(script); free(inputs);
      napi_throw_error(env, NULL, ISOLATE_POISONED_MESSAGE);
      return NULL;
    }
    if (!g_initialized || (g_teardown_state != TEARDOWN_NONE && !g_teardown_cancelled)) {
      uv_mutex_unlock(&g_mutex);
      free(script); free(inputs);
      napi_throw_error(env, NULL, "Not initialized. Call initialize() first.");
      return NULL;
    }
    g_active_ops++;
    // Round-11 (#3): pin the engine in the same critical section as the
    // g_active_ops reservation so a concurrent destroyEngine cannot free the
    // resolver bridge (still held by Java as the resolver ctx) while this
    // synchronous op attaches to Graal or runs. NULL for a resolver-less/unknown
    // handle -- bridge_end_op no-ops on NULL. Released in the attach-failure and
    // completion paths below, alongside g_active_ops.
    engine_bridge_t* bridge = bridge_begin_op_locked(handle);
    uv_mutex_unlock(&g_mutex);

    void* thread = NULL;
    if (fn_attach_thread(g_isolate, &thread) != 0) {
      bridge_end_op(bridge, /*env_still_alive=*/true);
      uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);
      free(script); free(inputs);
      napi_throw_error(env, NULL, "Failed to attach thread");
      return NULL;
    }

    long long native_handle = bridge != NULL ? bridge->native_handle : 0;
    char* result = (char*)fn_run_script_engine(thread, native_handle, script, inputs);

    // The pin taken at admission kept this record alive across the run, so no
    // second lookup is needed. resolver_results_free_all is a no-op for a
    // resolver-less/unknown engine (bridge == NULL).
    if (bridge != NULL) resolver_results_free_all(bridge);

    char* result_copy = result ? strdup(result) : NULL;
    if (result != NULL) fn_free_cstring(thread, result);
    detach_thread_checked(DETACH_SITE_SYNCHRONOUS_RUN, thread);
    free(script); free(inputs);

    // Round-11 (#3): release the per-engine pin (may finalize a destroy that a
    // concurrent Worker deferred while this op held in_flight > 0), then release
    // the global op reservation. env is live on this JS thread, so env_still_alive
    // is true. Order: bridge_end_op before the g_active_ops release, mirroring
    // streaming/transform completion.
    bridge_end_op(bridge, /*env_still_alive=*/true);
    uv_mutex_lock(&g_mutex); g_active_ops--; uv_cond_broadcast(&g_teardown_cond); uv_mutex_unlock(&g_mutex);

    // Round-15 (svacas P1): op-completion drain point -- retry destroy for any
    // bridge stranded on a transient attach failure (Graal-only + free, no napi
    // env call). This is the synchronous raw-FFI path whose resolve_module_callback
    // is the UAF the retain fix protects.
    drain_stranded_bridges();

    napi_value out;
    if (result_copy) { napi_create_string_utf8(env, result_copy, NAPI_AUTO_LENGTH, &out); free(result_copy); }
    else { napi_create_string_utf8(env, "", 0, &out); }
    return out;
}

// --- Cleanup (must run on a separate thread to avoid V8 signal handler conflict) ---

// Called on each waiter's own env/thread (via its own napi_threadsafe_function)
// once the waiter thread has finished isolate teardown. Resolves that specific
// caller's promise, then releases its tsfn and frees the node. `data` is
// unused (NULL) -- there is nothing to report beyond "done".
//
// napi_call_threadsafe_function(..., napi_tsfn_blocking) only ENQUEUES this
// callback for the target env's event loop to run later; it does not wait for
// it to actually execute. So the waiter node and its tsfn must stay alive
// until this callback runs and must be released/freed HERE, not by the
// thread that enqueued the call (teardown_waiter_thread_fn) -- freeing there
// right after the enqueueing call would be a use-after-free once this
// callback later dereferences `context`. Same ownership pattern as
// call_js_write/call_js_transform_write freeing their own work struct from
// inside their own completion branch.
static void call_js_teardown_done(napi_env env, napi_value js_callback, void* context, void* data) {
  (void)js_callback;
  (void)data;
  teardown_waiter_t* waiter = (teardown_waiter_t*)context;
  if (waiter == NULL) return;

  if (env != NULL) {
    napi_value undefined;
    napi_get_undefined(env, &undefined);
    napi_resolve_deferred(env, waiter->deferred, undefined);
  }

  napi_release_threadsafe_function(waiter->tsfn, napi_tsfn_release);
  free(waiter);
}

// `arg` is a cleanup_thread_result_t* out-param: the caller must initialize its
// CLEANUP_RETAIN before spawning this thread (so a spawn that never runs, or the
// attach-failure early return, leaves the live isolate retained) and read it
// after uv_thread_join returns. Mirrors teardown_waiter_thread_fn's outcome
// exactly, so the caller can distinguish "isolate torn down / nothing to tear
// down" (clear g_thread/g_isolate/g_initialized/g_ref_count) from "attach or
// teardown failed but the isolate is still reachable" (retain + arm retry) from
// "teardown AND detach both failed" (unrecoverable -- leak the isolate).
// Follow-up detaches remain direct: they classify that teardown double failure,
// rather than poisoning an otherwise completed ordinary operation.
static void cleanup_thread_fn(void* arg) {
  cleanup_thread_result_t* result = (cleanup_thread_result_t*)arg;
  // graal_tear_down_isolate() must be passed the IsolateThread belonging to the
  // *calling* OS thread. g_thread was created by graal_create_isolate() on the
  // (now-exited, already-joined) init thread, so it is invalid here — passing it
  // trips GraalVM's "wrong IsolateThread" guard and aborts with a fatal
  // StackOverflowError during teardown. Attach this cleanup thread to the isolate
  // to obtain a valid local IsolateThread, then tear down with that.
  if (!fn_tear_down_isolate || !fn_attach_thread || !g_isolate) {
    // Nothing to tear down (no isolate / FFI unavailable) -- safe to clear.
    result->outcome = CLEANUP_TORN_DOWN;
    return;
  }
  if (g_isolate_poisoned) {
    // review #21 #1: an earlier op's detach failed, leaving a phantom attached
    // thread. graal_tear_down_isolate() would block forever waiting for it, so do
    // NOT attempt teardown -- signal leak-and-continue (the caller runs
    // abandon_unrecoverable_isolate_locked()). Reading g_isolate_poisoned unlocked
    // is safe: the caller spawns+joins this thread while holding g_mutex.
    result->outcome = CLEANUP_UNRECOVERABLE;
    return;
  }
  void* local_thread = NULL;
  if (fn_attach_thread(g_isolate, &local_thread) != 0 || local_thread == NULL) {
    // Attach failed -- the isolate is still alive. Leave *out_result at its
    // caller-initialized CLEANUP_RETAIN so the caller does NOT clear g_isolate
    // (or it becomes unreachable and can never be torn down) and arms the retry.
    return;
  }
  // The attached worker is about to invoke teardown. The caller increments the
  // counter after join, avoiding a second g_mutex lock while synchronous callers
  // deliberately hold it across this worker.
  result->teardown_callable = true;
  // Check the teardown return code (0 == success). On nonzero the isolate is
  // still live and this thread is still attached to it -- detach before exiting
  // or the live isolate keeps a phantom attached thread that can block/fail a
  // later retry teardown (review #7 #1). On success the isolate is gone: do NOT
  // detach (would be a UAF).
  if (fn_tear_down_isolate(local_thread) == 0) {
    result->outcome = CLEANUP_TORN_DOWN;
  } else if (fn_detach_thread(local_thread) == 0) {
    // Teardown failed but the worker detached cleanly: the isolate is live and
    // reachable -- retain it and (per the caller's own logic) arm the retry
    // (review #6 #3).
    result->outcome = CLEANUP_RETAIN;
  } else {
    // Teardown AND detach both failed (review #17 #1): the worker is stuck
    // attached, so this isolate can never be torn down. Signal leak-and-continue.
    result->outcome = CLEANUP_UNRECOVERABLE;
  }
}

// Spawned only when napi_cleanup finds g_active_ops > 0 on the last release
// (case 5 in the design doc). Blocks until every active streaming/transform
// op has drained, performs isolate teardown exactly like cleanup_thread_fn
// does on the unchanged fast path, then resolves every caller who is waiting
// on this same teardown (there may be more than one -- see g_teardown_waiters).
// Its teardown-failure follow-up detach is direct for the same double-failure
// classification documented on cleanup_thread_fn.
static void teardown_waiter_thread_fn(void* arg) {
  (void)arg;

  uv_mutex_lock(&g_mutex);
  while ((g_active_ops > 0 ||
          (g_detach_in_progress > 0 &&
           g_detach_in_progress_generation == g_isolate_generation)) &&
         !g_teardown_cancelled) {
    uv_cond_wait(&g_teardown_cond, &g_mutex);
  }
  bool cancelled = g_teardown_cancelled;
  bool poisoned = g_isolate_poisoned;
  if (!cancelled) {
    // Point of no return: from here an adopting initialize() must NOT reuse the
    // isolate, so publish TEARING_DOWN under the lock before we drop it to call
    // graal_tear_down_isolate().
    g_teardown_state = TEARDOWN_TEARING_DOWN;
  }
  uv_mutex_unlock(&g_mutex);

  // Perform teardown exactly as the synchronous cleanup_thread_fn path does.
  // Honor the return codes (0 == success). Skipped entirely when an initialize()
  // call adopted the live isolate instead (see napi_initialize's
  // TEARDOWN_PENDING_WAIT branch).
  cleanup_result_t result = CLEANUP_RETAIN;
  if (!cancelled && poisoned) {
    // review #21 #1: a prior op's failed detach left a phantom attached thread;
    // graal_tear_down_isolate() would hang. Skip it and leak-and-continue -- the
    // post block below runs abandon_unrecoverable_isolate_locked().
    result = CLEANUP_UNRECOVERABLE;
  } else if (!cancelled && fn_tear_down_isolate && fn_attach_thread && g_isolate) {
    void* local_thread = NULL;
    if (fn_attach_thread(g_isolate, &local_thread) == 0 && local_thread != NULL) {
      if (g_test_hooks) {
        uv_mutex_lock(&g_mutex);
        g_test_teardown_calls++;
        uv_mutex_unlock(&g_mutex);
      }
      if (fn_tear_down_isolate(local_thread) == 0) {
        result = CLEANUP_TORN_DOWN;
      } else if (fn_detach_thread(local_thread) == 0) {
        // Teardown failed, worker detached cleanly: retain + arm below (review #6 #3).
        result = CLEANUP_RETAIN;
      } else {
        // Teardown AND detach both failed (review #17 #1): leak-and-continue below.
        result = CLEANUP_UNRECOVERABLE;
      }
    }
    // else: attach failed -- isolate still alive and reachable; leave
    // result == CLEANUP_RETAIN so the post block retains + arms the retry.
  } else if (!cancelled) {
    // Nothing to tear down (no isolate / FFI unavailable) -- safe to clear.
    result = CLEANUP_TORN_DOWN;
  }
  // if (cancelled): leave result == CLEANUP_RETAIN -- the isolate stays live for
  // the adopter; we tear nothing down and the post block's !cancelled guards skip
  // every branch, leaving the adopter's state untouched.

  uv_mutex_lock(&g_mutex);
  if (!cancelled && result == CLEANUP_TORN_DOWN) {
    if (g_test_engine_record_allocation_failure_generation == g_isolate_generation) {
      g_test_engine_record_allocation_failure_generation = 0;
    }
    g_thread = NULL;
    g_isolate = NULL;
    g_initialized = 0;
    g_ref_count = 0;
  } else if (!cancelled && result == CLEANUP_UNRECOVERABLE) {
    // teardown+detach double failure on the deferred path (review #17 #1):
    // abandon + leak the isolate; do NOT arm the retry. The deferred cleanup()
    // promise still RESOLVES below (deliberate, exactly as the retain branch
    // does). The helper emits its own stderr diagnostic. Mirrors Python
    // native.py leak-and-continue.
    abandon_unrecoverable_isolate_locked();
  } else if (!cancelled && g_isolate != NULL && g_ref_count == 0) {
    // Teardown did not happen (attach failed, or graal_tear_down_isolate
    // returned nonzero with a clean detach -- review #6 #3) and this async-waiter
    // path IS the last release: arm the retry signal so a later drain or a fresh
    // initialize() retries teardown (review #6 #4).
    g_teardown_needed = true;
    // Observable failure (review #10 #5): the deferred cleanup() promise is still
    // RESOLVED below, so emit a diagnostic or a failed async teardown would be
    // silent. Parity with Python's _release_isolate stderr notice (native.py).
    fprintf(stderr,
            "[DataWeave Node addon] GraalVM isolate teardown failed on deferred "
            "cleanup(); the isolate is retained and teardown will be retried on the "
            "next initialize() or op completion.\n");
  }
  // If cancelled: g_isolate/g_initialized/g_ref_count are left exactly as the
  // adopting initialize() set them (it already did g_ref_count++ on the live
  // isolate).
  g_teardown_state = TEARDOWN_NONE;
  g_teardown_cancelled = false;
  // Release any initialize() call blocked waiting for teardown to finish
  // (see Task 3).
  uv_cond_broadcast(&g_teardown_cond);
  teardown_waiter_t* waiters = g_teardown_waiters;
  g_teardown_waiters = NULL;
  uv_mutex_unlock(&g_mutex);

  // Resolve every waiting caller's promise on its own env/thread via its own
  // tsfn -- napi_deferred/napi_env are thread-affine, so this cannot be done
  // from this waiter thread directly. napi_call_threadsafe_function only
  // ENQUEUES the call for the target thread to run later; it does not wait
  // for call_js_teardown_done to execute. So do NOT free/release here --
  // call_js_teardown_done owns and releases each node after it actually runs
  // (freeing it here instead would be a use-after-free the moment the
  // enqueued callback later dereferences it).
  while (waiters != NULL) {
    teardown_waiter_t* next = waiters->next;
    napi_status enq = napi_call_threadsafe_function(waiters->tsfn, waiters, napi_tsfn_blocking);
    if (enq != napi_ok) {
      // The waiter's env is tearing down (napi_closing): call_js_teardown_done
      // will never run, so it can neither resolve waiter->deferred nor release
      // the tsfn nor free the node. Free the node here instead of leaking it
      // (one leak per Worker that terminated while this teardown was pending).
      // Do NOT napi_release_threadsafe_function(waiters->tsfn, ...): a
      // napi_closing return already discharges this tsfn's registration (Node
      // may have destroyed the tsfn object), so a release would be a
      // double-discharge/UAF -- same reasoning as the sentinel-enqueue-failure
      // paths in streaming_thread_fn/transform_thread_fn. The unresolved
      // deferred is env-affine and reclaimed when the dead env is destroyed.
      free(waiters);
    }
    waiters = next;
  }
}

// Creates a promise, a threadsafe function bound to call_js_teardown_done for
// THIS call's env, and a teardown_waiter_t node carrying both. The node is
// NOT linked into g_teardown_waiters here -- the caller does that under
// g_mutex, since callers append at two different points in napi_cleanup
// (case 3: joining an existing pending teardown; case 5: starting a new one).
// Returns NULL (and throws) if node allocation fails.
static teardown_waiter_t* teardown_waiter_create(napi_env env, napi_value* out_promise) {
  teardown_waiter_t* waiter = (teardown_waiter_t*)calloc(1, sizeof(teardown_waiter_t));
  if (waiter == NULL) {
    napi_throw_error(env, NULL, "Failed to allocate teardown waiter");
    return NULL;
  }
  waiter->env = env;

  if (napi_create_promise(env, &waiter->deferred, out_promise) != napi_ok) {
    free(waiter);
    napi_throw_error(env, NULL, "Failed to create teardown promise");
    return NULL;
  }

  napi_value resource_name;
  if (napi_create_string_utf8(env, "dwTeardown", NAPI_AUTO_LENGTH, &resource_name) != napi_ok) {
    free(waiter);
    napi_throw_error(env, NULL, "Failed to create teardown resource name");
    return NULL;
  }

  if (napi_create_threadsafe_function(
        env, NULL, NULL, resource_name, 0, 1, NULL, NULL, waiter, call_js_teardown_done, &waiter->tsfn
      ) != napi_ok) {
    free(waiter);
    napi_throw_error(env, NULL, "Failed to create teardown threadsafe function");
    return NULL;
  }

  return waiter;
}

// Creates an already-resolved promise -- used by napi_cleanup's two
// "nothing to wait for" branches (not-the-last-release, and last-release
// with no active ops) so the function's return type is uniformly "a
// promise" regardless of which branch runs.
static napi_value already_resolved_promise(napi_env env) {
  napi_deferred deferred;
  napi_value promise;
  napi_create_promise(env, &deferred, &promise);
  napi_value undefined;
  napi_get_undefined(env, &undefined);
  napi_resolve_deferred(env, deferred, undefined);
  return promise;
}

// Release n (>=0) initialization references at once, then make the teardown
// decision AT MOST ONCE. Caller holds g_mutex and this KEEPS it held. n==0 is a
// no-op. Equivalent to n serial single-releases for the COUNT, but guarantees
// the reached-zero teardown/waiter logic runs exactly once (a serial loop would
// re-enter the decision on an already-zero count). Used by env_init_cleanup
// (round-13 #5) to release all of a dead env's references from one decision
// point. (Previously also used by a single-release wrapper,
// isolate_ref_release_core_locked, retired in round-13 #5 once the per-engine
// finalize path stopped releasing init references directly.)
// Round-14 (#2/#3): retry a teardown that a prior last-release could not carry
// out. Caller holds g_mutex and this KEEPS it held. No-op unless a stranded
// live isolate is waiting (g_teardown_needed) with no owners and no teardown in
// progress and ops drained. Makes the reached-zero teardown decision at most
// once per call (same synchronous cleanup_thread_fn path as Case 4); on repeated
// failure it leaves g_teardown_needed set to retry on the next drain. Spawns+joins
// cleanup_thread_fn while holding g_mutex, exactly as the Case-4 /
// isolate_ref_release_n_locked g_active_ops==0 branch does; cleanup_thread_fn
// makes no N-API calls and writes only its caller-owned result struct, so this
// is deadlock-free and thread-safe from any drain site.
static void retry_stranded_teardown_locked(void) {
  if (!g_teardown_needed) return;
  if (g_ref_count > 0) { g_teardown_needed = false; return; }  // adopted -> keep
  if (g_teardown_state != TEARDOWN_NONE) return;               // a teardown drives
  if (g_active_ops > 0) return;                                // wait for drain
  if (g_detach_in_progress > 0 &&
      g_detach_in_progress_generation == g_isolate_generation) return;
  if (g_isolate == NULL) { g_teardown_needed = false; return; } // nothing to do
  uv_thread_t tid;
  uv_thread_options_t opts;
  opts.flags = UV_THREAD_HAS_STACK_SIZE;
  opts.stack_size = 2 * 1024 * 1024;
  cleanup_thread_result_t result = {CLEANUP_RETAIN, false};
  int spawn_rc = uv_thread_create_ex(&tid, &opts, cleanup_thread_fn, &result);
  if (spawn_rc == 0) uv_thread_join(&tid);
  if (g_test_hooks && result.teardown_callable) g_test_teardown_calls++;
  if (result.outcome == CLEANUP_TORN_DOWN) {
    if (g_test_engine_record_allocation_failure_generation == g_isolate_generation) {
      g_test_engine_record_allocation_failure_generation = 0;
    }
    g_thread = NULL;
    g_isolate = NULL;
    g_initialized = 0;
    g_ref_count = 0;
    g_teardown_needed = false;
  } else if (result.outcome == CLEANUP_UNRECOVERABLE) {
    // teardown+detach double failure (review #17 #1): abandon + leak; the helper
    // also clears g_teardown_needed so this stranded-teardown retry stops.
    abandon_unrecoverable_isolate_locked();
  }
  // else (CLEANUP_RETAIN): spawn/attach failed again -- leave g_teardown_needed
  // set so the next drain (or a later initialize() adoption) retries.
}

static void isolate_ref_release_n_locked(int n) {
  if (n <= 0) return;
  if (g_ref_count >= n) g_ref_count -= n; else g_ref_count = 0;
  if (g_ref_count > 0) return;              // other envs still hold references
  if (g_teardown_state != TEARDOWN_NONE) return;  // a teardown already drives

  if (g_active_ops == 0 &&
      !(g_detach_in_progress > 0 &&
        g_detach_in_progress_generation == g_isolate_generation)) {
    uv_thread_t tid;
    uv_thread_options_t opts;
    opts.flags = UV_THREAD_HAS_STACK_SIZE;
    opts.stack_size = 2 * 1024 * 1024;
    cleanup_thread_result_t result = {CLEANUP_RETAIN, false};
    int spawn_rc = uv_thread_create_ex(&tid, &opts, cleanup_thread_fn, &result);
    if (spawn_rc == 0) {
      uv_thread_join(&tid);
    }
    if (g_test_hooks && result.teardown_callable) g_test_teardown_calls++;
    if (result.outcome == CLEANUP_TORN_DOWN) {
      if (g_test_engine_record_allocation_failure_generation == g_isolate_generation) {
        g_test_engine_record_allocation_failure_generation = 0;
      }
      g_thread = NULL;
      g_isolate = NULL;
      g_initialized = 0;
      g_ref_count = 0;
    } else if (result.outcome == CLEANUP_UNRECOVERABLE) {
      // teardown+detach double failure (review #17 #1): abandon + leak the
      // isolate; do NOT arm the retry. Mirrors Python native.py leak-and-continue.
      abandon_unrecoverable_isolate_locked();
    } else if (g_isolate != NULL && g_ref_count == 0) {
      // Sync teardown failed (spawn or cleanup_thread_fn attach) with the isolate
      // still live and no owners: arm the retry signal (round-14 #3). g_active_ops
      // is already 0 here, but a later op could still re-pin; the flag is cleared
      // on adoption and retried on drain or by the next initialize() (review #6
      // #5). Documented residual: if NO later op or initialize() ever occurs, the
      // isolate lingers until process exit, where the OS reclaims it -- benign
      // (single process-lifetime isolate, no ref-count violation).
      g_teardown_needed = true;
    }
    return;
  }

  // g_active_ops > 0: defer to the waiter thread, no promises attached.
  g_teardown_state = TEARDOWN_PENDING_WAIT;
  g_teardown_cancelled = false;
  g_teardown_waiters = NULL;  // no JS caller waiting
  uv_thread_t waiter_tid;
  uv_thread_options_t waiter_opts;
  waiter_opts.flags = UV_THREAD_HAS_STACK_SIZE;
  waiter_opts.stack_size = 2 * 1024 * 1024;
  int spawn_rc = uv_thread_create_ex(&waiter_tid, &waiter_opts, teardown_waiter_thread_fn, NULL);
  if (spawn_rc == 0) {
    // Reclaim the waiter's OS thread handle without joining it (joining on this
    // JS thread would reintroduce the blocking-JS deadlock this deferral avoids).
    // libuv has no uv_thread_detach, and uv_thread_t IS the underlying platform
    // handle, so detach it directly: the OS reclaims the thread on exit, leaving
    // zero unreaped handles across repeated init/stream/cleanup cycles (review #20 #2).
#ifdef _WIN32
    CloseHandle(waiter_tid);
#else
    pthread_detach(waiter_tid);
#endif
  }
  if (spawn_rc != 0) {
    // Best-effort degradation: the waiter thread never started, so nothing will
    // drain the isolate. Restore g_ref_count to the true remaining ownership
    // (Σ init_refs, = 0 here) to keep the invariant, and ARM the retry signal so
    // the next op-completion drain retries teardown -- otherwise this live
    // isolate has zero owners and nothing would ever tear it down (round-14 #3).
    g_teardown_state = TEARDOWN_NONE;
    g_ref_count = env_init_refs_total_locked();
    if (g_isolate != NULL && g_ref_count == 0) g_teardown_needed = true;
  }
}

// Env-death hook for a per-env init record (round-13 #5). Registered once per
// env by initialize()'s first acquire (env_init_acquire_and_hook). Node runs
// env-cleanup hooks LIFO. In the normal initialize()-then-createEngine() order
// this hook is registered BEFORE any engine's bridge_env_cleanup for the same
// env, so it runs AFTER every engine bridge has finalized on a live isolate.
// The pathological raw-ffi order (createEngine() on this env -- succeeding
// because another env already initialized -- THEN initialize() here) can
// register this hook after an engine hook, so it may run first; that is still
// safe, because bridge_finalize_registry re-checks teardown state under g_mutex
// (registry removal no-ops on a torn-down isolate) and the napi_ref delete runs
// with env_still_alive=true on this env's own live thread. Releases exactly the
// references this env still holds (n), from a single env-scoped decision point:
// because g_ref_count == sum of init_refs, releasing this env's n reaches zero
// ONLY if no other env holds a reference, so an abandoned env can never tear the
// isolate down under a live env. Runs on the dying env's own thread with the
// env alive; does only g_mutex-guarded integer/list work + free (no env-affine
// napi calls).
// Round-14 (#1): the create path now enforces per-env ownership (an env with
// init_refs == 0 is rejected), so the pathological order below -- createEngine()
// on this env BEFORE its own initialize() -- is now rejected at the create call
// rather than relying on the finalize-time teardown-state re-check.
static void env_init_cleanup(void* arg) {
    env_init_rec_t* rec = (env_init_rec_t*)arg;
    if (rec == NULL) return;
    uv_mutex_lock(&g_mutex);
    // Unlink from g_env_recs if still present.
    env_init_rec_t** pp = &g_env_recs;
    while (*pp != NULL) {
        if (*pp == rec) { *pp = rec->next; break; }
        pp = &(*pp)->next;
    }
    int n = rec->init_refs;
    rec->init_refs = 0;
    free(rec);
    // Release all n references and make the teardown decision at most once.
    isolate_ref_release_n_locked(n);
    uv_mutex_unlock(&g_mutex);
}

// Promise-less core of an isolate-reference release. Caller holds g_mutex and
// this function KEEPS it held (does not unlock). Decrements g_ref_count and, on
// the last release, drives teardown WITHOUT binding any napi promise/waiter:
//   - g_active_ops == 0 -> synchronous cleanup_thread_fn (same as Case 4).
//   - g_active_ops  > 0 -> spawn the waiter thread with an EMPTY waiter list
//                          (TEARDOWN_PENDING_WAIT); it tears down (or is adopted)
//                          with no promises to resolve.
//   - a teardown already pending (TEARDOWN_NONE != state) -> nothing to do; the
//                          existing waiter will tear down; this release just
//                          drops the count.
// Used by env_init_cleanup (round-13 #5), the env-death hook, which has no
// live JS caller to hand a promise to.
//
// Deliberately does NOT call (or get called by) release_isolate_ref_locked
// below: that promise-bearing sibling needs per-caller promise plumbing this
// core omits on purpose (binding a waiter/promise to a tearing-down env is a
// thread-affinity hazard). They share the last-release *policy* only; see
// release_isolate_ref_locked's header comment for the promise-bearing twin.
//
// The isolate reference is now owned per env (env_init_rec), not per engine
// bridge (round-13 #5): initialize()'s acquire sites and env_init_cleanup are
// the only callers that mutate g_ref_count via this function, alongside
// release_isolate_ref_locked below for the explicit cleanup() path. The
// per-engine finalize path (bridge_env_cleanup / bridge_end_op) no longer
// touches g_ref_count at all, so a raw multi-engine-per-initialize() caller's
// abandoned env fires exactly one release for the whole balance it holds,
// regardless of how many engines it created.

// Releases ONE initialization reference on the shared isolate. Caller MUST
// hold g_mutex; this function UNLOCKS g_mutex before returning (the sync and
// waiter teardown paths both require dropping the lock). Returns the napi
// promise to hand back to the JS caller. This is napi_cleanup's original
// Case 1..5 body.
static napi_value release_isolate_ref_locked(napi_env env) {
  // Case 1/2: not the last release (or nothing was ever initialized). Decrement
  // only if positive -- a second cleanup() call while g_ref_count is already at
  // 0 (e.g. one already dropped it while teardown is pending) must not go
  // negative.
  // Round-13 (#5): an env may release only a reference IT owns. If this env has
  // no outstanding init reference (a cleanup() with no matching initialize() on
  // this env, or a double-cleanup()), do NOT touch g_ref_count -- releasing here
  // would steal another env's reference and could tear the isolate down under a
  // live user. No-op: resolve immediately. (g_ref_count == sum of init_refs, so
  // this env's zero balance means it contributes nothing to release.)
  env_init_rec_t* self = env_init_rec_find_locked(env);
  if (self == NULL || self->init_refs == 0) {
    uv_mutex_unlock(&g_mutex);
    return already_resolved_promise(env);
  }
  self->init_refs--;
  if (g_ref_count > 0) {
    g_ref_count--;
  }
  if (g_ref_count > 0) {
    uv_mutex_unlock(&g_mutex);
    return already_resolved_promise(env);
  }

  // Case 3: a teardown from an earlier cleanup() call is already pending
  // (possibly triggered from a different Worker/env). Join its waiter list
  // instead of spawning a second waiter thread.
  if (g_teardown_state != TEARDOWN_NONE) {
    napi_value promise;
    teardown_waiter_t* waiter = teardown_waiter_create(env, &promise);
    if (waiter == NULL) {
      uv_mutex_unlock(&g_mutex);
      return NULL;  // teardown_waiter_create already threw
    }
    waiter->next = g_teardown_waiters;
    g_teardown_waiters = waiter;
    uv_mutex_unlock(&g_mutex);
    return promise;
  }

  // Case 4: last release, no teardown pending, and nothing active -- the
  // original, unchanged synchronous fast path.
  if (g_active_ops == 0 &&
      !(g_detach_in_progress > 0 &&
        g_detach_in_progress_generation == g_isolate_generation)) {
    uv_thread_t tid;
    uv_thread_options_t opts;
    opts.flags = UV_THREAD_HAS_STACK_SIZE;
    opts.stack_size = 2 * 1024 * 1024;
    // result is cleanup_thread_fn's out-param (mirrors teardown_waiter_thread_fn's
    // outcome exactly): must be initialized to CLEANUP_RETAIN before the thread runs
    // so a spawn that never happens, or the attach-failure early-return path (which
    // never touches it), leaves the live isolate retained + the retry armed.
    // uv_thread_join is synchronous, so when spawn_rc == 0 this stack variable safely
    // outlives the thread's write to it.
    cleanup_thread_result_t result = {CLEANUP_RETAIN, false};
    int spawn_rc = uv_thread_create_ex(&tid, &opts, cleanup_thread_fn, &result);
    if (spawn_rc == 0) {
      uv_thread_join(&tid);
    }
    // Only clear global state if the isolate was actually torn down (or there
    // was nothing to tear down). If spawn failed, the thread never ran and
    // result stays CLEANUP_RETAIN -- leave the globals set rather than orphaning a live
    // isolate (unreachable via these globals, could never be torn down), which
    // is a strict improvement over unconditionally clearing them here. Same
    // reasoning for cleanup_thread_fn's internal attach-failure path: the
    // isolate is still alive, g_initialized stays 1, and g_ref_count was
    // already decremented to 0 above without being reset here, so a later
    // initialize() correctly ref-counts the surviving isolate instead of
    // building a second one (identical semantics to teardown_waiter_thread_fn's
    // attach-failure path).
    if (g_test_hooks && result.teardown_callable) g_test_teardown_calls++;
    if (result.outcome == CLEANUP_TORN_DOWN) {
      if (g_test_engine_record_allocation_failure_generation == g_isolate_generation) {
        g_test_engine_record_allocation_failure_generation = 0;
      }
      g_thread = NULL;
      g_isolate = NULL;
      g_initialized = 0;
      g_ref_count = 0;
    } else if (result.outcome == CLEANUP_UNRECOVERABLE) {
      // teardown+detach double failure (review #17 #1): abandon + leak the
      // isolate; the promise below still RESOLVES (deliberate, per the note that
      // follows). The helper emits its own stderr diagnostic. Mirrors Python
      // native.py leak-and-continue.
      abandon_unrecoverable_isolate_locked();
    } else if (g_isolate != NULL && g_ref_count == 0) {
      // cleanup_thread_fn spawn/attach failed: the isolate is still live with
      // zero owners. Arm the retry signal so a later op-completion drain or the
      // next initialize() (review #6 #5) tears it down instead of stranding it —
      // mirrors the twin arm in isolate_ref_release_n_locked. Documented residual:
      // if no later op or initialize() ever runs, the isolate lingers to process
      // exit (OS reclaims it) -- benign, no ref-count violation.
      g_teardown_needed = true;
      // Make the failure OBSERVABLE (review #10 #5): the promise below still
      // RESOLVES (see the deliberate-resolve note), so without a diagnostic a
      // failed final teardown would be entirely silent. Mirrors the stderr notice
      // Python emits in _release_isolate on the same failure (native.py).
      fprintf(stderr,
              "[DataWeave Node addon] GraalVM isolate teardown failed on cleanup(); "
              "the isolate is retained and teardown will be retried on the next "
              "initialize() or op completion.\n");
    }
    // Deliberate design (review #10 #5): cleanup() RESOLVES even when the final
    // Graal teardown failed above -- it does NOT reject. Teardown failure is a
    // recoverable, retryable condition (the isolate is retained and
    // g_teardown_needed is armed for a later retry), not a caller error, and this
    // file never uses napi_reject_deferred: run/streaming/transform failures also
    // surface as RESOLVED values. Rejecting here would break the isolate
    // adoption/coalescing contract (a still-live PENDING_WAIT isolate a concurrent
    // initialize() may adopt) and the existing cleanup() tests. The failure stays
    // observable via the armed retry + the stderr diagnostic above. Parity:
    // Python's _release_isolate arms _teardown_needed and logs to stderr on the
    // same failure rather than surfacing a hard error (native.py).
    uv_mutex_unlock(&g_mutex);
    return already_resolved_promise(env);
  }

  // Case 5: last release, but streaming/transform ops are still active.
  // Defer teardown to a dedicated waiter thread instead of blocking this JS
  // thread -- this is the deadlock fix. g_initialized/g_isolate/g_thread stay
  // set until the waiter thread finishes, matching today's behavior of
  // treating "still tearing down" as "still initialized" for concurrent
  // initialize() calls (see Task 3).
  g_teardown_state = TEARDOWN_PENDING_WAIT;
  g_teardown_cancelled = false;
  napi_value promise;
  teardown_waiter_t* waiter = teardown_waiter_create(env, &promise);
  if (waiter == NULL) {
    // The last reference was already dropped (g_ref_count == 0) but we cannot
    // build the waiter to drain the isolate. Arm the retry signal so the op
    // drain retries teardown -- without it this live isolate would have zero
    // owners and nothing to tear it down (round-14 #2).
    g_teardown_state = TEARDOWN_NONE;
    if (g_isolate != NULL && g_ref_count == 0) g_teardown_needed = true;
    uv_mutex_unlock(&g_mutex);
    return NULL;  // teardown_waiter_create already threw
  }
  waiter->next = NULL;
  g_teardown_waiters = waiter;

  uv_thread_t waiter_tid;
  uv_thread_options_t waiter_opts;
  waiter_opts.flags = UV_THREAD_HAS_STACK_SIZE;
  waiter_opts.stack_size = 2 * 1024 * 1024;
  int spawn_rc = uv_thread_create_ex(&waiter_tid, &waiter_opts, teardown_waiter_thread_fn, NULL);
  // Deliberately not JOINED -- this thread finishes on its own and resolves
  // every waiter's promise itself; joining here would reintroduce exactly
  // the blocking-JS-thread problem this fix removes. But we must still reclaim
  // its OS thread handle, so DETACH it: libuv has no uv_thread_detach and
  // uv_thread_t IS the platform handle, so the OS reclaims the thread on exit
  // with zero unreaped handles across cycles (review #20 #2).
  if (spawn_rc == 0) {
#ifdef _WIN32
    CloseHandle(waiter_tid);
#else
    pthread_detach(waiter_tid);
#endif
  }

  if (spawn_rc != 0) {
    // Best-effort degradation: if the waiter thread never starts, nothing
    // will ever clear g_teardown_state, which would otherwise permanently
    // wedge every future initialize()/cleanup() call. Roll back to "teardown
    // did not start" -- the isolate stays up and the caller's promise still
    // resolves, mirroring the fast path's ignore-teardown-return-code posture.
    g_teardown_state = TEARDOWN_NONE;
    g_teardown_waiters = NULL;

    napi_value undefined;
    napi_get_undefined(env, &undefined);
    napi_resolve_deferred(env, waiter->deferred, undefined);

    napi_release_threadsafe_function(waiter->tsfn, napi_tsfn_release);
    free(waiter);

    // Best-effort degradation: the isolate stays live (g_initialized/g_isolate
    // untouched) but no waiter will drain it. Restore g_ref_count to the true
    // remaining ownership (Σ init_refs) rather than a hardcoded 1: this env just
    // decremented its own init_refs above, and reaching Case 5 means g_ref_count
    // hit 0, so the sum is 0 (or whatever surviving envs still own). Hardcoding 1
    // here would strand a reference no env owns -- unreleasable by any cleanup()
    // or env-death hook -- and would break the invariant g_ref_count == Σ
    // init_refs. A later initialize() will re-acquire on the surviving isolate.
    g_ref_count = env_init_refs_total_locked();
    // Arm the retry signal: the isolate stays live with no owners and no waiter,
    // so the op-completion drain must retry teardown (round-14 #2).
    if (g_isolate != NULL && g_ref_count == 0) g_teardown_needed = true;

    uv_mutex_unlock(&g_mutex);
    return promise;
  }

  uv_mutex_unlock(&g_mutex);
  return promise;
}

static napi_value napi_cleanup(napi_env env, napi_callback_info info) {
  if (native_callback_active()) return throw_callback_reentrancy(env);
  (void)info;
  uv_mutex_lock(&g_mutex);
  return release_isolate_ref_locked(env);  // unlocks g_mutex, returns the promise
}

// --- Module init ---

static void init_g_mutex(void) {
  uv_mutex_init(&g_mutex);
  uv_mutex_init(&g_test_output_mutex);
  uv_cond_init(&g_teardown_cond);
  g_native_callback_depth_status = uv_key_create(&g_native_callback_depth);
}

// --- Test-only N-API entrypoints (review #12 #3 / #13) ---
// Registered only when DATAWEAVE_TEST_HOOKS is set (see Init). They let the Node
// strand regression test arm a single forced live-isolate strand and inspect the
// resulting bookkeeping. None of these touch thread-affine napi state beyond
// creating a plain return value on the calling env, so they are callable from any
// JS thread (main or Worker) that loaded this addon.
static napi_value napi_test_force_strand_once(napi_env env, napi_callback_info info) {
    (void)info;
    uv_mutex_lock(&g_mutex);
    g_test_force_strand_once = true;
    uv_mutex_unlock(&g_mutex);
    return NULL;
}

static napi_value napi_test_stranded_count(napi_env env, napi_callback_info info) {
    (void)info;
    long long n = 0;
    uv_mutex_lock(&g_mutex);
    for (engine_bridge_t* b = g_stranded_bridges; b != NULL; b = b->next) n++;
    uv_mutex_unlock(&g_mutex);
    napi_value out; napi_create_int64(env, (int64_t)n, &out);
    return out;
}

static napi_value napi_test_resolver_ref_delete_count(napi_env env, napi_callback_info info) {
    (void)info;
    uv_mutex_lock(&g_mutex);
    long long n = g_test_resolver_ref_deletes;
    uv_mutex_unlock(&g_mutex);
    napi_value out; napi_create_int64(env, (int64_t)n, &out);
    return out;
}

static detach_site_t detach_site_from_name(const char* name, size_t length) {
  #define DETACH_SITE_MATCH(value, site) \
    if (length == sizeof(value) - 1 && memcmp(name, value, sizeof(value) - 1) == 0) return site
  DETACH_SITE_MATCH("bridge-finalize", DETACH_SITE_BRIDGE_FINALIZE);
  DETACH_SITE_MATCH("stream-worker", DETACH_SITE_STREAM_WORKER);
  DETACH_SITE_MATCH("transform-worker", DETACH_SITE_TRANSFORM_WORKER);
  DETACH_SITE_MATCH("create-engine", DETACH_SITE_CREATE_ENGINE);
  DETACH_SITE_MATCH("create-rollback", DETACH_SITE_CREATE_ROLLBACK);
  DETACH_SITE_MATCH("resolver-create", DETACH_SITE_RESOLVER_CREATE);
  DETACH_SITE_MATCH("unknown-destroy", DETACH_SITE_UNKNOWN_DESTROY);
  DETACH_SITE_MATCH("synchronous-run", DETACH_SITE_SYNCHRONOUS_RUN);
  #undef DETACH_SITE_MATCH
  return DETACH_SITE_NONE;
}

static napi_value napi_test_force_detach_failure_once(
    napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1];
  napi_valuetype type;
  char site_name[64];
  size_t length = 0;
  if (napi_get_cb_info(env, info, &argc, argv, NULL, NULL) != napi_ok || argc < 1 ||
      napi_typeof(env, argv[0], &type) != napi_ok || type != napi_string ||
      napi_get_value_string_utf8(env, argv[0], NULL, 0, &length) != napi_ok ||
      length >= sizeof(site_name)) {
    napi_throw_type_error(env, NULL, "A detach site string is required");
    return NULL;
  }
  size_t copied = 0;
  if (
      napi_get_value_string_utf8(
        env, argv[0], site_name, sizeof(site_name), &copied) != napi_ok ||
      copied != length || memchr(site_name, '\0', length) != NULL) {
    napi_throw_type_error(env, NULL, "A detach site string is required");
    return NULL;
  }
  detach_site_t site = detach_site_from_name(site_name, length);
  if (site == DETACH_SITE_NONE) {
    napi_throw_range_error(env, NULL, "Unknown detach site");
    return NULL;
  }
  uv_mutex_lock(&g_mutex);
  if (g_test_detach_failure_site != DETACH_SITE_NONE) {
    uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "A detach failure is already armed");
    return NULL;
  }
  g_test_detach_failure_site = site;
  uv_mutex_unlock(&g_mutex);
  return NULL;
}

static napi_value napi_test_isolate_poisoned(napi_env env, napi_callback_info info) {
  (void)info;
  uv_mutex_lock(&g_mutex);
  bool poisoned = g_isolate_poisoned;
  uv_mutex_unlock(&g_mutex);
  napi_value out;
  napi_get_boolean(env, poisoned, &out);
  return out;
}

static napi_value test_uint64_counter(napi_env env, uint64_t value) {
  napi_value out;
  if (napi_create_bigint_uint64(env, value, &out) != napi_ok) {
    napi_throw_error(env, NULL, "Failed to create test counter");
    return NULL;
  }
  return out;
}

static napi_value napi_test_isolate_creation_count(napi_env env, napi_callback_info info) {
  (void)info;
  uv_mutex_lock(&g_mutex);
  uint64_t value = g_test_isolate_creations;
  uv_mutex_unlock(&g_mutex);
  return test_uint64_counter(env, value);
}

static napi_value napi_test_teardown_call_count(napi_env env, napi_callback_info info) {
  (void)info;
  uv_mutex_lock(&g_mutex);
  uint64_t value = g_test_teardown_calls;
  uv_mutex_unlock(&g_mutex);
  return test_uint64_counter(env, value);
}

static napi_value napi_test_abandoned_isolate_count(napi_env env, napi_callback_info info) {
  (void)info;
  uv_mutex_lock(&g_mutex);
  uint64_t value = g_test_abandoned_isolates;
  uv_mutex_unlock(&g_mutex);
  return test_uint64_counter(env, value);
}

static napi_value napi_test_forced_detach_failure_count(
    napi_env env, napi_callback_info info) {
  (void)info;
  uv_mutex_lock(&g_mutex);
  uint64_t value = g_test_forced_detach_failures;
  uv_mutex_unlock(&g_mutex);
  return test_uint64_counter(env, value);
}

static napi_value napi_test_hold_next_async_op(napi_env env, napi_callback_info info) {
    (void)info;
    uv_mutex_lock(&g_mutex);
    if (g_test_hold_next_async_op || g_test_async_op_held) {
        uv_mutex_unlock(&g_mutex);
        napi_throw_error(env, NULL, "A test async operation gate is already armed");
        return NULL;
    }
    g_test_hold_next_async_op = true;
    g_test_release_async_op = false;
    uv_mutex_unlock(&g_mutex);
    return NULL;
}

static napi_value napi_test_async_op_held(napi_env env, napi_callback_info info) {
    (void)info;
    uv_mutex_lock(&g_mutex);
    bool held = g_test_async_op_held;
    uv_mutex_unlock(&g_mutex);
    napi_value out;
    napi_get_boolean(env, held, &out);
    return out;
}

static napi_value napi_test_release_async_op(napi_env env, napi_callback_info info) {
  (void)env;
  (void)info;
  uv_mutex_lock(&g_mutex);
  g_test_release_async_op = true;
  uv_cond_broadcast(&g_teardown_cond);
  uv_mutex_unlock(&g_mutex);
  return NULL;
}

static napi_value napi_test_hold_detach_publication(
    napi_env env, napi_callback_info info) {
  (void)info;
  uv_mutex_lock(&g_mutex);
  if (g_test_hold_next_detach_publication || g_test_detach_publication_held) {
    uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "A detach publication gate is already armed");
    return NULL;
  }
  g_test_hold_next_detach_publication = true;
  g_test_release_detach_publication = false;
  uv_mutex_unlock(&g_mutex);
  return NULL;
}

static napi_value napi_test_detach_publication_held(
    napi_env env, napi_callback_info info) {
  (void)info;
  uv_mutex_lock(&g_mutex);
  bool held = g_test_detach_publication_held;
  uv_mutex_unlock(&g_mutex);
  napi_value out;
  napi_get_boolean(env, held, &out);
  return out;
}

static napi_value napi_test_detach_publication_waiters(
    napi_env env, napi_callback_info info) {
  (void)info;
  uv_mutex_lock(&g_mutex);
  uint64_t waiters = g_detach_publication_waiters;
  uv_mutex_unlock(&g_mutex);
  return test_uint64_counter(env, waiters);
}

static napi_value napi_test_release_detach_publication(
    napi_env env, napi_callback_info info) {
  (void)env;
  (void)info;
  uv_mutex_lock(&g_mutex);
  g_test_release_detach_publication = true;
  uv_cond_broadcast(&g_teardown_cond);
  uv_mutex_unlock(&g_mutex);
  return NULL;
}

static napi_value napi_test_live_stranded_resolver_ref_count(
    napi_env env, napi_callback_info info) {
  (void)info;
  uv_mutex_lock(&g_mutex);
  uint64_t count = g_test_live_resolver_refs;
  uv_mutex_unlock(&g_mutex);
  return test_uint64_counter(env, count);
}

static napi_value napi_test_fail_next_engine_record_allocation(
    napi_env env, napi_callback_info info) {
  (void)info;
  uv_mutex_lock(&g_mutex);
  if (!g_initialized || g_isolate == NULL || g_isolate_poisoned ||
      g_teardown_state != TEARDOWN_NONE || g_teardown_needed ||
      g_active_ops != 0 || g_isolate_generation == 0) {
    uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "A healthy initialized isolate generation is required");
    return NULL;
  }
  if (g_test_engine_record_allocation_failure_generation != 0) {
    uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "An engine record allocation failure is already armed");
    return NULL;
  }
  g_test_engine_record_allocation_failure_generation = g_isolate_generation;
  uv_mutex_unlock(&g_mutex);
  return NULL;
}

static napi_value napi_test_set_next_engine_handle(
    napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1];
  napi_valuetype type;
  double next_handle;
  if (napi_get_cb_info(env, info, &argc, argv, NULL, NULL) != napi_ok || argc < 1 ||
      napi_typeof(env, argv[0], &type) != napi_ok || type != napi_number ||
      napi_get_value_double(env, argv[0], &next_handle) != napi_ok ||
      next_handle <= 0 || next_handle > (double)MAX_SAFE_ENGINE_HANDLE ||
      next_handle != (double)(long long)next_handle) {
    napi_throw_range_error(env, NULL, "Next engine handle must be a positive safe integer");
    return NULL;
  }
  uv_mutex_lock(&g_mutex);
  if (g_bridges != NULL || g_stranded_bridges != NULL || g_active_ops != 0 ||
      g_next_engine_handle == 0 || next_handle < g_next_engine_handle) {
    uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "Next engine handle cannot be changed in the current state");
    return NULL;
  }
  g_next_engine_handle = (long long)next_handle;
  uv_mutex_unlock(&g_mutex);
  return NULL;
}

static napi_value napi_test_set_isolate_generation(
    napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1];
  uint64_t generation;
  bool lossless = false;
  if (napi_get_cb_info(env, info, &argc, argv, NULL, NULL) != napi_ok || argc < 1 ||
      napi_get_value_bigint_uint64(env, argv[0], &generation, &lossless) != napi_ok ||
      !lossless) {
    napi_throw_range_error(env, NULL, "Isolate generation must be a non-decreasing uint64 BigInt");
    return NULL;
  }
  uv_mutex_lock(&g_mutex);
  if (generation < g_isolate_generation ||
      !g_initialized || g_isolate == NULL || g_isolate_poisoned ||
      g_teardown_state != TEARDOWN_NONE || g_active_ops != 0 ||
      g_bridges != NULL || g_stranded_bridges != NULL ||
      g_test_engine_record_allocation_failure_generation != 0) {
    uv_mutex_unlock(&g_mutex);
    napi_throw_error(env, NULL, "Isolate generation cannot be changed in the current state");
    return NULL;
  }
  g_isolate_generation = generation;
  uv_mutex_unlock(&g_mutex);
  return NULL;
}

static napi_value napi_test_isolate_generation(
    napi_env env, napi_callback_info info) {
  (void)info;
  uv_mutex_lock(&g_mutex);
  uint64_t generation = g_isolate_generation;
  uv_mutex_unlock(&g_mutex);
  return test_uint64_counter(env, generation);
}

typedef struct foreign_wrapped_value {
  uint64_t marker;
} foreign_wrapped_value_t;

static void foreign_wrapped_finalize(napi_env env, void* data, void* hint) {
  (void)env;
  (void)hint;
  free(data);
}

static napi_value napi_test_create_foreign_wrapped_object(
    napi_env env, napi_callback_info info) {
  (void)info;
  foreign_wrapped_value_t* value = calloc(1, sizeof(foreign_wrapped_value_t));
  if (value == NULL) {
    napi_throw_error(env, NULL, "OOM");
    return NULL;
  }
  value->marker = 424242;
  napi_value object;
  if (napi_create_object(env, &object) != napi_ok ||
      napi_wrap(env, object, value, foreign_wrapped_finalize, NULL, NULL) != napi_ok) {
    free(value);
    napi_throw_error(env, NULL, "Failed to create foreign wrapped object");
    return NULL;
  }
  return object;
}

static napi_value napi_test_fail_next_output_settlement(
    napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1];
  size_t length;
  char stage[64];
  if (napi_get_cb_info(env, info, &argc, argv, NULL, NULL) != napi_ok || argc < 1 ||
      napi_get_value_string_utf8(env, argv[0], stage, sizeof(stage), &length) != napi_ok) {
    napi_throw_type_error(env, NULL, "A settlement fault stage is required");
    return NULL;
  }
  output_settlement_fault_t fault = OUTPUT_SETTLEMENT_FAULT_NONE;
  if (strcmp(stage, "initial-create-generic") == 0) {
    fault = OUTPUT_SETTLEMENT_FAULT_INITIAL_CREATE_GENERIC;
  } else if (strcmp(stage, "initial-pending-exception") == 0) {
    fault = OUTPUT_SETTLEMENT_FAULT_INITIAL_PENDING_EXCEPTION;
  } else if (strcmp(stage, "initial-call-generic-after-call") == 0) {
    fault = OUTPUT_SETTLEMENT_FAULT_INITIAL_CALL_GENERIC_AFTER_CALL;
  } else if (strcmp(stage, "initial-call-pending-after-call") == 0) {
    fault = OUTPUT_SETTLEMENT_FAULT_INITIAL_CALL_PENDING_AFTER_CALL;
  } else if (strcmp(stage, "fallback-call-generic") == 0) {
    fault = OUTPUT_SETTLEMENT_FAULT_FALLBACK_CALL_GENERIC;
  } else if (strcmp(stage, "fallback-pending-exception") == 0) {
    fault = OUTPUT_SETTLEMENT_FAULT_FALLBACK_PENDING_EXCEPTION;
  } else if (strcmp(stage, "fallback-call-generic-after-call") == 0) {
    fault = OUTPUT_SETTLEMENT_FAULT_FALLBACK_CALL_GENERIC_AFTER_CALL;
  } else {
    napi_throw_range_error(env, NULL, "Unknown settlement fault stage");
    return NULL;
  }
  uv_mutex_lock(&g_test_output_mutex);
  if (g_test_next_output_settlement_fault != OUTPUT_SETTLEMENT_FAULT_NONE) {
    uv_mutex_unlock(&g_test_output_mutex);
    napi_throw_error(env, NULL, "An output settlement fault is already armed");
    return NULL;
  }
  g_test_next_output_settlement_fault = fault;
  uv_mutex_unlock(&g_test_output_mutex);
  return NULL;
}

static napi_value napi_test_fail_next_output_exception_clear(
    napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1];
  size_t length;
  char stage[64];
  if (napi_get_cb_info(env, info, &argc, argv, NULL, NULL) != napi_ok || argc < 1 ||
      napi_get_value_string_utf8(env, argv[0], stage, sizeof(stage), &length) != napi_ok) {
    napi_throw_type_error(env, NULL, "An exception clear fault stage is required");
    return NULL;
  }
  output_exception_clear_fault_t fault = OUTPUT_EXCEPTION_CLEAR_FAULT_NONE;
  if (strcmp(stage, "is-exception-pending") == 0) {
    fault = OUTPUT_EXCEPTION_CLEAR_FAULT_IS_PENDING;
  } else if (strcmp(stage, "get-and-clear-last-exception") == 0) {
    fault = OUTPUT_EXCEPTION_CLEAR_FAULT_GET_AND_CLEAR;
  } else {
    napi_throw_range_error(env, NULL, "Unknown exception clear fault stage");
    return NULL;
  }
  uv_mutex_lock(&g_test_output_mutex);
  if (g_test_next_output_exception_clear_fault !=
      OUTPUT_EXCEPTION_CLEAR_FAULT_NONE) {
    uv_mutex_unlock(&g_test_output_mutex);
    napi_throw_error(env, NULL, "An output exception clear fault is already armed");
    return NULL;
  }
  g_test_next_output_exception_clear_fault = fault;
  uv_mutex_unlock(&g_test_output_mutex);
  return NULL;
}

static napi_value napi_test_hold_next_output_delivery(
    napi_env env, napi_callback_info info) {
  (void)info;
  uv_mutex_lock(&g_test_output_mutex);
  if (g_test_hold_next_output_delivery || g_test_output_delivery_held) {
    uv_mutex_unlock(&g_test_output_mutex);
    napi_throw_error(env, NULL, "An output delivery gate is already armed");
    return NULL;
  }
  g_test_hold_next_output_delivery = true;
  g_test_release_output_delivery = false;
  uv_mutex_unlock(&g_test_output_mutex);
  return NULL;
}

static napi_value napi_test_held_output_delivery(
    napi_env env, napi_callback_info info) {
  (void)info;
  uv_mutex_lock(&g_test_output_mutex);
  bool held = g_test_output_delivery_held;
  uint64_t sequence = g_test_held_output_sequence;
  size_t bytes = g_test_held_output_bytes;
  uv_mutex_unlock(&g_test_output_mutex);

  napi_value out;
  napi_value value;
  if (napi_create_object(env, &out) != napi_ok ||
      napi_get_boolean(env, held, &value) != napi_ok ||
      napi_set_named_property(env, out, "held", value) != napi_ok ||
      napi_create_bigint_uint64(env, sequence, &value) != napi_ok ||
      napi_set_named_property(env, out, "sequence", value) != napi_ok ||
      napi_create_double(env, (double)bytes, &value) != napi_ok ||
      napi_set_named_property(env, out, "bytes", value) != napi_ok) {
    napi_throw_error(env, NULL, "Failed to create held output delivery state");
    return NULL;
  }
  return out;
}

static napi_value napi_test_release_output_delivery(
    napi_env env, napi_callback_info info) {
  (void)env;
  (void)info;
  uv_mutex_lock(&g_test_output_mutex);
  g_test_release_output_delivery = true;
  if (!g_test_output_delivery_held) {
    g_test_hold_next_output_delivery = false;
    g_test_held_output_sequence = 0;
    g_test_held_output_bytes = 0;
  }
  uv_mutex_unlock(&g_test_output_mutex);
  return NULL;
}

static void set_named_size(napi_env env, napi_value object, const char* name, size_t value) {
  napi_value out;
  napi_create_double(env, (double)value, &out);
  napi_set_named_property(env, object, name, out);
}

static void set_named_bool(napi_env env, napi_value object, const char* name, bool value) {
  napi_value out;
  napi_get_boolean(env, value, &out);
  napi_set_named_property(env, object, name, out);
}

static napi_value output_stats_value(napi_env env, const output_flow_stats_t* stats) {
  napi_value out;
  napi_value value;
  napi_create_object(env, &out);
  napi_create_double(env, (double)stats->operation_id, &value);
  napi_set_named_property(env, out, "operationId", value);
  set_named_size(env, out, "outstandingBytes", stats->outstanding_bytes);
  set_named_size(env, out, "outstandingChunks", stats->outstanding_chunks);
  set_named_size(env, out, "peakBufferedBytes", stats->peak_buffered_bytes);
  set_named_size(env, out, "peakBufferedChunks", stats->peak_buffered_chunks);
  set_named_size(env, out, "largestChunkBytes", stats->largest_chunk_bytes);
  set_named_size(env, out, "highBytes", OUTPUT_HIGH_BYTES);
  set_named_size(env, out, "lowBytes", OUTPUT_LOW_BYTES);
  set_named_size(env, out, "highChunks", OUTPUT_HIGH_CHUNKS);
  set_named_size(env, out, "lowChunks", OUTPUT_LOW_CHUNKS);
  set_named_bool(env, out, "paused", stats->paused);
  set_named_bool(env, out, "cancelled", stats->cancelled);
  set_named_bool(env, out, "done", stats->done);
  napi_create_int64(env, stats->live_flows, &value);
  napi_set_named_property(env, out, "liveFlows", value);
  return out;
}

static napi_value napi_test_output_stats(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1];
  napi_get_cb_info(env, info, &argc, argv, NULL, NULL);
  uint64_t requested = 0;
  if (argc == 1) {
    int64_t id;
    if (napi_get_value_int64(env, argv[0], &id) != napi_ok || id < 0) {
      napi_throw_type_error(env, NULL, "operationId must be a non-negative integer");
      return NULL;
    }
    requested = (uint64_t)id;
  }
  output_flow_stats_t stats;
  uv_mutex_lock(&g_test_output_mutex);
  stats = g_test_last_output_stats;
  stats.live_flows = g_test_live_output_flows;
  uv_mutex_unlock(&g_test_output_mutex);
  if (requested != 0 && requested != stats.operation_id) {
    napi_throw_error(env, NULL, "Output operation statistics are no longer current");
    return NULL;
  }
  return output_stats_value(env, &stats);
}

static napi_value napi_test_output_operation_id(napi_env env, napi_callback_info info) {
  size_t argc = 1;
  napi_value argv[1];
  if (napi_get_cb_info(env, info, &argc, argv, NULL, NULL) != napi_ok || argc < 1) {
    napi_throw_type_error(env, NULL, "An output controller is required");
    return NULL;
  }
  bool tagged = false;
  if (napi_check_object_type_tag(env, argv[0], &OUTPUT_CONTROLLER_TAG, &tagged) != napi_ok ||
      !tagged) {
    napi_throw_type_error(env, NULL, "Invalid output controller");
    return NULL;
  }
  output_controller_t* holder = NULL;
  if (napi_unwrap(env, argv[0], (void**)&holder) != napi_ok || holder == NULL) {
    napi_throw_type_error(env, NULL, "Invalid output controller");
    return NULL;
  }
  napi_value out;
  napi_create_double(env, (double)holder->operation_id, &out);
  return out;
}

static bool export_function(napi_env env, napi_value exports, const char* name,
                            napi_callback callback) {
  napi_value fn;
  return napi_create_function(
           env, name, NAPI_AUTO_LENGTH, callback, NULL, &fn) == napi_ok &&
         napi_set_named_property(env, exports, name, fn) == napi_ok;
}

static napi_value Init(napi_env env, napi_value exports) {
  uv_once(&g_mutex_once, init_g_mutex);

  if (g_native_callback_depth_status != 0) {
    char message[128];
    snprintf(message, sizeof(message),
             "Failed to initialize native callback state (libuv error %d)",
             g_native_callback_depth_status);
    napi_throw_error(env, NULL, message);
    return NULL;
  }

  if (!export_function(env, exports, "initialize", napi_initialize) ||
      !export_function(env, exports, "createEngine", napi_create_engine) ||
      !export_function(env, exports, "createEngineWithResolver", napi_create_engine_with_resolver) ||
      !export_function(env, exports, "destroyEngine", napi_destroy_engine) ||
      !export_function(env, exports, "runScriptEngine", napi_run_script_engine) ||
      !export_function(env, exports, "runScriptStreamingEngine", napi_run_script_streaming_engine) ||
      !export_function(env, exports, "runScriptTransformEngine", napi_run_script_transform_engine) ||
      !export_function(env, exports, "cleanup", napi_cleanup)) {
    napi_throw_error(env, NULL, "Failed to register DataWeave native exports");
    return NULL;
  }

  // Test-only entrypoints, registered only when the process opts in via
  // DATAWEAVE_TEST_HOOKS (non-empty). getenv() is safe here: Init runs once per
  // env on the main JS thread at module load, before any engine/finalize can run,
  // so this write-once flag is visible to every later reader without a barrier.
  const char* test_hooks = getenv("DATAWEAVE_TEST_HOOKS");
  if (test_hooks != NULL && test_hooks[0] != '\0') {
    g_test_hooks = true;
    if (!export_function(env, exports, "__test_forceStrandOnce", napi_test_force_strand_once) ||
        !export_function(env, exports, "__test_strandedCount", napi_test_stranded_count) ||
        !export_function(env, exports, "__test_resolverRefDeleteCount", napi_test_resolver_ref_delete_count) ||
        !export_function(env, exports, "__test_forceDetachFailureOnce", napi_test_force_detach_failure_once) ||
        !export_function(env, exports, "__test_isolatePoisoned", napi_test_isolate_poisoned) ||
        !export_function(env, exports, "__test_isolateCreationCount", napi_test_isolate_creation_count) ||
        !export_function(env, exports, "__test_teardownCallCount", napi_test_teardown_call_count) ||
        !export_function(env, exports, "__test_abandonedIsolateCount", napi_test_abandoned_isolate_count) ||
        !export_function(env, exports, "__test_forcedDetachFailureCount", napi_test_forced_detach_failure_count) ||
        !export_function(env, exports, "__test_failNextEngineRecordAllocation", napi_test_fail_next_engine_record_allocation) ||
        !export_function(env, exports, "__test_setNextEngineHandle", napi_test_set_next_engine_handle) ||
        !export_function(env, exports, "__test_setIsolateGeneration", napi_test_set_isolate_generation) ||
        !export_function(env, exports, "__test_isolateGeneration", napi_test_isolate_generation) ||
        !export_function(env, exports, "__test_holdNextAsyncOp", napi_test_hold_next_async_op) ||
        !export_function(env, exports, "__test_asyncOpHeld", napi_test_async_op_held) ||
        !export_function(env, exports, "__test_releaseAsyncOp", napi_test_release_async_op) ||
        !export_function(env, exports, "__test_holdDetachPublication", napi_test_hold_detach_publication) ||
        !export_function(env, exports, "__test_detachPublicationHeld", napi_test_detach_publication_held) ||
        !export_function(env, exports, "__test_detachPublicationWaiters", napi_test_detach_publication_waiters) ||
        !export_function(env, exports, "__test_releaseDetachPublication", napi_test_release_detach_publication) ||
        !export_function(env, exports, "__test_liveStrandedResolverRefCount", napi_test_live_stranded_resolver_ref_count) ||
        !export_function(env, exports, "__test_outputStats", napi_test_output_stats) ||
        !export_function(env, exports, "__test_outputOperationId", napi_test_output_operation_id) ||
        !export_function(env, exports, "__test_createForeignWrappedObject", napi_test_create_foreign_wrapped_object) ||
        !export_function(env, exports, "__test_failNextOutputSettlement", napi_test_fail_next_output_settlement) ||
        !export_function(env, exports, "__test_failNextOutputExceptionClear", napi_test_fail_next_output_exception_clear) ||
        !export_function(env, exports, "__test_holdNextOutputDelivery", napi_test_hold_next_output_delivery) ||
        !export_function(env, exports, "__test_heldOutputDelivery", napi_test_held_output_delivery) ||
        !export_function(env, exports, "__test_releaseOutputDelivery", napi_test_release_output_delivery)) {
      napi_throw_error(env, NULL, "Failed to register DataWeave native test exports");
      return NULL;
    }
  }

  return exports;
}

NAPI_MODULE(NODE_GYP_MODULE_NAME, Init)
