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

import clojure.lang.IFn;
import com.oracle.truffle.api.frame.VirtualFrame;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

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
        Object instance = instanceNode.executeGeneric(virtualFrame);
        Object[] argValues = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            argValues[i] = args[i].executeGeneric(virtualFrame);
        }
        Method method = resolveMethod(instance.getClass(), argValues);
        try {
            method.setAccessible(true);
            return method.invoke(instance, convertArgs(method, argValues));
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
    }

    private Method resolveMethod(Class<?> clazz, Object[] argValues) {
        Method found = findMethod(clazz, argValues);
        if (found != null) return found;
        for (Class<?> iface : clazz.getInterfaces()) {
            found = findMethod(iface, argValues);
            if (found != null) return found;
        }
        Class<?> sup = clazz.getSuperclass();
        while (sup != null) {
            found = findMethod(sup, argValues);
            if (found != null) return found;
            sup = sup.getSuperclass();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("No matching method: ").append(clazz.getName()).append(".").append(methodName)
                .append(" with ").append(argValues.length).append(" args [");
        for (int i = 0; i < argValues.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(argValues[i] == null ? "null" : argValues[i].getClass().getName());
        }
        sb.append("]");
        throw new IllegalStateException(sb.toString());
    }

    private Method findMethod(Class<?> clazz, Object[] argValues) {
        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals(methodName)) continue;
            Class<?>[] paramTypes = m.getParameterTypes();
            if (paramTypes.length != argValues.length) continue;
            if (paramsMatch(paramTypes, argValues)) return m;
        }
        return null;
    }

    private static boolean paramsMatch(Class<?>[] paramTypes, Object[] argValues) {
        for (int i = 0; i < paramTypes.length; i++) {
            if (argValues[i] == null) {
                if (paramTypes[i].isPrimitive()) return false;
                continue;
            }
            if (!isAssignable(paramTypes[i], argValues[i].getClass())) return false;
        }
        return true;
    }

    private static boolean isAssignable(Class<?> param, Class<?> arg) {
        if (param.isAssignableFrom(arg)) return true;
        if (param == int.class && (arg == Integer.class || arg == Long.class || arg == Short.class || arg == Byte.class)) return true;
        if (param == long.class && (arg == Long.class || arg == Integer.class || arg == Short.class || arg == Byte.class)) return true;
        if (param == double.class && (arg == Double.class || arg == Float.class || arg == Long.class || arg == Integer.class)) return true;
        if (param == float.class && (arg == Float.class || arg == Integer.class || arg == Long.class)) return true;
        if (param == boolean.class && arg == Boolean.class) return true;
        if (param == char.class && arg == Character.class) return true;
        if (param == short.class && (arg == Short.class || arg == Byte.class)) return true;
        if (param == byte.class && arg == Byte.class) return true;
        if (IFn.class.isAssignableFrom(param) && FnNode.class.isAssignableFrom(arg)) return true;
        return false;
    }

    private static Object[] convertArgs(Method method, Object[] argValues) {
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] converted = new Object[argValues.length];
        for (int i = 0; i < argValues.length; i++) {
            converted[i] = convertArg(paramTypes[i], argValues[i]);
        }
        return converted;
    }

    private static Object convertArg(Class<?> paramType, Object value) {
        if (value == null || paramType.isInstance(value)) return value;
        if (value instanceof Number num) {
            if (paramType == int.class || paramType == Integer.class) return num.intValue();
            if (paramType == long.class || paramType == Long.class) return num.longValue();
            if (paramType == double.class || paramType == Double.class) return num.doubleValue();
            if (paramType == float.class || paramType == Float.class) return num.floatValue();
            if (paramType == short.class || paramType == Short.class) return num.shortValue();
            if (paramType == byte.class || paramType == Byte.class) return num.byteValue();
        }
        if (IFn.class.isAssignableFrom(paramType) && value instanceof FnNode fnNode) {
            return fnNode.toIFn();
        }
        return value;
    }
}
