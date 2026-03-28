package net.javacrumbs.cloffle.nodes;

import clojure.lang.Reflector;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

public class NewNode extends ClojureNode {

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        return tag == StandardTags.CallTag.class
            || tag == StandardTags.ExpressionTag.class;
    }

    private final Class<?> clazz;
    private final java.lang.reflect.Constructor<?> resolvedCtor;

    @Children
    private final ClojureNode[] args;

    public NewNode(Class<?> clazz, ClojureNode[] args) {
        this(clazz, args, null);
    }

    public NewNode(Class<?> clazz, ClojureNode[] args, java.lang.reflect.Constructor<?> resolvedCtor) {
        this.clazz = clazz;
        this.args = args;
        this.resolvedCtor = resolvedCtor;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object[] argValues = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            argValues[i] = ClojureInterop.unwrapFromPolyglot(args[i].executeGeneric(virtualFrame));
        }
        try {
            if (resolvedCtor != null) {
                Object[] boxed;
                try {
                    boxed = Reflector.boxArgs(resolvedCtor.getParameterTypes(),
                            coercePrimitiveInteropArgs(resolvedCtor.getParameterTypes(), argValues));
                } catch (IllegalArgumentException e) {
                    throw new ClassCastException(e.getMessage());
                }
                return resolvedCtor.newInstance(boxed);
            }
            return Reflector.invokeConstructor(clazz, argValues);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof AbstractTruffleException) throw (AbstractTruffleException) cause;
            CompilerDirectives.transferToInterpreter();
            throw ClojureException.wrap(cause != null ? cause : ite, this);
        } catch (AbstractTruffleException e) {
            throw e;
        } catch (Throwable t) {
            CompilerDirectives.transferToInterpreter();
            throw ClojureException.wrap(t, this);
        }
    }

    /**
     * Mirrors Clojure interop coercion in resolved-constructor fast path.
     */
    private static Object[] coercePrimitiveInteropArgs(Class<?>[] parameterTypes, Object[] argValues) {
        Object[] coerced = argValues.clone();
        for (int i = 0; i < parameterTypes.length && i < coerced.length; i++) {
            Object arg = coerced[i];
            if (!(arg instanceof Character character)) {
                continue;
            }
            Class<?> paramType = parameterTypes[i];
            char ch = character.charValue();
            if (paramType == int.class) {
                coerced[i] = (int) ch;
            } else if (paramType == long.class) {
                coerced[i] = (long) ch;
            } else if (paramType == double.class) {
                coerced[i] = (double) ch;
            } else if (paramType == float.class) {
                coerced[i] = (float) ch;
            } else if (paramType == short.class) {
                coerced[i] = (short) ch;
            } else if (paramType == byte.class) {
                coerced[i] = (byte) ch;
            } else if (paramType == char.class) {
                coerced[i] = ch;
            }
        }
        return coerced;
    }
}
