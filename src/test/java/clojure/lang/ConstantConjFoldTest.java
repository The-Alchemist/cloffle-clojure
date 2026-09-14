package clojure.lang;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Literal {@code conj} constant-fold was disabled so {@code with-redefs} is observed
 * (analyze-time fold erased the call before bindRoot). Values still compute correctly
 * via runtime {@code TupleConj} / Var invoke.
 */
public class ConstantConjFoldTest {

    @BeforeAll
    static void initCore() {
        RT.init();
    }

    @Test
    @Tag("direct-linking-off")
    void singleLiteralConjFromEmptyDoesNotConstantFold() throws Exception {
        Compiler.Expr expr = BytecodeDslTestSupport.analyzeExpressionDirectLinkingOff("(conj [] :v1)");
        assertFalse(expr instanceof Compiler.ConstantVectorExpr,
                () -> "conj must remain a call site for with-redefs, was " + expr.getClass().getName());
        assertTrue(expr instanceof Compiler.InvokeExpr || expr instanceof Compiler.StaticMethodExpr);
    }

    @Test
    @Tag("direct-linking-off")
    void nestedLiteralConjFromEmptyDoesNotConstantFold() throws Exception {
        Compiler.Expr expr =
                BytecodeDslTestSupport.analyzeExpressionDirectLinkingOff("(conj (conj (conj [] :v1) :v2) :v3)");
        assertFalse(expr instanceof Compiler.ConstantVectorExpr,
                () -> "expected non-constant conj nest, got " + expr.getClass().getName());
    }

    @Test
    void conjValueIsThreeElementVector() {
        assertEquals(RT.vector(1L, 2L, 3L), BytecodeDslTestSupport.evalBytecode("(conj [1 2] 3)"));
    }

    @Test
    void conjInRestDestructuringEvaluates() {
        Object v = BytecodeDslTestSupport.evalBytecode(
                "(let [[first-elem & rest-elems] (conj [1 2] 3)] (first rest-elems))");
        assertEquals(2L, ((Number) v).longValue());
    }

    @Test
    @Tag("direct-linking-off")
    void withRedefsOnConjIsObserved() throws Exception {
        assertEquals(Keyword.intern(null, "redefined"),
                BytecodeDslTestSupport.evalBytecodeDirectLinkingOff(
                        "(with-redefs [conj (fn [& _] :redefined)] (conj [1] 2))"));
    }
}
