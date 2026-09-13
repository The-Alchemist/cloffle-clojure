package net.javacrumbs.cloffle;

import clojure.lang.Compiler;
import clojure.lang.Keyword;
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

    private static Object previousCompilerOptions;

    @BeforeClass
    public static void setUp() {
        RT.init();
        // :cloffle/op (KeywordAssoc etc.) emits only under :direct-linking.
        previousCompilerOptions = Compiler.COMPILER_OPTIONS.deref();
        Object opts = previousCompilerOptions;
        if (opts == null) {
            opts = clojure.lang.PersistentHashMap.EMPTY;
        }
        Compiler.COMPILER_OPTIONS.bindRoot(RT.assoc(opts, Keyword.directLinkingKey, Boolean.TRUE));
    }

    @org.junit.AfterClass
    public static void tearDown() {
        Compiler.COMPILER_OPTIONS.bindRoot(previousCompilerOptions);
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
        }
    }

    /**
     * Under {@code :direct-linking}, lowered {@code assoc} ignores {@code with-redefs}
     * (stock direct-linking contract). The intrinsic keeps running.
     */
    @Test
    public void withRedefsDoesNotDivertLoweredAssoc() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/stable-assoc");
            assertEquals("before", fn.execute("before").asString());

            Value redefined = context.eval("cloffle",
                    "(str (with-redefs [assoc (fn [m k v] {:b :redefined})]"
                            + "       (test.guest.assoc-lowering/stable-assoc :ignored)))");
            assertEquals("under :direct-linking, with-redefs must not affect lowered assoc",
                    ":ignored", redefined.asString());

            List<SpecializationInfo> all = keywordAssocSpecializations("test.guest.assoc-lowering", "stable-assoc");
            assertActive(all, "doShapeMap");

            assertEquals("after", fn.execute("after").asString());
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
        }
    }

    /**
     * Counts 10–16 are still {@code PersistentShapeMap16}, but {@code dissocTransition} returns
     * null. Without a {@code count == 9} guard the generated cache initializer NPEs on
     * {@code cached.matches} — the clj-http compat crash.
     */
    @Test
    public void shapeMap16TenKeyDissocFallsThroughToGeneric() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle",
                    "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :k8 :v8 :k9 :v9}");
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/dissoc16-10");
            for (int i = 0; i < 10; i++) {
                assertEquals(":v0", fn.execute(map).asString());
            }

            List<SpecializationInfo> all =
                    specializationsOf("test.guest.assoc-lowering", "dissoc16-10", "KeywordDissoc");
            assertActive(all, "doShapeMap16Generic");
            assertInactive(all, "doShapeMap16");
        }
    }

    /** Same null-transition trap at the other end of the ShapeMap16 range. */
    @Test
    public void shapeMap16SixteenKeyDissocFallsThroughToGeneric() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle", sixteenKeyMap());
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/dissoc16-16");
            for (int i = 0; i < 10; i++) {
                assertEquals(":v0", fn.execute(map).asString());
            }

            List<SpecializationInfo> all =
                    specializationsOf("test.guest.assoc-lowering", "dissoc16-16", "KeywordDissoc");
            assertActive(all, "doShapeMap16Generic");
            assertInactive(all, "doShapeMap16");
        }
    }

    /** Demote the first and last of the nine ShapeMap16 slots, not only a middle key. */
    @Test
    public void shapeMap16DissocDemotesEndSlots() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle",
                    "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :k8 :v8}");

            Value first = context.eval("cloffle", "test.guest.assoc-lowering/dissoc16-first");
            assertEquals("clojure.lang.PersistentShapeMap/8/false", first.execute(map).asString());
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "dissoc16-first", "KeywordDissoc"),
                    "doShapeMap16");

            Value last = context.eval("cloffle", "test.guest.assoc-lowering/dissoc16-last");
            assertEquals("clojure.lang.PersistentShapeMap/8/false", last.execute(map).asString());
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "dissoc16-last", "KeywordDissoc"),
                    "doShapeMap16");
        }
    }

    /** Absent-key dissoc on a 9-key map is still a cached NoOpDissoc16Transition. */
    @Test
    public void shapeMap16AbsentDissocStaysOnTheCachedTransition() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle",
                    "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :k8 :v8}");
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/dissoc16-absent");
            for (int i = 0; i < 10; i++) {
                assertTrue(fn.execute(map).asBoolean());
            }
            List<SpecializationInfo> all =
                    specializationsOf("test.guest.assoc-lowering", "dissoc16-absent", "KeywordDissoc");
            assertActive(all, "doShapeMap16");
            assertInactive(all, "doShapeMap16Generic");
        }
    }

    @Test
    public void emptyMapDissocIsACachedNoOp() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/empty-dissoc");
            assertTrue(fn.execute().asBoolean());
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "empty-dissoc", "KeywordDissoc"),
                    "doShapeMap");
        }
    }

    @Test
    public void lastKeyDissocEmptiesAShapeMap() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle", "{:a 1}");
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/last-key-dissoc");
            assertEquals("clojure.lang.PersistentShapeMap/0", fn.execute(map).asString());
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "last-key-dissoc", "KeywordDissoc"),
                    "doShapeMap");
        }
    }

    @Test
    public void absentKeyDissocOnShapeMapIsACachedNoOp() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle", "{:a :v1 :b :v2 :c :v3}");
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/absent-dissoc");
            assertTrue(fn.execute(map).asBoolean());
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "absent-dissoc", "KeywordDissoc"),
                    "doShapeMap");
        }
    }

    @Test
    public void nilDissocUsesTheNullSpecialization() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/nil-dissoc");
            assertTrue(fn.execute().isNull());
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "nil-dissoc", "KeywordDissoc"),
                    "doNull");
        }
    }

    @Test
    public void exhaustingTheDissocTransitionCacheFallsBackWithoutLosingTheType() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/polymorphic-dissoc");
            String[] maps = {
                    "{:a 1 :b 2}",
                    "{:a 1 :b 2 :c 3}",
                    "{:a 1 :b 2 :d 4}",
                    "{:a 1 :b 2 :e 5}",
                    "{:a 1 :b 2 :f 6}",
            };
            for (String literal : maps) {
                Value map = context.eval("cloffle", literal);
                assertEquals(1, fn.execute(map).asInt());
            }
            List<SpecializationInfo> all =
                    specializationsOf("test.guest.assoc-lowering", "polymorphic-dissoc", "KeywordDissoc");
            assertActive(all, "doShapeMapGeneric");
        }
    }

    @Test
    public void hashMapDissocUsesTheClassCache() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle", seventeenKeyMap());
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/hash-dissoc");
            assertEquals(
                    "clojure.lang.PersistentHashMap/clojure.lang.PersistentHashMap/16/false",
                    fn.execute(map).asString());
            List<SpecializationInfo> all =
                    specializationsOf("test.guest.assoc-lowering", "hash-dissoc", "KeywordDissoc");
            assertActive(all, "doMapCached");
            assertInactive(all, "doShapeMap16");
            assertInactive(all, "doShapeMap");
        }
    }

    /** 8-key ShapeMap + a new key is Promote16Transition, still on {@code doShapeMap}. */
    @Test
    public void eightKeyAssocPromotesToShapeMap16OnTheCachedTransition() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle",
                    "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7}");
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/promote-assoc");
            assertEquals("clojure.lang.PersistentShapeMap16/9", fn.execute(map).asString());
            List<SpecializationInfo> all = keywordAssocSpecializations("test.guest.assoc-lowering", "promote-assoc");
            assertActive(all, "doShapeMap");
            assertInactive(all, "doShapeMapGeneric");
            assertInactive(all, "doAssociativeCached");
        }
    }

    @Test
    public void emptyMapAssocUsesTheCachedInsertTransition() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/empty-assoc");
            assertEquals("clojure.lang.PersistentShapeMap/1/1", fn.execute().asString());
            assertActive(
                    keywordAssocSpecializations("test.guest.assoc-lowering", "empty-assoc"),
                    "doShapeMap");
        }
    }

    @Test
    public void insertAssocAddsAKeyWithoutLeavingTheCachedTransition() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle", "{:a 1 :b 2 :c 3}");
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/insert-assoc");
            assertEquals("clojure.lang.PersistentShapeMap/4", fn.execute(map).asString());
            assertActive(
                    keywordAssocSpecializations("test.guest.assoc-lowering", "insert-assoc"),
                    "doShapeMap");
        }
    }

    @Test
    public void nilAssocUsesTheNullSpecialization() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/nil-assoc");
            assertEquals(1, fn.execute().asInt());
            assertActive(
                    keywordAssocSpecializations("test.guest.assoc-lowering", "nil-assoc"),
                    "doNull");
        }
    }

    /** PersistentShapeMap16 new-key insert uses cached {@code Insert16Transition} on {@code doShapeMap16}. */
    @Test
    public void shapeMap16InsertUsesCachedTransition() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle",
                    "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :k8 :v8}");
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/shape16-assoc");
            assertEquals("clojure.lang.PersistentShapeMap16/10", fn.execute(map).asString());
            List<SpecializationInfo> all =
                    keywordAssocSpecializations("test.guest.assoc-lowering", "shape16-assoc");
            assertActive(all, "doShapeMap16");
            assertInactive(all, "doShapeMap16Generic");
            assertInactive(all, "doShapeMap");
            assertInactive(all, "doAssociativeCached");
        }
    }

    @Test
    public void shapeMap16TwelveKeyInsertUsesCachedTransition() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle", twelveKeyMap());
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/shape16-12-assoc");
            assertEquals("clojure.lang.PersistentShapeMap16/13", fn.execute(map).asString());
            List<SpecializationInfo> all =
                    keywordAssocSpecializations("test.guest.assoc-lowering", "shape16-12-assoc");
            assertActive(all, "doShapeMap16");
            assertInactive(all, "doShapeMap16Generic");
        }
    }

    @Test
    public void sixteenKeyAssocPromotesToHashMapOnCachedTransition() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle", sixteenKeyMap());
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/shape16-16-assoc");
            assertEquals("clojure.lang.PersistentHashMap/17", fn.execute(map).asString());
            List<SpecializationInfo> all =
                    keywordAssocSpecializations("test.guest.assoc-lowering", "shape16-16-assoc");
            assertActive(all, "doShapeMap16");
            assertInactive(all, "doShapeMap16Generic");
            assertInactive(all, "doShapeMap");
        }
    }

    @Test
    public void shapeMap16RewriteStaysOnTheCachedTransition() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle", nineKeyMap());
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/shape16-rewrite");
            for (int i = 0; i < 10; i++) {
                assertEquals("v", fn.execute(map, "v").asString());
            }
            List<SpecializationInfo> all =
                    keywordAssocSpecializations("test.guest.assoc-lowering", "shape16-rewrite");
            assertActive(all, "doShapeMap16");
            assertInactive(all, "doShapeMap16Generic");
            assertInactive(all, "doAssociativeCached");
            assertEquals(1, find(all, "doShapeMap16").getInstances());
        }
    }

    @Test
    public void shapeMap16SixteenKeyRewriteStaysOnTheCachedTransition() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle", sixteenKeyMap());
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/shape16-16-rewrite");
            for (int i = 0; i < 10; i++) {
                assertEquals("rewritten", fn.execute(map, "rewritten").asString());
            }
            List<SpecializationInfo> all =
                    keywordAssocSpecializations("test.guest.assoc-lowering", "shape16-16-rewrite");
            assertActive(all, "doShapeMap16");
            assertInactive(all, "doShapeMap16Generic");
        }
    }

    @Test
    public void shapeMap16LiteralEmitsCreateMapShaped16() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/shape16-literal");
            for (int i = 0; i < 10; i++) {
                assertEquals("runtime", fn.execute("runtime").asString());
            }
            List<String> names = instructionNames("test.guest.assoc-lowering", "shape16-literal");
            assertTrue(
                    "expected CreateMapShaped16, found " + names,
                    names.stream().anyMatch(name -> name.endsWith("CreateMapShaped16")));
            assertTrue(
                    "CreateMapN must not be used for a 9-key keyword literal, found " + names,
                    names.stream().noneMatch(name -> name.endsWith("CreateMapN")));

            Value constant = context.eval("cloffle", "test.guest.assoc-lowering/shape16-const");
            assertEquals(":v0", constant.execute().asString());
            List<String> constNames = instructionNames("test.guest.assoc-lowering", "shape16-const");
            assertTrue(
                    "expected CreateMapShaped16 for a constant 16-key map, found " + constNames,
                    constNames.stream().anyMatch(name -> name.endsWith("CreateMapShaped16")));
            assertTrue(
                    constNames.stream().noneMatch(name -> name.endsWith("CreateMapN")));

            List<SpecializationInfo> lookups =
                    specializationsOf("test.guest.assoc-lowering", "shape16-literal", "KeywordLookup");
            assertActive(lookups, "doShapeMap16");
            assertInactive(lookups, "doILookupCached");
            assertInactive(lookups, "doShapeMap16Generic");
        }
    }

    @Test
    public void shapeMap16LiteralGetLowersToKeywordLookup() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle", nineKeyMap());
            Value get2 = context.eval("cloffle", "test.guest.assoc-lowering/literal-get-16");
            for (int i = 0; i < 10; i++) {
                assertEquals(":v4", get2.execute(map).asString());
            }
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "literal-get-16", "KeywordLookup"),
                    "doShapeMap16");
            Value get3 = context.eval("cloffle", "test.guest.assoc-lowering/literal-get-16-default");
            for (int i = 0; i < 10; i++) {
                assertEquals(":fallback", get3.execute(map).asString());
            }
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "literal-get-16-default", "KeywordLookupDefault"),
                    "doShapeMap16");
        }
    }

    @Test
    public void exhaustingTheShapeMap16RewriteCacheFallsBackWithoutLosingTheType() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/polymorphic-shape16-assoc");
            String[] maps = {
                    nineKeyMap(),
                    "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :a :va}",
                    "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :b :vb}",
                    "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :c :vc}",
                    "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :d :vd}",
            };
            for (String literal : maps) {
                Value map = context.eval("cloffle", literal);
                assertEquals("v", fn.execute(map, "v").asString());
            }
            List<SpecializationInfo> all =
                    keywordAssocSpecializations("test.guest.assoc-lowering", "polymorphic-shape16-assoc");
            assertActive(all, "doShapeMap16Generic");
        }
    }

    @Test
    public void withRedefsDoesNotDivertShapeMap16Assoc() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle", nineKeyMap());
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/shape16-rewrite");
            assertEquals("before", fn.execute(map, "before").asString());

            Value redefined = context.eval("cloffle",
                    "(str (with-redefs [assoc (fn [m k v] {:k4 :redefined})]"
                            + "       (test.guest.assoc-lowering/shape16-rewrite "
                            + nineKeyMap() + " :ignored)))");
            assertEquals("under :direct-linking, with-redefs must not affect lowered assoc",
                    ":ignored", redefined.asString());

            List<SpecializationInfo> all =
                    keywordAssocSpecializations("test.guest.assoc-lowering", "shape16-rewrite");
            assertActive(all, "doShapeMap16");

            assertEquals("after", fn.execute(map, "after").asString());
        }
    }

    /** Under {@code :direct-linking}, lowered {@code dissoc} ignores {@code with-redefs}. */
    @Test
    public void withRedefsDoesNotDivertDissocLowering() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle", "{:a :v1 :b :v2 :c :v3}");
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/stable-dissoc");
            assertEquals(":v1", fn.execute(map).asString());

            Value redefined = context.eval("cloffle",
                    "(str (with-redefs [dissoc (fn [m k] {:a :redefined})]"
                            + "       (test.guest.assoc-lowering/stable-dissoc {:a :v1 :b :v2 :c :v3})))");
            assertEquals("under :direct-linking, with-redefs must not affect lowered dissoc",
                    ":v1", redefined.asString());

            List<SpecializationInfo> all =
                    specializationsOf("test.guest.assoc-lowering", "stable-dissoc", "KeywordDissoc");
            assertActive(all, "doShapeMap");

            assertEquals(":v1", fn.execute(map).asString());
        }
    }

    /**
     * Under {@code :direct-linking}, {@code alter-var-root} does not divert a lowered {@code assoc} site.
     */
    @Test
    public void alterVarRootDoesNotDivertAssocLowering() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/stable-assoc");
            assertEquals("before", fn.execute("before").asString());

            Var assoc = RT.var("clojure.core", "assoc");
            Object orig = assoc.getRawRoot();
            try {
                context.eval("cloffle",
                        "(alter-var-root #'clojure.core/assoc (constantly (fn [m k v] {:b :altered})))");
                Value altered = context.eval("cloffle",
                        "(str (test.guest.assoc-lowering/stable-assoc :ignored))");
                assertEquals("under :direct-linking, alter-var-root must not affect lowered assoc",
                        ":ignored", altered.asString());

                List<SpecializationInfo> all = keywordAssocSpecializations("test.guest.assoc-lowering", "stable-assoc");
                assertActive(all, "doShapeMap");
            } finally {
                assoc.bindRoot(orig);
            }

            assertEquals("after", fn.execute("after").asString());
        }
    }

    /** Same ignore-redef contract as {@link #alterVarRootDoesNotDivertAssocLowering} for {@code dissoc}. */
    @Test
    public void alterVarRootDoesNotDivertDissocLowering() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("assoc-lowering"));
            Value map = context.eval("cloffle", "{:a :v1 :b :v2 :c :v3}");
            Value fn = context.eval("cloffle", "test.guest.assoc-lowering/stable-dissoc");
            assertEquals(":v1", fn.execute(map).asString());

            Var dissoc = RT.var("clojure.core", "dissoc");
            Object orig = dissoc.getRawRoot();
            try {
                context.eval("cloffle",
                        "(alter-var-root #'clojure.core/dissoc (constantly (fn [m k] {:a :altered})))");
                Value altered = context.eval("cloffle",
                        "(str (test.guest.assoc-lowering/stable-dissoc {:a :v1 :b :v2 :c :v3}))");
                assertEquals("under :direct-linking, alter-var-root must not affect lowered dissoc",
                        ":v1", altered.asString());

                List<SpecializationInfo> all =
                        specializationsOf("test.guest.assoc-lowering", "stable-dissoc", "KeywordDissoc");
                assertActive(all, "doShapeMap");
            } finally {
                dissoc.bindRoot(orig);
            }

            assertEquals(":v1", fn.execute(map).asString());
        }
    }

    /**
     * Control: {@code get} lowering matches stock {@code :inline}, so altering the Var must not divert a
     * warmed {@code KeywordLookup} site.
     */
    @Test
    public void alterVarRootOnGetDoesNotDivertKeywordLookup() {
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

            Var get = RT.var("clojure.core", "get");
            Object orig = get.getRawRoot();
            try {
                context.eval("cloffle",
                        "(alter-var-root #'clojure.core/get (constantly (fn [& _] :altered)))");
                assertEquals(
                        "stock-inline-compatible get lowering must ignore the altered root",
                        ":v2",
                        get2.execute(map).asString());
                assertActive(
                        specializationsOf("test.guest.assoc-lowering", "literal-get", "KeywordLookup"),
                        "doShapeMap");
            } finally {
                get.bindRoot(orig);
            }

            assertEquals(":v2", get2.execute(map).asString());
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "literal-get", "KeywordLookup"),
                    "doShapeMap");
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

    private static String nineKeyMap() {
        return "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :k8 :v8}";
    }

    private static String twelveKeyMap() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 12; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(":k").append(i).append(" :v").append(i);
        }
        return sb.append('}').toString();
    }

    private static String sixteenKeyMap() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 16; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(":k").append(i).append(" :v").append(i);
        }
        return sb.append('}').toString();
    }

    private static String seventeenKeyMap() {
        return sixteenKeyMap().replace("}", " :k16 :v16}");
    }

    @Test
    public void constantMapExprNestedHeadersEmitsCreateMapShaped() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("const-map-shape"));
            assertEquals("text/plain",
                    context.eval("cloffle",
                            "(:content-type (:headers (test.guest.const-map-shape/nested-const-headers \"x\")))")
                            .asString());
            List<String> names = instructionNames("test.guest.const-map-shape", "nested-const-headers");
            assertTrue("expected CreateMapShaped2 for headers, found " + names,
                    names.stream().anyMatch(n -> n.endsWith("CreateMapShaped2")));
            assertTrue("expected CreateMapShaped3 for outer map, found " + names,
                    names.stream().anyMatch(n -> n.endsWith("CreateMapShaped3")));
        }
    }

    @Test
    public void constantMapExprAllConstNestedEmitsCreateMapShaped() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("const-map-shape"));
            context.eval("cloffle", "test.guest.const-map-shape/all-const-nested");
            List<String> names = instructionNames("test.guest.const-map-shape", "all-const-nested");
            assertTrue("expected CreateMapShaped1 for single-key headers, found " + names,
                    names.stream().anyMatch(n -> n.endsWith("CreateMapShaped1")));
            assertTrue("expected CreateMapShaped3 for outer map, found " + names,
                    names.stream().anyMatch(n -> n.endsWith("CreateMapShaped3")));
            assertTrue("must not use unshaped CreateMap3 for keyword constant nest, found " + names,
                    names.stream().noneMatch(n -> n.endsWith("CreateMap3")));
        }
    }

    @Test
    public void constantMapExprIntKeyDoesNotEmitCreateMapShaped() {
        try (Context context = createContext()) {
            context.eval("cloffle", guestSource("const-map-shape"));
            assertEquals(":a", context.eval("cloffle", "test.guest.const-map-shape/const-int-key").execute().asString());
            List<String> names = instructionNames("test.guest.const-map-shape", "const-int-key");
            assertTrue("non-keyword constant map must not use CreateMapShaped*, found " + names,
                    names.stream().noneMatch(n -> n.contains("CreateMapShaped")));
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
