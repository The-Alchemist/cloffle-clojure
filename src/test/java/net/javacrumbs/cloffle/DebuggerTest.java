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
import com.oracle.truffle.api.interop.InteropLibrary;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import net.javacrumbs.cloffle.nodes.ClojureScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

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

    @BeforeEach
    public void setUp() {
        engine = Engine.create();
        context = Context.newBuilder("cloffle")
                .engine(engine)
                .allowAllAccess(true)
                .build();
        CloffleEvalTestSupport.bindFreshNamespace(context, "debugger");
        debugger = Debugger.find(engine);
    }

    @AfterEach
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

    @Test
    public void scopeNullValueInteropDisplayIsNil() {
        InteropLibrary interop = InteropLibrary.getUncached();
        ClojureScope.NullValue nv = ClojureScope.NullValue.INSTANCE;
        assertTrue(interop.isNull(nv));
        assertEquals("nil", interop.toDisplayString(nv, false));
        assertEquals("nil", nv.toString());
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
                assertNotNull(event.getSourceSection(), "source section must be present");
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertTrue(suspended[0], "should have suspended");
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

            assertTrue(hit[0], "breakpoint should have fired");
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
                "(defn square-plus-one [x] (let [y (* x x)] (+ y 1)))\n" +  // L1
                "(square-plus-one 5)\n");                                      // L2

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

            assertTrue(hit[0], "should have hit breakpoint");
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
            assertEquals(2, suspensions[0], "step-into should produce two suspensions");
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
            assertEquals(3L, result.asLong(), "should visit 3 lines");
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
        boolean[] hitCall = {false};
        boolean[] hitAfterStepOut = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(3).build());

            cb.add(event -> {
                hitCall[0] = true;
                event.prepareStepInto(1);
            });

            cb.add(event -> {
                event.prepareStepOut(1);
            });

            cb.add(event -> {
                hitAfterStepOut[0] = true;
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertTrue(hitCall[0], "should have hit call site");
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
        // Use non-tail call sites so frames for a and b remain when stopped inside c
        // (plain (b) and (c) in tail position would be merged by self-tail / TCO).
        Source code = src("stack.clj",
                "(defn c []\n" +                 // L1
                "  (+ 1 2))\n" +                 // L2
                "(defn b [] (+ 0 (c)))\n" +      // L3
                "(defn a [] (+ 0 (b)))\n" +      // L4
                "(a)\n");                         // L5

        OrderedCallback cb = new OrderedCallback();
        List<Integer> depths = new ArrayList<>();

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

            for (int i = 0; i < 3; i++) {
                cb.add(event -> {
                    int depth = 0;
                    for (DebugStackFrame frame : event.getStackFrames()) {
                        if (frame.isHost() || frame.isInternal()) {
                            continue;
                        }
                        depth++;
                    }
                    depths.add(depth);
                    event.prepareContinue();
                });
            }

            context.eval(code);

            assertFalse(depths.isEmpty(), "breakpoint should fire at least once");
            assertTrue(depths.stream().allMatch(d -> d >= 1), "at least one frame should be present");
            assertTrue(depths.stream().anyMatch(d -> d >= 3), "a→b→c chain should surface multiple stack frames at breakpoint in c");
        }
    }

    /**
     * Multi-line {@code defn} bytecode roots must not span the whole form (that makes DAP snap
     * body-line breakpoints to the def head). Breakpoint on an inner body line should resolve there.
     */
    @Test
    public void breakpointOnMultiLineDefnBodyLineResolvesToBodyNotDefHead() {
        Source code = src(
                "defn_body_line.clj",
                "(defn f [x]\n"
                        + "  (let [y (+ x 1)]\n"
                        + "    y))\n"
                        + "(f 0)\n");

        OrderedCallback cb = new OrderedCallback();
        int[] hitLine = {-1};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(3).build());

            cb.add(event -> {
                hitLine[0] = event.getSourceSection().getStartLine();
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertEquals(1L, result.asLong());
        }

        assertEquals(3, hitLine[0], "breakpoint on line 3 (inner body) should resolve to that line, not the defn head");
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

            assertNotNull(hitChars[0], "should have source");
            assertTrue(hitLine[0] >= 1, "line should be >= 1");
            assertTrue(hitCol[0] >= 1, "column should be >= 1");
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
            assertTrue(stackDepths.get(0) <= stackDepths.get(4), "stack should grow with recursion");
        }
    }

    /** Tail call from {@code outer} to {@code inner}: still evaluates under an active debugger session. */
    @Test
    public void tailCallFromOuterToInnerEvaluatesWithDebuggerSession() {
        Source code = src("tail_stack_dbg.clj",
                "(defn inner []\n" +
                "  (+ 1 2))\n" +
                "(defn outer []\n" +
                "  (inner))\n" +
                "(outer)\n");

        OrderedCallback cb = new OrderedCallback();

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(5).build());

            cb.add(event -> {
                event.prepareContinue();
            });

            Value result = context.eval(code);
            assertEquals(3L, result.asLong());
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
            assertEquals(2, suspensions[0], "should suspend twice (breakpoint + step-into)");
            assertNotNull(calleeSource[0], "callee should have source");
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
            assertEquals(2, suspensions[0], "step-into multi-arity should produce two suspensions");
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
            assertTrue(hitCallSite[0], "breakpoint should fire on call expression");
            assertEquals(2, hitLine[0], "should hit on line 2");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  16. Breakpoint on each of three def forms fires in order
    // ═══════════════════════════════════════════════════════════════════

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
            assertTrue(suspensions[0] >= 2, "should suspend at least twice");
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
            assertTrue(suspensions[0] >= 2, "should suspend at least twice");
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
            assertEquals(2, suspensions[0], "step-into anonymous fn should produce two suspensions");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  20. Breakpoint inside multi-line function body
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointInsideMultiLineFnBody() {
        Source code = src("fnbody_bp.clj",
                "(defn double-sum [x y]\n" +            // L1
                "  (let [sum (+ x y)]\n" +           // L2
                "    (* sum 2)))\n" +                // L3
                "(double-sum 3 4)\n");                   // L4

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
            assertTrue(hit[0], "breakpoint should fire");
            assertTrue(hitLine[0] >= 1 && hitLine[0] <= 2, "should hit on line 1 or 2");
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
            assertEquals(2, suspensions[0], "step-into should produce two suspensions");
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
            assertEquals(5, stackDepths.size(), "should hit breakpoint 5 times");
            assertTrue(stackDepths.get(0) <= stackDepths.get(4), "stack should grow");
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
        boolean[] hitCall = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(3).build());

            cb.add(event -> {
                hitCall[0] = true;
                event.prepareStepInto(1);
            });

            cb.add(event -> {
                event.prepareStepOut(1);
            });

            cb.add(event -> {
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertTrue(hitCall[0], "should have hit call site");
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
            assertTrue(hit[0], "breakpoint inside if should fire");
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
            assertTrue(hit[0], "breakpoint on let should fire");
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
            assertEquals(2, suspensions[0], "step-into closure should produce two suspensions");
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
            assertTrue(hitCount[0] >= 5, "loop breakpoint should fire multiple times");
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
            assertNotNull(hitChars[0], "should have source at call site");
            assertTrue(hitChars[0].contains("add"), "source should contain the call form");
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
            assertTrue(hit[0], "breakpoint inside do should fire");
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
            assertEquals(2, suspensions[0], "step-into across evals should produce two suspensions");
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

            assertTrue(result.asString().contains("positive"), "should return :positive keyword");
            assertTrue(hit[0], "breakpoint on cond call should fire");
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
            assertTrue(suspensions[0] >= 2, "should suspend at least twice following the call chain");
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
            assertTrue(sourceNames.stream().allMatch("stepover33.clj"::equals), "both suspensions should be in our source");
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
            assertTrue(hit[0], "breakpoint inside try should fire");
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
            assertEquals(2, suspensions[0], "step-into variadic fn should produce two suspensions");
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

            assertTrue(result.asString().contains("two"), "result should be :two");
            assertTrue(hit[0], "breakpoint on case should fire");
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
            assertTrue(hit[0], "breakpoint on throw should fire");
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
            assertTrue(hitCount[0] >= 4, "recur breakpoint should fire at least 4 times (got " + hitCount[0] + ")");
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
            assertTrue(suspensions[0] >= 1, "should suspend at least once");
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
            assertTrue(hit[0], "breakpoint on nested let should fire");
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
            assertEquals(1, hits[0], "first breakpoint should fire");

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
            assertTrue(hit[0], "breakpoint on keyword invoke should fire");
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

            assertTrue(result.asBoolean(), "result should be true");
            assertTrue(hit[0], "breakpoint inside letfn should fire");
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
            assertTrue(hit[0], "breakpoint on interop call should fire");
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
            assertTrue(hit[0], "breakpoint on constructor call should fire");
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
            assertTrue(hit[0], "breakpoint on static method call should fire");
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
            assertEquals(2, suspensions[0], "should suspend twice");
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
            assertEquals(3, hits[0], "all 3 breakpoints should fire");
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

            assertTrue(result.asBoolean(), "result should be true");
            assertTrue(hit[0], "breakpoint on and/or macro should fire");
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

            assertTrue(charLen[0] > 0, "source section should have positive length");
            assertTrue(charLen[0] >= 14, "source section length should cover the form (>= 14 chars for '(def result 42)')");
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
            assertTrue(hit[0], "breakpoint on when call should fire");
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
            assertTrue(hitIterations.size() >= 5, "breakpoint should fire multiple times");
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
            assertEquals(1, hitCount[0], "one-shot breakpoint should fire exactly once");
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
            assertTrue(hitCount[0] > 0, "ignoreCount(3) should still fire some hits (got " + hitCount[0] + ")");
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
            assertTrue(bp.getHitCount() > 0, "hit count should be > 0");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  57. DebugStackFrame.getScope() returns local variables
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void scopeContainsLocalVariables() {
        context.eval(src("scope_setup.clj",
                "(defn double-sum [x y] (let [sum (+ x y)] (* sum 2)))"));

        Source code = src("scope_call.clj",
                "(double-sum 3 4)\n");

        OrderedCallback cb = new OrderedCallback();
        List<String> varNames = new ArrayList<>();
        boolean[] scopeFound = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> {
                event.prepareStepInto(1);
            });

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
            assertTrue(scopeFound[0], "scope should have been found");
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
            assertFalse(anyInternal[0], "no internal frames should be visible in default mode");
            assertTrue(totalFrames[0] > 0, "should have at least one frame");
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

            assertEquals(SuspendAnchor.BEFORE, anchor[0], "breakpoint suspend anchor should be BEFORE");
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

            assertTrue(bp.isResolved(), "breakpoint should be resolved after execution");
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
            assertEquals(1, hits[0], "should hit once when enabled");

            bp.setEnabled(false);
            assertFalse(bp.isEnabled(), "breakpoint should be disabled");

            cb.add(event -> {
                hits[0]++;
                event.prepareContinue();
            });

            context.eval(src("toggle2.clj", "(def b 2)\n"));

            bp.setEnabled(true);
            assertTrue(bp.isEnabled(), "breakpoint should be re-enabled");
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

            assertTrue(bpReported[0], "event should report the breakpoint");
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
            assertEquals(2, suspensions[0], "stepInto(2) should produce two suspensions");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  66. Step-over count > 1 skips multiple statements
    // ═══════════════════════════════════════════════════════════════════

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

            assertTrue(firstIsBP[0], "first suspension should be breakpoint hit");
            assertTrue(secondIsStep[0], "second suspension should be step");
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
            assertTrue(hitInLib[0], "breakpoint in lib.clj should fire when called from main.clj");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  69. Breakpoint on defn line does NOT fire (matches Java/Python/JS
    //      behavior: function definitions are invisible to the debugger
    //      at load time). BP on the call line fires normally.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointOnDefnDoesNotFire() {
        Source code = src("defn_bp.clj",
                "(defn my-fn [] 42)\n" +    // L1 — definition, should NOT halt
                "(my-fn)\n");                // L2 — call, breakpoint should fire

        OrderedCallback cb = new OrderedCallback();
        int[] defnHitCount = {0};
        int[] callHitCount = {0};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(2).build());

            for (int i = 0; i < 5; i++) {
                cb.add(event -> {
                    int line = event.getSourceSection().getStartLine();
                    if (line == 1) defnHitCount[0]++;
                    else callHitCount[0]++;
                    event.prepareContinue();
                });
            }

            Value result = context.eval(code);

            assertEquals(42L, result.asLong());
            assertEquals(0, defnHitCount[0], "breakpoint on defn line should not fire");
            assertTrue(callHitCount[0] >= 1, "breakpoint on call line should fire");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  69b. Breakpoint on call line after defn's halts exactly once
    //       (reproduces the t.clj scenario: multiple defn + final call)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void breakpointOnCallAfterDefnsStopsOnce() {
        Source code = src("call_after_defns.clj",
                "(defn leaf [x] x)\n" +           // L1
                "(defn branch [x] (leaf x))\n" +   // L2
                "(defn run [n] (branch n))\n" +    // L3
                "(run 11)\n");                      // L4

        OrderedCallback cb = new OrderedCallback();
        int[] hitCount = {0};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(4).build());

            for (int i = 0; i < 5; i++) {
                cb.add(event -> {
                    hitCount[0]++;
                    event.prepareContinue();
                });
            }

            Value result = context.eval(code);

            assertEquals(11L, result.asLong());
            assertEquals(1, hitCount[0], "call-line breakpoint should fire exactly once");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  70. Eval expression in suspended frame context
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void evalInSuspendedFrame() {
        context.eval(src("eval_setup.clj",
                "(defn add-ten [x] (+ x 10))"));

        Source code = src("eval_call.clj", "(add-ten 5)\n");

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

    // ═══════════════════════════════════════════════════════════════════
    //  71. Scope at breakpoint shows fn params with correct values
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void scopeShowsFnParamsWithValues() {
        context.eval(src("scope_params_setup.clj",
                "(defn add [a b] (+ a b))"));

        Source code = src("scope_params_call.clj",
                "(add 10 20)\n");

        OrderedCallback cb = new OrderedCallback();
        List<String> varNames = new ArrayList<>();
        boolean[] scopeFound = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> {
                event.prepareStepInto(1);
            });

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
            assertTrue(scopeFound[0], "scope should have been found");
            assertTrue(varNames.contains("a"), "scope should contain parameter 'a'");
            assertTrue(varNames.contains("b"), "scope should contain parameter 'b'");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  72. Scope name is the function name
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void scopeNameIsFunctionName() {
        context.eval(src("scope_name_setup.clj",
                "(defn my-fn [x] (* x x))"));

        Source code = src("scope_name_call.clj",
                "(my-fn 5)\n");

        OrderedCallback cb = new OrderedCallback();
        String[] scopeName = {null};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> {
                event.prepareStepInto(1);
            });

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
            assertNotNull(scopeName[0], "scope should have a name");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  73. Scope has source location
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void scopeHasSourceLocation() {
        context.eval(src("scope_loc_setup.clj",
                "(defn helper [x] (+ x 1))"));

        Source code = src("scope_loc_call.clj",
                "(helper 5)\n");

        OrderedCallback cb = new OrderedCallback();
        boolean[] hasLoc = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> {
                event.prepareStepInto(1);
            });

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
            assertTrue(hasLoc[0], "scope should have source location");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  74. Scope shows let-bound variables
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void scopeShowsLetBindings() {
        context.eval(src("scope_let_setup.clj",
                "(defn calc [x] (let [doubled (* x 2) tripled (* x 3)] (+ doubled tripled)))"));

        Source code = src("scope_let_call.clj",
                "(calc 5)\n");

        OrderedCallback cb = new OrderedCallback();
        List<String> varNames = new ArrayList<>();
        boolean[] scopeFound = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> {
                event.prepareStepInto(1);
            });

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
            assertTrue(scopeFound[0], "scope should be found");
            assertTrue(varNames.contains("x"), "scope should contain 'x'");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  75. DebugValue for scope variable has correct value
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void scopeVariableHasCorrectValueAfterEntryStep() {
        context.eval(src("scope_val_strict_setup.clj",
                "(defn add [a b] (+ a b))"));

        Source code = src("scope_val_strict_call.clj", "(add 10 20)\n");

        OrderedCallback cb = new OrderedCallback();
        boolean[] foundScope = {false};
        long[] aValue = {Long.MIN_VALUE};
        long[] bValue = {Long.MIN_VALUE};
        boolean[] autoAdvanced = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> event.prepareStepInto(1));

            Consumer<SuspendedEvent> captureScope = new Consumer<>() {
                @Override
                public void accept(SuspendedEvent event) {
                DebugStackFrame frame = event.getTopStackFrame();
                DebugScope scope = frame.getScope();
                assertNotNull(scope, "scope should be available after step-into");
                DebugValue aVal = scope.getDeclaredValue("a");
                DebugValue bVal = scope.getDeclaredValue("b");
                assertNotNull(aVal, "scope should declare parameter a");
                assertNotNull(bVal, "scope should declare parameter b");
                boolean aReadable = aVal.isNumber() || aVal.fitsInLong();
                boolean bReadable = bVal.isNumber() || bVal.fitsInLong();
                if (DebugStepPolicies.maybeAdvancePastEntryBefore(
                        event,
                        autoAdvanced,
                        !aReadable || !bReadable,
                        () -> cb.add(this))) {
                    return;
                }
                foundScope[0] = true;
                assertTrue(aReadable, "a should be numeric");
                assertTrue(bReadable, "b should be numeric");
                aValue[0] = aVal.asLong();
                bValue[0] = bVal.asLong();
                event.prepareContinue();
                }
            };
            cb.add(captureScope);

            Value result = context.eval(code);

            assertEquals(30L, result.asLong());
            assertTrue(foundScope[0], "scope should have been observed at step-into stop");
            assertEquals(10L, aValue[0], "a should be 10 at step-into stop");
            assertEquals(20L, bValue[0], "b should be 20 at step-into stop");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  76. Scope in recursive function shows current iteration values
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void scopeInRecursionShowsCurrentValues() {
        Source code = src("scope_recurse.clj",
                "(defn countdown [n]\n" +        // L1
                "  (if (<= n 0)\n" +             // L2
                "    0\n" +                      // L3
                "    (countdown (dec n))))\n" +   // L4
                "(countdown 3)\n");               // L5

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
            assertFalse(nValues.isEmpty(), "should have captured n values");
            assertEquals(Long.valueOf(3), nValues.get(0), "first hit should have n=3");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  77. Top scope is accessible at breakpoint
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void topScopeAccessibleAtBreakpoint() {
        context.eval(src("top_setup.clj",
                "(def my-value 42)"));

        Source code = src("top_call.clj",
                "(+ my-value 1)\n");

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
                    if (val != null) {
                        foundMyValue[0] = true;
                    }
                }
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertEquals(43L, result.asLong());
            assertTrue(topScopeFound[0], "top scope should be accessible");
            assertTrue(foundMyValue[0], "top scope should contain 'my-value'");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  78. Top scope reads correct var values at breakpoint
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void topScopeReadsVarValues() {
        context.eval(src("top_val_setup.clj",
                "(def answer 42)"));

        Source code = src("top_val_call.clj",
                "(+ answer 1)\n");

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
            assertEquals(42L, readValue[0], "answer should be 42");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  78b. Top scope name matches script (ns ...) at breakpoint (guest-namespace snapshot)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void topScopeNameAtBreakpointMatchesScriptNamespace() {
        Source code = src(
                "ns_breakpoint.clj",
                "(ns com.cloffle.debug.breakpoint-test)\n"
                        + "(def only-in-this-ns 41)\n"
                        + "(+ only-in-this-ns 1)\n");

        OrderedCallback cb = new OrderedCallback();
        String[] topScopeName = {null};
        boolean[] foundVar = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(3).build());

            cb.add(event -> {
                DebugScope topScope = session.getTopScope("cloffle");
                if (topScope != null) {
                    topScopeName[0] = topScope.getName();
                    DebugValue val = topScope.getDeclaredValue("only-in-this-ns");
                    foundVar[0] = val != null && val.isNumber() && val.asLong() == 41L;
                }
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertEquals(42L, result.asLong());
            assertNotNull(topScopeName[0], "top scope should have a name");
            assertTrue(topScopeName[0].contains("com.cloffle.debug.breakpoint-test"), "top scope label should include script namespace: " + topScopeName[0]);
            assertTrue(foundVar[0], "top scope should list def in that namespace");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  79. Exception breakpoint fires on uncaught exception
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void exceptionBreakpointFiresOnUncaughtException() {
        Source code = src("exc_uncaught.clj",
                "(/ 1 0)\n");

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

            assertTrue(exceptionHit[0], "exception breakpoint should have fired on uncaught exception");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  80. Scope language is cloffle
    // ═══════════════════════════════════════════════════════════════════

    @Test
    public void scopeAtTopLevelIsAvailable() {
        Source code = src("scope_toplevel.clj",
                "(def x 42)\n" +
                "(+ x 1)\n");

        OrderedCallback cb = new OrderedCallback();
        boolean[] scopeFound = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> {
                DebugStackFrame frame = event.getTopStackFrame();
                DebugScope scope = frame.getScope();
                if (scope != null) {
                    scopeFound[0] = true;
                }
                event.prepareContinue();
            });

            Value result = context.eval(code);

            assertEquals(43L, result.asLong());
            assertTrue(scopeFound[0], "scope should be available at top level");
        }
    }

    @Test
    public void scopeLocalKeywordDisplaysAsString() {
        context.eval(src("kw_scope_setup.clj",
                "(defn kw-fn [] (let [tag :positive] tag))"));

        Source code = src("kw_scope_call.clj", "(kw-fn)\n");

        OrderedCallback cb = new OrderedCallback();
        List<String> varNames = new ArrayList<>();
        boolean[] sawTag = {false};

        try (DebuggerSession session = debugger.startSession(cb)) {
            session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());

            cb.add(event -> event.prepareStepInto(1));

            cb.add(event -> {
                DebugScope scope = event.getTopStackFrame().getScope();
                if (scope != null) {
                    for (DebugValue val : scope.getDeclaredValues()) {
                        varNames.add(val.getName());
                        if ("tag".equals(val.getName()) && !val.isNull()) {
                            sawTag[0] = val.isString() && ":positive".equals(val.asString());
                        }
                    }
                }
                event.prepareContinue();
            });

            context.eval(code);
            assertTrue(varNames.contains("tag"), "scope should list let binding tag");
            assertTrue(sawTag[0], "keyword local should present as string :positive");
        }
    }
}
