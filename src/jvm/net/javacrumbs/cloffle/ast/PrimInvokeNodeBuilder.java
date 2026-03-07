package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import com.oracle.truffle.api.TruffleLanguage;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.invoke.InvokeNode;

import java.util.Map;

/**
 * Handles :prim-invoke, which is a primitive-optimized invocation.
 * Delegates to InvokeNode since we don't differentiate primitive calls.
 */
public class PrimInvokeNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword PRIM_INVOKE = keyword("prim-invoke");
    private static final Keyword FN = keyword("fn");
    private static final Keyword ARGS = keyword("args");

    protected PrimInvokeNodeBuilder(AstBuilder astBuilder) {
        super(PRIM_INVOKE, astBuilder);
    }

    @Override
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        ClojureNode fn = build(tree.get(FN));
        ClojureNode[] args = convertToNodes(tree.get(ARGS), ClojureNode[]::new);
        TruffleLanguage<?> language = getAstBuilder().getLanguage();
        return new InvokeNode(fn, getAstBuilder(), language, args);
    }
}
