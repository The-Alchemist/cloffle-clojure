package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

/**
 * Wraps a bytecode (or any) root call target so Java {@code null} results become
 * {@link net.javacrumbs.cloffle.nodes.value.NilNode#NIL}. GraalVM Polyglot rejects raw {@code null}
 * as a language return value from {@code org.graalvm.polyglot.Context#eval}.
 */
public final class PolyglotNilSafeRootNode extends RootNode {

    private final CallTarget inner;

    public PolyglotNilSafeRootNode(TruffleLanguage<?> language, FrameDescriptor frameDescriptor, CallTarget inner) {
        super(language, frameDescriptor);
        this.inner = inner;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return ClojureInterop.wrapForPolyglot(inner.call(frame.getArguments()));
    }
}
