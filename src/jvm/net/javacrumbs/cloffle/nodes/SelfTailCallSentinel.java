package net.javacrumbs.cloffle.nodes;

/**
 * Value object carrying evaluated arguments for a self tail call.
 * Consumed by FnMethodNode's execute loop to rebind params without
 * allocating a new Truffle call frame.
 */
public final class SelfTailCallSentinel {

    private final Object[] args;

    public SelfTailCallSentinel(Object[] args) {
        this.args = args;
    }

    public Object[] getArgs() {
        return args;
    }
}
