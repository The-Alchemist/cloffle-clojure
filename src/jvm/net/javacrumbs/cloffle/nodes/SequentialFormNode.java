package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import net.javacrumbs.cloffle.Clojure;
import net.javacrumbs.cloffle.nodes.value.NilNode;

/**
 * Executes a sequence of top-level forms, each wrapped in its own
 * ClojureRootNode with a per-form FrameDescriptor. The CallTargets are
 * created at parse time and stored as {@code @Children DirectCallNode}s
 * so the debugger can step between forms and breakpoints can match
 * nodes inside each per-form root.
 */
public class SequentialFormNode extends ClojureNode {

    @Children
    private final DirectCallNode[] callNodes;

    public SequentialFormNode(Object[] formEntries, TruffleLanguage<?> language, Source source) {
        callNodes = new DirectCallNode[formEntries.length];
        for (int i = 0; i < formEntries.length; i++) {
            var entry = (Clojure.FormEntry) formEntries[i];
            ClojureNode formNode = entry.node();
            FrameDescriptor fd = entry.frameDescriptor();

            ClojureRootNode rootNode = ClojureRootNode.createRaw(formNode, fd, language);
            if (source != null) {
                rootNode.setSourceSection(source.createSection(0, source.getLength()));
                rootNode.adoptChildren();
                SourceSection formSection = formNode.getSourceSection();
                if (formSection != null && formSection.isAvailable()) {
                    rootNode.setSourceSection(formSection);
                }
            }
            callNodes[i] = DirectCallNode.create(rootNode.getCallTarget());
        }
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object lastResult = NilNode.NIL;
        for (int i = 0; i < callNodes.length; i++) {
            lastResult = callNodes[i].call();
        }
        return lastResult;
    }
}
