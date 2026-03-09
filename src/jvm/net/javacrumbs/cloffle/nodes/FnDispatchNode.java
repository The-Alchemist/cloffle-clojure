package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

public class FnDispatchNode extends ClojureNode {
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
