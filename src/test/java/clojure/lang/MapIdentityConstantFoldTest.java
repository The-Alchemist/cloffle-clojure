package clojure.lang;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MapIdentityConstantFoldTest {

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
    public void mapIdentityOnLiteralVectorAnalyzesToConstantVector() {
        Compiler.Expr expr = analyze("(map identity [:one])");
        assertTrue("expected ConstantVectorExpr, was " + expr.getClass().getName(),
                expr instanceof Compiler.ConstantVectorExpr);
        assertEquals(RT.vector(Keyword.intern("one")), ((Compiler.ConstantVectorExpr) expr).val);
    }

    @Test
    public void firstMapIdentityOnLiteralVectorDoesNotAnalyzeToMapInvoke() {
        Compiler.Expr expr = analyze("(first (map identity [:one]))");
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
        assertTrue("map should fold to ConstantVectorExpr, was " + collArg.getClass().getName(),
                collArg instanceof Compiler.ConstantVectorExpr);
    }

    @Test
    public void mapIdentityOnNonIdentityFnDoesNotFold() {
        Compiler.Expr expr = analyze("(map inc [:one])");
        assertFalse(expr instanceof Compiler.ConstantVectorExpr);
        assertTrue(expr instanceof Compiler.InvokeExpr);
    }

    @Test
    public void evalFirstMapIdentityOneKeyword() {
        assertEquals(Keyword.intern("one"),
                BytecodeDslTestSupport.evalBytecode("(first (map identity [:one]))"));
    }

    @Test
    public void evalMapIdentityPreservesVector() {
        assertEquals(RT.vector(Keyword.intern("a"), Keyword.intern("b")),
                BytecodeDslTestSupport.evalBytecode("(map identity [:a :b])"));
    }
}
