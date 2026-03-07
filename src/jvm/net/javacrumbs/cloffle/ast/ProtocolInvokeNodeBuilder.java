package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.ProtocolInvokeNode;

import java.util.List;
import java.util.Map;

public class ProtocolInvokeNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword PROTOCOL_INVOKE = keyword("protocol-invoke");
    private static final Keyword PROTOCOL_FN = keyword("protocol-fn");
    private static final Keyword TARGET = keyword("target");
    private static final Keyword ARGS = keyword("args");

    protected ProtocolInvokeNodeBuilder(AstBuilder astBuilder) {
        super(PROTOCOL_INVOKE, astBuilder);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        ClojureNode protocolFn = build(tree.get(PROTOCOL_FN));
        ClojureNode target = build(tree.get(TARGET));

        List<Map<Keyword, Object>> argMaps = (List<Map<Keyword, Object>>) tree.get(ARGS);
        ClojureNode[] args = argMaps.stream().map(this::build).toArray(ClojureNode[]::new);

        return new ProtocolInvokeNode(protocolFn, target, args);
    }
}
