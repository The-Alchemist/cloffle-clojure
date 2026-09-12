package clojure.lang;

import java.io.Serializable;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.CompilerDirectives.ValueType;

/**
 * An immutable key layout for {@link PersistentShapeMap}: up to eight keywords
 * in construction / insertion order.
 *
 * <p>Shapes are a value type.  Construction records the keys in the order
 * given, so two shapes over the same key <em>set</em> are equal only when that
 * set was inserted in the same order.  Every operation here is a pure function
 * of those fields — one instance is freely substitutable for another that holds
 * the same fields.  {@link ValueType} declares exactly that to Graal, which may
 * then merge or duplicate shapes instead of materializing them where control
 * flow joins.
 *
 * <p>The price is that <strong>reference comparison is undefined</strong>:
 * {@code ==} on two shapes may report either answer, and neither
 * {@link System#identityHashCode} nor synchronization on a shape means
 * anything.  Compare layouts with {@link #sameKeys(MapShape)} or
 * {@link #equals(Object)}, and test for the empty layout with
 * {@code count == 0} rather than {@code shape == EMPTY}.  Shapes are also not
 * interned, so equal layouts really do arrive as distinct objects: a
 * guard that compared references would never hit and its node would never
 * stabilize in compiled code.
 *
 * <p>{@link #sameKeys} is fieldwise and therefore order-sensitive.  Against a
 * cached shape, {@link #indexOf} folds to a literal slot.  The same key set
 * written in a different order is a different shape, so a two-entry inline
 * cache is enough only for sites that see a small number of insertion orders.
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
        checkLayout(count, k0, k1, k2, k3, k4, k5, k6, k7);
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
     * Slots 0..count-1 must hold distinct keywords and the rest must be empty.
     *
     * <p>Checked on every construction because a duplicate or a hole is silently
     * wrong rather than an error.  {@link #insertSlot} appends at {@code count};
     * {@link Factory} maps source values onto those slots; and {@link #sameKeys}
     * is fieldwise identity, so two shapes over one key set agree only when the
     * keys occupy the same slots.  The {@code fromKeys} entry points are public
     * and their callers inherit the order from elsewhere —
     * {@link PersistentShapeMap16} demotion, for instance — so nothing else
     * enforces this.
     *
     * <p>Presence and pairwise identity checks in straight-line form,
     * deliberately not a loop over a varargs array: {@code count} is only a
     * compile-time constant on some paths ({@link #addKey} and
     * {@link #removeKey} derive it from the receiver), and where it is not, a
     * loop would keep both the array and its non-constant indexing alive.  This
     * way the check costs registers and branches at worst, and nothing at all
     * once partial evaluation folds it.  The failure paths build their messages
     * behind {@link TruffleBoundary} and invalidate first, so no string
     * concatenation reaches the compiled graph either.
     */
    private static void checkLayout(int count,
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
        checkDistinct(count, 1, k0, k1);
        checkDistinct(count, 2, k0, k2);
        checkDistinct(count, 2, k1, k2);
        checkDistinct(count, 3, k0, k3);
        checkDistinct(count, 3, k1, k3);
        checkDistinct(count, 3, k2, k3);
        checkDistinct(count, 4, k0, k4);
        checkDistinct(count, 4, k1, k4);
        checkDistinct(count, 4, k2, k4);
        checkDistinct(count, 4, k3, k4);
        checkDistinct(count, 5, k0, k5);
        checkDistinct(count, 5, k1, k5);
        checkDistinct(count, 5, k2, k5);
        checkDistinct(count, 5, k3, k5);
        checkDistinct(count, 5, k4, k5);
        checkDistinct(count, 6, k0, k6);
        checkDistinct(count, 6, k1, k6);
        checkDistinct(count, 6, k2, k6);
        checkDistinct(count, 6, k3, k6);
        checkDistinct(count, 6, k4, k6);
        checkDistinct(count, 6, k5, k6);
        checkDistinct(count, 7, k0, k7);
        checkDistinct(count, 7, k1, k7);
        checkDistinct(count, 7, k2, k7);
        checkDistinct(count, 7, k3, k7);
        checkDistinct(count, 7, k4, k7);
        checkDistinct(count, 7, k5, k7);
        checkDistinct(count, 7, k6, k7);
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
     * Occupied slots must be pairwise distinct by identity.  Both keywords are
     * non-null once {@link #checkPresence} has passed for {@code slot} and the
     * earlier slot.
     */
    private static void checkDistinct(int count, int slot, Keyword a, Keyword b) {
        if (slot < count && a == b) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw duplicateKey(a);
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
    private static IllegalArgumentException duplicateKey(Keyword k) {
        return new IllegalArgumentException("Duplicate key: " + k);
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
        for (int i = 0; i < keys.length; i++) {
            for (int j = i + 1; j < keys.length; j++) {
                if (keys[i] == keys[j]) {
                    throw new IllegalArgumentException("Duplicate key: " + keys[i]);
                }
            }
        }
        return fromKeys(keys.length, keys);
    }

    public static MapShape fromKeys(int count,
                                    Keyword k0, Keyword k1, Keyword k2, Keyword k3,
                                    Keyword k4, Keyword k5, Keyword k6, Keyword k7) {
        return new MapShape(count, k0, k1, k2, k3, k4, k5, k6, k7);
    }

    public static MapShape fromKeys(int count, Keyword[] keys) {
        return fromKeys(count,
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
     * Key-layout equality.  Shapes are not interned, so a map built by one
     * route and a map built by another can carry equal layouts in distinct
     * objects; inline-cache guards must compare keys rather than references or
     * they never hit and their nodes never stabilize in compiled code.
     *
     * <p>The {@code ==} first line is a shortcut, not a contradiction of the
     * class contract: Graal only ever merges or duplicates instances that hold
     * the same value, so a hit cannot be wrong, and a miss just falls through
     * to the field comparison.  Equality is order-sensitive: the same key set
     * in a different insertion order is a different layout.
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
     * interned and {@link System#identityHashCode} is not stable for a
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
     * constructor, which would leave the layout invariant unchecked on a shape
     * read off a stream — {@link PersistentShapeMap} is serializable and holds
     * one.  Rebuilding through {@link #fromKeys} re-runs the validation.
     * Returning a different instance is free here precisely because this is a
     * {@link ValueType}.
     */
    private Object readResolve() {
        return fromKeys(count, k0, k1, k2, k3, k4, k5, k6, k7);
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

    /**
     * Slot at which a keyword absent from this layout is inserted: always
     * {@link #count}, preserving insertion order.  Callers use
     * {@link #indexOf} first to detect updates.
     */
    public int insertSlot(Keyword kw) {
        return count;
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
     * promote to {@link PersistentShapeMap16} instead.  Insertion-order assoc
     * always passes {@code slot == count}.
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
     * Bundles a {@link MapShape} as a {@code @ConstantOperand} for the Truffle
     * bytecode interpreter.  Slots follow source / insertion order, so
     * {@link #sourceIndex} is the identity: each {@code pick} call resolves to
     * a direct value reference.
     */
    @ValueType
    public static final class Factory {
        public final MapShape shape;

        public Factory(MapShape shape, Keyword... sourceKeys) {
            this.shape = shape;
        }

        /** Returns the source index for the given slot (identity: insertion order). */
        public int sourceIndex(int slot) {
            return slot;
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
