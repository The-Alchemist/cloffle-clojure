package net.javacrumbs.cloffle.nodes.value;

import clojure.lang.IPersistentVector;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

@ExportLibrary(InteropLibrary.class)
public final class ClojureVector implements TruffleObject {

    private final IPersistentVector vector;

    public ClojureVector(IPersistentVector vector) {
        this.vector = vector;
    }

    public IPersistentVector getVector() {
        return vector;
    }

    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize() {
        return vector.count();
    }

    @ExportMessage
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < vector.count();
    }

    @ExportMessage
    Object readArrayElement(long index) throws InvalidArrayIndexException {
        if (!isArrayElementReadable(index)) {
            throw InvalidArrayIndexException.create(index);
        }
        return ClojureInterop.wrapForPolyglot(vector.nth((int) index));
    }

    @ExportMessage
    String toDisplayString(boolean allowSideEffects) {
        return vector.toString();
    }

    @Override
    public String toString() {
        return vector.toString();
    }
}
