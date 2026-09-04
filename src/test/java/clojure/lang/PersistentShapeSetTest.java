package clojure.lang;

import org.junit.Test;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.util.Iterator;
import java.util.Set;

import static org.junit.Assert.*;

public class PersistentShapeSetTest {

    @Test
    public void testEmptyAndCreation() {
        PersistentShapeSet empty = PersistentShapeSet.EMPTY;
        assertEquals(0, empty.count());
        assertTrue(empty.isEmpty());
        assertNull(empty.seq());

        Keyword a = Keyword.intern("test-a");
        Keyword b = Keyword.intern("test-b");
        Keyword c = Keyword.intern("test-c");

        PersistentShapeSet s1 = PersistentShapeSet.create(a);
        assertEquals(1, s1.count());
        assertTrue(s1.contains(a));
        assertFalse(s1.contains(b));
        assertEquals(a, s1.get(a));
        assertNull(s1.get(b));

        PersistentShapeSet s3 = PersistentShapeSet.create(a, b, c);
        assertEquals(3, s3.count());
        assertTrue(s3.contains(a));
        assertTrue(s3.contains(b));
        assertTrue(s3.contains(c));
        assertFalse(s3.contains(Keyword.intern("missing")));
    }

    @Test
    public void testShapeDescriptors() {
        Keyword k0 = Keyword.intern("sk0");
        Keyword k1 = Keyword.intern("sk1");
        Keyword k2 = Keyword.intern("sk2");
        Keyword k3 = Keyword.intern("sk3");
        Keyword k4 = Keyword.intern("sk4");
        Keyword k5 = Keyword.intern("sk5");
        Keyword k6 = Keyword.intern("sk6");
        Keyword k7 = Keyword.intern("sk7");

        PersistentShapeSet s1 = PersistentShapeSet.shape1(k0).set;
        assertEquals(1, s1.count());
        assertTrue(s1.contains(k0));

        PersistentShapeSet s2 = PersistentShapeSet.shape2(k0, k1).set;
        assertEquals(2, s2.count());
        assertTrue(s2.contains(k0));
        assertTrue(s2.contains(k1));

        PersistentShapeSet s3 = PersistentShapeSet.shape3(k0, k1, k2).set;
        assertEquals(3, s3.count());

        PersistentShapeSet s4 = PersistentShapeSet.shape4(k0, k1, k2, k3).set;
        assertEquals(4, s4.count());

        PersistentShapeSet s5 = PersistentShapeSet.shape5(k0, k1, k2, k3, k4).set;
        assertEquals(5, s5.count());

        PersistentShapeSet s6 = PersistentShapeSet.shape6(k0, k1, k2, k3, k4, k5).set;
        assertEquals(6, s6.count());

        PersistentShapeSet s7 = PersistentShapeSet.shape7(k0, k1, k2, k3, k4, k5, k6).set;
        assertEquals(7, s7.count());

        PersistentShapeSet s8 = PersistentShapeSet.shape8(k0, k1, k2, k3, k4, k5, k6, k7).set;
        assertEquals(8, s8.count());
        for (Keyword k : new Keyword[]{k0, k1, k2, k3, k4, k5, k6, k7}) {
            assertTrue(s8.contains(k));
        }
    }

    @Test
    public void testConsAndDisjoin() {
        Keyword a = Keyword.intern("cons-a");
        Keyword b = Keyword.intern("cons-b");
        Keyword c = Keyword.intern("cons-c");

        PersistentShapeSet s = PersistentShapeSet.EMPTY;
        IPersistentSet s1 = (IPersistentSet) s.cons(a);
        assertTrue(s1 instanceof PersistentShapeSet);
        assertEquals(1, s1.count());
        assertTrue(s1.contains(a));

        IPersistentSet s2 = (IPersistentSet) s1.cons(b);
        assertTrue(s2 instanceof PersistentShapeSet);
        assertEquals(2, s2.count());
        assertTrue(s2.contains(a));
        assertTrue(s2.contains(b));

        // Cons existing is no-op
        assertSame(s2, s2.cons(a));

        IPersistentSet s3 = (IPersistentSet) s2.cons(c);
        assertEquals(3, s3.count());

        // Disjoin
        IPersistentSet d1 = s3.disjoin(b);
        assertTrue(d1 instanceof PersistentShapeSet);
        assertEquals(2, d1.count());
        assertTrue(d1.contains(a));
        assertFalse(d1.contains(b));
        assertTrue(d1.contains(c));

        // Disjoin non-existing is no-op
        assertSame(d1, d1.disjoin(b));

        IPersistentSet d2 = d1.disjoin(a).disjoin(c);
        assertEquals(0, d2.count());
        assertSame(PersistentShapeSet.EMPTY, d2);
    }

    @Test
    public void testPromotionToPersistentHashSet() {
        IPersistentSet s = PersistentShapeSet.EMPTY;
        for (int i = 0; i < 8; i++) {
            s = (IPersistentSet) s.cons(Keyword.intern("promo-" + i));
            assertTrue(s instanceof PersistentShapeSet);
        }
        assertEquals(8, s.count());

        // 9th key promotes to PersistentHashSet
        IPersistentSet s9 = (IPersistentSet) s.cons(Keyword.intern("promo-8"));
        assertTrue("Expected PersistentHashSet on count > 8", s9 instanceof PersistentHashSet);
        assertEquals(9, s9.count());

        // Adding non-keyword also promotes
        IPersistentSet sNonKw = (IPersistentSet) ((IPersistentSet) PersistentShapeSet.EMPTY.cons(Keyword.intern("k"))).cons("non-keyword");
        assertTrue("Expected PersistentHashSet on non-keyword", sNonKw instanceof PersistentHashSet);
        assertEquals(2, sNonKw.count());
    }

    @Test
    public void testEqualityWithPersistentHashSet() {
        Keyword a = Keyword.intern("eq-a");
        Keyword b = Keyword.intern("eq-b");
        Keyword c = Keyword.intern("eq-c");

        PersistentShapeSet shapeSet = PersistentShapeSet.create(a, b, c);
        PersistentHashSet hashSet = PersistentHashSet.create(a, b, c);

        assertEquals(hashSet, shapeSet);
        assertEquals(shapeSet, hashSet);
        assertEquals(hashSet.hashCode(), shapeSet.hashCode());
        assertEquals(hashSet.hasheq(), shapeSet.hasheq());
    }

    @Test
    public void testIterationAndSeq() {
        Keyword a = Keyword.intern("iter-a");
        Keyword b = Keyword.intern("iter-b");
        PersistentShapeSet s = PersistentShapeSet.create(a, b);

        int count = 0;
        for (Object o : s) {
            assertTrue(o instanceof Keyword);
            count++;
        }
        assertEquals(2, count);

        ISeq seq = s.seq();
        assertNotNull(seq);
        assertEquals(2, seq.count());

        Object[] arr = s.toArray();
        assertEquals(2, arr.length);
    }

    @Test
    public void testGuestSetLiteralInCloffle() {
        try (Context ctx = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value isShapeSet = ctx.eval("cloffle", "(instance? clojure.lang.PersistentShapeSet #{:alpha :beta :gamma})");
            assertTrue("Expected PersistentShapeSet from set literal", isShapeSet.asBoolean());

            Value countRes = ctx.eval("cloffle", "(count #{:alpha :beta :gamma})");
            assertEquals(3, countRes.asInt());

            Value containsRes = ctx.eval("cloffle", "(contains? #{:alpha :beta} :alpha)");
            assertTrue(containsRes.asBoolean());

            Value invokeRes = ctx.eval("cloffle", "(identical? (#{:alpha :beta} :beta) :beta)");
            assertTrue(invokeRes.asBoolean());

            Value emptySetRes = ctx.eval("cloffle", "(identical? #{} clojure.lang.PersistentShapeSet/EMPTY)");
            assertTrue(emptySetRes.asBoolean());
        }
    }
}
