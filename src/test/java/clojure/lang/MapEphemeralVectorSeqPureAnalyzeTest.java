package clojure.lang;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MapEphemeralVectorSeqPureAnalyzeTest {

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

    private static void assertEphemeralVectorSeqCreate(Compiler.Expr expr) {
        assertTrue("expected EphemeralVectorSeq.create, was " + expr.getClass().getName(),
                expr instanceof Compiler.StaticMethodExpr sme
                        && sme.c == EphemeralVectorSeq.class
                        && "create".equals(sme.methodName));
    }

    @Test
    public void mapKeywordOnVectorCallAnalyzesToEphemeralVectorSeqCreate() {
        assertEphemeralVectorSeqCreate(analyze("(map :status (vector {:status :ok}))"));
    }

    @Test
    public void mapKeywordOnVecCallAnalyzesToEphemeralVectorSeqCreate() {
        assertEphemeralVectorSeqCreate(analyze("(map :id (vec '({:id :one} {:id :two})))"));
    }

    @Test
    public void evalFirstMapIdOnVecLetRows() {
        assertEquals(Keyword.intern("one"), BytecodeDslTestSupport.evalBytecode(
                "(first (let [rows (vec '({:id :one} {:id :two}))] (map :id rows)))"));
    }

    @Test
    public void mapKeywordOnLiteralVectorOfMapsConstantFolds() {
        Compiler.Expr expr = analyze("(map :id [{:id :one :n 1} {:id :two :n 2}])");
        assertTrue(expr instanceof Compiler.ConstantVectorExpr);
        assertEquals(RT.vector(Keyword.intern("one"), Keyword.intern("two")),
                ((Compiler.ConstantVectorExpr) expr).val);
    }

    @Test
    public void evalMapIdsFromLiteralVector() {
        assertEquals(Keyword.intern("five"), BytecodeDslTestSupport.evalBytecode(
                "(nth (map :id [{:id :one} {:id :two} {:id :three} {:id :four} {:id :five}]) 4)"));
    }

    @Test
    public void mapIncOnLiteralVectorDoesNotRewrite() {
        Compiler.Expr expr = analyze("(map inc [:one])");
        assertFalse(expr instanceof Compiler.StaticMethodExpr);
        assertTrue(expr instanceof Compiler.InvokeExpr);
    }

    @Test
    public void evalFirstMapNameOnRecords() {
        assertEquals("a", BytecodeDslTestSupport.evalBytecode(
                "(first (map :name [{:name \"a\"} {:name \"b\"}]))"));
    }

    @Test
    public void evalFirstMapStatusOnLiteralVector() {
        assertEquals(Keyword.intern("ok"), BytecodeDslTestSupport.evalBytecode(
                "(first (map :status [{:status :ok :id 1} {:status :fail :id 2}]))"));
    }
}
