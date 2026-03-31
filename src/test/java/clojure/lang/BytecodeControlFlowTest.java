package clojure.lang;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@code if}, {@code do}, {@code case*}, and {@link Compiler.KeywordInvokeExpr} ({@code (:kw map)}).
 * <p>
 * No {@code clojure.core} load — forms limited to what {@link Compiler#analyze} handles natively.
 * <p>
 * Package {@code clojure.lang} for access to {@link Compiler} internals.
 * Helpers: {@link BytecodeDslTestSupport}.
 */
public class BytecodeControlFlowTest {

    @Test
    public void ifWithTruthiness() {
        assertEquals(1L, BytecodeDslTestSupport.evalBytecode("(if true 1 2)"));
        assertEquals(2L, BytecodeDslTestSupport.evalBytecode("(if false 1 2)"));
        assertEquals(1L, BytecodeDslTestSupport.evalBytecode("(if :x 1 2)"));
        assertEquals(2L, BytecodeDslTestSupport.evalBytecode("(if nil 1 2)"));
    }

    @Test
    public void nestedIf() {
        assertEquals(2L, BytecodeDslTestSupport.evalBytecode("(if true (if false 1 2) 3)"));
        assertEquals(3L, BytecodeDslTestSupport.evalBytecode("(if false (if true 1 2) 3)"));
    }

    @Test
    public void doReturnsLastValue() {
        assertEquals(3L, BytecodeDslTestSupport.evalBytecode("(do 1 2 3)"));
        assertEquals(null, BytecodeDslTestSupport.evalBytecode("(do nil)"));
    }

    /**
     * {@code case*} special form (no {@code clojure.core} {@code case} macro). Map shape matches
     * {@link Compiler.CaseExpr.Parser}: {@code {dispatch-int [test-constant then] ...}}.
     */
    @Test
    public void caseStarIntCompactDispatches() {
        String k = "(let* [x %s] (case* x 0 0 :none {1 [1 :a] 2 [2 :b]} :compact :int))";
        assertEquals(Keyword.intern(null, "a"), BytecodeDslTestSupport.evalBytecode(String.format(k, "1")));
        assertEquals(Keyword.intern(null, "b"), BytecodeDslTestSupport.evalBytecode(String.format(k, "2")));
        assertEquals(Keyword.intern(null, "none"), BytecodeDslTestSupport.evalBytecode(String.format(k, "99")));
    }

    /**
     * {@code case*} with {@code recur} branches inside {@code loop*}.
     * Mirrors the pattern from {@code clojure.tools.reader/read-string*}:
     * {@code (loop [sb (StringBuilder.) ch ...] (case* ch ... (recur ...)))}.
     */
    @Test
    public void caseStarWithRecurInLoop() {
        Object result = BytecodeDslTestSupport.evalBytecode(
                "(loop* [x 0 acc 0]"
                + "  (case* x 0 3 acc"
                + "    {0 [0 (recur 3 (clojure.lang.Numbers/add acc 10))]}  "
                + "    :compact :int))");
        assertEquals("case recur should loop once then hit default", 10L, result);
    }

    @Test
    public void caseStarWithRecurMultipleBranches() {
        Object result = BytecodeDslTestSupport.evalBytecode(
                "(loop* [x 0 acc \"\"]"
                + "  (case* x 0 3 acc"
                + "    {0 [0 (recur 1 (.concat acc \"a\"))]"
                + "     1 [1 (recur 2 (.concat acc \"b\"))]"
                + "     2 [2 (recur 3 (.concat acc \"c\"))]}"
                + "    :compact :int))");
        assertEquals("abc", result);
    }

    // --- KeywordInvokeExpr ---

    @Test
    public void keywordInvokeOnMapLiteral() {
        assertEquals(1L, BytecodeDslTestSupport.evalBytecode("(:a {:a 1 :b 2})"));
        assertEquals(2L, BytecodeDslTestSupport.evalBytecode("(:b {:a 1 :b 2})"));
    }

    @Test
    public void keywordInvokeWithExpressionTarget() {
        assertEquals(7L, BytecodeDslTestSupport.evalBytecode("(let* [m {:x 7}] (:x m))"));
    }

    @Test
    public void nestedKeywordInvokeOnMapLiterals() {
        assertEquals(9L, BytecodeDslTestSupport.evalBytecode("(:b (:a {:a {:b 9}}))"));
    }
}
