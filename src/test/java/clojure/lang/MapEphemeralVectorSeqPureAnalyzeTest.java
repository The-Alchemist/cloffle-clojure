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
        assertTrue("expected EphemeralVectorSeqKeywordCreateExpr, was " + expr.getClass().getName(),
                expr instanceof Compiler.EphemeralVectorSeqKeywordCreateExpr);
    }

    @Test
    public void mapKeywordOnVectorCallRewritesToEphemeralVectorSeq() {
        // Pure map on vector-shaped coll → EphemeralVectorSeq (not ConstantVectorExpr /
        // PersistentTuple, which break realized? / IPending).
        Compiler.Expr expr = analyze("(map :status (vector {:status :ok}))");
        assertEphemeralVectorSeqCreate(expr);
        assertEquals(Keyword.intern("ok"), BytecodeDslTestSupport.evalBytecode(
                "(first (map :status (vector {:status :ok})))"));
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
    public void mapKeywordOnVecQuotedMapsRewritesToEphemeralVectorSeq() {
        Compiler.Expr expr = analyze("(map :id (vec '({:id :one} {:id :two})))");
        assertEphemeralVectorSeqCreate(expr);
        assertEquals(RT.vector(Keyword.intern("one"), Keyword.intern("two")),
                BytecodeDslTestSupport.evalBytecode(
                        "(vec (map :id (vec '({:id :one} {:id :two}))))"));
    }

    @Test
    public void mapKeywordElidesSeqAroundVectorishLocal() {
        Compiler.FnExpr fn = (Compiler.FnExpr) analyze(
                "(fn [] (let [rows (vec (list {:id (identity :one)} {:id :two}))]"
                        + " (map :id (seq rows))))");
        Compiler.FnMethod method = (Compiler.FnMethod) fn.methods().seq().first();
        Compiler.Expr expr = method.body;
        if (expr instanceof Compiler.BodyExpr body) {
            expr = (Compiler.Expr) body.exprs.nth(0);
        }
        Compiler.LetExpr let = (Compiler.LetExpr) expr;
        expr = let.body;
        if (expr instanceof Compiler.BodyExpr body) {
            expr = (Compiler.Expr) body.exprs.nth(0);
        }
        assertEphemeralVectorSeqCreate(expr);
        Compiler.EphemeralVectorSeqKeywordCreateExpr create =
                (Compiler.EphemeralVectorSeqKeywordCreateExpr) expr;
        assertTrue("seq wrapper should be removed before EVS keyword create",
                create.coll instanceof Compiler.LocalBindingExpr);
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
    public void mapKeywordOnLiteralVectorOfMapsRewritesToEphemeralVectorSeq() {
        Compiler.Expr expr = analyze("(map :id [{:id :one :n 1} {:id :two :n 2}])");
        assertEphemeralVectorSeqCreate(expr);
        assertEquals(RT.vector(Keyword.intern("one"), Keyword.intern("two")),
                BytecodeDslTestSupport.evalBytecode(
                        "(vec (map :id [{:id :one :n 1} {:id :two :n 2}]))"));
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
    public void mapKeywordOnFilteredVectorLiteralRewritesToCreateMapped() {
        String code = "(map :id (filter #(= :ok (:status %)) [{:status :ok :id :one} {:status :fail :id :two}]))";
        Compiler.Expr expr = analyze(code);
        assertTrue("expected FilteredEphemeralVectorSeq.createMapped, was "
                        + expr.getClass().getName(),
                expr instanceof Compiler.StaticMethodExpr sme
                        && sme.c == FilteredEphemeralVectorSeq.class
                        && "createMapped".equals(sme.methodName));
        assertEquals(Keyword.intern("one"), BytecodeDslTestSupport.evalBytecode(
                "(first " + code + ")"));
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
    public void evalMapFieldRowsRuntimeSnippet() {
        String code = "(let [coll (list {:status :ok :id :one} {:status :fail :id :two})"
                + " rows (vec coll)] (first (map :id rows)))";
        assertEquals(Keyword.intern("one"), BytecodeDslTestSupport.evalBytecode(code));
    }

    @Test
    public void evalFirstFilterOnMapIdMatchesSnippetFixture() {
        String code = "(let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]"
                + " (first (filter #(= :one %) (map :id rows))))";
        assertEquals(Keyword.intern("one"), BytecodeDslTestSupport.evalBytecode(code));
    }

    @Test
    public void evalEmptyFilterOnVectorIsTruthyWithEmptySeq() throws Exception {
        assertEquals(true, BytecodeDslTestSupport.evalBytecode(
                "(let [rows (vector 2 4)] (boolean (filter odd? rows)))"));
        assertEquals(0L, ((Number) BytecodeDslTestSupport.evalBytecode(
                "(let [rows (vector 2 4)] (count (filter odd? rows)))")).longValue());
        assertEquals(true, BytecodeDslTestSupport.evalBytecode(
                "(let [rows (vector 2 4)] (nil? (seq (filter odd? rows))))"));
    }

    @Test
    public void evalEmptyMapOnFilteredVector() throws Exception {
        // Empty FilteredEphemeralVectorSeq.createMapped may surface as null (same as
        // EphemeralVectorSeq.create) when emitted as a bare StaticMethodExpr — unlike
        // stock LazySeq, which is always an object. count/seq still match.
        String code = "(map :id (filter #(= :ok (:status %))"
                + " (vector {:status :fail :id :one})))";
        assertEquals(0L, ((Number) BytecodeDslTestSupport.evalBytecode(
                "(count " + code + ")")).longValue());
        assertEquals(true, BytecodeDslTestSupport.evalBytecode(
                "(nil? (seq " + code + "))"));
        assertEquals(Keyword.intern("one"), BytecodeDslTestSupport.evalBytecode(
                "(first (map :id (filter #(= :ok (:status %))"
                + " (vector {:status :ok :id :one} {:status :fail :id :two}))))"));
    }

    @Test
    public void evalIntoCompFilterMapOnRuntimeVector() throws Exception {
        assertEquals(Keyword.intern("one"), BytecodeDslTestSupport.evalBytecode(
                "(let [rows (vector {:status :ok :id :one} {:status :fail :id :two})]"
                + " (first (into [] (comp (map :id) (filter #(= :ok (:status %)))) rows)))"));
        assertEquals(0L, ((Number) BytecodeDslTestSupport.evalBytecode(
                "(count (into [] (comp (map :id) (filter #(= :ok (:status %))))"
                + " (vector {:status :fail :id :two})))")).longValue());
    }

    @Test
    public void evalFilterOnVectorAllMatch() throws Exception {
        assertEquals(2L, ((Number) BytecodeDslTestSupport.evalBytecode(
                "(count (filter even? (vector 2 4)))")).longValue());
    }
}

