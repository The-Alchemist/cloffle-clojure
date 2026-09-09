package clojure.lang;

import java.io.Serializable;
import java.util.Arrays;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.CompilerDirectives.ValueType;

/**
 * An immutable key layout for {@link PersistentShapeMap}: up to eight keywords
 * in ascending {@link Keyword#id} order.
 *
 * <p>Shapes are a value type.  Construction sorts and rejects anything
 * unsorted, so two shapes over the same key set carry identical fields, and
 * every operation here is a pure function of those fields — one instance is
 * freely substitutable for another.  {@link ValueType} declares exactly that to
 * Graal, which may then merge or duplicate shapes instead of materializing them
 * where control flow joins.
 *
 * <p>The price is that <strong>reference comparison is undefined</strong>:
 * {@code ==} on two shapes may report either answer, and neither
 * {@link System#identityHashCode} nor synchronization on a shape means
 * anything.  Compare layouts with {@link #sameKeys(MapShape)} or
 * {@link #equals(Object)}, and test for the empty layout with
 * {@code count == 0} rather than {@code shape == EMPTY}.  Shapes are also not
 * canonicalized, so equal layouts really do arrive as distinct objects: a
 * guard that compared references would never hit and its node would never
 * stabilize in compiled code.
 *
 * <p>The canonical order is what keeps derived quantities constant-foldable.
 * Against a cached shape, {@link #indexOf} folds to a literal slot, and every
 * key ordering of one map literal collapses onto a single shape, which is what
 * makes a two-entry inline cache sufficient.
 */
@ValueType
public final class MapShape implements Serializable {

    private static final long serialVersionUID = 1L;

    // ── Singleton empty shape ────────────────────────────────────────────

    /**
     * The zero-key layout.  A convenience for initializing an empty map, not a
     * canonical instance: this is a {@link ValueType}, so {@code shape == EMPTY}
     * is undefined.  Test {@code shape.count == 0} instead.
     */
    public static final MapShape EMPTY =
            new MapShape(0, null, null, null, null, null, null, null, null);

    // ── Instance fields ──────────────────────────────────────────────────

    public final int count;
    public final Keyword k0, k1, k2, k3, k4, k5, k6, k7;

    // ── Constructor (private) ────────────────────────────────────────────

    private MapShape(int count,
                     Keyword k0, Keyword k1, Keyword k2, Keyword k3,
                     Keyword k4, Keyword k5, Keyword k6, Keyword k7) {
        checkSorted(count, k0, k1, k2, k3, k4, k5, k6, k7);
        this.count = count;
        this.k0 = k0;
        this.k1 = k1;
        this.k2 = k2;
        this.k3 = k3;
        this.k4 = k4;
        this.k5 = k5;
        this.k6 = k6;
        this.k7 = k7;
    }

    /**
     * Slots 0..count-1 must hold distinct keywords in ascending id order and the
     * rest must be empty.
     *
     * <p>Checked on every construction because an out-of-order layout is
     * silently wrong rather than an error.  {@link #insertSlot} derives a
     * position by counting smaller ids; {@link Factory} routes source values
     * onto sorted slots; and {@link #sameKeys} collapses every shape over one
     * key set only because that set has exactly one canonical order.  Hand any
     * of them an unsorted shape and lookups return the wrong value.  The
     * {@code fromSorted} entry points are public and their callers inherit the
     * order from elsewhere — {@link PersistentShapeMap16} demotion, for
     * instance — so nothing else enforces this.
     *
     * <p>Fifteen comparisons in straight-line form, deliberately not a loop
     * over a varargs array: {@code count} is only a compile-time constant on
     * some paths ({@link #addKey} and {@link #removeKey} derive it from the
     * receiver), and where it is not, a loop would keep both the array and its
     * non-constant indexing alive.  This way the check costs registers and
     * branches at worst, and nothing at all once partial evaluation folds it.
     * The failure paths build their messages behind {@link TruffleBoundary}
     * and invalidate first, so no string concatenation reaches the compiled
     * graph either.
     */
    private static void checkSorted(int count,
                                    Keyword k0, Keyword k1, Keyword k2, Keyword k3,
                                    Keyword k4, Keyword k5, Keyword k6, Keyword k7) {
        if (count < 0 || count > 8) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw badCount(count);
        }
        checkPresence(count, 0, k0);
        checkPresence(count, 1, k1);
        checkPresence(count, 2, k2);
        checkPresence(count, 3, k3);
        checkPresence(count, 4, k4);
        checkPresence(count, 5, k5);
        checkPresence(count, 6, k6);
        checkPresence(count, 7, k7);
        checkAscending(count, 1, k0, k1);
        checkAscending(count, 2, k1, k2);
        checkAscending(count, 3, k2, k3);
        checkAscending(count, 4, k3, k4);
        checkAscending(count, 5, k4, k5);
        checkAscending(count, 6, k5, k6);
        checkAscending(count, 7, k6, k7);
    }

    /** A slot holds a keyword exactly when its index is below {@code count}. */
    private static void checkPresence(int count, int slot, Keyword k) {
        if (slot < count) {
            if (k == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw nullSlot(slot, count);
            }
        } else if (k != null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw slotPastCount(slot, k, count);
        }
    }

    /**
     * Adjacent occupied slots ascend by {@link Keyword#id}, which also rules out
     * duplicates.  Both keywords are non-null once {@link #checkPresence} has
     * passed for {@code slot} and the one before it.
     */
    private static void checkAscending(int count, int slot, Keyword prev, Keyword k) {
        if (slot < count && prev.id >= k.id) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw outOfOrder(prev, k);
        }
    }

    @TruffleBoundary
    private static IllegalArgumentException badCount(int count) {
        return new IllegalArgumentException("MapShape count out of range: " + count);
    }

    @TruffleBoundary
    private static IllegalArgumentException nullSlot(int slot, int count) {
        return new IllegalArgumentException(
                "MapShape slot " + slot + " is null but count is " + count);
    }

    @TruffleBoundary
    private static IllegalArgumentException outOfOrder(Keyword prev, Keyword next) {
        return new IllegalArgumentException("MapShape keys must be sorted and distinct: "
                + prev + " precedes " + next);
    }

    @TruffleBoundary
    private static IllegalArgumentException slotPastCount(int slot, Keyword k, int count) {
        return new IllegalArgumentException(
                "MapShape slot " + slot + " is " + k + " but count is " + count);
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

    public static MapShape fromSorted(int count,
                                      Keyword k0, Keyword k1, Keyword k2, Keyword k3,
                                      Keyword k4, Keyword k5, Keyword k6, Keyword k7) {
        return new MapShape(count, k0, k1, k2, k3, k4, k5, k6, k7);
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

    /**
     * Key-layout equality.  Shapes are not canonicalized, so a map built by one
     * route and a map built by another can carry equal layouts in distinct
     * objects; inline-cache guards must compare keys rather than references or
     * they never hit and their nodes never stabilize in compiled code.
     *
     * <p>The {@code ==} first line is a shortcut, not a contradiction of the
     * class contract: Graal only ever merges or duplicates instances that hold
     * the same value, so a hit cannot be wrong, and a miss just falls through
     * to the field comparison.
     */
    public boolean sameKeys(MapShape other) {
        if (this == other) return true;
        if (other == null) return false;
        return count == other.count
                && k0 == other.k0 && k1 == other.k1 && k2 == other.k2 && k3 == other.k3
                && k4 == other.k4 && k5 == other.k5 && k6 == other.k6 && k7 == other.k7;
    }

    /** Key-layout equality, so that the conventional API agrees with {@link #sameKeys}. */
    @Override
    public boolean equals(Object obj) {
        return obj instanceof MapShape other && sameKeys(other);
    }

    /**
     * Derived from the key ids rather than identity: shapes are not
     * canonicalized and {@link System#identityHashCode} is not stable for a
     * {@link ValueType}, so the default would disagree with {@link #equals}.
     */
    @Override
    public int hashCode() {
        long h = count;
        h = mixKey(h, k0); h = mixKey(h, k1); h = mixKey(h, k2); h = mixKey(h, k3);
        h = mixKey(h, k4); h = mixKey(h, k5); h = mixKey(h, k6); h = mixKey(h, k7);
        return (int) (h ^ (h >>> 32));
    }

    private static long mixKey(long h, Keyword k) {
        return (h + (k == null ? 0L : k.id)) * 0x9E3779B97F4A7C15L;
    }

    /**
     * Deserialization sets the fields directly and never reaches the
     * constructor, which would leave the sorted invariant unchecked on a shape
     * read off a stream — {@link PersistentShapeMap} is serializable and holds
     * one.  Rebuilding through {@link #fromSorted} re-runs the validation.
     * Returning a different instance is free here precisely because this is a
     * {@link ValueType}.
     */
    private Object readResolve() {
        return fromSorted(count, k0, k1, k2, k3, k4, k5, k6, k7);
    }

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
            default:
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw badSlot(i);
        }
    }

    @TruffleBoundary
    private static IndexOutOfBoundsException badSlot(int slot) {
        return new IndexOutOfBoundsException("Slot: " + slot);
    }

    // ── Transition methods ───────────────────────────────────────────────

    /**
     * The new layout with {@code kw} spliced in at {@code slot}, shifting the
     * occupants at and above it up by one.
     *
     * <p>Written as a switch over the eight slots rather than a scratch array
     * and two loops: {@code slot} and {@code count} are compile-time constants
     * on the cached-transition path but not on {@link PersistentShapeMap#assoc},
     * and a loop there would keep the array and its non-constant indexing
     * alive, so the resulting shape could never be scalar replaced.  Overflow
     * past eight keys is caught by the constructor's count check; callers
     * promote to {@link PersistentShapeMap16} instead.
     */
    public MapShape addKey(Keyword kw, int slot) {
        return switch (slot) {
            case 0 -> new MapShape(count + 1, kw, k0, k1, k2, k3, k4, k5, k6);
            case 1 -> new MapShape(count + 1, k0, kw, k1, k2, k3, k4, k5, k6);
            case 2 -> new MapShape(count + 1, k0, k1, kw, k2, k3, k4, k5, k6);
            case 3 -> new MapShape(count + 1, k0, k1, k2, kw, k3, k4, k5, k6);
            case 4 -> new MapShape(count + 1, k0, k1, k2, k3, kw, k4, k5, k6);
            case 5 -> new MapShape(count + 1, k0, k1, k2, k3, k4, kw, k5, k6);
            case 6 -> new MapShape(count + 1, k0, k1, k2, k3, k4, k5, kw, k6);
            case 7 -> new MapShape(count + 1, k0, k1, k2, k3, k4, k5, k6, kw);
            default -> {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw badSlot(slot);
            }
        };
    }

    public MapShape addKey(Keyword kw) {
        return addKey(kw, insertSlot(kw));
    }

    /**
     * The new layout without slot {@code slot}, shifting the occupants above it
     * down by one.  Switch rather than array-and-loop for the reason given on
     * {@link #addKey(Keyword, int)}.
     */
    public MapShape removeKey(int slot) {
        return switch (slot) {
            case 0 -> new MapShape(count - 1, k1, k2, k3, k4, k5, k6, k7, null);
            case 1 -> new MapShape(count - 1, k0, k2, k3, k4, k5, k6, k7, null);
            case 2 -> new MapShape(count - 1, k0, k1, k3, k4, k5, k6, k7, null);
            case 3 -> new MapShape(count - 1, k0, k1, k2, k4, k5, k6, k7, null);
            case 4 -> new MapShape(count - 1, k0, k1, k2, k3, k5, k6, k7, null);
            case 5 -> new MapShape(count - 1, k0, k1, k2, k3, k4, k6, k7, null);
            case 6 -> new MapShape(count - 1, k0, k1, k2, k3, k4, k5, k7, null);
            case 7 -> new MapShape(count - 1, k0, k1, k2, k3, k4, k5, k6, null);
            default -> {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw badSlot(slot);
            }
        };
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

}
