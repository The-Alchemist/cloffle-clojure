package net.javacrumbs.cloffle.bytecode;

import org.cloffle.trufflejson.JsonScan;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Idempotent;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.api.strings.TruffleStringBuilder;
import com.oracle.truffle.api.bytecode.Variadic;
import net.javacrumbs.cloffle.Clojure;
import net.javacrumbs.cloffle.GuestNamespaceRecorder;
import net.javacrumbs.cloffle.nodes.ClojureClosure;
import clojure.lang.Associative;
import clojure.lang.Counted;
import clojure.lang.EphemeralVectorSeq;
import clojure.lang.IFn;
import clojure.lang.ILookup;
import clojure.lang.Indexed;
import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentVector;
import clojure.lang.JsonParser;
import clojure.lang.PersistentVector;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.Numbers;
import clojure.lang.PersistentList;
import clojure.lang.PersistentShapeMap;
import clojure.lang.PersistentShapeMap16;
import clojure.lang.PersistentTuple;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import net.javacrumbs.cloffle.bytecode.archive.IdentityConstant;

import java.lang.invoke.MethodHandle;
import java.util.Map;

@GenerateBytecode(
    languageClass = Clojure.class,
    enableSerialization = true,
    enableMaterializedLocalAccesses = true,
    enableTagInstrumentation = true,
    storeBytecodeIndexInFrame = true,
    // Primitive loop/let/recur stores skip EnsureObject when LocalBinding.getPrimitiveType() is
    // long/double/int (see ExprToBytecode). Object locals still go through EnsureObject so
    // ClearLocal + mixed Object stores stay on the Object path.
    boxingEliminationTypes = {long.class, double.class, int.class},
    // Lets tests assert *which* specialization is live, not just that the answer is right.
    // The lowering layer rotted away once because no suite could tell a cached shape transition
    // from the generic Var call; see AssocLoweringIntrospectionTest.
    enableSpecializationIntrospection = true,
    tagTreeNodeLibrary = CloffleBytecodeTagTreeNodeExports.class
)
@SuppressWarnings("truffle-interpreted-performance")
public abstract class CloffleBytecodeRootNode extends RootNode implements BytecodeRootNode {

    protected String name = null;

    protected CloffleBytecodeRootNode(Clojure language, FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
    }

    /**
     * Load a local's final value and clear its frame slot atomically from the bytecode
     * operation tree's perspective. A separate {@code LoadLocal}; {@code ClearLocal}
     * sequence cannot be used as a value-producing child because {@code ClearLocal} is void.
     */
    @Operation
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = LocalAccessor.class, name = "local")
    public static final class LoadAndClearLocal {
        @Specialization
        public static Object doLoadAndClear(
                VirtualFrame frame,
                LocalAccessor local,
                @Bind BytecodeNode bytecodeNode) {
            Object value = local.getObject(bytecodeNode, frame);
            // Built-in clear changes the frame kind to Illegal. With boxing elimination enabled,
            // later frame merges/debug reads expect Object and throw FrameSlotTypeException.
            // Null drops the reference while preserving a valid Object slot kind.
            local.setObject(bytecodeNode, frame, null);
            return value;
        }
    }

    /** Drop an unread Object local without changing its frame kind to Illegal. */
    @Operation
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = LocalAccessor.class, name = "local")
    public static final class ClearLocalToNull {
        @Specialization
        public static void doClear(
                VirtualFrame frame,
                LocalAccessor local,
                @Bind BytecodeNode bytecodeNode) {
            local.setObject(bytecodeNode, frame, null);
        }
    }

    @Operation
    public static final class RecordGuestNamespaceResult {
        @Specialization
        public static Object doRecord(Object result) {
            GuestNamespaceRecorder.recordNonUserIfPossible();
            return result;
        }
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
     * Debugger display names keyed by physical local offset; built and read by
     * {@link BytecodeRootDebugNames}, which also documents the keying and the Var fallback.
     * Non-transient so roots stay debuggable after bytecode serialization round-trips.
     */
    protected Map<Integer, String> bytecodeLocalOffsetDebugNames;

    public void setBytecodeLocalOffsetDebugNames(Map<Integer, String> names) {
        this.bytecodeLocalOffsetDebugNames = BytecodeRootDebugNames.store(names);
    }

    /**
     * Returns the debug name map stored directly on <em>this</em> root instance — no Var fallback.
     * The primary data source; should be populated on every root including instrumented/reparsed ones.
     */
    public Map<Integer, String> getDirectBytecodeLocalOffsetDebugNames() {
        return BytecodeRootDebugNames.direct(this);
    }

    /**
     * Resolved view used by {@link BytecodeLocalScope}: returns the direct field if populated,
     * otherwise falls back to the Var's original closure root.
     */
    public Map<Integer, String> getBytecodeLocalOffsetDebugNames() {
        return BytecodeRootDebugNames.resolve(this);
    }

    /** Resolved single-offset lookup: direct field first, then Var fallback. */
    public String getBytecodeLocalOffsetDebugName(int localOffset) {
        return BytecodeRootDebugNames.resolveOne(this, localOffset);
    }

    @Override
    public AbstractTruffleException interceptTruffleException(
            AbstractTruffleException ex,
            VirtualFrame frame,
            BytecodeNode bytecodeNode,
            int bytecodeIndex) {
        return BytecodeExceptionBoundary.interceptTruffleException(this, ex, bytecodeNode, bytecodeIndex);
    }

    /**
     * Wraps plain host exceptions so guest {@code catch} clauses can match them; see
     * {@link BytecodeExceptionBoundary#wrapInternalException}.
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
            return BytecodeExceptionBoundary.wrapInternalException(e);
        }
        return throwable;
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
            GuestNamespaceRecorder.recordVar(var);
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
        public static Object doCreateNull(
                int requiredArity, boolean isVariadic, clojure.lang.IPersistentMap meta,
                CloffleBytecodeRootNode targetNode, Object frame,
                @com.oracle.truffle.api.dsl.Cached(
                        value = "createNullClosure(requiredArity, isVariadic, meta, targetNode)",
                        neverDefault = true) ClojureClosure closure) {
            return closure;
        }

        @Specialization(guards = "frame != null")
        public static Object doCreate(int requiredArity, boolean isVariadic, clojure.lang.IPersistentMap meta,
                                      CloffleBytecodeRootNode targetNode, com.oracle.truffle.api.frame.MaterializedFrame frame) {
            return new net.javacrumbs.cloffle.nodes.ClojureClosure(targetNode.getCallTarget(), frame,
                    requiredArity, isVariadic, meta);
        }

        protected static ClojureClosure createNullClosure(
                int requiredArity, boolean isVariadic, clojure.lang.IPersistentMap meta,
                CloffleBytecodeRootNode targetNode) {
            return new ClojureClosure(targetNode.getCallTarget(), null, requiredArity, isVariadic, meta);
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


    /** Box primitives so StoreLocal stays on the Object path (avoids StoreLocal$Long quickening hazards). */
    @Operation(storeBytecodeIndex = false)
    public static final class EnsureObject {
        @Specialization
        public static Object doLong(long value) {
            return value;
        }

        @Specialization
        public static Object doDouble(double value) {
            return value;
        }

        @Specialization
        public static Object doInt(int value) {
            return value;
        }

        @Specialization
        public static Object doObject(Object value) {
            return value;
        }
    }

    @Operation(storeBytecodeIndex = false)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = long.class, name = "value")
    public static final class ConstLong {
        @Specialization
        public static long doLong(long value) {
            return value;
        }
    }

    @Operation(storeBytecodeIndex = false)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = double.class, name = "value")
    public static final class ConstDouble {
        @Specialization
        public static double doDouble(double value) {
            return value;
        }
    }

    @Operation(storeBytecodeIndex = false)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = int.class, name = "value")
    public static final class ConstInt {
        @Specialization
        public static int doInt(int value) {
            return value;
        }
    }

    @Operation(storeBytecodeIndex = false)
    public static final class UnboxLong {
        @Specialization
        public static long doLong(long value) {
            return value;
        }

        @Specialization
        public static long doInt(int value) {
            return value;
        }

        @Specialization
        public static long doNumber(Number value) {
            return RT.longCast(value);
        }

        @Specialization
        public static long doObject(Object value) {
            return RT.longCast(value);
        }
    }

    @Operation(storeBytecodeIndex = false)
    public static final class UnboxDouble {
        @Specialization
        public static double doDouble(double value) {
            return value;
        }

        @Specialization
        public static double doLong(long value) {
            return value;
        }

        @Specialization
        public static double doInt(int value) {
            return value;
        }

        @Specialization
        public static double doNumber(Number value) {
            return RT.doubleCast(value);
        }

        @Specialization
        public static double doObject(Object value) {
            return RT.doubleCast(value);
        }
    }

    @Operation(storeBytecodeIndex = false)
    public static final class UnboxInt {
        @Specialization
        public static int doInt(int value) {
            return value;
        }

        @Specialization
        public static int doLong(long value) {
            return RT.intCast(value);
        }

        @Specialization
        public static int doNumber(Number value) {
            return RT.intCast(value);
        }

        @Specialization
        public static int doObject(Object value) {
            return RT.intCast(value);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "targetClass")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "fieldName")
    public static final class StaticField {
        @Specialization(guards = "getter != null")
        public static Object doCached(
                Object targetClass, String fieldName,
                @com.oracle.truffle.api.dsl.Cached(
                        value = "createGetter(targetClass, fieldName)",
                        neverDefault = false) MethodHandle getter) {
            try {
                return getter.invokeExact();
            } catch (Throwable t) {
                throw BytecodeStaticMethod.handleException(t);
            }
        }

        @Specialization(replaces = "doCached")
        public static Object doReflective(Object targetClass, String fieldName) {
            return BytecodeInterop.staticField(targetClass, fieldName);
        }

        protected static MethodHandle createGetter(Object targetClass, String fieldName) {
            return BytecodeInterop.createStaticFieldGetter(targetClass, fieldName);
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
        @Specialization(guards = {"isLong0(resolvedMethod)", "mh != null"})
        public static long doLong(
                Object targetClass, String methodName, Object resolvedMethod,
                @com.oracle.truffle.api.dsl.Cached(value = "createExactMethodHandle(resolvedMethod)", neverDefault = false) MethodHandle mh) {
            try {
                return (long) mh.invokeExact();
            } catch (Throwable t) {
                throw BytecodeStaticMethod.handleException(t);
            }
        }

        @Specialization(guards = {"isDouble0(resolvedMethod)", "mh != null"})
        public static double doDouble(
                Object targetClass, String methodName, Object resolvedMethod,
                @com.oracle.truffle.api.dsl.Cached(value = "createExactMethodHandle(resolvedMethod)", neverDefault = false) MethodHandle mh) {
            try {
                return (double) mh.invokeExact();
            } catch (Throwable t) {
                throw BytecodeStaticMethod.handleException(t);
            }
        }

        @Specialization(guards = {"isInt0(resolvedMethod)", "mh != null"})
        public static int doInt(
                Object targetClass, String methodName, Object resolvedMethod,
                @com.oracle.truffle.api.dsl.Cached(value = "createExactMethodHandle(resolvedMethod)", neverDefault = false) MethodHandle mh) {
            try {
                return (int) mh.invokeExact();
            } catch (Throwable t) {
                throw BytecodeStaticMethod.handleException(t);
            }
        }

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

        @Specialization(replaces = {"doLong", "doDouble", "doInt", "doFast"})
        public static Object doFallback(Object targetClass, String methodName, Object resolvedMethod) {
            return BytecodeInterop.staticMethod(targetClass, methodName, resolvedMethod, BytecodeStaticMethod.EMPTY_ARRAY);
        }

        @Idempotent
        protected static boolean isLong0(Object resolvedMethod) {
            return BytecodeStaticMethod.isLong0(resolvedMethod);
        }

        @Idempotent
        protected static boolean isDouble0(Object resolvedMethod) {
            return BytecodeStaticMethod.isDouble0(resolvedMethod);
        }

        @Idempotent
        protected static boolean isInt0(Object resolvedMethod) {
            return BytecodeStaticMethod.isInt0(resolvedMethod);
        }

        protected static MethodHandle createMethodHandle(Object resolvedMethod) {
            return BytecodeStaticMethod.createMethodHandle(resolvedMethod, 0);
        }

        protected static MethodHandle createExactMethodHandle(Object resolvedMethod) {
            return BytecodeStaticMethod.createExactMethodHandle(resolvedMethod, 0);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "targetClass")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "methodName")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "resolvedMethod")
    public static final class StaticMethod1 {
        @Specialization(guards = {"isLong1(resolvedMethod)", "mh != null"})
        public static long doLong(
                Object targetClass, String methodName, Object resolvedMethod,
                long a0,
                @com.oracle.truffle.api.dsl.Cached(value = "createExactMethodHandle(resolvedMethod)", neverDefault = false) MethodHandle mh) {
            try {
                return (long) mh.invokeExact(a0);
            } catch (Throwable t) {
                throw BytecodeStaticMethod.handleException(t);
            }
        }

        @Specialization(guards = {"isDouble1(resolvedMethod)", "mh != null"})
        public static double doDouble(
                Object targetClass, String methodName, Object resolvedMethod,
                double a0,
                @com.oracle.truffle.api.dsl.Cached(value = "createExactMethodHandle(resolvedMethod)", neverDefault = false) MethodHandle mh) {
            try {
                return (double) mh.invokeExact(a0);
            } catch (Throwable t) {
                throw BytecodeStaticMethod.handleException(t);
            }
        }

        @Specialization(guards = {"isInt1(resolvedMethod)", "mh != null"})
        public static int doInt(
                Object targetClass, String methodName, Object resolvedMethod,
                int a0,
                @com.oracle.truffle.api.dsl.Cached(value = "createExactMethodHandle(resolvedMethod)", neverDefault = false) MethodHandle mh) {
            try {
                return (int) mh.invokeExact(a0);
            } catch (Throwable t) {
                throw BytecodeStaticMethod.handleException(t);
            }
        }

        @Specialization(guards = {"isIntReturn1(resolvedMethod)", "mh != null"})
        public static int doIntReturn(
                Object targetClass, String methodName, Object resolvedMethod,
                Object a0,
                @com.oracle.truffle.api.dsl.Cached(value = "createExactMethodHandle(resolvedMethod)", neverDefault = false) MethodHandle mh) {
            try {
                return (int) mh.invokeExact(BytecodeStaticMethod.unwrap(a0));
            } catch (Throwable t) {
                throw BytecodeStaticMethod.handleException(t);
            }
        }

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

        @Specialization(replaces = {"doLong", "doDouble", "doInt", "doIntReturn", "doFast"})
        public static Object doFallback(Object targetClass, String methodName, Object resolvedMethod, Object a0) {
            return BytecodeInterop.staticMethod(targetClass, methodName, resolvedMethod, new Object[]{a0});
        }

        @Idempotent
        protected static boolean isLong1(Object resolvedMethod) {
            return BytecodeStaticMethod.isLong1(resolvedMethod);
        }

        @Idempotent
        protected static boolean isDouble1(Object resolvedMethod) {
            return BytecodeStaticMethod.isDouble1(resolvedMethod);
        }

        @Idempotent
        protected static boolean isInt1(Object resolvedMethod) {
            return BytecodeStaticMethod.isInt1(resolvedMethod);
        }

        @Idempotent
        protected static boolean isIntReturn1(Object resolvedMethod) {
            return BytecodeStaticMethod.isIntReturn1(resolvedMethod);
        }

        protected static MethodHandle createMethodHandle(Object resolvedMethod) {
            return BytecodeStaticMethod.createMethodHandle(resolvedMethod, 1);
        }

        protected static MethodHandle createExactMethodHandle(Object resolvedMethod) {
            return BytecodeStaticMethod.createExactMethodHandle(resolvedMethod, 1);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "targetClass")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "methodName")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Object.class, name = "resolvedMethod")
    public static final class StaticMethod2 {
        @Specialization(guards = {"isLongLong2(resolvedMethod)", "mh != null"})
        public static long doLongLong(
                Object targetClass, String methodName, Object resolvedMethod,
                long a0, long a1,
                @com.oracle.truffle.api.dsl.Cached(value = "createExactMethodHandle(resolvedMethod)", neverDefault = false) MethodHandle mh) {
            try {
                return (long) mh.invokeExact(a0, a1);
            } catch (Throwable t) {
                throw BytecodeStaticMethod.handleException(t);
            }
        }

        @Specialization(guards = {"isDoubleDouble2(resolvedMethod)", "mh != null"})
        public static double doDoubleDouble(
                Object targetClass, String methodName, Object resolvedMethod,
                double a0, double a1,
                @com.oracle.truffle.api.dsl.Cached(value = "createExactMethodHandle(resolvedMethod)", neverDefault = false) MethodHandle mh) {
            try {
                return (double) mh.invokeExact(a0, a1);
            } catch (Throwable t) {
                throw BytecodeStaticMethod.handleException(t);
            }
        }

        @Specialization(guards = {"isIntInt2(resolvedMethod)", "mh != null"})
        public static int doIntInt(
                Object targetClass, String methodName, Object resolvedMethod,
                int a0, int a1,
                @com.oracle.truffle.api.dsl.Cached(value = "createExactMethodHandle(resolvedMethod)", neverDefault = false) MethodHandle mh) {
            try {
                return (int) mh.invokeExact(a0, a1);
            } catch (Throwable t) {
                throw BytecodeStaticMethod.handleException(t);
            }
        }

        @Specialization(guards = {"isBoolLongLong2(resolvedMethod)", "mh != null"})
        public static boolean doBoolLongLong(
                Object targetClass, String methodName, Object resolvedMethod,
                long a0, long a1,
                @com.oracle.truffle.api.dsl.Cached(value = "createExactMethodHandle(resolvedMethod)", neverDefault = false) MethodHandle mh) {
            try {
                return (boolean) mh.invokeExact(a0, a1);
            } catch (Throwable t) {
                throw BytecodeStaticMethod.handleException(t);
            }
        }

        @Specialization(guards = {"isBoolDoubleDouble2(resolvedMethod)", "mh != null"})
        public static boolean doBoolDoubleDouble(
                Object targetClass, String methodName, Object resolvedMethod,
                double a0, double a1,
                @com.oracle.truffle.api.dsl.Cached(value = "createExactMethodHandle(resolvedMethod)", neverDefault = false) MethodHandle mh) {
            try {
                return (boolean) mh.invokeExact(a0, a1);
            } catch (Throwable t) {
                throw BytecodeStaticMethod.handleException(t);
            }
        }

        @Specialization(guards = {"isObjectInt2(resolvedMethod)", "mh != null"})
        public static Object doObjectInt(
                Object targetClass, String methodName, Object resolvedMethod,
                Object a0, int a1,
                @com.oracle.truffle.api.dsl.Cached(value = "createExactMethodHandle(resolvedMethod)", neverDefault = false) MethodHandle mh) {
            try {
                return (Object) mh.invokeExact(BytecodeStaticMethod.unwrap(a0), a1);
            } catch (Throwable t) {
                throw BytecodeStaticMethod.handleException(t);
            }
        }

        @Specialization(guards = {"isObjectInt2(resolvedMethod)", "mh != null"})
        public static Object doObjectIntFromLong(
                Object targetClass, String methodName, Object resolvedMethod,
                Object a0, long a1,
                @com.oracle.truffle.api.dsl.Cached(value = "createExactMethodHandle(resolvedMethod)", neverDefault = false) MethodHandle mh) {
            try {
                return (Object) mh.invokeExact(BytecodeStaticMethod.unwrap(a0), RT.intCast(a1));
            } catch (Throwable t) {
                throw BytecodeStaticMethod.handleException(t);
            }
        }

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

        @Specialization(replaces = {
                "doLongLong", "doDoubleDouble", "doIntInt",
                "doBoolLongLong", "doBoolDoubleDouble",
                "doObjectInt", "doObjectIntFromLong", "doFast"})
        public static Object doFallback(Object targetClass, String methodName, Object resolvedMethod, Object a0, Object a1) {
            return BytecodeInterop.staticMethod(targetClass, methodName, resolvedMethod, new Object[]{a0, a1});
        }

        @Idempotent
        protected static boolean isLongLong2(Object resolvedMethod) {
            return BytecodeStaticMethod.isLongLong2(resolvedMethod);
        }

        @Idempotent
        protected static boolean isDoubleDouble2(Object resolvedMethod) {
            return BytecodeStaticMethod.isDoubleDouble2(resolvedMethod);
        }

        @Idempotent
        protected static boolean isIntInt2(Object resolvedMethod) {
            return BytecodeStaticMethod.isIntInt2(resolvedMethod);
        }

        @Idempotent
        protected static boolean isBoolLongLong2(Object resolvedMethod) {
            return BytecodeStaticMethod.isBoolLongLong2(resolvedMethod);
        }

        @Idempotent
        protected static boolean isBoolDoubleDouble2(Object resolvedMethod) {
            return BytecodeStaticMethod.isBoolDoubleDouble2(resolvedMethod);
        }

        @Idempotent
        protected static boolean isObjectInt2(Object resolvedMethod) {
            return BytecodeStaticMethod.isObjectInt2(resolvedMethod);
        }

        protected static MethodHandle createMethodHandle(Object resolvedMethod) {
            return BytecodeStaticMethod.createMethodHandle(resolvedMethod, 2);
        }

        protected static MethodHandle createExactMethodHandle(Object resolvedMethod) {
            return BytecodeStaticMethod.createExactMethodHandle(resolvedMethod, 2);
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

    // ── Shaped map creation (constant-operand path) ──────────────────────
    // Values are emitted in source / insertion order, matching MapShape slots.
    // The factory is a @ConstantOperand so the shape folds at compile time.

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = clojure.lang.MapShape.Factory.class, name = "factory")
    public static final class CreateMapShaped1 {
        @Specialization
        public static Object doCreate(clojure.lang.MapShape.Factory factory, Object v0) {
            return BytecodeCreateMap.createShaped1(factory, v0);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = clojure.lang.MapShape.Factory.class, name = "factory")
    public static final class CreateMapShaped2 {
        @Specialization
        public static Object doCreate(clojure.lang.MapShape.Factory factory, Object v0, Object v1) {
            return BytecodeCreateMap.createShaped2(factory, v0, v1);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = clojure.lang.MapShape.Factory.class, name = "factory")
    public static final class CreateMapShaped3 {
        @Specialization
        public static Object doCreate(clojure.lang.MapShape.Factory factory, Object v0, Object v1, Object v2) {
            return BytecodeCreateMap.createShaped3(factory, v0, v1, v2);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = clojure.lang.MapShape.Factory.class, name = "factory")
    public static final class CreateMapShaped4 {
        @Specialization
        public static Object doCreate(clojure.lang.MapShape.Factory factory, Object v0, Object v1, Object v2, Object v3) {
            return BytecodeCreateMap.createShaped4(factory, v0, v1, v2, v3);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = clojure.lang.MapShape.Factory.class, name = "factory")
    public static final class CreateMapShaped5 {
        @Specialization
        public static Object doCreate(clojure.lang.MapShape.Factory factory, Object v0, Object v1, Object v2, Object v3, Object v4) {
            return BytecodeCreateMap.createShaped5(factory, v0, v1, v2, v3, v4);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = clojure.lang.MapShape.Factory.class, name = "factory")
    public static final class CreateMapShaped6 {
        @Specialization
        public static Object doCreate(clojure.lang.MapShape.Factory factory, Object v0, Object v1, Object v2, Object v3, Object v4, Object v5) {
            return BytecodeCreateMap.createShaped6(factory, v0, v1, v2, v3, v4, v5);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = clojure.lang.MapShape.Factory.class, name = "factory")
    public static final class CreateMapShaped7 {
        @Specialization
        public static Object doCreate(clojure.lang.MapShape.Factory factory, Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6) {
            return BytecodeCreateMap.createShaped7(factory, v0, v1, v2, v3, v4, v5, v6);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = clojure.lang.MapShape.Factory.class, name = "factory")
    public static final class CreateMapShaped8 {
        @Specialization
                public static Object doCreate(clojure.lang.MapShape.Factory factory, Object v0, Object v1, Object v2, Object v3, Object v4, Object v5, Object v6, Object v7) {
            return BytecodeCreateMap.createShaped8(factory, v0, v1, v2, v3, v4, v5, v6, v7);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = PersistentShapeMap16.Factory.class, name = "factory")
    public static final class CreateMapShaped16 {
        @Specialization
        public static Object doCreate(PersistentShapeMap16.Factory factory,
                                      Object v0, Object v1, Object v2, Object v3,
                                      Object v4, Object v5, Object v6, Object v7,
                                      Object v8, Object v9, Object v10, Object v11,
                                      Object v12, Object v13, Object v14, Object v15) {
            return BytecodeCreateMap.createShaped16(factory, v0, v1, v2, v3, v4, v5, v6, v7,
                    v8, v9, v10, v11, v12, v13, v14, v15);
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

        @Specialization(guards = "cachedShape.sameKeys(target.shape)", limit = "2")
        public static Object doShapeMap(
                Keyword keyword,
                PersistentShapeMap target,
                @com.oracle.truffle.api.dsl.Cached("target.shape") clojure.lang.MapShape cachedShape,
                @com.oracle.truffle.api.dsl.Cached("cachedShape.indexOf(keyword)") int cachedSlot) {
            return BytecodeKeywordMaps.slotValue(target, cachedSlot, null);
        }

        @Specialization(replaces = "doShapeMap")
        public static Object doShapeMapGeneric(Keyword keyword, PersistentShapeMap target) {
            return BytecodeKeywordMaps.lookup(target, keyword);
        }

        @Specialization(guards = "cached.matches(target, keyword)", limit = "2")
        public static Object doShapeMap16(
                Keyword keyword,
                PersistentShapeMap16 target,
                @com.oracle.truffle.api.dsl.Cached("lookup16Transition(target, keyword)")
                        PersistentShapeMap16.Lookup16Transition cached) {
            return cached.get(target, null);
        }

        @Specialization(replaces = "doShapeMap16")
        public static Object doShapeMap16Generic(Keyword keyword, PersistentShapeMap16 target) {
            return BytecodeKeywordMaps.lookup(target, keyword);
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
            return BytecodeKeywordMaps.lookup(target, keyword);
        }

        @Specialization(guards = {"target != null", "!isILookup(target)"})
        public static Object doGeneric(Keyword keyword, Object target) {
            return BytecodeKeywordMaps.lookupGeneric(keyword, target);
        }

        protected static boolean isILookup(Object obj) {
            return BytecodeKeywordMaps.isILookup(obj);
        }

        protected static PersistentShapeMap16.Lookup16Transition lookup16Transition(
                PersistentShapeMap16 map, Keyword keyword) {
            return BytecodeKeywordMaps.lookup16Transition(map, keyword);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Keyword.class, name = "keyword")
    public static final class KeywordLookupDefault {
        @Specialization(guards = "target == null")
        public static Object doNull(Keyword keyword, Object target, Object notFound) {
            return notFound;
        }

        @Specialization(guards = "cachedShape.sameKeys(target.shape)", limit = "2")
        public static Object doShapeMap(
                Keyword keyword,
                PersistentShapeMap target,
                Object notFound,
                @com.oracle.truffle.api.dsl.Cached("target.shape") clojure.lang.MapShape cachedShape,
                @com.oracle.truffle.api.dsl.Cached("cachedShape.indexOf(keyword)") int cachedSlot) {
            return BytecodeKeywordMaps.slotValue(target, cachedSlot, notFound);
        }

        @Specialization(replaces = "doShapeMap")
        public static Object doShapeMapGeneric(Keyword keyword, PersistentShapeMap target, Object notFound) {
            return BytecodeKeywordMaps.lookup(target, keyword, notFound);
        }

        @Specialization(guards = "cached.matches(target, keyword)", limit = "2")
        public static Object doShapeMap16(
                Keyword keyword,
                PersistentShapeMap16 target,
                Object notFound,
                @com.oracle.truffle.api.dsl.Cached("lookup16Transition(target, keyword)")
                        PersistentShapeMap16.Lookup16Transition cached) {
            return cached.get(target, notFound);
        }

        @Specialization(replaces = "doShapeMap16")
        public static Object doShapeMap16Generic(Keyword keyword, PersistentShapeMap16 target, Object notFound) {
            return BytecodeKeywordMaps.lookup(target, keyword, notFound);
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
            return BytecodeKeywordMaps.lookup(target, keyword, notFound);
        }

        @Specialization(guards = {"target != null", "!isILookup(target)"})
        public static Object doGeneric(Keyword keyword, Object target, Object notFound) {
            return BytecodeKeywordMaps.lookupGeneric(keyword, target, notFound);
        }

        protected static boolean isILookup(Object obj) {
            return BytecodeKeywordMaps.isILookup(obj);
        }

        protected static PersistentShapeMap16.Lookup16Transition lookup16Transition(
                PersistentShapeMap16 map, Keyword keyword) {
            return BytecodeKeywordMaps.lookup16Transition(map, keyword);
        }
    }

    /**
     * The assumption every Tier 2 fast specialization runs under; see
     * {@link BytecodeLowering#sanctionedRootAssumption}. Kept here because each nested operation's
     * {@code loweringAssumption} resolves against the enclosing class.
     */
    protected static Assumption sanctionedRootAssumption(Var var) {
        return BytecodeLowering.sanctionedRootAssumption(var);
    }

    /**
     * Lowered {@code (assoc m :k v)}: arity 3 with a literal {@link Keyword} key.
     *
     * <p>Without this operation every {@code assoc} in the program funnels through a single
     * {@code InvokeVar3} → {@code clojure.core/assoc} → {@link RT#assoc} CallTarget, so no call site
     * can hold a shape cache and the result never scalar-replaces.
     *
     * <p>{@code clojure.core/assoc} is only {@code :static}, not {@code :inline}, upstream — it is
     * legitimately redefinable. Lowering it unconditionally would reopen
     * {@code COMPATIBILITY_RISK_AUDIT.md} Finding 2. Every fast specialization is therefore gated on
     * {@link CloffleBytecodeRootNode#loweringAssumption}; a {@code with-redefs} makes it permanently
     * invalid for this Var and
     * the node re-specializes to {@link #doRedefined}, which calls whatever the Var now holds.
     *
     * <p>Specialization order is policy, not taste: the concrete {@code @ValueType}
     * ({@link PersistentShapeMap}) comes first with a cache that turns layout work into constants,
     * then same-type uncached, then a {@code castExact} class cache, then generic. Naming
     * {@link Associative} first would show partial escape analysis an interface call and nothing
     * would virtualize.
     *
     * <p>The transition guard is keyword identity plus {@code MapShape.sameKeys}, never shape
     * identity — shapes stopped being canonicalized in {@code ca1ba0c5}.
     */
    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Keyword.class, name = "keyword")
    public static final class KeywordAssoc {
        @Specialization(guards = "target == null", assumptions = "assumption")
        public static Object doNull(
                Var var,
                Keyword keyword,
                Object target,
                Object val,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeKeywordMaps.assocNull(keyword, val);
        }

        @Specialization(guards = "cached.matches(target, keyword)", assumptions = "assumption", limit = "4")
        public static Object doShapeMap(
                Var var,
                Keyword keyword,
                PersistentShapeMap target,
                Object val,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached("assocTransition(target, keyword)")
                        PersistentShapeMap.AssocTransition cached) {
            return cached.apply(target, val);
        }

        @Specialization(replaces = "doShapeMap", assumptions = "assumption")
        public static Object doShapeMapGeneric(
                Var var,
                Keyword keyword,
                PersistentShapeMap target,
                Object val,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeKeywordMaps.assoc(target, keyword, val);
        }

        @Specialization(guards = "cached.matches(target, keyword)",
                assumptions = "assumption", limit = "4")
        public static Object doShapeMap16(
                Var var,
                Keyword keyword,
                PersistentShapeMap16 target,
                Object val,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached("assoc16Transition(target, keyword)")
                        PersistentShapeMap16.Assoc16Transition cached) {
            return cached.apply(target, val);
        }

        @Specialization(replaces = "doShapeMap16", assumptions = "assumption")
        public static Object doShapeMap16Generic(
                Var var,
                Keyword keyword,
                PersistentShapeMap16 target,
                Object val,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeKeywordMaps.assoc(target, keyword, val);
        }

        @Specialization(guards = "target.getClass() == cachedClass", assumptions = "assumption", limit = "8")
        public static Object doAssociativeCached(
                Var var,
                Keyword keyword,
                Associative target,
                Object val,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached("target.getClass()") Class<? extends Associative> cachedClass) {
            return CompilerDirectives.castExact(target, cachedClass).assoc(keyword, val);
        }

        @Specialization(replaces = "doAssociativeCached", assumptions = "assumption")
        public static Object doAssociativeGeneric(
                Var var,
                Keyword keyword,
                Associative target,
                Object val,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeKeywordMaps.assoc(target, keyword, val);
        }

        /** Non-{@link Associative}, non-null receiver: see {@link BytecodeKeywordMaps#assocGeneric}. */
        @Specialization(guards = {"target != null", "!isAssociative(target)"}, assumptions = "assumption")
        public static Object doNotAssociative(
                Var var,
                Keyword keyword,
                Object target,
                Object val,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeKeywordMaps.assocGeneric(target, keyword, val);
        }

        /**
         * The Var no longer holds its original root (a {@code with-redefs} ran). Call it like any other
         * 3-argument Var invocation; the constant keyword becomes an ordinary argument again.
         */
        @Specialization(replaces = {
                "doNull", "doShapeMap", "doShapeMapGeneric",
                "doShapeMap16", "doShapeMap16Generic",
                "doAssociativeCached", "doAssociativeGeneric", "doNotAssociative"})
        public static Object doRedefined(
                Var var,
                Keyword keyword,
                Object target,
                Object val,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, target, keyword, val);
        }


        /**
         * Local handle on {@link CloffleBytecodeRootNode#sanctionedRootAssumption}. The Truffle DSL
         * resolves {@code @Cached} expressions against the operation class only, so each Tier 2
         * operation needs its own entry point into the shared guard.
         */
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }

        protected static PersistentShapeMap.AssocTransition assocTransition(PersistentShapeMap map, Keyword keyword) {
            return BytecodeKeywordMaps.assocTransition(map, keyword);
        }

        protected static PersistentShapeMap16.Assoc16Transition assoc16Transition(
                PersistentShapeMap16 map, Keyword keyword) {
            return BytecodeKeywordMaps.assoc16Transition(map, keyword);
        }

        protected static boolean isAssociative(Object obj) {
            return BytecodeKeywordMaps.isAssociative(obj);
        }
    }

    /**
     * Lowered {@code (dissoc m :k)}: arity 2 with a literal {@link Keyword} key.
     *
     * <p>The {@code KeywordAssoc} argument applies unchanged. Without this operation every
     * {@code dissoc} funnels through one {@code InvokeVar2} → {@code clojure.core/dissoc} →
     * {@link RT#dissoc} CallTarget, so no call site holds a shape cache and the result cannot
     * scalar-replace.
     *
     * <p>{@code clojure.core/dissoc} is {@code :static} but not {@code :inline} upstream, so it is
     * legitimately redefinable and this is a Tier 2 lowering: every fast specialization is gated on
     * {@link CloffleBytecodeRootNode#loweringAssumption} and a {@code with-redefs} retires the whole
     * fast path to {@link #doRedefined}.
     *
     * <p>Both shaped map classes get their own cached transition because
     * {@link PersistentShapeMap16} is a sibling of {@link PersistentShapeMap}, not a subclass, and a
     * shared {@link IPersistentMap} specialization would show partial escape analysis an interface
     * call. {@code Dissoc16Transition} additionally covers the 9→8 demotion back into
     * {@link PersistentShapeMap}. Counts 10–16 have no cached plan ({@code dissocTransition}
     * returns null), so {@link #doShapeMap16} is gated on {@code count == 9} and those maps fall
     * through to {@link #doShapeMap16Generic}. Evaluating the cache initializer first would NPE
     * on {@code cached.matches}.
     */
    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Keyword.class, name = "keyword")
    public static final class KeywordDissoc {
        /** {@link RT#dissoc} returns null for a null receiver rather than throwing. */
        @Specialization(guards = "target == null", assumptions = "assumption")
        public static Object doNull(
                Var var,
                Keyword keyword,
                Object target,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return null;
        }

        @Specialization(guards = "cached.matches(target, keyword)", assumptions = "assumption", limit = "4")
        public static Object doShapeMap(
                Var var,
                Keyword keyword,
                PersistentShapeMap target,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached("dissocTransition(target, keyword)")
                        PersistentShapeMap.DissocTransition cached) {
            return cached.apply(target);
        }

        @Specialization(replaces = "doShapeMap", assumptions = "assumption")
        public static Object doShapeMapGeneric(
                Var var,
                Keyword keyword,
                PersistentShapeMap target,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeKeywordMaps.without(target, keyword);
        }

        @Specialization(guards = {"target.count == 9", "cached.matches(target, keyword)"},
                assumptions = "assumption", limit = "4")
        public static Object doShapeMap16(
                Var var,
                Keyword keyword,
                PersistentShapeMap16 target,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached("dissoc16Transition(target, keyword)")
                        PersistentShapeMap16.Dissoc16Transition cached) {
            return cached.apply(target);
        }

        @Specialization(replaces = "doShapeMap16", assumptions = "assumption")
        public static Object doShapeMap16Generic(
                Var var,
                Keyword keyword,
                PersistentShapeMap16 target,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeKeywordMaps.without(target, keyword);
        }

        @Specialization(guards = "target.getClass() == cachedClass", assumptions = "assumption", limit = "8")
        public static Object doMapCached(
                Var var,
                Keyword keyword,
                IPersistentMap target,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached("target.getClass()") Class<? extends IPersistentMap> cachedClass) {
            return CompilerDirectives.castExact(target, cachedClass).without(keyword);
        }

        @Specialization(replaces = "doMapCached", assumptions = "assumption")
        public static Object doMapGeneric(
                Var var,
                Keyword keyword,
                IPersistentMap target,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeKeywordMaps.without(target, keyword);
        }

        /** Non-{@link IPersistentMap}, non-null receiver: see {@link BytecodeKeywordMaps#dissocGeneric}. */
        @Specialization(guards = {"target != null", "!isMap(target)"}, assumptions = "assumption")
        public static Object doNotMap(
                Var var,
                Keyword keyword,
                Object target,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeKeywordMaps.dissocGeneric(target, keyword);
        }

        /**
         * The Var no longer holds its original root (a {@code with-redefs} ran). Call it like any other
         * 2-argument Var invocation; the constant keyword becomes an ordinary argument again.
         */
        @Specialization(replaces = {
                "doNull", "doShapeMap", "doShapeMapGeneric", "doShapeMap16", "doShapeMap16Generic",
                "doMapCached", "doMapGeneric", "doNotMap"})
        public static Object doRedefined(
                Var var,
                Keyword keyword,
                Object target,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, target, keyword);
        }


        /**
         * Local handle on {@link CloffleBytecodeRootNode#sanctionedRootAssumption}. The Truffle DSL
         * resolves {@code @Cached} expressions against the operation class only, so each Tier 2
         * operation needs its own entry point into the shared guard.
         */
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }

        protected static PersistentShapeMap.DissocTransition dissocTransition(
                PersistentShapeMap map, Keyword keyword) {
            return BytecodeKeywordMaps.dissocTransition(map, keyword);
        }

        protected static PersistentShapeMap16.Dissoc16Transition dissoc16Transition(
                PersistentShapeMap16 map, Keyword keyword) {
            return BytecodeKeywordMaps.dissoc16Transition(map, keyword);
        }

        protected static boolean isMap(Object obj) {
            return BytecodeKeywordMaps.isMap(obj);
        }
    }

    /**
     * Lowered {@code (conj coll x)} at arity 2: direct {@link PersistentTuple} growth for the empty
     * vector and tuple ladder instead of a virtual {@code cons} behind {@code RT/conj}.
     *
     * <p>Tier 2 lowering on {@code #'conj}: guarded by {@link #loweringAssumption}. A plain call-site
     * split to {@code RT/conj} was measured and rejected — see {@code TODO_lowering_layer.md} Phase 2
     * step 5. This operation only wins when it can emit a precomputed grow (constructor), not when it
     * delegates to {@code coll.cons(x)}.
     */
    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class TupleConj {
        @Specialization(guards = "coll == null", assumptions = "assumption")
        public static Object doNull(
                Var var,
                Object coll,
                Object x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeTupleConj.nullConj(x);
        }

        @Specialization(guards = "isEmptyVector(coll)", assumptions = "assumption")
        public static Object doEmptyVector(
                Var var,
                PersistentVector coll,
                Object x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeTupleConj.emptyVector(coll, x);
        }

        @Specialization(assumptions = "assumption")
        public static Object doTuple1(
                Var var,
                PersistentTuple.PersistentTuple1 coll,
                Object x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeTupleConj.tuple1(coll, x);
        }

        @Specialization(assumptions = "assumption")
        public static Object doTuple2(
                Var var,
                PersistentTuple.PersistentTuple2 coll,
                Object x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeTupleConj.tuple2(coll, x);
        }

        @Specialization(assumptions = "assumption")
        public static Object doTuple3(
                Var var,
                PersistentTuple.PersistentTuple3 coll,
                Object x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeTupleConj.tuple3(coll, x);
        }

        @Specialization(assumptions = "assumption")
        public static Object doTuple4(
                Var var,
                PersistentTuple.PersistentTuple4 coll,
                Object x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeTupleConj.tuple4(coll, x);
        }

        @Specialization(assumptions = "assumption")
        public static Object doTuple5(
                Var var,
                PersistentTuple.PersistentTuple5 coll,
                Object x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeTupleConj.tuple5(coll, x);
        }

        @Specialization(assumptions = "assumption")
        public static Object doTuple6(
                Var var,
                PersistentTuple.PersistentTuple6 coll,
                Object x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeTupleConj.tuple6(coll, x);
        }

        @Specialization(assumptions = "assumption")
        public static Object doTuple7(
                Var var,
                PersistentTuple.PersistentTuple7 coll,
                Object x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeTupleConj.tuple7(coll, x);
        }

        @Specialization(assumptions = "assumption")
        public static Object doTuple8(
                Var var,
                PersistentTuple.PersistentTuple8 coll,
                Object x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeTupleConj.tuple8(coll, x);
        }

        @Specialization(replaces = {
                "doNull", "doEmptyVector", "doTuple1", "doTuple2", "doTuple3", "doTuple4",
                "doTuple5", "doTuple6", "doTuple7", "doTuple8"},
                assumptions = "assumption")
        public static Object doGeneric(
                Var var,
                Object coll,
                Object x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeTupleConj.generic(coll, x);
        }

        @Specialization(replaces = {
                "doNull", "doEmptyVector", "doTuple1", "doTuple2", "doTuple3", "doTuple4",
                "doTuple5", "doTuple6", "doTuple7", "doTuple8", "doGeneric"})
        public static Object doRedefined(
                Var var,
                Object coll,
                Object x,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, coll, x);
        }

        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }

        protected static boolean isEmptyVector(PersistentVector coll) {
            return BytecodeTupleConj.isEmptyVector(coll);
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
     * Schema-compiled typed projection. Scanning is a boundary, while TruffleString decoding and
     * fixed-shape assembly remain visible to partial evaluation.
     */
    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(
            type = JsonTypedProjectPlan.class, name = "plan")
    public static final class JsonTypedProject {
        @Specialization(guards = "isPrimitiveRoot(plan, 1)", assumptions = "assumption")
        public static int doInt(
                JsonTypedProjectPlan plan,
                Object source,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(plan)")
                        Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached.Shared("typedFromBytes")
                @com.oracle.truffle.api.dsl.Cached
                        TruffleString.FromByteArrayNode fromByteArray,
                @com.oracle.truffle.api.dsl.Cached.Shared("typedParseInt")
                @com.oracle.truffle.api.dsl.Cached
                        TruffleString.ParseIntNode parseInt) {
            return plan.decodeRootInt(plan.scan(source), fromByteArray, parseInt);
        }

        @Specialization(guards = "isPrimitiveRoot(plan, 2)", assumptions = "assumption")
        public static long doLong(
                JsonTypedProjectPlan plan,
                Object source,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(plan)")
                        Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached.Shared("typedFromBytes")
                @com.oracle.truffle.api.dsl.Cached
                        TruffleString.FromByteArrayNode fromByteArray,
                @com.oracle.truffle.api.dsl.Cached.Shared("typedParseLong")
                @com.oracle.truffle.api.dsl.Cached
                        TruffleString.ParseLongNode parseLong) {
            return plan.decodeRootLong(plan.scan(source), fromByteArray, parseLong);
        }

        @Specialization(guards = "isPrimitiveRoot(plan, 3)", assumptions = "assumption")
        public static double doDouble(
                JsonTypedProjectPlan plan,
                Object source,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(plan)")
                        Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached.Shared("typedFromBytes")
                @com.oracle.truffle.api.dsl.Cached
                        TruffleString.FromByteArrayNode fromByteArray,
                @com.oracle.truffle.api.dsl.Cached.Shared("typedParseDouble")
                @com.oracle.truffle.api.dsl.Cached
                        TruffleString.ParseDoubleNode parseDouble) {
            return plan.decodeRootDouble(plan.scan(source), fromByteArray, parseDouble);
        }

        @Specialization(guards = "isPrimitiveRoot(plan, 4)", assumptions = "assumption")
        public static boolean doBoolean(
                JsonTypedProjectPlan plan,
                Object source,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(plan)")
                        Assumption assumption) {
            return plan.decodeRootBoolean(plan.scan(source));
        }

        @Specialization(
                replaces = {"doInt", "doLong", "doDouble", "doBoolean"},
                assumptions = "assumption")
        public static Object doProject(
                JsonTypedProjectPlan plan,
                Object source,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(plan)")
                        Assumption assumption,
                @com.oracle.truffle.api.dsl.Cached.Shared("typedFromBytes")
                @com.oracle.truffle.api.dsl.Cached
                        TruffleString.FromByteArrayNode fromByteArray,
                @com.oracle.truffle.api.dsl.Cached
                        TruffleString.SubstringByteIndexNode substring,
                @com.oracle.truffle.api.dsl.Cached
                        TruffleString.ToJavaStringNode toJava,
                @com.oracle.truffle.api.dsl.Cached.Shared("typedParseInt")
                @com.oracle.truffle.api.dsl.Cached
                        TruffleString.ParseIntNode parseInt,
                @com.oracle.truffle.api.dsl.Cached.Shared("typedParseLong")
                @com.oracle.truffle.api.dsl.Cached
                        TruffleString.ParseLongNode parseLong,
                @com.oracle.truffle.api.dsl.Cached.Shared("typedParseDouble")
                @com.oracle.truffle.api.dsl.Cached
                        TruffleString.ParseDoubleNode parseDouble,
                @com.oracle.truffle.api.dsl.Cached
                        TruffleString.MaterializeSubstringNode materialize,
                @com.oracle.truffle.api.dsl.Cached
                        TruffleStringBuilder.AppendSubstringByteIndexNode appendSubstring,
                @com.oracle.truffle.api.dsl.Cached
                        TruffleStringBuilder.AppendCodePointNode appendCodePoint,
                @com.oracle.truffle.api.dsl.Cached
                        TruffleStringBuilder.ToStringNode builderToString,
                @com.oracle.truffle.api.dsl.Cached
                        TruffleString.ReadByteNode readByte) {
            JsonScan.TypedScanResult scan = plan.scan(source);
            JsonTypedProjectPlan.decode(
                    scan, plan.leaves, fromByteArray, substring, toJava,
                    parseInt, parseLong, parseDouble, materialize,
                    appendSubstring, appendCodePoint, builderToString, readByte);
            return materializeOutput(plan.output, scan.values);
        }

        /**
         * Nested maps and tuples are allocated here (not in {@code OutputNode.build}) so PEA
         * can see every {@link PersistentShapeMap} / {@link PersistentTuple} constructor.
         */
        static Object materializeOutput(JsonTypedProjectPlan.OutputNode node, Object[] slots) {
            if (node instanceof JsonTypedProjectPlan.EntryOutput entry) {
                if (entry.missing(slots)) {
                    if (entry.hasDefault) {
                        return entry.defaultValue;
                    }
                    if (entry.optional) {
                        return JsonParser.MISSING;
                    }
                    CompilerDirectives.transferToInterpreter();
                    throw new IllegalArgumentException("Required JSON field is missing");
                }
                return materializeOutput(entry.child, slots);
            }
            if (node instanceof JsonTypedProjectPlan.LeafOutput leaf) {
                return slots[leaf.slot];
            }
            if (node instanceof JsonTypedProjectPlan.ConstantOutput constant) {
                return constant.value;
            }
            if (node instanceof JsonTypedProjectPlan.MapOutput map && map.shape != null) {
                int n = map.entries.length;
                Object v0 = n > 0 ? materializeOutput(map.entries[0], slots) : null;
                Object v1 = n > 1 ? materializeOutput(map.entries[1], slots) : null;
                Object v2 = n > 2 ? materializeOutput(map.entries[2], slots) : null;
                Object v3 = n > 3 ? materializeOutput(map.entries[3], slots) : null;
                Object v4 = n > 4 ? materializeOutput(map.entries[4], slots) : null;
                Object v5 = n > 5 ? materializeOutput(map.entries[5], slots) : null;
                Object v6 = n > 6 ? materializeOutput(map.entries[6], slots) : null;
                Object v7 = n > 7 ? materializeOutput(map.entries[7], slots) : null;
                return new PersistentShapeMap(null, map.shape, v0, v1, v2, v3, v4, v5, v6, v7);
            }
            if (node instanceof JsonTypedProjectPlan.TupleOutput tuple) {
                int n = tuple.children.length;
                Object v0 = n > 0 ? materializeOutput(tuple.children[0], slots) : null;
                Object v1 = n > 1 ? materializeOutput(tuple.children[1], slots) : null;
                Object v2 = n > 2 ? materializeOutput(tuple.children[2], slots) : null;
                Object v3 = n > 3 ? materializeOutput(tuple.children[3], slots) : null;
                Object v4 = n > 4 ? materializeOutput(tuple.children[4], slots) : null;
                Object v5 = n > 5 ? materializeOutput(tuple.children[5], slots) : null;
                Object v6 = n > 6 ? materializeOutput(tuple.children[6], slots) : null;
                Object v7 = n > 7 ? materializeOutput(tuple.children[7], slots) : null;
                return switch (n) {
                    case 0 -> PersistentTuple.EMPTY;
                    case 1 -> PersistentTuple.create(v0);
                    case 2 -> PersistentTuple.create(v0, v1);
                    case 3 -> PersistentTuple.create(v0, v1, v2);
                    case 4 -> PersistentTuple.create(v0, v1, v2, v3);
                    case 5 -> PersistentTuple.create(v0, v1, v2, v3, v4);
                    case 6 -> PersistentTuple.create(v0, v1, v2, v3, v4, v5);
                    case 7 -> PersistentTuple.create(v0, v1, v2, v3, v4, v5, v6);
                    case 8 -> PersistentTuple.create(v0, v1, v2, v3, v4, v5, v6, v7);
                    default -> {
                        Object[] values = new Object[n];
                        for (int i = 0; i < n; i++) {
                            values[i] = materializeOutput(tuple.children[i], slots);
                        }
                        yield RT.vector(values);
                    }
                };
            }
            return node.build(slots);
        }

        /** Allocates in this compilation unit so PEA can see the {@link PersistentShapeMap}. */
        static PersistentShapeMap buildShapeMap(JsonTypedProjectPlan plan, Object[] slots) {
            return (PersistentShapeMap) materializeOutput(plan.output, slots);
        }

        @Specialization(replaces = "doProject")
        public static Object doRedefined(
                JsonTypedProjectPlan plan,
                Object source,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            if (plan.options == null) {
                return BytecodeLowering.invokeRedefined(
                        plan.projectVar, callNode, source, plan.schema);
            }
            return BytecodeLowering.invokeRedefined(
                    plan.projectVar, callNode, source, plan.schema, plan.options);
        }

        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(JsonTypedProjectPlan plan) {
            return plan.loweringAssumption();
        }

        protected static boolean isPrimitiveRoot(JsonTypedProjectPlan plan, int kind) {
            return plan.isPrimitiveRoot(kind);
        }
    }

    /** Fixed-arity {@code (str a b)} — avoids rest ArraySeq of variadic {@code [x & ys]}. */
    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class CoreStr2 {
        @Specialization(assumptions = "assumption")
        public static String doObjects(Var var, Object a, Object b,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeLowering.str(a, b);
        }
        @Specialization(replaces = "doObjects")
        public static Object doRedefined(Var var, Object a, Object b,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, a, b);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class CoreStr3 {
        @Specialization(assumptions = "assumption")
        public static String doObjects(Var var, Object a, Object b, Object c,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeLowering.str(a, b, c);
        }
        @Specialization(replaces = "doObjects")
        public static Object doRedefined(Var var, Object a, Object b, Object c,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, a, b, c);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class CoreStr4 {
        @Specialization(assumptions = "assumption")
        public static String doObjects(Var var, Object a, Object b, Object c, Object d,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return BytecodeLowering.str(a, b, c, d);
        }
        @Specialization(replaces = "doObjects")
        public static Object doRedefined(Var var, Object a, Object b, Object c, Object d,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, a, b, c, d);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    /** Lowered {@code (+ x y)} / unchecked variant — see NumbersAdd. */
    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class NumbersAdd {
        @Specialization(assumptions = "assumption")
        public static long doLongLong(Var var, long x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.add(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static double doDoubleDouble(Var var, double x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.add(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static double doLongDouble(Var var, long x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.add(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static double doDoubleLong(Var var, double x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.add(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static Object doGeneric(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.add(x, y);
        }
        @Specialization(replaces = {"doLongLong", "doDoubleDouble", "doLongDouble", "doDoubleLong", "doGeneric"})
        public static Object doRedefined(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, x, y);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class NumbersUncheckedAdd {
        @Specialization(assumptions = "assumption")
        public static long doLongLong(Var var, long x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_add(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static double doDoubleDouble(Var var, double x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_add(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static double doLongDouble(Var var, long x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_add(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static double doDoubleLong(Var var, double x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_add(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static Object doGeneric(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_add(x, y);
        }
        @Specialization(replaces = {"doLongLong", "doDoubleDouble", "doLongDouble", "doDoubleLong", "doGeneric"})
        public static Object doRedefined(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, x, y);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class NumbersMultiply {
        @Specialization(assumptions = "assumption")
        public static long doLongLong(Var var, long x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.multiply(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static double doDoubleDouble(Var var, double x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.multiply(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static double doLongDouble(Var var, long x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.multiply(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static double doDoubleLong(Var var, double x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.multiply(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static Object doGeneric(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.multiply(x, y);
        }
        @Specialization(replaces = {"doLongLong", "doDoubleDouble", "doLongDouble", "doDoubleLong", "doGeneric"})
        public static Object doRedefined(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, x, y);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class NumbersUncheckedMultiply {
        @Specialization(assumptions = "assumption")
        public static long doLongLong(Var var, long x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_multiply(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static double doDoubleDouble(Var var, double x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_multiply(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static double doLongDouble(Var var, long x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_multiply(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static double doDoubleLong(Var var, double x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_multiply(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static Object doGeneric(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_multiply(x, y);
        }
        @Specialization(replaces = {"doLongLong", "doDoubleDouble", "doLongDouble", "doDoubleLong", "doGeneric"})
        public static Object doRedefined(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, x, y);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class NumbersMinus {
        @Specialization(assumptions = "assumption")
        public static long doLongLong(Var var, long x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.minus(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static double doDoubleDouble(Var var, double x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.minus(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static double doLongDouble(Var var, long x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.minus(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static double doDoubleLong(Var var, double x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.minus(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static Object doGeneric(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.minus(x, y);
        }
        @Specialization(replaces = {"doLongLong", "doDoubleDouble", "doLongDouble", "doDoubleLong", "doGeneric"})
        public static Object doRedefined(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, x, y);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class NumbersUncheckedMinus {
        @Specialization(assumptions = "assumption")
        public static long doLongLong(Var var, long x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_minus(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static double doDoubleDouble(Var var, double x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_minus(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static double doLongDouble(Var var, long x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_minus(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static double doDoubleLong(Var var, double x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_minus(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static Object doGeneric(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_minus(x, y);
        }
        @Specialization(replaces = {"doLongLong", "doDoubleDouble", "doLongDouble", "doDoubleLong", "doGeneric"})
        public static Object doRedefined(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, x, y);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class NumbersDivide {
        @Specialization(assumptions = "assumption")
        public static Object doLongLong(Var var, long x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.divide(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static double doDoubleDouble(Var var, double x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.divide(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static double doLongDouble(Var var, long x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.divide(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static double doDoubleLong(Var var, double x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.divide(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static Object doGeneric(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.divide(x, y);
        }
        @Specialization(replaces = {"doLongLong", "doDoubleDouble", "doLongDouble", "doDoubleLong", "doGeneric"})
        public static Object doRedefined(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, x, y);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class NumbersLt {
        @Specialization(assumptions = "assumption")
        public static boolean doLongLong(Var var, long x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.lt(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static boolean doDoubleDouble(Var var, double x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.lt(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static boolean doLongDouble(Var var, long x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.lt(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static boolean doDoubleLong(Var var, double x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.lt(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static boolean doGeneric(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.lt(x, y);
        }
        @Specialization(replaces = {"doLongLong", "doDoubleDouble", "doLongDouble", "doDoubleLong", "doGeneric"})
        public static Object doRedefined(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, x, y);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class NumbersLte {
        @Specialization(assumptions = "assumption")
        public static boolean doLongLong(Var var, long x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.lte(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static boolean doDoubleDouble(Var var, double x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.lte(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static boolean doLongDouble(Var var, long x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.lte(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static boolean doDoubleLong(Var var, double x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.lte(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static boolean doGeneric(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.lte(x, y);
        }
        @Specialization(replaces = {"doLongLong", "doDoubleDouble", "doLongDouble", "doDoubleLong", "doGeneric"})
        public static Object doRedefined(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, x, y);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class NumbersGt {
        @Specialization(assumptions = "assumption")
        public static boolean doLongLong(Var var, long x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.gt(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static boolean doDoubleDouble(Var var, double x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.gt(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static boolean doLongDouble(Var var, long x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.gt(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static boolean doDoubleLong(Var var, double x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.gt(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static boolean doGeneric(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.gt(x, y);
        }
        @Specialization(replaces = {"doLongLong", "doDoubleDouble", "doLongDouble", "doDoubleLong", "doGeneric"})
        public static Object doRedefined(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, x, y);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class NumbersGte {
        @Specialization(assumptions = "assumption")
        public static boolean doLongLong(Var var, long x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.gte(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static boolean doDoubleDouble(Var var, double x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.gte(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static boolean doLongDouble(Var var, long x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.gte(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static boolean doDoubleLong(Var var, double x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.gte(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static boolean doGeneric(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.gte(x, y);
        }
        @Specialization(replaces = {"doLongLong", "doDoubleDouble", "doLongDouble", "doDoubleLong", "doGeneric"})
        public static Object doRedefined(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, x, y);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class NumbersEquiv {
        @Specialization(assumptions = "assumption")
        public static boolean doLongLong(Var var, long x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.equiv(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static boolean doDoubleDouble(Var var, double x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.equiv(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static boolean doLongDouble(Var var, long x, double y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.equiv(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static boolean doDoubleLong(Var var, double x, long y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.equiv(x, y);
        }
        @Specialization(assumptions = "assumption")
        public static boolean doGeneric(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.equiv(x, y);
        }
        @Specialization(replaces = {"doLongLong", "doDoubleDouble", "doLongDouble", "doDoubleLong", "doGeneric"})
        public static Object doRedefined(Var var, Object x, Object y,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, x, y);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class NumbersInc {
        @Specialization(assumptions = "assumption")
        public static long doLong(Var var, long x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.inc(x);
        }
        @Specialization(assumptions = "assumption")
        public static double doDouble(Var var, double x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.inc(x);
        }
        @Specialization(assumptions = "assumption")
        public static Object doGeneric(Var var, Object x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.inc(x);
        }
        @Specialization(replaces = {"doLong", "doDouble", "doGeneric"})
        public static Object doRedefined(Var var, Object x,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, x);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class NumbersUncheckedInc {
        @Specialization(assumptions = "assumption")
        public static long doLong(Var var, long x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_inc(x);
        }
        @Specialization(assumptions = "assumption")
        public static double doDouble(Var var, double x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_inc(x);
        }
        @Specialization(assumptions = "assumption")
        public static Object doGeneric(Var var, Object x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_inc(x);
        }
        @Specialization(replaces = {"doLong", "doDouble", "doGeneric"})
        public static Object doRedefined(Var var, Object x,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, x);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class NumbersDec {
        @Specialization(assumptions = "assumption")
        public static long doLong(Var var, long x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.dec(x);
        }
        @Specialization(assumptions = "assumption")
        public static double doDouble(Var var, double x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.dec(x);
        }
        @Specialization(assumptions = "assumption")
        public static Object doGeneric(Var var, Object x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.dec(x);
        }
        @Specialization(replaces = {"doLong", "doDouble", "doGeneric"})
        public static Object doRedefined(Var var, Object x,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, x);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class NumbersUncheckedDec {
        @Specialization(assumptions = "assumption")
        public static long doLong(Var var, long x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_dec(x);
        }
        @Specialization(assumptions = "assumption")
        public static double doDouble(Var var, double x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_dec(x);
        }
        @Specialization(assumptions = "assumption")
        public static Object doGeneric(Var var, Object x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_dec(x);
        }
        @Specialization(replaces = {"doLong", "doDouble", "doGeneric"})
        public static Object doRedefined(Var var, Object x,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, x);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class NumbersNegate {
        @Specialization(assumptions = "assumption")
        public static long doLong(Var var, long x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.minus(x);
        }
        @Specialization(assumptions = "assumption")
        public static double doDouble(Var var, double x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.minus(x);
        }
        @Specialization(assumptions = "assumption")
        public static Object doGeneric(Var var, Object x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.minus(x);
        }
        @Specialization(replaces = {"doLong", "doDouble", "doGeneric"})
        public static Object doRedefined(Var var, Object x,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, x);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class NumbersUncheckedNegate {
        @Specialization(assumptions = "assumption")
        public static long doLong(Var var, long x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_minus(x);
        }
        @Specialization(assumptions = "assumption")
        public static double doDouble(Var var, double x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_minus(x);
        }
        @Specialization(assumptions = "assumption")
        public static Object doGeneric(Var var, Object x,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return Numbers.unchecked_minus(x);
        }
        @Specialization(replaces = {"doLong", "doDouble", "doGeneric"})
        public static Object doRedefined(Var var, Object x,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, x);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    /**
     * Lowering for tier-3 {@code RT.nth} static calls (not {@code :cloffle/op} on {@code #'nth}).
     * Inline-cached {@link Indexed} access with primitive index specializations.
     */
    @Operation(storeBytecodeIndex = true)
    public static final class VectorNth2 {
        @Specialization(guards = "coll == null")
        public static Object doNull(Object coll, Object n) {
            return null;
        }

        @Specialization(guards = "coll.getClass() == cachedClass", limit = "8")
        public static Object doIndexedCached(
                Indexed coll,
                int n,
                @com.oracle.truffle.api.dsl.Cached("coll.getClass()") Class<? extends Indexed> cachedClass) {
            return CompilerDirectives.castExact(coll, cachedClass).nth(n);
        }

        @Specialization(guards = "coll.getClass() == cachedClass", limit = "8")
        public static Object doIndexedCachedLong(
                Indexed coll,
                long n,
                @com.oracle.truffle.api.dsl.Cached("coll.getClass()") Class<? extends Indexed> cachedClass) {
            return CompilerDirectives.castExact(coll, cachedClass).nth((int) n);
        }

        @Specialization(guards = "coll.getClass() == cachedClass", limit = "8")
        public static Object doIndexedCachedBoxed(
                Indexed coll,
                Long n,
                @com.oracle.truffle.api.dsl.Cached("coll.getClass()") Class<? extends Indexed> cachedClass) {
            return CompilerDirectives.castExact(coll, cachedClass).nth(n.intValue());
        }

        @Specialization(replaces = {"doIndexedCached", "doIndexedCachedLong", "doIndexedCachedBoxed"})
        public static Object doIndexedGeneric(Indexed coll, Object n) {
            return BytecodeSeqAccess.nthIndexed(coll, n);
        }

        @Specialization(guards = {"coll != null", "!isIndexed(coll)"})
        public static Object doGeneric(Object coll, Object n) {
            return BytecodeSeqAccess.nthGeneric(coll, n);
        }

        protected static boolean isIndexed(Object coll) {
            return BytecodeSeqAccess.isIndexed(coll);
        }
    }

    @Operation(storeBytecodeIndex = true)
    public static final class VectorNth3 {
        @Specialization(guards = "coll == null")
        public static Object doNull(Object coll, Object n, Object notFound) {
            return notFound;
        }

        @Specialization(guards = "coll.getClass() == cachedClass", limit = "8")
        public static Object doIndexedCached(
                Indexed coll,
                int n,
                Object notFound,
                @com.oracle.truffle.api.dsl.Cached("coll.getClass()") Class<? extends Indexed> cachedClass) {
            return CompilerDirectives.castExact(coll, cachedClass).nth(n, notFound);
        }

        @Specialization(guards = "coll.getClass() == cachedClass", limit = "8")
        public static Object doIndexedCachedLong(
                Indexed coll,
                long n,
                Object notFound,
                @com.oracle.truffle.api.dsl.Cached("coll.getClass()") Class<? extends Indexed> cachedClass) {
            return CompilerDirectives.castExact(coll, cachedClass).nth((int) n, notFound);
        }

        @Specialization(guards = "coll.getClass() == cachedClass", limit = "8")
        public static Object doIndexedCachedBoxed(
                Indexed coll,
                Long n,
                Object notFound,
                @com.oracle.truffle.api.dsl.Cached("coll.getClass()") Class<? extends Indexed> cachedClass) {
            return CompilerDirectives.castExact(coll, cachedClass).nth(n.intValue(), notFound);
        }

        @Specialization(replaces = {"doIndexedCached", "doIndexedCachedLong", "doIndexedCachedBoxed"})
        public static Object doIndexedGeneric(Indexed coll, Object n, Object notFound) {
            return BytecodeSeqAccess.nthIndexed(coll, n, notFound);
        }

        @Specialization(guards = {"coll != null", "!isIndexed(coll)"})
        public static Object doGeneric(Object coll, Object n, Object notFound) {
            return BytecodeSeqAccess.nthGeneric(coll, n, notFound);
        }

        protected static boolean isIndexed(Object coll) {
            return BytecodeSeqAccess.isIndexed(coll);
        }
    }

    /**
     * {@code (first (map :kw vector-coll))} when analyze emits
     * {@code EphemeralVectorSeq/create} with a keyword — fuses seq head + field lookup (no EVS alloc).
     */
    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Keyword.class, name = "keyword")
    public static final class VectorKeywordMapFirst {
        @Specialization(guards = "v == null")
        public static Object doNullVector(Keyword keyword, Object v) {
            return null;
        }

        @Specialization(guards = "isEmptyVector(v)")
        public static Object doEmptyVector(Keyword keyword, Object v) {
            return null;
        }

        @Specialization(guards = "v.getClass() == cachedClass", limit = "8")
        public static Object doVectorCached(
                Keyword keyword,
                IPersistentVector v,
                @com.oracle.truffle.api.dsl.Cached("v.getClass()") Class<? extends IPersistentVector> cachedClass) {
            IPersistentVector vec = CompilerDirectives.castExact(v, cachedClass);
            return BytecodeSeqAccess.keywordAtHead(keyword, vec);
        }

        @Specialization(replaces = "doVectorCached")
        public static Object doVectorGeneric(Keyword keyword, IPersistentVector v) {
            return BytecodeSeqAccess.keywordAtHeadChecked(keyword, v);
        }

        @Specialization(guards = {"v != null", "!isPersistentVector(v)"})
        public static Object doNonVector(Keyword keyword, Object v) {
            return null;
        }

        protected static boolean isEmptyVector(Object v) {
            return BytecodeSeqAccess.isEmptyVector(v);
        }

        protected static boolean isPersistentVector(Object v) {
            return BytecodeSeqAccess.isPersistentVector(v);
        }
    }

    /**
     * Analyze-time {@code EphemeralVectorSeq/create} with keyword {@code f} — direct node instead of
     * generic {@link StaticMethod3} so Graal can scalar-replace {@link EphemeralVectorSeq}.
     */
    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Keyword.class, name = "keyword")
    public static final class EphemeralVectorSeqKeywordCreate {
        @Specialization(guards = "v == null")
        public static Object doNullVector(Keyword keyword, Object v, int i) {
            return null;
        }

        @Specialization(guards = "isOutOfRange(v, i)")
        public static Object doOutOfRange(Keyword keyword, Object v, int i) {
            return null;
        }

        @Specialization(guards = "!isOutOfRange(v, i)")
        public static Object doCreate(Keyword keyword, IPersistentVector v, int i) {
            return BytecodeSeqAccess.evsCreate(keyword, v, i);
        }

        @Specialization(guards = "!isOutOfRange(v, i)", replaces = "doCreate")
        public static Object doCreateLong(Keyword keyword, IPersistentVector v, long i) {
            return BytecodeSeqAccess.evsCreate(keyword, v, i);
        }

        @Specialization(replaces = {"doCreate", "doCreateLong"})
        public static Object doCreateGeneric(Keyword keyword, Object v, Object i) {
            return BytecodeSeqAccess.evsCreateGeneric(keyword, v, i);
        }

        protected static boolean isOutOfRange(Object v, int i) {
            return BytecodeSeqAccess.isOutOfRange(v, i);
        }

        protected static boolean isOutOfRange(Object v, Object i) {
            return BytecodeSeqAccess.isOutOfRange(v, i);
        }
    }

    /**
     * Experimental {@code (nth coll idx)} via {@code :cloffle/op} on the Var (unwired). Prefer
     * {@link VectorNth2} on tier-3 {@code RT.nth} {@link StaticMethodExpr} sites instead.
     */
    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class NumbersNth {
        @Specialization(assumptions = "assumption")
        public static Object doLong(Var var, Object coll, long index,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return RT.nth(coll, RT.intCast(index));
        }
        @Specialization(assumptions = "assumption")
        public static Object doInt(Var var, Object coll, int index,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return RT.nth(coll, index);
        }
        @Specialization(assumptions = "assumption")
        public static Object doGeneric(Var var, Object coll, Object index,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return RT.nth(coll, RT.intCast(index));
        }
        @Specialization(replaces = {"doLong", "doInt", "doGeneric"})
        public static Object doRedefined(Var var, Object coll, Object index,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, coll, index);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    /**
     * Experimental {@code (count coll)} lowering. Host {@code int} internally; Object boundary boxes
     * to {@link Integer} (same as RT.count). Accept only if it beats the StaticMethod1 int-return path.
     */
    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class NumbersCount {
        @Specialization(assumptions = "assumption")
        public static int doCounted(Var var, Counted coll,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return coll.count();
        }
        @Specialization(assumptions = "assumption")
        public static int doGeneric(Var var, Object coll,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return RT.count(coll);
        }
        @Specialization(replaces = {"doCounted", "doGeneric"})
        public static Object doRedefined(Var var, Object coll,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, coll);
        }
        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    /** Lowered {@code (aset array idx val)} — stock :inline expands to {@code RT.aset} with {@code (int idx)}. */
    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class RtAset {
        @Specialization(assumptions = "assumption")
        public static Object doGeneric(Var var, Object array, Object idx, Object val,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return RT.aset(array, idx, val);
        }

        @Specialization(replaces = "doGeneric")
        public static Object doRedefined(Var var, Object array, Object idx, Object val,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, array, idx, val);
        }

        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
        }
    }

    /** Lowered {@code (aget array idx)} — stock :inline expands to {@code RT.aget} with {@code (int idx)}. */
    @Operation(storeBytecodeIndex = true)
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = Var.class, name = "var")
    public static final class RtAget {
        @Specialization(assumptions = "assumption")
        public static Object doGeneric(Var var, Object array, Object idx,
                @com.oracle.truffle.api.dsl.Cached("loweringAssumption(var)") Assumption assumption) {
            return RT.aget(array, idx);
        }

        @Specialization(replaces = "doGeneric")
        public static Object doRedefined(Var var, Object array, Object idx,
                @com.oracle.truffle.api.dsl.Cached IndirectCallNode callNode) {
            return BytecodeLowering.invokeRedefined(var, callNode, array, idx);
        }

        @com.oracle.truffle.api.dsl.NeverDefault
        protected static Assumption loweringAssumption(Var var) {
            return sanctionedRootAssumption(var);
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
