package clojure.lang;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class MapIdentityConstantFoldTest {

    @BeforeAll
    static void initCore() {
        RT.init();
    }

    @Test
    @Tag("direct-linking-off")
    void mapIdentityDoesNotConstantFoldWhenLockedFoldsOff() throws Exception {
        Compiler.Expr expr = BytecodeDslTestSupport.analyzeExpressionDirectLinkingOff("(map identity [:one])");
        assertFalse(expr instanceof Compiler.ConstantVectorExpr);
        assertTrue(expr instanceof Compiler.InvokeExpr);
    }

    @Test
    @Tag("direct-linking-on")
    void mapIdentityOnLiteralVectorAnalyzesToConstantVector() throws Exception {
        Compiler.Expr expr = BytecodeDslTestSupport.analyzeExpressionDirectLinkingOn("(map identity [:one])");
        assertTrue(expr instanceof Compiler.ConstantVectorExpr,
                () -> "expected ConstantVectorExpr, was " + expr.getClass().getName());
        assertEquals(RT.vector(Keyword.intern("one")), ((Compiler.ConstantVectorExpr) expr).val);
    }

    @Test
    @Tag("direct-linking-on")
    void firstMapIdentityOnLiteralVectorDoesNotAnalyzeToMapInvoke() throws Exception {
        Compiler.Expr expr =
                BytecodeDslTestSupport.analyzeExpressionDirectLinkingOn("(first (map identity [:one]))");
        Compiler.Expr collArg;
        if (expr instanceof Compiler.StaticMethodExpr sm) {
            assertEquals("first", sm.methodName);
            collArg = (Compiler.Expr) sm.args.nth(0);
        } else if (expr instanceof Compiler.InvokeExpr ie) {
            assertTrue(ie.fexpr instanceof Compiler.VarExpr);
            assertEquals("first", ((Compiler.VarExpr) ie.fexpr).var.sym.toString());
            collArg = (Compiler.Expr) ie.args.nth(0);
        } else {
            fail("expected StaticMethodExpr or InvokeExpr for first, was " + expr.getClass().getName());
            return;
        }
        assertTrue(collArg instanceof Compiler.ConstantVectorExpr,
                () -> "map should fold to ConstantVectorExpr, was " + collArg.getClass().getName());
    }

    @Test
    @Tag("direct-linking-on")
    void mapIdentityOnLiteralVectorCallConstantFolds() throws Exception {
        Compiler.Expr expr = BytecodeDslTestSupport.analyzeExpressionDirectLinkingOn("(map identity (vector 1 2))");
        assertTrue(expr instanceof Compiler.ConstantVectorExpr,
                () -> "literal vector call should fold, was " + expr.getClass().getName());
        assertEquals(RT.vector(1L, 2L), ((Compiler.ConstantVectorExpr) expr).val);
    }

    @Test
    @Tag("direct-linking-on")
    void evalMapIdentityVectorCall() throws Exception {
        assertEquals(1L, ((Number) BytecodeDslTestSupport.evalBytecodeDirectLinkingOn(
                "(first (map identity (vector 1 2)))")).longValue());
    }

    @Test
    @Tag("direct-linking-on")
    void mapIdentityOnNonIdentityFnDoesNotFold() throws Exception {
        Compiler.Expr expr = BytecodeDslTestSupport.analyzeExpressionDirectLinkingOn("(map inc [:one])");
        assertFalse(expr instanceof Compiler.ConstantVectorExpr);
        assertTrue(expr instanceof Compiler.InvokeExpr);
    }

    @Test
    @Tag("direct-linking-on")
    void evalFirstMapIdentityOneKeyword() throws Exception {
        assertEquals(Keyword.intern("one"),
                BytecodeDslTestSupport.evalBytecodeDirectLinkingOn("(first (map identity [:one]))"));
    }

    @Test
    @Tag("direct-linking-on")
    void evalMapIdentityPreservesVector() throws Exception {
        assertEquals(RT.vector(Keyword.intern("a"), Keyword.intern("b")),
                BytecodeDslTestSupport.evalBytecodeDirectLinkingOn("(map identity [:a :b])"));
    }
}
