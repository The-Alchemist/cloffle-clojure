package net.javacrumbs.cloffle.benchmark;

/** Separate class so the call is not trivially same-class inlined. */
final class PointPeaOutOfLine {
    private PointPeaOutOfLine() {
    }

    static int sumFields(PointPeaBenchmark.Point p) {
        return p.x + p.y;
    }

    static PointPeaBenchmark.Point sum(PointPeaBenchmark.Point p1, PointPeaBenchmark.Point p2) {
        return new PointPeaBenchmark.Point(p1.x + p2.x, p1.y + p2.y);
    }
}
