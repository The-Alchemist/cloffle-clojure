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

import clojure.lang.Keyword;
import clojure.lang.RT;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.util.concurrent.TimeUnit;

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

    private Value smallM;
    private Value largeM;
    private Value nestedM;
    private Value shape12M;

    private Keyword kwA;
    private Keyword kwB;
    private Keyword kwC;
    private Keyword kwK6;
    private Keyword kwAbsent;
    private clojure.lang.PersistentShapeMap shapeMap;
    private clojure.lang.PersistentShapeMap16 shapeMap16;
    private clojure.lang.PersistentHashMap hashMap12;

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
        shapeMap = (clojure.lang.PersistentShapeMap) RT.map(kwA, 1, kwB, 2, kwC, 3);

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
    public boolean keywordPointerEquals() {
        return kwA == kwB;
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

    @Benchmark
    public Value assocPipeline() {
        return assocFn.execute(smallM);
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

    @Benchmark
    public Value assocPipeline12() {
        return assocPipeline12Fn.execute(shape12M);
    }
}
