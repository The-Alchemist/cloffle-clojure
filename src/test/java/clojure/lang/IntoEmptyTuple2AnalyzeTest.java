package clojure.lang;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Why {@code into-empty-tuple2} stays at ~496 B/op while {@code tuple-destructure} is 0 B/op.
 */
public class IntoEmptyTuple2AnalyzeTest {

    @BeforeClass
    public static void initCore() {
        RT.init();
    }

    private static Compiler.Expr analyze(String code) {
        Object form = LispReader.read(
                new LineNumberingPushbackReader(new StringReader(code)),
                false, null, false, null);
        return Compiler.analyze(Compiler.C.EXPRESSION, Compiler.macroexpand(form));
    }

    private static Compiler.Expr analyzeWithLockedFolds(String code) throws Exception {
        return BytecodeDslTestSupport.withLockedCallSiteRewrites(() -> analyze(code));
    }

    @Test
    public void literalPairVectorAnalyzesToConstantVector() {
        Compiler.Expr expr = analyze("[:first :second]");
        assertTrue("snippet source vector should be ConstantVectorExpr, was " + expr.getClass().getName(),
                expr instanceof Compiler.ConstantVectorExpr);
    }

    @Test
    public void intoEmptyDoesNotConstantFoldWhenLockedFoldsOff() {
        Compiler.Expr expr = analyze("(into [] [:first :second])");
        assertFalse(expr instanceof Compiler.ConstantVectorExpr);
        assertTrue("default options leave #'into as InvokeExpr for redef parity, was "
                        + expr.getClass().getName(),
                expr instanceof Compiler.InvokeExpr);
    }

    @Test
    public void intoEmptyTwoElementAnalyzesToRtIntoStaticCall() throws Exception {
        Compiler.Expr expr = analyzeWithLockedFolds("(into [] [:first :second])");
        assertTrue("into [] literal pair should constant-fold to ConstantVectorExpr, was "
                        + expr.getClass().getName(),
                expr instanceof Compiler.ConstantVectorExpr);
    }

    @Test
    public void intoEmptyWithMapIdentityLiteralAnalyzesToConstantVector() throws Exception {
        Compiler.Expr expr = analyzeWithLockedFolds("(into [] (map identity [:one :two :three]))");
        assertTrue(expr instanceof Compiler.ConstantVectorExpr);
    }
}
