package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import clojure.lang.Symbol;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.InstanceFieldNode;

import java.util.Map;

public class InstanceFieldNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword INSTANCE_FIELD = keyword("instance-field");
    private static final Keyword INSTANCE = keyword("instance");
    private static final Keyword FIELD = keyword("field");

    protected InstanceFieldNodeBuilder(AstBuilder astBuilder) {
        super(INSTANCE_FIELD, astBuilder);
    }

    @Override
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        Symbol fieldSym = (Symbol) tree.get(FIELD);
        ClojureNode instance = build(tree.get(INSTANCE));
        return new InstanceFieldNode(fieldSym.getName(), instance);
    }
}
