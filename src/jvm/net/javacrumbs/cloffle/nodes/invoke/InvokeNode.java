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
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.ClojureRootNode;
import net.javacrumbs.cloffle.nodes.FnNode;
import net.javacrumbs.cloffle.nodes.FnDispatchNode;
import net.javacrumbs.cloffle.nodes.NativeCallNode;
import net.javacrumbs.cloffle.nodes.TruffleIFn;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;
import net.javacrumbs.cloffle.nodes.vars.VarNode;

import java.util.function.Supplier;

public class InvokeNode extends ClojureNode {
    @Child
    private ClojureNode fn;
    private FrameDescriptor frameDescriptor;
    private final Supplier<FrameDescriptor> frameDescriptorSupplier;
    private final Source source;
    private final com.oracle.truffle.api.TruffleLanguage<?> language;
    private final boolean fnIsStatic;

    @Children
    private final ClojureNode[] args;

    @Child
    private DirectCallNode directCallNode;

    @Child
    private IndirectCallNode indirectCallNode;

    public InvokeNode(ClojureNode fn, FrameDescriptor frameDescriptor, Source source,
                      Object language, ClojureNode[] args) {
        this.fn = fn;
        this.frameDescriptor = frameDescriptor;
        this.frameDescriptorSupplier = null;
        this.source = source;
        this.language = (com.oracle.truffle.api.TruffleLanguage<?>) language;
        this.args = args;
        // Only FnNode is static; VarNode is not, so we deref the var on every call and see redefinitions.
        this.fnIsStatic = (fn instanceof FnNode);
    }

    public InvokeNode(ClojureNode fn, Supplier<FrameDescriptor> frameDescriptorSupplier, Source source,
                      Object language, ClojureNode[] args) {
        this.fn = fn;
        this.frameDescriptorSupplier = frameDescriptorSupplier;
        this.source = source;
        this.language = (com.oracle.truffle.api.TruffleLanguage<?>) language;
        this.args = args;
        // Only FnNode is static; VarNode is not, so we deref the var on every call and see redefinitions.
        this.fnIsStatic = (fn instanceof FnNode);
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

        if (fn instanceof VarNode varNode) {
            Object val = varNode.getVar().deref();
            // System.out.println("DEBUG: InvokeNode resolving var " + varNode.getVar() + " -> " + val + " (" + (val == null ? "null" : val.getClass().getName()) + ")");
            if (val instanceof TruffleIFn truffleIFn) {
                target = truffleIFn.getCallTarget();
            } else if (val instanceof FnNode fnNode) {
                FrameDescriptor fnFd = fnNode.getFrameDescriptor();
                if (fnFd == null) fnFd = fd;
                target = createRootWithSource(new FnDispatchNode(fnNode), fnFd).getCallTarget();
            } else {
                NativeCallNode ncn = new NativeCallNode((IFn) val);
                target = createRootWithSource(ncn, fd).getCallTarget();
            }
        } else if (fn instanceof FnNode fnNode) {
            FrameDescriptor fnFd = fnNode.getFrameDescriptor();
            if (fnFd == null) fnFd = fd;
            target = createRootWithSource(new FnDispatchNode(fnNode), fnFd).getCallTarget();
        } else {
            target = createRootWithSource(fn, fd).getCallTarget();
        }

        this.directCallNode = insert(DirectCallNode.create(target));
    }

    private ClojureRootNode createRootWithSource(ClojureNode body, FrameDescriptor fd) {
        ClojureRootNode rootNode = ClojureRootNode.createRaw(body, fd, language);
        if (source != null) {
            // Use call site (invoke expression) source when available for precise stack traces
            SourceSection callSiteSection = getSourceSection();
            if (callSiteSection != null) {
                rootNode.setSourceSection(callSiteSection);
            } else {
                rootNode.setSourceSection(source.createSection(0, source.getLength()));
            }
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
            // Static path (literal fn): use current frame as closure frame
            // We need to inject the current frame as arg 0
            Object[] callArgs = new Object[1 + resolvedArgs.length];
            callArgs[0] = ClojureRootNode.snapshotFrame(virtualFrame);
            System.arraycopy(resolvedArgs, 0, callArgs, 1, resolvedArgs.length);
            return directCallNode.call(callArgs);
        }

        Object fnValue = fn.executeGeneric(virtualFrame);
        return invokeGeneric(fnValue, resolvedArgs);
    }

    private Object invokeGeneric(Object fnValue, Object[] args) {
        CallTarget callTarget = null;
        Object closureFrame = null;

        if (fnValue instanceof ClojureClosure closure) {
            callTarget = closure.getCallTarget();
            closureFrame = closure.getCapturedFrame();
        } else if (fnValue instanceof TruffleIFn truffleIFn) {
            callTarget = truffleIFn.getCallTarget();
        } else if (fnValue instanceof FnNode fnNode) {
            // Should not happen if FnNode returns closure, but for safety
            ClojureClosure closure = (ClojureClosure) fnNode.toIFn();
            callTarget = closure.getCallTarget();
            closureFrame = closure.getCapturedFrame();
        }

        if (callTarget != null) {
            if (indirectCallNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                indirectCallNode = insert(IndirectCallNode.create());
            }
            // Pass closure frame (or null) as first arg
            Object[] callArgs = new Object[1 + args.length];
            callArgs[0] = closureFrame;
            System.arraycopy(args, 0, callArgs, 1, args.length);
            return indirectCallNode.call(callTarget, callArgs);
        }

        if (fnValue instanceof IFn ifn) {
            return invokeIFnDirect(ifn, args);
        }

        throw new RuntimeException("Cannot invoke non-function value: " + fnValue
                + " (" + (fnValue != null ? fnValue.getClass().getName() : "null") + ")");
    }

    @CompilerDirectives.TruffleBoundary
    private static Object invokeIFnDirect(IFn ifn, Object[] args) {
        for (int i = 0; i < args.length; i++) {
            args[i] = ClojureInterop.unwrapFromPolyglot(args[i]);
        }
        return switch (args.length) {
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
    }
}
