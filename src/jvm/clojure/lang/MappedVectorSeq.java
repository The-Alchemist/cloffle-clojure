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

import java.io.Serializable;

public final class MappedVectorSeq extends ASeq implements IndexedSeq, IReduce, Counted, IPending, Indexed, Serializable {

    private static final long serialVersionUID = 1L;
    private static final Object UNREALIZED = new Object();

    public final IFn f;
    public final IPersistentVector v;
    public final int i;

    private volatile Object _val = UNREALIZED;
    private volatile ISeq _next = null;

    public static final class ComposedFn extends AFn implements Serializable {
        private static final long serialVersionUID = 1L;
        public final IFn g;
        public final IFn f;

        public ComposedFn(IFn g, IFn f) {
            this.g = g;
            this.f = f;
        }

        @Override
        public Object invoke(Object arg) {
            return g.invoke(f.invoke(arg));
        }
    }

    public MappedVectorSeq(IFn f, IPersistentVector v, int i) {
        this(null, f, v, i);
    }

    public MappedVectorSeq(IPersistentMap meta, IFn f, IPersistentVector v, int i) {
        super(meta);
        this.f = f;
        this.v = v;
        this.i = i;
    }

    public static ISeq create(IFn f, IPersistentVector v, int i) {
        if (v == null || i >= v.count() || i < 0) {
            return null;
        }
        return new MappedVectorSeq(f, v, i);
    }

    public static ISeq create(IFn f, IPersistentVector v) {
        return create(f, v, 0);
    }

    public static ISeq create(IFn g, Object coll, int i) {
        if (coll == null) {
            return null;
        }
        if (coll instanceof MappedVectorSeq mvs) {
            int newIdx = mvs.i + i;
            if (mvs.v == null || newIdx >= mvs.v.count() || newIdx < 0) {
                return null;
            }
            IFn composed = new ComposedFn(g, mvs.f);
            return new MappedVectorSeq(composed, mvs.v, newIdx);
        }
        if (coll instanceof EphemeralVectorSeq evs) {
            int newIdx = evs.i + i;
            if (evs.v == null || newIdx >= evs.v.count() || newIdx < 0) {
                return null;
            }
            IFn composed = new ComposedFn(g, evs.f);
            return new MappedVectorSeq(composed, evs.v, newIdx);
        }
        if (coll instanceof IPersistentVector v) {
            return create(g, v, i);
        }
        throw new IllegalArgumentException("MappedVectorSeq requires an IPersistentVector, MappedVectorSeq, or EphemeralVectorSeq, got: " + coll.getClass().getName());
    }

    public static ISeq create(IFn g, Object coll) {
        return create(g, coll, 0);
    }

    @Override
    public boolean isRealized() {
        return _val != UNREALIZED;
    }

    @Override
    public Object first() {
        if (_val == UNREALIZED) {
            synchronized (this) {
                if (_val == UNREALIZED) {
                    _val = f.invoke(v.nth(i));
                }
            }
        }
        return _val;
    }

    @Override
    public ISeq next() {
        first();
        if (i + 1 >= v.count()) {
            return null;
        }
        if (_next == null) {
            synchronized (this) {
                if (_next == null) {
                    _next = new MappedVectorSeq(f, v, i + 1);
                }
            }
        }
        return _next;
    }

    @Override
    public int count() {
        return Math.max(0, v.count() - i);
    }

    @Override
    public int index() {
        return i;
    }

    @Override
    public Object nth(int n) {
        if (n < 0 || n >= count()) {
            throw new IndexOutOfBoundsException();
        }
        ISeq s = this;
        for (int k = 0; k < n; k++) {
            s = s.next();
        }
        return s.first();
    }

    @Override
    public Object nth(int n, Object notFound) {
        if (n >= 0 && n < count()) {
            return nth(n);
        }
        return notFound;
    }

    @Override
    public Object reduce(IFn rf, Object start) {
        if (_val != UNREALIZED) {
            Object acc = start;
            for (ISeq s = this; s != null; s = s.next()) {
                acc = rf.invoke(acc, s.first());
                if (RT.isReduced(acc)) {
                    return ((IDeref) acc).deref();
                }
            }
            return acc;
        }
        Object acc = start;
        int n = v.count();
        for (int x = i; x < n; x++) {
            acc = rf.invoke(acc, f.invoke(v.nth(x)));
            if (RT.isReduced(acc)) {
                return ((IDeref) acc).deref();
            }
        }
        return acc;
    }

    @Override
    public Object reduce(IFn rf) {
        if (_val != UNREALIZED) {
            Object acc = first();
            for (ISeq s = next(); s != null; s = s.next()) {
                acc = rf.invoke(acc, s.first());
                if (RT.isReduced(acc)) {
                    return ((IDeref) acc).deref();
                }
            }
            return acc;
        }
        int n = v.count();
        if (i >= n) {
            return rf.invoke();
        }
        Object acc = f.invoke(v.nth(i));
        if (RT.isReduced(acc)) {
            return ((IDeref) acc).deref();
        }
        for (int x = i + 1; x < n; x++) {
            acc = rf.invoke(acc, f.invoke(v.nth(x)));
            if (RT.isReduced(acc)) {
                return ((IDeref) acc).deref();
            }
        }
        return acc;
    }

    @Override
    public MappedVectorSeq withMeta(IPersistentMap meta) {
        if (meta() == meta) {
            return this;
        }
        MappedVectorSeq ret = new MappedVectorSeq(meta, f, v, i);
        ret._val = this._val;
        ret._next = this._next;
        return ret;
    }
}
