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
import com.oracle.truffle.api.bytecode.Variadic;
import net.javacrumbs.cloffle.Clojure;
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
    public static final class CreateClosure {
        @Specialization
        public static Object doCreate(CloffleBytecodeRootNode targetNode, com.oracle.truffle.api.frame.MaterializedFrame frame) {
            return new net.javacrumbs.cloffle.nodes.ClojureClosure(targetNode.getCallTarget(), frame);
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
            if (argCount >= 0) throw new clojure.lang.ArityException(argCount, name);
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
            java.util.List<Object> rest = new java.util.ArrayList<>(args.length - start);
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
            // Note: In Truffle, exceptions might be wrapped in TruffleException.
            // But for simple Java interop and Clojure exceptions, we can check the class.
            Object unwrapped = exception;
            if (exception instanceof com.oracle.truffle.api.exception.AbstractTruffleException ate && net.javacrumbs.cloffle.nodes.ClojureException.class.isInstance(exception)) {
                unwrapped = ((net.javacrumbs.cloffle.nodes.ClojureException) exception).getCause();
            }
            if (unwrapped == null) unwrapped = exception;
            return ((Class<?>) catchClass).isInstance(unwrapped);
        }
    }

    @Operation
    public static final class UnwrapException {
        @Specialization
        public static Object doUnwrap(Object exception) {
            if (exception instanceof net.javacrumbs.cloffle.nodes.ClojureException ce) {
                return ce.getCause();
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
            } catch (Exception e) {
                throw new net.javacrumbs.cloffle.nodes.ClojureException(e.getMessage(), e, null);
            }
        }
    }

    @Operation
    @com.oracle.truffle.api.bytecode.ConstantOperand(type = String.class, name = "methodName")
    public static final class InstanceMethod {
        @Specialization
        public static Object doInvoke(String methodName, Object instance, @Variadic Object[] args) {
            try {
                return clojure.lang.Reflector.invokeInstanceMethod(instance, methodName, args);
            } catch (Exception e) {
                throw new net.javacrumbs.cloffle.nodes.ClojureException(e.getMessage(), e, null);
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
            } catch (Exception e) {
                throw new net.javacrumbs.cloffle.nodes.ClojureException(e.getMessage(), e, null);
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
            } catch (Exception e) {
                throw new net.javacrumbs.cloffle.nodes.ClojureException(e.getMessage(), e, null);
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
            } catch (Exception e) {
                throw new net.javacrumbs.cloffle.nodes.ClojureException(e.getMessage(), e, null);
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
            } catch (Exception e) {
                throw new net.javacrumbs.cloffle.nodes.ClojureException(e.getMessage(), e, null);
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
    public static final class StaticMethod {
        @Specialization
        public static Object doInvoke(Object targetClass, String methodName, @Variadic Object[] args) {
            try {
                return clojure.lang.Reflector.invokeStaticMethod((Class<?>) targetClass, methodName, args);
            } catch (Exception e) {
                throw new net.javacrumbs.cloffle.nodes.ClojureException(e.getMessage(), e, null);
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
            switch (args.length) {
                case 0: return fn.invoke();
                case 1: return fn.invoke(args[0]);
                case 2: return fn.invoke(args[0], args[1]);
                case 3: return fn.invoke(args[0], args[1], args[2]);
                case 4: return fn.invoke(args[0], args[1], args[2], args[3]);
                default: return fn.applyTo(clojure.lang.RT.seq(args));
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
}
