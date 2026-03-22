/**
 * Copyright 2009-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.javacrumbs.cloffle.nodes.invoke;

import clojure.lang.IFn;
import clojure.lang.Util;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.nodes.ClojureClosure;
import net.javacrumbs.cloffle.nodes.ClojureException;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.ErrorMessages;
import net.javacrumbs.cloffle.nodes.ClojureRootNode;
import net.javacrumbs.cloffle.nodes.FnNode;
import net.javacrumbs.cloffle.nodes.FnDispatchNode;
import net.javacrumbs.cloffle.nodes.NativeCallNode;
import net.javacrumbs.cloffle.nodes.SelfTailCallSentinel;
import net.javacrumbs.cloffle.nodes.TailCallException;
import net.javacrumbs.cloffle.nodes.TruffleIFn;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;
import net.javacrumbs.cloffle.nodes.vars.VarNode;

import java.util.function.Supplier;

public class InvokeNode extends ClojureNode {
    private record ResolvedTruffleCall(CallTarget callTarget, Object closureFrame) {
    }

    @Child
    private ClojureNode fn;
    private FrameDescriptor frameDescriptor;
    private final Supplier<FrameDescriptor> frameDescriptorSupplier;
    private final Source source;
    private final com.oracle.truffle.api.TruffleLanguage<?> language;
    private final boolean fnIsStatic;
    private final boolean tailPosition;

    @Children
    private final ClojureNode[] args;

    @Child
    private DirectCallNode directCallNode;

    @Child
    private IndirectCallNode indirectCallNode;

    public InvokeNode(ClojureNode fn, FrameDescriptor frameDescriptor, Source source,
                      Object language, ClojureNode[] args) {
        this(fn, frameDescriptor, source, language, args, false);
    }

    public InvokeNode(ClojureNode fn, FrameDescriptor frameDescriptor, Source source,
                      Object language, ClojureNode[] args, boolean tailPosition) {
        this.fn = fn;
        this.frameDescriptor = frameDescriptor;
        this.frameDescriptorSupplier = null;
        this.source = source;
        this.language = (com.oracle.truffle.api.TruffleLanguage<?>) language;
        this.args = args;
        this.tailPosition = tailPosition;
        this.fnIsStatic = isStaticFn(fn);
    }

    public InvokeNode(ClojureNode fn, Supplier<FrameDescriptor> frameDescriptorSupplier, Source source,
                      Object language, ClojureNode[] args) {
        this(fn, frameDescriptorSupplier, source, language, args, false);
    }

    public InvokeNode(ClojureNode fn, Supplier<FrameDescriptor> frameDescriptorSupplier, Source source,
                      Object language, ClojureNode[] args, boolean tailPosition) {
        this.fn = fn;
        this.frameDescriptorSupplier = frameDescriptorSupplier;
        this.source = source;
        this.language = (com.oracle.truffle.api.TruffleLanguage<?>) language;
        this.args = args;
        this.tailPosition = tailPosition;
        this.fnIsStatic = isStaticFn(fn);
    }

    private static boolean isStaticFn(ClojureNode fn) {
        return fn instanceof FnNode fnNode && !fnNode.hasSelfReference();
    }

    private FrameDescriptor resolveFrameDescriptor() {
        if (frameDescriptor == null && frameDescriptorSupplier != null) {
            frameDescriptor = frameDescriptorSupplier.get();
        }
        return frameDescriptor;
    }

    private void initializeCallNode(VirtualFrame frame) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        CallTarget target;
        FrameDescriptor fd = resolveFrameDescriptor();
        String callName = null;

        if (fn instanceof VarNode varNode) {
            callName = varNode.getVar().sym.getName();
            Object val = varNode.getVar().deref();
            if (val instanceof TruffleIFn truffleIFn) {
                target = truffleIFn.getCallTarget();
            } else if (val instanceof FnNode fnNode) {
                FrameDescriptor fnFd = fnNode.getFrameDescriptor();
                if (fnFd == null) fnFd = fd;
                target = createRootWithSource(new FnDispatchNode(fnNode), fnFd, callName).getCallTarget();
            } else {
                NativeCallNode ncn = new NativeCallNode((IFn) val);
                copySourceSection(ncn);
                target = createRootWithSource(ncn, fd, callName).getCallTarget();
            }
        } else if (fn instanceof FnNode fnNode) {
            callName = fnNode.getFnName();
            FrameDescriptor fnFd = fnNode.getFrameDescriptor();
            if (fnFd == null) fnFd = fd;
            target = createRootWithSource(new FnDispatchNode(fnNode), fnFd, callName).getCallTarget();
        } else {
            target = createRootWithSource(fn, fd, null).getCallTarget();
        }

        this.directCallNode = insert(DirectCallNode.create(target));
    }

    private void copySourceSection(ClojureNode target) {
        if (hasSource()) {
            SourceSection ss = getSourceSection();
            if (ss != null && ss.isAvailable()) {
                target.setSourceSection(ss.getCharIndex(), ss.getCharLength());
            }
        }
    }

    private ClojureRootNode createRootWithSource(ClojureNode body, FrameDescriptor fd, String name) {
        ClojureRootNode rootNode = ClojureRootNode.createRaw(body, fd, language);
        if (source != null) {
            SourceSection callSiteSection = getSourceSection();
            if (callSiteSection != null) {
                rootNode.setSourceSection(callSiteSection);
            } else {
                rootNode.setSourceSection(source.createSection(0, source.getLength()));
            }
        }
        if (name != null) {
            rootNode.setName(name);
        }
        return rootNode;
    }

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object[] resolvedArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            resolvedArgs[i] = args[i].executeGeneric(virtualFrame);
        }

        if (fnIsStatic) {
            if (directCallNode == null) {
                initializeCallNode(virtualFrame);
            }
            for (int i = 0; i < resolvedArgs.length; i++) {
                resolvedArgs[i] = ClojureInterop.unwrapFromPolyglot(resolvedArgs[i]);
            }
            Object[] callArgs = new Object[1 + resolvedArgs.length];
            callArgs[0] = ClojureRootNode.snapshotFrame(virtualFrame);
            System.arraycopy(resolvedArgs, 0, callArgs, 1, resolvedArgs.length);
            try {
                return directCallNode.call(callArgs);
            } catch (TailCallException e) {
                e.addEliminatedCallSite(this);
                return invokeTruffleTarget(e.getCallTarget(), e.getClosureFrame(), e.getArgs());
            }
        }

        Object fnValue = fn.executeGeneric(virtualFrame);
        if (tailPosition && isSelfTailCall(fnValue, virtualFrame, resolvedArgs)) {
            return new SelfTailCallSentinel(resolvedArgs);
        }
        if (tailPosition) {
            ResolvedTruffleCall tailCall = resolveTruffleCall(fnValue);
            if (tailCall != null) {
                TailCallException tce = new TailCallException(tailCall.callTarget(), tailCall.closureFrame(), resolvedArgs);
                tce.addEliminatedCallSite(this);
                throw tce;
            }
        }
        return invokeGeneric(fnValue, resolvedArgs);
    }

    private boolean isSelfTailCall(Object fnValue, VirtualFrame virtualFrame, Object[] resolvedArgs) {
        Object[] currentArgs = virtualFrame.getArguments();
        if (currentArgs.length == 0) {
            return false;
        }
        // Only optimize calls that keep this method's arity.
        if (resolvedArgs.length != currentArgs.length - 1) {
            return false;
        }

        ResolvedTruffleCall resolvedCall = resolveTruffleCall(fnValue);
        CallTarget target = resolvedCall != null ? resolvedCall.callTarget() : null;
        if (target == null || getRootNode() == null) {
            return false;
        }
        return target == getRootNode().getCallTarget();
    }

    private Object invokeGeneric(Object fnValue, Object[] args) {
        ResolvedTruffleCall resolvedCall = resolveTruffleCall(fnValue);
        if (resolvedCall != null) {
            return invokeTruffleTarget(resolvedCall.callTarget(), resolvedCall.closureFrame(), args);
        }

        if (fnValue instanceof IFn ifn) {
            try {
                return invokeIFnDirect(ifn, args);
            } catch (com.oracle.truffle.api.exception.AbstractTruffleException e) {
                throw e;
            } catch (clojure.lang.ArityException e) {
                throw e;
            } catch (Throwable t) {
                Throwable unwrapped = unwrapCloffleException(t);
                CompilerDirectives.transferToInterpreter();
                throw Util.sneakyThrow(unwrapped);
            }
        }

        throw new ClojureException(ErrorMessages.cannotCallMessage(fnValue), this);
    }

    private static Throwable unwrapCloffleException(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof ClojureException ce && ce.getCause() != null) {
            current = ce.getCause();
        }
        return current != null ? current : throwable;
    }

    private ResolvedTruffleCall resolveTruffleCall(Object fnValue) {
        if (fnValue instanceof ClojureClosure closure) {
            return new ResolvedTruffleCall(closure.getCallTarget(), closure.getCapturedFrame());
        }
        if (fnValue instanceof TruffleIFn truffleIFn) {
            return new ResolvedTruffleCall(truffleIFn.getCallTarget(), null);
        }
        if (fnValue instanceof FnNode fnNode) {
            // Should not happen if FnNode returns closure, but keep parity with the old fallback.
            ClojureClosure closure = (ClojureClosure) fnNode.toIFn();
            return new ResolvedTruffleCall(closure.getCallTarget(), closure.getCapturedFrame());
        }
        return null;
    }

    private Object invokeTruffleTarget(CallTarget callTarget, Object closureFrame, Object[] args) {
        if (indirectCallNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            indirectCallNode = insert(IndirectCallNode.create());
        }

        for (int i = 0; i < args.length; i++) {
            args[i] = ClojureInterop.unwrapFromPolyglot(args[i]);
        }

        java.util.List<com.oracle.truffle.api.nodes.Node> tailCallSites = null;
        while (true) {
            Object[] callArgs = new Object[1 + args.length];
            callArgs[0] = closureFrame;
            System.arraycopy(args, 0, callArgs, 1, args.length);
            try {
                return indirectCallNode.call(callTarget, callArgs);
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
                    ce.addFrame(this);
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
