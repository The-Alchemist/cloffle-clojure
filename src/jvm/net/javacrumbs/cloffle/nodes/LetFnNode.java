package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.binding.BindingNode;

/**
 * letfn must expose all local functions to each closure, including mutually
 * recursive calls. We first create the closures, bind them to the frame, then
 * repoint those closures at one shared snapshot that contains the final set of
 * letfn bindings.
 */
public class LetFnNode extends ClojureNode {

    @Children
    private final BindingNode[] bindings;

    @Child
    private ClojureNode body;

    public LetFnNode(BindingNode[] bindings, ClojureNode body) {
        this.bindings = bindings;
        this.body = body;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object[] boundValues = new Object[bindings.length];
        for (int i = 0; i < bindings.length; i++) {
            boundValues[i] = bindings[i].executeGeneric(virtualFrame);
        }

        MaterializedFrame capturedFrame = ClojureRootNode.snapshotFrame(virtualFrame);
        for (Object value : boundValues) {
            if (value instanceof ClojureClosure closure) {
                closure.setCapturedFrame(capturedFrame);
            }
        }

        return body.executeGeneric(virtualFrame);
    }
}
