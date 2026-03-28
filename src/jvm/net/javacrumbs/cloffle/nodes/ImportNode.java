package net.javacrumbs.cloffle.nodes;

import clojure.lang.Namespace;
import clojure.lang.RT;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import net.javacrumbs.cloffle.nodes.value.NilNode;

public class ImportNode extends ClojureNode {

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        return tag == StandardTags.StatementTag.class;
    }

    private final String className;

    public ImportNode(String className) {
        this.className = className;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        try {
            Namespace ns = (Namespace) RT.CURRENT_NS.deref();
            Class<?> c = RT.classForName(className);
            ns.importClass(c);
        } catch (Exception e) {
            throw new ClojureException("Cannot import class: " + className
                    + " (" + e.getClass().getSimpleName() + ")", e, this);
        }
        return NilNode.NIL;
    }
}
