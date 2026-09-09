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
import clojure.lang.PersistentTuple;
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
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 2, time = 1)
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
    private IFn benchArrayMapLookupFn;
    private IFn benchHashMapLookupFn;
    private IFn benchShape12LookupFn;
    private IFn benchKeywordInvokeFn;
    private IFn benchNestedGetInFn;
    private IFn benchRtGetFn;
    private IFn benchEcho2Fn;
    private IFn guestEphemeralPipelineFn;
    private IFn guestEphemeralInsertFn;
    private IFn guestEphemeralPromote8Fn;
    private IFn guestTupleDestructureFn;
    private IFn guestListEphemeralPipelineFn;
    private IFn guestLazySeqFirstFn;
    private IFn guestConsFirstFn;
    private IFn guestLazySeqConsFirstFn;
    private IFn guestLazySeqApplyFirstFn;
    private IFn guestLazySeqWhenSeqFirstFn;
    private IFn guestMapFirstFn;
    private IFn guestMapSecondFn;
    private IFn guestMappedVectorReduceFn;
    private IFn guestMappedMapFirstFn;
    private IFn guestStreamSeqPipelineFn;
    private IFn guestPipelineIntoFn;
    private IFn guestPipelineVecFn;
    private IFn guestPipelineReduceFn;
    private IFn guestPipelineTakeDropFn;
    private IFn guestPipelineXformControlFn;
    private IFn guestTuple2TransformFn;
    private IFn guestRingPipelineFn;
    private IFn guestHiccupNormalizeFn;
    private IFn guestKwargsDestructureFn;
    private IFn guestMiddlewarePipelineFn;
    private IFn guestCondOptionPipelineFn;
    private IFn guestEventEnrichPipelineFn;
    private IFn guestEphemeralDissocFn;
    private IFn guestEventSanitizePipelineFn;
    private IFn guestCheshireFieldNamePipelineFn;
    private IFn guestGetInEphemeralPipelineFn;

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

    /**
     * Keyword *values* for the ephemeral map fixtures. These deliberately are not numbers: an
     * {@code Integer} value would put autoboxing on the measured path and confuse a boxing win with a
     * map-shape win. Revisit only alongside a dedicated primitive-specialization pass.
     */
    private static final Keyword V0 = Keyword.intern(null, "v0");
    private static final Keyword V1 = Keyword.intern(null, "v1");
    private static final Keyword V2 = Keyword.intern(null, "v2");
    private static final Keyword V3 = Keyword.intern(null, "v3");
    private static final Keyword V4 = Keyword.intern(null, "v4");
    private static final Keyword V5 = Keyword.intern(null, "v5");
    private static final Keyword V6 = Keyword.intern(null, "v6");
    private static final Keyword V7 = Keyword.intern(null, "v7");
    private static final Keyword V8 = Keyword.intern(null, "v8");
    private static final Keyword V_UPDATED = Keyword.intern(null, "v-updated");
    private static final Keyword V_NESTED = Keyword.intern(null, "v-nested");
    private static final Keyword V_INSERT = Keyword.intern(null, "v-insert");

    /** Compilation-final 2→3 insert and 8→9 promote plans (same keys as the local ephemeral maps). */
    private static final PersistentShapeMap.AssocTransition PEA_INSERT_C =
            PersistentShapeMap.assocTransition(PersistentShapeMap.create(PEA_A, V1, PEA_B, V2), PEA_C);
    private static final PersistentShapeMap.AssocTransition PEA_PROMOTE_K8 =
            PersistentShapeMap.assocTransition(
                    PersistentShapeMap.create(PEA_K0, V0, PEA_K1, V1, PEA_K2, V2, PEA_K3, V3,
                            PEA_K4, V4, PEA_K5, V5, PEA_K6, V6, PEA_K7, V7),
                    PEA_K8);
    private static final PersistentShapeMap.DissocTransition PEA_DISSOC_B =
            PersistentShapeMap.dissocTransition(PersistentShapeMap.create(PEA_A, V1, PEA_B, V2, PEA_C, V3), PEA_B);

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

    /** Non-constant so insert/assoc cannot fold to a literal return. */
    private Object insertVal = V_INSERT;

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
        shapeMap = (clojure.lang.PersistentShapeMap) clojure.lang.PersistentShapeMap.createWithCheck(new Object[]{kwA, V1, kwB, V2, kwC, V3});
        arrayMap3 = new clojure.lang.PersistentArrayMap(new Object[]{kwA, V1, kwB, V2, kwC, V3});

        Object[] init8 = new Object[16];
        for (int i = 0; i < 8; i++) {
            init8[i * 2] = Keyword.intern(null, "k" + i);
            init8[i * 2 + 1] = Keyword.intern(null, "v" + i);
        }
        shapeMap8 = (clojure.lang.PersistentShapeMap) clojure.lang.PersistentShapeMap.createWithCheck(init8);
        arrayMap8 = new clojure.lang.PersistentArrayMap(init8);

        Object[] init12 = new Object[24];
        for (int i = 0; i < 12; i++) {
            init12[i * 2] = Keyword.intern(null, "k" + i);
            init12[i * 2 + 1] = Keyword.intern(null, "v" + i);
        }
        shapeMap16 = (clojure.lang.PersistentShapeMap16) clojure.lang.PersistentShapeMap16.createWithCheck(init12);
        hashMap12 = clojure.lang.PersistentHashMap.create(null, init12);

        context.eval("cloffle", ClojureClasspathResources.read("keyword-map-benchmark/setup.clj"));
        smallM = guestValue("small-m");
        arrayMapLookupFn = guestFn("get-small");
        benchArrayMapLookupFn = guestFn("bench-get-small");
        largeM = guestValue("large-m");
        hashMapLookupFn = guestFn("get-large");
        benchHashMapLookupFn = guestFn("bench-get-large");
        shape12M = guestValue("shape-m12");
        shape12LookupFn = guestFn("get-shape12");
        benchShape12LookupFn = guestFn("bench-get-shape12");
        keywordInvokeFn = guestFn("kw-invoke");
        benchKeywordInvokeFn = guestFn("bench-kw-invoke");
        nestedM = guestValue("nested-m");
        nestedGetInFn = guestFn("get-in-nested");
        benchNestedGetInFn = guestFn("bench-get-in-nested");
        benchRtGetFn = guestFn("bench-rt-get-small");
        benchEcho2Fn = guestFn("bench-interop-echo2");
        assocFn = guestFn("assoc-pipeline");
        shape8PromoteFn = guestFn("shape8-promote");
        assocPipeline12Fn = guestFn("assoc-pipe12");
        guestEphemeralPipelineFn = guestFn("guest-ephemeral-pipeline");
        guestEphemeralInsertFn = guestFn("guest-ephemeral-insert");
        guestEphemeralPromote8Fn = guestFn("guest-ephemeral-promote8");
        guestTupleDestructureFn = guestFn("guest-tuple-destructure");
        guestListEphemeralPipelineFn = guestFn("guest-list-ephemeral-pipeline");
        guestLazySeqFirstFn = guestFn("guest-lazy-seq-first");
        guestConsFirstFn = guestFn("guest-cons-first");
        guestLazySeqConsFirstFn = guestFn("guest-lazy-seq-cons-first");
        guestLazySeqApplyFirstFn = guestFn("guest-lazy-seq-apply-first");
        guestLazySeqWhenSeqFirstFn = guestFn("guest-lazy-seq-when-seq-first");
        guestMapFirstFn = guestFn("guest-map-first");
        guestMapSecondFn = guestFn("guest-map-second");
        guestMappedVectorReduceFn = guestFn("guest-mapped-vector-reduce");
        guestMappedMapFirstFn = guestFn("guest-mapped-map-first");
        guestStreamSeqPipelineFn = guestFn("guest-stream-seq-pipeline");
        guestPipelineIntoFn = guestFn("guest-pipeline-into");
        guestPipelineVecFn = guestFn("guest-pipeline-vec");
        guestPipelineReduceFn = guestFn("guest-pipeline-reduce");
        guestPipelineTakeDropFn = guestFn("guest-pipeline-take-drop");
        guestPipelineXformControlFn = guestFn("guest-pipeline-xform-control");
        guestRingPipelineFn = guestFn("guest-ring-pipeline");
        guestHiccupNormalizeFn = guestFn("guest-hiccup-normalize");
        guestTuple2TransformFn = guestFn("guest-tuple2-transform");
        guestKwargsDestructureFn = guestFn("guest-kwargs-destructure");
        guestMiddlewarePipelineFn = guestFn("guest-middleware-pipeline");
        guestCondOptionPipelineFn = guestFn("guest-cond-option-pipeline");
        guestEventEnrichPipelineFn = guestFn("guest-event-enrich-pipeline");
        guestEphemeralDissocFn = guestFn("guest-ephemeral-dissoc");
        guestEventSanitizePipelineFn = guestFn("guest-event-sanitize-pipeline");
        guestCheshireFieldNamePipelineFn = guestFn("guest-cheshire-field-name");
        guestGetInEphemeralPipelineFn = guestFn("guest-get-in-ephemeral-pipeline");

        // Keep the context entered so timed IFn.invoke calls bypass Polyglot Value.execute.
        context.enter();
        CAPTURED_GUEST_VALUES.remove();
    }

    private static PersistentShapeMap16 ephemeralShape9(Object v0) {
        return new PersistentShapeMap16(null, 9,
                PEA_K0, v0, PEA_K1, V1, PEA_K2, V2, PEA_K3, V3, PEA_K4, V4, PEA_K5, V5, PEA_K6, V6, PEA_K7, V7, PEA_K8, V8,
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
        return benchArrayMapLookupFn.invoke();
    }

    @Benchmark
    public Object hashMapLookup() {
        return benchHashMapLookupFn.invoke();
    }

    @Benchmark
    public Object keywordDirectInvoke() {
        return benchKeywordInvokeFn.invoke();
    }

    @Benchmark
    public Object rtGetDirectInvoke() {
        return benchRtGetFn.invoke();
    }

    @Benchmark
    public Object staticEcho2DirectInvoke() {
        return benchEcho2Fn.invoke();
    }

    @Benchmark
    public Object nestedGetIn() {
        return benchNestedGetInFn.invoke();
    }

    /** Guest assoc on a shared map ({@code smallM}); result escapes. Shared-update cost. */
    @Benchmark
    public Object assocPipeline() {
        return assocFn.invoke(smallM);
    }

    /** Stable shared 8-key ShapeMap input; result is consumed after cached 8->9 promotion. */
    @Benchmark
    public Object guestShapeMap8Promote() {
        return shape8PromoteFn.invoke(shapeMap8, insertVal);
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
        return arrayMap3.assoc(kwA, V_UPDATED);
    }

    /** Shared ShapeMap field; assoc result escapes. Measures heap update cost, not PEA. */
    @Benchmark
    public Object shapeMap3DirectAssoc() {
        return shapeMap.assoc(kwA, V_UPDATED);
    }

    /**
     * Opaque instance-field map and key. Cold assoc arms stay live and PEA commits
     * (~128 B/op). Negative control for scalar replacement, not a PEA success claim.
     */
    @Benchmark
    public Object shapeMap3DirectAssocThenLookup() {
        return shapeMap.assoc(kwA, V_UPDATED).valAt(kwA);
    }

    /**
     * Host PEA success: local create, static-final keywords, consume as int.
     * Graal prunes demote/insert/promote and folds to {@code return :v-updated} (~0 B/op).
     */
    @Benchmark
    public Object shapeMap3EphemeralAssocThenLookup() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, V1, PEA_B, V2, PEA_C, V3);
        PersistentShapeMap updated = (PersistentShapeMap) m.assoc(PEA_A, V_UPDATED);
        return updated.valAt(PEA_A);
    }

    /** Host PEA: local create + valAt only (no assoc). */
    @Benchmark
    public Object shapeMap3EphemeralValAtOnly() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, V1, PEA_B, V2, PEA_C, V3);
        return m.valAt(PEA_A);
    }

    /**
     * New-key insert (not existing-key rewrite). Unrolled field ctor; host PEA target (~0 B/op).
     */
    @Benchmark
    public Object shapeMap3EphemeralInsertThenLookup() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, V1, PEA_B, V2);
        return m.assoc(PEA_C, insertVal).valAt(PEA_C);
    }

    /**
     * Host PEA of {@code AssocTransition.apply} insert (2→3), using a compilation-final plan.
     * Does not go through {@code PersistentShapeMap.assoc}.
     */
    @Benchmark
    public Object shapeMap2EphemeralTransitionInsertThenLookup() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, V1, PEA_B, V2);
        return PEA_INSERT_C.apply(m, insertVal).valAt(PEA_C);
    }

    /**
     * Host PEA of {@code DissocTransition.apply} remove (3→2), using a compilation-final plan.
     */
    @Benchmark
    public Object shapeMap3EphemeralTransitionDissocThenLookup() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, V1, PEA_B, insertVal, PEA_C, V3);
        return PEA_DISSOC_B.apply(m).valAt(PEA_C);
    }

    /**
     * Host PEA of {@code Promote16Transition.apply} (8→9) without {@code @TruffleBoundary assocPromote16}.
     */
    @Benchmark
    public Object shapeMap8EphemeralTransitionPromoteThenLookup() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_K0, V1, PEA_K1, V1, PEA_K2, V2, PEA_K3, V3,
                PEA_K4, 4, PEA_K5, 5, PEA_K6, 6, PEA_K7, 7);
        return PEA_PROMOTE_K8.apply(m, insertVal).valAt(PEA_K8);
    }

    /** Array clone on assoc; expect allocation even with local create. */
    @Benchmark
    public Object arrayMap3EphemeralAssocThenLookup() {
        PersistentArrayMap m = new PersistentArrayMap(new Object[]{PEA_A, V1, PEA_B, V2, PEA_C, V3});
        return m.assoc(PEA_A, insertVal).valAt(PEA_A);
    }

    /** Keyword-as-IFn vs {@code valAt} on an ephemeral ShapeMap. */
    @Benchmark
    public Object shapeMap3EphemeralKeywordInvoke() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, V1, PEA_B, V2, PEA_C, V3);
        return PEA_A.invoke(m);
    }

    /** Nested virtual maps: outer valAt then inner valAt, consume as int. */
    @Benchmark
    public Object shapeMap3EphemeralNestedValAt() {
        PersistentShapeMap inner = PersistentShapeMap.create(PEA_C, V_NESTED);
        PersistentShapeMap outer = PersistentShapeMap.create(PEA_A, inner, PEA_B, V1);
        return ((PersistentShapeMap) outer.valAt(PEA_A)).valAt(PEA_C);
    }

    /** Host PEA: local create + without + valAt (unrolled field shift). */
    @Benchmark
    public Object shapeMap3EphemeralWithoutThenLookup() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, V1, PEA_B, V2, PEA_C, V3);
        return m.without(PEA_B).valAt(PEA_A);
    }

    /**
     * Negative control: {@code seq} of MapEntry objects. Must allocate; not a PEA claim.
     */
    @Benchmark
    public Object shapeMap3EphemeralSeqWalk() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, V1, PEA_B, V2, PEA_C, V3);
        Object last = null;
        for (ISeq s = m.seq(); s != null; s = s.next()) {
            last = ((IMapEntry) s.first()).val();
        }
        return last;
    }

    /**
     * Opportunity 3: Ephemeral ShapeMap3 kvreduce via unrolled field access.
     */
    @Benchmark
    public Object shapeMap3EphemeralKvReduce() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, V1, PEA_B, V2, PEA_C, V3);
        Object res = m.kvreduce(new clojure.lang.AFn() {
            @Override
            public Object invoke(Object acc, Object k, Object v) {
                return v;
            }
        }, V0);
        return res;
    }

    /**
     * Opportunity 3: Ephemeral ShapeMap3 reduce with MapEntry scalar replacement.
     */
    @Benchmark
    public Object shapeMap3EphemeralReduce() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, V1, PEA_B, V2, PEA_C, V3);
        Object res = m.reduce(new clojure.lang.AFn() {
            @Override
            public Object invoke(Object acc, Object entry) {
                return ((clojure.lang.IMapEntry) entry).val();
            }
        }, V0);
        return res;
    }

    /** ShapeMap16 existing-key assoc + lookup via local ctor (no createWithCheck arrays). */
    @Benchmark
    public Object shapeMap16EphemeralAssocThenLookup() {
        PersistentShapeMap16 m = ephemeralShape9(V1);
        PersistentShapeMap16 updated = (PersistentShapeMap16) m.assoc(PEA_K0, V_UPDATED);
        return updated.valAt(PEA_K0);
    }

    /** Host PEA: ShapeMap16 new-key insert via unrolled field ctor. */
    @Benchmark
    public Object shapeMap16EphemeralInsertThenLookup() {
        PersistentShapeMap16 m = ephemeralShape9(V1);
        return m.assoc(PEA_K9, insertVal).valAt(PEA_K9);
    }

    /** Host PEA: 5-key ShapeMap create + valAt. */
    @Benchmark
    public Object shapeMap5EphemeralValAtOnly() {
        PersistentShapeMap m = PersistentShapeMap.create(PEA_A, V1, PEA_B, V2, PEA_C, V3, PEA_D, V4, PEA_E, V5);
        return m.valAt(PEA_C);
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
        return benchShape12LookupFn.invoke();
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
        return guestEphemeralInsertFn.invoke(PEA_C);
    }

    /**
     * Guest local 8-key ShapeMap then {@code (assoc m :p8 x)} consumed as a scalar.
     * Exercises {@code KeywordAssoc} {@code Promote16Transition} inside one compilation unit.
     */
    @Benchmark
    public Object guestShapeMapEphemeralPromote8() {
        return guestEphemeralPromote8Fn.invoke(insertVal);
    }

    /** Guest {@code (let [[a b] [x y]] (+ a b))}; PEA candidate, not a returned vector. */
    @Benchmark
    public Object guestTupleDestructure() {
        return guestTupleDestructureFn.invoke(PEA_B, PEA_C);
    }

    /** Guest {@code (let [[a b] (list x y)] (+ a b))}; unrolled list PEA candidate. */
    @Benchmark
    public Object guestListEphemeralPipeline() {
        return guestListEphemeralPipelineFn.invoke(PEA_B, PEA_C);
    }

    /**
     * One LazySeq cell: {@code (first (lazy-seq [x]))}. Probes whether realized
     * {@code seq()} inlines without the recursive {@code map} pipeline.
     */
    @Benchmark
    public Object guestLazySeqFirst() {
        return guestLazySeqFirstFn.invoke(PEA_A);
    }

    /**
     * Step 1: Cons cell scalar replacement.
     */
    @Benchmark
    public Object guestConsFirst() {
        return guestConsFirstFn.invoke(PEA_A);
    }

    /**
     * Step 2: LazySeq + Cons fusion.
     */
    @Benchmark
    public Object guestLazySeqConsFirst() {
        return guestLazySeqConsFirstFn.invoke(PEA_A);
    }

    /**
     * Step 3: Closure application inside lazy-seq.
     */
    @Benchmark
    public Object guestLazySeqApplyFirst() {
        return guestLazySeqApplyFirstFn.invoke(PEA_A);
    }

    /**
     * Step 4: Input seq guard elimination.
     */
    @Benchmark
    public Object guestLazySeqWhenSeqFirst() {
        return guestLazySeqWhenSeqFirstFn.invoke(PEA_A);
    }

    /**
     * Step 5: Canonical 1-element map realization.
     */
    @Benchmark
    public Object guestMapFirst() {
        return guestMapFirstFn.invoke(PEA_A);
    }

    /**
     * Step 6: 2-element tail realization.
     */
    @Benchmark
    public Object guestMapSecond() {
        return guestMapSecondFn.invoke(PEA_A, PEA_B);
    }

    @Benchmark
    public Object guestMappedVectorReduce() {
        return guestMappedVectorReduceFn.invoke(PEA_A, PEA_B);
    }

    @Benchmark
    public Object guestMappedMapFirst() {
        return guestMappedMapFirstFn.invoke(PEA_A, PEA_B);
    }

    /**
     * Guest transducer control {@code map}/{@code filter}/{@code into} over a 2-tuple.
     * Uses interned keywords, not boxed integers, so GC and scalar-replacement
     * checks are not polluted by {@code Integer}/{@code Long} boxing.
     */
    @Benchmark
    public Object guestStreamSeqPipeline() {
        return guestStreamSeqPipelineFn.invoke(PEA_A, PEA_B);
    }

    /**
     * Eager-consumer fusion shapes. All elements and per-element operations are
     * reference operations (interned keywords, set membership, {@code Keyword.getName})
     * so boxing never contributes to the allocation counts these measure.
     *
     * <p>{@code PEA_A} passes the filter and {@code PEA_C} is rejected, exercising both
     * the keep and the skip branch.
     */
    @Benchmark
    public Object guestPipelineInto() {
        return guestPipelineIntoFn.invoke(PEA_A, PEA_C);
    }

    @Benchmark
    public Object guestPipelineVec() {
        return guestPipelineVecFn.invoke(PEA_A, PEA_C);
    }

    @Benchmark
    public Object guestPipelineReduce() {
        return guestPipelineReduceFn.invoke(PEA_A, PEA_C);
    }

    @Benchmark
    public Object guestPipelineTakeDrop() {
        return guestPipelineTakeDropFn.invoke(PEA_A, PEA_B, PEA_C);
    }

    /**
     * The control: the hand-written transducer spelling of {@link #guestPipelineInto()}.
     * Fused benchmarks should be indistinguishable from this in ns/op and B/op.
     */
    @Benchmark
    public Object guestPipelineXformControl() {
        return guestPipelineXformControlFn.invoke(PEA_A, PEA_C);
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
        return guestTuple2TransformFn.invoke(PEA_B, PEA_C);
    }

    /**
     * Opportunity 4: Keyword arguments destructuring lowering to PersistentShapeMap.
     * ShapeMap virtualized into CPU registers (0 B/op).
     */
    @Benchmark
    public Object guestKwargsDestructure() {
        return guestKwargsDestructureFn.invoke("500ms");
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
        return guestEphemeralDissocFn.invoke(insertVal);
    }

    /**
     * Sanitization pipeline PEA: Chained dissocs on an ephemeral event map.
     * Eliminates intermediate maps and scalar replaces remaining fields.
     */
    @Benchmark
    public Object guestEventSanitizePipeline() {
        return guestEventSanitizePipelineFn.invoke("secret-token");
    }

    /**
     * Opportunity 10: Zero-Allocation Keyword Field Names (Cheshire / JSON Encoding).
     * Extracts keyword and string field names without allocating throwaway strings or substring copies.
     * All intermediate maps, keys, and field extraction operations execute with zero allocation (0 B/op).
     */
    @Benchmark
    public Object guestCheshireFieldNamePipeline() {
        return guestCheshireFieldNamePipelineFn.invoke("ok");
    }

    @Benchmark
    public Object guestGetInEphemeralPipeline() {
        return guestGetInEphemeralPipelineFn.invoke(PEA_C);
    }

    @Benchmark
    public Object shapeMap3EphemeralGetInHost() {
        PersistentShapeMap profile = PersistentShapeMap.create(PEA_A, V_NESTED, PEA_B, "admin");
        PersistentShapeMap user = PersistentShapeMap.create(PEA_C, profile);
        PersistentShapeMap m = PersistentShapeMap.create(PEA_D, user);
        return RT.getIn(m, PersistentTuple.create(PEA_D, PEA_C, PEA_A));
    }

}
