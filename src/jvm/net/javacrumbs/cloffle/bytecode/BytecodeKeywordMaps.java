package net.javacrumbs.cloffle.bytecode;

import clojure.lang.ILookup;
import clojure.lang.Keyword;
import clojure.lang.RT;

final class BytecodeKeywordMaps {
    private BytecodeKeywordMaps() {
    }

    static boolean isILookup(Object obj) {
        return obj instanceof ILookup;
    }

    static Object lookupGeneric(Keyword keyword, Object target) {
        return RT.get(target, keyword);
    }

    static Object lookupGeneric(Keyword keyword, Object target, Object notFound) {
        return RT.get(target, keyword, notFound);
    }
}
