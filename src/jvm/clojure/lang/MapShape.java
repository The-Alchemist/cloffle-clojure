package clojure.lang;

import java.io.ObjectStreamException;
import java.io.Serializable;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.CompilerDirectives.ValueType;

@ValueType
public final class MapShape implements Serializable {

    private static final long serialVersionUID = 1L;

    // ── Intern table (weak, global) ──────────────────────────────────────

    private static final ConcurrentHashMap<Long, Reference<MapShape>> INTERN_TABLE =
            new ConcurrentHashMap<>();
    static final ReferenceQueue<MapShape> RQ = new ReferenceQueue<>();

    // ── Singleton empty shape ────────────────────────────────────────────

    public static final MapShape EMPTY =
            intern(new MapShape(0, null, null, null, null, null, null, null, null));

    // ── Instance fields ──────────────────────────────────────────────────

    public final int count;
    public final Keyword k0, k1, k2, k3, k4, k5, k6, k7;
    public final long tags;

    // ── Per-shape transition caches (lock-free, racy is fine) ────────────

    private volatile Object addCache1Key, addCache1Val;
    private volatile Object addCache2Key, addCache2Val;

    private volatile int removeCache1Slot = -1;
    private volatile Object removeCache1Val;
    private volatile int removeCache2Slot = -1;
    private volatile Object removeCache2Val;

    // ── Constructor (private) ────────────────────────────────────────────

    private MapShape(int count,
                     Keyword k0, Keyword k1, Keyword k2, Keyword k3,
                     Keyword k4, Keyword k5, Keyword k6, Keyword k7) {
        this.count = count;
        this.k0 = k0;
        this.k1 = k1;
        this.k2 = k2;
        this.k3 = k3;
        this.k4 = k4;
        this.k5 = k5;
        this.k6 = k6;
        this.k7 = k7;
        this.tags = packTags(k0, k1, k2, k3, k4, k5, k6, k7);
    }

    // ── Tag computation (matches PersistentShapeMap16.tagOf) ─────────────

    private static long tagOf(Keyword k) {
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

    // ── Canonical key (hash for intern table) ────────────────────────────

    private static long canonicalKey(int count,
                                     Keyword k0, Keyword k1, Keyword k2, Keyword k3,
                                     Keyword k4, Keyword k5, Keyword k6, Keyword k7) {
        long h = count;
        if (k0 != null) h ^= k0.id * 0x9E3779B97F4A7C15L;
        if (k1 != null) h ^= k1.id * 0x9E3779B97F4A7C15L;
        if (k2 != null) h ^= k2.id * 0x9E3779B97F4A7C15L;
        if (k3 != null) h ^= k3.id * 0x9E3779B97F4A7C15L;
        if (k4 != null) h ^= k4.id * 0x9E3779B97F4A7C15L;
        if (k5 != null) h ^= k5.id * 0x9E3779B97F4A7C15L;
        if (k6 != null) h ^= k6.id * 0x9E3779B97F4A7C15L;
        if (k7 != null) h ^= k7.id * 0x9E3779B97F4A7C15L;
        return h;
    }

    // ── Interning ────────────────────────────────────────────────────────

    @TruffleBoundary
    static MapShape intern(MapShape shape) {
        long ck = canonicalKey(shape.count,
                shape.k0, shape.k1, shape.k2, shape.k3,
                shape.k4, shape.k5, shape.k6, shape.k7);
        Reference<MapShape> existingRef = INTERN_TABLE.get(ck);
        if (existingRef != null) {
            MapShape existing = existingRef.get();
            if (existing != null) return existing;
        }
        Util.clearCache(RQ, INTERN_TABLE);
        WeakReference<MapShape> newRef = new WeakReference<>(shape, RQ);
        Reference<MapShape> prev = INTERN_TABLE.putIfAbsent(ck, newRef);
        if (prev == null) return shape;
        MapShape existing = prev.get();
        if (existing != null) return existing;
        INTERN_TABLE.put(ck, newRef);
        return shape;
    }

    // ── Static factories ─────────────────────────────────────────────────

    @TruffleBoundary
    public static MapShape of(Keyword... keys) {
        if (keys.length == 0) return EMPTY;
        if (keys.length > 8) {
            throw new IllegalArgumentException("MapShape supports at most 8 keys");
        }
        for (Keyword k : keys) {
            if (k == null) throw new NullPointerException("MapShape keys must not be null");
        }
        Keyword[] sorted = keys.clone();
        Arrays.sort(sorted, (a, b) -> Long.compare(a.id, b.id));
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i].id == sorted[i - 1].id) {
                throw new IllegalArgumentException("Duplicate key: " + sorted[i]);
            }
        }
        return fromSorted(sorted.length, sorted);
    }

    @TruffleBoundary
    public static MapShape fromSorted(int count,
                                      Keyword k0, Keyword k1, Keyword k2, Keyword k3,
                                      Keyword k4, Keyword k5, Keyword k6, Keyword k7) {
        return intern(new MapShape(count, k0, k1, k2, k3, k4, k5, k6, k7));
    }

    public static MapShape fromSorted(int count, Keyword[] keys) {
        return fromSorted(count,
                keys.length > 0 ? keys[0] : null,
                keys.length > 1 ? keys[1] : null,
                keys.length > 2 ? keys[2] : null,
                keys.length > 3 ? keys[3] : null,
                keys.length > 4 ? keys[4] : null,
                keys.length > 5 ? keys[5] : null,
                keys.length > 6 ? keys[6] : null,
                keys.length > 7 ? keys[7] : null);
    }

    // ── Key operations ───────────────────────────────────────────────────

    public int indexOf(Keyword kw) {
        if (kw == k0) return 0;
        if (kw == k1) return 1;
        if (kw == k2) return 2;
        if (kw == k3) return 3;
        if (kw == k4) return 4;
        if (kw == k5) return 5;
        if (kw == k6) return 6;
        if (kw == k7) return 7;
        return -1;
    }

    public int insertSlot(Keyword kw) {
        long id = kw.id;
        int mask = 0;
        if (k0 != null && id > k0.id) mask |= 1;
        if (k1 != null && id > k1.id) mask |= 2;
        if (k2 != null && id > k2.id) mask |= 4;
        if (k3 != null && id > k3.id) mask |= 8;
        if (k4 != null && id > k4.id) mask |= 16;
        if (k5 != null && id > k5.id) mask |= 32;
        if (k6 != null && id > k6.id) mask |= 64;
        if (k7 != null && id > k7.id) mask |= 128;
        return Integer.bitCount(mask);
    }

    public Keyword getKey(int i) {
        switch (i) {
            case 0: return k0;
            case 1: return k1;
            case 2: return k2;
            case 3: return k3;
            case 4: return k4;
            case 5: return k5;
            case 6: return k6;
            case 7: return k7;
            default: throw new IndexOutOfBoundsException("Slot: " + i);
        }
    }

    // ── Transition methods ───────────────────────────────────────────────

    public MapShape addKey(Keyword kw, int slot) {
        Object ck = addCache1Key;
        if (ck == kw) {
            MapShape v = (MapShape) addCache1Val;
            if (v != null) return v;
        }
        ck = addCache2Key;
        if (ck == kw) {
            MapShape v = (MapShape) addCache2Val;
            if (v != null) return v;
        }
        return addKeySlow(kw, slot);
    }

    @TruffleBoundary
    private MapShape addKeySlow(Keyword kw, int slot) {
        Keyword[] keys = new Keyword[8];
        for (int i = 0; i < slot; i++) keys[i] = getKey(i);
        keys[slot] = kw;
        for (int i = slot; i < count; i++) keys[i + 1] = getKey(i);

        MapShape result = intern(new MapShape(count + 1,
                keys[0], keys[1], keys[2], keys[3],
                keys[4], keys[5], keys[6], keys[7]));

        addCache2Val = addCache1Val;
        addCache2Key = addCache1Key;
        addCache1Val = result;
        addCache1Key = kw;

        return result;
    }

    public MapShape addKey(Keyword kw) {
        return addKey(kw, insertSlot(kw));
    }

    public MapShape removeKey(int slot) {
        int cs1 = removeCache1Slot;
        if (cs1 == slot) {
            MapShape v = (MapShape) removeCache1Val;
            if (v != null) return v;
        }
        int cs2 = removeCache2Slot;
        if (cs2 == slot) {
            MapShape v = (MapShape) removeCache2Val;
            if (v != null) return v;
        }
        return removeKeySlow(slot);
    }

    @TruffleBoundary
    private MapShape removeKeySlow(int slot) {
        Keyword[] keys = new Keyword[8];
        for (int i = 0; i < slot; i++) keys[i] = getKey(i);
        for (int i = slot + 1; i < count; i++) keys[i - 1] = getKey(i);

        MapShape result = intern(new MapShape(count - 1,
                keys[0], keys[1], keys[2], keys[3],
                keys[4], keys[5], keys[6], keys[7]));

        removeCache2Val = removeCache1Val;
        removeCache2Slot = removeCache1Slot;
        removeCache1Val = result;
        removeCache1Slot = slot;

        return result;
    }

    // ── Factory (compile-time constant for shaped map creation) ─────────

    /**
     * Bundles a {@link MapShape} with a source-to-sorted permutation so that
     * values emitted in source order can be routed to their canonical slot
     * positions.  Intended as a {@code @ConstantOperand} in the Truffle
     * bytecode interpreter — the permutation folds at compile time and
     * each {@code pick} call resolves to a direct value reference.
     *
     * <p>Packed layout: 4 bits per sorted slot, giving the source index
     * (0–7).  8 slots × 4 bits = 32 bits, fits in a single {@code int}.
     */
    @ValueType
    public static final class Factory {
        public final MapShape shape;
        private final int permutation;

        public Factory(MapShape shape, Keyword... sourceKeys) {
            this.shape = shape;
            int perm = 0;
            for (int slot = 0; slot < shape.count; slot++) {
                Keyword sortedKey = shape.getKey(slot);
                for (int src = 0; src < sourceKeys.length; src++) {
                    if (sourceKeys[src] == sortedKey) {
                        perm |= (src << (slot * 4));
                        break;
                    }
                }
            }
            this.permutation = perm;
        }

        /** Returns the source index for the given sorted slot. */
        public int sourceIndex(int slot) {
            return (permutation >>> (slot * 4)) & 0xF;
        }
    }

    // ── SlotGetter (inner class) ─────────────────────────────────────────

    @ValueType
    public static final class SlotGetter {
        private final MapShape shape;
        private final int slot;

        SlotGetter(MapShape shape, int slot) {
            this.shape = shape;
            this.slot = slot;
        }

        public MapShape shape() { return shape; }
        public int slot() { return slot; }
    }

    public SlotGetter slotGetter(Keyword k) {
        int idx = indexOf(k);
        return idx >= 0 ? new SlotGetter(this, idx) : null;
    }

    // ── Serialization ────────────────────────────────────────────────────

    private Object readResolve() throws ObjectStreamException {
        return intern(this);
    }
}
