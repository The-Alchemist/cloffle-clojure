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

public final class MappedMapSeq extends ASeq implements IReduce, Counted, IPending, Serializable {

    private static final long serialVersionUID = 1L;
    private static final Object UNREALIZED = new Object();

    public final IFn f;
    public final IPersistentMap m;
    public final ISeq entries;
    public final boolean isHead;

    private volatile Object _val = UNREALIZED;
    private volatile ISeq _next = null;

    public MappedMapSeq(IFn f, IPersistentMap m, ISeq entries) {
        this(null, f, m, entries, true);
    }

    public MappedMapSeq(IFn f, IPersistentMap m, ISeq entries, boolean isHead) {
        this(null, f, m, entries, isHead);
    }

    public MappedMapSeq(IPersistentMap meta, IFn f, IPersistentMap m, ISeq entries, boolean isHead) {
        super(meta);
        this.f = f;
        this.m = m;
        this.entries = entries;
        this.isHead = isHead;
    }

    public static ISeq create(IFn f, IPersistentMap m) {
        if (m == null || m.count() == 0) {
            return null;
        }
        ISeq entries = m.seq();
        if (entries == null) {
            return null;
        }
        return new MappedMapSeq(f, m, entries, true);
    }

    public static ISeq create(IFn g, Object coll) {
        if (coll == null) {
            return null;
        }
        if (coll instanceof MappedMapSeq mms) {
            IFn composed = new MappedVectorSeq.ComposedFn(g, mms.f);
            return new MappedMapSeq(composed, mms.m, mms.entries, mms.isHead);
        }
        if (coll instanceof IPersistentMap m) {
            return create(g, m);
        }
        throw new IllegalArgumentException("MappedMapSeq requires an IPersistentMap or MappedMapSeq, got: " + coll.getClass().getName());
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
                    _val = f.invoke(entries.first());
                }
            }
        }
        return _val;
    }

    @Override
    public ISeq next() {
        first();
        ISeq nextEntries = entries.next();
        if (nextEntries == null) {
            return null;
        }
        if (_next == null) {
            synchronized (this) {
                if (_next == null) {
                    _next = new MappedMapSeq(f, m, nextEntries, false);
                }
            }
        }
        return _next;
    }

    @Override
    public int count() {
        if (isHead) {
            return m.count();
        }
        if (entries instanceof Counted c) {
            return c.count();
        }
        return super.count();
    }

    @Override
    public Object reduce(IFn rf, Object start) {
        if (RT.isReduced(start)) {
            return ((IDeref) start).deref();
        }
        if (isHead && m instanceof IKVReduce kvm) {
            return kvm.kvreduce(new AFn() {
                @Override
                public Object invoke(Object acc, Object k, Object v) {
                    Object item = f.invoke(MapEntry.create(k, v));
                    return rf.invoke(acc, item);
                }
            }, start);
        }
        ISeq s = entries;
        Object acc = start;
        while (s != null) {
            acc = rf.invoke(acc, f.invoke(s.first()));
            if (RT.isReduced(acc)) {
                return ((IDeref) acc).deref();
            }
            s = s.next();
        }
        return acc;
    }

    @Override
    public Object reduce(IFn rf) {
        if (isHead && m instanceof IKVReduce kvm) {
            final Object sentinel = new Object();
            Object ret = kvm.kvreduce(new AFn() {
                Object acc = sentinel;
                @Override
                public Object invoke(Object ignored, Object k, Object v) {
                    Object item = f.invoke(MapEntry.create(k, v));
                    if (acc == sentinel) {
                        acc = item;
                        return acc;
                    }
                    acc = rf.invoke(acc, item);
                    return acc;
                }
            }, sentinel);
            if (ret == sentinel) {
                return rf.invoke();
            }
            if (RT.isReduced(ret)) {
                return ((IDeref) ret).deref();
            }
            return ret;
        }
        if (entries == null) {
            return rf.invoke();
        }
        Object acc = f.invoke(entries.first());
        if (RT.isReduced(acc)) {
            return ((IDeref) acc).deref();
        }
        ISeq s = entries.next();
        while (s != null) {
            acc = rf.invoke(acc, f.invoke(s.first()));
            if (RT.isReduced(acc)) {
                return ((IDeref) acc).deref();
            }
            s = s.next();
        }
        return acc;
    }

    @Override
    public MappedMapSeq withMeta(IPersistentMap meta) {
        if (meta() == meta) {
            return this;
        }
        MappedMapSeq ret = new MappedMapSeq(meta, f, m, entries, isHead);
        ret._val = this._val;
        ret._next = this._next;
        return ret;
    }
}
