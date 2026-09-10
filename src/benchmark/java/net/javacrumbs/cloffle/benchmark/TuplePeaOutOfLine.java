package net.javacrumbs.cloffle.benchmark;

import clojure.lang.IPersistentVector;
import clojure.lang.PersistentTuple;

final class TuplePeaOutOfLine {
    private TuplePeaOutOfLine() {
    }

    static int sumNth0And1(IPersistentVector t) {
        return ((Integer) t.nth(0)) + ((Integer) t.nth(1));
    }

    static IPersistentVector sum(IPersistentVector t1, IPersistentVector t2) {
        return PersistentTuple.create(
                (Integer) t1.nth(0) + (Integer) t2.nth(0),
                (Integer) t1.nth(1) + (Integer) t2.nth(1));
    }
}
