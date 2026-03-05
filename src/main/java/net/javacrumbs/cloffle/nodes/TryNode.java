package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

public class TryNode extends ClojureNode {

    @Child
    private ClojureNode body;

    @Children
    private final CatchNode[] catchNodes;

    @Child
    private ClojureNode finallyNode;

    public TryNode(ClojureNode body, CatchNode[] catchNodes, ClojureNode finallyNode) {
        this.body = body;
        this.catchNodes = catchNodes;
        this.finallyNode = finallyNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        try {
            return body.executeGeneric(virtualFrame);
        } catch (Exception e) {
            Throwable root = unwrapToRoot(e);
            for (CatchNode catchNode : catchNodes) {
                if (catchNode.matches(root)) {
                    return catchNode.executeWithException(virtualFrame, root);
                }
            }
            throw e;
        } finally {
            if (finallyNode != null) {
                finallyNode.executeGeneric(virtualFrame);
            }
        }
    }

    private static Throwable unwrapToRoot(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }
}
