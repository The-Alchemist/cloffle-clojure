package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;

public class FnDispatchNode extends ClojureNode {

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        return tag == StandardTags.RootBodyTag.class
            || tag == StandardTags.RootTag.class;
    }
    @Child
    private FnNode fnNode;

    public FnDispatchNode(FnNode fnNode) {
        this.fnNode = fnNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        return fnNode.invoke(virtualFrame);
    }
}
