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
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Shape-based immutable persistent map for small keyword-only maps (<= 8 keys).
 * Enables GraalVM Partial Escape Analysis (PEA) and scalar replacement by using
 * direct object fields and canonical Keyword.id ordering.
 */
public class PersistentShapeMap extends APersistentMap implements IObj, IEditableCollection, IMapIterable, IKVReduce, IDrop, IKeywordLookup {

    private static final long serialVersionUID = 7712849182371928374L;

    public static final PersistentShapeMap EMPTY = new PersistentShapeMap();
    public static final int MAX_SHAPE_KEYS = 8;

    public final int count;
    public final long mask0;
    public final long mask1;
    public final boolean hasHighKeys;
    public final Keyword k0, k1, k2, k3, k4, k5, k6, k7;
    public final Object v0, v1, v2, v3, v4, v5, v6, v7;
    private final IPersistentMap _meta;

    public PersistentShapeMap() {
        this(null, 0, 0L, 0L, false, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public PersistentShapeMap(IPersistentMap meta, int count,
                              long mask0, long mask1, boolean hasHighKeys,
                              Keyword k0, Object v0,
                              Keyword k1, Object v1,
                              Keyword k2, Object v2,
                              Keyword k3, Object v3,
                              Keyword k4, Object v4,
                              Keyword k5, Object v5,
                              Keyword k6, Object v6,
                              Keyword k7, Object v7) {
        this._meta = meta;
        this.count = count;
        this.mask0 = mask0;
        this.mask1 = mask1;
        this.hasHighKeys = hasHighKeys;
        this.k0 = k0; this.v0 = v0;
        this.k1 = k1; this.v1 = v1;
        this.k2 = k2; this.v2 = v2;
        this.k3 = k3; this.v3 = v3;
        this.k4 = k4; this.v4 = v4;
        this.k5 = k5; this.v5 = v5;
        this.k6 = k6; this.v6 = v6;
        this.k7 = k7; this.v7 = v7;
    }

    public PersistentShapeMap(IPersistentMap meta, int count,
                              Keyword k0, Object v0,
                              Keyword k1, Object v1,
                              Keyword k2, Object v2,
                              Keyword k3, Object v3,
                              Keyword k4, Object v4,
                              Keyword k5, Object v5,
                              Keyword k6, Object v6,
                              Keyword k7, Object v7) {
        this(meta, count,
             computeMask0(count, k0, k1, k2, k3, k4, k5, k6, k7),
             computeMask1(count, k0, k1, k2, k3, k4, k5, k6, k7),
             computeHasHighKeys(count, k0, k1, k2, k3, k4, k5, k6, k7),
             k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7);
    }

    private static long computeMask0(int count, Keyword k0, Keyword k1, Keyword k2, Keyword k3, Keyword k4, Keyword k5, Keyword k6, Keyword k7) {
        long m0 = 0L;
        if (count > 0 && k0 != null) m0 |= k0.mask0;
        if (count > 1 && k1 != null) m0 |= k1.mask0;
        if (count > 2 && k2 != null) m0 |= k2.mask0;
        if (count > 3 && k3 != null) m0 |= k3.mask0;
        if (count > 4 && k4 != null) m0 |= k4.mask0;
        if (count > 5 && k5 != null) m0 |= k5.mask0;
        if (count > 6 && k6 != null) m0 |= k6.mask0;
        if (count > 7 && k7 != null) m0 |= k7.mask0;
        return m0;
    }

    private static long computeMask1(int count, Keyword k0, Keyword k1, Keyword k2, Keyword k3, Keyword k4, Keyword k5, Keyword k6, Keyword k7) {
        long m1 = 0L;
        if (count > 0 && k0 != null) m1 |= k0.mask1;
        if (count > 1 && k1 != null) m1 |= k1.mask1;
        if (count > 2 && k2 != null) m1 |= k2.mask1;
        if (count > 3 && k3 != null) m1 |= k3.mask1;
        if (count > 4 && k4 != null) m1 |= k4.mask1;
        if (count > 5 && k5 != null) m1 |= k5.mask1;
        if (count > 6 && k6 != null) m1 |= k6.mask1;
        if (count > 7 && k7 != null) m1 |= k7.mask1;
        return m1;
    }

    private static boolean computeHasHighKeys(int count, Keyword k0, Keyword k1, Keyword k2, Keyword k3, Keyword k4, Keyword k5, Keyword k6, Keyword k7) {
        if (count > 0 && k0 != null && k0.id >= 128) return true;
        if (count > 1 && k1 != null && k1.id >= 128) return true;
        if (count > 2 && k2 != null && k2.id >= 128) return true;
        if (count > 3 && k3 != null && k3.id >= 128) return true;
        if (count > 4 && k4 != null && k4.id >= 128) return true;
        if (count > 5 && k5 != null && k5.id >= 128) return true;
        if (count > 6 && k6 != null && k6.id >= 128) return true;
        if (count > 7 && k7 != null && k7.id >= 128) return true;
        return false;
    }

    public static boolean canBeShapeMap(Object[] init) {
        if (init == null || init.length > MAX_SHAPE_KEYS * 2 || (init.length & 1) != 0)
            return false;
        for (int i = 0; i < init.length; i += 2) {
            if (!(init[i] instanceof Keyword))
                return false;
        }
        return true;
    }

    public static PersistentShapeMap createWithCheck(Object[] init) {
        int pairCount = init.length / 2;
        if (pairCount == 0) return EMPTY;

        Keyword[] keys = new Keyword[pairCount];
        Object[] vals = new Object[pairCount];
        for (int i = 0; i < pairCount; i++) {
            keys[i] = (Keyword) init[i * 2];
            vals[i] = init[i * 2 + 1];
        }
        for (int i = 0; i < pairCount; i++) {
            for (int j = i + 1; j < pairCount; j++) {
                if (keys[i] == keys[j]) {
                    throw new IllegalArgumentException("Duplicate key: " + keys[i]);
                }
                if (keys[i].id > keys[j].id) {
                    Keyword tk = keys[i]; keys[i] = keys[j]; keys[j] = tk;
                    Object tv = vals[i]; vals[i] = vals[j]; vals[j] = tv;
                }
            }
        }
        return createFromSorted(null, pairCount, keys, vals);
    }

    public static PersistentShapeMap createFromSorted(IPersistentMap meta, int pairCount, Keyword[] keys, Object[] vals) {
        long m0 = 0L;
        long m1 = 0L;
        boolean highKeys = false;
        for (int i = 0; i < pairCount; i++) {
            m0 |= keys[i].mask0;
            m1 |= keys[i].mask1;
            if (keys[i].id >= 128) {
                highKeys = true;
            }
        }
        Keyword pk0 = pairCount > 0 ? keys[0] : null; Object pv0 = pairCount > 0 ? vals[0] : null;
        Keyword pk1 = pairCount > 1 ? keys[1] : null; Object pv1 = pairCount > 1 ? vals[1] : null;
        Keyword pk2 = pairCount > 2 ? keys[2] : null; Object pv2 = pairCount > 2 ? vals[2] : null;
        Keyword pk3 = pairCount > 3 ? keys[3] : null; Object pv3 = pairCount > 3 ? vals[3] : null;
        Keyword pk4 = pairCount > 4 ? keys[4] : null; Object pv4 = pairCount > 4 ? vals[4] : null;
        Keyword pk5 = pairCount > 5 ? keys[5] : null; Object pv5 = pairCount > 5 ? vals[5] : null;
        Keyword pk6 = pairCount > 6 ? keys[6] : null; Object pv6 = pairCount > 6 ? vals[6] : null;
        Keyword pk7 = pairCount > 7 ? keys[7] : null; Object pv7 = pairCount > 7 ? vals[7] : null;
        return new PersistentShapeMap(meta, pairCount, m0, m1, highKeys, pk0, pv0, pk1, pv1, pk2, pv2, pk3, pv3, pk4, pv4, pk5, pv5, pk6, pv6, pk7, pv7);
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

    public Object getVal(int i) {
        return switch (i) {
            case 0 -> v0;
            case 1 -> v1;
            case 2 -> v2;
            case 3 -> v3;
            case 4 -> v4;
            case 5 -> v5;
            case 6 -> v6;
            case 7 -> v7;
            default -> null;
        };
    }

    @Override
    public int count() {
        return count;
    }

    @Override
    public boolean containsKey(Object key) {
        if (key instanceof Keyword kw) {
            long kid = kw.id;
            if (kid < 64) {
                return (mask0 & kw.mask0) != 0;
            } else if (kid < 128) {
                return (mask1 & kw.mask1) != 0;
            } else if (!hasHighKeys) {
                return false;
            } else {
                return containsKeyHigh(kw);
            }
        }
        return false;
    }

    private boolean containsKeyHigh(Keyword kw) {
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
    public IMapEntry entryAt(Object key) {
        if (key instanceof Keyword kw) {
            long kid = kw.id;
            if (kid < 64) {
                if ((mask0 & kw.mask0) == 0) return null;
                int slot = Long.bitCount(mask0 & (kw.mask0 - 1));
                return (IMapEntry) MapEntry.create(getKey(slot), getVal(slot));
            } else if (kid < 128) {
                if ((mask1 & kw.mask1) == 0) return null;
                int slot = Long.bitCount(mask0) + Long.bitCount(mask1 & (kw.mask1 - 1));
                return (IMapEntry) MapEntry.create(getKey(slot), getVal(slot));
            } else if (!hasHighKeys) {
                return null;
            } else {
                return entryAtHigh(kw);
            }
        }
        return null;
    }

    private IMapEntry entryAtHigh(Keyword kw) {
        if (count > 0 && kw == k0) return (IMapEntry) MapEntry.create(k0, v0);
        if (count > 1 && kw == k1) return (IMapEntry) MapEntry.create(k1, v1);
        if (count > 2 && kw == k2) return (IMapEntry) MapEntry.create(k2, v2);
        if (count > 3 && kw == k3) return (IMapEntry) MapEntry.create(k3, v3);
        if (count > 4 && kw == k4) return (IMapEntry) MapEntry.create(k4, v4);
        if (count > 5 && kw == k5) return (IMapEntry) MapEntry.create(k5, v5);
        if (count > 6 && kw == k6) return (IMapEntry) MapEntry.create(k6, v6);
        if (count > 7 && kw == k7) return (IMapEntry) MapEntry.create(k7, v7);
        return null;
    }

    @Override
    public Object valAt(Object key) {
        return valAt(key, null);
    }

    @Override
    public Object valAt(Object key, Object notFound) {
        if (key instanceof Keyword kw) {
            long kid = kw.id;
            if (kid < 64) {
                if ((mask0 & kw.mask0) == 0) return notFound;
                int slot = Long.bitCount(mask0 & (kw.mask0 - 1));
                return getVal(slot);
            } else if (kid < 128) {
                if ((mask1 & kw.mask1) == 0) return notFound;
                int slot = Long.bitCount(mask0) + Long.bitCount(mask1 & (kw.mask1 - 1));
                return getVal(slot);
            } else if (!hasHighKeys) {
                return notFound;
            } else {
                return valAtHigh(kw, notFound);
            }
        }
        return notFound;
    }

    private Object valAtHigh(Keyword kw, Object notFound) {
        switch (count) {
            case 8: if (kw == k7) return v7;
            case 7: if (kw == k6) return v6;
            case 6: if (kw == k5) return v5;
            case 5: if (kw == k4) return v4;
            case 4: if (kw == k3) return v3;
            case 3: if (kw == k2) return v2;
            case 2: if (kw == k1) return v1;
            case 1: if (kw == k0) return v0;
            case 0: return notFound;
        }
        return notFound;
    }

    @Override
    public IPersistentMap assoc(Object key, Object val) {
        if (!(key instanceof Keyword kw)) {
            // Demote to PersistentArrayMap
            Object[] arr = toArray();
            return new PersistentArrayMap(meta(), arr).assoc(key, val);
        }

        // Check if key already exists
        long kid = kw.id;
        int existingSlot = -1;
        if (kid < 64) {
            if ((mask0 & kw.mask0) != 0) {
                existingSlot = Long.bitCount(mask0 & (kw.mask0 - 1));
            }
        } else if (kid < 128) {
            if ((mask1 & kw.mask1) != 0) {
                existingSlot = Long.bitCount(mask0) + Long.bitCount(mask1 & (kw.mask1 - 1));
            }
        } else if (hasHighKeys) {
            if (count > 0 && kw == k0) existingSlot = 0;
            else if (count > 1 && kw == k1) existingSlot = 1;
            else if (count > 2 && kw == k2) existingSlot = 2;
            else if (count > 3 && kw == k3) existingSlot = 3;
            else if (count > 4 && kw == k4) existingSlot = 4;
            else if (count > 5 && kw == k5) existingSlot = 5;
            else if (count > 6 && kw == k6) existingSlot = 6;
            else if (count > 7 && kw == k7) existingSlot = 7;
        }

        if (existingSlot >= 0) {
            return switch (existingSlot) {
                case 0 -> new PersistentShapeMap(meta(), count, mask0, mask1, hasHighKeys, k0, val, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7);
                case 1 -> new PersistentShapeMap(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, val, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7);
                case 2 -> new PersistentShapeMap(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, v1, k2, val, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7);
                case 3 -> new PersistentShapeMap(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, v1, k2, v2, k3, val, k4, v4, k5, v5, k6, v6, k7, v7);
                case 4 -> new PersistentShapeMap(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, v1, k2, v2, k3, v3, k4, val, k5, v5, k6, v6, k7, v7);
                case 5 -> new PersistentShapeMap(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, val, k6, v6, k7, v7);
                case 6 -> new PersistentShapeMap(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, val, k7, v7);
                case 7 -> new PersistentShapeMap(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, val);
                default -> this;
            };
        }

        if (count == MAX_SHAPE_KEYS) {
            // Promote to PersistentHashMap
            Object[] arr = toArray();
            return PersistentHashMap.create(meta(), arr).assoc(kw, val);
        }

        // Insert in sorted order of Keyword.id
        Keyword[] keys = new Keyword[count + 1];
        Object[] vals = new Object[count + 1];
        int inserted = 0;
        for (int i = 0; i < count; i++) {
            if (inserted == 0 && kw.id < getKey(i).id) {
                keys[i] = kw;
                vals[i] = val;
                inserted = 1;
            }
            keys[i + inserted] = getKey(i);
            vals[i + inserted] = getVal(i);
        }
        if (inserted == 0) {
            keys[count] = kw;
            vals[count] = val;
        }

        return createFromSorted(meta(), count + 1, keys, vals);
    }

    @Override
    public IPersistentMap assocEx(Object key, Object val) {
        if (containsKey(key)) {
            throw Util.runtimeException("Key already present");
        }
        return assoc(key, val);
    }

    @Override
    public IPersistentMap without(Object key) {
        if (!(key instanceof Keyword kw)) {
            return this;
        }

        int matchIdx = -1;
        for (int i = 0; i < count; i++) {
            if (kw == getKey(i)) {
                matchIdx = i;
                break;
            }
        }
        if (matchIdx == -1) {
            return this;
        }
        if (count == 1) {
            return (IPersistentMap) EMPTY.withMeta(meta());
        }

        Keyword[] keys = new Keyword[count - 1];
        Object[] vals = new Object[count - 1];
        int dest = 0;
        for (int i = 0; i < count; i++) {
            if (i != matchIdx) {
                keys[dest] = getKey(i);
                vals[dest] = getVal(i);
                dest++;
            }
        }
        return createFromSorted(meta(), count - 1, keys, vals);
    }

    @Override
    public IPersistentMap empty() {
        return (IPersistentMap) EMPTY.withMeta(meta());
    }

    public Object[] toArray() {
        Object[] arr = new Object[count * 2];
        for (int i = 0; i < count; i++) {
            arr[i * 2] = getKey(i);
            arr[i * 2 + 1] = getVal(i);
        }
        return arr;
    }

    @Override
    public Iterator iterator() {
        return new PersistentArrayMap.Iter(toArray(), APersistentMap.MAKE_ENTRY);
    }

    public Iterator keyIterator() {
        return new PersistentArrayMap.Iter(toArray(), APersistentMap.MAKE_KEY);
    }

    public Iterator valIterator() {
        return new PersistentArrayMap.Iter(toArray(), APersistentMap.MAKE_VAL);
    }

    @Override
    public ISeq seq() {
        if (count > 0) {
            return new PersistentArrayMap.Seq(toArray(), 0);
        }
        return null;
    }

    @Override
    public Sequential drop(int n) {
        if (count > 0) {
            return ((PersistentArrayMap.Seq) seq()).drop(n);
        }
        return null;
    }

    @Override
    public IPersistentMap meta() {
        return _meta;
    }

    @Override
    public PersistentShapeMap withMeta(IPersistentMap meta) {
        if (meta() == meta)
            return this;
        return new PersistentShapeMap(meta, count, mask0, mask1, hasHighKeys, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7);
    }

    @Override
    public Object kvreduce(IFn f, Object init) {
        Object acc = init;
        for (int i = 0; i < count; i++) {
            acc = f.invoke(acc, getKey(i), getVal(i));
            if (RT.isReduced(acc))
                return ((IDeref) acc).deref();
        }
        return acc;
    }

    @Override
    public ILookupThunk getLookupThunk(final Keyword k) {
        long kid = k.id;
        if (kid < 64) {
            if ((mask0 & k.mask0) == 0) return null;
            final long kmask = k.mask0;
            final long lowerMask = kmask - 1;
            return target -> {
                if (target instanceof PersistentShapeMap sm && (sm.mask0 & kmask) != 0) {
                    int slot = Long.bitCount(sm.mask0 & lowerMask);
                    return sm.getVal(slot);
                }
                return target;
            };
        } else if (kid < 128) {
            if ((mask1 & k.mask1) == 0) return null;
            final long kmask = k.mask1;
            final long lowerMask = kmask - 1;
            return target -> {
                if (target instanceof PersistentShapeMap sm && (sm.mask1 & kmask) != 0) {
                    int slot = Long.bitCount(sm.mask0) + Long.bitCount(sm.mask1 & lowerMask);
                    return sm.getVal(slot);
                }
                return target;
            };
        } else {
            if (!hasHighKeys) return null;
            if (count > 0 && k == k0) return target -> target instanceof PersistentShapeMap sm && sm.k0 == k ? sm.v0 : target;
            if (count > 1 && k == k1) return target -> target instanceof PersistentShapeMap sm && sm.k1 == k ? sm.v1 : target;
            if (count > 2 && k == k2) return target -> target instanceof PersistentShapeMap sm && sm.k2 == k ? sm.v2 : target;
            if (count > 3 && k == k3) return target -> target instanceof PersistentShapeMap sm && sm.k3 == k ? sm.v3 : target;
            if (count > 4 && k == k4) return target -> target instanceof PersistentShapeMap sm && sm.k4 == k ? sm.v4 : target;
            if (count > 5 && k == k5) return target -> target instanceof PersistentShapeMap sm && sm.k5 == k ? sm.v5 : target;
            if (count > 6 && k == k6) return target -> target instanceof PersistentShapeMap sm && sm.k6 == k ? sm.v6 : target;
            if (count > 7 && k == k7) return target -> target instanceof PersistentShapeMap sm && sm.k7 == k ? sm.v7 : target;
            return null;
        }
    }

    @Override
    public ITransientMap asTransient() {
        return new PersistentArrayMap(toArray()).asTransient();
    }
}
