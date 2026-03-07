package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.NewNode;

import java.util.List;
import java.util.Map;

public class NewNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword NEW = keyword("new");
    private static final Keyword CLASS = keyword("class");
    private static final Keyword ARGS = keyword("args");

    protected NewNodeBuilder(AstBuilder astBuilder) {
        super(NEW, astBuilder);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        Map<Keyword, Object> classConst = (Map<Keyword, Object>) tree.get(CLASS);
        Class<?> clazz = (Class<?>) classConst.get(VAL);

        List<Map<Keyword, Object>> argMaps = (List<Map<Keyword, Object>>) tree.get(ARGS);
        ClojureNode[] args = argMaps.stream().map(this::build).toArray(ClojureNode[]::new);

        return new NewNode(clazz, args);
    }
}
