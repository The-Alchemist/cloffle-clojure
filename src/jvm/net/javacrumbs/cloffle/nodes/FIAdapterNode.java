package net.javacrumbs.cloffle.nodes;

import clojure.lang.FnInvokers;
import clojure.lang.IFn;
import clojure.lang.RT;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/**
 * Adapts an IFn to a Java functional interface at runtime.
 * Uses LambdaMetafactory when possible, falls back to Proxy for
 * dynamically-loaded interfaces (classloader compatibility).
 */
public class FIAdapterNode extends ClojureNode {
    @Child private ClojureNode expr;
    private final Class<?> targetFI;
    private final java.lang.reflect.Method fiMethod;

    @CompilationFinal private volatile AdapterStrategy strategy;

    public FIAdapterNode(ClojureNode expr, Class<?> targetFI, java.lang.reflect.Method fiMethod) {
        this.expr = expr;
        this.targetFI = targetFI;
        this.fiMethod = fiMethod;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object value = expr.executeGeneric(frame);
        Object unwrapped = ClojureInterop.unwrapFromPolyglot(value);

        if (!(unwrapped instanceof IFn)) {
            return value;
        }
        if (targetFI.isInstance(unwrapped)) {
            return value;
        }

        if (strategy == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            resolveStrategy();
        }

        try {
            Object adapted = strategy.adapt((IFn) unwrapped);
            return ClojureInterop.wrapForPolyglot(adapted);
        } catch (ClassCastException e) {
            CompilerDirectives.transferToInterpreter();
            throw ClojureException.wrap(e, this);
        } catch (Throwable t) {
            CompilerDirectives.transferToInterpreter();
            throw ClojureException.wrap(t, this);
        }
    }

    private void resolveStrategy() {
        AdapterStrategy s = tryLambdaMetafactory();
        if (s == null) {
            s = createProxyStrategy();
        }
        strategy = s;
    }

    private AdapterStrategy tryLambdaMetafactory() {
        try {
            int paramCount = fiMethod.getParameterCount();
            Class<?>[] fiParamTypes = fiMethod.getParameterTypes();
            Class<?> fiRetType = fiMethod.getReturnType();

            Class<?>[] invokerParams = new Class<?>[paramCount + 1];
            invokerParams[0] = IFn.class;
            StringBuilder invokerNameBuilder = new StringBuilder("invoke");
            for (int i = 0; i < paramCount; i++) {
                invokerParams[i + 1] = paramCount <= 2
                        ? toInvokerParamType(fiParamTypes[i])
                        : Object.class;
                invokerNameBuilder.append(FnInvokers.encodeInvokerType(invokerParams[i + 1]));
            }
            Class<?> invokerRetType = paramCount <= 2 ? fiRetType : Object.class;
            invokerNameBuilder.append(FnInvokers.encodeInvokerType(invokerRetType));
            String invokerMethodName = invokerNameBuilder.toString();

            java.lang.reflect.Method fnInvokerMethod =
                    FnInvokers.class.getMethod(invokerMethodName, invokerParams);

            MethodHandles.Lookup lookup;
            try {
                lookup = MethodHandles.privateLookupIn(targetFI, MethodHandles.lookup());
            } catch (IllegalAccessException e) {
                lookup = MethodHandles.lookup();
            }

            MethodHandle implHandle = lookup.unreflect(fnInvokerMethod);

            MethodType samMethodType = MethodType.methodType(
                    fiMethod.getReturnType(), fiMethod.getParameterTypes());
            MethodType factoryType = MethodType.methodType(targetFI, IFn.class);

            CallSite callSite = LambdaMetafactory.metafactory(
                    lookup,
                    fiMethod.getName(),
                    factoryType,
                    samMethodType,
                    implHandle,
                    samMethodType);

            MethodHandle factory = callSite.getTarget();
            return ifn -> factory.invoke(ifn);
        } catch (Throwable t) {
            return null;
        }
    }

    private AdapterStrategy createProxyStrategy() {
        final int paramCount = fiMethod.getParameterCount();
        final Class<?> retType = fiMethod.getReturnType();

        return ifn -> {
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "toString" -> ifn.toString();
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == (args != null ? args[0] : null);
                        default -> method.invoke(ifn, args);
                    };
                }
                Object result = switch (paramCount) {
                    case 0 -> ifn.invoke();
                    case 1 -> ifn.invoke(args[0]);
                    case 2 -> ifn.invoke(args[0], args[1]);
                    case 3 -> ifn.invoke(args[0], args[1], args[2]);
                    case 4 -> ifn.invoke(args[0], args[1], args[2], args[3]);
                    case 5 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4]);
                    case 6 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5]);
                    case 7 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6]);
                    case 8 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7]);
                    case 9 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8]);
                    case 10 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9]);
                    default -> ifn.applyTo(RT.seq(args));
                };
                return coerceReturn(result, retType);
            };
            return Proxy.newProxyInstance(
                    targetFI.getClassLoader(),
                    new Class<?>[]{targetFI},
                    handler);
        };
    }

    private static Object coerceReturn(Object result, Class<?> retType) {
        if (retType == void.class) return null;
        if (!retType.isPrimitive()) return result;
        if (result == null) return null;
        if (retType == long.class) return RT.longCast(result);
        if (retType == int.class) return RT.intCast(result);
        if (retType == double.class) return RT.doubleCast(result);
        if (retType == float.class) return RT.floatCast(result);
        if (retType == short.class) return RT.shortCast(result);
        if (retType == byte.class) return RT.byteCast(result);
        if (retType == boolean.class) return RT.booleanCast(result);
        return result;
    }

    private static Class<?> toInvokerParamType(Class<?> c) {
        if (c == byte.class || c == short.class || c == int.class || c == long.class) {
            return long.class;
        } else if (c == float.class || c == double.class) {
            return double.class;
        }
        return Object.class;
    }

    @FunctionalInterface
    private interface AdapterStrategy {
        Object adapt(IFn ifn) throws Throwable;
    }
}
