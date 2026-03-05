package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

public class InstanceCheckNode extends ClojureNode {

    private final Class<?> clazz;

    @Child
    private ClojureNode target;

    public InstanceCheckNode(Class<?> clazz, ClojureNode target) {
        this.clazz = clazz;
        this.target = target;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object value = target.executeGeneric(virtualFrame);
        return clazz.isInstance(value);
    }
}
