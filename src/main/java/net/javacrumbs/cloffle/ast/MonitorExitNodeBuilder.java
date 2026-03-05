package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.MonitorExitNode;

import java.util.Map;

public class MonitorExitNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword MONITOR_EXIT = keyword("monitor-exit");
    private static final Keyword TARGET = keyword("target");

    protected MonitorExitNodeBuilder(AstBuilder astBuilder) {
        super(MONITOR_EXIT, astBuilder);
    }

    @Override
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        ClojureNode target = build(tree.get(TARGET));
        return new MonitorExitNode(target);
    }
}
