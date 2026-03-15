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
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

public class GenericStaticCallNode extends ClojureNode {
    private final Class<?> clazz;
    private final String methodName;
    private final java.lang.reflect.Method resolvedMethod;

    @Children
    private final ClojureNode[] args;

    public GenericStaticCallNode(Class<?> clazz, String methodName, ClojureNode[] args) {
        this(clazz, methodName, args, null);
    }

    public GenericStaticCallNode(Class<?> clazz, String methodName, ClojureNode[] args,
                                 java.lang.reflect.Method resolvedMethod) {
        this.clazz = clazz;
        this.methodName = methodName;
        this.args = args;
        this.resolvedMethod = resolvedMethod;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object[] argValues = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            argValues[i] = ClojureInterop.unwrapFromPolyglot(args[i].executeGeneric(virtualFrame));
        }
        try {
            if (resolvedMethod != null) {
                Object[] boxed;
                try {
                    boxed = Reflector.boxArgs(resolvedMethod.getParameterTypes(), argValues);
                } catch (IllegalArgumentException e) {
                    // JVM bytecode would throw ClassCastException via checkcast
                    throw new ClassCastException(e.getMessage());
                }
                try {
                    Object result = resolvedMethod.invoke(null, boxed);
                    return ClojureInterop.wrapForPolyglot(
                            Reflector.prepRet(resolvedMethod.getReturnType(), result));
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    throw ite.getCause() != null ? ite.getCause() : ite;
                }
            }
            return ClojureInterop.wrapForPolyglot(Reflector.invokeStaticMethod(clazz, methodName, argValues));
        } catch (AbstractTruffleException e) {
            throw e;
        } catch (Throwable t) {
            CompilerDirectives.transferToInterpreter();
            throw ClojureException.wrap(t, this);
        }
    }
}
