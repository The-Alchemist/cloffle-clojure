package net.javacrumbs.cloffle.nodes.binding;

import clojure.lang.ArraySeq;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.value.NilNode;

/**
 * Initializes a variadic (&amp; rest) parameter by packing all arguments
 * from fixedArity onward into an ArraySeq. If no extra arguments
 * were passed, produces NilNode.NIL (Clojure nil).
 */
public class VariadicArgInitNode extends ClojureNode {
    private final int fixedArity;

    public VariadicArgInitNode(int fixedArity) {
        this.fixedArity = fixedArity;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object[] args = virtualFrame.getArguments();
        int restCount = args.length - fixedArity;
        if (restCount <= 0) {
            return NilNode.NIL;
        }
        Object[] restArray = new Object[restCount];
        System.arraycopy(args, fixedArity, restArray, 0, restCount);
        return ArraySeq.create(restArray);
    }
}
