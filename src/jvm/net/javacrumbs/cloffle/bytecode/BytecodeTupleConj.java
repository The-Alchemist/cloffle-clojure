package net.javacrumbs.cloffle.bytecode;

import clojure.lang.IPersistentCollection;
import clojure.lang.IPersistentMap;
import clojure.lang.PersistentTuple;
import clojure.lang.PersistentVector;
import clojure.lang.RT;

/** Implementation helpers for the {@code TupleConj} bytecode operation. */
final class BytecodeTupleConj {
    private BytecodeTupleConj() {
    }

    static Object nullConj(Object x) {
        return RT.conj(null, x);
    }

    static boolean isEmptyVector(PersistentVector coll) {
        return coll.count() == 0;
    }

    static Object emptyVector(PersistentVector coll, Object x) {
        IPersistentMap meta = coll.meta();
        return meta == null ? PersistentTuple.create(x) : new PersistentTuple.PersistentTuple1(meta, x);
    }

    static Object tuple1(PersistentTuple.PersistentTuple1 coll, Object x) {
        return new PersistentTuple.PersistentTuple2(coll.meta(), coll.v0, x);
    }

    static Object tuple2(PersistentTuple.PersistentTuple2 coll, Object x) {
        return new PersistentTuple.PersistentTuple3(coll.meta(), coll.v0, coll.v1, x);
    }

    static Object tuple3(PersistentTuple.PersistentTuple3 coll, Object x) {
        return new PersistentTuple.PersistentTuple4(coll.meta(), coll.v0, coll.v1, coll.v2, x);
    }

    static Object tuple4(PersistentTuple.PersistentTuple4 coll, Object x) {
        return new PersistentTuple.PersistentTuple5(coll.meta(), coll.v0, coll.v1, coll.v2, coll.v3, x);
    }

    static Object tuple5(PersistentTuple.PersistentTuple5 coll, Object x) {
        return new PersistentTuple.PersistentTuple6(coll.meta(), coll.v0, coll.v1, coll.v2, coll.v3, coll.v4, x);
    }

    static Object tuple6(PersistentTuple.PersistentTuple6 coll, Object x) {
        return new PersistentTuple.PersistentTuple7(
                coll.meta(), coll.v0, coll.v1, coll.v2, coll.v3, coll.v4, coll.v5, x);
    }

    static Object tuple7(PersistentTuple.PersistentTuple7 coll, Object x) {
        return new PersistentTuple.PersistentTuple8(
                coll.meta(), coll.v0, coll.v1, coll.v2, coll.v3, coll.v4, coll.v5, coll.v6, x);
    }

    static Object tuple8(PersistentTuple.PersistentTuple8 coll, Object x) {
        return coll.cons(x);
    }

    static Object generic(Object coll, Object x) {
        return RT.conj((IPersistentCollection) coll, x);
    }
}
