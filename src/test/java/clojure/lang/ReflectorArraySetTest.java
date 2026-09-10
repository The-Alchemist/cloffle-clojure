package clojure.lang;

import org.junit.Test;

import java.lang.reflect.Array;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link RT#aset(Object, Object, Object)} dispatch — same path as lowered {@code clojure.core/aset}.
 */
public class ReflectorArraySetTest {

    private static Object rtAset(Object array, Object idx, Object val) {
        return RT.aset(array, idx, val);
    }

    private static Object rtAget(Object array, Object idx) {
        return RT.aget(array, idx);
    }

    @Test
    public void coercesLongIndexAndValueForIntArray() {
        int[] a = new int[3];
        rtAset(a, 1L, 2L);
        assertEquals(2, a[1]);
    }

    @Test
    public void shuffleSwapPatternLikeCoreAsyncRandomArray() {
        int[] a = {0, 1, 2, 3};
        for (long i = 1; i < a.length; i++) {
            long j = 0;
            rtAset(a, i, Array.get(a, (int) j));
            rtAset(a, j, i);
        }
        assertArrayEquals(new int[]{3, 0, 1, 2}, a);
    }

    @Test
    public void coercesCharacterIndex() {
        int[] a = new int[80];
        rtAset(a, Character.valueOf((char) 65), 7L);
        assertEquals(7, a[65]);
    }

    @Test
    public void coercesNumberSubtypesIntoIntArray() {
        int[] a = new int[4];
        rtAset(a, 0, Integer.valueOf(1));
        rtAset(a, 1L, Short.valueOf((short) 2));
        rtAset(a, Byte.valueOf((byte) 2), 3L);
        rtAset(a, 3.9d, 4.1d);
        assertArrayEquals(new int[]{1, 2, 3, 4}, a);
    }

    @Test
    public void coercesForOtherPrimitiveComponentTypes() {
        long[] longs = new long[1];
        rtAset(longs, 0L, 9L);
        assertEquals(9L, longs[0]);

        byte[] bytes = new byte[1];
        rtAset(bytes, 0L, 127L);
        assertEquals((byte) 127, bytes[0]);

        short[] shorts = new short[1];
        rtAset(shorts, 0L, 300L);
        assertEquals((short) 300, shorts[0]);

        float[] floats = new float[1];
        rtAset(floats, 0L, 1.5d);
        assertEquals(1.5f, floats[0], 0.0f);

        double[] doubles = new double[1];
        rtAset(doubles, 0L, 1.25d);
        assertEquals(1.25d, doubles[0], 0.0);

        boolean[] bools = new boolean[1];
        rtAset(bools, 0L, true);
        assertEquals(true, bools[0]);

        char[] chars = new char[1];
        rtAset(chars, 0L, Character.valueOf('z'));
        assertEquals('z', chars[0]);
    }

    @Test
    public void agetRoundTripAfterAset() {
        int[] a = new int[1];
        rtAset(a, 0L, 42L);
        assertEquals(42, rtAget(a, 0L));
    }

    @Test
    public void rawArraySetRejectsLongValueOnIntArray() {
        int[] a = new int[1];
        try {
            Reflector.invokeStaticMethod(Array.class, "set", new Object[]{a, 0, 1L});
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().length() > 0);
        }
    }

    @Test
    public void rejectsBooleanIntoIntArray() {
        try {
            rtAset(new int[1], 0L, true);
            fail("expected exception for boolean into int array");
        } catch (RuntimeException e) {
            assertTrue(e.getClass().getName(), e instanceof ClassCastException || e instanceof IllegalArgumentException);
        }
    }

    /** Property-style: Fisher–Yates with Long indices/values must leave a permutation. */
    @Test
    public void generativeRandomArrayPermutationInvariant() {
        Random rnd = new Random(0xC0FFEE);
        for (int trial = 0; trial < 200; trial++) {
            int n = 1 + rnd.nextInt(32);
            int[] a = new int[n];
            for (int k = 0; k < n; k++) {
                a[k] = k;
            }
            for (long i = 1; i < n; i++) {
                long j = rnd.nextInt((int) i + 1);
                Object atJ = Array.get(a, (int) j);
                rtAset(a, i, atJ);
                rtAset(a, j, i);
            }
            int[] sorted = a.clone();
            java.util.Arrays.sort(sorted);
            for (int k = 0; k < n; k++) {
                assertEquals("trial " + trial + " idx " + k, k, sorted[k]);
            }
        }
    }

    @Test
    public void generativeCrossTypeNumericStores() {
        Random rnd = new Random(42);
        Number[] nums = {
                Integer.valueOf(0), Long.valueOf(1), Short.valueOf((short) 2),
                Byte.valueOf((byte) 3), Double.valueOf(4.7), Float.valueOf(5.2f)
        };
        for (int trial = 0; trial < 100; trial++) {
            int[] a = new int[8];
            Number idx = nums[rnd.nextInt(nums.length)];
            Number val = nums[rnd.nextInt(nums.length)];
            int i = Math.floorMod(idx.intValue(), a.length);
            rtAset(a, idx, val);
            assertEquals(val.intValue(), a[i]);
        }
    }
}
