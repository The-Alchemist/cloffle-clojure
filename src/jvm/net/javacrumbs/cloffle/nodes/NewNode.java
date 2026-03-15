package net.javacrumbs.cloffle.nodes;

import clojure.lang.Reflector;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

public class NewNode extends ClojureNode {

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
                Object[] boxed = Reflector.boxArgs(resolvedCtor.getParameterTypes(), argValues);
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
}
