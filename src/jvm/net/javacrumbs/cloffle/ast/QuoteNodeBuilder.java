package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import net.javacrumbs.cloffle.nodes.ClojureNode;

import java.util.Map;

public class QuoteNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword QUOTE = keyword("quote");
    private static final Keyword EXPR = keyword("expr");

    protected QuoteNodeBuilder(AstBuilder astBuilder) {
        super(QUOTE, astBuilder);
    }

    @Override
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        return build(tree.get(EXPR));
    }
}
