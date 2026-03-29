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

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import net.javacrumbs.cloffle.nodes.binding.BindingNode;

public class LetNode extends ClojureNode {

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        return tag == StandardTags.StatementTag.class
            || tag == StandardTags.ExpressionTag.class;
    }

    @Children
    private final BindingNode[] bindings;

    @Child
    private ClojureNode body;

    public LetNode(BindingNode[] bindings, ClojureNode body) {
        this.bindings = bindings;
        this.body = body;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        executeBindings(virtualFrame);
        return body.executeGeneric(virtualFrame);
    }

    @Override
    public long executeLong(VirtualFrame virtualFrame) throws UnexpectedResultException {
        executeBindings(virtualFrame);
        return body.executeLong(virtualFrame);
    }

    @Override
    public double executeDouble(VirtualFrame virtualFrame) throws UnexpectedResultException {
        executeBindings(virtualFrame);
        return body.executeDouble(virtualFrame);
    }

    @Override
    public boolean executeBoolean(VirtualFrame virtualFrame) throws UnexpectedResultException {
        executeBindings(virtualFrame);
        return body.executeBoolean(virtualFrame);
    }

    private void executeBindings(VirtualFrame virtualFrame) {
        for (BindingNode binding : bindings) {
            binding.executeGeneric(virtualFrame);
        }
    }
}
