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
import java.util.Iterator;

public final class StreamSeq extends ASeq implements IReduce, IReduceInit, IPending, Serializable {

    private static final long serialVersionUID = 1L;

    private static final ISeq UNREALIZED = new ASeq() {
        private static final long serialVersionUID = 1L;
        @Override public Object first() { return null; }
        @Override public ISeq next() { return null; }
        @Override public ASeq withMeta(IPersistentMap meta) { return this; }
    };

    public final IFn xform;
    public final Object source;

    private volatile ISeq _spine = UNREALIZED;

    private static final Object NONE = new Object();

    public static final class ComposedTransducer extends AFn implements Serializable {
        private static final long serialVersionUID = 1L;
        public final IFn xf1;
        public final IFn xf2;

        public ComposedTransducer(IFn xf1, IFn xf2) {
            this.xf1 = xf1;
            this.xf2 = xf2;
        }

        @Override
        public Object invoke(Object rf) {
            return xf1.invoke(xf2.invoke(rf));
        }
    }

    public static final class MapTransducer extends AFn implements Serializable {
        private static final long serialVersionUID = 1L;
        public final IFn f;

        public MapTransducer(IFn f) {
            this.f = f;
        }

        @Override
        public Object invoke(Object rfObj) {
            final IFn rf = (IFn) rfObj;
            return new AFn() {
                @Override
                public Object invoke() {
                    return rf.invoke();
                }

                @Override
                public Object invoke(Object result) {
                    return rf.invoke(result);
                }

                @Override
                public Object invoke(Object result, Object input) {
                    return rf.invoke(result, f.invoke(input));
                }
            };
        }
    }

    public StreamSeq(IFn xform, Object source) {
        this(null, xform, source);
    }

    public StreamSeq(IPersistentMap meta, IFn xform, Object source) {
        super(meta);
        this.xform = xform;
        this.source = source;
    }

    public static ISeq create(IFn newXf, Object coll) {
        if (coll == null) {
            return null;
        }
        if (coll instanceof StreamSeq ss) {
            if (!ss.isRealized()) {
                IFn composed = new ComposedTransducer(ss.xform, newXf);
                return new StreamSeq(composed, ss.source);
            } else {
                return new StreamSeq(newXf, ss);
            }
        }
        return new StreamSeq(newXf, coll);
    }

    private ISeq spine() {
        if (_spine == UNREALIZED) {
            synchronized (this) {
                if (_spine == UNREALIZED) {
                    Iterator iter = TransformerIterator.create(xform, RT.iter(source));
                    _spine = IteratorSeq.create(iter);
                }
            }
        }
        return _spine;
    }

    @Override
    public boolean isRealized() {
        return _spine != UNREALIZED;
    }

    @Override
    public ISeq seq() {
        ISeq s = spine();
        if (s == null) {
            return null;
        }
        return this;
    }

    @Override
    public Object first() {
        ISeq s = spine();
        if (s == null) {
            return null;
        }
        return s.first();
    }

    @Override
    public ISeq next() {
        first();
        ISeq s = spine();
        if (s == null) {
            return null;
        }
        return s.next();
    }

    @Override
    public ISeq more() {
        ISeq s = spine();
        if (s == null) {
            return PersistentList.EMPTY;
        }
        return s.more();
    }

    @Override
    public int count() {
        ISeq s = spine();
        if (s == null) {
            return 0;
        }
        return s.count();
    }

    @Override
    public boolean isEmpty() {
        return spine() == null;
    }

    @Override
    public StreamSeq withMeta(IPersistentMap meta) {
        if (meta() == meta) {
            return this;
        }
        return new StreamSeq(meta, xform, source);
    }

    private static boolean isArity1Exception(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof ArityException ae) {
                return ae.actual == 1;
            }
        }
        return false;
    }

    private Object pushReduce(IFn xrf, Object init) {
        Object ret;
        if (source instanceof IReduceInit) {
            ret = ((IReduceInit) source).reduce(xrf, init);
        } else if (source instanceof Iterable) {
            ret = init;
            for (Object item : (Iterable) source) {
                ret = xrf.invoke(ret, item);
                if (RT.isReduced(ret)) {
                    ret = ((IDeref) ret).deref();
                    break;
                }
            }
        } else {
            ISeq s = RT.seq(source);
            ret = init;
            while (s != null) {
                ret = xrf.invoke(ret, s.first());
                if (RT.isReduced(ret)) {
                    ret = ((IDeref) ret).deref();
                    break;
                }
                s = s.next();
            }
        }
        if (RT.isReduced(ret)) {
            ret = ((IDeref) ret).deref();
        }
        Object finalRet = xrf.invoke(ret);
        if (RT.isReduced(finalRet)) {
            return ((IDeref) finalRet).deref();
        }
        return finalRet;
    }

    @Override
    public Object reduce(IFn rf, Object init) {
        ISeq sp = _spine;
        if (sp != UNREALIZED) {
            Object acc = init;
            for (ISeq s = sp; s != null; s = s.next()) {
                acc = rf.invoke(acc, s.first());
                if (RT.isReduced(acc)) {
                    return ((IDeref) acc).deref();
                }
            }
            return acc;
        }

        IFn completingF = new AFn() {
            @Override
            public Object invoke() {
                return rf.invoke();
            }

            @Override
            public Object invoke(Object result) {
                try {
                    return rf.invoke(result);
                } catch (Throwable t) {
                    if (isArity1Exception(t)) {
                        return result;
                    }
                    throw Util.sneakyThrow(t);
                }
            }

            @Override
            public Object invoke(Object result, Object input) {
                return rf.invoke(result, input);
            }
        };
        IFn xrf = (IFn) xform.invoke(completingF);
        return pushReduce(xrf, init);
    }

    @Override
    public Object reduce(IFn rf) {
        ISeq sp = _spine;
        if (sp != UNREALIZED) {
            if (sp == null) {
                return rf.invoke();
            }
            Object acc = sp.first();
            for (ISeq s = sp.next(); s != null; s = s.next()) {
                acc = rf.invoke(acc, s.first());
                if (RT.isReduced(acc)) {
                    return ((IDeref) acc).deref();
                }
            }
            return acc;
        }

        IFn completingF = new AFn() {
            @Override
            public Object invoke() {
                return rf.invoke();
            }

            @Override
            public Object invoke(Object result) {
                if (result == NONE) {
                    return NONE;
                }
                try {
                    return rf.invoke(result);
                } catch (Throwable t) {
                    if (isArity1Exception(t)) {
                        return result;
                    }
                    throw Util.sneakyThrow(t);
                }
            }

            @Override
            public Object invoke(Object result, Object input) {
                if (result == NONE) {
                    return input;
                }
                return rf.invoke(result, input);
            }
        };
        IFn xrf = (IFn) xform.invoke(completingF);
        Object ret = pushReduce(xrf, NONE);
        if (ret == NONE) {
            return rf.invoke();
        }
        return ret;
    }
}
