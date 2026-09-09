package clojure.lang;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Observable Clojure numeric contracts for the Truffle bytecode backend.
 * Internal representation may be primitive, but every Object boundary must preserve
 * stock wrapper classes and overflow / promotion behavior.
 */
public class BytecodePrimitivesSemanticsTest {




    @Test
    public void simpleLongLoopRecurPreservesLongClass() {
        Object v = BytecodeDslTestSupport.evalBytecode(
                "(loop* [i 0] (if (clojure.lang.Numbers/lt i 3) (recur (clojure.lang.Numbers/add i 1)) i))");
        assertSame(Long.class, v.getClass());
        assertEquals(3L, v);
    }

    @Test
    public void integerLiteralsBoxAsLong() {
        Object v = BytecodeDslTestSupport.evalBytecode("42");
        assertSame(Long.class, v.getClass());
        assertEquals(42L, v);
    }

    @Test
    public void floatingLiteralsBoxAsDouble() {
        Object v = BytecodeDslTestSupport.evalBytecode("3.5");
        assertSame(Double.class, v.getClass());
        assertEquals(3.5, (Double) v, 0.0);
    }

    @Test
    public void letBoundIntegerLiteralPreservesLongClass() {
        Object v = BytecodeDslTestSupport.evalBytecode("(let* [x 7] x)");
        assertSame(Long.class, v.getClass());
        assertEquals(7L, v);
    }

    @Test
    public void loopRecurIntegerAccumulatorPreservesLongClass() {
        Object v = BytecodeDslTestSupport.evalBytecode(
                "(loop* [i 0] (if (clojure.lang.Util/equiv i 3) i (recur (clojure.lang.Numbers/add i 1))))");
        assertSame(Long.class, v.getClass());
        assertEquals(3L, v);
    }

    @Test
    public void checkedLongAddOverflowThrowsArithmeticException() {
        try {
            BytecodeDslTestSupport.evalBytecode(
                    "(clojure.lang.Numbers/add 9223372036854775807 1)");
            fail("expected ArithmeticException");
        } catch (RuntimeException e) {
            Throwable t = e;
            while (t.getCause() != null && t != t.getCause()) {
                t = t.getCause();
            }
            assertTrue("expected ArithmeticException, got " + t,
                    t instanceof ArithmeticException);
        }
    }

    @Test
    public void uncheckedLongAddWraps() {
        Object v = BytecodeDslTestSupport.evalBytecode(
                "(clojure.lang.Numbers/unchecked_add 9223372036854775807 1)");
        assertSame(Long.class, v.getClass());
        assertEquals(Long.MIN_VALUE, v);
    }

    @Test
    public void mixedLongDoubleAddReturnsDouble() {
        Object v = BytecodeDslTestSupport.evalBytecode(
                "(clojure.lang.Numbers/add 1 2.5)");
        assertSame(Double.class, v.getClass());
        assertEquals(3.5, (Double) v, 0.0);
    }

    @Test
    public void javaIntReturnPreservesIntegerClass() {
        Object v = BytecodeDslTestSupport.evalBytecode("(Integer/parseInt \"123\")");
        assertSame(Integer.class, v.getClass());
        assertEquals(123, v);
    }

    @Test
    public void rtCountReturnsInteger() {
        Object v = BytecodeDslTestSupport.evalBytecode(
                "(clojure.lang.RT/count (clojure.lang.RT/conj (clojure.lang.RT/conj (clojure.lang.RT/conj clojure.lang.PersistentVector/EMPTY 1) 2) 3))");
        assertSame(Integer.class, v.getClass());
        assertEquals(3, v);
    }

    @Test
    public void collectionInsertionPreservesLongElementClass() {
        Object v = BytecodeDslTestSupport.evalBytecode(
                "(clojure.lang.RT/nth (clojure.lang.RT/conj clojure.lang.PersistentVector/EMPTY 9) 0)");
        assertSame(Long.class, v.getClass());
        assertEquals(9L, v);
    }

    @Test
    public void identityPreservesLongClass() {
        Object v = BytecodeDslTestSupport.evalBytecode(
                "((fn* [x] x) 11)");
        assertSame(Long.class, v.getClass());
        assertEquals(11L, v);
    }

    @Test
    public void hintedLongParamRoundTripPreservesLongClass() {
        Object v = BytecodeDslTestSupport.evalBytecode(
                "((fn* [^long x] x) 13)");
        assertSame(Long.class, v.getClass());
        assertEquals(13L, v);
    }

    @Test
    public void hintedDoubleParamRoundTripPreservesDoubleClass() {
        Object v = BytecodeDslTestSupport.evalBytecode(
                "((fn* [^double x] x) 1.25)");
        assertSame(Double.class, v.getClass());
        assertEquals(1.25, (Double) v, 0.0);
    }

    @Test
    public void closureCaptureOfIntegerLiteralPreservesLongClass() {
        Object v = BytecodeDslTestSupport.evalBytecode(
                "(let* [x 21] ((fn* [] x)))");
        assertSame(Long.class, v.getClass());
        assertEquals(21L, v);
    }

        @Test
    public void instanceChecksSeeBoxedLongNotPrimitiveErasure() {
        // Raw analyzer (no clojure.core): Util/classOf is the stable Object-boundary probe.
        assertEquals(Long.class, BytecodeDslTestSupport.evalBytecode(
                "(clojure.lang.Util/classOf 1)"));
        assertEquals(Long.class, BytecodeDslTestSupport.evalBytecode(
                "(clojure.lang.Util/classOf (clojure.lang.Numbers/add 1 2))"));
    }

    @Test
    public void ratioLiteralRemainsRatio() {
        Object v = BytecodeDslTestSupport.evalBytecode("1/3");
        assertTrue(v instanceof Ratio);
    }

    @Test
    public void bigintLiteralRemainsBigInt() {
        Object v = BytecodeDslTestSupport.evalBytecode("10000000000000000000N");
        assertTrue(v instanceof BigInt);
    }

    @Test
    public void numericEqualityAcrossIntegerAndLongStillHolds() {
        // Host Integer from parseInt equals Clojure Long 123 by value, but is not identical.
        assertEquals(Boolean.TRUE, BytecodeDslTestSupport.evalBytecode(
                "(clojure.lang.Util/equiv (Integer/parseInt \"123\") 123)"));
        assertEquals(Boolean.FALSE, BytecodeDslTestSupport.evalBytecode(
                "(clojure.lang.Util/identical (Integer/parseInt \"123\") 123)"));
    }
}
