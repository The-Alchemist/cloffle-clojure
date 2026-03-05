package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.NilNode;

public class ImportNode extends ClojureNode {

    private final String className;

    public ImportNode(String className) {
        this.className = className;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        try {
            Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Cannot import class: " + className, e);
        }
        return NilNode.NIL;
    }
}
