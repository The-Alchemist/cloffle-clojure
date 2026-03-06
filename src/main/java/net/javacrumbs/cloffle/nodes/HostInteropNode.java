package net.javacrumbs.cloffle.nodes;

import clojure.lang.Reflector;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

/**
 * Handles unresolved host interop (reflective method call or field access).
 * This is used when the analyzer cannot determine the target type at compile time.
 */
public class HostInteropNode extends ClojureNode {

    private final String memberName;

    @Child
    private ClojureNode target;

    @Children
    private final ClojureNode[] args;

    public HostInteropNode(String memberName, ClojureNode target, ClojureNode[] args) {
        this.memberName = memberName;
        this.target = target;
        this.args = args;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object obj = ClojureInterop.unwrapFromPolyglot(target.executeGeneric(virtualFrame));

        Object[] argValues = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            argValues[i] = ClojureInterop.unwrapFromPolyglot(args[i].executeGeneric(virtualFrame));
        }

        if (args.length == 0) {
            try {
                return Reflector.getInstanceField(obj, memberName);
            } catch (IllegalArgumentException e) {
                // not a field, try as method
            }
        }

        try {
            return Reflector.invokeInstanceMethod(obj, memberName, argValues);
        } catch (IllegalArgumentException e) {
            // no matching method, try field fallback for >0 args
        }

        if (args.length > 0) {
            try {
                return Reflector.getInstanceField(obj, memberName);
            } catch (IllegalArgumentException e) {
                // fall through to error
            }
        }

        throw new RuntimeException("Cannot resolve member '" + memberName + "' on " + obj.getClass().getName());
    }

}
