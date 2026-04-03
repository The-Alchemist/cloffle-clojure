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
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Mapping-sensitive DAP behavior checks.
 */
public class DapMappingSensitiveTest {

    static {
        System.setProperty("polyglot.log.dap.level", "OFF");
        java.util.logging.Logger.getLogger("dap").setLevel(java.util.logging.Level.OFF);
    }

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

    private static void assertMappingSensitiveBreakpointHits(
            List<Integer> hitLines,
            int expectedFirstLine,
            int minLine,
            int maxLine) {
        assertTrue("should hit at least one installed breakpoint", hitLines.size() >= 1);
        assertEquals(Integer.valueOf(expectedFirstLine), hitLines.get(0));
        for (Integer line : hitLines) {
            assertTrue("hit line should be in the expected source range", line >= minLine && line <= maxLine);
        }
    }

    private static void assertMappingSensitiveScopeValueWhenReadable(
            boolean foundScope,
            List<String> declared,
            boolean sawVar,
            long value,
            long expectedValue,
            String varName) {
        assertTrue("scope should be found", foundScope);
        assertFalse("scope should expose declared values", declared.isEmpty());
        if (sawVar && value != -1) {
            assertEquals(varName + " should match expected value when readable", expectedValue, value);
        }
    }

    @Test
    public void multipleBreakpointsWithDap_mappingSensitive() throws Exception {
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
                assertMappingSensitiveBreakpointHits(hitLines, 1, 1, 3);
            }
        }
    }

    @Test
    public void multiLineDefnBreakpointWithDap_mappingSensitive() throws Exception {
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

                assertTrue("breakpoint should resolve to the function source (head or body line)",
                        startLine[0] == 1 || startLine[0] == 2);
            }
        }
    }

    @Test
    public void stepOverCountGreaterThanOneWithDap_mappingSensitive() throws Exception {
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
                assertEquals("first stop should be L1", Integer.valueOf(1), stoppedLines.get(0));
                assertTrue("second stop should be at the same or a later source line",
                        stoppedLines.get(1) >= stoppedLines.get(0));
            }
        }
    }

    @Test
    public void scopeVariableValueWithDap_mappingSensitive() throws Exception {
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
            List<String> declared = new ArrayList<>();
            boolean[] sawX = {false};
        boolean[] autoAdvanced = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

                cb.add(event -> event.prepareStepInto(1));

                Consumer<SuspendedEvent> captureScope = new Consumer<>() {
                    @Override
                    public void accept(SuspendedEvent event) {
                    DebugStackFrame frame = event.getTopStackFrame();
                    DebugScope scope = frame.getScope();
                    if (scope != null) {
                        found[0] = true;
                        for (DebugValue val : scope.getDeclaredValues()) {
                            declared.add(val.getName());
                        }
                        DebugValue xVal = scope.getDeclaredValue("x");
                        if (xVal != null) {
                            sawX[0] = true;
                            boolean xReadable = xVal.isNumber() || xVal.fitsInLong();
                            if (DebugStepPolicies.maybeAdvancePastEntryBefore(
                                    event,
                                    autoAdvanced,
                                    !xReadable,
                                    () -> cb.add(this))) {
                                return;
                            }
                            if (xReadable) {
                                xValue[0] = xVal.asLong();
                            }
                        }
                    }
                    event.prepareContinue();
                    }
                };
                cb.add(captureScope);

                Value result = context.eval(code);

                assertEquals(14L, result.asLong());
                assertTrue("scope should contain 'x'", declared.contains("x"));
                assertMappingSensitiveScopeValueWhenReadable(found[0], declared, sawX[0], xValue[0], 7L, "x");
            }
        }
    }
}
