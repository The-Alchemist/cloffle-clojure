package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;

public class CatchNode extends ClojureNode {

    private final Class<?> exceptionClass;
    private final int localSlot;

    @Child
    private ClojureNode body;

    public CatchNode(Class<?> exceptionClass, int localSlot, ClojureNode body) {
        this.exceptionClass = exceptionClass;
        this.localSlot = localSlot;
        this.body = body;
    }

    public boolean matches(Throwable t) {
        return exceptionClass.isInstance(t);
    }

    public Object executeWithException(VirtualFrame frame, Throwable exception) {
        FrameDescriptor fd = getRootNode().getFrameDescriptor();
        if (fd.getSlotKind(localSlot) != FrameSlotKind.Object) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            fd.setSlotKind(localSlot, FrameSlotKind.Object);
        }
        frame.setObject(localSlot, exception);
        return body.executeGeneric(frame);
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        throw new UnsupportedOperationException("CatchNode should not be executed directly");
    }
}
