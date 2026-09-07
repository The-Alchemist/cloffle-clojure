package clojure.lang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class MappedVectorSeqTest {

    @Test
    public void testSequentialInterfaceContract() {
        IPersistentVector vec = (IPersistentVector) RT.vector("a", "b", "c");
        IFn toUpper = new AFn() {
            @Override
            public Object invoke(Object arg) {
                return ((String) arg).toUpperCase();
            }
        };

        ISeq s = MappedVectorSeq.create(toUpper, vec, 0);
        assertNotNull(s);
        assertTrue(s instanceof MappedVectorSeq);
        MappedVectorSeq mvs = (MappedVectorSeq) s;

        // count & index
        assertEquals(3, mvs.count());
        assertEquals(0, mvs.index());

        // first
        assertEquals("A", mvs.first());

        // next & more
        ISeq s1 = mvs.next();
        assertNotNull(s1);
        assertTrue(s1 instanceof MappedVectorSeq);
        assertEquals(2, ((Counted) s1).count());
        assertEquals(1, ((IndexedSeq) s1).index());
        assertEquals("B", s1.first());

        ISeq s2 = s1.next();
        assertNotNull(s2);
        assertEquals("C", s2.first());

        // bounds on next()
        assertNull(s2.next());
        assertEquals(PersistentList.EMPTY, s2.more());

        // nth
        assertEquals("A", mvs.nth(0));
        assertEquals("B", mvs.nth(1));
        assertEquals("C", mvs.nth(2));
        assertEquals("not-found", mvs.nth(3, "not-found"));
        assertEquals("not-found", mvs.nth(-1, "not-found"));

        try {
            mvs.nth(3);
            fail("Expected IndexOutOfBoundsException");
        } catch (IndexOutOfBoundsException ignored) {
        }

        // equiv & equals
        ISeq expected = (ISeq) RT.vector("A", "B", "C").seq();
        assertTrue(mvs.equiv(expected));
        assertTrue(mvs.equals(expected));
        assertEquals(expected.hashCode(), mvs.hashCode());
        assertEquals(((IHashEq) expected).hasheq(), mvs.hasheq());
    }

    @Test
    public void testMemoizationOnRealization() {
        AtomicInteger count = new AtomicInteger(0);
        IFn sideEffectFn = new AFn() {
            @Override
            public Object invoke(Object x) {
                count.incrementAndGet();
                return Numbers.add(x, 10);
            }
        };

        IPersistentVector vec = (IPersistentVector) RT.vector(1, 2, 3);
        ISeq s = MappedVectorSeq.create(sideEffectFn, vec, 0);
        assertNotNull(s);
        MappedVectorSeq mvs = (MappedVectorSeq) s;

        assertFalse("Should be unrealized before first()", mvs.isRealized());
        assertEquals(0, count.get());

        // First call evaluates fn
        assertEquals(11L, ((Number) mvs.first()).longValue());
        assertTrue("Should be realized after first()", mvs.isRealized());
        assertEquals(1, count.get());

        // Second and third call MUST return cached value without re-running fn
        assertEquals(11L, ((Number) mvs.first()).longValue());
        assertEquals(11L, ((Number) mvs.first()).longValue());
        assertEquals(1, count.get());

        // Next element evaluates fn once
        ISeq sNext = mvs.next();
        assertNotNull(sNext);
        MappedVectorSeq mvsNext = (MappedVectorSeq) sNext;
        assertFalse(mvsNext.isRealized());

        assertEquals(12L, ((Number) mvsNext.first()).longValue());
        assertTrue(mvsNext.isRealized());
        assertEquals(2, count.get());

        assertEquals(12L, ((Number) mvsNext.first()).longValue());
        assertEquals(2, count.get());
    }

    @Test
    public void testIPendingContract() {
        IPersistentVector vec = (IPersistentVector) RT.vector(42);
        MappedVectorSeq s = (MappedVectorSeq) MappedVectorSeq.create(new AFn() {
            @Override
            public Object invoke(Object x) {
                return Numbers.multiply(x, 2);
            }
        }, vec, 0);

        assertFalse("IPending isRealized must be false initially", s.isRealized());
        assertEquals(84L, ((Number) s.first()).longValue());
        assertTrue("IPending isRealized must be true after first()", s.isRealized());
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

        ISeq s1 = MappedVectorSeq.create(f, vec, 0);
        assertTrue(s1 instanceof MappedVectorSeq);

        ISeq s2 = MappedVectorSeq.create(g, s1, 0);
        assertTrue(s2 instanceof MappedVectorSeq);
        MappedVectorSeq mvs2 = (MappedVectorSeq) s2;

        // Verify single-level indirection: underlying collection is still the original vector!
        assertSame("Underlying vector should be preserved across composition", vec, mvs2.v);
        assertEquals(0, mvs2.i);

        // Verify elements yield g(f(x)) = (x + 1) * 10
        assertEquals(20L, ((Number) mvs2.first()).longValue());
        assertEquals(30L, ((Number) mvs2.next().first()).longValue());
        assertEquals(40L, ((Number) mvs2.next().next().first()).longValue());
        assertNull(mvs2.next().next().next());

        // Triple composition: (h (g (f x)))
        IFn h = new AFn() {
            @Override
            public Object invoke(Object x) {
                return Numbers.minus(x, 5);
            }
        };
        ISeq s3 = MappedVectorSeq.create(h, s2, 0);
        assertTrue(s3 instanceof MappedVectorSeq);
        MappedVectorSeq mvs3 = (MappedVectorSeq) s3;
        assertSame(vec, mvs3.v);

        assertEquals(15L, ((Number) mvs3.first()).longValue());
        assertEquals(25L, ((Number) mvs3.next().first()).longValue());
        assertEquals(35L, ((Number) mvs3.next().next().first()).longValue());
    }

    @Test
    public void testNestedVectorTraversal() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value result = context.eval("cloffle",
                    "(let [maps [{:a 1} {:a 2} {:a 3}]\n" +
                    "      s (clojure.lang.MappedVectorSeq/create (fn [m] (update m :a inc)) maps 0)]\n" +
                    "  [(:a (first s))\n" +
                    "   (:a (first (rest s)))\n" +
                    "   (:a (first (rest (rest s))))])");

            assertEquals(2, result.getArrayElement(0).asInt());
            assertEquals(3, result.getArrayElement(1).asInt());
            assertEquals(4, result.getArrayElement(2).asInt());
        }
    }

    @Test
    public void testReduceAndReducedShortCircuiting() {
        IPersistentVector vec = (IPersistentVector) RT.vector(1, 2, 3, 4, 5);
        IFn incFn = new AFn() {
            @Override
            public Object invoke(Object x) {
                return Numbers.add(x, 10);
            }
        };

        MappedVectorSeq s = (MappedVectorSeq) MappedVectorSeq.create(incFn, vec, 0);

        // Standard 2-arg reduce
        IFn addFn = new AFn() {
            @Override
            public Object invoke(Object a, Object b) {
                return Numbers.add(a, b);
            }
        };
        Object sumWithInit = s.reduce(addFn, 0L);
        // (1+10) + (2+10) + (3+10) + (4+10) + (5+10) = 11 + 12 + 13 + 14 + 15 = 65
        assertEquals(65L, ((Number) sumWithInit).longValue());

        // 1-arg reduce
        Object sum = s.reduce(addFn);
        assertEquals(65L, ((Number) sum).longValue());

        // Reduced short-circuiting: stop when acc >= 30
        IFn shortCircuitFn = new AFn() {
            @Override
            public Object invoke(Object acc, Object x) {
                long total = Numbers.add(acc, x).longValue();
                if (total >= 30) {
                    return new Reduced(total);
                }
                return total;
            }
        };

        Object earlySum = s.reduce(shortCircuitFn, 0L);
        // 11 + 12 + 13 = 36 >= 30 -> Reduced(36) -> deref returns 36
        assertEquals(36L, ((Number) earlySum).longValue());

        // Empty reduce
        MappedVectorSeq empty = (MappedVectorSeq) new MappedVectorSeq(incFn, PersistentVector.EMPTY, 0);
        assertEquals(100L, empty.reduce(addFn, 100L));
    }

    @Test
    public void testEmptyAndBoundsHandling() {
        IFn id = new AFn() {
            @Override
            public Object invoke(Object x) {
                return x;
            }
        };

        // Empty vector returns null (consistent with seq convention)
        ISeq emptySeq = MappedVectorSeq.create(id, PersistentVector.EMPTY, 0);
        assertNull(emptySeq);

        ISeq nullCollSeq = MappedVectorSeq.create(id, (Object) null, 0);
        assertNull(nullCollSeq);

        // Out of bounds index returns null
        IPersistentVector vec2 = (IPersistentVector) RT.vector(10, 20);
        ISeq oobSeq = MappedVectorSeq.create(id, vec2, 5);
        assertNull(oobSeq);

        ISeq negSeq = MappedVectorSeq.create(id, vec2, -1);
        assertNull(negSeq);

        // 1-element vector: next() returns null
        IPersistentVector single = (IPersistentVector) RT.vector("only");
        ISeq singleSeq = MappedVectorSeq.create(id, single, 0);
        assertNotNull(singleSeq);
        assertEquals("only", singleSeq.first());
        assertNull(singleSeq.next());
        assertEquals(PersistentList.EMPTY, singleSeq.more());
    }

    @Test
    public void testConcurrentRealization() throws Exception {
        int numThreads = 16;
        ExecutorService exec = Executors.newFixedThreadPool(numThreads);
        try {
            AtomicInteger evalCount = new AtomicInteger(0);
            IFn thunk = new AFn() {
                @Override
                public Object invoke(Object x) {
                    evalCount.incrementAndGet();
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ignored) {
                    }
                    return Numbers.multiply(x, 100);
                }
            };

            IPersistentVector vec = (IPersistentVector) RT.vector(7);
            MappedVectorSeq seq = (MappedVectorSeq) MappedVectorSeq.create(thunk, vec, 0);

            CountDownLatch readyLatch = new CountDownLatch(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();

            for (int i = 0; i < numThreads; i++) {
                futures.add(exec.submit(() -> {
                    readyLatch.countDown();
                    startLatch.await();
                    return seq.first();
                }));
            }

            readyLatch.await();
            startLatch.countDown();

            for (Future<Object> f : futures) {
                assertEquals(700L, ((Number) f.get()).longValue());
            }

            assertEquals("Element evaluation must occur exactly once across threads", 1, evalCount.get());
        } finally {
            exec.shutdown();
        }
    }

    @Test
    public void testClojureIntegrationAndAtomMemoization() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value result = context.eval("cloffle",
                    "(let [c (atom 0)\n" +
                    "      s (clojure.lang.MappedVectorSeq/create (fn [x] (swap! c inc) (inc x)) [1 2 3] 0)]\n" +
                    "  (assert (= (first s) 2))\n" +
                    "  (assert (= (first s) 2))\n" +
                    "  (assert (= @c 1))\n" +
                    "  @c)");
            assertEquals(1, result.asInt());
        }
    }
}
