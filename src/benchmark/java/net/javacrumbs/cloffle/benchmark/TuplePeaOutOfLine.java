package net.javacrumbs.cloffle.benchmark;

import clojure.lang.PersistentTuple;
import clojure.lang.PersistentTuple.PersistentTuple2;

final class TuplePeaOutOfLine {
    private TuplePeaOutOfLine() {
    }

    static int sumNth0And1(PersistentTuple2 t) {
        return ((Integer) t.nth(0)) + ((Integer) t.nth(1));
    }

    static PersistentTuple2 sum(PersistentTuple2 t1, PersistentTuple2 t2) {
        return PersistentTuple.create(
                (Integer) t1.nth(0) + (Integer) t2.nth(0),
                (Integer) t1.nth(1) + (Integer) t2.nth(1));
    }
}
