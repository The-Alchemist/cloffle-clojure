package net.javacrumbs.cloffle.nodes.value;

import com.oracle.truffle.api.interop.InteropLibrary;
import net.javacrumbs.cloffle.CloffleContext;
import net.javacrumbs.cloffle.Clojure;
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

    /**
     * Values returned by an InteropLibrary export must satisfy Truffle's interop value contract.
     * Clojure locals can contain arbitrary host objects, so debugger scopes must expose those
     * objects through the current environment's host wrapper.
     */
    public static Object wrapForInterop(Object value) {
        if (value == null) {
            return NilNode.NIL;
        }
        if (InteropLibrary.isValidValue(value)) {
            return value;
        }
        CloffleContext context = Clojure.getContext();
        if (context == null || context.getEnv() == null) {
            return String.valueOf(value);
        }
        return context.getEnv().asGuestValue(value);
    }

    public static Object unwrapFromPolyglot(Object value) {
        if (value instanceof Nil) {
            return null;
        }
        return value;
    }
}
