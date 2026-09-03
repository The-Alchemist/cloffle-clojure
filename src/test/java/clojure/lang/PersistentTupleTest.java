package clojure.lang;

import org.junit.Test;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.util.Iterator;
import java.util.List;

import static org.junit.Assert.*;

public class PersistentTupleTest {

    @Test
    public void testTupleCreationAndClassTypes() {
        IPersistentVector t0 = Tuple.create();
        assertEquals(PersistentVector.EMPTY, t0);
        assertEquals(0, t0.count());

        IPersistentVector t1 = Tuple.create("a");
        assertTrue(t1 instanceof PersistentTuple.PersistentTuple1);
        assertEquals(1, t1.count());
        assertEquals("a", t1.nth(0));

        IPersistentVector t2 = Tuple.create("a", "b");
        assertTrue(t2 instanceof PersistentTuple.PersistentTuple2);
        assertEquals(2, t2.count());
        assertEquals("a", t2.nth(0));
        assertEquals("b", t2.nth(1));

        IPersistentVector t3 = Tuple.create("a", "b", "c");
        assertTrue(t3 instanceof PersistentTuple.PersistentTuple3);
        assertEquals(3, t3.count());

        IPersistentVector t4 = Tuple.create("a", "b", "c", "d");
        assertTrue(t4 instanceof PersistentTuple.PersistentTuple4);
        assertEquals(4, t4.count());

        IPersistentVector t5 = Tuple.create("a", "b", "c", "d", "e");
        assertTrue(t5 instanceof PersistentTuple.PersistentTuple5);
        assertEquals(5, t5.count());

        IPersistentVector t6 = Tuple.create("a", "b", "c", "d", "e", "f");
        assertTrue(t6 instanceof PersistentTuple.PersistentTuple6);
        assertEquals(6, t6.count());

        IPersistentVector t7 = Tuple.create("a", "b", "c", "d", "e", "f", "g");
        assertTrue(t7 instanceof PersistentTuple.PersistentTuple7);
        assertEquals(7, t7.count());

        IPersistentVector t8 = Tuple.create("a", "b", "c", "d", "e", "f", "g", "h");
        assertTrue(t8 instanceof PersistentTuple.PersistentTuple8);
        assertEquals(8, t8.count());
    }

    @Test
    public void testEqualityAndHashing() {
        for (int n = 1; n <= 8; n++) {
            Object[] arr = new Object[n];
            for (int i = 0; i < n; i++) {
                arr[i] = i * 10;
            }
            IPersistentVector tuple = PersistentTuple.createFromArray(arr);
            PersistentVector pvec = PersistentVector.adopt(arr.clone());

            assertEquals("Tuple" + n + " equals PersistentVector", pvec, tuple);
            assertEquals("PersistentVector equals Tuple" + n, tuple, pvec);
            assertEquals("Tuple" + n + " hashCode", pvec.hashCode(), tuple.hashCode());
            assertEquals("Tuple" + n + " hasheq", pvec.hasheq(), ((IHashEq) tuple).hasheq());
            assertEquals("Tuple" + n + " compareTo", 0, ((Comparable) tuple).compareTo(pvec));
        }
    }

    @Test
    public void testNthAndOutOfBounds() {
        IPersistentVector t4 = Tuple.create(10, 20, 30, 40);
        assertEquals(10, t4.nth(0));
        assertEquals(20, t4.nth(1));
        assertEquals(30, t4.nth(2));
        assertEquals(40, t4.nth(3));

        assertEquals(10, t4.nth(0, "default"));
        assertEquals("default", t4.nth(4, "default"));
        assertEquals("default", t4.nth(-1, "default"));

        try {
            t4.nth(4);
            fail("Expected IndexOutOfBoundsException");
        } catch (IndexOutOfBoundsException ignored) {
        }
        try {
            t4.nth(-1);
            fail("Expected IndexOutOfBoundsException");
        } catch (IndexOutOfBoundsException ignored) {
        }
    }

    @Test
    public void testAssocNAndGrowth() {
        IPersistentVector t = Tuple.create(1, 2);
        IPersistentVector updated = t.assocN(0, 99);
        assertTrue(updated instanceof PersistentTuple.PersistentTuple2);
        assertEquals(99, updated.nth(0));
        assertEquals(2, updated.nth(1));
        assertEquals(1, t.nth(0)); // Immutability

        IPersistentVector grown = t.assocN(2, 3);
        assertTrue(grown instanceof PersistentTuple.PersistentTuple3);
        assertEquals(3, grown.count());
        assertEquals(3, grown.nth(2));

        // Growth through all tuple sizes up to PersistentVector
        IPersistentVector cur = Tuple.create(0);
        for (int i = 1; i <= 7; i++) {
            cur = cur.cons(i);
            assertTrue("Expected PersistentTuple for count=" + (i + 1), cur instanceof PersistentTuple);
            assertEquals(i + 1, cur.count());
            assertEquals(i, cur.nth(i));
        }
        assertEquals(8, cur.count());
        assertTrue(cur instanceof PersistentTuple.PersistentTuple8);

        // 9th element promotes to PersistentVector
        IPersistentVector v9 = cur.cons(8);
        assertTrue(v9 instanceof PersistentVector);
        assertEquals(9, v9.count());
        for (int i = 0; i < 9; i++) {
            assertEquals(i, v9.nth(i));
        }
    }

    @Test
    public void testPopShrinking() {
        IPersistentVector t8 = Tuple.create(0, 1, 2, 3, 4, 5, 6, 7);
        IPersistentStack s = (IPersistentStack) t8;

        s = s.pop();
        assertTrue(s instanceof PersistentTuple.PersistentTuple7);
        assertEquals(7, ((IPersistentVector) s).count());

        s = s.pop();
        assertTrue(s instanceof PersistentTuple.PersistentTuple6);

        s = s.pop();
        assertTrue(s instanceof PersistentTuple.PersistentTuple5);

        s = s.pop();
        assertTrue(s instanceof PersistentTuple.PersistentTuple4);

        s = s.pop();
        assertTrue(s instanceof PersistentTuple.PersistentTuple3);

        s = s.pop();
        assertTrue(s instanceof PersistentTuple.PersistentTuple2);

        s = s.pop();
        assertTrue(s instanceof PersistentTuple.PersistentTuple1);

        s = s.pop();
        assertEquals(PersistentVector.EMPTY, s);
    }

    @Test
    public void testReduceAndKVReduce() {
        IPersistentVector t4 = Tuple.create(1, 2, 3, 4);
        IFn sum = new AFn() {
            @Override
            public Object invoke(Object arg1, Object arg2) {
                return ((Number) arg1).intValue() + ((Number) arg2).intValue();
            }
        };

        assertEquals(10, ((IReduce) t4).reduce(sum));
        assertEquals(20, ((IReduce) t4).reduce(sum, 10));

        // Early termination via Reduced
        IFn sumUntil3 = new AFn() {
            @Override
            public Object invoke(Object acc, Object val) {
                int next = ((Number) acc).intValue() + ((Number) val).intValue();
                if (next >= 3) return new Reduced(next);
                return next;
            }
        };
        assertEquals(3, ((IReduce) t4).reduce(sumUntil3));
        assertEquals(3, ((IReduce) t4).reduce(sumUntil3, 0));

        // KVReduce
        IFn kvSum = new AFn() {
            @Override
            public Object invoke(Object acc, Object k, Object v) {
                return ((Number) acc).intValue() + ((Number) k).intValue() * 10 + ((Number) v).intValue();
            }
        };
        // acc=0: k=0,v=1 -> 1; k=1,v=2 -> 1+12=13; k=2,v=3 -> 13+23=36; k=3,v=4 -> 36+34=70
        assertEquals(70, ((IKVReduce) t4).kvreduce(kvSum, 0));
    }

    @Test
    public void testMetadataAndTransient() {
        IPersistentVector t3 = Tuple.create("a", "b", "c");
        IPersistentMap meta = (IPersistentMap) RT.map(Keyword.intern("tag"), Keyword.intern("test"));
        IPersistentVector withM = (IPersistentVector) ((IObj) t3).withMeta(meta);

        assertNull(((IObj) t3).meta());
        assertEquals(meta, ((IObj) withM).meta());
        assertEquals(t3, withM);

        // Transient support
        ITransientCollection tv = ((IEditableCollection) t3).asTransient();
        tv = tv.conj("d");
        IPersistentVector persistent = (IPersistentVector) tv.persistent();
        assertEquals(4, persistent.count());
        assertEquals("d", persistent.nth(3));
    }

    @Test
    public void testDropAndSequences() {
        IPersistentVector t4 = Tuple.create("a", "b", "c", "d");
        assertEquals(t4, ((IDrop) t4).drop(0));
        assertEquals(PersistentVector.EMPTY, ((IDrop) t4).drop(4));
        assertEquals(PersistentVector.EMPTY, ((IDrop) t4).drop(5));

        Sequential dropped2 = ((IDrop) t4).drop(2);
        assertEquals(2, ((IPersistentVector) dropped2).count());
        assertEquals("c", ((IPersistentVector) dropped2).nth(0));
        assertEquals("d", ((IPersistentVector) dropped2).nth(1));

        // Seq and Iterator
        ISeq s = t4.seq();
        assertNotNull(s);
        assertEquals("a", s.first());
        assertEquals("b", s.next().first());
        assertEquals("c", s.next().next().first());
        assertEquals("d", s.next().next().next().first());
        assertNull(s.next().next().next().next());

        Iterator it = ((Iterable) t4).iterator();
        assertTrue(it.hasNext());
        assertEquals("a", it.next());
        assertEquals("b", it.next());
        assertEquals("c", it.next());
        assertEquals("d", it.next());
        assertFalse(it.hasNext());
    }

    @Test
    public void testCloffleBytecodeIntegration() {
        try (Context context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build()) {

            // Vector literal produces PersistentTuple
            Value res2 = context.eval("cloffle", "[1 2]");
            assertEquals(2, res2.getArraySize());
            assertEquals(1, res2.getArrayElement(0).asInt());
            assertEquals(2, res2.getArrayElement(1).asInt());

            // Vector of size 8
            Value res8 = context.eval("cloffle", "[10 20 30 40 50 60 70 80]");
            assertEquals(8, res8.getArraySize());
            assertEquals(10, res8.getArrayElement(0).asInt());
            assertEquals(80, res8.getArrayElement(7).asInt());

            // Destructuring
            Value sum8 = context.eval("cloffle",
                    "(let [[a b c d e f g h] [1 2 3 4 5 6 7 8]] (+ a b c d e f g h))");
            assertEquals(36, sum8.asInt());

            // Core functions on tuples: first, rest, nth, assoc, conj, pop
            Value firstVal = context.eval("cloffle", "(first [100 200])");
            assertEquals(100, firstVal.asInt());

            Value nthVal = context.eval("cloffle", "(nth [10 20 30] 1)");
            assertEquals(20, nthVal.asInt());

            Value assocVal = context.eval("cloffle", "(assoc [1 2 3] 1 99)");
            assertEquals(99, assocVal.getArrayElement(1).asInt());

            Value conjVal = context.eval("cloffle", "(conj [1 2] 3)");
            assertEquals(3, conjVal.getArraySize());
            assertEquals(3, conjVal.getArrayElement(2).asInt());

            Value popVal = context.eval("cloffle", "(pop [1 2 3])");
            assertEquals(2, popVal.getArraySize());
            assertEquals(2, popVal.getArrayElement(1).asInt());
        }
    }
}
