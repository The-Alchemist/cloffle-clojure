package net.javacrumbs.cloffle.ast;

import clojure.lang.Keyword;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.MonitorEnterNode;

import java.util.Map;

public class MonitorEnterNodeBuilder extends AbstractNodeBuilder {
    private static final Keyword MONITOR_ENTER = keyword("monitor-enter");
    private static final Keyword TARGET = keyword("target");

    protected MonitorEnterNodeBuilder(AstBuilder astBuilder) {
        super(MONITOR_ENTER, astBuilder);
    }

    @Override
    public ClojureNode buildNode(Map<Keyword, Object> tree) {
        ClojureNode target = build(tree.get(TARGET));
        return new MonitorEnterNode(target);
    }
}
