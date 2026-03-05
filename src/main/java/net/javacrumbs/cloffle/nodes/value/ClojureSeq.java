package net.javacrumbs.cloffle.nodes.value;

import clojure.lang.ISeq;
import clojure.lang.RT;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * Wraps an ISeq (including LazySeq) for the polyglot boundary without
 * eagerly realizing it. Elements are only forced when accessed.
 */
@ExportLibrary(InteropLibrary.class)
public final class ClojureSeq implements TruffleObject {

    private final ISeq seq;

    public ClojureSeq(ISeq seq) {
        this.seq = seq;
    }

    public ISeq getSeq() {
        return seq;
    }

    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize() {
        return RT.count(seq);
    }

    @ExportMessage
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < RT.count(seq);
    }

    @ExportMessage
    Object readArrayElement(long index) throws InvalidArrayIndexException {
        if (index < 0) {
            throw InvalidArrayIndexException.create(index);
        }
        ISeq s = seq;
        for (long i = 0; i < index && s != null; i++) {
            s = s.next();
        }
        if (s == null) {
            throw InvalidArrayIndexException.create(index);
        }
        return ClojureInterop.wrapForPolyglot(s.first());
    }

    @ExportMessage
    String toDisplayString(boolean allowSideEffects) {
        if (!allowSideEffects) {
            return "clojure.lang.ISeq";
        }
        return seq.toString();
    }

    @Override
    public String toString() {
        return seq.toString();
    }
}
