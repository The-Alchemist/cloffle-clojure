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
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.CompilerDirectives.ValueType;
import com.oracle.truffle.api.nodes.ExplodeLoop;

/**
 * Shape-based immutable persistent map for medium keyword-only maps (9..16 keys).
 * Enables GraalVM Partial Escape Analysis (PEA) and scalar replacement by using
 * direct object fields and canonical Keyword.id ordering.
 */
@ValueType
public class PersistentShapeMap16 extends APersistentMap implements IObj, IEditableCollection, IMapIterable, IKVReduce, IDrop, IKeywordLookup, IReduce {

    private static final long serialVersionUID = 7712849182371928375L;

    public static final int MIN_SHAPE16_KEYS = 9;
    public static final int MAX_SHAPE16_KEYS = 16;

    public final int count;
    public final long tags0, tags1;
    public final Keyword k0, k1, k2, k3, k4, k5, k6, k7, k8, k9, k10, k11, k12, k13, k14, k15;
    public final Object v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15;
    private final IPersistentMap _meta;

    public PersistentShapeMap16(IPersistentMap meta, int count,
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
        this(meta, count,
                packTags(k0, k1, k2, k3, k4, k5, k6, k7),
                packTags(k8, k9, k10, k11, k12, k13, k14, k15),
                k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7,
                k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
    }

    public PersistentShapeMap16(IPersistentMap meta, int count, long tags0, long tags1,
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
        this.tags0 = tags0;
        this.tags1 = tags1;
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

    /**
     * Derives a 1-byte non-zero tag from Keyword.id.
     * Empty slots (null key) receive tag 0. Non-empty keys produce [1..255].
     */
    public static long tagOf(Keyword k) {
        if (k == null) return 0L;
        long h = ((k.id * 0x9E3779B97F4A7C15L) >>> 56) & 0xFFL;
        return h == 0L ? 1L : h;
    }

    private static long packTags(Keyword a, Keyword b, Keyword c, Keyword d,
                                 Keyword e, Keyword f, Keyword g, Keyword h) {
        return tagOf(a)
                | (tagOf(b) << 8)
                | (tagOf(c) << 16)
                | (tagOf(d) << 24)
                | (tagOf(e) << 32)
                | (tagOf(f) << 40)
                | (tagOf(g) << 48)
                | (tagOf(h) << 56);
    }

    /**
     * SWAR haszero: tests each of the 8 bytes in {@code v} for zero in parallel.
     * High bit of each zero byte is set in the result.
     * Reference: https://graphics.stanford.edu/~seander/bithacks.html#ZeroInWord
     */
    public static long haszero(long v) {
        return (v - 0x0101010101010101L) & ~v & 0x8080808080808080L;
    }

    /**
     * SWAR hasvalue: tests each of the 8 bytes in {@code tags} for matching {@code tag}.
     * High bit of each matching byte is set in the result.
     * Reference: https://graphics.stanford.edu/~seander/bithacks.html#ValueInWord
     */
    public static long hasvalue(long tags, long tag) {
        return haszero(tags ^ (0x0101010101010101L * tag));
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
        return new PersistentShapeMap16(meta, pairCount,
                                        pk0, pv0, pk1, pv1, pk2, pv2, pk3, pv3,
                                        pk4, pv4, pk5, pv5, pk6, pv6, pk7, pv7,
                                        pk8, pv8, pk9, pv9, pk10, pv10, pk11, pv11,
                                        pk12, pv12, pk13, pv13, pk14, pv14, pk15, pv15);
    }

    /**
     * Compile-time layout plus source-to-sorted permutation for 9–16 keyword keys.
     * Intended as a {@code @ConstantOperand}: keys are scalar fields and the
     * permutation is a {@code long} (4 bits × 16 slots), so no arrays escape
     * into the compiled graph.
     */
    @ValueType
    public static final class Factory {
        public final int count;
        public final long tags0, tags1;
        public final Keyword k0, k1, k2, k3, k4, k5, k6, k7, k8, k9, k10, k11, k12, k13, k14, k15;
        private final long permutation;

        public Factory(Keyword... sourceKeys) {
            if (sourceKeys == null
                    || sourceKeys.length < MIN_SHAPE16_KEYS
                    || sourceKeys.length > MAX_SHAPE16_KEYS) {
                throw new IllegalArgumentException(
                        "PersistentShapeMap16.Factory supports 9–16 keys, got "
                                + (sourceKeys == null ? 0 : sourceKeys.length));
            }
            int n = sourceKeys.length;
            for (int i = 0; i < n; i++) {
                if (sourceKeys[i] == null) {
                    throw new NullPointerException("ShapeMap16 keys must not be null");
                }
            }
            Keyword[] ks = sourceKeys.clone();
            byte[] idx = new byte[n];
            for (int i = 0; i < n; i++) {
                idx[i] = (byte) i;
            }
            for (int i = 1; i < n; i++) {
                Keyword key = ks[i];
                byte id = idx[i];
                int j = i - 1;
                while (j >= 0 && ks[j].id > key.id) {
                    ks[j + 1] = ks[j];
                    idx[j + 1] = idx[j];
                    j--;
                }
                ks[j + 1] = key;
                idx[j + 1] = id;
            }
            for (int i = 1; i < n; i++) {
                if (ks[i].id == ks[i - 1].id) {
                    throw new IllegalArgumentException("Duplicate key: " + ks[i]);
                }
            }
            this.count = n;
            this.k0 = ks[0];
            this.k1 = ks[1];
            this.k2 = ks[2];
            this.k3 = ks[3];
            this.k4 = ks[4];
            this.k5 = ks[5];
            this.k6 = ks[6];
            this.k7 = ks[7];
            this.k8 = ks[8];
            this.k9 = n > 9 ? ks[9] : null;
            this.k10 = n > 10 ? ks[10] : null;
            this.k11 = n > 11 ? ks[11] : null;
            this.k12 = n > 12 ? ks[12] : null;
            this.k13 = n > 13 ? ks[13] : null;
            this.k14 = n > 14 ? ks[14] : null;
            this.k15 = n > 15 ? ks[15] : null;
            this.tags0 = packTags(this.k0, this.k1, this.k2, this.k3, this.k4, this.k5, this.k6, this.k7);
            this.tags1 = packTags(this.k8, this.k9, this.k10, this.k11, this.k12, this.k13, this.k14, this.k15);
            long perm = 0L;
            for (int slot = 0; slot < n; slot++) {
                perm |= ((long) (idx[slot] & 0xF)) << (slot * 4);
            }
            this.permutation = perm;
        }

        /** Source-order index for the given sorted slot. */
        public int sourceIndex(int slot) {
            return (int) ((permutation >>> (slot * 4)) & 0xF);
        }

        public Keyword getKey(int slot) {
            return switch (slot) {
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

    /**
     * Probes this map for the presence of {@code kw} using SWAR tag matching.
     * Evaluates tag equality across 8 slots per operation, only performing
     * reference equality checks on candidate slots. Returns the matching slot [0..15],
     * or -1 if the key is not present.
     */
    public int indexOfKey(Keyword kw) {
        if (kw == null) return -1;
        long tag = tagOf(kw);

        // Probe low 8 slots (tags0)
        long m0 = hasvalue(tags0, tag);
        while (m0 != 0L) {
            int byteIdx = Long.numberOfTrailingZeros(m0) >>> 3;
            if (getKey(byteIdx) == kw) return byteIdx;
            m0 &= m0 - 1L; // clear lowest bit
            m0 &= ~(0xFFL << (byteIdx << 3)); // clear remaining bits in that byte lane
        }

        // Probe high 8 slots (tags1)
        long m1 = hasvalue(tags1, tag);
        while (m1 != 0L) {
            int byteIdx = Long.numberOfTrailingZeros(m1) >>> 3;
            if (getKey(8 + byteIdx) == kw) return 8 + byteIdx;
            m1 &= m1 - 1L;
            m1 &= ~(0xFFL << (byteIdx << 3));
        }

        return -1;
    }

    @Override
    public int count() {
        return count;
    }

    @Override
    public boolean containsKey(Object key) {
        if (key instanceof Keyword kw) {
            return indexOfKey(kw) >= 0;
        }
        return false;
    }

    @Override
    public IMapEntry entryAt(Object key) {
        if (key instanceof Keyword kw) {
            int slot = indexOfKey(kw);
            if (slot >= 0) {
                return (IMapEntry) MapEntry.create(getKey(slot), getVal(slot));
            }
        }
        return null;
    }

    @Override
    public Object valAt(Object key) {
        return valAt(key, null);
    }

    @Override
    // Slots at or past count always hold a null key, and a Keyword argument is never null,
    // so identity compares alone cannot match an unused slot: no count guards needed.
    // Kept as an unrolled switch/cascade so Graal PEA and scalar replacement can fold
    // constant-keyword lookups directly without entering loop/SWAR arithmetic.
    public Object valAt(Object key, Object notFound) {
        if (key instanceof Keyword kw) {
            if (kw == k0) return v0;
            if (kw == k1) return v1;
            if (kw == k2) return v2;
            if (kw == k3) return v3;
            if (kw == k4) return v4;
            if (kw == k5) return v5;
            if (kw == k6) return v6;
            if (kw == k7) return v7;
            if (kw == k8) return v8;
            if (kw == k9) return v9;
            if (kw == k10) return v10;
            if (kw == k11) return v11;
            if (kw == k12) return v12;
            if (kw == k13) return v13;
            if (kw == k14) return v14;
            if (kw == k15) return v15;
        }
        return notFound;
    }

    @Override
    public IPersistentMap assoc(Object key, Object val) {
        if (!(key instanceof Keyword kw)) {
            return assocNonKeyword(key, val);
        }

        // Check if key already exists
        int existingSlot = indexOfKey(kw);

        if (existingSlot >= 0) {
            return switch (existingSlot) {
                case 0 -> new PersistentShapeMap16(meta(), count, k0, val, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 1 -> new PersistentShapeMap16(meta(), count, k0, v0, k1, val, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 2 -> new PersistentShapeMap16(meta(), count, k0, v0, k1, v1, k2, val, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 3 -> new PersistentShapeMap16(meta(), count, k0, v0, k1, v1, k2, v2, k3, val, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 4 -> new PersistentShapeMap16(meta(), count, k0, v0, k1, v1, k2, v2, k3, v3, k4, val, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 5 -> new PersistentShapeMap16(meta(), count, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, val, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 6 -> new PersistentShapeMap16(meta(), count, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, val, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 7 -> new PersistentShapeMap16(meta(), count, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, val, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 8 -> new PersistentShapeMap16(meta(), count, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, val, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 9 -> new PersistentShapeMap16(meta(), count, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, val, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 10 -> new PersistentShapeMap16(meta(), count, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, val, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15);
                case 11 -> new PersistentShapeMap16(meta(), count, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, val, k12, v12, k13, v13, k14, v14, k15, v15);
                case 12 -> new PersistentShapeMap16(meta(), count, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, val, k13, v13, k14, v14, k15, v15);
                case 13 -> new PersistentShapeMap16(meta(), count, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, val, k14, v14, k15, v15);
                case 14 -> new PersistentShapeMap16(meta(), count, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, val, k15, v15);
                case 15 -> new PersistentShapeMap16(meta(), count, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, val);
                default -> this;
            };
        }

        if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY, count == MAX_SHAPE16_KEYS)) {
            return assocPromoteHashMap(kw, val);
        }

        // Keys are sorted by Keyword.id, so the slots ordering before kw form a contiguous
        // low run and their population count is the insertion index. Building a mask first
        // keeps the sixteen compares independent instead of chaining them through ins++.
        // The count guards are required here: kN.id would NPE on an unused slot.
        int want = kw.id;
        int lt = ((count > 0 && want > k0.id) ? 1        : 0)
               | ((count > 1 && want > k1.id) ? 1 << 1   : 0)
               | ((count > 2 && want > k2.id) ? 1 << 2   : 0)
               | ((count > 3 && want > k3.id) ? 1 << 3   : 0)
               | ((count > 4 && want > k4.id) ? 1 << 4   : 0)
               | ((count > 5 && want > k5.id) ? 1 << 5   : 0)
               | ((count > 6 && want > k6.id) ? 1 << 6   : 0)
               | ((count > 7 && want > k7.id) ? 1 << 7   : 0)
               | ((count > 8 && want > k8.id) ? 1 << 8   : 0)
               | ((count > 9 && want > k9.id) ? 1 << 9   : 0)
               | ((count > 10 && want > k10.id) ? 1 << 10 : 0)
               | ((count > 11 && want > k11.id) ? 1 << 11 : 0)
               | ((count > 12 && want > k12.id) ? 1 << 12 : 0)
               | ((count > 13 && want > k13.id) ? 1 << 13 : 0)
               | ((count > 14 && want > k14.id) ? 1 << 14 : 0)
               | ((count > 15 && want > k15.id) ? 1 << 15 : 0);
        int ins = Integer.bitCount(lt);

        Keyword nk0 = k0, nk1 = k1, nk2 = k2, nk3 = k3, nk4 = k4, nk5 = k5, nk6 = k6, nk7 = k7;
        Keyword nk8 = k8, nk9 = k9, nk10 = k10, nk11 = k11, nk12 = k12, nk13 = k13, nk14 = k14, nk15 = k15;
        Object nv0 = v0, nv1 = v1, nv2 = v2, nv3 = v3, nv4 = v4, nv5 = v5, nv6 = v6, nv7 = v7;
        Object nv8 = v8, nv9 = v9, nv10 = v10, nv11 = v11, nv12 = v12, nv13 = v13, nv14 = v14, nv15 = v15;
        switch (ins) {
            case 0 -> {
                nk15 = k14; nv15 = v14;
                nk14 = k13; nv14 = v13;
                nk13 = k12; nv13 = v12;
                nk12 = k11; nv12 = v11;
                nk11 = k10; nv11 = v10;
                nk10 = k9; nv10 = v9;
                nk9 = k8; nv9 = v8;
                nk8 = k7; nv8 = v7;
                nk7 = k6; nv7 = v6;
                nk6 = k5; nv6 = v5;
                nk5 = k4; nv5 = v4;
                nk4 = k3; nv4 = v3;
                nk3 = k2; nv3 = v2;
                nk2 = k1; nv2 = v1;
                nk1 = k0; nv1 = v0;
                nk0 = kw; nv0 = val;
            }
            case 1 -> {
                nk15 = k14; nv15 = v14;
                nk14 = k13; nv14 = v13;
                nk13 = k12; nv13 = v12;
                nk12 = k11; nv12 = v11;
                nk11 = k10; nv11 = v10;
                nk10 = k9; nv10 = v9;
                nk9 = k8; nv9 = v8;
                nk8 = k7; nv8 = v7;
                nk7 = k6; nv7 = v6;
                nk6 = k5; nv6 = v5;
                nk5 = k4; nv5 = v4;
                nk4 = k3; nv4 = v3;
                nk3 = k2; nv3 = v2;
                nk2 = k1; nv2 = v1;
                nk1 = kw; nv1 = val;
            }
            case 2 -> {
                nk15 = k14; nv15 = v14;
                nk14 = k13; nv14 = v13;
                nk13 = k12; nv13 = v12;
                nk12 = k11; nv12 = v11;
                nk11 = k10; nv11 = v10;
                nk10 = k9; nv10 = v9;
                nk9 = k8; nv9 = v8;
                nk8 = k7; nv8 = v7;
                nk7 = k6; nv7 = v6;
                nk6 = k5; nv6 = v5;
                nk5 = k4; nv5 = v4;
                nk4 = k3; nv4 = v3;
                nk3 = k2; nv3 = v2;
                nk2 = kw; nv2 = val;
            }
            case 3 -> {
                nk15 = k14; nv15 = v14;
                nk14 = k13; nv14 = v13;
                nk13 = k12; nv13 = v12;
                nk12 = k11; nv12 = v11;
                nk11 = k10; nv11 = v10;
                nk10 = k9; nv10 = v9;
                nk9 = k8; nv9 = v8;
                nk8 = k7; nv8 = v7;
                nk7 = k6; nv7 = v6;
                nk6 = k5; nv6 = v5;
                nk5 = k4; nv5 = v4;
                nk4 = k3; nv4 = v3;
                nk3 = kw; nv3 = val;
            }
            case 4 -> {
                nk15 = k14; nv15 = v14;
                nk14 = k13; nv14 = v13;
                nk13 = k12; nv13 = v12;
                nk12 = k11; nv12 = v11;
                nk11 = k10; nv11 = v10;
                nk10 = k9; nv10 = v9;
                nk9 = k8; nv9 = v8;
                nk8 = k7; nv8 = v7;
                nk7 = k6; nv7 = v6;
                nk6 = k5; nv6 = v5;
                nk5 = k4; nv5 = v4;
                nk4 = kw; nv4 = val;
            }
            case 5 -> {
                nk15 = k14; nv15 = v14;
                nk14 = k13; nv14 = v13;
                nk13 = k12; nv13 = v12;
                nk12 = k11; nv12 = v11;
                nk11 = k10; nv11 = v10;
                nk10 = k9; nv10 = v9;
                nk9 = k8; nv9 = v8;
                nk8 = k7; nv8 = v7;
                nk7 = k6; nv7 = v6;
                nk6 = k5; nv6 = v5;
                nk5 = kw; nv5 = val;
            }
            case 6 -> {
                nk15 = k14; nv15 = v14;
                nk14 = k13; nv14 = v13;
                nk13 = k12; nv13 = v12;
                nk12 = k11; nv12 = v11;
                nk11 = k10; nv11 = v10;
                nk10 = k9; nv10 = v9;
                nk9 = k8; nv9 = v8;
                nk8 = k7; nv8 = v7;
                nk7 = k6; nv7 = v6;
                nk6 = kw; nv6 = val;
            }
            case 7 -> {
                nk15 = k14; nv15 = v14;
                nk14 = k13; nv14 = v13;
                nk13 = k12; nv13 = v12;
                nk12 = k11; nv12 = v11;
                nk11 = k10; nv11 = v10;
                nk10 = k9; nv10 = v9;
                nk9 = k8; nv9 = v8;
                nk8 = k7; nv8 = v7;
                nk7 = kw; nv7 = val;
            }
            case 8 -> {
                nk15 = k14; nv15 = v14;
                nk14 = k13; nv14 = v13;
                nk13 = k12; nv13 = v12;
                nk12 = k11; nv12 = v11;
                nk11 = k10; nv11 = v10;
                nk10 = k9; nv10 = v9;
                nk9 = k8; nv9 = v8;
                nk8 = kw; nv8 = val;
            }
            case 9 -> {
                nk15 = k14; nv15 = v14;
                nk14 = k13; nv14 = v13;
                nk13 = k12; nv13 = v12;
                nk12 = k11; nv12 = v11;
                nk11 = k10; nv11 = v10;
                nk10 = k9; nv10 = v9;
                nk9 = kw; nv9 = val;
            }
            case 10 -> {
                nk15 = k14; nv15 = v14;
                nk14 = k13; nv14 = v13;
                nk13 = k12; nv13 = v12;
                nk12 = k11; nv12 = v11;
                nk11 = k10; nv11 = v10;
                nk10 = kw; nv10 = val;
            }
            case 11 -> {
                nk15 = k14; nv15 = v14;
                nk14 = k13; nv14 = v13;
                nk13 = k12; nv13 = v12;
                nk12 = k11; nv12 = v11;
                nk11 = kw; nv11 = val;
            }
            case 12 -> {
                nk15 = k14; nv15 = v14;
                nk14 = k13; nv14 = v13;
                nk13 = k12; nv13 = v12;
                nk12 = kw; nv12 = val;
            }
            case 13 -> {
                nk15 = k14; nv15 = v14;
                nk14 = k13; nv14 = v13;
                nk13 = kw; nv13 = val;
            }
            case 14 -> {
                nk15 = k14; nv15 = v14;
                nk14 = kw; nv14 = val;
            }
            case 15 -> {
                nk15 = kw; nv15 = val;
            }
        }
        return new PersistentShapeMap16(meta(), count + 1,
                nk0, nv0, nk1, nv1, nk2, nv2, nk3, nv3, nk4, nv4, nk5, nv5, nk6, nv6, nk7, nv7,
                nk8, nv8, nk9, nv9, nk10, nv10, nk11, nv11, nk12, nv12, nk13, nv13, nk14, nv14, nk15, nv15);
    }

    @TruffleBoundary
    private IPersistentMap assocNonKeyword(Object key, Object val) {
        Object[] arr = toArray();
        return PersistentHashMap.create(meta(), arr).assoc(key, val);
    }

    @TruffleBoundary
    private IPersistentMap assocPromoteHashMap(Keyword kw, Object val) {
        Object[] arr = toArray();
        return PersistentHashMap.create(meta(), arr).assoc(kw, val);
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

        int matchIdx = indexOfKey(kw);
        if (matchIdx == -1) {
            return this;
        }

        if (count == 9) {
            return switch (matchIdx) {
                case 0 -> new PersistentShapeMap(meta(), 8, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8);
                case 1 -> new PersistentShapeMap(meta(), 8, k0, v0, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8);
                case 2 -> new PersistentShapeMap(meta(), 8, k0, v0, k1, v1, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8);
                case 3 -> new PersistentShapeMap(meta(), 8, k0, v0, k1, v1, k2, v2, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8);
                case 4 -> new PersistentShapeMap(meta(), 8, k0, v0, k1, v1, k2, v2, k3, v3, k5, v5, k6, v6, k7, v7, k8, v8);
                case 5 -> new PersistentShapeMap(meta(), 8, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k6, v6, k7, v7, k8, v8);
                case 6 -> new PersistentShapeMap(meta(), 8, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k7, v7, k8, v8);
                case 7 -> new PersistentShapeMap(meta(), 8, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k8, v8);
                case 8 -> new PersistentShapeMap(meta(), 8, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7);
                default -> this;
            };
        }

        return switch (matchIdx) {
            case 0 -> new PersistentShapeMap16(meta(), count - 1, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, null, null);
            case 1 -> new PersistentShapeMap16(meta(), count - 1, k0, v0, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, null, null);
            case 2 -> new PersistentShapeMap16(meta(), count - 1, k0, v0, k1, v1, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, null, null);
            case 3 -> new PersistentShapeMap16(meta(), count - 1, k0, v0, k1, v1, k2, v2, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, null, null);
            case 4 -> new PersistentShapeMap16(meta(), count - 1, k0, v0, k1, v1, k2, v2, k3, v3, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, null, null);
            case 5 -> new PersistentShapeMap16(meta(), count - 1, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, null, null);
            case 6 -> new PersistentShapeMap16(meta(), count - 1, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, null, null);
            case 7 -> new PersistentShapeMap16(meta(), count - 1, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, null, null);
            case 8 -> new PersistentShapeMap16(meta(), count - 1, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, null, null);
            case 9 -> new PersistentShapeMap16(meta(), count - 1, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, null, null);
            case 10 -> new PersistentShapeMap16(meta(), count - 1, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k11, v11, k12, v12, k13, v13, k14, v14, k15, v15, null, null);
            case 11 -> new PersistentShapeMap16(meta(), count - 1, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k12, v12, k13, v13, k14, v14, k15, v15, null, null);
            case 12 -> new PersistentShapeMap16(meta(), count - 1, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k13, v13, k14, v14, k15, v15, null, null);
            case 13 -> new PersistentShapeMap16(meta(), count - 1, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k14, v14, k15, v15, null, null);
            case 14 -> new PersistentShapeMap16(meta(), count - 1, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k15, v15, null, null);
            case 15 -> new PersistentShapeMap16(meta(), count - 1, k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10, k11, v11, k12, v12, k13, v13, k14, v14, null, null);
            default -> this;
        };
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
        return new ShapeMap16Iter(this, APersistentMap.MAKE_ENTRY);
    }

    public Iterator keyIterator() {
        return new ShapeMap16Iter(this, APersistentMap.MAKE_KEY);
    }

    public Iterator valIterator() {
        return new ShapeMap16Iter(this, APersistentMap.MAKE_VAL);
    }

    @Override
    public ISeq seq() {
        if (count > 0) {
            return new ShapeMap16Seq(this, 0);
        }
        return null;
    }

    @Override
    public Sequential drop(int n) {
        if (count > 0) {
            return ((ShapeMap16Seq) seq()).drop(n);
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
        return new PersistentShapeMap16(meta, count,
                                        k0, v0, k1, v1, k2, v2, k3, v3,
                                        k4, v4, k5, v5, k6, v6, k7, v7,
                                        k8, v8, k9, v9, k10, v10, k11, v11,
                                        k12, v12, k13, v13, k14, v14, k15, v15);
    }

    @Override
    @ExplodeLoop
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
    @ExplodeLoop
    public Object reduce(IFn f, Object start) {
        Object acc = start;
        for (int i = 0; i < count; i++) {
            acc = f.invoke(acc, MapEntry.create(getKey(i), getVal(i)));
            if (RT.isReduced(acc))
                return ((IDeref) acc).deref();
        }
        return acc;
    }

    @Override
    @ExplodeLoop
    public Object reduce(IFn f) {
        if (count == 0) return f.invoke();
        Object acc = MapEntry.create(k0, v0);
        for (int i = 1; i < count; i++) {
            if (RT.isReduced(acc)) return ((IDeref) acc).deref();
            acc = f.invoke(acc, MapEntry.create(getKey(i), getVal(i)));
        }
        return RT.isReduced(acc) ? ((IDeref) acc).deref() : acc;
    }

    static final class ShapeMap16Seq extends ASeq implements Counted, IReduce, IDrop {
        final PersistentShapeMap16 map;
        final int i;

        ShapeMap16Seq(PersistentShapeMap16 map, int i) {
            this.map = map;
            this.i = i;
        }

        ShapeMap16Seq(IPersistentMap meta, PersistentShapeMap16 map, int i) {
            super(meta);
            this.map = map;
            this.i = i;
        }

        @Override
        public Object first() {
            return MapEntry.create(map.getKey(i), map.getVal(i));
        }

        @Override
        public ISeq next() {
            if (i + 1 < map.count)
                return new ShapeMap16Seq(map, i + 1);
            return null;
        }

        @Override
        public int count() {
            return map.count - i;
        }

        @Override
        public Sequential drop(int n) {
            if (n <= 0) return this;
            if (i + n < map.count) {
                return new ShapeMap16Seq(map, i + n);
            }
            return null;
        }

        @Override
        public Obj withMeta(IPersistentMap meta) {
            if (meta() == meta) return this;
            return new ShapeMap16Seq(meta, map, i);
        }

        @Override
        public Object reduce(IFn f) {
            if (i < map.count) {
                Object acc = MapEntry.create(map.getKey(i), map.getVal(i));
                for (int j = i + 1; j < map.count; j++) {
                    acc = f.invoke(acc, MapEntry.create(map.getKey(j), map.getVal(j)));
                    if (RT.isReduced(acc))
                        return ((IDeref) acc).deref();
                }
                return acc;
            } else {
                return f.invoke();
            }
        }

        @Override
        public Object reduce(IFn f, Object start) {
            Object acc = start;
            for (int j = i; j < map.count; j++) {
                acc = f.invoke(acc, MapEntry.create(map.getKey(j), map.getVal(j)));
                if (RT.isReduced(acc))
                    return ((IDeref) acc).deref();
            }
            return acc;
        }
    }

    static final class ShapeMap16Iter implements Iterator {
        final PersistentShapeMap16 map;
        final IFn f;
        int i = 0;

        ShapeMap16Iter(PersistentShapeMap16 map, IFn f) {
            this.map = map;
            this.f = f;
        }

        @Override
        public boolean hasNext() {
            return i < map.count;
        }

        @Override
        public Object next() {
            if (i >= map.count) throw new NoSuchElementException();
            Object ret = f.invoke(map.getKey(i), map.getVal(i));
            i++;
            return ret;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public ILookupThunk getLookupThunk(final Keyword k) {
        int slot = indexOfKey(k);
        if (slot < 0) return null;
        final int targetSlot = slot;
        return new ILookupThunk() {
            @Override
            public Object get(Object target) {
                return target instanceof PersistentShapeMap16 sm && sm.getKey(targetSlot) == k ? sm.getVal(targetSlot) : this;
            }
        };
    }

    @Override
    public ITransientMap asTransient() {
        return new PersistentArrayMap(toArray()).asTransient();
    }

    /**
     * A bytecode-node-local, immutable dissoc plan for PersistentShapeMap16.
     * Specializes on 9->8 key demotion to PersistentShapeMap and no-ops on 9-key maps.
     */
    @ValueType
    public abstract static class Dissoc16Transition {
        public final Keyword keyword;
        public final int count;
        public final Keyword k0, k1, k2, k3, k4, k5, k6, k7, k8;

        protected Dissoc16Transition(PersistentShapeMap16 map, Keyword keyword) {
            this.keyword = keyword;
            this.count = map.count;
            this.k0 = map.k0;
            this.k1 = map.k1;
            this.k2 = map.k2;
            this.k3 = map.k3;
            this.k4 = map.k4;
            this.k5 = map.k5;
            this.k6 = map.k6;
            this.k7 = map.k7;
            this.k8 = map.k8;
        }

        // Unused slots are null on both sides once counts agree, so the key compares need
        // no count guards. Non-short-circuiting & keeps this a flat AND-tree rather than
        // nine branches; all nine compares run anyway on the cache-hit path.
        public final boolean matches(PersistentShapeMap16 map, Keyword keyword) {
            return this.keyword == keyword
                    && map.count == count
                    && ((map.k0 == k0) & (map.k1 == k1) & (map.k2 == k2) & (map.k3 == k3)
                      & (map.k4 == k4) & (map.k5 == k5) & (map.k6 == k6) & (map.k7 == k7)
                      & (map.k8 == k8));
        }

        public abstract IPersistentMap apply(PersistentShapeMap16 map);
    }

    private static final class NoOpDissoc16Transition extends Dissoc16Transition {
        private NoOpDissoc16Transition(PersistentShapeMap16 map, Keyword keyword) {
            super(map, keyword);
        }

        @Override
        public IPersistentMap apply(PersistentShapeMap16 map) {
            return map;
        }
    }

    private static final class DemoteToShape8Transition extends Dissoc16Transition {
        private final byte slot;
        private final Keyword toK0, toK1, toK2, toK3, toK4, toK5, toK6, toK7;

        private DemoteToShape8Transition(PersistentShapeMap16 map, Keyword keyword, int slot) {
            super(map, keyword);
            this.slot = (byte) slot;

            Keyword[] dest = new Keyword[8];
            int d = 0;
            for (int i = 0; i < 9; i++) {
                if (i != slot) {
                    dest[d++] = map.getKey(i);
                }
            }
            this.toK0 = dest[0];
            this.toK1 = dest[1];
            this.toK2 = dest[2];
            this.toK3 = dest[3];
            this.toK4 = dest[4];
            this.toK5 = dest[5];
            this.toK6 = dest[6];
            this.toK7 = dest[7];
        }

        @Override
        public PersistentShapeMap apply(PersistentShapeMap16 map) {
            return switch (slot) {
                case 0 -> new PersistentShapeMap(map.meta(), 8,
                        toK0, map.v1, toK1, map.v2, toK2, map.v3, toK3, map.v4,
                        toK4, map.v5, toK5, map.v6, toK6, map.v7, toK7, map.v8);
                case 1 -> new PersistentShapeMap(map.meta(), 8,
                        toK0, map.v0, toK1, map.v2, toK2, map.v3, toK3, map.v4,
                        toK4, map.v5, toK5, map.v6, toK6, map.v7, toK7, map.v8);
                case 2 -> new PersistentShapeMap(map.meta(), 8,
                        toK0, map.v0, toK1, map.v1, toK2, map.v3, toK3, map.v4,
                        toK4, map.v5, toK5, map.v6, toK6, map.v7, toK7, map.v8);
                case 3 -> new PersistentShapeMap(map.meta(), 8,
                        toK0, map.v0, toK1, map.v1, toK2, map.v2, toK3, map.v4,
                        toK4, map.v5, toK5, map.v6, toK6, map.v7, toK7, map.v8);
                case 4 -> new PersistentShapeMap(map.meta(), 8,
                        toK0, map.v0, toK1, map.v1, toK2, map.v2, toK3, map.v3,
                        toK4, map.v5, toK5, map.v6, toK6, map.v7, toK7, map.v8);
                case 5 -> new PersistentShapeMap(map.meta(), 8,
                        toK0, map.v0, toK1, map.v1, toK2, map.v2, toK3, map.v3,
                        toK4, map.v4, toK5, map.v6, toK6, map.v7, toK7, map.v8);
                case 6 -> new PersistentShapeMap(map.meta(), 8,
                        toK0, map.v0, toK1, map.v1, toK2, map.v2, toK3, map.v3,
                        toK4, map.v4, toK5, map.v5, toK6, map.v7, toK7, map.v8);
                case 7 -> new PersistentShapeMap(map.meta(), 8,
                        toK0, map.v0, toK1, map.v1, toK2, map.v2, toK3, map.v3,
                        toK4, map.v4, toK5, map.v5, toK6, map.v6, toK7, map.v8);
                case 8 -> new PersistentShapeMap(map.meta(), 8,
                        toK0, map.v0, toK1, map.v1, toK2, map.v2, toK3, map.v3,
                        toK4, map.v4, toK5, map.v5, toK6, map.v6, toK7, map.v7);
                default -> throw new AssertionError("Invalid Shape16 demote slot: " + slot);
            };
        }
    }

    /**
     * Bytecode-node-local existing-key rewrite plan. Insertion and 16→hash
     * promotion stay on generic {@link #assoc}; a never-matching sentinel is
     * returned for absent keys so the Truffle cache initializer cannot be null.
     */
    @ValueType
    public abstract static class Assoc16Transition {
        public final Keyword keyword;
        public final int count;
        public final Keyword k0, k1, k2, k3, k4, k5, k6, k7, k8, k9, k10, k11, k12, k13, k14, k15;

        protected Assoc16Transition(PersistentShapeMap16 map, Keyword keyword) {
            this.keyword = keyword;
            this.count = map.count;
            this.k0 = map.k0;
            this.k1 = map.k1;
            this.k2 = map.k2;
            this.k3 = map.k3;
            this.k4 = map.k4;
            this.k5 = map.k5;
            this.k6 = map.k6;
            this.k7 = map.k7;
            this.k8 = map.k8;
            this.k9 = map.k9;
            this.k10 = map.k10;
            this.k11 = map.k11;
            this.k12 = map.k12;
            this.k13 = map.k13;
            this.k14 = map.k14;
            this.k15 = map.k15;
        }

        public boolean matches(PersistentShapeMap16 map, Keyword keyword) {
            return this.keyword == keyword
                    && map.count == count
                    && ((map.k0 == k0) & (map.k1 == k1) & (map.k2 == k2) & (map.k3 == k3)
                      & (map.k4 == k4) & (map.k5 == k5) & (map.k6 == k6) & (map.k7 == k7)
                      & (map.k8 == k8) & (map.k9 == k9) & (map.k10 == k10) & (map.k11 == k11)
                      & (map.k12 == k12) & (map.k13 == k13) & (map.k14 == k14) & (map.k15 == k15));
        }

        public abstract IPersistentMap apply(PersistentShapeMap16 map, Object val);
    }

    private static final class Miss16Transition extends Assoc16Transition {
        private Miss16Transition(PersistentShapeMap16 map, Keyword keyword) {
            super(map, keyword);
        }

        @Override
        public boolean matches(PersistentShapeMap16 map, Keyword keyword) {
            return false;
        }

        @Override
        public IPersistentMap apply(PersistentShapeMap16 map, Object val) {
            return map.assoc(keyword, val);
        }
    }

    private static final class Update16Transition extends Assoc16Transition {
        private final byte slot;

        private Update16Transition(PersistentShapeMap16 map, Keyword keyword, int slot) {
            super(map, keyword);
            this.slot = (byte) slot;
        }

        @Override
        public PersistentShapeMap16 apply(PersistentShapeMap16 map, Object val) {
            Object nv0 = map.v0, nv1 = map.v1, nv2 = map.v2, nv3 = map.v3;
            Object nv4 = map.v4, nv5 = map.v5, nv6 = map.v6, nv7 = map.v7;
            Object nv8 = map.v8, nv9 = map.v9, nv10 = map.v10, nv11 = map.v11;
            Object nv12 = map.v12, nv13 = map.v13, nv14 = map.v14, nv15 = map.v15;
            switch (slot) {
                case 0 -> nv0 = val;
                case 1 -> nv1 = val;
                case 2 -> nv2 = val;
                case 3 -> nv3 = val;
                case 4 -> nv4 = val;
                case 5 -> nv5 = val;
                case 6 -> nv6 = val;
                case 7 -> nv7 = val;
                case 8 -> nv8 = val;
                case 9 -> nv9 = val;
                case 10 -> nv10 = val;
                case 11 -> nv11 = val;
                case 12 -> nv12 = val;
                case 13 -> nv13 = val;
                case 14 -> nv14 = val;
                case 15 -> nv15 = val;
                default -> throw new AssertionError("Invalid ShapeMap16 update slot: " + slot);
            }
            return new PersistentShapeMap16(map.meta(), map.count, map.tags0, map.tags1,
                    map.k0, nv0, map.k1, nv1, map.k2, nv2, map.k3, nv3,
                    map.k4, nv4, map.k5, nv5, map.k6, nv6, map.k7, nv7,
                    map.k8, nv8, map.k9, nv9, map.k10, nv10, map.k11, nv11,
                    map.k12, nv12, map.k13, nv13, map.k14, nv14, map.k15, nv15);
        }
    }

    /**
     * Bytecode-node-local existing-key lookup plan. Cached slot plus a flat
     * key-layout comparison so {@code KeywordLookup} can {@code getVal} without
     * sending a virtual {@code PersistentShapeMap16} through {@code ILookup.valAt}.
     */
    @ValueType
    public static final class Lookup16Transition {
        public final Keyword keyword;
        public final int count;
        public final Keyword k0, k1, k2, k3, k4, k5, k6, k7, k8, k9, k10, k11, k12, k13, k14, k15;
        private final byte slot;

        private Lookup16Transition(PersistentShapeMap16 map, Keyword keyword, int slot) {
            this.keyword = keyword;
            this.count = map.count;
            this.k0 = map.k0;
            this.k1 = map.k1;
            this.k2 = map.k2;
            this.k3 = map.k3;
            this.k4 = map.k4;
            this.k5 = map.k5;
            this.k6 = map.k6;
            this.k7 = map.k7;
            this.k8 = map.k8;
            this.k9 = map.k9;
            this.k10 = map.k10;
            this.k11 = map.k11;
            this.k12 = map.k12;
            this.k13 = map.k13;
            this.k14 = map.k14;
            this.k15 = map.k15;
            this.slot = (byte) slot;
        }

        public boolean matches(PersistentShapeMap16 map, Keyword keyword) {
            return this.keyword == keyword
                    && map.count == count
                    && ((map.k0 == k0) & (map.k1 == k1) & (map.k2 == k2) & (map.k3 == k3)
                      & (map.k4 == k4) & (map.k5 == k5) & (map.k6 == k6) & (map.k7 == k7)
                      & (map.k8 == k8) & (map.k9 == k9) & (map.k10 == k10) & (map.k11 == k11)
                      & (map.k12 == k12) & (map.k13 == k13) & (map.k14 == k14) & (map.k15 == k15));
        }

        public Object get(PersistentShapeMap16 map, Object notFound) {
            return slot >= 0 ? map.getVal(slot) : notFound;
        }
    }

    public static Assoc16Transition assocTransition(PersistentShapeMap16 map, Keyword keyword) {
        int slot = map.indexOfKey(keyword);
        if (slot < 0) {
            return new Miss16Transition(map, keyword);
        }
        return new Update16Transition(map, keyword, slot);
    }

    public static Lookup16Transition lookupTransition(PersistentShapeMap16 map, Keyword keyword) {
        return new Lookup16Transition(map, keyword, map.indexOfKey(keyword));
    }

    public static Dissoc16Transition dissocTransition(PersistentShapeMap16 map, Keyword keyword) {
        if (map.count != 9) {
            return null;
        }
        int slot = -1;
        if (map.k0 == keyword) slot = 0;
        else if (map.k1 == keyword) slot = 1;
        else if (map.k2 == keyword) slot = 2;
        else if (map.k3 == keyword) slot = 3;
        else if (map.k4 == keyword) slot = 4;
        else if (map.k5 == keyword) slot = 5;
        else if (map.k6 == keyword) slot = 6;
        else if (map.k7 == keyword) slot = 7;
        else if (map.k8 == keyword) slot = 8;

        if (slot < 0) {
            return new NoOpDissoc16Transition(map, keyword);
        }
        return new DemoteToShape8Transition(map, keyword, slot);
    }
}
