package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.VectorNode;

import java.util.Map;

public class VectorNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword VECTOR = keyword("vector");
    private static final Keyword ITEMS = keyword("items");

    protected VectorNodeBuilder(AstBuilder astBuilder) {
        super(VECTOR, astBuilder);
    }

    @Override
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        ClojureNode[] items = convertToNodes(tree.get(ITEMS), ClojureNode[]::new);
        return new VectorNode(items);
    }
}
