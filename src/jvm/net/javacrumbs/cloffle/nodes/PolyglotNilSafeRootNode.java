package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

/**
 * Wraps a bytecode (or any) root call target so Java {@code null} results become
 * {@link net.javacrumbs.cloffle.nodes.value.NilNode#NIL}. GraalVM Polyglot rejects raw {@code null}
 * as a language return value from {@link org.graalvm.polyglot.Context#eval}.
 * <p>
 * {@link #setSourceSection} stores the analyzed form’s span (balanced s-expression) for Polyglot
 * guest stack traces; the inner bytecode root may still report a whole-source section.
 */
public final class PolyglotNilSafeRootNode extends RootNode {

    private final CallTarget inner;
    private SourceSection sourceSection;

    public PolyglotNilSafeRootNode(TruffleLanguage<?> language, FrameDescriptor frameDescriptor, CallTarget inner) {
        super(language, frameDescriptor);
        this.inner = inner;
    }

    @Override
    public SourceSection getSourceSection() {
        return sourceSection;
    }

    public void setSourceSection(SourceSection sourceSection) {
        this.sourceSection = sourceSection;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return ClojureInterop.wrapForPolyglot(callInner(frame.getArguments()));
    }

    /**
     * Keeps a boundary around the inner call so Polyglot can attribute guest frames to this root’s
     * {@link #setSourceSection} span.
     */
    @TruffleBoundary
    private Object callInner(Object[] arguments) {
        return inner.call(arguments);
    }
}
