package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import clojure.lang.Symbol;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.StaticFieldNode;

import java.util.Map;

public class StaticFieldNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword STATIC_FIELD = keyword("static-field");
    private static final Keyword CLASS = keyword("class");
    private static final Keyword FIELD = keyword("field");

    protected StaticFieldNodeBuilder(AstBuilder astBuilder) {
        super(STATIC_FIELD, astBuilder);
    }

    @Override
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        Class<?> clazz = (Class<?>) tree.get(CLASS);
        Symbol fieldSym = (Symbol) tree.get(FIELD);
        return new StaticFieldNode(clazz, fieldSym.getName());
    }
}
