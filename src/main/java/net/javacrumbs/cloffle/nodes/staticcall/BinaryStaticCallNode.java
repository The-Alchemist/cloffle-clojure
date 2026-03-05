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
@NodeChildren({@NodeChild(value = "first", type = ClojureNode.class), @NodeChild(value = "second", type = ClojureNode.class)})
public abstract class BinaryStaticCallNode extends AbstractStaticCallNode {
    public BinaryStaticCallNode(Class<?> clazz, String methodName) {
        super(clazz, methodName);
    }

    @Specialization(rewriteOn = NoSuchMethodException.class)
    protected long callLongLong(long first, long second) throws NoSuchMethodException {
        MethodHandle methodHandle = getMethodHandle(long.class);
        try {
            return (long) methodHandle.invokeExact(first, second);
        } catch (Throwable e) {
            // FIXME
            throw new IllegalStateException(e);
        }
    }

    @Specialization(rewriteOn = NoSuchMethodException.class)
    protected double callDoubleDouble(double first, double second) throws NoSuchMethodException {
        MethodHandle methodHandle = getMethodHandle(double.class);
        try {
            return (double) methodHandle.invokeExact(first, second);
        } catch (Throwable e) {
            // FIXME
            throw new IllegalStateException(e);
        }
    }

    @Specialization(rewriteOn = NoSuchMethodException.class)
    protected boolean callObjectBoolean(Object first, Object second) throws NoSuchMethodException {
        MethodHandle methodHandle = getMethodHandle(boolean.class, Object.class, Object.class);
        try {
            return (boolean) methodHandle.invokeExact(first, second);
        } catch (Throwable e) {
            // FIXME
            throw new IllegalStateException(e);
        }
    }

    @Specialization
    protected Object callObjectObject(Object first, Object second) {
        Object unwrappedFirst = ClojureInterop.unwrap(first);
        Object unwrappedSecond = ClojureInterop.unwrap(second);
        MethodHandle methodHandle = resolveObjectMethod(unwrappedFirst, unwrappedSecond);
        try {
            return methodHandle.invoke(unwrappedFirst, unwrappedSecond);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    private MethodHandle resolveObjectMethod(Object first, Object second) {
        try {
            return getMethodHandle(Object.class);
        } catch (NoSuchMethodException e1) {
            try {
                return getMethodHandle(long.class, long.class, long.class);
            } catch (NoSuchMethodException e2) {
                return resolveByReflection(first, second);
            }
        }
    }

    private MethodHandle resolveByReflection(Object first, Object second) {
        for (Method m : getClazz().getMethods()) {
            if (!m.getName().equals(getMethodName())) continue;
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() != 2) continue;
            Class<?>[] paramTypes = m.getParameterTypes();
            boolean match = canAssign(paramTypes[0], first) && canAssign(paramTypes[1], second);
            if (match) {
                try {
                    return MethodHandles.publicLookup().unreflect(m);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        String t1 = first != null ? first.getClass().getSimpleName() : "null";
        String t2 = second != null ? second.getClass().getSimpleName() : "null";
        throw new IllegalStateException("No matching method: "
                + getClazz().getName() + "." + getMethodName() + "(" + t1 + ", " + t2 + ")");
    }

    private static boolean canAssign(Class<?> param, Object value) {
        if (value == null) return !param.isPrimitive();
        if (param.isAssignableFrom(value.getClass())) return true;
        if (param == long.class && value instanceof Number) return true;
        if (param == double.class && value instanceof Number) return true;
        if (param == int.class && value instanceof Number) return true;
        return false;
    }

    private MethodHandle getMethodHandle(Class<?> type) throws NoSuchMethodException {
        return getMethodHandle(type, type, type);
    }

}
