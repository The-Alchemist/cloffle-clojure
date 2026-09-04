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
import java.util.*;

public abstract class PersistentTuple extends APersistentVector implements IObj, IReduce, IKVReduce, IDrop, IEditableCollection {

    public static final IPersistentVector EMPTY = PersistentVector.EMPTY;

    final IPersistentMap _meta;

    PersistentTuple(IPersistentMap meta) {
        this._meta = meta;
    }

    @Override
    public IPersistentMap meta() {
        return _meta;
    }

    @Override
    public IPersistentCollection empty() {
        return PersistentVector.EMPTY.withMeta(_meta);
    }

    @Override
    public ITransientCollection asTransient() {
        ITransientCollection ret = PersistentVector.EMPTY.asTransient();
        for (int i = 0; i < count(); i++) {
            ret = ret.conj(nth(i));
        }
        return ret;
    }

    @Override
    public Sequential drop(int n) {
        if (n <= 0)
            return this;
        if (n >= count())
            return (Sequential) PersistentVector.EMPTY;
        return (Sequential) new APersistentVector.SubVector(_meta, this, n, count());
    }

    public static PersistentTuple1 create(Object v0) {
        return new PersistentTuple1(null, v0);
    }

    public static PersistentTuple2 create(Object v0, Object v1) {
        return new PersistentTuple2(null, v0, v1);
    }

    public static PersistentTuple3 create(Object v0, Object v1, Object v2) {
        return new PersistentTuple3(null, v0, v1, v2);
    }

    public static PersistentTuple4 create(Object v0, Object v1, Object v2, Object v3) {
        return new PersistentTuple4(null, v0, v1, v2, v3);
    }

    public static PersistentTuple5 create(Object v0, Object v1, Object v2, Object v3, Object v4) {
        return new PersistentTuple5(null, v0, v1, v2, v3, v4);
    }

    public static PersistentTuple6 create(Object v0, Object v1, Object v2, Object v3, Object v4, Object v5) {
        return new PersistentTuple6(null, v0, v1, v2, v3, v4, v5);
    }

    public static PersistentTuple7 create(Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6) {
        return new PersistentTuple7(null, v0, v1, v2, v3, v4, v5, v6);
    }

    public static PersistentTuple8 create(Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6, Object v7) {
        return new PersistentTuple8(null, v0, v1, v2, v3, v4, v5, v6, v7);
    }

    public static IPersistentVector createFromArray(Object[] items) {
        switch (items.length) {
            case 0: return PersistentVector.EMPTY;
            case 1: return create(items[0]);
            case 2: return create(items[0], items[1]);
            case 3: return create(items[0], items[1], items[2]);
            case 4: return create(items[0], items[1], items[2], items[3]);
            case 5: return create(items[0], items[1], items[2], items[3], items[4]);
            case 6: return create(items[0], items[1], items[2], items[3], items[4], items[5]);
            case 7: return create(items[0], items[1], items[2], items[3], items[4], items[5], items[6]);
            case 8: return create(items[0], items[1], items[2], items[3], items[4], items[5], items[6], items[7]);
            default: return PersistentVector.adopt(items);
        }
    }

    public static IPersistentVector createFromColl(Object coll) {
        if (coll instanceof IPersistentVector && !(coll instanceof ITransientCollection)) {
            return (IPersistentVector) coll;
        }
        if (coll instanceof RandomAccess && coll instanceof List) {
            List l = (List) coll;
            switch (l.size()) {
                case 0: return PersistentVector.EMPTY;
                case 1: return create(l.get(0));
                case 2: return create(l.get(0), l.get(1));
                case 3: return create(l.get(0), l.get(1), l.get(2));
                case 4: return create(l.get(0), l.get(1), l.get(2), l.get(3));
                case 5: return create(l.get(0), l.get(1), l.get(2), l.get(3), l.get(4));
                case 6: return create(l.get(0), l.get(1), l.get(2), l.get(3), l.get(4), l.get(5));
                case 7: return create(l.get(0), l.get(1), l.get(2), l.get(3), l.get(4), l.get(5), l.get(6));
                case 8: return create(l.get(0), l.get(1), l.get(2), l.get(3), l.get(4), l.get(5), l.get(6), l.get(7));
            }
        }
        ISeq seq = RT.seq(coll);
        if (seq == null) return PersistentVector.EMPTY;
        Object v0 = seq.first(); seq = seq.next();
        if (seq == null) return create(v0);
        Object v1 = seq.first(); seq = seq.next();
        if (seq == null) return create(v0, v1);
        Object v2 = seq.first(); seq = seq.next();
        if (seq == null) return create(v0, v1, v2);
        Object v3 = seq.first(); seq = seq.next();
        if (seq == null) return create(v0, v1, v2, v3);
        Object v4 = seq.first(); seq = seq.next();
        if (seq == null) return create(v0, v1, v2, v3, v4);
        Object v5 = seq.first(); seq = seq.next();
        if (seq == null) return create(v0, v1, v2, v3, v4, v5);
        Object v6 = seq.first(); seq = seq.next();
        if (seq == null) return create(v0, v1, v2, v3, v4, v5, v6);
        Object v7 = seq.first(); seq = seq.next();
        if (seq == null) return create(v0, v1, v2, v3, v4, v5, v6, v7);
        return PersistentVector.create(coll);
    }

    public static final class PersistentTuple1 extends PersistentTuple {
        public final Object v0;

        public PersistentTuple1(IPersistentMap meta, Object v0) {
            super(meta);
            this.v0 = v0;
        }

        @Override
        public int count() {
            return 1;
        }

        @Override
        public Object nth(int i) {
            if (i == 0) return v0;
            throw new IndexOutOfBoundsException();
        }

        @Override
        public Object nth(int i, Object notFound) {
            if (i == 0) return v0;
            return notFound;
        }

        @Override
        public IPersistentVector assocN(int i, Object val) {
            switch (i) {
                case 0: return new PersistentTuple1(_meta, val);
                case 1: return new PersistentTuple2(_meta, v0, val);
                default: throw new IndexOutOfBoundsException();
            }
        }

        @Override
        public IPersistentVector cons(Object val) {
            return new PersistentTuple2(_meta, v0, val);
        }

        @Override
        public IPersistentStack pop() {
            return PersistentVector.EMPTY.withMeta(_meta);
        }

        @Override
        public PersistentTuple1 withMeta(IPersistentMap meta) {
            if (meta == _meta) return this;
            return new PersistentTuple1(meta, v0);
        }

        @Override
        public Object reduce(IFn f) {
            return v0;
        }

        @Override
        public Object reduce(IFn f, Object start) {
            Object ret = f.invoke(start, v0);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }

        @Override
        public Object kvreduce(IFn f, Object init) {
            Object ret = f.invoke(init, 0, v0);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }
    }

    public static final class PersistentTuple2 extends PersistentTuple {
        public final Object v0, v1;

        public PersistentTuple2(IPersistentMap meta, Object v0, Object v1) {
            super(meta);
            this.v0 = v0;
            this.v1 = v1;
        }

        @Override
        public int count() {
            return 2;
        }

        @Override
        public Object nth(int i) {
            switch (i) {
                case 0: return v0;
                case 1: return v1;
                default: throw new IndexOutOfBoundsException();
            }
        }

        @Override
        public Object nth(int i, Object notFound) {
            switch (i) {
                case 0: return v0;
                case 1: return v1;
                default: return notFound;
            }
        }

        @Override
        public IPersistentVector assocN(int i, Object val) {
            switch (i) {
                case 0: return new PersistentTuple2(_meta, val, v1);
                case 1: return new PersistentTuple2(_meta, v0, val);
                case 2: return new PersistentTuple3(_meta, v0, v1, val);
                default: throw new IndexOutOfBoundsException();
            }
        }

        @Override
        public IPersistentVector cons(Object val) {
            return new PersistentTuple3(_meta, v0, v1, val);
        }

        @Override
        public IPersistentStack pop() {
            return new PersistentTuple1(_meta, v0);
        }

        @Override
        public PersistentTuple2 withMeta(IPersistentMap meta) {
            if (meta == _meta) return this;
            return new PersistentTuple2(meta, v0, v1);
        }

        @Override
        public Object reduce(IFn f) {
            Object ret = f.invoke(v0, v1);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }

        @Override
        public Object reduce(IFn f, Object start) {
            Object ret = f.invoke(start, v0);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v1);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }

        @Override
        public Object kvreduce(IFn f, Object init) {
            Object ret = f.invoke(init, 0, v0);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 1, v1);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }
    }

    public static final class PersistentTuple3 extends PersistentTuple {
        public final Object v0, v1, v2;

        public PersistentTuple3(IPersistentMap meta, Object v0, Object v1, Object v2) {
            super(meta);
            this.v0 = v0;
            this.v1 = v1;
            this.v2 = v2;
        }

        @Override
        public int count() {
            return 3;
        }

        @Override
        public Object nth(int i) {
            switch (i) {
                case 0: return v0;
                case 1: return v1;
                case 2: return v2;
                default: throw new IndexOutOfBoundsException();
            }
        }

        @Override
        public Object nth(int i, Object notFound) {
            switch (i) {
                case 0: return v0;
                case 1: return v1;
                case 2: return v2;
                default: return notFound;
            }
        }

        @Override
        public IPersistentVector assocN(int i, Object val) {
            switch (i) {
                case 0: return new PersistentTuple3(_meta, val, v1, v2);
                case 1: return new PersistentTuple3(_meta, v0, val, v2);
                case 2: return new PersistentTuple3(_meta, v0, v1, val);
                case 3: return new PersistentTuple4(_meta, v0, v1, v2, val);
                default: throw new IndexOutOfBoundsException();
            }
        }

        @Override
        public IPersistentVector cons(Object val) {
            return new PersistentTuple4(_meta, v0, v1, v2, val);
        }

        @Override
        public IPersistentStack pop() {
            return new PersistentTuple2(_meta, v0, v1);
        }

        @Override
        public PersistentTuple3 withMeta(IPersistentMap meta) {
            if (meta == _meta) return this;
            return new PersistentTuple3(meta, v0, v1, v2);
        }

        @Override
        public Object reduce(IFn f) {
            Object ret = f.invoke(v0, v1);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v2);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }

        @Override
        public Object reduce(IFn f, Object start) {
            Object ret = f.invoke(start, v0);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v1);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v2);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }

        @Override
        public Object kvreduce(IFn f, Object init) {
            Object ret = f.invoke(init, 0, v0);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 1, v1);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 2, v2);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }
    }

    public static final class PersistentTuple4 extends PersistentTuple {
        public final Object v0, v1, v2, v3;

        public PersistentTuple4(IPersistentMap meta, Object v0, Object v1, Object v2, Object v3) {
            super(meta);
            this.v0 = v0;
            this.v1 = v1;
            this.v2 = v2;
            this.v3 = v3;
        }

        @Override
        public int count() {
            return 4;
        }

        @Override
        public Object nth(int i) {
            switch (i) {
                case 0: return v0;
                case 1: return v1;
                case 2: return v2;
                case 3: return v3;
                default: throw new IndexOutOfBoundsException();
            }
        }

        @Override
        public Object nth(int i, Object notFound) {
            switch (i) {
                case 0: return v0;
                case 1: return v1;
                case 2: return v2;
                case 3: return v3;
                default: return notFound;
            }
        }

        @Override
        public IPersistentVector assocN(int i, Object val) {
            switch (i) {
                case 0: return new PersistentTuple4(_meta, val, v1, v2, v3);
                case 1: return new PersistentTuple4(_meta, v0, val, v2, v3);
                case 2: return new PersistentTuple4(_meta, v0, v1, val, v3);
                case 3: return new PersistentTuple4(_meta, v0, v1, v2, val);
                case 4: return new PersistentTuple5(_meta, v0, v1, v2, v3, val);
                default: throw new IndexOutOfBoundsException();
            }
        }

        @Override
        public IPersistentVector cons(Object val) {
            return new PersistentTuple5(_meta, v0, v1, v2, v3, val);
        }

        @Override
        public IPersistentStack pop() {
            return new PersistentTuple3(_meta, v0, v1, v2);
        }

        @Override
        public PersistentTuple4 withMeta(IPersistentMap meta) {
            if (meta == _meta) return this;
            return new PersistentTuple4(meta, v0, v1, v2, v3);
        }

        @Override
        public Object reduce(IFn f) {
            Object ret = f.invoke(v0, v1);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v2);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v3);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }

        @Override
        public Object reduce(IFn f, Object start) {
            Object ret = f.invoke(start, v0);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v1);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v2);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v3);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }

        @Override
        public Object kvreduce(IFn f, Object init) {
            Object ret = f.invoke(init, 0, v0);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 1, v1);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 2, v2);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 3, v3);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }
    }

    public static final class PersistentTuple5 extends PersistentTuple {
        public final Object v0, v1, v2, v3, v4;

        public PersistentTuple5(IPersistentMap meta, Object v0, Object v1, Object v2, Object v3, Object v4) {
            super(meta);
            this.v0 = v0;
            this.v1 = v1;
            this.v2 = v2;
            this.v3 = v3;
            this.v4 = v4;
        }

        @Override
        public int count() {
            return 5;
        }

        @Override
        public Object nth(int i) {
            switch (i) {
                case 0: return v0;
                case 1: return v1;
                case 2: return v2;
                case 3: return v3;
                case 4: return v4;
                default: throw new IndexOutOfBoundsException();
            }
        }

        @Override
        public Object nth(int i, Object notFound) {
            switch (i) {
                case 0: return v0;
                case 1: return v1;
                case 2: return v2;
                case 3: return v3;
                case 4: return v4;
                default: return notFound;
            }
        }

        @Override
        public IPersistentVector assocN(int i, Object val) {
            switch (i) {
                case 0: return new PersistentTuple5(_meta, val, v1, v2, v3, v4);
                case 1: return new PersistentTuple5(_meta, v0, val, v2, v3, v4);
                case 2: return new PersistentTuple5(_meta, v0, v1, val, v3, v4);
                case 3: return new PersistentTuple5(_meta, v0, v1, v2, val, v4);
                case 4: return new PersistentTuple5(_meta, v0, v1, v2, v3, val);
                case 5: return new PersistentTuple6(_meta, v0, v1, v2, v3, v4, val);
                default: throw new IndexOutOfBoundsException();
            }
        }

        @Override
        public IPersistentVector cons(Object val) {
            return new PersistentTuple6(_meta, v0, v1, v2, v3, v4, val);
        }

        @Override
        public IPersistentStack pop() {
            return new PersistentTuple4(_meta, v0, v1, v2, v3);
        }

        @Override
        public PersistentTuple5 withMeta(IPersistentMap meta) {
            if (meta == _meta) return this;
            return new PersistentTuple5(meta, v0, v1, v2, v3, v4);
        }

        @Override
        public Object reduce(IFn f) {
            Object ret = f.invoke(v0, v1);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v2);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v3);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v4);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }

        @Override
        public Object reduce(IFn f, Object start) {
            Object ret = f.invoke(start, v0);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v1);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v2);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v3);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v4);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }

        @Override
        public Object kvreduce(IFn f, Object init) {
            Object ret = f.invoke(init, 0, v0);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 1, v1);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 2, v2);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 3, v3);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 4, v4);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }
    }

    public static final class PersistentTuple6 extends PersistentTuple {
        public final Object v0, v1, v2, v3, v4, v5;

        public PersistentTuple6(IPersistentMap meta, Object v0, Object v1, Object v2, Object v3, Object v4, Object v5) {
            super(meta);
            this.v0 = v0;
            this.v1 = v1;
            this.v2 = v2;
            this.v3 = v3;
            this.v4 = v4;
            this.v5 = v5;
        }

        @Override
        public int count() {
            return 6;
        }

        @Override
        public Object nth(int i) {
            switch (i) {
                case 0: return v0;
                case 1: return v1;
                case 2: return v2;
                case 3: return v3;
                case 4: return v4;
                case 5: return v5;
                default: throw new IndexOutOfBoundsException();
            }
        }

        @Override
        public Object nth(int i, Object notFound) {
            switch (i) {
                case 0: return v0;
                case 1: return v1;
                case 2: return v2;
                case 3: return v3;
                case 4: return v4;
                case 5: return v5;
                default: return notFound;
            }
        }

        @Override
        public IPersistentVector assocN(int i, Object val) {
            switch (i) {
                case 0: return new PersistentTuple6(_meta, val, v1, v2, v3, v4, v5);
                case 1: return new PersistentTuple6(_meta, v0, val, v2, v3, v4, v5);
                case 2: return new PersistentTuple6(_meta, v0, v1, val, v3, v4, v5);
                case 3: return new PersistentTuple6(_meta, v0, v1, v2, val, v4, v5);
                case 4: return new PersistentTuple6(_meta, v0, v1, v2, v3, val, v5);
                case 5: return new PersistentTuple6(_meta, v0, v1, v2, v3, v4, val);
                case 6: return new PersistentTuple7(_meta, v0, v1, v2, v3, v4, v5, val);
                default: throw new IndexOutOfBoundsException();
            }
        }

        @Override
        public IPersistentVector cons(Object val) {
            return new PersistentTuple7(_meta, v0, v1, v2, v3, v4, v5, val);
        }

        @Override
        public IPersistentStack pop() {
            return new PersistentTuple5(_meta, v0, v1, v2, v3, v4);
        }

        @Override
        public PersistentTuple6 withMeta(IPersistentMap meta) {
            if (meta == _meta) return this;
            return new PersistentTuple6(meta, v0, v1, v2, v3, v4, v5);
        }

        @Override
        public Object reduce(IFn f) {
            Object ret = f.invoke(v0, v1);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v2);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v3);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v4);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v5);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }

        @Override
        public Object reduce(IFn f, Object start) {
            Object ret = f.invoke(start, v0);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v1);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v2);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v3);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v4);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v5);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }

        @Override
        public Object kvreduce(IFn f, Object init) {
            Object ret = f.invoke(init, 0, v0);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 1, v1);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 2, v2);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 3, v3);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 4, v4);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 5, v5);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }
    }

    public static final class PersistentTuple7 extends PersistentTuple {
        public final Object v0, v1, v2, v3, v4, v5, v6;

        public PersistentTuple7(IPersistentMap meta, Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6) {
            super(meta);
            this.v0 = v0;
            this.v1 = v1;
            this.v2 = v2;
            this.v3 = v3;
            this.v4 = v4;
            this.v5 = v5;
            this.v6 = v6;
        }

        @Override
        public int count() {
            return 7;
        }

        @Override
        public Object nth(int i) {
            switch (i) {
                case 0: return v0;
                case 1: return v1;
                case 2: return v2;
                case 3: return v3;
                case 4: return v4;
                case 5: return v5;
                case 6: return v6;
                default: throw new IndexOutOfBoundsException();
            }
        }

        @Override
        public Object nth(int i, Object notFound) {
            switch (i) {
                case 0: return v0;
                case 1: return v1;
                case 2: return v2;
                case 3: return v3;
                case 4: return v4;
                case 5: return v5;
                case 6: return v6;
                default: return notFound;
            }
        }

        @Override
        public IPersistentVector assocN(int i, Object val) {
            switch (i) {
                case 0: return new PersistentTuple7(_meta, val, v1, v2, v3, v4, v5, v6);
                case 1: return new PersistentTuple7(_meta, v0, val, v2, v3, v4, v5, v6);
                case 2: return new PersistentTuple7(_meta, v0, v1, val, v3, v4, v5, v6);
                case 3: return new PersistentTuple7(_meta, v0, v1, v2, val, v4, v5, v6);
                case 4: return new PersistentTuple7(_meta, v0, v1, v2, v3, val, v5, v6);
                case 5: return new PersistentTuple7(_meta, v0, v1, v2, v3, v4, val, v6);
                case 6: return new PersistentTuple7(_meta, v0, v1, v2, v3, v4, v5, val);
                case 7: return new PersistentTuple8(_meta, v0, v1, v2, v3, v4, v5, v6, val);
                default: throw new IndexOutOfBoundsException();
            }
        }

        @Override
        public IPersistentVector cons(Object val) {
            return new PersistentTuple8(_meta, v0, v1, v2, v3, v4, v5, v6, val);
        }

        @Override
        public IPersistentStack pop() {
            return new PersistentTuple6(_meta, v0, v1, v2, v3, v4, v5);
        }

        @Override
        public PersistentTuple7 withMeta(IPersistentMap meta) {
            if (meta == _meta) return this;
            return new PersistentTuple7(meta, v0, v1, v2, v3, v4, v5, v6);
        }

        @Override
        public Object reduce(IFn f) {
            Object ret = f.invoke(v0, v1);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v2);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v3);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v4);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v5);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v6);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }

        @Override
        public Object reduce(IFn f, Object start) {
            Object ret = f.invoke(start, v0);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v1);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v2);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v3);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v4);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v5);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v6);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }

        @Override
        public Object kvreduce(IFn f, Object init) {
            Object ret = f.invoke(init, 0, v0);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 1, v1);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 2, v2);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 3, v3);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 4, v4);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 5, v5);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 6, v6);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }
    }

    public static final class PersistentTuple8 extends PersistentTuple {
        public final Object v0, v1, v2, v3, v4, v5, v6, v7;

        public PersistentTuple8(IPersistentMap meta, Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6, Object v7) {
            super(meta);
            this.v0 = v0;
            this.v1 = v1;
            this.v2 = v2;
            this.v3 = v3;
            this.v4 = v4;
            this.v5 = v5;
            this.v6 = v6;
            this.v7 = v7;
        }

        @Override
        public int count() {
            return 8;
        }

        @Override
        public Object nth(int i) {
            switch (i) {
                case 0: return v0;
                case 1: return v1;
                case 2: return v2;
                case 3: return v3;
                case 4: return v4;
                case 5: return v5;
                case 6: return v6;
                case 7: return v7;
                default: throw new IndexOutOfBoundsException();
            }
        }

        @Override
        public Object nth(int i, Object notFound) {
            switch (i) {
                case 0: return v0;
                case 1: return v1;
                case 2: return v2;
                case 3: return v3;
                case 4: return v4;
                case 5: return v5;
                case 6: return v6;
                case 7: return v7;
                default: return notFound;
            }
        }

        @Override
        public IPersistentVector assocN(int i, Object val) {
            switch (i) {
                case 0: return new PersistentTuple8(_meta, val, v1, v2, v3, v4, v5, v6, v7);
                case 1: return new PersistentTuple8(_meta, v0, val, v2, v3, v4, v5, v6, v7);
                case 2: return new PersistentTuple8(_meta, v0, v1, val, v3, v4, v5, v6, v7);
                case 3: return new PersistentTuple8(_meta, v0, v1, v2, val, v4, v5, v6, v7);
                case 4: return new PersistentTuple8(_meta, v0, v1, v2, v3, val, v5, v6, v7);
                case 5: return new PersistentTuple8(_meta, v0, v1, v2, v3, v4, val, v6, v7);
                case 6: return new PersistentTuple8(_meta, v0, v1, v2, v3, v4, v5, val, v7);
                case 7: return new PersistentTuple8(_meta, v0, v1, v2, v3, v4, v5, v6, val);
                case 8: return new PersistentVector(_meta, 9, 5, PersistentVector.EMPTY_NODE, new Object[]{v0, v1, v2, v3, v4, v5, v6, v7, val});
                default: throw new IndexOutOfBoundsException();
            }
        }

        @Override
        public IPersistentVector cons(Object val) {
            return new PersistentVector(_meta, 9, 5, PersistentVector.EMPTY_NODE, new Object[]{v0, v1, v2, v3, v4, v5, v6, v7, val});
        }

        @Override
        public IPersistentStack pop() {
            return new PersistentTuple7(_meta, v0, v1, v2, v3, v4, v5, v6);
        }

        @Override
        public PersistentTuple8 withMeta(IPersistentMap meta) {
            if (meta == _meta) return this;
            return new PersistentTuple8(meta, v0, v1, v2, v3, v4, v5, v6, v7);
        }

        @Override
        public Object reduce(IFn f) {
            Object ret = f.invoke(v0, v1);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v2);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v3);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v4);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v5);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v6);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v7);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }

        @Override
        public Object reduce(IFn f, Object start) {
            Object ret = f.invoke(start, v0);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v1);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v2);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v3);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v4);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v5);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v6);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, v7);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }

        @Override
        public Object kvreduce(IFn f, Object init) {
            Object ret = f.invoke(init, 0, v0);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 1, v1);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 2, v2);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 3, v3);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 4, v4);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 5, v5);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 6, v6);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            ret = f.invoke(ret, 7, v7);
            if (RT.isReduced(ret)) return ((IDeref) ret).deref();
            return ret;
        }
    }
}
