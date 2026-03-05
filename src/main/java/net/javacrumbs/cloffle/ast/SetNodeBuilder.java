package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.SetNode;

import java.util.Map;

public class SetNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword SET = keyword("set");
    private static final Keyword ITEMS = keyword("items");

    protected SetNodeBuilder(AstBuilder astBuilder) {
        super(SET, astBuilder);
    }

    @Override
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        ClojureNode[] items = convertToNodes(tree.get(ITEMS), ClojureNode[]::new);
        return new SetNode(items);
    }
}
