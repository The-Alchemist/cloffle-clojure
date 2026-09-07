package clojure.lang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class MappedMapSeqTest {

    private static final IFn IDENTITY = new AFn() {
        @Override
        public Object invoke(Object arg) {
            return arg;
        }
    };

    @Test
    public void testMapEntryContract() {
        IPersistentMap map = (IPersistentMap) RT.map(Keyword.intern("a"), 1);
        ISeq s = MappedMapSeq.create(IDENTITY, map);
        assertNotNull(s);
        assertTrue(s instanceof MappedMapSeq);

        Object entryObj = s.first();
        assertNotNull(entryObj);
        assertTrue("first element must be an IMapEntry", entryObj instanceof IMapEntry);

        IMapEntry entry = (IMapEntry) entryObj;
        assertEquals(Keyword.intern("a"), entry.key());
        assertEquals(1, ((Number) entry.val()).intValue());
        assertEquals(Keyword.intern("a"), entry.getKey());
        assertEquals(1, ((Number) entry.getValue()).intValue());
    }

    @Test
    public void testMemoizationAndIPending() {
        AtomicInteger count = new AtomicInteger(0);
        IFn sideEffectFn = new AFn() {
            @Override
            public Object invoke(Object e) {
                count.incrementAndGet();
                IMapEntry me = (IMapEntry) e;
                return MapEntry.create(me.key(), Numbers.add(me.val(), 10));
            }
        };

        IPersistentMap map = (IPersistentMap) RT.map(Keyword.intern("k1"), 100, Keyword.intern("k2"), 200);
        ISeq s = MappedMapSeq.create(sideEffectFn, map);
        assertNotNull(s);
        MappedMapSeq mms = (MappedMapSeq) s;

        assertFalse("Should be unrealized initially", mms.isRealized());
        assertEquals(0, count.get());

        // First call evaluates fn
        IMapEntry firstEntry = (IMapEntry) mms.first();
        assertNotNull(firstEntry);
        assertTrue("Should be realized after first()", mms.isRealized());
        assertEquals(1, count.get());

        // Repeated calls return cached value without re-evaluating
        assertSame(firstEntry, mms.first());
        assertSame(firstEntry, mms.first());
        assertEquals("f must only be invoked once for the entry", 1, count.get());

        // Next element
        ISeq nextSeq = mms.next();
        assertNotNull(nextSeq);
        MappedMapSeq nextMms = (MappedMapSeq) nextSeq;
        assertFalse(nextMms.isRealized());

        IMapEntry secondEntry = (IMapEntry) nextMms.first();
        assertNotNull(secondEntry);
        assertTrue(nextMms.isRealized());
        assertEquals(2, count.get());

        assertSame(secondEntry, nextMms.first());
        assertEquals(2, count.get());
    }

    @Test
    public void testIKVReduceAcceleration() {
        IFn transformFn = new AFn() {
            @Override
            public Object invoke(Object e) {
                IMapEntry me = (IMapEntry) e;
                return MapEntry.create(me.key(), Numbers.multiply(me.val(), 2));
            }
        };

        IFn rf = new AFn() {
            @Override
            public Object invoke(Object acc, Object e) {
                IMapEntry me = (IMapEntry) e;
                return ((IPersistentMap) acc).assoc(me.key(), me.val());
            }
        };

        // 1. PersistentShapeMap (small map literal <= 8 keys)
        IPersistentMap shapeMap = (IPersistentMap) RT.map(Keyword.intern("x"), 1, Keyword.intern("y"), 2);
        assertTrue("Should be PersistentShapeMap", shapeMap instanceof PersistentShapeMap);
        MappedMapSeq s1 = (MappedMapSeq) MappedMapSeq.create(transformFn, shapeMap);
        IPersistentMap res1 = (IPersistentMap) s1.reduce(rf, PersistentArrayMap.EMPTY);
        assertEquals(2L, ((Number) res1.valAt(Keyword.intern("x"))).longValue());
        assertEquals(4L, ((Number) res1.valAt(Keyword.intern("y"))).longValue());

        // 2. PersistentArrayMap
        Object[] array = new Object[]{Keyword.intern("m1"), 10, Keyword.intern("m2"), 20};
        IPersistentMap arrayMap = new PersistentArrayMap(array);
        assertTrue(arrayMap instanceof PersistentArrayMap);
        MappedMapSeq s2 = (MappedMapSeq) MappedMapSeq.create(transformFn, arrayMap);
        IPersistentMap res2 = (IPersistentMap) s2.reduce(rf, PersistentArrayMap.EMPTY);
        assertEquals(20L, ((Number) res2.valAt(Keyword.intern("m1"))).longValue());
        assertEquals(40L, ((Number) res2.valAt(Keyword.intern("m2"))).longValue());

        // 3. PersistentHashMap (large map > 8 elements or constructed via PersistentHashMap)
        Map<Object, Object> jm = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            jm.put(Keyword.intern("k" + i), i);
        }
        IPersistentMap hashMap = PersistentHashMap.create(jm);
        assertTrue(hashMap instanceof PersistentHashMap);
        MappedMapSeq s3 = (MappedMapSeq) MappedMapSeq.create(transformFn, hashMap);
        IPersistentMap res3 = (IPersistentMap) s3.reduce(rf, PersistentHashMap.EMPTY);
        assertEquals(20, res3.count());
        for (int i = 0; i < 20; i++) {
            assertEquals((long) (i * 2), ((Number) res3.valAt(Keyword.intern("k" + i))).longValue());
        }
    }

    @Test
    public void testNestedMapTraversalAndDestructuring() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value result = context.eval("cloffle",
                    "(let [m {:a 1 :b 2 :c 3}\n" +
                    "      s (clojure.lang.MappedMapSeq/create (fn [[k v]] [k (inc v)]) m)\n" +
                    "      out (into {} s)]\n" +
                    "  [(:a out) (:b out) (:c out)])");

            assertEquals(2, result.getArrayElement(0).asInt());
            assertEquals(3, result.getArrayElement(1).asInt());
            assertEquals(4, result.getArrayElement(2).asInt());
        }
    }

    @Test
    public void testReducedShortCircuiting() {
        IPersistentMap map = (IPersistentMap) RT.map(Keyword.intern("a"), 10, Keyword.intern("b"), 20, Keyword.intern("c"), 30);
        MappedMapSeq s = (MappedMapSeq) MappedMapSeq.create(IDENTITY, map);

        IFn shortCircuitRf = new AFn() {
            @Override
            public Object invoke(Object acc, Object item) {
                IMapEntry me = (IMapEntry) item;
                long sum = Numbers.add(acc, me.val()).longValue();
                if (sum >= 30) {
                    return new Reduced(sum);
                }
                return sum;
            }
        };

        Object result = s.reduce(shortCircuitRf, 0L);
        assertEquals(30L, ((Number) result).longValue());

        // 1-arg reduce
        IFn sumValuesRf = new AFn() {
            @Override
            public Object invoke(Object acc, Object item) {
                long a = (acc instanceof IMapEntry) ? ((Number) ((IMapEntry) acc).val()).longValue() : ((Number) acc).longValue();
                long b = ((Number) ((IMapEntry) item).val()).longValue();
                return a + b;
            }
        };
        Object sumResult = s.reduce(sumValuesRf);
        assertEquals(60L, ((Number) sumResult).longValue());
    }

    @Test
    public void testAlgebraicComposition() {
        IPersistentMap map = (IPersistentMap) RT.map(Keyword.intern("a"), 1, Keyword.intern("b"), 2);
        IFn f = new AFn() {
            @Override
            public Object invoke(Object e) {
                IMapEntry me = (IMapEntry) e;
                return MapEntry.create(me.key(), Numbers.add(me.val(), 10));
            }
        };
        IFn g = new AFn() {
            @Override
            public Object invoke(Object e) {
                IMapEntry me = (IMapEntry) e;
                return MapEntry.create(me.key(), Numbers.multiply(me.val(), 2));
            }
        };

        ISeq s1 = MappedMapSeq.create(f, map);
        assertTrue(s1 instanceof MappedMapSeq);

        ISeq s2 = MappedMapSeq.create(g, s1);
        assertTrue(s2 instanceof MappedMapSeq);
        MappedMapSeq mms2 = (MappedMapSeq) s2;
        assertSame("Underlying map should be retained across composition", map, mms2.m);

        // Reduction with composed functions
        IFn rf = new AFn() {
            @Override
            public Object invoke(Object acc, Object e) {
                IMapEntry me = (IMapEntry) e;
                return ((IPersistentMap) acc).assoc(me.key(), me.val());
            }
        };
        IPersistentMap res = (IPersistentMap) mms2.reduce(rf, PersistentArrayMap.EMPTY);
        // (1 + 10) * 2 = 22, (2 + 10) * 2 = 24
        assertEquals(22L, ((Number) res.valAt(Keyword.intern("a"))).longValue());
        assertEquals(24L, ((Number) res.valAt(Keyword.intern("b"))).longValue());
    }

    @Test
    public void testEmptyAndBoundsHandling() {
        // Empty map returns PersistentList.EMPTY
        ISeq emptySeq = MappedMapSeq.create(IDENTITY, PersistentArrayMap.EMPTY);
        assertEquals(PersistentList.EMPTY, emptySeq);

        ISeq nullCollSeq = MappedMapSeq.create(IDENTITY, (IPersistentMap) null);
        assertEquals(PersistentList.EMPTY, nullCollSeq);

        // 1-element map next() returns null
        IPersistentMap single = (IPersistentMap) RT.map(Keyword.intern("only"), 42);
        ISeq singleSeq = MappedMapSeq.create(IDENTITY, single);
        assertNotNull(singleSeq);
        assertNotNull(singleSeq.first());
        assertNull(singleSeq.next());
        assertEquals(PersistentList.EMPTY, singleSeq.more());
    }

    @Test
    public void testConcurrentRealization() throws Exception {
        int numThreads = 16;
        ExecutorService exec = Executors.newFixedThreadPool(numThreads);
        try {
            AtomicInteger evalCount = new AtomicInteger(0);
            IFn sideEffect = new AFn() {
                @Override
                public Object invoke(Object e) {
                    evalCount.incrementAndGet();
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ignored) {
                    }
                    IMapEntry me = (IMapEntry) e;
                    return MapEntry.create(me.key(), Numbers.multiply(me.val(), 100));
                }
            };

            IPersistentMap map = (IPersistentMap) RT.map(Keyword.intern("key"), 7);
            MappedMapSeq seq = (MappedMapSeq) MappedMapSeq.create(sideEffect, map);

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
                IMapEntry me = (IMapEntry) f.get();
                assertEquals(700L, ((Number) me.val()).longValue());
            }

            assertEquals("Entry evaluation must occur exactly once across threads", 1, evalCount.get());
        } finally {
            exec.shutdown();
        }
    }

    @Test
    public void testSequentialInterfaceContract() {
        IPersistentMap map = (IPersistentMap) RT.map(Keyword.intern("k1"), 10, Keyword.intern("k2"), 20);
        ISeq s = MappedMapSeq.create(IDENTITY, map);
        assertNotNull(s);
        assertEquals(2, ((Counted) s).count());

        ISeq sNext = s.next();
        assertNotNull(sNext);
        assertEquals(1, ((Counted) sNext).count());

        assertNull(sNext.next());
        assertEquals(PersistentList.EMPTY, sNext.more());

        ISeq expected = map.seq();
        assertTrue(s.equiv(expected));
        assertTrue(s.equals(expected));
        assertEquals(expected.hashCode(), s.hashCode());
        assertEquals(((IHashEq) expected).hasheq(), ((IHashEq) s).hasheq());
    }

    @Test
    public void testClojureIntegrationAndAtomMemoization() {
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Value result = context.eval("cloffle",
                    "(let [c (atom 0)\n" +
                    "      m {:x 1 :y 2}\n" +
                    "      s (clojure.lang.MappedMapSeq/create (fn [e] (swap! c inc) e) m)]\n" +
                    "  (assert (= (val (first s)) 1))\n" +
                    "  (assert (= (val (first s)) 1))\n" +
                    "  (assert (= @c 1))\n" +
                    "  @c)");
            assertEquals(1, result.asInt());
        }
    }
}
