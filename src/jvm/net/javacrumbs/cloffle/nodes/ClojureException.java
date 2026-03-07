package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;

public class ClojureException extends AbstractTruffleException {

    public ClojureException(String message, Node location) {
        super(message, location);
    }

    public ClojureException(String message, Throwable cause, Node location) {
        super(message, cause, UNLIMITED_STACK_TRACE, location);
    }

    public static ClojureException wrap(Throwable t, Node location) {
        String msg = t.getClass().getSimpleName();
        String detail = t.getMessage();
        if (detail != null) {
            msg += ": " + detail;
        }
        return new ClojureException(msg, t, location);
    }
}
