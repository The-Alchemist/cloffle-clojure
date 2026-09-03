package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import net.javacrumbs.cloffle.nodes.value.NilNode;
import net.javacrumbs.cloffle.trace.CloffleTracer;

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
 *
 * <p>When {@link CloffleTracer} is enabled, emits {@code formEnter}/{@code formExit} around
 * the call and {@code exception} if the form throws.
 */
final class TopLevelEvalNode extends ClojureNode {

    @Child
    private DirectCallNode callNode;

    private final boolean isRuntimeStatement;
    private final String uri;
    private final int sourceLine;
    private final int sourceColumn;
    private final String formText;

    TopLevelEvalNode(CallTarget target, Source source, TopLevelFormEntry entry) {
        this.callNode = DirectCallNode.create(target);
        this.isRuntimeStatement = entry.isRuntimeStatement();
        this.uri = entry.uri() != null ? entry.uri() : CloffleTracer.uriOf(source);
        this.sourceLine = entry.sourceLine() >= 1 ? entry.sourceLine() : 1;
        this.sourceColumn = entry.sourceColumn() > 0 ? entry.sourceColumn() : 1;
        this.formText = entry.formText();
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
        boolean tracing = CloffleTracer.isEnabled();
        if (tracing) {
            CloffleTracer.formEnter(uri, sourceLine, sourceColumn, formText);
        }
        try {
            Object result = callNode.call();
            if (tracing) {
                CloffleTracer.formExit(uri, sourceLine, sourceColumn, formText, result);
            }
            return result;
        } catch (Throwable t) {
            if (tracing) {
                CloffleTracer.exception(uri, sourceLine, sourceColumn, t);
            }
            throw t;
        }
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
            children[i] = new TopLevelEvalNode(entries[i].target(), source, entries[i]);
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
