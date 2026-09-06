package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Keyword;
import clojure.lang.Named;
import clojure.lang.Symbol;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;

final class BytecodeStrings {
    private BytecodeStrings() {
    }

    static boolean isNamed(Object o) {
        return o instanceof Named;
    }

    static boolean isString(Object o) {
        return o instanceof String;
    }

    static boolean isKeyword(Object o) {
        return o instanceof Keyword;
    }

    static boolean isSymbol(Object o) {
        return o instanceof Symbol;
    }

    static boolean isNullLike(Object o) {
        return o == null
                || (o instanceof TruffleObject to && InteropLibrary.getUncached().isNull(to));
    }

    static String coreStrValue(Object value) {
        // Keyword and Symbol implement TruffleObject but are never interop null, so they are
        // matched ahead of the uncached interop probe below.
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof Keyword keyword) {
            return keyword.toString();
        }
        if (value instanceof Symbol symbol) {
            return symbol.toString();
        }
        if (isNullLike(value)) {
            return "";
        }
        return value.toString();
    }

    static String namedName(Object o) {
        return ((Named) o).getName();
    }

    static String namedNamespace(Object o) {
        return ((Named) o).getNamespace();
    }

    static String namedCastError(Object o) {
        throw new ClassCastException(o.getClass().getName() + " cannot be cast to clojure.lang.Named");
    }

    static String otherToString(Object o) {
        return o.toString();
    }

    static String substring1(String s) {
        return s.substring(1);
    }

    static String substring1Other(Object o) {
        String s = isNullLike(o) ? "" : o.toString();
        return s.substring(1);
    }
}
