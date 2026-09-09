package net.javacrumbs.cloffle;

import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.RootNode;
import net.javacrumbs.cloffle.benchmark.ClojureClasspathResources;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import net.javacrumbs.cloffle.nodes.ClojureClosure;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

public class GuestCompilationUnitTest {

    @BeforeClass
    public static void setUp() {
        RT.init();
    }

    private static String guestSource(String name) {
        return ClojureClasspathResources.read("guest-compilation/" + name + ".clj");
    }

    private Context createContext(String compileOnlyPattern) {
        Context.Builder builder = Context.newBuilder("cloffle")
                .allowAllAccess(true);
        if (compileOnlyPattern != null) {
            builder.option("engine.CompileImmediately", "true")
                   .option("engine.BackgroundCompilation", "false")
                   .option("engine.CompilationFailureAction", "Throw")
                   .option("engine.CompileOnly", compileOnlyPattern);
        } else {
            builder.option("engine.BackgroundCompilation", "false")
                   .option("engine.CompilationFailureAction", "Throw");
        }
        return builder.build();
    }

    private Context createContext(boolean compileImmediately) {
        return createContext(compileImmediately ? "CloffleBytecode" : null);
    }

    @Test
    public void testImmediateSynchronousGuestCompilation() {
        try (Context context = createContext(true)) {
            context.eval("cloffle", guestSource("compile"));

            Value fn = context.eval("cloffle", "test.guest.compile/compiled-check");
            Value r1 = fn.execute();
            assertFalse("Expected execution in interpreter on first call", r1.asBoolean());
            Value r2 = fn.execute();
            assertTrue("Expected execution in compiled code on second call", r2.asBoolean());
        }
    }

    @Test
    public void testEphemeralShapeMapPipelineInGuestCode() {
        try (Context context = createContext(true)) {
            context.eval("cloffle", guestSource("shapemap"));

            Value fn = context.eval("cloffle", "test.guest.shapemap/assoc-and-lookup");
            // First call triggers synchronous JIT compilation
            fn.execute("warmup");
            // Second call executes in compiled machine code
            Value res = fn.execute("test-val");

            assertEquals("test-val", res.getArrayElement(0).asString());
            assertTrue("Expected execution in compiled code", res.getArrayElement(1).asBoolean());
        }
    }

    @Test
    public void testCachedShapeMapAssocAndPromotionInGuestCode() {
        try (Context context = createContext(true)) {
            context.eval("cloffle", guestSource("assoc-transition"));

            Value assocFn = context.eval("cloffle", "test.guest.assoc-transition/cached-incoming-assoc");
            Value stableShape = context.eval("cloffle", "{:a 1 :b 2 :c 3}");
            assocFn.execute(stableShape, "warmup");
            Value stableResult = assocFn.execute(stableShape, "cached");
            assertEquals("cached", stableResult.getArrayElement(0).asString());
            assertTrue("Expected stable-shape assoc in compiled code", stableResult.getArrayElement(1).asBoolean());

            // A different ShapeMap layout must miss the first transition guard and remain correct.
            Value differentShape = context.eval("cloffle", "{:x 1 :y 2}");
            Value mismatchResult = assocFn.execute(differentShape, "different");
            assertEquals("different", mismatchResult.getArrayElement(0).asString());

            // Non-ShapeMap Associative receivers continue through the existing class-cached fallback.
            Value arrayMap = context.eval("cloffle", "(array-map :array-key 1)");
            Value fallbackResult = assocFn.execute(arrayMap, "fallback");
            assertEquals("fallback", fallbackResult.getArrayElement(0).asString());

            Value promoteFn = context.eval("cloffle", "test.guest.assoc-transition/promote-eight");
            promoteFn.execute("warmup");
            Value promoted = promoteFn.execute("promoted");
            assertEquals("promoted", promoted.getArrayElement(0).asString());
            assertEquals(9L, promoted.getArrayElement(1).asLong());
            assertTrue("Expected direct promotion to ShapeMap16", promoted.getArrayElement(2).asBoolean());
            assertTrue("Expected 8->9 promotion in compiled code", promoted.getArrayElement(3).asBoolean());

            Value rewriteFn = context.eval("cloffle", "test.guest.assoc-transition/rewrite-sixteen");
            Value sixteen = context.eval("cloffle",
                    "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :k8 :v8 :k9 :v9 :k10 :v10 :k11 :v11 :k12 :v12 :k13 :v13 :k14 :v14 :k15 :v15}");
            rewriteFn.execute(sixteen, "warmup");
            Value rewritten = rewriteFn.execute(sixteen, "rewritten");
            assertEquals("rewritten", rewritten.getArrayElement(0).asString());
            assertEquals(":v15", rewritten.getArrayElement(1).asString());
            assertEquals(16L, rewritten.getArrayElement(2).asLong());
            assertTrue(rewritten.getArrayElement(3).asBoolean());
            assertTrue("Expected ShapeMap16 rewrite in compiled code", rewritten.getArrayElement(4).asBoolean());
        }
    }

    @Test
    public void testEventEnrichPipelineReturnsScalarInCompiledCode() {
        try (Context context = createContext(true)) {
            context.eval("cloffle", guestSource("event-enrich"));
            Value fn = context.eval("cloffle", "test.guest.event-enrich/guest-event-enrich-pipeline");
            fn.execute("warmup");
            Value res = fn.execute("ok");
            assertEquals("ok", res.getArrayElement(0).asString());
            assertTrue("Expected event enrich pipeline in compiled code", res.getArrayElement(1).asBoolean());
        }
    }

    @Test
    public void testCachedShapeMapDissocAndDemotionInGuestCode() {
        try (Context context = createContext(true)) {
            context.eval("cloffle", guestSource("dissoc-transition"));

            Value dissocFn = context.eval("cloffle", "test.guest.dissoc-transition/cached-incoming-dissoc");
            Value stableShape = context.eval("cloffle", "{:a 1 :b 2 :c 3}");
            dissocFn.execute(stableShape);
            Value stableRes = dissocFn.execute(stableShape);
            assertTrue(stableRes.getArrayElement(0).isNull());
            assertEquals(1L, stableRes.getArrayElement(1).asLong());
            assertEquals(3L, stableRes.getArrayElement(2).asLong());
            assertEquals(2L, stableRes.getArrayElement(3).asLong());
            assertTrue("Expected stable-shape dissoc in compiled code", stableRes.getArrayElement(4).asBoolean());

            // Layout mismatch should still work via fallback
            Value diffShape = context.eval("cloffle", "{:b 2 :x 10}");
            Value diffRes = dissocFn.execute(diffShape);
            assertTrue(diffRes.getArrayElement(0).isNull());
            assertEquals(1L, diffRes.getArrayElement(3).asLong());

            // Non-ShapeMap fallback
            Value arrayMap = context.eval("cloffle", "(array-map :a 1 :b 2 :c 3)");
            Value arrayRes = dissocFn.execute(arrayMap);
            assertTrue(arrayRes.getArrayElement(0).isNull());
            assertEquals(2L, arrayRes.getArrayElement(3).asLong());

            // Multi-step pipeline
            Value multiFn = context.eval("cloffle", "test.guest.dissoc-transition/multi-step-dissoc");
            multiFn.execute(stableShape);
            Value multiRes = multiFn.execute(stableShape);
            assertTrue(multiRes.getArrayElement(0).isNull());
            assertEquals(2L, multiRes.getArrayElement(1).asLong());
            assertTrue(multiRes.getArrayElement(2).isNull());
            assertEquals(1L, multiRes.getArrayElement(3).asLong());
            assertTrue("Expected multi-step dissoc in compiled code", multiRes.getArrayElement(4).asBoolean());

            // 9 -> 8 demote to PersistentShapeMap
            Value demoteFn = context.eval("cloffle", "test.guest.dissoc-transition/demote-nine");
            Value shape9 = context.eval("cloffle", "{:p0 0 :p1 1 :p2 2 :p3 3 :p4 4 :p5 5 :p6 6 :p7 7 :p8 8}");
            demoteFn.execute(shape9);
            Value demoteRes = demoteFn.execute(shape9);
            assertTrue(demoteRes.getArrayElement(0).isNull());
            assertEquals(8L, demoteRes.getArrayElement(1).asLong());
            assertTrue("Expected demotion to PersistentShapeMap", demoteRes.getArrayElement(2).asBoolean());
            assertTrue("Expected 9->8 demote in compiled code", demoteRes.getArrayElement(3).asBoolean());
        }
    }

    @Test
    public void testEphemeralDissocReturnsScalarInCompiledCode() {
        try (Context context = createContext(true)) {
            context.eval("cloffle", guestSource("dissoc-pea"));
            Value fn = context.eval("cloffle", "test.guest.dissoc-pea/guest-ephemeral-dissoc");
            fn.execute(10);
            Value res = fn.execute(20);
            assertEquals(3L, res.getArrayElement(0).asLong());
            assertTrue("Expected ephemeral dissoc in compiled code", res.getArrayElement(1).asBoolean());
        }
    }

    @Test
    public void testEventSanitizePipelineReturnsScalarInCompiledCode() {
        try (Context context = createContext(true)) {
            context.eval("cloffle", guestSource("event-sanitize"));
            Value fn = context.eval("cloffle", "test.guest.event-sanitize/guest-event-sanitize-pipeline");
            fn.execute("top-secret");
            Value res = fn.execute("classified");
            assertEquals(101L, res.getArrayElement(0).asLong());
            assertTrue("Expected event sanitize pipeline in compiled code", res.getArrayElement(1).asBoolean());
        }
    }

    @Test
    public void testMultiStepUpdateAndDestructuringPipeline() {
        try (Context context = createContext(true)) {
            context.eval("cloffle", guestSource("pipeline"));

            Value fn = context.eval("cloffle", "test.guest.pipeline/thread-and-destructure");
            // First call triggers synchronous JIT compilation
            fn.execute(1, 2);
            // Second call executes in compiled machine code
            Value res = fn.execute(11, 21);

            assertEquals(11L, res.getArrayElement(0).asLong());
            assertEquals(21L, res.getArrayElement(1).asLong());
            assertTrue("Expected execution in compiled code", res.getArrayElement(2).asBoolean());
        }
    }

    @Test
    public void testScalarReplacementBaseline() {
        try (Context context = createContext(true)) {
            context.eval("cloffle", guestSource("baseline"));

            Value fn = context.eval("cloffle", "test.guest.baseline/compute-pair");
            fn.execute(2, 3);
            Value res = fn.execute(2, 3);

            assertEquals(2L, res.getArrayElement(0).asLong());
            assertEquals(3L, res.getArrayElement(1).asLong());
            assertTrue("Expected execution in compiled code", res.getArrayElement(2).asBoolean());
        }
    }

    @Test
    public void testProgrammaticCallTargetInspection() {
        try (Context context = createContext(false)) {
            context.eval("cloffle", guestSource("inspection"));

            Var v = Var.find(Symbol.intern("test.guest.inspection", "inspected-fn"));
            assertNotNull("Var must exist in namespace", v);

            Object deref = v.deref();
            assertTrue("Deref value must be ClojureClosure", deref instanceof ClojureClosure);

            ClojureClosure closure = (ClojureClosure) deref;
            CallTarget ct = closure.getCallTarget();
            assertNotNull("CallTarget must not be null", ct);
            assertTrue("CallTarget should be RootCallTarget", ct instanceof RootCallTarget);

            RootCallTarget rct = (RootCallTarget) ct;
            RootNode rn = rct.getRootNode();
            assertNotNull("RootNode must not be null", rn);
            assertTrue("RootNode must be CloffleBytecodeRootNode", rn instanceof CloffleBytecodeRootNode);

            CloffleBytecodeRootNode cbrn = (CloffleBytecodeRootNode) rn;
            assertEquals("test.guest.inspection/inspected-fn", cbrn.getName());
        }
    }

    @Test
    public void testCondOptionPipeline() {
        try (Context context = createContext(true)) {
            context.eval("cloffle", guestSource("cond"));

            Value fn = context.eval("cloffle", "test.guest.cond/guest-cond-options");
            // First call triggers synchronous JIT compilation
            fn.execute("btn", "primary", "/submit", 500);
            // Second call executes in compiled machine code
            Value res = fn.execute("btn", "primary", "/submit", 500);

            assertNotNull(res);
            assertEquals(500L, res.getArrayElement(0).asLong());
            assertTrue("Expected execution in compiled code", res.getArrayElement(1).asBoolean());
        }
    }

    @Test
    public void testNestedGetInLiteralPathInCompiledCode() {
        try (Context context = createContext(true)) {
            context.eval("cloffle", guestSource("get-in"));

            Value fn = context.eval("cloffle", "test.guest.get-in/nested");
            fn.execute();
            Value res = fn.execute();
            assertEquals("Alice", res.getArrayElement(0).asString());
            assertTrue("Expected execution in compiled code", res.getArrayElement(1).asBoolean());
        }
    }

    // Intrinsic tests (testZeroAllocationKeywordFieldNames, testFixedArityStrInCompiledCode)
    // removed to eliminate hardcoding of clojure.core functions and match stock Clojure semantics.
}
