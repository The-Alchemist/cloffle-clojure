package net.javacrumbs.cloffle;

import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.DebugScope;
import com.oracle.truffle.api.debug.DebugStackFrame;
import com.oracle.truffle.api.debug.DebugValue;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.SuspendedCallback;
import com.oracle.truffle.api.debug.SuspendedEvent;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Instrument;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/**
 * Tests for DAP (Debug Adapter Protocol) integration with Cloffle.
 * Verifies that the GraalVM DAP instrument is discoverable, that the DAP
 * server starts and accepts connections, and that the Truffle debugger
 * features (breakpoints, stepping, scopes) work when DAP is active.
 */
public class DapTest {

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

    private static Source src(String name, String code) {
        return Source.newBuilder("cloffle", code, name).buildLiteral();
    }

    private static class OrderedCallback implements SuspendedCallback {
        private final Queue<Consumer<SuspendedEvent>> handlers = new LinkedList<>();
        void add(Consumer<SuspendedEvent> handler) {
            handlers.add(handler);
        }

        @Override
        public void onSuspend(SuspendedEvent event) {
            Consumer<SuspendedEvent> h = handlers.poll();
            if (h != null) {
                h.accept(event);
            } else {
                event.prepareContinue();
            }
        }
    }

    private static int findFreePort() throws IOException {
        try (var ss = new java.net.ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  1. DAP instrument is discoverable
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void dapInstrumentIsDiscoverable() {
        try (Engine engine = Engine.create()) {
            Map<String, Instrument> instruments = engine.getInstruments();
            assertTrue("'dap' instrument should be present",
                    instruments.containsKey("dap"));
            assertEquals("Debug Protocol Server",
                    instruments.get("dap").getName());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  2. DAP server starts and listens on the configured port
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void dapServerStartsAndListens() throws Exception {
        int port = findFreePort();
        boolean connected = false;

        try (Engine engine = Engine.newBuilder()
                .option("dap", ":" + port)
                .option("dap.Suspend", "false")
                .option("dap.WaitAttached", "false")
                .build();
             Context context = Context.newBuilder("cloffle")
                     .engine(engine)
                     .allowAllAccess(true)
                     .build()) {

            Thread.sleep(500);

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
                connected = socket.isConnected();
            }

            Thread.sleep(200);
        } catch (Exception e) {
            if (!connected) throw e;
        }

        assertTrue("should have connected to DAP server", connected);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  3. Cloffle eval works correctly with DAP enabled
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void evalWorksWithDapEnabled() throws Exception {
        int port = findFreePort();

        try (Engine engine = Engine.newBuilder()
                .option("dap", ":" + port)
                .option("dap.Suspend", "false")
                .option("dap.WaitAttached", "false")
                .build();
             Context context = Context.newBuilder("cloffle")
                     .engine(engine)
                     .allowAllAccess(true)
                     .build()) {

            Value result = context.eval(src("dap_eval.clj", "(+ 1 2)"));
            assertEquals(3L, result.asLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  4. Multiple expressions evaluate correctly with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void multipleEvalsWithDap() throws Exception {
        int port = findFreePort();

        try (Engine engine = Engine.newBuilder()
                .option("dap", ":" + port)
                .option("dap.Suspend", "false")
                .option("dap.WaitAttached", "false")
                .build();
             Context context = Context.newBuilder("cloffle")
                     .engine(engine)
                     .allowAllAccess(true)
                     .build()) {

            context.eval(src("dap_multi_1.clj", "(def x 10)"));
            context.eval(src("dap_multi_2.clj", "(def y 20)"));
            Value result = context.eval(src("dap_multi_3.clj", "(+ x y)"));
            assertEquals(30L, result.asLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  5. Function definition and call work with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void defnAndCallWithDap() throws Exception {
        int port = findFreePort();

        try (Engine engine = Engine.newBuilder()
                .option("dap", ":" + port)
                .option("dap.Suspend", "false")
                .option("dap.WaitAttached", "false")
                .build();
             Context context = Context.newBuilder("cloffle")
                     .engine(engine)
                     .allowAllAccess(true)
                     .build()) {

            Value result = context.eval(src("dap_defn.clj",
                    "(defn square [x] (* x x))\n(square 7)"));
            assertEquals(49L, result.asLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  6. Debugger API works alongside DAP instrument
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void debuggerApiWorksWithDap() throws Exception {
        int port = findFreePort();

        try (Engine engine = Engine.newBuilder()
                .option("dap", ":" + port)
                .option("dap.Suspend", "false")
                .option("dap.WaitAttached", "false")
                .build();
             Context context = Context.newBuilder("cloffle")
                     .engine(engine)
                     .allowAllAccess(true)
                     .build()) {

            Debugger debugger = Debugger.find(engine);
            assertNotNull("Debugger should be available with DAP", debugger);

            Source code = src("dap_dbg.clj", "(+ 1 2)");
            OrderedCallback cb = new OrderedCallback();
            boolean[] suspended = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.suspendNextExecution();

                cb.add(event -> {
                    suspended[0] = true;
                    assertNotNull("source section should be present",
                            event.getSourceSection());
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertTrue("should have suspended", suspended[0]);
                assertEquals(3L, result.asLong());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  7. Line breakpoints work with DAP enabled
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void lineBreakpointsWithDap() throws Exception {
        int port = findFreePort();

        try (Engine engine = Engine.newBuilder()
                .option("dap", ":" + port)
                .option("dap.Suspend", "false")
                .option("dap.WaitAttached", "false")
                .build();
             Context context = Context.newBuilder("cloffle")
                     .engine(engine)
                     .allowAllAccess(true)
                     .build()) {

            Debugger debugger = Debugger.find(engine);
            Source code = src("dap_bp.clj",
                    "(def a 10)\n" +   // L1
                    "(def b 20)\n" +   // L2
                    "(+ a b)\n");      // L3

            OrderedCallback cb = new OrderedCallback();
            boolean[] hit = {false};
            int[] hitLine = {0};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

                cb.add(event -> {
                    hit[0] = true;
                    hitLine[0] = event.getSourceSection().getStartLine();
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertTrue("breakpoint should fire", hit[0]);
                assertEquals(2, hitLine[0]);
                assertEquals(30L, result.asLong());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  8. Step-into works with DAP enabled
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepIntoWithDap() throws Exception {
        int port = findFreePort();

        try (Engine engine = Engine.newBuilder()
                .option("dap", ":" + port)
                .option("dap.Suspend", "false")
                .option("dap.WaitAttached", "false")
                .build();
             Context context = Context.newBuilder("cloffle")
                     .engine(engine)
                     .allowAllAccess(true)
                     .build()) {

            Debugger debugger = Debugger.find(engine);
            Source code = src("dap_stepin.clj",
                    "(defn double-it [x] (* x 2))\n" +  // L1
                    "(double-it 5)\n");                    // L2

            OrderedCallback cb = new OrderedCallback();
            int[] suspensions = {0};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

                cb.add(event -> {
                    suspensions[0]++;
                    event.prepareStepInto(1);
                });

                cb.add(event -> {
                    suspensions[0]++;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(10L, result.asLong());
                assertEquals("step-into should produce two suspensions",
                        2, suspensions[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  9. Variable scope inspection works with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void scopeInspectionWithDap() throws Exception {
        int port = findFreePort();

        try (Engine engine = Engine.newBuilder()
                .option("dap", ":" + port)
                .option("dap.Suspend", "false")
                .option("dap.WaitAttached", "false")
                .build();
             Context context = Context.newBuilder("cloffle")
                     .engine(engine)
                     .allowAllAccess(true)
                     .build()) {

            context.eval(src("dap_scope_setup.clj",
                    "(defn compute [a b] (let [sum (+ a b)] (* sum 2)))"));

            Debugger debugger = Debugger.find(engine);
            Source code = src("dap_scope_call.clj", "(compute 3 4)\n");

            OrderedCallback cb = new OrderedCallback();
            List<String> varNames = new ArrayList<>();
            boolean[] scopeFound = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> event.prepareStepInto(1));

                cb.add(event -> {
                    DebugStackFrame frame = event.getTopStackFrame();
                    DebugScope scope = frame.getScope();
                    if (scope != null) {
                        scopeFound[0] = true;
                        for (DebugValue val : scope.getDeclaredValues()) {
                            varNames.add(val.getName());
                        }
                    }
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(14L, result.asLong());
                assertTrue("scope should have been found", scopeFound[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  10. Stack frames available with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stackFramesWithDap() throws Exception {
        int port = findFreePort();

        try (Engine engine = Engine.newBuilder()
                .option("dap", ":" + port)
                .option("dap.Suspend", "false")
                .option("dap.WaitAttached", "false")
                .build();
             Context context = Context.newBuilder("cloffle")
                     .engine(engine)
                     .allowAllAccess(true)
                     .build()) {

            context.eval(src("dap_stack_setup.clj",
                    "(defn c [] (+ 1 2))\n" +
                    "(defn b [] (+ 0 (c)))\n" +
                    "(defn a [] (+ 0 (b)))"));

            Debugger debugger = Debugger.find(engine);
            Source code = src("dap_stack_call.clj", "(a)\n");

            OrderedCallback cb = new OrderedCallback();
            List<Integer> depths = new ArrayList<>();

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> {
                    event.prepareStepInto(1);
                });

                for (int i = 0; i < 5; i++) {
                    cb.add(event -> {
                        int d = 0;
                        for (DebugStackFrame frame : event.getStackFrames()) {
                            if (!frame.isHost() && !frame.isInternal()) {
                                d++;
                            }
                        }
                        depths.add(d);
                        event.prepareStepInto(1);
                    });
                }

                Value result = context.eval(code);

                assertEquals(3L, result.asLong());
                assertFalse("should have recorded stack depths", depths.isEmpty());
                assertTrue("should have at least one frame",
                        depths.stream().allMatch(d -> d >= 1));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  11. Custom DAP port works
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void customPortWorks() throws Exception {
        int port = findFreePort();

        try (Engine engine = Engine.newBuilder()
                .option("dap", ":" + port)
                .option("dap.Suspend", "false")
                .option("dap.WaitAttached", "false")
                .build();
             Context context = Context.newBuilder("cloffle")
                     .engine(engine)
                     .allowAllAccess(true)
                     .build()) {

            Value result = context.eval(src("dap_custom_port.clj", "(* 6 7)"));
            assertEquals("eval on custom DAP port should work",
                    42L, result.asLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  12. Recursive function debugging with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void recursiveDebuggingWithDap() throws Exception {
        int port = findFreePort();

        try (Engine engine = Engine.newBuilder()
                .option("dap", ":" + port)
                .option("dap.Suspend", "false")
                .option("dap.WaitAttached", "false")
                .build();
             Context context = Context.newBuilder("cloffle")
                     .engine(engine)
                     .allowAllAccess(true)
                     .build()) {

            Debugger debugger = Debugger.find(engine);
            Source code = src("dap_factorial.clj",
                    "(defn factorial [n]\n" +
                    "  (if (<= n 1)\n" +
                    "    1\n" +
                    "    (* n (factorial (dec n)))))\n" +
                    "(factorial 5)\n");

            OrderedCallback cb = new OrderedCallback();
            List<Integer> stackDepths = new ArrayList<>();

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

                for (int i = 0; i < 5; i++) {
                    cb.add(event -> {
                        int d = 0;
                        for (DebugStackFrame f : event.getStackFrames()) {
                            if (f.getSourceSection() != null) d++;
                        }
                        stackDepths.add(d);
                        event.prepareContinue();
                    });
                }

                Value result = context.eval(code);

                assertEquals(120L, result.asLong());
                assertEquals(5, stackDepths.size());
                assertTrue("stack should grow with recursion",
                        stackDepths.get(0) <= stackDepths.get(4));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  13. String and collection results work with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stringAndCollectionResultsWithDap() throws Exception {
        int port = findFreePort();

        try (Engine engine = Engine.newBuilder()
                .option("dap", ":" + port)
                .option("dap.Suspend", "false")
                .option("dap.WaitAttached", "false")
                .build();
             Context context = Context.newBuilder("cloffle")
                     .engine(engine)
                     .allowAllAccess(true)
                     .build()) {

            Value strResult = context.eval(src("dap_str.clj",
                    "(str \"Hello, \" \"DAP!\")"));
            assertEquals("Hello, DAP!", strResult.asString());

            Value vecResult = context.eval(src("dap_vec.clj",
                    "(count [1 2 3 4 5])"));
            assertEquals(5L, vecResult.asLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  14. Exception handling works with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void exceptionHandlingWithDap() throws Exception {
        int port = findFreePort();

        try (Engine engine = Engine.newBuilder()
                .option("dap", ":" + port)
                .option("dap.Suspend", "false")
                .option("dap.WaitAttached", "false")
                .build();
             Context context = Context.newBuilder("cloffle")
                     .engine(engine)
                     .allowAllAccess(true)
                     .build()) {

            Value result = context.eval(src("dap_exc.clj",
                    "(try (throw (Exception. \"test-error\")) " +
                    "(catch Exception e (.getMessage e)))"));
            assertEquals("test-error", result.asString());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  15. Step-over works with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepOverWithDap() throws Exception {
        int port = findFreePort();

        try (Engine engine = Engine.newBuilder()
                .option("dap", ":" + port)
                .option("dap.Suspend", "false")
                .option("dap.WaitAttached", "false")
                .build();
             Context context = Context.newBuilder("cloffle")
                     .engine(engine)
                     .allowAllAccess(true)
                     .build()) {

            Debugger debugger = Debugger.find(engine);
            Source code = src("dap_stepover.clj",
                    "(def a 1)\n" +   // L1
                    "(def b 2)\n" +   // L2
                    "(+ a b)\n");     // L3

            OrderedCallback cb = new OrderedCallback();
            List<Integer> stoppedLines = new ArrayList<>();

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.suspendNextExecution();

                cb.add(event -> {
                    stoppedLines.add(event.getSourceSection().getStartLine());
                    event.prepareStepOver(1);
                });
                cb.add(event -> {
                    stoppedLines.add(event.getSourceSection().getStartLine());
                    event.prepareStepOver(1);
                });
                cb.add(event -> {
                    stoppedLines.add(event.getSourceSection().getStartLine());
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(3, stoppedLines.size());
                assertEquals(3L, result.asLong());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  16. Loop/recur debugging with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void loopRecurWithDap() throws Exception {
        int port = findFreePort();

        try (Engine engine = Engine.newBuilder()
                .option("dap", ":" + port)
                .option("dap.Suspend", "false")
                .option("dap.WaitAttached", "false")
                .build();
             Context context = Context.newBuilder("cloffle")
                     .engine(engine)
                     .allowAllAccess(true)
                     .build()) {

            Debugger debugger = Debugger.find(engine);
            Source code = src("dap_loop.clj",
                    "(loop [i 0]\n" +
                    "  (if (< i 3)\n" +
                    "    (recur (inc i))\n" +
                    "    i))\n");

            OrderedCallback cb = new OrderedCallback();
            List<Integer> hitLines = new ArrayList<>();

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(3).build());

                for (int i = 0; i < 3; i++) {
                    cb.add(event -> {
                        hitLines.add(event.getSourceSection().getStartLine());
                        event.prepareContinue();
                    });
                }

                Value result = context.eval(code);

                assertEquals(3L, result.asLong());
                assertEquals("recur breakpoint should fire 3 times",
                        3, hitLines.size());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  17. Language ID reported correctly with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void languageIdWithDap() throws Exception {
        int port = findFreePort();

        try (Engine engine = Engine.newBuilder()
                .option("dap", ":" + port)
                .option("dap.Suspend", "false")
                .option("dap.WaitAttached", "false")
                .build();
             Context context = Context.newBuilder("cloffle")
                     .engine(engine)
                     .allowAllAccess(true)
                     .build()) {

            Debugger debugger = Debugger.find(engine);
            Source code = src("dap_langid.clj", "(+ 1 2)\n");

            OrderedCallback cb = new OrderedCallback();
            String[] langId = {null};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> {
                    DebugStackFrame frame = event.getTopStackFrame();
                    if (frame.getLanguage() != null) {
                        langId[0] = frame.getLanguage().getId();
                    }
                    event.prepareContinue();
                });

                context.eval(code);

                assertEquals("cloffle", langId[0]);
            }
        }
    }
}
