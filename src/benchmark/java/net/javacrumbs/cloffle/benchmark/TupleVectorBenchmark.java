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
import clojure.lang.Tuple;
import clojure.lang.PersistentTuple;
import clojure.lang.PersistentVector;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class TupleVectorBenchmark {

    private Context context;

    private Value createTuple2Fn;
    private Value createTuple4Fn;
    private Value createTuple8Fn;

    private Value destructTuple2Fn;
    private Value destructTuple4Fn;
    private Value destructTuple8Fn;

    private Value nthTuple4Fn;

    private Value tuple2Val;
    private Value tuple4Val;
    private Value tuple8Val;

    @Setup(Level.Trial)
    public void setup() {
        RT.init();
        context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build();

        createTuple2Fn = context.eval("cloffle", "(fn [x y] [x y])");
        createTuple4Fn = context.eval("cloffle", "(fn [a b c d] [a b c d])");
        createTuple8Fn = context.eval("cloffle", "(fn [a b c d e f g h] [a b c d e f g h])");

        destructTuple2Fn = context.eval("cloffle", "(fn [v] (let [[a b] v] (+ a b)))");
        destructTuple4Fn = context.eval("cloffle", "(fn [v] (let [[a b c d] v] (+ a b c d)))");
        destructTuple8Fn = context.eval("cloffle", "(fn [v] (let [[a b c d e f g h] v] (+ a b c d e f g h)))");

        nthTuple4Fn = context.eval("cloffle", "(fn [v] (+ (nth v 0) (nth v 1) (nth v 2) (nth v 3)))");

        tuple2Val = context.eval("cloffle", "[10 20]");
        tuple4Val = context.eval("cloffle", "[10 20 30 40]");
        tuple8Val = context.eval("cloffle", "[10 20 30 40 50 60 70 80]");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Benchmark
    public Object createTuple2() {
        return createTuple2Fn.execute(10, 20);
    }

    @Benchmark
    public Object createTuple4() {
        return createTuple4Fn.execute(10, 20, 30, 40);
    }

    @Benchmark
    public Object createTuple8() {
        return createTuple8Fn.execute(10, 20, 30, 40, 50, 60, 70, 80);
    }

    @Benchmark
    public Object destructTuple2() {
        return destructTuple2Fn.execute(tuple2Val);
    }

    @Benchmark
    public Object destructTuple4() {
        return destructTuple4Fn.execute(tuple4Val);
    }

    @Benchmark
    public Object destructTuple8() {
        return destructTuple8Fn.execute(tuple8Val);
    }

    @Benchmark
    public Object nthTuple4() {
        return nthTuple4Fn.execute(tuple4Val);
    }

    @Benchmark
    public Object javaTuple2Direct() {
        return Tuple.create(10, 20);
    }

    @Benchmark
    public Object javaTuple4Direct() {
        return Tuple.create(10, 20, 30, 40);
    }

    @Benchmark
    public Object javaTuple8Direct() {
        return Tuple.create(10, 20, 30, 40, 50, 60, 70, 80);
    }
}
