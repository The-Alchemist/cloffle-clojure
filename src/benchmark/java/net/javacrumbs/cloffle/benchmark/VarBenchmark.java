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
public class VarBenchmark {

    private Context context;

    private Value staticVarCallFn;
    private Value crossFnTuplePeaFn;
    private Value crossFnShapeMapPeaFn;
    private Value staticVarConstReadFn;
    private Value dynamicVarCallFn;

    @Setup(Level.Trial)
    public void setup() {
        RT.init();
        context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build();

        context.eval("cloffle",
                "(ns bench.var)\n" +
                "(defn add2 [a b] (+ a b))\n" +
                "(defn static-call [x y] (add2 x y))\n" +
                "\n" +
                "(defn make-tuple2 [x y] [x y])\n" +
                "(defn consume-tuple2 [t] (let [[a b] t] (+ (* a a) (* b b))))\n" +
                "(defn tuple-pipeline [x y] (consume-tuple2 (make-tuple2 x y)))\n" +
                "\n" +
                "(defn make-record [id name] {:id id :name name :role :admin})\n" +
                "(defn auth-record [r] (= (:role r) :admin))\n" +
                "(defn map-pipeline [id name] (auth-record (make-record id name)))\n" +
                "\n" +
                "(def app-config {:port 8080 :host \"localhost\" :threads 16})\n" +
                "(defn read-config [] app-config)\n" +
                "\n" +
                "(def ^:dynamic *dyn-fn* (fn [x] (+ x 10)))\n" +
                "(defn dyn-call [x] (*dyn-fn* x))\n"
        );

        staticVarCallFn = context.eval("cloffle", "bench.var/static-call");
        crossFnTuplePeaFn = context.eval("cloffle", "bench.var/tuple-pipeline");
        crossFnShapeMapPeaFn = context.eval("cloffle", "bench.var/map-pipeline");
        staticVarConstReadFn = context.eval("cloffle", "bench.var/read-config");
        dynamicVarCallFn = context.eval("cloffle", "bench.var/dyn-call");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Benchmark
    public Object directStaticVarCall() {
        return staticVarCallFn.execute(10, 20);
    }

    @Benchmark
    public Object crossFunctionTuplePEA() {
        return crossFnTuplePeaFn.execute(10, 20);
    }

    @Benchmark
    public Object crossFunctionShapeMapPEA() {
        return crossFnShapeMapPeaFn.execute(42, "alchemist");
    }

    @Benchmark
    public Object staticVarConstantRead() {
        return staticVarConstReadFn.execute();
    }

    @Benchmark
    public Object dynamicVarCall() {
        return dynamicVarCallFn.execute(42);
    }
}
