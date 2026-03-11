package net.javacrumbs.cloffle.nodes;

import clojure.lang.Util;
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
        if (value instanceof Throwable t) {
            throw Util.sneakyThrow(t);
        }
        throw new ClojureException("Cannot throw non-Throwable: " + value, this);
    }
}
