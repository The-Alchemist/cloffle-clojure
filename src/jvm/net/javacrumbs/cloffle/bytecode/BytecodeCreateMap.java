package net.javacrumbs.cloffle.bytecode;

import clojure.lang.IObj;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.MapShape;
import clojure.lang.PersistentShapeMap;
import clojure.lang.RT;

final class BytecodeCreateMap {
    private BytecodeCreateMap() {
    }

    static Object empty() {
        return PersistentShapeMap.EMPTY;
    }

    static Object standard(Object[] keyvals) {
        return RT.map(keyvals);
    }

    static Object set(Object[] items) {
        return RT.set(items);
    }

    static Object withMeta(Object obj, IPersistentMap meta) {
        if (obj instanceof IObj iobj) {
            return iobj.withMeta(meta);
        }
        return obj;
    }

    static boolean isKeyword(Object obj) {
        return obj instanceof Keyword;
    }

    static boolean areKeywords(Object k0, Object k1) {
        return k0 instanceof Keyword && k1 instanceof Keyword;
    }

    static boolean areKeywords(Object k0, Object k1, Object k2) {
        return k0 instanceof Keyword && k1 instanceof Keyword && k2 instanceof Keyword;
    }

    static boolean areKeywords(Object k0, Object k1, Object k2, Object k3) {
        return k0 instanceof Keyword && k1 instanceof Keyword && k2 instanceof Keyword && k3 instanceof Keyword;
    }

    static boolean areKeywords(Object k0, Object k1, Object k2, Object k3, Object k4) {
        return k0 instanceof Keyword && k1 instanceof Keyword && k2 instanceof Keyword && k3 instanceof Keyword
                && k4 instanceof Keyword;
    }

    static boolean areKeywords(Object k0, Object k1, Object k2, Object k3, Object k4, Object k5) {
        return k0 instanceof Keyword && k1 instanceof Keyword && k2 instanceof Keyword && k3 instanceof Keyword
                && k4 instanceof Keyword && k5 instanceof Keyword;
    }

    static boolean areKeywords(Object k0, Object k1, Object k2, Object k3, Object k4, Object k5, Object k6) {
        return k0 instanceof Keyword && k1 instanceof Keyword && k2 instanceof Keyword && k3 instanceof Keyword
                && k4 instanceof Keyword && k5 instanceof Keyword && k6 instanceof Keyword;
    }

    static boolean areKeywords(Object k0, Object k1, Object k2, Object k3, Object k4, Object k5, Object k6, Object k7) {
        return k0 instanceof Keyword && k1 instanceof Keyword && k2 instanceof Keyword && k3 instanceof Keyword
                && k4 instanceof Keyword && k5 instanceof Keyword && k6 instanceof Keyword && k7 instanceof Keyword;
    }

    static PersistentShapeMap.Shape1 shape1(Keyword k0) {
        return PersistentShapeMap.shape1(k0);
    }

    static PersistentShapeMap.Shape2 shape2(Keyword k0, Keyword k1) {
        return PersistentShapeMap.shape2(k0, k1);
    }

    static PersistentShapeMap.Shape3 shape3(Keyword k0, Keyword k1, Keyword k2) {
        return PersistentShapeMap.shape3(k0, k1, k2);
    }

    static PersistentShapeMap.Shape4 shape4(Keyword k0, Keyword k1, Keyword k2, Keyword k3) {
        return PersistentShapeMap.shape4(k0, k1, k2, k3);
    }

    static PersistentShapeMap.Shape5 shape5(Keyword k0, Keyword k1, Keyword k2, Keyword k3, Keyword k4) {
        return PersistentShapeMap.shape5(k0, k1, k2, k3, k4);
    }

    static PersistentShapeMap.Shape6 shape6(Keyword k0, Keyword k1, Keyword k2, Keyword k3, Keyword k4, Keyword k5) {
        return PersistentShapeMap.shape6(k0, k1, k2, k3, k4, k5);
    }

    static PersistentShapeMap.Shape7 shape7(Keyword k0, Keyword k1, Keyword k2, Keyword k3, Keyword k4, Keyword k5, Keyword k6) {
        return PersistentShapeMap.shape7(k0, k1, k2, k3, k4, k5, k6);
    }

    static PersistentShapeMap.Shape8 shape8(Keyword k0, Keyword k1, Keyword k2, Keyword k3, Keyword k4, Keyword k5, Keyword k6, Keyword k7) {
        return PersistentShapeMap.shape8(k0, k1, k2, k3, k4, k5, k6, k7);
    }

    // ── Shaped creation (constant-operand path) ──────────────────────────

    static Object createShaped1(MapShape.Factory f, Object v0) {
        return new PersistentShapeMap(null, f.shape,
                v0, null, null, null, null, null, null, null);
    }

    static Object createShaped2(MapShape.Factory f, Object v0, Object v1) {
        int s0 = f.sourceIndex(0), s1 = f.sourceIndex(1);
        return new PersistentShapeMap(null, f.shape,
                pick2(s0, v0, v1), pick2(s1, v0, v1),
                null, null, null, null, null, null);
    }

    static Object createShaped3(MapShape.Factory f, Object v0, Object v1, Object v2) {
        int s0 = f.sourceIndex(0), s1 = f.sourceIndex(1), s2 = f.sourceIndex(2);
        return new PersistentShapeMap(null, f.shape,
                pick3(s0, v0, v1, v2), pick3(s1, v0, v1, v2), pick3(s2, v0, v1, v2),
                null, null, null, null, null);
    }

    static Object createShaped4(MapShape.Factory f, Object v0, Object v1, Object v2, Object v3) {
        int s0 = f.sourceIndex(0), s1 = f.sourceIndex(1), s2 = f.sourceIndex(2), s3 = f.sourceIndex(3);
        return new PersistentShapeMap(null, f.shape,
                pick4(s0, v0, v1, v2, v3), pick4(s1, v0, v1, v2, v3),
                pick4(s2, v0, v1, v2, v3), pick4(s3, v0, v1, v2, v3),
                null, null, null, null);
    }

    static Object createShaped5(MapShape.Factory f, Object v0, Object v1, Object v2, Object v3, Object v4) {
        int s0 = f.sourceIndex(0), s1 = f.sourceIndex(1), s2 = f.sourceIndex(2), s3 = f.sourceIndex(3), s4 = f.sourceIndex(4);
        return new PersistentShapeMap(null, f.shape,
                pick5(s0, v0, v1, v2, v3, v4), pick5(s1, v0, v1, v2, v3, v4),
                pick5(s2, v0, v1, v2, v3, v4), pick5(s3, v0, v1, v2, v3, v4),
                pick5(s4, v0, v1, v2, v3, v4), null, null, null);
    }

    static Object createShaped6(MapShape.Factory f, Object v0, Object v1, Object v2, Object v3, Object v4, Object v5) {
        int s0 = f.sourceIndex(0), s1 = f.sourceIndex(1), s2 = f.sourceIndex(2), s3 = f.sourceIndex(3), s4 = f.sourceIndex(4), s5 = f.sourceIndex(5);
        return new PersistentShapeMap(null, f.shape,
                pick6(s0, v0, v1, v2, v3, v4, v5), pick6(s1, v0, v1, v2, v3, v4, v5),
                pick6(s2, v0, v1, v2, v3, v4, v5), pick6(s3, v0, v1, v2, v3, v4, v5),
                pick6(s4, v0, v1, v2, v3, v4, v5), pick6(s5, v0, v1, v2, v3, v4, v5),
                null, null);
    }

    static Object createShaped7(MapShape.Factory f, Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6) {
        int s0 = f.sourceIndex(0), s1 = f.sourceIndex(1), s2 = f.sourceIndex(2), s3 = f.sourceIndex(3),
            s4 = f.sourceIndex(4), s5 = f.sourceIndex(5), s6 = f.sourceIndex(6);
        return new PersistentShapeMap(null, f.shape,
                pick7(s0, v0, v1, v2, v3, v4, v5, v6), pick7(s1, v0, v1, v2, v3, v4, v5, v6),
                pick7(s2, v0, v1, v2, v3, v4, v5, v6), pick7(s3, v0, v1, v2, v3, v4, v5, v6),
                pick7(s4, v0, v1, v2, v3, v4, v5, v6), pick7(s5, v0, v1, v2, v3, v4, v5, v6),
                pick7(s6, v0, v1, v2, v3, v4, v5, v6), null);
    }

    static Object createShaped8(MapShape.Factory f, Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6, Object v7) {
        int s0 = f.sourceIndex(0), s1 = f.sourceIndex(1), s2 = f.sourceIndex(2), s3 = f.sourceIndex(3),
            s4 = f.sourceIndex(4), s5 = f.sourceIndex(5), s6 = f.sourceIndex(6), s7 = f.sourceIndex(7);
        return new PersistentShapeMap(null, f.shape,
                pick8(s0, v0, v1, v2, v3, v4, v5, v6, v7), pick8(s1, v0, v1, v2, v3, v4, v5, v6, v7),
                pick8(s2, v0, v1, v2, v3, v4, v5, v6, v7), pick8(s3, v0, v1, v2, v3, v4, v5, v6, v7),
                pick8(s4, v0, v1, v2, v3, v4, v5, v6, v7), pick8(s5, v0, v1, v2, v3, v4, v5, v6, v7),
                pick8(s6, v0, v1, v2, v3, v4, v5, v6, v7), pick8(s7, v0, v1, v2, v3, v4, v5, v6, v7));
    }

    private static Object pick2(int p, Object v0, Object v1) {
        return p == 0 ? v0 : v1;
    }

    private static Object pick3(int p, Object v0, Object v1, Object v2) {
        return switch (p) { case 0 -> v0; case 1 -> v1; default -> v2; };
    }

    private static Object pick4(int p, Object v0, Object v1, Object v2, Object v3) {
        return switch (p) { case 0 -> v0; case 1 -> v1; case 2 -> v2; default -> v3; };
    }

    private static Object pick5(int p, Object v0, Object v1, Object v2, Object v3, Object v4) {
        return switch (p) { case 0 -> v0; case 1 -> v1; case 2 -> v2; case 3 -> v3; default -> v4; };
    }

    private static Object pick6(int p, Object v0, Object v1, Object v2, Object v3, Object v4, Object v5) {
        return switch (p) { case 0 -> v0; case 1 -> v1; case 2 -> v2; case 3 -> v3; case 4 -> v4; default -> v5; };
    }

    private static Object pick7(int p, Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6) {
        return switch (p) { case 0 -> v0; case 1 -> v1; case 2 -> v2; case 3 -> v3; case 4 -> v4; case 5 -> v5; default -> v6; };
    }

    private static Object pick8(int p, Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6, Object v7) {
        return switch (p) { case 0 -> v0; case 1 -> v1; case 2 -> v2; case 3 -> v3; case 4 -> v4; case 5 -> v5; case 6 -> v6; default -> v7; };
    }
}
