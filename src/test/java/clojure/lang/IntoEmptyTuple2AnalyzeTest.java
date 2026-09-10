package clojure.lang;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.StringReader;

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

    @Test
    public void literalPairVectorAnalyzesToConstantVector() {
        Compiler.Expr expr = analyze("[:first :second]");
        assertTrue("snippet source vector should be ConstantVectorExpr, was " + expr.getClass().getName(),
                expr instanceof Compiler.ConstantVectorExpr);
    }

    @Test
    public void intoEmptyTwoElementAnalyzesToRtIntoStaticCall() {
        Compiler.Expr expr = analyze("(into [] [:first :second])");
        assertTrue("into should rewrite to RT.into StaticMethodExpr, was " + expr.getClass().getName(),
                expr instanceof Compiler.StaticMethodExpr sme
                        && sme.c == RT.class
                        && "into".equals(sme.methodName));
    }
}
