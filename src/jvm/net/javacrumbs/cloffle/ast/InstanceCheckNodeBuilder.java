package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.InstanceCheckNode;

import java.util.Map;

public class InstanceCheckNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword INSTANCE_CHECK = keyword("instance?");
    private static final Keyword CLASS = keyword("class");
    private static final Keyword TARGET = keyword("target");

    protected InstanceCheckNodeBuilder(AstBuilder astBuilder) {
        super(INSTANCE_CHECK, astBuilder);
    }

    @Override
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        Class<?> clazz = (Class<?>) tree.get(CLASS);
        ClojureNode target = build(tree.get(TARGET));
        return new InstanceCheckNode(clazz, target);
    }
}
