package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

import java.lang.reflect.Field;

public class InstanceFieldNode extends ClojureNode {

    private final String fieldName;

    @Child
    private ClojureNode instance;

    public InstanceFieldNode(String fieldName, ClojureNode instance) {
        this.fieldName = fieldName;
        this.instance = instance;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object obj = instance.executeGeneric(virtualFrame);
        Object unwrapped = ClojureInterop.unwrap(obj);
        try {
            Field field = findField(unwrapped.getClass());
            field.setAccessible(true);
            Object result = field.get(unwrapped);
            return ClojureInterop.wrap(result);
        } catch (Exception e) {
            throw new RuntimeException("Cannot access field '" + fieldName + "' on " + unwrapped.getClass().getName(), e);
        }
    }

    private Field findField(Class<?> clazz) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName + " on " + clazz.getName());
    }
}
