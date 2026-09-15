package clojure.lang;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct-linking vs {@code clojure.core/vector}: Cloffle bytecode vs stock {@code clojure.jar} compile.
 * <p>
 * Stock lowers {@code vector} through the var's {@code invokeStatic} arities, not multi-arg {@code RT/vector}
 * host interop (which stock does not compile). Cloffle should match that for {@code vector}; spread
 * {@code RT/vector} is a separate Cloffle host-interop extension ({@link RtSpreadVarargsStaticMethodTest}).
 */
public class DirectLinkingVectorCompatTest {

    private static final String STOCK_NS = "dl.vector.compat.stock";
    private static final String CLOFFLE_FIVE =
            "(first (map identity (vector :one :two :three :four :five)))";

    @BeforeAll
    static void initCore() {
        RT.init();
    }

    private static String vectorForm(int arity) {
        StringBuilder sb = new StringBuilder("(vector");
        for (int i = 1; i <= arity; i++) {
            sb.append(' ').append(i);
        }
        sb.append(')');
        return sb.toString();
    }

    private static String stockDefn(String body) {
        return "(ns " + STOCK_NS + ")\n(defn run [] " + body + ")\n";
    }

    private static boolean mentionsRtVectorStatic(Compiler.Expr expr) {
        if (expr instanceof Compiler.StaticMethodExpr sm
                && sm.c == RT.class
                && "vector".equals(sm.methodName)) {
            return true;
        }
        if (expr instanceof Compiler.InvokeExpr ie) {
            for (int i = 0; i < ie.args.count(); i++) {
                if (mentionsRtVectorStatic((Compiler.Expr) ie.args.nth(i))) {
                    return true;
                }
            }
        }
        if (expr instanceof Compiler.StaticMethodExpr sm) {
            for (int i = 0; i < sm.args.count(); i++) {
                if (mentionsRtVectorStatic((Compiler.Expr) sm.args.nth(i))) {
                    return true;
                }
            }
        }
        if (expr instanceof Compiler.LetExpr let) {
            for (int i = 0; i < let.bindingInits.count(); i++) {
                Object pair = let.bindingInits.nth(i);
                if (mentionsRtVectorStatic((Compiler.Expr) RT.second(pair))) {
                    return true;
                }
            }
            if (let.body != null && mentionsRtVectorStatic(let.body)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @Tag("direct-linking-off")
    void cloffleVectorArityFiveEvalDirectLinkingOff() throws Exception {
        assertEquals(5L, RT.count(BytecodeDslTestSupport.evalBytecodeDirectLinkingOff(vectorForm(5))));
    }

    @Test
    @Tag("direct-linking-on")
    void cloffleVectorArityFiveEvalDirectLinkingOn() throws Exception {
        assertEquals(5L, RT.count(BytecodeDslTestSupport.evalBytecodeDirectLinkingOn(vectorForm(5))));
    }

    @Test
    @Tag("direct-linking-on")
    void cloffleMapIdentityFiveKeywordsBenchShapeDirectLinkingOn() throws Exception {
        assertEquals(Keyword.intern("one"),
                BytecodeDslTestSupport.evalBytecodeDirectLinkingOn(CLOFFLE_FIVE));
    }

    @Test
    @Tag("direct-linking-on")
    void cloffleVectorArityFiveAnalyzeDoesNotLowerToRtVectorStatic() throws Exception {
        Compiler.Expr expr =
                BytecodeDslTestSupport.analyzeExpressionDirectLinkingOn(vectorForm(5));
        assertFalse(mentionsRtVectorStatic(expr),
                () -> "vector must not analyze to RT.vector static interop under :direct-linking");
    }

    @Test
    @Tag("direct-linking-on")
    void cloffleVectorArityTwoStillConstantFoldsWhenLiteral() throws Exception {
        Compiler.Expr expr =
                BytecodeDslTestSupport.analyzeExpressionDirectLinkingOn("(map identity (vector 1 2))");
        assertInstanceOf(Compiler.ConstantVectorExpr.class, expr);
    }

    @Test
    @Tag("direct-linking-off")
    void stockVectorArityFiveCompilesDirectLinkingOff() throws Exception {
        StockClojureCompileSupport.compileSource(stockDefn(vectorForm(5)), false);
    }

    @Test
    @Tag("direct-linking-on")
    void stockVectorArityFiveCompilesDirectLinkingOn() throws Exception {
        StockClojureCompileSupport.compileSource(stockDefn(vectorForm(5)), true);
    }

    @Test
    @Tag("direct-linking-on")
    void stockMapIdentityFiveKeywordsCompilesDirectLinkingOn() throws Exception {
        StockClojureCompileSupport.compileSource(stockDefn(CLOFFLE_FIVE), true);
    }

    @Test
    void stockMultiArgRtVectorStillFailsCompile() {
        String src = stockDefn("(clojure.lang.RT/count (clojure.lang.RT/vector :a :b :c :d))");
        Exception ex = assertThrows(Exception.class,
                () -> StockClojureCompileSupport.compileSource(src, false));
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        assertTrue(msg != null && msg.contains("vector"),
                "expected RT/vector arity error, got: " + msg);
    }

    @Test
    void cloffleMultiArgRtVectorEvalMatchesStockCountLiteral() throws Exception {
        Object viaRt = BytecodeDslTestSupport.evalBytecodeDirectLinkingOff(
                "(clojure.lang.RT/count (clojure.lang.RT/vector :a :b :c :d))");
        Object viaLit = BytecodeDslTestSupport.evalBytecodeDirectLinkingOff(
                "(clojure.lang.RT/count [:a :b :c :d])");
        assertEquals(viaLit, viaRt);
    }

    @Test
    @Tag("direct-linking-on")
    void cloffleVectorAritySevenEvalDirectLinkingOn() throws Exception {
        assertEquals(7L, RT.count(BytecodeDslTestSupport.evalBytecodeDirectLinkingOn(vectorForm(7))));
    }

    @Test
    @Tag("direct-linking-on")
    void stockVectorAritySevenCompilesDirectLinkingOn() throws Exception {
        StockClojureCompileSupport.compileSource(stockDefn(vectorForm(7)), true);
    }
}
