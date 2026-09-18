package org.cloffle.trufflejson;

import com.oracle.truffle.api.CompilerDirectives.ValueType;

/**
 * Optional 8-slot result for non-Cloffle Truffle languages. Cloffle must not copy instances of
 * this type into {@code PersistentShapeMap}; it should {@code new PersistentShapeMap} from
 * decoded {@link JsonScan.TypedScanResult} locals in its own bytecode op.
 */
@ValueType
public final class FixedSlots8 {
    public final Utf8Layout layout;
    public final Object v0, v1, v2, v3, v4, v5, v6, v7;

    public FixedSlots8(Utf8Layout layout,
                       Object v0, Object v1, Object v2, Object v3,
                       Object v4, Object v5, Object v6, Object v7) {
        this.layout = layout;
        this.v0 = v0;
        this.v1 = v1;
        this.v2 = v2;
        this.v3 = v3;
        this.v4 = v4;
        this.v5 = v5;
        this.v6 = v6;
        this.v7 = v7;
    }

    public static FixedSlots8 fromSlots(Utf8Layout layout, Object[] slots) {
        int n = layout.count;
        return new FixedSlots8(
                layout,
                n > 0 ? slots[0] : null,
                n > 1 ? slots[1] : null,
                n > 2 ? slots[2] : null,
                n > 3 ? slots[3] : null,
                n > 4 ? slots[4] : null,
                n > 5 ? slots[5] : null,
                n > 6 ? slots[6] : null,
                n > 7 ? slots[7] : null);
    }

    public Object slot(int i) {
        return switch (i) {
            case 0 -> v0;
            case 1 -> v1;
            case 2 -> v2;
            case 3 -> v3;
            case 4 -> v4;
            case 5 -> v5;
            case 6 -> v6;
            case 7 -> v7;
            default -> throw new IndexOutOfBoundsException(i);
        };
    }
}
