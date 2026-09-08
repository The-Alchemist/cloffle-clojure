package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Keyword;
import clojure.lang.Symbol;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;

final class BytecodeStrings {
    private BytecodeStrings() {
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

    static String otherToString(Object o) {
        return o.toString();
    }
}
