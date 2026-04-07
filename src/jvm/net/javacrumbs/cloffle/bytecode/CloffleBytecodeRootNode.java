package net.javacrumbs.cloffle.bytecode;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.bytecode.BytecodeLocation;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.bytecode.Variadic;
import net.javacrumbs.cloffle.Clojure;
import net.javacrumbs.cloffle.nodes.ClojureClosure;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;
import clojure.lang.IFn;
import clojure.lang.Namespace;
import clojure.lang.PersistentHashMap;
import clojure.lang.RT;
import clojure.lang.Var;

import com.oracle.truffle.api.RootCallTarget;

import java.util.HashMap;
import java.util.Map;

@GenerateBytecode(
    languageClass = Clojure.class,
    enableSerialization = true,
    enableMaterializedLocalAccesses = true,
    enableTagInstrumentation = true,
    storeBytecodeIndexInFrame = true,
    tagTreeNodeLibrary = CloffleBytecodeTagTreeNodeExports.class
)
public abstract class CloffleBytecodeRootNode extends RootNode implements BytecodeRootNode {

    protected String name = null;

    protected CloffleBytecodeRootNode(Clojure language, FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
    }

    @Override
    public String getName() {
        return name != null ? name : "CloffleBytecodeRootNode";
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * Debugger display names keyed by physical local offset (third argument to
     * {@link BytecodeNode#getLocalValue(int, com.oracle.truffle.api.frame.Frame, int)}), i.e.
     * {@link com.oracle.truffle.api.bytecode.BytecodeLocal#getLocalOffset()}. Filled by the emitter for params,
     * closure copies, and {@code let*} bindings so {@link BytecodeLocalScope} avoids
     * {@code Builder#createLocal(Object, Object)} (which shifts the locals table and breaks emitted code).
     * Non-transient so roots stay debuggable after bytecode serialization round-trips.
     */
    protected Map<Integer, String> bytecodeLocalOffsetDebugNames;

    public void setBytecodeLocalOffsetDebugNames(Map<Integer, String> names) {
        if (names == null || names.isEmpty()) {
            this.bytecodeLocalOffsetDebugNames = null;
            return;
        }
        // Keep this field serialization-friendly (IPersistentMap is supported by bytecode serializer).
        @SuppressWarnings("unchecked")
        Map<Integer, String> persistent = (Map<Integer, String>) (Map<?, ?>) PersistentHashMap.create(new HashMap<>(names));
        this.bytecodeLocalOffsetDebugNames = persistent;
    }

    /**
     * Returns the debug name map stored directly on <em>this</em> root instance — no Var fallback.
     * The primary data source; should be populated on every root including instrumented/reparsed ones.
     */
    public Map<Integer, String> getDirectBytecodeLocalOffsetDebugNames() {
        Map<Integer, String> local = bytecodeLocalOffsetDebugNames;
        return (local != null && !local.isEmpty()) ? local : Map.of();
    }

    /**
     * Resolved view used by {@link BytecodeLocalScope}: returns the direct field if populated,
     * otherwise falls back to the Var's original closure root via {@link #debugNamesFromVarByRootName}.
     */
    public Map<Integer, String> getBytecodeLocalOffsetDebugNames() {
        Map<Integer, String> local = bytecodeLocalOffsetDebugNames;
        if (local != null && !local.isEmpty()) {
            return local;
        }
        Map<Integer, String> fromVar = debugNamesFromVarByRootName(this);
        return fromVar.isEmpty() ? Map.of() : fromVar;
    }

    /** Resolved single-offset lookup: direct field first, then Var fallback. */
    public String getBytecodeLocalOffsetDebugName(int localOffset) {
        Map<Integer, String> m = bytecodeLocalOffsetDebugNames;
        if (m != null) {
            String s = m.get(localOffset);
            if (s != null) {
                return s;
            }
        }
        return debugNamesFromVarByRootName(this).get(localOffset);
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
    private static Map<Integer, String> debugNamesFromVarByRootName(CloffleBytecodeRootNode self) {
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

    /**
     * Bytecode operations throw {@link net.javacrumbs.cloffle.nodes.ClojureException} with {@code null}
     * {@link com.oracle.truffle.api.nodes.Node} location; attach the current instruction's
     * {@link SourceSection} so Polyglot and guest stack frames report line/column.
     */
    private static boolean hasPolyglotUsableExceptionLocation(net.javacrumbs.cloffle.nodes.ClojureException ce) {
        Node loc = ce.getLocation();
        if (loc == null) {
            return false;
        }
        SourceSection ss = loc.getSourceSection();
        if (ss == null || !ss.isAvailable() || !ss.hasLines() || ss.getStartLine() <= 0) {
            ss = loc.getEncapsulatingSourceSection();
        }
        return ss != null && ss.isAvailable() && ss.hasLines() && ss.getStartLine() > 0;
    }

    @Override
    public AbstractTruffleException interceptTruffleException(
            AbstractTruffleException ex,
            VirtualFrame frame,
            BytecodeNode bytecodeNode,
            int bytecodeIndex) {
        if (ex instanceof net.javacrumbs.cloffle.nodes.ClojureException ce
                && bytecodeNode != null) {
            SourceSection instrSS = resolveBytecodeSourceSection(bytecodeNode, bytecodeIndex);

            if (!hasPolyglotUsableExceptionLocation(ce)) {
                try {
                    if (instrSS != null && instrSS.isAvailable()) {
                        ce = net.javacrumbs.cloffle.nodes.ClojureException.withBytecodeSourceSection(ce, instrSS);
                    } else {
                        Node loc = bytecodeNode.getRootNode();
                        if (loc == null) loc = bytecodeNode;
                        ce = net.javacrumbs.cloffle.nodes.ClojureException.withLocationNode(ce, loc);
                    }
                } catch (Throwable ignored) {
                    Node loc = bytecodeNode.getRootNode();
                    if (loc == null) loc = bytecodeNode;
                    ce = net.javacrumbs.cloffle.nodes.ClojureException.withLocationNode(ce, loc);
                }
            }

            // Enriched frame tracking: add call-site source info so deep stacks show
            // all intermediate frames (parity with AST InvokeNode.invokeTruffleTarget).
            CompilerDirectives.transferToInterpreter();
            if (instrSS != null && instrSS.isAvailable() && instrSS.hasLines() && instrSS.getStartLine() > 0) {
                ce.addFrame(instrSS, this.name);
            }

            return ce;
        }
        return ex;
    }

    private static SourceSection resolveBytecodeSourceSection(BytecodeNode bytecodeNode, int bytecodeIndex) {
        try {
            if (bytecodeIndex >= 0) {
                bytecodeNode.ensureSourceInformation();
                SourceSection ss = bytecodeNode.getSourceLocation(bytecodeIndex);
                if (ss == null || !ss.isAvailable()) {
                    BytecodeLocation loc = BytecodeLocation.get(bytecodeNode, bytecodeIndex);
                    if (loc != null) {
                        loc = loc.ensureSourceInformation();
                        ss = loc.getSourceLocation();
                    }
                }
                if (ss != null && ss.isAvailable()) {
                    return ss;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Operation
    public static final class ReadVar {
        @Specialization
        public static Object doVar(clojure.lang.Var var) {
            return var.get();
        }
    }

    @Operation
    public static final class WriteVar {
        @Specialization
        public static Object doWrite(clojure.lang.Var var, Object value) {
            return var.set(value);
        }
    }

    @Operation
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = boolean.class, name = "initProvided")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = boolean.class, name = "isDynamic")
    public static final class DefVar {
        @Specialization
        public static Object doDef(boolean initProvided, boolean isDynamic, clojure.lang.Var var, Object value, Object meta) {
            if (initProvided) {
                var.bindRoot(value);
            }
            if (meta != null) {
                var.setMeta((clojure.lang.IPersistentMap) meta);
            }
            if (isDynamic)
                var.setDynamic();
            return var;
        }
    }

    @Operation
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "className")
    public static final class ImportClass {
        @Specialization
        public static Object doImport(String className) {
            try {
                Class<?> c = clojure.lang.RT.classForNameNonLoading(className);
                clojure.lang.Namespace ns = (clojure.lang.Namespace) clojure.lang.RT.CURRENT_NS.deref();
                ns.importClass(c);
                return null;
            } catch (net.javacrumbs.cloffle.nodes.ClojureException ce) {
                throw ce;
            } catch (com.oracle.truffle.api.exception.AbstractTruffleException ate) {
                throw ate;
            } catch (Exception e) {
                throw net.javacrumbs.cloffle.nodes.ClojureException.wrapReflective(e);
            }
        }
    }

    @Operation
    public static final class Truthiness {
        @Specialization
        public static boolean doObject(Object value) {
            return clojure.lang.RT.booleanCast(value);
        }
    }

    @Operation
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = int.class, name = "requiredArity")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = boolean.class, name = "isVariadic")
    public static final class CreateClosure {
        @Specialization
        public static Object doCreate(int requiredArity, boolean isVariadic,
                                      CloffleBytecodeRootNode targetNode, com.oracle.truffle.api.frame.MaterializedFrame frame) {
            return new net.javacrumbs.cloffle.nodes.ClojureClosure(targetNode.getCallTarget(), frame,
                    requiredArity, isVariadic);
        }
    }

    /**
     * First half of named-fn self-reference setup (mirrors {@link net.javacrumbs.cloffle.nodes.FnNode}:
     * install the closure into {@code thisLocal} on the live frame <em>before</em> materializing it).
     * Pair with {@link FinalizeClosureCapture}.
     */
    @Operation
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = int.class, name = "requiredArity")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = boolean.class, name = "isVariadic")
    public static final class CreateClosurePendingCapture {
        @Specialization
        public static Object doCreate(int requiredArity, boolean isVariadic, CloffleBytecodeRootNode targetNode) {
            return new ClojureClosure(targetNode.getCallTarget(), null, requiredArity, isVariadic);
        }
    }

    /**
     * After the pending closure is stored in the self slot, materialize the current frame and attach
     * it so recursive loads see the closure (same order as {@code FnNode.executeGeneric}).
     */
    @Operation
    public static final class FinalizeClosureCapture {
        @Specialization
        public static Object doFinalize(ClojureClosure closure, MaterializedFrame frame) {
            closure.setCapturedFrame(frame);
            return closure;
        }
    }

    @Operation
    public static final class GetOuterFrame {
        @Specialization
        public static com.oracle.truffle.api.frame.MaterializedFrame doGet(com.oracle.truffle.api.frame.VirtualFrame frame) {
            return frame.materialize();
        }
    }

    @Operation
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = int.class, name = "expectedCount")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = boolean.class, name = "isVariadic")
    public static final class CheckArity {
        @Specialization
        public static boolean doCheck(int expectedCount, boolean isVariadic, int argsCount) {
            if (isVariadic) {
                return argsCount >= expectedCount;
            } else {
                return argsCount == expectedCount;
            }
        }
    }

    @Operation
    public static final class GetArgCount {
        @Specialization
        public static int doCount(com.oracle.truffle.api.frame.VirtualFrame frame) {
            // The first argument is the closure frame, so subtract 1
            return frame.getArguments().length - 1;
        }
    }

    @Operation
    public static final class ThrowArity {
        @Specialization
        public static Object doThrow(int argCount, String name) {
            if (argCount >= 0) {
                var ae = new clojure.lang.ArityException(argCount, name);
                throw new net.javacrumbs.cloffle.nodes.ClojureException(ae.getMessage(), ae, null);
            }
            return null;
        }
    }

    @Operation
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = int.class, name = "reqArity")
    public static final class GetRestArgs {
        @Specialization
        public static Object doGet(com.oracle.truffle.api.frame.VirtualFrame frame, int reqArity) {
            Object[] args = frame.getArguments();
            int start = reqArity + 1; // +1 for closure frame
            if (start >= args.length) {
                return null;
            }
            int restCount = args.length - start;
            if (restCount == 1 && args[start] instanceof net.javacrumbs.cloffle.nodes.ClojureClosure.RestArgs ra) {
                return ra.seq != null ? ra.seq : null;
            }
            java.util.List<Object> rest = new java.util.ArrayList<>(restCount);
            for (int i = start; i < args.length; i++) {
                rest.add(args[i]);
            }
            return clojure.lang.RT.seq(rest);
        }
    }

    @Operation
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "catchClass")
    public static final class CheckCatch {
        @Specialization
        public static boolean doCheck(Object catchClass, Object exception) {
            Object unwrapped = exception;
            if (exception instanceof net.javacrumbs.cloffle.nodes.ClojureException ce) {
                Throwable cause = ce.getCause();
                while (cause instanceof net.javacrumbs.cloffle.nodes.ClojureException inner) {
                    cause = inner.getCause();
                }
                if (cause != null) unwrapped = cause;
            }
            return ((Class<?>) catchClass).isInstance(unwrapped);
        }
    }

    @Operation
    public static final class UnwrapException {
        @Specialization
        public static Object doUnwrap(Object exception) {
            if (exception instanceof net.javacrumbs.cloffle.nodes.ClojureException ce) {
                Throwable cause = ce.getCause();
                while (cause instanceof net.javacrumbs.cloffle.nodes.ClojureException inner) {
                    cause = inner.getCause();
                }
                if (cause != null) return cause;
            }
            return exception;
        }
    }

    @Operation
    public static final class ThrowException {
        @Specialization
        public static Object doThrow(Object exception) {
            if (exception instanceof Throwable t) {
                if (t instanceof net.javacrumbs.cloffle.nodes.ClojureException) {
                    throw (net.javacrumbs.cloffle.nodes.ClojureException) t;
                }
                throw net.javacrumbs.cloffle.nodes.ClojureException.wrap(t, null);
            } else {
                throw new RuntimeException("Thrown object is not a Throwable: " + exception);
            }
        }
    }
    @Operation
    public static final class ThrowArityException {
        @Specialization
        public static Object doThrow(int actual, String name) {
            throw new net.javacrumbs.cloffle.nodes.ClojureException(actual + " args", new clojure.lang.ArityException(actual, name == null ? "fn" : name), null);
        }
    }

    @Operation
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "targetClass")
    public static final class NewObject {
        @Specialization
        public static Object doNew(Object targetClass, @Variadic Object[] args) {
            try {
                return clojure.lang.Reflector.invokeConstructor((Class<?>) targetClass, unwrapArgsForReflect(args));
            } catch (net.javacrumbs.cloffle.nodes.ClojureException ce) {
                throw ce;
            } catch (com.oracle.truffle.api.exception.AbstractTruffleException ate) {
                throw ate;
            } catch (IllegalArgumentException iae) {
                if (iae.getMessage() != null && iae.getMessage().startsWith("Unexpected param type")) {
                    throw net.javacrumbs.cloffle.nodes.ClojureException.wrapReflective(
                        new ClassCastException(iae.getMessage()));
                }
                throw net.javacrumbs.cloffle.nodes.ClojureException.wrapReflective(iae);
            } catch (Exception e) {
                throw net.javacrumbs.cloffle.nodes.ClojureException.wrapReflective(e);
            }
        }
    }

    @Operation
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "methodName")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "resolvedMethod")
    public static final class InstanceMethod {
        @Specialization
        public static Object doInvoke(String methodName, Object resolvedMethod, Object instance, @Variadic Object[] args) {
            try {
                instance = unwrapForReflect(instance);
                args = unwrapArgsForReflect(args);
                if (resolvedMethod instanceof java.lang.reflect.Method m) {
                    Class<?> declClass = m.getDeclaringClass();
                    Object target = adaptFIInstance(declClass, instance);
                    if (target != null && !declClass.isInstance(target)) {
                        throw new ClassCastException(
                                (instance == null ? "null" : instance.getClass().getName())
                                        + " cannot be cast to "
                                        + declClass.getName());
                    }
                    try {
                        return clojure.lang.Reflector.prepRet(m.getReturnType(), m.invoke(target, clojure.lang.Reflector.boxArgs(m.getParameterTypes(), args)));
                    } catch (IllegalArgumentException iae) {
                        throw new ClassCastException(iae.getMessage());
                    }
                }
                return clojure.lang.Reflector.invokeInstanceMethod(instance, methodName, args);
            } catch (net.javacrumbs.cloffle.nodes.ClojureException ce) {
                throw ce;
            } catch (com.oracle.truffle.api.exception.AbstractTruffleException ate) {
                throw ate;
            } catch (Exception e) {
                throw net.javacrumbs.cloffle.nodes.ClojureException.wrapReflective(e);
            }
        }
    }

    @Operation
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "targetClass")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "fieldName")
    public static final class StaticField {
        @Specialization
        public static Object doGet(Object targetClass, String fieldName) {
            try {
                return clojure.lang.Reflector.getStaticField((Class<?>) targetClass, fieldName);
            } catch (net.javacrumbs.cloffle.nodes.ClojureException ce) {
                throw ce;
            } catch (com.oracle.truffle.api.exception.AbstractTruffleException ate) {
                throw ate;
            } catch (Exception e) {
                throw net.javacrumbs.cloffle.nodes.ClojureException.wrapReflective(e);
            }
        }
    }

    @Operation
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "targetClass")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "fieldName")
    public static final class SetStaticField {
        @Specialization
        public static Object doSet(Object targetClass, String fieldName, Object value) {
            try {
                return clojure.lang.Reflector.setStaticField((Class<?>) targetClass, fieldName, unwrapForReflect(value));
            } catch (net.javacrumbs.cloffle.nodes.ClojureException ce) {
                throw ce;
            } catch (com.oracle.truffle.api.exception.AbstractTruffleException ate) {
                throw ate;
            } catch (Exception e) {
                throw net.javacrumbs.cloffle.nodes.ClojureException.wrapReflective(e);
            }
        }
    }

    @Operation
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "fieldName")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = boolean.class, name = "requireField")
    public static final class InstanceField {
        @Specialization
        public static Object doGet(String fieldName, boolean requireField, Object instance) {
            try {
                instance = unwrapForReflect(instance);
                if (requireField) {
                    return clojure.lang.Reflector.getInstanceField(instance, fieldName);
                } else {
                    return clojure.lang.Reflector.invokeNoArgInstanceMember(instance, fieldName);
                }
            } catch (net.javacrumbs.cloffle.nodes.ClojureException ce) {
                throw ce;
            } catch (com.oracle.truffle.api.exception.AbstractTruffleException ate) {
                throw ate;
            } catch (Exception e) {
                throw net.javacrumbs.cloffle.nodes.ClojureException.wrapReflective(e);
            }
        }
    }

    @Operation
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "fieldName")
    public static final class SetInstanceField {
        @Specialization
        public static Object doSet(String fieldName, Object target, Object value) {
            try {
                return clojure.lang.Reflector.setInstanceField(
                        unwrapForReflect(target), fieldName, unwrapForReflect(value));
            } catch (net.javacrumbs.cloffle.nodes.ClojureException ce) {
                throw ce;
            } catch (com.oracle.truffle.api.exception.AbstractTruffleException ate) {
                throw ate;
            } catch (Exception e) {
                throw net.javacrumbs.cloffle.nodes.ClojureException.wrapReflective(e);
            }
        }
    }

    @Operation
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "targetClass")
    public static final class InstanceOf {
        @Specialization
        public static boolean doCheck(Object targetClass, Object instance) {
            return ((Class<?>) targetClass).isInstance(unwrapForReflect(instance));
        }
    }

    @Operation
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "targetClass")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "methodName")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "resolvedMethod")
    public static final class StaticMethod {
        @Specialization
        public static Object doInvoke(Object targetClass, String methodName, Object resolvedMethod, @Variadic Object[] args) {
            try {
                args = unwrapArgsForReflect(args);
                if (resolvedMethod instanceof java.lang.reflect.Method m) {
                    try {
                        return clojure.lang.Reflector.prepRet(m.getReturnType(), m.invoke(null, clojure.lang.Reflector.boxArgs(m.getParameterTypes(), args)));
                    } catch (IllegalArgumentException iae) {
                        throw new ClassCastException(iae.getMessage());
                    }
                }
                return clojure.lang.Reflector.invokeStaticMethod((Class<?>) targetClass, methodName, args);
            } catch (net.javacrumbs.cloffle.nodes.ClojureException ce) {
                throw ce;
            } catch (com.oracle.truffle.api.exception.AbstractTruffleException ate) {
                throw ate;
            } catch (Exception e) {
                throw net.javacrumbs.cloffle.nodes.ClojureException.wrapReflective(e);
            }
        }
    }

    @Operation
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "targetClass")
    public static final class AdaptFI {
        @Specialization
        public static Object doAdapt(Object targetClass, Object value) {
            Class<?> fiClass = (Class<?>) targetClass;
            value = unwrapForReflect(value);
            if (value instanceof IFn && !fiClass.isInstance(value)
                    && clojure.lang.Compiler.FISupport.maybeFIMethod(fiClass) != null) {
                return clojure.lang.Reflector.boxArg(fiClass, value);
            }
            return value;
        }
    }

    /**
     * Identity-based wrapper that prevents Truffle's equals-based constant pool
     * from merging structurally-equal but type-distinct collections
     * (e.g. PersistentList(1,2,3).equals(PersistentVector(1,2,3)) is true).
     */
    public static final class IdentityConstant {
        public final Object value;
        public IdentityConstant(Object value) { this.value = value; }
        @Override public boolean equals(Object o) { return this == o; }
        @Override public int hashCode() { return System.identityHashCode(this); }
    }

    @Operation
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = IdentityConstant.class)
    public static final class LoadIdentityConstant {
        @Specialization
        public static Object doLoad(IdentityConstant constant) {
            return constant.value;
        }
    }

    @Operation
    public static final class CreateVector {
        @Specialization
        public static Object doCreate(@Variadic Object[] items) {
            return clojure.lang.RT.vector(items);
        }
    }

    @Operation
    public static final class CreateSet {
        @Specialization
        public static Object doCreate(@Variadic Object[] items) {
            return clojure.lang.RT.set(items);
        }
    }

    @Operation
    public static final class WithMeta {
        @Specialization
        public static Object doMeta(Object obj, clojure.lang.IPersistentMap meta) {
            if (obj instanceof clojure.lang.IObj iobj) {
                return iobj.withMeta(meta);
            }
            return obj;
        }
    }

    @Operation
    public static final class CreateList {
        @Specialization
        public static Object doCreate(@Variadic Object[] items) {
            return clojure.lang.RT.arrayToList(items);
        }
    }

    @Operation
    public static final class CreateMap {
        @Specialization
        public static Object doCreate(@Variadic Object[] items) {
            return clojure.lang.RT.map(items);
        }
    }

    @Operation
    public static final class Invoke {
        @Specialization
        public static Object doIFn(IFn fn, @Variadic Object[] args) {
            try {
                switch (args.length) {
                    case 0: return fn.invoke();
                    case 1: return fn.invoke(args[0]);
                    case 2: return fn.invoke(args[0], args[1]);
                    case 3: return fn.invoke(args[0], args[1], args[2]);
                    case 4: return fn.invoke(args[0], args[1], args[2], args[3]);
                    default: return fn.applyTo(clojure.lang.RT.seq(args));
                }
            } catch (net.javacrumbs.cloffle.nodes.ClojureException ce) {
                throw ce;
            } catch (com.oracle.truffle.api.exception.AbstractTruffleException ate) {
                throw ate;
            } catch (Exception e) {
                throw net.javacrumbs.cloffle.nodes.ClojureException.wrapReflective(e);
            }
        }

        /**
         * Non-{@link IFn} in function position must not fall through to DSL "unsupported specialization";
         * match Clojure's "Cannot call … as a function" ({@link net.javacrumbs.cloffle.nodes.ErrorMessages#cannotCallMessage}).
         */
        @Specialization
        public static Object doNonIFn(Object fn, @Variadic Object[] args) {
            CompilerDirectives.transferToInterpreter();
            throw new net.javacrumbs.cloffle.nodes.ClojureException(
                    net.javacrumbs.cloffle.nodes.ErrorMessages.cannotCallMessage(fn), null);
        }
    }

    @Operation
    public static final class ArrayCreate {
        @Specialization
        public static Object[] doCreate(int length) {
            return new Object[length];
        }
    }

    @Operation
    public static final class ArrayWrite {
        @Specialization
        public static Object doWrite(Object[] array, int index, Object value) {
            array[index] = value;
            return value;
        }
    }

    /**
     * JVM {@code monitorenter}-style synchronization for {@code locking} / {@code monitor-enter} special
     * form. Uses {@link net.javacrumbs.cloffle.nodes.MonitorRegistry} (same as {@code MonitorEnterNode} on
     * the AST path).
     */
    @Operation
    public static final class MonitorEnter {
        @Specialization
        public static Object doEnter(Object obj) {
            net.javacrumbs.cloffle.nodes.MonitorRegistry.enter(obj);
            return null;
        }
    }

    /**
     * Pairs with {@link MonitorEnter}; same semantics as {@code MonitorExitNode} / JVM {@code monitorexit}.
     */
    @Operation
    public static final class MonitorExit {
        @Specialization
        public static Object doExit(Object obj) {
            net.javacrumbs.cloffle.nodes.MonitorRegistry.exit(obj);
            return null;
        }
    }

    /**
     * After each {@code letfn*} binding’s {@code fn*} has been evaluated into a {@link ClojureClosure},
     * {@link VirtualFrame#materialize()} the current frame and set each closure’s captured frame so mutual
     * recursion sees sibling locals (AST {@link net.javacrumbs.cloffle.nodes.LetFnNode} uses
     * {@link net.javacrumbs.cloffle.nodes.ClojureRootNode#snapshotFrame} on interpreter frames).
     */
    /**
     * Unwrap polyglot nil ({@code NilNode}) and similar before {@link clojure.lang.Reflector} /
     * {@code Method.invoke} — same boundary as AST nodes using {@link ClojureInterop}.
     */
    private static Object unwrapForReflect(Object o) {
        return ClojureInterop.unwrapFromPolyglot(o);
    }

    private static Object[] unwrapArgsForReflect(Object[] args) {
        if (args == null || args.length == 0) {
            return args;
        }
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = unwrapForReflect(args[i]);
        }
        return out;
    }

    /**
     * If {@code instance} is an {@link IFn} and {@code declaringClass} is a
     * {@link FunctionalInterface} that the instance doesn't already implement,
     * wrap it in a dynamic proxy via {@link Reflector#boxArg}.
     * This compensates for the missing JVM-bytecode-level FI adaptation that
     * stock Clojure's {@code MethodExpr.emitTypedArgs} would normally emit.
     */
    private static Object adaptFIInstance(Class<?> declaringClass, Object instance) {
        if (instance instanceof IFn && !declaringClass.isInstance(instance)
                && clojure.lang.Compiler.FISupport.maybeFIMethod(declaringClass) != null) {
            return clojure.lang.Reflector.boxArg(declaringClass, instance);
        }
        return instance;
    }

    @Operation
    public static final class WireLetFnClosures {
        @Specialization
        public static Object doWire(VirtualFrame frame, @Variadic Object[] closures) {
            // Bytecode-root frames use slot kinds that snapshotFrame's getValue loop cannot always read
            // (illegal object slots); materialize copies the live frame for closure wiring.
            MaterializedFrame snap = frame.materialize();
            for (Object o : closures) {
                if (o instanceof ClojureClosure c) {
                    c.setCapturedFrame(snap);
                }
            }
            return null;
        }
    }
}
