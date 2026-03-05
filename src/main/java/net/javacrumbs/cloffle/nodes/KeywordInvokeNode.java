package net.javacrumbs.cloffle.nodes;

import clojure.lang.Keyword;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;
import net.javacrumbs.cloffle.nodes.value.ClojureMap;

public class KeywordInvokeNode extends ClojureNode {

    private final Keyword keyword;

    @Child
    private ClojureNode target;

    public KeywordInvokeNode(Keyword keyword, ClojureNode target) {
        this.keyword = keyword;
        this.target = target;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object targetVal = target.executeGeneric(virtualFrame);
        if (targetVal instanceof ClojureMap clojureMap) {
            Object result = clojureMap.getMap().valAt(keyword);
            return ClojureInterop.wrap(result);
        }
        Object unwrapped = ClojureInterop.unwrap(targetVal);
        if (unwrapped instanceof clojure.lang.ILookup lookup) {
            return ClojureInterop.wrap(lookup.valAt(keyword));
        }
        throw new RuntimeException("Cannot invoke keyword " + keyword + " on " + targetVal);
    }
}
