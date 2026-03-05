package net.javacrumbs.cloffle.nodes.value;

import net.javacrumbs.cloffle.nodes.FnNode;

/**
 * Handles conversion at the Truffle polyglot boundary.
 * <p>
 * Most Clojure types (Keyword, Symbol, PersistentVector, PersistentHashMap,
 * PersistentHashSet, ASeq, LazySeq, AFn subclasses, Var) now implement
 * TruffleObject directly, so they pass through unchanged.
 * Only null, primitives, and FnNode need special handling.
 */
public final class ClojureInterop {

    private ClojureInterop() {}

    public static Object wrapForPolyglot(Object value) {
        if (value == null) {
            return NilNode.NIL;
        }
        if (value instanceof FnNode fnNode) {
            return fnNode.toIFn();
        }
        return value;
    }

    public static Object unwrapFromPolyglot(Object value) {
        if (value instanceof NilNode.Nil) {
            return null;
        }
        if (value instanceof FnNode fnNode) {
            return fnNode.toIFn();
        }
        return value;
    }
}
