package net.javacrumbs.cloffle.nodes;

import clojure.lang.Reflector;
import com.oracle.truffle.api.frame.VirtualFrame;

public class InstanceFieldNode extends ClojureNode {

    private final String fieldName;

    @Child
    private ClojureNode instance;

    public InstanceFieldNode(String fieldName, ClojureNode instance) {
        this.fieldName = fieldName;
        this.instance = instance;
    }

    public String getFieldName() {
        return fieldName;
    }

    public Object evaluateInstance(VirtualFrame virtualFrame) {
        return instance.executeGeneric(virtualFrame);
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object obj = instance.executeGeneric(virtualFrame);
        return Reflector.getInstanceField(obj, fieldName);
    }
}
