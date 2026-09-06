package net.javacrumbs.cloffle.bytecode;

import clojure.lang.IObj;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
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
}
