package net.javacrumbs.cloffle.nodes.value;

import clojure.lang.Symbol;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

@ExportLibrary(InteropLibrary.class)
public final class ClojureSymbol implements TruffleObject {

    private final Symbol symbol;

    public ClojureSymbol(Symbol symbol) {
        this.symbol = symbol;
    }

    public Symbol getSymbol() {
        return symbol;
    }

    @ExportMessage
    boolean isString() {
        return true;
    }

    @ExportMessage
    String asString() {
        return symbol.toString();
    }

    @ExportMessage
    String toDisplayString(boolean allowSideEffects) {
        return symbol.toString();
    }

    @Override
    public String toString() {
        return symbol.toString();
    }
}
