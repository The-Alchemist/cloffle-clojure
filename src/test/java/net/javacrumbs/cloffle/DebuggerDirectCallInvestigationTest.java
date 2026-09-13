package net.javacrumbs.cloffle;

import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.DebugStackFrame;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.SuspendedCallback;
import com.oracle.truffle.api.debug.SuspendedEvent;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Edge cases for top-level {@code DirectCallNode} / {@code PolyglotNilSafeRootNode} /
 * bytecode tag policy (investigation harness — not part of the numbered DebuggerTest suite).
 */
public class DebuggerDirectCallInvestigationTest {

    private Engine engine;
    private Context context;
    private Debugger debugger;

    @Before
    public void setUp() {
        engine = Engine.create();
        context = Context.newBuilder("cloffle")
                .engine(engine)
                .allowAllAccess(true)
                .build();
        CloffleEvalTestSupport.bindFreshNamespace(context, "debugger-dc-inv");
        debugger = Debugger.find(engine);
    }

    @After
    public void tearDown() {
        if (context != null) {
            context.close();
        }
        if (engine != null) {
            engine.close();
        }
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

    private static int guestFrameCount(SuspendedEvent event) {
        int depth = 0;
        for (DebugStackFrame frame : event.getStackFrames()) {
            if (!frame.isHost() && !frame.isInternal()) {
                depth++;
            }
        }
        return depth;
    }

    /** Top-level IIFE must carry {@code CallTag} and not duplicate line breakpoints with {@code TopLevelEvalNode}. */
    @Test
    public void topLevelIifeLineBreakpointStopsOnce() {
        Source code = src("iife_bp.clj", "((fn* [] 42))\n");

        OrderedCallback cb = new OrderedCallback();
        int[] hits = {0};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());
            for (int i = 0; i < 5; i++) {
                cb.add(event -> {
                    hits[0]++;
                    event.prepareContinue();
                });
            }

            Value result = context.eval(code);
            assertEquals(42L, result.asLong());
            assertEquals("line breakpoint on top-level IIFE should halt once", 1, hits[0]);
        }
    }

    @Test
    public void topLevelIifeStepIntoEntersBody() {
        Source code = src("iife_step.clj", "((fn* [] (+ 1 2)))\n");

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
                event.prepareContinue();
            });

            Value result = context.eval(code);
            assertEquals(3L, result.asLong());
            assertEquals("step-into should suspend inside IIFE body", 2, suspensions[0]);
        }
    }

    /** {@link net.javacrumbs.cloffle.bytecode.BytecodeTagPolicy} treats literals as non-runtime at top level. */
    @Test
    public void topLevelLiteralLineBreakpointDoesNotFire() {
        Source code = src("literal_bp.clj", "42\n(+ 1 2)\n");

        OrderedCallback cb = new OrderedCallback();
        int[] line1Hits = {0};
        int[] line2Hits = {0};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());
            for (int i = 0; i < 5; i++) {
                cb.add(event -> {
                    int line = event.getSourceSection().getStartLine();
                    if (line == 1) {
                        line1Hits[0]++;
                    } else if (line == 2) {
                        line2Hits[0]++;
                    }
                    event.prepareContinue();
                });
            }

            Value result = context.eval(code);
            assertEquals(3L, result.asLong());
            assertEquals("literal top-level form should not be a statement stop", 0, line1Hits[0]);
            assertTrue("call on line 2 should still break", line2Hits[0] >= 1);
        }
    }

    /** {@code (def x (fn* [] 1))} classifies as FN_DEFINITION like {@code defn}. */
    @Test
    public void defWithFnLiteralDefinitionLineDoesNotBreak() {
        Source code = src("def_fn_lit.clj",
                "(def x (fn* [] 1))\n" +
                "(x)\n");

        OrderedCallback cb = new OrderedCallback();
        int[] defLineHits = {0};
        int[] callLineHits = {0};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());
            for (int i = 0; i < 5; i++) {
                cb.add(event -> {
                    int line = event.getSourceSection().getStartLine();
                    if (line == 1) {
                        defLineHits[0]++;
                    } else if (line == 2) {
                        callLineHits[0]++;
                    }
                    event.prepareContinue();
                });
            }

            Value result = context.eval(code);
            assertEquals(1L, result.asLong());
            assertEquals("def+fn* install line should not halt", 0, defLineHits[0]);
            assertTrue("call line should halt", callLineHits[0] >= 1);
        }
    }

    /** Nested guest calls through parse wrapper + bytecode {@code DirectCallNode} should show depth ≥ 3. */
    @Test
    public void stackDepthAtLeafIncludesCallerChainThroughParsePath() {
        Source code = src("parse_stack.clj",
                "(defn leaf [] (+ 1 2))\n" +
                "(defn mid [] (+ 0 (leaf)))\n" +
                "(mid)\n");

        OrderedCallback cb = new OrderedCallback();
        List<Integer> depths = new ArrayList<>();

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());
            cb.add(event -> {
                depths.add(guestFrameCount(event));
                event.prepareContinue();
            });

            context.eval(code);
            assertFalse("breakpoint in leaf body should fire", depths.isEmpty());
            assertTrue("caller chain should surface multiple guest frames (leaf + mid + top-level)",
                    depths.stream().anyMatch(d -> d >= 3));
        }
    }
}
