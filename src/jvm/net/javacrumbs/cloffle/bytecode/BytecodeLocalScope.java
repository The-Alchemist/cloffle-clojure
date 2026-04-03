package net.javacrumbs.cloffle.bytecode;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.LocalVariable;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import net.javacrumbs.cloffle.Clojure;
import net.javacrumbs.cloffle.nodes.ClojureScope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Debugger scope for bytecode roots: {@link com.oracle.truffle.api.interop.NodeLibrary#getScope} supplies a plain
 * {@link Frame} (not {@link com.oracle.truffle.api.frame.MaterializedFrame}) valid only while execution is suspended;
 * this class introspects that same frame via {@link BytecodeNode} local APIs at the current BCI — {@link
 * BytecodeNode#getLocalCount(int)}, {@link BytecodeNode#getLocalNames(int)}, {@link BytecodeNode#getLocalValue(int,
 * Frame, int)}, {@link BytecodeNode#setLocalValue(int, Frame, int, Object)} — matching the Bytecode DSL default path
 * ({@code TagTreeNode.createDefaultScope} / {@code DefaultBytecodeScope}). Ordinal indices from {@code getLocalNames}
 * are mapped through {@link LocalVariable#getLocalOffset()} where block scoping requires it. A direct-slot fallback
 * complements {@code getLocalValue} when generated access only covers object slots.
 * <p>
 * For snapshot / off-thread / out-of-suspend inspection, use bytecode snapshot APIs ({@code BytecodeFrame},
 * {@link BytecodeNode#getLocalValues(com.oracle.truffle.api.frame.FrameInstance)}), not this scope.
 * <p>
 * Debug names from {@link CloffleBytecodeRootNode#getBytecodeLocalOffsetDebugNames()} not covered by live ordinals use
 * negative map keys {@code -(offset + 1)} and resolve through the same bytecode offset + frame reads.
 */
@ExportLibrary(InteropLibrary.class)
public final class BytecodeLocalScope implements TruffleObject {

    /** Truffle Bytecode frame: slot 0 holds current BCI; user locals start at index 1. */
    private static final int USER_LOCAL_FRAME_BASE = 1;

    private final Frame frame;
    private final BytecodeNode bytecodeNode;
    private final int bytecodeIndex;
    private final RootNode rootNode;

    public BytecodeLocalScope(Frame frame, BytecodeNode bytecodeNode, int bytecodeIndex, RootNode rootNode) {
        this.frame = frame;
        this.bytecodeNode = bytecodeNode;
        this.bytecodeIndex = bytecodeIndex;
        this.rootNode = rootNode;
    }

    @ExportMessage
    boolean isScope() {
        return true;
    }

    @ExportMessage
    boolean hasLanguage() {
        return true;
    }

    @ExportMessage
    Class<? extends TruffleLanguage<?>> getLanguage() {
        return Clojure.class;
    }

    @ExportMessage
    boolean hasSourceLocation() {
        return rootNode != null && rootNode.getSourceSection() != null;
    }

    @ExportMessage
    @TruffleBoundary
    SourceSection getSourceLocation() throws UnsupportedMessageException {
        if (rootNode != null && rootNode.getSourceSection() != null) {
            return rootNode.getSourceSection();
        }
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    @TruffleBoundary
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        if (rootNode != null && rootNode.getName() != null) {
            return rootNode.getName();
        }
        return "Clojure bytecode";
    }

    @ExportMessage
    boolean hasMembers() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object getMembers(@SuppressWarnings("unused") boolean includeInternal) {
        return new NameArray(collectNameToIndex().keySet().toArray(new String[0]));
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberReadable(String member) {
        return collectNameToIndex().containsKey(member);
    }

    @ExportMessage
    @TruffleBoundary
    Object readMember(String member) throws UnknownIdentifierException {
        Integer idx = collectNameToIndex().get(member);
        if (idx == null) {
            throw UnknownIdentifierException.create(member);
        }
        if (frame == null) {
            return ClojureScope.NullValue.INSTANCE;
        }
        Object val = readLocalSlot(idx);
        if (val == null) {
            return ClojureScope.NullValue.INSTANCE;
        }
        return val;
    }

    @ExportMessage
    @SuppressWarnings("static-method")
    boolean isMemberInsertable(@SuppressWarnings("unused") String member) {
        return false;
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberModifiable(String member) {
        return frame != null && collectNameToIndex().containsKey(member);
    }

    @ExportMessage
    @TruffleBoundary
    void writeMember(String member, Object value)
            throws UnknownIdentifierException, UnsupportedMessageException {
        Integer idx = collectNameToIndex().get(member);
        if (idx == null) {
            throw UnknownIdentifierException.create(member);
        }
        if (frame == null) {
            throw UnsupportedMessageException.create();
        }
        if (idx < 0) {
            writeBytecodeLocalOffset(-idx - 1, value);
            return;
        }
        int lc = bytecodeNode.getLocalCount(bytecodeIndex);
        if (lc > 0 && idx < lc) {
            int off = localOffsetForOrdinal(idx);
            if (off >= 0) {
                bytecodeNode.setLocalValue(bytecodeIndex, frame, off, value);
            } else {
                writePhysicalSlot(idx, value);
            }
        } else {
            writePhysicalSlot(idx, value);
        }
    }

    /** {@code setLocalValue} at Truffle bytecode local offset (from debug map / {@link LocalVariable#getLocalOffset()}). */
    private void writeBytecodeLocalOffset(int bytecodeLocalOffset, Object value) throws UnsupportedMessageException {
        int lc = bytecodeNode.getLocalCount(bytecodeIndex);
        if (bytecodeLocalOffset >= 0 && bytecodeLocalOffset < lc) {
            bytecodeNode.setLocalValue(bytecodeIndex, frame, bytecodeLocalOffset, value);
            return;
        }
        writePhysicalSlot(bytecodeLocalOffset, value);
    }

    private void writePhysicalSlot(int physicalOffset, Object value) throws UnsupportedMessageException {
        int fi = USER_LOCAL_FRAME_BASE + physicalOffset;
        if (frame.isObject(fi)) {
            frame.setObject(fi, value);
        } else if (frame.isLong(fi) && value instanceof Number n) {
            frame.setLong(fi, n.longValue());
        } else {
            throw UnsupportedMessageException.create();
        }
    }

    /**
     * Negative {@code idx} means physical local offset {@code -idx - 1} (debug map / frame direct read). Non-negative
     * {@code idx} with live locals uses {@link BytecodeNode#getLocalValue}; legacy positive physical indices are no
     * longer used (debug-only slots always use negative encoding).
     */
    @TruffleBoundary
    private Object readLocalSlot(int idx) {
        if (idx < 0) {
            return readPhysicalSlot(-idx - 1);
        }
        int lc = bytecodeNode.getLocalCount(bytecodeIndex);
        if (lc > 0 && idx < lc) {
            int off = localOffsetForOrdinal(idx);
            if (off >= 0) {
                return readLocalAtBytecodeOffset(off);
            }
        }
        return readPhysicalSlot(idx);
    }

    /**
     * Live locals at {@link #bytecodeIndex}, in the same order as {@link BytecodeNode#getLocalCount(int)} /
     * {@link BytecodeNode#getLocalNames(int)} (table iteration order).
     */
    private List<LocalVariable> liveLocalsAtBci() {
        int lc = bytecodeNode.getLocalCount(bytecodeIndex);
        List<LocalVariable> liveAtBci = new ArrayList<>(lc);
        for (LocalVariable lv : bytecodeNode.getLocals()) {
            if (bytecodeIndex >= lv.getStartIndex() && bytecodeIndex < lv.getEndIndex()) {
                liveAtBci.add(lv);
            }
        }
        return liveAtBci;
    }

    /**
     * Maps ordinal {@code i} (index into {@link BytecodeNode#getLocalNames(int)}) to Truffle
     * {@link LocalVariable#getLocalOffset()} for {@link BytecodeNode#getLocalValue(int, Frame, int)} — under block
     * scoping this need not equal {@code i}.
     */
    private int localOffsetForOrdinal(int ordinal) {
        List<LocalVariable> live = liveLocalsAtBci();
        if (ordinal < 0 || ordinal >= live.size()) {
            return -1;
        }
        return live.get(ordinal).getLocalOffset();
    }

    /**
     * {@link BytecodeNode#getLocalValue} on the {@linkplain #frame scope frame}, then typed reads on that same frame
     * (generated {@code getLocalValue} may only use {@link Frame#getObject} for a local).
     */
    private Object readLocalAtBytecodeOffset(int localOffset) {
        Object v = bytecodeNode.getLocalValue(bytecodeIndex, frame, localOffset);
        if (v != null) {
            return v;
        }
        return readPrimitiveOrObjectAtLocalOffset(frame, localOffset);
    }

    private static Object readPrimitiveOrObjectAtLocalOffset(Frame f, int localOffset) {
        int fi = USER_LOCAL_FRAME_BASE + localOffset;
        try {
            if (f.isObject(fi)) {
                return f.getObject(fi);
            }
            if (f.isLong(fi)) {
                return f.getLong(fi);
            }
            if (f.isInt(fi)) {
                return f.getInt(fi);
            }
            if (f.isDouble(fi)) {
                return f.getDouble(fi);
            }
            if (f.isFloat(fi)) {
                return f.getFloat(fi);
            }
            if (f.isBoolean(fi)) {
                return f.getBoolean(fi);
            }
            if (f.isByte(fi)) {
                return f.getByte(fi);
            }
        } catch (FrameSlotTypeException e) {
            return null;
        }
        return null;
    }

    /**
     * Reads a local by Truffle bytecode local offset ({@link LocalVariable#getLocalOffset()} /
     * {@link com.oracle.truffle.api.bytecode.BytecodeLocal#getLocalOffset()}). Debugger frames may reject
     * {@link Frame#getValue}; prefer {@link #readLocalAtBytecodeOffset}.
     */
    private Object readPhysicalSlot(int bytecodeLocalOffset) {
        for (LocalVariable lv : liveLocalsAtBci()) {
            if (lv.getLocalOffset() == bytecodeLocalOffset) {
                return readLocalAtBytecodeOffset(bytecodeLocalOffset);
            }
        }
        int fi = USER_LOCAL_FRAME_BASE + bytecodeLocalOffset;
        try {
            return frame.getValue(fi);
        } catch (FrameSlotTypeException e) {
            return null;
        }
    }

    @TruffleBoundary
    private Map<String, Integer> collectNameToIndex() {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (frame == null || bytecodeNode == null || bytecodeIndex < 0) {
            return result;
        }
        int localCount = bytecodeNode.getLocalCount(bytecodeIndex);
        List<LocalVariable> liveAtBci = liveLocalsAtBci();
        CloffleBytecodeRootNode cloffleRoot = rootNode instanceof CloffleBytecodeRootNode r ? r : null;
        Object[] rawNames = bytecodeNode.getLocalNames(bytecodeIndex);
        for (int i = 0; i < localCount; i++) {
            Object raw = null;
            if (rawNames != null && i < rawNames.length) {
                raw = rawNames[i];
            } else {
                raw = bytecodeNode.getLocalName(bytecodeIndex, i);
            }
            String name = ClojureScope.slotDisplayName(raw);
            if (name == null && raw instanceof String s && !s.isEmpty()) {
                name = s;
            }
            if (name == null && cloffleRoot != null) {
                if (i < liveAtBci.size()) {
                    name = cloffleRoot.getBytecodeLocalOffsetDebugName(liveAtBci.get(i).getLocalOffset());
                }
                if (name == null) {
                    name = cloffleRoot.getBytecodeLocalOffsetDebugName(i);
                }
            }
            if (name != null) {
                result.put(name, i);
            }
        }
        if (cloffleRoot != null) {
            Map<Integer, String> debug = cloffleRoot.getBytecodeLocalOffsetDebugNames();
            if (!debug.isEmpty()) {
                List<Map.Entry<Integer, String>> entries = new ArrayList<>(debug.entrySet());
                entries.sort(Comparator.comparingInt(Map.Entry::getKey));
                for (Map.Entry<Integer, String> e : entries) {
                    String n = e.getValue();
                    if (n == null || n.isEmpty() || result.containsKey(n)) {
                        continue;
                    }
                    int phys = e.getKey();
                    // Negative index: physical offset (not an ordinal into getLocalValue). Include even when
                    // isObject/isLong is false — bytecode frames often use static tags; getValue still reads.
                    result.put(n, -(phys + 1));
                }
            }
        }
        return result;
    }

    @ExportLibrary(InteropLibrary.class)
    static final class NameArray implements TruffleObject {
        private final String[] names;

        NameArray(String[] names) {
            this.names = names;
        }

        @ExportMessage
        boolean hasArrayElements() {
            return true;
        }

        @ExportMessage
        long getArraySize() {
            return names.length;
        }

        @ExportMessage
        boolean isArrayElementReadable(long index) {
            return index >= 0 && index < names.length;
        }

        @ExportMessage
        Object readArrayElement(long index) throws InvalidArrayIndexException {
            if (!isArrayElementReadable(index)) {
                throw InvalidArrayIndexException.create(index);
            }
            return names[(int) index];
        }
    }
}
