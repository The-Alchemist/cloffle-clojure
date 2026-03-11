package net.javacrumbs.cloffle.nodes;

/**
 * Value object holding the eagerly-evaluated recur arguments.
 * Used to pass pre-evaluated recur values from RecurNode to LoopNode/FnMethodNode.
 */
public final class RecurSentinel {

    private final Object[] values;

    public RecurSentinel(Object[] values) {
        this.values = values;
    }

    public Object[] getValues() {
        return values;
    }
}
