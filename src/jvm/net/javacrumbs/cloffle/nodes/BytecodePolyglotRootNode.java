package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import net.javacrumbs.cloffle.Clojure;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

/**
 * Wraps a bytecode {@link CallTarget} so top-level Polyglot evaluation never returns raw Java
 * {@code null} (invalid for interop); delegates via {@link DirectCallNode} like
 * {@link SequentialFormNode} for TCO-friendly dispatch.
 */
public final class BytecodePolyglotRootNode extends RootNode {

    @Child
    private DirectCallNode callNode;
    private SourceSection sourceSection;

    public BytecodePolyglotRootNode(Clojure language, FrameDescriptor frameDescriptor, CallTarget bytecodeTarget) {
        super(language, frameDescriptor);
        this.callNode = insert(DirectCallNode.create(bytecodeTarget));
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return ClojureInterop.wrapForPolyglot(callNode.call());
    }

    @Override
    public SourceSection getSourceSection() {
        return sourceSection;
    }

    public void setSourceSection(SourceSection sourceSection) {
        this.sourceSection = sourceSection;
    }
}
