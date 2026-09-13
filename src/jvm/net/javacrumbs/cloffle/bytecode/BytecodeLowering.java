package net.javacrumbs.cloffle.bytecode;

/**
 * Shared helpers for Tier-2 / core bytecode ops (e.g. {@code clojure.core/str} argument conversion).
 * {@code :cloffle/op} emission is gated on {@code :direct-linking}; those sites intentionally ignore
 * {@code with-redefs}, so there is no sanctioned-root / {@code doRedefined} path here.
 */
final class BytecodeLowering {
    private BytecodeLowering() {
    }

    /** Match {@code clojure.core/str} on one arg: nil → "", else {@code toString()}. */
    static String str(Object x) {
        return x == null ? "" : x.toString();
    }

    static String str(Object a, Object b) {
        return str(a) + str(b);
    }

    static String str(Object a, Object b, Object c) {
        return str(a) + str(b) + str(c);
    }

    static String str(Object a, Object b, Object c, Object d) {
        return str(a) + str(b) + str(c) + str(d);
    }
}
