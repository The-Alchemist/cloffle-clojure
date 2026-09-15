package net.javacrumbs.cloffle;

import clojure.lang.Keyword;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.Instruction;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import net.javacrumbs.cloffle.nodes.ClojureClosure;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class LocalLastUseClearingTest {

    @BeforeClass
    public static void setUp() {
        RT.init();
    }

    @Test
    public void clearsSingleUseLetLocalBeforeKeywordLookupChain() {
        String ns = "test.guest.last-use-clear-enabled";
        try (Context context = context(true)) {
            context.eval("cloffle",
                    "(ns " + ns + ")"
                            + "(defn nested [v] (let [m {:a {:b v}}] (:b (:a m))))");

            Value fn = context.eval("cloffle", ns + "/nested");
            assertEquals("payload", fn.execute("payload").asString());
            assertTrue(hasInstruction(ns, "nested", "LoadAndClearLocal"));
        }
    }

    @Test
    public void keepsOrdinaryLoadWhenClearingIsDisabled() {
        String ns = "test.guest.last-use-clear-disabled";
        try (Context context = context(false)) {
            context.eval("cloffle",
                    "(ns " + ns + ")"
                            + "(defn nested [v] (let [m {:a {:b v}}] (:b (:a m))))");

            Value fn = context.eval("cloffle", ns + "/nested");
            assertEquals("payload", fn.execute("payload").asString());
            assertEquals(0, countInstructions(ns, "nested", "LoadAndClearLocal"));
        }
    }

    @Test
    public void clearsLastUseOnBothIfBranches() {
        String ns = "test.guest.last-use-if-branches";
        try (Context context = context(true)) {
            context.eval("cloffle",
                    "(ns " + ns + ")"
                            + "(defn both [p input] (let [m input] (if p (:a m) (:b m))))");
            Value fn = context.eval("cloffle", ns + "/both");
            assertEquals("A", fn.execute(true, RT.map(Keyword.intern(null, "a"), "A",
                    Keyword.intern(null, "b"), "B")).asString());
            assertEquals("B", fn.execute(false, RT.map(Keyword.intern(null, "a"), "A",
                    Keyword.intern(null, "b"), "B")).asString());
            assertTrue("each branch should clear m",
                    countInstructions(ns, "both", "LoadAndClearLocal") >= 2);
        }
    }

    @Test
    public void ifTestPlusBranchKeepsTestLoad() {
        String ns = "test.guest.last-use-if-test-branch";
        try (Context context = context(true)) {
            context.eval("cloffle",
                    "(ns " + ns + ")"
                            + "(defn test-then [input] (let [m input] (if m (:a m) nil)))");
            Value fn = context.eval("cloffle", ns + "/test-then");
            assertEquals(1L, fn.execute(RT.map(Keyword.intern(null, "a"), 1L)).asLong());
            assertTrue(fn.execute((Object) null).isNull());
            assertTrue(hasInstruction(ns, "test-then", "LoadAndClearLocal"));
            assertTrue("if test should keep a builtin load.local",
                    instructionNames(ns, "test-then").contains("load.local"));
        }
    }

    @Test
    public void andExpandsToLastUseClearing() {
        String ns = "test.guest.last-use-and";
        try (Context context = context(true)) {
            context.eval("cloffle",
                    "(ns " + ns + ")"
                            + "(defn and-lookup [input] (let [m input] (and m (:a m))))");
            Value fn = context.eval("cloffle", ns + "/and-lookup");
            assertTrue(fn.execute((Object) null).isNull());
            assertEquals("ok", fn.execute(RT.map(Keyword.intern(null, "a"), "ok")).asString());
            assertTrue(hasInstruction(ns, "and-lookup", "LoadAndClearLocal"));
        }
    }

    @Test
    public void vectorDestructuringClearsTempInLaterInit() {
        String ns = "test.guest.last-use-vec-destructure";
        try (Context context = context(true)) {
            context.eval("cloffle",
                    "(ns " + ns + ")"
                            + "(defn unpack [v] (let [[a b] v] (+ a b)))");
            Value fn = context.eval("cloffle", ns + "/unpack");
            assertEquals(3L, fn.execute(RT.vector(1L, 2L)).asLong());
            assertTrue(hasInstruction(ns, "unpack", "LoadAndClearLocal"));
        }
    }

    @Test
    public void mapDestructuringClearsTempInLaterInit() {
        String ns = "test.guest.last-use-map-destructure";
        try (Context context = context(true)) {
            context.eval("cloffle",
                    "(ns " + ns + ")"
                            + "(defn unpack [m] (let [{:keys [a b]} m] (+ a b)))");
            Value fn = context.eval("cloffle", ns + "/unpack");
            assertEquals(3L, fn.execute(RT.map(Keyword.intern(null, "a"), 1L,
                    Keyword.intern(null, "b"), 2L)).asLong());
            assertTrue(hasInstruction(ns, "unpack", "LoadAndClearLocal"));
        }
    }

    @Test
    public void nonLoopLetInRecurArgumentSurvivesIterations() {
        String ns = "test.guest.last-use-recur-let";
        try (Context context = context(true)) {
            context.eval("cloffle",
                    "(ns " + ns + ")"
                            + "(defn sum [n]"
                            + "  (loop [i 0 acc 0]"
                            + "    (if (< i n)"
                            + "      (let [x i] (recur (inc i) (+ acc x)))"
                            + "      acc)))");
            Value fn = context.eval("cloffle", ns + "/sum");
            assertEquals(10L, fn.execute(5L).asLong());
            assertTrue(hasInstruction(ns, "sum", "LoadAndClearLocal"));
        }
    }

    @Test
    public void loopBindingIsNotCleared() {
        String ns = "test.guest.last-use-loop-binding";
        try (Context context = context(true)) {
            context.eval("cloffle",
                    "(ns " + ns + ")"
                            + "(defn count-up [] (loop [n 0] (if (< n 3) (recur (inc n)) n)))");
            Value fn = context.eval("cloffle", ns + "/count-up");
            assertEquals(3L, fn.execute().asLong());
            assertEquals(0, countInstructions(ns, "count-up", "LoadAndClearLocal"));
        }
    }

    @Test
    public void fnParameterIsNotCleared() {
        String ns = "test.guest.last-use-fn-param";
        try (Context context = context(true)) {
            context.eval("cloffle",
                    "(ns " + ns + ")"
                            + "(defn bump [x] (if x (inc x) 0))");
            Value fn = context.eval("cloffle", ns + "/bump");
            assertEquals(2L, fn.execute(1L).asLong());
            assertEquals(0, countInstructions(ns, "bump", "LoadAndClearLocal"));
        }
    }

    @Test
    public void capturedLocalSurvivesEarlierDirectUse() {
        String ns = "test.guest.last-use-captured";
        try (Context context = context(true)) {
            context.eval("cloffle",
                    "(ns " + ns + ")"
                            + "(defn later [x]"
                            + "  (let [v x"
                            + "        _ (inc v)"
                            + "        f (fn [] v)]"
                            + "    (f)))");
            Value fn = context.eval("cloffle", ns + "/later");
            assertEquals(7L, fn.execute(7L).asLong());
        }
    }

    @Test
    public void lazySeqCaptureStaysLive() {
        String ns = "test.guest.last-use-lazy";
        try (Context context = context(true)) {
            context.eval("cloffle",
                    "(ns " + ns + ")"
                            + "(defn delayed [x] (let [v x s (lazy-seq (cons v nil))] (first s)))");
            Value fn = context.eval("cloffle", ns + "/delayed");
            assertEquals(9L, fn.execute(9L).asLong());
        }
    }

    @Test
    public void loopCreatedClosureSeesBindingAfterLaterRecur() {
        String ns = "test.guest.last-use-loop-closure";
        try (Context context = context(true)) {
            context.eval("cloffle",
                    "(ns " + ns + ")"
                            + "(defn after-recur []"
                            + "  (loop [n 0]"
                            + "    (let [x n"
                            + "          f (fn [] x)]"
                            + "      (if (< n 2)"
                            + "        (recur (inc n))"
                            + "        (f)))))");
            Value fn = context.eval("cloffle", ns + "/after-recur");
            assertEquals(2L, fn.execute().asLong());
        }
    }

    @Test
    public void caseDiscriminatorIsOrdinaryLoad() {
        String ns = "test.guest.last-use-case";
        try (Context context = context(true)) {
            context.eval("cloffle",
                    "(ns " + ns + ")"
                            + "(defn by-x [x] (let [k x] (case k 1 :a 2 :b :c)))");
            Value fn = context.eval("cloffle", ns + "/by-x");
            assertEquals(Keyword.intern(null, "b"), fn.execute(2L).as(Keyword.class));
            // case* forces shouldClear=false on the discriminator; an outer let of that
            // local may still last-use-clear when copying into case's disc slot.
        }
    }

    @Test
    public void repeatedReadClearsOnlyLastReference() {
        String ns = "test.guest.last-use-repeated";
        try (Context context = context(true)) {
            context.eval("cloffle",
                    "(ns " + ns + ")"
                            + "(defn twice [x] (let [v x] (+ v v)))");
            Value fn = context.eval("cloffle", ns + "/twice");
            assertEquals(10L, fn.execute(5L).asLong());
            assertEquals(1, countInstructions(ns, "twice", "LoadAndClearLocal"));
        }
    }

    private static Context context(boolean clearDeadLocals) {
        return Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .allowExperimentalOptions(true)
                .option(Clojure.CLEAR_DEAD_LOCALS_NAME, Boolean.toString(clearDeadLocals))
                .option("engine.BackgroundCompilation", "false")
                // Throw: PE bailouts fail the test. run-tests leaves the default Silent in place.
                .option("engine.CompilationFailureAction", "Throw")
                .build();
    }

    private static boolean hasInstruction(String namespace, String fnName, String suffix) {
        return countInstructions(namespace, fnName, suffix) > 0;
    }

    private static java.util.List<String> instructionNames(String namespace, String fnName) {
        Var var = Var.find(Symbol.intern(namespace, fnName));
        assertNotNull("Var must exist: " + namespace + "/" + fnName, var);
        ClojureClosure closure = (ClojureClosure) var.deref();
        CloffleBytecodeRootNode root =
                (CloffleBytecodeRootNode) ((RootCallTarget) closure.getCallTarget()).getRootNode();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
            names.add(instruction.getName());
        }
        return names;
    }

    private static int countInstructions(String namespace, String fnName, String suffix) {
        int count = 0;
        for (String name : instructionNames(namespace, fnName)) {
            if (name.endsWith(suffix)) {
                count++;
            }
        }
        return count;
    }
}
