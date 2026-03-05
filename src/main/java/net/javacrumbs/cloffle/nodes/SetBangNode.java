package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

import java.lang.reflect.Field;

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

        if (target instanceof StaticFieldNode sfn) {
            try {
                Field field = sfn.getClazz().getDeclaredField(sfn.getFieldName());
                field.setAccessible(true);
                field.set(null, value);
                return value;
            } catch (Exception e) {
                throw new RuntimeException("Cannot set static field " + sfn.getClazz().getName() + "/" + sfn.getFieldName(), e);
            }
        } else if (target instanceof InstanceFieldNode) {
            throw new UnsupportedOperationException("set! on instance fields not yet supported");
        }

        throw new UnsupportedOperationException("set! target type not supported: " + target.getClass().getName());
    }
}
