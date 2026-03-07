package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.KeywordInvokeNode;

import java.util.Map;

public class KeywordInvokeNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword KEYWORD_INVOKE = keyword("keyword-invoke");
    private static final Keyword KEYWORD_KEY = keyword("keyword");
    private static final Keyword TARGET = keyword("target");

    protected KeywordInvokeNodeBuilder(AstBuilder astBuilder) {
        super(KEYWORD_INVOKE, astBuilder);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        Map<Keyword, Object> keywordNode = (Map<Keyword, Object>) tree.get(KEYWORD_KEY);
        Keyword keyword = (Keyword) keywordNode.get(keyword("val"));
        ClojureNode target = build(tree.get(TARGET));
        return new KeywordInvokeNode(keyword, target);
    }
}
