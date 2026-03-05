package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.NilNode;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/**
 * Implements Clojure's (reify ...) form by creating a Java Proxy
 * that dispatches method calls to Cloffle AST method bodies.
 */
public class ReifyNode extends ClojureNode {

    private final Class<?>[] interfaces;
    private final ReifyMethodDef[] methodDefs;
    private final com.oracle.truffle.api.TruffleLanguage<?> language;

    public ReifyNode(Class<?>[] interfaces, ReifyMethodDef[] methodDefs,
                     com.oracle.truffle.api.TruffleLanguage<?> language) {
        this.interfaces = interfaces;
        this.methodDefs = methodDefs;
        this.language = language;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        return createProxy();
    }

    @TruffleBoundary
    private Object createProxy() {
        InvocationHandler handler = (proxy, method, args) -> {
            if (args == null) args = new Object[0];

            for (ReifyMethodDef def : methodDefs) {
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
                return "reify@" + Integer.toHexString(System.identityHashCode(proxy));
            }

            throw new UnsupportedOperationException(
                    "No reify method found for: " + method.getName() + "/" + args.length);
        };

        return Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                interfaces,
                handler
        );
    }

    public static class ReifyMethodDef {
        private final String name;
        private final int thisSlot;
        private final int[] paramSlots;
        private final ClojureNode body;
        private final int maxSlot;

        public ReifyMethodDef(String name, int thisSlot, int[] paramSlots, ClojureNode body) {
            this.name = name;
            this.thisSlot = thisSlot;
            this.paramSlots = paramSlots;
            this.body = body;
            int max = thisSlot;
            for (int s : paramSlots) {
                if (s > max) max = s;
            }
            this.maxSlot = max;
        }

        public boolean matches(String methodName, int argCount) {
            return this.name.equals(methodName) && this.paramSlots.length == argCount;
        }

        @TruffleBoundary
        public Object invoke(Object proxy, Object[] args,
                             com.oracle.truffle.api.TruffleLanguage<?> language) {
            FrameDescriptor.Builder fdBuilder = FrameDescriptor.newBuilder().defaultValue(null);
            for (int i = 0; i <= maxSlot; i++) {
                fdBuilder.addSlot(FrameSlotKind.Object, null, null);
            }
            FrameDescriptor fd = fdBuilder.build();

            ReifyMethodWrapperNode wrapper = new ReifyMethodWrapperNode(
                    thisSlot, paramSlots, body, proxy, args);
            ClojureRootNode rootNode = ClojureRootNode.createRaw(wrapper, fd, language);
            CallTarget callTarget = rootNode.getCallTarget();
            Object result = callTarget.call();
            if (result instanceof net.javacrumbs.cloffle.nodes.value.NilNode.Nil) return null;
            if (result instanceof FnNode fnNode) return fnNode.toIFn();
            return result;
        }
    }

    private static class ReifyMethodWrapperNode extends ClojureNode {
        private final int thisSlot;
        private final int[] paramSlots;
        @Child private ClojureNode body;
        private final Object thisValue;
        private final Object[] argValues;

        ReifyMethodWrapperNode(int thisSlot, int[] paramSlots, ClojureNode body,
                               Object thisValue, Object[] argValues) {
            this.thisSlot = thisSlot;
            this.paramSlots = paramSlots;
            this.body = body;
            this.thisValue = thisValue;
            this.argValues = argValues;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            frame.setObject(thisSlot, thisValue);
            for (int i = 0; i < paramSlots.length; i++) {
                frame.setObject(paramSlots[i], argValues[i]);
            }
            Object result = body.executeGeneric(frame);
            return result != null ? result : NilNode.NIL;
        }
    }
}
