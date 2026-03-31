package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import net.javacrumbs.cloffle.nodes.value.NilNode;

/**
 * Executes a sequence of top-level forms. Each form's {@link CallTarget}
 * (produced by either the AST or bytecode backend) is wrapped in a
 * {@link DirectCallNode} so the debugger can step between forms.
 */
public class SequentialFormNode extends ClojureNode {

    @Children
    private final DirectCallNode[] callNodes;

    public SequentialFormNode(CallTarget[] targets) {
        callNodes = new DirectCallNode[targets.length];
        for (int i = 0; i < targets.length; i++) {
            callNodes[i] = DirectCallNode.create(targets[i]);
        }
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object lastResult = NilNode.NIL;
        for (int i = 0; i < callNodes.length; i++) {
            lastResult = callNodes[i].call();
        }
        return lastResult;
    }
}
