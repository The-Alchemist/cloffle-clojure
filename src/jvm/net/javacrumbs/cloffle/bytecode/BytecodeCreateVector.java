package net.javacrumbs.cloffle.bytecode;

import clojure.lang.PersistentVector;
import clojure.lang.RT;
import clojure.lang.Tuple;

final class BytecodeCreateVector {
    private BytecodeCreateVector() {
    }

    static Object create0() {
        return PersistentVector.EMPTY;
    }

    static Object create1(Object v0) {
        return Tuple.create(v0);
    }

    static Object create2(Object v0, Object v1) {
        return Tuple.create(v0, v1);
    }

    static Object create3(Object v0, Object v1, Object v2) {
        return Tuple.create(v0, v1, v2);
    }

    static Object create4(Object v0, Object v1, Object v2, Object v3) {
        return Tuple.create(v0, v1, v2, v3);
    }

    static Object create5(Object v0, Object v1, Object v2, Object v3, Object v4) {
        return Tuple.create(v0, v1, v2, v3, v4);
    }

    static Object create6(Object v0, Object v1, Object v2, Object v3, Object v4, Object v5) {
        return Tuple.create(v0, v1, v2, v3, v4, v5);
    }

    static Object create7(Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6) {
        return Tuple.create(v0, v1, v2, v3, v4, v5, v6);
    }

    static Object create8(Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6, Object v7) {
        return Tuple.create(v0, v1, v2, v3, v4, v5, v6, v7);
    }

    static Object createN(Object[] items) {
        return RT.vector(items);
    }
}
