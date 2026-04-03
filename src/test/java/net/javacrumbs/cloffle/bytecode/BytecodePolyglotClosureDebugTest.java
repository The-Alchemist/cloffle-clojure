package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Var;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.debug.DebugScope;
import com.oracle.truffle.api.debug.DebugValue;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.SuspendAnchor;
import com.oracle.truffle.api.debug.SuspendedCallback;
import com.oracle.truffle.api.debug.SuspendedEvent;
import com.oracle.truffle.api.nodes.Node;
import net.javacrumbs.cloffle.Clojure;
import net.javacrumbs.cloffle.nodes.ClojureClosure;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies bytecode param debug metadata survives Polyglot eval (same path as {@link net.javacrumbs.cloffle.DebuggerTest}).
 */
public class BytecodePolyglotClosureDebugTest {

    @Test
    public void polyglotDefFnClosureRootKeepsParamDebugNames() {
        Engine engine = Engine.create();
        Context ctx = Context.newBuilder("cloffle").engine(engine).allowAllAccess(true).build();
        try {
            ctx.eval(Source.newBuilder("cloffle", "(def add (fn* ([a b] a)))", "t.clj").buildLiteral());
            Namespace ns = (Namespace) RT.CURRENT_NS.deref();
            String nsName = ns.getName().getName();
            Var v = RT.var(nsName, "add");
            Object fn = v.deref();
            assertTrue("def should install a ClojureClosure", fn instanceof ClojureClosure);
            ClojureClosure c = (ClojureClosure) fn;
            var root = ((RootCallTarget) c.getCallTarget()).getRootNode();
            assertTrue(root instanceof CloffleBytecodeRootNode);
            Map<Integer, String> m = ((CloffleBytecodeRootNode) root).getBytecodeLocalOffsetDebugNames();
            assertTrue("expected debug name a", m.containsValue("a"));
            assertTrue("expected debug name b", m.containsValue("b"));
        } finally {
            ctx.close();
            engine.close();
        }
    }

    /**
     * Minimal copy of {@code DebuggerTest#scopeShowsFnParamsWithValues} to see declared scope names after step-into.
     */
    @Test
    public void debuggerSessionDeclaresAddParamsAfterStepInto() {
        Engine engine = Engine.create();
        Context ctx = Context.newBuilder("cloffle").engine(engine).allowAllAccess(true).build();
        Debugger debugger = Debugger.find(engine);
        try {
            ctx.eval(Source.newBuilder("cloffle", "(defn add [a b] (+ a b))", "scope_params_setup.clj").buildLiteral());
            Source code = Source.newBuilder("cloffle", "(add 10 20)\n", "scope_params_call.clj").buildLiteral();

            Queue<Consumer<SuspendedEvent>> handlers = new LinkedList<>();
            SuspendedCallback cb =
                    event -> {
                        Consumer<SuspendedEvent> h = handlers.poll();
                        if (h != null) {
                            h.accept(event);
                        } else {
                            event.prepareContinue();
                        }
                    };

            List<String> varNames = new ArrayList<>();
            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());
                handlers.add(event -> event.prepareStepInto(1));
                handlers.add(
                        event -> {
                            com.oracle.truffle.api.debug.DebugStackFrame top = event.getTopStackFrame();
                            Node halt = top.getRawNode(Clojure.class);
                            assertTrue("expected bytecode halt site", halt != null);
                            DebugScope scope = top.getScope();
                            if (scope != null) {
                                for (DebugValue val : scope.getDeclaredValues()) {
                                    varNames.add(val.getName());
                                }
                            }
                            event.prepareContinue();
                        });
                Value result = ctx.eval(code);
                assertEquals(30L, result.asLong());
            }
            assertTrue("declared names: " + varNames, varNames.contains("a"));
            assertTrue("declared names: " + varNames, varNames.contains("b"));
        } finally {
            ctx.close();
            engine.close();
        }
    }

    /**
     * Minimal repro for {@code DebuggerTest#scopeVariableHasCorrectValue}: after step-into {@code (double-it 7)},
     * parameter {@code x} must be {@code 7} in {@link DebugScope}.
     */
    @Test
    public void stepIntoDoubleItParamXIsSevenInScope() {
        Engine engine = Engine.create();
        Context ctx = Context.newBuilder("cloffle").engine(engine).allowAllAccess(true).build();
        Debugger debugger = Debugger.find(engine);
        try {
            ctx.eval(
                    Source.newBuilder(
                                    "cloffle",
                                    "(defn double-it [x] (let [result (* x 2)] result))",
                                    "double_it_setup.clj")
                            .buildLiteral());

            Namespace ns = (Namespace) RT.CURRENT_NS.deref();
            Var dv = RT.var(ns.getName().getName(), "double-it");
            Object dfn = dv.deref();
            String mapStr = "no-closure";
            if (dfn instanceof ClojureClosure dcc) {
                var dr = ((RootCallTarget) dcc.getCallTarget()).getRootNode();
                if (dr instanceof CloffleBytecodeRootNode cbr) {
                    mapStr = cbr.getBytecodeLocalOffsetDebugNames().toString();
                }
            }

            Source code = Source.newBuilder("cloffle", "(double-it 7)\n", "double_it_call.clj").buildLiteral();

            Queue<Consumer<SuspendedEvent>> handlers = new LinkedList<>();
            SuspendedCallback cb =
                    event -> {
                        Consumer<SuspendedEvent> h = handlers.poll();
                        if (h != null) {
                            h.accept(event);
                        } else {
                            event.prepareContinue();
                        }
                    };

            long[] xValue = {-1};
            List<String> declared = new ArrayList<>();
            String[] frameDiag = {null};
            String[] xValDiag = {""};
            boolean[] autoAdvanced = {false};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());
                handlers.add(event -> event.prepareStepInto(1));
                Consumer<SuspendedEvent> captureScope = new Consumer<>() {
                    @Override
                    public void accept(SuspendedEvent event) {
                            com.oracle.truffle.api.debug.DebugStackFrame top = event.getTopStackFrame();
                            Frame f = top.getRawFrame(Clojure.class, FrameInstance.FrameAccess.READ_ONLY);
                            if (f != null) {
                                StringBuilder sb = new StringBuilder("slots=");
                                int n = f.getFrameDescriptor().getNumberOfSlots();
                                for (int i = 0; i < Math.min(n, 10); i++) {
                                    sb.append('[').append(i).append(']');
                                    try {
                                        Object v = f.getValue(i);
                                        sb.append("val=").append(v);
                                    } catch (FrameSlotTypeException ex) {
                                        sb.append("illegal");
                                    }
                                    sb.append(';');
                                }
                                frameDiag[0] = sb.toString();
                            }
                            DebugScope scope = top.getScope();
                            if (scope != null) {
                                for (DebugValue val : scope.getDeclaredValues()) {
                                    declared.add(val.getName());
                                }
                                DebugValue xVal = scope.getDeclaredValue("x");
                                if (xVal != null) {
                                    boolean xReadable = xVal.isNumber() || xVal.fitsInLong();
                                    if (!autoAdvanced[0]
                                            && event.getSuspendAnchor() == SuspendAnchor.BEFORE
                                            && !xReadable) {
                                        autoAdvanced[0] = true;
                                        handlers.add(this);
                                        event.prepareStepInto(1);
                                        return;
                                    }
                                    xValDiag[0] =
                                            "isNumber="
                                                    + xVal.isNumber()
                                                    + " fitsLong="
                                                    + xVal.fitsInLong()
                                                    + " str="
                                                    + xVal;
                                    if (xReadable) {
                                        xValue[0] = xVal.asLong();
                                    }
                                }
                            }
                            event.prepareContinue();
                        }
                };
                handlers.add(captureScope);

                Value result = ctx.eval(code);
                assertEquals(14L, result.asLong());
            }

            assertEquals(
                    "debugMap="
                            + mapStr
                            + " declared="
                            + declared
                            + " "
                            + frameDiag[0]
                            + " "
                            + xValDiag[0],
                    7L,
                    xValue[0]);
        } finally {
            ctx.close();
            engine.close();
        }
    }

    /**
     * Asserts that the <em>direct</em> debug-name map
     * ({@link CloffleBytecodeRootNode#getDirectBytecodeLocalOffsetDebugNames()}) is populated on
     * the bytecode root the debugger actually executes after instrumentation — not just through
     * the Var-fallback path.
     */
    @Test
    public void instrumentedRootCarriesDirectDebugNames() {
        Engine engine = Engine.create();
        Context ctx = Context.newBuilder("cloffle").engine(engine).allowAllAccess(true).build();
        Debugger debugger = Debugger.find(engine);
        try {
            ctx.eval(Source.newBuilder("cloffle", "(defn add [a b] (+ a b))", "direct_debug_setup.clj").buildLiteral());
            Source code = Source.newBuilder("cloffle", "(add 10 20)\n", "direct_debug_call.clj").buildLiteral();

            Queue<Consumer<SuspendedEvent>> handlers = new LinkedList<>();
            SuspendedCallback cb = event -> {
                Consumer<SuspendedEvent> h = handlers.poll();
                if (h != null) h.accept(event);
                else event.prepareContinue();
            };

            @SuppressWarnings("unchecked")
            Map<Integer, String>[] directMap = new Map[]{null};
            CloffleBytecodeRootNode[] executingRoot = {null};

            try (DebuggerSession session = debugger.startSession(cb)) {
                session.install(Breakpoint.newBuilder(code.getURI()).lineIs(1).build());
                // Step 1: breakpoint hit on (add 10 20) — step into once
                handlers.add(event -> event.prepareStepInto(1));
                // Step 2: still in the calling wrapper root — step into again to enter add body
                handlers.add(event -> event.prepareStepInto(1));
                // Step 3: now inside the add function body — capture the fn root
                handlers.add(event -> {
                    com.oracle.truffle.api.debug.DebugStackFrame top = event.getTopStackFrame();
                    Node halt = top.getRawNode(Clojure.class);
                    if (halt != null) {
                        com.oracle.truffle.api.nodes.RootNode rn = halt.getRootNode();
                        if (rn instanceof CloffleBytecodeRootNode cbr) {
                            executingRoot[0] = cbr;
                            directMap[0] = cbr.getDirectBytecodeLocalOffsetDebugNames();
                        }
                    }
                    event.prepareContinue();
                });
                Value result = ctx.eval(code);
                assertEquals(30L, result.asLong());
            }

            assertNotNull("should have observed an executing CloffleBytecodeRootNode", executingRoot[0]);
            assertNotNull("direct debug map should not be null", directMap[0]);
            assertFalse("direct debug map should not be empty (no Var fallback): " + directMap[0],
                    directMap[0].isEmpty());
            assertTrue("direct debug map should contain 'a': " + directMap[0],
                    directMap[0].containsValue("a"));
            assertTrue("direct debug map should contain 'b': " + directMap[0],
                    directMap[0].containsValue("b"));
        } finally {
            ctx.close();
            engine.close();
        }
    }
}
