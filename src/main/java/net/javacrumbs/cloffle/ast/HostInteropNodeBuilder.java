package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import clojure.lang.Symbol;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.HostInteropNode;

import java.util.List;
import java.util.Map;

public class HostInteropNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword HOST_INTEROP = keyword("host-interop");
    private static final Keyword M_OR_F = keyword("m-or-f");
    private static final Keyword TARGET = keyword("target");

    protected HostInteropNodeBuilder(AstBuilder astBuilder) {
        super(HOST_INTEROP, astBuilder);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        Symbol memberSym = (Symbol) tree.get(M_OR_F);
        ClojureNode target = build(tree.get(TARGET));
        return new HostInteropNode(memberSym.getName(), target, new ClojureNode[0]);
    }
}
