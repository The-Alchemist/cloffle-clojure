package net.javacrumbs.cloffle.benchmark;

import clojure.lang.IPersistentVector;
import clojure.lang.PersistentTuple;
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

/**
 * {@link PersistentTuple} analogue of {@link PointPeaBenchmark}: host PEA on local Tuple2
 * create, {@code nth}, and a {@code sum(t1,t2)} that allocates another Tuple2.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class TuplePeaBenchmark {

    private int argA = 2;
    private int argB = 3;

    private static int sumNth0And1(IPersistentVector t) {
        return ((Integer) t.nth(0)) + ((Integer) t.nth(1));
    }

    /** Element-wise add into a new Tuple2 (like {@code Point} sum). */
    private static PersistentTuple.PersistentTuple2 sum(IPersistentVector t1, IPersistentVector t2) {
        return PersistentTuple.create(
                (Integer) t1.nth(0) + (Integer) t2.nth(0),
                (Integer) t1.nth(1) + (Integer) t2.nth(1));
    }

    @Benchmark
    public int inMethod() {
        PersistentTuple.PersistentTuple2 t =
                (PersistentTuple.PersistentTuple2) PersistentTuple.create(argA, argB);
        return ((Integer) t.nth(0)) + ((Integer) t.nth(1));
    }

    @Benchmark
    public int viaPrivateHelper() {
        PersistentTuple.PersistentTuple2 t =
                (PersistentTuple.PersistentTuple2) PersistentTuple.create(argA, argB);
        return sumNth0And1(t);
    }

    @Benchmark
    public int viaOutOfLineHelper() {
        PersistentTuple.PersistentTuple2 t =
                (PersistentTuple.PersistentTuple2) PersistentTuple.create(argA, argB);
        return TuplePeaOutOfLine.sumNth0And1(t);
    }

    @Benchmark
    public IPersistentVector materialized() {
        return PersistentTuple.create(argA, argB);
    }

    @Benchmark
    public int twoTuplesSumThenConsumeNth() {
        IPersistentVector t1 = PersistentTuple.create(argA, argB);
        IPersistentVector t2 = PersistentTuple.create(argB, argA);
        IPersistentVector combined = sum(t1, t2);
        return ((Integer) combined.nth(0)) + ((Integer) combined.nth(1));
    }

    @Benchmark
    public int twoTuplesSumThenConsumeNthOutOfLine() {
        IPersistentVector t1 = PersistentTuple.create(argA, argB);
        IPersistentVector t2 = PersistentTuple.create(argB, argA);
        IPersistentVector combined = TuplePeaOutOfLine.sum(t1, t2);
        return ((Integer) combined.nth(0)) + ((Integer) combined.nth(1));
    }

    @Benchmark
    public IPersistentVector twoTuplesSumMaterialized() {
        IPersistentVector t1 = PersistentTuple.create(argA, argB);
        IPersistentVector t2 = PersistentTuple.create(argB, argA);
        return sum(t1, t2);
    }
}
