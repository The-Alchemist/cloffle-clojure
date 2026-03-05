package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.NilNode;

public class MonitorExitNode extends ClojureNode {

    @Child
    private ClojureNode target;

    public MonitorExitNode(ClojureNode target) {
        this.target = target;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object obj = target.executeGeneric(virtualFrame);
        MonitorRegistry.exit(obj);
        return NilNode.NIL;
    }
}
