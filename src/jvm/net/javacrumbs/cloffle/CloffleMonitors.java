package net.javacrumbs.cloffle;

import clojure.lang.IFn;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Host-side implementation of {@code clojure.core/locking}.
 *
 * <p>Stock Clojure emits {@code monitorenter} / {@code monitorexit} around the body inside a single
 * generated method, which satisfies JVM structured locking (JVMS 2.11.10). Cloffle cannot do the
 * same: the bytecode DSL would have to split the two instructions across separate host frames, and
 * HotSpot releases frame-held monitors on return and refuses to JIT non-nested monitor pairs.
 *
 * <p>Running the body inside a host {@code synchronized} block keeps the enter/exit pair in one
 * frame, so {@code locking} uses the real object monitor: it excludes host {@code synchronized}
 * (for example {@link clojure.lang.AReference#alterMeta} or {@code LazySeq} realization), supports
 * {@code wait} / {@code notify}, is reentrant, and releases on any unwind.
 *
 * <p>Blocking here is not safepoint-aware — {@code monitorenter} has no interruptible variant — so
 * a guest thread contending for a lock cannot be interrupted by the debugger or by context
 * cancellation. That matches stock Clojure on the JVM.
 */
public final class CloffleMonitors {

    private CloffleMonitors() {}

    @TruffleBoundary
    public static Object lock(Object lockee, IFn body) {
        synchronized (lockee) {
            return body.invoke();
        }
    }
}
