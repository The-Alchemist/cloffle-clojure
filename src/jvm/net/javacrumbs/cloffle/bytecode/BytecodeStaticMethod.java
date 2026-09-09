package net.javacrumbs.cloffle.bytecode;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Idempotent;
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
 * Provides conservative eligibility checking and caching of unreflected {@link MethodHandle}s
 * adapted to generic Object signatures for fixed-arity calls.
 */
public final class BytecodeStaticMethod {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final MethodHandle INELIGIBLE = MethodHandles.constant(Object.class, null);
    private static final ConcurrentHashMap<Method, MethodHandle> MH_CACHE = new ConcurrentHashMap<>();

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

    /**
     * Checks if {@code resolvedMethod} is eligible for the fast {@link MethodHandle} path:
     * - Must be a resolved {@link Method}
     * - Must be public static on a public class
     * - Arity must match parameter count
     * - No parameter may be a functional interface, and the return type may not be void
     * - Unreflecting and adapting to generic signature must succeed
     */
    @CompilerDirectives.TruffleBoundary
    public static boolean isEligible(Object resolvedMethod, int arity) {
        if (!(resolvedMethod instanceof Method m)) {
            return false;
        }
        return getOrComputeMethodHandle(m, arity) != null;
    }

    /**
     * Returns the cached adapted {@link MethodHandle} for {@code resolvedMethod}, or null if ineligible.
     */
    @CompilerDirectives.TruffleBoundary
    public static MethodHandle createMethodHandle(Object resolvedMethod, int arity) {
        if (!(resolvedMethod instanceof Method m)) {
            return null;
        }
        return getOrComputeMethodHandle(m, arity);
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
    private static MethodHandle computeMethodHandle(Method m, int arity) {
        try {
            int mods = m.getModifiers();
            if (!Modifier.isStatic(mods) || !Modifier.isPublic(mods)) {
                return null;
            }
            Class<?> declaring = m.getDeclaringClass();
            if (!Modifier.isPublic(declaring.getModifiers())) {
                return null;
            }
            // Compiler probes such as inCompiledCode() report partial-evaluation state and are
            // substituted at their own call site, which a MethodHandle adapter hides. They stay
            // on the path that special-cases them in BytecodeInterop.staticMethod.
            if (declaring == CompilerDirectives.class
                    || "com.oracle.truffle.api.CompilerDirectives".equals(declaring.getName())) {
                return null;
            }
            if (m.getParameterCount() != arity) {
                return null;
            }
            Class<?>[] params = m.getParameterTypes();
            for (Class<?> p : params) {
                // Functional-interface params need proxy adaptation, which stays on the reflective path.
                if (clojure.lang.Compiler.FISupport.maybeFIMethod(p) != null) {
                    return null;
                }
            }
            if (m.getReturnType() == void.class) {
                return null;
            }
            MethodHandle mh = LOOKUP.unreflect(m).asFixedArity();
            for (int i = 0; i < params.length; i++) {
                if (params[i].isPrimitive()) {
                    mh = MethodHandles.filterArguments(mh, i, primitiveArgFilter(params[i]));
                }
            }
            // A primitive return is boxed by asType, matching what reflective invocation hands back.
            return mh.asType(MethodType.genericMethodType(arity));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Coerces one argument the way the reflective path does, so both agree on what a Clojure
     * value converts to and on the exception when it does not convert. Unlike that path, only
     * coercion failures become {@link ClassCastException} here; an {@code IllegalArgumentException}
     * thrown by the target method itself propagates unchanged.
     */
    public static Object coerceArg(Class<?> paramType, Object arg) {
        try {
            return clojure.lang.Reflector.boxArg(paramType, arg);
        } catch (IllegalArgumentException e) {
            throw new ClassCastException(e.getMessage());
        }
    }

    /**
     * Builds the {@code (Object)primitive} filter for one parameter. The trailing {@code asType}
     * unboxes the wrapper {@code coerceArg} returns. Both fold away once the type is constant.
     */
    @CompilerDirectives.TruffleBoundary
    private static MethodHandle primitiveArgFilter(Class<?> primitive) {
        return MethodHandles.insertArguments(COERCE_ARG, 0, primitive)
                .asType(MethodType.methodType(primitive, Object.class));
    }

    /**
     * Unwraps polyglot nil before invocation without allocating an array.
     */
    public static Object unwrap(Object o) {
        return ClojureInterop.unwrapFromPolyglot(o);
    }

    /**
     * Handles exceptions from MethodHandle.invokeExact consistently with Cloffle interop.
     */
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
