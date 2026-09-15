package clojure.lang;

import org.junit.jupiter.api.Test;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PersistentListUnrolledTest {

    @Test
    public void testCreationAndTypes() {
        assertSame(PersistentList.EMPTY, PersistentList.createList());

        IPersistentList l1 = PersistentList.createList(1);
        assertTrue(l1 instanceof PersistentList.PersistentList1);
        assertEquals(1, l1.count());
        assertEquals(1, ((ISeq) l1).first());
        assertNull(((ISeq) l1).next());
        assertEquals(PersistentList.EMPTY, ((ISeq) l1).more());
        assertEquals(1, l1.peek());
        assertEquals(PersistentList.EMPTY, l1.pop());

        IPersistentList l2 = PersistentList.createList(1, 2);
        assertTrue(l2 instanceof PersistentList.PersistentList2);
        assertEquals(2, l2.count());
        assertEquals(1, ((ISeq) l2).first());
        assertTrue(((ISeq) l2).next() instanceof PersistentList.PersistentList1);
        assertEquals(2, ((ISeq) l2).next().first());
        assertEquals(1, l2.peek());
        assertTrue(l2.pop() instanceof PersistentList.PersistentList1);

        IPersistentList l3 = PersistentList.createList(1, 2, 3);
        assertTrue(l3 instanceof PersistentList.PersistentList3);
        assertEquals(3, l3.count());

        IPersistentList l4 = PersistentList.createList(1, 2, 3, 4);
        assertTrue(l4 instanceof PersistentList.PersistentList4);
        assertEquals(4, l4.count());

        IPersistentList l5 = PersistentList.createList(1, 2, 3, 4, 5);
        assertTrue(l5 instanceof PersistentList.PersistentList5);
        assertEquals(5, l5.count());

        IPersistentList l6 = PersistentList.createList(1, 2, 3, 4, 5, 6);
        assertTrue(l6 instanceof PersistentList.PersistentList6);
        assertEquals(6, l6.count());

        IPersistentList l7 = PersistentList.createList(1, 2, 3, 4, 5, 6, 7);
        assertTrue(l7 instanceof PersistentList.PersistentList7);
        assertEquals(7, l7.count());

        IPersistentList l8 = PersistentList.createList(1, 2, 3, 4, 5, 6, 7, 8);
        assertTrue(l8 instanceof PersistentList.PersistentList8);
        assertEquals(8, l8.count());
    }

    @Test
    public void testConsChainingAndPromotion() {
        IPersistentList cur = PersistentList.EMPTY;
        assertEquals(0, cur.count());

        cur = (IPersistentList) cur.cons(1);
        assertTrue(cur instanceof PersistentList.PersistentList1);
        assertEquals(1, cur.count());
        assertEquals(1, ((ISeq) cur).first());

        cur = (IPersistentList) cur.cons(2);
        assertTrue(cur instanceof PersistentList.PersistentList2);
        assertEquals(2, cur.count());
        assertEquals(2, ((ISeq) cur).first());
        assertEquals(1, ((ISeq) cur).next().first());

        cur = (IPersistentList) cur.cons(3);
        assertTrue(cur instanceof PersistentList.PersistentList3);
        cur = (IPersistentList) cur.cons(4);
        assertTrue(cur instanceof PersistentList.PersistentList4);
        cur = (IPersistentList) cur.cons(5);
        assertTrue(cur instanceof PersistentList.PersistentList5);
        cur = (IPersistentList) cur.cons(6);
        assertTrue(cur instanceof PersistentList.PersistentList6);
        cur = (IPersistentList) cur.cons(7);
        assertTrue(cur instanceof PersistentList.PersistentList7);
        cur = (IPersistentList) cur.cons(8);
        assertTrue(cur instanceof PersistentList.PersistentList8);

        // 9th element promotes to classic linked PersistentList
        cur = (IPersistentList) cur.cons(9);
        assertFalse(cur instanceof PersistentList.PersistentList8);
        assertTrue(cur instanceof PersistentList);
        assertEquals(9, cur.count());
        assertEquals(9, ((ISeq) cur).first());
    }

    @Test
    public void testEqualityAndEquiv() {
        for (int n = 1; n <= 8; n++) {
            Object[] arr = new Object[n];
            for (int i = 0; i < n; i++) arr[i] = i * 10;
            IPersistentList unrolled = PersistentList.createListFromArray(arr);
            IPersistentList fromList = PersistentList.create(List.of(arr));
            IPersistentVector vec = PersistentVector.adopt(arr.clone());

            assertEquals(fromList, unrolled, "List" + n + " equals fromList");
            assertEquals(vec, unrolled, "List" + n + " equals vector");
            assertEquals(unrolled, vec, "Vector equals List" + n);
            assertEquals(fromList.hashCode(), unrolled.hashCode(), "List" + n + " hashCode");
            assertEquals(((IHashEq) fromList).hasheq(), ((IHashEq) unrolled).hasheq(), "List" + n + " hasheq");
        }
    }

    @Test
    public void testReduce() {
        IPersistentList l4 = PersistentList.createList(1, 2, 3, 4);
        IFn sumFn = new AFn() {
            @Override
            public Object invoke(Object a, Object b) {
                return ((Number) a).intValue() + ((Number) b).intValue();
            }
        };
        assertEquals(10, ((IReduce) l4).reduce(sumFn));
        assertEquals(20, ((IReduce) l4).reduce(sumFn, 10));

        IFn earlyStopFn = new AFn() {
            @Override
            public Object invoke(Object a, Object b) {
                int sum = ((Number) a).intValue() + ((Number) b).intValue();
                if (sum >= 3) return new Reduced(sum);
                return sum;
            }
        };
        assertEquals(3, ((IReduce) l4).reduce(earlyStopFn));
    }

    @Test
    public void testGuestClojureListSemantics() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value res = context.eval("cloffle",
                    "(let [xs (list 1 2 3)]\n" +
                    "  [(list? xs)\n" +
                    "   (seq? xs)\n" +
                    "   (vector? xs)\n" +
                    "   (count xs)\n" +
                    "   (first xs)\n" +
                    "   (second xs)\n" +
                    "   (peek xs)\n" +
                    "   (= xs [1 2 3])\n" +
                    "   (= xs '(1 2 3))\n" +
                    "   (pr-str xs)])");

            assertTrue(res.getArrayElement(0).asBoolean()); // list?
            assertTrue(res.getArrayElement(1).asBoolean()); // seq?
            assertFalse(res.getArrayElement(2).asBoolean()); // vector?
            assertEquals(3, res.getArrayElement(3).asInt()); // count
            assertEquals(1, res.getArrayElement(4).asInt()); // first
            assertEquals(2, res.getArrayElement(5).asInt()); // second
            assertEquals(1, res.getArrayElement(6).asInt()); // peek
            assertTrue(res.getArrayElement(7).asBoolean()); // (= xs [1 2 3])
            assertTrue(res.getArrayElement(8).asBoolean()); // (= xs '(1 2 3))
            assertEquals("(1 2 3)", res.getArrayElement(9).asString());
        }
    }

    @Test
    public void testGuestDestructuring() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value res = context.eval("cloffle",
                    "(let [[a b c] (list 10 20 30)]\n" +
                    "  (+ a b c))");
            assertEquals(60, res.asInt());
        }
    }

    /** Compiler fold: {@code (list lit …)} in {@code let*} must match {@code PersistentList/creator} (stack ops). */
    @Test
    public void testGuestFoldedListPop() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value res = context.eval("cloffle",
                    "(let [xs (list 1 2 3)]\n" +
                    "  [(peek xs)\n" +
                    "   (peek (pop xs))\n" +
                    "   (first (pop (pop xs)))\n" +
                    "   (count (pop xs))])");
            assertEquals(1, res.getArrayElement(0).asInt());
            assertEquals(2, res.getArrayElement(1).asInt());
            assertEquals(3, res.getArrayElement(2).asInt());
            assertEquals(2, res.getArrayElement(3).asInt());
        }
    }

    @Test
    public void testGuestFoldedListEqualsFreshList() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value res = context.eval("cloffle",
                    "(let [xs (list 1 2 3)]\n" +
                    "  (= xs (list 1 2 3)))");
            assertTrue(res.asBoolean());
        }
    }

    /** {@code (vec (list …))} and {@code (vec coll)} when {@code coll}'s init is a literal list. */
    @Test
    public void testGuestVecFromFoldedLiteralList() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value direct = context.eval("cloffle", "(= (vec (list 4 5 6)) [4 5 6])");
            assertTrue(direct.asBoolean());

            Value viaLet = context.eval("cloffle",
                    "(let [coll (list 4 5 6)\n" +
                    "      rows (vec coll)]\n" +
                    "  [(vector? rows) (= rows [4 5 6]) (peek coll)])");
            assertTrue(viaLet.getArrayElement(0).asBoolean());
            assertTrue(viaLet.getArrayElement(1).asBoolean());
            assertEquals(4, viaLet.getArrayElement(2).asInt());
        }
    }

    /** Fold applies for ≤8 literal args; 9th forces runtime {@code list} (still must be a proper list). */
    @Test
    public void testGuestLiteralListFoldEightVsNineElements() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value eight = context.eval("cloffle",
                    "(let [xs (list 1 2 3 4 5 6 7 8)]\n" +
                    "  [(list? xs) (peek xs) (count xs) (= xs (list 1 2 3 4 5 6 7 8))])");
            assertTrue(eight.getArrayElement(0).asBoolean());
            assertEquals(1, eight.getArrayElement(1).asInt());
            assertEquals(8, eight.getArrayElement(2).asInt());
            assertTrue(eight.getArrayElement(3).asBoolean());

            Value nine = context.eval("cloffle",
                    "(let [xs (list 1 2 3 4 5 6 7 8 9)]\n" +
                    "  [(list? xs) (peek xs) (count xs) (= xs (list 1 2 3 4 5 6 7 8 9))])");
            assertTrue(nine.getArrayElement(0).asBoolean());
            assertEquals(1, nine.getArrayElement(1).asInt());
            assertEquals(9, nine.getArrayElement(2).asInt());
            assertTrue(nine.getArrayElement(3).asBoolean());
        }
    }
}
