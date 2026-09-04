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
import java.util.NoSuchElementException;

/**
 * Immutable keyword-only set holding 1 to 8 keys in scalar object fields.
 * Enables GraalVM Partial Escape Analysis (PEA) and scalar replacement by using
 * direct object fields and canonical Keyword.id bitmask lookups.
 */
public class PersistentShapeSet extends APersistentSet implements IObj, IEditableCollection, IKVReduce, IDrop {

    private static final long serialVersionUID = 7712849182371928375L;

    public static final PersistentShapeSet EMPTY = new PersistentShapeSet();
    public static final int MAX_SHAPE_KEYS = 8;

    public final int count;
    public final long mask0;
    public final long mask1;
    public final boolean hasHighKeys;
    public final Keyword k0, k1, k2, k3, k4, k5, k6, k7;
    private final IPersistentMap _meta;

    public PersistentShapeSet() {
        this(null, 0, 0L, 0L, false, null, null, null, null, null, null, null, null);
    }

    public PersistentShapeSet(IPersistentMap meta, int count,
                              long mask0, long mask1, boolean hasHighKeys,
                              Keyword k0, Keyword k1, Keyword k2, Keyword k3,
                              Keyword k4, Keyword k5, Keyword k6, Keyword k7) {
        super(null);
        this._meta = meta;
        this.count = count;
        this.mask0 = mask0;
        this.mask1 = mask1;
        this.hasHighKeys = hasHighKeys;
        this.k0 = k0;
        this.k1 = k1;
        this.k2 = k2;
        this.k3 = k3;
        this.k4 = k4;
        this.k5 = k5;
        this.k6 = k6;
        this.k7 = k7;
    }

    public static PersistentShapeSet create(Keyword k0) {
        if (k0 == null) throw new IllegalArgumentException("Set element cannot be null");
        return new PersistentShapeSet(null, 1, k0.mask0, k0.mask1, k0.id >= 128,
                k0, null, null, null, null, null, null, null);
    }

    public static PersistentShapeSet create(Keyword k0, Keyword k1) {
        if (k0 == null || k1 == null) throw new IllegalArgumentException("Set element cannot be null");
        if (k0 == k1) throw new IllegalArgumentException("Duplicate key: " + k0);
        if (k0.id > k1.id) {
            Keyword tk = k0; k0 = k1; k1 = tk;
        }
        long m0 = k0.mask0 | k1.mask0;
        long m1 = k0.mask1 | k1.mask1;
        boolean highKeys = (k0.id >= 128) || (k1.id >= 128);
        return new PersistentShapeSet(null, 2, m0, m1, highKeys,
                k0, k1, null, null, null, null, null, null);
    }

    public static PersistentShapeSet create(Keyword k0, Keyword k1, Keyword k2) {
        if (k0 == null || k1 == null || k2 == null) throw new IllegalArgumentException("Set element cannot be null");
        if (k0 == k1 || k0 == k2 || k1 == k2) throw new IllegalArgumentException("Duplicate key");
        if (k0.id > k1.id) { Keyword tk = k0; k0 = k1; k1 = tk; }
        if (k1.id > k2.id) { Keyword tk = k1; k1 = k2; k2 = tk; }
        if (k0.id > k1.id) { Keyword tk = k0; k0 = k1; k1 = tk; }
        long m0 = k0.mask0 | k1.mask0 | k2.mask0;
        long m1 = k0.mask1 | k1.mask1 | k2.mask1;
        boolean highKeys = (k0.id >= 128) || (k1.id >= 128) || (k2.id >= 128);
        return new PersistentShapeSet(null, 3, m0, m1, highKeys,
                k0, k1, k2, null, null, null, null, null);
    }

    public static PersistentShapeSet create(Keyword k0, Keyword k1, Keyword k2, Keyword k3) {
        if (k0 == null || k1 == null || k2 == null || k3 == null) throw new IllegalArgumentException("Set element cannot be null");
        if (k0 == k1 || k0 == k2 || k0 == k3 || k1 == k2 || k1 == k3 || k2 == k3) throw new IllegalArgumentException("Duplicate key");
        if (k0.id > k1.id) { Keyword tk = k0; k0 = k1; k1 = tk; }
        if (k2.id > k3.id) { Keyword tk = k2; k2 = k3; k3 = tk; }
        if (k0.id > k2.id) { Keyword tk = k0; k0 = k2; k2 = tk; }
        if (k1.id > k3.id) { Keyword tk = k1; k1 = k3; k3 = tk; }
        if (k1.id > k2.id) { Keyword tk = k1; k1 = k2; k2 = tk; }
        long m0 = k0.mask0 | k1.mask0 | k2.mask0 | k3.mask0;
        long m1 = k0.mask1 | k1.mask1 | k2.mask1 | k3.mask1;
        boolean highKeys = (k0.id >= 128) || (k1.id >= 128) || (k2.id >= 128) || (k3.id >= 128);
        return new PersistentShapeSet(null, 4, m0, m1, highKeys,
                k0, k1, k2, k3, null, null, null, null);
    }

    public static PersistentShapeSet create(Keyword... ks) {
        if (ks == null || ks.length == 0) return EMPTY;
        int n = ks.length;
        if (n > 8) throw new IllegalArgumentException("ShapeSet max 8 elements, got " + n);
        for (int i = 0; i < n; i++) {
            if (ks[i] == null) throw new IllegalArgumentException("Set element cannot be null");
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (ks[i] == ks[j]) throw new IllegalArgumentException("Duplicate key: " + ks[i]);
            }
        }
        Keyword[] sorted = ks.clone();
        for (int i = 0; i < n - 1; i++) {
            for (int j = i + 1; j < n; j++) {
                if (sorted[i].id > sorted[j].id) {
                    Keyword tk = sorted[i]; sorted[i] = sorted[j]; sorted[j] = tk;
                }
            }
        }
        return createFromSorted(null, n, sorted);
    }

    public static IPersistentSet create(java.util.List init) {
        if (init == null || init.isEmpty()) return EMPTY;
        boolean allKeywords = true;
        for (Object o : init) {
            if (!(o instanceof Keyword)) {
                allKeywords = false;
                break;
            }
        }
        if (allKeywords && init.size() <= 8) {
            Keyword[] ks = new Keyword[init.size()];
            for (int i = 0; i < init.size(); i++) {
                ks[i] = (Keyword) init.get(i);
            }
            return create(ks);
        }
        return PersistentHashSet.create(init);
    }

    public static IPersistentSet create(ISeq items) {
        if (items == null) return EMPTY;
        return PersistentHashSet.create(items);
    }

    public static PersistentShapeSet createFromSorted(IPersistentMap meta, int n, Keyword[] sorted) {
        long m0 = 0L;
        long m1 = 0L;
        boolean highKeys = false;
        for (int i = 0; i < n; i++) {
            m0 |= sorted[i].mask0;
            m1 |= sorted[i].mask1;
            if (sorted[i].id >= 128) {
                highKeys = true;
            }
        }
        Keyword pk0 = n > 0 ? sorted[0] : null;
        Keyword pk1 = n > 1 ? sorted[1] : null;
        Keyword pk2 = n > 2 ? sorted[2] : null;
        Keyword pk3 = n > 3 ? sorted[3] : null;
        Keyword pk4 = n > 4 ? sorted[4] : null;
        Keyword pk5 = n > 5 ? sorted[5] : null;
        Keyword pk6 = n > 6 ? sorted[6] : null;
        Keyword pk7 = n > 7 ? sorted[7] : null;
        return new PersistentShapeSet(meta, n, m0, m1, highKeys, pk0, pk1, pk2, pk3, pk4, pk5, pk6, pk7);
    }

    public static Shape1 shape1(Keyword k0) { return new Shape1(k0); }
    public static final class Shape1 {
        public final Keyword k0;
        public final PersistentShapeSet set;
        public Shape1(Keyword k0) {
            this.k0 = k0;
            this.set = PersistentShapeSet.create(k0);
        }
    }

    public static Shape2 shape2(Keyword k0, Keyword k1) { return new Shape2(k0, k1); }
    public static final class Shape2 {
        public final Keyword k0, k1;
        public final PersistentShapeSet set;
        public Shape2(Keyword a, Keyword b) {
            this.k0 = a; this.k1 = b;
            this.set = PersistentShapeSet.create(a, b);
        }
    }

    public static Shape3 shape3(Keyword k0, Keyword k1, Keyword k2) { return new Shape3(k0, k1, k2); }
    public static final class Shape3 {
        public final Keyword k0, k1, k2;
        public final PersistentShapeSet set;
        public Shape3(Keyword a, Keyword b, Keyword c) {
            this.k0 = a; this.k1 = b; this.k2 = c;
            this.set = PersistentShapeSet.create(a, b, c);
        }
    }

    public static Shape4 shape4(Keyword k0, Keyword k1, Keyword k2, Keyword k3) { return new Shape4(k0, k1, k2, k3); }
    public static final class Shape4 {
        public final Keyword k0, k1, k2, k3;
        public final PersistentShapeSet set;
        public Shape4(Keyword a, Keyword b, Keyword c, Keyword d) {
            this.k0 = a; this.k1 = b; this.k2 = c; this.k3 = d;
            this.set = PersistentShapeSet.create(a, b, c, d);
        }
    }

    public static Shape5 shape5(Keyword k0, Keyword k1, Keyword k2, Keyword k3, Keyword k4) { return new Shape5(k0, k1, k2, k3, k4); }
    public static final class Shape5 {
        public final Keyword k0, k1, k2, k3, k4;
        public final PersistentShapeSet set;
        public Shape5(Keyword a, Keyword b, Keyword c, Keyword d, Keyword e) {
            this.k0 = a; this.k1 = b; this.k2 = c; this.k3 = d; this.k4 = e;
            this.set = PersistentShapeSet.create(a, b, c, d, e);
        }
    }

    public static Shape6 shape6(Keyword k0, Keyword k1, Keyword k2, Keyword k3, Keyword k4, Keyword k5) { return new Shape6(k0, k1, k2, k3, k4, k5); }
    public static final class Shape6 {
        public final Keyword k0, k1, k2, k3, k4, k5;
        public final PersistentShapeSet set;
        public Shape6(Keyword a, Keyword b, Keyword c, Keyword d, Keyword e, Keyword f) {
            this.k0 = a; this.k1 = b; this.k2 = c; this.k3 = d; this.k4 = e; this.k5 = f;
            this.set = PersistentShapeSet.create(a, b, c, d, e, f);
        }
    }

    public static Shape7 shape7(Keyword k0, Keyword k1, Keyword k2, Keyword k3, Keyword k4, Keyword k5, Keyword k6) { return new Shape7(k0, k1, k2, k3, k4, k5, k6); }
    public static final class Shape7 {
        public final Keyword k0, k1, k2, k3, k4, k5, k6;
        public final PersistentShapeSet set;
        public Shape7(Keyword a, Keyword b, Keyword c, Keyword d, Keyword e, Keyword f, Keyword g) {
            this.k0 = a; this.k1 = b; this.k2 = c; this.k3 = d; this.k4 = e; this.k5 = f; this.k6 = g;
            this.set = PersistentShapeSet.create(a, b, c, d, e, f, g);
        }
    }

    public static Shape8 shape8(Keyword k0, Keyword k1, Keyword k2, Keyword k3, Keyword k4, Keyword k5, Keyword k6, Keyword k7) { return new Shape8(k0, k1, k2, k3, k4, k5, k6, k7); }
    public static final class Shape8 {
        public final Keyword k0, k1, k2, k3, k4, k5, k6, k7;
        public final PersistentShapeSet set;
        public Shape8(Keyword a, Keyword b, Keyword c, Keyword d, Keyword e, Keyword f, Keyword g, Keyword h) {
            this.k0 = a; this.k1 = b; this.k2 = c; this.k3 = d; this.k4 = e; this.k5 = f; this.k6 = g; this.k7 = h;
            this.set = PersistentShapeSet.create(a, b, c, d, e, f, g, h);
        }
    }

    public Keyword getKey(int i) {
        return switch (i) {
            case 0 -> k0;
            case 1 -> k1;
            case 2 -> k2;
            case 3 -> k3;
            case 4 -> k4;
            case 5 -> k5;
            case 6 -> k6;
            case 7 -> k7;
            default -> null;
        };
    }

    @Override
    public int count() {
        return count;
    }

    @Override
    public boolean contains(Object key) {
        if (key instanceof Keyword kw) {
            long kid = kw.id;
            if (kid < 64) {
                return (mask0 & kw.mask0) != 0;
            } else if (kid < 128) {
                return (mask1 & kw.mask1) != 0;
            } else if (!hasHighKeys) {
                return false;
            } else {
                return containsHigh(kw);
            }
        }
        return false;
    }

    private boolean containsHigh(Keyword kw) {
        return switch (count) {
            case 8 -> kw == k7 || kw == k6 || kw == k5 || kw == k4 || kw == k3 || kw == k2 || kw == k1 || kw == k0;
            case 7 -> kw == k6 || kw == k5 || kw == k4 || kw == k3 || kw == k2 || kw == k1 || kw == k0;
            case 6 -> kw == k5 || kw == k4 || kw == k3 || kw == k2 || kw == k1 || kw == k0;
            case 5 -> kw == k4 || kw == k3 || kw == k2 || kw == k1 || kw == k0;
            case 4 -> kw == k3 || kw == k2 || kw == k1 || kw == k0;
            case 3 -> kw == k2 || kw == k1 || kw == k0;
            case 2 -> kw == k1 || kw == k0;
            case 1 -> kw == k0;
            default -> false;
        };
    }

    @Override
    public Object get(Object key) {
        if (contains(key)) {
            return key;
        }
        return null;
    }

    @Override
    public IPersistentSet cons(Object o) {
        if (contains(o)) {
            return this;
        }
        if (!(o instanceof Keyword kw) || count == 8) {
            ITransientSet ret = (ITransientSet) PersistentHashSet.EMPTY.asTransient();
            for (int i = 0; i < count; i++) {
                ret = (ITransientSet) ret.conj(getKey(i));
            }
            ret = (ITransientSet) ret.conj(o);
            return ((PersistentHashSet) ret.persistent()).withMeta(meta());
        }
        long kid = kw.id;
        int ins;
        if (kid < 64) {
            ins = Long.bitCount(mask0 & (kw.mask0 - 1));
        } else if (kid < 128) {
            ins = Long.bitCount(mask0) + Long.bitCount(mask1 & (kw.mask1 - 1));
        } else {
            ins = count;
            for (int i = 0; i < count; i++) {
                if (getKey(i).id > kid) {
                    ins = i;
                    break;
                }
            }
        }
        long newMask0 = mask0 | kw.mask0;
        long newMask1 = mask1 | kw.mask1;
        boolean newHasHigh = hasHighKeys || (kid >= 128);
        return insertKey(kw, ins, newMask0, newMask1, newHasHigh);
    }

    private PersistentShapeSet insertKey(Keyword kw, int ins, long newMask0, long newMask1, boolean newHasHigh) {
        Keyword nk0 = k0, nk1 = k1, nk2 = k2, nk3 = k3, nk4 = k4, nk5 = k5, nk6 = k6, nk7 = k7;
        switch (ins) {
            case 0 -> {
                nk7 = k6; nk6 = k5; nk5 = k4; nk4 = k3; nk3 = k2; nk2 = k1; nk1 = k0; nk0 = kw;
            }
            case 1 -> {
                nk7 = k6; nk6 = k5; nk5 = k4; nk4 = k3; nk3 = k2; nk2 = k1; nk1 = kw;
            }
            case 2 -> {
                nk7 = k6; nk6 = k5; nk5 = k4; nk4 = k3; nk3 = k2; nk2 = kw;
            }
            case 3 -> {
                nk7 = k6; nk6 = k5; nk5 = k4; nk4 = k3; nk3 = kw;
            }
            case 4 -> {
                nk7 = k6; nk6 = k5; nk5 = k4; nk4 = kw;
            }
            case 5 -> {
                nk7 = k6; nk6 = k5; nk5 = kw;
            }
            case 6 -> {
                nk7 = k6; nk6 = kw;
            }
            case 7 -> {
                nk7 = kw;
            }
        }
        return new PersistentShapeSet(meta(), count + 1, newMask0, newMask1, newHasHigh,
                nk0, nk1, nk2, nk3, nk4, nk5, nk6, nk7);
    }

    @Override
    public IPersistentSet disjoin(Object key) {
        if (!(key instanceof Keyword kw)) {
            return this;
        }

        long kid = kw.id;
        int matchIdx = -1;
        if (kid < 64) {
            if ((mask0 & kw.mask0) == 0) {
                return this;
            }
            matchIdx = Long.bitCount(mask0 & (kw.mask0 - 1));
        } else if (kid < 128) {
            if ((mask1 & kw.mask1) == 0) {
                return this;
            }
            matchIdx = Long.bitCount(mask0) + Long.bitCount(mask1 & (kw.mask1 - 1));
        } else if (hasHighKeys) {
            if (count > 0 && kw == k0) matchIdx = 0;
            else if (count > 1 && kw == k1) matchIdx = 1;
            else if (count > 2 && kw == k2) matchIdx = 2;
            else if (count > 3 && kw == k3) matchIdx = 3;
            else if (count > 4 && kw == k4) matchIdx = 4;
            else if (count > 5 && kw == k5) matchIdx = 5;
            else if (count > 6 && kw == k6) matchIdx = 6;
            else if (count > 7 && kw == k7) matchIdx = 7;
            else return this;
        } else {
            return this;
        }

        if (count == 1) {
            return (IPersistentSet) EMPTY.withMeta(meta());
        }

        long newMask0 = mask0 & ~kw.mask0;
        long newMask1 = mask1 & ~kw.mask1;
        Keyword nk0 = k0, nk1 = k1, nk2 = k2, nk3 = k3, nk4 = k4, nk5 = k5, nk6 = k6, nk7 = null;
        switch (matchIdx) {
            case 0 -> {
                nk0 = k1; nk1 = k2; nk2 = k3; nk3 = k4; nk4 = k5; nk5 = k6; nk6 = k7;
            }
            case 1 -> {
                nk1 = k2; nk2 = k3; nk3 = k4; nk4 = k5; nk5 = k6; nk6 = k7;
            }
            case 2 -> {
                nk2 = k3; nk3 = k4; nk4 = k5; nk5 = k6; nk6 = k7;
            }
            case 3 -> {
                nk3 = k4; nk4 = k5; nk5 = k6; nk6 = k7;
            }
            case 4 -> {
                nk4 = k5; nk5 = k6; nk6 = k7;
            }
            case 5 -> {
                nk5 = k6; nk6 = k7;
            }
            case 6 -> {
                nk6 = k7;
            }
            case 7 -> {
                // nk7 is already null
            }
            default -> {
                return this;
            }
        }

        Keyword highest = switch (count - 1) {
            case 7 -> nk6;
            case 6 -> nk5;
            case 5 -> nk4;
            case 4 -> nk3;
            case 3 -> nk2;
            case 2 -> nk1;
            case 1 -> nk0;
            default -> null;
        };
        boolean newHasHighKeys = highest != null && highest.id >= 128;
        return new PersistentShapeSet(meta(), count - 1, newMask0, newMask1, newHasHighKeys,
                nk0, nk1, nk2, nk3, nk4, nk5, nk6, nk7);
    }

    @Override
    public IPersistentCollection empty() {
        return (IPersistentCollection) EMPTY.withMeta(meta());
    }

    @Override
    public IPersistentMap meta() {
        return _meta;
    }

    @Override
    public PersistentShapeSet withMeta(IPersistentMap meta) {
        if (meta() == meta)
            return this;
        return new PersistentShapeSet(meta, count, mask0, mask1, hasHighKeys, k0, k1, k2, k3, k4, k5, k6, k7);
    }

    @Override
    public ITransientCollection asTransient() {
        ITransientSet ret = (ITransientSet) PersistentHashSet.EMPTY.asTransient();
        for (int i = 0; i < count; i++) {
            ret = (ITransientSet) ret.conj(getKey(i));
        }
        return ret;
    }

    @Override
    public ISeq seq() {
        if (count > 0) {
            return new Seq(toArray(), 0);
        }
        return null;
    }

    @Override
    public Sequential drop(int n) {
        if (n <= 0) return (Sequential) seq();
        if (n >= count) return null;
        return new Seq(toArray(), n);
    }

    public static final class Seq extends ASeq implements IReduce, IDrop, IndexedSeq {
        private final Object[] array;
        private final int i;

        public Seq(Object[] array, int i) {
            this.array = array;
            this.i = i;
        }

        public Seq(IPersistentMap meta, Object[] array, int i) {
            super(meta);
            this.array = array;
            this.i = i;
        }

        @Override
        public Object first() {
            return array[i];
        }

        @Override
        public ISeq next() {
            if (i + 1 < array.length) {
                return new Seq(meta(), array, i + 1);
            }
            return null;
        }

        @Override
        public int count() {
            return array.length - i;
        }

        @Override
        public int index() {
            return i;
        }

        @Override
        public Sequential drop(int n) {
            if (n <= 0) return this;
            if (i + n >= array.length) return null;
            return new Seq(meta(), array, i + n);
        }

        @Override
        public Obj withMeta(IPersistentMap meta) {
            if (meta == meta()) return this;
            return new Seq(meta, array, i);
        }

        @Override
        public Object reduce(IFn f) {
            Object acc = array[i];
            for (int x = i + 1; x < array.length; x++) {
                acc = f.invoke(acc, array[x]);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
            }
            return acc;
        }

        @Override
        public Object reduce(IFn f, Object start) {
            Object acc = start;
            for (int x = i; x < array.length; x++) {
                acc = f.invoke(acc, array[x]);
                if (RT.isReduced(acc)) return ((IDeref) acc).deref();
            }
            return acc;
        }
    }

    @Override
    public Iterator iterator() {
        return new Iterator() {
            private int idx = 0;
            @Override
            public boolean hasNext() { return idx < count; }
            @Override
            public Object next() {
                if (idx >= count) throw new NoSuchElementException();
                return getKey(idx++);
            }
        };
    }

    @Override
    public Object[] toArray() {
        Object[] arr = new Object[count];
        for (int i = 0; i < count; i++) {
            arr[i] = getKey(i);
        }
        return arr;
    }

    @Override
    public Object[] toArray(Object[] a) {
        if (a.length < count) {
            a = (Object[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), count);
        }
        for (int i = 0; i < count; i++) {
            a[i] = getKey(i);
        }
        if (a.length > count) {
            a[count] = null;
        }
        return a;
    }

    @Override
    public Object kvreduce(IFn f, Object init) {
        Object acc = init;
        for (int i = 0; i < count; i++) {
            Keyword k = getKey(i);
            acc = f.invoke(acc, k, k);
            if (RT.isReduced(acc))
                return ((IDeref) acc).deref();
        }
        return acc;
    }
}
