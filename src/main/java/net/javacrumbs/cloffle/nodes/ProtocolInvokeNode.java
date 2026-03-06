package net.javacrumbs.cloffle.nodes;

import clojure.lang.IFn;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

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
        Object fn = ClojureInterop.unwrapFromPolyglot(protocolFn.executeGeneric(virtualFrame));
        Object tgt = ClojureInterop.unwrapFromPolyglot(target.executeGeneric(virtualFrame));

        if (!(fn instanceof IFn ifn)) {
            throw new RuntimeException("Protocol function is not an IFn: " + fn.getClass().getName());
        }

        int totalArgs = args.length + 1;
        switch (totalArgs) {
            case 1: return ifn.invoke(tgt);
            case 2: return ifn.invoke(tgt, ClojureInterop.unwrapFromPolyglot(args[0].executeGeneric(virtualFrame)));
            case 3: return ifn.invoke(tgt,
                    ClojureInterop.unwrapFromPolyglot(args[0].executeGeneric(virtualFrame)),
                    ClojureInterop.unwrapFromPolyglot(args[1].executeGeneric(virtualFrame)));
            case 4: return ifn.invoke(tgt,
                    ClojureInterop.unwrapFromPolyglot(args[0].executeGeneric(virtualFrame)),
                    ClojureInterop.unwrapFromPolyglot(args[1].executeGeneric(virtualFrame)),
                    ClojureInterop.unwrapFromPolyglot(args[2].executeGeneric(virtualFrame)));
            default:
                Object[] allArgs = new Object[totalArgs];
                allArgs[0] = tgt;
                for (int i = 0; i < args.length; i++) {
                    allArgs[i + 1] = ClojureInterop.unwrapFromPolyglot(args[i].executeGeneric(virtualFrame));
                }
                return ifn.applyTo(clojure.lang.RT.seq(allArgs));
        }
    }

}
