package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.LetNode;
import net.javacrumbs.cloffle.nodes.binding.BindingNode;

import java.util.Map;

/**
 * Handles :letfn (mutually recursive function bindings).
 * Structurally identical to :let for our purposes.
 */
public class LetFnNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword LETFN = keyword("letfn");
    private static final Keyword BINDINGS = keyword("bindings");
    private static final Keyword BODY = keyword("body");

    protected LetFnNodeBuilder(AstBuilder astBuilder) {
        super(LETFN, astBuilder);
    }

    @Override
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        BindingNode[] bindings = convertToNodes(tree.get(BINDINGS), BindingNode[]::new);
        ClojureNode body = build(tree.get(BODY));
        return new LetNode(bindings, body);
    }
}
