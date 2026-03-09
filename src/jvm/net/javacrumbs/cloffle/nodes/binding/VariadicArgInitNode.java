package net.javacrumbs.cloffle.nodes.binding;

import clojure.lang.ArraySeq;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.value.NilNode;

/**
 * Initializes a variadic (&amp; rest) parameter by packing all arguments
 * from fixedArity onward into an ArraySeq. If no extra arguments
 * were passed, produces NilNode.NIL (Clojure nil).
 * Arguments are shifted by 1 (arg 0 is captured frame).
 */
public class VariadicArgInitNode extends ClojureNode {
    private final int fixedArity;

    public VariadicArgInitNode(int fixedArity) {
        this.fixedArity = fixedArity;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object[] args = virtualFrame.getArguments();
        // User arguments start at index 1
        int userArgsLength = args.length - 1;
        int restCount = userArgsLength - fixedArity;
        if (restCount <= 0) {
            return NilNode.NIL;
        }
        Object[] restArray = new Object[restCount];
        // Copy starting from 1 (captured frame) + fixedArity
        System.arraycopy(args, 1 + fixedArity, restArray, 0, restCount);
        return ArraySeq.create(restArray);
    }
}
