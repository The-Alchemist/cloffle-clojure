package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import net.javacrumbs.cloffle.nodes.CaseNode;
import net.javacrumbs.cloffle.nodes.ClojureNode;

import java.util.List;
import java.util.Map;

public class CaseNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword CASE = keyword("case");
    private static final Keyword TEST = keyword("test");
    private static final Keyword TESTS = keyword("tests");
    private static final Keyword THENS = keyword("thens");
    private static final Keyword DEFAULT = keyword("default");
    private static final Keyword THEN = keyword("then");

    protected CaseNodeBuilder(AstBuilder astBuilder) {
        super(CASE, astBuilder);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        ClojureNode test = build(tree.get(TEST));

        List<Map<Keyword, Object>> testMaps = (List<Map<Keyword, Object>>) tree.get(TESTS);
        ClojureNode[] caseTests = new ClojureNode[testMaps.size()];
        for (int i = 0; i < testMaps.size(); i++) {
            caseTests[i] = build(testMaps.get(i).get(TEST));
        }

        List<Map<Keyword, Object>> thenMaps = (List<Map<Keyword, Object>>) tree.get(THENS);
        ClojureNode[] caseThens = new ClojureNode[thenMaps.size()];
        for (int i = 0; i < thenMaps.size(); i++) {
            caseThens[i] = build(thenMaps.get(i).get(THEN));
        }

        ClojureNode defaultNode = buildOptional(tree.get(DEFAULT));

        return new CaseNode(test, caseTests, caseThens, defaultNode);
    }
}
