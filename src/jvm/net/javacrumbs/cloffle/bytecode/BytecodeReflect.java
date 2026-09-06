package net.javacrumbs.cloffle.bytecode;

import clojure.lang.IFn;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

final class BytecodeReflect {
    private BytecodeReflect() {
    }

    /**
     * Unwrap polyglot nil ({@code NilNode}) and similar before {@link clojure.lang.Reflector} /
     * {@code Method.invoke} — same boundary as {@link ClojureInterop} at the host boundary.
     */
    static Object unwrapForReflect(Object o) {
        return ClojureInterop.unwrapFromPolyglot(o);
    }

    static Object[] unwrapArgsForReflect(Object[] args) {
        if (args == null || args.length == 0) {
            return args;
        }
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = unwrapForReflect(args[i]);
        }
        return out;
    }

    /**
     * If {@code instance} is an {@link IFn} and {@code declaringClass} is a
     * {@link FunctionalInterface} that the instance doesn't already implement,
     * wrap it in a dynamic proxy via {@link clojure.lang.Reflector#boxArg}.
     */
    static Object adaptFIInstance(Class<?> declaringClass, Object instance) {
        if (instance instanceof IFn && !declaringClass.isInstance(instance)
                && clojure.lang.Compiler.FISupport.maybeFIMethod(declaringClass) != null) {
            return clojure.lang.Reflector.boxArg(declaringClass, instance);
        }
        return instance;
    }
}
