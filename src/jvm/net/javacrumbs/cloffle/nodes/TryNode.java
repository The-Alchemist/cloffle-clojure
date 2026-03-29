package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import net.javacrumbs.cloffle.ClojureTypesGen;

public class TryNode extends ClojureNode {

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        return tag == StandardTags.StatementTag.class
            || tag == StandardTags.ExpressionTag.class;
    }

    @Child
    private ClojureNode body;

    @Children
    private final CatchNode[] catchNodes;

    @Child
    private ClojureNode finallyNode;

    public TryNode(ClojureNode body, CatchNode[] catchNodes, ClojureNode finallyNode) {
        this.body = body;
        this.catchNodes = catchNodes;
        this.finallyNode = finallyNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        try {
            return body.executeGeneric(virtualFrame);
        } catch (ControlFlowException e) {
            throw e;
        } catch (Throwable e) {
            return handleException(virtualFrame, e);
        } finally {
            if (finallyNode != null) {
                finallyNode.executeGeneric(virtualFrame);
            }
        }
    }

    @Override
    public long executeLong(VirtualFrame virtualFrame) throws UnexpectedResultException {
        try {
            return body.executeLong(virtualFrame);
        } catch (UnexpectedResultException e) {
            throw e;
        } catch (ControlFlowException e) {
            throw e;
        } catch (Throwable e) {
            return ClojureTypesGen.expectLong(handleException(virtualFrame, e));
        } finally {
            if (finallyNode != null) {
                finallyNode.executeGeneric(virtualFrame);
            }
        }
    }

    @Override
    public double executeDouble(VirtualFrame virtualFrame) throws UnexpectedResultException {
        try {
            return body.executeDouble(virtualFrame);
        } catch (UnexpectedResultException e) {
            throw e;
        } catch (ControlFlowException e) {
            throw e;
        } catch (Throwable e) {
            return ClojureTypesGen.expectDouble(handleException(virtualFrame, e));
        } finally {
            if (finallyNode != null) {
                finallyNode.executeGeneric(virtualFrame);
            }
        }
    }

    @Override
    public boolean executeBoolean(VirtualFrame virtualFrame) throws UnexpectedResultException {
        try {
            return body.executeBoolean(virtualFrame);
        } catch (UnexpectedResultException e) {
            throw e;
        } catch (ControlFlowException e) {
            throw e;
        } catch (Throwable e) {
            return ClojureTypesGen.expectBoolean(handleException(virtualFrame, e));
        } finally {
            if (finallyNode != null) {
                finallyNode.executeGeneric(virtualFrame);
            }
        }
    }

    private Object handleException(VirtualFrame virtualFrame, Throwable e) {
        Throwable unwrapped = unwrapClojureException(e);
        for (CatchNode catchNode : catchNodes) {
            if (catchNode.matches(unwrapped)) {
                return catchNode.executeWithException(virtualFrame, unwrapped);
            }
        }
        if (e instanceof AbstractTruffleException) {
            throw (AbstractTruffleException) e;
        }
        throw ClojureException.wrap(unwrapped, this);
    }

    private static Throwable unwrapClojureException(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof ClojureException ce && ce.getCause() != null) {
            current = ce.getCause();
        }
        return current;
    }
}
