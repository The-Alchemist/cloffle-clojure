package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.ImportNode;

import java.util.Map;

public class ImportNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword IMPORT = keyword("import");
    private static final Keyword CLASS = keyword("class");

    protected ImportNodeBuilder(AstBuilder astBuilder) {
        super(IMPORT, astBuilder);
    }

    @Override
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        String className = (String) tree.get(CLASS);
        return new ImportNode(className);
    }
}
