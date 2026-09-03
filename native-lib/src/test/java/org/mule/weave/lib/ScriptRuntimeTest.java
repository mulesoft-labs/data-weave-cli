package org.mule.weave.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mule.weave.v2.parser.ast.variables.NameIdentifier;
import org.mule.weave.v2.sdk.NameIdentifierHelper;
import org.mule.weave.v2.sdk.WeaveResource;
import org.mule.weave.v2.sdk.WeaveResourceResolver;
import scala.Option;
import scala.collection.JavaConverters;
import scala.collection.immutable.Seq;
import scala.collection.immutable.Seq$;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

class ScriptRuntimeTest {

    @Test
    void runSimpleScript() {
        ScriptRuntime runtime = new ScriptRuntime();
        
        System.out.println("Running sqrt(144) 10 times with timing:");
        System.out.println("=".repeat(50));
        
        for (int i = 1; i <= 20; i++) {
            long startTime = System.nanoTime();
            String result = runtime.run("sqrt(144)");
            long endTime = System.nanoTime();
            double executionTimeMs = (endTime - startTime) / 1_000_000.0;
            
            assertEquals("12", Result.parse(result).result);
            System.out.printf("Run %2d: %.3f ms - Result: %s%n", i, executionTimeMs, result);
        }
        
        System.out.println("=".repeat(50));
    }

    @Test
    void runParseError() {
        ScriptRuntime runtime = new ScriptRuntime();

        System.out.println("Running sqrt(144) 10 times with timing:");
        System.out.println("=".repeat(50));

        String result = runtime.run("invalid syntax here");

        String error = Result.parse(result).error;
        assertTrue(error.contains("Unable to resolve reference"));
        System.out.printf("Error: %s%n", result);

        System.out.println("=".repeat(50));
    }

    @Test
    void runWithInputs() {
        ScriptRuntime runtime = new ScriptRuntime();
        
        System.out.println("Testing runWithInputs with two integer numbers:");
        System.out.println("=".repeat(50));
        
        // Test 1: Sum 25 + 17
        int num1 = 25;
        int num2 = 17;
        int expected = num1 + num2;
        
        // Create inputs JSON with content and mimeType for each binding
        String inputsJson = String.format(
            "{\"num1\": {\"content\": \"%s\", \"mimeType\": \"application/json\"}, " +
            "\"num2\": {\"content\": \"%s\", \"mimeType\": \"application/json\"}}",
            encode(num1), encode(num2)
        );
        
        String script = "num1 + num2";
        
        System.out.printf("Test 1: %d + %d%n", num1, num2);
        System.out.printf("Script: %s%n", script);
        System.out.printf("Inputs: %s%n", inputsJson);
        
        long startTime = System.nanoTime();
        String result = Result.parse(runtime.run(script, inputsJson)).result;
        long endTime = System.nanoTime();
        double executionTimeMs = (endTime - startTime) / 1_000_000.0;
        
        System.out.printf("Result: %s%n", result);
        System.out.printf("Expected: %d%n", expected);
        System.out.printf("Execution time: %.3f ms%n", executionTimeMs);
        
        assertEquals(String.valueOf(expected), result);
        System.out.println("✓ Test 1 passed!");
        
        System.out.println("-".repeat(50));
        
        // Test 2: Sum 100 + 250
        num1 = 100;
        num2 = 250;
        expected = num1 + num2;
        
        inputsJson = String.format(
            "{\"num1\": {\"content\": \"%s\", \"mimeType\": \"application/json\"}, " +
            "\"num2\": {\"content\": \"%s\", \"mimeType\": \"application/json\"}}",
            encode(num1), encode(num2)
        );
        
        System.out.printf("Test 2: %d + %d%n", num1, num2);
        System.out.printf("Script: %s%n", script);
        
        startTime = System.nanoTime();
        result = Result.parse(runtime.run(script, inputsJson)).result;
        endTime = System.nanoTime();
        executionTimeMs = (endTime - startTime) / 1_000_000.0;
        
        System.out.printf("Result: %s%n", result);
        System.out.printf("Expected: %d%n", expected);
        System.out.printf("Execution time: %.3f ms%n", executionTimeMs);
        
        assertEquals(String.valueOf(expected), result);
        System.out.println("✓ Test 2 passed!");
        
        System.out.println("=".repeat(50));
    }

    private String encode(Object value) {
        byte[] bytes = value instanceof byte[] ? (byte[]) value : String.valueOf(value).getBytes();
        return Base64.getEncoder().encodeToString(bytes);

    }

    @Test
    void runWithXmlInput() {
        ScriptRuntime runtime = new ScriptRuntime();
        
        System.out.println("Testing runWithInputs with XML input to calculate average age:");
        System.out.println("=".repeat(50));
        
        // XML input with two people
        String xmlInput = """
            <people>
                <person>
                    <age>19</age>
                    <name>john</name>
                </person>
                <person>
                    <age>25</age>
                    <name>jane</name>
                </person>
            </people>
            """;

        String inputsJson = String.format(
            "{\"people\": {\"content\": \"%s\", \"mimeType\": \"application/xml\"}}",
            encode(xmlInput)
        );
        
        // DataWeave script to calculate average age
        String script = """
            output application/json
            ---
            avg(people.people.*person.age)
            """;
        
        System.out.printf("XML Input:%n%s%n", xmlInput);
        System.out.printf("Script:%n%s%n", script);
        
        long startTime = System.nanoTime();
        String result = runtime.run(script, inputsJson);
        long endTime = System.nanoTime();
        double executionTimeMs = (endTime - startTime) / 1_000_000.0;
        
        System.out.printf("Result: %s%n", result);
        System.out.printf("Expected: 22 (average of 19 and 25)%n");
        System.out.printf("Execution time: %.3f ms%n", executionTimeMs);
        
        // The average of 19 and 25 is 22
        assertEquals("22", Result.parse(result).result);
        System.out.println("✓ Test passed!");
        
        System.out.println("=".repeat(50));
    }

    @Test
    void runWithJsonObjectInput() {
        ScriptRuntime runtime = new ScriptRuntime();
        
        System.out.println("Testing runWithInputs with JSON object input:");
        System.out.println("=".repeat(50));
        
        String jsonInput = "{\"name\": \"John\", \"age\": 30}";
        
        String inputsJson = String.format(
            "{\"payload\": {\"content\": \"%s\", \"mimeType\": \"application/json\"}}",
            encode(jsonInput)
        );

        // DataWeave script to extract name
        String script = "output application/json\n---\npayload.name";
        
        System.out.printf("JSON Input: %s%n", jsonInput);
        System.out.printf("Script: %s%n", script);
        
        long startTime = System.nanoTime();
        String result = Result.parse(runtime.run(script, inputsJson)).result;
        long endTime = System.nanoTime();
        double executionTimeMs = (endTime - startTime) / 1_000_000.0;
        
        System.out.printf("Result: %s%n", result);
        System.out.printf("Expected: \"John\"%n");
        System.out.printf("Execution time: %.3f ms%n", executionTimeMs);
        
        assertEquals("\"John\"", result);
        System.out.println("✓ Test passed!");
        
        System.out.println("=".repeat(50));
    }

    @Test
    void runWithBinaryResult() {
        ScriptRuntime runtime = new ScriptRuntime();

        System.out.println("Running fromBase64 10 times with timing:");
        System.out.println("=".repeat(50));

        for (int i = 1; i <= 1; i++) {
            long startTime = System.nanoTime();
            Result result = Result.parse(runtime.run("import fromBase64 from dw::core::Binaries\n" +
                    "output application/octet-stream\n" +
                    "---\n" +
                    "fromBase64(\"12345678\")", ""));
            long endTime = System.nanoTime();
            double executionTimeMs = (endTime - startTime) / 1_000_000.0;

            assertEquals("12345678", result.result);
            System.out.printf("Run %2d: %.3f ms - Result: %s%n", i, executionTimeMs, result.result);
        }

        System.out.println("=".repeat(50));
    }

    @Test
    void runWithInputProperties() {
        ScriptRuntime runtime = new ScriptRuntime();
        String encodedIn0 = Base64.getEncoder().encodeToString("1234567".getBytes());
        Result result = Result.parse(runtime.run("in0.column_1[0] as Number",
                "{\"in0\": " +
                        "{\"content\": \"" + encodedIn0 + "\", " +
                        "\"mimeType\": \"application/csv\", " +
                        "\"properties\": {\"header\": false, \"separator\": \"4\"}}}"));
        assertEquals("567", result.result);

    }

    @Test
    void streamSimpleScript() throws IOException {
        ScriptRuntime runtime = new ScriptRuntime();

        System.out.println("Testing streaming simple script:");
        System.out.println("=".repeat(50));

        StreamSession session = runtime.runStreaming("sqrt(144)", null);
        assertFalse(session.isError(), "Expected successful session");
        assertNull(session.getError());
        assertNotNull(session.getMimeType());

        byte[] buf = new byte[64];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int n;
        while ((n = session.read(buf, buf.length)) > 0) {
            bos.write(buf, 0, n);
        }
        String result = bos.toString(session.getCharset());
        assertEquals("12", result);
        StreamSession.close(session.register()); // clean up handle

        System.out.println("Result: " + result);
        System.out.println("✓ Streaming simple script passed!");
        System.out.println("=".repeat(50));
    }

    @Test
    void streamWithInputs() throws IOException {
        ScriptRuntime runtime = new ScriptRuntime();

        System.out.println("Testing streaming with inputs:");
        System.out.println("=".repeat(50));

        String inputsJson = String.format(
            "{\"num1\": {\"content\": \"%s\", \"mimeType\": \"application/json\"}, " +
            "\"num2\": {\"content\": \"%s\", \"mimeType\": \"application/json\"}}",
            encode(25), encode(17)
        );

        StreamSession session = runtime.runStreaming("num1 + num2", inputsJson);
        assertFalse(session.isError());

        byte[] buf = new byte[64];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int n;
        while ((n = session.read(buf, buf.length)) > 0) {
            bos.write(buf, 0, n);
        }
        String result = bos.toString(session.getCharset());
        assertEquals("42", result);
        StreamSession.close(session.register());

        System.out.println("Result: " + result);
        System.out.println("✓ Streaming with inputs passed!");
        System.out.println("=".repeat(50));
    }

    @Test
    void streamChunkedRead() throws IOException {
        ScriptRuntime runtime = new ScriptRuntime();

        System.out.println("Testing streaming chunked read:");
        System.out.println("=".repeat(50));

        String script = "output application/json\n---\n{items: (1 to 100) map {id: $, name: \"item_\" ++ $}}";

        StreamSession session = runtime.runStreaming(script, null);
        assertFalse(session.isError());

        byte[] smallBuf = new byte[32];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int n;
        int chunkCount = 0;
        while ((n = session.read(smallBuf, smallBuf.length)) > 0) {
            bos.write(smallBuf, 0, n);
            chunkCount++;
        }
        String result = bos.toString(session.getCharset());
        assertTrue(chunkCount > 1, "Expected multiple chunks, got " + chunkCount);
        assertTrue(result.contains("item_1"));
        assertTrue(result.contains("item_100"));
        StreamSession.close(session.register());

        System.out.printf("Read %d chunks, total %d bytes%n", chunkCount, bos.size());
        System.out.println("✓ Streaming chunked read passed!");
        System.out.println("=".repeat(50));
    }

    @Test
    void streamWithStreamingInput() throws Exception {
        ScriptRuntime runtime = new ScriptRuntime();

        System.out.println("Testing streaming with streaming input:");
        System.out.println("=".repeat(50));

        // Create an input stream session for JSON data
        InputStreamSession inputSession = new InputStreamSession("application/json", "UTF-8");
        long inputHandle = inputSession.register();

        // Build inputs JSON referencing the streamHandle
        String inputsJson = "{\"payload\": {\"streamHandle\": \"" + inputHandle + "\", \"mimeType\": \"application/json\"}}";

        // The DW engine will read from the PipedInputStream on the main thread,
        // so we must feed data from a separate thread.
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Exception> feedError = new AtomicReference<>();

        Thread feeder = new Thread(() -> {
            try {
                started.countDown();
                String jsonData = "{\"name\": \"Alice\", \"age\": 30}";
                byte[] bytes = jsonData.getBytes("UTF-8");
                inputSession.write(bytes, bytes.length);
                inputSession.closeWriter();
            } catch (Exception e) {
                feedError.set(e);
            }
        });
        feeder.start();
        started.await();

        // Run streaming with the piped input
        StreamSession session = runtime.runStreaming("output application/json\n---\npayload.name", inputsJson);
        assertFalse(session.isError(), "Expected successful session but got: " + session.getError());

        byte[] buf = new byte[64];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int n;
        while ((n = session.read(buf, buf.length)) > 0) {
            bos.write(buf, 0, n);
        }
        String result = bos.toString(session.getCharset());
        assertEquals("\"Alice\"", result);
        StreamSession.close(session.register());
        InputStreamSession.close(inputHandle);
        feeder.join(5000);
        assertNull(feedError.get(), "Feeder thread threw: " + feedError.get());

        System.out.println("Result: " + result);
        System.out.println("✓ Streaming with streaming input passed!");
        System.out.println("=".repeat(50));
    }

    @Test
    void streamWithLargeStreamingInput() throws Exception {
        ScriptRuntime runtime = new ScriptRuntime();

        System.out.println("Testing streaming with large streaming input:");
        System.out.println("=".repeat(50));

        InputStreamSession inputSession = new InputStreamSession("application/json", "UTF-8");
        long inputHandle = inputSession.register();

        String inputsJson = "{\"payload\": {\"streamHandle\": \"" + inputHandle + "\", \"mimeType\": \"application/json\"}}";

        // Feed a large JSON array from a separate thread
        AtomicReference<Exception> feedError = new AtomicReference<>();
        Thread feeder = new Thread(() -> {
            try {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 1; i <= 1000; i++) {
                    if (i > 1) sb.append(",");
                    sb.append("{\"id\":").append(i).append(",\"val\":\"item_").append(i).append("\"}");
                }
                sb.append("]");
                byte[] bytes = sb.toString().getBytes("UTF-8");
                // Write in chunks to simulate streaming
                int chunkSize = 4096;
                for (int off = 0; off < bytes.length; off += chunkSize) {
                    int len = Math.min(chunkSize, bytes.length - off);
                    byte[] chunk = new byte[len];
                    System.arraycopy(bytes, off, chunk, 0, len);
                    inputSession.write(chunk, len);
                }
                inputSession.closeWriter();
            } catch (Exception e) {
                feedError.set(e);
            }
        });
        feeder.start();

        StreamSession session = runtime.runStreaming("output application/json\n---\nsizeOf(payload)", inputsJson);
        assertFalse(session.isError(), "Expected successful session but got: " + session.getError());

        byte[] buf = new byte[256];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int n;
        while ((n = session.read(buf, buf.length)) > 0) {
            bos.write(buf, 0, n);
        }
        String result = bos.toString(session.getCharset());
        assertEquals("1000", result);
        StreamSession.close(session.register());
        InputStreamSession.close(inputHandle);
        feeder.join(10000);
        assertNull(feedError.get(), "Feeder thread threw: " + feedError.get());

        System.out.println("Result: " + result);
        System.out.println("✓ Streaming with large streaming input passed!");
        System.out.println("=".repeat(50));
    }

    @Test
    void streamErrorSession() {
        ScriptRuntime runtime = new ScriptRuntime();

        System.out.println("Testing streaming error session:");
        System.out.println("=".repeat(50));

        StreamSession session = runtime.runStreaming("invalid syntax here", null);
        assertTrue(session.isError());
        assertNotNull(session.getError());
        assertTrue(session.getError().contains("Unable to resolve reference"));

        System.out.println("Error: " + session.getError());
        System.out.println("✓ Streaming error session passed!");
        System.out.println("=".repeat(50));
    }

    // ── Callback-based streaming pattern tests ──────────────────────────

    @Test
    void callbackOutputStreaming() throws IOException {
        ScriptRuntime runtime = new ScriptRuntime();

        System.out.println("Testing callback-based output streaming:");
        System.out.println("=".repeat(50));

        StreamSession session = runtime.runStreaming("output application/json\n---\n{items: (1 to 50) map {id: $}}", null);
        assertFalse(session.isError());

        // Simulate the write-callback pattern: read chunks and collect them
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buf = new byte[64];
        int callbackCount = 0;
        int n;
        while ((n = session.read(buf, buf.length)) > 0) {
            // This is what the write callback would receive
            collected.write(buf, 0, n);
            callbackCount++;
        }
        String result = collected.toString(session.getCharset());
        assertTrue(result.contains("\"id\": 1"), "Expected id 1 in result");
        assertTrue(result.contains("\"id\": 50"), "Expected id 50 in result");
        assertTrue(callbackCount > 0, "Expected at least one callback invocation");
        StreamSession.close(session.register());

        System.out.printf("Callback invoked %d times, total %d bytes%n", callbackCount, collected.size());
        System.out.println("✓ Callback output streaming passed!");
        System.out.println("=".repeat(50));
    }

    @Test
    void callbackInputOutputStreaming() throws Exception {
        ScriptRuntime runtime = new ScriptRuntime();

        System.out.println("Testing callback-based input+output streaming:");
        System.out.println("=".repeat(50));

        // Simulate the read-callback pattern: a feeder thread pulls from a data source
        // and pushes into an InputStreamSession, while the main thread reads the output.
        InputStreamSession inputSession = new InputStreamSession("application/json", "UTF-8");
        long inputHandle = inputSession.register();

        String inputsJson = "{\"payload\": {\"streamHandle\": \"" + inputHandle + "\", \"mimeType\": \"application/json\"}}";

        // Simulate read callback: feeds a JSON array in chunks
        byte[] sourceData = "[10, 20, 30, 40, 50]".getBytes("UTF-8");
        AtomicReference<Exception> feedError = new AtomicReference<>();

        Thread feeder = new Thread(() -> {
            try {
                int chunkSize = 8;
                for (int off = 0; off < sourceData.length; off += chunkSize) {
                    int len = Math.min(chunkSize, sourceData.length - off);
                    byte[] chunk = new byte[len];
                    System.arraycopy(sourceData, off, chunk, 0, len);
                    inputSession.write(chunk, len);
                }
                inputSession.closeWriter();
            } catch (Exception e) {
                feedError.set(e);
            }
        }, "test-read-callback-feeder");
        feeder.start();

        StreamSession session = runtime.runStreaming("output application/json\n---\npayload map ($ * 2)", inputsJson);
        assertFalse(session.isError(), "Expected successful session but got: " + session.getError());

        // Simulate write callback: collect output chunks
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buf = new byte[32];
        int callbackCount = 0;
        int n;
        while ((n = session.read(buf, buf.length)) > 0) {
            collected.write(buf, 0, n);
            callbackCount++;
        }
        String result = collected.toString(session.getCharset());
        assertTrue(result.contains("20"), "Expected 20 in result (10*2)");
        assertTrue(result.contains("100"), "Expected 100 in result (50*2)");

        StreamSession.close(session.register());
        InputStreamSession.close(inputHandle);
        feeder.join(5000);
        assertNull(feedError.get(), "Feeder thread threw: " + feedError.get());

        System.out.printf("Read callback fed %d bytes, write callback invoked %d times, output: %s%n",
                sourceData.length, callbackCount, result.trim());
        System.out.println("✓ Callback input+output streaming passed!");
        System.out.println("=".repeat(50));
    }

    @Test
    void callbackOutputStreamingError() {
        ScriptRuntime runtime = new ScriptRuntime();

        System.out.println("Testing callback-based output streaming with error:");
        System.out.println("=".repeat(50));

        StreamSession session = runtime.runStreaming("invalid syntax here", null);
        assertTrue(session.isError());
        assertNotNull(session.getError());

        System.out.println("Error correctly returned: " + session.getError());
        System.out.println("✓ Callback output streaming error passed!");
        System.out.println("=".repeat(50));
    }

    // --- Multi-engine registry (W-23692110) ---

    /** In-memory WeaveResourceResolver fake — the JVM-constructable seam standing
     *  in for CallbackWeaveResourceResolver (a CFunctionPointer, which cannot be
     *  built in test mode). */
    static final class MapResolver implements WeaveResourceResolver {
        private final Map<String, String> modules;
        MapResolver(Map<String, String> modules) { this.modules = modules; }

        @Override
        public Option<WeaveResource> resolve(NameIdentifier id) {
            String path = NameIdentifierHelper.toWeaveFilePath(id, "/");
            String key = path.startsWith("/") ? path.substring(1) : path;
            String src = modules.get(key);
            if (src == null) return Option.empty();
            return Option.apply(WeaveResource.apply(path, src));
        }

        @Override
        public Seq<WeaveResource> resolveAll(NameIdentifier id) {
            Option<WeaveResource> r = resolve(id);
            if (r.isDefined()) {
                return JavaConverters
                        .asScalaBuffer(Collections.singletonList(r.get()))
                        .toList();
            }
            return (Seq<WeaveResource>) Seq$.MODULE$.<WeaveResource>empty();
        }
    }

    private static final String IMPORT_A =
            "%dw 2.0\nimport org::test::a\noutput application/json\n---\na::greet(\"X\")";
    private static final String IMPORT_B =
            "%dw 2.0\nimport org::test::b\noutput application/json\n---\nb::greet(\"X\")";

    @Test
    void twoEnginesResolveOnlyTheirOwnModule() {
        ScriptRuntime engineA = new ScriptRuntime(new MapResolver(Map.of(
                "org/test/a.dwl", "%dw 2.0\nfun greet(n: String) = \"A:\" ++ n")));
        ScriptRuntime engineB = new ScriptRuntime(new MapResolver(Map.of(
                "org/test/b.dwl", "%dw 2.0\nfun greet(n: String) = \"B:\" ++ n")));

        long hA = ScriptRuntime.register(engineA);
        long hB = ScriptRuntime.register(engineB);
        try {
            try (ScriptRuntime.EngineLease leaseA = ScriptRuntime.acquire(hA);
                 ScriptRuntime.EngineLease leaseB = ScriptRuntime.acquire(hB)) {
                assertNotNull(leaseA);
                assertNotNull(leaseB);

                // Each engine resolves its own module...
                assertEquals("\"A:X\"", Result.parse(leaseA.runtime().run(IMPORT_A)).result);
                assertEquals("\"B:X\"", Result.parse(leaseB.runtime().run(IMPORT_B)).result);

                // ...and NOT the other's (no cross-talk).
                assertNotNull(Result.parse(leaseA.runtime().run(IMPORT_B)).error);
                assertNotNull(Result.parse(leaseB.runtime().run(IMPORT_A)).error);
            }

            // destroy removes it; a fresh handle is distinct.
            assertTrue(ScriptRuntime.destroy(hA));
            assertCannotAcquire(hA);
            assertFalse(ScriptRuntime.destroy(hA)); // already gone
            try (ScriptRuntime.EngineLease leaseB = ScriptRuntime.acquire(hB)) {
                assertNotNull(leaseB);
            }
        } finally {
            ScriptRuntime.destroy(hA);
            ScriptRuntime.destroy(hB);
        }
    }

    @Test
    void engineWithoutResolverStillRunsBuiltins() {
        ScriptRuntime engine = new ScriptRuntime(); // ClassLoader-only
        long h = ScriptRuntime.register(engine);
        try {
            try (ScriptRuntime.EngineLease lease = ScriptRuntime.acquire(h)) {
                assertNotNull(lease);
                String r = lease.runtime().run(
                        "%dw 2.0\nimport dw::core::Strings\noutput application/json\n---\nStrings::capitalize(\"hello\")");
                assertEquals("\"Hello\"", Result.parse(r).result);
            }
        } finally {
            ScriptRuntime.destroy(h);
        }
    }

    /**
     * Locks in the hard contract for the per-engine FFI entrypoints
     * ({@code run_script_engine}, {@code run_script_callback_engine},
     * {@code run_script_input_output_callback_engine} in {@link NativeLib}): running a
     * script against an unknown or already-destroyed engine handle must return exactly
     * {@code {"success":false,"error":"Unknown engine handle"}} rather than throwing.
     */
    @Test
    void unknownEngineHandleProducesExactErrorJson() {
        long unregisteredHandle = Long.MAX_VALUE;
        assertCannotAcquire(unregisteredHandle);
        assertEquals("{\"success\":false,\"error\":\"Unknown engine handle\"}",
                NativeLib.UNKNOWN_ENGINE_HANDLE_JSON);
    }

    private static void assertCannotAcquire(long handle) {
        ScriptRuntime.EngineLease lease = ScriptRuntime.acquire(handle);
        try {
            assertNull(lease);
        } finally {
            if (lease != null) {
                lease.close();
            }
        }
    }

    // ── Fail-closed input parsing (review #11 #5) ──────────────────────────

    /** (a) Malformed inputs JSON must fail closed, not silently run on empty bindings. */
    @Test
    void runMalformedInputsJsonFailsClosed() {
        ScriptRuntime runtime = new ScriptRuntime();
        // Script has no input dependency, so pre-fix the swallowed parse error
        // would let this run on empty bindings and return success:true.
        String result = runtime.run("1 + 1", "{not json");
        assertTrue(result.contains("\"success\":false"),
                "Expected success:false for malformed inputs JSON, got: " + result);
        assertFalse(Result.parse(result).success);
    }

    /**
     * (b) A malformed SECOND entry (invalid base64 content) must fail the whole run,
     * not silently drop the entry and execute bound to only the first entry.
     */
    @Test
    void runMalformedSecondEntryFailsClosed() {
        ScriptRuntime runtime = new ScriptRuntime();

        // First entry valid; second entry has invalid base64 content.
        String inputsJson = String.format(
                "{\"num1\": {\"content\": \"%s\", \"mimeType\": \"application/json\"}, " +
                "\"num2\": {\"content\": \"@@@not-valid-base64@@@\", \"mimeType\": \"application/json\"}}",
                encode(10));

        // Script references only the first binding: pre-fix the second (malformed)
        // entry would be silently dropped and this would wrongly succeed on num1 alone.
        String result = runtime.run("num1", inputsJson);
        assertFalse(Result.parse(result).success,
                "Expected fail-closed on malformed second entry, got: " + result);
        assertNotNull(Result.parse(result).error);

        // And a script that references the malformed binding must not run on a missing var.
        String result2 = runtime.run("num1 + num2", inputsJson);
        assertFalse(Result.parse(result2).success,
                "Expected fail-closed when referencing malformed binding, got: " + result2);
    }

    /** (c) A valid single-entry inputs doc must still run successfully (no regression). */
    @Test
    void runValidSingleEntryStillSucceeds() {
        ScriptRuntime runtime = new ScriptRuntime();
        String inputsJson = String.format(
                "{\"num1\": {\"content\": \"%s\", \"mimeType\": \"application/json\"}}",
                encode(41));
        String result = runtime.run("num1 + 1", inputsJson);
        assertTrue(Result.parse(result).success, "Expected success for valid inputs, got: " + result);
        assertEquals("42", Result.parse(result).result);
    }

    /** runStreaming must return an error session on malformed inputs JSON. */
    @Test
    void runStreamingMalformedInputsJsonReturnsErrorSession() {
        ScriptRuntime runtime = new ScriptRuntime();
        StreamSession session = runtime.runStreaming("1 + 1", "{not json");
        assertTrue(session.isError(), "Expected error session for malformed inputs JSON");
        assertNotNull(session.getError());
    }

    static class Result {
        boolean success;
        String result;
        String error;
        boolean binary;
        String mimeType;
        String charset;

        static Result parse(String json) {
            Result result = new Result();
            JSONObject obj = new JSONObject(json);

            result.success = obj.getBoolean("success");
            if (result.success) {
                result.binary = obj.getBoolean("binary");
                result.mimeType = obj.getString("mimeType");
                result.charset = obj.getString("charset");
                String encoded = obj.getString("result");
                if (result.binary) {
                    result.result = encoded;
                } else {
                    result.result = new String(Base64.getDecoder().decode(encoded), Charset.forName(result.charset));
                }
            } else {
                result.error = obj.getString("error");
            }
            return result;
        }
    }

}
