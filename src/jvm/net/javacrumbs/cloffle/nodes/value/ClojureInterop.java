package net.javacrumbs.cloffle.nodes.value;

import com.oracle.truffle.api.interop.InteropLibrary;
import net.javacrumbs.cloffle.CloffleContext;
import net.javacrumbs.cloffle.Clojure;
import net.javacrumbs.cloffle.nodes.value.NilNode.Nil;

/**
 * Wraps Clojure values for Truffle {@link com.oracle.truffle.api.interop.InteropLibrary}
 * (debugger scopes, nested map/seq expansion, and optional host embedding).
 * <p>
 * Core Clojure types implement {@link com.oracle.truffle.api.interop.TruffleObject} with
 * exported interop messages; {@code null} is {@link NilNode#NIL}.
 */
public final class ClojureInterop {

    private ClojureInterop() {}

    public static Object wrapForPolyglot(Object value) {
        if (value == null) {
            return NilNode.NIL;
        }
        return wrapForInterop(value);
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
