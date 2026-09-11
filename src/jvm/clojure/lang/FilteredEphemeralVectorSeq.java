/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

package clojure.lang;

import com.oracle.truffle.api.CompilerDirectives.ValueType;

import java.io.Serializable;

/**
 * Indexed walk over {@link IPersistentVector} keeping only elements matching {@code pred}.
 * Optional {@code mapF} applies on retained elements (for {@code (map f (filter pred v))} fusion).
 */
@ValueType
public final class FilteredEphemeralVectorSeq extends ASeq
        implements IndexedSeq, IReduce, Counted, IPending, Indexed, Serializable {

    private static final long serialVersionUID = 1L;

    public final IFn pred;
    public final IFn mapF;
    public final IPersistentVector v;
    public final int i;

    public FilteredEphemeralVectorSeq(IFn pred, IFn mapF, IPersistentVector v, int i) {
        this(null, pred, mapF, v, i);
    }

    public FilteredEphemeralVectorSeq(IPersistentMap meta, IFn pred, IFn mapF, IPersistentVector v, int i) {
        super(meta);
        this.pred = pred;
        this.mapF = mapF;
        this.v = v;
        this.i = i;
    }

    public static ISeq create(IFn pred, IPersistentVector v, int start) {
        return createMapped(null, pred, v, start);
    }

    public static ISeq createMapped(IFn mapF, IFn pred, IPersistentVector v, int start) {
        if (pred == null || v == null || start < 0 || start >= v.count()) {
            return null;
        }
        int k = nextMatch(pred, v, start);
        if (k < 0) {
            return null;
        }
        return new FilteredEphemeralVectorSeq(pred, mapF, v, k);
    }

    private static int nextMatch(IFn pred, IPersistentVector v, int from) {
        for (int x = from; x < v.count(); x++) {
            if (RT.booleanCast(pred.invoke(v.nth(x)))) {
                return x;
            }
        }
        return -1;
    }

    private int currentIndex() {
        return i;
    }

    @Override
    public boolean isRealized() {
        return true;
    }

    @Override
    public Object first() {
        Object elt = v.nth(i);
        return mapF != null ? mapF.invoke(elt) : elt;
    }

    @Override
    public ISeq next() {
        int n = nextMatch(pred, v, i + 1);
        if (n < 0) {
            return null;
        }
        return new FilteredEphemeralVectorSeq(meta(), pred, mapF, v, n);
    }

    @Override
    public int count() {
        int c = 0;
        for (int x = i; x < v.count(); x++) {
            if (RT.booleanCast(pred.invoke(v.nth(x)))) {
                c++;
            }
        }
        return c;
    }

    @Override
    public int index() {
        return 0;
    }

    @Override
    public Object nth(int n) {
        if (n < 0) {
            throw new IndexOutOfBoundsException();
        }
        int seen = 0;
        for (int x = i; x < v.count(); x++) {
            if (RT.booleanCast(pred.invoke(v.nth(x)))) {
                if (seen == n) {
                    Object elt = v.nth(x);
                    return mapF != null ? mapF.invoke(elt) : elt;
                }
                seen++;
            }
        }
        throw new IndexOutOfBoundsException();
    }

    @Override
    public Object nth(int n, Object notFound) {
        try {
            return nth(n);
        } catch (IndexOutOfBoundsException e) {
            return notFound;
        }
    }

    @Override
    public Object reduce(IFn rf, Object start) {
        Object acc = start;
        for (int x = i; x < v.count(); x++) {
            if (RT.booleanCast(pred.invoke(v.nth(x)))) {
                Object elt = v.nth(x);
                Object val = mapF != null ? mapF.invoke(elt) : elt;
                acc = rf.invoke(acc, val);
                if (RT.isReduced(acc)) {
                    return ((IDeref) acc).deref();
                }
            }
        }
        return acc;
    }

    @Override
    public Object reduce(IFn rf) {
        int k = currentIndex();
        if (k < 0 || k >= v.count()) {
            return rf.invoke();
        }
        boolean first = true;
        Object acc = null;
        for (int x = k; x < v.count(); x++) {
            if (RT.booleanCast(pred.invoke(v.nth(x)))) {
                Object elt = v.nth(x);
                Object val = mapF != null ? mapF.invoke(elt) : elt;
                if (first) {
                    acc = val;
                    first = false;
                } else {
                    acc = rf.invoke(acc, val);
                    if (RT.isReduced(acc)) {
                        return ((IDeref) acc).deref();
                    }
                }
            }
        }
        return first ? rf.invoke() : acc;
    }

    @Override
    public FilteredEphemeralVectorSeq withMeta(IPersistentMap meta) {
        if (meta() == meta) {
            return this;
        }
        return new FilteredEphemeralVectorSeq(meta, pred, mapF, v, i);
    }

    /**
     * {@code (into [] (comp (map f) (filter pred)) v)} — filter on vector elements, then map.
     */
    public static IPersistentVector materializeFilterThenMap(IFn mapF, IFn pred, IPersistentVector v) {
        if (pred == null || v == null) {
            return PersistentVector.EMPTY;
        }
        IPersistentVector acc = PersistentVector.EMPTY;
        for (int i = 0; i < v.count(); i++) {
            Object elt = v.nth(i);
            if (RT.booleanCast(pred.invoke(elt))) {
                Object val = mapF != null ? mapF.invoke(elt) : elt;
                acc = (IPersistentVector) acc.cons(val);
            }
        }
        return acc;
    }

    /**
     * {@code (into [] (comp (filter pred) (map f)) v)} — map each element, then filter mapped values.
     */
    public static IPersistentVector materializeMapThenFilter(IFn mapF, IFn pred, IPersistentVector v) {
        if (pred == null || v == null) {
            return PersistentVector.EMPTY;
        }
        IPersistentVector acc = PersistentVector.EMPTY;
        for (int i = 0; i < v.count(); i++) {
            Object mapped = mapF != null ? mapF.invoke(v.nth(i)) : v.nth(i);
            if (RT.booleanCast(pred.invoke(mapped))) {
                acc = (IPersistentVector) acc.cons(mapped);
            }
        }
        return acc;
    }
}
