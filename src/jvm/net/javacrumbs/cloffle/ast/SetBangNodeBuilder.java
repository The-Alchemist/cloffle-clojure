package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.SetBangNode;

import java.util.Map;

public class SetBangNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword SET_BANG = keyword("set!");
    private static final Keyword TARGET = keyword("target");

    protected SetBangNodeBuilder(AstBuilder astBuilder) {
        super(SET_BANG, astBuilder);
    }

    @Override
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        ClojureNode target = build(tree.get(TARGET));
        ClojureNode val = build(tree.get(VAL));
        return new SetBangNode(target, val);
    }
}
