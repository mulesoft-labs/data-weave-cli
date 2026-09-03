package org.mule.weave.lib;

import org.json.JSONObject;
import org.mule.weave.v2.runtime.BindingValue;
import org.mule.weave.v2.runtime.DataWeaveResult;
import org.mule.weave.v2.runtime.ScriptingBindings;
import org.mule.weave.v2.runtime.api.DWModuleComponentsFactory;
import org.mule.weave.v2.runtime.api.DWResult;
import org.mule.weave.v2.runtime.api.DWScript;
import org.mule.weave.v2.runtime.api.DWScriptingEngine;
import org.mule.weave.v2.sdk.ClassLoaderWeaveResourceResolver;
import org.mule.weave.v2.sdk.CompositeWeaveResourceResolver;
import org.mule.weave.v2.sdk.WeaveResourceResolver;
import scala.Option;
import scala.Tuple2;
import scala.collection.immutable.Map;
import scala.collection.immutable.Map$;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wrapper around a {@link DWScriptingEngine} used to compile and execute DataWeave scripts.
 *
 * <p>Each {@link ScriptRuntime} instance owns its own engine (and therefore its own module
 * resolver and script cache), so multiple isolated engines can coexist within one process.
 * Instances are tracked in a handle-keyed registry so native callers can address a specific
 * engine by an opaque {@code long} handle.</p>
 *
 * <p>Execution results are returned as a JSON string containing a base64-encoded payload plus metadata
 * (mime type, charset, and whether the result is binary). Errors are returned as a JSON string with
 * {@code success=false} and an escaped error message.</p>
 */
public class ScriptRuntime {

    // ── Handle registry ──────────────────────────────────────────────────
    private static final ConcurrentHashMap<Long, EngineRecord> REGISTRY = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_HANDLE = new AtomicLong(1);

    /** Registers a runtime and returns its non-zero handle. */
    public static long register(ScriptRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        long handle = NEXT_HANDLE.getAndIncrement();
        if (handle <= 0) {
            throw new IllegalStateException("Engine handle space exhausted");
        }
        REGISTRY.put(handle, new EngineRecord(runtime));
        return handle;
    }

    /** Returns the runtime for a handle, or {@code null} if unknown/destroyed. */
    public static ScriptRuntime get(long handle) {
        EngineRecord record = REGISTRY.get(handle);
        return record == null ? null : record.runtime;
    }

    /** Acquires a lease for a live runtime, or returns {@code null} if admission is closed. */
    public static EngineLease acquire(long handle) {
        EngineRecord record = REGISTRY.get(handle);
        return record == null ? null : record.tryAcquire();
    }

    /** Closes admission, drains active leases, and removes the runtime. */
    public static boolean destroy(long handle) {
        EngineRecord record = REGISTRY.get(handle);
        if (record == null) {
            return false;
        }
        record.closeAndAwait();
        REGISTRY.remove(handle, record);
        return true;
    }

    public static final class EngineLease implements AutoCloseable {
        private final EngineRecord record;
        private final ScriptRuntime runtime;
        private boolean closed;

        private EngineLease(EngineRecord record, ScriptRuntime runtime) {
            this.record = record;
            this.runtime = runtime;
        }

        public ScriptRuntime runtime() {
            return runtime;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            record.release();
        }
    }

    private static final class EngineRecord {
        private enum State {
            LIVE,
            CLOSING,
            DESTROYED
        }

        private final ScriptRuntime runtime;
        private State state = State.LIVE;
        private int activeLeases;

        private EngineRecord(ScriptRuntime runtime) {
            this.runtime = runtime;
        }

        private synchronized EngineLease tryAcquire() {
            if (state != State.LIVE) {
                return null;
            }
            activeLeases++;
            return new EngineLease(this, runtime);
        }

        private void closeAndAwait() {
            boolean interrupted = false;
            synchronized (this) {
                if (state == State.LIVE) {
                    state = State.CLOSING;
                }
                while (state != State.DESTROYED && activeLeases > 0) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                state = State.DESTROYED;
                notifyAll();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private synchronized void release() {
            activeLeases--;
            if (activeLeases == 0) {
                notifyAll();
            }
        }
    }

    // ── Per-instance engine ───────────────────────────────────────────────
    private final DWScriptingEngine engine;

    /**
     * Builds an engine whose resolver is Composite(ClassLoader-built-ins + {@code customResolver});
     * a null {@code customResolver} yields ClassLoader-only.
     *
     * @param customResolver additional resolver for user-supplied modules, or {@code null}
     */
    public ScriptRuntime(WeaveResourceResolver customResolver) {
        this.engine = DWScriptingEngine.builder()
                .withDWModuleComponentsFactory(createModuleComponentsFactory(customResolver))
                .build();
    }

    /** Builds an engine with built-in (ClassLoader) modules only — no custom resolver. */
    public ScriptRuntime() {
        this(null);
    }

    /**
     * Creates composite resolver: ClassLoader (built-ins) + custom (user modules).
     * If no custom resolver is provided, returns ClassLoader only.
     */
    private static WeaveResourceResolver compositeResolver(WeaveResourceResolver customResolver) {
        WeaveResourceResolver classLoaderResolver = ClassLoaderWeaveResourceResolver.apply();
        if (customResolver == null) {
            return classLoaderResolver;
        }
        return CompositeWeaveResourceResolver.apply(
            classLoaderResolver,  // Try built-ins first
            customResolver        // Then callback for user modules
        );
    }

    private static DWModuleComponentsFactory createModuleComponentsFactory(WeaveResourceResolver customResolver) {
        return DWModuleComponentsFactory.createSimpleDWModuleComponentsFactoryBuilder()
                .withWeaveResourceResolver(compositeResolver(customResolver))
                .build();
    }

    /**
     * Executes a DataWeave script with no input bindings.
     *
     * @param script the DataWeave script source
     * @return a JSON string describing either the successful result or an error
     */
    public String run(String script) {
        return run(script, null);
    }

    /**
     * Executes a DataWeave script with optional input bindings encoded as JSON.
     *
     * <p>The expected JSON structure maps binding names to an object containing {@code content}
     * (base64), {@code mimeType}, optional {@code charset}, and optional {@code properties}.</p>
     *
     * @param script the DataWeave script source
     * @param inputsJson JSON string encoding the input bindings map, or {@code null}
     * @return a JSON string describing either the successful result or an error
     */
    public String run(String script, String inputsJson) {
        try {
            ScriptingBindings bindings = parseJsonInputsToBindings(inputsJson);
            String[] inputs = bindings.bindingNames();

            DWScript compiled = engine.compileDWScript(script, inputs);
            DWResult dwResult = compiled.writeDWResult(bindings);

            String encodedResult;
            if (dwResult.getContent() instanceof InputStream) {
                try {
                    byte[] ba = ((InputStream) dwResult.getContent()).readAllBytes();
                    encodedResult = Base64.getEncoder().encodeToString(ba);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                throw new RuntimeException("Result is not an InputStream: " + dwResult.getContent().getClass().getName());
            }

            return "{"
                + "\"success\":true,"
                + "\"result\":\"" + encodedResult + "\","
                + "\"mimeType\":\"" + dwResult.getMimeType() + "\","
                + "\"charset\":\"" + dwResult.getCharset() + "\","
                + "\"binary\":" + ((DataWeaveResult) dwResult).isBinary()
                + "}";
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || message.trim().isEmpty()) {
                message = e.toString();
            }

            return "{"
                + "\"success\":false,"
                + "\"error\":\"" + escapeJsonString(message) + "\""
                + "}";
        }
    }

    /**
     * Executes a DataWeave script and returns a {@link StreamSession} whose {@link java.io.InputStream}
     * can be read incrementally, avoiding loading the entire result into memory.
     *
     * @param script    the DataWeave script source
     * @param inputsJson JSON string encoding the input bindings map, or {@code null}
     * @return a {@link StreamSession} with the result stream and metadata, or an error session
     */
    public StreamSession runStreaming(String script, String inputsJson) {
        try {
            ScriptingBindings bindings = parseJsonInputsToBindings(inputsJson);
            String[] inputs = bindings.bindingNames();

            DWScript compiled = engine.compileDWScript(script, inputs);
            DWResult dwResult = compiled.writeDWResult(bindings);

            if (dwResult.getContent() instanceof InputStream) {
                return new StreamSession(
                        (InputStream) dwResult.getContent(),
                        dwResult.getMimeType(),
                        dwResult.getCharset().name(),
                        ((DataWeaveResult) dwResult).isBinary()
                );
            } else {
                return StreamSession.ofError("Result is not an InputStream: " + dwResult.getContent().getClass().getName());
            }
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || message.trim().isEmpty()) {
                message = e.toString();
            }
            return StreamSession.ofError(message);
        }
    }

    private ScriptingBindings parseJsonInputsToBindings(String inputsJson) {
        ScriptingBindings bindings = new ScriptingBindings();

        if (inputsJson == null || inputsJson.trim().isEmpty()) {
            return bindings;
        }

        // Fail closed: any malformed entry (bad JSON / base64 / charset / streamHandle /
        // properties) must propagate so the caller returns an error result rather than
        // silently executing on partial/empty bindings. Because `bindings` is only
        // returned after the loop completes, a propagated exception discards any
        // partially-built bindings automatically.
        JSONObject root = new JSONObject(inputsJson);

        for (String name : root.keySet()) {
            JSONObject entry = root.getJSONObject(name);

            if (entry.has("streamHandle")) {
                long streamHandle = Long.parseLong(entry.getString("streamHandle"));
                InputStreamSession inputSession = InputStreamSession.get(streamHandle);
                if (inputSession == null) {
                    throw new RuntimeException("Invalid streamHandle " + streamHandle + " for input '" + name + "'");
                }
                String mimeTypeRaw = entry.optString("mimeType", inputSession.getMimeType());
                String charsetRaw = entry.optString("charset", inputSession.getCharset());
                Charset charset = Charset.forName(charsetRaw);
                Option<String> mimeType = Option.apply(mimeTypeRaw);

                BindingValue bindingValue = new BindingValue(inputSession.getInputStream(), mimeType, Map$.MODULE$.empty(), charset);
                bindings.addBinding(name, bindingValue);

            } else if (entry.has("content")) {
                String contentRaw = entry.getString("content");
                String mimeTypeRaw = entry.optString("mimeType", null);
                String charsetRaw = entry.optString("charset", "UTF-8");

                Map<String, Object> properties = Map$.MODULE$.empty();
                if (entry.has("properties") && !entry.isNull("properties")) {
                    JSONObject propsObj = entry.getJSONObject("properties");
                    properties = parseJsonProperties(propsObj);
                }

                Charset charset = Charset.forName(charsetRaw);
                Option<String> mimeType = Option.apply(mimeTypeRaw);

                byte[] content = Base64.getDecoder().decode(contentRaw);
                BindingValue bindingValue = new BindingValue(content, mimeType, properties, charset);
                bindings.addBinding(name, bindingValue);
            }
        }

        return bindings;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonProperties(JSONObject propsObj) {
        Map<String, Object> result = Map$.MODULE$.empty();

        for (String key : propsObj.keySet()) {
            Object val = propsObj.get(key);
            if (val instanceof String || val instanceof Boolean) {
                result = (Map<String, Object>) result.$plus(new Tuple2<>(key, val));
            } else if (val instanceof Number) {
                Number num = (Number) val;
                Object boxed;
                if (val instanceof Double || val instanceof Float) {
                    boxed = num.doubleValue();
                } else {
                    boxed = num.longValue();
                }
                result = (Map<String, Object>) result.$plus(new Tuple2<>(key, boxed));
            } else if (val == JSONObject.NULL) {
                throw new IllegalArgumentException("properties values cannot be null (key '" + key + "')");
            } else {
                throw new IllegalArgumentException("properties values must be primitive (string/number/boolean) (key '" + key + "')");
            }
        }

        return result;
    }

    private String escapeJsonString(String input) {
        if (input == null) {
            return "";
        }

        return input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
