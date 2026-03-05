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
package net.javacrumbs.cloffle.nodes.vars;

import clojure.lang.IFn;
import clojure.lang.Var;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import net.javacrumbs.cloffle.Clojure;
import net.javacrumbs.cloffle.CloffleContext;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.FnNode;
import net.javacrumbs.cloffle.nodes.NativeCallNode;
import net.javacrumbs.cloffle.nodes.value.ObjectNode;

public class VarNode extends AbstractValueNode {

    private final Var var;

    public VarNode(int slotIndex, Var var) {
        super(slotIndex);
        this.var = var;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        ClojureNode node = getVarNode(virtualFrame);
        if (node instanceof FnNode) {
            return node;
        }
        if (node instanceof NativeCallNode ncn) {
            return ncn.getFn();
        }
        return node.executeGeneric(virtualFrame);
    }

    @Override
    public boolean executeBoolean(VirtualFrame virtualFrame) throws UnexpectedResultException {
        ClojureNode node = getVarNode(virtualFrame);
        return node.executeBoolean(virtualFrame);
    }

    @Override
    public long executeLong(VirtualFrame virtualFrame) throws UnexpectedResultException {
        ClojureNode node = getVarNode(virtualFrame);
        return node.executeLong(virtualFrame);
    }

    @Override
    public double executeDouble(VirtualFrame virtualFrame) throws UnexpectedResultException {
        ClojureNode node = getVarNode(virtualFrame);
        return node.executeDouble(virtualFrame);
    }

    private ClojureNode getVarNode(VirtualFrame virtualFrame) {
        Object value = getValueOrNull(virtualFrame);
        if (value instanceof ClojureNode node) {
            return node;
        }
        CloffleContext.DefEntry entry = Clojure.getContext().getDef(var);
        if (entry != null) {
            return entry.node();
        }
        if (var.isBound()) {
            Object bound = var.deref();
            if (bound instanceof IFn ifn) {
                return new NativeCallNode(ifn);
            }
            return new ObjectNode(bound);
        }
        throw new RuntimeException("Undefined var: " + var);
    }

    public ClojureNode getVarValue(VirtualFrame virtualFrame) {
        return getVarNode(virtualFrame);
    }

    /**
     * Returns the FrameDescriptor associated with this var's definition,
     * or null if the var was found in the local frame (same eval).
     */
    public FrameDescriptor getVarFrameDescriptor(VirtualFrame virtualFrame) {
        Object value = getValueOrNull(virtualFrame);
        if (value != null) {
            return null;
        }
        CloffleContext.DefEntry entry = Clojure.getContext().getDef(var);
        if (entry != null) {
            return entry.frameDescriptor();
        }
        return null;
    }
}
