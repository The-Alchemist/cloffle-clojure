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
package net.javacrumbs.cloffle.nodes.staticcall;

import clojure.lang.Reflector;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.ClojureException;
import net.javacrumbs.cloffle.nodes.ClojureNode;

public class GenericStaticCallNode extends ClojureNode {
    private final Class<?> clazz;
    private final String methodName;

    @Children
    private final ClojureNode[] args;

    public GenericStaticCallNode(Class<?> clazz, String methodName, ClojureNode[] args) {
        this.clazz = clazz;
        this.methodName = methodName;
        this.args = args;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object[] argValues = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            argValues[i] = args[i].executeGeneric(virtualFrame);
        }
        try {
            return Reflector.invokeStaticMethod(clazz, methodName, argValues);
        } catch (AbstractTruffleException e) {
            throw e;
        } catch (Throwable t) {
            CompilerDirectives.transferToInterpreter();
            throw ClojureException.wrap(t, this);
        }
    }
}
