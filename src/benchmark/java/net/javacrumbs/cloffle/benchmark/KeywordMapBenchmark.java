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

    private Value smallM;
    private Value largeM;
    private Value nestedM;

    private Keyword kwA;
    private Keyword kwB;
    private Keyword kwC;
    private Keyword kwAbsent;
    private clojure.lang.PersistentShapeMap shapeMap;

    @Setup(Level.Trial)
    public void setup() {
        RT.init();
        context = Context.newBuilder("cloffle")
            .allowAllAccess(true)
            .build();

        kwA = Keyword.intern(null, "a");
        kwB = Keyword.intern(null, "b");
        kwC = Keyword.intern(null, "c");
        kwAbsent = Keyword.intern(null, "nonexistent-absent-key");
        shapeMap = (clojure.lang.PersistentShapeMap) RT.map(kwA, 1, kwB, 2, kwC, 3);

        // Small map (PersistentArrayMap) lookup
        context.eval("cloffle", "(def small-m {:a 1 :b 2 :c 3})");
        smallM = context.eval("cloffle", "small-m");
        context.eval("cloffle", "(defn get-small [m] (get m :b))");
        arrayMapLookupFn = context.eval("cloffle", "get-small");

        // Large map (PersistentHashMap) lookup (> 8 keys)
        context.eval("cloffle", "(def large-m {:k1 1 :k2 2 :k3 3 :k4 4 :k5 5 :k6 6 :k7 7 :k8 8 :k9 9 :k10 10})");
        largeM = context.eval("cloffle", "large-m");
        context.eval("cloffle", "(defn get-large [m] (get m :k5))");
        hashMapLookupFn = context.eval("cloffle", "get-large");

        // Keyword direct invocation (:k m)
        context.eval("cloffle", "(defn kw-invoke [m] (:b m))");
        keywordInvokeFn = context.eval("cloffle", "kw-invoke");

        // Nested lookup
        context.eval("cloffle", "(def nested-m {:user {:profile {:name \"Alice\"}}})");
        nestedM = context.eval("cloffle", "nested-m");
        context.eval("cloffle", "(defn get-in-nested [m] (get-in m [:user :profile :name]))");
        nestedGetInFn = context.eval("cloffle", "get-in-nested");

        // Assoc pipeline
        context.eval("cloffle", "(defn assoc-pipeline [m] (get (assoc m :status :active) :status))");
        assocFn = context.eval("cloffle", "assoc-pipeline");
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
}
