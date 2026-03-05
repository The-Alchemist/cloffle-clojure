package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

public class ThrowNode extends ClojureNode {

    @Child
    private ClojureNode exception;

    public ThrowNode(ClojureNode exception) {
        this.exception = exception;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object value = exception.executeGeneric(virtualFrame);
        Object unwrapped = ClojureInterop.unwrap(value);
        if (unwrapped instanceof RuntimeException re) {
            throw re;
        } else if (unwrapped instanceof Exception e) {
            throw new RuntimeException(e);
        } else if (unwrapped instanceof Throwable t) {
            throw new RuntimeException(t);
        }
        throw new RuntimeException("Cannot throw non-Throwable: " + unwrapped);
    }
}
