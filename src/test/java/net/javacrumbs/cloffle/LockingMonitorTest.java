package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code clojure.core/locking} holds the real JVM monitor of its argument (see
 * {@link CloffleMonitors}), so it interlocks with host {@code synchronized} and supports
 * {@code wait} / {@code notify}. The bare {@code monitor-enter} / {@code monitor-exit} special
 * forms are unsupported and throw when executed.
 */
public class LockingMonitorTest {

    /** Read from guest code as a static field; reassigned per test. */
    public static Object HOST_LOCK = new Object();

    public static CountDownLatch HOST_LATCH = new CountDownLatch(1);

    public static AtomicBoolean HOST_DONE = new AtomicBoolean();

    private static Context newContext() {
        return Context.newBuilder("cloffle").allowAllAccess(true).build();
    }

    /**
     * A host thread holds {@code synchronized (HOST_LOCK)} and only sets {@code HOST_DONE} just
     * before releasing it. Guest {@code locking} on the same object must therefore observe
     * {@code true}. A side-table lock would let the guest in immediately and observe {@code false}.
     */
    @Test
    public void guestLockingExcludesHostSynchronized() throws Exception {
        HOST_LOCK = new Object();
        HOST_DONE = new AtomicBoolean(false);
        HOST_LATCH = new CountDownLatch(1);
        CountDownLatch holding = HOST_LATCH;
        Object lock = HOST_LOCK;

        Thread host = new Thread(() -> {
            synchronized (lock) {
                holding.countDown();
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                HOST_DONE.set(true);
            }
        }, "host-monitor-holder");
        host.start();

        try (Context context = newContext()) {
            Value observed = context.eval(
                    "cloffle",
                    "(do (.await net.javacrumbs.cloffle.LockingMonitorTest/HOST_LATCH)"
                            + "    (locking net.javacrumbs.cloffle.LockingMonitorTest/HOST_LOCK"
                            + "      (.get net.javacrumbs.cloffle.LockingMonitorTest/HOST_DONE)))");
            assertTrue(observed.asBoolean(), "guest locking must wait for the host synchronized block to finish");
        }
        host.join();
    }

    /**
     * {@code .wait} inside {@code locking} requires the thread to own the object's monitor. The
     * notifier's {@code synchronized} block cannot run until the guest's {@code wait} releases it.
     */
    @Test
    public void waitAndNotifyWorkInsideLocking() throws Exception {
        HOST_LOCK = new Object();
        HOST_LATCH = new CountDownLatch(1);
        CountDownLatch entered = HOST_LATCH;
        Object lock = HOST_LOCK;

        Thread notifier = new Thread(() -> {
            try {
                entered.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (lock) {
                lock.notifyAll();
            }
        }, "host-monitor-notifier");
        notifier.start();

        try (Context context = newContext()) {
            Value result = context.eval(
                    "cloffle",
                    "(locking net.javacrumbs.cloffle.LockingMonitorTest/HOST_LOCK"
                            + "  (.countDown net.javacrumbs.cloffle.LockingMonitorTest/HOST_LATCH)"
                            + "  (.wait net.javacrumbs.cloffle.LockingMonitorTest/HOST_LOCK 5000)"
                            + "  \"woke\")");
            assertEquals("woke", result.asString());
        }
        notifier.join();
    }

    /**
     * {@code alter-meta!} is {@code synchronized} on the reference in {@code AReference}, so a
     * guest thread holding {@code locking} on the same var delays it.
     */
    @Test
    public void lockingAVarExcludesAlterMeta() {
        try (Context context = newContext()) {
            Value order = context.eval(
                    "cloffle",
                    "(let [v (intern (create-ns 'cloffle.monitor-parity) 'probe 1)"
                            + "      log (atom [])"
                            + "      entered (promise)"
                            + "      f (future (locking v"
                            + "                  (deliver entered true)"
                            + "                  (Thread/sleep 300)"
                            + "                  (swap! log conj :locked)))]"
                            + "  @entered"
                            + "  (alter-meta! v (fn [m] (swap! log conj :meta) m))"
                            + "  @f"
                            + "  (pr-str @log))");
            assertEquals("[:locked :meta]", order.asString());
        }
    }

    @Test
    public void lockingIsReentrantOnTheSameObject() {
        try (Context context = newContext()) {
            Value result = context.eval(
                    "cloffle",
                    "(let [o (Object.)] (locking o (locking o 42)))");
            assertEquals(42L, result.asLong());
        }
    }

    /**
     * Cloffle's locking body is an fn passed to a host synchronized helper. A mutable deftype field
     * referenced by that fn must resolve through the owning deftype instance, not a captured value.
     */
    @Test
    public void lockingClosureWritesOwningMutableDeftypeField() {
        try (Context context = newContext()) {
            Value result = context.eval(
                    "cloffle",
                    "(do"
                            + " (deftype LockingMutableBox [lock ^:volatile-mutable value]"
                            + "   Object"
                            + "   (toString [this]"
                            + "     (locking lock"
                            + "       (set! value (inc value))"
                            + "       (str value))))"
                            // ->LockingMutableBox is a Var, so it resolves at run time; the bare
                            // class name is not importable within this same compilation unit.
                            + " (let [x (->LockingMutableBox (Object.) 0)]"
                            + "   (str (.toString x) \"|\" (.toString x))))");
            assertEquals("1|2", result.asString());
        }
    }

    /** A throw out of the body must release the monitor for other guest threads. */
    @Test
    public void lockReleasedWhenBodyThrows() {
        try (Context context = newContext()) {
            Value result = context.eval(
                    "cloffle",
                    "(let [o (Object.)]"
                            + "  (try (locking o (throw (RuntimeException. \"boom\")))"
                            + "       (catch RuntimeException _ nil))"
                            + "  (deref (future (locking o \"acquired\")) 5000 \"timeout\"))");
            assertEquals("acquired", result.asString());
        }
    }

    /** {@code serialized-require} locks {@code RT/REQUIRE_LOCK}; run it from several guest threads. */
    @Test
    public void concurrentRequiringResolveAcrossGuestThreads() {
        try (Context context = newContext()) {
            Value resolved = context.eval(
                    "cloffle",
                    "(let [fs (doall (repeatedly 8 #(future (requiring-resolve 'clojure.set/union))))]"
                            + "  (count (filter some? (map deref fs))))");
            assertEquals(8L, resolved.asLong());
        }
    }

    @Test
    public void bareMonitorEnterThrowsUnsupported() {
        try (Context context = newContext()) {
            context.eval("cloffle", "(let [o (Object.)] (monitor-enter o))");
            fail("expected monitor-enter to throw");
        } catch (PolyglotException e) {
            assertTrue(e.getMessage().contains("monitor-enter"), e.getMessage());
            assertTrue(e.getMessage().contains("locking"), e.getMessage());
        }
    }
}
