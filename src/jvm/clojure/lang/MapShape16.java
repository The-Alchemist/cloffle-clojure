package clojure.lang;

import java.io.Serializable;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.CompilerDirectives.ValueType;

/**
 * An immutable key layout for {@link PersistentShapeMap16}: up to sixteen keywords
 * in construction / insertion order.
 *
 * <p>Sibling of {@link MapShape} (≤8). Same {@link ValueType} contract: compare with
 * {@link #sameKeys}, never {@code ==}. Not interned.
 *
 * <p>Counts 0..{@link #MAX_KEYS} are valid for layout algebra (e.g. merge results).
 * Live {@link PersistentShapeMap16} instances use 9..16.
 */
@ValueType
public final class MapShape16 implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int MAX_KEYS = 16;
    public static final int MIN_PERSISTENT_KEYS = 9;

    public static final MapShape16 EMPTY =
            new MapShape16(0,
                    null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null);

    public final int count;
    public final Keyword k0, k1, k2, k3, k4, k5, k6, k7;
    public final Keyword k8, k9, k10, k11, k12, k13, k14, k15;

    private MapShape16(int count,
                       Keyword k0, Keyword k1, Keyword k2, Keyword k3,
                       Keyword k4, Keyword k5, Keyword k6, Keyword k7,
                       Keyword k8, Keyword k9, Keyword k10, Keyword k11,
                       Keyword k12, Keyword k13, Keyword k14, Keyword k15) {
        if (count < 0 || count > MAX_KEYS) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IllegalArgumentException("MapShape16 count out of range: " + count);
        }
        this.count = count;
        this.k0 = k0; this.k1 = k1; this.k2 = k2; this.k3 = k3;
        this.k4 = k4; this.k5 = k5; this.k6 = k6; this.k7 = k7;
        this.k8 = k8; this.k9 = k9; this.k10 = k10; this.k11 = k11;
        this.k12 = k12; this.k13 = k13; this.k14 = k14; this.k15 = k15;
    }

    public static MapShape16 fromKeys(int count, Keyword[] keys) {
        return new MapShape16(count,
                count > 0 ? keys[0] : null, count > 1 ? keys[1] : null,
                count > 2 ? keys[2] : null, count > 3 ? keys[3] : null,
                count > 4 ? keys[4] : null, count > 5 ? keys[5] : null,
                count > 6 ? keys[6] : null, count > 7 ? keys[7] : null,
                count > 8 ? keys[8] : null, count > 9 ? keys[9] : null,
                count > 10 ? keys[10] : null, count > 11 ? keys[11] : null,
                count > 12 ? keys[12] : null, count > 13 ? keys[13] : null,
                count > 14 ? keys[14] : null, count > 15 ? keys[15] : null);
    }

    public static MapShape16 from(PersistentShapeMap16 m) {
        return new MapShape16(m.count,
                m.k0, m.k1, m.k2, m.k3, m.k4, m.k5, m.k6, m.k7,
                m.k8, m.k9, m.k10, m.k11, m.k12, m.k13, m.k14, m.k15);
    }

    public boolean sameKeys(MapShape16 other) {
        if (this == other) return true;
        if (other == null) return false;
        return count == other.count
                && ((k0 == other.k0) & (k1 == other.k1) & (k2 == other.k2) & (k3 == other.k3)
                  & (k4 == other.k4) & (k5 == other.k5) & (k6 == other.k6) & (k7 == other.k7)
                  & (k8 == other.k8) & (k9 == other.k9) & (k10 == other.k10) & (k11 == other.k11)
                  & (k12 == other.k12) & (k13 == other.k13) & (k14 == other.k14) & (k15 == other.k15));
    }

    /** Fieldwise layout match against a live shape-16 map (no allocation). */
    public boolean sameKeys(PersistentShapeMap16 m) {
        if (m == null) return false;
        return m.count == count
                && ((m.k0 == k0) & (m.k1 == k1) & (m.k2 == k2) & (m.k3 == k3)
                  & (m.k4 == k4) & (m.k5 == k5) & (m.k6 == k6) & (m.k7 == k7)
                  & (m.k8 == k8) & (m.k9 == k9) & (m.k10 == k10) & (m.k11 == k11)
                  & (m.k12 == k12) & (m.k13 == k13) & (m.k14 == k14) & (m.k15 == k15));
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof MapShape16 other && sameKeys(other);
    }

    @Override
    public int hashCode() {
        long h = count;
        h = mixKey(h, k0); h = mixKey(h, k1); h = mixKey(h, k2); h = mixKey(h, k3);
        h = mixKey(h, k4); h = mixKey(h, k5); h = mixKey(h, k6); h = mixKey(h, k7);
        h = mixKey(h, k8); h = mixKey(h, k9); h = mixKey(h, k10); h = mixKey(h, k11);
        h = mixKey(h, k12); h = mixKey(h, k13); h = mixKey(h, k14); h = mixKey(h, k15);
        return (int) (h ^ (h >>> 32));
    }

    private static long mixKey(long h, Keyword k) {
        return (h + (k == null ? 0L : k.id)) * 0x9E3779B97F4A7C15L;
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
        if (kw == k8) return 8;
        if (kw == k9) return 9;
        if (kw == k10) return 10;
        if (kw == k11) return 11;
        if (kw == k12) return 12;
        if (kw == k13) return 13;
        if (kw == k14) return 14;
        if (kw == k15) return 15;
        return -1;
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
            default -> {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new IndexOutOfBoundsException("Slot: " + i);
            }
        };
    }

    @TruffleBoundary
    public ShapeMergePlan mergePlan(MapShape right) {
        return ShapeMergePlan.build(
                count, this::getKey, this::indexOf,
                right.count, right::getKey, right::indexOf);
    }

    @TruffleBoundary
    public ShapeMergePlan mergePlan(MapShape16 right) {
        return ShapeMergePlan.build(
                count, this::getKey, this::indexOf,
                right.count, right::getKey, right::indexOf);
    }
}
