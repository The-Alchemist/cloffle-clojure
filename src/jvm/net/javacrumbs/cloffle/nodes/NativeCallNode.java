package net.javacrumbs.cloffle.nodes;

import clojure.lang.IFn;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;
import net.javacrumbs.cloffle.nodes.value.NilNode;

/**
 * Wraps a native Clojure IFn for invocation from Cloffle.
 * Arguments are passed via the frame's arguments array.
 */
public class NativeCallNode extends ClojureNode {

    private final IFn fn;

    public NativeCallNode(IFn fn) {
        this.fn = fn;
    }

    public IFn getFn() {
        return fn;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object[] raw = virtualFrame.getArguments();
        Object[] args = new Object[raw.length];
        for (int i = 0; i < raw.length; i++) {
            args[i] = ClojureInterop.unwrapFromPolyglot(raw[i]);
        }
        Object result;
        switch (args.length) {
            case 0 -> result = fn.invoke();
            case 1 -> result = fn.invoke(args[0]);
            case 2 -> result = fn.invoke(args[0], args[1]);
            case 3 -> result = fn.invoke(args[0], args[1], args[2]);
            case 4 -> result = fn.invoke(args[0], args[1], args[2], args[3]);
            default -> result = fn.applyTo(clojure.lang.RT.seq(args));
        }
        if (result == null) return NilNode.NIL;
        return result;
    }

}
