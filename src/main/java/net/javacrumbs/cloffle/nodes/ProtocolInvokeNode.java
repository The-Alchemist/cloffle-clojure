package net.javacrumbs.cloffle.nodes;

import clojure.lang.IFn;
import com.oracle.truffle.api.frame.VirtualFrame;

/**
 * Invokes a protocol function. The protocol function is called with
 * the target as the first argument, followed by any additional args.
 */
public class ProtocolInvokeNode extends ClojureNode {

    @Child
    private ClojureNode protocolFn;

    @Child
    private ClojureNode target;

    @Children
    private final ClojureNode[] args;

    public ProtocolInvokeNode(ClojureNode protocolFn, ClojureNode target, ClojureNode[] args) {
        this.protocolFn = protocolFn;
        this.target = target;
        this.args = args;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object fn = protocolFn.executeGeneric(virtualFrame);
        Object tgt = target.executeGeneric(virtualFrame);

        Object[] argValues = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            argValues[i] = args[i].executeGeneric(virtualFrame);
        }

        if (fn instanceof IFn ifn) {
            Object[] allArgs = new Object[argValues.length + 1];
            allArgs[0] = tgt;
            System.arraycopy(argValues, 0, allArgs, 1, argValues.length);
            return ifn.applyTo(clojure.lang.RT.seq(allArgs));
        }

        throw new RuntimeException("Protocol function is not an IFn: " + fn.getClass().getName());
    }
}
