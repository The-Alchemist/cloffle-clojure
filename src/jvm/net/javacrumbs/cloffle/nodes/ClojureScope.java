package net.javacrumbs.cloffle.nodes;

import clojure.lang.Compiler.LocalBinding;
import clojure.lang.Var;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
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
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes Clojure local variables (let bindings, fn params, loop bindings)
 * to the Truffle debugger via the InteropLibrary scope protocol.
 *
 * <p>When a debugger suspends execution, it calls {@code NodeLibrary.getScope()}
 * which returns an instance of this class. The debugger then uses
 * {@code getMembers()}, {@code readMember()}, etc. to inspect local variables.
 */
@ExportLibrary(InteropLibrary.class)
public final class ClojureScope implements TruffleObject {

    private final Frame frame;
    private final RootNode rootNode;

    public ClojureScope(Frame frame, RootNode rootNode) {
        this.frame = frame;
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
        return "Clojure";
    }

    @ExportMessage
    boolean hasMembers() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object getMembers(@SuppressWarnings("unused") boolean includeInternal) {
        Map<String, Integer> vars = collectVariables();
        return new VariableNamesArray(vars.keySet().toArray(new String[0]));
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberReadable(String member) {
        return collectVariables().containsKey(member);
    }

    @ExportMessage
    @TruffleBoundary
    Object readMember(String member) throws UnknownIdentifierException {
        Map<String, Integer> vars = collectVariables();
        Integer slot = vars.get(member);
        if (slot == null) {
            throw UnknownIdentifierException.create(member);
        }
        if (frame == null) {
            return NullValue.INSTANCE;
        }
        Object val = frame.getValue(slot);
        if (val == null) {
            return NullValue.INSTANCE;
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
        return frame != null && collectVariables().containsKey(member);
    }

    @ExportMessage
    @TruffleBoundary
    void writeMember(String member, Object value)
            throws UnknownIdentifierException, UnsupportedMessageException {
        Map<String, Integer> vars = collectVariables();
        Integer slot = vars.get(member);
        if (slot == null) {
            throw UnknownIdentifierException.create(member);
        }
        if (frame == null) {
            throw UnsupportedMessageException.create();
        }
        frame.setObject(slot, value);
    }

    /**
     * Collects variables from frame descriptor slots. Uses a LinkedHashMap to
     * preserve declaration order and deduplicate (last writer wins for shadowed names).
     */
    @TruffleBoundary
    private Map<String, Integer> collectVariables() {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (frame == null && rootNode == null) {
            return result;
        }

        FrameDescriptor fd;
        if (frame != null) {
            fd = frame.getFrameDescriptor();
        } else {
            fd = rootNode.getFrameDescriptor();
        }

        int slotCount = fd.getNumberOfSlots();
        for (int i = 0; i < slotCount; i++) {
            // Bytecode interpreter stores the current bytecode index in frame slot 0 (int).
            if (rootNode instanceof CloffleBytecodeRootNode && i == 0) {
                continue;
            }
            Object slotName = fd.getSlotName(i);
            String name = slotDisplayName(slotName);
            if (name != null) {
                // List every named slot even when the value is null (Clojure nil, or uninitialized);
                // readMember returns NullValue so the Variables panel can show them.
                result.put(name, i);
            }
        }
        return result;
    }

    /**
     * Human-readable debugger name for a frame slot / bytecode local name object.
     * Slot names are either {@link LocalBinding} (for locals/params) or
     * {@link Var} (for var references used by InvokeNode) — vars are hidden from scope lists.
     */
    @TruffleBoundary
    public static String slotDisplayName(Object slotName) {
        if (slotName instanceof LocalBinding lb) {
            if (lb.sym != null) {
                return lb.sym.getName();
            }
            if (lb.name != null) {
                return lb.name;
            }
            return null;
        }
        if (slotName instanceof Var v) {
            return null;
        }
        if (slotName instanceof String s) {
            return s;
        }
        if (slotName instanceof clojure.lang.Symbol sym) {
            return sym.getName();
        }
        return null;
    }

    /**
     * Represents the nil/null value for scope variable display.
     */
    @ExportLibrary(InteropLibrary.class)
    public static final class NullValue implements TruffleObject {
        public static final NullValue INSTANCE = new NullValue();

        @ExportMessage
        boolean isNull() {
            return true;
        }

        /**
         * Debugger / DAP uses display string for variable values; without this, clients fall back to
         * {@link Object#toString()} and show {@code ClojureScope$NullValue@…}.
         */
        @ExportMessage
        @TruffleBoundary
        Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
            return "nil";
        }

        @Override
        public String toString() {
            return "nil";
        }
    }

    /**
     * An interop array of variable name strings.
     */
    @ExportLibrary(InteropLibrary.class)
    static final class VariableNamesArray implements TruffleObject {
        private final String[] names;

        VariableNamesArray(String[] names) {
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
