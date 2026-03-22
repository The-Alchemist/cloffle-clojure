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
import clojure.lang.Util;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

public class InstanceCallNode extends ClojureNode {
    @Child
    private ClojureNode instanceNode;

    private final String methodName;
    private final java.lang.reflect.Method resolvedMethod;
    @Children
    private final ClojureNode[] args;

    public InstanceCallNode(ClojureNode instanceNode, String methodName, ClojureNode... args) {
        this(instanceNode, methodName, null, args);
    }

    public InstanceCallNode(ClojureNode instanceNode, String methodName,
                            java.lang.reflect.Method resolvedMethod, ClojureNode... args) {
        this.instanceNode = instanceNode;
        this.methodName = methodName;
        this.resolvedMethod = resolvedMethod;
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
            if (resolvedMethod != null) {
                java.lang.reflect.Method method = resolvedMethod;
                Class<?> declaringClass = method.getDeclaringClass();
                if (!declaringClass.isInstance(instance)) {
                    if (instance == null) {
                        throw new NullPointerException();
                    }
                    if (!isClassloaderSplitCandidate(declaringClass, instance.getClass())) {
                        throw new ClassCastException(instance.getClass().getName()
                                + " cannot be cast to " + declaringClass.getName());
                    }
                    // Classloader identity split: the compile-time class and the
                    // runtime class were loaded by different classloaders.
                    // Re-resolve by name/signature against the runtime class.
                    method = resolveMethodOnClass(instance.getClass(), method);
                    if (method == null) {
                        return ClojureInterop.wrapForPolyglot(
                                Reflector.invokeInstanceMethod(instance, methodName, argValues));
                    }
                }
                Object[] boxed;
                try {
                    boxed = Reflector.boxArgs(method.getParameterTypes(), argValues);
                } catch (IllegalArgumentException e) {
                    throw new ClassCastException(e.getMessage());
                }
                try {
                    Object result = method.invoke(instance, boxed);
                    return ClojureInterop.wrapForPolyglot(
                            Reflector.prepRet(method.getReturnType(), result));
                } catch (IllegalArgumentException e) {
                    throw new ClassCastException(e.getMessage());
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    throw ite.getCause() != null ? ite.getCause() : ite;
                }
            }
            return ClojureInterop.wrapForPolyglot(Reflector.invokeInstanceMethod(instance, methodName, argValues));
        } catch (AbstractTruffleException e) {
            throw e;
        } catch (Throwable t) {
            CompilerDirectives.transferToInterpreter();
            throw Util.sneakyThrow(unwrapCloffleException(t));
        }
    }

    private static Throwable unwrapCloffleException(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof ClojureException ce && ce.getCause() != null) {
            current = ce.getCause();
        }
        return current != null ? current : throwable;
    }

    private static boolean isClassloaderSplitCandidate(Class<?> declaringClass, Class<?> runtimeClass) {
        return hasTypeNamed(runtimeClass, declaringClass.getName());
    }

    private static boolean hasTypeNamed(Class<?> type, String expectedName) {
        if (type == null) {
            return false;
        }
        if (expectedName.equals(type.getName())) {
            return true;
        }
        for (Class<?> iface : type.getInterfaces()) {
            if (hasTypeNamed(iface, expectedName)) {
                return true;
            }
        }
        return hasTypeNamed(type.getSuperclass(), expectedName);
    }

    @CompilerDirectives.TruffleBoundary
    private static java.lang.reflect.Method resolveMethodOnClass(Class<?> targetClass, java.lang.reflect.Method method) {
        Class<?>[] expectedParams = method.getParameterTypes();
        for (java.lang.reflect.Method candidate : targetClass.getMethods()) {
            if (candidate.getName().equals(method.getName())
                    && java.util.Arrays.equals(candidate.getParameterTypes(), expectedParams)) {
                return candidate;
            }
        }
        return null;
    }

}
