package net.javacrumbs.cloffle.nodes.value;

import clojure.lang.Var;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * Wraps a clojure.lang.Var so it can cross the Truffle polyglot boundary.
 * Mirrors Clojure semantics: (def x 42) returns #'ns/x.
 */
@ExportLibrary(InteropLibrary.class)
public final class VarValue implements TruffleObject {

    private final Var var;

    public VarValue(Var var) {
        this.var = var;
    }

    public Var getVar() {
        return var;
    }

    @ExportMessage
    boolean hasMembers() {
        return false;
    }

    @ExportMessage
    Object getMembers(boolean includeInternal) {
        return new String[0];
    }

    @ExportMessage
    String toDisplayString(boolean allowSideEffects) {
        return var.toString();
    }

    @Override
    public String toString() {
        return var.toString();
    }
}
