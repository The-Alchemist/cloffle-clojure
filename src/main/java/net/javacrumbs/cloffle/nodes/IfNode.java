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

import clojure.lang.RT;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import net.javacrumbs.cloffle.nodes.value.NilNode;

public class IfNode extends ClojureNode {
    @Child
    private ClojureNode condition;
    @Child
    private ClojureNode thenNode;
    @Child
    private ClojureNode elseNode;

    public IfNode(ClojureNode condition, ClojureNode thenNode, ClojureNode elseNode) {
        this.condition = condition;
        this.thenNode = thenNode;
        this.elseNode = elseNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        if (isTruthy(virtualFrame)) {
            return thenNode.executeGeneric(virtualFrame);
        } else {
            return elseNode.executeGeneric(virtualFrame);
        }
    }

    @Override
    public long executeLong(VirtualFrame virtualFrame) throws UnexpectedResultException {
        if (isTruthy(virtualFrame)) {
            return thenNode.executeLong(virtualFrame);
        } else {
            return elseNode.executeLong(virtualFrame);
        }
    }

    @Override
    public double executeDouble(VirtualFrame virtualFrame) throws UnexpectedResultException {
        if (isTruthy(virtualFrame)) {
            return thenNode.executeDouble(virtualFrame);
        } else {
            return elseNode.executeDouble(virtualFrame);
        }
    }

    @Override
    public boolean executeBoolean(VirtualFrame virtualFrame) throws UnexpectedResultException {
        if (isTruthy(virtualFrame)) {
            return thenNode.executeBoolean(virtualFrame);
        } else {
            return elseNode.executeBoolean(virtualFrame);
        }
    }

    private boolean isTruthy(VirtualFrame virtualFrame) {
        try {
            return condition.executeBoolean(virtualFrame);
        } catch (UnexpectedResultException e) {
            Object value = e.getResult();
            if (value instanceof NilNode.Nil) return false;
            return RT.booleanCast(value);
        }
    }
}
