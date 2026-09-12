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

import java.io.ObjectStreamException;
import java.io.Serializable;

/**
 * An unmemoized, immutable view sequence over an IPersistentVector for pure functions.
 * Marked with @ValueType and containing zero volatile fields or synchronization locks,
 * allowing GraalVM Partial Escape Analysis (PEA) to scalar-replace instances into CPU
 * registers during iterative traversal.
 */
@ValueType
public final class EphemeralVectorSeq extends ASeq implements IndexedSeq, IReduce, Counted, IPending, Indexed, Serializable {

    private static final long serialVersionUID = 1L;

    public final IFn f;
    public final IPersistentVector v;
    public final int i;

    @ValueType
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

    public EphemeralVectorSeq(IFn f, IPersistentVector v, int i) {
        this(null, f, v, i);
    }

    public EphemeralVectorSeq(IPersistentMap meta, IFn f, IPersistentVector v, int i) {
        super(meta);
        this.f = f;
        this.v = v;
        this.i = i;
    }

    public static ISeq create(IFn f, IPersistentVector v, int i) {
        if (v == null || i >= v.count() || i < 0) {
            return null;
        }
        return new EphemeralVectorSeq(f, v, i);
    }

    public static ISeq create(IFn f, IPersistentVector v) {
        return create(f, v, 0);
    }

    public static ISeq create(IFn g, Object coll, int i) {
        if (coll == null) {
            return null;
        }
        if (coll instanceof EphemeralVectorSeq evs) {
            int newIdx = evs.i + i;
            if (evs.v == null || newIdx >= evs.v.count() || newIdx < 0) {
                return null;
            }
            IFn composed = new ComposedFn(g, evs.f);
            return new EphemeralVectorSeq(composed, evs.v, newIdx);
        }
        if (coll instanceof MappedVectorSeq mvs) {
            int newIdx = mvs.i + i;
            if (mvs.v == null || newIdx >= mvs.v.count() || newIdx < 0) {
                return null;
            }
            IFn composed = new MappedVectorSeq.ComposedFn(g, mvs.f);
            return new MappedVectorSeq(composed, mvs.v, newIdx);
        }
        if (coll instanceof IPersistentVector v) {
            return create(g, v, i);
        }
        throw new IllegalArgumentException("EphemeralVectorSeq requires an IPersistentVector, EphemeralVectorSeq, or MappedVectorSeq, got: " + coll.getClass().getName());
    }

    public static ISeq create(IFn g, Object coll) {
        return create(g, coll, 0);
    }

    /**
     * Determines whether a function is known to be pure and side-effect free,
     * making unmemoized re-evaluation semantically unobservable.
     */
    public static boolean isPure(Object f) {
        if (f instanceof Keyword || f instanceof IPersistentSet || f instanceof IPersistentMap) {
            return true;
        }
        if (f instanceof ComposedFn cf) {
            return isPure(cf.g) && isPure(cf.f);
        }
        return false;
    }

    @Override
    public boolean isRealized() {
        return true;
    }

    @Override
    public Object first() {
        return f.invoke(v.nth(i));
    }

    @Override
    public ISeq next() {
        if (i + 1 >= v.count()) {
            return null;
        }
        return new EphemeralVectorSeq(meta(), f, v, i + 1);
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
        return f.invoke(v.nth(i + n));
    }

    @Override
    public Object nth(int n, Object notFound) {
        if (n >= 0 && n < count()) {
            return f.invoke(v.nth(i + n));
        }
        return notFound;
    }

    @Override
    public Object reduce(IFn rf, Object start) {
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
    public EphemeralVectorSeq withMeta(IPersistentMap meta) {
        if (meta() == meta) {
            return this;
        }
        return new EphemeralVectorSeq(meta, f, v, i);
    }

    private Object writeReplace() throws ObjectStreamException {
        return PersistentList.createListFromArray(RT.seqToArray(this));
    }
}
