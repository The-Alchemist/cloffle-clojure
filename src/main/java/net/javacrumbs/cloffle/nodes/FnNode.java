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
package net.javacrumbs.cloffle.nodes;

import clojure.lang.AFn;
import clojure.lang.IFn;
import clojure.lang.ISeq;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.Clojure;
import net.javacrumbs.cloffle.CloffleContext;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;
import net.javacrumbs.cloffle.ast.AstBuilder;

public class FnNode extends ClojureNode {

    @Children
    private final FnMethodNode[] fnMethodNodes;

    private AstBuilder astBuilder;

    public FnNode(FnMethodNode[] fnMethodNodes) {
        this.fnMethodNodes = fnMethodNodes;
    }

    public void setAstBuilder(AstBuilder astBuilder) {
        this.astBuilder = astBuilder;
    }

    public FrameDescriptor getFrameDescriptor() {
        return astBuilder != null ? astBuilder.getFrameDescriptor() : null;
    }

    public FnMethodNode[] getMethods() {
        return fnMethodNodes;
    }

    /**
     * When evaluated as a value (e.g., in a map, def init, argument position),
     * a fn form returns the function itself, not the result of calling it.
     */
    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        return this;
    }

    /**
     * Dispatch to the correct arity method and execute the function body.
     * Called by ClojureRootNode when the FnNode is explicitly invoked.
     */
    public Object invoke(VirtualFrame virtualFrame) {
        int argCount = virtualFrame.getArguments().length;
        for (FnMethodNode method : fnMethodNodes) {
            if (!method.isVariadic() && method.getFixedArity() == argCount) {
                return method.executeGeneric(virtualFrame);
            }
        }
        for (FnMethodNode method : fnMethodNodes) {
            if (method.isVariadic() && argCount >= method.getFixedArity()) {
                return method.executeGeneric(virtualFrame);
            }
        }
        StringBuilder sb = new StringBuilder("Wrong number of args (")
                .append(argCount).append(") passed to fn. Available arities: ");
        for (int i = 0; i < fnMethodNodes.length; i++) {
            if (i > 0) sb.append(", ");
            FnMethodNode m = fnMethodNodes[i];
            sb.append(m.getFixedArity());
            if (m.isVariadic()) sb.append("+");
        }
        throw new clojure.lang.ArityException(argCount, sb.toString());
    }

    /**
     * Creates an IFn adapter that delegates to this FnNode's invoke logic.
     * Used when a Cloffle function needs to cross into native Clojure code.
     */
    public IFn toIFn() {
        FnNode self = this;
        CloffleContext ctx = Clojure.getContext();
        FrameDescriptor fd = getFrameDescriptor();
        if (fd == null) {
            for (var entry : ctx.getAllDefs()) {
                if (entry.getValue().node() == self) {
                    fd = entry.getValue().frameDescriptor();
                    break;
                }
            }
        }
        final FrameDescriptor finalFd = fd != null ? fd : new FrameDescriptor();
        ClojureRootNode rootNode = ClojureRootNode.createRaw(self, finalFd, ctx.language());
        if (astBuilder != null && astBuilder.getSource() != null) {
            Source src = astBuilder.getSource();
            rootNode.setSourceSection(src.createSection(0, src.getLength()));
        }
        final CallTarget cachedTarget = rootNode.getCallTarget();
        return new AFn() {
            @Override
            public Object invoke() {
                return ClojureInterop.unwrapFromPolyglot(cachedTarget.call());
            }
            @Override
            public Object invoke(Object arg1) {
                return ClojureInterop.unwrapFromPolyglot(cachedTarget.call(arg1));
            }
            @Override
            public Object invoke(Object arg1, Object arg2) {
                return ClojureInterop.unwrapFromPolyglot(cachedTarget.call(arg1, arg2));
            }
            @Override
            public Object invoke(Object arg1, Object arg2, Object arg3) {
                return ClojureInterop.unwrapFromPolyglot(cachedTarget.call(arg1, arg2, arg3));
            }
            @Override
            public Object applyTo(ISeq arglist) {
                return ClojureInterop.unwrapFromPolyglot(cachedTarget.call(clojure.lang.RT.seqToArray(arglist)));
            }
        };
    }
}
