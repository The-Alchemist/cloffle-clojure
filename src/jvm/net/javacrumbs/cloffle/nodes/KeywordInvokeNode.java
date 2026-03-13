package net.javacrumbs.cloffle.nodes;

import clojure.lang.ILookup;
import clojure.lang.Keyword;
import clojure.lang.RT;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.VirtualFrame;

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
        try {
            if (targetVal instanceof ILookup lookup) {
                return lookup.valAt(keyword);
            }
            return RT.get(targetVal, keyword);
        } catch (AbstractTruffleException e) {
            throw e;
        } catch (Throwable t) {
            CompilerDirectives.transferToInterpreter();
            throw ClojureException.wrap(t, this);
        }
    }
}
