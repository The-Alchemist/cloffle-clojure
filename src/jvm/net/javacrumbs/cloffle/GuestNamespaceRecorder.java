package net.javacrumbs.cloffle;

import clojure.lang.Namespace;
import clojure.lang.RT;
import com.oracle.truffle.api.CompilerDirectives;

/**
 * Records {@code RT.CURRENT_NS} from the <strong>guest</strong> Truffle thread so debugger tooling
 * (which runs on other threads) can show namespace vars and labels that match the suspended
 * program. Plain {@code Var#deref()} on {@code *ns*} from a DAP/protocol thread sees only root
 * bindings ({@code clojure.core}), not the guest thread's dynamic scope.
 */
public final class GuestNamespaceRecorder {

    private GuestNamespaceRecorder() {}

    /**
     * Snapshot current {@code *ns*} into {@link CloffleContext}. Safe to call from guest roots
     * and from {@link com.oracle.truffle.api.interop.NodeLibrary#getScope} while handling a
     * suspended guest thread.
     */
    @CompilerDirectives.TruffleBoundary
    public static void recordIfPossible() {
        try {
            CloffleContext ctx = Clojure.getContext();
            if (ctx == null) {
                return;
            }
            Object o = RT.CURRENT_NS.deref();
            if (o instanceof Namespace ns) {
                // Do not snapshot clojure.core: getScope() may run when *ns* has fallen back to core
                // on this thread and would overwrite a good snapshot from root execute.
                if (isClojureCore(ns)) {
                    return;
                }
                ctx.setGuestNamespaceForDebugger(ns);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isClojureCore(Namespace ns) {
        return ns.getName() != null && "clojure.core".equals(ns.getName().getName());
    }
}
