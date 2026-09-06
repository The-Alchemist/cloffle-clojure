package net.javacrumbs.cloffle.bytecode;

import clojure.lang.PersistentList;
import clojure.lang.RT;

final class BytecodeCreateList {
    private BytecodeCreateList() {
    }

    static Object create0() {
        return PersistentList.EMPTY;
    }

    static Object create1(Object e0) {
        return PersistentList.createList(e0);
    }

    static Object create2(Object e0, Object e1) {
        return PersistentList.createList(e0, e1);
    }

    static Object create3(Object e0, Object e1, Object e2) {
        return PersistentList.createList(e0, e1, e2);
    }

    static Object create4(Object e0, Object e1, Object e2, Object e3) {
        return PersistentList.createList(e0, e1, e2, e3);
    }

    static Object create5(Object e0, Object e1, Object e2, Object e3, Object e4) {
        return PersistentList.createList(e0, e1, e2, e3, e4);
    }

    static Object create6(Object e0, Object e1, Object e2, Object e3, Object e4, Object e5) {
        return PersistentList.createList(e0, e1, e2, e3, e4, e5);
    }

    static Object create7(Object e0, Object e1, Object e2, Object e3, Object e4, Object e5, Object e6) {
        return PersistentList.createList(e0, e1, e2, e3, e4, e5, e6);
    }

    static Object create8(Object e0, Object e1, Object e2, Object e3, Object e4, Object e5, Object e6, Object e7) {
        return PersistentList.createList(e0, e1, e2, e3, e4, e5, e6, e7);
    }

    static Object createN(Object[] items) {
        return RT.arrayToList(items);
    }
}
