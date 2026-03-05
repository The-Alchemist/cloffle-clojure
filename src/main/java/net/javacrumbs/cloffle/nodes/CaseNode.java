package net.javacrumbs.cloffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

import java.util.Objects;

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

    @Child
    private ClojureNode defaultNode;

    public CaseNode(ClojureNode test, ClojureNode[] caseTests, ClojureNode[] caseThens,
                    ClojureNode defaultNode) {
        this.test = test;
        this.caseTests = caseTests;
        this.caseThens = caseThens;
        this.defaultNode = defaultNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame virtualFrame) {
        Object testValue = test.executeGeneric(virtualFrame);

        for (int i = 0; i < caseTests.length; i++) {
            Object caseTestValue = caseTests[i].executeGeneric(virtualFrame);
            if (Objects.equals(testValue, caseTestValue)) {
                return caseThens[i].executeGeneric(virtualFrame);
            }
        }

        if (defaultNode != null) {
            return defaultNode.executeGeneric(virtualFrame);
        }

        throw new IllegalArgumentException("No matching clause for case: " + testValue);
    }
}
