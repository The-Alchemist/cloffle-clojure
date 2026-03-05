package net.javacrumbs.cloffle.nodes.value;

import clojure.lang.IPersistentMap;
import clojure.lang.IMapEntry;
import clojure.lang.ISeq;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownKeyException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

@ExportLibrary(InteropLibrary.class)
public final class ClojureMap implements TruffleObject {

    private final IPersistentMap map;

    public ClojureMap(IPersistentMap map) {
        this.map = map;
    }

    public IPersistentMap getMap() {
        return map;
    }

    @ExportMessage
    boolean hasHashEntries() {
        return true;
    }

    @ExportMessage
    long getHashSize() {
        return map.count();
    }

    @ExportMessage
    boolean isHashEntryReadable(Object key) {
        Object unwrapped = ClojureInterop.unwrap(key);
        return map.containsKey(unwrapped);
    }

    @ExportMessage
    Object readHashValue(Object key) throws UnknownKeyException {
        Object unwrapped = ClojureInterop.unwrap(key);
        if (!map.containsKey(unwrapped)) {
            throw UnknownKeyException.create(key);
        }
        return ClojureInterop.wrap(map.valAt(unwrapped));
    }

    @ExportMessage
    Object readHashValueOrDefault(Object key, Object defaultValue) {
        Object unwrapped = ClojureInterop.unwrap(key);
        Object val = map.valAt(unwrapped);
        return val != null ? ClojureInterop.wrap(val) : defaultValue;
    }

    @ExportMessage
    Object getHashEntriesIterator() {
        return new ClojureMapIterator(map.seq());
    }

    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize() {
        return map.count();
    }

    @ExportMessage
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < map.count();
    }

    @ExportMessage
    Object readArrayElement(long index) throws com.oracle.truffle.api.interop.InvalidArrayIndexException {
        if (!isArrayElementReadable(index)) {
            throw com.oracle.truffle.api.interop.InvalidArrayIndexException.create(index);
        }
        ISeq seq = map.seq();
        for (long i = 0; i < index; i++) {
            seq = seq.next();
        }
        IMapEntry entry = (IMapEntry) seq.first();
        return new ClojureVector(clojure.lang.PersistentVector.create(entry.key(), entry.val()));
    }

    @ExportMessage
    String toDisplayString(boolean allowSideEffects) {
        return map.toString();
    }

    @Override
    public String toString() {
        return map.toString();
    }

    @ExportLibrary(InteropLibrary.class)
    static final class ClojureMapIterator implements TruffleObject {
        private ISeq seq;

        ClojureMapIterator(ISeq seq) {
            this.seq = seq;
        }

        @ExportMessage
        boolean isIterator() {
            return true;
        }

        @ExportMessage
        boolean hasIteratorNextElement() {
            return seq != null;
        }

        @ExportMessage
        Object[] getIteratorNextElement() throws com.oracle.truffle.api.interop.StopIterationException {
            if (seq == null) {
                throw com.oracle.truffle.api.interop.StopIterationException.create();
            }
            IMapEntry entry = (IMapEntry) seq.first();
            seq = seq.next();
            return new Object[]{ClojureInterop.wrap(entry.key()), ClojureInterop.wrap(entry.val())};
        }

        @ExportMessage
        String toDisplayString(boolean allowSideEffects) {
            return "ClojureMapIterator";
        }
    }
}
