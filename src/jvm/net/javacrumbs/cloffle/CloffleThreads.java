package net.javacrumbs.cloffle;

import clojure.lang.Agent;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleSafepoint;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Creates platform {@link Thread}s that enter the current Cloffle polyglot context when
 * one is entered and thread creation is allowed. Falls back to a plain JDK thread otherwise.
 *
 * <p>Agent send/send-off pools and {@code future} submit through this factory. Concurrent
 * Cloffle contexts in one JVM are unsupported: a new context resets the agent pools so
 * workers cannot outlive (or leak into) a foreign context.
 */
public final class CloffleThreads {

    private static final Object LOCK = new Object();
    private static CloffleContext owner;
    private static final Set<Thread> live = ConcurrentHashMap.newKeySet();

    private CloffleThreads() {}

    public static void onContextCreated(CloffleContext ctx) {
        boolean reset = false;
        synchronized (LOCK) {
            reset = owner != null && owner != ctx;
        }
        // Never call shutdownAndReset while holding LOCK or from ThreadFactory.newThread:
        // ThreadPoolExecutor.addWorker already holds its mainLock, and shutdown needs that lock.
        if (reset) {
            Agent.shutdownAndReset();
        }
        synchronized (LOCK) {
            owner = ctx;
        }
    }

    /**
     * Join every guest thread created for the current context. Idle cached-pool workers and
     * daemon helpers (tap-loop) must finish before {@code Context.close}. Does not use
     * {@code ExecutorService.awaitTermination}: a polyglot thread can be cancelled before it
     * ever runs its executor worker.
     */
    public static void joinTrackedThreads() {
        Thread[] threads;
        synchronized (LOCK) {
            threads = live.toArray(Thread[]::new);
        }
        for (Thread t : threads) {
            if (t.isDaemon() && t.isAlive()) {
                t.interrupt();
            }
        }
        for (Thread t : threads) {
            joinThread(t);
            live.remove(t);
        }
        synchronized (LOCK) {
            owner = null;
        }
    }

    @TruffleBoundary
    public static Thread newThread(Runnable runnable, String name) {
        CloffleContext ctx = currentContextOrNull();
        TruffleLanguage.Env env = ctx != null ? ctx.getEnv() : null;
        if (ctx != null && env != null && env.isCreateThreadAllowed()) {
            synchronized (LOCK) {
                owner = ctx;
            }
            Thread thread = env.newTruffleThreadBuilder(runnable)
                    .afterLeave(() -> live.remove(Thread.currentThread()))
                    .build();
            thread.setName(name);
            thread.setUncaughtExceptionHandler((t, e) ->
                    System.err.println("Uncaught exception on Cloffle guest thread \""
                            + t.getName() + "\": " + e));
            live.add(thread);
            return thread;
        }
        Thread thread = new Thread(runnable);
        thread.setName(name);
        return thread;
    }

    @TruffleBoundary
    public static void awaitLatch(CountDownLatch latch) throws InterruptedException {
        if (!safepointsAvailable()) {
            latch.await();
            return;
        }
        TruffleSafepoint.setBlockedThreadInterruptible(null, CountDownLatch::await, latch);
    }

    @TruffleBoundary
    public static boolean awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (!safepointsAvailable()) {
            return latch.await(timeout, unit);
        }
        long[] args = new long[]{timeout};
        TimeUnit[] units = new TimeUnit[]{unit};
        return Boolean.TRUE.equals(TruffleSafepoint.setBlockedThreadInterruptibleFunction(null, l -> {
            boolean ok = l.await(args[0], units[0]);
            return ok ? Boolean.TRUE : Boolean.FALSE;
        }, latch));
    }

    @TruffleBoundary
    public static Object getFuture(Future<?> fut)
            throws InterruptedException, ExecutionException {
        if (!safepointsAvailable()) {
            return fut.get();
        }
        try {
            return TruffleSafepoint.setBlockedThreadInterruptibleFunction(null, f -> {
                try {
                    return f.get();
                } catch (ExecutionException e) {
                    throw new WrappedExecution(e);
                }
            }, fut);
        } catch (WrappedExecution e) {
            throw e.cause;
        }
    }

    @TruffleBoundary
    public static Object getFuture(Future<?> fut, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (!safepointsAvailable()) {
            return fut.get(timeout, unit);
        }
        long[] args = new long[]{timeout};
        TimeUnit[] units = new TimeUnit[]{unit};
        try {
            return TruffleSafepoint.setBlockedThreadInterruptibleFunction(null, f -> {
                try {
                    return f.get(args[0], units[0]);
                } catch (ExecutionException e) {
                    throw new WrappedExecution(e);
                } catch (TimeoutException e) {
                    throw new WrappedTimeout(e);
                }
            }, fut);
        } catch (WrappedExecution e) {
            throw e.cause;
        } catch (WrappedTimeout e) {
            throw e.cause;
        }
    }

    private static final class WrappedExecution extends RuntimeException {
        final ExecutionException cause;
        WrappedExecution(ExecutionException cause) {
            super(cause);
            this.cause = cause;
        }
    }

    private static final class WrappedTimeout extends RuntimeException {
        final TimeoutException cause;
        WrappedTimeout(TimeoutException cause) {
            super(cause);
            this.cause = cause;
        }
    }

    @TruffleBoundary
    public static void joinThread(Thread thread) {
        if (!safepointsAvailable()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        TruffleSafepoint.setBlockedThreadInterruptible(null, Thread::join, thread);
    }

    private static boolean safepointsAvailable() {
        return currentContextOrNull() != null;
    }

    private static CloffleContext currentContextOrNull() {
        try {
            return Clojure.getContext();
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
