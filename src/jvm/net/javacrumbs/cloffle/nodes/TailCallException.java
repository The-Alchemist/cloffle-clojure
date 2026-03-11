package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.nodes.ControlFlowException;

/**
 * Unwinds a tail call to the nearest invoke trampoline so mutually
 * recursive Truffle functions can continue without growing the Java stack.
 */
public final class TailCallException extends ControlFlowException {

    private final CallTarget callTarget;
    private final Object closureFrame;
    private final Object[] args;

    public TailCallException(CallTarget callTarget, Object closureFrame, Object[] args) {
        this.callTarget = callTarget;
        this.closureFrame = closureFrame;
        this.args = args;
    }

    public CallTarget getCallTarget() {
        return callTarget;
    }

    public Object getClosureFrame() {
        return closureFrame;
    }

    public Object[] getArgs() {
        return args;
    }
}
