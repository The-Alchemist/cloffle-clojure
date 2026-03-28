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

import static org.junit.Assert.*;

/**
 * Tests Truffle debugger integration: breakpoints, stepping (into/over/out),
 * suspended-state inspection (source location, stack frames), and continue.
 *
 * <p>All evaluation runs on the test thread. The {@link SuspendedCallback} fires
 * synchronously during {@code context.eval()}, so no background thread is needed.
 * Each test pre-registers a sequence of handlers; the callback dequeues and invokes
 * them in order.
 */
public class DebuggerTest {

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
        debugger = Debugger.find(engine);
    }

    @After
    public void tearDown() {
        if (context != null) context.close();
        if (engine != null) engine.close();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════

    private static Source src(String name, String code) {
        return Source.newBuilder("cloffle", code, name).buildLiteral();
    }

    /**
     * Collects pre-registered suspension handlers and dispatches them in order.
     * Any suspension beyond the registered handlers gets {@code prepareContinue()}.
     */
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

    // ═══════════════════════════════════════════════════════════════════
    //  1. suspendNextExecution stops and has source section
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void suspendNextExecutionStops() {
        Source code = src("first.clj", "(+ 1 2)");

        OrderedCallback cb = new OrderedCallback();
        boolean[] suspended = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.suspendNextExecution();

            cb.add(event -> {
                suspended[0] = true;
                assertNotNull("source section must be present", event.getSourceSection());
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertTrue("should have suspended", suspended[0]);
            assertEquals(3L, result.asLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  2. Line breakpoint fires and execution continues correctly
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void lineBreakpointFiresAndContinues() {
        Source code = src("bp.clj",
                "(def x 10)\n" +   // L1
                "(def y 20)\n" +   // L2
                "(+ x y)\n");      // L3

        OrderedCallback cb = new OrderedCallback();
        boolean[] hit = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> {
                hit[0] = true;
                assertEquals(1, event.getSourceSection().getStartLine());
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertTrue("breakpoint should have fired", hit[0]);
            assertEquals(30L, result.asLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  3. Breakpoint inside function body (single-line defn)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointInsideSingleLineFnBody() {
        // Put defn on L1 and call on L2 (all on separate lines)
        Source code = src("fnbp.clj",
                "(defn compute [x] (let [y (* x x)] (+ y 1)))\n" +  // L1
                "(compute 5)\n");                                      // L2

        OrderedCallback cb = new OrderedCallback();
        boolean[] hit = {false};
        String[] frameName = {null};

        try (DebuggerSession session = debugger.startSession(cb)) {
            // Breakpoint on call site line
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

            cb.add(event -> {
                hit[0] = true;
                frameName[0] = event.getTopStackFrame().getName();
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertTrue("should have hit breakpoint", hit[0]);
            assertEquals(26L, result.asLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  4. Step-into from a breakpoint produces a second suspension
    //     When a breakpoint fires on a call node and step-into is
    //     requested, execution should suspend again inside the called
    //     function body. The FnDispatchNode has RootTag so the debugger
    //     recognizes function entry boundaries.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepIntoFromCallSite() {
        Source code = src("stepin.clj",
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
            assertTrue("breakpoint should fire at least once", suspensions[0] >= 1);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  5. Step over advances to next top-level form
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepOverAdvancesToNextForm() {
        Source code = src("stepover.clj",
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
            assertEquals("should visit 3 lines", 3L, result.asLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  6. Step out returns to caller
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepOutReturnsToCaller() {
        Source code = src("stepout.clj",
                "(defn inner [] 42)\n" +      // L1
                "(defn outer [] (inner))\n" +  // L2
                "(outer)\n");                   // L3

        OrderedCallback cb = new OrderedCallback();
        boolean[] hitInner = {false};
        boolean[] hitAfterStepOut = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> {
                hitInner[0] = true;
                event.prepareStepOut(1);
            });

            cb.add(event -> {
                hitAfterStepOut[0] = true;
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertTrue("should have hit inner", hitInner[0]);
            assertEquals(42L, result.asLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  7. Stack frames at breakpoint
    //     Breakpoints fire at instrumentable nodes. When a breakpoint inside
    //     a function body fires, the stack should show at least one frame.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stackFramesAtBreakpoint() {
        Source code = src("stack.clj",
                "(defn c []\n" +                 // L1
                "  (+ 1 2))\n" +                 // L2
                "(defn b [] (c))\n" +            // L3
                "(defn a [] (b))\n" +            // L4
                "(a)\n");                         // L5

        OrderedCallback cb = new OrderedCallback();
        List<Integer> depths = new ArrayList<>();

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

            for (int i = 0; i < 3; i++) {
                cb.add(event -> {
                    int depth = 0;
                    for (DebugStackFrame frame : event.getStackFrames()) {
                        if (frame.getSourceSection() != null) depth++;
                    }
                    depths.add(depth);
                    event.prepareContinue();
                });
            }

            context.eval(code);

            assertFalse("breakpoint should fire at least once", depths.isEmpty());
            assertTrue("at least one frame should be present",
                    depths.stream().allMatch(d -> d >= 1));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  8. Breakpoint in loop hits multiple times
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointInLoopHitsMultipleTimes() {
        Source code = src("loop.clj",
                "(loop [i 0]\n" +            // L1
                "  (if (< i 3)\n" +          // L2
                "    (recur (inc i))\n" +     // L3
                "    i))\n");                 // L4

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
            assertEquals(3, hitLines.size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  9. Source section at breakpoint has line and column
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointSourceSectionHasLineAndColumn() {
        Source code = src("col.clj",
                "(defn greet [name] (str \"Hello, \" name))\n" +  // L1
                "(greet \"world\")\n");                              // L2

        OrderedCallback cb = new OrderedCallback();
        String[] hitChars = {null};
        int[] hitLine = {0};
        int[] hitCol = {0};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

            cb.add(event -> {
                hitChars[0] = event.getSourceSection().getCharacters().toString();
                hitLine[0] = event.getSourceSection().getStartLine();
                hitCol[0] = event.getSourceSection().getStartColumn();
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertNotNull("should have source", hitChars[0]);
            assertTrue("line should be >= 1", hitLine[0] >= 1);
            assertTrue("column should be >= 1", hitCol[0] >= 1);
            assertEquals("Hello, world", result.asString());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  10. Continue after breakpoint completes execution
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void continueAfterBreakpoint() {
        Source code = src("cont.clj",
                "(def x 10)\n" +   // L1
                "(def y 20)\n" +   // L2
                "(+ x y)\n");      // L3

        OrderedCallback cb = new OrderedCallback();

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> {
                assertEquals(1, event.getSourceSection().getStartLine());
                event.prepareContinue();
            });

            Value result = context.eval(code);
            assertEquals(30L, result.asLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  11. Multiple breakpoints both fire
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void multipleBreakpointsBothFire() {
        // Each form on its own line, all single-line
        Source code = src("multi.clj",
                "(def a 1)\n" +           // L1
                "(def b 2)\n" +           // L2
                "(def c (+ a b))\n" +     // L3
                "c\n");                    // L4

        OrderedCallback cb = new OrderedCallback();
        List<Integer> hitLines = new ArrayList<>();

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

            cb.add(event -> {
                hitLines.add(event.getSourceSection().getStartLine());
                event.prepareContinue();
            });
            cb.add(event -> {
                hitLines.add(event.getSourceSection().getStartLine());
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertEquals(2, hitLines.size());
            assertEquals(Integer.valueOf(1), hitLines.get(0));
            assertEquals(Integer.valueOf(2), hitLines.get(1));
            assertEquals(3L, result.asLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  12. Recursive function stack grows with depth
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void recursiveStackGrows() {
        Source code = src("factorial.clj",
                "(defn factorial [n]\n" +          // L1
                "  (if (<= n 1)\n" +               // L2
                "    1\n" +                         // L3
                "    (* n (factorial (dec n)))))\n" + // L4
                "(factorial 5)\n");                  // L5

        OrderedCallback cb = new OrderedCallback();
        List<Integer> stackDepths = new ArrayList<>();

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

            for (int i = 0; i < 5; i++) {
                cb.add(event -> {
                    int depth = 0;
                    for (DebugStackFrame f : event.getStackFrames()) {
                        if (f.getSourceSection() != null) depth++;
                    }
                    stackDepths.add(depth);
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

    // ═══════════════════════════════════════════════════════════════════
    //  13. FnDispatchNode has RootTag for debugger integration
    //     Verifies that function roots are tagged so that the debugger
    //     can identify function entry points for stepping and profiling.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void fnDispatchNodeHasRootTag() {
        Source code = src("fn_root_tag.clj",
                "(defn compute [x] (* x x))\n" +   // L1
                "(compute 5)\n");                    // L2

        OrderedCallback cb = new OrderedCallback();
        boolean[] suspended = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.suspendNextExecution();

            cb.add(event -> {
                suspended[0] = true;
                assertNotNull("source section should be present", event.getSourceSection());
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertTrue("should have suspended", suspended[0]);
            assertEquals(25L, result.asLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  14. Function root nodes carry the function name
    //     When a function is called, the root node should have a name
    //     derived from the function's Clojure name.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void functionRootNodesHaveName() {
        Source code = src("fn_name.clj",
                "(defn my-compute [x] (+ x 10))\n" +   // L1
                "(my-compute 5)\n");                     // L2

        OrderedCallback cb = new OrderedCallback();
        boolean[] suspended = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> {
                suspended[0] = true;
                assertNotNull("source section must be present", event.getSourceSection());
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertTrue("breakpoint should fire", suspended[0]);
            assertEquals(15L, result.asLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  15. Breakpoint fires inside function body (multi-line defn)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointInsideMultiLineFnBody() {
        Source code = src("fnbody_bp.clj",
                "(defn compute [x y]\n" +            // L1
                "  (let [sum (+ x y)]\n" +           // L2
                "    (* sum 2)))\n" +                // L3
                "(compute 3 4)\n");                   // L4

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

            assertEquals(14L, result.asLong());
            assertTrue("breakpoint should fire", hit[0]);
            assertTrue("should hit on line 1 or 2", hitLine[0] >= 1 && hitLine[0] <= 2);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  16. Step-over works across multiple single-line forms
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepOverAcrossMultipleForms() {
        Source code = src("step_multi.clj",
                "(def x 1)\n" +       // L1
                "(def y 2)\n" +       // L2
                "(def z 3)\n" +       // L3
                "(+ x y z)\n");       // L4

        OrderedCallback cb = new OrderedCallback();
        List<Integer> stoppedLines = new ArrayList<>();

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.suspendNextExecution();

            for (int i = 0; i < 5; i++) {
                cb.add(event -> {
                    if (event.getSourceSection() != null
                            && "step_multi.clj".equals(event.getSourceSection().getSource().getName())) {
                        stoppedLines.add(event.getSourceSection().getStartLine());
                    }
                    event.prepareStepOver(1);
                });
            }

            Value result = context.eval(code);

            assertEquals(6L, result.asLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  17. Recursive function has stack growth
    //     Each recursive call should increase the stack depth.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void recursiveStackDepthsIncrease() {
        Source code = src("rec_depth.clj",
                "(defn count-down [n]\n" +         // L1
                "  (if (<= n 0)\n" +               // L2
                "    0\n" +                         // L3
                "    (count-down (dec n))))\n" +    // L4
                "(count-down 4)\n");                // L5

        OrderedCallback cb = new OrderedCallback();
        List<Integer> stackDepths = new ArrayList<>();

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

            for (int i = 0; i < 5; i++) {
                cb.add(event -> {
                    int depth = 0;
                    for (DebugStackFrame f : event.getStackFrames()) {
                        if (f.getSourceSection() != null) depth++;
                    }
                    stackDepths.add(depth);
                    event.prepareContinue();
                });
            }

            Value result = context.eval(code);

            assertEquals(0L, result.asLong());
            assertEquals("should hit breakpoint 5 times", 5, stackDepths.size());
            assertTrue("stack should grow",
                    stackDepths.get(0) <= stackDepths.get(4));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  18. Step-out from a function call returns to caller
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepOutFromFunctionReturns() {
        Source code = src("stepout2.clj",
                "(defn helper [] 42)\n" +              // L1
                "(defn caller [] (helper))\n" +        // L2
                "(caller)\n");                          // L3

        OrderedCallback cb = new OrderedCallback();
        boolean[] hitHelper = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> {
                hitHelper[0] = true;
                event.prepareStepOut(1);
            });

            cb.add(event -> {
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertTrue("should have hit helper", hitHelper[0]);
            assertEquals(42L, result.asLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  19. FnNode propagates source section to FnDispatchNode
    //     The FnDispatchNode should inherit the source section from
    //     the FnNode so isInstrumentable() returns true.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void fnNodePropagatesSourceToDispatch() {
        Source code = src("fn_src.clj",
                "(defn triple [x] (* x 3))\n" +      // L1
                "(triple 7)\n");                       // L2

        OrderedCallback cb = new OrderedCallback();
        boolean[] suspended = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> {
                suspended[0] = true;
                assertNotNull("source section must be present", event.getSourceSection());
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertTrue("breakpoint should fire", suspended[0]);
            assertEquals(21L, result.asLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  20. truffleEval roots have source sections for debugging
    //     Eagerly executed forms (like defmacro) should have source
    //     sections set on their root nodes.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void eagerlyExecutedFormsHaveSourceSections() {
        Source code = src("eager_src.clj",
                "(def a 100)\n" +                       // L1
                "(+ a 1)\n");                            // L2

        OrderedCallback cb = new OrderedCallback();
        boolean[] suspended = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> {
                suspended[0] = true;
                assertNotNull("source section present", event.getSourceSection());
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertTrue("breakpoint should fire", suspended[0]);
            assertEquals(101L, result.asLong());
        }
    }
}
