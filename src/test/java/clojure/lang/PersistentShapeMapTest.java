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
        assertEquals(a, fromShape.shape.k0);
        assertEquals(1, fromShape.v0);
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
        assertEquals(a, forward.shape.k0);
        assertEquals(b, forward.shape.k1);
        assertEquals(b, reverse.shape.k0);
        assertEquals(a, reverse.shape.k1);
        assertEquals(1, reverse.v1);
        assertEquals(2, reverse.v0);
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
        PersistentShapeMap forward = PersistentShapeMap.shape3(a, b, c).create(1, 2, 3);
        PersistentShapeMap scrambled = PersistentShapeMap.shape3(c, a, b).create(3, 1, 2);
        PersistentShapeMap reverse = PersistentShapeMap.shape3(c, b, a).create(3, 2, 1);
        assertEquals(PersistentShapeMap.create(a, 1, b, 2, c, 3), forward);
        assertEquals(c, scrambled.shape.k0);
        assertEquals(a, scrambled.shape.k1);
        assertEquals(b, scrambled.shape.k2);
        assertEquals(3, scrambled.v0);
        assertEquals(1, scrambled.v1);
        assertEquals(2, scrambled.v2);
        assertEquals(c, reverse.shape.k0);
        assertEquals(b, reverse.shape.k1);
        assertEquals(a, reverse.shape.k2);
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
        PersistentShapeMap forward = PersistentShapeMap.shape4(a, b, c, d).create(1, 2, 3, 4);
        PersistentShapeMap scrambled = PersistentShapeMap.shape4(d, b, a, c).create(4, 2, 1, 3);
        assertEquals(PersistentShapeMap.create(a, 1, b, 2, c, 3, d, 4), forward);
        assertEquals(d, scrambled.shape.k0);
        assertEquals(b, scrambled.shape.k1);
        assertEquals(a, scrambled.shape.k2);
        assertEquals(c, scrambled.shape.k3);
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
    public void testMapEqualityPreservesAcrossArgumentOrder() {
        Keyword a = Keyword.intern("a");
        Keyword b = Keyword.intern("b");

        PersistentShapeMap m1 = (PersistentShapeMap) RT.map(a, 1, b, 2);
        PersistentShapeMap m2 = (PersistentShapeMap) RT.map(b, 2, a, 1);

        assertEquals(m1, m2);
        assertEquals(a, m1.shape.k0);
        assertEquals(b, m1.shape.k1);
        assertEquals(b, m2.shape.k0);
        assertEquals(a, m2.shape.k1);
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
        assertEquals(8, m.count());
        assertEquals(meta, m.meta());

        // For each slot 0..7, update existing key and verify all invariants
        for (int slot = 0; slot < 8; slot++) {
            Keyword targetKey = m.getKey(slot);
            Object oldVal = m.getVal(slot);
            Object newVal = 9000 + slot;

            PersistentShapeMap updated = (PersistentShapeMap) m.assoc(targetKey, newVal);

            assertEquals(8, updated.count());
            assertEquals(meta, updated.meta());

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

        // Test with high-key (id >= 128)
        Keyword highKey = Keyword.intern("high-slot-kw-" + System.nanoTime());
        while (highKey.id < 128) {
            highKey = Keyword.intern("high-slot-kw-" + System.nanoTime());
        }
        Keyword lowKey = Keyword.intern("a");
        PersistentShapeMap mHigh = (PersistentShapeMap) RT.map(lowKey, 10, highKey, 20);

        PersistentShapeMap updatedLow = (PersistentShapeMap) mHigh.assoc(lowKey, 111);
        assertEquals(111, updatedLow.valAt(lowKey));
        assertEquals(20, updatedLow.valAt(highKey));

        PersistentShapeMap updatedHigh = (PersistentShapeMap) mHigh.assoc(highKey, 222);
        assertEquals(10, updatedHigh.valAt(lowKey));
        assertEquals(222, updatedHigh.valAt(highKey));
    }

    @Test
    public void testAssocInsertPositionsAllSlots() {
        IPersistentMap meta = PersistentArrayMap.EMPTY.assoc(Keyword.intern("insert-meta"), true);
        for (int n = 0; n <= 7; n++) {
            Keyword[] keys = new Keyword[n + 1];
            for (int i = 0; i < n + 1; i++) {
                keys[i] = Keyword.intern("insert-pos-" + n + "-" + i + "-" + System.nanoTime());
            }
            PersistentShapeMap base = (PersistentShapeMap) PersistentShapeMap.EMPTY.withMeta(meta);
            for (int i = 0; i < n + 1; i++) {
                base = (PersistentShapeMap) base.assoc(keys[i], 100 + i);
                assertEquals(i + 1, base.count());
                assertEquals(keys[i], base.getKey(i));
                assertEquals(100 + i, base.valAt(keys[i]));
            }
            assertEquals(meta, base.meta());
        }
    }

    @Test
    public void testAssocPromote16AllInsertPositions() {
        IPersistentMap meta = PersistentArrayMap.EMPTY.assoc(Keyword.intern("promote-meta"), 1);
        Keyword[] keys = new Keyword[9];
        for (int i = 0; i < 9; i++) {
            keys[i] = Keyword.intern("promote16-pos-" + i + "-" + System.nanoTime());
        }
        PersistentShapeMap base = (PersistentShapeMap) PersistentShapeMap.EMPTY.withMeta(meta);
        for (int i = 0; i < 8; i++) {
            base = (PersistentShapeMap) base.assoc(keys[i], 200 + i);
        }
        assertEquals(8, base.count());
        IPersistentMap promoted = base.assoc(keys[8], 200 + 8);
        assertTrue(promoted instanceof PersistentShapeMap16);
        PersistentShapeMap16 sm16 = (PersistentShapeMap16) promoted;
        assertEquals(9, sm16.count());
        assertEquals(meta, sm16.meta());
        for (int i = 0; i < 9; i++) {
            assertEquals(keys[i], sm16.getKey(i));
            assertEquals(200 + i, sm16.valAt(keys[i]));
        }
    }

    @Test
    public void testCachedAssocTransitionsAllRoutesAndGuards() {
        IPersistentMap meta = PersistentArrayMap.EMPTY.assoc(Keyword.intern("transition-meta"), "preserved");
        for (int size = 0; size <= 8; size++) {
            Keyword[] ordered = new Keyword[size + 1];
            for (int i = 0; i <= size; i++) {
                ordered[i] = Keyword.intern("transition-" + size + "-" + i + "-" + System.nanoTime());
            }
            PersistentShapeMap base = (PersistentShapeMap) PersistentShapeMap.EMPTY.withMeta(meta);
            for (int i = 0; i < size; i++) {
                base = (PersistentShapeMap) base.assoc(ordered[i], 100 + i);
            }

            PersistentShapeMap.AssocTransition transition =
                    PersistentShapeMap.assocTransition(base, ordered[size]);
            assertTrue(transition.matches(base, ordered[size]));
            IPersistentMap result = transition.apply(base, 100 + size);

            assertEquals(size + 1, result.count());
            assertEquals(meta, ((IObj) result).meta());
            assertEquals(size == 8, result instanceof PersistentShapeMap16);
            for (int i = 0; i <= size; i++) {
                assertEquals(100 + i, result.valAt(ordered[i]));
            }
            assertEquals(size, base.count());

            if (size > 0) {
                PersistentShapeMap updateBase = (PersistentShapeMap) PersistentShapeMap.EMPTY.withMeta(meta);
                for (int i = 0; i < size; i++) {
                    updateBase = (PersistentShapeMap) updateBase.assoc(ordered[i], i);
                }
                for (int slot = 0; slot < size; slot++) {
                    PersistentShapeMap.AssocTransition updateTransition =
                            PersistentShapeMap.assocTransition(updateBase, updateBase.getKey(slot));
                    PersistentShapeMap updated = (PersistentShapeMap) updateTransition.apply(updateBase, 900 + slot);
                    assertEquals(meta, updated.meta());
                    for (int i = 0; i < size; i++) {
                        assertSame(updateBase.getKey(i), updated.getKey(i));
                        assertEquals(i == slot ? 900 + slot : i, updated.getVal(i));
                    }
                }

                PersistentShapeMap differentShape =
                        (PersistentShapeMap) PersistentShapeMap.EMPTY.assoc(ordered[size], -1);
                PersistentShapeMap.AssocTransition guardTransition =
                        PersistentShapeMap.assocTransition(updateBase, updateBase.getKey(0));
                assertFalse(guardTransition.matches(differentShape, updateBase.getKey(0)));
                assertFalse(guardTransition.matches(updateBase, ordered[size]));
            }
        }
    }

    @Test
    public void testCachedDissocTransitionsAllRoutesAndGuards() {
        IPersistentMap meta = PersistentArrayMap.EMPTY.assoc(Keyword.intern("dissoc-meta"), "saved");

        // Size 0 map: no-op dissoc
        PersistentShapeMap emptyMap = (PersistentShapeMap) PersistentShapeMap.EMPTY.withMeta(meta);
        Keyword absentKw = Keyword.intern("absent-key");
        PersistentShapeMap.DissocTransition emptyTrans = PersistentShapeMap.dissocTransition(emptyMap, absentKw);
        assertTrue(emptyTrans.matches(emptyMap, absentKw));
        assertSame(emptyMap, emptyTrans.apply(emptyMap));

        // Sizes 1..8
        for (int size = 1; size <= 8; size++) {
            Keyword[] ordered = new Keyword[size];
            for (int i = 0; i < size; i++) {
                ordered[i] = Keyword.intern("dissoc-" + size + "-" + i + "-" + System.nanoTime());
            }
            java.util.Arrays.sort(ordered, (a, b) -> Integer.compare(a.id, b.id));

            PersistentShapeMap base = (PersistentShapeMap) PersistentShapeMap.EMPTY.withMeta(meta);
            for (int i = 0; i < size; i++) {
                base = (PersistentShapeMap) base.assoc(ordered[i], 10 + i);
            }

            // 1. Dissoc an absent key -> NoOpDissocTransition returns identical map
            PersistentShapeMap.DissocTransition absentTrans = PersistentShapeMap.dissocTransition(base, absentKw);
            assertTrue(absentTrans.matches(base, absentKw));
            assertSame(base, absentTrans.apply(base));

            // 2. Dissoc each present key
            for (int slot = 0; slot < size; slot++) {
                Keyword targetKey = ordered[slot];
                PersistentShapeMap.DissocTransition trans = PersistentShapeMap.dissocTransition(base, targetKey);
                assertTrue(trans.matches(base, targetKey));

                IPersistentMap result = trans.apply(base);
                assertEquals(size - 1, result.count());
                assertEquals(meta, ((IObj) result).meta());

                if (size == 1) {
                    assertEquals(0, result.count());
                    assertEquals(PersistentShapeMap.EMPTY, result);
                } else {
                    assertTrue(result instanceof PersistentShapeMap);
                    PersistentShapeMap resPSM = (PersistentShapeMap) result;
                    // Check all remaining keys and values
                    for (int i = 0; i < size; i++) {
                        if (i == slot) {
                            assertNull(resPSM.valAt(ordered[i]));
                        } else {
                            assertEquals(10 + i, resPSM.valAt(ordered[i]));
                        }
                    }
                    // Host without(targetKey) should produce the exact same contents
                    IPersistentMap hostWithout = base.without(targetKey);
                    assertEquals(hostWithout, resPSM);
                }

                // Guard negative checks: different keyword or different map shape
                assertFalse(trans.matches(base, absentKw));
                PersistentShapeMap diffShape = (PersistentShapeMap) PersistentShapeMap.EMPTY.assoc(targetKey, 999);
                if (size != 1) {
                    assertFalse(trans.matches(diffShape, targetKey));
                }
            }
        }
    }

    @Test
    public void testCachedShape16DissocDemoteTransition() {
        IPersistentMap meta = PersistentArrayMap.EMPTY.assoc(Keyword.intern("demote16-meta"), "saved");

        // Construct 9-key PersistentShapeMap16
        Keyword[] ordered = new Keyword[9];
        for (int i = 0; i < 9; i++) {
            ordered[i] = Keyword.intern("demote16-" + i + "-" + System.nanoTime());
        }
        java.util.Arrays.sort(ordered, (a, b) -> Integer.compare(a.id, b.id));

        IPersistentMap baseMap = PersistentShapeMap.EMPTY.withMeta(meta);
        for (int i = 0; i < 9; i++) {
            baseMap = baseMap.assoc(ordered[i], 20 + i);
        }
        assertTrue("Expected PersistentShapeMap16 for 9 keys", baseMap instanceof PersistentShapeMap16);
        PersistentShapeMap16 map16 = (PersistentShapeMap16) baseMap;

        // 1. Absent key -> NoOpDissoc16Transition
        Keyword absentKw = Keyword.intern("absent-16");
        PersistentShapeMap16.Dissoc16Transition noopTrans = PersistentShapeMap16.dissocTransition(map16, absentKw);
        assertNotNull(noopTrans);
        assertTrue(noopTrans.matches(map16, absentKw));
        assertSame(map16, noopTrans.apply(map16));

        // 2. Remove each of the 9 slots -> DemoteToShape8Transition producing PersistentShapeMap (count 8)
        for (int slot = 0; slot < 9; slot++) {
            Keyword targetKey = ordered[slot];
            PersistentShapeMap16.Dissoc16Transition trans = PersistentShapeMap16.dissocTransition(map16, targetKey);
            assertNotNull(trans);
            assertTrue(trans.matches(map16, targetKey));

            IPersistentMap result = trans.apply(map16);
            assertTrue("Expected demotion to PersistentShapeMap", result instanceof PersistentShapeMap);
            assertEquals(8, result.count());
            assertEquals(meta, ((IObj) result).meta());

            for (int i = 0; i < 9; i++) {
                if (i == slot) {
                    assertNull(result.valAt(ordered[i]));
                } else {
                    assertEquals(20 + i, result.valAt(ordered[i]));
                }
            }

            // Ensure matches host without(targetKey)
            IPersistentMap hostWithout = map16.without(targetKey);
            assertEquals(hostWithout, result);

            // Guard negative checks
            assertFalse(trans.matches(map16, absentKw));
        }

        // Count != 9 returns null
        IPersistentMap map16_10 = map16.assoc(Keyword.intern("tenth"), 10);
        assertTrue(map16_10 instanceof PersistentShapeMap16);
        assertNull(PersistentShapeMap16.dissocTransition((PersistentShapeMap16) map16_10, ordered[0]));
    }

    @Test
    public void testShapeMap16FactoryPermutationAtMediumCounts() {
        int[] counts = {9, 12, 14, 16};
        for (int n : counts) {
            Keyword[] source = new Keyword[n];
            for (int i = 0; i < n; i++) {
                source[i] = Keyword.intern("factory16-" + n + "-" + i + "-" + System.nanoTime());
            }
            PersistentShapeMap16.Factory factory = new PersistentShapeMap16.Factory(source);
            assertEquals(n, factory.count);
            for (int slot = 0; slot < n; slot++) {
                assertSame(source[factory.sourceIndex(slot)], factory.getKey(slot));
                if (slot > 0) {
                    assertTrue(factory.getKey(slot - 1).id < factory.getKey(slot).id);
                }
            }
            for (int slot = n; slot < 16; slot++) {
                assertNull(factory.getKey(slot));
            }
        }
    }

    @Test
    public void testCachedAssoc16TransitionsRewriteEverySlot() {
        IPersistentMap meta = PersistentArrayMap.EMPTY.assoc(Keyword.intern("assoc16-meta"), "kept");
        for (int size = 9; size <= 16; size++) {
            Keyword[] ordered = new Keyword[size];
            for (int i = 0; i < size; i++) {
                ordered[i] = Keyword.intern("assoc16-" + size + "-" + i + "-" + System.nanoTime());
            }
            java.util.Arrays.sort(ordered, (a, b) -> Integer.compare(a.id, b.id));

            IPersistentMap built = PersistentShapeMap.EMPTY.withMeta(meta);
            for (int i = 0; i < size; i++) {
                built = built.assoc(ordered[i], 100 + i);
            }
            PersistentShapeMap16 map = (PersistentShapeMap16) built;
            assertEquals(size, map.count());

            for (int slot = 0; slot < size; slot++) {
                PersistentShapeMap16.Assoc16Transition trans =
                        PersistentShapeMap16.assocTransition(map, ordered[slot]);
                assertTrue(trans.matches(map, ordered[slot]));
                IPersistentMap updated = trans.apply(map, 900 + slot);
                assertTrue(updated instanceof PersistentShapeMap16);
                assertEquals(size, updated.count());
                assertEquals(meta, ((IObj) updated).meta());
                assertEquals(900 + slot, updated.valAt(ordered[slot]));
                for (int i = 0; i < size; i++) {
                    if (i != slot) {
                        assertEquals(100 + i, updated.valAt(ordered[i]));
                        assertSame(map.getKey(i), ((PersistentShapeMap16) updated).getKey(i));
                    }
                }
                assertEquals(100 + slot, map.valAt(ordered[slot]));
                assertEquals(map.assoc(ordered[slot], 900 + slot), updated);
            }

            Keyword absent = Keyword.intern("assoc16-absent-" + size);
            PersistentShapeMap16.Assoc16Transition miss =
                    PersistentShapeMap16.assocTransition(map, absent);
            assertFalse(miss.matches(map, absent));
            assertFalse(PersistentShapeMap16.assocTransition(map, ordered[0]).matches(map, ordered[1]));
        }
    }

    @Test
    public void testAssoc16TransitionRejectsDifferentLayout() {
        Keyword[] a = new Keyword[9];
        Keyword[] b = new Keyword[9];
        for (int i = 0; i < 9; i++) {
            a[i] = Keyword.intern("assoc16-layout-a-" + i + "-" + System.nanoTime());
            b[i] = Keyword.intern("assoc16-layout-b-" + i + "-" + System.nanoTime());
        }
        IPersistentMap ma = PersistentShapeMap.EMPTY;
        IPersistentMap mb = PersistentShapeMap.EMPTY;
        for (int i = 0; i < 9; i++) {
            ma = ma.assoc(a[i], i);
            mb = mb.assoc(b[i], i);
        }
        PersistentShapeMap16 sa = (PersistentShapeMap16) ma;
        PersistentShapeMap16 sb = (PersistentShapeMap16) mb;
        PersistentShapeMap16.Assoc16Transition trans = PersistentShapeMap16.assocTransition(sa, a[0]);
        assertTrue(trans.matches(sa, a[0]));
        assertFalse(trans.matches(sb, a[0]));
        assertFalse(trans.matches(sa, a[1]));

        IPersistentMap otherValues = PersistentShapeMap.EMPTY;
        for (int i = 0; i < 9; i++) {
            otherValues = otherValues.assoc(a[i], 50 + i);
        }
        PersistentShapeMap16 sameKeys = (PersistentShapeMap16) otherValues;
        assertTrue(trans.matches(sameKeys, a[0]));
        assertEquals(77, trans.apply(sameKeys, 77).valAt(a[0]));
    }

    @Test
    public void testCachedLookup16TransitionsReadEverySlot() {
        for (int size = 9; size <= 16; size++) {
            Keyword[] ordered = new Keyword[size];
            for (int i = 0; i < size; i++) {
                ordered[i] = Keyword.intern("lookup16-" + size + "-" + i + "-" + System.nanoTime());
            }
            java.util.Arrays.sort(ordered, (a, b) -> Integer.compare(a.id, b.id));
            IPersistentMap built = PersistentShapeMap.EMPTY;
            for (int i = 0; i < size; i++) {
                built = built.assoc(ordered[i], 200 + i);
            }
            PersistentShapeMap16 map = (PersistentShapeMap16) built;
            for (int slot = 0; slot < size; slot++) {
                PersistentShapeMap16.Lookup16Transition trans =
                        PersistentShapeMap16.lookupTransition(map, ordered[slot]);
                assertTrue(trans.matches(map, ordered[slot]));
                assertEquals(200 + slot, trans.get(map, "missing"));
            }
            Keyword absent = Keyword.intern("lookup16-absent-" + size);
            PersistentShapeMap16.Lookup16Transition miss =
                    PersistentShapeMap16.lookupTransition(map, absent);
            assertTrue(miss.matches(map, absent));
            assertEquals("missing", miss.get(map, "missing"));
            assertFalse(PersistentShapeMap16.lookupTransition(map, ordered[0]).matches(map, ordered[1]));
        }
    }

    @Test
    public void testDissoc16TransitionIsNullForEveryNonNineCount() {
        Keyword[] keys = new Keyword[16];
        for (int i = 0; i < 16; i++) {
            keys[i] = Keyword.intern("npe16-count-" + i + "-" + System.nanoTime());
        }
        java.util.Arrays.sort(keys, (a, b) -> Long.compare(a.id, b.id));

        IPersistentMap m = PersistentShapeMap.EMPTY;
        for (int i = 0; i < 16; i++) {
            m = m.assoc(keys[i], i);
            if (i + 1 >= 9) {
                assertTrue(m instanceof PersistentShapeMap16);
                PersistentShapeMap16 sm16 = (PersistentShapeMap16) m;
                if (sm16.count == 9) {
                    assertNotNull(PersistentShapeMap16.dissocTransition(sm16, keys[0]));
                    assertNotNull(PersistentShapeMap16.dissocTransition(sm16, Keyword.intern("absent-npe16")));
                } else {
                    assertNull("count=" + sm16.count,
                            PersistentShapeMap16.dissocTransition(sm16, keys[0]));
                    assertNull("absent count=" + sm16.count,
                            PersistentShapeMap16.dissocTransition(sm16, Keyword.intern("absent-npe16")));
                }
            }
        }
    }

    @Test
    public void testDissoc16MatchesRejectsADifferentNineKeyLayout() {
        Keyword[] a = new Keyword[9];
        Keyword[] b = new Keyword[9];
        for (int i = 0; i < 9; i++) {
            a[i] = Keyword.intern("layout-a-" + i + "-" + System.nanoTime());
            b[i] = Keyword.intern("layout-b-" + i + "-" + System.nanoTime());
        }
        IPersistentMap ma = PersistentShapeMap.EMPTY;
        IPersistentMap mb = PersistentShapeMap.EMPTY;
        for (int i = 0; i < 9; i++) {
            ma = ma.assoc(a[i], i);
            mb = mb.assoc(b[i], i);
        }
        PersistentShapeMap16 sa = (PersistentShapeMap16) ma;
        PersistentShapeMap16 sb = (PersistentShapeMap16) mb;
        PersistentShapeMap16.Dissoc16Transition trans = PersistentShapeMap16.dissocTransition(sa, a[0]);
        assertTrue(trans.matches(sa, a[0]));
        assertFalse(trans.matches(sb, a[0]));
        assertFalse(trans.matches(sa, a[1]));
    }

    @Test
    public void testEmptyShapeMapAssocAndDissocTransitions() {
        IPersistentMap meta = PersistentArrayMap.EMPTY.assoc(Keyword.intern("empty-meta"), true);
        PersistentShapeMap empty = (PersistentShapeMap) PersistentShapeMap.EMPTY.withMeta(meta);
        Keyword k = Keyword.intern("empty-insert-" + System.nanoTime());

        PersistentShapeMap.AssocTransition insert = PersistentShapeMap.assocTransition(empty, k);
        assertTrue(insert.matches(empty, k));
        IPersistentMap inserted = insert.apply(empty, 1);
        assertTrue(inserted instanceof PersistentShapeMap);
        assertEquals(1, inserted.count());
        assertEquals(meta, ((IObj) inserted).meta());
        assertEquals(1, inserted.valAt(k));

        PersistentShapeMap.DissocTransition noop = PersistentShapeMap.dissocTransition(empty, k);
        assertSame(empty, noop.apply(empty));

        PersistentShapeMap.DissocTransition last = PersistentShapeMap.dissocTransition(
                (PersistentShapeMap) inserted, k);
        IPersistentMap emptied = last.apply((PersistentShapeMap) inserted);
        assertEquals(0, emptied.count());
        assertEquals(meta, ((IObj) emptied).meta());
    }

    @Test
    public void testPromote16TransitionDoesNotMatchThePromotedMap() {
        Keyword[] ordered = new Keyword[9];
        for (int i = 0; i < 9; i++) {
            ordered[i] = Keyword.intern("promote-match-" + i + "-" + System.nanoTime());
        }
        java.util.Arrays.sort(ordered, (a, b) -> Long.compare(a.id, b.id));
        PersistentShapeMap eight = PersistentShapeMap.EMPTY;
        for (int i = 0; i < 8; i++) {
            eight = (PersistentShapeMap) eight.assoc(ordered[i], i);
        }
        PersistentShapeMap.AssocTransition promote = PersistentShapeMap.assocTransition(eight, ordered[8]);
        IPersistentMap nine = promote.apply(eight, 8);
        assertTrue(nine instanceof PersistentShapeMap16);
        assertTrue(promote.matches(eight, ordered[8]));
        assertFalse("Promoted ShapeMap16 is a different class; the ShapeMap cache must miss",
                nine instanceof PersistentShapeMap && promote.matches((PersistentShapeMap) nine, ordered[8]));
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
            assertEquals(7, sm.count());
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

        // Test map with low and mid keywords
        PersistentShapeMap m = (PersistentShapeMap) RT.map(lowKw, 100, midKw, 200);

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
