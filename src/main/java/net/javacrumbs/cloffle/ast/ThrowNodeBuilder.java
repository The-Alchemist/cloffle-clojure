package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.ThrowNode;

import java.util.Map;

public class ThrowNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword THROW = keyword("throw");
    private static final Keyword EXCEPTION = keyword("exception");

    protected ThrowNodeBuilder(AstBuilder astBuilder) {
        super(THROW, astBuilder);
    }

    @Override
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        ClojureNode exception = build(tree.get(EXCEPTION));
        return new ThrowNode(exception);
    }
}
