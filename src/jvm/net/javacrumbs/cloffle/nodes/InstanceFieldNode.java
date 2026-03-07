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

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object obj = instance.executeGeneric(virtualFrame);
        return Reflector.getInstanceField(obj, fieldName);
    }
}
