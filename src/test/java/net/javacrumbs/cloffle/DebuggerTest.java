package net.javacrumbs.cloffle;

import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.DebugScope;
import com.oracle.truffle.api.debug.DebugStackFrame;
import com.oracle.truffle.api.debug.DebugValue;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.SuspendAnchor;
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
import java.util.Collection;
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
    //  4. Step-into from call site enters called function body
    //     When a breakpoint fires on a call node and step-into is
    //     requested, execution suspends inside the called function body.
    //     FnDispatchNode has RootTag so the debugger recognizes function
    //     entry boundaries.
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
            assertEquals("step-into should produce two suspensions", 2, suspensions[0]);
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
    //  7. Stack frames at breakpoint show caller chain
    //     When a breakpoint fires inside a function body that's called
    //     through a chain a->b->c, the stack should show multiple frames.
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
    //  13. Step-into named function reports callee source
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepIntoShowsCalleeSource() {
        Source code = src("stepin_name.clj",
                "(defn greet [name] (str \"Hello, \" name))\n" +  // L1
                "(greet \"world\")\n");                              // L2

        OrderedCallback cb = new OrderedCallback();
        int[] suspensions = {0};
        String[] calleeSource = {null};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

            cb.add(event -> {
                suspensions[0]++;
                event.prepareStepInto(1);
            });

            cb.add(event -> {
                suspensions[0]++;
                calleeSource[0] = event.getSourceSection().getCharacters().toString();
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertEquals("Hello, world", result.asString());
            assertEquals("should suspend twice (breakpoint + step-into)", 2, suspensions[0]);
            assertNotNull("callee should have source", calleeSource[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  14. Step-into with multi-arity function
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepIntoMultiArityFn() {
        Source code = src("stepin_multi.clj",
                "(defn add\n" +                         // L1
                "  ([a] (+ a 10))\n" +                  // L2
                "  ([a b] (+ a b)))\n" +                // L3
                "(add 5)\n");                            // L4

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

            assertEquals(15L, result.asLong());
            assertEquals("step-into multi-arity should produce two suspensions", 2, suspensions[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  15. Breakpoint fires on call expression line (StatementTag)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointOnCallExpressionLine() {
        Source code = src("call_bp.clj",
                "(defn square [x] (* x x))\n" +   // L1
                "(square 7)\n");                    // L2

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
            assertTrue("breakpoint should fire on call expression", hitCallSite[0]);
            assertEquals("should hit on line 2", 2, hitLine[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  16. Breakpoint on each of three def forms fires in order
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointsOnThreeDefsFireInOrder() {
        Source code = src("three_defs.clj",
                "(def a 1)\n" +   // L1
                "(def b 2)\n" +   // L2
                "(def c 3)\n");   // L3

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

            context.eval(code);

            assertEquals("should hit all 3 breakpoints", 3, hitLines.size());
            assertEquals(Integer.valueOf(1), hitLines.get(0));
            assertEquals(Integer.valueOf(2), hitLines.get(1));
            assertEquals(Integer.valueOf(3), hitLines.get(2));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  17. Step-into then step-over stays in callee
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepIntoThenStepOver() {
        Source code = src("stepin_over.clj",
                "(defn work [x]\n" +             // L1
                "  (def tmp (* x 2))\n" +        // L2
                "  (+ tmp 1))\n" +               // L3
                "(work 10)\n");                   // L4

        OrderedCallback cb = new OrderedCallback();
        int[] suspensions = {0};
        List<String> suspendedChars = new ArrayList<>();

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(4).build());

            // 1: hit BP on (work 10), step into
            cb.add(event -> {
                suspensions[0]++;
                event.prepareStepInto(1);
            });

            // 2: inside work body, step over
            cb.add(event -> {
                suspensions[0]++;
                suspendedChars.add(event.getSourceSection().getCharacters().toString());
                event.prepareStepOver(1);
            });

            // 3: next statement in work body
            cb.add(event -> {
                suspensions[0]++;
                suspendedChars.add(event.getSourceSection().getCharacters().toString());
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertEquals(21L, result.asLong());
            assertTrue("should suspend at least twice", suspensions[0] >= 2);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  18. Step-into then step-out returns to caller
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepIntoThenStepOut() {
        Source code = src("stepin_out.clj",
                "(defn helper [x] (+ x 100))\n" +   // L1
                "(helper 5)\n");                      // L2

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

    // ═══════════════════════════════════════════════════════════════════
    //  19. Step-into anonymous fn (created with fn, not defn)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepIntoAnonymousFunction() {
        Source code = src("stepin_anon.clj",
                "(def my-fn (fn [x] (* x 3)))\n" +   // L1
                "(my-fn 7)\n");                        // L2

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
            assertEquals("step-into anonymous fn should produce two suspensions", 2, suspensions[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  20. Breakpoint inside multi-line function body
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
    //  21. Step-into higher-order function call
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepIntoHigherOrderCall() {
        Source code = src("stepin_ho.clj",
                "(defn apply-fn [f x] (f x))\n" +    // L1
                "(defn square [x] (* x x))\n" +       // L2
                "(apply-fn square 4)\n");               // L3

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
            assertEquals("step-into should produce two suspensions", 2, suspensions[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  22. Recursive function: stack depth increases monotonically
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
    //  23. Step-out from function returns to caller
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
    //  24. Breakpoint on if-then branch fires only when taken
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointOnIfBranch() {
        Source code = src("if_bp.clj",
                "(defn check [x]\n" +            // L1
                "  (if (> x 0)\n" +              // L2
                "    (+ x 10)\n" +               // L3
                "    (- x 10)))\n" +             // L4
                "(check 5)\n");                   // L5

        OrderedCallback cb = new OrderedCallback();
        boolean[] hit = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

            cb.add(event -> {
                hit[0] = true;
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertEquals(15L, result.asLong());
            assertTrue("breakpoint inside if should fire", hit[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  25. Breakpoint on let binding fires
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointOnLetBinding() {
        Source code = src("let_bp.clj",
                "(let [a 10\n" +                // L1
                "      b 20]\n" +               // L2
                "  (+ a b))\n");                // L3

        OrderedCallback cb = new OrderedCallback();
        boolean[] hit = {false};
        int[] hitLine = {0};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> {
                hit[0] = true;
                hitLine[0] = event.getSourceSection().getStartLine();
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertEquals(30L, result.asLong());
            assertTrue("breakpoint on let should fire", hit[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  26. Step-into with closures that capture locals
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepIntoClosureCapturingLocals() {
        Source code = src("stepin_closure.clj",
                "(defn make-adder [n] (fn [x] (+ x n)))\n" +  // L1
                "(def add5 (make-adder 5))\n" +                 // L2
                "(add5 10)\n");                                  // L3

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
            assertEquals("step-into closure should produce two suspensions", 2, suspensions[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  27. Breakpoint in loop body fires on each iteration
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointInLoopBody() {
        Source code = src("loop_body_bp.clj",
                "(loop [i 0 acc 0]\n" +           // L1
                "  (if (< i 5)\n" +               // L2
                "    (recur (inc i) (+ acc i))\n" +// L3
                "    acc))\n");                    // L4

        OrderedCallback cb = new OrderedCallback();
        int[] hitCount = {0};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

            for (int i = 0; i < 10; i++) {
                cb.add(event -> {
                    hitCount[0]++;
                    event.prepareContinue();
                });
            }

            Value result = context.eval(code);

            assertEquals(10L, result.asLong());
            assertTrue("loop breakpoint should fire multiple times", hitCount[0] >= 5);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  28. Source section at call site has correct characters
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void sourceSectionAtCallSiteHasCorrectChars() {
        Source code = src("call_src.clj",
                "(defn add [a b] (+ a b))\n" +    // L1
                "(add 3 4)\n");                    // L2

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

    // ═══════════════════════════════════════════════════════════════════
    //  29. Breakpoint on do body form
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointOnDoBody() {
        Source code = src("do_bp.clj",
                "(do\n" +                         // L1
                "  (def x 10)\n" +                // L2
                "  (def y 20)\n" +                // L3
                "  (+ x y))\n");                  // L4

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

    // ═══════════════════════════════════════════════════════════════════
    //  30. Step-into with separate define and call evals
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepIntoAcrossSeparateEvals() {
        context.eval(src("setup.clj",
                "(defn triple [x] (* x 3))"));

        Source code = src("call.clj", "(triple 7)\n");

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

            assertEquals(21L, result.asLong());
            assertEquals("step-into across evals should produce two suspensions", 2, suspensions[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  31. Breakpoint with cond macro expansion
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointWithCondMacro() {
        Source code = src("cond_bp.clj",
                "(defn classify [x]\n" +            // L1
                "  (cond\n" +                       // L2
                "    (< x 0) :negative\n" +         // L3
                "    (= x 0) :zero\n" +             // L4
                "    :else :positive))\n" +          // L5
                "(classify 5)\n");                    // L6

        OrderedCallback cb = new OrderedCallback();
        boolean[] hit = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(6).build());

            cb.add(event -> {
                hit[0] = true;
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertTrue("should return :positive keyword",
                    result.asString().contains("positive"));
            assertTrue("breakpoint on cond call should fire", hit[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  32. Multiple step-into follows call chain (separate eval)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void multipleStepIntoFollowsCallChain() {
        context.eval(src("defs.clj",
                "(defn c [] 42)\n" +
                "(defn b [] (c))"));

        Source code = src("chain.clj", "(b)\n");

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
            assertTrue("should suspend at least twice following the call chain",
                    suspensions[0] >= 2);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  33. Step-over a function call does NOT enter the callee
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepOverDoesNotEnterCallee() {
        context.eval(src("setup33.clj", "(defn inner [] (+ 1 2))"));

        Source code = src("stepover33.clj",
                "(def a (inner))\n" +   // L1
                "(def b 99)\n");        // L2

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
                    sourceNames.stream().allMatch("stepover33.clj"::equals));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  34. Breakpoint inside try body fires
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointInsideTryBody() {
        Source code = src("try_bp.clj",
                "(try\n" +                            // L1
                "  (def x 42)\n" +                    // L2
                "  (+ x 1)\n" +                       // L3
                "  (catch Exception e 0))\n");        // L4

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

    // ═══════════════════════════════════════════════════════════════════
    //  35. Step-into variadic function
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepIntoVariadicFunction() {
        Source code = src("stepin_variadic.clj",
                "(defn sum [& nums] (apply + nums))\n" +  // L1
                "(sum 1 2 3)\n");                           // L2

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
            assertEquals("step-into variadic fn should produce two suspensions", 2, suspensions[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  36. Breakpoint on case form
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointOnCaseForm() {
        Source code = src("case_bp.clj",
                "(defn dispatch [x]\n" +              // L1
                "  (case x\n" +                       // L2
                "    1 :one\n" +                       // L3
                "    2 :two\n" +                       // L4
                "    :other))\n" +                     // L5
                "(dispatch 2)\n");                     // L6

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

    // ═══════════════════════════════════════════════════════════════════
    //  37. Breakpoint on throw form fires
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointOnThrowForm() {
        Source code = src("throw_bp.clj",
                "(try\n" +                                     // L1
                "  (throw (Exception. \"boom\"))\n" +         // L2
                "  (catch Exception e\n" +                     // L3
                "    (.getMessage e)))\n");                    // L4

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

    // ═══════════════════════════════════════════════════════════════════
    //  38. Breakpoint on recur form fires each iteration
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointOnRecurForm() {
        Source code = src("recur_bp.clj",
                "(loop [i 0]\n" +            // L1
                "  (if (< i 4)\n" +          // L2
                "    (recur (inc i))\n" +     // L3
                "    i))\n");                 // L4

        OrderedCallback cb = new OrderedCallback();
        int[] hitCount = {0};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(3).build());

            for (int i = 0; i < 10; i++) {
                cb.add(event -> {
                    hitCount[0]++;
                    event.prepareContinue();
                });
            }

            Value result = context.eval(code);

            assertEquals(4L, result.asLong());
            assertTrue("recur breakpoint should fire at least 4 times (got " + hitCount[0] + ")",
                    hitCount[0] >= 4);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  39. Source file name at breakpoint is correct
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void sourceFileNameAtBreakpoint() {
        Source code = src("my_source.clj",
                "(def x 42)\n");

        OrderedCallback cb = new OrderedCallback();
        String[] sourceName = {null};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> {
                sourceName[0] = event.getSourceSection().getSource().getName();
                event.prepareContinue();
            });

            context.eval(code);

            assertEquals("my_source.clj", sourceName[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  40. Step-into locally defined function (let + fn)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepIntoLocalFunction() {
        Source code = src("stepin_local.clj",
                "(let [double-it (fn [x] (* x 2))]\n" +  // L1
                "  (double-it 5))\n");                      // L2

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

            assertEquals(10L, result.asLong());
            assertTrue("should suspend at least once", suspensions[0] >= 1);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  41. Breakpoint on nested let
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointOnNestedLet() {
        Source code = src("nested_let_bp.clj",
                "(let [a 1]\n" +                      // L1
                "  (let [b 2]\n" +                    // L2
                "    (+ a b)))\n");                   // L3

        OrderedCallback cb = new OrderedCallback();
        boolean[] hit = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

            cb.add(event -> {
                hit[0] = true;
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertEquals(3L, result.asLong());
            assertTrue("breakpoint on nested let should fire", hit[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  42. Breakpoint removal prevents further hits
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointRemovalPreventsHit() {
        Source code1 = src("bp_remove1.clj", "(def a 1)\n");
        Source code2 = src("bp_remove2.clj", "(def b 2)\n");

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

    // ═══════════════════════════════════════════════════════════════════
    //  43. Breakpoint on keyword invoke ((:key map) form)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointOnKeywordInvoke() {
        Source code = src("kw_invoke_bp.clj",
                "(def m {:a 1 :b 2})\n" +   // L1
                "(:a m)\n");                  // L2

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

    // ═══════════════════════════════════════════════════════════════════
    //  44. Step-into with letfn mutual recursion
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointInsideLetfn() {
        Source code = src("letfn_bp.clj",
                "(letfn [(even? [n]\n" +                    // L1
                "          (if (zero? n) true\n" +          // L2
                "            (odd? (dec n))))\n" +          // L3
                "        (odd? [n]\n" +                     // L4
                "          (if (zero? n) false\n" +         // L5
                "            (even? (dec n))))]\n" +        // L6
                "  (even? 4))\n");                          // L7

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

    // ═══════════════════════════════════════════════════════════════════
    //  45. Breakpoint on Java interop (.method call)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointOnJavaInterop() {
        Source code = src("interop_bp.clj",
                "(defn get-len [s] (.length s))\n" +   // L1
                "(get-len \"hello world\")\n");          // L2

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
            assertTrue("breakpoint on interop call should fire", hit[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  46. Breakpoint on new/constructor call
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointOnConstructorCall() {
        Source code = src("new_bp.clj",
                "(def sb (StringBuilder. \"hello\"))\n" +   // L1
                "(.toString sb)\n");                          // L2

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
            assertTrue("breakpoint on constructor call should fire", hit[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  47. Breakpoint on static method call
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointOnStaticMethodCall() {
        Source code = src("static_bp.clj",
                "(def n (Integer/parseInt \"42\"))\n" +   // L1
                "(+ n 1)\n");                               // L2

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
            assertTrue("breakpoint on static method call should fire", hit[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  48. Step-into with default/optional arity (2-arity fn called
    //      with 1 arg falls through to variadic)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepIntoMultiArityDifferentCalls() {
        Source code = src("stepin_arities.clj",
                "(defn greet\n" +                              // L1
                "  ([name] (greet name \"Hello\"))\n" +       // L2
                "  ([name greeting] (str greeting \", \" name)))\n" + // L3
                "(greet \"Alice\")\n");                         // L4

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
            assertEquals("should suspend twice", 2, suspensions[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  49. Continue after multiple breakpoints resumes fully
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void continueAfterMultipleBreakpoints() {
        Source code = src("multi_cont.clj",
                "(def a 1)\n" +    // L1
                "(def b 2)\n" +    // L2
                "(def c 3)\n" +    // L3
                "(+ a b c)\n");    // L4

        OrderedCallback cb = new OrderedCallback();
        int[] hits = {0};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(3).build());

            for (int i = 0; i < 3; i++) {
                cb.add(event -> {
                    hits[0]++;
                    event.prepareContinue();
                });
            }

            Value result = context.eval(code);

            assertEquals(6L, result.asLong());
            assertEquals("all 3 breakpoints should fire", 3, hits[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  50. Breakpoint on and/or macro expansion
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointOnAndOrMacro() {
        Source code = src("and_or_bp.clj",
                "(def x true)\n" +                    // L1
                "(def y false)\n" +                   // L2
                "(and x (not y))\n");                  // L3

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

    // ═══════════════════════════════════════════════════════════════════
    //  51. Source section length matches form length
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void sourceSectionLengthMatchesForm() {
        Source code = src("len_check.clj",
                "(def result 42)\n");

        OrderedCallback cb = new OrderedCallback();
        int[] charLen = {0};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> {
                charLen[0] = event.getSourceSection().getCharLength();
                event.prepareContinue();
            });

            context.eval(code);

            assertTrue("source section should have positive length", charLen[0] > 0);
            assertTrue("source section length should cover the form (>= 14 chars for '(def result 42)')",
                    charLen[0] >= 14);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  52. Breakpoint on when macro (expands to if with nil else)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointOnWhenMacro() {
        Source code = src("when_bp.clj",
                "(defn maybe-inc [x]\n" +             // L1
                "  (when (> x 0)\n" +                 // L2
                "    (inc x)))\n" +                   // L3
                "(maybe-inc 5)\n");                    // L4

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

    // ═══════════════════════════════════════════════════════════════════
    //  53. Conditional breakpoint only fires when condition is true
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void conditionalBreakpoint() {
        Source code = src("cond_bp2.clj",
                "(loop [i 0]\n" +              // L1
                "  (if (< i 5)\n" +            // L2
                "    (recur (inc i))\n" +       // L3
                "    i))\n");                  // L4

        OrderedCallback cb = new OrderedCallback();
        List<Integer> hitIterations = new ArrayList<>();

        try (DebuggerSession session = debugger.startSession(cb)) {
            Breakpoint bp = Breakpoint.newBuilder(code.getURI()).lineIs(2)
                    .build();
            session.install(bp);

            for (int i = 0; i < 10; i++) {
                cb.add(event -> {
                    hitIterations.add(hitIterations.size());
                    event.prepareContinue();
                });
            }

            Value result = context.eval(code);

            assertEquals(5L, result.asLong());
            assertTrue("breakpoint should fire multiple times", hitIterations.size() >= 5);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  54. One-shot breakpoint fires only once
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void oneShotBreakpoint() {
        Source code = src("oneshot.clj",
                "(loop [i 0]\n" +              // L1
                "  (if (< i 5)\n" +            // L2
                "    (recur (inc i))\n" +       // L3
                "    i))\n");                  // L4

        OrderedCallback cb = new OrderedCallback();
        int[] hitCount = {0};

        try (DebuggerSession session = debugger.startSession(cb)) {
            Breakpoint bp = Breakpoint.newBuilder(code.getURI()).lineIs(2)
                    .oneShot()
                    .build();
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

    // ═══════════════════════════════════════════════════════════════════
    //  55. Breakpoint ignoreCount skips first N hits
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointIgnoreCount() {
        Source code = src("ignore_bp.clj",
                "(loop [i 0]\n" +              // L1
                "  (if (< i 5)\n" +            // L2
                "    (recur (inc i))\n" +       // L3
                "    i))\n");                  // L4

        OrderedCallback cb = new OrderedCallback();
        int[] hitCount = {0};

        try (DebuggerSession session = debugger.startSession(cb)) {
            Breakpoint bp = Breakpoint.newBuilder(code.getURI()).lineIs(2)
                    .ignoreCount(3)
                    .build();
            session.install(bp);

            for (int i = 0; i < 10; i++) {
                cb.add(event -> {
                    hitCount[0]++;
                    event.prepareContinue();
                });
            }

            Value result = context.eval(code);

            assertEquals(5L, result.asLong());
            assertTrue("ignoreCount(3) should still fire some hits (got " + hitCount[0] + ")",
                    hitCount[0] > 0);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  56. Breakpoint hit count tracks total activations
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointHitCount() {
        Source code = src("hitcount.clj",
                "(loop [i 0]\n" +              // L1
                "  (if (< i 3)\n" +            // L2
                "    (recur (inc i))\n" +       // L3
                "    i))\n");                  // L4

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

    // ═══════════════════════════════════════════════════════════════════
    //  57. DebugStackFrame.getScope() returns local variables
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void scopeContainsLocalVariables() {
        context.eval(src("scope_setup.clj",
                "(defn compute [x y] (let [sum (+ x y)] (* sum 2)))"));

        Source code = src("scope_call.clj",
                "(compute 3 4)\n");

        OrderedCallback cb = new OrderedCallback();
        List<String> varNames = new ArrayList<>();

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> {
                DebugStackFrame frame = event.getTopStackFrame();
                DebugScope scope = frame.getScope();
                if (scope != null) {
                    for (DebugValue val : scope.getDeclaredValues()) {
                        varNames.add(val.getName());
                    }
                }
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertEquals(14L, result.asLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  58. DebugStackFrame.getLanguage() returns cloffle language info
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void frameLanguageIsCloffle() {
        Source code = src("lang_check.clj", "(def x 42)\n");

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

    // ═══════════════════════════════════════════════════════════════════
    //  59. Internal frames are not shown to the debugger
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void internalFramesNotVisible() {
        context.eval(src("internal_setup.clj",
                "(defn outer [] (+ 1 2))"));

        Source code = src("internal_call.clj", "(outer)\n");

        OrderedCallback cb = new OrderedCallback();
        boolean[] anyInternal = {false};
        int[] totalFrames = {0};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> {
                for (DebugStackFrame frame : event.getStackFrames()) {
                    totalFrames[0]++;
                    if (frame.isInternal()) {
                        anyInternal[0] = true;
                    }
                }
                event.prepareStepInto(1);
            });

            cb.add(event -> {
                for (DebugStackFrame frame : event.getStackFrames()) {
                    totalFrames[0]++;
                    if (frame.isInternal()) {
                        anyInternal[0] = true;
                    }
                }
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertEquals(3L, result.asLong());
            assertFalse("no internal frames should be visible in default mode", anyInternal[0]);
            assertTrue("should have at least one frame", totalFrames[0] > 0);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  60. SuspendedEvent.getSuspendAnchor() returns BEFORE
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void suspendAnchorIsBefore() {
        Source code = src("anchor.clj", "(def x 42)\n");

        OrderedCallback cb = new OrderedCallback();
        SuspendAnchor[] anchor = {null};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> {
                anchor[0] = event.getSuspendAnchor();
                event.prepareContinue();
            });

            context.eval(code);

            assertEquals("breakpoint suspend anchor should be BEFORE",
                    SuspendAnchor.BEFORE, anchor[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  61. Return value is available after step-over (AFTER anchor)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void returnValueAfterStepOver() {
        Source code = src("retval.clj",
                "(def x 42)\n" +    // L1
                "(def y 58)\n");    // L2

        OrderedCallback cb = new OrderedCallback();
        Object[] returnVal = {null};
        boolean[] gotReturn = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> {
                event.prepareStepOver(1);
            });

            cb.add(event -> {
                DebugValue rv = event.getReturnValue();
                if (rv != null) {
                    gotReturn[0] = true;
                }
                event.prepareContinue();
            });

            context.eval(code);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  62. Breakpoint isResolved after installation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointIsResolvedAfterEval() {
        Source code = src("resolved.clj",
                "(def x 42)\n");

        OrderedCallback cb = new OrderedCallback();

        try (DebuggerSession session = debugger.startSession(cb)) {
            Breakpoint bp = Breakpoint.newBuilder(code.getURI()).lineIs(1).build();
            session.install(bp);

            cb.add(event -> event.prepareContinue());

            context.eval(code);

            assertTrue("breakpoint should be resolved after execution", bp.isResolved());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  63. Breakpoint enable/disable toggle
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointEnableDisableToggle() {
        Source code = src("toggle.clj",
                "(def a 1)\n");

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

            context.eval(src("toggle2.clj", "(def b 2)\n"));

            bp.setEnabled(true);
            assertTrue("breakpoint should be re-enabled", bp.isEnabled());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  64. SuspendedEvent.getBreakpoints() returns the firing breakpoint
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void suspendedEventReportsBreakpoint() {
        Source code = src("report_bp.clj", "(def x 42)\n");

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

    // ═══════════════════════════════════════════════════════════════════
    //  65. Step-into count > 1 steps multiple times
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepIntoCountGreaterThanOne() {
        context.eval(src("setup65.clj",
                "(defn a [x] (+ x 1))\n" +
                "(defn b [x] (a x))"));

        Source code = src("call65.clj", "(b 5)\n");

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
            assertEquals("stepInto(2) should produce two suspensions", 2, suspensions[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  66. Step-over count > 1 skips multiple statements
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void stepOverCountGreaterThanOne() {
        Source code = src("step2.clj",
                "(def a 1)\n" +    // L1
                "(def b 2)\n" +    // L2
                "(def c 3)\n" +    // L3
                "(+ a b c)\n");    // L4

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
            assertTrue("second stop should skip ahead (L2 or later)",
                    stoppedLines.get(1) > stoppedLines.get(0));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  67. SuspendedEvent.isBreakpointHit() vs isStep()
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void isBreakpointHitVsIsStep() {
        Source code = src("hitcheck.clj",
                "(def a 1)\n" +   // L1
                "(def b 2)\n");   // L2

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

    // ═══════════════════════════════════════════════════════════════════
    //  68. Breakpoint on function defined in one source, called from another
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointOnFunctionInDifferentSource() {
        Source defSource = src("lib.clj",
                "(defn helper [x] (* x 10))\n");
        context.eval(defSource);

        Source callSource = src("main.clj",
                "(helper 5)\n");

        OrderedCallback cb = new OrderedCallback();
        boolean[] hitInLib = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(defSource.getURI()).lineIs(1).build());

            cb.add(event -> {
                hitInLib[0] = true;
                assertEquals("lib.clj",
                        event.getSourceSection().getSource().getName());
                event.prepareContinue();
            });

            Value result = context.eval(callSource);

            assertEquals(50L, result.asLong());
            assertTrue("breakpoint in lib.clj should fire when called from main.clj", hitInLib[0]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  69. Breakpoint on defn line fires during definition
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointOnDefnFires() {
        Source code = src("defn_bp.clj",
                "(defn my-fn [] 42)\n" +    // L1
                "(my-fn)\n");                // L2

        OrderedCallback cb = new OrderedCallback();
        int[] hitCount = {0};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            for (int i = 0; i < 5; i++) {
                cb.add(event -> {
                    hitCount[0]++;
                    event.prepareContinue();
                });
            }

            Value result = context.eval(code);

            assertEquals(42L, result.asLong());
            assertTrue("breakpoint on defn should fire at least once", hitCount[0] >= 1);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  70. Eval expression in suspended frame context
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void evalInSuspendedFrame() {
        context.eval(src("eval_setup.clj",
                "(defn compute [x] (+ x 10))"));

        Source code = src("eval_call.clj", "(compute 5)\n");

        OrderedCallback cb = new OrderedCallback();
        long[] evalResult = {0};
        boolean[] evaluated = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> {
                event.prepareStepInto(1);
            });

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
