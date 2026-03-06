package net.javacrumbs.cloffle.nodes;

import clojure.lang.Reflector;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.NilNode;

public class NewNode extends ClojureNode {

    private final Class<?> clazz;

    @Children
    private final ClojureNode[] args;

    public NewNode(Class<?> clazz, ClojureNode[] args) {
        this.clazz = clazz;
        this.args = args;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object[] argValues = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object v = args[i].executeGeneric(virtualFrame);
            if (v instanceof NilNode.Nil) v = null;
            else if (v instanceof FnNode fnNode) v = fnNode.toIFn();
            argValues[i] = v;
        }

        try {
            return Reflector.invokeConstructor(clazz, argValues);
        } catch (Exception e) {
            throw ClojureException.wrap(e, this);
        }
    }
}
