package net.javacrumbs.cloffle.nodes.value;

import clojure.lang.Keyword;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

@ExportLibrary(InteropLibrary.class)
public final class ClojureKeyword implements TruffleObject {

    private final Keyword keyword;

    public ClojureKeyword(Keyword keyword) {
        this.keyword = keyword;
    }

    public Keyword getKeyword() {
        return keyword;
    }

    @ExportMessage
    boolean isString() {
        return true;
    }

    @ExportMessage
    String asString() {
        return keyword.toString();
    }

    @ExportMessage
    String toDisplayString(boolean allowSideEffects) {
        return keyword.toString();
    }

    @Override
    public String toString() {
        return keyword.toString();
    }
}
