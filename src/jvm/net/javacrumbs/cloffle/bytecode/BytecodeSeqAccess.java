package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Counted;
import clojure.lang.EphemeralVectorSeq;
import clojure.lang.Indexed;
import clojure.lang.IPersistentVector;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.PersistentList;
import clojure.lang.PersistentTuple;
import clojure.lang.RT;

final class BytecodeSeqAccess {
    private BytecodeSeqAccess() {
    }

    static boolean isIndexed(Object coll) {
        return coll instanceof Indexed;
    }

    static boolean isVectorOrSeq(Object coll) {
        return coll instanceof IPersistentVector || coll instanceof ISeq;
    }

    static boolean isTuple(Object coll) {
        return coll instanceof PersistentTuple;
    }

    static boolean isSeq(Object coll) {
        return coll instanceof ISeq;
    }

    static boolean isCounted(Object coll) {
        return coll instanceof Counted;
    }

    static int index(Object n) {
        return (n instanceof Number num) ? num.intValue() : 0;
    }

    static Object restTuple2(PersistentTuple.PersistentTuple2 t) {
        return PersistentTuple.create(t.v1);
    }

    static Object restTuple3(PersistentTuple.PersistentTuple3 t) {
        return PersistentTuple.create(t.v1, t.v2);
    }

    static Object restTuple4(PersistentTuple.PersistentTuple4 t) {
        return PersistentTuple.create(t.v1, t.v2, t.v3);
    }

    static Object restTuple5(PersistentTuple.PersistentTuple5 t) {
        return PersistentTuple.create(t.v1, t.v2, t.v3, t.v4);
    }

    static Object restTuple6(PersistentTuple.PersistentTuple6 t) {
        return PersistentTuple.create(t.v1, t.v2, t.v3, t.v4, t.v5);
    }

    static Object restTuple7(PersistentTuple.PersistentTuple7 t) {
        return PersistentTuple.create(t.v1, t.v2, t.v3, t.v4, t.v5, t.v6);
    }

    static Object restTuple8(PersistentTuple.PersistentTuple8 t) {
        return PersistentTuple.create(t.v1, t.v2, t.v3, t.v4, t.v5, t.v6, t.v7);
    }

    static Object restList2(PersistentList.PersistentList2 xs) {
        return new PersistentList.PersistentList1(xs.meta(), xs.e1);
    }

    static Object restList3(PersistentList.PersistentList3 xs) {
        return new PersistentList.PersistentList2(xs.meta(), xs.e1, xs.e2);
    }

    static Object restList4(PersistentList.PersistentList4 xs) {
        return new PersistentList.PersistentList3(xs.meta(), xs.e1, xs.e2, xs.e3);
    }

    static Object restList5(PersistentList.PersistentList5 xs) {
        return new PersistentList.PersistentList4(xs.meta(), xs.e1, xs.e2, xs.e3, xs.e4);
    }

    static Object restList6(PersistentList.PersistentList6 xs) {
        return new PersistentList.PersistentList5(xs.meta(), xs.e1, xs.e2, xs.e3, xs.e4, xs.e5);
    }

    static Object restList7(PersistentList.PersistentList7 xs) {
        return new PersistentList.PersistentList6(xs.meta(), xs.e1, xs.e2, xs.e3, xs.e4, xs.e5, xs.e6);
    }

    static Object restList8(PersistentList.PersistentList8 xs) {
        return new PersistentList.PersistentList7(xs.meta(), xs.e1, xs.e2, xs.e3, xs.e4, xs.e5, xs.e6, xs.e7);
    }

    static Object moreGeneric(Object coll) {
        return RT.more(coll);
    }

    static Object nextGeneric(Object coll) {
        return RT.next(coll);
    }

    static Object firstGeneric(Object coll) {
        return RT.first(coll);
    }

    static Object nthGeneric(Object coll, Object n) {
        return RT.nth(coll, index(n));
    }

    static Object nthGeneric(Object coll, Object n, Object notFound) {
        return RT.nth(coll, index(n), notFound);
    }

    static int countFallback(Object coll) {
        return RT.count(coll);
    }

    static Object nthIndexed(Indexed coll, Object n) {
        return coll.nth(index(n));
    }

    static Object nthIndexed(Indexed coll, Object n, Object notFound) {
        return coll.nth(index(n), notFound);
    }

    static boolean isPersistentVector(Object v) {
        return v instanceof IPersistentVector;
    }

    static boolean isEmptyVector(Object v) {
        return v instanceof IPersistentVector pv && pv.count() == 0;
    }

    /** {@code (:kw (first v))} without materializing the seq; null for an empty vector. */
    static Object keywordAtHead(Keyword keyword, IPersistentVector v) {
        return BytecodeKeywordMaps.lookupGeneric(keyword, v.nth(0));
    }

    static Object keywordAtHeadChecked(Keyword keyword, IPersistentVector v) {
        return v.count() == 0 ? null : keywordAtHead(keyword, v);
    }

    static Object evsCreate(Keyword keyword, IPersistentVector v, int i) {
        return EphemeralVectorSeq.create(keyword, v, i);
    }

    static Object evsCreate(Keyword keyword, IPersistentVector v, long i) {
        return EphemeralVectorSeq.create(keyword, v, (int) i);
    }

    static Object evsCreateGeneric(Keyword keyword, Object v, Object i) {
        if (!(v instanceof IPersistentVector pv)) {
            throw new IllegalArgumentException(
                    "EphemeralVectorSeq requires IPersistentVector, got: "
                            + (v == null ? "null" : v.getClass().getName()));
        }
        int idx = index(i);
        return EphemeralVectorSeq.create(keyword, pv, idx);
    }

    static boolean isOutOfRange(Object v, int i) {
        return v instanceof IPersistentVector pv && (i < 0 || i >= pv.count());
    }

    static boolean isOutOfRange(Object v, Object i) {
        return isOutOfRange(v, index(i));
    }
}
