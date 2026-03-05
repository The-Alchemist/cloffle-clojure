package net.javacrumbs.cloffle.nodes.value;

import clojure.lang.IFn;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

@ExportLibrary(InteropLibrary.class)
public final class ClojureFunction implements TruffleObject {

    private final IFn fn;

    public ClojureFunction(IFn fn) {
        this.fn = fn;
    }

    public IFn getFn() {
        return fn;
    }

    @ExportMessage
    boolean isExecutable() {
        return true;
    }

    @ExportMessage
    Object execute(Object... args) throws UnsupportedTypeException, ArityException, UnsupportedMessageException {
        Object[] unwrapped = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            unwrapped[i] = ClojureInterop.unwrapFromPolyglot(args[i]);
        }
        Object result = fn.applyTo(clojure.lang.RT.seq(unwrapped));
        return ClojureInterop.wrapForPolyglot(result);
    }

    @ExportMessage
    String toDisplayString(boolean allowSideEffects) {
        return fn.toString();
    }

    @Override
    public String toString() {
        return fn.toString();
    }
}
