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
    public void testCachedShapeMapAssocAndPromotionInGuestCode() {
        try (Context context = createContext(true)) {
            context.eval("cloffle",
                    "(ns test.guest.assoc-transition)\n" +
                    "(defn cached-incoming-assoc [m v]\n" +
                    "  (let [updated (assoc m :transition-added v)]\n" +
                    "    [(:transition-added updated)\n" +
                    "     (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))\n" +
                    "(defn promote-eight [v]\n" +
                    "  (let [updated (assoc {:p0 0 :p1 1 :p2 2 :p3 3 :p4 4 :p5 5 :p6 6 :p7 7}\n" +
                    "                       :transition-ninth v)]\n" +
                    "    [(:transition-ninth updated)\n" +
                    "     (count updated)\n" +
                    "     (instance? clojure.lang.PersistentShapeMap16 updated)\n" +
                    "     (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))\n"
            );

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
        }
    }

    @Test
    public void testEphemeralPromote8ReturnsScalarInCompiledCode() {
        try (Context context = createContext(true)) {
            context.eval("cloffle",
                    "(ns test.guest.assoc-pea)\n" +
                    "(defn guest-ephemeral-promote8 [x]\n" +
                    "  (let [m {:p0 0 :p1 1 :p2 2 :p3 3 :p4 4 :p5 5 :p6 6 :p7 7}\n" +
                    "        m2 (assoc m :p8 x)]\n" +
                    "    (if (identical? (:p0 m2) 0)\n" +
                    "      [(:p8 m2) (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]\n" +
                    "      nil)))\n"
            );
            Value fn = context.eval("cloffle", "test.guest.assoc-pea/guest-ephemeral-promote8");
            fn.execute(3);
            Value res = fn.execute(9);
            assertEquals(9L, res.getArrayElement(0).asLong());
            assertTrue("Expected ephemeral 8->9 assoc in compiled code", res.getArrayElement(1).asBoolean());
        }
    }

    @Test
    public void testEventEnrichPipelineReturnsScalarInCompiledCode() {
        try (Context context = createContext(true)) {
            context.eval("cloffle",
                    "(ns test.guest.event-enrich)\n" +
                    "(defn guest-event-enrich-pipeline [payload-str]\n" +
                    "  (let [event {:id 101 :type :auth :user \"alice\" :tenant \"org-1\"\n" +
                    "               :ip \"127.0.0.1\" :status :ok :timestamp 1700000000 :version 1}\n" +
                    "        enriched (assoc event :payload payload-str)\n" +
                    "        {:keys [id status user payload]} enriched]\n" +
                    "    (if (and (identical? id 101)\n" +
                    "             (identical? status :ok)\n" +
                    "             (identical? user \"alice\"))\n" +
                    "      [payload (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]\n" +
                    "      nil)))\n"
            );
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
            context.eval("cloffle",
                    "(ns test.guest.dissoc-transition)\n" +
                    "(defn cached-incoming-dissoc [m]\n" +
                    "  (let [updated (dissoc m :b)]\n" +
                    "    [(:b updated)\n" +
                    "     (:a updated)\n" +
                    "     (:c updated)\n" +
                    "     (count updated)\n" +
                    "     (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))\n" +
                    "(defn multi-step-dissoc [m]\n" +
                    "  (let [updated (-> m (dissoc :c) (dissoc :a))]\n" +
                    "    [(:a updated)\n" +
                    "     (:b updated)\n" +
                    "     (:c updated)\n" +
                    "     (count updated)\n" +
                    "     (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))\n" +
                    "(defn demote-nine [m]\n" +
                    "  (let [updated (dissoc m :p8)]\n" +
                    "    [(:p8 updated)\n" +
                    "     (count updated)\n" +
                    "     (instance? clojure.lang.PersistentShapeMap updated)\n" +
                    "     (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))\n"
            );

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
            context.eval("cloffle",
                    "(ns test.guest.dissoc-pea)\n" +
                    "(defn guest-ephemeral-dissoc [x]\n" +
                    "  (let [m {:a 1 :b x :c 3}\n" +
                    "        m2 (dissoc m :b)]\n" +
                    "    (if (identical? (:a m2) 1)\n" +
                    "      [(:c m2) (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]\n" +
                    "      nil)))\n"
            );
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
            context.eval("cloffle",
                    "(ns test.guest.event-sanitize)\n" +
                    "(defn guest-event-sanitize-pipeline [token]\n" +
                    "  (let [event {:id 101 :user \"alice\" :secret token :temp 999 :status :ok}\n" +
                    "        sanitized (-> event (dissoc :secret) (dissoc :temp))\n" +
                    "        {:keys [id user secret temp status]} sanitized]\n" +
                    "    (if (and (identical? id 101)\n" +
                    "             (identical? status :ok)\n" +
                    "             (identical? user \"alice\")\n" +
                    "             (nil? secret)\n" +
                    "             (nil? temp))\n" +
                    "      [id (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]\n" +
                    "      nil)))\n"
            );
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

    @Test
    public void testCondOptionPipeline() {
        try (Context context = createContext(true)) {
            context.eval("cloffle",
                    "(ns test.guest.cond)\n" +
                    "(defn guest-cond-options [id cls href timeout]\n" +
                    "  (let [opts (cond-> {}\n" +
                    "               id (assoc :id id)\n" +
                    "               cls (assoc :class cls)\n" +
                    "               href (assoc :href href)\n" +
                    "               timeout (assoc :timeout timeout))\n" +
                    "        {:keys [id class href timeout]} opts]\n" +
                    "    (if (and (identical? id \"btn\")\n" +
                    "             (identical? class \"primary\")\n" +
                    "             (identical? href \"/submit\"))\n" +
                    "      [timeout (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]\n" +
                    "      nil)))\n"
            );

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
            context.eval("cloffle",
                    "(ns test.guest.get-in)\n" +
                    "(defn nested []\n" +
                    "  [(get-in {:user {:profile {:name \"Alice\"}}} [:user :profile :name])\n" +
                    "   (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)])\n"
            );

            Value fn = context.eval("cloffle", "test.guest.get-in/nested");
            fn.execute();
            Value res = fn.execute();
            assertEquals("Alice", res.getArrayElement(0).asString());
            assertTrue("Expected execution in compiled code", res.getArrayElement(1).asBoolean());
        }
    }

    @Test
    public void testZeroAllocationKeywordFieldNames() {
        try (Context context = createContext(true)) {
            context.eval("cloffle",
                    "(ns test.guest.field-names)\n" +
                    "(defn cheshire-fn [k]\n" +
                    "  [(if (keyword? k) (.substring (str k) 1) (str k))\n" +
                    "   (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)])\n" +
                    "(defn cheshire-instance-fn [k]\n" +
                    "  [(if (instance? clojure.lang.Keyword k) (.substring (str k) 1) (str k))\n" +
                    "   (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)])\n" +
                    "(defn substring-str-fn [k]\n" +
                    "  [(.substring (str k) 1)\n" +
                    "   (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)])\n" +
                    "(defn core-name-fn [x]\n" +
                    "  [(name x)\n" +
                    "   (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)])\n" +
                    "(defn core-namespace-fn [x]\n" +
                    "  [(namespace x)\n" +
                    "   (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)])\n" +
                    "(defn core-str1-fn [x]\n" +
                    "  [(str x)\n" +
                    "   (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)])\n"
            );

            // Test cheshire pattern (if (keyword? k) (.substring (str k) 1) (str k))
            Value cheshireFn = context.eval("cloffle", "test.guest.field-names/cheshire-fn");
            cheshireFn.execute(RT.keyword(null, "foo"));
            Value rKw = cheshireFn.execute(RT.keyword(null, "foo"));
            assertEquals("foo", rKw.getArrayElement(0).asString());
            assertTrue("Expected inCompiledCode", rKw.getArrayElement(1).asBoolean());

            Value rNsKw = cheshireFn.execute(RT.keyword("user", "name"));
            assertEquals("user/name", rNsKw.getArrayElement(0).asString());

            Value rStr = cheshireFn.execute("custom_field");
            assertEquals("custom_field", rStr.getArrayElement(0).asString());

            // Test cheshire instance? pattern
            Value cheshireInstFn = context.eval("cloffle", "test.guest.field-names/cheshire-instance-fn");
            cheshireInstFn.execute(RT.keyword(null, "bar"));
            Value rInstKw = cheshireInstFn.execute(RT.keyword(null, "bar"));
            assertEquals("bar", rInstKw.getArrayElement(0).asString());
            assertTrue("Expected inCompiledCode", rInstKw.getArrayElement(1).asBoolean());

            Value rInstNsKw = cheshireInstFn.execute(RT.keyword("test", "field"));
            assertEquals("test/field", rInstNsKw.getArrayElement(0).asString());

            Value rInstStr = cheshireInstFn.execute("raw_str");
            assertEquals("raw_str", rInstStr.getArrayElement(0).asString());

            // Test direct (.substring (str k) 1)
            Value substrFn = context.eval("cloffle", "test.guest.field-names/substring-str-fn");
            substrFn.execute(RT.keyword(null, "baz"));
            Value rSubstrKw = substrFn.execute(RT.keyword(null, "baz"));
            assertEquals("baz", rSubstrKw.getArrayElement(0).asString());
            assertTrue("Expected inCompiledCode", rSubstrKw.getArrayElement(1).asBoolean());

            Value rSubstrNsKw = substrFn.execute(RT.keyword("order", "id"));
            assertEquals("order/id", rSubstrNsKw.getArrayElement(0).asString());

            Value rSubstrStr = substrFn.execute("hello");
            assertEquals("ello", rSubstrStr.getArrayElement(0).asString());

            // Test core name
            Value nameFn = context.eval("cloffle", "test.guest.field-names/core-name-fn");
            nameFn.execute(RT.keyword(null, "alpha"));
            Value rNameKw = nameFn.execute(RT.keyword(null, "alpha"));
            assertEquals("alpha", rNameKw.getArrayElement(0).asString());
            assertTrue("Expected inCompiledCode", rNameKw.getArrayElement(1).asBoolean());

            Value rNameNsKw = nameFn.execute(RT.keyword("user", "alpha"));
            assertEquals("alpha", rNameNsKw.getArrayElement(0).asString());

            Value rNameSym = nameFn.execute(Symbol.intern(null, "my-sym"));
            assertEquals("my-sym", rNameSym.getArrayElement(0).asString());

            Value rNameStr = nameFn.execute("plain-str");
            assertEquals("plain-str", rNameStr.getArrayElement(0).asString());

            // Test core namespace
            Value nsFn = context.eval("cloffle", "test.guest.field-names/core-namespace-fn");
            nsFn.execute(RT.keyword("user", "email"));
            Value rNs1 = nsFn.execute(RT.keyword("user", "email"));
            assertEquals("user", rNs1.getArrayElement(0).asString());
            assertTrue("Expected inCompiledCode", rNs1.getArrayElement(1).asBoolean());

            Value rNs2 = nsFn.execute(RT.keyword(null, "unnamespaced"));
            assertTrue("Expected nil namespace", rNs2.getArrayElement(0).isNull());

            // Test core str1
            Value str1Fn = context.eval("cloffle", "test.guest.field-names/core-str1-fn");
            str1Fn.execute(RT.keyword(null, "status"));
            Value rStrKw = str1Fn.execute(RT.keyword(null, "status"));
            assertEquals(":status", rStrKw.getArrayElement(0).asString());
            assertTrue("Expected inCompiledCode", rStrKw.getArrayElement(1).asBoolean());

            Value rStrStr = str1Fn.execute("hello-world");
            assertEquals("hello-world", rStrStr.getArrayElement(0).asString());

            Value rStrNil = context.eval("cloffle", "(test.guest.field-names/core-str1-fn nil)");
            assertEquals("", rStrNil.getArrayElement(0).asString());
        }
    }

    @Test
    public void testFixedArityStrInCompiledCode() {
        try (Context context = createContext(true)) {
            context.eval("cloffle",
                    "(ns test.guest.fixed-str)\n" +
                    "(defn str2-fn [a b]\n" +
                    "  [(str a b)\n" +
                    "   (string? (str a b))\n" +
                    "   (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)])\n" +
                    "(defn str3-fn [a b c]\n" +
                    "  [(str a b c)\n" +
                    "   (string? (str a b c))\n" +
                    "   (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)])\n" +
                    "(defn fixed-cases-a []\n" +
                    "  [(str nil nil)\n" +
                    "   (str nil 1)\n" +
                    "   (str 1 nil)\n" +
                    "   (str nil \"b\" \"c\")\n" +
                    "   (str \"a\" nil \"c\")\n" +
                    "   (str \"a\" \"b\" nil)\n" +
                    "   (str :left :right)\n" +
                    "   (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)])\n" +
                    "(defn fixed-cases-b []\n" +
                    "  [(str 'left \"-right\")\n" +
                    "   (str \\a \\b)\n" +
                    "   (str (int 12) (long 34))\n" +
                    "   (str)\n" +
                    "   (str \"one\")\n" +
                    "   (apply str [\"a\" \"b\"])\n" +
                    "   (str \"a\" \"b\" \"c\" \"d\")\n" +
                    "   (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)])\n"
            );

            Value str2 = context.eval("cloffle", "test.guest.fixed-str/str2-fn");
            str2.execute("a", "b");
            Value str2Result = str2.execute("a", "b");
            assertEquals("ab", str2Result.getArrayElement(0).asString());
            assertTrue(str2Result.getArrayElement(1).asBoolean());
            assertTrue("Expected str2 execution in compiled code",
                    str2Result.getArrayElement(2).asBoolean());

            Value keywordResult = str2.execute(RT.keyword(null, "left"), RT.keyword(null, "right"));
            assertEquals(":left:right", keywordResult.getArrayElement(0).asString());
            Value symbolResult = str2.execute(Symbol.intern(null, "left"), "-right");
            assertEquals("left-right", symbolResult.getArrayElement(0).asString());
            Value characterResult = str2.execute(Character.valueOf('a'), Character.valueOf('b'));
            assertEquals("ab", characterResult.getArrayElement(0).asString());
            Value boxedResult = str2.execute(Integer.valueOf(12), Long.valueOf(34));
            assertEquals("1234", boxedResult.getArrayElement(0).asString());

            Value str3 = context.eval("cloffle", "test.guest.fixed-str/str3-fn");
            str3.execute("a", "b", "c");
            Value str3Result = str3.execute("a", "b", "c");
            assertEquals("abc", str3Result.getArrayElement(0).asString());
            assertTrue(str3Result.getArrayElement(1).asBoolean());
            assertTrue("Expected str3 execution in compiled code",
                    str3Result.getArrayElement(2).asBoolean());

            Value fixedCasesA = context.eval("cloffle", "test.guest.fixed-str/fixed-cases-a");
            fixedCasesA.execute();
            Value casesA = fixedCasesA.execute();
            String[] expectedA = {"", "1", "1", "bc", "ac", "ab", ":left:right"};
            for (int i = 0; i < expectedA.length; i++) {
                assertEquals("fixed str case A" + i, expectedA[i], casesA.getArrayElement(i).asString());
            }
            assertTrue("Expected fixed cases A execution in compiled code",
                    casesA.getArrayElement(expectedA.length).asBoolean());

            Value fixedCasesB = context.eval("cloffle", "test.guest.fixed-str/fixed-cases-b");
            fixedCasesB.execute();
            Value casesB = fixedCasesB.execute();
            String[] expectedB = {"left-right", "ab", "1234", "", "one", "ab", "abcd"};
            for (int i = 0; i < expectedB.length; i++) {
                assertEquals("fixed str case B" + i, expectedB[i], casesB.getArrayElement(i).asString());
            }
            assertTrue("Expected fixed cases B execution in compiled code",
                    casesB.getArrayElement(expectedB.length).asBoolean());
        }
    }
}
