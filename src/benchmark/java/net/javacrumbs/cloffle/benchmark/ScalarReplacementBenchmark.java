package net.javacrumbs.cloffle.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class ScalarReplacementBenchmark {

    public static final class SimpleBox {
        public final int k;
        public SimpleBox(int k) {
            this.k = k;
        }
    }

    public static final class SimplePair {
        public final int a;
        public final int b;
        public SimplePair(int a, int b) {
            this.a = a;
            this.b = b;
        }
    }

    private int argA = 2;
    private int argB = 3;

    @Benchmark
    public int baselineScalarReplacementLiteral() {
        SimpleBox box = new SimpleBox(2 + 3);
        int k = box.k;
        return k;
    }

    @Benchmark
    public int baselineScalarReplacementFields() {
        SimplePair pair = new SimplePair(argA, argB);
        int k = pair.a + pair.b;
        return k;
    }
}
