package net.javacrumbs.cloffle.bytecode;

import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.RT;
import clojure.lang.Symbol;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import net.javacrumbs.cloffle.nodes.ClojureException;

import java.lang.reflect.Method;

final class BytecodeInterop {
    private BytecodeInterop() {
    }

    static Object newObject(Object targetClass, Object[] args) {
        try {
            return clojure.lang.Reflector.invokeConstructor((Class<?>) targetClass, BytecodeReflect.unwrapArgsForReflect(args));
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

    static Object instanceMethod(String methodName, Object resolvedMethod, Object instance, Object[] args) {
        try {
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
        } catch (ClojureException ce) {
            throw ce;
        } catch (AbstractTruffleException ate) {
            throw ate;
        } catch (Exception e) {
            throw ClojureException.wrapReflective(e);
        }
    }

    static Object invokeProtocol(clojure.lang.Var var, Object onMethod, Object[] args) {
        try {
            Method method = (Method) onMethod;
            Object receiver = BytecodeReflect.unwrapForReflect(args[0]);
            if (receiver != null && method.getDeclaringClass().isInstance(receiver)) {
                Object[] methodArgs = new Object[args.length - 1];
                System.arraycopy(args, 1, methodArgs, 0, methodArgs.length);
                methodArgs = BytecodeReflect.unwrapArgsForReflect(methodArgs);
                return clojure.lang.Reflector.prepRet(
                        method.getReturnType(),
                        method.invoke(receiver, clojure.lang.Reflector.boxArgs(method.getParameterTypes(), methodArgs)));
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
            instance = BytecodeReflect.unwrapForReflect(instance);
            if (requireField) {
                return clojure.lang.Reflector.getInstanceField(instance, fieldName);
            } else {
                return clojure.lang.Reflector.invokeNoArgInstanceMember(instance, fieldName);
            }
        } catch (ClojureException ce) {
            throw ce;
        } catch (AbstractTruffleException ate) {
            throw ate;
        } catch (Exception e) {
            throw ClojureException.wrapReflective(e);
        }
    }

    static Object setInstanceField(String fieldName, Object target, Object value) {
        try {
            return clojure.lang.Reflector.setInstanceField(
                    BytecodeReflect.unwrapForReflect(target), fieldName, BytecodeReflect.unwrapForReflect(value));
        } catch (ClojureException ce) {
            throw ce;
        } catch (AbstractTruffleException ate) {
            throw ate;
        } catch (Exception e) {
            throw ClojureException.wrapReflective(e);
        }
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
                return clojure.lang.Reflector.prepRet(m.getReturnType(), m.invoke(null, clojure.lang.Reflector.boxArgs(m.getParameterTypes(), args)));
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
            return clojure.lang.Reflector.boxArg(fiClass, value);
        }
        return value;
    }
}
