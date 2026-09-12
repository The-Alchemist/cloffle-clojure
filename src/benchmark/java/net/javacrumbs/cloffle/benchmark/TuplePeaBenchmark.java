package net.javacrumbs.cloffle.benchmark;

import clojure.lang.IPersistentVector;
import clojure.lang.PersistentTuple;
import clojure.lang.PersistentTuple.PersistentTuple2;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * {@link PersistentTuple} analogue of {@link PointPeaBenchmark}: host PEA on local Tuple2
 * create, {@code nth}, and a {@code sum(t1,t2)} that allocates another Tuple2.
 * Truffle/guest PEA across {@code defn} CallTargets is {@link KeywordMapBenchmark#guestCrossCallTuplePea}
 * (JUnit: {@code GuestCompilationUnitTest} tuple-pea).
 * Branching benchmarks use {@link BranchParam} so each path is compiled separately ({@code @Param}).
 * Fixed {@code for} loops use {@link #LOOP_ITERS} and often fully unroll; {@link TripParam} {@code while}
 * loops use a countdown/limit so the compiler must optimize a loop header (counted loop) per trip count.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class TuplePeaBenchmark {

    @State(Scope.Benchmark)
    public static class BranchParam {
        /** {@code 0} vs {@code 1} — JMH forks measurements so each branch can optimize in isolation. */
        @Param({"0", "1"})
        public int branch;
    }

    /** Trip count for {@code while} benchmarks; separate JMH param ⇒ separate compilations per count. */
    @State(Scope.Benchmark)
    public static class TripParam {
        @Param({"8", "16", "32"})
        public int trips;
    }

    /** Small fixed trip count so the compiler can unroll / optimize the loop body as a unit. */
    private static final int LOOP_ITERS = 16;

    private int argA = 2;
    private int argB = 3;

    private static int sumNth0And1(PersistentTuple2 t) {
        return ((Integer) t.nth(0)) + ((Integer) t.nth(1));
    }

    /** Element-wise add into a new Tuple2 (like {@code Point} sum). */
    private static PersistentTuple.PersistentTuple2 sum(PersistentTuple2 t1, PersistentTuple2 t2) {
        return PersistentTuple.create((Integer)t1.v0 + (Integer)t2.v0, (Integer)t1.v1 + (Integer)t2.v1);
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
        PersistentTuple2 t1 = PersistentTuple.create(argA, argB);
        PersistentTuple2 t2 = PersistentTuple.create(argB, argA);
        PersistentTuple2 combined = sum(t1, t2);
        return ((Integer) combined.nth(0)) + ((Integer) combined.nth(1));
    }

    @Benchmark
    public int twoTuplesSumThenConsumeNthOutOfLine() {
        PersistentTuple2 t1 = PersistentTuple.create(argA, argB);
        PersistentTuple2 t2 = PersistentTuple.create(argB, argA);
        PersistentTuple2 combined = TuplePeaOutOfLine.sum(t1, t2);
        return ((Integer) combined.nth(0)) + ((Integer) combined.nth(1));
    }

    @Benchmark
    public IPersistentVector twoTuplesSumMaterialized() {
        PersistentTuple2 t1 = PersistentTuple.create(argA, argB);
        PersistentTuple2 t2 = PersistentTuple.create(argB, argA);
        return sum(t1, t2);
    }

    /** Only allocate a tuple on the taken branch; other branch uses plain ints. */
    @Benchmark
    public int branchLazyTupleCreate(BranchParam p) {
        if (p.branch != 0) {
            PersistentTuple2 t = PersistentTuple.create(argA, argB);
            return sumNth0And1(t);
        }
        return argA + argB;
    }

    /** Both tuples always created; only one is read — flow-sensitive PEA per {@code @Param} fork. */
    @Benchmark
    public int branchPickOneTuple(BranchParam p) {
        PersistentTuple2 t1 = PersistentTuple.create(argA, argB);
        PersistentTuple2 t2 = PersistentTuple.create(argB, argA);
        if (p.branch != 0) {
            return sumNth0And1(t1);
        }
        return sumNth0And1(t2);
    }

    /** True branch uses {@code sum}; false branch adds slots without a combined tuple. */
    @Benchmark
    public int branchSumTupleOrDirectSlots(BranchParam p) {
        PersistentTuple2 t1 = PersistentTuple.create(argA, argB);
        PersistentTuple2 t2 = PersistentTuple.create(argB, argA);
        if (p.branch != 0) {
            PersistentTuple2 combined = sum(t1, t2);
            return sumNth0And1(combined);
        }
        return ((Integer) t1.nth(0)) + ((Integer) t1.nth(1))
                + ((Integer) t2.nth(0)) + ((Integer) t2.nth(1));
    }

    /** Compile-time constant condition ({@code argA < argB}); dead branch still in bytecode. */
    @Benchmark
    public int branchConstantConditionConsume() {
        PersistentTuple2 t1 = PersistentTuple.create(argA, argB);
        PersistentTuple2 t2 = PersistentTuple.create(argB, argA);
        if (argA < argB) {
            PersistentTuple2 combined = sum(t1, t2);
            return sumNth0And1(combined);
        }
        return sumNth0And1(t1) + sumNth0And1(t2);
    }

    /** One branch returns an escaping tuple; the other returns {@code int} via {@link Object}. */
    @Benchmark
    public Object branchMaterializeOrConsumeInt(BranchParam p) {
        PersistentTuple2 t1 = PersistentTuple.create(argA, argB);
        PersistentTuple2 t2 = PersistentTuple.create(argB, argA);
        if (p.branch != 0) {
            return sum(t1, t2);
        }
        PersistentTuple2 combined = sum(t1, t2);
        return sumNth0And1(combined);
    }

    @Benchmark
    @OperationsPerInvocation(LOOP_ITERS)
    public int loopCreateConsumeNth() {
        int acc = 0;
        for (int i = 0; i < LOOP_ITERS; i++) {
            PersistentTuple2 t = PersistentTuple.create(argA + i, argB + i);
            acc += sumNth0And1(t);
        }
        return acc;
    }

    @Benchmark
    @OperationsPerInvocation(LOOP_ITERS)
    public int loopPairSumEachIteration() {
        int acc = 0;
        for (int i = 0; i < LOOP_ITERS; i++) {
            PersistentTuple2 t1 = PersistentTuple.create(argA + i, argB);
            PersistentTuple2 t2 = PersistentTuple.create(argB, argA + i);
            PersistentTuple2 combined = sum(t1, t2);
            acc += sumNth0And1(combined);
        }
        return acc;
    }

    /** Carries a {@link PersistentTuple2} across iterations via {@code sum}; tests PEA through loop phis. */
    @Benchmark
    @OperationsPerInvocation(LOOP_ITERS)
    public int loopFoldSumCarry() {
        PersistentTuple2 acc = PersistentTuple.create(0, 0);
        for (int i = 1; i <= LOOP_ITERS; i++) {
            PersistentTuple2 step = PersistentTuple.create(i, i + 1);
            acc = sum(acc, step);
        }
        return sumNth0And1(acc);
    }

    @Benchmark
    @OperationsPerInvocation(LOOP_ITERS)
    public int loopAlternatingBranchConsume(BranchParam p) {
        int acc = 0;
        for (int i = 0; i < LOOP_ITERS; i++) {
            if ((i & 1) == p.branch) {
                PersistentTuple2 t1 = PersistentTuple.create(argA + i, argB);
                PersistentTuple2 t2 = PersistentTuple.create(argB, argA + i);
                PersistentTuple2 combined = sum(t1, t2);
                acc += sumNth0And1(combined);
            } else {
                PersistentTuple2 t = PersistentTuple.create(argA, argB + i);
                acc += sumNth0And1(t);
            }
        }
        return acc;
    }

    @Benchmark
    @OperationsPerInvocation(LOOP_ITERS)
    public int loopBranchParamSamePathEachIter(BranchParam p) {
        int acc = 0;
        for (int i = 0; i < LOOP_ITERS; i++) {
            PersistentTuple2 t1 = PersistentTuple.create(argA + i, argB);
            PersistentTuple2 t2 = PersistentTuple.create(argB, argA + i);
            if (p.branch != 0) {
                PersistentTuple2 combined = sum(t1, t2);
                acc += sumNth0And1(combined);
            } else {
                acc += ((Integer) t1.nth(0)) + ((Integer) t1.nth(1))
                        + ((Integer) t2.nth(0)) + ((Integer) t2.nth(1));
            }
        }
        return acc;
    }

    /** Control: one fresh tuple per iteration escapes into an array; elements are re-read. */
    @Benchmark
    @OperationsPerInvocation(LOOP_ITERS)
    public int loopMaterializeEveryIteration(Blackhole blackhole) {
        PersistentTuple2[] sink = new PersistentTuple2[LOOP_ITERS];
        int acc = 0;
        for (int i = 0; i < LOOP_ITERS; i++) {
            PersistentTuple2 t = PersistentTuple.create(argA + i, argB + i);
            sink[i] = t;
            blackhole.consume(t);
            acc += sumNth0And1(t);
        }
        for (int i = 0; i < LOOP_ITERS; i++) {
            blackhole.consume(sink[i]);
            acc += sumNth0And1(sink[i]);
        }
        return acc;
    }

    /** Same work as {@link #loopCreateConsumeNth} but {@code while (n > 0)} countdown — tests PEA through a loop phi. */
    @Benchmark
    public int whileCountdownConsume(TripParam p) {
        int acc = 0;
        int n = p.trips;
        while (n > 0) {
            PersistentTuple2 t = PersistentTuple.create(argA + n, argB + n);
            acc += sumNth0And1(t);
            n--;
        }
        return acc;
    }

    /** Accumulate until {@code acc >= trips * (argA + argB)}; trip bound comes from the param, not a for-limit. */
    @Benchmark
    public int whileAccBelowTargetConsume(TripParam p) {
        int target = p.trips * (argA + argB);
        int acc = 0;
        int i = 0;
        while (acc < target) {
            PersistentTuple2 t = PersistentTuple.create(argA + i, argB + i);
            acc += sumNth0And1(t);
            i++;
        }
        return acc;
    }

    /** Same fold as {@link #loopFoldSumCarry}, but trip count from {@link TripParam} (was {@code while}). */
    @Benchmark
    public int whileFoldSumCarry(TripParam p) {
        int trips = p.trips;
        PersistentTuple2 acc = PersistentTuple.create(0, 0);
        for (int i = 1; i <= trips; i++) {
            PersistentTuple2 step = PersistentTuple.create(i, i + 1);
            acc = sum(acc, step);
        }
        return sumNth0And1(acc);
    }

    /**
     * Same {@code for} fold as {@link #whileFoldSumCarry}, but {@code i <= LOOP_ITERS} so the bound is
     * compile-time constant (compare GC/PEA to {@code p.trips}).
     */
    @Benchmark
    public int forFoldSumCarryConstantLimit() {
        PersistentTuple2 acc = PersistentTuple.create(0, 0);
        for (int i = 1; i <= LOOP_ITERS; i++) {
            PersistentTuple2 step = PersistentTuple.create(i, i + 1);
            acc = sum(acc, step);
        }
        return sumNth0And1(acc);
    }

    @Benchmark
    public int whilePairSumCountdown(TripParam p) {
        int acc = 0;
        int n = p.trips;
        while (n > 0) {
            PersistentTuple2 t1 = PersistentTuple.create(argA + n, argB);
            PersistentTuple2 t2 = PersistentTuple.create(argB, argA + n);
            PersistentTuple2 combined = sum(t1, t2);
            acc += sumNth0And1(combined);
            n--;
        }
        return acc;
    }

    /** Pair with {@link #whileCountdownConsume}: identical trips, {@code for} shape (often full unroll). */
    @Benchmark
    public int forSameTripsAsWhileConsume(TripParam p) {
        int acc = 0;
        for (int i = 0; i < p.trips; i++) {
            PersistentTuple2 t = PersistentTuple.create(argA + i, argB + i);
            acc += sumNth0And1(t);
        }
        return acc;
    }

    @Benchmark
    public int whileMaterializeCountdown(TripParam p, Blackhole blackhole) {
        int acc = 0;
        int n = p.trips;
        while (n > 0) {
            PersistentTuple2 t = PersistentTuple.create(argA + n, argB + n);
            blackhole.consume(t);
            acc += sumNth0And1(t);
            n--;
        }
        return acc;
    }

    /**
     * Countdown; on the last trip ({@code n == 1}) return after consuming one tuple — loop exits early
     * without running the full trip count as accumulate-only.
     */
    @Benchmark
    public int whileEarlyReturnLastIterConsume(TripParam p) {
        int acc = 0;
        int n = p.trips;
        while (n > 0) {
            PersistentTuple2 t = PersistentTuple.create(argA + n, argB + n);
            if (n == 1) {
                return acc + sumNth0And1(t);
            }
            acc += sumNth0And1(t);
            n--;
        }
        return acc;
    }

    /** Same shape; early return hands the tuple to the caller (must allocate). */
    @Benchmark
    public IPersistentVector whileEarlyReturnLastIterMaterialize(TripParam p) {
        int n = p.trips;
        while (n > 0) {
            PersistentTuple2 t = PersistentTuple.create(argA + n, argB + n);
            if (n == 1) {
                return t;
            }
            n--;
        }
        return PersistentTuple.create(argA, argB);
    }

    /**
     * Fold {@code acc} with {@code sum} each trip, but return as soon as {@code n == 1} without the
     * final {@code sum} — tests PEA when a carried tuple meets an early {@code return int}.
     */
    @Benchmark
    public int whileEarlyReturnBeforeLastFold(TripParam p) {
        PersistentTuple2 acc = PersistentTuple.create(0, 0);
        int n = p.trips;
        while (n > 0) {
            if (n == 1) {
                return sumNth0And1(acc);
            }
            PersistentTuple2 step = PersistentTuple.create(n, n + 1);
            acc = sum(acc, step);
            n--;
        }
        return sumNth0And1(acc);
    }

    /**
     * {@code if} inside {@code while}: param chooses early-return-on-last-iter vs full accumulate.
     */
    @Benchmark
    public int whileEarlyReturnOrFullLoop(BranchParam b, TripParam p) {
        int acc = 0;
        int n = p.trips;
        while (n > 0) {
            PersistentTuple2 t = PersistentTuple.create(argA + n, argB + n);
            if (b.branch != 0 && n == 1) {
                return acc + sumNth0And1(t);
            }
            acc += sumNth0And1(t);
            n--;
        }
        return acc;
    }
}
