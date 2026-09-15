package clojure.lang;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class LazySeqLockFreeTest {

    @Test
    public void testConcurrentRealization() throws Exception {
        int numThreads = 16;
        ExecutorService exec = Executors.newFixedThreadPool(numThreads);
        try {
            AtomicInteger executionCount = new AtomicInteger(0);
            LazySeq seq = new LazySeq(new AFn() {
                @Override
                public Object invoke() {
                    executionCount.incrementAndGet();
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ignored) {
                    }
                    return RT.vector(1, 2, 3);
                }
            });

            CountDownLatch readyLatch = new CountDownLatch(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();

            for (int i = 0; i < numThreads; i++) {
                final int threadIdx = i;
                futures.add(exec.submit(() -> {
                    readyLatch.countDown();
                    startLatch.await();
                    if (threadIdx % 3 == 0) {
                        return seq.first();
                    } else if (threadIdx % 3 == 1) {
                        return seq.count();
                    } else {
                        return seq.seq() != null;
                    }
                }));
            }

            readyLatch.await();
            startLatch.countDown();

            for (Future<Object> f : futures) {
                assertNotNull(f.get());
            }

            assertEquals(1, executionCount.get(), "Thunk must execute exactly once under concurrency");
            assertTrue(seq.isRealized());
            assertEquals(1, seq.first());
            assertEquals(3, seq.count());
        } finally {
            exec.shutdown();
        }
    }

    @Test
    public void testMemoization() {
        AtomicInteger count = new AtomicInteger(0);
        LazySeq s = new LazySeq(new AFn() {
            @Override
            public Object invoke() {
                count.incrementAndGet();
                return RT.vector(10, 20, 30);
            }
        });

        assertEquals(0, count.get());
        assertEquals(10, s.first());
        assertEquals(1, count.get());
        assertEquals(10, s.first());
        assertEquals(10, s.first());
        assertEquals(1, count.get());

        ISeq next = s.next();
        assertNotNull(next);
        assertEquals(20, next.first());
        assertEquals(1, count.get());
    }

    @Test
    public void testIPendingContract() {
        LazySeq s = new LazySeq(new AFn() {
            @Override
            public Object invoke() {
                return RT.vector("a", "b");
            }
        });

        assertFalse(s.isRealized());
        Object val = s.first();
        assertEquals("a", val);
        assertTrue(s.isRealized());
    }

    @Test
    public void testLazinessPreservation() {
        AtomicBoolean invoked = new AtomicBoolean(false);
        LazySeq s = new LazySeq(new AFn() {
            @Override
            public Object invoke() {
                invoked.set(true);
                return RT.vector(42);
            }
        });

        assertFalse(invoked.get(), "Thunk must not run on construction");
        assertFalse(s.isRealized(), "Sequence must not be realized on construction");

        assertEquals(42, s.first());
        assertTrue(invoked.get(), "Thunk must run on first dereference");
        assertTrue(s.isRealized(), "Sequence must be realized after dereference");
    }

    @Test
    public void testReduceAndReduceInit() {
        LazySeq s = new LazySeq(new AFn() {
            @Override
            public Object invoke() {
                return RT.vector(1, 2, 3, 4, 5);
            }
        });

        IFn addFn = new AFn() {
            @Override
            public Object invoke(Object a, Object b) {
                return Numbers.add(a, b);
            }
        };

        // Test 2-arg reduce (IReduceInit)
        Object sumWithInit = ((IReduceInit) s).reduce(addFn, 10);
        assertEquals(25L, ((Number) sumWithInit).longValue());

        // Test 1-arg reduce (IReduce)
        Object sum = ((IReduce) s).reduce(addFn);
        assertEquals(15L, ((Number) sum).longValue());

        // Test short-circuiting with Reduced
        LazySeq infiniteLike = new LazySeq(new AFn() {
            @Override
            public Object invoke() {
                return RT.vector(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            }
        });

        IFn stopAtSixFn = new AFn() {
            @Override
            public Object invoke(Object acc, Object x) {
                long next = Numbers.add(acc, x).longValue();
                if (next >= 6) {
                    return new Reduced(next);
                }
                return next;
            }
        };

        Object earlyResult = ((IReduceInit) infiniteLike).reduce(stopAtSixFn, 0L);
        assertEquals(6L, ((Number) earlyResult).longValue());

        // Test empty sequence reduction
        LazySeq empty = new LazySeq(new AFn() {
            @Override
            public Object invoke() {
                return PersistentList.EMPTY;
            }
        });
        assertEquals(42, ((IReduceInit) empty).reduce(addFn, 42));

        IFn arity0Fn = new AFn() {
            @Override
            public Object invoke() {
                return "empty-identity";
            }

            @Override
            public Object invoke(Object a, Object b) {
                return a;
            }
        };
        assertEquals("empty-identity", ((IReduce) empty).reduce(arity0Fn));
    }

    @Test
    public void testPostForceExceptionRecovery() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        Seqable flakySeqable = new Seqable() {
            @Override
            public ISeq seq() {
                if (fail.get()) {
                    throw new RuntimeException("Simulated transient seq error");
                }
                return RT.vector(100, 200).seq();
            }
        };

        LazySeq s = new LazySeq(new AFn() {
            @Override
            public Object invoke() {
                return flakySeqable;
            }
        });

        try {
            s.seq();
            fail("Expected RuntimeException on first attempt");
        } catch (RuntimeException e) {
            assertEquals("Simulated transient seq error", e.getMessage());
        }

        // Must not be left in REALIZED state (state != 2)
        java.lang.reflect.Field stateField = LazySeq.class.getDeclaredField("state");
        stateField.setAccessible(true);
        assertNotEquals(2, stateField.getInt(s), "Must not be left in REALIZED state");
        assertEquals(1, stateField.getInt(s), "Must be in FORCED state");

        // Fix the transient condition and retry
        fail.set(false);
        ISeq retried = s.seq();
        assertNotNull(retried, "Retrying must recover and produce seq");
        assertEquals(100, retried.first());
        assertEquals(2, stateField.getInt(s), "Must be REALIZED after successful retry");
        assertEquals(100, s.first());
    }

    @Test
    public void testNestedIntermediateExceptionRecovery() throws Exception {
        AtomicBoolean innerFail = new AtomicBoolean(true);
        LazySeq inner = new LazySeq(new AFn() {
            @Override
            public Object invoke() {
                if (innerFail.get()) {
                    throw new RuntimeException("Inner thunk failure");
                }
                return RT.vector(7, 8, 9);
            }
        });

        LazySeq outer = new LazySeq(new AFn() {
            @Override
            public Object invoke() {
                return inner;
            }
        });

        try {
            outer.seq();
            fail("Expected RuntimeException from inner");
        } catch (RuntimeException e) {
            assertEquals("Inner thunk failure", e.getMessage());
        }

        java.lang.reflect.Field stateField = LazySeq.class.getDeclaredField("state");
        stateField.setAccessible(true);
        assertNotEquals(2, stateField.getInt(outer), "Outer must not be in REALIZED state on exception");

        innerFail.set(false);
        ISeq res = outer.seq();
        assertNotNull(res);
        assertEquals(7, res.first());
        assertEquals(2, stateField.getInt(outer), "Outer must be REALIZED after successful retry");
    }

    @Test
    public void testDeepChain() {
        int depth = 100_000;
        LazySeq current = new LazySeq(new AFn() {
            @Override
            public Object invoke() {
                return RT.vector(999);
            }
        });

        for (int i = 0; i < depth; i++) {
            final LazySeq inner = current;
            current = new LazySeq(new AFn() {
                @Override
                public Object invoke() {
                    return inner;
                }
            });
        }

        // Must not StackOverflow and must realize iteratively
        assertEquals(999, current.first());
        assertTrue(current.isRealized());
    }

    @Test
    public void testConcurrentIntermediateAccess() throws Exception {
        int numThreads = 16;
        ExecutorService exec = Executors.newFixedThreadPool(numThreads);
        try {
            LazySeq inner = new LazySeq(new AFn() {
                @Override
                public Object invoke() {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ignored) {
                    }
                    return RT.vector("alpha", "beta");
                }
            });

            LazySeq middle = new LazySeq(new AFn() {
                @Override
                public Object invoke() {
                    return inner;
                }
            });

            LazySeq outer = new LazySeq(new AFn() {
                @Override
                public Object invoke() {
                    return middle;
                }
            });

            CountDownLatch readyLatch = new CountDownLatch(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();

            for (int i = 0; i < numThreads; i++) {
                final int idx = i;
                futures.add(exec.submit(() -> {
                    readyLatch.countDown();
                    startLatch.await();
                    if (idx % 2 == 0) {
                        return outer.first();
                    } else {
                        return middle.first();
                    }
                }));
            }

            readyLatch.await();
            startLatch.countDown();

            for (Future<Object> f : futures) {
                assertEquals("alpha", f.get());
            }

            assertEquals("alpha", middle.first());
            assertEquals("alpha", outer.first());
        } finally {
            exec.shutdown();
        }
    }

    @Test
    @Timeout(value = 5000, unit = java.util.concurrent.TimeUnit.MILLISECONDS)
    public void testSelfReferenceTermination() {
        final LazySeq[] holder = new LazySeq[1];
        holder[0] = new LazySeq(new AFn() {
            @Override
            public Object invoke() {
                return holder[0];
            }
        });

        try {
            holder[0].seq();
            fail("Direct self-cycle must throw IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Recursive lazy-seq realization"));
        }
    }

    @Test
    public void testConsGuardedSelfReferenceWorks() {
        // Idiomatic recursive lazy seq: (def ones (lazy-seq (cons 1 ones)))
        final LazySeq[] ones = new LazySeq[1];
        ones[0] = new LazySeq(new AFn() {
            @Override
            public Object invoke() {
                return RT.cons(1, ones[0]);
            }
        });

        assertEquals(1, ones[0].first());
        assertNotNull(ones[0].next());
        assertEquals(1, ones[0].next().first());
        assertNotNull(ones[0].next().next());
        assertEquals(1, ones[0].next().next().first());
    }
}
