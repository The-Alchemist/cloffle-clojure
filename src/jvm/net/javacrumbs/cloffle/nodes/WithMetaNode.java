package net.javacrumbs.cloffle.nodes;

import clojure.lang.IObj;
import clojure.lang.IPersistentMap;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

public class WithMetaNode extends ClojureNode {

    @Child private ClojureNode expr;
    @Child private ClojureNode meta;

    public WithMetaNode(ClojureNode expr, ClojureNode meta) {
        this.expr = expr;
        this.meta = meta;
    }

    public ClojureNode getInnerExpr() {
        return expr;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object exprValue = ClojureInterop.unwrapFromPolyglot(expr.executeGeneric(virtualFrame));
        Object metaValue = ClojureInterop.unwrapFromPolyglot(meta.executeGeneric(virtualFrame));
        if (exprValue instanceof IObj iobj && metaValue instanceof IPersistentMap map) {
            return iobj.withMeta(map);
        }
        return exprValue;
    }
}
