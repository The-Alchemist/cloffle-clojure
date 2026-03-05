package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;
import net.javacrumbs.cloffle.nodes.value.NilNode;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * Implements Clojure's (deftype ...) form.
 * Creates a factory function that produces Proxy instances implementing the given interfaces.
 * Fields are stored in a map accessible via the proxy's invocation handler.
 */
public class DefTypeNode extends ClojureNode {

    private final Class<?>[] interfaces;
    private final String[] fieldNames;
    private final int[] fieldSlots;
    private final ReifyNode.ReifyMethodDef[] methodDefs;
    private final com.oracle.truffle.api.TruffleLanguage<?> language;

    public DefTypeNode(Class<?>[] interfaces, String[] fieldNames, int[] fieldSlots,
                       ReifyNode.ReifyMethodDef[] methodDefs,
                       com.oracle.truffle.api.TruffleLanguage<?> language) {
        this.interfaces = interfaces;
        this.fieldNames = fieldNames;
        this.fieldSlots = fieldSlots;
        this.methodDefs = methodDefs;
        this.language = language;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        return NilNode.NIL;
    }

    @TruffleBoundary
    public Object createInstance(Object[] fieldValues) {
        Map<String, Object> fields = new HashMap<>();
        for (int i = 0; i < fieldNames.length; i++) {
            fields.put(fieldNames[i], i < fieldValues.length ? fieldValues[i] : null);
        }

        int maxSlot = 0;
        for (int s : fieldSlots) if (s > maxSlot) maxSlot = s;
        for (var def : methodDefs) {
            // maxSlot is tracked internally by ReifyMethodDef
        }

        InvocationHandler handler = (proxy, method, args) -> {
            if (args == null) args = new Object[0];

            for (ReifyNode.ReifyMethodDef def : methodDefs) {
                if (def.matches(method.getName(), args.length)) {
                    return def.invoke(proxy, args, language);
                }
            }

            if ("hashCode".equals(method.getName()) && args.length == 0) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(method.getName()) && args.length == 1) {
                return proxy == args[0];
            }
            if ("toString".equals(method.getName()) && args.length == 0) {
                return "deftype@" + Integer.toHexString(System.identityHashCode(proxy));
            }

            throw new UnsupportedOperationException(
                    "No deftype method found for: " + method.getName() + "/" + args.length);
        };

        return Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                interfaces,
                handler
        );
    }
}
