package net.javacrumbs.cloffle.bytecode;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.RootNode;
import clojure.lang.Namespace;
import clojure.lang.PersistentHashMap;
import clojure.lang.RT;
import clojure.lang.Var;
import net.javacrumbs.cloffle.nodes.ClojureClosure;

import java.util.HashMap;
import java.util.Map;

/**
 * Local-offset debug-name storage and resolution for {@link CloffleBytecodeRootNode}.
 * <p>
 * The names are keyed by physical local offset (third argument to
 * {@link com.oracle.truffle.api.bytecode.BytecodeNode#getLocalValue(int, com.oracle.truffle.api.frame.Frame, int)}),
 * i.e. {@link com.oracle.truffle.api.bytecode.BytecodeLocal#getLocalOffset()}. Filled by the emitter for params,
 * closure copies, and {@code let*} bindings so {@link BytecodeLocalScope} avoids
 * {@code Builder#createLocal(Object, Object)} (which shifts the locals table and breaks emitted code).
 * <p>
 * The map lives in {@link CloffleBytecodeRootNode#bytecodeLocalOffsetDebugNames} (non-transient, so roots stay
 * debuggable after bytecode serialization round-trips); this class only builds and reads it.
 */
final class BytecodeRootDebugNames {

    private BytecodeRootDebugNames() {
    }

    /**
     * Converts an emitter-supplied map into the form stored on the root: {@code null} when there is nothing to
     * store, otherwise a persistent map (IPersistentMap is supported by the bytecode serializer).
     */
    static Map<Integer, String> store(Map<Integer, String> names) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<Integer, String> persistent = (Map<Integer, String>) (Map<?, ?>) PersistentHashMap.create(new HashMap<>(names));
        return persistent;
    }

    /** Names stored directly on <em>this</em> root instance — no Var fallback. */
    static Map<Integer, String> direct(CloffleBytecodeRootNode self) {
        Map<Integer, String> local = self.bytecodeLocalOffsetDebugNames;
        return (local != null && !local.isEmpty()) ? local : Map.of();
    }

    /** Resolved view: the direct field if populated, otherwise the Var fallback. */
    static Map<Integer, String> resolve(CloffleBytecodeRootNode self) {
        Map<Integer, String> local = self.bytecodeLocalOffsetDebugNames;
        if (local != null && !local.isEmpty()) {
            return local;
        }
        Map<Integer, String> fromVar = fromVarByRootName(self);
        return fromVar.isEmpty() ? Map.of() : fromVar;
    }

    /** Resolved single-offset lookup: direct field first, then Var fallback. */
    static String resolveOne(CloffleBytecodeRootNode self, int localOffset) {
        Map<Integer, String> m = self.bytecodeLocalOffsetDebugNames;
        if (m != null) {
            String s = m.get(localOffset);
            if (s != null) {
                return s;
            }
        }
        return fromVarByRootName(self).get(localOffset);
    }

    /**
     * Best-effort fallback: look up the Var by root name in the current namespace, and if it
     * holds a {@link ClojureClosure} whose original root carries debug names, borrow them.
     * <p>
     * With the deferred-offset fix in {@code ExprToBytecode.registerSlotDebugName}, the direct
     * field should always be populated after parse (initial or reparse). This fallback exists
     * only as a safety net for edge cases (e.g. roots created by external tooling that bypass
     * the normal {@code ExprToBytecode} path).
     */
    @CompilerDirectives.TruffleBoundary
    static Map<Integer, String> fromVarByRootName(CloffleBytecodeRootNode self) {
        String name = self.getName();
        if (name == null
                || name.isEmpty()
                || "fn".equals(name)
                || "CloffleBytecodeRootNode".equals(name)) {
            return Map.of();
        }
        try {
            Object nsObj = RT.CURRENT_NS.deref();
            if (!(nsObj instanceof Namespace ns)) {
                return Map.of();
            }
            Var v = RT.var(ns.getName().getName(), name);
            if (!v.isBound()) {
                return Map.of();
            }
            Object fn = v.deref();
            if (fn instanceof ClojureClosure cc) {
                RootNode r = ((RootCallTarget) cc.getCallTarget()).getRootNode();
                if (r instanceof CloffleBytecodeRootNode other && other != self) {
                    Map<Integer, String> raw = other.bytecodeLocalOffsetDebugNames;
                    if (raw != null && !raw.isEmpty()) {
                        return raw;
                    }
                }
            }
        } catch (Throwable ignored) {
            // e.g. wrong language context or host interop
        }
        return Map.of();
    }
}
