package net.javacrumbs.cloffle.bytecode;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.bytecode.Variadic;
import net.javacrumbs.cloffle.Clojure;
import net.javacrumbs.cloffle.nodes.ClojureClosure;
import clojure.lang.IFn;

@GenerateBytecode(
    languageClass = Clojure.class,
    enableSerialization = true,
    enableMaterializedLocalAccesses = true
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
            return var.setDynamic(isDynamic);
        }
    }

    @Operation
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "className")
    public static final class ImportClass {
        @Specialization
        public static Object doImport(String className) {
            Class<?> c = clojure.lang.RT.classForNameNonLoading(className);
            clojure.lang.Namespace ns = (clojure.lang.Namespace) clojure.lang.RT.CURRENT_NS.deref();
            ns.importClass(c);
            return null;
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
                throw new net.javacrumbs.cloffle.nodes.ClojureException(t.getMessage(), t, null);
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
                return clojure.lang.Reflector.invokeConstructor((Class<?>) targetClass, args);
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
                if (resolvedMethod instanceof java.lang.reflect.Method m) {
                    Class<?> declClass = m.getDeclaringClass();
                    Object target = adaptFIInstance(declClass, instance);
                    if (!declClass.isInstance(target)) {
                        throw new ClassCastException(
                            instance.getClass().getName() + " cannot be cast to " + declClass.getName());
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
                return clojure.lang.Reflector.setStaticField((Class<?>) targetClass, fieldName, value);
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
                return clojure.lang.Reflector.setInstanceField(target, fieldName, value);
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
            return ((Class<?>) targetClass).isInstance(instance);
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
                throw new net.javacrumbs.cloffle.nodes.ClojureException(e.getMessage(), e, null);
            }
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
