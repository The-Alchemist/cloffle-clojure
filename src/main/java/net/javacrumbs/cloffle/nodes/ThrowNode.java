package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

public class ThrowNode extends ClojureNode {

    @Child
    private ClojureNode exception;

    public ThrowNode(ClojureNode exception) {
        this.exception = exception;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object value = exception.executeGeneric(virtualFrame);
        if (value instanceof RuntimeException re) {
            throw re;
        } else if (value instanceof Exception e) {
            throw new RuntimeException(e);
        } else if (value instanceof Throwable t) {
            throw new RuntimeException(t);
        }
        throw new RuntimeException("Cannot throw non-Throwable: " + value);
    }
}
