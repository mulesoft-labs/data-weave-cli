package org.mule.weave.lib;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.PointerBase;
import org.graalvm.word.WordFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * GraalVM native entry points exposed for FFI consumers.
 *
 * <p>This class provides C-callable functions to execute DataWeave scripts and to free the returned
 * unmanaged strings.</p>
 */
public class NativeLib {

    /**
     * The exact JSON error payload returned by the per-engine entrypoints
     * ({@link #runScriptEngine}, {@link #runScriptCallbackEngine},
     * {@link #runScriptInputOutputCallbackEngine}) when {@code handle} does not identify a
     * live engine. Package-visible (rather than embedded as a string literal at each call
     * site) so the exact contract can be asserted directly from a JVM unit test, since the
     * {@code @CEntryPoint} methods themselves rely on GraalVM word types that only resolve
     * inside a compiled native image.
     */
    static final String UNKNOWN_ENGINE_HANDLE_JSON = "{\"success\":false,\"error\":\"Unknown engine handle\"}";

    /**
     * Frees a C string previously returned by engine entrypoints.
     *
     * @param thread the isolate thread (automatically provided by GraalVM)
     * @param pointer the pointer to the unmanaged C string to free; if null, this is a no-op
     */
    @CEntryPoint(name = "free_cstring",
            exceptionHandler = CEntryPointExceptionHandlers.ReturnVoid.class)
    public static void freeCString(IsolateThread thread, CCharPointer pointer) {
        if (pointer.isNull()) {
            return;
        }
        UnmanagedMemory.free(pointer);
    }

    // ── Callback-based Streaming API ─────────────────────────────────────

    private static final int CALLBACK_BUFFER_SIZE = 8 * 1024;

    /**
     * Runs the streaming write-callback loop shared by the per-engine entrypoint
     * ({@link #runScriptCallbackEngine}).
     */
    private static CCharPointer streamToWriteCallback(
            ScriptRuntime runtime, String dwScript, String inputs,
            NativeCallbacks.WriteCallback writeCallback, PointerBase ctx) {
        StreamSession session = runtime.runStreaming(dwScript, inputs);

        if (session.isError()) {
            return toUnmanagedCString("{\"success\":false,\"error\":\""
                    + escapeJsonString(session.getError()) + "\"}");
        }

        try {
            byte[] buf = new byte[CALLBACK_BUFFER_SIZE];
            CCharPointer nativeBuf = UnmanagedMemory.malloc(CALLBACK_BUFFER_SIZE);
            try {
                int n;
                while ((n = session.read(buf, buf.length)) > 0) {
                    for (int i = 0; i < n; i++) {
                        nativeBuf.write(i, buf[i]);
                    }
                    int rc = writeCallback.invoke(ctx, nativeBuf, n);
                    if (rc != 0) {
                        return toUnmanagedCString("{\"success\":false,\"error\":\""
                                + "Write callback returned error: " + rc + "\"}");
                    }
                }
            } finally {
                UnmanagedMemory.free(nativeBuf);
            }
        } catch (IOException e) {
            return toUnmanagedCString("{\"success\":false,\"error\":\""
                    + escapeJsonString(e.getMessage()) + "\"}");
        } finally {
            session.closeStream();
        }

        return toUnmanagedCString("{\"success\":true"
                + ",\"mimeType\":\"" + session.getMimeType() + "\""
                + ",\"charset\":\"" + session.getCharset() + "\""
                + ",\"binary\":" + session.isBinary()
                + "}");
    }

    /**
     * Runs the input-feeder + output-streaming loop shared by the per-engine entrypoint
     * ({@link #runScriptInputOutputCallbackEngine}).
     */
    private static CCharPointer transformViaCallbacks(
            ScriptRuntime runtime, String dwScript, String inputs,
            String inName, String inMime, String inCharset,
            NativeCallbacks.ReadCallback readCallback, NativeCallbacks.WriteCallback writeCallback,
            PointerBase ctx) {

        // Register the input session and merge its stream-handle entry into the inputs JSON.
        // This setup can throw on a malformed `inputs` string; setUpInputSession closes the
        // handle and yields an error envelope in that case, so nothing leaks and no exception
        // escapes this @CEntryPoint before the feeder is even started.
        InputSetup setup = setUpInputSession(inputs, inName, inMime, inCharset);
        if (setup.errorEnvelope != null) {
            return toUnmanagedCString(setup.errorEnvelope);
        }
        long inputHandle = setup.handle;

        InputCallbackFeeder feederRunnable = null;
        Thread feeder = null;
        boolean cleaned = false;
        try {
            // Start a background thread that calls the readCallback and feeds data into the pipe.
            // Word types (CCharPointer, CFunctionPointer, PointerBase) cannot be captured in
            // lambdas in GraalVM Native Image, so we use an explicit Runnable that stores their
            // raw addresses and reconstitutes them via WordFactory.
            final long readCallbackAddr = readCallback.rawValue();
            final long ctxAddr = ctx.rawValue();
            feederRunnable = new InputCallbackFeeder(readCallbackAddr, ctxAddr, setup.session);
            feeder = new Thread(feederRunnable, "dw-input-callback-feeder");
            feeder.setDaemon(true);
            feeder.start();

            // Execute the script and stream output via the writeCallback
            StreamSession session = runtime.runStreaming(dwScript, setup.mergedInputs);

            if (session.isError()) {
                return toUnmanagedCString("{\"success\":false,\"error\":\""
                        + escapeJsonString(session.getError()) + "\"}");
            }

            try {
                byte[] buf = new byte[CALLBACK_BUFFER_SIZE];
                CCharPointer writeBuf = UnmanagedMemory.malloc(CALLBACK_BUFFER_SIZE);
                try {
                    int n;
                    while ((n = session.read(buf, buf.length)) > 0) {
                        for (int i = 0; i < n; i++) {
                            writeBuf.write(i, buf[i]);
                        }
                        int rc = writeCallback.invoke(ctx, writeBuf, n);
                        if (rc != 0) {
                            return toUnmanagedCString("{\"success\":false,\"error\":\""
                                    + "Write callback returned error: " + rc + "\"}");
                        }
                    }
                } finally {
                    UnmanagedMemory.free(writeBuf);
                }
            } finally {
                session.closeStream();
            }

            // Join the feeder and select the success/error envelope. Delegated to a helper that
            // returns a plain String (rather than inlined here) so a JVM unit test can assert the
            // join-before-getError() ordering directly against the exact production code path.
            String resultJson = selectTransformResult(feederRunnable, feeder, inputHandle, session);
            cleaned = true;
            return toUnmanagedCString(resultJson);
        } catch (Exception e) {
            // No Java exception may escape this @CEntryPoint: convert to an error envelope.
            String m = e.getMessage();
            if (m == null || m.trim().isEmpty()) {
                m = e.toString();
            }
            return toUnmanagedCString("{\"success\":false,\"error\":\""
                    + escapeJsonString(m) + "\"}");
        } finally {
            // Sole close of the input handle + feeder join for every path that did not already
            // clean up in-try (exception paths and the early error returns). Safe (and a no-op
            // cancel/join) when the feeder never started.
            if (!cleaned) {
                cleanupFeeder(feederRunnable, feeder, inputHandle);
            }
        }
    }

    /**
     * Joins the input feeder — via {@link #cleanupFeeder} — and only <em>then</em> reads its
     * terminal error, returning the {@code success:false} envelope if it failed or the
     * {@code success:true} envelope built from {@code outputSession} otherwise.
     *
     * <p><strong>Ordering is the entire point of this method:</strong> an in-flight read
     * callback that fails <em>after</em> output reached EOF sets {@link InputCallbackFeeder}'s
     * terminal error only once it returns, so {@link InputCallbackFeeder#getError()} must not be
     * read until {@code cleanupFeeder} has cancelled, unblocked (by closing the input session),
     * and joined the feeder thread to completion — otherwise a late failure is missed and
     * {@code success:true} is returned over truncated input. The engine usually errors first via
     * {@code StreamSession.isError()} (checked by the caller before this method runs); this
     * covers the case where it tolerated the truncated input instead.</p>
     *
     * <p>Package-private (rather than folded inline into {@link #transformViaCallbacks}) so a JVM
     * unit test can assert the join-then-read ordering against this exact code path:
     * {@code transformViaCallbacks} itself returns a GraalVM {@code CCharPointer}, which cannot be
     * exercised from a hosted JVM, but this method returns a plain {@link String}. A test driving
     * an in-flight failing read callback through this method would observe {@code success:true}
     * instead of the feeder's error if {@code getError()} were ever read before the join — the
     * exact regression this method's ordering prevents.</p>
     */
    static String selectTransformResult(InputCallbackFeeder feederRunnable, Thread feeder,
                                         long inputHandle, StreamSession outputSession) {
        cleanupFeeder(feederRunnable, feeder, inputHandle);

        String feederError = feederRunnable.getError();
        if (feederError != null) {
            return "{\"success\":false,\"error\":\"" + escapeJsonString(feederError) + "\"}";
        }

        return "{\"success\":true"
                + ",\"mimeType\":\"" + outputSession.getMimeType() + "\""
                + ",\"charset\":\"" + outputSession.getCharset() + "\""
                + ",\"binary\":" + outputSession.isBinary()
                + "}";
    }

    /**
     * Registers a new {@link InputStreamSession} for the callback-supplied input and merges its
     * stream-handle entry into {@code inputs}.
     *
     * <p>Package-private (rather than {@code private}) so a JVM unit test can drive this
     * leak-prone setup region directly: {@link #transformViaCallbacks} itself takes GraalVM
     * {@code Word}-typed callbacks and returns a {@code CCharPointer}, neither of which resolves in
     * a hosted JVM. This helper uses only plain-Java types.</p>
     *
     * <p>On success, {@link InputSetup#errorEnvelope} is {@code null}, {@link InputSetup#session}
     * and {@link InputSetup#mergedInputs} are populated, and the session is left registered and
     * open for the feeder — its handle must ultimately be closed via {@link #cleanupFeeder}. On a
     * malformed {@code inputs} string the handle is already closed and
     * {@link InputSetup#errorEnvelope} carries the {@code success:false} payload to return
     * verbatim, so nothing leaks and no {@code JSONException} escapes.</p>
     */
    static InputSetup setUpInputSession(String inputs, String inName, String inMime, String inCharset) {
        InputStreamSession inputSession = new InputStreamSession(inMime, inCharset);
        long inputHandle = inputSession.register();
        try {
            org.json.JSONObject streamEntry = new org.json.JSONObject();
            streamEntry.put("streamHandle", Long.toString(inputHandle));
            streamEntry.put("mimeType", inMime);
            if (inCharset != null) {
                streamEntry.put("charset", inCharset);
            }
            String mergedInputs = mergeInputEntry(inputs, inName, streamEntry);
            return new InputSetup(inputSession, inputHandle, mergedInputs, null);
        } catch (Exception e) {
            InputStreamSession.close(inputHandle);
            String m = e.getMessage();
            if (m == null || m.trim().isEmpty()) {
                m = e.toString();
            }
            return new InputSetup(null, inputHandle, null,
                    "{\"success\":false,\"error\":\"" + escapeJsonString(m) + "\"}");
        }
    }

    /**
     * Outcome of {@link #setUpInputSession}: either a live registered session plus its merged
     * inputs ({@link #errorEnvelope} {@code null}), or a {@code success:false} error envelope with
     * the handle already closed ({@link #session}/{@link #mergedInputs} {@code null}).
     */
    static final class InputSetup {
        final InputStreamSession session;
        final long handle;
        final String mergedInputs;
        final String errorEnvelope;

        InputSetup(InputStreamSession session, long handle, String mergedInputs, String errorEnvelope) {
            this.session = session;
            this.handle = handle;
            this.mergedInputs = mergedInputs;
            this.errorEnvelope = errorEnvelope;
        }
    }

    /**
     * Merges a single input entry into an existing JSON inputs string.
     */
    private static String mergeInputEntry(String existingJson, String name, org.json.JSONObject entry) {
        org.json.JSONObject obj = (existingJson == null || existingJson.trim().isEmpty())
                ? new org.json.JSONObject()
                : new org.json.JSONObject(existingJson);
        obj.put(name, entry);
        return obj.toString();
    }

    /**
     * Cancels the input feeder, waits for it to fully exit {@link InputCallbackFeeder#run()}
     * (including its {@code finally} block), and closes the input session.
     *
     * <p><strong>Why this must not abandon a live feeder:</strong> once this method returns,
     * {@link #transformViaCallbacks} returns to its {@code @CEntryPoint}, which returns to the
     * native caller — at which point the caller is free to release the callback state
     * ({@code ctx}). If the feeder thread were still alive it could invoke
     * {@code readCallback(ctx, …)} against freed memory, a native use-after-free. Therefore this
     * method may only return once {@code thread.isAlive() == false}.</p>
     *
     * <p><strong>Order (all three are part of stopping the feeder):</strong></p>
     * <ol>
     *   <li><b>Signal cancel</b> — {@link InputCallbackFeeder#cancel()} sets a volatile flag the
     *       loop checks immediately after each {@code readCallback} invocation returns and before
     *       re-invoking it, so a slow-but-returning in-flight callback breaks the loop instead of
     *       being re-entered.</li>
     *   <li><b>Close the input session</b> — this closes both ends of the pipe, which unblocks a
     *       feeder parked inside {@link InputStreamSession#write} on a full pipe (the next write
     *       throws {@link IOException} and breaks the loop). This is a legitimate part of the
     *       cancel signal for the pipe-backpressure case and is harmless on the success path,
     *       where the feeder has already reached EOF and exited. It also unregisters the handle.</li>
     *   <li><b>Join without a finite timeout</b> — we wait for {@code run()} to complete rather
     *       than abandoning the thread after a bound. An {@link InterruptedException} does not end
     *       the wait (returning early would reopen the use-after-free window): we record the
     *       interruption locally and keep joining with the interrupt status <em>cleared</em>, so
     *       {@code join()} actually blocks instead of immediately re-throwing and busy-spinning.
     *       The caller's interrupt status is restored exactly once, after the feeder has
     *       terminated.</li>
     * </ol>
     *
     * <p><strong>Null-safety:</strong> when the feeder never started — a setup failure threw
     * before {@code feeder.start()} — {@code feederRunnable} and/or {@code thread} may be
     * {@code null}. The cancel and join are then no-ops, but the input handle is <em>always</em>
     * closed so a failed setup cannot leak the session.</p>
     *
     * <p><strong>Documented trade-off:</strong> cancellation guarantees we wait only for the
     * <em>in-flight</em> {@code readCallback} to return — no signal can interrupt native code
     * parked inside the caller's callback. A callback that blocks <em>forever</em> inside a single
     * invocation therefore cannot be joined and this method would block indefinitely. That is the
     * correct trade: the only alternative — abandoning a still-live feeder — is the
     * use-after-free this method exists to prevent.</p>
     */
    static void cleanupFeeder(InputCallbackFeeder feederRunnable, Thread thread, long inputHandle) {
        if (feederRunnable != null) {
            feederRunnable.cancel();
        }
        // Always drop the session from the registry (and unblock a feeder parked on a full pipe).
        // This runs even when the feeder never started, so the input handle is never leaked.
        InputStreamSession.close(inputHandle);
        if (thread == null) {
            return;
        }
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                // Never abandon a live feeder (abandoning reopens the use-after-free
                // window this method exists to close). Record the interruption and
                // keep waiting with the interrupt status CLEARED, so join() actually
                // blocks instead of re-throwing immediately and busy-spinning.
                interrupted = true;
            }
        }
        if (interrupted) {
            // Restore the caller's interrupt status now that the feeder has exited.
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Explicit {@link Runnable} that drives the read-callback loop on a background thread.
     *
     * <p>GraalVM Native Image forbids capturing {@code Word} types (such as
     * {@link CCharPointer} or {@link CFunctionPointer}) inside lambdas. This class stores
     * the raw addresses as plain {@code long} values and reconstitutes the pointers via
     * {@link WordFactory#pointer(long)} inside {@link #run()}.</p>
     *
     * <p>The feeder allocates its own native read buffer and frees it in its {@code finally}
     * block, ensuring no shared native memory between threads.</p>
     *
     * <p><strong>Cancellation:</strong> {@link #cancel()} sets a {@code volatile} flag that
     * {@link #run()} checks immediately <em>after</em> {@code readChunk} (the read callback)
     * returns and <em>before</em> the next iteration re-invokes it. This is what closes the
     * use-after-free window: once cancellation is requested, a callback that was blocked and then
     * returns breaks the loop instead of being re-entered. See
     * {@link NativeLib#cleanupFeeder} for the full stop protocol.</p>
     *
     * <p>Package-private and non-final (rather than {@code private}) so a JVM unit test can
     * subclass it and override {@link #readChunk} with a pure-Java blocking source, exercising the
     * cancel/join contract without the GraalVM {@code Word}-type machinery
     * ({@link WordFactory#pointer}, {@code cb.invoke}), which only initialises inside a compiled
     * native image.</p>
     */
    static class InputCallbackFeeder implements Runnable {
        private final long readCallbackAddr;
        private final long ctxAddr;
        private final InputStreamSession inputSession;
        private volatile boolean cancelled = false;
        private volatile String feederError;

        InputCallbackFeeder(long readCallbackAddr, long ctxAddr,
                            InputStreamSession inputSession) {
            this.readCallbackAddr = readCallbackAddr;
            this.ctxAddr = ctxAddr;
            this.inputSession = inputSession;
        }

        /** Requests the feeder loop stop after the in-flight {@code readChunk} returns. */
        void cancel() {
            cancelled = true;
        }

        boolean isCancelled() {
            return cancelled;
        }

        /**
         * The read-callback contract violation that stopped the feeder as an error, or
         * {@code null} if the feeder reached a clean EOF (or never ran). Read by
         * {@link NativeLib#transformViaCallbacks} after the output loop so a truncated input
         * caused by a misbehaving callback is reported as {@code success:false} rather than
         * presented as a successful transform.
         */
        String getError() {
            return feederError;
        }

        /**
         * Records an out-of-range read-callback length as a feeder error and maps it to the
         * error return code ({@code -1}). Per the read convention {@code 0} = EOF, {@code >0} =
         * bytes read, {@code -1} = error; any length outside {@code [-1, max]} is a contract
         * violation.
         */
        private int rejectOutOfRange(int n, int max) {
            feederError = "Input read callback returned out-of-range length " + n
                    + " (max " + max + ")";
            return -1;
        }

        /**
         * Pulls the next input chunk from the caller-owned read callback into {@code dest},
         * returning the number of bytes read ({@code 0} = EOF, negative = error).
         *
         * <p>Reconstitutes the {@code Word}-typed callback and context from their raw addresses
         * and copies the bytes out of a freshly allocated native scratch buffer. Overridable so
         * JVM tests can supply a pure-Java implementation; production code never overrides it.</p>
         */
        int readChunk(byte[] dest, int max) {
            NativeCallbacks.ReadCallback cb = WordFactory.pointer(readCallbackAddr);
            PointerBase ctx = WordFactory.pointer(ctxAddr);
            CCharPointer buf = UnmanagedMemory.malloc(max);
            try {
                int n = cb.invoke(ctx, buf, max);
                // Reject a contract violation BEFORE the copy loop: n > max would index past
                // dest[] / the native buf (an out-of-bounds copy that used to silently kill the
                // feeder and present the engine with a clean EOF on truncated input).
                if (n > max || n < -1) {
                    return rejectOutOfRange(n, max);
                }
                if (n > 0) {
                    for (int i = 0; i < n; i++) {
                        dest[i] = buf.read(i);
                    }
                }
                return n;
            } finally {
                UnmanagedMemory.free(buf);
            }
        }

        @Override
        public void run() {
            byte[] tmp = new byte[CALLBACK_BUFFER_SIZE];
            try {
                while (!cancelled) {
                    int n = readChunk(tmp, CALLBACK_BUFFER_SIZE);
                    // Defence in depth for the overridable readChunk seam: reject any length
                    // outside [-1, max] here too, so an out-of-range value can never reach the
                    // write below (which would throw IndexOutOfBounds out of run()). In
                    // production readChunk has already recorded this and returned -1.
                    if (n > CALLBACK_BUFFER_SIZE || n < -1) {
                        n = rejectOutOfRange(n, CALLBACK_BUFFER_SIZE);
                    }
                    if (n == 0) {
                        break; // clean EOF
                    }
                    if (n < 0) {
                        // n == -1: the read callback signalled an input error (the documented
                        // error code, produced e.g. when a Python read callback raises). Record a
                        // terminal feeder error so complete-but-then-failed input can never be
                        // reported as success:true. rejectOutOfRange already set a more specific
                        // message for out-of-range values funnelled to -1, so only fill in the
                        // generic error when none was recorded.
                        if (feederError == null) {
                            feederError = "Input read callback signalled an error (returned -1)";
                        }
                        break;
                    }
                    // Check AFTER the callback returns and BEFORE re-invoking / writing: once
                    // cancelled, a slow-but-returning in-flight callback must not be re-entered
                    // (its ctx may be freed the moment cleanupFeeder returns).
                    if (cancelled) {
                        break;
                    }
                    inputSession.write(tmp, n);
                }
            } catch (IOException e) {
                // pipe broken – DW engine will see the error
            } finally {
                try {
                    inputSession.closeWriter();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String escapeJsonString(String input) {
        if (input == null) return "";
        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static CCharPointer toUnmanagedCString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        CCharPointer ptr = UnmanagedMemory.malloc(bytes.length + 1);
        for (int i = 0; i < bytes.length; i++) {
            ptr.write(i, bytes[i]);
        }
        ptr.write(bytes.length, (byte) 0);
        return ptr;
    }

    // ── Multi-Engine FFI Entrypoints (W-23692110) ────────────────────────

    /**
     * Creates a new isolated engine (ClassLoader-only resolver) and returns its handle.
     *
     * @param thread the isolate thread
     * @return a non-zero handle identifying the new engine
     */
    @CEntryPoint(name = "create_engine",
            exceptionHandler = CEntryPointExceptionHandlers.ReturnZero.class)
    public static long createEngine(IsolateThread thread) {
        return ScriptRuntime.register(new ScriptRuntime());
    }

    /**
     * Creates a new isolated engine backed by a caller-supplied module resolver callback,
     * and returns its handle.
     *
     * @param thread           the isolate thread
     * @param resolverCallback callback used to resolve external modules for this engine only
     * @param ctx              opaque context pointer forwarded to every resolver invocation
     * @return a non-zero handle identifying the new engine
     */
    @CEntryPoint(name = "create_engine_with_resolver",
            exceptionHandler = CEntryPointExceptionHandlers.ReturnZero.class)
    public static long createEngineWithResolver(
            IsolateThread thread,
            NativeCallbacks.ResolveModuleCallback resolverCallback,
            PointerBase ctx) {
        if (resolverCallback.isNull()) {
            return 0L;
        }
        CallbackWeaveResourceResolver resolver =
                new CallbackWeaveResourceResolver(resolverCallback, ctx);
        return ScriptRuntime.register(new ScriptRuntime(resolver));
    }

    /**
     * Destroys an engine created by {@link #createEngine} / {@link #createEngineWithResolver}.
     * A no-op if the handle is unknown or already destroyed.
     *
     * @param thread the isolate thread
     * @param handle the engine handle to remove
     */
    @CEntryPoint(name = "destroy_engine",
            exceptionHandler = CEntryPointExceptionHandlers.ReturnVoid.class)
    public static void destroyEngine(IsolateThread thread, long handle) {
        ScriptRuntime.destroy(handle);
    }

    /**
     * Executes a DataWeave script against a specific engine.
     *
     * <p>If {@code handle} does not identify a live engine, returns
     * {@code {"success":false,"error":"Unknown engine handle"}} rather than throwing.</p>
     *
     * @param thread     the isolate thread
     * @param handle     the target engine's handle
     * @param script     the DataWeave script (C string)
     * @param inputsJson JSON-encoded inputs map (C string), may be null
     * @return the script execution result (unmanaged C string, must be freed)
     */
    @CEntryPoint(name = "run_script_engine",
            exceptionHandler = CEntryPointExceptionHandlers.ReturnNullPointer.class)
    public static CCharPointer runScriptEngine(
            IsolateThread thread, long handle, CCharPointer script, CCharPointer inputsJson) {
        try (ScriptRuntime.EngineLease lease = ScriptRuntime.acquire(handle)) {
            if (lease == null) {
                return toUnmanagedCString(UNKNOWN_ENGINE_HANDLE_JSON);
            }
            if (script.isNull()) {
                return toUnmanagedCString("{\"success\":false,\"error\":\"Script cannot be null\"}");
            }
            String dwScript = CTypeConversion.toJavaString(script);
            String inputs = inputsJson.isNull() ? null : CTypeConversion.toJavaString(inputsJson);
            return toUnmanagedCString(lease.runtime().run(dwScript, inputs));
        }
    }

    /**
     * Executes a DataWeave script against a specific engine, streaming the result to a
     * caller-supplied write callback. See {@link #streamToWriteCallback} for the callback contract.
     *
     * <p>If {@code handle} does not identify a live engine, returns
     * {@code {"success":false,"error":"Unknown engine handle"}} rather than throwing.</p>
     *
     * @param thread        the isolate thread
     * @param handle        the target engine's handle
     * @param script        the DataWeave script (C string)
     * @param inputsJson    JSON-encoded inputs map (C string), may be null
     * @param writeCallback function pointer invoked with each output chunk; must return 0 on success
     * @param ctx           opaque context pointer forwarded to every callback invocation
     * @return an unmanaged C string with JSON metadata/error
     */
    @CEntryPoint(name = "run_script_callback_engine",
            exceptionHandler = CEntryPointExceptionHandlers.ReturnNullPointer.class)
    public static CCharPointer runScriptCallbackEngine(
            IsolateThread thread, long handle, CCharPointer script, CCharPointer inputsJson,
            NativeCallbacks.WriteCallback writeCallback, PointerBase ctx) {
        try (ScriptRuntime.EngineLease lease = ScriptRuntime.acquire(handle)) {
            if (lease == null) {
                return toUnmanagedCString(UNKNOWN_ENGINE_HANDLE_JSON);
            }
            if (script.isNull()) {
                return toUnmanagedCString("{\"success\":false,\"error\":\"Script cannot be null\"}");
            }
            if (writeCallback.isNull()) {
                return toUnmanagedCString("{\"success\":false,\"error\":\"Write callback cannot be null\"}");
            }
            String dwScript = CTypeConversion.toJavaString(script);
            String inputs = inputsJson.isNull() ? null : CTypeConversion.toJavaString(inputsJson);
            return streamToWriteCallback(lease.runtime(), dwScript, inputs, writeCallback, ctx);
        }
    }

    /**
     * Executes a DataWeave script against a specific engine, with a callback-supplied input
     * and callback-streamed output. See {@link #transformViaCallbacks} for the callback
     * contract.
     *
     * <p>If {@code handle} does not identify a live engine, returns
     * {@code {"success":false,"error":"Unknown engine handle"}} rather than throwing.</p>
     *
     * @param thread        the isolate thread
     * @param handle        the target engine's handle
     * @param script        the DataWeave script (C string)
     * @param inputsJson    JSON-encoded inputs map (C string), may be null
     * @param inputName     the binding name for the callback-supplied input (C string)
     * @param inputMimeType the MIME type of the callback-supplied input (C string)
     * @param inputCharset  the charset of the callback-supplied input (C string), may be null for UTF-8
     * @param readCallback  function pointer invoked to read the next chunk
     * @param writeCallback function pointer invoked with each output chunk; must return 0 on success
     * @param ctx           opaque context pointer forwarded to every callback invocation
     * @return an unmanaged C string with JSON metadata/error
     */
    @CEntryPoint(name = "run_script_input_output_callback_engine",
            exceptionHandler = CEntryPointExceptionHandlers.ReturnNullPointer.class)
    public static CCharPointer runScriptInputOutputCallbackEngine(
            IsolateThread thread, long handle, CCharPointer script, CCharPointer inputsJson,
            CCharPointer inputName, CCharPointer inputMimeType, CCharPointer inputCharset,
            NativeCallbacks.ReadCallback readCallback, NativeCallbacks.WriteCallback writeCallback,
            PointerBase ctx) {
        try (ScriptRuntime.EngineLease lease = ScriptRuntime.acquire(handle)) {
            if (lease == null) {
                return toUnmanagedCString(UNKNOWN_ENGINE_HANDLE_JSON);
            }
            if (script.isNull()) {
                return toUnmanagedCString("{\"success\":false,\"error\":\"Script cannot be null\"}");
            }
            if (inputName.isNull()) {
                return toUnmanagedCString("{\"success\":false,\"error\":\"Input name cannot be null\"}");
            }
            if (inputMimeType.isNull()) {
                return toUnmanagedCString("{\"success\":false,\"error\":\"Input MIME type cannot be null\"}");
            }
            if (readCallback.isNull()) {
                return toUnmanagedCString("{\"success\":false,\"error\":\"Read callback cannot be null\"}");
            }
            if (writeCallback.isNull()) {
                return toUnmanagedCString("{\"success\":false,\"error\":\"Write callback cannot be null\"}");
            }
            String dwScript = CTypeConversion.toJavaString(script);
            String inputs = inputsJson.isNull() ? null : CTypeConversion.toJavaString(inputsJson);
            String inName = CTypeConversion.toJavaString(inputName);
            String inMime = CTypeConversion.toJavaString(inputMimeType);
            String inCharset = inputCharset.isNull() ? null : CTypeConversion.toJavaString(inputCharset);
            return transformViaCallbacks(lease.runtime(), dwScript, inputs, inName, inMime, inCharset,
                    readCallback, writeCallback, ctx);
        }
    }

}
