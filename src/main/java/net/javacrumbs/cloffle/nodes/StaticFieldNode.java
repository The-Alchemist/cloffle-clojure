package net.javacrumbs.cloffle.nodes;

import clojure.lang.Reflector;
import com.oracle.truffle.api.frame.VirtualFrame;

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
        return Reflector.getStaticField(clazz, fieldName);
    }
}
