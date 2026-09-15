package clojure.lang;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shapes are {@code @ValueType}s and are not interned.  These tests hammer
 * {@link MapShape#addKey} and {@link MapShape#removeKey} from several threads
 * and assert that a transition never returns a shape whose key set disagrees
 * with the requested one.
 */
public class MapShapeConcurrentTransitionTest {

    private static final int THREADS = 8;
    private static final int ITERATIONS = 20_000;

    @Test
    public void addKeyNeverReturnsShapeMissingTheKey() throws Exception {
        String tag = "add-" + System.nanoTime();
        MapShape base = MapShape.of(
                Keyword.intern(tag + "-base0"),
                Keyword.intern(tag + "-base1"),
                Keyword.intern(tag + "-base2"));

        Keyword[] added = new Keyword[THREADS];
        for (int i = 0; i < THREADS; i++) added[i] = Keyword.intern(tag + "-new" + i);

        runConcurrently(t -> {
            Keyword kw = added[t];
            for (int i = 0; i < ITERATIONS; i++) {
                MapShape result = base.addKey(kw);
                assertEquals(base.count + 1, result.count, "added shape should have one more key");
                assertTrue(result.indexOf(kw) >= 0, "addKey returned a shape without " + kw);
                for (int slot = 0; slot < base.count; slot++) {
                    assertTrue(result.indexOf(base.getKey(slot)) >= 0, "added shape dropped " + base.getKey(slot));
                }
            }
        });
    }

    @Test
    public void removeKeyNeverReturnsShapeKeepingTheKey() throws Exception {
        String tag = "remove-" + System.nanoTime();
        Keyword[] keys = new Keyword[THREADS];
        for (int i = 0; i < THREADS; i++) keys[i] = Keyword.intern(tag + "-k" + i);
        MapShape base = MapShape.of(keys);

        runConcurrently(t -> {
            int slot = t;
            Keyword removed = base.getKey(slot);
            for (int i = 0; i < ITERATIONS; i++) {
                MapShape result = base.removeKey(slot);
                assertEquals(base.count - 1, result.count, "removed shape should have one fewer key");
                assertEquals(-1, result.indexOf(removed), "removeKey returned a shape still holding " + removed);
                for (int other = 0; other < base.count; other++) {
                    if (other == slot) continue;
                    assertTrue(result.indexOf(base.getKey(other)) >= 0, "removed shape dropped " + base.getKey(other));
                }
            }
        });
    }

    private interface ThreadBody {
        void run(int threadIndex) throws Exception;
    }

    private static void runConcurrently(ThreadBody body) throws Exception {
        CyclicBarrier start = new CyclicBarrier(THREADS);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>(THREADS);

        for (int t = 0; t < THREADS; t++) {
            int index = t;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    body.run(index);
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }, "map-shape-transition-" + t);
            threads.add(thread);
            thread.start();
        }

        for (Thread thread : threads) thread.join();

        Throwable e = failure.get();
        if (e != null) throw new AssertionError("concurrent transition failed", e);
    }
}
