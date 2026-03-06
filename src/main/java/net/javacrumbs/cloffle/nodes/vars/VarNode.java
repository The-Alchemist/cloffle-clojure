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
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import net.javacrumbs.cloffle.nodes.ClojureNode;

public class VarNode extends AbstractValueNode {

    private final Var var;

    public VarNode(int slotIndex, Var var) {
        super(slotIndex);
        this.var = var;
    }

    public Var getVar() {
        return var;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object resolved = resolveVar(virtualFrame);
        if (resolved instanceof IFn) {
            return resolved;
        }
        if (resolved instanceof ClojureNode node) {
            return node.executeGeneric(virtualFrame);
        }
        return resolved;
    }

    @Override
    public boolean executeBoolean(VirtualFrame virtualFrame) throws UnexpectedResultException {
        Object resolved = resolveVar(virtualFrame);
        if (resolved instanceof Boolean b) {
            return b;
        }
        if (resolved instanceof ClojureNode node) {
            return node.executeBoolean(virtualFrame);
        }
        throw new UnexpectedResultException(resolved);
    }

    @Override
    public long executeLong(VirtualFrame virtualFrame) throws UnexpectedResultException {
        Object resolved = resolveVar(virtualFrame);
        if (resolved instanceof Long l) {
            return l;
        }
        if (resolved instanceof ClojureNode node) {
            return node.executeLong(virtualFrame);
        }
        throw new UnexpectedResultException(resolved);
    }

    @Override
    public double executeDouble(VirtualFrame virtualFrame) throws UnexpectedResultException {
        Object resolved = resolveVar(virtualFrame);
        if (resolved instanceof Double d) {
            return d;
        }
        if (resolved instanceof ClojureNode node) {
            return node.executeDouble(virtualFrame);
        }
        throw new UnexpectedResultException(resolved);
    }

    /**
     * Resolves the var's value. Checks the local frame first (for same-eval
     * bindings), then falls back to var.deref() which is the single source of truth.
     */
    private Object resolveVar(VirtualFrame virtualFrame) {
        Object local = getLocalValueOrNull(virtualFrame);
        if (local != null) {
            return local;
        }
        if (var.isBound()) {
            return var.deref();
        }
        throw new RuntimeException("Undefined var: " + var);
    }

    /**
     * Check only the current frame for a value at our slot index.
     * Does NOT walk the call stack -- that was only needed for the old
     * globalDefs approach. Var.deref() handles cross-eval resolution.
     */
    private Object getLocalValueOrNull(VirtualFrame virtualFrame) {
        try {
            return virtualFrame.getValue(getSlotIndex());
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }
}
