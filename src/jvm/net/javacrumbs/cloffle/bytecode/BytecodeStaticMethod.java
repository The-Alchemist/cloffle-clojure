package net.javacrumbs.cloffle.bytecode;

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

    public static final Object[] EMPTY_ARRAY = new Object[0];

    private BytecodeStaticMethod() {
    }

    /**
     * Checks if {@code resolvedMethod} is eligible for the fast {@link MethodHandle} path:
     * - Must be a resolved {@link Method}
     * - Must be public static on a public class
     * - Arity must match parameter count
     * - All parameters and return type must be non-primitive references (excluding void)
     * - Unreflecting and adapting to generic signature must succeed
     */
    @Idempotent
    public static boolean isEligible(Object resolvedMethod, int arity) {
        if (!(resolvedMethod instanceof Method m)) {
            return false;
        }
        return getOrComputeMethodHandle(m, arity) != null;
    }

    /**
     * Returns the cached adapted {@link MethodHandle} for {@code resolvedMethod}, or null if ineligible.
     */
    public static MethodHandle createMethodHandle(Object resolvedMethod, int arity) {
        if (!(resolvedMethod instanceof Method m)) {
            return null;
        }
        return getOrComputeMethodHandle(m, arity);
    }

    private static MethodHandle getOrComputeMethodHandle(Method m, int arity) {
        MethodHandle cached = MH_CACHE.get(m);
        if (cached != null) {
            return cached == INELIGIBLE ? null : cached;
        }
        MethodHandle created = computeMethodHandle(m, arity);
        MH_CACHE.put(m, created == null ? INELIGIBLE : created);
        return created;
    }

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
            if (m.getParameterCount() != arity) {
                return null;
            }
            for (Class<?> p : m.getParameterTypes()) {
                if (p.isPrimitive() || clojure.lang.Compiler.FISupport.maybeFIMethod(p) != null) {
                    return null;
                }
            }
            Class<?> ret = m.getReturnType();
            if (ret.isPrimitive() || ret == void.class) {
                return null;
            }
            MethodHandle mh = LOOKUP.unreflect(m).asFixedArity();
            return mh.asType(MethodType.genericMethodType(arity));
        } catch (Throwable t) {
            return null;
        }
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
