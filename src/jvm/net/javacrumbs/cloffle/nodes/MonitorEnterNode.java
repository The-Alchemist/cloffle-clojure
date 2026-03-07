package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.NilNode;

public class MonitorEnterNode extends ClojureNode {

    @Child
    private ClojureNode target;

    public MonitorEnterNode(ClojureNode target) {
        this.target = target;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object obj = target.executeGeneric(virtualFrame);
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        MonitorRegistry.enter(obj);
        return NilNode.NIL;
    }
}
