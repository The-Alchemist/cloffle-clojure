package net.javacrumbs.cloffle.nodes;

import clojure.lang.Util;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import net.javacrumbs.cloffle.nodes.value.ClojureInterop;

/**
 * Implements Clojure's (case ...) expression.
 * Evaluates the test expression and compares it against case-test values,
 * returning the matching case-then result, or the default if no match.
 */
public class CaseNode extends ClojureNode {

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        return tag == StandardTags.StatementTag.class
            || tag == StandardTags.ExpressionTag.class;
    }

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
        int matchIndex = findMatch(virtualFrame, testValue);
        if (matchIndex >= 0) {
            return caseThens[matchIndex].executeGeneric(virtualFrame);
        }
        if (defaultNode != null) {
            return defaultNode.executeGeneric(virtualFrame);
        }
        throw noMatchException(testValue);
    }

    @Override
    public long executeLong(VirtualFrame virtualFrame) throws UnexpectedResultException {
        Object testValue = ClojureInterop.unwrapFromPolyglot(test.executeGeneric(virtualFrame));
        int matchIndex = findMatch(virtualFrame, testValue);
        if (matchIndex >= 0) {
            return caseThens[matchIndex].executeLong(virtualFrame);
        }
        if (defaultNode != null) {
            return defaultNode.executeLong(virtualFrame);
        }
        throw noMatchException(testValue);
    }

    @Override
    public double executeDouble(VirtualFrame virtualFrame) throws UnexpectedResultException {
        Object testValue = ClojureInterop.unwrapFromPolyglot(test.executeGeneric(virtualFrame));
        int matchIndex = findMatch(virtualFrame, testValue);
        if (matchIndex >= 0) {
            return caseThens[matchIndex].executeDouble(virtualFrame);
        }
        if (defaultNode != null) {
            return defaultNode.executeDouble(virtualFrame);
        }
        throw noMatchException(testValue);
    }

    @Override
    public boolean executeBoolean(VirtualFrame virtualFrame) throws UnexpectedResultException {
        Object testValue = ClojureInterop.unwrapFromPolyglot(test.executeGeneric(virtualFrame));
        int matchIndex = findMatch(virtualFrame, testValue);
        if (matchIndex >= 0) {
            return caseThens[matchIndex].executeBoolean(virtualFrame);
        }
        if (defaultNode != null) {
            return defaultNode.executeBoolean(virtualFrame);
        }
        throw noMatchException(testValue);
    }

    private int findMatch(VirtualFrame virtualFrame, Object testValue) {
        for (int i = 0; i < caseTests.length; i++) {
            Object caseTestValue = ClojureInterop.unwrapFromPolyglot(caseTests[i].executeGeneric(virtualFrame));
            if (skipCheck[i]) {
                if (Util.hash(testValue) == Util.hash(caseTestValue)) {
                    return i;
                }
            } else {
                if (Util.equiv(testValue, caseTestValue)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private ClojureException noMatchException(Object testValue) {
        return new ClojureException("No matching clause for case: "
                + ErrorMessages.truncateValue(testValue, 40), this);
    }
}
