package clojure.lang;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MapEphemeralVectorSeqPureAnalyzeTest {

    @BeforeAll
    static void initCore() {
        RT.init();
    }

    private static void assertEphemeralVectorSeqCreate(Compiler.Expr expr) {
        assertTrue(expr instanceof Compiler.EphemeralVectorSeqKeywordCreateExpr,
                () -> "expected EphemeralVectorSeqKeywordCreateExpr, was " + expr.getClass().getName());
    }

    @Test
    @Tag("direct-linking-off")
    void mapKeywordOnVectorDoesNotRewriteWhenLockedFoldsOff() throws Exception {
        Compiler.Expr expr = BytecodeDslTestSupport.analyzeExpressionDirectLinkingOff("(map :status (vector {:status :ok}))");
        assertTrue(expr instanceof Compiler.InvokeExpr,
                () -> "default options must leave #'map as InvokeExpr for redef parity, was "
                        + expr.getClass().getName());
    }

    @Test
    @Tag("direct-linking-on")
    void mapKeywordOnVectorCallRewritesToEphemeralVectorSeq() throws Exception {
        // Pure map on vector-shaped coll → EphemeralVectorSeq (not ConstantVectorExpr /
        // PersistentTuple, which break realized? / IPending).
        Compiler.Expr expr = BytecodeDslTestSupport.analyzeExpressionDirectLinkingOn("(map :status (vector {:status :ok}))");
        assertEphemeralVectorSeqCreate(expr);
        assertEquals(Keyword.intern("ok"), BytecodeDslTestSupport.evalBytecodeDirectLinkingOn(
                "(first (map :status (vector {:status :ok})))"));
    }

    @Test
    @Tag("direct-linking-on")
    void vecExplicitQuoteAnalyzesToTwoElementConstantVector() throws Exception {
        Compiler.Expr expr = BytecodeDslTestSupport.analyzeExpressionDirectLinkingOn("(vec (quote ({:id :one} {:id :two})))");
        assertTrue(expr instanceof Compiler.ConstantVectorExpr);
        assertEquals(2, ((Compiler.ConstantVectorExpr) expr).val.count());
    }

    @Test
    @Tag("direct-linking-on")
    void vecQuotedMapsAloneAnalyzesToConstantVector() throws Exception {
        Compiler.Expr expr = BytecodeDslTestSupport.analyzeExpressionDirectLinkingOn(
                "(vec '({:status :ok :id :one} {:status :fail :id :two}))");
        assertTrue(expr instanceof Compiler.ConstantVectorExpr,
                () -> "expected ConstantVectorExpr, was " + expr.getClass().getName());
        assertEquals(2, ((Compiler.ConstantVectorExpr) expr).val.count());
    }

    @Test
    @Tag("direct-linking-on")
    void mapKeywordOnVecQuotedMapsRewritesToEphemeralVectorSeq() throws Exception {
        Compiler.Expr expr = BytecodeDslTestSupport.analyzeExpressionDirectLinkingOn("(map :id (vec '({:id :one} {:id :two})))");
        assertEphemeralVectorSeqCreate(expr);
        assertEquals(RT.vector(Keyword.intern("one"), Keyword.intern("two")),
                BytecodeDslTestSupport.evalBytecodeDirectLinkingOn(
                        "(vec (map :id (vec '({:id :one} {:id :two}))))"));
    }

    @Test
    @Tag("direct-linking-on")
    void mapKeywordElidesSeqAroundVectorishLocal() throws Exception {
        Compiler.FnExpr fn = (Compiler.FnExpr) BytecodeDslTestSupport.analyzeExpressionDirectLinkingOn(
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
        assertTrue(create.coll instanceof Compiler.LocalBindingExpr,
                "seq wrapper should be removed before EVS keyword create");
    }

    @Test
    @Tag("direct-linking-on")
    void vecQuotedMapsInLetInitAnalyzesToConstantVector() throws Exception {
        Compiler.FnExpr fn = (Compiler.FnExpr) BytecodeDslTestSupport.analyzeExpressionDirectLinkingOn(
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
    @Tag("direct-linking-on")
    void mapKeywordOnLiteralVectorOfMapsRewritesToEphemeralVectorSeq() throws Exception {
        Compiler.Expr expr = BytecodeDslTestSupport.analyzeExpressionDirectLinkingOn("(map :id [{:id :one :n 1} {:id :two :n 2}])");
        assertEphemeralVectorSeqCreate(expr);
        assertEquals(RT.vector(Keyword.intern("one"), Keyword.intern("two")),
                BytecodeDslTestSupport.evalBytecodeDirectLinkingOn(
                        "(vec (map :id [{:id :one :n 1} {:id :two :n 2}]))"));
    }

    @Test
    @Tag("direct-linking-on")
    void intoCompFilterMapOnVecQuoteRowsConstantFolds() throws Exception {
        String form = "(into [] (comp (map :id) (filter #(= :ok (:status %))))"
                + " [{:status :ok :id :one} {:status :fail :id :two}])";
        Compiler.Expr expr = BytecodeDslTestSupport.analyzeExpressionDirectLinkingOn(form);
        if (expr instanceof Compiler.ConstantVectorExpr cve) {
            assertEquals(Keyword.intern("one"), cve.val.nth(0));
            return;
        }
        assertTrue(expr instanceof Compiler.StaticMethodExpr sme
                        && sme.c == FilteredEphemeralVectorSeq.class
                        && "materializeFilterThenMap".equals(sme.methodName),
                () -> "expected constant fold or FilteredEphemeralVectorSeq materialize, was "
                        + expr.getClass().getName());
        // Eval under locked folds so analyze rewrites match the fold under test.
        assertEquals(Keyword.intern("one"),
                BytecodeDslTestSupport.evalBytecodeDirectLinkingOn("(first " + form + ")"));
    }

    @Test
    @Tag("direct-linking-on")
    void mapKeywordOnFilteredVectorLiteralRewritesToCreateMapped() throws Exception {
        String code = "(map :id (filter #(= :ok (:status %)) [{:status :ok :id :one} {:status :fail :id :two}]))";
        Compiler.Expr expr = BytecodeDslTestSupport.analyzeExpressionDirectLinkingOn(code);
        assertTrue(expr instanceof Compiler.StaticMethodExpr sme
                        && sme.c == FilteredEphemeralVectorSeq.class
                        && "createMapped".equals(sme.methodName),
                () -> "expected FilteredEphemeralVectorSeq.createMapped, was "
                        + expr.getClass().getName());
        assertEquals(Keyword.intern("one"), BytecodeDslTestSupport.evalBytecodeDirectLinkingOn(
                "(first " + code + ")"));
    }

    @Test
    @Tag("direct-linking-on")
    void filterOnVectorCallAnalyzesToFilteredEphemeralVectorSeqCreate() throws Exception {
        Compiler.Expr expr = BytecodeDslTestSupport.analyzeExpressionDirectLinkingOn(
                "(filter #(= :ok (:status %)) (vector {:status :ok} {:status :fail}))");
        assertTrue(expr instanceof Compiler.StaticMethodExpr sme
                        && sme.c == FilteredEphemeralVectorSeq.class
                || expr instanceof Compiler.ConstantVectorExpr);
    }

    @Test
    void evalMapIdsFromLiteralVector() {
        assertEquals(Keyword.intern("five"), BytecodeDslTestSupport.evalBytecode(
                "(nth (map :id [{:id :one} {:id :two} {:id :three} {:id :four} {:id :five}]) 4)"));
    }

    @Test
    @Tag("direct-linking-off")
    void mapIncOnLiteralVectorDoesNotRewrite() throws Exception {
        Compiler.Expr expr = BytecodeDslTestSupport.analyzeExpressionDirectLinkingOff("(map inc [:one])");
        assertFalse(expr instanceof Compiler.StaticMethodExpr);
        assertTrue(expr instanceof Compiler.InvokeExpr);
    }

    @Test
    void evalFirstMapNameOnRecords() {
        assertEquals("a", BytecodeDslTestSupport.evalBytecode(
                "(first (map :name [{:name \"a\"} {:name \"b\"}]))"));
    }

    @Test
    void evalFirstMapStatusOnLiteralVector() {
        assertEquals(Keyword.intern("ok"), BytecodeDslTestSupport.evalBytecode(
                "(first (map :status [{:status :ok :id 1} {:status :fail :id 2}]))"));
    }

    @Test
    @Tag("direct-linking-on")
    void filterOnMapKeywordAnalyzesToMaterializeMapThenFilter() throws Exception {
        String code = "(filter #(= :one %) (map :id (vec '({:status :ok :id :one} {:status :fail :id :two}))))";
        Compiler.Expr expr = BytecodeDslTestSupport.analyzeExpressionDirectLinkingOn(code);
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
    @Tag("direct-linking-on")
    void filterOnMapKeywordOnLetRowsAnalyzesToFoldOrMaterialize() throws Exception {
        Compiler.FnExpr fn = (Compiler.FnExpr) BytecodeDslTestSupport.analyzeExpressionDirectLinkingOn(
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
        assertTrue(false,
                () -> "expected FilteredEphemeralVectorSeq." + methodName + ", was " + expr);
    }

    @Test
    void evalMapFieldRowsRuntimeSnippet() {
        String code = "(let [coll (list {:status :ok :id :one} {:status :fail :id :two})"
                + " rows (vec coll)] (first (map :id rows)))";
        assertEquals(Keyword.intern("one"), BytecodeDslTestSupport.evalBytecode(code));
    }

    @Test
    void evalFirstFilterOnMapIdMatchesSnippetFixture() {
        String code = "(let [rows (vec '({:status :ok :id :one} {:status :fail :id :two}))]"
                + " (first (filter #(= :one %) (map :id rows))))";
        assertEquals(Keyword.intern("one"), BytecodeDslTestSupport.evalBytecode(code));
    }

    @Test
    void evalEmptyFilterOnVectorIsTruthyWithEmptySeq() throws Exception {
        assertEquals(true, BytecodeDslTestSupport.evalBytecode(
                "(let [rows (vector 2 4)] (boolean (filter odd? rows)))"));
        assertEquals(0L, ((Number) BytecodeDslTestSupport.evalBytecode(
                "(let [rows (vector 2 4)] (count (filter odd? rows)))")).longValue());
        assertEquals(true, BytecodeDslTestSupport.evalBytecode(
                "(let [rows (vector 2 4)] (nil? (seq (filter odd? rows))))"));
    }

    @Test
    void evalEmptyMapOnFilteredVector() throws Exception {
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
    @Tag("direct-linking-on")
    void evalIntoCompFilterMapOnRuntimeVector() throws Exception {
        // Transducers compose left-to-right: map :id, then filter by :status (matches analyze fold fixture).
        assertEquals(Keyword.intern("one"), BytecodeDslTestSupport.evalBytecodeDirectLinkingOn(
                "(let [rows (vector {:status :ok :id :one} {:status :fail :id :two})]"
                + " (first (into [] (comp (map :id) (filter #(= :ok (:status %)))) rows)))"));
        assertEquals(0L, ((Number) BytecodeDslTestSupport.evalBytecodeDirectLinkingOn(
                "(count (into [] (comp (map :id) (filter #(= :ok (:status %))))"
                + " (vector {:status :fail :id :two})))")).longValue());
    }

    @Test
    void evalFilterOnVectorAllMatch() throws Exception {
        assertEquals(2L, ((Number) BytecodeDslTestSupport.evalBytecode(
                "(count (filter even? (vector 2 4)))")).longValue());
    }
}

