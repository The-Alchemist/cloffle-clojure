package net.javacrumbs.cloffle;

import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.dsl.Introspection.SpecializationInfo;
import net.javacrumbs.cloffle.benchmark.ClojureClasspathResources;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import net.javacrumbs.cloffle.nodes.ClojureClosure;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Asserts <em>which</em> {@code KeywordAssoc} specialization is live for guest {@code assoc} calls.
 *
 * <p>This is the gate whose absence let the lowering layer rot: the operations were deleted across four
 * commits while {@code run-tests} and {@code run-clj-tests} stayed green, because every existing suite
 * only checks results, and the generic Var path produces identical results — just slower and allocating.
 * A benchmark cannot serve as this gate either; it is noisy and reports a number, not a reason.
 */
public class AssocLoweringIntrospectionTest {

    @BeforeClass
    public static void setUp() {
        RT.init();
    }

    private static Context createContext() {
        return Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .option("engine.BackgroundCompilation", "false")
                .option("engine.CompilationFailureAction", "Throw")
                .build();
    }

    private static String guestSource(String name) {
        return ClojureClasspathResources.read("guest-compilation/" + name + ".clj");
    }

    /**
     * Collects the specializations the DSL recorded for the {@code KeywordAssoc} instructions in a guest
     * fn. Scoping to that instruction matters: {@code KeywordLookup} also has a {@code doShapeMap}, so an
     * unscoped search would happily pass on the wrong operation.
     */
    private static List<SpecializationInfo> keywordAssocSpecializations(String namespace, String fnName) {
        return specializationsOf(namespace, fnName, "KeywordAssoc");
    }

    private static List<SpecializationInfo> specializationsOf(
            String namespace, String fnName, String instructionSuffix) {
        Var var = Var.find(Symbol.intern(namespace, fnName));
        assertNotNull("Var must exist: " + namespace + "/" + fnName, var);
        ClojureClosure closure = (ClojureClosure) var.deref();
        CloffleBytecodeRootNode root =
                (CloffleBytecodeRootNode) ((RootCallTarget) closure.getCallTarget()).getRootNode();
        BytecodeNode bytecode = root.getBytecodeNode();
        assertNotNull("Bytecode node must be materialized", bytecode);

        List<SpecializationInfo> all = new ArrayList<>();
        for (Instruction instruction : bytecode.getInstructions()) {
            if (!instruction.getName().endsWith(instructionSuffix)) {
                continue;
            }
            for (Instruction.Argument argument : instruction.getArguments()) {
                if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                    List<SpecializationInfo> info = argument.getSpecializationInfo();
                    if (info != null) {
                        all.addAll(info);
                    }
                }
            }
        }
        assertFalse(
                "No " + instructionSuffix + " instruction was emitted for " + namespace + "/" + fnName
                        + " — the :cloffle/op lowering did not fire at all",
                all.isEmpty());
        return all;
    }

    private static SpecializationInfo find(List<SpecializationInfo> all, String methodName) {
        for (SpecializationInfo info : all) {
            if (info.getMethodName().equals(methodName)) {
                return info;
            }
        }
        return null;
    }

    private static void assertActive(List<SpecializationInfo> all, String methodName) {
        SpecializationInfo info = find(all, methodName);
        assertNotNull(methodName + " must be present; found " + all, info);
        assertTrue(methodName + " must be the live specialization; found " + all, info.isActive());
    }

    private static void assertInactive(List<SpecializationInfo> all, String methodName) {
        SpecializationInfo info = find(all, methodName);
        if (info != null) {
            assertFalse(methodName + " must not be active; found " + all, info.isActive());
        }
    }

    @Test
    public void stableShapeAssocStaysOnTheCachedTransition() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/stable-assoc");
            for (int i = 0; i < 10; i++) {
                assertEquals("v", fn.execute("v").asString());
            }

            List<SpecializationInfo> all = keywordAssocSpecializations("test.guest.assoc-lowering", "stable-assoc");
            assertActive(all, "doShapeMap");
            assertInactive(all, "doShapeMapGeneric");
            assertInactive(all, "doAssociativeCached");
            assertInactive(all, "doRedefined");

            SpecializationInfo shapeMap = find(all, "doShapeMap");
            assertEquals("A stable shape must occupy exactly one cache entry", 1, shapeMap.getInstances());
        }
    }

    @Test
    public void exhaustingTheTransitionCacheFallsBackWithoutLosingTheType() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/polymorphic-assoc");

            // Five distinct ShapeMap layouts against a transition cache of 4.
            String[] maps = {
                    "{:a 1 :b 2}",
                    "{:b 2 :c 3}",
                    "{:b 2 :d 4}",
                    "{:b 2 :e 5}",
                    "{:b 2 :f 6}",
            };
            for (String literal : maps) {
                Value map = context.eval("cloffle", literal);
                assertEquals("v", fn.execute(map, "v").asString());
            }

            List<SpecializationInfo> all = keywordAssocSpecializations("test.guest.assoc-lowering", "polymorphic-assoc");
            assertActive(all, "doShapeMapGeneric");
            assertInactive(all, "doRedefined");
        }
    }

    /**
     * The Tier 2 acceptance test. {@code assoc} is {@code :static} but not {@code :inline} upstream, so it
     * is legitimately redefinable and the lowering must step aside — not merely produce a correct answer,
     * but demonstrably stop using the intrinsic.
     */
    @Test
    public void withRedefsRetiresTheLoweringPermanently() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/stable-assoc");
            assertEquals("before", fn.execute("before").asString());

            Value redefined = context.eval("cloffle",
                    "(str (with-redefs [assoc (fn [m k v] {:b :redefined})]"
                            + "       (test.guest.assoc-lowering/stable-assoc :ignored)))");
            assertEquals("with-redefs must reach the lowered call site", ":redefined", redefined.asString());

            List<SpecializationInfo> all = keywordAssocSpecializations("test.guest.assoc-lowering", "stable-assoc");
            assertActive(all, "doRedefined");
            assertInactive(all, "doShapeMap");

            // The root is restored, but the call site stays generic: correct, just not re-optimized.
            assertEquals("after", fn.execute("after").asString());
            assertActive(keywordAssocSpecializations("test.guest.assoc-lowering", "stable-assoc"), "doRedefined");
        }
    }

    /**
     * {@code get} is Tier 1: upstream marks it {@code :inline} with {@code :inline-arities #{2 3}}, so
     * stock already compiles {@code (get m :k)} straight to {@code RT.get} and ignores redefinition.
     * Lowering it to the same operations {@code (:k m)} uses is stock parity, not a new divergence.
     */
    @Test
    public void literalKeyGetLowersToKeywordLookup() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle", "{:a :v1 :b :v2 :c :v3}");

            Value get2 = context.eval("cloffle", "test.guest.assoc-lowering/literal-get");
            for (int i = 0; i < 10; i++) {
                assertEquals(":v2", get2.execute(map).asString());
            }
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "literal-get", "KeywordLookup"),
                    "doShapeMap");

            Value get3 = context.eval("cloffle", "test.guest.assoc-lowering/literal-get-default");
            for (int i = 0; i < 10; i++) {
                assertEquals(":fallback", get3.execute(map).asString());
            }
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "literal-get-default", "KeywordLookupDefault"),
                    "doShapeMap");
        }
    }

    /** A computed key carries no constant operand, so it must stay on the Var path. */
    @Test
    public void computedKeyGetIsNotLowered() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle", "{:a :v1 :b :v2}");
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/computed-get");
            assertEquals(":v2", fn.execute(map, context.eval("cloffle", ":b")).asString());

            assertTrue(
                    "(get m k) with a computed key must not lower",
                    instructionNames("test.guest.assoc-lowering", "computed-get").stream()
                            .noneMatch(name -> name.endsWith("KeywordLookup")));
        }
    }

    /** {@code dissoc} is Tier 2 like {@code assoc}: shaped fast path, plus a redefinition guard. */
    @Test
    public void stableShapeDissocStaysOnTheCachedTransition() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle", "{:a :v1 :b :v2 :c :v3}");
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/stable-dissoc");
            for (int i = 0; i < 10; i++) {
                assertEquals(":v1", fn.execute(map).asString());
            }

            List<SpecializationInfo> all =
                    specializationsOf("test.guest.assoc-lowering", "stable-dissoc", "KeywordDissoc");
            assertActive(all, "doShapeMap");
            assertInactive(all, "doShapeMapGeneric");
            assertInactive(all, "doMapCached");
            assertInactive(all, "doRedefined");

            assertEquals(
                    "A stable shape must occupy exactly one cache entry",
                    1,
                    find(all, "doShapeMap").getInstances());
        }
    }

    /** A 9-key receiver is a {@code PersistentShapeMap16}, which has its own transition class. */
    @Test
    public void shapeMap16DissocUsesItsOwnTransition() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle",
                    "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :k8 :v8}");
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/dissoc16");
            for (int i = 0; i < 10; i++) {
                assertEquals(":v0", fn.execute(map).asString());
            }

            List<SpecializationInfo> all =
                    specializationsOf("test.guest.assoc-lowering", "dissoc16", "KeywordDissoc");
            assertActive(all, "doShapeMap16");
            assertInactive(all, "doShapeMap");
            assertInactive(all, "doMapCached");
            assertInactive(all, "doRedefined");
        }
    }

    /** The Tier 2 acceptance test for {@code dissoc}: {@code with-redefs} must retire the fast path. */
    @Test
    public void withRedefsRetiresTheDissocLowering() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle", "{:a :v1 :b :v2 :c :v3}");
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/stable-dissoc");
            assertEquals(":v1", fn.execute(map).asString());

            Value redefined = context.eval("cloffle",
                    "(str (with-redefs [dissoc (fn [m k] {:a :redefined})]"
                            + "       (test.guest.assoc-lowering/stable-dissoc {:a :v1 :b :v2 :c :v3})))");
            assertEquals("with-redefs must reach the lowered call site", ":redefined", redefined.asString());

            List<SpecializationInfo> all =
                    specializationsOf("test.guest.assoc-lowering", "stable-dissoc", "KeywordDissoc");
            assertActive(all, "doRedefined");
            assertInactive(all, "doShapeMap");

            assertEquals(":v1", fn.execute(map).asString());
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "stable-dissoc", "KeywordDissoc"),
                    "doRedefined");
        }
    }

    /** A computed key carries no constant operand, so {@code dissoc} must stay on the Var path. */
    @Test
    public void computedKeyDissocIsNotLowered() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle", "{:a :v1 :b :v2}");
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/computed-dissoc");
            assertEquals(":v1", fn.execute(map, context.eval("cloffle", ":b")).asString());

            assertTrue(
                    "(dissoc m k) with a computed key must not lower",
                    instructionNames("test.guest.assoc-lowering", "computed-dissoc").stream()
                            .noneMatch(name -> name.endsWith("KeywordDissoc")));
        }
    }

    private static List<String> instructionNames(String namespace, String fnName) {
        Var var = Var.find(Symbol.intern(namespace, fnName));
        assertNotNull("Var must exist: " + namespace + "/" + fnName, var);
        ClojureClosure closure = (ClojureClosure) var.deref();
        CloffleBytecodeRootNode root =
                (CloffleBytecodeRootNode) ((RootCallTarget) closure.getCallTarget()).getRootNode();
        List<String> names = new ArrayList<>();
        for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
            names.add(instruction.getName());
        }
        return names;
    }
}
