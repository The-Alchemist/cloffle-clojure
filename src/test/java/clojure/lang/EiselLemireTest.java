package clojure.lang;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;

/**
 * The fast double path has to agree with {@link Double#parseDouble} bit for bit, since it replaces
 * it for every literal of at most {@link EiselLemire#MAX_SIGNIFICANT_DIGITS} significant digits.
 */
public class EiselLemireTest {

    private static void assertSame(String literal, boolean negative, long digits, long exp10) {
        assertEquals(literal,
                Double.doubleToRawLongBits(Double.parseDouble(literal)),
                Double.doubleToRawLongBits(EiselLemire.toDouble(negative, digits, exp10)));
    }

    @Test
    public void matchesJavaOnCommonLiterals() {
        assertSame("3.5", false, 35, -1);
        assertSame("-3.5", true, 35, -1);
        assertSame("0.1", false, 1, -1);
        assertSame("1.0E30", false, 10, 29);
        assertSame("1.7976931348623157E308", false, 17976931348623157L, 292);
        assertSame("4.9E-324", false, 49, -325);
        assertSame("2.2250738585072013E-308", false, 22250738585072013L, -324);
        assertSame("0.0", false, 0, 0);
        assertSame("-0.0", true, 0, 0);
    }

    @Test
    public void saturatesOutsideBinary64Range() {
        assertEquals(Double.POSITIVE_INFINITY, EiselLemire.toDouble(false, 1, 400), 0.0);
        assertEquals(Double.NEGATIVE_INFINITY, EiselLemire.toDouble(true, 1, 400), 0.0);
        assertEquals(0.0, EiselLemire.toDouble(false, 1, -400), 0.0);
        assertEquals(-0.0, EiselLemire.toDouble(true, 1, -400), 0.0);
    }

    @Test
    public void matchesJavaOnRandomLiterals() {
        Random random = new Random(20260918L);
        for (int i = 0; i < 200_000; i++) {
            // Unsigned, up to the 19-digit ceiling the fast path is exact for.
            long digits = Long.remainderUnsigned(random.nextLong(), -8446744073709551616L);
            int exp10 = random.nextInt(-330, 300);
            boolean negative = random.nextBoolean();
            String literal = (negative ? "-" : "") + Long.toUnsignedString(digits) + "e" + exp10;
            assertSame(literal, negative, digits, exp10);
        }
    }

    @Test
    public void matchesJavaAcrossSignificandWidths() {
        for (int width = 1; width <= EiselLemire.MAX_SIGNIFICANT_DIGITS; width++) {
            long digits = 0;
            for (int i = 0; i < width; i++) {
                digits = digits * 10 + (i % 9) + 1;
            }
            for (int exp10 = -40; exp10 <= 40; exp10++) {
                String literal = Long.toUnsignedString(digits) + "e" + exp10;
                assertSame(literal, false, digits, exp10);
            }
        }
    }
}
