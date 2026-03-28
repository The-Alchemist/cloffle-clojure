package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;

public class ThrowNode extends ClojureNode {

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        return tag == StandardTags.StatementTag.class
            || tag == StandardTags.ExpressionTag.class;
    }

    @Child
    private ClojureNode exception;

    public ThrowNode(ClojureNode exception) {
        this.exception = exception;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object value = exception.executeGeneric(virtualFrame);
        if (value instanceof AbstractTruffleException ate) {
            throw ate;
        }
        if (value instanceof Throwable t) {
            throw ClojureException.wrap(t, this);
        }
        throw new ClojureException("Cannot throw non-Throwable: " + value, this);
    }
}
