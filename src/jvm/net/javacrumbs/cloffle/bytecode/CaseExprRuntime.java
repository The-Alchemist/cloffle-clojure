package net.javacrumbs.cloffle.bytecode;

/**
 * Runtime helpers for {@link clojure.lang.Compiler.CaseExpr} emission in {@link ExprToBytecode}.
 * Mirrors the JVM {@code emit} path closely enough for int and hash dispatch keys.
 */
public final class CaseExprRuntime {

    private CaseExprRuntime() {}

    /**
     * Int / boxed-number path after {@code intValue()}, same as {@code CaseExpr.emitExprForInts}
     * when the tested expression is not a primitive (see {@code maybePrimitiveType(expr) == null}).
     */
    public static int intDispatchKey(Object d, int shift, int mask) {
        if (d == null) {
            return Integer.MIN_VALUE;
        }
        if (!(d instanceof Number)) {
            return Integer.MIN_VALUE;
        }
        int iv = ((Number) d).intValue();
        if (mask != 0) {
            iv = (iv >> shift) & mask;
        }
        return iv;
    }

    public static int hashDispatchKey(Object d, int shift, int mask) {
        int h = clojure.lang.Util.hash(d);
        if (mask != 0) {
            h = (h >> shift) & mask;
        }
        return h;
    }

    public static boolean intEq(Object a, Object b) {
        if (a == null || b == null) {
            return false;
        }
        return ((Number) a).intValue() == ((Number) b).intValue();
    }

    public static boolean identical(Object a, Object b) {
        return a == b;
    }
}
