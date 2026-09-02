package clojure.lang;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class PersistentShapeMapTest {

    @Test
    public void testEmptyAndCreation() {
        PersistentShapeMap empty = PersistentShapeMap.EMPTY;
        assertEquals(0, empty.count());
        assertNull(empty.seq());

        Keyword a = Keyword.intern("a");
        Keyword b = Keyword.intern("b");
        Keyword c = Keyword.intern("c");

        IPersistentMap m = (IPersistentMap) RT.map(a, 1, b, 2, c, 3);
        assertTrue("Expected PersistentShapeMap for <= 8 keyword map", m instanceof PersistentShapeMap);
        assertEquals(3, m.count());
        assertEquals(1, m.valAt(a));
        assertEquals(2, m.valAt(b));
        assertEquals(3, m.valAt(c));
        assertNull(m.valAt(Keyword.intern("missing")));
        assertEquals("default", m.valAt(Keyword.intern("missing"), "default"));
    }

    @Test
    public void testCanonicalKeywordIdSorting() {
        Keyword a = Keyword.intern("a");
        Keyword b = Keyword.intern("b");

        PersistentShapeMap m1 = (PersistentShapeMap) RT.map(a, 1, b, 2);
        PersistentShapeMap m2 = (PersistentShapeMap) RT.map(b, 2, a, 1);

        assertEquals(m1, m2);
        assertEquals(m1.hashCode(), m2.hashCode());
        assertEquals(m1.k0, m2.k0);
        assertEquals(m1.v0, m2.v0);
        assertEquals(m1.k1, m2.k1);
        assertEquals(m1.v1, m2.v1);
    }

    @Test
    public void testAssocExistingKey() {
        Keyword a = Keyword.intern("a");
        Keyword b = Keyword.intern("b");

        IPersistentMap m = (IPersistentMap) RT.map(a, 1, b, 2);
        IPersistentMap updated = m.assoc(a, 99);

        assertTrue(updated instanceof PersistentShapeMap);
        assertEquals(2, updated.count());
        assertEquals(99, updated.valAt(a));
        assertEquals(2, updated.valAt(b));
        assertEquals(1, m.valAt(a)); // Immutability
    }

    @Test
    public void testDemotionToPersistentArrayMapOnNonKeyword() {
        Keyword a = Keyword.intern("a");
        IPersistentMap m = (IPersistentMap) RT.map(a, 1);
        assertTrue(m instanceof PersistentShapeMap);

        IPersistentMap demoted = m.assoc("str-key", 42);
        assertTrue("Expected demotion to PersistentArrayMap", demoted instanceof PersistentArrayMap);
        assertEquals(2, demoted.count());
        assertEquals(1, demoted.valAt(a));
        assertEquals(42, demoted.valAt("str-key"));
    }

    @Test
    public void testPromotionToPersistentHashMapOver8Keys() {
        IPersistentMap m = PersistentShapeMap.EMPTY;
        for (int i = 0; i < 8; i++) {
            m = m.assoc(Keyword.intern("k" + i), i);
            assertTrue(m instanceof PersistentShapeMap);
        }
        assertEquals(8, m.count());

        IPersistentMap promoted = m.assoc(Keyword.intern("k8"), 8);
        assertTrue("Expected promotion to PersistentHashMap", promoted instanceof PersistentHashMap);
        assertEquals(9, promoted.count());
        for (int i = 0; i <= 8; i++) {
            assertEquals(i, promoted.valAt(Keyword.intern("k" + i)));
        }
    }

    @Test
    public void testWithoutAndDissoc() {
        Keyword a = Keyword.intern("a");
        Keyword b = Keyword.intern("b");
        Keyword c = Keyword.intern("c");

        IPersistentMap m = (IPersistentMap) RT.map(a, 1, b, 2, c, 3);
        IPersistentMap removed = m.without(b);

        assertTrue(removed instanceof PersistentShapeMap);
        assertEquals(2, removed.count());
        assertEquals(1, removed.valAt(a));
        assertNull(removed.valAt(b));
        assertEquals(3, removed.valAt(c));

        IPersistentMap empty = removed.without(a).without(c);
        assertEquals(0, empty.count());
        assertTrue(empty instanceof PersistentShapeMap);
    }

    @Test
    public void testKVReduce() {
        Keyword a = Keyword.intern("a");
        Keyword b = Keyword.intern("b");
        Keyword c = Keyword.intern("c");

        PersistentShapeMap m = (PersistentShapeMap) RT.map(a, 10, b, 20, c, 30);
        Object sum = m.kvreduce(new AFn() {
            @Override
            public Object invoke(Object acc, Object k, Object v) {
                return ((Number) acc).longValue() + ((Number) v).longValue();
            }
        }, 0L);
        assertEquals(60L, sum);
    }

    @Test
    public void test128BitBitmasksAndPOPCNTIndexing() throws Exception {
        Keyword lowKw = null;
        Keyword midKw = null;
        Keyword highKw = null;

        // Inspect existing interned keywords in Keyword table
        java.lang.reflect.Field tableField = Keyword.class.getDeclaredField("table");
        tableField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Symbol, java.lang.ref.Reference<Keyword>> table = (Map<Symbol, java.lang.ref.Reference<Keyword>>) tableField.get(null);
        for (java.lang.ref.Reference<Keyword> ref : table.values()) {
            Keyword k = ref != null ? ref.get() : null;
            if (k != null) {
                if (k.id < 64 && lowKw == null) lowKw = k;
                if (k.id >= 64 && k.id < 128 && midKw == null) midKw = k;
                if (k.id >= 128 && highKw == null) highKw = k;
            }
        }

        int counter = 0;
        while (lowKw == null || midKw == null || highKw == null) {
            Keyword k = Keyword.intern("test-bitmask-gen-" + (counter++));
            if (k.id < 64 && lowKw == null) {
                lowKw = k;
            } else if (k.id >= 64 && k.id < 128 && midKw == null) {
                midKw = k;
            } else if (k.id >= 128 && highKw == null) {
                highKw = k;
            }
        }

        assertNotNull("Should have a low keyword", lowKw);
        assertTrue(lowKw.id < 64);
        assertNotNull("Should have a mid keyword", midKw);
        assertTrue(midKw.id >= 64 && midKw.id < 128);
        assertNotNull("Should have a high keyword", highKw);
        assertTrue(highKw.id >= 128);

        assertTrue(lowKw.mask0 != 0 && lowKw.mask1 == 0);
        assertTrue(midKw.mask0 == 0 && midKw.mask1 != 0);
        assertTrue(highKw.mask0 == 0 && highKw.mask1 == 0);

        // Test map with low and mid keywords
        PersistentShapeMap m = (PersistentShapeMap) RT.map(lowKw, 100, midKw, 200);
        assertFalse(m.hasHighKeys);
        assertEquals(lowKw.mask0, m.mask0);
        assertEquals(midKw.mask1, m.mask1);

        // Test containsKey
        assertTrue(m.containsKey(lowKw));
        assertTrue(m.containsKey(midKw));
        assertFalse(m.containsKey(highKw));
        assertFalse(m.containsKey(Keyword.intern("unrelated-low-missing-key")));

        // Test valAt
        assertEquals(100, m.valAt(lowKw));
        assertEquals(200, m.valAt(midKw));
        assertNull(m.valAt(highKw));
        assertEquals("default", m.valAt(highKw, "default"));
        assertEquals("default", m.valAt(Keyword.intern("nonexistent-key"), "default"));

        // Test entryAt
        assertEquals(100, m.entryAt(lowKw).val());
        assertEquals(200, m.entryAt(midKw).val());
        assertNull(m.entryAt(highKw));

        // Test getLookupThunk
        ILookupThunk thunkLow = m.getLookupThunk(lowKw);
        assertNotNull(thunkLow);
        assertEquals(100, thunkLow.get(m));

        ILookupThunk thunkMid = m.getLookupThunk(midKw);
        assertNotNull(thunkMid);
        assertEquals(200, thunkMid.get(m));

        assertNull(m.getLookupThunk(highKw));

        // Test map with high keyword
        PersistentShapeMap mHigh = (PersistentShapeMap) RT.map(lowKw, 100, midKw, 200, highKw, 300);
        assertTrue(mHigh.hasHighKeys);
        assertTrue(mHigh.containsKey(highKw));
        assertEquals(300, mHigh.valAt(highKw));
        assertEquals(300, mHigh.entryAt(highKw).val());

        ILookupThunk thunkHigh = mHigh.getLookupThunk(highKw);
        assertNotNull(thunkHigh);
        assertEquals(300, thunkHigh.get(mHigh));

        // Test assoc update and immutability
        PersistentShapeMap mUpdated = (PersistentShapeMap) m.assoc(midKw, 999);
        assertEquals(999, mUpdated.valAt(midKw));
        assertEquals(200, m.valAt(midKw));
        assertEquals(m.mask0, mUpdated.mask0);
        assertEquals(m.mask1, mUpdated.mask1);
    }
}
