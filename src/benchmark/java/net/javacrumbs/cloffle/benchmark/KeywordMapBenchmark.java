package net.javacrumbs.cloffle.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import clojure.lang.IFn;
import clojure.lang.IMapEntry;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentShapeMap;
import clojure.lang.PersistentShapeMap16;
import clojure.lang.RT;
import org.graalvm.polyglot.Context;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Keyword / map microbenches. Assoc methods that take a {@code @State} field map
 * measure shared-update heap cost (result escapes). PEA claims belong on
 * {@link #shapeMap3EphemeralAssocThenLookup} (host) or {@link #guestShapeMapEphemeralPipeline}.
 * See GRAAL_GRAPH_ANALYSIS.md section 11.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Threads(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class KeywordMapBenchmark {

    private static final ThreadLocal<Map<String, Object>> CAPTURED_GUEST_VALUES =
            ThreadLocal.withInitial(HashMap::new);

    private Context context;
    private IFn arrayMapLookupFn;
    private IFn hashMapLookupFn;
    private IFn keywordInvokeFn;
    private IFn nestedGetInFn;
    private IFn assocFn;
    private IFn shape8PromoteFn;
    private IFn shape12LookupFn;
    private IFn assocPipeline12Fn;
    private IFn guestEphemeralPipelineFn;
    private IFn guestEphemeralInsertFn;
    private IFn guestEphemeralPromote8Fn;
    private IFn guestTupleDestructureFn;
    private IFn guestTuple2TransformFn;
    private IFn guestRingPipelineFn;
    private IFn guestHiccupNormalizeFn;
    private IFn guestKwargsDestructureFn;
    private IFn guestMiddlewarePipelineFn;
    private IFn guestCondOptionPipelineFn;
    private IFn guestEventEnrichPipelineFn;
    private IFn guestEphemeralDissocFn;
    private IFn guestEventSanitizePipelineFn;

    private Object smallM;
    private Object largeM;
    private Object nestedM;
    private Object shape12M;

    /** Compile-time-stable keywords for ephemeral ShapeMap PEA (not instance fields). */
    private static final Keyword PEA_A = Keyword.intern(null, "pea-a");
    private static final Keyword PEA_B = Keyword.intern(null, "pea-b");
    private static final Keyword PEA_C = Keyword.intern(null, "pea-c");
    private static final Keyword PEA_D = Keyword.intern(null, "pea-d");
    private static final Keyword PEA_K0 = Keyword.intern(null, "pea-k0");
    private static final Keyword PEA_K1 = Keyword.intern(null, "pea-k1");
    private static final Keyword PEA_K2 = Keyword.intern(null, "pea-k2");
    private static final Keyword PEA_K3 = Keyword.intern(null, "pea-k3");
    private static final Keyword PEA_K4 = Keyword.intern(null, "pea-k4");
    private static final Keyword PEA_K5 = Keyword.intern(null, "pea-k5");
    private static final Keyword PEA_K6 = Keyword.intern(null, "pea-k6");
    private static final Keyword PEA_K7 = Keyword.intern(null, "pea-k7");
    private static final Keyword PEA_K8 = Keyword.intern(null, "pea-k8");
    private static final Keyword PEA_K9 = Keyword.intern(null, "pea-k9");
    private static final Keyword PEA_E = Keyword.intern(null, "pea-e");

    /** Compilation-final 2→3 insert and 8→9 promote plans (same keys as the local ephemeral maps). */
    private static final PersistentShapeMap.AssocTransition PEA_INSERT_C =
            PersistentShapeMap.assocTransition(PersistentShapeMap.create(PEA_A, 1, PEA_B, 2), PEA_C);
    private static final PersistentShapeMap.AssocTransition PEA_PROMOTE_K8 =
            PersistentShapeMap.assocTransition(
                    PersistentShapeMap.create(PEA_K0, 0, PEA_K1, 1, PEA_K2, 2, PEA_K3, 3,
                            PEA_K4, 4, PEA_K5, 5, PEA_K6, 6, PEA_K7, 7),
                    PEA_K8);
    private static final PersistentShapeMap.DissocTransition PEA_DISSOC_B =
            PersistentShapeMap.dissocTransition(PersistentShapeMap.create(PEA_A, 1, PEA_B, 2, PEA_C, 3), PEA_B);

    private Keyword kwA;
    private Keyword kwB;
    private Keyword kwC;
    private Keyword kwK6;
    private Keyword kwAbsent;
    private clojure.lang.PersistentShapeMap shapeMap;
    private clojure.lang.PersistentShapeMap16 shapeMap16;
    private clojure.lang.PersistentHashMap hashMap12;
    private clojure.lang.PersistentArrayMap arrayMap3;
    private clojure.lang.PersistentArrayMap arrayMap8;
    private clojure.lang.PersistentShapeMap shapeMap8;

    /** Non-constant so insert/assoc cannot fold to {@code return 3}. */
    private int peaInsertVal = 3;

    /**
     * Setup-only bridge that lets guest code hand its raw JVM objects to the benchmark without
     * retaining a Polyglot Value wrapper in the timed path.
     */
    public static Object captureGuestValue(String name, Object value) {
        CAPTURED_GUEST_VALUES.get().put(name, value);
        return value;
    }

    private Object guestValue(String name) {
        context.eval("cloffle",
                "(net.javacrumbs.cloffle.benchmark.KeywordMapBenchmark/captureGuestValue "
                        + "\"" + name + "\" " + name + ")");
        Object value = CAPTURED_GUEST_VALUES.get().remove(name);
        if (value == null) {
            throw new IllegalStateException("Guest value was not captured: " + name);
        }
        return value;
    }

    private IFn guestFn(String name) {
        return (IFn) guestValue(name);
    }

    @Setup(Level.Trial)
    public void setup() {
        RT.init();
        context = Context.newBuilder("cloffle")
            .allowAllAccess(true)
            .build();

        kwA = Keyword.intern(null, "a");
        kwB = Keyword.intern(null, "b");
        kwC = Keyword.intern(null, "c");
        kwK6 = Keyword.intern(null, "k6");
        kwAbsent = Keyword.intern(null, "nonexistent-absent-key");
        shapeMap = (clojure.lang.PersistentShapeMap) clojure.lang.PersistentShapeMap.createWithCheck(new Object[]{kwA, 1, kwB, 2, kwC, 3});
        arrayMap3 = new clojure.lang.PersistentArrayMap(new Object[]{kwA, 1, kwB, 2, kwC, 3});

        Object[] init8 = new Object[16];
        for (int i = 0; i < 8; i++) {
            init8[i * 2] = Keyword.intern(null, "k" + i);
            init8[i * 2 + 1] = i;
        }
        shapeMap8 = (clojure.lang.PersistentShapeMap) clojure.lang.PersistentShapeMap.createWithCheck(init8);
        arrayMap8 = new clojure.lang.PersistentArrayMap(init8);

        Object[] init12 = new Object[24];
        for (int i = 0; i < 12; i++) {
            init12[i * 2] = Keyword.intern(null, "k" + i);
            init12[i * 2 + 1] = i;
        }
        shapeMap16 = (clojure.lang.PersistentShapeMap16) clojure.lang.PersistentShapeMap16.createWithCheck(init12);
        hashMap12 = clojure.lang.PersistentHashMap.create(null, init12);

        // Small map (PersistentArrayMap) lookup
        context.eval("cloffle", "(def small-m {:a 1 :b 2 :c 3})");
        smallM = guestValue("small-m");
        context.eval("cloffle", "(defn get-small [m] (get m :b))");
        arrayMapLookupFn = guestFn("get-small");

        // Large map (PersistentHashMap) lookup (> 16 keys)
        context.eval("cloffle", "(def large-m {:k0 0 :k1 1 :k2 2 :k3 3 :k4 4 :k5 5 :k6 6 :k7 7 :k8 8 :k9 9 :k10 10 :k11 11 :k12 12 :k13 13 :k14 14 :k15 15 :k16 16 :k17 17})");
        largeM = guestValue("large-m");
        context.eval("cloffle", "(defn get-large [m] (get m :k5))");
        hashMapLookupFn = guestFn("get-large");

        // 12-key ShapeMap16 in Cloffle
        context.eval("cloffle", "(def shape-m12 {:k0 0 :k1 1 :k2 2 :k3 3 :k4 4 :k5 5 :k6 6 :k7 7 :k8 8 :k9 9 :k10 10 :k11 11})");
        shape12M = guestValue("shape-m12");
        context.eval("cloffle", "(defn get-shape12 [m] (get m :k6))");
        shape12LookupFn = guestFn("get-shape12");

        // Keyword direct invocation (:k m)
        context.eval("cloffle", "(defn kw-invoke [m] (:b m))");
        keywordInvokeFn = guestFn("kw-invoke");

        // Nested lookup
        context.eval("cloffle", "(def nested-m {:user {:profile {:name \"Alice\"}}})");
        nestedM = guestValue("nested-m");
        context.eval("cloffle", "(defn get-in-nested [m] (get-in m [:user :profile :name]))");
        nestedGetInFn = guestFn("get-in-nested");

        // Assoc pipeline (3 keys)
        context.eval("cloffle", "(defn assoc-pipeline [m] (get (assoc m :status :active) :status))");
        assocFn = guestFn("assoc-pipeline");

        // Stable incoming 8-key ShapeMap -> direct cached ShapeMap16 promotion.
        context.eval("cloffle", "(defn shape8-promote [m v] (:transition-ninth (assoc m :transition-ninth v)))");
        shape8PromoteFn = guestFn("shape8-promote");

        // Assoc pipeline (12 keys -> 13 keys)
        context.eval("cloffle", "(defn assoc-pipe12 [m] (get (assoc m :status :active) :status))");
        assocPipeline12Fn = guestFn("assoc-pipe12");

        // Guest ephemeral ShapeMap pipeline (isolated guest compilation unit)
        context.eval("cloffle",
                "(defn guest-ephemeral-pipeline [x]\n" +
                "  (let [m {:a x :b 2 :c 3}]\n" +
                "    (:a (assoc m :a \"replacement\"))))");
        guestEphemeralPipelineFn = guestFn("guest-ephemeral-pipeline");

        context.eval("cloffle",
                "(defn guest-ephemeral-insert [x]\n" +
                "  (let [m {:a 1 :b 2}\n" +
                "        m2 (assoc m :c x)]\n" +
                "    (if (= (:a m2) 1)\n" +
                "      (:c m2)\n" +
                "      nil)))");
        guestEphemeralInsertFn = guestFn("guest-ephemeral-insert");

        context.eval("cloffle",
                "(defn guest-ephemeral-promote8 [x]\n" +
                "  (let [m {:p0 0 :p1 1 :p2 2 :p3 3 :p4 4 :p5 5 :p6 6 :p7 7}\n" +
                "        m2 (assoc m :p8 x)]\n" +
                "    (if (= (:p0 m2) 0)\n" +
                "      (:p8 m2)\n" +
                "      nil)))");
        guestEphemeralPromote8Fn = guestFn("guest-ephemeral-promote8");

        context.eval("cloffle",
                "(defn guest-tuple-destructure [x y]\n" +
                "  (let [[a b] [x y]]\n" +
                "    (if (= a x)\n" +
                "      b\n" +
                "      nil)))");
        guestTupleDestructureFn = guestFn("guest-tuple-destructure");

        context.eval("cloffle",
                "(defn guest-ring-pipeline [body]\n" +
                "  (let [resp {:status 200 :headers {:content-type \"text/plain\"} :body body}\n" +
                "        resp2 (assoc resp :headers (assoc (:headers resp) :server \"cloffle\"))\n" +
                "        resp3 (assoc resp2 :status 201)\n" +
                "        {:keys [status headers body]} resp3]\n" +
                "    (if (and (= status 201)\n" +
                "             (= (:server headers) \"cloffle\")\n" +
                "             (= (:content-type headers) \"text/plain\"))\n" +
                "      body\n" +
                "      nil)))");
        guestRingPipelineFn = guestFn("guest-ring-pipeline");

        context.eval("cloffle",
                "(defn guest-hiccup-normalize [tag-name content-str]\n" +
                "  (let [elem [tag-name {:class \"btn\" :href \"/home\"} content-str]\n" +
                "        t (nth elem 0)\n" +
                "        second-el (nth elem 1)\n" +
                "        attrs (if (instance? clojure.lang.IPersistentMap second-el) second-el nil)\n" +
                "        content (if (instance? clojure.lang.IPersistentMap second-el) (nth elem 2) second-el)\n" +
                "        norm [t attrs content]\n" +
                "        final-tag (nth norm 0)\n" +
                "        final-attrs (nth norm 1)\n" +
                "        final-content (nth norm 2)]\n" +
                "    (if (and (= final-tag tag-name)\n" +
                "             (= (:href final-attrs) \"/home\"))\n" +
                "      final-content\n" +
                "      nil)))");
        guestHiccupNormalizeFn = guestFn("guest-hiccup-normalize");

        context.eval("cloffle",
                "(defn guest-tuple2-transform [x y]\n" +
                "  (let [[a b] [x y]\n" +
                "        [c d] [b a]]\n" +
                "    c))");
        guestTuple2TransformFn = guestFn("guest-tuple2-transform");

        context.eval("cloffle",
                "(defn guest-kwargs-destructure [timeout]\n" +
                "  (let [opts {:method :post :timeout timeout}\n" +
                "        {:keys [method timeout] :or {method :get timeout 1000}} opts]\n" +
                "    (if (= method :post) timeout 0)))");
        guestKwargsDestructureFn = guestFn("guest-kwargs-destructure");

        context.eval("cloffle",
                "(defn guest-middleware-pipeline [raw-body]\n" +
                "  (let [req {:uri \"/api/data\" :request-method :post :headers {:content-type \"application/json\"} :body raw-body}\n" +
                "        req2 (assoc req :params {:query \"search\"})\n" +
                "        req3 (assoc req2 :session {:user \"alice\"})\n" +
                "        {:keys [uri request-method headers params session body]} req3]\n" +
                "    (if (and (= request-method :post)\n" +
                "             (= (:user session) \"alice\")\n" +
                "             (= (:query params) \"search\")\n" +
                "             (= (:content-type headers) \"application/json\"))\n" +
                "      body\n" +
                "      nil)))");
        guestMiddlewarePipelineFn = guestFn("guest-middleware-pipeline");

        context.eval("cloffle",
                "(defn guest-cond-option-pipeline [raw-timeout]\n" +
                "  (let [opts (-> {}\n" +
                "                 (cond-> true (assoc :id \"btn\"))\n" +
                "                 (cond-> true (assoc :role \"primary\"))\n" +
                "                 (cond-> true (assoc :href \"/submit\"))\n" +
                "                 (cond-> raw-timeout (assoc :timeout raw-timeout)))\n" +
                "        {:keys [id role href timeout]} opts]\n" +
                "    (if (and (= id \"btn\")\n" +
                "             (= role \"primary\")\n" +
                "             (= href \"/submit\"))\n" +
                "      timeout\n" +
                "      nil)))");
        guestCondOptionPipelineFn = guestFn("guest-cond-option-pipeline");

        context.eval("cloffle",
                "(defn guest-event-enrich-pipeline [payload-str]\n" +
                "  (let [event {:id 101 :type :auth :user \"alice\" :tenant \"org-1\"\n" +
                "               :ip \"127.0.0.1\" :status :ok :timestamp 1700000000 :version 1}\n" +
                "        enriched (assoc event :payload payload-str)\n" +
                "        {:keys [id status user payload]} enriched]\n" +
                "    (if (and (= id 101)\n" +
                "             (= status :ok)\n" +
                "             (= user \"alice\"))\n" +
                "      payload\n" +
                "      nil)))");
        guestEventEnrichPipelineFn = guestFn("guest-event-enrich-pipeline");

        context.eval("cloffle",
                "(defn guest-ephemeral-dissoc [x]\n" +
                "  (let [m {:a 1 :b x :c 3}\n" +
                "        m2 (dissoc m :b)]\n" +
                "    (if (= (:a m2) 1)\n" +
                "      (:c m2)\n" +
                "      nil)))");
        guestEphemeralDissocFn = guestFn("guest-ephemeral-dissoc");

        context.eval("cloffle",
                "(defn guest-event-sanitize-pipeline [token]\n" +
                "  (let [event {:id 101 :user \"alice\" :secret token :temp 999 :status :ok}\n" +
                "        sanitized (-> event (dissoc :secret) (dissoc :temp))\n" +
                "        {:keys [id user secret temp status]} sanitized]\n" +
                "    (if (and (= id 101)\n" +
                "             (= status :ok)\n" +
                "             (= user \"alice\")\n" +
                "             (nil? secret)\n" +
                "             (nil? temp))\n" +
                "      id\n" +
                "      nil)))");
        guestEventSanitizePipelineFn = guestFn("guest-event-sanitize-pipeline");

        // Keep the context entered so timed IFn.invoke calls bypass Polyglot Value.execute.
        context.enter();
        CAPTURED_GUEST_VALUES.remove();
    }

    private static PersistentShapeMap16 ephemeralShape9(int v0) {
        long m0 = PEA_K0.mask0 | PEA_K1.mask0 | PEA_K2.mask0 | PEA_K3.mask0 | PEA_K4.mask0
                | PEA_K5.mask0 | PEA_K6.mask0 | PEA_K7.mask0 | PEA_K8.mask0;
        long m1 = PEA_K0.mask1 | PEA_K1.mask1 | PEA_K2.mask1 | PEA_K3.mask1 | PEA_K4.mask1
                | PEA_K5.mask1 | PEA_K6.mask1 | PEA_K7.mask1 | PEA_K8.mask1;
        boolean high = PEA_K0.id >= 128 || PEA_K1.id >= 128 || PEA_K2.id >= 128 || PEA_K3.id >= 128
                || PEA_K4.id >= 128 || PEA_K5.id >= 128 || PEA_K6.id >= 128 || PEA_K7.id >= 128
                || PEA_K8.id >= 128;
        return new PersistentShapeMap16(null, 9, m0, m1, high,
                PEA_K0, v0, PEA_K1, 1, PEA_K2, 2, PEA_K3, 3, PEA_K4, 4, PEA_K5, 5, PEA_K6, 6, PEA_K7, 7, PEA_K8, 8,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (context != null) {
            context.leave();
            context.close();
        }
    }

    @Benchmark
    public boolean keywordIdEquals() {
        return kwA.id == kwB.id;
    }

    @Benchmark
    public Object arrayMapLookup() {
        return arrayMapLookupFn.invoke(smallM);
    }

    @Benchmark
    public Object hashMapLookup() {
        return hashMapLookupFn.invoke(largeM);
    }

    @Benchmark
    public Object keywordDirectInvoke() {
        return keywordInvokeFn.invoke(smallM);
    }

    @Benchmark
    public Object nestedGetIn() {
        return nestedGetInFn.invoke(nestedM);
    }

    /** Guest assoc on a shared map ({@code smallM}); result escapes. Shared-update cost. */
    @Benchmark
    public Object assocPipeline() {
        return assocFn.invoke(smallM);
    }

    /** Stable shared 8-key ShapeMap input; result is consumed after cached 8->9 promotion. */
    @Benchmark
    public Object guestShapeMap8Promote() {
        return shape8PromoteFn.invoke(shapeMap8, peaInsertVal);
    }

    @Benchmark
    public Object arrayMap3DirectValAtPresent() {
        return arrayMap3.valAt(kwB);
    }

    @Benchmark
    public Object arrayMap3DirectValAtAbsent() {
        return arrayMap3.valAt(kwAbsent);
    }

    @Benchmark
    public Object shapeMapDirectValAtPresent() {
        return shapeMap.valAt(kwB);
    }

    @Benchmark
    public Object shapeMapDirectValAtAbsent() {
        return shapeMap.valAt(kwAbsent);
    }

    @Benchmark
    public Object arrayMap8DirectValAtPresent() {
        return arrayMap8.valAt(kwK6);
    }

    @Benchmark
    public Object arrayMap8DirectValAtAbsent() {
        return arrayMap8.valAt(kwAbsent);
    }

    @Benchmark
    public Object shapeMap8DirectValAtPresent() {
        return shapeMap8.valAt(kwK6);
    }

    @Benchmark
    public Object shapeMap8DirectValAtAbsent() {
        return shapeMap8.valAt(kwAbsent);
    }

    /** Shared ArrayMap field; assoc result escapes. Measures heap update cost, not PEA. */
    @Benchmark
    public Object arrayMap3DirectAssoc() {
        return arrayMap3.assoc(kwA, 999);
    }

    /** Shared ShapeMap field; assoc result escapes. Measures heap update cost, not PEA. */
    @Benchmark
    public Object shapeMap3DirectAssoc() {
        return shapeMap.assoc(kwA, 999);
    }

    /**
     * Opaque instance-field map and key. Cold assoc arms stay live and PEA commits
     * (~128 B/op). Negative control for scalar replacement, not a PEA success claim.
     */
    @Benchmark
    public Object shapeMap3DirectAssocThenLookup() {
        return shapeMap.assoc(kwA, 999).valAt(kwA);
    }

    /**
     * Host PEA success: local create, static-final keywords, consume as int.
     * Graal prunes demote/insert/promote and folds to {@code return 999} (~0 B/op).
     */
    @Benchmark
    public int shapeMap3EphemeralAssocThenLookup() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, 1, PEA_B, 2, PEA_C, 3);
        PersistentShapeMap updated = (PersistentShapeMap) m.assoc(PEA_A, 999);
        return ((Integer) updated.valAt(PEA_A)).intValue();
    }

    /** Host PEA: local create + valAt only (no assoc). */
    @Benchmark
    public int shapeMap3EphemeralValAtOnly() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, 1, PEA_B, 2, PEA_C, 3);
        return ((Integer) m.valAt(PEA_A)).intValue();
    }

    /**
     * New-key insert (not existing-key rewrite). Unrolled field ctor; host PEA target (~0 B/op).
     */
    @Benchmark
    public int shapeMap3EphemeralInsertThenLookup() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, 1, PEA_B, 2);
        return ((Integer) m.assoc(PEA_C, peaInsertVal).valAt(PEA_C)).intValue();
    }

    /**
     * Host PEA of {@code AssocTransition.apply} insert (2→3), using a compilation-final plan.
     * Does not go through {@code PersistentShapeMap.assoc}.
     */
    @Benchmark
    public int shapeMap2EphemeralTransitionInsertThenLookup() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, 1, PEA_B, 2);
        return ((Integer) PEA_INSERT_C.apply(m, peaInsertVal).valAt(PEA_C)).intValue();
    }

    /**
     * Host PEA of {@code DissocTransition.apply} remove (3→2), using a compilation-final plan.
     */
    @Benchmark
    public int shapeMap3EphemeralTransitionDissocThenLookup() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, 1, PEA_B, peaInsertVal, PEA_C, 3);
        return ((Integer) PEA_DISSOC_B.apply(m).valAt(PEA_C)).intValue();
    }

    /**
     * Host PEA of {@code Promote16Transition.apply} (8→9) without {@code @TruffleBoundary assocPromote16}.
     */
    @Benchmark
    public int shapeMap8EphemeralTransitionPromoteThenLookup() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_K0, 1, PEA_K1, 1, PEA_K2, 2, PEA_K3, 3,
                PEA_K4, 4, PEA_K5, 5, PEA_K6, 6, PEA_K7, 7);
        return ((Integer) PEA_PROMOTE_K8.apply(m, peaInsertVal).valAt(PEA_K8)).intValue();
    }

    /** Array clone on assoc; expect allocation even with local create. */
    @Benchmark
    public int arrayMap3EphemeralAssocThenLookup() {
        PersistentArrayMap m = new PersistentArrayMap(new Object[]{PEA_A, 1, PEA_B, 2, PEA_C, 3});
        return ((Integer) m.assoc(PEA_A, peaInsertVal).valAt(PEA_A)).intValue();
    }

    /** Keyword-as-IFn vs {@code valAt} on an ephemeral ShapeMap. */
    @Benchmark
    public int shapeMap3EphemeralKeywordInvoke() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, 1, PEA_B, 2, PEA_C, 3);
        return ((Integer) PEA_A.invoke(m)).intValue();
    }

    /** Nested virtual maps: outer valAt then inner valAt, consume as int. */
    @Benchmark
    public int shapeMap3EphemeralNestedValAt() {
        PersistentShapeMap inner = PersistentShapeMap.create(PEA_C, 42);
        PersistentShapeMap outer = PersistentShapeMap.create(PEA_A, inner, PEA_B, 1);
        return ((Integer) ((PersistentShapeMap) outer.valAt(PEA_A)).valAt(PEA_C)).intValue();
    }

    /** Host PEA: local create + without + valAt (unrolled field shift). */
    @Benchmark
    public int shapeMap3EphemeralWithoutThenLookup() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, 1, PEA_B, 2, PEA_C, 3);
        return ((Integer) m.without(PEA_B).valAt(PEA_A)).intValue();
    }

    /**
     * Negative control: {@code seq} of MapEntry objects. Must allocate; not a PEA claim.
     */
    @Benchmark
    public int shapeMap3EphemeralSeqSum() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, 1, PEA_B, 2, PEA_C, 3);
        int sum = 0;
        for (ISeq s = m.seq(); s != null; s = s.next()) {
            sum += ((Integer) ((IMapEntry) s.first()).val()).intValue();
        }
        return sum;
    }

    /**
     * Opportunity 3: Ephemeral ShapeMap3 kvreduce via unrolled field access.
     */
    @Benchmark
    public int shapeMap3EphemeralKvReduce() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, 1, PEA_B, 2, PEA_C, 3);
        Object res = m.kvreduce(new clojure.lang.AFn() {
            @Override
            public Object invoke(Object acc, Object k, Object v) {
                return ((Integer) acc) + ((Integer) v);
            }
        }, 0);
        return ((Integer) res).intValue();
    }

    /**
     * Opportunity 3: Ephemeral ShapeMap3 reduce with MapEntry scalar replacement.
     */
    @Benchmark
    public int shapeMap3EphemeralReduce() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, 1, PEA_B, 2, PEA_C, 3);
        Object res = m.reduce(new clojure.lang.AFn() {
            @Override
            public Object invoke(Object acc, Object entry) {
                return ((Integer) acc) + ((Integer) ((clojure.lang.IMapEntry) entry).val());
            }
        }, 0);
        return ((Integer) res).intValue();
    }

    /** ShapeMap16 existing-key assoc + lookup via local ctor (no createWithCheck arrays). */
    @Benchmark
    public int shapeMap16EphemeralAssocThenLookup() {
        PersistentShapeMap16 m = ephemeralShape9(1);
        PersistentShapeMap16 updated = (PersistentShapeMap16) m.assoc(PEA_K0, 999);
        return ((Integer) updated.valAt(PEA_K0)).intValue();
    }

    /** Host PEA: ShapeMap16 new-key insert via unrolled field ctor. */
    @Benchmark
    public int shapeMap16EphemeralInsertThenLookup() {
        PersistentShapeMap16 m = ephemeralShape9(1);
        return ((Integer) m.assoc(PEA_K9, peaInsertVal).valAt(PEA_K9)).intValue();
    }

    /** Host PEA: 5-key ShapeMap create + valAt. */
    @Benchmark
    public int shapeMap5EphemeralValAtOnly() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, 1, PEA_B, 2, PEA_C, 3, PEA_D, 4, PEA_E, 5);
        return ((Integer) m.valAt(PEA_C)).intValue();
    }

    @Benchmark
    public Object shapeMap16DirectValAtPresent() {
        return shapeMap16.valAt(kwK6);
    }

    @Benchmark
    public Object shapeMap16DirectValAtAbsent() {
        return shapeMap16.valAt(kwAbsent);
    }

    @Benchmark
    public Object hashMap12DirectValAtPresent() {
        return hashMap12.valAt(kwK6);
    }

    @Benchmark
    public Object hashMap12DirectValAtAbsent() {
        return hashMap12.valAt(kwAbsent);
    }

    @Benchmark
    public Object shapeMap16ClojureLookup() {
        return shape12LookupFn.invoke(shape12M);
    }

    /** Guest assoc on shared {@code shape12M} (12→13 keys). Shared-update cost, not PEA. */
    @Benchmark
    public Object assocPipeline12() {
        return assocPipeline12Fn.invoke(shape12M);
    }

    /** Guest compilation unit: map is created inside the fn, not a shared field. PEA candidate. */
    @Benchmark
    public Object guestShapeMapEphemeralPipeline() {
        return guestEphemeralPipelineFn.invoke("initial");
    }

    /**
     * Guest new-key insert ({@code {:a 1 :b 2}} then {@code (assoc m :c x)}), consume as int.
     * Host insert is 0 B/op; this checks KeywordAssoc / guest compilation after the unroll.
     */
    @Benchmark
    public Object guestShapeMapEphemeralInsert() {
        return guestEphemeralInsertFn.invoke(3);
    }

    /**
     * Guest local 8-key ShapeMap then {@code (assoc m :p8 x)} consumed as a scalar.
     * Exercises {@code KeywordAssoc} {@code Promote16Transition} inside one compilation unit.
     */
    @Benchmark
    public Object guestShapeMapEphemeralPromote8() {
        return guestEphemeralPromote8Fn.invoke(peaInsertVal);
    }

    /** Guest {@code (let [[a b] [x y]] (+ a b))}; PEA candidate, not a returned vector. */
    @Benchmark
    public Object guestTupleDestructure() {
        return guestTupleDestructureFn.invoke(2, 3);
    }

    /**
     * Opportunity 1: Canonical Ring response map literal + middleware header assoc + destructuring.
     * Maps and intermediate maps are purely ephemeral and should be scalar replaced (0 B/op).
     */
    @Benchmark
    public Object guestRingResponsePipeline() {
        return guestRingPipelineFn.invoke("ok");
    }

    /**
     * Opportunity 2: Hiccup tag vector + attr map normalization and destructuring.
     * PersistentTuple3 and PersistentShapeMap are virtualized (0 B/op).
     */
    @Benchmark
    public Object guestHiccupNormalizeTag() {
        return guestHiccupNormalizeFn.invoke("a", "click");
    }

    /**
     * Opportunity 6: 2-element vector pair swapping and transformation.
     * PersistentTuple2 pairs virtualized into CPU registers (0 B/op).
     */
    @Benchmark
    public Object guestTuple2Transform() {
        return guestTuple2TransformFn.invoke(2, 3);
    }

    /**
     * Opportunity 4: Keyword arguments destructuring lowering to PersistentShapeMap.
     * ShapeMap virtualized into CPU registers (0 B/op).
     */
    @Benchmark
    public Object guestKwargsDestructure() {
        return guestKwargsDestructureFn.invoke(500);
    }

    /**
     * Opportunity 5: Ephemeral intermediate middleware request maps and nested maps PEA.
     * Request map, header map, params map, and session map virtualized into CPU registers (0 B/op).
     */
    @Benchmark
    public Object guestMiddlewarePipeline() {
        return guestMiddlewarePipelineFn.invoke("test-payload");
    }

    /**
     * Opportunity 9: cond-> and -> option map accumulator PEA.
     * Starts from PersistentShapeMap.EMPTY and accumulates options via unrolled assoc.
     * All intermediate maps and final destructured map virtualized into CPU registers (0 B/op).
     */
    @Benchmark
    public Object guestCondOptionPipeline() {
        return guestCondOptionPipelineFn.invoke("500");
    }

    /**
     * Opportunity 10: Event enrichment & 8->9 ShapeMap16 transition promotion PEA.
     * Starts from an 8-key ShapeMap, appends a 9th keyword, and destructures fields.
     * PersistentShapeMap and PersistentShapeMap16 are completely virtualized (0 B/op).
     */
    @Benchmark
    public Object guestEventEnrichPipeline() {
        return guestEventEnrichPipelineFn.invoke("ok");
    }

    /**
     * Guest local 3-key ShapeMap then {@code (dissoc m :b)} consumed as a scalar.
     * Exercises {@code KeywordDissoc} {@code RemoveTransition} inside one compilation unit.
     */
    @Benchmark
    public Object guestShapeMapEphemeralDissoc() {
        return guestEphemeralDissocFn.invoke(peaInsertVal);
    }

    /**
     * Sanitization pipeline PEA: Chained dissocs on an ephemeral event map.
     * Eliminates intermediate maps and scalar replaces remaining fields.
     */
    @Benchmark
    public Object guestEventSanitizePipeline() {
        return guestEventSanitizePipelineFn.invoke("secret-token");
    }

}
