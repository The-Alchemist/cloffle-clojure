package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import net.javacrumbs.cloffle.nodes.value.NilNode;

/**
 * One top-level form: wraps the form's {@link CallTarget} so the debugger can step between
 * forms in a multi-form script. {@code StatementTag} is conditional on
 * {@link TopLevelFormEntry#isRuntimeStatement()}: runtime forms (calls, simple defs, control
 * flow) are steppable; function definitions ({@code defn}/{@code defmacro}) are not —
 * matching Java/Python/JS UX where definitions do not halt the debugger at load time.
 *
 * <p>The inner bytecode root suppresses its own root-level {@code StatementTag} (via
 * {@link net.javacrumbs.cloffle.bytecode.ExprToBytecode#convertRoot}'s inhibit mechanism)
 * so a runtime form gets exactly one {@code StatementTag} on a given line — this one.
 */
final class TopLevelEvalNode extends ClojureNode {

    @Child
    private DirectCallNode callNode;

    private final boolean isRuntimeStatement;

    TopLevelEvalNode(CallTarget target, Source source, int sourceLine, boolean isRuntimeStatement) {
        this.callNode = DirectCallNode.create(target);
        this.isRuntimeStatement = isRuntimeStatement;
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
        if (tag == StandardTags.StatementTag.class) return isRuntimeStatement;
        return tag == StandardTags.ExpressionTag.class;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        return callNode.call();
    }
}

/**
 * Executes a sequence of top-level forms. Each form's {@link CallTarget}
 * (produced by the bytecode backend for each top-level form) is wrapped in a
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
            children[i] = new TopLevelEvalNode(e.target(), source, line, e.isRuntimeStatement());
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
