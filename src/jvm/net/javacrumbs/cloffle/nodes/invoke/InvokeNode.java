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
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.nodes.ClojureClosure;
import net.javacrumbs.cloffle.nodes.ClojureInvoke;
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

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        return tag == StandardTags.CallTag.class
            || tag == StandardTags.ExpressionTag.class
            || tag == StandardTags.StatementTag.class;
    }
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

    public InvokeNode(ClojureNode fn, FrameDescriptor frameDescriptor, Source source,
                      Object language, ClojureNode[] args) {
        this.fn = fn;
        this.frameDescriptor = frameDescriptor;
        this.frameDescriptorSupplier = null;
        this.source = source;
        this.language = (com.oracle.truffle.api.TruffleLanguage<?>) language;
        this.args = args;
        this.fnIsStatic = isStaticFn(fn);
    }

    public InvokeNode(ClojureNode fn, Supplier<FrameDescriptor> frameDescriptorSupplier, Source source,
                      Object language, ClojureNode[] args) {
        this.fn = fn;
        this.frameDescriptorSupplier = frameDescriptorSupplier;
        this.source = source;
        this.language = (com.oracle.truffle.api.TruffleLanguage<?>) language;
        this.args = args;
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
                FnDispatchNode dispatch = new FnDispatchNode(fnNode);
                copySourceSection(dispatch);
                target = createRootWithSource(dispatch, fnFd, callName).getCallTarget();
            } else {
                NativeCallNode ncn = new NativeCallNode((IFn) val);
                copySourceSection(ncn);
                target = createRootWithSource(ncn, fd, callName).getCallTarget();
            }
        } else if (fn instanceof FnNode fnNode) {
            callName = fnNode.getFnName();
            FrameDescriptor fnFd = fnNode.getFrameDescriptor();
            if (fnFd == null) fnFd = fd;
            FnDispatchNode dispatch = new FnDispatchNode(fnNode);
            copySourceSection(dispatch);
            target = createRootWithSource(dispatch, fnFd, callName).getCallTarget();
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
            return directCallNode.call(callArgs);
        }

        Object fnValue = fn.executeGeneric(virtualFrame);
        return ClojureInvoke.invoke(fnValue, resolvedArgs, this);
    }
}
