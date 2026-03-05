package net.javacrumbs.cloffle.nodes.value;

import clojure.lang.IPersistentSet;
import clojure.lang.ISeq;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

@ExportLibrary(InteropLibrary.class)
public final class ClojureSet implements TruffleObject {

    private final IPersistentSet set;

    public ClojureSet(IPersistentSet set) {
        this.set = set;
    }

    public IPersistentSet getSet() {
        return set;
    }

    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize() {
        return set.count();
    }

    @ExportMessage
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < set.count();
    }

    @ExportMessage
    Object readArrayElement(long index) throws InvalidArrayIndexException {
        if (!isArrayElementReadable(index)) {
            throw InvalidArrayIndexException.create(index);
        }
        ISeq seq = set.seq();
        for (long i = 0; i < index; i++) {
            seq = seq.next();
        }
        return ClojureInterop.wrap(seq.first());
    }

    @ExportMessage
    String toDisplayString(boolean allowSideEffects) {
        return set.toString();
    }

    @Override
    public String toString() {
        return set.toString();
    }
}
