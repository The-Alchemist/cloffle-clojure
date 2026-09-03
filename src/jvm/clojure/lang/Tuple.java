/**
 * Copyright (c) Rich Hickey. All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 **/

/* rich 7/16/15 */
// proposed by Zach Tellman

package clojure.lang;

public class Tuple {
    public static final int MAX_SIZE = 8;

    public static IPersistentVector create() {
        return PersistentVector.EMPTY;
    }

    public static IPersistentVector create(Object v0) {
        return PersistentTuple.create(v0);
    }

    public static IPersistentVector create(Object v0, Object v1) {
        return PersistentTuple.create(v0, v1);
    }

    public static IPersistentVector create(Object v0, Object v1, Object v2) {
        return PersistentTuple.create(v0, v1, v2);
    }

    public static IPersistentVector create(Object v0, Object v1, Object v2, Object v3) {
        return PersistentTuple.create(v0, v1, v2, v3);
    }

    public static IPersistentVector create(Object v0, Object v1, Object v2, Object v3, Object v4) {
        return PersistentTuple.create(v0, v1, v2, v3, v4);
    }

    public static IPersistentVector create(Object v0, Object v1, Object v2, Object v3, Object v4, Object v5) {
        return PersistentTuple.create(v0, v1, v2, v3, v4, v5);
    }

    public static IPersistentVector create(Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6) {
        return PersistentTuple.create(v0, v1, v2, v3, v4, v5, v6);
    }

    public static IPersistentVector create(Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6, Object v7) {
        return PersistentTuple.create(v0, v1, v2, v3, v4, v5, v6, v7);
    }

    public static IPersistentVector createFromArray(Object[] items) {
        return PersistentTuple.createFromArray(items);
    }

    public static IPersistentVector createFromColl(Object coll) {
        return PersistentTuple.createFromColl(coll);
    }
}
