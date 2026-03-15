package net.javacrumbs.cloffle.nodes;

import clojure.lang.Util;
import com.oracle.truffle.api.frame.VirtualFrame;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

/**
 * Implements Clojure's (case ...) expression.
 * Evaluates the test expression and compares it against case-test values,
 * returning the matching case-then result, or the default if no match.
 */
public class CaseNode extends ClojureNode {

    @Child
    private ClojureNode test;

    @Children
    private final ClojureNode[] caseTests;

    @Children
    private final ClojureNode[] caseThens;

    private final boolean[] skipCheck;

    @Child
    private ClojureNode defaultNode;

    public CaseNode(ClojureNode test, ClojureNode[] caseTests, ClojureNode[] caseThens,
                    boolean[] skipCheck, ClojureNode defaultNode) {
        this.test = test;
        this.caseTests = caseTests;
        this.caseThens = caseThens;
        this.skipCheck = skipCheck;
        this.defaultNode = defaultNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object testValue = ClojureInterop.unwrapFromPolyglot(test.executeGeneric(virtualFrame));

        for (int i = 0; i < caseTests.length; i++) {
            if (skipCheck[i]) {
                Object caseTestValue = ClojureInterop.unwrapFromPolyglot(caseTests[i].executeGeneric(virtualFrame));
                if (Util.hash(testValue) == Util.hash(caseTestValue)) {
                    return caseThens[i].executeGeneric(virtualFrame);
                }
            } else {
                Object caseTestValue = ClojureInterop.unwrapFromPolyglot(caseTests[i].executeGeneric(virtualFrame));
                if (Util.equiv(testValue, caseTestValue)) {
                    return caseThens[i].executeGeneric(virtualFrame);
                }
            }
        }

        if (defaultNode != null) {
            return defaultNode.executeGeneric(virtualFrame);
        }

        throw new ClojureException("No matching clause for case: "
                + ErrorMessages.truncateValue(testValue, 40), this);
    }
}
