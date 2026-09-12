package net.javacrumbs.cloffle;

import clojure.lang.Namespace;

import com.oracle.truffle.api.ContextThreadLocal;
import com.oracle.truffle.api.TruffleLanguage;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Language context that persists across evaluations within a single
 * Polyglot Context. Var bindings are managed by Clojure's Var system
 * (Var.bindRoot / Var.deref) -- this context only holds the Truffle
 * language reference needed for RootNode construction.
 */
public class CloffleContext {

    private TruffleLanguage<?> language;
    private TruffleLanguage.Env env;

    /** Value of {@link Clojure#CLEAR_DEAD_LOCALS} for this context, read once at context creation. */
    private boolean clearDeadLocals = true;

    /**
     * Per-guest-thread {@code *ns*} snapshot ({@link GuestNamespaceRecorder}). Tooling threads
     * that never ran guest code fall back to {@link #guestNamespaceForDebuggerFallback}.
     */
    private ContextThreadLocal<AtomicReference<Namespace>> guestNamespaceForThread;

    /**
     * Last {@code *ns*} observed on any guest thread. Used by
     * {@link net.javacrumbs.cloffle.nodes.ClojureTopScope} when debugger tooling runs off-thread.
     */
    private volatile Namespace guestNamespaceForDebuggerFallback;

    public void setLanguage(TruffleLanguage<?> language) {
        this.language = language;
    }

    public TruffleLanguage<?> language() {
        return language;
    }

    /** Set once in {@link Clojure#createContext(TruffleLanguage.Env)}. Used for debugger lookups. */
    public void setEnv(TruffleLanguage.Env env) {
        this.env = env;
    }

    public TruffleLanguage.Env getEnv() {
        return env;
    }

    void setGuestNamespaceThreadLocal(ContextThreadLocal<AtomicReference<Namespace>> local) {
        this.guestNamespaceForThread = local;
    }

    /**
     * Called when Truffle first allows a second thread into this context. Vars / agent pools are
     * already thread-safe; this exists so the language hook has a place to flip representation later.
     */
    public void switchToMultiThreaded() {
    }

    /** Set once in {@link Clojure#createContext(TruffleLanguage.Env)} from the context's options. */
    public void setClearDeadLocals(boolean clearDeadLocals) {
        this.clearDeadLocals = clearDeadLocals;
    }

    public boolean clearDeadLocals() {
        return clearDeadLocals;
    }

    public void setGuestNamespaceForDebugger(Namespace ns) {
        ContextThreadLocal<AtomicReference<Namespace>> local = guestNamespaceForThread;
        if (local != null) {
            try {
                AtomicReference<Namespace> ref = local.get();
                if (ref != null) {
                    ref.set(ns);
                }
            } catch (IllegalStateException ignored) {
                // Not entered on this thread; keep the fallback only.
            }
        }
        this.guestNamespaceForDebuggerFallback = ns;
    }

    public Namespace getGuestNamespaceForDebugger() {
        ContextThreadLocal<AtomicReference<Namespace>> local = guestNamespaceForThread;
        if (local != null) {
            try {
                AtomicReference<Namespace> ref = local.get();
                if (ref != null) {
                    Namespace ns = ref.get();
                    if (ns != null) {
                        return ns;
                    }
                }
            } catch (IllegalStateException ignored) {
                // Tooling thread: use last guest snapshot.
            }
        }
        return guestNamespaceForDebuggerFallback;
    }
}
