package clojure.lang;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.CompilerDirectives.ValueType;

/**
 * Precomputed key-layout merge: result keys in clojure.core/merge order plus per-slot
 * value sources as scalar fields ({@code s0}..{@code s15}) so ≤16 apply can PE-fold without
 * indexing arrays.
 *
 * <p>Counts &gt; 16 (hash promotion) keep {@link #hashKeys} / {@link #hashSources} — that path
 * is not PEA-critical.
 *
 * <p>Built by {@link MapShape#mergePlan} / {@link MapShape16#mergePlan}; applied by
 * shape-map transitions via {@link ShapeMapMergeSupport}.
 */
@ValueType
public final class ShapeMergePlan {

    public static final byte FROM_RIGHT = (byte) 0x80;
    public static final byte SLOT_MASK = 0x7F;

    public final int count;
    /** Non-null when {@code count <= 8}. */
    public final MapShape resultShape8;
    /** Non-null when {@code 9 <= count <= 16}. */
    public final MapShape16 resultShape16;
    /** Non-null when {@code count > 16}. */
    public final Keyword[] hashKeys;
    /** Non-null when {@code count > 16}; parallel to {@link #hashKeys}. */
    public final byte[] hashSources;

    /** Per result slot for ≤16 results: left slot, or {@code FROM_RIGHT | rightSlot}. */
    public final byte s0, s1, s2, s3, s4, s5, s6, s7;
    public final byte s8, s9, s10, s11, s12, s13, s14, s15;

    ShapeMergePlan(int count,
                   MapShape resultShape8, MapShape16 resultShape16,
                   Keyword[] hashKeys, byte[] hashSources,
                   byte s0, byte s1, byte s2, byte s3, byte s4, byte s5, byte s6, byte s7,
                   byte s8, byte s9, byte s10, byte s11, byte s12, byte s13, byte s14, byte s15) {
        this.count = count;
        this.resultShape8 = resultShape8;
        this.resultShape16 = resultShape16;
        this.hashKeys = hashKeys;
        this.hashSources = hashSources;
        this.s0 = s0; this.s1 = s1; this.s2 = s2; this.s3 = s3;
        this.s4 = s4; this.s5 = s5; this.s6 = s6; this.s7 = s7;
        this.s8 = s8; this.s9 = s9; this.s10 = s10; this.s11 = s11;
        this.s12 = s12; this.s13 = s13; this.s14 = s14; this.s15 = s15;
    }

    public boolean isUpdateOnly(int leftCount) {
        return count == leftCount;
    }

    @FunctionalInterface
    interface KeyAt {
        Keyword get(int i);
    }

    @FunctionalInterface
    interface IndexOf {
        int indexOf(Keyword k);
    }

    /**
     * Scratch arrays stay inside this boundary; ≤16 plans expose only scalars.
     */
    @TruffleBoundary
    static ShapeMergePlan build(int leftCount, KeyAt leftKey, IndexOf leftIndex,
                                int rightCount, KeyAt rightKey, IndexOf rightIndex) {
        Keyword[] keys = new Keyword[leftCount + rightCount];
        byte[] sources = new byte[leftCount + rightCount];
        int n = 0;
        for (int i = 0; i < leftCount; i++) {
            Keyword k = leftKey.get(i);
            keys[n] = k;
            int rs = rightIndex.indexOf(k);
            sources[n] = rs >= 0 ? (byte) (FROM_RIGHT | rs) : (byte) i;
            n++;
        }
        for (int i = 0; i < rightCount; i++) {
            Keyword k = rightKey.get(i);
            if (leftIndex.indexOf(k) < 0) {
                keys[n] = k;
                sources[n] = (byte) (FROM_RIGHT | i);
                n++;
            }
        }

        MapShape shape8 = null;
        MapShape16 shape16 = null;
        Keyword[] hashKeys = null;
        byte[] hashSources = null;
        if (n <= MapShape.MAX_KEYS) {
            shape8 = MapShape.fromKeys(n, keys);
        } else if (n <= MapShape16.MAX_KEYS) {
            shape16 = MapShape16.fromKeys(n, keys);
        } else {
            hashKeys = new Keyword[n];
            hashSources = new byte[n];
            System.arraycopy(keys, 0, hashKeys, 0, n);
            System.arraycopy(sources, 0, hashSources, 0, n);
        }

        return new ShapeMergePlan(n, shape8, shape16, hashKeys, hashSources,
                at(sources, n, 0), at(sources, n, 1), at(sources, n, 2), at(sources, n, 3),
                at(sources, n, 4), at(sources, n, 5), at(sources, n, 6), at(sources, n, 7),
                at(sources, n, 8), at(sources, n, 9), at(sources, n, 10), at(sources, n, 11),
                at(sources, n, 12), at(sources, n, 13), at(sources, n, 14), at(sources, n, 15));
    }

    private static byte at(byte[] sources, int n, int i) {
        return i < n ? sources[i] : 0;
    }
}
