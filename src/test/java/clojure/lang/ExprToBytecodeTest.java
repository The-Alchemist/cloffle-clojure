package clojure.lang;

import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import org.junit.Test;

import java.io.StringReader;
import java.math.BigInteger;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link ExprToBytecode} / {@link CloffleBytecodeRootNode} tests with <b>no</b> {@code clojure.core}
 * load and <b>no</b> Cloffle AST execution ({@link net.javacrumbs.cloffle.compiler.CloffleCompiler}).
 * <p>
 * Forms are limited to what {@link Compiler#macroexpand} and {@link Compiler#analyze} can handle
 * without core-provided macros or vars — e.g. literals, {@code if}, {@code do}, {@code quote}, {@code try},
 * {@code fn*} (not the {@code fn} macro), {@code let*}, {@code def}, {@code var}, {@code case*} (not the
 * {@code case} macro), Java interop, and collection literals whose elements need no core.
 * <p>
 * Package {@code clojure.lang} for access to {@link Compiler#macroexpand} and {@link Compiler.Expr}.
 * {@code loop}/{@code recur} omitted: backward branches unsupported (see {@code CLOFFLE_TRUFFLE_BYTECODE.md}).
 * <p>
 * Helpers: {@link BytecodeDslTestSupport}. Source sections and {@code Source} serialization:
 * {@link ExprToBytecodeSourceLocationTest}.
 */
public class ExprToBytecodeTest {

    /** Public static field for {@link #setBangOnStaticField} (Java interop {@code set!}). */
    public static int bytecodeTestMutableStatic = 0;

    @Test
    public void nilConstant() {
        assertNull(BytecodeDslTestSupport.evalBytecode("nil"));
    }

    @Test
    public void longConstant() {
        assertEquals(42L, BytecodeDslTestSupport.evalBytecode("42"));
    }

    @Test
    public void keywordConstant() {
        Object k = BytecodeDslTestSupport.evalBytecode(":hello/bytecode");
        assertTrue(k instanceof Keyword);
        assertEquals("hello", ((Keyword) k).getNamespace());
        assertEquals("bytecode", ((Keyword) k).getName());
    }

    @Test
    public void stringConstant() {
        assertEquals("truffle", BytecodeDslTestSupport.evalBytecode("\"truffle\""));
    }

    @Test
    public void booleanConstants() {
        assertSame(RT.T, BytecodeDslTestSupport.evalBytecode("true"));
        assertSame(RT.F, BytecodeDslTestSupport.evalBytecode("false"));
    }

    @Test
    public void emptyVectorConstant() {
        Object v = BytecodeDslTestSupport.evalBytecode("[]");
        assertTrue(v instanceof IPersistentVector);
        assertTrue(((IPersistentVector) v).count() == 0);
    }

    @Test
    public void ifWithTruthiness() {
        assertEquals(1L, BytecodeDslTestSupport.evalBytecode("(if true 1 2)"));
        assertEquals(2L, BytecodeDslTestSupport.evalBytecode("(if false 1 2)"));
        assertEquals(1L, BytecodeDslTestSupport.evalBytecode("(if :x 1 2)"));
        assertEquals(2L, BytecodeDslTestSupport.evalBytecode("(if nil 1 2)"));
    }

    @Test
    public void nestedIf() {
        assertEquals(2L, BytecodeDslTestSupport.evalBytecode("(if true (if false 1 2) 3)"));
        assertEquals(3L, BytecodeDslTestSupport.evalBytecode("(if false (if true 1 2) 3)"));
    }

    @Test
    public void doReturnsLastValue() {
        assertEquals(3L, BytecodeDslTestSupport.evalBytecode("(do 1 2 3)"));
        assertNull(BytecodeDslTestSupport.evalBytecode("(do nil)"));
    }

    @Test
    public void doubleConstant() {
        assertEquals(3.14, (Double) BytecodeDslTestSupport.evalBytecode("3.14"), 0.0);
    }

    @Test
    public void characterConstant() {
        assertEquals(Character.valueOf('z'), BytecodeDslTestSupport.evalBytecode("\\z"));
    }

    @Test
    public void quotedList() {
        Object x = BytecodeDslTestSupport.evalBytecode("(quote (1 2 3))");
        assertTrue(x instanceof ISeq);
        ISeq s = (ISeq) x;
        assertEquals(1L, s.first());
        assertEquals(2L, RT.second(s));
        assertEquals(3L, RT.third(s));
    }

    @Test
    public void quotedSymbol() {
        Object x = BytecodeDslTestSupport.evalBytecode("(quote abcd)");
        assertTrue(x instanceof Symbol);
        assertEquals("abcd", ((Symbol) x).getName());
    }

    @Test
    public void ratioConstant() {
        Object r = BytecodeDslTestSupport.evalBytecode("1/2");
        assertTrue(r instanceof Ratio);
        assertEquals(BigInteger.ONE, ((Ratio) r).numerator);
        assertEquals(BigInteger.TWO, ((Ratio) r).denominator);
    }

    @Test
    public void emptyMapAndSetLiterals() {
        Object m = BytecodeDslTestSupport.evalBytecode("{}");
        assertTrue(m instanceof IPersistentMap);
        assertEquals(0, ((IPersistentMap) m).count());
        Object st = BytecodeDslTestSupport.evalBytecode("#{}");
        assertTrue(st instanceof IPersistentSet);
        assertEquals(0, ((IPersistentSet) st).count());
    }

    @Test
    public void setLiteralWithoutCoreFns() {
        Object st = BytecodeDslTestSupport.evalBytecode("#{1 2 3}");
        assertTrue(st instanceof IPersistentSet);
        IPersistentSet set = (IPersistentSet) st;
        assertEquals(3, set.count());
        assertTrue(set.contains(1L));
        assertTrue(set.contains(2L));
        assertTrue(set.contains(3L));
    }

    @Test
    public void vectorLiteralWithoutCoreFns() {
        Object v = BytecodeDslTestSupport.evalBytecode("[1 2 3]");
        assertTrue(v instanceof IPersistentVector);
        IPersistentVector vec = (IPersistentVector) v;
        assertEquals(3, vec.count());
        assertEquals(1L, vec.nth(0));
        assertEquals(2L, vec.nth(1));
        assertEquals(3L, vec.nth(2));
    }

    @Test
    public void mapLiteralWithoutCoreFns() {
        Object m = BytecodeDslTestSupport.evalBytecode("{:a 1 :b 2}");
        assertTrue(m instanceof IPersistentMap);
        IPersistentMap map = (IPersistentMap) m;
        assertEquals(2, map.count());
        assertEquals(1L, map.valAt(Keyword.intern("a")));
        assertEquals(2L, map.valAt(Keyword.intern("b")));
    }

    @Test
    public void keywordInvokeOnMapLiteral() {
        assertEquals(1L, BytecodeDslTestSupport.evalBytecode("(:a {:a 1 :b 2})"));
        assertEquals(2L, BytecodeDslTestSupport.evalBytecode("(:b {:a 1 :b 2})"));
    }

    @Test
    public void keywordInvokeWithExpressionTarget() {
        assertEquals(7L, BytecodeDslTestSupport.evalBytecode("(let* [m {:x 7}] (:x m))"));
    }

    @Test
    public void nestedKeywordInvokeOnMapLiterals() {
        assertEquals(9L, BytecodeDslTestSupport.evalBytecode("(:b (:a {:a {:b 9}}))"));
    }

    /**
     * {@code case*} special form (no {@code clojure.core} {@code case} macro). Map shape matches
     * {@link Compiler.CaseExpr.Parser}: {@code {dispatch-int [test-constant then] ...}}.
     */
    @Test
    public void caseStarIntCompactDispatches() {
        String k = "(let* [x %s] (case* x 0 0 :none {1 [1 :a] 2 [2 :b]} :compact :int))";
        assertEquals(Keyword.intern(null, "a"), BytecodeDslTestSupport.evalBytecode(String.format(k, "1")));
        assertEquals(Keyword.intern(null, "b"), BytecodeDslTestSupport.evalBytecode(String.format(k, "2")));
        assertEquals(Keyword.intern(null, "none"), BytecodeDslTestSupport.evalBytecode(String.format(k, "99")));
    }

    @Test
    public void letStarThreeBindings() {
        assertEquals(3L, BytecodeDslTestSupport.evalBytecode("(let* [a 1 b 2 c 3] c)"));
        assertEquals(2L, BytecodeDslTestSupport.evalBytecode("(let* [a 1 b 2 c 3] b)"));
    }

    @Test
    public void fnStarBodyWithDo() {
        String f = "(fn* ([] (do 1 2 99)))";
        assertEquals(99L, BytecodeDslTestSupport.evalBytecode("(" + f + ")"));
    }

    @Test
    public void quotedEmptyList() {
        Object x = BytecodeDslTestSupport.evalBytecode("(quote ())");
        assertTrue(x instanceof IPersistentCollection);
        assertEquals(0, ((IPersistentCollection) x).count());
    }

    @Test
    public void tryCatchReturnsTryBodyWhenNoThrow() {
        assertEquals(7L, BytecodeDslTestSupport.evalBytecode("(try 7 (catch Throwable t 0))"));
    }

    @Test
    public void tryFinallyRunsAndReturnsBody() {
        assertEquals(1L, BytecodeDslTestSupport.evalBytecode("(try 1 (finally nil))"));
    }

    @Test
    public void fnStarZeroArityInvoke() {
        // `fn` is a core macro; `fn*` is the special form (no clojure.core).
        assertEquals(42L, BytecodeDslTestSupport.evalBytecode("((fn* ([] 42)))"));
    }

    @Test
    public void javaStaticMethodCall() {
        assertEquals(99L, BytecodeDslTestSupport.evalBytecode("(Long/valueOf 99)"));
    }

    @Test
    public void importStarSpecialFormBindsShortClassName() {
        // Import runs at eval time; `new` with a short name must be analyzed after the namespace
        // mapping exists — not in the same `do` as the import (analyze resolves classes before eval).
        BytecodeDslTestSupport.evalBytecode("(clojure.core/import* \"java.util.concurrent.atomic.AtomicInteger\")");
        Object x = BytecodeDslTestSupport.evalBytecode("(new AtomicInteger 7)");
        assertTrue(x instanceof java.util.concurrent.atomic.AtomicInteger);
        assertEquals(7, ((java.util.concurrent.atomic.AtomicInteger) x).get());
    }

    @Test
    public void qualifiedMethodSymbolAsValueIsIFnThunk() {
        assertEquals(99L, BytecodeDslTestSupport.evalBytecode("(let* [f Long/valueOf] (f 99))"));
    }

    @Test
    public void javaInstanceMethodCall() {
        // Reflector returns int boxed as Integer for .length
        assertEquals(Integer.valueOf(3), BytecodeDslTestSupport.evalBytecode("(.length \"abc\")"));
    }

    @Test
    public void javaNewAndInstanceOf() {
        Object s = BytecodeDslTestSupport.evalBytecode("(new String \"hi\")");
        assertTrue(s instanceof String);
        assertEquals("hi", s);
        assertSame(RT.T, BytecodeDslTestSupport.evalBytecode("(instance? String \"a\")"));
        assertSame(RT.F, BytecodeDslTestSupport.evalBytecode("(instance? String 1)"));
    }

    @Test
    public void letStarBindsLocals() {
        // `let` is a core macro; `let*` is the special form.
        assertEquals(1L, BytecodeDslTestSupport.evalBytecode("(let* [a 1] a)"));
        // Later bindings see earlier locals: b uses a's value (both 1).
        assertEquals(1L, BytecodeDslTestSupport.evalBytecode("(let* [a 1 b a] b)"));
        assertEquals(2L, BytecodeDslTestSupport.evalBytecode("(let* [a 1 b 2] b)"));
    }

    @Test
    public void fnStarUnaryInvoke() {
        assertEquals(99L, BytecodeDslTestSupport.evalBytecode("((fn* ([x] x)) 99)"));
    }

    @Test
    public void multiArityFnWithoutOuterCallReturnsIFn() {
        Object f = BytecodeDslTestSupport.evalBytecode("(fn* ([] 10) ([x] x) ([x y] y))");
        assertTrue("multi-arity fn* should compile to IFn, got " + (f == null ? "null" : f.getClass()),
                f instanceof IFn);
    }

    @Test
    public void multiArityFnExprHasThreeMethods() throws Exception {
        String code = "((fn* ([] 10) ([x] x) ([x y] y)))";
        Object form = LispReader.read(
                new LineNumberingPushbackReader(new StringReader(code)), false, null, false, null);
        Compiler.Expr expr = Compiler.analyze(Compiler.C.EVAL, Compiler.macroexpand(form));
        assertTrue(expr instanceof Compiler.InvokeExpr);
        Compiler.Expr fexpr = ((Compiler.InvokeExpr) expr).fexpr;
        assertTrue(fexpr instanceof Compiler.FnExpr);
        assertEquals(3, ((Compiler.FnExpr) fexpr).methods().count());
    }

    @Test
    public void fnStarMultiArityDirectInvoke() {
        String f = "(fn* ([] 10) ([x] x) ([x y] y))";
        // f is already wrapped in parens as a fn* form; only one outer paren for invoke — not "((" + f + "))"
        // which would read as (((fn* ...))) and analyze to a different invoke shape.
        assertEquals(10L, BytecodeDslTestSupport.evalBytecode("(" + f + ")"));
        assertEquals(5L, BytecodeDslTestSupport.evalBytecode("(" + f + " 5)"));
        assertEquals(2L, BytecodeDslTestSupport.evalBytecode("(" + f + " 1 2)"));
    }

    @Test
    public void fnStarMultiArityDispatchViaLetStarAndSymbolInvoke() {
        String f = "(fn* ([] 10) ([x] x) ([x y] y))";
        assertEquals(10L, BytecodeDslTestSupport.evalBytecode("(let* [f " + f + "] (f))"));
        assertEquals(5L, BytecodeDslTestSupport.evalBytecode("(let* [f " + f + "] (f 5))"));
        assertEquals(2L, BytecodeDslTestSupport.evalBytecode("(let* [f " + f + "] (f 1 2))"));
    }

    @Test
    public void fnStarRestArgs() {
        Object seq = BytecodeDslTestSupport.evalBytecode("((fn* ([x & rest] rest)) 1 2 3)");
        assertTrue(seq instanceof ISeq);
        assertEquals(2L, ((ISeq) seq).first());
        assertEquals(3L, RT.second((ISeq) seq));
    }

    @Test
    public void letStarClosureCapturesLocal() {
        assertEquals(7L, BytecodeDslTestSupport.evalBytecode("(let* [n 7] ((fn* [] n)))"));
    }

    @Test
    public void tryCatchFinallyWhenNoThrow() {
        assertEquals(5L, BytecodeDslTestSupport.evalBytecode("(try 5 (catch Throwable t 0) (finally nil))"));
    }

    @Test
    public void bigintLiteral() {
        Object n = BytecodeDslTestSupport.evalBytecode("10000000000000000000N");
        assertTrue(n instanceof BigInt);
        assertEquals(new BigInteger("10000000000000000000"), ((BigInt) n).toBigInteger());
    }

    @Test
    public void regexLiteral() {
        Object p = BytecodeDslTestSupport.evalBytecode("#\"a+\"");
        assertTrue(p instanceof Pattern);
        assertTrue(((Pattern) p).matcher("aaa").matches());
    }

    @Test
    public void throwCaughtInTry() {
        Object v = BytecodeDslTestSupport.evalBytecode(
                "(try (throw (new Exception \"boom\")) (catch Exception e :caught))");
        assertEquals(Keyword.intern("caught"), v);
    }

    @Test
    public void javaStaticField() {
        assertEquals(Long.MAX_VALUE, BytecodeDslTestSupport.evalBytecode("Long/MAX_VALUE"));
    }

    @Test
    public void defBindsRootAndSymbolReadsVar() {
        String sym = "expr_to_bytecode__def_test_" + System.nanoTime();
        String code = "(do (def " + sym + " 77) " + sym + ")";
        assertEquals(77L, BytecodeDslTestSupport.evalBytecode(code));
    }

    @Test
    public void setBangOnStaticField() {
        bytecodeTestMutableStatic = 0;
        assertEquals(
                9L,
                BytecodeDslTestSupport.evalBytecode("(set! clojure.lang.ExprToBytecodeTest/bytecodeTestMutableStatic 9)"));
        assertEquals(9, bytecodeTestMutableStatic);
    }

    @Test
    public void setBangOnInstanceField() {
        Object v =
                BytecodeDslTestSupport.evalBytecode("(let* [p (new java.awt.Point 1 2)] (set! (.x p) 42) (.x p))");
        assertEquals(42, ((Number) v).intValue());
    }

    @Test
    public void theVarSpecialForm() {
        String sym = "expr_to_bytecode__var_test_" + System.nanoTime();
        String defCode = "(def " + sym + " 88)";
        BytecodeDslTestSupport.evalBytecode(defCode);
        Var v = (Var) BytecodeDslTestSupport.evalBytecode("(var " + sym + ")");
        assertEquals(88L, v.get());
    }

    @Test
    public void vectorWithMetadata() {
        Object v = BytecodeDslTestSupport.evalBytecode("^{:x 1} [1 2]");
        assertTrue(v instanceof IPersistentVector);
        IPersistentVector vec = (IPersistentVector) v;
        assertEquals(2, vec.count());
        Object meta = RT.meta(vec);
        assertNotNull(meta);
        assertEquals(1L, RT.get(meta, Keyword.intern("x")));
    }

    @Test
    public void rootNodeNameIsSet() throws Exception {
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot("(if true 3 4)");
        assertEquals("namedRoot", root.getName());
    }
}
