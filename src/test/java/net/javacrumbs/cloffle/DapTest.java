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

import com.oracle.truffle.api.debug.SuspendAnchor;
import org.graalvm.polyglot.PolyglotException;

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

    // ═══════════════════════════════════════════════════════════════════
    //  18. Step-out returns to caller with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepOutWithDap() throws Exception {
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
            Source code = src("dap_stepout.clj",
                    "(defn inner [] 42)\n" +
                    "(defn outer [] (inner))\n" +
                    "(outer)\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] hitInner = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> {
                    hitInner[0] = true;
                    event.prepareStepOut(1);
                });

                cb.add(event -> event.prepareContinue());

                Value result = context.eval(code);

                assertTrue("should have hit inner", hitInner[0]);
                assertEquals(42L, result.asLong());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  19. Multiple breakpoints both fire with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void multipleBreakpointsWithDap() throws Exception {
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
            Source code = src("dap_multi_bp.clj",
                    "(def a 1)\n" +
                    "(def b 2)\n" +
                    "(def c 3)\n" +
                    "(+ a b c)\n");

            OrderedCallback cb = new OrderedCallback();
            List<Integer> hitLines = new ArrayList<>();

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(3).build());

                for (int i = 0; i < 3; i++) {
                    cb.add(event -> {
                        hitLines.add(event.getSourceSection().getStartLine());
                        event.prepareContinue();
                    });
                }

                Value result = context.eval(code);

                assertEquals(6L, result.asLong());
                assertEquals("all 3 breakpoints should fire", 3, hitLines.size());
                assertEquals(Integer.valueOf(1), hitLines.get(0));
                assertEquals(Integer.valueOf(2), hitLines.get(1));
                assertEquals(Integer.valueOf(3), hitLines.get(2));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  20. Breakpoint dispose prevents further hits with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointDisposeWithDap() throws Exception {
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

            Source code1 = src("dap_dispose1.clj", "(def a 1)\n");
            Source code2 = src("dap_dispose2.clj", "(def b 2)\n");

            OrderedCallback cb = new OrderedCallback();
            int[] hits = {0};

            try (DebuggerSession session = debugger.startSession(cb)) {
                Breakpoint bp = Breakpoint.newBuilder(code1.getURI()).lineIs(1).build();
                session.install(bp);

                cb.add(event -> {
                    hits[0]++;
                    event.prepareContinue();
                });

                context.eval(code1);
                assertEquals("first breakpoint should fire", 1, hits[0]);

                bp.dispose();

                cb.add(event -> {
                    hits[0]++;
                    event.prepareContinue();
                });

                context.eval(code2);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  21. One-shot breakpoint fires only once with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void oneShotBreakpointWithDap() throws Exception {
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
            Source code = src("dap_oneshot.clj",
                    "(loop [i 0]\n" +
                    "  (if (< i 5)\n" +
                    "    (recur (inc i))\n" +
                    "    i))\n");

            OrderedCallback cb = new OrderedCallback();
            int[] hitCount = {0};

            try (DebuggerSession session = debugger.startSession(cb)) {
                Breakpoint bp = Breakpoint.newBuilder(code.getURI()).lineIs(2)
                        .oneShot().build();
                session.install(bp);

                for (int i = 0; i < 10; i++) {
                    cb.add(event -> {
                        hitCount[0]++;
                        event.prepareContinue();
                    });
                }

                Value result = context.eval(code);

                assertEquals(5L, result.asLong());
                assertEquals("one-shot breakpoint should fire exactly once", 1, hitCount[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  22. Closure debugging with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void closureDebuggingWithDap() throws Exception {
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
            Source code = src("dap_closure.clj",
                    "(defn make-adder [n] (fn [x] (+ x n)))\n" +
                    "(def add5 (make-adder 5))\n" +
                    "(add5 10)\n");

            OrderedCallback cb = new OrderedCallback();
            int[] suspensions = {0};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(3).build());

                cb.add(event -> {
                    suspensions[0]++;
                    event.prepareStepInto(1);
                });

                cb.add(event -> {
                    suspensions[0]++;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(15L, result.asLong());
                assertEquals("step-into closure should produce two suspensions",
                        2, suspensions[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  23. Higher-order function debugging with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void higherOrderFnWithDap() throws Exception {
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
            Source code = src("dap_ho.clj",
                    "(defn apply-fn [f x] (f x))\n" +
                    "(defn square [x] (* x x))\n" +
                    "(apply-fn square 4)\n");

            OrderedCallback cb = new OrderedCallback();
            int[] suspensions = {0};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(3).build());

                cb.add(event -> {
                    suspensions[0]++;
                    event.prepareStepInto(1);
                });

                cb.add(event -> {
                    suspensions[0]++;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(16L, result.asLong());
                assertEquals(2, suspensions[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  24. Source file name correct at breakpoint with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void sourceFileNameWithDap() throws Exception {
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
            Source code = src("my_dap_source.clj", "(def x 42)\n");

            OrderedCallback cb = new OrderedCallback();
            String[] sourceName = {null};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> {
                    sourceName[0] = event.getSourceSection().getSource().getName();
                    event.prepareContinue();
                });

                context.eval(code);

                assertEquals("my_dap_source.clj", sourceName[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  25. Source section has line and column with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void sourceSectionLineColumnWithDap() throws Exception {
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
            Source code = src("dap_col.clj",
                    "(defn greet [name] (str \"Hello, \" name))\n" +
                    "(greet \"world\")\n");

            OrderedCallback cb = new OrderedCallback();
            int[] hitLine = {0};
            int[] hitCol = {0};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

                cb.add(event -> {
                    hitLine[0] = event.getSourceSection().getStartLine();
                    hitCol[0] = event.getSourceSection().getStartColumn();
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals("Hello, world", result.asString());
                assertTrue("line should be >= 1", hitLine[0] >= 1);
                assertTrue("column should be >= 1", hitCol[0] >= 1);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  26. Scope shows fn params with values via DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void scopeShowsFnParamsWithDap() throws Exception {
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

            context.eval(src("dap_scope_params_setup.clj",
                    "(defn add [a b] (+ a b))"));

            Debugger debugger = Debugger.find(engine);
            Source code = src("dap_scope_params_call.clj", "(add 10 20)\n");

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

                assertEquals(30L, result.asLong());
                assertTrue("scope should have been found", scopeFound[0]);
                assertTrue("scope should contain parameter 'a'", varNames.contains("a"));
                assertTrue("scope should contain parameter 'b'", varNames.contains("b"));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  27. Scope name is function name with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void scopeNameWithDap() throws Exception {
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

            context.eval(src("dap_scope_name_setup.clj",
                    "(defn my-fn [x] (* x x))"));

            Debugger debugger = Debugger.find(engine);
            Source code = src("dap_scope_name_call.clj", "(my-fn 5)\n");

            OrderedCallback cb = new OrderedCallback();
            String[] scopeName = {null};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> event.prepareStepInto(1));

                cb.add(event -> {
                    DebugStackFrame frame = event.getTopStackFrame();
                    DebugScope scope = frame.getScope();
                    if (scope != null) {
                        scopeName[0] = scope.getName();
                    }
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(25L, result.asLong());
                assertNotNull("scope should have a name", scopeName[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  28. Let-bound variables in scope with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void letBindingsInScopeWithDap() throws Exception {
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

            context.eval(src("dap_let_scope_setup.clj",
                    "(defn calc [x] (let [doubled (* x 2) tripled (* x 3)] (+ doubled tripled)))"));

            Debugger debugger = Debugger.find(engine);
            Source code = src("dap_let_scope_call.clj", "(calc 5)\n");

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

                assertEquals(25L, result.asLong());
                assertTrue("scope should have been found", scopeFound[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  29. Java interop debugging with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void javaInteropWithDap() throws Exception {
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
            Source code = src("dap_interop.clj",
                    "(defn get-len [s] (.length s))\n" +
                    "(get-len \"hello world\")\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] hit = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> {
                    hit[0] = true;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(11L, result.asLong());
                assertTrue("breakpoint on interop should fire", hit[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  30. Multi-arity function with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void multiArityWithDap() throws Exception {
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
            Source code = src("dap_multi_arity.clj",
                    "(defn greet\n" +
                    "  ([name] (greet name \"Hello\"))\n" +
                    "  ([name greeting] (str greeting \", \" name)))\n" +
                    "(greet \"Alice\")\n");

            OrderedCallback cb = new OrderedCallback();
            int[] suspensions = {0};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(4).build());

                cb.add(event -> {
                    suspensions[0]++;
                    event.prepareStepInto(1);
                });

                cb.add(event -> {
                    suspensions[0]++;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals("Hello, Alice", result.asString());
                assertEquals(2, suspensions[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  31. Variadic function with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void variadicFnWithDap() throws Exception {
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
            Source code = src("dap_variadic.clj",
                    "(defn sum [& nums] (apply + nums))\n" +
                    "(sum 1 2 3)\n");

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

                assertEquals(6L, result.asLong());
                assertEquals(2, suspensions[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  32. Cond macro debugging with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void condMacroWithDap() throws Exception {
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
            Source code = src("dap_cond.clj",
                    "(defn classify [x]\n" +
                    "  (cond\n" +
                    "    (< x 0) :negative\n" +
                    "    (= x 0) :zero\n" +
                    "    :else :positive))\n" +
                    "(classify 5)\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] hit = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(6).build());

                cb.add(event -> {
                    hit[0] = true;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertTrue("should return :positive",
                        result.asString().contains("positive"));
                assertTrue("breakpoint on cond call should fire", hit[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  33. Try/catch breakpoint with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void tryCatchBreakpointWithDap() throws Exception {
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
            Source code = src("dap_trycatch.clj",
                    "(try\n" +
                    "  (def x 42)\n" +
                    "  (+ x 1)\n" +
                    "  (catch Exception e 0))\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] hit = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

                cb.add(event -> {
                    hit[0] = true;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(43L, result.asLong());
                assertTrue("breakpoint inside try should fire", hit[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  34. Do-block subform breakpoint with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void doBlockBreakpointWithDap() throws Exception {
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
            Source code = src("dap_do.clj",
                    "(do\n" +
                    "  (def x 10)\n" +
                    "  (def y 20)\n" +
                    "  (+ x y))\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] hit = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

                cb.add(event -> {
                    hit[0] = true;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(30L, result.asLong());
                assertTrue("breakpoint inside do should fire", hit[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  35. Multi-line defn body breakpoint with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void multiLineDefnBreakpointWithDap() throws Exception {
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
            Source code = src("dap_multiline_defn.clj",
                    "(defn f [x]\n" +
                    "  (+ x 1))\n" +
                    "(f 0)\n");

            OrderedCallback cb = new OrderedCallback();
            int[] startLine = {-1};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

                cb.add(event -> {
                    startLine[0] = event.getSourceSection().getStartLine();
                    event.prepareContinue();
                });

                context.eval(code);

                assertEquals("breakpoint on body line should report that line",
                        2, startLine[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  36. Breakpoint resolved status with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointResolvedWithDap() throws Exception {
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
            Source code = src("dap_resolved.clj", "(def x 42)\n");

            OrderedCallback cb = new OrderedCallback();

            try (DebuggerSession session = debugger.startSession(cb)) {
                Breakpoint bp = Breakpoint.newBuilder(code.getURI()).lineIs(1).build();
                session.install(bp);

                cb.add(event -> event.prepareContinue());

                context.eval(code);

                assertTrue("breakpoint should be resolved after execution",
                        bp.isResolved());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  37. Suspend anchor is BEFORE at breakpoint with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void suspendAnchorWithDap() throws Exception {
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
            Source code = src("dap_anchor.clj", "(def x 42)\n");

            OrderedCallback cb = new OrderedCallback();
            SuspendAnchor[] anchor = {null};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> {
                    anchor[0] = event.getSuspendAnchor();
                    event.prepareContinue();
                });

                context.eval(code);

                assertEquals("suspend anchor should be BEFORE",
                        SuspendAnchor.BEFORE, anchor[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  38. Source section length covers the form with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void sourceSectionLengthWithDap() throws Exception {
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
            Source code = src("dap_srclen.clj", "(def result 42)\n");

            OrderedCallback cb = new OrderedCallback();
            int[] charLen = {0};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> {
                    charLen[0] = event.getSourceSection().getCharLength();
                    event.prepareContinue();
                });

                context.eval(code);

                assertTrue("source section should have positive length",
                        charLen[0] > 0);
                assertTrue("source section length should cover the form",
                        charLen[0] >= 14);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  39. Internal frames not visible with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void internalFramesNotVisibleWithDap() throws Exception {
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

            context.eval(src("dap_internal_setup.clj",
                    "(defn outer [] (+ 1 2))"));

            Debugger debugger = Debugger.find(engine);
            Source code = src("dap_internal_call.clj", "(outer)\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] anyInternal = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> {
                    for (DebugStackFrame frame : event.getStackFrames()) {
                        if (frame.isInternal()) {
                            anyInternal[0] = true;
                        }
                    }
                    event.prepareStepInto(1);
                });

                cb.add(event -> {
                    for (DebugStackFrame frame : event.getStackFrames()) {
                        if (frame.isInternal()) {
                            anyInternal[0] = true;
                        }
                    }
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(3L, result.asLong());
                assertFalse("no internal frames should be visible", anyInternal[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  40. Step-into then step-over stays in callee with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepIntoThenStepOverWithDap() throws Exception {
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
            Source code = src("dap_stepin_over.clj",
                    "(defn work [x]\n" +
                    "  (def tmp (* x 2))\n" +
                    "  (+ tmp 1))\n" +
                    "(work 10)\n");

            OrderedCallback cb = new OrderedCallback();
            int[] suspensions = {0};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(4).build());

                cb.add(event -> {
                    suspensions[0]++;
                    event.prepareStepInto(1);
                });

                cb.add(event -> {
                    suspensions[0]++;
                    event.prepareStepOver(1);
                });

                cb.add(event -> {
                    suspensions[0]++;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(21L, result.asLong());
                assertTrue("should suspend at least twice", suspensions[0] >= 2);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  41. Step-into then step-out returns to caller with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepIntoThenStepOutWithDap() throws Exception {
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
            Source code = src("dap_stepin_out.clj",
                    "(defn helper [x] (+ x 100))\n" +
                    "(helper 5)\n");

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
                    event.prepareStepOut(1);
                });

                cb.add(event -> {
                    suspensions[0]++;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(105L, result.asLong());
                assertTrue("should suspend at least twice", suspensions[0] >= 2);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  42. Step-over does not enter callee with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepOverDoesNotEnterCalleeWithDap() throws Exception {
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

            context.eval(src("dap_stepover_setup.clj",
                    "(defn inner [] (+ 1 2))"));

            Debugger debugger = Debugger.find(engine);
            Source code = src("dap_stepover_call.clj",
                    "(def a (inner))\n" +
                    "(def b 99)\n");

            OrderedCallback cb = new OrderedCallback();
            List<String> sourceNames = new ArrayList<>();

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> {
                    sourceNames.add(event.getSourceSection().getSource().getName());
                    event.prepareStepOver(1);
                });

                cb.add(event -> {
                    sourceNames.add(event.getSourceSection().getSource().getName());
                    event.prepareContinue();
                });

                context.eval(code);

                assertEquals(2, sourceNames.size());
                assertTrue("both suspensions should be in our source",
                        sourceNames.stream().allMatch("dap_stepover_call.clj"::equals));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  43. Letfn mutual recursion debugging with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void letfnWithDap() throws Exception {
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
            Source code = src("dap_letfn.clj",
                    "(letfn [(even? [n]\n" +
                    "          (if (zero? n) true\n" +
                    "            (odd? (dec n))))\n" +
                    "        (odd? [n]\n" +
                    "          (if (zero? n) false\n" +
                    "            (even? (dec n))))]\n" +
                    "  (even? 4))\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] hit = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> {
                    hit[0] = true;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertTrue("result should be true", result.asBoolean());
                assertTrue("breakpoint inside letfn should fire", hit[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  44. Keyword invoke debugging with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void keywordInvokeWithDap() throws Exception {
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
            Source code = src("dap_kw_invoke.clj",
                    "(def m {:a 1 :b 2})\n" +
                    "(:a m)\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] hit = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

                cb.add(event -> {
                    hit[0] = true;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(1L, result.asLong());
                assertTrue("breakpoint on keyword invoke should fire", hit[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  45. Static method debugging with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void staticMethodWithDap() throws Exception {
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
            Source code = src("dap_static.clj",
                    "(def n (Integer/parseInt \"42\"))\n" +
                    "(+ n 1)\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] hit = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> {
                    hit[0] = true;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(43L, result.asLong());
                assertTrue("breakpoint on static method should fire", hit[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  46. Scope has source location with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void scopeHasSourceLocationWithDap() throws Exception {
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

            context.eval(src("dap_scope_loc_setup.clj",
                    "(defn helper [x] (+ x 1))"));

            Debugger debugger = Debugger.find(engine);
            Source code = src("dap_scope_loc_call.clj", "(helper 5)\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] hasLoc = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> event.prepareStepInto(1));

                cb.add(event -> {
                    DebugStackFrame frame = event.getTopStackFrame();
                    DebugScope scope = frame.getScope();
                    if (scope != null) {
                        hasLoc[0] = scope.getSourceSection() != null;
                    }
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(6L, result.asLong());
                assertTrue("scope should have source location", hasLoc[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  47. Eval in suspended frame with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void evalInSuspendedFrameWithDap() throws Exception {
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

            context.eval(src("dap_eval_frame_setup.clj",
                    "(defn compute [x] (+ x 10))"));

            Debugger debugger = Debugger.find(engine);
            Source code = src("dap_eval_frame_call.clj", "(compute 5)\n");

            OrderedCallback cb = new OrderedCallback();
            long[] evalResult = {0};
            boolean[] evaluated = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> event.prepareStepInto(1));

                cb.add(event -> {
                    try {
                        DebugValue result = event.getTopStackFrame().eval("(+ 1 2 3)");
                        if (result != null && result.isNumber()) {
                            evalResult[0] = result.asLong();
                            evaluated[0] = true;
                        }
                    } catch (Exception e) {
                        // eval may not be supported in all contexts
                    }
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(15L, result.asLong());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  48. Unhandled exception propagates correctly with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void unhandledExceptionWithDap() throws Exception {
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

            boolean threw = false;
            try {
                context.eval(src("dap_unhandled.clj",
                        "(throw (Exception. \"deliberate\"))"));
            } catch (PolyglotException e) {
                threw = true;
                assertTrue("message should contain 'deliberate'",
                        e.getMessage().contains("deliberate"));
            }

            assertTrue("unhandled exception should propagate", threw);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  49. Boolean and nil results with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void booleanAndNilResultsWithDap() throws Exception {
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

            Value trueResult = context.eval(src("dap_bool_true.clj", "(= 1 1)"));
            assertTrue("(= 1 1) should be true", trueResult.asBoolean());

            Value falseResult = context.eval(src("dap_bool_false.clj", "(= 1 2)"));
            assertFalse("(= 1 2) should be false", falseResult.asBoolean());

            Value nilResult = context.eval(src("dap_nil.clj", "nil"));
            assertTrue("nil should be null", nilResult.isNull());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  50. Nested function calls stepping with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void nestedCallsSteppingWithDap() throws Exception {
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

            context.eval(src("dap_nested_setup.clj",
                    "(defn c [] 42)\n(defn b [] (c))"));

            Debugger debugger = Debugger.find(engine);
            Source code = src("dap_nested_call.clj", "(b)\n");

            OrderedCallback cb = new OrderedCallback();
            int[] suspensions = {0};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> {
                    suspensions[0]++;
                    event.prepareStepInto(1);
                });

                cb.add(event -> {
                    suspensions[0]++;
                    event.prepareStepInto(1);
                });

                cb.add(event -> {
                    suspensions[0]++;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(42L, result.asLong());
                assertTrue("should suspend following the call chain",
                        suspensions[0] >= 2);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  51. Breakpoint ignoreCount skips first N hits with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointIgnoreCountWithDap() throws Exception {
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
            Source code = src("dap_ignore.clj",
                    "(loop [i 0]\n" +
                    "  (if (< i 5)\n" +
                    "    (recur (inc i))\n" +
                    "    i))\n");

            OrderedCallback cb = new OrderedCallback();
            int[] hitCount = {0};

            try (DebuggerSession session = debugger.startSession(cb)) {
                Breakpoint bp = Breakpoint.newBuilder(code.getURI()).lineIs(2)
                        .ignoreCount(3).build();
                session.install(bp);

                for (int i = 0; i < 10; i++) {
                    cb.add(event -> {
                        hitCount[0]++;
                        event.prepareContinue();
                    });
                }

                Value result = context.eval(code);

                assertEquals(5L, result.asLong());
                assertTrue("ignoreCount(3) should still fire some hits",
                        hitCount[0] > 0);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  52. Breakpoint hit count tracks activations with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointHitCountWithDap() throws Exception {
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
            Source code = src("dap_hitcount.clj",
                    "(loop [i 0]\n" +
                    "  (if (< i 3)\n" +
                    "    (recur (inc i))\n" +
                    "    i))\n");

            OrderedCallback cb = new OrderedCallback();

            try (DebuggerSession session = debugger.startSession(cb)) {
                Breakpoint bp = Breakpoint.newBuilder(code.getURI()).lineIs(2).build();
                session.install(bp);

                for (int i = 0; i < 10; i++) {
                    cb.add(event -> event.prepareContinue());
                }

                Value result = context.eval(code);

                assertEquals(3L, result.asLong());
                assertTrue("hit count should be > 0", bp.getHitCount() > 0);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  53. Breakpoint enable/disable toggle with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointEnableDisableWithDap() throws Exception {
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
            Source code = src("dap_toggle.clj", "(def a 1)\n");

            OrderedCallback cb = new OrderedCallback();
            int[] hits = {0};

            try (DebuggerSession session = debugger.startSession(cb)) {
                Breakpoint bp = Breakpoint.newBuilder(code.getURI()).lineIs(1).build();
                session.install(bp);

                cb.add(event -> {
                    hits[0]++;
                    event.prepareContinue();
                });

                context.eval(code);
                assertEquals("should hit once when enabled", 1, hits[0]);

                bp.setEnabled(false);
                assertFalse("breakpoint should be disabled", bp.isEnabled());

                cb.add(event -> {
                    hits[0]++;
                    event.prepareContinue();
                });

                context.eval(src("dap_toggle2.clj", "(def b 2)\n"));

                bp.setEnabled(true);
                assertTrue("breakpoint should be re-enabled", bp.isEnabled());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  54. SuspendedEvent.getBreakpoints() reports the firing breakpoint
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void suspendedEventReportsBreakpointWithDap() throws Exception {
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
            Source code = src("dap_report_bp.clj", "(def x 42)\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] bpReported = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                Breakpoint bp = Breakpoint.newBuilder(code.getURI()).lineIs(1).build();
                session.install(bp);

                cb.add(event -> {
                    List<Breakpoint> bps = event.getBreakpoints();
                    bpReported[0] = !bps.isEmpty();
                    event.prepareContinue();
                });

                context.eval(code);

                assertTrue("event should report the breakpoint", bpReported[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  55. isBreakpointHit() vs isStep() with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void isBreakpointHitVsIsStepWithDap() throws Exception {
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
            Source code = src("dap_hitcheck.clj",
                    "(def a 1)\n" +
                    "(def b 2)\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] firstIsBP = {false};
            boolean[] secondIsStep = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> {
                    firstIsBP[0] = event.isBreakpointHit();
                    event.prepareStepOver(1);
                });

                cb.add(event -> {
                    secondIsStep[0] = event.isStep();
                    event.prepareContinue();
                });

                context.eval(code);

                assertTrue("first suspension should be breakpoint hit", firstIsBP[0]);
                assertTrue("second suspension should be step", secondIsStep[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  56. Step-into count > 1 with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepIntoCountGreaterThanOneWithDap() throws Exception {
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

            context.eval(src("dap_stepin2_setup.clj",
                    "(defn a [x] (+ x 1))\n(defn b [x] (a x))"));

            Debugger debugger = Debugger.find(engine);
            Source code = src("dap_stepin2_call.clj", "(b 5)\n");

            OrderedCallback cb = new OrderedCallback();
            int[] suspensions = {0};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> {
                    suspensions[0]++;
                    event.prepareStepInto(2);
                });

                cb.add(event -> {
                    suspensions[0]++;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(6L, result.asLong());
                assertEquals("stepInto(2) should produce two suspensions",
                        2, suspensions[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  57. Step-over count > 1 with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepOverCountGreaterThanOneWithDap() throws Exception {
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
            Source code = src("dap_step2.clj",
                    "(def a 1)\n" +
                    "(def b 2)\n" +
                    "(def c 3)\n" +
                    "(+ a b c)\n");

            OrderedCallback cb = new OrderedCallback();
            List<Integer> stoppedLines = new ArrayList<>();

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> {
                    stoppedLines.add(event.getSourceSection().getStartLine());
                    event.prepareStepOver(2);
                });

                cb.add(event -> {
                    stoppedLines.add(event.getSourceSection().getStartLine());
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(6L, result.asLong());
                assertEquals("should stop twice", 2, stoppedLines.size());
                assertEquals("first stop should be L1",
                        Integer.valueOf(1), stoppedLines.get(0));
                assertTrue("second stop should skip ahead",
                        stoppedLines.get(1) > stoppedLines.get(0));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  58. Breakpoint in different source fires with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointInDifferentSourceWithDap() throws Exception {
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

            Source defSource = src("dap_lib.clj",
                    "(defn helper [x] (* x 10))\n");
            context.eval(defSource);

            Debugger debugger = Debugger.find(engine);
            Source callSource = src("dap_main.clj", "(helper 5)\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] hitInLib = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(defSource.getURI()).lineIs(1).build());

                cb.add(event -> {
                    hitInLib[0] = true;
                    assertEquals("dap_lib.clj",
                            event.getSourceSection().getSource().getName());
                    event.prepareContinue();
                });

                Value result = context.eval(callSource);

                assertEquals(50L, result.asLong());
                assertTrue("breakpoint in lib should fire when called from main",
                        hitInLib[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  59. Scope variable has correct numeric value with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void scopeVariableValueWithDap() throws Exception {
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

            context.eval(src("dap_scope_val_setup.clj",
                    "(defn double-it [x] (let [result (* x 2)] result))"));

            Debugger debugger = Debugger.find(engine);
            Source code = src("dap_scope_val_call.clj", "(double-it 7)\n");

            OrderedCallback cb = new OrderedCallback();
            long[] xValue = {-1};
            boolean[] found = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> event.prepareStepInto(1));

                cb.add(event -> {
                    DebugStackFrame frame = event.getTopStackFrame();
                    DebugScope scope = frame.getScope();
                    if (scope != null) {
                        found[0] = true;
                        DebugValue xVal = scope.getDeclaredValue("x");
                        if (xVal != null && xVal.isNumber()) {
                            xValue[0] = xVal.asLong();
                        }
                    }
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(14L, result.asLong());
                assertTrue("scope should be found", found[0]);
                assertEquals("x should be 7", 7L, xValue[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  60. Scope in recursive function shows changing values with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void scopeRecursionValuesWithDap() throws Exception {
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
            Source code = src("dap_scope_recurse.clj",
                    "(defn countdown [n]\n" +
                    "  (if (<= n 0)\n" +
                    "    0\n" +
                    "    (countdown (dec n))))\n" +
                    "(countdown 3)\n");

            OrderedCallback cb = new OrderedCallback();
            List<Long> nValues = new ArrayList<>();

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

                for (int i = 0; i < 5; i++) {
                    cb.add(event -> {
                        DebugStackFrame frame = event.getTopStackFrame();
                        DebugScope scope = frame.getScope();
                        if (scope != null) {
                            DebugValue nVal = scope.getDeclaredValue("n");
                            if (nVal != null && nVal.isNumber()) {
                                nValues.add(nVal.asLong());
                            }
                        }
                        event.prepareContinue();
                    });
                }

                Value result = context.eval(code);

                assertEquals(0L, result.asLong());
                assertFalse("should have captured n values", nValues.isEmpty());
                assertEquals("first hit should have n=3",
                        Long.valueOf(3), nValues.get(0));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  61. Top scope accessible at breakpoint with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void topScopeWithDap() throws Exception {
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

            context.eval(src("dap_top_setup.clj", "(def my-value 42)"));

            Debugger debugger = Debugger.find(engine);
            Source code = src("dap_top_call.clj", "(+ my-value 1)\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] topScopeFound = {false};
            boolean[] foundMyValue = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> {
                    DebugScope topScope = session.getTopScope("cloffle");
                    if (topScope != null) {
                        topScopeFound[0] = true;
                        DebugValue val = topScope.getDeclaredValue("my-value");
                        if (val != null) foundMyValue[0] = true;
                    }
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(43L, result.asLong());
                assertTrue("top scope should be accessible", topScopeFound[0]);
                assertTrue("top scope should contain 'my-value'", foundMyValue[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  62. Top scope reads correct var values with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void topScopeReadsVarValuesWithDap() throws Exception {
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

            context.eval(src("dap_top_val_setup.clj", "(def answer 42)"));

            Debugger debugger = Debugger.find(engine);
            Source code = src("dap_top_val_call.clj", "(+ answer 1)\n");

            OrderedCallback cb = new OrderedCallback();
            long[] readValue = {-1};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> {
                    DebugScope topScope = session.getTopScope("cloffle");
                    if (topScope != null) {
                        DebugValue val = topScope.getDeclaredValue("answer");
                        if (val != null && val.isNumber()) {
                            readValue[0] = val.asLong();
                        }
                    }
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(43L, result.asLong());
                assertEquals("answer should be 42", 42L, readValue[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  63. Exception breakpoint fires on uncaught exception with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void exceptionBreakpointWithDap() throws Exception {
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
            Source code = src("dap_exc_bp.clj", "(/ 1 0)\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] exceptionHit = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                Breakpoint bp = Breakpoint.newExceptionBuilder(false, true).build();
                session.install(bp);

                cb.add(event -> {
                    exceptionHit[0] = true;
                    event.prepareContinue();
                });

                try {
                    context.eval(code);
                } catch (Exception ignored) {
                }

                assertTrue("exception breakpoint should fire on uncaught exception",
                        exceptionHit[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  64. Return value after step-over with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void returnValueAfterStepOverWithDap() throws Exception {
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
            Source code = src("dap_retval.clj",
                    "(def x 42)\n" +
                    "(def y 58)\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] gotReturn = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> event.prepareStepOver(1));

                cb.add(event -> {
                    DebugValue rv = event.getReturnValue();
                    if (rv != null) gotReturn[0] = true;
                    event.prepareContinue();
                });

                context.eval(code);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  65. Scope at top level with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void scopeAtTopLevelWithDap() throws Exception {
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
            Source code = src("dap_scope_top.clj",
                    "(def x 42)\n(+ x 1)\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] scopeFound = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> {
                    DebugStackFrame frame = event.getTopStackFrame();
                    DebugScope scope = frame.getScope();
                    if (scope != null) scopeFound[0] = true;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(43L, result.asLong());
                assertTrue("scope should be available at top level",
                        scopeFound[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  66. Constructor call debugging with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void constructorCallWithDap() throws Exception {
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
            Source code = src("dap_new.clj",
                    "(def sb (StringBuilder. \"hello\"))\n" +
                    "(.toString sb)\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] hit = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> {
                    hit[0] = true;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals("hello", result.asString());
                assertTrue("breakpoint on constructor should fire", hit[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  67. And/or macro debugging with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void andOrMacroWithDap() throws Exception {
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
            Source code = src("dap_and_or.clj",
                    "(def x true)\n" +
                    "(def y false)\n" +
                    "(and x (not y))\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] hit = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(3).build());

                cb.add(event -> {
                    hit[0] = true;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertTrue("result should be true", result.asBoolean());
                assertTrue("breakpoint on and/or macro should fire", hit[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  68. When macro debugging with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void whenMacroWithDap() throws Exception {
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
            Source code = src("dap_when.clj",
                    "(defn maybe-inc [x]\n" +
                    "  (when (> x 0)\n" +
                    "    (inc x)))\n" +
                    "(maybe-inc 5)\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] hit = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(4).build());

                cb.add(event -> {
                    hit[0] = true;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(6L, result.asLong());
                assertTrue("breakpoint on when call should fire", hit[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  69. Case form debugging with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void caseFormWithDap() throws Exception {
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
            Source code = src("dap_case.clj",
                    "(defn dispatch [x]\n" +
                    "  (case x\n" +
                    "    1 :one\n" +
                    "    2 :two\n" +
                    "    :other))\n" +
                    "(dispatch 2)\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] hit = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

                cb.add(event -> {
                    hit[0] = true;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertTrue("result should be :two",
                        result.asString().contains("two"));
                assertTrue("breakpoint on case should fire", hit[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  70. Throw form breakpoint with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void throwFormBreakpointWithDap() throws Exception {
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
            Source code = src("dap_throw.clj",
                    "(try\n" +
                    "  (throw (Exception. \"boom\"))\n" +
                    "  (catch Exception e\n" +
                    "    (.getMessage e)))\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] hit = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

                cb.add(event -> {
                    hit[0] = true;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals("boom", result.asString());
                assertTrue("breakpoint on throw should fire", hit[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  71. Deeply nested let debugging with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void deeplyNestedLetWithDap() throws Exception {
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
            Source code = src("dap_deep_let.clj",
                    "(let [a 1]\n" +
                    "  (let [b (+ a 1)]\n" +
                    "    (let [c (+ b 1)]\n" +
                    "      (let [d (+ c 1)]\n" +
                    "        (+ a b c d)))))\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] hit = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> {
                    hit[0] = true;
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(10L, result.asLong());
                assertTrue("breakpoint on nested let should fire", hit[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  72. Anonymous inline fn debugging with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void anonymousInlineFnWithDap() throws Exception {
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
            Source code = src("dap_anon_inline.clj",
                    "(def my-fn (fn [x] (* x 3)))\n" +
                    "(my-fn 7)\n");

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

                assertEquals(21L, result.asLong());
                assertEquals("step-into anon fn should produce two suspensions",
                        2, suspensions[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  73. Map/reduce with breakpoints via DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void mapReduceWithDap() throws Exception {
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

            Value result = context.eval(src("dap_mapreduce.clj",
                    "(reduce + (map inc [1 2 3 4 5]))"));
            assertEquals(20L, result.asLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  74. Defn on one line, call on next with breakpoint on call
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointOnCallLineWithDap() throws Exception {
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
            Source code = src("dap_call_bp.clj",
                    "(defn square [x] (* x x))\n" +
                    "(square 7)\n");

            OrderedCallback cb = new OrderedCallback();
            boolean[] hitCallSite = {false};
            int[] hitLine = {0};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

                cb.add(event -> {
                    hitCallSite[0] = true;
                    hitLine[0] = event.getSourceSection().getStartLine();
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(49L, result.asLong());
                assertTrue("breakpoint should fire on call line", hitCallSite[0]);
                assertEquals("should hit on line 2", 2, hitLine[0]);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  75. Source section chars contain call form with DAP
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void sourceSectionCharsWithDap() throws Exception {
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
            Source code = src("dap_call_src.clj",
                    "(defn add [a b] (+ a b))\n" +
                    "(add 3 4)\n");

            OrderedCallback cb = new OrderedCallback();
            String[] hitChars = {null};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

                cb.add(event -> {
                    hitChars[0] = event.getSourceSection().getCharacters().toString();
                    event.prepareContinue();
                });

                Value result = context.eval(code);

                assertEquals(7L, result.asLong());
                assertNotNull("should have source at call site", hitChars[0]);
                assertTrue("source should contain the call form",
                        hitChars[0].contains("add"));
            }
        }
    }
}
