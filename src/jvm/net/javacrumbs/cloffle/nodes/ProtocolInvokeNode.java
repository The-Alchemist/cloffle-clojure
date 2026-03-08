package net.javacrumbs.cloffle.nodes;

import clojure.lang.IFn;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
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
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object fn = ClojureInterop.unwrapFromPolyglot(protocolFn.executeGeneric(virtualFrame));
        Object tgt = ClojureInterop.unwrapFromPolyglot(target.executeGeneric(virtualFrame));

        if (!(fn instanceof IFn ifn)) {
            throw new RuntimeException("Protocol function is not an IFn: " + fn.getClass().getName());
        }

        Object[] resolvedArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            resolvedArgs[i] = ClojureInterop.unwrapFromPolyglot(args[i].executeGeneric(virtualFrame));
        }

        return invokeProtocol(ifn, tgt, resolvedArgs);
    }

    @CompilerDirectives.TruffleBoundary
    private static Object invokeProtocol(IFn ifn, Object tgt, Object[] args) {
        return switch (args.length + 1) {
            case 1 -> ifn.invoke(tgt);
            case 2 -> ifn.invoke(tgt, args[0]);
            case 3 -> ifn.invoke(tgt, args[0], args[1]);
            case 4 -> ifn.invoke(tgt, args[0], args[1], args[2]);
            case 5 -> ifn.invoke(tgt, args[0], args[1], args[2], args[3]);
            case 6 -> ifn.invoke(tgt, args[0], args[1], args[2], args[3], args[4]);
            case 7 -> ifn.invoke(tgt, args[0], args[1], args[2], args[3], args[4], args[5]);
            case 8 -> ifn.invoke(tgt, args[0], args[1], args[2], args[3], args[4], args[5], args[6]);
            case 9 -> ifn.invoke(tgt, args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7]);
            case 10 -> ifn.invoke(tgt, args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8]);
            case 11 -> ifn.invoke(tgt, args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9]);
            case 12 -> ifn.invoke(tgt, args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10]);
            case 13 -> ifn.invoke(tgt, args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11]);
            case 14 -> ifn.invoke(tgt, args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12]);
            case 15 -> ifn.invoke(tgt, args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13]);
            case 16 -> ifn.invoke(tgt, args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14]);
            case 17 -> ifn.invoke(tgt, args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15]);
            case 18 -> ifn.invoke(tgt, args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15], args[16]);
            case 19 -> ifn.invoke(tgt, args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15], args[16], args[17]);
            case 20 -> ifn.invoke(tgt, args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15], args[16], args[17], args[18]);
            default -> {
                Object[] allArgs = new Object[args.length + 1];
                allArgs[0] = tgt;
                System.arraycopy(args, 0, allArgs, 1, args.length);
                yield ifn.applyTo(clojure.lang.RT.seq(allArgs));
            }
        };
    }
}
