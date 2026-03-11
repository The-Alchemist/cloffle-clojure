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

import clojure.lang.ArraySeq;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import net.javacrumbs.cloffle.nodes.binding.BindingNode;
import net.javacrumbs.cloffle.nodes.value.NilNode;

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
            if (result instanceof RecurSentinel sentinel) {
                Object[] values = sentinel.getValues();
                if (values.length != params.length) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    throw new RuntimeException("Arity mismatch in recur: expected " + params.length + " but got " + values.length);
                }
                rebindParams(virtualFrame, values);
                continue;
            }
            if (result instanceof SelfTailCallSentinel sentinel) {
                rebindFromTailCall(virtualFrame, sentinel.getArgs());
                continue;
            }
            return result;
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

    @ExplodeLoop
    private void rebindFromTailCall(VirtualFrame virtualFrame, Object[] args) {
        if (!matches(args.length)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Arity mismatch in tail self call: expected " + fixedArity
                    + (variadic ? "+" : "") + " but got " + args.length);
        }

        if (!variadic) {
            for (int i = 0; i < fixedArity; i++) {
                params[i].rebindValue(args[i], virtualFrame);
            }
            return;
        }

        for (int i = 0; i < fixedArity; i++) {
            params[i].rebindValue(args[i], virtualFrame);
        }

        int restCount = args.length - fixedArity;
        if (restCount <= 0) {
            params[fixedArity].rebindValue(NilNode.NIL, virtualFrame);
            return;
        }
        Object[] restArray = new Object[restCount];
        System.arraycopy(args, fixedArity, restArray, 0, restCount);
        params[fixedArity].rebindValue(ArraySeq.create(restArray), virtualFrame);
    }
}
