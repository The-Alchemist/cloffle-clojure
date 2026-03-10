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

import clojure.lang.Var;
import com.oracle.truffle.api.frame.VirtualFrame;

public class DefNode extends ClojureNode {
    private final Var var;
    private final boolean initProvided;

    @Child
    private ClojureNode init;

    public DefNode(ClojureNode init, Var var, boolean initProvided) {
        this.init = init;
        this.var = var;
        this.initProvided = initProvided;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        if (initProvided) {
            Object value = init.executeGeneric(virtualFrame);
            var.bindRoot(value);
        }
        return var;
    }
}
