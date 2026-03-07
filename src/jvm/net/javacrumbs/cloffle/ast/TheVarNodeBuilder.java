package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import clojure.lang.Var;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.value.ObjectNode;

import java.util.Map;

public class TheVarNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword THE_VAR = keyword("the-var");
    private static final Keyword VAR = keyword("var");

    protected TheVarNodeBuilder(AstBuilder astBuilder) {
        super(THE_VAR, astBuilder);
    }

    @Override
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        Var var = (Var) tree.get(VAR);
        return new ObjectNode(var);
    }
}
