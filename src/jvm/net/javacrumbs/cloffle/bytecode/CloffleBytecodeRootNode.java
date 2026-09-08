package net.javacrumbs.cloffle.bytecode;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.bytecode.BytecodeLocation;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.dsl.Idempotent;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.bytecode.Variadic;
import net.javacrumbs.cloffle.Clojure;
import net.javacrumbs.cloffle.nodes.ClojureClosure;
import clojure.lang.Associative;
import clojure.lang.Counted;
import clojure.lang.IFn;
import clojure.lang.ILookup;
import clojure.lang.Indexed;
import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentVector;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.Namespace;
import clojure.lang.PersistentHashMap;
import clojure.lang.PersistentList;
import clojure.lang.PersistentShapeMap;
import clojure.lang.PersistentShapeMap16;
import clojure.lang.PersistentTuple;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import net.javacrumbs.cloffle.bytecode.archive.IdentityConstant;

import com.oracle.truffle.api.RootCallTarget;

import java.lang.invoke.MethodHandle;
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
@SuppressWarnings("truffle-interpreted-performance")
public abstract class CloffleBytecodeRootNode extends RootNode implements BytecodeRootNode {

    protected String name = null;

    protected CloffleBytecodeRootNode(Clojure language, FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
    }

    @Override
    public String getName() {
        return name != null ? name : "CloffleBytecodeRootNode";
    }

    @Override
    public String toString() {
        return "CloffleBytecodeRootNode[" + getName() + "]";
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
        return interceptTruffleExceptionBoundary(ex, bytecodeNode, bytecodeIndex);
    }

    /**
     * Guest {@code try}/{@code catch} handlers only run for {@link AbstractTruffleException}s. Operations
     * that call {@code clojure.lang} directly (e.g. {@link MapAssoc}, {@link VectorNth2}) throw plain host
     * exceptions, which would otherwise unwind past every Clojure {@code catch} clause. Wrap them the same
     * way the {@link Reflector}-based operations do so {@link CheckCatch} can match on the cause.
     *
     * <p>{@link Error}s are left alone: they are not part of the reflective operations' {@code catch
     * (Exception e)} contract, and wrapping {@link StackOverflowError} risks overflowing again while
     * formatting the message.
     */
    @Override
    public Throwable interceptInternalException(
            Throwable throwable,
            VirtualFrame frame,
            BytecodeNode bytecodeNode,
            int bytecodeIndex) {
        if (throwable instanceof Exception e) {
            return wrapInternalExceptionBoundary(e);
        }
        return throwable;
    }

    @CompilerDirectives.TruffleBoundary
    private static Throwable wrapInternalExceptionBoundary(Exception e) {
        return net.javacrumbs.cloffle.nodes.ClojureException.wrapReflective(e);
    }

    @CompilerDirectives.TruffleBoundary
    private AbstractTruffleException interceptTruffleExceptionBoundary(
            AbstractTruffleException ex,
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
            // intermediate frames at Truffle call sites.
            CompilerDirectives.transferToInterpreter();
            if (instrSS != null && instrSS.isAvailable() && instrSS.hasLines() && instrSS.getStartLine() > 0) {
                ce.addFrame(instrSS, getName());
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

    @Operation(storeBytecodeIndex = true)
public static final class ReadVar {
        @Specialization
        public static Object doVar(clojure.lang.Var var) {
            return var.get();
        }
    }

    @Operation(storeBytecodeIndex = true)
@com.oracle.truffle.api.bytecode.ConstantOperand(type = clojure.lang.Var.class, name = "var")
    public static final class ReadVarConst {
        @Specialization(
                guards = {"!var.isDynamic()", "!isUnbound(cachedRoot)"},
                assumptions = "assumption")
        public static Object doCached(
                clojure.lang.Var var,
                @com.oracle.truffle.api.dsl.Cached(value = "var.getRootAssumption()", neverDefault = true) Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached(value = "var.getRawRoot()", neverDefault = false) Object cachedRoot) {
            return cachedRoot;
        }

        @Specialization(replaces = "doCached")
        public static Object doDynamic(clojure.lang.Var var) {
            return var.get();
        }

        @Idempotent
        protected static boolean isUnbound(Object root) {
            return root instanceof clojure.lang.Var.Unbound || root == null;
        }
    }

    @Operation(storeBytecodeIndex = true)
public static final class WriteVar {
        @Specialization
        public static Object doWrite(clojure.lang.Var var, Object value) {
            return var.set(value);
        }
    }

    @Operation(storeBytecodeIndex = true)
@com.oracle.truffle.api.bytecode.ConstantOperand(type = boolean.class, name = "initProvided")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = boolean.class, name = "isDynamic")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = int.class, name = "line")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = int.class, name = "column")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "uri")
    public static final class DefVar {
        @Specialization
        public static Object doDef(boolean initProvided, boolean isDynamic, int line, int column, String uri,
                                   clojure.lang.Var var, Object value, Object meta) {
            if (initProvided) {
                Object previous = var.getRawRoot();
                var.bindRoot(value);
                if (net.javacrumbs.cloffle.trace.CloffleTracer.isEnabled()) {
                    String sym = var.sym != null ? var.sym.getName() : null;
                    String loc = (uri != null && !uri.isEmpty()) ? uri : null;
                    net.javacrumbs.cloffle.trace.CloffleTracer.bindingWrite(
                            loc, line, column, sym, value, previous);
                }
            }
            if (meta != null) {
                var.setMeta((clojure.lang.IPersistentMap) meta);
            }
            if (isDynamic)
                var.setDynamic();
            return var;
        }
    }

    @Operation(storeBytecodeIndex = true)
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

    @Operation(storeBytecodeIndex = false)
public static final class Truthiness {
        @Specialization
        public static boolean doObject(Object value) {
            return clojure.lang.RT.booleanCast(value);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = int.class, name = "requiredArity")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = boolean.class, name = "isVariadic")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = clojure.lang.IPersistentMap.class, name = "meta")
    public static final class CreateClosure {
        @Specialization(guards = "frame == null")
        public static Object doCreateNull(int requiredArity, boolean isVariadic, clojure.lang.IPersistentMap meta,
                                          CloffleBytecodeRootNode targetNode, Object frame) {
            return new net.javacrumbs.cloffle.nodes.ClojureClosure(targetNode.getCallTarget(), null,
                    requiredArity, isVariadic, meta);
        }

        @Specialization(guards = "frame != null")
        public static Object doCreate(int requiredArity, boolean isVariadic, clojure.lang.IPersistentMap meta,
                                      CloffleBytecodeRootNode targetNode, com.oracle.truffle.api.frame.MaterializedFrame frame) {
            return new net.javacrumbs.cloffle.nodes.ClojureClosure(targetNode.getCallTarget(), frame,
                    requiredArity, isVariadic, meta);
        }
    }

    /**
     * First half of named-fn self-reference setup: install the closure into {@code thisLocal} on the
     * live frame <em>before</em> materializing it. Pair with {@link FinalizeClosureCapture}.
     */
    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = int.class, name = "requiredArity")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = boolean.class, name = "isVariadic")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = clojure.lang.IPersistentMap.class, name = "meta")
    public static final class CreateClosurePendingCapture {
        @Specialization
        public static Object doCreate(int requiredArity, boolean isVariadic, clojure.lang.IPersistentMap meta,
                                      CloffleBytecodeRootNode targetNode) {
            return new ClojureClosure(targetNode.getCallTarget(), null, requiredArity, isVariadic, meta);
        }
    }

    /**
     * After the pending closure is stored in the self slot, materialize the current frame and attach
     * it so recursive loads see the closure.
     */
    @Operation(storeBytecodeIndex = true)
public static final class FinalizeClosureCapture {
        @Specialization
        public static Object doFinalize(ClojureClosure closure, MaterializedFrame frame) {
            closure.setCapturedFrame(frame);
            return closure;
        }
    }

    @Operation(storeBytecodeIndex = false)
public static final class GetOuterFrame {
        @Specialization
        public static com.oracle.truffle.api.frame.MaterializedFrame doGet(com.oracle.truffle.api.frame.VirtualFrame frame) {
            return net.javacrumbs.cloffle.nodes.ClojureRootNode.snapshotFrame(frame);
        }
    }

    @Operation(storeBytecodeIndex = true)
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

    @Operation(storeBytecodeIndex = false)
public static final class GetArgCount {
        @Specialization
        public static int doCount(com.oracle.truffle.api.frame.VirtualFrame frame) {
            // The first argument is the closure frame, so subtract 1
            return frame.getArguments().length - 1;
        }
    }

    @Operation(storeBytecodeIndex = true)
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

    @Operation(storeBytecodeIndex = true)
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
            Object[] rest = new Object[restCount];
            System.arraycopy(args, start, rest, 0, restCount);
            return clojure.lang.ArraySeq.create(rest);
        }
    }

    @Operation(storeBytecodeIndex = false)
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

    @Operation(storeBytecodeIndex = false)
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

    @Operation(storeBytecodeIndex = true)
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
    @Operation(storeBytecodeIndex = true)
public static final class ThrowArityException {
        @Specialization
        public static Object doThrow(int actual, String name) {
            throw new net.javacrumbs.cloffle.nodes.ClojureException(actual + " args", new clojure.lang.ArityException(actual, name == null ? "fn" : name), null);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class NewLazySeq {
        @Specialization
        public static Object doNew(Object fn) {
            return new clojure.lang.LazySeq((clojure.lang.IFn) fn);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "targetClass")
    public static final class NewObject {
        @Specialization
        public static Object doNew(Object targetClass, @Variadic Object[] args) {
            return BytecodeInterop.newObject(targetClass, args);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "methodName")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "resolvedMethod")
    public static final class InstanceMethod {
        @Specialization
        public static Object doInvoke(String methodName, Object resolvedMethod, Object instance, @Variadic Object[] args) {
            return BytecodeInterop.instanceMethod(methodName, resolvedMethod, instance, args);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = clojure.lang.Var.class, name = "var")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "onMethod")
    public static final class InvokeProtocol {
        @Specialization
        public static Object doInvoke(clojure.lang.Var var, Object onMethod, @Variadic Object[] args) {
            return BytecodeInterop.invokeProtocol(var, onMethod, args);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "targetClass")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "fieldName")
    public static final class StaticField {
        @Specialization
        public static Object doGet(Object targetClass, String fieldName) {
            return BytecodeInterop.staticField(targetClass, fieldName);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "targetClass")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "fieldName")
    public static final class SetStaticField {
        @Specialization
        public static Object doSet(Object targetClass, String fieldName, Object value) {
            return BytecodeInterop.setStaticField(targetClass, fieldName, value);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "fieldName")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = boolean.class, name = "requireField")
    public static final class InstanceField {
        @Specialization
        public static Object doGet(String fieldName, boolean requireField, Object instance) {
            return BytecodeInterop.instanceField(fieldName, requireField, instance);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "fieldName")
    public static final class SetInstanceField {
        @Specialization
        public static Object doSet(String fieldName, Object target, Object value) {
            return BytecodeInterop.setInstanceField(fieldName, target, value);
        }
    }

    @Operation(storeBytecodeIndex = false)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "targetClass")
    public static final class InstanceOf {
        @Specialization
        public static boolean doCheck(Object targetClass, Object instance) {
            return BytecodeInterop.instanceOf(targetClass, instance);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "targetClass")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "methodName")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "resolvedMethod")
    public static final class StaticMethod0 {
        @Specialization(guards = "mh != null")
        public static Object doFast(
                Object targetClass, String methodName, Object resolvedMethod,
                @com.oracle.truffle.api.dsl.Cached(value = "createMethodHandle(resolvedMethod)", neverDefault = false) MethodHandle mh) {
            try {
                return mh.invokeExact();
            } catch (Throwable t) {
                throw BytecodeStaticMethod.handleException(t);
            }
        }

        @Specialization(replaces = "doFast")
        public static Object doFallback(Object targetClass, String methodName, Object resolvedMethod) {
            return BytecodeInterop.staticMethod(targetClass, methodName, resolvedMethod, BytecodeStaticMethod.EMPTY_ARRAY);
        }

        protected static MethodHandle createMethodHandle(Object resolvedMethod) {
            return BytecodeStaticMethod.createMethodHandle(resolvedMethod, 0);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "targetClass")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "methodName")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "resolvedMethod")
    public static final class StaticMethod1 {
        @Specialization(guards = "mh != null")
        public static Object doFast(
                Object targetClass, String methodName, Object resolvedMethod,
                Object a0,
                @com.oracle.truffle.api.dsl.Cached(value = "createMethodHandle(resolvedMethod)", neverDefault = false) MethodHandle mh) {
            try {
                return mh.invokeExact(BytecodeStaticMethod.unwrap(a0));
            } catch (Throwable t) {
                throw BytecodeStaticMethod.handleException(t);
            }
        }

        @Specialization(replaces = "doFast")
        public static Object doFallback(Object targetClass, String methodName, Object resolvedMethod, Object a0) {
            return BytecodeInterop.staticMethod(targetClass, methodName, resolvedMethod, new Object[]{a0});
        }

        protected static MethodHandle createMethodHandle(Object resolvedMethod) {
            return BytecodeStaticMethod.createMethodHandle(resolvedMethod, 1);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "targetClass")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "methodName")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "resolvedMethod")
    public static final class StaticMethod2 {
        @Specialization(guards = "mh != null")
        public static Object doFast(
                Object targetClass, String methodName, Object resolvedMethod,
                Object a0, Object a1,
                @com.oracle.truffle.api.dsl.Cached(value = "createMethodHandle(resolvedMethod)", neverDefault = false) MethodHandle mh) {
            try {
                return mh.invokeExact(BytecodeStaticMethod.unwrap(a0), BytecodeStaticMethod.unwrap(a1));
            } catch (Throwable t) {
                throw BytecodeStaticMethod.handleException(t);
            }
        }

        @Specialization(replaces = "doFast")
        public static Object doFallback(Object targetClass, String methodName, Object resolvedMethod, Object a0, Object a1) {
            return BytecodeInterop.staticMethod(targetClass, methodName, resolvedMethod, new Object[]{a0, a1});
        }

        protected static MethodHandle createMethodHandle(Object resolvedMethod) {
            return BytecodeStaticMethod.createMethodHandle(resolvedMethod, 2);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "targetClass")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "methodName")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "resolvedMethod")
    public static final class StaticMethod3 {
        @Specialization(guards = "mh != null")
        public static Object doFast(
                Object targetClass, String methodName, Object resolvedMethod,
                Object a0, Object a1, Object a2,
                @com.oracle.truffle.api.dsl.Cached(value = "createMethodHandle(resolvedMethod)", neverDefault = false) MethodHandle mh) {
            try {
                return mh.invokeExact(BytecodeStaticMethod.unwrap(a0), BytecodeStaticMethod.unwrap(a1), BytecodeStaticMethod.unwrap(a2));
            } catch (Throwable t) {
                throw BytecodeStaticMethod.handleException(t);
            }
        }

        @Specialization(replaces = "doFast")
        public static Object doFallback(Object targetClass, String methodName, Object resolvedMethod, Object a0, Object a1, Object a2) {
            return BytecodeInterop.staticMethod(targetClass, methodName, resolvedMethod, new Object[]{a0, a1, a2});
        }

        protected static MethodHandle createMethodHandle(Object resolvedMethod) {
            return BytecodeStaticMethod.createMethodHandle(resolvedMethod, 3);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "targetClass")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "methodName")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "resolvedMethod")
    public static final class StaticMethod4 {
        @Specialization(guards = "mh != null")
        public static Object doFast(
                Object targetClass, String methodName, Object resolvedMethod,
                Object a0, Object a1, Object a2, Object a3,
                @com.oracle.truffle.api.dsl.Cached(value = "createMethodHandle(resolvedMethod)", neverDefault = false) MethodHandle mh) {
            try {
                return mh.invokeExact(BytecodeStaticMethod.unwrap(a0), BytecodeStaticMethod.unwrap(a1), BytecodeStaticMethod.unwrap(a2), BytecodeStaticMethod.unwrap(a3));
            } catch (Throwable t) {
                throw BytecodeStaticMethod.handleException(t);
            }
        }

        @Specialization(replaces = "doFast")
        public static Object doFallback(Object targetClass, String methodName, Object resolvedMethod, Object a0, Object a1, Object a2, Object a3) {
            return BytecodeInterop.staticMethod(targetClass, methodName, resolvedMethod, new Object[]{a0, a1, a2, a3});
        }

        protected static MethodHandle createMethodHandle(Object resolvedMethod) {
            return BytecodeStaticMethod.createMethodHandle(resolvedMethod, 4);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "targetClass")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "methodName")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "resolvedMethod")
    public static final class StaticMethodN {
        @Specialization
        public static Object doInvoke(Object targetClass, String methodName, Object resolvedMethod, @Variadic Object[] args) {
            return BytecodeInterop.staticMethod(targetClass, methodName, resolvedMethod, args);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "targetClass")
    public static final class AdaptFI {
        @Specialization
        public static Object doAdapt(Object targetClass, Object value) {
            return BytecodeInterop.adaptFI(targetClass, value);
        }
    }

    @Operation(storeBytecodeIndex = false)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = IdentityConstant.class)
    public static final class LoadIdentityConstant {
        @Specialization
        public static Object doLoad(IdentityConstant constant) {
            return constant.value;
        }
    }

    @Operation(storeBytecodeIndex = false)
    public static final class CreateVector0 {
        @Specialization
        public static Object doCreate() {
            return BytecodeCreateVector.create0();
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateVector1 {
        @Specialization
        public static Object doCreate(Object v0) {
            return BytecodeCreateVector.create1(v0);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateVector2 {
        @Specialization
        public static Object doCreate(Object v0, Object v1) {
            return BytecodeCreateVector.create2(v0, v1);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateVector3 {
        @Specialization
        public static Object doCreate(Object v0, Object v1, Object v2) {
            return BytecodeCreateVector.create3(v0, v1, v2);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateVector4 {
        @Specialization
        public static Object doCreate(Object v0, Object v1, Object v2, Object v3) {
            return BytecodeCreateVector.create4(v0, v1, v2, v3);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateVector5 {
        @Specialization
        public static Object doCreate(Object v0, Object v1, Object v2, Object v3, Object v4) {
            return BytecodeCreateVector.create5(v0, v1, v2, v3, v4);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateVector6 {
        @Specialization
        public static Object doCreate(Object v0, Object v1, Object v2, Object v3, Object v4, Object v5) {
            return BytecodeCreateVector.create6(v0, v1, v2, v3, v4, v5);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateVector7 {
        @Specialization
        public static Object doCreate(Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6) {
            return BytecodeCreateVector.create7(v0, v1, v2, v3, v4, v5, v6);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateVector8 {
        @Specialization
        public static Object doCreate(Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6, Object v7) {
            return BytecodeCreateVector.create8(v0, v1, v2, v3, v4, v5, v6, v7);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateVectorN {
        @Specialization
        public static Object doCreate(@Variadic Object[] items) {
            return BytecodeCreateVector.createN(items);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateVector {
        @Specialization
        public static Object doCreate(@Variadic Object[] items) {
            return BytecodeCreateVector.createN(items);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateSet {
        @Specialization
        public static Object doCreate(@Variadic Object[] items) {
            return BytecodeCreateMap.set(items);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class WithMeta {
        @Specialization
        public static Object doMeta(Object obj, clojure.lang.IPersistentMap meta) {
            return BytecodeCreateMap.withMeta(obj, meta);
        }
    }

    @Operation(storeBytecodeIndex = false)
    public static final class CreateList0 {
        @Specialization
        public static Object doCreate() {
            return BytecodeCreateList.create0();
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateList1 {
        @Specialization
        public static Object doCreate(Object e0) {
            return BytecodeCreateList.create1(e0);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateList2 {
        @Specialization
        public static Object doCreate(Object e0, Object e1) {
            return BytecodeCreateList.create2(e0, e1);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateList3 {
        @Specialization
        public static Object doCreate(Object e0, Object e1, Object e2) {
            return BytecodeCreateList.create3(e0, e1, e2);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateList4 {
        @Specialization
        public static Object doCreate(Object e0, Object e1, Object e2, Object e3) {
            return BytecodeCreateList.create4(e0, e1, e2, e3);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateList5 {
        @Specialization
        public static Object doCreate(Object e0, Object e1, Object e2, Object e3, Object e4) {
            return BytecodeCreateList.create5(e0, e1, e2, e3, e4);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateList6 {
        @Specialization
        public static Object doCreate(Object e0, Object e1, Object e2, Object e3, Object e4, Object e5) {
            return BytecodeCreateList.create6(e0, e1, e2, e3, e4, e5);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateList7 {
        @Specialization
        public static Object doCreate(Object e0, Object e1, Object e2, Object e3, Object e4, Object e5, Object e6) {
            return BytecodeCreateList.create7(e0, e1, e2, e3, e4, e5, e6);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateList8 {
        @Specialization
        public static Object doCreate(Object e0, Object e1, Object e2, Object e3, Object e4, Object e5, Object e6, Object e7) {
            return BytecodeCreateList.create8(e0, e1, e2, e3, e4, e5, e6, e7);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateListN {
        @Specialization
        public static Object doCreate(@Variadic Object[] items) {
            return BytecodeCreateList.createN(items);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateList {
        @Specialization
        public static Object doCreate(@Variadic Object[] items) {
            return BytecodeCreateList.createN(items);
        }
    }

    @Operation(storeBytecodeIndex = false)
    public static final class CreateMap0 {
        @Specialization
        public static Object doCreate() {
            return BytecodeCreateMap.empty();
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateStandardMap {
        @Specialization
        public static Object doCreate(@Variadic Object[] keyvals) {
            return BytecodeCreateMap.standard(keyvals);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateMap1 {
        @Specialization(guards = "k0 == cachedK0", limit = "2")
        public static Object doKeywordCached(
                Keyword k0, Object v0,
                @com.oracle.truffle.api.dsl.Cached("k0") Keyword cachedK0,
                @com.oracle.truffle.api.dsl.Cached("shape1(cachedK0)") PersistentShapeMap.Shape1 shape) {
            return shape.create(v0);
        }

        @Specialization(replaces = "doKeywordCached")
        public static Object doKeyword(Keyword k0, Object v0) {
            return PersistentShapeMap.create(k0, v0);
        }

        @Specialization(guards = "!isKeyword(k0)")
        public static Object doGeneric(Object k0, Object v0) {
            return RT.map(k0, v0);
        }

        protected static boolean isKeyword(Object obj) {
            return BytecodeCreateMap.isKeyword(obj);
        }

        protected static PersistentShapeMap.Shape1 shape1(Keyword k0) {
            return BytecodeCreateMap.shape1(k0);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateMap2 {
        @Specialization(guards = {"k0 == cachedK0", "k1 == cachedK1"}, limit = "2")
        public static Object doKeywordCached(
                Keyword k0, Object v0, Keyword k1, Object v1,
                @com.oracle.truffle.api.dsl.Cached("k0") Keyword cachedK0,
                @com.oracle.truffle.api.dsl.Cached("k1") Keyword cachedK1,
                @com.oracle.truffle.api.dsl.Cached("shape2(cachedK0, cachedK1)") PersistentShapeMap.Shape2 shape) {
            return shape.create(v0, v1);
        }

        @Specialization(replaces = "doKeywordCached")
        public static Object doKeyword(Keyword k0, Object v0, Keyword k1, Object v1) {
            return PersistentShapeMap.create(k0, v0, k1, v1);
        }

        @Specialization(guards = "!areKeywords(k0, k1)")
        public static Object doGeneric(Object k0, Object v0, Object k1, Object v1) {
            return RT.map(k0, v0, k1, v1);
        }

        protected static boolean areKeywords(Object k0, Object k1) {
            return BytecodeCreateMap.areKeywords(k0, k1);
        }

        protected static PersistentShapeMap.Shape2 shape2(Keyword k0, Keyword k1) {
            return BytecodeCreateMap.shape2(k0, k1);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateMap3 {
        @Specialization(guards = {"k0 == cachedK0", "k1 == cachedK1", "k2 == cachedK2"}, limit = "2")
        public static Object doKeywordCached(
                Keyword k0, Object v0, Keyword k1, Object v1, Keyword k2, Object v2,
                @com.oracle.truffle.api.dsl.Cached("k0") Keyword cachedK0,
                @com.oracle.truffle.api.dsl.Cached("k1") Keyword cachedK1,
                @com.oracle.truffle.api.dsl.Cached("k2") Keyword cachedK2,
                @com.oracle.truffle.api.dsl.Cached("shape3(cachedK0, cachedK1, cachedK2)") PersistentShapeMap.Shape3 shape) {
            return shape.create(v0, v1, v2);
        }

        @Specialization(replaces = "doKeywordCached")
        public static Object doKeyword(Keyword k0, Object v0, Keyword k1, Object v1, Keyword k2, Object v2) {
            return PersistentShapeMap.create(k0, v0, k1, v1, k2, v2);
        }

        @Specialization(guards = "!areKeywords(k0, k1, k2)")
        public static Object doGeneric(Object k0, Object v0, Object k1, Object v1, Object k2, Object v2) {
            return RT.map(k0, v0, k1, v1, k2, v2);
        }

        protected static boolean areKeywords(Object k0, Object k1, Object k2) {
            return BytecodeCreateMap.areKeywords(k0, k1, k2);
        }

        protected static PersistentShapeMap.Shape3 shape3(Keyword k0, Keyword k1, Keyword k2) {
            return BytecodeCreateMap.shape3(k0, k1, k2);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateMap4 {
        @Specialization(guards = {"k0 == cachedK0", "k1 == cachedK1", "k2 == cachedK2", "k3 == cachedK3"}, limit = "2")
        public static Object doKeywordCached(
                Keyword k0, Object v0, Keyword k1, Object v1, Keyword k2, Object v2, Keyword k3, Object v3,
                @com.oracle.truffle.api.dsl.Cached("k0") Keyword cachedK0,
                @com.oracle.truffle.api.dsl.Cached("k1") Keyword cachedK1,
                @com.oracle.truffle.api.dsl.Cached("k2") Keyword cachedK2,
                @com.oracle.truffle.api.dsl.Cached("k3") Keyword cachedK3,
                @com.oracle.truffle.api.dsl.Cached("shape4(cachedK0, cachedK1, cachedK2, cachedK3)") PersistentShapeMap.Shape4 shape) {
            return shape.create(v0, v1, v2, v3);
        }

        @Specialization(replaces = "doKeywordCached")
        public static Object doKeyword(Keyword k0, Object v0, Keyword k1, Object v1, Keyword k2, Object v2, Keyword k3, Object v3) {
            return PersistentShapeMap.create(k0, v0, k1, v1, k2, v2, k3, v3);
        }

        @Specialization(guards = "!areKeywords(k0, k1, k2, k3)")
        public static Object doGeneric(Object k0, Object v0, Object k1, Object v1, Object k2, Object v2, Object k3, Object v3) {
            return RT.map(k0, v0, k1, v1, k2, v2, k3, v3);
        }

        protected static boolean areKeywords(Object k0, Object k1, Object k2, Object k3) {
            return BytecodeCreateMap.areKeywords(k0, k1, k2, k3);
        }

        protected static PersistentShapeMap.Shape4 shape4(Keyword k0, Keyword k1, Keyword k2, Keyword k3) {
            return BytecodeCreateMap.shape4(k0, k1, k2, k3);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateMap5 {
        @Specialization(guards = {"k0 == cachedK0", "k1 == cachedK1", "k2 == cachedK2", "k3 == cachedK3", "k4 == cachedK4"}, limit = "2")
        public static Object doKeywordCached(
                Keyword k0, Object v0, Keyword k1, Object v1, Keyword k2, Object v2, Keyword k3, Object v3, Keyword k4, Object v4,
                @com.oracle.truffle.api.dsl.Cached("k0") Keyword cachedK0,
                @com.oracle.truffle.api.dsl.Cached("k1") Keyword cachedK1,
                @com.oracle.truffle.api.dsl.Cached("k2") Keyword cachedK2,
                @com.oracle.truffle.api.dsl.Cached("k3") Keyword cachedK3,
                @com.oracle.truffle.api.dsl.Cached("k4") Keyword cachedK4,
                @com.oracle.truffle.api.dsl.Cached("shape5(cachedK0, cachedK1, cachedK2, cachedK3, cachedK4)") PersistentShapeMap.Shape5 shape) {
            return shape.create(v0, v1, v2, v3, v4);
        }

        @Specialization(replaces = "doKeywordCached")
        public static Object doKeyword(Keyword k0, Object v0, Keyword k1, Object v1, Keyword k2, Object v2, Keyword k3, Object v3, Keyword k4, Object v4) {
            return PersistentShapeMap.create(k0, v0, k1, v1, k2, v2, k3, v3, k4, v4);
        }

        @Specialization(guards = "!areKeywords(k0, k1, k2, k3, k4)")
        public static Object doGeneric(Object k0, Object v0, Object k1, Object v1, Object k2, Object v2, Object k3, Object v3, Object k4, Object v4) {
            return RT.map(k0, v0, k1, v1, k2, v2, k3, v3, k4, v4);
        }

        protected static boolean areKeywords(Object k0, Object k1, Object k2, Object k3, Object k4) {
            return BytecodeCreateMap.areKeywords(k0, k1, k2, k3, k4);
        }

        protected static PersistentShapeMap.Shape5 shape5(Keyword k0, Keyword k1, Keyword k2, Keyword k3, Keyword k4) {
            return BytecodeCreateMap.shape5(k0, k1, k2, k3, k4);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateMap6 {
        @Specialization(guards = {"k0 == cachedK0", "k1 == cachedK1", "k2 == cachedK2", "k3 == cachedK3", "k4 == cachedK4", "k5 == cachedK5"}, limit = "2")
        public static Object doKeywordCached(
                Keyword k0, Object v0, Keyword k1, Object v1, Keyword k2, Object v2, Keyword k3, Object v3, Keyword k4, Object v4, Keyword k5, Object v5,
                @com.oracle.truffle.api.dsl.Cached("k0") Keyword cachedK0,
                @com.oracle.truffle.api.dsl.Cached("k1") Keyword cachedK1,
                @com.oracle.truffle.api.dsl.Cached("k2") Keyword cachedK2,
                @com.oracle.truffle.api.dsl.Cached("k3") Keyword cachedK3,
                @com.oracle.truffle.api.dsl.Cached("k4") Keyword cachedK4,
                @com.oracle.truffle.api.dsl.Cached("k5") Keyword cachedK5,
                @com.oracle.truffle.api.dsl.Cached("shape6(cachedK0, cachedK1, cachedK2, cachedK3, cachedK4, cachedK5)") PersistentShapeMap.Shape6 shape) {
            return shape.create(v0, v1, v2, v3, v4, v5);
        }

        @Specialization(replaces = "doKeywordCached")
        public static Object doKeyword(Keyword k0, Object v0, Keyword k1, Object v1, Keyword k2, Object v2, Keyword k3, Object v3, Keyword k4, Object v4, Keyword k5, Object v5) {
            return PersistentShapeMap.create(k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5);
        }

        @Specialization(guards = "!areKeywords(k0, k1, k2, k3, k4, k5)")
        public static Object doGeneric(Object k0, Object v0, Object k1, Object v1, Object k2, Object v2, Object k3, Object v3, Object k4, Object v4, Object k5, Object v5) {
            return RT.map(k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5);
        }

        protected static boolean areKeywords(Object k0, Object k1, Object k2, Object k3, Object k4, Object k5) {
            return BytecodeCreateMap.areKeywords(k0, k1, k2, k3, k4, k5);
        }

        protected static PersistentShapeMap.Shape6 shape6(Keyword k0, Keyword k1, Keyword k2, Keyword k3, Keyword k4, Keyword k5) {
            return BytecodeCreateMap.shape6(k0, k1, k2, k3, k4, k5);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateMap7 {
        @Specialization(guards = {"k0 == cachedK0", "k1 == cachedK1", "k2 == cachedK2", "k3 == cachedK3", "k4 == cachedK4", "k5 == cachedK5", "k6 == cachedK6"}, limit = "2")
        public static Object doKeywordCached(
                Keyword k0, Object v0, Keyword k1, Object v1, Keyword k2, Object v2, Keyword k3, Object v3, Keyword k4, Object v4, Keyword k5, Object v5, Keyword k6, Object v6,
                @com.oracle.truffle.api.dsl.Cached("k0") Keyword cachedK0,
                @com.oracle.truffle.api.dsl.Cached("k1") Keyword cachedK1,
                @com.oracle.truffle.api.dsl.Cached("k2") Keyword cachedK2,
                @com.oracle.truffle.api.dsl.Cached("k3") Keyword cachedK3,
                @com.oracle.truffle.api.dsl.Cached("k4") Keyword cachedK4,
                @com.oracle.truffle.api.dsl.Cached("k5") Keyword cachedK5,
                @com.oracle.truffle.api.dsl.Cached("k6") Keyword cachedK6,
                @com.oracle.truffle.api.dsl.Cached("shape7(cachedK0, cachedK1, cachedK2, cachedK3, cachedK4, cachedK5, cachedK6)") PersistentShapeMap.Shape7 shape) {
            return shape.create(v0, v1, v2, v3, v4, v5, v6);
        }

        @Specialization(replaces = "doKeywordCached")
        public static Object doKeyword(Keyword k0, Object v0, Keyword k1, Object v1, Keyword k2, Object v2, Keyword k3, Object v3, Keyword k4, Object v4, Keyword k5, Object v5, Keyword k6, Object v6) {
            return PersistentShapeMap.create(k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6);
        }

        @Specialization(guards = "!areKeywords(k0, k1, k2, k3, k4, k5, k6)")
        public static Object doGeneric(Object k0, Object v0, Object k1, Object v1, Object k2, Object v2, Object k3, Object v3, Object k4, Object v4, Object k5, Object v5, Object k6, Object v6) {
            return RT.map(k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6);
        }

        protected static boolean areKeywords(Object k0, Object k1, Object k2, Object k3, Object k4, Object k5, Object k6) {
            return BytecodeCreateMap.areKeywords(k0, k1, k2, k3, k4, k5, k6);
        }

        protected static PersistentShapeMap.Shape7 shape7(Keyword k0, Keyword k1, Keyword k2, Keyword k3, Keyword k4, Keyword k5, Keyword k6) {
            return BytecodeCreateMap.shape7(k0, k1, k2, k3, k4, k5, k6);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateMap8 {
        @Specialization(guards = {"k0 == cachedK0", "k1 == cachedK1", "k2 == cachedK2", "k3 == cachedK3", "k4 == cachedK4", "k5 == cachedK5", "k6 == cachedK6", "k7 == cachedK7"}, limit = "2")
        public static Object doKeywordCached(
                Keyword k0, Object v0, Keyword k1, Object v1, Keyword k2, Object v2, Keyword k3, Object v3, Keyword k4, Object v4, Keyword k5, Object v5, Keyword k6, Object v6, Keyword k7, Object v7,
                @com.oracle.truffle.api.dsl.Cached("k0") Keyword cachedK0,
                @com.oracle.truffle.api.dsl.Cached("k1") Keyword cachedK1,
                @com.oracle.truffle.api.dsl.Cached("k2") Keyword cachedK2,
                @com.oracle.truffle.api.dsl.Cached("k3") Keyword cachedK3,
                @com.oracle.truffle.api.dsl.Cached("k4") Keyword cachedK4,
                @com.oracle.truffle.api.dsl.Cached("k5") Keyword cachedK5,
                @com.oracle.truffle.api.dsl.Cached("k6") Keyword cachedK6,
                @com.oracle.truffle.api.dsl.Cached("k7") Keyword cachedK7,
                @com.oracle.truffle.api.dsl.Cached("shape8(cachedK0, cachedK1, cachedK2, cachedK3, cachedK4, cachedK5, cachedK6, cachedK7)") PersistentShapeMap.Shape8 shape) {
            return shape.create(v0, v1, v2, v3, v4, v5, v6, v7);
        }

        @Specialization(replaces = "doKeywordCached")
        public static Object doKeyword(Keyword k0, Object v0, Keyword k1, Object v1, Keyword k2, Object v2, Keyword k3, Object v3, Keyword k4, Object v4, Keyword k5, Object v5, Keyword k6, Object v6, Keyword k7, Object v7) {
            return PersistentShapeMap.create(k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7);
        }

        @Specialization(guards = "!areKeywords(k0, k1, k2, k3, k4, k5, k6, k7)")
        public static Object doGeneric(Object k0, Object v0, Object k1, Object v1, Object k2, Object v2, Object k3, Object v3, Object k4, Object v4, Object k5, Object v5, Object k6, Object v6, Object k7, Object v7) {
            return RT.map(k0, v0, k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7);
        }

        protected static boolean areKeywords(Object k0, Object k1, Object k2, Object k3, Object k4, Object k5, Object k6, Object k7) {
            return BytecodeCreateMap.areKeywords(k0, k1, k2, k3, k4, k5, k6, k7);
        }

        protected static PersistentShapeMap.Shape8 shape8(Keyword k0, Keyword k1, Keyword k2, Keyword k3, Keyword k4, Keyword k5, Keyword k6, Keyword k7) {
            return BytecodeCreateMap.shape8(k0, k1, k2, k3, k4, k5, k6, k7);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateMapN {
        @Specialization
        public static Object doCreate(@Variadic Object[] items) {
            return BytecodeCreateMap.standard(items);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class CreateMap {
        @Specialization
        public static Object doCreate(@Variadic Object[] items) {
            return BytecodeCreateMap.standard(items);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class Invoke0 {
        @Specialization(limit = "3", guards = "fn.getCallTarget() == cachedTarget")
        public static Object doClojureClosureCached(
                ClojureClosure fn,
                @com.oracle.truffle.api.dsl.Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @com.oracle.truffle.api.dsl.Cached("create(cachedTarget)") DirectCallNode callNode) {
            return BytecodeInvoke.callDirect(callNode, new Object[]{fn.getCapturedFrame()});
        }

        @Specialization(replaces = "doClojureClosureCached")
        public static Object doClojureClosureIndirect(
                ClojureClosure fn,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeInvoke.callIndirect(callNode, fn.getCallTarget(), new Object[]{fn.getCapturedFrame()});
        }

        @Specialization(guards = "!isClojureClosure(fn)")
        public static Object doIFn(IFn fn) {
            return BytecodeInvoke.invokeIFn(fn);
        }

        @Specialization
        public static Object doNonIFn(Object fn) {
            return BytecodeInvoke.cannotCall(fn);
        }

        protected static boolean isClojureClosure(IFn fn) {
            return BytecodeInvoke.isClojureClosure(fn);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class Invoke1 {
        @Specialization(limit = "3", guards = "fn.getCallTarget() == cachedTarget")
        public static Object doClojureClosureCached(
                ClojureClosure fn,
                Object a0,
                @com.oracle.truffle.api.dsl.Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @com.oracle.truffle.api.dsl.Cached("create(cachedTarget)") DirectCallNode callNode) {
            return BytecodeInvoke.callDirect(callNode, new Object[]{fn.getCapturedFrame(), a0});
        }

        @Specialization(replaces = "doClojureClosureCached")
        public static Object doClojureClosureIndirect(
                ClojureClosure fn,
                Object a0,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeInvoke.callIndirect(callNode, fn.getCallTarget(), new Object[]{fn.getCapturedFrame(), a0});
        }

        @Specialization(guards = "!isClojureClosure(fn)")
        public static Object doIFn(IFn fn, Object a0) {
            return BytecodeInvoke.invokeIFn(fn, a0);
        }

        @Specialization
        public static Object doNonIFn(Object fn, Object a0) {
            return BytecodeInvoke.cannotCall(fn);
        }

        protected static boolean isClojureClosure(IFn fn) {
            return BytecodeInvoke.isClojureClosure(fn);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class Invoke2 {
        @Specialization(limit = "3", guards = "fn.getCallTarget() == cachedTarget")
        public static Object doClojureClosureCached(
                ClojureClosure fn,
                Object a0,
                Object a1,
                @com.oracle.truffle.api.dsl.Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @com.oracle.truffle.api.dsl.Cached("create(cachedTarget)") DirectCallNode callNode) {
            return BytecodeInvoke.callDirect(callNode, new Object[]{fn.getCapturedFrame(), a0, a1});
        }

        @Specialization(replaces = "doClojureClosureCached")
        public static Object doClojureClosureIndirect(
                ClojureClosure fn,
                Object a0,
                Object a1,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeInvoke.callIndirect(callNode, fn.getCallTarget(), new Object[]{fn.getCapturedFrame(), a0, a1});
        }

        @Specialization(guards = "!isClojureClosure(fn)")
        public static Object doIFn(IFn fn, Object a0, Object a1) {
            return BytecodeInvoke.invokeIFn(fn, a0, a1);
        }

        @Specialization
        public static Object doNonIFn(Object fn, Object a0, Object a1) {
            return BytecodeInvoke.cannotCall(fn);
        }

        protected static boolean isClojureClosure(IFn fn) {
            return BytecodeInvoke.isClojureClosure(fn);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class Invoke3 {
        @Specialization(limit = "3", guards = "fn.getCallTarget() == cachedTarget")
        public static Object doClojureClosureCached(
                ClojureClosure fn,
                Object a0,
                Object a1,
                Object a2,
                @com.oracle.truffle.api.dsl.Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @com.oracle.truffle.api.dsl.Cached("create(cachedTarget)") DirectCallNode callNode) {
            return BytecodeInvoke.callDirect(callNode, new Object[]{fn.getCapturedFrame(), a0, a1, a2});
        }

        @Specialization(replaces = "doClojureClosureCached")
        public static Object doClojureClosureIndirect(
                ClojureClosure fn,
                Object a0,
                Object a1,
                Object a2,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeInvoke.callIndirect(callNode, fn.getCallTarget(), new Object[]{fn.getCapturedFrame(), a0, a1, a2});
        }

        @Specialization(guards = "!isClojureClosure(fn)")
        public static Object doIFn(IFn fn, Object a0, Object a1, Object a2) {
            return BytecodeInvoke.invokeIFn(fn, a0, a1, a2);
        }

        @Specialization
        public static Object doNonIFn(Object fn, Object a0, Object a1, Object a2) {
            return BytecodeInvoke.cannotCall(fn);
        }

        protected static boolean isClojureClosure(IFn fn) {
            return BytecodeInvoke.isClojureClosure(fn);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class Invoke4 {
        @Specialization(limit = "3", guards = "fn.getCallTarget() == cachedTarget")
        public static Object doClojureClosureCached(
                ClojureClosure fn,
                Object a0,
                Object a1,
                Object a2,
                Object a3,
                @com.oracle.truffle.api.dsl.Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @com.oracle.truffle.api.dsl.Cached("create(cachedTarget)") DirectCallNode callNode) {
            return BytecodeInvoke.callDirect(callNode, new Object[]{fn.getCapturedFrame(), a0, a1, a2, a3});
        }

        @Specialization(replaces = "doClojureClosureCached")
        public static Object doClojureClosureIndirect(
                ClojureClosure fn,
                Object a0,
                Object a1,
                Object a2,
                Object a3,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeInvoke.callIndirect(callNode, fn.getCallTarget(), new Object[]{fn.getCapturedFrame(), a0, a1, a2, a3});
        }

        @Specialization(guards = "!isClojureClosure(fn)")
        public static Object doIFn(IFn fn, Object a0, Object a1, Object a2, Object a3) {
            return BytecodeInvoke.invokeIFn(fn, a0, a1, a2, a3);
        }

        @Specialization
        public static Object doNonIFn(Object fn, Object a0, Object a1, Object a2, Object a3) {
            return BytecodeInvoke.cannotCall(fn);
        }

        protected static boolean isClojureClosure(IFn fn) {
            return BytecodeInvoke.isClojureClosure(fn);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class InvokeN {
        @Specialization(limit = "3", guards = "fn.getCallTarget() == cachedTarget")
        public static Object doClojureClosureCached(
                ClojureClosure fn,
                @Variadic Object[] args,
                @com.oracle.truffle.api.dsl.Cached("fn.getCallTarget()") CallTarget cachedTarget,
                @com.oracle.truffle.api.dsl.Cached("create(cachedTarget)") DirectCallNode callNode) {
            return BytecodeInvoke.callDirect(callNode, BytecodeInvoke.withCapturedFrame(fn, args));
        }

        @Specialization(replaces = "doClojureClosureCached")
        public static Object doClojureClosureIndirect(
                ClojureClosure fn,
                @Variadic Object[] args,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeInvoke.callIndirect(callNode, fn.getCallTarget(), BytecodeInvoke.withCapturedFrame(fn, args));
        }

        @Specialization(guards = "!isClojureClosure(fn)")
        public static Object doIFn(IFn fn, @Variadic Object[] args) {
            return BytecodeInvoke.invokeIFnVariadic(fn, args);
        }

        @Specialization
        public static Object doNonIFn(Object fn, @Variadic Object[] args) {
            return BytecodeInvoke.cannotCall(fn);
        }

        protected static boolean isClojureClosure(IFn fn) {
            return BytecodeInvoke.isClojureClosure(fn);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = clojure.lang.Var.class, name = "var")
    public static final class InvokeVar0 {
        @Specialization(
                guards = {"!var.isDynamic()", "cachedFn != null"},
                assumptions = "assumption")
        public static Object doClojureClosure(
                clojure.lang.Var var,
                @com.oracle.truffle.api.dsl.Cached(value = "var.getRootAssumption()", neverDefault = true) Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached(value = "getClojureClosure(var)", neverDefault = false) ClojureClosure cachedFn,
                @com.oracle.truffle.api.dsl.Cached(value = "createCallNode(cachedFn)", neverDefault = false) DirectCallNode callNode) {
            return BytecodeInvoke.callDirect(callNode, new Object[]{cachedFn.getCapturedFrame()});
        }

        @Specialization(
                guards = {"!var.isDynamic()", "cachedFn != null"},
                assumptions = "assumption")
        public static Object doIFnCached(
                clojure.lang.Var var,
                @com.oracle.truffle.api.dsl.Cached(value = "var.getRootAssumption()", neverDefault = true) Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached(value = "getIFn(var)", neverDefault = false) IFn cachedFn) {
            return BytecodeInvoke.invokeIFn(cachedFn);
        }

        @Specialization(replaces = {"doClojureClosure", "doIFnCached"})
        public static Object doDynamic(
                clojure.lang.Var var,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            Object root = var.get();
            if (root instanceof ClojureClosure cc) {
                return BytecodeInvoke.callIndirect(callNode, cc.getCallTarget(), new Object[]{cc.getCapturedFrame()});
            } else if (root instanceof IFn fn) {
                return BytecodeInvoke.invokeIFn(fn);
            } else {
                return BytecodeInvoke.cannotCall(root);
            }
        }

        protected static ClojureClosure getClojureClosure(clojure.lang.Var var) {
            return BytecodeInvokeVar.getClojureClosure(var);
        }

        protected static IFn getIFn(clojure.lang.Var var) {
            return BytecodeInvokeVar.getIFn(var);
        }

        protected static DirectCallNode createCallNode(ClojureClosure fn) {
            return BytecodeInvokeVar.createCallNode(fn);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = clojure.lang.Var.class, name = "var")
    public static final class InvokeVar1 {
        @Specialization(
                guards = {"!var.isDynamic()", "cachedFn != null"},
                assumptions = "assumption")
        public static Object doClojureClosure(
                clojure.lang.Var var,
                Object a0,
                @com.oracle.truffle.api.dsl.Cached(value = "var.getRootAssumption()", neverDefault = true) Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached(value = "getClojureClosure(var)", neverDefault = false) ClojureClosure cachedFn,
                @com.oracle.truffle.api.dsl.Cached(value = "createCallNode(cachedFn)", neverDefault = false) DirectCallNode callNode) {
            return BytecodeInvoke.callDirect(callNode, new Object[]{cachedFn.getCapturedFrame(), a0});
        }

        @Specialization(
                guards = {"!var.isDynamic()", "cachedFn != null"},
                assumptions = "assumption")
        public static Object doIFnCached(
                clojure.lang.Var var,
                Object a0,
                @com.oracle.truffle.api.dsl.Cached(value = "var.getRootAssumption()", neverDefault = true) Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached(value = "getIFn(var)", neverDefault = false) IFn cachedFn) {
            return BytecodeInvoke.invokeIFn(cachedFn, a0);
        }

        @Specialization(replaces = {"doClojureClosure", "doIFnCached"})
        public static Object doDynamic(
                clojure.lang.Var var,
                Object a0,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            Object root = var.get();
            if (root instanceof ClojureClosure cc) {
                return BytecodeInvoke.callIndirect(callNode, cc.getCallTarget(), new Object[]{cc.getCapturedFrame(), a0});
            } else if (root instanceof IFn fn) {
                return BytecodeInvoke.invokeIFn(fn, a0);
            } else {
                return BytecodeInvoke.cannotCall(root);
            }
        }

        protected static ClojureClosure getClojureClosure(clojure.lang.Var var) {
            return BytecodeInvokeVar.getClojureClosure(var);
        }

        protected static IFn getIFn(clojure.lang.Var var) {
            return BytecodeInvokeVar.getIFn(var);
        }

        protected static DirectCallNode createCallNode(ClojureClosure fn) {
            return BytecodeInvokeVar.createCallNode(fn);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = clojure.lang.Var.class, name = "var")
    public static final class InvokeVar2 {
        @Specialization(
                guards = {"!var.isDynamic()", "cachedFn != null"},
                assumptions = "assumption")
        public static Object doClojureClosure(
                clojure.lang.Var var,
                Object a0,
                Object a1,
                @com.oracle.truffle.api.dsl.Cached(value = "var.getRootAssumption()", neverDefault = true) Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached(value = "getClojureClosure(var)", neverDefault = false) ClojureClosure cachedFn,
                @com.oracle.truffle.api.dsl.Cached(value = "createCallNode(cachedFn)", neverDefault = false) DirectCallNode callNode) {
            return BytecodeInvoke.callDirect(callNode, new Object[]{cachedFn.getCapturedFrame(), a0, a1});
        }

        @Specialization(
                guards = {"!var.isDynamic()", "cachedFn != null"},
                assumptions = "assumption")
        public static Object doIFnCached(
                clojure.lang.Var var,
                Object a0,
                Object a1,
                @com.oracle.truffle.api.dsl.Cached(value = "var.getRootAssumption()", neverDefault = true) Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached(value = "getIFn(var)", neverDefault = false) IFn cachedFn) {
            return BytecodeInvoke.invokeIFn(cachedFn, a0, a1);
        }

        @Specialization(replaces = {"doClojureClosure", "doIFnCached"})
        public static Object doDynamic(
                clojure.lang.Var var,
                Object a0,
                Object a1,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            Object root = var.get();
            if (root instanceof ClojureClosure cc) {
                return BytecodeInvoke.callIndirect(callNode, cc.getCallTarget(), new Object[]{cc.getCapturedFrame(), a0, a1});
            } else if (root instanceof IFn fn) {
                return BytecodeInvoke.invokeIFn(fn, a0, a1);
            } else {
                return BytecodeInvoke.cannotCall(root);
            }
        }

        protected static ClojureClosure getClojureClosure(clojure.lang.Var var) {
            return BytecodeInvokeVar.getClojureClosure(var);
        }

        protected static IFn getIFn(clojure.lang.Var var) {
            return BytecodeInvokeVar.getIFn(var);
        }

        protected static DirectCallNode createCallNode(ClojureClosure fn) {
            return BytecodeInvokeVar.createCallNode(fn);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = clojure.lang.Var.class, name = "var")
    public static final class InvokeVar3 {
        @Specialization(
                guards = {"!var.isDynamic()", "cachedFn != null"},
                assumptions = "assumption")
        public static Object doClojureClosure(
                clojure.lang.Var var,
                Object a0,
                Object a1,
                Object a2,
                @com.oracle.truffle.api.dsl.Cached(value = "var.getRootAssumption()", neverDefault = true) Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached(value = "getClojureClosure(var)", neverDefault = false) ClojureClosure cachedFn,
                @com.oracle.truffle.api.dsl.Cached(value = "createCallNode(cachedFn)", neverDefault = false) DirectCallNode callNode) {
            return BytecodeInvoke.callDirect(callNode, new Object[]{cachedFn.getCapturedFrame(), a0, a1, a2});
        }

        @Specialization(
                guards = {"!var.isDynamic()", "cachedFn != null"},
                assumptions = "assumption")
        public static Object doIFnCached(
                clojure.lang.Var var,
                Object a0,
                Object a1,
                Object a2,
                @com.oracle.truffle.api.dsl.Cached(value = "var.getRootAssumption()", neverDefault = true) Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached(value = "getIFn(var)", neverDefault = false) IFn cachedFn) {
            return BytecodeInvoke.invokeIFn(cachedFn, a0, a1, a2);
        }

        @Specialization(replaces = {"doClojureClosure", "doIFnCached"})
        public static Object doDynamic(
                clojure.lang.Var var,
                Object a0,
                Object a1,
                Object a2,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            Object root = var.get();
            if (root instanceof ClojureClosure cc) {
                return BytecodeInvoke.callIndirect(callNode, cc.getCallTarget(), new Object[]{cc.getCapturedFrame(), a0, a1, a2});
            } else if (root instanceof IFn fn) {
                return BytecodeInvoke.invokeIFn(fn, a0, a1, a2);
            } else {
                return BytecodeInvoke.cannotCall(root);
            }
        }

        protected static ClojureClosure getClojureClosure(clojure.lang.Var var) {
            return BytecodeInvokeVar.getClojureClosure(var);
        }

        protected static IFn getIFn(clojure.lang.Var var) {
            return BytecodeInvokeVar.getIFn(var);
        }

        protected static DirectCallNode createCallNode(ClojureClosure fn) {
            return BytecodeInvokeVar.createCallNode(fn);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = clojure.lang.Var.class, name = "var")
    public static final class InvokeVar4 {
        @Specialization(
                guards = {"!var.isDynamic()", "cachedFn != null"},
                assumptions = "assumption")
        public static Object doClojureClosure(
                clojure.lang.Var var,
                Object a0,
                Object a1,
                Object a2,
                Object a3,
                @com.oracle.truffle.api.dsl.Cached(value = "var.getRootAssumption()", neverDefault = true) Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached(value = "getClojureClosure(var)", neverDefault = false) ClojureClosure cachedFn,
                @com.oracle.truffle.api.dsl.Cached(value = "createCallNode(cachedFn)", neverDefault = false) DirectCallNode callNode) {
            return BytecodeInvoke.callDirect(callNode, new Object[]{cachedFn.getCapturedFrame(), a0, a1, a2, a3});
        }

        @Specialization(
                guards = {"!var.isDynamic()", "cachedFn != null"},
                assumptions = "assumption")
        public static Object doIFnCached(
                clojure.lang.Var var,
                Object a0,
                Object a1,
                Object a2,
                Object a3,
                @com.oracle.truffle.api.dsl.Cached(value = "var.getRootAssumption()", neverDefault = true) Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached(value = "getIFn(var)", neverDefault = false) IFn cachedFn) {
            return BytecodeInvoke.invokeIFn(cachedFn, a0, a1, a2, a3);
        }

        @Specialization(replaces = {"doClojureClosure", "doIFnCached"})
        public static Object doDynamic(
                clojure.lang.Var var,
                Object a0,
                Object a1,
                Object a2,
                Object a3,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            Object root = var.get();
            if (root instanceof ClojureClosure cc) {
                return BytecodeInvoke.callIndirect(callNode, cc.getCallTarget(), new Object[]{cc.getCapturedFrame(), a0, a1, a2, a3});
            } else if (root instanceof IFn fn) {
                return BytecodeInvoke.invokeIFn(fn, a0, a1, a2, a3);
            } else {
                return BytecodeInvoke.cannotCall(root);
            }
        }

        protected static ClojureClosure getClojureClosure(clojure.lang.Var var) {
            return BytecodeInvokeVar.getClojureClosure(var);
        }

        protected static IFn getIFn(clojure.lang.Var var) {
            return BytecodeInvokeVar.getIFn(var);
        }

        protected static DirectCallNode createCallNode(ClojureClosure fn) {
            return BytecodeInvokeVar.createCallNode(fn);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = clojure.lang.Var.class, name = "var")
    public static final class InvokeVarN {
        @Specialization(
                guards = {"!var.isDynamic()", "cachedFn != null"},
                assumptions = "assumption")
        public static Object doClojureClosure(
                clojure.lang.Var var,
                @Variadic Object[] args,
                @com.oracle.truffle.api.dsl.Cached(value = "var.getRootAssumption()", neverDefault = true) Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached(value = "getClojureClosure(var)", neverDefault = false) ClojureClosure cachedFn,
                @com.oracle.truffle.api.dsl.Cached(value = "createCallNode(cachedFn)", neverDefault = false) DirectCallNode callNode) {
            return BytecodeInvoke.callDirect(callNode, BytecodeInvokeVar.withCapturedFrame(cachedFn, args));
        }

        @Specialization(
                guards = {"!var.isDynamic()", "cachedFn != null"},
                assumptions = "assumption")
        public static Object doIFnCached(
                clojure.lang.Var var,
                @Variadic Object[] args,
                @com.oracle.truffle.api.dsl.Cached(value = "var.getRootAssumption()", neverDefault = true) Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached(value = "getIFn(var)", neverDefault = false) IFn cachedFn) {
            return BytecodeInvoke.invokeIFnVariadic(cachedFn, args);
        }

        @Specialization(replaces = {"doClojureClosure", "doIFnCached"})
        public static Object doDynamic(
                clojure.lang.Var var,
                @Variadic Object[] args,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            Object root = var.get();
            if (root instanceof ClojureClosure cc) {
                return BytecodeInvoke.callIndirect(callNode, cc.getCallTarget(), BytecodeInvokeVar.withCapturedFrame(cc, args));
            } else if (root instanceof IFn fn) {
                return BytecodeInvoke.invokeIFnVariadic(fn, args);
            } else {
                return BytecodeInvoke.cannotCall(root);
            }
        }

        protected static ClojureClosure getClojureClosure(clojure.lang.Var var) {
            return BytecodeInvokeVar.getClojureClosure(var);
        }

        protected static IFn getIFn(clojure.lang.Var var) {
            return BytecodeInvokeVar.getIFn(var);
        }

        protected static DirectCallNode createCallNode(ClojureClosure fn) {
            return BytecodeInvokeVar.createCallNode(fn);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Keyword.class, name = "keyword")
    public static final class KeywordLookup {
        @Specialization(guards = "target == null")
        public static Object doNull(Keyword keyword, Object target) {
            return null;
        }

        @Specialization(guards = "target.getClass() == cachedClass", limit = "8")
        public static Object doILookupCached(
                Keyword keyword,
                ILookup target,
                @com.oracle.truffle.api.dsl.Cached("target.getClass()") Class<? extends ILookup> cachedClass) {
            return CompilerDirectives.castExact(target, cachedClass).valAt(keyword);
        }

        @Specialization(replaces = "doILookupCached")
        public static Object doILookupGeneric(Keyword keyword, ILookup target) {
            return target.valAt(keyword);
        }

        @Specialization(guards = {"target != null", "!isILookup(target)"})
        public static Object doGeneric(Keyword keyword, Object target) {
            return BytecodeKeywordMaps.lookupGeneric(keyword, target);
        }

        protected static boolean isILookup(Object obj) {
            return BytecodeKeywordMaps.isILookup(obj);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Keyword.class, name = "keyword")
    public static final class KeywordLookupDefault {
        @Specialization(guards = "target == null")
        public static Object doNull(Keyword keyword, Object target, Object notFound) {
            return notFound;
        }

        @Specialization(guards = "target.getClass() == cachedClass", limit = "8")
        public static Object doILookupCached(
                Keyword keyword,
                ILookup target,
                Object notFound,
                @com.oracle.truffle.api.dsl.Cached("target.getClass()") Class<? extends ILookup> cachedClass) {
            return CompilerDirectives.castExact(target, cachedClass).valAt(keyword, notFound);
        }

        @Specialization(replaces = "doILookupCached")
        public static Object doILookupGeneric(Keyword keyword, ILookup target, Object notFound) {
            return target.valAt(keyword, notFound);
        }

        @Specialization(guards = {"target != null", "!isILookup(target)"})
        public static Object doGeneric(Keyword keyword, Object target, Object notFound) {
            return BytecodeKeywordMaps.lookupGeneric(keyword, target, notFound);
        }

        protected static boolean isILookup(Object obj) {
            return BytecodeKeywordMaps.isILookup(obj);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class KeywordFieldName {
        @Specialization
        public static String doKeyword(Keyword kw) {
            return kw.getFieldName();
        }

        @Specialization
        public static String doString(String s) {
            return s;
        }

        @Specialization
        public static String doSymbol(Symbol sym) {
            return sym.toString();
        }

        @Specialization(guards = "isNullLike(o)")
        public static String doNull(Object o) {
            return "";
        }

        @Specialization(guards = {"!isNullLike(o)", "!isKeyword(o)", "!isString(o)", "!isSymbol(o)"})
        public static String doOther(Object o) {
            return BytecodeStrings.otherToString(o);
        }

        protected static boolean isKeyword(Object o) {
            return BytecodeStrings.isKeyword(o);
        }

        protected static boolean isString(Object o) {
            return BytecodeStrings.isString(o);
        }

        protected static boolean isSymbol(Object o) {
            return BytecodeStrings.isSymbol(o);
        }

        protected static boolean isNullLike(Object o) {
            return BytecodeStrings.isNullLike(o);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class ArrayCreate {
        @Specialization
        public static Object[] doCreate(int length) {
            return new Object[length];
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class ArrayWrite {
        @Specialization
        public static Object doWrite(Object[] array, int index, Object value) {
            array[index] = value;
            return value;
        }
    }

    /**
     * JVM {@code monitorenter}-style synchronization for {@code locking} / {@code monitor-enter} special
     * form. Uses {@link net.javacrumbs.cloffle.nodes.MonitorRegistry}.
     */
    @Operation(storeBytecodeIndex = true)
    public static final class MonitorEnter {
        @Specialization
        public static Object doEnter(Object obj) {
            net.javacrumbs.cloffle.nodes.MonitorRegistry.enter(obj);
            return null;
        }
    }

    /** Pairs with {@link MonitorEnter}; JVM {@code monitorexit} semantics. */
    @Operation(storeBytecodeIndex = true)
    public static final class MonitorExit {
        @Specialization
        public static Object doExit(Object obj) {
            net.javacrumbs.cloffle.nodes.MonitorRegistry.exit(obj);
            return null;
        }
    }

    /**
     * After each {@code letfn*} binding’s {@code fn*} has been evaluated into a {@link ClojureClosure},
     * materialize the current frame and set each closure’s captured frame so mutual recursion sees
     * sibling locals (same intent as {@link net.javacrumbs.cloffle.nodes.ClojureRootNode#snapshotFrame}).
     */
    @Operation(storeBytecodeIndex = true)
    public static final class WireLetFnClosures {
        @Specialization
        public static Object doWire(VirtualFrame frame, @Variadic Object[] closures) {
            MaterializedFrame snap = net.javacrumbs.cloffle.nodes.ClojureRootNode.snapshotFrame(frame);
            for (Object o : closures) {
                if (o instanceof ClojureClosure c) {
                    c.setCapturedFrame(snap);
                }
            }
            return null;
        }
    }
}
