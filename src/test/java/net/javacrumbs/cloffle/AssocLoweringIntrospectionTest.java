package net.javacrumbs.cloffle;

import clojure.lang.BytecodeDslTestSupport;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts <em>which</em> {@code KeywordAssoc} specialization is live for guest {@code assoc} calls.
 *
 * <p>This is the gate whose absence let the lowering layer rot: the operations were deleted across four
 * commits while {@code run-tests} and {@code run-clj-tests} stayed green, because every existing suite
 * only checks results, and the generic Var path produces identical results — just slower and allocating.
 * A benchmark cannot serve as this gate either; it is noisy and reports a number, not a reason.
 */
public class AssocLoweringIntrospectionTest {

    @BeforeAll
    static void setUp() {
        RT.init();
    }

    /**
     * :cloffle/op lowering emits only under {@code :direct-linking}. Thread-bind per eval instead
     * of {@link Var#bindRoot} on {@code *compiler-options*} so other tests see stock JVM options.
     */
    private static Value evalWithDirectLinking(Context context, String code) {
        try {
            return BytecodeDslTestSupport.withDirectLinkingOn(() -> context.eval("cloffle", code));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void loadGuest(Context context, String resourceName) {
        evalWithDirectLinking(context, guestSource(resourceName));
    }

    private static Context createContext() {
        // Throw: Graal PE bailouts fail this test even when it is launched outside run-tests.
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
        assertNotNull(var, "Var must exist: " + namespace + "/" + fnName);
        ClojureClosure closure = (ClojureClosure) var.deref();
        CloffleBytecodeRootNode root =
                (CloffleBytecodeRootNode) ((RootCallTarget) closure.getCallTarget()).getRootNode();
        BytecodeNode bytecode = root.getBytecodeNode();
        assertNotNull(bytecode, "Bytecode node must be materialized");

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
        assertFalse(all.isEmpty(),
                "No " + instructionSuffix + " instruction was emitted for " + namespace + "/" + fnName
                        + " — the :cloffle/op lowering did not fire at all");
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
        assertNotNull(info, methodName + " must be present; found " + all);
        assertTrue(info.isActive(), methodName + " must be the live specialization; found " + all);
    }

    private static void assertInactive(List<SpecializationInfo> all, String methodName) {
        SpecializationInfo info = find(all, methodName);
        if (info != null) {
            assertFalse(info.isActive(), methodName + " must not be active; found " + all);
        }
    }

    @Test
    @Tag("direct-linking-on")
    void stableShapeAssocStaysOnTheCachedTransition() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/stable-assoc");
            for (int i = 0; i < 10; i++) {
                assertEquals("v", fn.execute("v").asString());
            }

            List<SpecializationInfo> all = keywordAssocSpecializations("test.guest.assoc-lowering", "stable-assoc");
            assertActive(all, "doShapeMap");
            assertInactive(all, "doShapeMapGeneric");
            assertInactive(all, "doAssociativeCached");

            SpecializationInfo shapeMap = find(all, "doShapeMap");
            assertEquals(1, shapeMap.getInstances(),
                    "A stable shape must occupy exactly one cache entry");
        }
    }

    @Test
    @Tag("direct-linking-on")
    void exhaustingTheTransitionCacheFallsBackWithoutLosingTheType() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/polymorphic-assoc");

            // Five distinct ShapeMap layouts against a transition cache of 4.
            String[] maps = {
                    "{:a 1 :b 2}",
                    "{:b 2 :c 3}",
                    "{:b 2 :d 4}",
                    "{:b 2 :e 5}",
                    "{:b 2 :f 6}",
            };
            for (String literal : maps) {
                Value map = evalWithDirectLinking(context, literal);
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
    @Tag("direct-linking-on")
    void withRedefsDoesNotDivertLoweredAssoc() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/stable-assoc");
            assertEquals("before", fn.execute("before").asString());

            Value redefined = evalWithDirectLinking(context,
                    "(str (with-redefs [assoc (fn [m k v] {:b :redefined})]"
                            + "       (test.guest.assoc-lowering/stable-assoc :ignored)))");
            assertEquals(":ignored", redefined.asString(),
                    "under :direct-linking, with-redefs must not affect lowered assoc");

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
    @Tag("direct-linking-on")
    void literalKeyGetLowersToKeywordLookup() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context, "{:a :v1 :b :v2 :c :v3}");

            Value get2 = evalWithDirectLinking(context, "test.guest.assoc-lowering/literal-get");
            for (int i = 0; i < 10; i++) {
                assertEquals(":v2", get2.execute(map).asString());
            }
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "literal-get", "KeywordLookup"),
                    "doShapeMap");

            Value get3 = evalWithDirectLinking(context, "test.guest.assoc-lowering/literal-get-default");
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
    @Tag("direct-linking-on")
    void computedKeyGetIsNotLowered() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context, "{:a :v1 :b :v2}");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/computed-get");
            assertEquals(":v2", fn.execute(map, evalWithDirectLinking(context, ":b")).asString());

            assertTrue(
                    instructionNames("test.guest.assoc-lowering", "computed-get").stream()
                            .noneMatch(name -> name.endsWith("KeywordLookup")),
                    "(get m k) with a computed key must not lower");
        }
    }

    /** {@code dissoc} is Tier 2 like {@code assoc}: shaped fast path, plus a redefinition guard. */
    @Test
    @Tag("direct-linking-on")
    void stableShapeDissocStaysOnTheCachedTransition() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context, "{:a :v1 :b :v2 :c :v3}");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/stable-dissoc");
            for (int i = 0; i < 10; i++) {
                assertEquals(":v1", fn.execute(map).asString());
            }

            List<SpecializationInfo> all =
                    specializationsOf("test.guest.assoc-lowering", "stable-dissoc", "KeywordDissoc");
            assertActive(all, "doShapeMap");
            assertInactive(all, "doShapeMapGeneric");
            assertInactive(all, "doMapCached");

            assertEquals(1, find(all, "doShapeMap").getInstances(),
                    "A stable shape must occupy exactly one cache entry");
        }
    }

    /** A 9-key receiver is a {@code PersistentShapeMap16}, which has its own transition class. */
    @Test
    @Tag("direct-linking-on")
    void shapeMap16DissocUsesItsOwnTransition() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context,
                    "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :k8 :v8}");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/dissoc16");
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
    @Tag("direct-linking-on")
    void shapeMap16TenKeyDissocFallsThroughToGeneric() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context,
                    "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :k8 :v8 :k9 :v9}");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/dissoc16-10");
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
    @Tag("direct-linking-on")
    void shapeMap16SixteenKeyDissocFallsThroughToGeneric() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context, sixteenKeyMap());
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/dissoc16-16");
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
    @Tag("direct-linking-on")
    void shapeMap16DissocDemotesEndSlots() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context,
                    "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :k8 :v8}");

            Value first = evalWithDirectLinking(context, "test.guest.assoc-lowering/dissoc16-first");
            assertEquals("clojure.lang.PersistentShapeMap/8/false", first.execute(map).asString());
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "dissoc16-first", "KeywordDissoc"),
                    "doShapeMap16");

            Value last = evalWithDirectLinking(context, "test.guest.assoc-lowering/dissoc16-last");
            assertEquals("clojure.lang.PersistentShapeMap/8/false", last.execute(map).asString());
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "dissoc16-last", "KeywordDissoc"),
                    "doShapeMap16");
        }
    }

    /** Absent-key dissoc on a 9-key map is still a cached NoOpDissoc16Transition. */
    @Test
    @Tag("direct-linking-on")
    void shapeMap16AbsentDissocStaysOnTheCachedTransition() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context,
                    "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :k8 :v8}");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/dissoc16-absent");
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
    @Tag("direct-linking-on")
    void emptyMapDissocIsACachedNoOp() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/empty-dissoc");
            assertTrue(fn.execute().asBoolean());
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "empty-dissoc", "KeywordDissoc"),
                    "doShapeMap");
        }
    }

    @Test
    @Tag("direct-linking-on")
    void lastKeyDissocEmptiesAShapeMap() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context, "{:a 1}");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/last-key-dissoc");
            assertEquals("clojure.lang.PersistentShapeMap/0", fn.execute(map).asString());
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "last-key-dissoc", "KeywordDissoc"),
                    "doShapeMap");
        }
    }

    @Test
    @Tag("direct-linking-on")
    void absentKeyDissocOnShapeMapIsACachedNoOp() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context, "{:a :v1 :b :v2 :c :v3}");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/absent-dissoc");
            assertTrue(fn.execute(map).asBoolean());
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "absent-dissoc", "KeywordDissoc"),
                    "doShapeMap");
        }
    }

    @Test
    @Tag("direct-linking-on")
    void nilDissocUsesTheNullSpecialization() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/nil-dissoc");
            assertTrue(fn.execute().isNull());
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "nil-dissoc", "KeywordDissoc"),
                    "doNull");
        }
    }

    @Test
    @Tag("direct-linking-on")
    void exhaustingTheDissocTransitionCacheFallsBackWithoutLosingTheType() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/polymorphic-dissoc");
            String[] maps = {
                    "{:a 1 :b 2}",
                    "{:a 1 :b 2 :c 3}",
                    "{:a 1 :b 2 :d 4}",
                    "{:a 1 :b 2 :e 5}",
                    "{:a 1 :b 2 :f 6}",
            };
            for (String literal : maps) {
                Value map = evalWithDirectLinking(context, literal);
                assertEquals(1, fn.execute(map).asInt());
            }
            List<SpecializationInfo> all =
                    specializationsOf("test.guest.assoc-lowering", "polymorphic-dissoc", "KeywordDissoc");
            assertActive(all, "doShapeMapGeneric");
        }
    }

    @Test
    @Tag("direct-linking-on")
    void hashMapDissocUsesTheClassCache() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context, seventeenKeyMap());
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/hash-dissoc");
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
    @Tag("direct-linking-on")
    void eightKeyAssocPromotesToShapeMap16OnTheCachedTransition() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context,
                    "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7}");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/promote-assoc");
            assertEquals("clojure.lang.PersistentShapeMap16/9", fn.execute(map).asString());
            List<SpecializationInfo> all = keywordAssocSpecializations("test.guest.assoc-lowering", "promote-assoc");
            assertActive(all, "doShapeMap");
            assertInactive(all, "doShapeMapGeneric");
            assertInactive(all, "doAssociativeCached");
        }
    }

    @Test
    @Tag("direct-linking-on")
    void emptyMapAssocUsesTheCachedInsertTransition() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/empty-assoc");
            assertEquals("clojure.lang.PersistentShapeMap/1/1", fn.execute().asString());
            assertActive(
                    keywordAssocSpecializations("test.guest.assoc-lowering", "empty-assoc"),
                    "doShapeMap");
        }
    }

    @Test
    @Tag("direct-linking-on")
    void insertAssocAddsAKeyWithoutLeavingTheCachedTransition() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context, "{:a 1 :b 2 :c 3}");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/insert-assoc");
            assertEquals("clojure.lang.PersistentShapeMap/4", fn.execute(map).asString());
            assertActive(
                    keywordAssocSpecializations("test.guest.assoc-lowering", "insert-assoc"),
                    "doShapeMap");
        }
    }

    @Test
    @Tag("direct-linking-on")
    void nilAssocUsesTheNullSpecialization() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/nil-assoc");
            assertEquals(1, fn.execute().asInt());
            assertActive(
                    keywordAssocSpecializations("test.guest.assoc-lowering", "nil-assoc"),
                    "doNull");
        }
    }

    /** PersistentShapeMap16 new-key insert uses cached {@code Insert16Transition} on {@code doShapeMap16}. */
    @Test
    @Tag("direct-linking-on")
    void shapeMap16InsertUsesCachedTransition() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context,
                    "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :k8 :v8}");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/shape16-assoc");
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
    @Tag("direct-linking-on")
    void shapeMap16TwelveKeyInsertUsesCachedTransition() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context, twelveKeyMap());
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/shape16-12-assoc");
            assertEquals("clojure.lang.PersistentShapeMap16/13", fn.execute(map).asString());
            List<SpecializationInfo> all =
                    keywordAssocSpecializations("test.guest.assoc-lowering", "shape16-12-assoc");
            assertActive(all, "doShapeMap16");
            assertInactive(all, "doShapeMap16Generic");
        }
    }

    @Test
    @Tag("direct-linking-on")
    void sixteenKeyAssocPromotesToHashMapOnCachedTransition() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context, sixteenKeyMap());
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/shape16-16-assoc");
            assertEquals("clojure.lang.PersistentHashMap/17", fn.execute(map).asString());
            List<SpecializationInfo> all =
                    keywordAssocSpecializations("test.guest.assoc-lowering", "shape16-16-assoc");
            assertActive(all, "doShapeMap16");
            assertInactive(all, "doShapeMap16Generic");
            assertInactive(all, "doShapeMap");
        }
    }

    @Test
    @Tag("direct-linking-on")
    void shapeMap16RewriteStaysOnTheCachedTransition() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context, nineKeyMap());
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/shape16-rewrite");
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
    @Tag("direct-linking-on")
    void shapeMap16SixteenKeyRewriteStaysOnTheCachedTransition() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context, sixteenKeyMap());
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/shape16-16-rewrite");
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
    @Tag("direct-linking-on")
    void shapeMap16LiteralEmitsCreateMapShaped16() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/shape16-literal");
            for (int i = 0; i < 10; i++) {
                assertEquals("runtime", fn.execute("runtime").asString());
            }
            List<String> names = instructionNames("test.guest.assoc-lowering", "shape16-literal");
            assertTrue(
                    names.stream().anyMatch(name -> name.endsWith("CreateMapShaped16")),
                    "expected CreateMapShaped16, found " + names);
            assertTrue(
                    names.stream().noneMatch(name -> name.endsWith("CreateMapN")),
                    "CreateMapN must not be used for a 9-key keyword literal, found " + names);

            Value constant = evalWithDirectLinking(context, "test.guest.assoc-lowering/shape16-const");
            assertEquals(":v0", constant.execute().asString());
            List<String> constNames = instructionNames("test.guest.assoc-lowering", "shape16-const");
            assertTrue(
                    constNames.stream().anyMatch(name -> name.endsWith("CreateMapShaped16")),
                    "expected CreateMapShaped16 for a constant 16-key map, found " + constNames);
            assertTrue(
                    constNames.stream().noneMatch(name -> name.endsWith("CreateMapN")),
                    "CreateMapN must not be used for a constant 16-key map, found " + constNames);

            List<SpecializationInfo> lookups =
                    specializationsOf("test.guest.assoc-lowering", "shape16-literal", "KeywordLookup");
            assertActive(lookups, "doShapeMap16");
            assertInactive(lookups, "doILookupCached");
            assertInactive(lookups, "doShapeMap16Generic");
        }
    }

    @Test
    @Tag("direct-linking-on")
    void shapeMap16LiteralGetLowersToKeywordLookup() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context, nineKeyMap());
            Value get2 = evalWithDirectLinking(context, "test.guest.assoc-lowering/literal-get-16");
            for (int i = 0; i < 10; i++) {
                assertEquals(":v4", get2.execute(map).asString());
            }
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "literal-get-16", "KeywordLookup"),
                    "doShapeMap16");
            Value get3 = evalWithDirectLinking(context, "test.guest.assoc-lowering/literal-get-16-default");
            for (int i = 0; i < 10; i++) {
                assertEquals(":fallback", get3.execute(map).asString());
            }
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "literal-get-16-default", "KeywordLookupDefault"),
                    "doShapeMap16");
        }
    }

    @Test
    @Tag("direct-linking-on")
    void exhaustingTheShapeMap16RewriteCacheFallsBackWithoutLosingTheType() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/polymorphic-shape16-assoc");
            String[] maps = {
                    nineKeyMap(),
                    "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :a :va}",
                    "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :b :vb}",
                    "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :c :vc}",
                    "{:k0 :v0 :k1 :v1 :k2 :v2 :k3 :v3 :k4 :v4 :k5 :v5 :k6 :v6 :k7 :v7 :d :vd}",
            };
            for (String literal : maps) {
                Value map = evalWithDirectLinking(context, literal);
                assertEquals("v", fn.execute(map, "v").asString());
            }
            List<SpecializationInfo> all =
                    keywordAssocSpecializations("test.guest.assoc-lowering", "polymorphic-shape16-assoc");
            assertActive(all, "doShapeMap16Generic");
        }
    }

    @Test
    @Tag("direct-linking-on")
    void withRedefsDoesNotDivertShapeMap16Assoc() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context, nineKeyMap());
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/shape16-rewrite");
            assertEquals("before", fn.execute(map, "before").asString());

            Value redefined = evalWithDirectLinking(context,
                    "(str (with-redefs [assoc (fn [m k v] {:k4 :redefined})]"
                            + "       (test.guest.assoc-lowering/shape16-rewrite "
                            + nineKeyMap() + " :ignored)))");
            assertEquals(":ignored", redefined.asString(),
                    "under :direct-linking, with-redefs must not affect lowered assoc");

            List<SpecializationInfo> all =
                    keywordAssocSpecializations("test.guest.assoc-lowering", "shape16-rewrite");
            assertActive(all, "doShapeMap16");

            assertEquals("after", fn.execute(map, "after").asString());
        }
    }

    /** Under {@code :direct-linking}, lowered {@code dissoc} ignores {@code with-redefs}. */
    @Test
    @Tag("direct-linking-on")
    void withRedefsDoesNotDivertDissocLowering() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context, "{:a :v1 :b :v2 :c :v3}");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/stable-dissoc");
            assertEquals(":v1", fn.execute(map).asString());

            Value redefined = evalWithDirectLinking(context,
                    "(str (with-redefs [dissoc (fn [m k] {:a :redefined})]"
                            + "       (test.guest.assoc-lowering/stable-dissoc {:a :v1 :b :v2 :c :v3})))");
            assertEquals(":v1", redefined.asString(),
                    "under :direct-linking, with-redefs must not affect lowered dissoc");

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
    @Tag("direct-linking-on")
    void alterVarRootDoesNotDivertAssocLowering() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/stable-assoc");
            assertEquals("before", fn.execute("before").asString());

            Var assoc = RT.var("clojure.core", "assoc");
            Object orig = assoc.getRawRoot();
            try {
                evalWithDirectLinking(context,
                        "(alter-var-root #'clojure.core/assoc (constantly (fn [m k v] {:b :altered})))");
                Value altered = evalWithDirectLinking(context,
                        "(str (test.guest.assoc-lowering/stable-assoc :ignored))");
                assertEquals(":ignored", altered.asString(),
                        "under :direct-linking, alter-var-root must not affect lowered assoc");

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
    @Tag("direct-linking-on")
    void alterVarRootDoesNotDivertDissocLowering() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context, "{:a :v1 :b :v2 :c :v3}");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/stable-dissoc");
            assertEquals(":v1", fn.execute(map).asString());

            Var dissoc = RT.var("clojure.core", "dissoc");
            Object orig = dissoc.getRawRoot();
            try {
                evalWithDirectLinking(context,
                        "(alter-var-root #'clojure.core/dissoc (constantly (fn [m k] {:a :altered})))");
                Value altered = evalWithDirectLinking(context,
                        "(str (test.guest.assoc-lowering/stable-dissoc {:a :v1 :b :v2 :c :v3}))");
                assertEquals(":v1", altered.asString(),
                        "under :direct-linking, alter-var-root must not affect lowered dissoc");

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
    @Tag("direct-linking-on")
    void alterVarRootOnGetDoesNotDivertKeywordLookup() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context, "{:a :v1 :b :v2 :c :v3}");
            Value get2 = evalWithDirectLinking(context, "test.guest.assoc-lowering/literal-get");
            for (int i = 0; i < 10; i++) {
                assertEquals(":v2", get2.execute(map).asString());
            }
            assertActive(
                    specializationsOf("test.guest.assoc-lowering", "literal-get", "KeywordLookup"),
                    "doShapeMap");

            Var get = RT.var("clojure.core", "get");
            Object orig = get.getRawRoot();
            try {
                evalWithDirectLinking(context,
                        "(alter-var-root #'clojure.core/get (constantly (fn [& _] :altered)))");
                assertEquals(":v2", get2.execute(map).asString(),
                        "stock-inline-compatible get lowering must ignore the altered root");
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
    @Tag("direct-linking-on")
    void computedKeyDissocIsNotLowered() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context, "{:a :v1 :b :v2}");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/computed-dissoc");
            assertEquals(":v1", fn.execute(map, evalWithDirectLinking(context, ":b")).asString());

            assertTrue(
                    instructionNames("test.guest.assoc-lowering", "computed-dissoc").stream()
                            .noneMatch(name -> name.endsWith("KeywordDissoc")),
                    "(dissoc m k) with a computed key must not lower");
        }
    }

    /**
     * Multi-arity {@code (assoc m :a v :b v)} under direct linking unrolls into nested
     * {@code KeywordAssoc} (one per literal key pair), not a single Var applyTo.
     */
    @Test
    @Tag("direct-linking-on")
    void multiArityLiteralAssocUnrollsToNestedKeywordAssoc() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/multi-arity-assoc");
            for (int i = 0; i < 10; i++) {
                assertEquals("v", fn.execute("v").asString());
            }

            long keywordAssocCount = instructionNames("test.guest.assoc-lowering", "multi-arity-assoc").stream()
                    .filter(name -> name.endsWith("KeywordAssoc"))
                    .count();
            assertEquals(2, keywordAssocCount,
                    "expected two nested KeywordAssoc instructions for two literal key pairs");

            List<SpecializationInfo> all =
                    keywordAssocSpecializations("test.guest.assoc-lowering", "multi-arity-assoc");
            assertActive(all, "doShapeMap");
            assertInactive(all, "doShapeMapGeneric");
            assertInactive(all, "doAssociativeCached");
        }
    }

    /** A computed key among multi-arity pairs blocks unrolling; stay on the Var path. */
    @Test
    @Tag("direct-linking-on")
    void multiArityComputedKeyIsNotLowered() {
        try (Context context = createContext()) {
            loadGuest(context, "assoc-lowering");
            Value map = evalWithDirectLinking(context, "{:a :v1 :b :v2}");
            Value fn = evalWithDirectLinking(context, "test.guest.assoc-lowering/multi-arity-computed-key");
            assertEquals(2, fn.execute(map, evalWithDirectLinking(context, ":b"), 2).asInt());

            assertTrue(
                    instructionNames("test.guest.assoc-lowering", "multi-arity-computed-key").stream()
                            .noneMatch(name -> name.endsWith("KeywordAssoc")),
                    "(assoc m :a 1 k v) with a computed key must not lower");
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
    @Tag("direct-linking-on")
    void constantMapExprNestedHeadersEmitsCreateMapShaped() {
        try (Context context = createContext()) {
            evalWithDirectLinking(context, guestSource("const-map-shape"));
            assertEquals("text/plain",
                    evalWithDirectLinking(context,
                            "(:content-type (:headers (test.guest.const-map-shape/nested-const-headers \"x\")))")
                            .asString());
            List<String> names = instructionNames("test.guest.const-map-shape", "nested-const-headers");
            assertTrue(names.stream().anyMatch(n -> n.endsWith("CreateMapShaped2")),
                    "expected CreateMapShaped2 for headers, found " + names);
            assertTrue(names.stream().anyMatch(n -> n.endsWith("CreateMapShaped3")),
                    "expected CreateMapShaped3 for outer map, found " + names);
        }
    }

    @Test
    @Tag("direct-linking-on")
    void constantMapExprAllConstNestedEmitsCreateMapShaped() {
        try (Context context = createContext()) {
            evalWithDirectLinking(context, guestSource("const-map-shape"));
            evalWithDirectLinking(context, "test.guest.const-map-shape/all-const-nested");
            List<String> names = instructionNames("test.guest.const-map-shape", "all-const-nested");
            assertTrue(names.stream().anyMatch(n -> n.endsWith("CreateMapShaped1")),
                    "expected CreateMapShaped1 for single-key headers, found " + names);
            assertTrue(names.stream().anyMatch(n -> n.endsWith("CreateMapShaped3")),
                    "expected CreateMapShaped3 for outer map, found " + names);
            assertTrue(names.stream().noneMatch(n -> n.endsWith("CreateMap3")),
                    "must not use unshaped CreateMap3 for keyword constant nest, found " + names);
        }
    }

    @Test
    @Tag("direct-linking-on")
    void constantMapExprIntKeyDoesNotEmitCreateMapShaped() {
        try (Context context = createContext()) {
            evalWithDirectLinking(context, guestSource("const-map-shape"));
            assertEquals(":a", evalWithDirectLinking(context, "test.guest.const-map-shape/const-int-key").execute().asString());
            List<String> names = instructionNames("test.guest.const-map-shape", "const-int-key");
            assertTrue(names.stream().noneMatch(n -> n.contains("CreateMapShaped")),
                    "non-keyword constant map must not use CreateMapShaped*, found " + names);
        }
    }

    private static List<String> instructionNames(String namespace, String fnName) {
        Var var = Var.find(Symbol.intern(namespace, fnName));
        assertNotNull(var, "Var must exist: " + namespace + "/" + fnName);
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
