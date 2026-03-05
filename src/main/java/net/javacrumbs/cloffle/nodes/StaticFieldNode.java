package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

import java.lang.reflect.Field;

public class StaticFieldNode extends ClojureNode {

    private final Class<?> clazz;
    private final String fieldName;

    public StaticFieldNode(Class<?> clazz, String fieldName) {
        this.clazz = clazz;
        this.fieldName = fieldName;
    }

    public Class<?> getClazz() {
        return clazz;
    }

    public String getFieldName() {
        return fieldName;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Cannot access static field " + clazz.getName() + "/" + fieldName, e);
        }
    }
}
