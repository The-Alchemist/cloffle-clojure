package net.javacrumbs.cloffle.benchmark;

import com.oracle.truffle.api.CompilerDirectives;
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

    public static final class Point {
        public final int x;
        public final int y;

        public Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

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
}
