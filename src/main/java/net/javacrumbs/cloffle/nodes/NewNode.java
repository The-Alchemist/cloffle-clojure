package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.NilNode;

import java.lang.reflect.Constructor;

public class NewNode extends ClojureNode {

    private final Class<?> clazz;

    @Children
    private final ClojureNode[] args;

    public NewNode(Class<?> clazz, ClojureNode[] args) {
        this.clazz = clazz;
        this.args = args;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object[] argValues = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object v = args[i].executeGeneric(virtualFrame);
            if (v instanceof NilNode.Nil) v = null;
            else if (v instanceof FnNode fnNode) v = fnNode.toIFn();
            argValues[i] = v;
        }

        try {
            Constructor<?>[] constructors = clazz.getConstructors();
            for (Constructor<?> ctor : constructors) {
                if (ctor.getParameterCount() == argValues.length && matches(ctor, argValues)) {
                    return ctor.newInstance(argValues);
                }
            }
            throw new RuntimeException("No matching constructor found for " + clazz.getName() + " with " + argValues.length + " args");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean matches(Constructor<?> ctor, Object[] argValues) {
        Class<?>[] paramTypes = ctor.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            if (argValues[i] != null && !isAssignable(paramTypes[i], argValues[i].getClass())) {
                return false;
            }
        }
        return true;
    }

    private boolean isAssignable(Class<?> paramType, Class<?> argType) {
        if (paramType.isAssignableFrom(argType)) return true;
        if (paramType.isPrimitive()) {
            if (paramType == int.class && argType == Integer.class) return true;
            if (paramType == long.class && (argType == Long.class || argType == Integer.class)) return true;
            if (paramType == double.class && (argType == Double.class || argType == Float.class)) return true;
            if (paramType == boolean.class && argType == Boolean.class) return true;
        }
        return false;
    }
}
