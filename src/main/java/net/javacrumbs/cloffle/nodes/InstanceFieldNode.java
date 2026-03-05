package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

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
        try {
            Field field = findField(obj.getClass());
            field.setAccessible(true);
            return field.get(obj);
        } catch (Exception e) {
            throw new RuntimeException("Cannot access field '" + fieldName + "' on " + obj.getClass().getName(), e);
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
