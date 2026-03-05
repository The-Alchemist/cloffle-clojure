package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class MonitorRegistry {

    private static final ConcurrentHashMap<Integer, ReentrantLock> locks = new ConcurrentHashMap<>();

    private MonitorRegistry() {}

    @TruffleBoundary
    public static void enter(Object obj) {
        getLock(obj).lock();
    }

    @TruffleBoundary
    public static void exit(Object obj) {
        getLock(obj).unlock();
    }

    private static ReentrantLock getLock(Object obj) {
        return locks.computeIfAbsent(System.identityHashCode(obj), k -> new ReentrantLock());
    }
}
