package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.Clojure;
import net.javacrumbs.cloffle.nodes.value.NilNode;

/**
 * Executes a sequence of top-level forms, each with its own FrameDescriptor.
 * This mimics how Clojure's Compiler.load() works: read one form, compile it,
 * evaluate it, then move to the next. Each form gets its own isolated frame.
 */
public class SequentialFormNode extends ClojureNode {

    private final FormEntry[] forms;
    private final TruffleLanguage<?> language;
    private final Source source;

    public record FormEntry(ClojureNode node, FrameDescriptor frameDescriptor) {}

    public SequentialFormNode(Object[] formEntries, TruffleLanguage<?> language, Source source) {
        this.forms = new FormEntry[formEntries.length];
        for (int i = 0; i < formEntries.length; i++) {
            var entry = (Clojure.FormEntry) formEntries[i];
            this.forms[i] = new FormEntry(entry.node(), entry.frameDescriptor());
        }
        this.language = language;
        this.source = source;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        return executeSequentially();
    }

    @TruffleBoundary
    private Object executeSequentially() {
        Object lastResult = NilNode.NIL;
        for (int i = 0; i < forms.length; i++) {
            FormEntry form = forms[i];
            ClojureRootNode rootNode = ClojureRootNode.createRaw(
                    form.node, form.frameDescriptor, language);
            if (source != null) {
                rootNode.setSourceSection(source.createSection(0, source.getLength()));
            }
            CallTarget callTarget = rootNode.getCallTarget();
            lastResult = callTarget.call();
        }
        return lastResult;
    }
}
