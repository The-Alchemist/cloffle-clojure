package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DapIntegrationTest {

    static {
        System.setProperty("polyglot.log.dap.level", "OFF");
        java.util.logging.Logger.getLogger("dap").setLevel(java.util.logging.Level.OFF);
    }

    private static Source src(String name, String code) {
        return Source.newBuilder("cloffle", code, name).buildLiteral();
    }

    @BeforeClass
    public static void warmUpRuntime() {
        DapLifecycleSupport.warmUpRuntime();
    }

    private static int findFreePort() throws IOException {
        return DapLifecycleSupport.allocatePort();
    }

    private static void sendDapRequest(Socket socket, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        socket.getOutputStream().write(header);
        socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
    }

    private static String readDapMessage(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        StringBuilder header = new StringBuilder();
        while (!header.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("Unexpected EOF while reading DAP header");
            }
            header.append((char) b);
        }

        int contentLength = -1;
        for (String line : header.toString().split("\r\n")) {
            if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                contentLength = Integer.parseInt(line.substring("Content-Length:".length()).trim());
                break;
            }
        }
        if (contentLength < 0) {
            throw new IOException("DAP message missing Content-Length header");
        }

        byte[] body = in.readNBytes(contentLength);
        if (body.length != contentLength) {
            throw new IOException("Unexpected EOF while reading DAP body");
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static String waitForDapMessage(Socket socket, Predicate<String> predicate, String description, long timeoutMs)
            throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int remaining = (int) Math.max(1, deadline - System.currentTimeMillis());
            socket.setSoTimeout(remaining);
            try {
                String msg = readDapMessage(socket);
                if (predicate.test(msg)) {
                    return msg;
                }
            } catch (SocketTimeoutException e) {
                break;
            }
        }
        fail("Timed out waiting for DAP message: " + description);
        return null;
    }

    private static int extractJsonIntField(String json, String fieldName) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    private static Boolean extractJsonBooleanField(String json, String fieldName) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(true|false)");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return Boolean.parseBoolean(m.group(1));
        }
        return null;
    }

    private static String waitForResponse(Socket socket, String command, long timeoutMs) throws IOException {
        return waitForDapMessage(socket,
                msg -> msg.contains("\"type\":\"response\"") && msg.contains("\"command\":\"" + command + "\""),
                command + " response", timeoutMs);
    }

    private static void assertCapabilityTrue(String initializeResponse, String capability) {
        Boolean value = extractJsonBooleanField(initializeResponse, capability);
        assertEquals("initialize capabilities should advertise " + capability + "=true; got: " + initializeResponse,
                Boolean.TRUE, value);
    }

    private static void assertCommandRecognized(String response, String command) {
        assertFalse(command + " should be a recognized DAP command, not an unsupported stub. Response: " + response,
                response.contains("'" + command + "' command not supported")
                        || response.contains("\"" + command + "\" command not supported"));
        assertTrue(command + " response should be success=true. Response: " + response,
                response.contains("\"success\":true"));
    }

    @Test
    public void dapAttachAndSuspendHandshakeWorks() throws Exception {
        int port = findFreePort();

        Engine engine = Engine.newBuilder()
                .option("dap", ":" + port)
                .option("dap.Suspend", "true")
                .option("dap.WaitAttached", "true")
                .build();
        try {
            try (Context context = Context.newBuilder("cloffle")
                    .engine(engine)
                    .allowAllAccess(true)
                    .build()) {

                Source code = src("dap_attach_suspend.clj", "(+ 1 2)\n");
                String[] stopped = {null};
                int[] threadId = {-1};
                Throwable[] clientError = {null};

                Thread dapClientThread = new Thread(() -> {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);

                        sendDapRequest(socket,
                                "{\"seq\":1,\"type\":\"request\",\"command\":\"initialize\",\"arguments\":{\"adapterID\":\"cloffle-tests\"}}");
                        waitForDapMessage(socket,
                                msg -> msg.contains("\"type\":\"response\"")
                                        && msg.contains("\"command\":\"initialize\"")
                                        && msg.contains("\"success\":true"),
                                "initialize response", 3000);

                        sendDapRequest(socket,
                                "{\"seq\":2,\"type\":\"request\",\"command\":\"attach\",\"arguments\":{}}");
                        waitForDapMessage(socket,
                                msg -> msg.contains("\"type\":\"response\"")
                                        && msg.contains("\"command\":\"attach\"")
                                        && msg.contains("\"success\":true"),
                                "attach response", 3000);

                        sendDapRequest(socket,
                                "{\"seq\":3,\"type\":\"request\",\"command\":\"configurationDone\",\"arguments\":{}}");
                        waitForDapMessage(socket,
                                msg -> msg.contains("\"type\":\"response\"")
                                        && msg.contains("\"command\":\"configurationDone\"")
                                        && msg.contains("\"success\":true"),
                                "configurationDone response", 3000);

                        stopped[0] = waitForDapMessage(socket,
                                msg -> msg.contains("\"type\":\"event\"")
                                        && msg.contains("\"event\":\"stopped\""),
                                "stopped event", 5000);
                        threadId[0] = extractJsonIntField(stopped[0], "threadId");

                        sendDapRequest(socket,
                                "{\"seq\":4,\"type\":\"request\",\"command\":\"continue\",\"arguments\":{\"threadId\":" + threadId[0] + "}}");
                        waitForDapMessage(socket,
                                msg -> msg.contains("\"type\":\"response\"")
                                        && msg.contains("\"command\":\"continue\"")
                                        && msg.contains("\"success\":true"),
                                "continue response", 3000);

                        sendDapRequest(socket,
                                "{\"seq\":5,\"type\":\"request\",\"command\":\"disconnect\",\"arguments\":{\"terminateDebuggee\":false}}");
                        waitForDapMessage(socket,
                                msg -> msg.contains("\"type\":\"response\"")
                                        && msg.contains("\"command\":\"disconnect\"")
                                        && msg.contains("\"success\":true"),
                                "disconnect response", 3000);
                        // Give the server time to send 'terminated' before we close the TCP socket.
                        Thread.sleep(150);
                    } catch (Throwable t) {
                        clientError[0] = t;
                    }
                }, "dap-attach-client");
                dapClientThread.start();

                Value result = context.eval(code);

                dapClientThread.join(5000);
                assertFalse("DAP client thread should finish", dapClientThread.isAlive());
                if (clientError[0] != null) {
                    throw new AssertionError("DAP client flow failed", clientError[0]);
                }
                assertNotNull("should receive a stopped event after attach/configurationDone", stopped[0]);
                assertTrue("stopped event should include a reason", stopped[0].contains("\"reason\":"));
                assertTrue("stopped event should contain threadId", threadId[0] > 0);
                assertEquals(3L, result.asLong());
            }
            // Truffle 25.1+ keeps the DAP client connection system thread briefly after disconnect;
            // Engine.close() refuses while it is still alive.
            awaitDapSystemThreadExit(2000);
        } finally {
            closeEngineIgnoringClosedSocket(engine);
        }
    }

    /**
     * Pins GraalVM dap-tool's initialize Capabilities and exercises the optional
     * commands those flags imply ({@code setExceptionBreakpoints}, {@code setVariable},
     * {@code breakpointLocations}, {@code loadedSources}). {@code completions} is
     * not advertised and the default handler rejects it.
     *
     * <p>Uses {@code dap.Suspend=false} so probing these commands does not pause inside
     * clojure.core load the way {@code WaitAttached} tests do.
     */
    @Test
    public void initializeCapabilitiesAndOptionalCommands() throws Exception {
        int port = findFreePort();

        Engine engine = Engine.newBuilder()
                .option("dap", ":" + port)
                .option("dap.Suspend", "false")
                .option("dap.WaitAttached", "false")
                .build();
        try (Context context = CloffleEvalTestSupport.newContext(engine, "dap-caps")) {
            Thread.sleep(400);

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);

                sendDapRequest(socket,
                        "{\"seq\":1,\"type\":\"request\",\"command\":\"initialize\",\"arguments\":{\"adapterID\":\"cloffle-tests\",\"linesStartAt1\":true,\"columnsStartAt1\":true}}");
                String initializeResponse = waitForResponse(socket, "initialize", 3000);

                assertCapabilityTrue(initializeResponse, "supportsConfigurationDoneRequest");
                assertCapabilityTrue(initializeResponse, "supportsFunctionBreakpoints");
                assertCapabilityTrue(initializeResponse, "supportsConditionalBreakpoints");
                assertCapabilityTrue(initializeResponse, "supportsHitConditionalBreakpoints");
                assertCapabilityTrue(initializeResponse, "supportsSetVariable");
                assertCapabilityTrue(initializeResponse, "supportsExceptionInfoRequest");
                assertCapabilityTrue(initializeResponse, "supportsLoadedSourcesRequest");
                assertCapabilityTrue(initializeResponse, "supportsLogPoints");
                assertCapabilityTrue(initializeResponse, "supportsBreakpointLocationsRequest");
                assertTrue("exceptionBreakpointFilters should include 'all': " + initializeResponse,
                        initializeResponse.contains("\"filter\":\"all\"")
                                || initializeResponse.contains("\"filter\": \"all\""));
                assertTrue("exceptionBreakpointFilters should include 'uncaught': " + initializeResponse,
                        initializeResponse.contains("\"filter\":\"uncaught\"")
                                || initializeResponse.contains("\"filter\": \"uncaught\""));
                assertNotEquals("supportsCompletionsRequest is not advertised by Graal dap-tool: "
                                + initializeResponse,
                        Boolean.TRUE, extractJsonBooleanField(initializeResponse, "supportsCompletionsRequest"));

                sendDapRequest(socket,
                        "{\"seq\":2,\"type\":\"request\",\"command\":\"attach\",\"arguments\":{}}");
                waitForResponse(socket, "attach", 3000);

                sendDapRequest(socket,
                        "{\"seq\":3,\"type\":\"request\",\"command\":\"loadedSources\",\"arguments\":{}}");
                String loadedSourcesResponse = waitForResponse(socket, "loadedSources", 3000);
                assertCommandRecognized(loadedSourcesResponse, "loadedSources");
                assertTrue("loadedSources should return a sources array: " + loadedSourcesResponse,
                        loadedSourcesResponse.contains("\"sources\"")
                                || loadedSourcesResponse.contains("\"name\"")
                                || loadedSourcesResponse.contains("\"path\""));

                sendDapRequest(socket,
                        "{\"seq\":4,\"type\":\"request\",\"command\":\"breakpointLocations\",\"arguments\":{\"source\":{\"name\":\"dap_caps.clj\"},\"line\":1}}");
                String breakpointLocationsResponse = waitForResponse(socket, "breakpointLocations", 3000);
                assertCommandRecognized(breakpointLocationsResponse, "breakpointLocations");
                assertTrue("breakpointLocations should return a body with locations or breakpoints: "
                                + breakpointLocationsResponse,
                        breakpointLocationsResponse.contains("\"locations\"")
                                || breakpointLocationsResponse.contains("\"breakpoints\""));

                sendDapRequest(socket,
                        "{\"seq\":5,\"type\":\"request\",\"command\":\"setExceptionBreakpoints\",\"arguments\":{\"filters\":[\"uncaught\"]}}");
                String exceptionBreakpointsResponse = waitForResponse(socket, "setExceptionBreakpoints", 3000);
                assertCommandRecognized(exceptionBreakpointsResponse, "setExceptionBreakpoints");

                sendDapRequest(socket,
                        "{\"seq\":6,\"type\":\"request\",\"command\":\"setVariable\",\"arguments\":{\"variablesReference\":1,\"name\":\"noSuchDapVar\",\"value\":\"42\"}}");
                String setVariableResponse = waitForResponse(socket, "setVariable", 3000);
                assertFalse("setVariable is implemented; it must not report command-not-supported. Response: "
                                + setVariableResponse,
                        setVariableResponse.contains("'setVariable' command not supported"));

                String completionsResponse;
                try {
                    sendDapRequest(socket,
                            "{\"seq\":7,\"type\":\"request\",\"command\":\"completions\",\"arguments\":{\"text\":\"inc\",\"column\":3,\"line\":1}}");
                    completionsResponse = waitForResponse(socket, "completions", 3000);
                } catch (Throwable completionsError) {
                    completionsResponse = completionsError.toString();
                }
                assertFalse("completions is unimplemented by Graal dap-tool. Response: " + completionsResponse,
                        completionsResponse.contains("\"command\":\"completions\"")
                                && completionsResponse.contains("\"success\":true"));

                sendDapRequest(socket,
                        "{\"seq\":8,\"type\":\"request\",\"command\":\"disconnect\",\"arguments\":{\"terminateDebuggee\":false}}");
                waitForResponse(socket, "disconnect", 3000);
                Thread.sleep(150);
            }

            assertEquals(3L, context.eval(src("dap_caps.clj", "(+ 1 2)")).asLong());
            awaitDapSystemThreadExit(2000);
        } finally {
            closeEngineIgnoringClosedSocket(engine);
        }
    }

    /**
     * DAP dispose sends a {@code terminated} event; if the client already closed the
     * TCP socket, Engine.close surfaces that as a PolyglotException.
     */
    private static void closeEngineIgnoringClosedSocket(Engine engine) {
        try {
            engine.close();
        } catch (PolyglotException e) {
            String message = String.valueOf(e.getMessage());
            if (!message.contains("Socket closed") && !message.contains("SocketException")) {
                throw e;
            }
        }
    }

    /** Poll until Engine.close would not see a live DAP system thread, or timeout. */
    private static void awaitDapSystemThreadExit(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean dapAlive = false;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                String name = t.getName();
                if (name != null && name.contains("DAP client connection") && t.isAlive()) {
                    dapAlive = true;
                    break;
                }
            }
            if (!dapAlive) {
                return;
            }
            Thread.sleep(50);
        }
    }
}
