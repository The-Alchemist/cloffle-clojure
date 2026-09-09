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

import clojure.lang.IPersistentVector;
import clojure.lang.PersistentList;
import clojure.lang.PersistentTuple;
import clojure.lang.PersistentVector;
import clojure.lang.PersistentTuple;

import java.util.concurrent.TimeUnit;

/**
 * Host PEA microbenches: local create, consume as {@code int}, no JMH object escape.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class PersistentTypeScalarReplacementBenchmark {

    private int argA = 2;
    private int argB = 3;
    private int argC = 5;
    private int argD = 7;

    @Benchmark
    public int baselineTuple2ScalarReplacement() {
        PersistentTuple.PersistentTuple2 t = (PersistentTuple.PersistentTuple2) PersistentTuple.create(argA, argB);
        return ((Integer) t.nth(0)) + ((Integer) t.nth(1));
    }

    @Benchmark
    public int baselineTuple3ScalarReplacement() {
        PersistentTuple.PersistentTuple3 t = (PersistentTuple.PersistentTuple3) PersistentTuple.create(argA, argB, argC);
        return ((Integer) t.nth(0)) + ((Integer) t.nth(1)) + ((Integer) t.nth(2));
    }

    @Benchmark
    public int baselineTuple4ScalarReplacement() {
        PersistentTuple.PersistentTuple4 t = (PersistentTuple.PersistentTuple4) PersistentTuple.create(argA, argB, argC, argD);
        return ((Integer) t.nth(0)) + ((Integer) t.nth(1))
                + ((Integer) t.nth(2)) + ((Integer) t.nth(3));
    }

    /** Existing-index rewrite: {@code assocN} to same Tuple2 class, then {@code nth}. */
    @Benchmark
    public int tuple2AssocNThenNth() {
        PersistentTuple.PersistentTuple2 t = (PersistentTuple.PersistentTuple2) PersistentTuple.create(argA, argB);
        IPersistentVector updated = t.assocN(0, 999);
        return ((Integer) updated.nth(0)) + ((Integer) updated.nth(1));
    }

    /** Class change: Tuple2 {@code cons} promotes to Tuple3, then {@code nth}. */
    @Benchmark
    public int tuple2ConsThenNth() {
        PersistentTuple.PersistentTuple2 t = (PersistentTuple.PersistentTuple2) PersistentTuple.create(argA, argB);
        IPersistentVector promoted = t.cons(argC);
        return ((Integer) promoted.nth(0)) + ((Integer) promoted.nth(1)) + ((Integer) promoted.nth(2));
    }

    @Benchmark
    public int baselineList2ScalarReplacement() {
        PersistentList.PersistentList2 xs = (PersistentList.PersistentList2) PersistentList.createList(argA, argB);
        return ((Integer) xs.first()) + ((Integer) xs.next().first());
    }

    @Benchmark
    public int list2ConsThenFirst() {
        PersistentList xs = (PersistentList) PersistentList.EMPTY.cons(argB).cons(argA);
        return ((Integer) xs.first()) + ((Integer) xs.next().first());
    }

}
