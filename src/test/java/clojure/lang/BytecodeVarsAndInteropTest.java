package clojure.lang;

import net.javacrumbs.cloffle.bytecode.ExprToBytecode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@code def}, {@code var}, dynamic vars, {@code set!} on vars and fields, Java interop
 * ({@code new}, static/instance methods and fields, {@code import*}), {@code reify*}/{@code deftype*},
 * and analyzer placeholders ({@link Compiler.UnresolvedVarExpr}).
 * <p>
 * No {@code clojure.core} load — forms limited to what {@link Compiler#analyze} handles natively.
 * <p>
 * Package {@code clojure.lang} for access to {@link Compiler} internals.
 * Helpers: {@link BytecodeDslTestSupport}.
 */
public class BytecodeVarsAndInteropTest {

    /** Public static field for {@link #setBangOnStaticField}. */
    public static int mutableStatic = 0;

    // --- def / var / dynamic vars ---

    @Test
    public void evalBytecodeThreadBindsCurrentNsForDeref() {
        Object ns =
                BytecodeDslTestSupport.evalBytecode("(.deref clojure.lang.RT/CURRENT_NS)");
        assertTrue(ns instanceof clojure.lang.Namespace);
        assertEquals(
                "clojure.core",
                ((clojure.lang.Namespace) ns).getName().toString());
    }

    @Test
    public void varPushThreadBindingsThreadLocalRead() {
        String sym = "expr_to_bytecode_dyn_" + System.nanoTime();
        String code =
                "(let* [v (clojure.lang.Var/intern (clojure.lang.Namespace/findOrCreate (clojure.lang.Symbol/intern nil \"user\")) (clojure.lang.Symbol/intern nil \""
                        + sym
                        + "\"))] "
                        + "(do (.bindRoot (.setDynamic v) 0) "
                        + "(. clojure.lang.Var (pushThreadBindings (clojure.lang.PersistentHashMap/create (clojure.lang.RT/list v 42)))) "
                        + "(try (.deref v) (finally (. clojure.lang.Var (popThreadBindings))))))";
        assertEquals(42L, BytecodeDslTestSupport.evalBytecode(code));
    }

    @Test
    public void emptyLetStarBindingMacroShapePushPopThreadBindings() {
        String sym = "expr_to_bytecode_bind_shape_" + System.nanoTime();
        String code =
                "(let* [v (clojure.lang.Var/intern (clojure.lang.Namespace/findOrCreate (clojure.lang.Symbol/intern nil \"user\")) (clojure.lang.Symbol/intern nil \""
                        + sym
                        + "\"))] "
                        + "(do (.bindRoot (.setDynamic v) 0) "
                        + "(let* [] "
                        + "(. clojure.lang.Var (pushThreadBindings (clojure.lang.PersistentHashMap/create (clojure.lang.RT/list v 42)))) "
                        + "(try (.deref v) (finally (. clojure.lang.Var (popThreadBindings)))))))";
        assertEquals(42L, BytecodeDslTestSupport.evalBytecode(code));
    }

    @Test
    public void varSetBangThreadBoundThenPopRestoresRoot() {
        String sym = "expr_to_bytecode_setbang_" + System.nanoTime();
        String code =
                "(do (def ^:dynamic "
                        + sym
                        + " 0) "
                        + "(.bindRoot (.setDynamic (var "
                        + sym
                        + ")) 0) "
                        + "(. clojure.lang.Var (pushThreadBindings (clojure.lang.PersistentHashMap/create (clojure.lang.RT/list (var "
                        + sym
                        + ") 42)))) "
                        + "(let* [during (try (do (set! "
                        + sym
                        + " 99) "
                        + sym
                        + ") (finally (. clojure.lang.Var (popThreadBindings))))] "
                        + "(clojure.lang.PersistentVector/create (clojure.lang.RT/list during "
                        + sym
                        + "))))";
        Object v = BytecodeDslTestSupport.evalBytecode(code);
        assertTrue(v instanceof IPersistentVector);
        IPersistentVector vec = (IPersistentVector) v;
        assertEquals(2, vec.count());
        assertEquals(99L, vec.nth(0));
        assertEquals(0L, vec.nth(1));
    }

    @Test
    public void defBindsRootAndSymbolReadsVar() {
        String sym = "expr_to_bytecode__def_test_" + System.nanoTime();
        String code = "(do (def " + sym + " 77) " + sym + ")";
        assertEquals(77L, BytecodeDslTestSupport.evalBytecode(code));
    }

    @Test
    public void defFnStarThenInvokeByName() {
        String sym = "expr_to_bytecode_corefn_" + System.nanoTime();
        assertEquals(
                42L,
                BytecodeDslTestSupport.evalBytecode(
                        "(do (def " + sym + " (fn* [n] (clojure.lang.Numbers/add n 1))) (" + sym + " 41))"));
    }

    @Test
    public void defMultiArityFnStarThenInvoke() {
        String sym = "expr_to_bytecode_multi_" + System.nanoTime();
        assertEquals(
                true,
                BytecodeDslTestSupport.evalBytecode(
                        "(do (def " + sym + " (fn* ([x] true) ([x y] (clojure.lang.Util/equiv x y)))) (" + sym + " 1 1))"));
    }

    @Test
    public void theVarSpecialForm() {
        String sym = "expr_to_bytecode__var_test_" + System.nanoTime();
        String defCode = "(def " + sym + " 88)";
        BytecodeDslTestSupport.evalBytecode(defCode);
        Var v = (Var) BytecodeDslTestSupport.evalBytecode("(var " + sym + ")");
        assertEquals(88L, v.get());
    }

    // --- set! ---

    @Test
    public void setBangOnStaticField() {
        BytecodeVarsAndInteropTest.mutableStatic = 0;
        assertEquals(
                9L,
                BytecodeDslTestSupport.evalBytecode("(set! clojure.lang.BytecodeVarsAndInteropTest/mutableStatic 9)"));
        assertEquals(9, BytecodeVarsAndInteropTest.mutableStatic);
    }

    @Test
    public void setBangOnInstanceField() {
        Object v =
                BytecodeDslTestSupport.evalBytecode("(let* [p (new java.awt.Point 1 2)] (set! (.x p) 42) (.x p))");
        assertEquals(42, ((Number) v).intValue());
    }

    // --- Java interop ---

    @Test
    public void javaStaticMethodCall() {
        assertEquals(99L, BytecodeDslTestSupport.evalBytecode("(Long/valueOf 99)"));
    }

    @Test
    public void importStarSpecialFormBindsShortClassName() {
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
    public void javaStaticField() {
        assertEquals(Long.MAX_VALUE, BytecodeDslTestSupport.evalBytecode("Long/MAX_VALUE"));
    }

    @Test
    public void javaMathAbsLong() {
        assertEquals(9L, BytecodeDslTestSupport.evalBytecode("(java.lang.Math/abs -9)"));
    }

    @Test
    public void rtFirstNextOnList() {
        assertEquals(
                2L,
                BytecodeDslTestSupport.evalBytecode(
                        "(clojure.lang.RT/first (clojure.lang.RT/next (clojure.lang.RT/seq (clojure.lang.RT/list 1 2 3))))"));
    }

    @Test
    public void instanceOfJavaLangNumber() {
        assertSame(RT.T, BytecodeDslTestSupport.evalBytecode("(instance? java.lang.Number 5)"));
        assertSame(RT.F, BytecodeDslTestSupport.evalBytecode("(instance? java.lang.Number \"s\")"));
    }

    // --- reify* / deftype* ---

    @Test
    public void reifyStarRunnableNoCloses() {
        Object r =
                BytecodeDslTestSupport.evalBytecode("(reify* [java.lang.Runnable] (run [this] nil))");
        assertTrue(r instanceof Runnable);
        ((Runnable) r).run();
    }

    @Test
    public void reifyStarCallableClosesOverLocal() {
        assertEquals(
                42L,
                BytecodeDslTestSupport.evalBytecode(
                        "(let* [x 42] (let* [c (reify* [java.util.concurrent.Callable] (call [this] x))] (. c (call))))"));
    }

    @Test
    public void deftypeStarExpressionIsNull() {
        assertNull(
                BytecodeDslTestSupport.evalBytecode(
                        "(deftype* ExprToBytecodeDeftypeMvp expr_to_bytecode_deftype_mvp [a b] :implements [clojure.lang.Seqable] (seq [this] nil))"));
    }

    // --- Analyzer placeholders ---

    @Test
    public void unresolvedVarExprThrowsSameMessageAsCompilerEval() {
        com.oracle.truffle.api.source.Source source =
                com.oracle.truffle.api.source.Source.newBuilder("cloffle", "x", "unresolved.clj").build();
        ExprToBytecode conv = new ExprToBytecode(null, source);
        Compiler.UnresolvedVarExpr uve = new Compiler.UnresolvedVarExpr(Symbol.intern(null, "no.such/var"));
        try {
            conv.convertRoot(uve, "root");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            assertEquals("UnresolvedVarExpr cannot be evalled", ex.getMessage());
        }
    }
}
