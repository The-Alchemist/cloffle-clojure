package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.Clojure;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;
import net.javacrumbs.cloffle.nodes.value.NilNode;

/**
 * Executes a sequence of top-level forms, each with its own FrameDescriptor.
 * This mimics how Clojure's Compiler.load() works: read one form, compile it,
 * evaluate it, then move to the next. Each form gets its own isolated frame.
 */
public class SequentialFormNode extends ClojureNode {

    private final FormEntry[] forms;
    private final TruffleLanguage<?> language;

    public record FormEntry(ClojureNode node, FrameDescriptor frameDescriptor) {}

    public SequentialFormNode(Object[] formEntries, TruffleLanguage<?> language) {
        this.forms = new FormEntry[formEntries.length];
        for (int i = 0; i < formEntries.length; i++) {
            @SuppressWarnings("unchecked")
            var entry = (Clojure.FormEntry) formEntries[i];
            this.forms[i] = new FormEntry(entry.node(), entry.frameDescriptor());
        }
        this.language = language;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        return executeSequentially();
    }

    @TruffleBoundary
    private Object executeSequentially() {
        Object lastResult = NilNode.NIL;
        int errorCount = 0;
        for (int i = 0; i < forms.length; i++) {
            FormEntry form = forms[i];
            try {
                CallTarget callTarget = ClojureRootNode.create(
                        form.node, form.frameDescriptor, language).getCallTarget();
                lastResult = ClojureInterop.unwrap(callTarget.call());
            } catch (Exception e) {
                errorCount++;
                Throwable cause = e;
                while (cause.getCause() != null) cause = cause.getCause();
                System.err.println("CLOFFLE: Error at form #" + (i + 1)
                        + " (" + form.node.getClass().getSimpleName() + "): "
                        + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
        }
        System.err.println("CLOFFLE: Completed " + forms.length + " forms with " + errorCount + " errors");
        return lastResult;
    }
}
