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
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

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
        if (var.isBound()) {
            return ClojureInterop.wrapForPolyglot(var.deref());
        }
        // In Clojure, if a var is unbound, we typically throw UnboundException during evaluation
        // unless it's being used in a special way.
        // However, for compatibility with some tests, maybe we return the var itself if it's being returned?
        // No, standard evaluation throws.
        
        String symName = var.sym.getName();
        String msg = "Unable to resolve symbol: " + symName + " in this context";
        String suggestion = net.javacrumbs.cloffle.nodes.ErrorMessages.didYouMean(
                symName, var.ns);
        if (suggestion != null) {
            msg += ". Did you mean: " + suggestion + "?";
        }
        throw new net.javacrumbs.cloffle.nodes.ClojureException(msg, this);
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
