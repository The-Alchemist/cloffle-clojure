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

/**
 * Benchmarks var resolution through Truffle's partial evaluation pipeline.
 *
 * This benchmark verifies that indirect var calls (through another defn)
 * converge to near-direct-call speed after Truffle compilation.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
public class NamespaceBenchmark {

    private Context context;
    private Value directIncFn;
    private Value indirectIncFn;
    private Value chainedCallFn;

    @Setup(Level.Trial)
    public void setup() {
        RT.init();
        context = Context.newBuilder("cloffle")
            .allowAllAccess(true)
            .build();

        // Direct: function body is just (inc x) -- one var lookup (inc)
        context.eval("cloffle", "(defn direct-inc [x] (inc x))");

        // Indirect: calls direct-inc -- two var lookups (direct-inc, then inc inside it)
        context.eval("cloffle", "(defn indirect-inc [x] (direct-inc x))");

        // Chained: three levels of var lookup
        context.eval("cloffle", "(defn chained-inc [x] (indirect-inc x))");

        directIncFn = context.eval("cloffle", "direct-inc");
        indirectIncFn = context.eval("cloffle", "indirect-inc");
        chainedCallFn = context.eval("cloffle", "chained-inc");
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (context != null) {
            context.close();
        }
    }

    @Benchmark
    public double baselineJavaMath() {
        return Math.log(42);
    }

    @Benchmark
    public Value directCall() {
        return directIncFn.execute(42);
    }

    @Benchmark
    public Value indirectCall() {
        return indirectIncFn.execute(42);
    }

    @Benchmark
    public Value chainedCall() {
        return chainedCallFn.execute(42);
    }
}
