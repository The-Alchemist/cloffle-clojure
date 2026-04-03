package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import net.javacrumbs.cloffle.nodes.value.NilNode;

/**
 * One top-level form: statement boundary for the debugger between {@code DirectCallNode} evals.
 */
final class TopLevelEvalNode extends ClojureNode {

    @Child
    private DirectCallNode callNode;

    TopLevelEvalNode(CallTarget target, Source source, int sourceLine) {
        this.callNode = DirectCallNode.create(target);
        if (source != null && sourceLine >= 1) {
            try {
                int len = Math.max(1, source.getLineLength(sourceLine));
                setSourceSectionByLine(sourceLine, 1, len);
            } catch (Exception e) {
                setSourceSectionByLine(sourceLine, 1, 1);
            }
        }
    }

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        return tag == StandardTags.StatementTag.class
                || tag == StandardTags.ExpressionTag.class;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        return callNode.call();
    }
}

/**
 * Executes a sequence of top-level forms. Each form's {@link CallTarget}
 * (produced by either the AST or bytecode backend) is wrapped in a
 * {@link TopLevelEvalNode} so the debugger can step between forms.
 */
public class SequentialFormNode extends ClojureNode {

    /** Declared as {@link ClojureNode} so instrumentation can insert {@link ClojureNodeWrapper}. */
    @Children
    private final ClojureNode[] children;

    public SequentialFormNode(Source source, TopLevelFormEntry[] entries) {
        children = new ClojureNode[entries.length];
        for (int i = 0; i < entries.length; i++) {
            TopLevelFormEntry e = entries[i];
            int line = e.sourceLine() >= 1 ? e.sourceLine() : 1;
            children[i] = new TopLevelEvalNode(e.target(), source, line);
        }
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object lastResult = NilNode.NIL;
        for (ClojureNode child : children) {
            lastResult = child.executeGeneric(virtualFrame);
        }
        return lastResult;
    }
}
