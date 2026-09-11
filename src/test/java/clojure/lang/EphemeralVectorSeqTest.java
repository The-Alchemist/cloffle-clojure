package clojure.lang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class EphemeralVectorSeqTest {

    @Test
    public void testSequentialInterfaceContract() {
        IPersistentVector vec = (IPersistentVector) RT.vector("a", "b", "c");
        IFn toUpper = new AFn() {
            @Override
            public Object invoke(Object arg) {
                return ((String) arg).toUpperCase();
            }
        };

        ISeq s = EphemeralVectorSeq.create(toUpper, vec, 0);
        assertNotNull(s);
        assertTrue(s instanceof EphemeralVectorSeq);
        EphemeralVectorSeq evs = (EphemeralVectorSeq) s;

        // count & index
        assertEquals(3, evs.count());
        assertEquals(0, evs.index());

        // first
        assertEquals("A", evs.first());

        // next & more
        ISeq s1 = evs.next();
        assertNotNull(s1);
        assertTrue(s1 instanceof EphemeralVectorSeq);
        assertEquals(2, ((Counted) s1).count());
        assertEquals(1, ((IndexedSeq) s1).index());
        assertEquals("B", s1.first());

        ISeq s2 = s1.next();
        assertNotNull(s2);
        assertEquals("C", s2.first());

        // bounds on next()
        assertNull(s2.next());
        assertEquals(PersistentList.EMPTY, s2.more());

        // nth (O(1) direct indexing)
        assertEquals("A", evs.nth(0));
        assertEquals("B", evs.nth(1));
        assertEquals("C", evs.nth(2));
        assertEquals("not-found", evs.nth(3, "not-found"));
        assertEquals("not-found", evs.nth(-1, "not-found"));

        try {
            evs.nth(3);
            fail("Expected IndexOutOfBoundsException");
        } catch (IndexOutOfBoundsException ignored) {
        }

        // equiv & equals
        ISeq expected = (ISeq) RT.vector("A", "B", "C").seq();
        assertTrue(evs.equiv(expected));
        assertTrue(evs.equals(expected));
        assertEquals(expected.hashCode(), evs.hashCode());
        assertEquals(((IHashEq) expected).hasheq(), evs.hasheq());

        // realized? is always true for ephemeral vector views
        assertTrue(evs.isRealized());
    }

    @Test
    public void testReEvaluationOnDemand() {
        AtomicInteger count = new AtomicInteger(0);
        IFn countingFn = new AFn() {
            @Override
            public Object invoke(Object x) {
                count.incrementAndGet();
                return Numbers.add(x, 10);
            }
        };

        IPersistentVector vec = (IPersistentVector) RT.vector(1, 2, 3);
        ISeq s = EphemeralVectorSeq.create(countingFn, vec, 0);
        assertNotNull(s);
        EphemeralVectorSeq evs = (EphemeralVectorSeq) s;

        assertEquals(0, count.get());

        // First call evaluates fn
        assertEquals(11L, ((Number) evs.first()).longValue());
        assertEquals(1, count.get());

        // EphemeralVectorSeq does NOT memoize: repeated calls evaluate on demand without caching locks
        assertEquals(11L, ((Number) evs.first()).longValue());
        assertEquals(2, count.get());
        assertEquals(11L, ((Number) evs.first()).longValue());
        assertEquals(3, count.get());
    }

    @Test
    public void testPurityDetection() {
        // Keywords are pure
        assertTrue(EphemeralVectorSeq.isPure(Keyword.intern("id")));
        assertTrue(EphemeralVectorSeq.isPure(Keyword.intern("user", "name")));

        // Persistent Sets and Maps are pure
        assertTrue(EphemeralVectorSeq.isPure(PersistentHashSet.EMPTY));
        assertTrue(EphemeralVectorSeq.isPure(PersistentArrayMap.EMPTY));

        // Composed pure functions are pure
        IFn pure1 = Keyword.intern("a");
        IFn pure2 = Keyword.intern("b");
        IFn composedPure = new EphemeralVectorSeq.ComposedFn(pure1, pure2);
        assertTrue(EphemeralVectorSeq.isPure(composedPure));

        // Arbitrary closures or AFn without purity guarantee are not pure
        IFn arbitrary = new AFn() {
            @Override
            public Object invoke(Object arg) {
                return arg;
            }
        };
        assertFalse(EphemeralVectorSeq.isPure(arbitrary));
    }

    @Test
    public void testAlgebraicComposition() {
        IPersistentVector vec = (IPersistentVector) RT.vector(1, 2, 3);
        IFn f = new AFn() {
            @Override
            public Object invoke(Object x) {
                return Numbers.add(x, 1);
            }
        };
        IFn g = new AFn() {
            @Override
            public Object invoke(Object x) {
                return Numbers.multiply(x, 10);
            }
        };

        ISeq s1 = EphemeralVectorSeq.create(f, vec, 0);
        assertTrue(s1 instanceof EphemeralVectorSeq);

        ISeq s2 = EphemeralVectorSeq.create(g, s1, 0);
        assertTrue(s2 instanceof EphemeralVectorSeq);
        EphemeralVectorSeq evs2 = (EphemeralVectorSeq) s2;

        // Verify single-level indirection: underlying collection is still the original vector!
        assertSame("Underlying vector should be preserved across composition", vec, evs2.v);
        assertEquals(0, evs2.i);

        // Verify elements yield g(f(x)) = (x + 1) * 10
        assertEquals(20L, ((Number) evs2.first()).longValue());
        assertEquals(30L, ((Number) evs2.next().first()).longValue());
        assertEquals(40L, ((Number) evs2.next().next().first()).longValue());
        assertNull(evs2.next().next().next());
    }

    @Test
    public void testCrossCompositionWithMappedVectorSeq() {
        IPersistentVector vec = (IPersistentVector) RT.vector(1, 2, 3);
        IFn pure = Keyword.intern("a");
        IFn sideEffect = new AFn() {
            @Override
            public Object invoke(Object x) {
                return x;
            }
        };

        ISeq ephemeral = EphemeralVectorSeq.create(pure, vec, 0);
        assertTrue(ephemeral instanceof EphemeralVectorSeq);

        // Composing sideEffect over ephemeral should promote to MappedVectorSeq to preserve memoization
        ISeq mapped = MappedVectorSeq.create(sideEffect, ephemeral, 0);
        assertTrue(mapped instanceof MappedVectorSeq);

        // Composing pure over mapped should stay MappedVectorSeq
        ISeq mapped2 = EphemeralVectorSeq.create(pure, mapped, 0);
        assertTrue(mapped2 instanceof MappedVectorSeq);
    }

    @Test
    public void testAcceleratedReduceAndReduced() {
        IPersistentVector vec = (IPersistentVector) RT.vector(1, 2, 3, 4, 5);
        IFn dbl = new AFn() {
            @Override
            public Object invoke(Object x) {
                return Numbers.multiply(x, 2);
            }
        };

        EphemeralVectorSeq evs = (EphemeralVectorSeq) EphemeralVectorSeq.create(dbl, vec, 0);

        IFn sumRf = new AFn() {
            @Override
            public Object invoke(Object acc, Object x) {
                return Numbers.add(acc, x);
            }
        };

        // 2-arg reduce with start
        Object result = evs.reduce(sumRf, 0L);
        assertEquals(30L, ((Number) result).longValue());

        // 1-arg reduce
        Object result1 = evs.reduce(sumRf);
        assertEquals(30L, ((Number) result1).longValue());

        // Reduced short-circuiting
        IFn earlyStopRf = new AFn() {
            @Override
            public Object invoke(Object acc, Object x) {
                long current = Numbers.add(acc, x).longValue();
                if (current >= 10) {
                    return new Reduced(current);
                }
                return current;
            }
        };

        Object earlyResult = evs.reduce(earlyStopRf, 0L);
        assertEquals(12L, ((Number) earlyResult).longValue());
    }

    @Test
    public void testCoreMapAutomaticSelection() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            // Literal vector-of-maps constant-folds; use runtime coll for EVS selection.
            Value isEphemeral = context.eval("cloffle",
                    "(instance? clojure.lang.EphemeralVectorSeq (map :a (vector {:a 1} (hash-map))))");
            assertTrue("Pure keyword map on vector should return EphemeralVectorSeq", isEphemeral.asBoolean());

            // (map identity [1 2 3]) constant-folds to the vector literal; use a non-literal coll for EVS.
            Value isIdentityEphemeral = context.eval("cloffle",
                    "(instance? clojure.lang.EphemeralVectorSeq (map identity (vector 1 2 (hash-map))))");
            assertTrue("Pure identity map on vector should return EphemeralVectorSeq", isIdentityEphemeral.asBoolean());

            // Arbitrary lambda should fall back to MappedVectorSeq
            Value isMapped = context.eval("cloffle",
                    "(instance? clojure.lang.MappedVectorSeq (map (fn [x] (inc x)) [1 2 3]))");
            assertTrue("Arbitrary lambda on vector should return MappedVectorSeq", isMapped.asBoolean());

            // Results must be identical
            Value matches = context.eval("cloffle",
                    "(= [10 20 30] (into [] (map :a [{:a 10} {:a 20} {:a 30}])))");
            assertTrue("Mapped elements should match expected vector", matches.asBoolean());
        }
    }
}
