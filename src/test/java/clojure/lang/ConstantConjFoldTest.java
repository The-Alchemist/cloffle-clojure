package clojure.lang;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConstantConjFoldTest {

    @BeforeClass
    public static void initCore() {
        RT.init();
    }

    @Test
    public void singleLiteralConjFromEmptyAnalyzesToConstantVector() throws Exception {
        Object form = LispReader.read(
                new LineNumberingPushbackReader(new StringReader("(conj [] :v1)")),
                false, null, false, null);
        Compiler.Expr expr = Compiler.analyze(Compiler.C.EXPRESSION, Compiler.macroexpand(form));
        assertTrue(expr instanceof Compiler.ConstantVectorExpr);
    }

    @Test
    public void nestedLiteralConjFromEmptyAnalyzesToConstantVector() throws Exception {
        Object form = LispReader.read(
                new LineNumberingPushbackReader(new StringReader(
                        "(conj (conj (conj [] :v1) :v2) :v3)")),
                false, null, false, null);
        Object expanded = Compiler.macroexpand(form);
        Compiler.Expr expr = Compiler.analyze(Compiler.C.EXPRESSION, expanded);
        assertTrue("expected ConstantVectorExpr, got " + expr.getClass().getName(),
                expr instanceof Compiler.ConstantVectorExpr);
    }

    @Test
    public void foldedConjValueIsThreeElementVector() {
        assertEquals(RT.vector(1L, 2L, 3L), BytecodeDslTestSupport.evalBytecode("(conj [1 2] 3)"));
    }

    @Test
    public void foldedConjInRestDestructuringEvaluates() {
        Object v = BytecodeDslTestSupport.evalBytecode(
                "(let [[first-elem & rest-elems] (conj [1 2] 3)] (first rest-elems))");
        assertEquals(2L, ((Number) v).longValue());
    }
}
