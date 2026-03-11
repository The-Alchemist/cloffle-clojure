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

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import net.javacrumbs.cloffle.nodes.binding.BindingNode;

public class FnMethodNode extends ClojureNode {

    @Children
    private final BindingNode[] params;

    @Child
    private ClojureNode body;

    private final int fixedArity;
    private final boolean variadic;

    public FnMethodNode(BindingNode[] params, ClojureNode body, int fixedArity, boolean variadic) {
        this.params = params;
        this.body = body;
        this.fixedArity = fixedArity;
        this.variadic = variadic;
    }

    public int getFixedArity() {
        return fixedArity;
    }

    public boolean isVariadic() {
        return variadic;
    }

    public boolean matches(int argCount) {
        if (variadic) {
            return argCount >= fixedArity;
        }
        return argCount == fixedArity;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        initializeParams(virtualFrame);
        while (true) {
            Object result = body.executeGeneric(virtualFrame);
            if (!(result instanceof RecurSentinel sentinel)) {
                return result;
            }
            Object[] values = sentinel.getValues();
            if (values.length != params.length) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeException("Arity mismatch in recur: expected " + params.length + " but got " + values.length);
            }
            rebindParams(virtualFrame, values);
        }
    }

    @ExplodeLoop
    private void initializeParams(VirtualFrame virtualFrame) {
        for (BindingNode binding : params) {
            binding.executeGeneric(virtualFrame);
        }
    }

    @ExplodeLoop
    private void rebindParams(VirtualFrame virtualFrame, Object[] values) {
        for (int i = 0; i < params.length; i++) {
            params[i].rebindValue(values[i], virtualFrame);
        }
    }
}
