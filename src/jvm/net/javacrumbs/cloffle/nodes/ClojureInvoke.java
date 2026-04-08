package net.javacrumbs.cloffle.nodes;

import clojure.lang.IFn;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.nodes.Node;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

/**
 * Dispatches a function value to a Truffle {@link CallTarget} or {@link IFn}, without tail-call optimization.
 */
public final class ClojureInvoke {

    private ClojureInvoke() {
    }

    private record ResolvedCall(CallTarget callTarget, Object closureFrame) {}

    /**
     * Invokes {@code fnValue} with {@code args} (mutated in place for unwrap). {@code callSiteNode} is
     * attached to {@link ClojureException} frames when non-null.
     */
    public static Object invoke(Object fnValue, Object[] args, Node callSiteNode) {
        ResolvedCall resolved = resolveCall(fnValue);
        if (resolved != null) {
            return callTruffle(resolved.callTarget(), resolved.closureFrame(), args, callSiteNode);
        }
        if (fnValue instanceof IFn ifn) {
            return invokeIFn(ifn, args, callSiteNode);
        }
        throw new ClojureException(ErrorMessages.cannotCallMessage(fnValue), callSiteNode);
    }

    private static ResolvedCall resolveCall(Object fnValue) {
        if (fnValue instanceof ClojureClosure closure) {
            return new ResolvedCall(closure.getCallTarget(), closure.getCapturedFrame());
        }
        if (fnValue instanceof TruffleIFn truffleIFn) {
            return new ResolvedCall(truffleIFn.getCallTarget(), null);
        }
        if (fnValue instanceof FnNode fnNode) {
            ClojureClosure closure = (ClojureClosure) fnNode.toIFn();
            return new ResolvedCall(closure.getCallTarget(), closure.getCapturedFrame());
        }
        return null;
    }

    private static Object callTruffle(CallTarget callTarget, Object closureFrame, Object[] args, Node callSiteNode) {
        for (int i = 0; i < args.length; i++) {
            args[i] = ClojureInterop.unwrapFromPolyglot(args[i]);
        }
        Object[] callArgs = new Object[1 + args.length];
        callArgs[0] = closureFrame;
        System.arraycopy(args, 0, callArgs, 1, args.length);
        try {
            return callTarget.call(callArgs);
        } catch (com.oracle.truffle.api.exception.AbstractTruffleException ate) {
            CompilerDirectives.transferToInterpreter();
            if (ate instanceof ClojureException ce && callSiteNode != null) {
                ce.addFrame(callSiteNode);
            }
            throw ate;
        }
    }

    @CompilerDirectives.TruffleBoundary
    private static Object invokeIFn(IFn ifn, Object[] args, Node callSiteNode) {
        for (int i = 0; i < args.length; i++) {
            args[i] = ClojureInterop.unwrapFromPolyglot(args[i]);
        }
        try {
            Object result = switch (args.length) {
                case 0 -> ifn.invoke();
                case 1 -> ifn.invoke(args[0]);
                case 2 -> ifn.invoke(args[0], args[1]);
                case 3 -> ifn.invoke(args[0], args[1], args[2]);
                case 4 -> ifn.invoke(args[0], args[1], args[2], args[3]);
                case 5 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4]);
                case 6 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5]);
                case 7 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6]);
                case 8 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7]);
                case 9 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8]);
                case 10 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9]);
                case 11 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10]);
                case 12 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11]);
                case 13 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12]);
                case 14 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13]);
                case 15 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14]);
                case 16 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15]);
                case 17 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15], args[16]);
                case 18 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15], args[16], args[17]);
                case 19 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15], args[16], args[17], args[18]);
                case 20 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15], args[16], args[17], args[18], args[19]);
                default -> ifn.applyTo(clojure.lang.RT.seq(args));
            };
            return ClojureInterop.wrapForPolyglot(result);
        } catch (com.oracle.truffle.api.exception.AbstractTruffleException e) {
            throw e;
        } catch (clojure.lang.ArityException e) {
            CompilerDirectives.transferToInterpreter();
            throw ClojureException.wrap(e, callSiteNode);
        } catch (Throwable t) {
            CompilerDirectives.transferToInterpreter();
            throw ClojureException.wrap(t, callSiteNode);
        }
    }
}
