package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Handles unresolved host interop (reflective method call or field access).
 * This is used when the analyzer cannot determine the target type at compile time.
 */
public class HostInteropNode extends ClojureNode {

    private final String memberName;

    @Child
    private ClojureNode target;

    @Children
    private final ClojureNode[] args;

    public HostInteropNode(String memberName, ClojureNode target, ClojureNode[] args) {
        this.memberName = memberName;
        this.target = target;
        this.args = args;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object obj = target.executeGeneric(virtualFrame);

        Object[] argValues = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            argValues[i] = args[i].executeGeneric(virtualFrame);
        }

        if (args.length == 0) {
            Object fieldResult = tryField(obj);
            if (fieldResult != SENTINEL) {
                return fieldResult;
            }
        }

        Object methodResult = tryMethod(obj, argValues);
        if (methodResult != SENTINEL) {
            return methodResult;
        }

        if (args.length > 0) {
            Object fieldResult = tryField(obj);
            if (fieldResult != SENTINEL) {
                return fieldResult;
            }
        }

        throw new RuntimeException("Cannot resolve member '" + memberName + "' on " + obj.getClass().getName());
    }

    private static final Object SENTINEL = new Object();

    private Object tryField(Object obj) {
        try {
            Class<?> clazz = obj.getClass();
            while (clazz != null) {
                try {
                    Field field = clazz.getDeclaredField(memberName);
                    field.setAccessible(true);
                    return field.get(obj);
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
        } catch (Exception ignored) {
        }
        return SENTINEL;
    }

    private Object tryMethod(Object obj, Object[] argValues) {
        try {
            for (Method method : obj.getClass().getMethods()) {
                if (method.getName().equals(memberName) && method.getParameterCount() == argValues.length) {
                    method.setAccessible(true);
                    return method.invoke(obj, argValues);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error invoking " + memberName + " on " + obj.getClass().getName(), e);
        }
        return SENTINEL;
    }
}
