package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import clojure.lang.Symbol;
import net.javacrumbs.cloffle.nodes.CatchNode;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.TryNode;

import java.util.List;
import java.util.Map;

public class TryNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword TRY = keyword("try");
    private static final Keyword BODY = keyword("body");
    private static final Keyword CATCHES = keyword("catches");
    private static final Keyword FINALLY = keyword("finally");
    private static final Keyword CLASS = keyword("class");
    private static final Keyword LOCAL = keyword("local");
    private static final Keyword NAME = keyword("name");

    protected TryNodeBuilder(AstBuilder astBuilder) {
        super(TRY, astBuilder);
    }

    @Override
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        ClojureNode body = build(tree.get(BODY));

        @SuppressWarnings("unchecked")
        List<Map<Keyword, Object>> catchMaps = (List<Map<Keyword, Object>>) tree.get(CATCHES);
        CatchNode[] catchNodes = new CatchNode[catchMaps.size()];
        for (int i = 0; i < catchMaps.size(); i++) {
            catchNodes[i] = buildCatch(catchMaps.get(i));
        }

        ClojureNode finallyNode = buildOptional(tree.get(FINALLY));
        return new TryNode(body, catchNodes, finallyNode);
    }

    @SuppressWarnings("unchecked")
    private CatchNode buildCatch(Map<Keyword, Object> catchMap) {
        Map<Keyword, Object> classConst = (Map<Keyword, Object>) catchMap.get(CLASS);
        Class<?> exceptionClass = (Class<?>) classConst.get(VAL);

        Map<Keyword, Object> local = (Map<Keyword, Object>) catchMap.get(LOCAL);
        Object localName = local.get(NAME);
        int slot = getSlotIndex(localName);

        ClojureNode catchBody = build(catchMap.get(BODY));
        return new CatchNode(exceptionClass, slot, catchBody);
    }
}
