package org.cloffle.trufflejson;

import com.oracle.truffle.api.CompilerDirectives.ValueType;

/**
 * Compile-time field names for {@link FixedSlots8}. Not a Cloffle {@code MapShape}: names are
 * UTF-8 bytes, not Keywords. Allocate {@link FixedSlots8} in the caller's compilation unit.
 */
@ValueType
public final class Utf8Layout {
    public static final int MAX_KEYS = 8;

    public final int count;
    public final byte[] n0, n1, n2, n3, n4, n5, n6, n7;

    public Utf8Layout(byte[]... names) {
        if (names.length > MAX_KEYS) {
            throw new IllegalArgumentException("Utf8Layout supports at most " + MAX_KEYS + " keys");
        }
        this.count = names.length;
        this.n0 = names.length > 0 ? names[0] : null;
        this.n1 = names.length > 1 ? names[1] : null;
        this.n2 = names.length > 2 ? names[2] : null;
        this.n3 = names.length > 3 ? names[3] : null;
        this.n4 = names.length > 4 ? names[4] : null;
        this.n5 = names.length > 5 ? names[5] : null;
        this.n6 = names.length > 6 ? names[6] : null;
        this.n7 = names.length > 7 ? names[7] : null;
    }
}
