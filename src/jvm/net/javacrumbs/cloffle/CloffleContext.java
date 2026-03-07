package net.javacrumbs.cloffle;

/**
 * Language context that persists across evaluations within a single
 * Polyglot Context. Var bindings are managed by Clojure's Var system
 * (Var.bindRoot / Var.deref) -- this context only holds the Truffle
 * language reference needed for RootNode construction.
 */
public class CloffleContext {

    private com.oracle.truffle.api.TruffleLanguage<?> language;

    public void setLanguage(com.oracle.truffle.api.TruffleLanguage<?> language) {
        this.language = language;
    }

    public com.oracle.truffle.api.TruffleLanguage<?> language() {
        return language;
    }
}
