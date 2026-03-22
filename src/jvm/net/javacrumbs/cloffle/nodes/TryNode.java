package net.javacrumbs.cloffle.nodes;

import clojure.lang.Util;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ControlFlowException;

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
        } catch (ControlFlowException e) {
            throw e;
        } catch (Throwable e) {
            Throwable unwrapped = unwrapClojureException(e);
            for (CatchNode catchNode : catchNodes) {
                if (catchNode.matches(unwrapped)) {
                    return catchNode.executeWithException(virtualFrame, unwrapped);
                }
            }
            if (e instanceof AbstractTruffleException) {
                throw (AbstractTruffleException) e;
            }
            throw Util.sneakyThrow(unwrapped);
        } finally {
            if (finallyNode != null) {
                finallyNode.executeGeneric(virtualFrame);
            }
        }
    }

    private static Throwable unwrapClojureException(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof ClojureException ce && ce.getCause() != null) {
            current = ce.getCause();
        }
        return current;
    }
}
