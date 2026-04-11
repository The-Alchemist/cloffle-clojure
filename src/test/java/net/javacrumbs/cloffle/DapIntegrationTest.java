package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
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

    private static int findFreePort() throws IOException {
        try (var ss = new java.net.ServerSocket(0)) {
            return ss.getLocalPort();
        }
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

    @Test
    public void dapAttachAndSuspendHandshakeWorks() throws Exception {
        int port = findFreePort();

        try (Engine engine = Engine.newBuilder()
                .option("dap", ":" + port)
                .option("dap.Suspend", "true")
                .option("dap.WaitAttached", "true")
                .build();
             Context context = Context.newBuilder("cloffle")
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
    }
}
