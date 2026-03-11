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
        } catch (Throwable e) {
            // ThrowNode wraps user exceptions in ClojureException; unwrap one level so
            // (catch Exception e) matches and binds the user's exception, not the wrapper.
            Throwable toMatch = e;
            Throwable toBind = e;
            if (e instanceof ClojureException ce && ce.getCause() != null) {
                toMatch = ce.getCause();
                toBind = ce.getCause();
            }
            for (CatchNode catchNode : catchNodes) {
                if (catchNode.matches(toMatch)) {
                    return catchNode.executeWithException(virtualFrame, toBind);
                }
            }
            if (e instanceof RuntimeException re) throw re;
            if (e instanceof Error err) throw err;
            throw new RuntimeException(e);
        } finally {
            if (finallyNode != null) {
                finallyNode.executeGeneric(virtualFrame);
            }
        }
    }
}
