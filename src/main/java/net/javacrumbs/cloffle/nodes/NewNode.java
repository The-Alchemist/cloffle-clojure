package net.javacrumbs.cloffle.nodes;

import clojure.lang.Reflector;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

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
            argValues[i] = ClojureInterop.unwrapFromPolyglot(args[i].executeGeneric(virtualFrame));
        }

        try {
            return Reflector.invokeConstructor(clazz, argValues);
        } catch (Exception e) {
            throw ClojureException.wrap(e, this);
        }
    }
}
