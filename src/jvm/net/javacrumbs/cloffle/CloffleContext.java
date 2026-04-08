package net.javacrumbs.cloffle;

import com.oracle.truffle.api.TruffleLanguage;

/**
 * Language context that persists across evaluations within a single
 * Polyglot Context. Var bindings are managed by Clojure's Var system
 * (Var.bindRoot / Var.deref) -- this context only holds the Truffle
 * language reference needed for RootNode construction.
 */
public class CloffleContext {

    private TruffleLanguage<?> language;
    private TruffleLanguage.Env env;

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
}
