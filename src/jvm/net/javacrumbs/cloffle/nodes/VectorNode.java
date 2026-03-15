package net.javacrumbs.cloffle.nodes;

import clojure.lang.RT;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

public class VectorNode extends ClojureNode {

    @Children
    private final ClojureNode[] items;

    public VectorNode(ClojureNode[] items) {
        this.items = items;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object[] values = new Object[items.length];
        for (int i = 0; i < items.length; i++) {
            values[i] = ClojureInterop.unwrapFromPolyglot(items[i].executeGeneric(virtualFrame));
        }
        return RT.vector(values);
    }
}
