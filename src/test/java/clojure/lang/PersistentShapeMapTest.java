package clojure.lang;

import org.junit.Test;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

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
    public void testShape1MatchesCreate() {
        Keyword a = Keyword.intern("shape1-a");
        PersistentShapeMap fromShape = PersistentShapeMap.shape1(a).create(1);
        PersistentShapeMap fromCreate = PersistentShapeMap.create(a, 1);
        assertEquals(fromCreate, fromShape);
        assertEquals(a, fromShape.k0);
        assertEquals(1, fromShape.v0);
        assertEquals(a.mask0, fromShape.mask0);
        assertEquals(a.mask1, fromShape.mask1);
        assertEquals(a.id >= 128, fromShape.hasHighKeys);
    }

    @Test
    public void testShape2ForwardAndReverseOrder() {
        Keyword a = Keyword.intern("shape2-a");
        Keyword b = Keyword.intern("shape2-b");
        PersistentShapeMap expected = PersistentShapeMap.create(a, 1, b, 2);
        PersistentShapeMap forward = PersistentShapeMap.shape2(a, b).create(1, 2);
        PersistentShapeMap reverse = PersistentShapeMap.shape2(b, a).create(2, 1);
        assertEquals(expected, forward);
        assertEquals(expected, reverse);
        assertEquals(expected.k0, forward.k0);
        assertEquals(expected.k1, forward.k1);
        assertEquals(expected.v0, reverse.v0);
        assertEquals(expected.v1, reverse.v1);
        assertEquals(expected, RT.map(a, 1, b, 2));
    }

    @Test
    public void testShape2DuplicateThrows() {
        Keyword a = Keyword.intern("shape2-dup");
        try {
            PersistentShapeMap.shape2(a, a);
            fail("expected duplicate key");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Duplicate key"));
        }
    }

    @Test
    public void testShape3PermutationsMatchCreate() {
        Keyword a = Keyword.intern("shape3-a");
        Keyword b = Keyword.intern("shape3-b");
        Keyword c = Keyword.intern("shape3-c");
        PersistentShapeMap expected = PersistentShapeMap.create(a, 1, b, 2, c, 3);
        PersistentShapeMap scrambled = PersistentShapeMap.shape3(c, a, b).create(3, 1, 2);
        PersistentShapeMap reverse = PersistentShapeMap.shape3(c, b, a).create(3, 2, 1);
        assertEquals(expected, scrambled);
        assertEquals(expected, reverse);
        assertEquals(expected.k0, scrambled.k0);
        assertEquals(expected.k1, scrambled.k1);
        assertEquals(expected.k2, scrambled.k2);
        assertEquals(expected, RT.map(a, 1, b, 2, c, 3));
    }

    @Test
    public void testShape3DuplicateThrows() {
        Keyword a = Keyword.intern("shape3-dup-a");
        Keyword b = Keyword.intern("shape3-dup-b");
        try {
            PersistentShapeMap.shape3(a, b, a);
            fail("expected duplicate key");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Duplicate key"));
        }
    }

    @Test
    public void testShape4PermutationsMatchCreate() {
        Keyword a = Keyword.intern("shape4-a");
        Keyword b = Keyword.intern("shape4-b");
        Keyword c = Keyword.intern("shape4-c");
        Keyword d = Keyword.intern("shape4-d");
        PersistentShapeMap expected = PersistentShapeMap.create(a, 1, b, 2, c, 3, d, 4);
        PersistentShapeMap scrambled = PersistentShapeMap.shape4(d, b, a, c).create(4, 2, 1, 3);
        PersistentShapeMap reverse = PersistentShapeMap.shape4(d, c, b, a).create(4, 3, 2, 1);
        assertEquals(expected, scrambled);
        assertEquals(expected, reverse);
        assertEquals(expected.k0, scrambled.k0);
        assertEquals(expected.k1, scrambled.k1);
        assertEquals(expected.k2, scrambled.k2);
        assertEquals(expected.k3, scrambled.k3);
        assertEquals(expected, RT.map(a, 1, b, 2, c, 3, d, 4));
    }

    @Test
    public void testShape4DuplicateThrows() {
        Keyword a = Keyword.intern("shape4-dup-a");
        Keyword b = Keyword.intern("shape4-dup-b");
        Keyword c = Keyword.intern("shape4-dup-c");
        try {
            PersistentShapeMap.shape4(a, b, c, b);
            fail("expected duplicate key");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Duplicate key"));
        }
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
    public void testArrayMapAssocPreservesInsertionOrderWithoutShapePromotion() {
        Keyword a = Keyword.intern("promotion-order-a-" + System.nanoTime());
        Keyword b = Keyword.intern("promotion-order-b-" + System.nanoTime());
        Keyword c = Keyword.intern("promotion-order-c-" + System.nanoTime());
        assertTrue(a.id < b.id && b.id < c.id);

        PersistentArrayMap base = new PersistentArrayMap(new Object[] { b, 2, a, 1 });
        IPersistentMap updated = base.assoc(c, 3);

        assertTrue(updated instanceof PersistentArrayMap);
        ISeq entries = updated.seq();
        assertEquals(b, ((IMapEntry) entries.first()).key());
        entries = entries.next();
        assertEquals(a, ((IMapEntry) entries.first()).key());
        entries = entries.next();
        assertEquals(c, ((IMapEntry) entries.first()).key());
        assertEquals(2, updated.valAt(b));
        assertEquals(1, updated.valAt(a));
        assertEquals(3, updated.valAt(c));
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
    public void testAssocExistingKeyAllSlots() {
        // Create an 8-key map with metadata
        Keyword[] keys = new Keyword[8];
        Object[] kvs = new Object[16];
        for (int i = 0; i < 8; i++) {
            keys[i] = Keyword.intern("test-all-slots-" + i);
            kvs[i * 2] = keys[i];
            kvs[i * 2 + 1] = i;
        }

        IPersistentMap meta = PersistentArrayMap.EMPTY.assoc(Keyword.intern("tag"), "my-meta");
        PersistentShapeMap m = (PersistentShapeMap) ((PersistentShapeMap) PersistentShapeMap.createWithCheck(kvs)).withMeta(meta);
        assertEquals(8, m.count);
        assertEquals(meta, m.meta());

        // For each slot 0..7, update existing key and verify all invariants
        for (int slot = 0; slot < 8; slot++) {
            Keyword targetKey = m.getKey(slot);
            Object oldVal = m.getVal(slot);
            Object newVal = 9000 + slot;

            PersistentShapeMap updated = (PersistentShapeMap) m.assoc(targetKey, newVal);

            assertEquals(8, updated.count);
            assertEquals(meta, updated.meta());
            assertEquals(m.mask0, updated.mask0);
            assertEquals(m.mask1, updated.mask1);
            assertEquals(m.hasHighKeys, updated.hasHighKeys);

            for (int j = 0; j < 8; j++) {
                assertEquals("Key at position " + j + " must match", m.getKey(j), updated.getKey(j));
                if (j == slot) {
                    assertEquals(newVal, updated.getVal(j));
                    assertEquals(newVal, updated.valAt(m.getKey(j)));
                } else {
                    assertEquals(m.getVal(j), updated.getVal(j));
                    assertEquals(m.getVal(j), updated.valAt(m.getKey(j)));
                }
            }

            // Verify immutability of original map
            assertEquals(oldVal, m.getVal(slot));
            assertEquals(oldVal, m.valAt(targetKey));
        }

        // Test with high-key (hasHighKeys = true)
        Keyword highKey = Keyword.intern("high-slot-kw-" + System.nanoTime());
        while (highKey.id < 128) {
            highKey = Keyword.intern("high-slot-kw-" + System.nanoTime());
        }
        Keyword lowKey = Keyword.intern("a");
        PersistentShapeMap mHigh = (PersistentShapeMap) RT.map(lowKey, 10, highKey, 20);
        assertTrue(mHigh.hasHighKeys);

        PersistentShapeMap updatedLow = (PersistentShapeMap) mHigh.assoc(lowKey, 111);
        assertEquals(111, updatedLow.valAt(lowKey));
        assertEquals(20, updatedLow.valAt(highKey));
        assertTrue(updatedLow.hasHighKeys);

        PersistentShapeMap updatedHigh = (PersistentShapeMap) mHigh.assoc(highKey, 222);
        assertEquals(10, updatedHigh.valAt(lowKey));
        assertEquals(222, updatedHigh.valAt(highKey));
        assertTrue(updatedHigh.hasHighKeys);
    }

    @Test
    public void testAssocInsertPositionsAllSlots() {
        IPersistentMap meta = PersistentArrayMap.EMPTY.assoc(Keyword.intern("insert-meta"), true);
        for (int n = 0; n <= 7; n++) {
            Keyword[] ordered = new Keyword[n + 1];
            for (int i = 0; i < n + 1; i++) {
                ordered[i] = Keyword.intern("insert-pos-" + n + "-" + i + "-" + System.nanoTime());
            }
            // Re-sort by Keyword.id in case intern reuse produced non-monotonic ids
            java.util.Arrays.sort(ordered, (a, b) -> Long.compare(a.id, b.id));
            for (int ins = 0; ins <= n; ins++) {
                PersistentShapeMap base = PersistentShapeMap.EMPTY;
                if (meta != null) {
                    base = (PersistentShapeMap) base.withMeta(meta);
                }
                for (int i = 0; i < n + 1; i++) {
                    if (i != ins) {
                        base = (PersistentShapeMap) base.assoc(ordered[i], 100 + i);
                    }
                }
                assertEquals(n, base.count());
                PersistentShapeMap inserted = (PersistentShapeMap) base.assoc(ordered[ins], 100 + ins);
                assertEquals(n + 1, inserted.count());
                assertEquals(meta, inserted.meta());
                long expectedM0 = 0L, expectedM1 = 0L;
                boolean expectedHigh = false;
                for (int i = 0; i < n + 1; i++) {
                    assertEquals("n=" + n + " ins=" + ins + " slot=" + i, ordered[i], inserted.getKey(i));
                    assertEquals(100 + i, inserted.getVal(i));
                    assertEquals(100 + i, inserted.valAt(ordered[i]));
                    expectedM0 |= ordered[i].mask0;
                    expectedM1 |= ordered[i].mask1;
                    if (ordered[i].id >= 128) expectedHigh = true;
                }
                assertEquals(expectedM0, inserted.mask0);
                assertEquals(expectedM1, inserted.mask1);
                assertEquals(expectedHigh, inserted.hasHighKeys);
                assertEquals(n, base.count());
            }
        }
    }

    @Test
    public void testAssocPromote16AllInsertPositions() {
        IPersistentMap meta = PersistentArrayMap.EMPTY.assoc(Keyword.intern("promote-meta"), 1);
        Keyword[] ordered = new Keyword[9];
        for (int i = 0; i < 9; i++) {
            ordered[i] = Keyword.intern("promote16-pos-" + i + "-" + System.nanoTime());
        }
        java.util.Arrays.sort(ordered, (a, b) -> Long.compare(a.id, b.id));
        for (int ins = 0; ins <= 8; ins++) {
            PersistentShapeMap base = (PersistentShapeMap) PersistentShapeMap.EMPTY.withMeta(meta);
            for (int i = 0; i < 9; i++) {
                if (i != ins) {
                    base = (PersistentShapeMap) base.assoc(ordered[i], 200 + i);
                }
            }
            assertEquals(8, base.count());
            IPersistentMap promoted = base.assoc(ordered[ins], 200 + ins);
            assertTrue("ins=" + ins, promoted instanceof PersistentShapeMap16);
            PersistentShapeMap16 sm16 = (PersistentShapeMap16) promoted;
            assertEquals(9, sm16.count());
            assertEquals(meta, sm16.meta());
            for (int i = 0; i < 9; i++) {
                assertEquals("ins=" + ins + " slot=" + i, ordered[i], sm16.getKey(i));
                assertEquals(200 + i, sm16.getVal(i));
                assertEquals(200 + i, sm16.valAt(ordered[i]));
            }
        }
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
    public void testPromotionToPersistentShapeMap16AndHashMap() {
        IPersistentMap m = PersistentShapeMap.EMPTY;
        for (int i = 0; i < 8; i++) {
            m = m.assoc(Keyword.intern("k" + i), i);
            assertTrue("Expected PersistentShapeMap for <= 8 keys", m instanceof PersistentShapeMap);
        }
        assertEquals(8, m.count());

        // 9th key promotes to PersistentShapeMap16
        IPersistentMap promoted16 = m.assoc(Keyword.intern("k8"), 8);
        assertTrue("Expected promotion to PersistentShapeMap16 for 9 keys", promoted16 instanceof PersistentShapeMap16);
        assertEquals(9, promoted16.count());

        for (int i = 9; i < 16; i++) {
            promoted16 = promoted16.assoc(Keyword.intern("k" + i), i);
            assertTrue("Expected PersistentShapeMap16 for 9..16 keys", promoted16 instanceof PersistentShapeMap16);
        }
        assertEquals(16, promoted16.count());

        // 17th key promotes to PersistentHashMap
        IPersistentMap promotedHash = promoted16.assoc(Keyword.intern("k16"), 16);
        assertTrue("Expected promotion to PersistentHashMap for 17 keys", promotedHash instanceof PersistentHashMap);
        assertEquals(17, promotedHash.count());

        for (int i = 0; i <= 16; i++) {
            assertEquals(i, promotedHash.valAt(Keyword.intern("k" + i)));
        }

        // Test demotion on without: 16 -> 8 demotes from Shape16 to ShapeMap
        IPersistentMap shape16 = promoted16;
        for (int i = 15; i >= 8; i--) {
            shape16 = shape16.without(Keyword.intern("k" + i));
        }
        assertEquals(8, shape16.count());
        assertTrue("Expected demotion to PersistentShapeMap when size <= 8", shape16 instanceof PersistentShapeMap);

        // Test demotion on non-keyword assoc (exceeds PersistentArrayMap.HASHTABLE_THRESHOLD, so promotes to PersistentHashMap)
        IPersistentMap demoted = promoted16.assoc("non-kw", 999);
        assertTrue("Expected demotion to PersistentHashMap for > 8 keys", demoted instanceof PersistentHashMap);
        assertEquals(999, demoted.valAt("non-kw"));
        assertEquals(0, demoted.valAt(Keyword.intern("k0")));
    }


    @Test
    public void testWithoutAllSlotsAndMasks() {
        Keyword[] keys = new Keyword[8];
        Object[] kvs = new Object[16];
        for (int i = 0; i < 8; i++) {
            keys[i] = Keyword.intern("without-slot-" + i);
            kvs[i * 2] = keys[i];
            kvs[i * 2 + 1] = i;
        }
        PersistentShapeMap full = PersistentShapeMap.createWithCheck(kvs);
        assertEquals(8, full.count());
        for (int removeIdx = 0; removeIdx < 8; removeIdx++) {
            IPersistentMap removed = full.without(keys[removeIdx]);
            assertTrue(removed instanceof PersistentShapeMap);
            assertEquals(7, removed.count());
            assertNull(removed.valAt(keys[removeIdx]));
            for (int i = 0; i < 8; i++) {
                if (i != removeIdx) {
                    assertEquals(i, removed.valAt(keys[i]));
                }
            }
            PersistentShapeMap sm = (PersistentShapeMap) removed;
            assertEquals(full.mask0 & ~keys[removeIdx].mask0, sm.mask0);
            assertEquals(full.mask1 & ~keys[removeIdx].mask1, sm.mask1);
        }
        IPersistentMap one = PersistentShapeMap.create(keys[0], 0);
        assertEquals(0, one.without(keys[0]).count());
        assertSame(one, one.without(Keyword.intern("absent-without-key")));
    }

    @Test
    public void testShape16WithoutDemoteAndInteriorSlots() {
        Object[] kvs = new Object[18];
        Keyword[] keys = new Keyword[9];
        for (int i = 0; i < 9; i++) {
            keys[i] = Keyword.intern("s16-without-" + i);
            kvs[i * 2] = keys[i];
            kvs[i * 2 + 1] = i;
        }
        PersistentShapeMap16 m9 = PersistentShapeMap16.createWithCheck(kvs);
        assertEquals(9, m9.count());

        // Demote 9 -> 8
        IPersistentMap demoted = m9.without(keys[4]);
        assertTrue(demoted instanceof PersistentShapeMap);
        assertEquals(8, demoted.count());
        assertNull(demoted.valAt(keys[4]));
        assertEquals(0, demoted.valAt(keys[0]));
        assertEquals(8, demoted.valAt(keys[8]));

        // Grow to 12 then remove ends and middle without demoting
        IPersistentMap m = m9;
        for (int i = 9; i < 12; i++) {
            m = m.assoc(Keyword.intern("s16-without-" + i), i);
        }
        assertTrue(m instanceof PersistentShapeMap16);
        assertEquals(12, m.count());
        IPersistentMap removedFirst = m.without(keys[0]);
        assertTrue(removedFirst instanceof PersistentShapeMap16);
        assertEquals(11, removedFirst.count());
        assertNull(removedFirst.valAt(keys[0]));
        Keyword last = Keyword.intern("s16-without-11");
        IPersistentMap removedLast = m.without(last);
        assertEquals(11, removedLast.count());
        assertNull(removedLast.valAt(last));
    }

    @Test
    public void testShape16UnrolledInsertPositions() {
        Object[] kvs = new Object[18];
        Keyword[] keys = new Keyword[9];
        for (int i = 0; i < 9; i++) {
            keys[i] = Keyword.intern("s16-ins-" + i);
            kvs[i * 2] = keys[i];
            kvs[i * 2 + 1] = i;
        }
        PersistentShapeMap16 base = PersistentShapeMap16.createWithCheck(kvs);

        Keyword low = Keyword.intern("s16-ins-low");
        // Ensure low sorts before keys[0] when possible by using a freshly interned name;
        // assoc still returns ShapeMap16 with sorted keys.
        IPersistentMap withLow = base.assoc(low, -1);
        assertTrue(withLow instanceof PersistentShapeMap16);
        assertEquals(10, withLow.count());
        assertEquals(-1, withLow.valAt(low));

        Keyword mid = Keyword.intern("s16-ins-mid");
        IPersistentMap withMid = base.assoc(mid, 99);
        assertTrue(withMid instanceof PersistentShapeMap16);
        assertEquals(10, withMid.count());
        assertEquals(99, withMid.valAt(mid));

        Keyword high = Keyword.intern("s16-ins-zzz-high");
        IPersistentMap withHigh = base.assoc(high, 1000);
        assertTrue(withHigh instanceof PersistentShapeMap16);
        assertEquals(10, withHigh.count());
        assertEquals(1000, withHigh.valAt(high));

        // Fill to 16 then promote on 17th
        IPersistentMap m = base;
        for (int i = 9; i < 16; i++) {
            m = m.assoc(Keyword.intern("s16-ins-fill-" + i), i);
        }
        assertTrue(m instanceof PersistentShapeMap16);
        assertEquals(16, m.count());
        IPersistentMap promoted = m.assoc(Keyword.intern("s16-ins-overflow"), 17);
        assertTrue(promoted instanceof PersistentHashMap);
        assertEquals(17, promoted.count());
    }

    @Test
    public void testShape5ThroughShape8() {
        Keyword a = Keyword.intern("s58-a");
        Keyword b = Keyword.intern("s58-b");
        Keyword c = Keyword.intern("s58-c");
        Keyword d = Keyword.intern("s58-d");
        Keyword e = Keyword.intern("s58-e");
        Keyword f = Keyword.intern("s58-f");
        Keyword g = Keyword.intern("s58-g");
        Keyword h = Keyword.intern("s58-h");

        PersistentShapeMap s5 = PersistentShapeMap.shape5(e, c, a, d, b).create(5, 3, 1, 4, 2);
        assertEquals(PersistentShapeMap.create(a, 1, b, 2, c, 3, d, 4, e, 5), s5);
        assertEquals(5, s5.count());

        PersistentShapeMap s6 = PersistentShapeMap.shape6(f, a, c, e, b, d).create(6, 1, 3, 5, 2, 4);
        assertEquals(PersistentShapeMap.create(a, 1, b, 2, c, 3, d, 4, e, 5, f, 6), s6);

        PersistentShapeMap s7 = PersistentShapeMap.create(a, 1, b, 2, c, 3, d, 4, e, 5, f, 6, g, 7);
        assertEquals(7, s7.count());
        assertEquals(7, s7.valAt(g));

        PersistentShapeMap s8 = PersistentShapeMap.shape8(h, g, f, e, d, c, b, a).create(8, 7, 6, 5, 4, 3, 2, 1);
        assertEquals(PersistentShapeMap.create(a, 1, b, 2, c, 3, d, 4, e, 5, f, 6, g, 7, h, 8), s8);
        assertEquals(8, s8.count());

        try {
            PersistentShapeMap.shape5(a, b, c, d, a);
            fail("expected duplicate");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage().contains("Duplicate"));
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

    @Test
    public void testPersistentShapeMap16Operations() {
        Object[] init = new Object[24]; // 12 key-value pairs
        Keyword[] keys = new Keyword[12];
        for (int i = 0; i < 12; i++) {
            keys[i] = Keyword.intern("shape16-k" + i);
            init[i * 2] = keys[i];
            init[i * 2 + 1] = i * 10;
        }

        IPersistentMap m = (IPersistentMap) RT.map(init);
        assertTrue("Expected PersistentShapeMap16 for 12 keyword pairs", m instanceof PersistentShapeMap16);
        assertEquals(12, m.count());

        for (int i = 0; i < 12; i++) {
            assertTrue(m.containsKey(keys[i]));
            assertEquals(i * 10, m.valAt(keys[i]));
            assertEquals(i * 10, m.entryAt(keys[i]).val());
            ILookupThunk thunk = ((IKeywordLookup) m).getLookupThunk(keys[i]);
            if (thunk != null) {
                assertEquals(i * 10, thunk.get(m));
            }
        }

        assertFalse(m.containsKey(Keyword.intern("missing-shape16-key")));
        assertNull(m.valAt(Keyword.intern("missing-shape16-key")));
        assertEquals("default", m.valAt(Keyword.intern("missing-shape16-key"), "default"));

        // Test kvreduce
        Object sum = ((IKVReduce) m).kvreduce(new AFn() {
            @Override
            public Object invoke(Object acc, Object k, Object v) {
                return ((Number) acc).longValue() + ((Number) v).longValue();
            }
        }, 0L);
        assertEquals(660L, sum); // sum(0..11) * 10 = 66 * 10 = 660

        // Test update existing key in Shape16
        IPersistentMap updated = m.assoc(keys[5], 555);
        assertTrue(updated instanceof PersistentShapeMap16);
        assertEquals(12, updated.count());
        assertEquals(555, updated.valAt(keys[5]));
        assertEquals(50, m.valAt(keys[5])); // Immutability

        // Test withMeta
        IPersistentMap metaMap = (IPersistentMap) RT.map(Keyword.intern("meta-key"), "meta-val");
        PersistentShapeMap16 withMetaMap = ((PersistentShapeMap16) m).withMeta(metaMap);
        assertEquals(metaMap, withMetaMap.meta());
        assertEquals(12, withMetaMap.count());
        assertEquals(0, withMetaMap.valAt(keys[0]));
    }

    @Test
    public void testUnrolledAssocInCloffle() {
        RT.init();
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            // Multi-arg assoc unrolling
            assertEquals(4, context.eval("cloffle", "(count (assoc {:a 1} :b 2 :c 3 :d 4))").asInt());
            assertEquals(1, context.eval("cloffle", "(:a (assoc {:a 1} :b 2 :c 3 :d 4))").asInt());
            assertEquals(4, context.eval("cloffle", "(:d (assoc {:a 1} :b 2 :c 3 :d 4))").asInt());

            // assoc on nil
            assertEquals(2, context.eval("cloffle", "(count (assoc nil :x 10 :y 20))").asInt());
            assertEquals(10, context.eval("cloffle", "(:x (assoc nil :x 10 :y 20))").asInt());
            assertEquals(20, context.eval("cloffle", "(:y (assoc nil :x 10 :y 20))").asInt());

            // non-keyword assoc
            assertEquals(99, context.eval("cloffle", "(get (assoc {:a 1} \"key\" 99) \"key\")").asInt());

            // nested assoc-in with keywords
            assertEquals(31, context.eval("cloffle", "(get-in (assoc-in {:user {:profile {:age 30}}} [:user :profile :age] 31) [:user :profile :age])").asInt());

            // assoc-in new key
            assertEquals("Prague", context.eval("cloffle", "(get-in (assoc-in {:user {:profile {:age 30}}} [:user :profile :city] \"Prague\") [:user :profile :city])").asString());
        }
    }

    @Test
    public void testShapeMapsHonorProtocolsExtendedToStandardMapClasses() {
        RT.init();
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            String result = context.eval("cloffle",
                    "(do " +
                    "  (defprotocol ShapeMapProtocol (shape-map-kind [x])) " +
                    "  (extend-protocol ShapeMapProtocol " +
                    "    clojure.lang.PersistentArrayMap (shape-map-kind [_] \"array\") " +
                    "    clojure.lang.PersistentHashMap (shape-map-kind [_] \"hash\")) " +
                    "  (str (shape-map-kind {:a 1}) \":\" " +
                    "       (shape-map-kind {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8 :i 9})))")
                    .asString();
            assertEquals("array:hash", result);
        }
    }

    @Test
    public void testFixedArityConstructorsInCloffle() {
        RT.init();
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            // Vectors 0..5
            assertEquals(0, context.eval("cloffle", "(count [])").asInt());
            assertEquals(1, context.eval("cloffle", "(count [1])").asInt());
            assertEquals(2, context.eval("cloffle", "(count [1 2])").asInt());
            assertEquals(3, context.eval("cloffle", "(count [1 2 3])").asInt());
            assertEquals(4, context.eval("cloffle", "(count [1 2 3 4])").asInt());
            assertEquals(5, context.eval("cloffle", "(count [1 2 3 4 5])").asInt());

            // Maps 0..5
            assertEquals(0, context.eval("cloffle", "(count {})").asInt());
            assertEquals(1, context.eval("cloffle", "(count {:a 1})").asInt());
            assertEquals(2, context.eval("cloffle", "(count {:a 1 :b 2})").asInt());
            assertEquals(3, context.eval("cloffle", "(count {:a 1 :b 2 :c 3})").asInt());
            assertEquals(4, context.eval("cloffle", "(count {:a 1 :b 2 :c 3 :d 4})").asInt());
            assertEquals(5, context.eval("cloffle", "(count {:a 1 :b 2 :c 3 :d 4 :e 5})").asInt());

            // ShapeMap verification for fixed arities
            Value m1 = context.eval("cloffle", "{:a 1}");
            assertEquals(1, context.eval("cloffle", "(:a {:a 1})").asInt());
            Value m4 = context.eval("cloffle", "{:status 200 :body \"ok\" :headers {} :ok? true}");
            assertEquals(200, context.eval("cloffle", "(:status {:status 200 :body \"ok\" :headers {} :ok? true})").asInt());
            assertEquals("ok", context.eval("cloffle", "(:body {:status 200 :body \"ok\" :headers {} :ok? true})").asString());
        }
    }

    @Test
    public void testUnrolledUpdateAndUpdateInInCloffle() {
        RT.init();
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            // update 1-arg fn
            assertEquals(2, context.eval("cloffle", "(:a (update {:a 1} :a inc))").asInt());
            // update with 1 extra arg
            assertEquals(11, context.eval("cloffle", "(:a (update {:a 1} :a + 10))").asInt());
            // update with 2 extra args
            assertEquals(31, context.eval("cloffle", "(:a (update {:a 1} :a + 10 20))").asInt());
            // update with 3 extra args
            assertEquals(61, context.eval("cloffle", "(:a (update {:a 1} :a + 10 20 30))").asInt());

            // update on string key
            assertEquals(99, context.eval("cloffle", "(get (update {:a 1} \"b\" (fn [_] 99)) \"b\")").asInt());

            // update on nil
            assertEquals(42, context.eval("cloffle", "(:a (update nil :a (fn [x] (if (nil? x) 42 0))))").asInt());

            // update-in single level
            assertEquals(21, context.eval("cloffle", "(:age (:user (update-in {:user {:age 20}} [:user :age] inc)))").asInt());
            // update-in with extra arg
            assertEquals(25, context.eval("cloffle", "(:age (:user (update-in {:user {:age 20}} [:user :age] + 5)))").asInt());
            // update-in multi-level
            assertEquals(30, context.eval("cloffle", "(get-in (update-in {:a {:b {:c 10}}} [:a :b :c] * 3) [:a :b :c])").asInt());
        }
    }

    @Test
    public void testUnrolledMergeInCloffle() {
        RT.init();
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            assertEquals(2, context.eval("cloffle", "(count (merge {:a 1} {:b 2}))").asInt());
            assertEquals(2, context.eval("cloffle", "(:a (merge {:a 1} {:a 2 :b 3}))").asInt());
            assertEquals(3, context.eval("cloffle", "(:b (merge {:a 1} {:a 2 :b 3}))").asInt());
            assertEquals(1, context.eval("cloffle", "(:a (merge nil {:a 1}))").asInt());
        }
    }

    @Test
    public void testVectorAccessAndDestructuringInCloffle() {
        RT.init();
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            // nth
            assertEquals(20, context.eval("cloffle", "(nth [10 20 30] 1)").asInt());
            assertEquals("default", context.eval("cloffle", "(nth [10 20 30] 5 \"default\")").asString());
            assertEquals(3, context.eval("cloffle", "(nth '(1 2 3) 2)").asInt());
            assertEquals("fallback", context.eval("cloffle", "(nth nil 0 \"fallback\")").asString());

            // first
            assertEquals(100, context.eval("cloffle", "(first [100 200])").asInt());
            assertTrue(context.eval("cloffle", "(first [])").isNull());
            assertTrue(context.eval("cloffle", "(first nil)").isNull());
            assertEquals(1, context.eval("cloffle", "(first '(1 2 3))").asInt());

            // rest
            assertEquals(2, context.eval("cloffle", "(count (rest [100 200 300]))").asInt());
            assertEquals(200, context.eval("cloffle", "(first (rest [100 200 300]))").asInt());
            assertEquals(0, context.eval("cloffle", "(count (rest [100]))").asInt());
            assertEquals(0, context.eval("cloffle", "(count (rest []))").asInt());
            assertEquals(0, context.eval("cloffle", "(count (rest nil))").asInt());

            // Destructuring
            assertEquals(10, context.eval("cloffle", "(let [[a b [c d]] [1 2 [3 4]]] (+ a b c d))").asInt());
            assertEquals(2, context.eval("cloffle", "(let [[a b c] [1 2]] (count (filter identity [a b c])))").asInt());
        }
    }

    @Test
    public void testKeywordLookupThunkProtocol() {
        Keyword k = Keyword.intern("target-key");
        Keyword other = Keyword.intern("other-key");
        PersistentShapeMap sm = PersistentShapeMap.create(k, "val");
        ILookupThunk thunk = sm.getLookupThunk(k);
        assertNotNull(thunk);

        // When target matches shape, returns the value
        assertEquals("val", thunk.get(sm));

        // When target does NOT match shape (e.g. non-shape map or different shape),
        // it must return the thunk itself so KeywordLookupSite triggers fault/fallback
        PersistentHashMap phm = PersistentHashMap.create(k, "val-phm");
        assertSame(thunk, thunk.get(phm));
        assertSame(thunk, thunk.get(PersistentShapeMap.create(other, "other")));
        assertSame(thunk, thunk.get(null));
        assertSame(thunk, thunk.get("not a map"));
    }
}
