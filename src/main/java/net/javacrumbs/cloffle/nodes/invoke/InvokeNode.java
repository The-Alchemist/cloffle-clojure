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

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import net.javacrumbs.cloffle.Clojure;
import net.javacrumbs.cloffle.CloffleContext;
import net.javacrumbs.cloffle.ast.AstBuilder;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.ClojureRootNode;
import net.javacrumbs.cloffle.nodes.FnNode;
import net.javacrumbs.cloffle.nodes.NativeCallNode;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;
import net.javacrumbs.cloffle.nodes.vars.VarNode;

public class InvokeNode extends ClojureNode {
    @Child
    private ClojureNode fn;
    private final AstBuilder astBuilder;
    private final com.oracle.truffle.api.TruffleLanguage<?> language;
    private final boolean fnIsStatic;

    @Children
    private final ClojureNode[] args;

    @Child
    private DirectCallNode directCallNode;

    @Child
    private IndirectCallNode indirectCallNode;

    public InvokeNode(ClojureNode fn, AstBuilder astBuilder, Object language, ClojureNode[] args) {
        this.fn = fn;
        this.astBuilder = astBuilder;
        this.language = (com.oracle.truffle.api.TruffleLanguage<?>) language;
        this.args = args;
        this.fnIsStatic = (fn instanceof VarNode) || (fn instanceof FnNode);
    }

    private DirectCallNode getDirectCallNode(VirtualFrame frame) {
        if (directCallNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            ClojureNode resolvedFn;
            FrameDescriptor fd;
            if (fn instanceof VarNode varNode) {
                fd = varNode.getVarFrameDescriptor(frame);
                resolvedFn = varNode.getVarValue(frame);
            } else {
                resolvedFn = fn;
                fd = null;
            }
            if (fd == null) {
                fd = astBuilder.getFrameDescriptor();
            }
            CallTarget target = ClojureRootNode.create(resolvedFn, fd, language).getCallTarget();
            directCallNode = insert(DirectCallNode.create(target));
        }
        return directCallNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object[] resolvedArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            resolvedArgs[i] = args[i].executeGeneric(virtualFrame);
        }

        if (fnIsStatic) {
            return ClojureInterop.unwrapFromPolyglot(getDirectCallNode(virtualFrame).call(resolvedArgs));
        }

        Object fnValue = fn.executeGeneric(virtualFrame);
        ClojureNode fnNode;
        FrameDescriptor fd;

        if (fnValue instanceof ClojureNode node) {
            fnNode = node;
            fd = findFrameDescriptor(node);
        } else if (fnValue instanceof clojure.lang.IFn ifn) {
            fnNode = new NativeCallNode(ifn);
            fd = astBuilder.getFrameDescriptor();
        } else {
            throw new RuntimeException("Cannot invoke non-function value: " + fnValue
                    + " (" + (fnValue != null ? fnValue.getClass().getName() : "null") + ")");
        }

        if (indirectCallNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            indirectCallNode = insert(IndirectCallNode.create());
        }
        CallTarget target = ClojureRootNode.create(fnNode, fd, language).getCallTarget();
        return ClojureInterop.unwrapFromPolyglot(indirectCallNode.call(target, resolvedArgs));
    }

    private FrameDescriptor findFrameDescriptor(ClojureNode node) {
        if (node instanceof FnNode fnNode && fnNode.getFrameDescriptor() != null) {
            return fnNode.getFrameDescriptor();
        }
        CloffleContext ctx = Clojure.getContext();
        for (var entry : ctx.getAllDefs()) {
            if (entry.getValue().node() == node) {
                return entry.getValue().frameDescriptor();
            }
        }
        return astBuilder.getFrameDescriptor();
    }
}
