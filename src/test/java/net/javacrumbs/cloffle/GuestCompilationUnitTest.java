package net.javacrumbs.cloffle;

import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.RootNode;
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
            context.eval("cloffle",
                    "(ns test.guest.compile)\n" +
                    "(defn compiled-check []\n" +
                    "  (com.oracle.truffle.api.CompilerDirectives/inCompiledCode))\n"
            );

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
            context.eval("cloffle",
                    "(ns test.guest.shapemap)\n" +
                    "(defn assoc-and-lookup [v]\n" +
                    "  (let [m {:a 1 :b 2 :c 3}\n" +
                    "        updated (assoc m :a v)]\n" +
                    "    [(:a updated)\n" +
                    "     (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))\n"
            );

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
    public void testMultiStepUpdateAndDestructuringPipeline() {
        try (Context context = createContext(true)) {
            context.eval("cloffle",
                    "(ns test.guest.pipeline)\n" +
                    "(defn thread-and-destructure [v1 v2]\n" +
                    "  (let [m (-> {:a 10 :b 20}\n" +
                    "              (assoc :a v1)\n" +
                    "              (assoc :b v2))\n" +
                    "        [a b] [(:a m) (:b m)]]\n" +
                    "    [a b (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))\n"
            );

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
            context.eval("cloffle",
                    "(ns test.guest.baseline)\n" +
                    "(defn compute-pair [a b]\n" +
                    "  (let [p [a b]]\n" +
                    "    [(nth p 0) (nth p 1)\n" +
                    "     (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))\n"
            );

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
            context.eval("cloffle",
                    "(ns test.guest.inspection)\n" +
                    "(defn inspected-fn [a b] (+ a b))\n"
            );

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
}
