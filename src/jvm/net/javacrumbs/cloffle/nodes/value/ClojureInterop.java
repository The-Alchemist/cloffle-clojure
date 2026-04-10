package net.javacrumbs.cloffle.nodes.value;

import net.javacrumbs.cloffle.nodes.value.NilNode.Nil;
/**
 * Handles conversion at the Truffle polyglot boundary.
 * <p>
 * Most Clojure types (Keyword, Symbol, PersistentVector, PersistentHashMap,
 * PersistentHashSet, ASeq, LazySeq, AFn subclasses, Var) now implement
 * TruffleObject directly, so they pass through unchanged.
 * Guest {@code nil} is represented as {@link NilNode#NIL} for interop.
 */
public final class ClojureInterop {

    private ClojureInterop() {}

    public static Object wrapForPolyglot(Object value) {
        if (value == null) {
            return NilNode.NIL;
        }
        return value;
    }

    public static Object unwrapFromPolyglot(Object value) {
        if (value instanceof Nil) {
            return null;
        }
        return value;
    }
}
