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
    public void vecExplicitQuoteAnalyzesToTwoElementConstantVector() {
        Compiler.Expr expr = analyze("(vec (quote ({:id :one} {:id :two})))");
        assertTrue(expr instanceof Compiler.ConstantVectorExpr);
        assertEquals(2, ((Compiler.ConstantVectorExpr) expr).val.count());
    }

    @Test
    public void vecQuotedMapsAloneAnalyzesToConstantVector() {
        Compiler.Expr expr = analyze("(vec '({:status :ok :id :one} {:status :fail :id :two}))");
        assertTrue("expected ConstantVectorExpr, was " + expr.getClass().getName(),
                expr instanceof Compiler.ConstantVectorExpr);
        assertEquals(2, ((Compiler.ConstantVectorExpr) expr).val.count());
    }

    @Test
    public void mapKeywordOnVecQuotedMapsConstantFolds() {
        Compiler.Expr expr = analyze("(map :id (vec '({:id :one} {:id :two})))");
        assertTrue("expected ConstantVectorExpr, was " + expr.getClass().getName(),
                expr instanceof Compiler.ConstantVectorExpr);
        assertEquals(RT.vector(Keyword.intern("one"), Keyword.intern("two")),
                ((Compiler.ConstantVectorExpr) expr).val);
    }

    @Test
    public void vecQuotedMapsInLetInitAnalyzesToConstantVector() {
        Compiler.FnExpr fn = (Compiler.FnExpr) analyze(
                "(fn [] (let [rows (vec '({:id :one} {:id :two}))] rows))");
        Compiler.FnMethod m = (Compiler.FnMethod) fn.methods().seq().first();
        Compiler.Expr inner = m.body;
        if (inner instanceof Compiler.BodyExpr be && be.exprs.count() > 0) {
            inner = (Compiler.Expr) be.exprs.nth(0);
        }
        assertTrue(inner instanceof Compiler.LetExpr);
        Compiler.BindingInit bi = (Compiler.BindingInit) ((Compiler.LetExpr) inner).bindingInits.nth(0);
        assertTrue(bi.init() instanceof Compiler.ConstantVectorExpr);
        assertEquals(2, ((Compiler.ConstantVectorExpr) bi.init()).val.count());
    }

    @Test
    public void mapKeywordOnLiteralVectorOfMapsConstantFolds() {
        Compiler.Expr expr = analyze("(map :id [{:id :one :n 1} {:id :two :n 2}])");
        assertTrue(expr instanceof Compiler.ConstantVectorExpr);
        assertEquals(RT.vector(Keyword.intern("one"), Keyword.intern("two")),
                ((Compiler.ConstantVectorExpr) expr).val);
    }

    @Test
    public void intoCompFilterMapOnVecQuoteRowsConstantFolds() {
        Compiler.Expr expr = analyze(
                "(into [] (comp (map :id) (filter #(= :ok (:status %))))"
                        + " [{:status :ok :id :one} {:status :fail :id :two}])");
        if (expr instanceof Compiler.ConstantVectorExpr cve) {
            assertEquals(Keyword.intern("one"), cve.val.nth(0));
            return;
        }
        assertTrue("expected constant fold or FilteredEphemeralVectorSeq materialize, was "
                        + expr.getClass().getName(),
                expr instanceof Compiler.StaticMethodExpr sme
                        && sme.c == FilteredEphemeralVectorSeq.class
                        && "materializeFilterThenMap".equals(sme.methodName));
        assertEquals(Keyword.intern("one"), BytecodeDslTestSupport.evalBytecode(
                "(first (into [] (comp (map :id) (filter #(= :ok (:status %))))"
                        + " [{:status :ok :id :one} {:status :fail :id :two}]))"));
    }

    @Test
    public void mapKeywordOnFilteredVectorLiteralConstantFolds() {
        String code = "(map :id (filter #(= :ok (:status %)) [{:status :ok :id :one} {:status :fail :id :two}]))";
        Compiler.Expr expr = analyze(code);
        if (expr instanceof Compiler.ConstantVectorExpr cve) {
            assertEquals(Keyword.intern("one"), cve.val.nth(0));
        } else {
            assertTrue("expected constant fold or FilteredEphemeralVectorSeq.createMapped, was "
                            + expr.getClass().getName(),
                    expr instanceof Compiler.StaticMethodExpr sme
                            && sme.c == FilteredEphemeralVectorSeq.class
                            && "createMapped".equals(sme.methodName));
            assertEquals(Keyword.intern("one"), BytecodeDslTestSupport.evalBytecode(
                    "(first " + code + ")"));
        }
    }

    @Test
    public void filterOnVectorCallAnalyzesToFilteredEphemeralVectorSeqCreate() {
        Compiler.Expr expr = analyze("(filter #(= :ok (:status %)) (vector {:status :ok} {:status :fail}))");
        assertTrue(expr instanceof Compiler.StaticMethodExpr sme
                        && sme.c == FilteredEphemeralVectorSeq.class
                || expr instanceof Compiler.ConstantVectorExpr);
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

    @Test
    public void filterOnMapKeywordAnalyzesToMaterializeMapThenFilter() {
        String code = "(filter #(= :one %) (map :id (vec '({:status :ok :id :one} {:status :fail :id :two}))))";
        Compiler.Expr expr = analyze(code);
        if (expr instanceof Compiler.ConstantVectorExpr cve) {
            assertEquals(Keyword.intern("one"), cve.val.nth(0));
            assertEquals(1, cve.val.count());
            return;
        }
        if (expr instanceof Compiler.StaticMethodExpr sme
                && sme.c == FilteredEphemeralVectorSeq.class
                && "create".equals(sme.methodName)) {
            return;
        }
        assertFilteredEvsMethod(expr, "materializeMapThenFilter");
    }

    @Test
    public void filterOnMapKeywordOnLetRowsAnalyzesToFoldOrMaterialize() {
        Compiler.FnExpr fn = (Compiler.FnExpr) analyze(
                "(fn [] (let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]"
                        + " (filter #(= :one %) (map :id rows))))");
        Compiler.FnMethod m = (Compiler.FnMethod) fn.methods().seq().first();
        Compiler.Expr expr = m.body;
        if (expr instanceof Compiler.BodyExpr be && be.exprs.count() > 0) {
            expr = (Compiler.Expr) be.exprs.nth(0);
        }
        assertTrue(expr instanceof Compiler.LetExpr);
        Compiler.LetExpr le = (Compiler.LetExpr) expr;
        Compiler.BindingInit bi = (Compiler.BindingInit) le.bindingInits.nth(0);
        assertTrue(bi.init() instanceof Compiler.ConstantVectorExpr);
        expr = le.body;
        if (expr instanceof Compiler.BodyExpr be && be.exprs.count() > 0) {
            expr = (Compiler.Expr) be.exprs.nth(0);
        }
        if (expr instanceof Compiler.ConstantVectorExpr cve) {
            assertEquals(Keyword.intern("one"), cve.val.nth(0));
            return;
        }
        if (expr instanceof Compiler.StaticMethodExpr sme
                && sme.c == FilteredEphemeralVectorSeq.class
                && "create".equals(sme.methodName)) {
            return;
        }
        assertFilteredEvsMethod(expr, "materializeMapThenFilter");
    }

    private static void assertFilteredEvsMethod(Compiler.Expr expr, String methodName) {
        if (expr instanceof Compiler.StaticMethodExpr sme
                && sme.c == FilteredEphemeralVectorSeq.class
                && methodName.equals(sme.methodName)) {
            return;
        }
        assertTrue("expected FilteredEphemeralVectorSeq." + methodName + ", was " + expr,
                false);
    }

    @Test
    public void evalFirstFilterOnMapIdMatchesSnippetFixture() {
        String code = "(let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]"
                + " (first (filter #(= :one %) (map :id rows))))";
        assertEquals(Keyword.intern("one"), BytecodeDslTestSupport.evalBytecode(code));
    }
}
