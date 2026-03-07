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

import clojure.lang.Reflector;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

public class InstanceCallNode extends ClojureNode {
    @Child
    private ClojureNode instanceNode;

    private final String methodName;
    @Children
    private final ClojureNode[] args;

    public InstanceCallNode(ClojureNode instanceNode, String methodName, ClojureNode... args) {
        this.instanceNode = instanceNode;
        this.methodName = methodName;
        this.args = args;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object instance = ClojureInterop.unwrapFromPolyglot(instanceNode.executeGeneric(virtualFrame));
        Object[] argValues = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            argValues[i] = ClojureInterop.unwrapFromPolyglot(args[i].executeGeneric(virtualFrame));
        }
        try {
            return Reflector.invokeInstanceMethod(instance, methodName, argValues);
        } catch (Exception e) {
            throw ClojureException.wrap(e, this);
        }
    }

}
