package net.javacrumbs.cloffle.nodes;

import clojure.lang.Reflector;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.vars.LocalNode;
import net.javacrumbs.cloffle.nodes.vars.VarNode;

public class SetBangNode extends ClojureNode {

    @Child
    private ClojureNode target;

    @Child
    private ClojureNode val;

    public SetBangNode(ClojureNode target, ClojureNode val) {
        this.target = target;
        this.val = val;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object value = val.executeGeneric(virtualFrame);

        if (target instanceof VarNode varNode) {
            varNode.getVar().set(value);
            return value;
        } else if (target instanceof StaticFieldNode sfn) {
            return Reflector.setStaticField(sfn.getClazz(), sfn.getFieldName(), value);
        } else if (target instanceof LocalNode localNode) {
            virtualFrame.setObject(localNode.getSlot(), value);
            return value;
        } else if (target instanceof InstanceFieldNode ifn) {
            Object instance = ifn.evaluateInstance(virtualFrame);
            return Reflector.setInstanceField(instance, ifn.getFieldName(), value);
        }

        throw new UnsupportedOperationException("set! target type not supported: " + target.getClass().getName());
    }
}
