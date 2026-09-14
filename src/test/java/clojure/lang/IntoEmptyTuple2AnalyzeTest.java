package clojure.lang;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Why {@code into-empty-tuple2} stays at ~496 B/op while {@code tuple-destructure} is 0 B/op.
 */
public class IntoEmptyTuple2AnalyzeTest {

    @BeforeAll
    static void initCore() {
        RT.init();
    }

    @Test
    void literalPairVectorAnalyzesToConstantVector() throws Exception {
        Compiler.Expr expr = BytecodeDslTestSupport.analyzeExpressionDirectLinkingOff("[:first :second]");
        assertTrue(expr instanceof Compiler.ConstantVectorExpr,
                () -> "snippet source vector should be ConstantVectorExpr, was " + expr.getClass().getName());
    }

    @Test
    @Tag("direct-linking-off")
    void intoEmptyDoesNotConstantFoldWhenLockedFoldsOff() throws Exception {
        Compiler.Expr expr = BytecodeDslTestSupport.analyzeExpressionDirectLinkingOff("(into [] [:first :second])");
        assertFalse(expr instanceof Compiler.ConstantVectorExpr);
        assertTrue(expr instanceof Compiler.InvokeExpr,
                () -> "default options leave #'into as InvokeExpr for redef parity, was "
                        + expr.getClass().getName());
    }

    @Test
    @Tag("direct-linking-on")
    void intoEmptyTwoElementAnalyzesToRtIntoStaticCall() throws Exception {
        Compiler.Expr expr = BytecodeDslTestSupport.analyzeExpressionDirectLinkingOn("(into [] [:first :second])");
        assertTrue(expr instanceof Compiler.ConstantVectorExpr,
                () -> "into [] literal pair should constant-fold to ConstantVectorExpr, was "
                        + expr.getClass().getName());
    }

    @Test
    @Tag("direct-linking-on")
    void intoEmptyWithMapIdentityLiteralAnalyzesToConstantVector() throws Exception {
        Compiler.Expr expr =
                BytecodeDslTestSupport.analyzeExpressionDirectLinkingOn("(into [] (map identity [:one :two :three]))");
        assertTrue(expr instanceof Compiler.ConstantVectorExpr);
    }
}
