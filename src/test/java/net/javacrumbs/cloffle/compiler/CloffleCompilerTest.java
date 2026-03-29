package net.javacrumbs.cloffle.compiler;

import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import net.javacrumbs.cloffle.nodes.ClojureClosure;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertSame;

public class CloffleCompilerTest {

    @BeforeClass
    public static void setUp() {
        RT.init();
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
    }

    private Object compileAndRun(String code) {
        try {
            // Call the Cloffle compiler entrypoint directly for unit-level behavior tests.
            return CloffleCompiler.compile(new StringReader(code), "test", "test.clj");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testSimpleAddition() {
        Object result = compileAndRun("(+ 1 2)");
        assertEquals(3L, result);
    }

    @Test
    public void testLet() {
        Object result = compileAndRun("(let [a 10 b 20] (+ a b))");
        assertEquals(30L, result);
    }

    @Test
    public void testDef() {
         compileAndRun("(def test-val 42)");
         Var v = RT.var("user", "test-val");
         assertEquals(42L, v.get());
    }

    @Test
    public void testIf() {
        assertEquals(1L, compileAndRun("(if true 1 2)"));
        assertEquals(2L, compileAndRun("(if false 1 2)"));
        assertEquals(1L, compileAndRun("(if :truthy 1 2)"));
        assertEquals(2L, compileAndRun("(if nil 1 2)"));
    }

    @Test
    public void testFn() {
        // Simple fn invocation
        assertEquals(5L, compileAndRun("((fn [x] (+ x 2)) 3)"));
    }

    @Test
    public void testLoopRecur() {
        assertEquals(10L, compileAndRun("(loop [x 0] (if (< x 10) (recur (inc x)) x))"));
    }

    @Test
    public void restoresContextClassLoaderAfterSuccess() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        compileAndRun("(+ 1 2)");
        assertSame(original, Thread.currentThread().getContextClassLoader());
    }

    @Test
    public void restoresContextClassLoaderAfterFailure() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            compileAndRun("(throw (RuntimeException. \"boom\"))");
        } catch (RuntimeException ignored) {
            // expected
        }
        assertSame(original, Thread.currentThread().getContextClassLoader());
    }

    /**
     * Regression: {@code defn} with {@code ^double} param and {@code :inline} must compile via ExprToNode
     * without colliding frame slots between body fn and inliner (see CLOFFLE_NOTES.md).
     * {@code :inline} wins at call sites, so we only assert successful compile here, not body semantics.
     */
    @Test
    public void defnWithDoubleHintAndInlineCompiles() {
        assertNull(
                compileAndRun(
                        "(do (defn slot-inline-regression {:inline (fn [x] `(identity ~x))} [^double x] (Double/isNaN x))"
                                + " nil)"));
    }

    @Test
    public void hintedLongParamCastsRatioLikeClojure() {
        assertEquals(0L, compileAndRun("(do (defn hinted-long [^long x] x) (hinted-long 1/2))"));
    }

    @Test
    public void hintedLongParamRejectsOutOfRangeBigIntLikeClojure() {
        try {
            compileAndRun("(do (defn hinted-long2 [^long x] x) (hinted-long2 9223372036854775808N))");
            fail("Should have thrown");
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            org.junit.Assert.assertTrue(cause instanceof IllegalArgumentException);
            org.junit.Assert.assertTrue(cause.getMessage().contains("Value out of range for long"));
        }
    }

    @Test
    public void hintedDoubleParamCastsNumericTowerValuesLikeClojure() {
        assertEquals(0.5d, ((Number) compileAndRun("(do (defn hinted-double [^double x] x) (hinted-double 1/2))")).doubleValue(), 0.0d);
        assertEquals(9.223372036854776E18d,
                ((Number) compileAndRun("(do (defn hinted-double2 [^double x] x) (hinted-double2 9223372036854775808N))")).doubleValue(),
                0.0d);
    }

    // --- Tests for patterns found in clojure.core's defn macro ---

    @Test
    public void fnParamSurvivesLetShadowing() {
        String code = """
            ((fn [name x]
               (let [m (if (string? x) {:doc x} {})
                     x (if (string? x) nil x)
                     m (if (map? x) (conj m x) m)
                     x (if (map? x) nil x)]
                 [name (class name)]))
             'foo "docstring")
            """;
        Object result = compileAndRun(code);
        assertTrue("name should remain a Symbol, got: " + result,
                result.toString().contains("clojure.lang.Symbol"));
    }

    @Test
    public void fnParamReadAfterNestedLet() {
        String code = """
            ((fn [name fdecl]
               (let [m {}
                     m (let [inline (:inline m)
                             ifn (first inline)]
                         (if ifn
                           (assoc m :inline ifn)
                           m))
                     m (conj (if (meta name) (meta name) {}) m)]
                 (.withMeta name m)))
             'foo '([x] x))
            """;
        Object result = compileAndRun(code);
        assertTrue("Result should be a Symbol, got: " + result.getClass(),
                result instanceof Symbol);
        assertEquals("foo", ((Symbol) result).getName());
    }

    @Test
    public void letRebindingSameNamePreservesOtherLocals() {
        String code = """
            ((fn [a b]
               (let [x 1
                     x (+ x 10)
                     x (+ x 100)]
                 [a b x]))
             :alpha :beta)
            """;
        Object result = compileAndRun(code);
        assertEquals("[:alpha :beta 111]", result.toString());
    }

    @Test
    public void macroExpansionPreservesSymbolTypes() {
        String code = """
            (do
              (defmacro my-def [name & body]
                (let [m (if (string? (first body)) {:doc (first body)} {})
                      body (if (string? (first body)) (next body) body)
                      m (if (map? (first body)) (conj m (first body)) m)
                      body (if (map? (first body)) (next body) body)]
                  (list 'def (with-meta name m) (cons 'fn body))))
              (macroexpand-1 '(my-def foo "a doc" [x] x)))
            """;
        Object result = compileAndRun(code);
        assertTrue("macroexpand result: " + result, result.toString().startsWith("(def foo"));
    }

    @Test
    public void instanceCheckInFnBody() {
        String code = """
            ((fn [x] (instance? clojure.lang.Symbol x)) 'hello)
            """;
        assertEquals(true, compileAndRun(code));
    }

    @Test
    public void symbolMetadataRoundTrip() {
        String code = """
            (let [s 'foo
                  m {:tag "bar"}
                  s2 (with-meta s m)]
              (class s2))
            """;
        Object result = compileAndRun(code);
        assertEquals(Symbol.class, result);
    }

    @Test
    public void truffleDefinedFnCalledByAnotherTruffleFn() {
        // Both functions defined through Truffle, one calls the other
        String code = """
            (do
              (def my-identity (fn [x] x))
              (def my-caller (fn [s] (my-identity s)))
              [(class (my-caller 'hello)) (my-caller 'hello)])
            """;
        Object result = compileAndRun(code);
        assertTrue("Symbol should survive Truffle fn calls: " + result,
                result.toString().contains("clojure.lang.Symbol"));
    }

    @Test
    public void selfReferencingFn() {
        String code = """
            ((fn self [] (class self)))
            """;
        Object result = compileAndRun(code);
        assertEquals(ClojureClosure.class, result);
    }

    @Test
    public void selfReferencingFnGetClassLoader() {
        // This is the exact pattern used by with-loading-context
        String code = """
            ((fn loading []
               (.getClassLoader (.getClass ^Object loading))))
            """;
        Object result = compileAndRun(code);
        assertTrue("Should return a ClassLoader, got: " + result,
                result instanceof ClassLoader);
    }

    @Test
    public void nsMacroExecutesCorrectly() {
        try {
            compileAndRun("(ns test.cloffle.nstest)");
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            StringBuilder sb = new StringBuilder("(ns test.cloffle.nstest) failed: " + root);
            for (StackTraceElement frame : root.getStackTrace()) {
                if (frame.getClassName().contains("cloffle") || frame.getClassName().contains("clojure")) {
                    sb.append("\n  at ").append(frame);
                }
            }
            fail(sb.toString());
        }
    }

    // Full core.clj loading test is in CoreCljLoadTest
}
