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

import clojure.lang.Var;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

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
        Object local = getLocalValueOrNull(virtualFrame);
        if (local != null) {
            return local;
        }

        if (var.isBound()) {
            return var.deref();
        }
        throw new RuntimeException("Undefined var: " + var);
    }

    @Override
    public boolean executeBoolean(VirtualFrame virtualFrame) throws UnexpectedResultException {
        Object res = executeGeneric(virtualFrame);
        if (res instanceof Boolean b) return b;
        throw new UnexpectedResultException(res);
    }

    @Override
    public long executeLong(VirtualFrame virtualFrame) throws UnexpectedResultException {
         Object res = executeGeneric(virtualFrame);
         if (res instanceof Long l) return l;
         throw new UnexpectedResultException(res);
    }

    @Override
    public double executeDouble(VirtualFrame virtualFrame) throws UnexpectedResultException {
         Object res = executeGeneric(virtualFrame);
         if (res instanceof Double d) return d;
         throw new UnexpectedResultException(res);
    }

    /**
     * Check only the current frame for a value at our slot index.
     * Var.deref() handles cross-eval resolution.
     */
    private Object getLocalValueOrNull(VirtualFrame virtualFrame) {
        try {
            return virtualFrame.getValue(getSlotIndex());
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }
}
