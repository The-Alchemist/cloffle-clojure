package net.javacrumbs.cloffle.bytecode;

import clojure.lang.IObj;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.MapShape;
import clojure.lang.PersistentShapeMap;
import clojure.lang.PersistentShapeMap16;
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
        return new PersistentShapeMap(null, f.shape,
                v0, v1, null, null, null, null, null, null);
    }

    static Object createShaped3(MapShape.Factory f, Object v0, Object v1, Object v2) {
        return new PersistentShapeMap(null, f.shape,
                v0, v1, v2, null, null, null, null, null);
    }

    static Object createShaped4(MapShape.Factory f, Object v0, Object v1, Object v2, Object v3) {
        return new PersistentShapeMap(null, f.shape,
                v0, v1, v2, v3, null, null, null, null);
    }

    static Object createShaped5(MapShape.Factory f, Object v0, Object v1, Object v2, Object v3, Object v4) {
        return new PersistentShapeMap(null, f.shape,
                v0, v1, v2, v3, v4, null, null, null);
    }

    static Object createShaped6(MapShape.Factory f, Object v0, Object v1, Object v2, Object v3, Object v4, Object v5) {
        return new PersistentShapeMap(null, f.shape,
                v0, v1, v2, v3, v4, v5, null, null);
    }

    static Object createShaped7(MapShape.Factory f, Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6) {
        return new PersistentShapeMap(null, f.shape,
                v0, v1, v2, v3, v4, v5, v6, null);
    }

    static Object createShaped8(MapShape.Factory f, Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6, Object v7) {
        return new PersistentShapeMap(null, f.shape,
                v0, v1, v2, v3, v4, v5, v6, v7);
    }

    static Object createShaped16(PersistentShapeMap16.Factory f,
                                 Object v0, Object v1, Object v2, Object v3,
                                 Object v4, Object v5, Object v6, Object v7,
                                 Object v8, Object v9, Object v10, Object v11,
                                 Object v12, Object v13, Object v14, Object v15) {
        return new PersistentShapeMap16(null, f.count, f.tags0, f.tags1,
                f.k0, v0, f.k1, v1, f.k2, v2, f.k3, v3,
                f.k4, v4, f.k5, v5, f.k6, v6, f.k7, v7,
                f.k8, v8, f.k9, v9, f.k10, v10, f.k11, v11,
                f.k12, v12, f.k13, v13, f.k14, v14, f.k15, v15);
    }
}
