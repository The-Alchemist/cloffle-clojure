package clojure.lang;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Literal {@code conj} constant-fold was disabled so {@code with-redefs} is observed
 * (analyze-time fold erased the call before bindRoot). Values still compute correctly
 * via runtime {@code TupleConj} / Var invoke.
 */
public class ConstantConjFoldTest {

    @BeforeClass
    public static void initCore() {
        RT.init();
    }

    private static Compiler.Expr analyze(String code) throws Exception {
        Object form = LispReader.read(
                new LineNumberingPushbackReader(new StringReader(code)),
                false, null, false, null);
        return Compiler.analyze(Compiler.C.EXPRESSION, Compiler.macroexpand(form));
    }

    @Test
    public void singleLiteralConjFromEmptyDoesNotConstantFold() throws Exception {
        Compiler.Expr expr = analyze("(conj [] :v1)");
        assertFalse("conj must remain a call site for with-redefs, was " + expr.getClass().getName(),
                expr instanceof Compiler.ConstantVectorExpr);
        assertTrue(expr instanceof Compiler.InvokeExpr
                || expr instanceof Compiler.StaticMethodExpr);
    }

    @Test
    public void nestedLiteralConjFromEmptyDoesNotConstantFold() throws Exception {
        Compiler.Expr expr = analyze("(conj (conj (conj [] :v1) :v2) :v3)");
        assertFalse("expected non-constant conj nest, got " + expr.getClass().getName(),
                expr instanceof Compiler.ConstantVectorExpr);
    }

    @Test
    public void conjValueIsThreeElementVector() {
        assertEquals(RT.vector(1L, 2L, 3L), BytecodeDslTestSupport.evalBytecode("(conj [1 2] 3)"));
    }

    @Test
    public void conjInRestDestructuringEvaluates() {
        Object v = BytecodeDslTestSupport.evalBytecode(
                "(let [[first-elem & rest-elems] (conj [1 2] 3)] (first rest-elems))");
        assertEquals(2L, ((Number) v).longValue());
    }

    @Test
    public void withRedefsOnConjIsObserved() {
        assertEquals(Keyword.intern(null, "redefined"),
                BytecodeDslTestSupport.evalBytecode(
                        "(with-redefs [conj (fn [& _] :redefined)] (conj [1] 2))"));
    }
}
