package net.javacrumbs.cloffle.nodes;

import clojure.lang.RT;
import com.oracle.truffle.api.frame.VirtualFrame;

public class ListNode extends ClojureNode {

    @Children
    private final ClojureNode[] items;

    public ListNode(ClojureNode[] items) {
        this.items = items;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object[] values = new Object[items.length];
        for (int i = 0; i < items.length; i++) {
            values[i] = items[i].executeGeneric(virtualFrame);
        }
        return RT.arrayToList(values);
    }
}
