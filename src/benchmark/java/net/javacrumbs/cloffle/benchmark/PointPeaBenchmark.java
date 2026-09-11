package net.javacrumbs.cloffle.benchmark;

import com.oracle.truffle.api.CompilerDirectives;
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
 * Classic {@code Point(x,y)} partial escape analysis on the host Graal/C2 compiler.
 * <p>
 * {@code inMethod} and {@code viaPrivateHelper} should scalar-replace when the {@code Point}
 * does not escape the benchmark. {@code twoPointsSumThenConsumeFields} allocates two inputs plus
 * a {@code sum(p1,p2)} result, but PEA can remove all three when only field sums are returned.
 * {@code twoPointsSumMaterialized} escapes the summed point (24 B/op). {@code viaTruffleBoundaryHelper} is identical at the Java
 * level; {@link CompilerDirectives.TruffleBoundary} affects Truffle partial evaluation, not
 * this JMH hot loop unless the method is compiled through Truffle. {@code materialized}
 * is a positive control that returns the allocated object.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class PointPeaBenchmark {

    @State(Scope.Benchmark)
    public static class BranchParam {
        @Param({"0", "1"})
        public int branch;
    }

    @State(Scope.Benchmark)
    public static class TripParam {
        @Param({"8", "16", "32"})
        public int trips;
    }

    public static final class Point {
        public final int x;
        public final int y;

        public Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final int LOOP_ITERS = 16;

    private int argA = 2;
    private int argB = 3;

    private static int sumFields(Point p) {
        return p.x + p.y;
    }

    /** Adds two points and allocates a new {@code Point}; PEA may still remove it if it does not escape. */
    private static Point sum(Point p1, Point p2) {
        return new Point(p1.x + p2.x, p1.y + p2.y);
    }

    @CompilerDirectives.TruffleBoundary
    private static int sumFieldsTruffleBoundary(Point p) {
        return p.x + p.y;
    }

    /** Allocate and read fields in the same method. */
    @Benchmark
    public int inMethod() {
        Point p = new Point(argA, argB);
        return p.x + p.y;
    }

    /** Same logic via a small private helper (typically inlined, then PEA sees one region). */
    @Benchmark
    public int viaPrivateHelper() {
        Point p = new Point(argA, argB);
        return sumFields(p);
    }

    /** Same as helper; boundary annotation is for Truffle PE, not host JIT in this JMH setup. */
    @Benchmark
    public int viaTruffleBoundaryHelper() {
        Point p = new Point(argA, argB);
        return sumFieldsTruffleBoundary(p);
    }

    /** Cross-class call; Graal may still inline, but this is a slightly harder boundary. */
    @Benchmark
    public int viaOutOfLineHelper() {
        Point p = new Point(argA, argB);
        return PointPeaOutOfLine.sumFields(p);
    }

    /** Positive control: allocation escapes to the JMH return value. */
    @Benchmark
    public Point materialized() {
        return new Point(argA, argB);
    }

    /** Two local points, {@code sum} allocates a third; consume as {@code int} only. */
    @Benchmark
    public int twoPointsSumThenConsumeFields() {
        Point p1 = new Point(argA, argB);
        Point p2 = new Point(argB, argA);
        Point combined = sum(p1, p2);
        return combined.x + combined.y;
    }

    /** Same via cross-class {@code sum}. */
    @Benchmark
    public int twoPointsSumThenConsumeFieldsOutOfLine() {
        Point p1 = new Point(argA, argB);
        Point p2 = new Point(argB, argA);
        Point combined = PointPeaOutOfLine.sum(p1, p2);
        return combined.x + combined.y;
    }

    /** Control: the summed {@code Point} escapes to the caller. */
    @Benchmark
    public Point twoPointsSumMaterialized() {
        Point p1 = new Point(argA, argB);
        Point p2 = new Point(argB, argA);
        return sum(p1, p2);
    }

    @Benchmark
    public int branchLazyPointCreate(BranchParam p) {
        if (p.branch != 0) {
            Point pt = new Point(argA, argB);
            return sumFields(pt);
        }
        return argA + argB;
    }

    @Benchmark
    public int branchPickOnePoint(BranchParam p) {
        Point p1 = new Point(argA, argB);
        Point p2 = new Point(argB, argA);
        if (p.branch != 0) {
            return sumFields(p1);
        }
        return sumFields(p2);
    }

    @Benchmark
    public int branchSumPointOrDirectFields(BranchParam p) {
        Point p1 = new Point(argA, argB);
        Point p2 = new Point(argB, argA);
        if (p.branch != 0) {
            Point combined = sum(p1, p2);
            return sumFields(combined);
        }
        return p1.x + p1.y + p2.x + p2.y;
    }

    @Benchmark
    public Object branchMaterializeOrConsumeInt(BranchParam p) {
        Point p1 = new Point(argA, argB);
        Point p2 = new Point(argB, argA);
        if (p.branch != 0) {
            return sum(p1, p2);
        }
        Point combined = sum(p1, p2);
        return sumFields(combined);
    }

    @Benchmark
    @OperationsPerInvocation(LOOP_ITERS)
    public int loopCreateConsumeFields() {
        int acc = 0;
        for (int i = 0; i < LOOP_ITERS; i++) {
            Point p = new Point(argA + i, argB + i);
            acc += sumFields(p);
        }
        return acc;
    }

    @Benchmark
    @OperationsPerInvocation(LOOP_ITERS)
    public int loopPairSumEachIteration() {
        int acc = 0;
        for (int i = 0; i < LOOP_ITERS; i++) {
            Point p1 = new Point(argA + i, argB);
            Point p2 = new Point(argB, argA + i);
            Point combined = sum(p1, p2);
            acc += sumFields(combined);
        }
        return acc;
    }

    @Benchmark
    @OperationsPerInvocation(LOOP_ITERS)
    public int loopFoldSumCarry() {
        Point acc = new Point(0, 0);
        for (int i = 1; i <= LOOP_ITERS; i++) {
            Point step = new Point(i, i + 1);
            acc = sum(acc, step);
        }
        return sumFields(acc);
    }

    @Benchmark
    @OperationsPerInvocation(LOOP_ITERS)
    public int loopMaterializeEveryIteration(Blackhole blackhole) {
        Point[] sink = new Point[LOOP_ITERS];
        int acc = 0;
        for (int i = 0; i < LOOP_ITERS; i++) {
            Point p = new Point(argA + i, argB + i);
            sink[i] = p;
            blackhole.consume(p);
            acc += sumFields(p);
        }
        for (int j = 0; j < LOOP_ITERS; j++) {
            blackhole.consume(sink[j]);
            acc += sumFields(sink[j]);
        }
        return acc;
    }

    @Benchmark
    public int whileCountdownConsume(TripParam p) {
        int acc = 0;
        int n = p.trips;
        while (n > 0) {
            Point pt = new Point(argA + n, argB + n);
            acc += sumFields(pt);
            n--;
        }
        return acc;
    }

    @Benchmark
    public int whileFoldSumCarry(TripParam p) {
        Point acc = new Point(0, 0);
        int n = p.trips;
        while (n > 0) {
            Point step = new Point(n, n + 1);
            acc = sum(acc, step);
            n--;
        }
        return sumFields(acc);
    }

    @Benchmark
    public int whileMaterializeCountdown(TripParam p, Blackhole blackhole) {
        int acc = 0;
        int n = p.trips;
        while (n > 0) {
            Point pt = new Point(argA + n, argB + n);
            blackhole.consume(pt);
            acc += sumFields(pt);
            n--;
        }
        return acc;
    }
}
