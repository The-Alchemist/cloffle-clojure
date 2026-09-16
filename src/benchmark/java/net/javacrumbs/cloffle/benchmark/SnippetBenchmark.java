package net.javacrumbs.cloffle.benchmark;

import clojure.lang.IFn;
import org.graalvm.polyglot.Context;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Microbenchmark comparing arbitrary Clojure code snippets between
 * official standard Clojure (JVM) and Cloffle (GraalVM Truffle).
 * <p>
 * Snippet resources declare {@code (ns bench.snippet.<id>)} and {@code (defn bench [] …)}.
 * Trial setup loads the namespace once; JMH only invokes {@code bench}.
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Threads(1)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 1, time = 1)
public class SnippetBenchmark {

    /** @deprecated use {@link SnippetBenchmarkSupport#captureGuestFn} */
    @Deprecated
    public static Object captureGuestFn(Object fn) {
        return SnippetBenchmarkSupport.captureGuestFn(fn);
    }

    @State(Scope.Benchmark)
    public static class SampleState {
        @Param({
                SnippetBenchmarkSupport.FILE,
                SnippetBenchmarkSupport.CONSUME_ASSOC,
                SnippetBenchmarkSupport.CONSUME_ASSOC_NO_LET,
                SnippetBenchmarkSupport.ASSOC_ONLY,
                SnippetBenchmarkSupport.ASSOC_RETURN_NIL,
                SnippetBenchmarkSupport.ASSOC_MULTI_ARITY,
                SnippetBenchmarkSupport.ARRAY_MAP_LOOKUP,
                SnippetBenchmarkSupport.HASH_MAP_LOOKUP,
                SnippetBenchmarkSupport.SHAPE_MAP16_LOOKUP,
                SnippetBenchmarkSupport.RT_GET_LOOKUP,
                SnippetBenchmarkSupport.KEYWORD_INVOKE,
                SnippetBenchmarkSupport.NESTED_GET_IN,
                SnippetBenchmarkSupport.ASSOC_PIPELINE,
                SnippetBenchmarkSupport.EPHEMERAL_PIPELINE,
                SnippetBenchmarkSupport.EPHEMERAL_INSERT,
                SnippetBenchmarkSupport.EPHEMERAL_PROMOTE8,
                SnippetBenchmarkSupport.EPHEMERAL_DISSOC,
                SnippetBenchmarkSupport.TUPLE_DESTRUCTURE,
                SnippetBenchmarkSupport.INTO_EMPTY_TUPLE2,
                SnippetBenchmarkSupport.INTO_MAP_SMALL,
                SnippetBenchmarkSupport.MAP_SMALL_VECTOR,
                SnippetBenchmarkSupport.MAP_FIRST_SMALL,
                SnippetBenchmarkSupport.MAP_FIRST_ONE,
                SnippetBenchmarkSupport.MAP_IDENTITY_VECTOR,
                SnippetBenchmarkSupport.MAPV_SMALL_VECTOR,
                SnippetBenchmarkSupport.LADDER_NTH5_KEYWORDS,
                SnippetBenchmarkSupport.LADDER_FIRST5_KEYWORDS,
                SnippetBenchmarkSupport.LADDER_SEQ_FIRST5_KEYWORDS,
                SnippetBenchmarkSupport.LAZY_SEQ_FIRST,
                SnippetBenchmarkSupport.LAZY_SEQ_VEC_FIRST,
                SnippetBenchmarkSupport.TUPLE2_TRANSFORM,
                SnippetBenchmarkSupport.RING_RESPONSE,
                SnippetBenchmarkSupport.HICCUP_NORMALIZE,
                SnippetBenchmarkSupport.HICCUP_NORMALIZE_SMALL,
                SnippetBenchmarkSupport.NORM_TUPLE_NTH,
                SnippetBenchmarkSupport.KWARGS_DESTRUCTURE,
                SnippetBenchmarkSupport.MIDDLEWARE_PIPELINE,
                SnippetBenchmarkSupport.COND_OPTION_PIPELINE,
                SnippetBenchmarkSupport.EVENT_ENRICH,
                SnippetBenchmarkSupport.EVENT_SANITIZE,
                SnippetBenchmarkSupport.MERGE_LITERAL,
                SnippetBenchmarkSupport.MERGE_RUNTIME,
                SnippetBenchmarkSupport.FIXED_STR2,
                SnippetBenchmarkSupport.CROSS_CALL_MAP,
                SnippetBenchmarkSupport.CROSS_CALL_NESTED_MAPS,
                SnippetBenchmarkSupport.CROSS_CALL_NESTED_LARGE,
                SnippetBenchmarkSupport.CROSS_CALL_NESTED_DEEP,
                SnippetBenchmarkSupport.CROSS_CALL_NESTED_ROWS,
                SnippetBenchmarkSupport.CROSS_CALL_JSONAPI,
                SnippetBenchmarkSupport.CROSS_CALL_DEFN_PIPELINE,
                SnippetBenchmarkSupport.CROSS_CALL_VALIDATION_PIPELINE,
                SnippetBenchmarkSupport.CROSS_CALL_VALIDATION_PIPELINE_THREADED,
                SnippetBenchmarkSupport.COND_SHAPE_POLY
        })
        public String name;

        public String snippetCode;
        public String snippetNs;

        @Setup(Level.Trial)
        public void setup() {
            this.snippetCode = SnippetBenchmarkSupport.codeFor(name);
            this.snippetNs = SnippetBenchmarkSupport.namespaceFor(name);
        }
    }

    @State(Scope.Benchmark)
    public static class ClojureState {
        public Supplier<?> supplier;
        public String snippetCode;

        @Setup(Level.Trial)
        public void setup(SampleState sample) throws Exception {
            this.snippetCode = sample.snippetCode;
            String source = SnippetBenchmarkSupport.namespacedSource(sample.name, sample.snippetCode);
            this.supplier = SnippetBenchmarkSupport.openStockBenchSupplier(sample.snippetNs, source);
        }
    }

    @State(Scope.Benchmark)
    public static class CloffleState {
        public Context context;
        public IFn cloffleFn;
        public String snippetCode;

        private SnippetBenchmarkSupport.CloffleBenchSession session;

        @Setup(Level.Trial)
        public void setup(SampleState sample) {
            this.snippetCode = sample.snippetCode;
            String source = SnippetBenchmarkSupport.namespacedSource(sample.name, sample.snippetCode);
            // Default on (unlike Boolean.getBoolean); opt out with -Dcloffle.bench.directLinking=false
            boolean directLinking = Boolean.parseBoolean(
                    System.getProperty(SnippetBenchmarkSupport.CLOFFLE_DIRECT_LINKING_PROP, "true"));
            this.session = SnippetBenchmarkSupport.openCloffleBench(sample.snippetNs, source, directLinking);
            this.context = session.context;
            this.cloffleFn = session.fn;
        }

        @TearDown(Level.Trial)
        public void teardown() {
            if (session != null) {
                session.close();
                session = null;
                context = null;
                cloffleFn = null;
            }
        }
    }

    @Benchmark
    public Object clojure(ClojureState state, Blackhole bh) {
        return state.supplier.get();
    }

    @Benchmark
    public Object cloffle(CloffleState state, Blackhole bh) {
        return state.cloffleFn.invoke();
    }
}
