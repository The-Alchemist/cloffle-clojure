package net.javacrumbs.cloffle.bytecode;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import net.javacrumbs.cloffle.nodes.ClojureException;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Helper methods for fast, inlineable Java static method invocation.
 * <p>
 * Two handle shapes are cached:
 * <ul>
 *   <li>generic {@code (Object…)Object} — boxes primitive returns at Object boundaries</li>
 *   <li>exact — preserves primitive parameter/return types for boxing-elimination transport</li>
 * </ul>
 */
public final class BytecodeStaticMethod {
    public static final boolean PRIMITIVE_STATIC_METHOD_TRANSPORT = true;
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final MethodHandle INELIGIBLE = MethodHandles.constant(Object.class, null);
    private static final ConcurrentHashMap<Method, MethodHandle> MH_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Method, MethodHandle> EXACT_MH_CACHE = new ConcurrentHashMap<>();

    private static final MethodHandle COERCE_ARG;

    static {
        try {
            COERCE_ARG = LOOKUP.findStatic(BytecodeStaticMethod.class, "coerceArg",
                    MethodType.methodType(Object.class, Class.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static final Object[] EMPTY_ARRAY = new Object[0];

    private BytecodeStaticMethod() {
    }

    @CompilerDirectives.TruffleBoundary
    public static boolean isEligible(Object resolvedMethod, int arity) {
        if (!(resolvedMethod instanceof Method m)) {
            return false;
        }
        return getOrComputeMethodHandle(m, arity) != null;
    }

    @CompilerDirectives.TruffleBoundary
    public static MethodHandle createMethodHandle(Object resolvedMethod, int arity) {
        if (!(resolvedMethod instanceof Method m)) {
            return null;
        }
        return getOrComputeMethodHandle(m, arity);
    }

    @CompilerDirectives.TruffleBoundary
    public static MethodHandle createExactMethodHandle(Object resolvedMethod, int arity) {
        if (!(resolvedMethod instanceof Method m)) {
            return null;
        }
        return getOrComputeExactMethodHandle(m, arity);
    }

    @CompilerDirectives.TruffleBoundary
    public static boolean isLong0(Object resolvedMethod) {
        return returns(resolvedMethod, long.class) && arityIs(resolvedMethod, 0);
    }

    @CompilerDirectives.TruffleBoundary
    public static boolean isDouble0(Object resolvedMethod) {
        return returns(resolvedMethod, double.class) && arityIs(resolvedMethod, 0);
    }

    @CompilerDirectives.TruffleBoundary
    public static boolean isInt0(Object resolvedMethod) {
        return returns(resolvedMethod, int.class) && arityIs(resolvedMethod, 0);
    }

    @CompilerDirectives.TruffleBoundary
    public static boolean isLong1(Object resolvedMethod) {
        return returns(resolvedMethod, long.class) && paramIs(resolvedMethod, 0, long.class);
    }

    @CompilerDirectives.TruffleBoundary
    public static boolean isDouble1(Object resolvedMethod) {
        return returns(resolvedMethod, double.class) && paramIs(resolvedMethod, 0, double.class);
    }

    @CompilerDirectives.TruffleBoundary
    public static boolean isInt1(Object resolvedMethod) {
        return returns(resolvedMethod, int.class) && paramIs(resolvedMethod, 0, int.class);
    }

    @CompilerDirectives.TruffleBoundary
    public static boolean isIntReturn1(Object resolvedMethod) {
        return returns(resolvedMethod, int.class)
                && arityIs(resolvedMethod, 1)
                && paramIs(resolvedMethod, 0, Object.class);
    }

    @CompilerDirectives.TruffleBoundary
    public static boolean isLongLong2(Object resolvedMethod) {
        return returns(resolvedMethod, long.class)
                && paramIs(resolvedMethod, 0, long.class)
                && paramIs(resolvedMethod, 1, long.class);
    }

    @CompilerDirectives.TruffleBoundary
    public static boolean isDoubleDouble2(Object resolvedMethod) {
        return returns(resolvedMethod, double.class)
                && paramIs(resolvedMethod, 0, double.class)
                && paramIs(resolvedMethod, 1, double.class);
    }

    @CompilerDirectives.TruffleBoundary
    public static boolean isIntInt2(Object resolvedMethod) {
        return returns(resolvedMethod, int.class)
                && paramIs(resolvedMethod, 0, int.class)
                && paramIs(resolvedMethod, 1, int.class);
    }

    @CompilerDirectives.TruffleBoundary
    public static boolean isObjectInt2(Object resolvedMethod) {
        return returns(resolvedMethod, Object.class)
                && paramIs(resolvedMethod, 0, Object.class)
                && paramIs(resolvedMethod, 1, int.class);
    }

    @CompilerDirectives.TruffleBoundary
    public static boolean isBoolLongLong2(Object resolvedMethod) {
        return returns(resolvedMethod, boolean.class)
                && paramIs(resolvedMethod, 0, long.class)
                && paramIs(resolvedMethod, 1, long.class);
    }

    @CompilerDirectives.TruffleBoundary
    public static boolean isBoolDoubleDouble2(Object resolvedMethod) {
        return returns(resolvedMethod, boolean.class)
                && paramIs(resolvedMethod, 0, double.class)
                && paramIs(resolvedMethod, 1, double.class);
    }

    private static boolean returns(Object resolvedMethod, Class<?> type) {
        if (!PRIMITIVE_STATIC_METHOD_TRANSPORT) return false;
        return resolvedMethod instanceof Method m && m.getReturnType() == type;
    }

    private static boolean arityIs(Object resolvedMethod, int arity) {
        return resolvedMethod instanceof Method m && m.getParameterCount() == arity;
    }

    private static boolean paramIs(Object resolvedMethod, int index, Class<?> type) {
        if (!(resolvedMethod instanceof Method m)) {
            return false;
        }
        Class<?>[] params = m.getParameterTypes();
        return index >= 0 && index < params.length && params[index] == type;
    }

    @CompilerDirectives.TruffleBoundary
    private static MethodHandle getOrComputeMethodHandle(Method m, int arity) {
        MethodHandle cached = MH_CACHE.get(m);
        if (cached != null) {
            return cached == INELIGIBLE ? null : cached;
        }
        MethodHandle created = computeMethodHandle(m, arity);
        MH_CACHE.put(m, created == null ? INELIGIBLE : created);
        return created;
    }

    @CompilerDirectives.TruffleBoundary
    private static MethodHandle getOrComputeExactMethodHandle(Method m, int arity) {
        MethodHandle cached = EXACT_MH_CACHE.get(m);
        if (cached != null) {
            return cached == INELIGIBLE ? null : cached;
        }
        MethodHandle created = computeExactMethodHandle(m, arity);
        EXACT_MH_CACHE.put(m, created == null ? INELIGIBLE : created);
        return created;
    }

    @CompilerDirectives.TruffleBoundary
    private static MethodHandle computeMethodHandle(Method m, int arity) {
        MethodHandle exact = computeExactMethodHandle(m, arity);
        if (exact == null) {
            return null;
        }
        try {
            Class<?>[] params = m.getParameterTypes();
            MethodHandle mh = exact;
            for (int i = 0; i < params.length; i++) {
                if (params[i].isPrimitive()) {
                    mh = MethodHandles.filterArguments(mh, i, primitiveArgFilter(params[i]));
                }
            }
            return mh.asType(MethodType.genericMethodType(arity));
        } catch (Throwable t) {
            return null;
        }
    }

    @CompilerDirectives.TruffleBoundary
    private static MethodHandle computeExactMethodHandle(Method m, int arity) {
        try {
            int mods = m.getModifiers();
            if (!Modifier.isStatic(mods) || !Modifier.isPublic(mods)) {
                return null;
            }
            Class<?> declaring = m.getDeclaringClass();
            if (!Modifier.isPublic(declaring.getModifiers())) {
                return null;
            }
            if (declaring == CompilerDirectives.class
                    || "com.oracle.truffle.api.CompilerDirectives".equals(declaring.getName())) {
                return null;
            }
            if (m.getParameterCount() != arity) {
                return null;
            }
            Class<?>[] params = m.getParameterTypes();
            for (Class<?> p : params) {
                if (clojure.lang.Compiler.FISupport.maybeFIMethod(p) != null) {
                    return null;
                }
            }
            if (m.getReturnType() == void.class) {
                return null;
            }
            return LOOKUP.unreflect(m).asFixedArity();
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object coerceArg(Class<?> paramType, Object arg) {
        try {
            return clojure.lang.Reflector.boxArg(paramType, arg);
        } catch (IllegalArgumentException e) {
            throw new ClassCastException(e.getMessage());
        }
    }

    @CompilerDirectives.TruffleBoundary
    private static MethodHandle primitiveArgFilter(Class<?> primitive) {
        return MethodHandles.insertArguments(COERCE_ARG, 0, primitive)
                .asType(MethodType.methodType(primitive, Object.class));
    }

    public static Object unwrap(Object o) {
        return ClojureInterop.unwrapFromPolyglot(o);
    }

    public static RuntimeException handleException(Throwable t) {
        if (t instanceof ClojureException ce) {
            throw ce;
        }
        if (t instanceof AbstractTruffleException ate) {
            throw ate;
        }
        if (t instanceof Error err) {
            throw err;
        }
        if (t instanceof Exception e) {
            throw ClojureException.wrapReflective(e);
        }
        throw ClojureException.wrapReflective(new RuntimeException(t));
    }
}
