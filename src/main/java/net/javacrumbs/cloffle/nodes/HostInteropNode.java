package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

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
        Object unwrapped = ClojureInterop.unwrap(obj);

        Object[] argValues = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            argValues[i] = ClojureInterop.unwrap(args[i].executeGeneric(virtualFrame));
        }

        if (args.length == 0) {
            Object fieldResult = tryField(unwrapped);
            if (fieldResult != SENTINEL) {
                return ClojureInterop.wrap(fieldResult);
            }
        }

        Object methodResult = tryMethod(unwrapped, argValues);
        if (methodResult != SENTINEL) {
            return ClojureInterop.wrap(methodResult);
        }

        if (args.length > 0) {
            Object fieldResult = tryField(unwrapped);
            if (fieldResult != SENTINEL) {
                return ClojureInterop.wrap(fieldResult);
            }
        }

        throw new RuntimeException("Cannot resolve member '" + memberName + "' on " + unwrapped.getClass().getName());
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
