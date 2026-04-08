package net.javacrumbs.cloffle.debug;

import com.oracle.truffle.api.debug.Debugger;
import net.javacrumbs.cloffle.CloffleContext;
import net.javacrumbs.cloffle.Clojure;
import org.graalvm.polyglot.Engine;

/**
 * When a Truffle {@link DebuggerSession} is active, {@link net.javacrumbs.cloffle.nodes.invoke.InvokeNode}
 * skips tail-call optimization so the guest stack matches lexical calls (and tools like DAP show separate
 * frames). Deep mutual tail recursion may then exhaust the Java stack while debugging — the usual trade-off.
 */
public final class DebuggerTailCallPolicy {

    /**
     * Polyglot embedders typically use {@link Debugger#find(Engine)}; guest code only sees
     * {@link com.oracle.truffle.api.TruffleLanguage.Env}. Session counts may differ per handle, so DAP
     * and tests register this hint when they own the {@link Engine}.
     */
    private static volatile Engine polyglotEngineHint;

    private DebuggerTailCallPolicy() {
    }

    /** Called by DAP / tests that attach the debugger via {@link Debugger#find(Engine)}. */
    public static void setPolyglotEngineHint(Engine engine) {
        polyglotEngineHint = engine;
    }

    /**
     * @return true if guest calls should not use {@code TailCallException} (preserve physical stack frames).
     */
    public static boolean preservePhysicalStackForDebugger() {
        boolean fromEnv = false;
        try {
            CloffleContext ctx = Clojure.getContext();
            if (ctx != null && ctx.getEnv() != null) {
                Debugger dbg = Debugger.find(ctx.getEnv());
                fromEnv = dbg != null && dbg.getSessionCount() > 0;
            }
        } catch (IllegalStateException ignored) {
            // e.g. getCurrentContext() when not entered on this thread — still try polyglot hint below.
        }
        if (fromEnv) {
            return true;
        }
        try {
            Engine eng = polyglotEngineHint;
            if (eng != null) {
                Debugger d2 = Debugger.find(eng);
                return d2 != null && d2.getSessionCount() > 0;
            }
        } catch (NoClassDefFoundError e) {
            return false;
        }
        return false;
    }
}
