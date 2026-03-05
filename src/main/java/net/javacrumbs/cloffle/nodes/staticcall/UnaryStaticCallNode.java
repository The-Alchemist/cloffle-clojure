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

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.dsl.Specialization;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * We want to optimize call with two args. Do not know how to simply optimize the generic case.
 */
@NodeChildren({@NodeChild(value = "arg", type = ClojureNode.class)})
public abstract class UnaryStaticCallNode extends AbstractStaticCallNode {
    public UnaryStaticCallNode(Class<?> clazz, String methodName) {
        super(clazz, methodName);
    }

    @Specialization(rewriteOn = NoSuchMethodException.class)
    protected long execute(long arg) throws NoSuchMethodException {
        MethodHandle methodHandle = getMethodHandle(long.class);
        try {
            return (long) methodHandle.invokeExact(arg);
        } catch (Throwable e) {
            // FIXME
            throw new IllegalStateException(e);
        }
    }

    @Specialization(rewriteOn = NoSuchMethodException.class)
    protected double execute(double arg) throws NoSuchMethodException {
        MethodHandle methodHandle = getMethodHandle(double.class);
        try {
            return (double) methodHandle.invokeExact(arg);
        } catch (Throwable e) {
            // FIXME
            throw new IllegalStateException(e);
        }
    }

    @Specialization
    protected Object execute(Object arg) {
        Object unwrapped = ClojureInterop.unwrap(arg);
        MethodHandle methodHandle = resolveObjectMethod(unwrapped);
        try {
            return methodHandle.invoke(unwrapped);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    private MethodHandle resolveObjectMethod(Object actualArg) {
        try {
            return getMethodHandle(Object.class, Object.class);
        } catch (NoSuchMethodException e1) {
            try {
                return getMethodHandle(long.class, Object.class);
            } catch (NoSuchMethodException e2) {
                try {
                    return getMethodHandle(double.class, Object.class);
                } catch (NoSuchMethodException e3) {
                    try {
                        return getMethodHandle(boolean.class, Object.class);
                    } catch (NoSuchMethodException e4) {
                        return resolveByReflection(actualArg);
                    }
                }
            }
        }
    }

    private MethodHandle resolveByReflection(Object actualArg) {
        for (Method m : getClazz().getMethods()) {
            if (!m.getName().equals(getMethodName())) continue;
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() != 1) continue;
            Class<?> paramType = m.getParameterTypes()[0];
            if (actualArg != null && paramType.isAssignableFrom(actualArg.getClass())) {
                try {
                    return MethodHandles.publicLookup().unreflect(m);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        String argType = actualArg != null ? actualArg.getClass().getSimpleName() : "null";
        throw new IllegalStateException("No matching method: "
                + getClazz().getName() + "." + getMethodName() + "(" + argType + ")");
    }

    private MethodHandle getMethodHandle(Class<?> type) throws NoSuchMethodException {
        return getMethodHandle(type, type);
    }

}
