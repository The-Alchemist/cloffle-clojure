package net.javacrumbs.cloffle.benchmark;

import clojure.lang.IFn;
import clojure.lang.RT;
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

import java.io.Reader;
import java.io.StringReader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Microbenchmark comparing arbitrary Clojure code snippets between
 * official standard Clojure (JVM) and Cloffle (GraalVM Truffle).
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Threads(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class SnippetBenchmark {

    private static final ThreadLocal<IFn> CAPTURED_GUEST_FN = new ThreadLocal<>();

    /**
     * Setup-only bridge that lets guest code hand its raw JVM closure to the benchmark without
     * retaining a Polyglot Value wrapper in the timed path.
     */
    public static Object captureGuestFn(Object fn) {
        CAPTURED_GUEST_FN.set((IFn) fn);
        return fn;
    }

    @State(Scope.Benchmark)
    public static class SampleState {
        @Param({
                SnippetBenchmarkSupport.FILE,
                SnippetBenchmarkSupport.CONSUME_ASSOC,
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
                SnippetBenchmarkSupport.LAZY_SEQ_FIRST,
                SnippetBenchmarkSupport.LAZY_SEQ_VEC_FIRST,
                SnippetBenchmarkSupport.TUPLE2_TRANSFORM,
                SnippetBenchmarkSupport.RING_RESPONSE,
                SnippetBenchmarkSupport.HICCUP_NORMALIZE,
                SnippetBenchmarkSupport.KWARGS_DESTRUCTURE,
                SnippetBenchmarkSupport.MIDDLEWARE_PIPELINE,
                SnippetBenchmarkSupport.COND_OPTION_PIPELINE,
                SnippetBenchmarkSupport.EVENT_ENRICH,
                SnippetBenchmarkSupport.EVENT_SANITIZE,
                SnippetBenchmarkSupport.FIXED_STR2,
                SnippetBenchmarkSupport.CROSS_CALL_MAP,
                SnippetBenchmarkSupport.COND_SHAPE_POLY
        })
        public String name;

        public String snippetCode;

        @Setup(Level.Trial)
        public void setup() {
            this.snippetCode = SnippetBenchmarkSupport.codeFor(name);
        }
    }

    @State(Scope.Benchmark)
    public static class ClojureState {
        public Supplier<?> supplier;
        public String snippetCode;

        @Setup(Level.Trial)
        public void setup(SampleState sample) throws Exception {
            this.snippetCode = sample.snippetCode;
            ClassLoader prevCl = Thread.currentThread().getContextClassLoader();
            try {
                URLClassLoader cl = SnippetBenchmarkSupport.createStockClojureClassLoader();
                Thread.currentThread().setContextClassLoader(cl);

                Class<?> rtClass = cl.loadClass("clojure.lang.RT");
                rtClass.getMethod("init").invoke(null);

                Class<?> compilerClass = cl.loadClass("clojure.lang.Compiler");
                Method loadMethod = compilerClass.getMethod("load", Reader.class);
                String form = "(fn [] " + snippetCode + ")";
                Object fnObj = loadMethod.invoke(null, new StringReader(form));

                Method invokeMethod = fnObj.getClass().getMethod("invoke");
                MethodHandle mh = MethodHandles.lookup().unreflect(invokeMethod).bindTo(fnObj);
                this.supplier = MethodHandleProxies.asInterfaceInstance(Supplier.class, mh);
            } finally {
                Thread.currentThread().setContextClassLoader(prevCl);
            }
        }
    }

    @State(Scope.Benchmark)
    public static class CloffleState {
        public Context context;
        public IFn cloffleFn;
        public String snippetCode;

        @Setup(Level.Trial)
        public void setup(SampleState sample) {
            RT.init();
            this.snippetCode = sample.snippetCode;

            Context.Builder builder = Context.newBuilder("cloffle")
                    .allowAllAccess(true)
                    .option("engine.BackgroundCompilation", "false");

            if (Boolean.getBoolean("cloffle.bench.throwOnFailure")) {
                builder.option("engine.CompilationFailureAction", "Throw");
            }
            if (Boolean.getBoolean("cloffle.bench.compileImmediately")) {
                builder.option("engine.CompileImmediately", "true");
            }

            this.context = builder.build();
            // Deliberately anonymous. Naming the fn would give the guest root a name that
            // -Djdk.graal.MethodFilter could select, which would make snippets diagnosable,
            // but a self-named fn measures ~80M ops/s where the anonymous form measures
            // ~181M on tuple-destructure, so it would corrupt the number being gated.
            // Diagnose snippets through a named benchmark instead; see HOWTO_SEAFOAM.md.
            String form = "(net.javacrumbs.cloffle.benchmark.SnippetBenchmark/captureGuestFn (fn [] "
                    + snippetCode + "))";
            context.eval("cloffle", form);
            this.cloffleFn = CAPTURED_GUEST_FN.get();
            CAPTURED_GUEST_FN.remove();
            if (this.cloffleFn == null) {
                throw new IllegalStateException("Guest snippet fn was not captured");
            }
            context.enter();
        }

        @TearDown(Level.Trial)
        public void teardown() {
            if (context != null) {
                context.leave();
                context.close();
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
