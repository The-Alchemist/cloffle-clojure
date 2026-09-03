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
import org.openjdk.jmh.annotations.Warmup;

import clojure.lang.IMapEntry;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentShapeMap;
import clojure.lang.PersistentShapeMap16;
import clojure.lang.RT;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

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
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class KeywordMapBenchmark {

    private Context context;
    private Value arrayMapLookupFn;
    private Value hashMapLookupFn;
    private Value keywordInvokeFn;
    private Value nestedGetInFn;
    private Value assocFn;
    private Value shape12LookupFn;
    private Value assocPipeline12Fn;
    private Value guestEphemeralPipelineFn;
    private Value guestTupleDestructureFn;

    private Value smallM;
    private Value largeM;
    private Value nestedM;
    private Value shape12M;

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
        smallM = context.eval("cloffle", "small-m");
        context.eval("cloffle", "(defn get-small [m] (get m :b))");
        arrayMapLookupFn = context.eval("cloffle", "get-small");

        // Large map (PersistentHashMap) lookup (> 16 keys)
        context.eval("cloffle", "(def large-m {:k0 0 :k1 1 :k2 2 :k3 3 :k4 4 :k5 5 :k6 6 :k7 7 :k8 8 :k9 9 :k10 10 :k11 11 :k12 12 :k13 13 :k14 14 :k15 15 :k16 16 :k17 17})");
        largeM = context.eval("cloffle", "large-m");
        context.eval("cloffle", "(defn get-large [m] (get m :k5))");
        hashMapLookupFn = context.eval("cloffle", "get-large");

        // 12-key ShapeMap16 in Cloffle
        context.eval("cloffle", "(def shape-m12 {:k0 0 :k1 1 :k2 2 :k3 3 :k4 4 :k5 5 :k6 6 :k7 7 :k8 8 :k9 9 :k10 10 :k11 11})");
        shape12M = context.eval("cloffle", "shape-m12");
        context.eval("cloffle", "(defn get-shape12 [m] (get m :k6))");
        shape12LookupFn = context.eval("cloffle", "get-shape12");

        // Keyword direct invocation (:k m)
        context.eval("cloffle", "(defn kw-invoke [m] (:b m))");
        keywordInvokeFn = context.eval("cloffle", "kw-invoke");

        // Nested lookup
        context.eval("cloffle", "(def nested-m {:user {:profile {:name \"Alice\"}}})");
        nestedM = context.eval("cloffle", "nested-m");
        context.eval("cloffle", "(defn get-in-nested [m] (get-in m [:user :profile :name]))");
        nestedGetInFn = context.eval("cloffle", "get-in-nested");

        // Assoc pipeline (3 keys)
        context.eval("cloffle", "(defn assoc-pipeline [m] (get (assoc m :status :active) :status))");
        assocFn = context.eval("cloffle", "assoc-pipeline");

        // Assoc pipeline (12 keys -> 13 keys)
        context.eval("cloffle", "(defn assoc-pipe12 [m] (get (assoc m :status :active) :status))");
        assocPipeline12Fn = context.eval("cloffle", "assoc-pipe12");

        // Guest ephemeral ShapeMap pipeline (isolated guest compilation unit)
        context.eval("cloffle",
                "(defn guest-ephemeral-pipeline [x]\n" +
                "  (let [m {:a x :b 2 :c 3}]\n" +
                "    (:a (assoc m :a \"replacement\"))))");
        guestEphemeralPipelineFn = context.eval("cloffle", "guest-ephemeral-pipeline");

        context.eval("cloffle",
                "(defn guest-tuple-destructure [x y]\n" +
                "  (let [[a b] [x y]] (+ a b)))");
        guestTupleDestructureFn = context.eval("cloffle", "guest-tuple-destructure");
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
            context.close();
        }
    }

    @Benchmark
    public boolean keywordIdEquals() {
        return kwA.id == kwB.id;
    }

    @Benchmark
    public Value arrayMapLookup() {
        return arrayMapLookupFn.execute(smallM);
    }

    @Benchmark
    public Value hashMapLookup() {
        return hashMapLookupFn.execute(largeM);
    }

    @Benchmark
    public Value keywordDirectInvoke() {
        return keywordInvokeFn.execute(smallM);
    }

    @Benchmark
    public Value nestedGetIn() {
        return nestedGetInFn.execute(nestedM);
    }

    /** Guest assoc on a shared polyglot map ({@code smallM}); result escapes. Shared-update cost. */
    @Benchmark
    public Value assocPipeline() {
        return assocFn.execute(smallM);
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
     * New-key insert (not existing-key rewrite). JMH measures ~64 B/op; MethodFilter graph dumps
     * can still look allocation-free. Insert uses {@code Keyword[]}/{@code Object[]} then
     * {@code createFromSorted}. Scalarize that path like existing-key assoc for 0 B/op insert.
     */
    @Benchmark
    public int shapeMap3EphemeralInsertThenLookup() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, 1, PEA_B, 2);
        return ((Integer) m.assoc(PEA_C, peaInsertVal).valAt(PEA_C)).intValue();
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

    /** {@code without} rebuilds via arrays; expect commit. */
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

    /** ShapeMap16 existing-key assoc + lookup via local ctor (no createWithCheck arrays). */
    @Benchmark
    public int shapeMap16EphemeralAssocThenLookup() {
        PersistentShapeMap16 m = ephemeralShape9(1);
        PersistentShapeMap16 updated = (PersistentShapeMap16) m.assoc(PEA_K0, 999);
        return ((Integer) updated.valAt(PEA_K0)).intValue();
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
    public Value shapeMap16ClojureLookup() {
        return shape12LookupFn.execute(shape12M);
    }

    /** Guest assoc on shared {@code shape12M} (12→13 keys). Shared-update cost, not PEA. */
    @Benchmark
    public Value assocPipeline12() {
        return assocPipeline12Fn.execute(shape12M);
    }

    /** Guest compilation unit: map is created inside the fn, not a shared field. PEA candidate. */
    @Benchmark
    public Value guestShapeMapEphemeralPipeline() {
        return guestEphemeralPipelineFn.execute("initial");
    }

    /** Guest {@code (let [[a b] [x y]] (+ a b))}; PEA candidate, not a returned vector. */
    @Benchmark
    public Value guestTupleDestructure() {
        return guestTupleDestructureFn.execute(2, 3);
    }
}
