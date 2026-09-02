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

/**
 * Shape-based immutable persistent map for medium keyword-only maps (9..16 keys).
 * Enables GraalVM Partial Escape Analysis (PEA) and scalar replacement by using
 * direct object fields and canonical Keyword.id ordering.
 */
public class PersistentShapeMap16 extends APersistentMap implements IObj, IEditableCollection, IMapIterable, IKVReduce, IDrop, IKeywordLookup {

    private static final long serialVersionUID = 7712849182371928375L;

    public static final int MIN_SHAPE16_KEYS = 9;
    public static final int MAX_SHAPE16_KEYS = 16;

    public final int count;
    public final long mask0;
    public final long mask1;
    public final boolean hasHighKeys;
    public final Keyword k0, k1, k2, k3, k4, k5, k6, k7, k8, k9, k10, k11, k12, k13, k14, k15;
    public final Object v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15;
    private final IPersistentMap _meta;

    public PersistentShapeMap16(IPersistentMap meta, int count,
                                long mask0, long mask1, boolean hasHighKeys,
                                Keyword k0, Object v0,
                                Keyword k1, Object v1,
                                Keyword k2, Object v2,
                                Keyword k3, Object v3,
                                Keyword k4, Object v4,
                                Keyword k5, Object v5,
                                Keyword k6, Object v6,
                                Keyword k7, Object v7,
                                Keyword k8, Object v8,
                                Keyword k9, Object v9,
                                Keyword k10, Object v10,
                                Keyword k11, Object v11,
                                Keyword k12, Object v12,
                                Keyword k13, Object v13,
                                Keyword k14, Object v14,
                                Keyword k15, Object v15) {
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
        this.k8 = k8; this.v8 = v8;
        this.k9 = k9; this.v9 = v9;
        this.k10 = k10; this.v10 = v10;
        this.k11 = k11; this.v11 = v11;
        this.k12 = k12; this.v12 = v12;
        this.k13 = k13; this.v13 = v13;
        this.k14 = k14; this.v14 = v14;
        this.k15 = k15; this.v15 = v15;
    }

    public static boolean canBeShapeMap16(Object[] init) {
        if (init == null || init.length <= PersistentShapeMap.MAX_SHAPE_KEYS * 2 || init.length > MAX_SHAPE16_KEYS * 2 || (init.length & 1) != 0)
            return false;
        for (int i = 0; i < init.length; i += 2) {
            if (!(init[i] instanceof Keyword))
                return false;
        }
        return true;
    }

    public static PersistentShapeMap16 createWithCheck(Object[] init) {
        int pairCount = init.length / 2;

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

    public static PersistentShapeMap16 createFromSorted(IPersistentMap meta, int pairCount, Keyword[] keys, Object[] vals) {
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
        Keyword pk8 = pairCount > 8 ? keys[8] : null; Object pv8 = pairCount > 8 ? vals[8] : null;
        Keyword pk9 = pairCount > 9 ? keys[9] : null; Object pv9 = pairCount > 9 ? vals[9] : null;
        Keyword pk10 = pairCount > 10 ? keys[10] : null; Object pv10 = pairCount > 10 ? vals[10] : null;
        Keyword pk11 = pairCount > 11 ? keys[11] : null; Object pv11 = pairCount > 11 ? vals[11] : null;
        Keyword pk12 = pairCount > 12 ? keys[12] : null; Object pv12 = pairCount > 12 ? vals[12] : null;
        Keyword pk13 = pairCount > 13 ? keys[13] : null; Object pv13 = pairCount > 13 ? vals[13] : null;
        Keyword pk14 = pairCount > 14 ? keys[14] : null; Object pv14 = pairCount > 14 ? vals[14] : null;
        Keyword pk15 = pairCount > 15 ? keys[15] : null; Object pv15 = pairCount > 15 ? vals[15] : null;
        return new PersistentShapeMap16(meta, pairCount, m0, m1, highKeys,
                                        pk0, pv0, pk1, pv1, pk2, pv2, pk3, pv3,
                                        pk4, pv4, pk5, pv5, pk6, pv6, pk7, pv7,
                                        pk8, pv8, pk9, pv9, pk10, pv10, pk11, pv11,
                                        pk12, pv12, pk13, pv13, pk14, pv14, pk15, pv15);
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
            case 8 -> k8;
            case 9 -> k9;
            case 10 -> k10;
            case 11 -> k11;
            case 12 -> k12;
            case 13 -> k13;
            case 14 -> k14;
            case 15 -> k15;
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
            case 8 -> v8;
            case 9 -> v9;
            case 10 -> v10;
            case 11 -> v11;
            case 12 -> v12;
            case 13 -> v13;
            case 14 -> v14;
            case 15 -> v15;
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
            case 16 -> kw == k15 || kw == k14 || kw == k13 || kw == k12 || kw == k11 || kw == k10 || kw == k9 || kw == k8 || kw == k7 || kw == k6 || kw == k5 || kw == k4 || kw == k3 || kw == k2 || kw == k1 || kw == k0;
            case 15 -> kw == k14 || kw == k13 || kw == k12 || kw == k11 || kw == k10 || kw == k9 || kw == k8 || kw == k7 || kw == k6 || kw == k5 || kw == k4 || kw == k3 || kw == k2 || kw == k1 || kw == k0;
            case 14 -> kw == k13 || kw == k12 || kw == k11 || kw == k10 || kw == k9 || kw == k8 || kw == k7 || kw == k6 || kw == k5 || kw == k4 || kw == k3 || kw == k2 || kw == k1 || kw == k0;
            case 13 -> kw == k12 || kw == k11 || kw == k10 || kw == k9 || kw == k8 || kw == k7 || kw == k6 || kw == k5 || kw == k4 || kw == k3 || kw == k2 || kw == k1 || kw == k0;
            case 12 -> kw == k11 || kw == k10 || kw == k9 || kw == k8 || kw == k7 || kw == k6 || kw == k5 || kw == k4 || kw == k3 || kw == k2 || kw == k1 || kw == k0;
            case 11 -> kw == k10 || kw == k9 || kw == k8 || kw == k7 || kw == k6 || kw == k5 || kw == k4 || kw == k3 || kw == k2 || kw == k1 || kw == k0;
            case 10 -> kw == k9 || kw == k8 || kw == k7 || kw == k6 || kw == k5 || kw == k4 || kw == k3 || kw == k2 || kw == k1 || kw == k0;
            case 9  -> kw == k8 || kw == k7 || kw == k6 || kw == k5 || kw == k4 || kw == k3 || kw == k2 || kw == k1 || kw == k0;
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
        for (int i = 0; i < count; i++) {
            if (kw == getKey(i)) {
                return (IMapEntry) MapEntry.create(getKey(i), getVal(i));
            }
        }
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
        for (int i = 0; i < count; i++) {
            if (kw == getKey(i)) {
                return getVal(i);
            }
        }
        return notFound;
    }

    @Override
    public IPersistentMap assoc(Object key, Object val) {
        if (!(key instanceof Keyword kw)) {
            // Demote to PersistentHashMap since count >= 9 exceeds PersistentArrayMap.HASHTABLE_THRESHOLD
            Object[] arr = toArray();
            return PersistentHashMap.create(meta(), arr).assoc(key, val);
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
            for (int i = 0; i < count; i++) {
                if (kw == getKey(i)) {
                    existingSlot = i;
                    break;
                }
            }
        }

        if (existingSlot >= 0) {
            return switch (existingSlot) {
                case 0 -> new PersistentShapeMap16(meta(), count, mask0, mask1, hasHighKeys, k0, val, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 1 -> new PersistentShapeMap16(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, val, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 2 -> new PersistentShapeMap16(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, v1, k2, val, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 3 -> new PersistentShapeMap16(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, v1, k2, v2, k3, val, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 4 -> new PersistentShapeMap16(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, v1, k2, v2, k3, v3, k4, val, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 5 -> new PersistentShapeMap16(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, val, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 6 -> new PersistentShapeMap16(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, val, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 7 -> new PersistentShapeMap16(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, val, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 8 -> new PersistentShapeMap16(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, val, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 9 -> new PersistentShapeMap16(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, val, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 10 -> new PersistentShapeMap16(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, val, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 11 -> new PersistentShapeMap16(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, val, k12, v12, k13, v13, k14, v14, k15, v15);
                case 12 -> new PersistentShapeMap16(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, val, k13, v13, k14, v14, k15, v15);
                case 13 -> new PersistentShapeMap16(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, val, k14, v14, k15, v15);
                case 14 -> new PersistentShapeMap16(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, val, k15, v15);
                case 15 -> new PersistentShapeMap16(meta(), count, mask0, mask1, hasHighKeys, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, val);
                default -> this;
            };
        }

        if (count == MAX_SHAPE16_KEYS) {
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
        if (count - 1 <= PersistentShapeMap.MAX_SHAPE_KEYS) {
            return PersistentShapeMap.createFromSorted(meta(), count - 1, keys, vals);
        }
        return createFromSorted(meta(), count - 1, keys, vals);
    }

    @Override
    public IPersistentMap empty() {
        return (IPersistentMap) PersistentShapeMap.EMPTY.withMeta(meta());
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
    public PersistentShapeMap16 withMeta(IPersistentMap meta) {
        if (meta() == meta)
            return this;
        return new PersistentShapeMap16(meta, count, mask0, mask1, hasHighKeys,
                                        k0, v0, k1, v1, k2, v2, k3, v3,
                                        k4, v4, k5, v5, k6, v6, k7, v7,
                                        k8, v8, k9, v9, k10, v10, k11, v11,
                                        k12, v12, k13, v13, k14, v14, k15, v15);
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
                if (target instanceof PersistentShapeMap16 sm && (sm.mask0 & kmask) != 0) {
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
                if (target instanceof PersistentShapeMap16 sm && (sm.mask1 & kmask) != 0) {
                    int slot = Long.bitCount(sm.mask0) + Long.bitCount(sm.mask1 & lowerMask);
                    return sm.getVal(slot);
                }
                return target;
            };
        } else {
            if (!hasHighKeys) return null;
            for (int i = 0; i < count; i++) {
                if (k == getKey(i)) {
                    final int slot = i;
                    return target -> target instanceof PersistentShapeMap16 sm && sm.getKey(slot) == k ? sm.getVal(slot) : target;
                }
            }
            return null;
        }
    }

    @Override
    public ITransientMap asTransient() {
        return new PersistentArrayMap(toArray()).asTransient();
    }
}
