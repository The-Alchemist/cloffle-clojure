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
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.ClojureRootNode;
import net.javacrumbs.cloffle.nodes.FnNode;
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
        this.fnIsStatic = (fn instanceof VarNode) || (fn instanceof FnNode);
    }

    public InvokeNode(ClojureNode fn, Supplier<FrameDescriptor> frameDescriptorSupplier, Source source,
                      Object language, ClojureNode[] args) {
        this.fn = fn;
        this.frameDescriptorSupplier = frameDescriptorSupplier;
        this.source = source;
        this.language = (com.oracle.truffle.api.TruffleLanguage<?>) language;
        this.args = args;
        this.fnIsStatic = (fn instanceof VarNode) || (fn instanceof FnNode);
    }

    private FrameDescriptor resolveFrameDescriptor() {
        if (frameDescriptor == null && frameDescriptorSupplier != null) {
            frameDescriptor = frameDescriptorSupplier.get();
        }
        return frameDescriptor;
    }

    private DirectCallNode getDirectCallNode(VirtualFrame frame) {
        if (directCallNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            CallTarget target;
            FrameDescriptor fd = resolveFrameDescriptor();

            if (fn instanceof VarNode varNode) {
                Object val = varNode.getVar().deref();
                if (val instanceof TruffleIFn truffleIFn) {
                    target = truffleIFn.getCallTarget();
                } else if (val instanceof FnNode fnNode) {
                    FrameDescriptor fnFd = fnNode.getFrameDescriptor();
                    if (fnFd == null) fnFd = fd;
                    target = createRootWithSource(fnNode, fnFd).getCallTarget();
                } else {
                    NativeCallNode ncn = new NativeCallNode((IFn) val);
                    target = createRootWithSource(ncn, fd).getCallTarget();
                }
            } else if (fn instanceof FnNode fnNode) {
                FrameDescriptor fnFd = fnNode.getFrameDescriptor();
                if (fnFd == null) fnFd = fd;
                target = createRootWithSource(fnNode, fnFd).getCallTarget();
            } else {
                target = createRootWithSource(fn, fd).getCallTarget();
            }

            directCallNode = insert(DirectCallNode.create(target));
        }
        return directCallNode;
    }

    private ClojureRootNode createRootWithSource(ClojureNode body, FrameDescriptor fd) {
        ClojureRootNode rootNode = ClojureRootNode.createRaw(body, fd, language);
        if (source != null) {
            rootNode.setSourceSection(source.createSection(0, source.getLength()));
        }
        return rootNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object[] resolvedArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            resolvedArgs[i] = args[i].executeGeneric(virtualFrame);
        }

        if (fnIsStatic) {
            return getDirectCallNode(virtualFrame).call(resolvedArgs);
        }

        Object fnValue = fn.executeGeneric(virtualFrame);
        CallTarget callTarget = null;

        if (fnValue instanceof TruffleIFn truffleIFn) {
            callTarget = truffleIFn.getCallTarget();
        } else if (fnValue instanceof FnNode fnNode) {
            callTarget = fnNode.toIFn() instanceof TruffleIFn t ? t.getCallTarget() : null;
        }

        if (callTarget != null) {
            if (indirectCallNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                indirectCallNode = insert(IndirectCallNode.create());
            }
            return indirectCallNode.call(callTarget, resolvedArgs);
        }

        if (fnValue instanceof IFn ifn) {
            return invokeIFnDirect(ifn, resolvedArgs);
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
            default -> ifn.applyTo(clojure.lang.RT.seq(args));
        };
    }
}
