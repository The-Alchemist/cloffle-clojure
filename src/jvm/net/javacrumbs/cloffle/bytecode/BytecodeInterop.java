package net.javacrumbs.cloffle.bytecode;

import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.RT;
import clojure.lang.Symbol;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import net.javacrumbs.cloffle.nodes.ClojureException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

final class BytecodeInterop {
    private BytecodeInterop() {
    }

    static Object newObject(Object targetClass, Object[] args) {
        try {
            return newObjectBoundary((Class<?>) targetClass, args);
        } catch (ClojureException ce) {
            throw ce;
        } catch (AbstractTruffleException ate) {
            throw ate;
        } catch (IllegalArgumentException iae) {
            if (iae.getMessage() != null && iae.getMessage().startsWith("Unexpected param type")) {
                throw ClojureException.wrapReflective(new ClassCastException(iae.getMessage()));
            }
            throw ClojureException.wrapReflective(iae);
        } catch (Exception e) {
            throw ClojureException.wrapReflective(e);
        }
    }

    /**
     * Reflection kept off the partial-evaluation graph (see {@link #invokeReflective}).
     * {@code Reflector.boxArgs} reaches {@code Proxy.newProxyInstance} and {@code CompilerFI}'s
     * annotation lookup, both of which recurse unboundedly under PE ("Too deep inlining").
     */
    @CompilerDirectives.TruffleBoundary
    private static Object newObjectBoundary(Class<?> targetClass, Object[] args) throws Exception {
        return clojure.lang.Reflector.invokeConstructor(targetClass, BytecodeReflect.unwrapArgsForReflect(args));
    }

    static Object instanceMethod(String methodName, Object resolvedMethod, Object instance, Object[] args) {
        try {
            return instanceMethodBoundary(methodName, resolvedMethod, instance, args);
        } catch (ClojureException ce) {
            throw ce;
        } catch (AbstractTruffleException ate) {
            throw ate;
        } catch (Exception e) {
            throw ClojureException.wrapReflective(e);
        }
    }

    /** Reflection kept off the partial-evaluation graph — see {@link #newObjectBoundary}. */
    @CompilerDirectives.TruffleBoundary
    private static Object instanceMethodBoundary(
            String methodName, Object resolvedMethod, Object instance, Object[] args) throws Exception {
        instance = BytecodeReflect.unwrapForReflect(instance);
        args = BytecodeReflect.unwrapArgsForReflect(args);
        if (resolvedMethod instanceof Method m) {
            Class<?> declClass = m.getDeclaringClass();
            Object target = BytecodeReflect.adaptFIInstance(declClass, instance);
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
    }

    static Object invokeProtocol(clojure.lang.Var var, Object onMethod, Object[] args) {
        try {
            Method method = (Method) onMethod;
            Object receiver = BytecodeReflect.unwrapForReflect(args[0]);
            if (receiver != null && method.getDeclaringClass().isInstance(receiver)) {
                return invokeProtocolReflective(method, receiver, args);
            }

            Object root = var.get();
            if (root instanceof IFn fn) {
                return fn.applyTo(RT.seq(args));
            }
            throw new ClojureException(net.javacrumbs.cloffle.nodes.ErrorMessages.cannotCallMessage(root), null);
        } catch (ClojureException ce) {
            throw ce;
        } catch (AbstractTruffleException ate) {
            throw ate;
        } catch (Exception e) {
            throw ClojureException.wrapReflective(e);
        }
    }

    /**
     * Reflective protocol dispatch, kept off the partial-evaluation graph like {@link #invokeReflective}.
     * {@code Reflector.boxArgs} reaches {@code CompilerFI.maybeFIMethod}, whose
     * {@code isAnnotationPresent} pulls in JDK annotation and generic-signature parsing; that parser
     * recurses unboundedly under PE and makes Graal bail out with "Too deep inlining".
     */
    @CompilerDirectives.TruffleBoundary
    private static Object invokeProtocolReflective(Method method, Object receiver, Object[] args) throws Exception {
        Object[] methodArgs = new Object[args.length - 1];
        System.arraycopy(args, 1, methodArgs, 0, methodArgs.length);
        methodArgs = BytecodeReflect.unwrapArgsForReflect(methodArgs);
        return clojure.lang.Reflector.prepRet(
                method.getReturnType(),
                method.invoke(receiver, clojure.lang.Reflector.boxArgs(method.getParameterTypes(), methodArgs)));
    }

    static Object staticField(Object targetClass, String fieldName) {
        try {
            return getStaticFieldBoundary((Class<?>) targetClass, fieldName);
        } catch (ClojureException ce) {
            throw ce;
        } catch (AbstractTruffleException ate) {
            throw ate;
        } catch (Exception e) {
            throw ClojureException.wrapReflective(e);
        }
    }

    @CompilerDirectives.TruffleBoundary
    static MethodHandle createStaticFieldGetter(Object targetClass, String fieldName) {
        if (!(targetClass instanceof Class<?> clazz)) {
            return null;
        }
        try {
            Field field = clazz.getField(fieldName);
            if (!Modifier.isStatic(field.getModifiers())) {
                return null;
            }
            return MethodHandles.lookup()
                    .unreflectGetter(field)
                    .asType(MethodType.methodType(Object.class));
        } catch (ReflectiveOperationException | SecurityException e) {
            return null;
        }
    }

    @CompilerDirectives.TruffleBoundary
    static Object getStaticFieldBoundary(Class<?> targetClass, String fieldName) throws Exception {
        return clojure.lang.Reflector.getStaticField(targetClass, fieldName);
    }

    static Object setStaticField(Object targetClass, String fieldName, Object value) {
        try {
            return setStaticFieldBoundary((Class<?>) targetClass, fieldName, value);
        } catch (ClojureException ce) {
            throw ce;
        } catch (AbstractTruffleException ate) {
            throw ate;
        } catch (Exception e) {
            throw ClojureException.wrapReflective(e);
        }
    }

    @CompilerDirectives.TruffleBoundary
    static Object setStaticFieldBoundary(Class<?> targetClass, String fieldName, Object value) throws Exception {
        return clojure.lang.Reflector.setStaticField(targetClass, fieldName, BytecodeReflect.unwrapForReflect(value));
    }

    static Object instanceField(String fieldName, boolean requireField, Object instance) {
        try {
            return instanceFieldBoundary(fieldName, requireField, instance);
        } catch (ClojureException ce) {
            throw ce;
        } catch (AbstractTruffleException ate) {
            throw ate;
        } catch (Exception e) {
            throw ClojureException.wrapReflective(e);
        }
    }

    /** Reflection kept off the partial-evaluation graph — see {@link #newObjectBoundary}. */
    @CompilerDirectives.TruffleBoundary
    private static Object instanceFieldBoundary(String fieldName, boolean requireField, Object instance)
            throws Exception {
        instance = BytecodeReflect.unwrapForReflect(instance);
        if (requireField) {
            return clojure.lang.Reflector.getInstanceField(instance, fieldName);
        }
        return clojure.lang.Reflector.invokeNoArgInstanceMember(instance, fieldName);
    }

    static Object setInstanceField(String fieldName, Object target, Object value) {
        try {
            return setInstanceFieldBoundary(fieldName, target, value);
        } catch (ClojureException ce) {
            throw ce;
        } catch (AbstractTruffleException ate) {
            throw ate;
        } catch (Exception e) {
            throw ClojureException.wrapReflective(e);
        }
    }

    /** Reflection kept off the partial-evaluation graph — see {@link #newObjectBoundary}. */
    @CompilerDirectives.TruffleBoundary
    private static Object setInstanceFieldBoundary(String fieldName, Object target, Object value) throws Exception {
        return clojure.lang.Reflector.setInstanceField(
                BytecodeReflect.unwrapForReflect(target), fieldName, BytecodeReflect.unwrapForReflect(value));
    }

    static boolean instanceOf(Object targetClass, Object instance) {
        if (targetClass == Keyword.class) {
            return instance instanceof Keyword;
        }
        if (targetClass == String.class) {
            return instance instanceof String;
        }
        if (targetClass == Symbol.class) {
            return instance instanceof Symbol;
        }
        return ((Class<?>) targetClass).isInstance(BytecodeReflect.unwrapForReflect(instance));
    }

    static Object staticMethod(Object targetClass, String methodName, Object resolvedMethod, Object[] args) {
        if (targetClass == CompilerDirectives.class
                || (targetClass instanceof Class<?> c && "com.oracle.truffle.api.CompilerDirectives".equals(c.getName()))) {
            if ("inCompiledCode".equals(methodName)) {
                return CompilerDirectives.inCompiledCode();
            }
            if ("inInterpreter".equals(methodName)) {
                return CompilerDirectives.inInterpreter();
            }
        }
        try {
            return invokeReflective((Class<?>) targetClass, methodName, (resolvedMethod instanceof Method m) ? m : null, args);
        } catch (ClojureException ce) {
            throw ce;
        } catch (AbstractTruffleException ate) {
            throw ate;
        } catch (Exception e) {
            throw ClojureException.wrapReflective(e);
        }
    }

    @CompilerDirectives.TruffleBoundary
    static Object invokeReflective(Class<?> targetClass, String methodName, Method m, Object[] args) throws Exception {
        args = BytecodeReflect.unwrapArgsForReflect(args);
        if (m != null) {
            try {
                Class<?>[] pts = m.getParameterTypes();
                if (m.isVarArgs() && pts.length == 1 && pts[0].isArray()) {
                    Object spreadArg = (args.length == 1 && pts[0].isInstance(args[0])) ? args[0] : args;
                    return clojure.lang.Reflector.prepRet(m.getReturnType(), m.invoke(null, spreadArg));
                }
                return clojure.lang.Reflector.prepRet(m.getReturnType(), m.invoke(null, clojure.lang.Reflector.boxArgs(pts, args)));
            } catch (IllegalArgumentException iae) {
                throw new ClassCastException(iae.getMessage());
            }
        }
        return clojure.lang.Reflector.invokeStaticMethod(targetClass, methodName, args);
    }

    static Object adaptFI(Object targetClass, Object value) {
        Class<?> fiClass = (Class<?>) targetClass;
        value = BytecodeReflect.unwrapForReflect(value);
        if (value instanceof IFn && !fiClass.isInstance(value)
                && clojure.lang.Compiler.FISupport.maybeFIMethod(fiClass) != null) {
            return adaptFIBoundary(fiClass, value);
        }
        return value;
    }

    /**
     * {@code Reflector.boxArg} builds a {@link java.lang.reflect.Proxy} for the functional interface;
     * {@code Proxy.newProxyInstance} recurses unboundedly under PE — see {@link #newObjectBoundary}.
     */
    @CompilerDirectives.TruffleBoundary
    private static Object adaptFIBoundary(Class<?> fiClass, Object value) {
        return clojure.lang.Reflector.boxArg(fiClass, value);
    }
}
