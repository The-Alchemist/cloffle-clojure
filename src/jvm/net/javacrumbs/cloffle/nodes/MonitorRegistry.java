package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class MonitorRegistry {

    private static final ConcurrentHashMap<IdentityKey, ReentrantLock> locks = new ConcurrentHashMap<>();

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
        return locks.computeIfAbsent(new IdentityKey(obj), k -> new ReentrantLock());
    }

    /**
     * Key that uses object identity (reference equality) for mapping.
     * This ensures each distinct object gets its own lock, even when
     * System.identityHashCode() would collide for different objects.
     */
    private static final class IdentityKey {
        private final Object obj;

        IdentityKey(Object obj) {
            this.obj = obj;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof IdentityKey k && k.obj == this.obj;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(obj);
        }
    }
}
