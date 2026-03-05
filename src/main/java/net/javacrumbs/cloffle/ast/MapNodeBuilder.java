package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.MapNode;

import java.util.Map;

public class MapNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword MAP = keyword("map");
    private static final Keyword KEYS = keyword("keys");
    private static final Keyword VALS = keyword("vals");

    protected MapNodeBuilder(AstBuilder astBuilder) {
        super(MAP, astBuilder);
    }

    @Override
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        ClojureNode[] keys = convertToNodes(tree.get(KEYS), ClojureNode[]::new);
        ClojureNode[] vals = convertToNodes(tree.get(VALS), ClojureNode[]::new);
        return new MapNode(keys, vals);
    }
}
