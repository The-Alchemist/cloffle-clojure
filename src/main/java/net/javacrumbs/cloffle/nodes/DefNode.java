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

import clojure.lang.IFn;
import clojure.lang.Var;
import com.oracle.truffle.api.frame.VirtualFrame;

public class DefNode extends ClojureNode {
    private final int slotIndex;
    private final Var var;

    @Child
    private ClojureNode init;

    public DefNode(int slotIndex, ClojureNode init, Var var) {
        this.slotIndex = slotIndex;
        this.init = init;
        this.var = var;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object value = init.executeGeneric(virtualFrame);
        if (value instanceof FnNode fnNode) {
            IFn ifn = fnNode.toIFn();
            var.bindRoot(ifn);
            virtualFrame.setObject(slotIndex, ifn);
        } else {
            var.bindRoot(value);
            virtualFrame.setObject(slotIndex, value);
        }
        return var;
    }
}
