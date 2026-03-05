package net.javacrumbs.cloffle.nodes.value;

import clojure.lang.IPersistentList;
import clojure.lang.Counted;
import clojure.lang.ISeq;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

@ExportLibrary(InteropLibrary.class)
public final class ClojureList implements TruffleObject {

    private final IPersistentList list;

    public ClojureList(IPersistentList list) {
        this.list = list;
    }

    public IPersistentList getList() {
        return list;
    }

    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize() {
        return list.count();
    }

    @ExportMessage
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < list.count();
    }

    @ExportMessage
    Object readArrayElement(long index) throws InvalidArrayIndexException {
        if (!isArrayElementReadable(index)) {
            throw InvalidArrayIndexException.create(index);
        }
        ISeq seq = list.seq();
        for (long i = 0; i < index; i++) {
            seq = seq.next();
        }
        return ClojureInterop.wrapForPolyglot(seq.first());
    }

    @ExportMessage
    String toDisplayString(boolean allowSideEffects) {
        return list.toString();
    }

    @Override
    public String toString() {
        return list.toString();
    }
}
