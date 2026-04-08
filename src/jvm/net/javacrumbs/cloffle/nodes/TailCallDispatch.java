package net.javacrumbs.cloffle.nodes;

import clojure.lang.IFn;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.debug.DebuggerTailCallPolicy;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

/**
 * Tail-call dispatch shared by {@link net.javacrumbs.cloffle.nodes.invoke.InvokeNode} and bytecode
 * {@code InvokeTail} (mutual tail calls and {@link DebuggerTailCallPolicy}). Self-tail
 * ({@link SelfTailCallSentinel}) is handled only on the AST path in {@code InvokeNode}.
 */
public final class TailCallDispatch {

    public record ResolvedTruffleCall(CallTarget callTarget, Object closureFrame) {}

    private TailCallDispatch() {
    }

    /**
     * Invoked after {@code InvokeNode} handles self-tail (or when self-tail does not apply, e.g. bytecode).
     */
    public static Object afterSelfTailHandled(
            boolean tailPosition,
            Object fnValue,
            Object[] resolvedArgs,
            Node callSiteNode) {
        if (tailPosition) {
            ResolvedTruffleCall tailCall = resolveTruffleCall(fnValue);
            if (tailCall != null && !DebuggerTailCallPolicy.preservePhysicalStackForDebugger()) {
                TailCallException tce = new TailCallException(tailCall.callTarget(), tailCall.closureFrame(), resolvedArgs);
                if (callSiteNode != null) {
                    tce.addEliminatedCallSite(callSiteNode);
                }
                throw tce;
            }
        }
        return invokeGeneric(fnValue, resolvedArgs, callSiteNode);
    }

    /** Continues a Truffle call chain after {@link TailCallException} (e.g. static-fn {@code InvokeNode} path). */
    public static Object resumeInvokeTruffleChain(CallTarget callTarget, Object closureFrame, Object[] args, Node callSiteNode) {
        return invokeTruffleTarget(callTarget, closureFrame, args, callSiteNode);
    }

    public static ResolvedTruffleCall resolveTruffleCall(Object fnValue) {
        if (fnValue instanceof ClojureClosure closure) {
            return new ResolvedTruffleCall(closure.getCallTarget(), closure.getCapturedFrame());
        }
        if (fnValue instanceof TruffleIFn truffleIFn) {
            return new ResolvedTruffleCall(truffleIFn.getCallTarget(), null);
        }
        if (fnValue instanceof FnNode fnNode) {
            ClojureClosure closure = (ClojureClosure) fnNode.toIFn();
            return new ResolvedTruffleCall(closure.getCallTarget(), closure.getCapturedFrame());
        }
        return null;
    }

    private static Object invokeGeneric(Object fnValue, Object[] args, Node callSiteNode) {
        ResolvedTruffleCall resolvedCall = resolveTruffleCall(fnValue);
        if (resolvedCall != null) {
            return invokeTruffleTarget(resolvedCall.callTarget(), resolvedCall.closureFrame(), args, callSiteNode);
        }
        if (fnValue instanceof IFn ifn) {
            try {
                return invokeIFnDirect(ifn, args);
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
        throw new ClojureException(ErrorMessages.cannotCallMessage(fnValue), callSiteNode);
    }

    /**
     * Use {@link CallTarget#call}, not {@link com.oracle.truffle.api.nodes.IndirectCallNode}: the latter can
     * skip the same callee entry path as {@link ClojureClosure}'s {@code doCall}, which breaks Truffle
     * bytecode roots that reserve frame slot 0 for the current BCI (see {@code storeBytecodeIndexInFrame}).
     */
    private static Object invokeTruffleTarget(CallTarget callTarget, Object closureFrame, Object[] args, Node callSiteNode) {
        for (int i = 0; i < args.length; i++) {
            args[i] = ClojureInterop.unwrapFromPolyglot(args[i]);
        }
        java.util.List<Node> tailCallSites = null;
        while (true) {
            Object[] callArgs = new Object[1 + args.length];
            callArgs[0] = closureFrame;
            System.arraycopy(args, 0, callArgs, 1, args.length);
            try {
                return callTarget.call(callArgs);
            } catch (TailCallException e) {
                if (tailCallSites == null) {
                    tailCallSites = new java.util.ArrayList<>(4);
                }
                tailCallSites.addAll(e.getEliminatedCallSites());
                callTarget = e.getCallTarget();
                closureFrame = e.getClosureFrame();
                args = e.getArgs();
            } catch (com.oracle.truffle.api.exception.AbstractTruffleException ate) {
                CompilerDirectives.transferToInterpreter();
                if (ate instanceof ClojureException ce) {
                    if (tailCallSites != null) {
                        for (int i = tailCallSites.size() - 1; i >= 0; i--) {
                            ce.addFrame(tailCallSites.get(i));
                        }
                    }
                    if (callSiteNode != null) {
                        ce.addFrame(callSiteNode);
                    }
                }
                throw ate;
            }
        }
    }

    @CompilerDirectives.TruffleBoundary
    private static Object invokeIFnDirect(IFn ifn, Object[] args) {
        for (int i = 0; i < args.length; i++) {
            args[i] = ClojureInterop.unwrapFromPolyglot(args[i]);
        }
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
    }
}
