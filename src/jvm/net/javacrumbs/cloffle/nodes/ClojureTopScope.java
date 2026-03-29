package net.javacrumbs.cloffle.nodes;

import clojure.lang.Namespace;
import clojure.lang.Symbol;
import clojure.lang.Var;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import net.javacrumbs.cloffle.Clojure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Exposes Clojure's global namespace/var bindings as a top-level scope
 * visible to debugger tools. This is returned by {@code TruffleLanguage.getScope()}.
 *
 * <p>Members are namespace-qualified var names from the current namespace
 * (e.g., "user/x", "user/my-fn"). The debugger can read their current values.
 */
@ExportLibrary(InteropLibrary.class)
public final class ClojureTopScope implements TruffleObject {

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
    @TruffleBoundary
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        try {
            Namespace ns = currentNamespace();
            return ns != null ? ns.getName().getName() : "Clojure";
        } catch (Exception e) {
            return "Clojure";
        }
    }

    @ExportMessage
    boolean hasMembers() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object getMembers(@SuppressWarnings("unused") boolean includeInternal) {
        List<String> names = new ArrayList<>();
        try {
            Namespace ns = currentNamespace();
            if (ns != null) {
                for (Object entry : ns.getMappings()) {
                    if (entry instanceof Map.Entry<?, ?> e && e.getValue() instanceof Var v) {
                        if (v.ns == ns && v.isBound()) {
                            names.add(v.sym.getName());
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new VarNamesArray(names.toArray(new String[0]));
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberReadable(String member) {
        return findVar(member) != null;
    }

    @ExportMessage
    @TruffleBoundary
    Object readMember(String member) throws UnknownIdentifierException {
        Var v = findVar(member);
        if (v == null) {
            throw UnknownIdentifierException.create(member);
        }
        Object val = v.deref();
        return val != null ? val : ClojureScope.NullValue.INSTANCE;
    }

    @ExportMessage
    @SuppressWarnings("static-method")
    boolean isMemberInsertable(@SuppressWarnings("unused") String member) {
        return false;
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberModifiable(String member) {
        return findVar(member) != null;
    }

    @ExportMessage
    @TruffleBoundary
    void writeMember(String member, Object value)
            throws UnknownIdentifierException, UnsupportedMessageException {
        Var v = findVar(member);
        if (v == null) {
            throw UnknownIdentifierException.create(member);
        }
        v.set(value);
    }

    @TruffleBoundary
    private static Namespace currentNamespace() {
        try {
            Var nsVar = Var.find(Symbol.intern("clojure.core", "*ns*"));
            if (nsVar != null) {
                Object ns = nsVar.deref();
                if (ns instanceof Namespace) {
                    return (Namespace) ns;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @TruffleBoundary
    private Var findVar(String name) {
        try {
            Namespace ns = currentNamespace();
            if (ns != null) {
                Var v = ns.findInternedVar(Symbol.intern(name));
                if (v != null && v.isBound()) {
                    return v;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @ExportLibrary(InteropLibrary.class)
    static final class VarNamesArray implements TruffleObject {
        private final String[] names;

        VarNamesArray(String[] names) {
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
