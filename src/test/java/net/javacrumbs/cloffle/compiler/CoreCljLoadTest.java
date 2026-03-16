package net.javacrumbs.cloffle.compiler;

import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.assertEquals;

/**
 * Tests Truffle compilation of macros (bottom-up) and core.clj form-by-form.
 */
public class CoreCljLoadTest {

    @BeforeClass
    public static void setUp() {
        RT.init();
    }

    private static Object eval(String code) {
        try {
            return CloffleCompiler.compile(new StringReader(code), "test", "test.clj");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Object step(String label, String code) {
        long t0 = System.currentTimeMillis();
        try {
            Object result = eval(code);
            System.err.printf("[%s] OK (%dms) => %s%n", label, System.currentTimeMillis() - t0, result);
            return result;
        } catch (Throwable e) {
            long elapsed = System.currentTimeMillis() - t0;
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            System.err.printf("[%s] FAIL (%dms): %s: %s%n", label, elapsed,
                    root.getClass().getSimpleName(), root.getMessage());
            throw new RuntimeException(label + " failed", e);
        }
    }

    /**
     * Bottom-up: verify fn, defn, defmacro, and macro invocation through Truffle,
     * including redefined core fns and the full core.clj defmacro body.
     */
    @Test
    public void bottomUpMacroTest() {
        eval("(do (in-ns 'coreload.macro) (clojure.core/refer-clojure))");

        assertEquals(42L, step("fn-identity", "(do (def my-id (fn [x] x)) (my-id 42))"));
        assertEquals(Symbol.intern("a"), step("fn-static-call",
                "(do (def my-first (fn [c] (. clojure.lang.RT (first c)))) (my-first '(a b c)))"));
        assertEquals(true, step("fn-chain",
                "(do (def t-first (fn [c] (. clojure.lang.RT (first c)))) " +
                "(def t-string? (fn [x] (instance? String x))) " +
                "(t-string? (t-first '(\"hello\" 2))))"));
        assertEquals(7L, step("defn-macro", "(do (defn my-add [a b] (+ a b)) (my-add 3 4))"));

        step("defmacro-define", "(defmacro my-when [t & body] (list 'if t (cons 'do body)))");
        assertEquals(99L, step("macro-use", "(my-when true 99)"));
        assertEquals(null, step("macro-nil", "(my-when false 99)"));

        step("redef-list-cons", "(do " +
                "(def list (fn list [& items] (if items (clojure.lang.PersistentList/create items) '())))" +
                "(def cons (fn cons [x s] (. clojure.lang.RT (cons x s)))))");
        step("macro-with-truffle-fns", "(defmacro my-when2 [t & body] (list 'if t (cons 'do body)))");
        assertEquals(77L, step("use-macro-with-truffle-fns", "(my-when2 true 77)"));

        step("redef-more-fns", "(do " +
                "(def first (fn first [c] (. clojure.lang.RT (first c))))" +
                "(def next (fn next [x] (. clojure.lang.RT (next x))))" +
                "(def string? (fn string? [x] (instance? String x)))" +
                "(def map? (fn map? [x] (instance? clojure.lang.IPersistentMap x))))");
        step("macro-redef-preds", "(defmacro my-when3 [t & body] (list 'if t (cons 'do body)))");
        assertEquals(55L, step("use-macro-redef-preds", "(my-when3 (string? \"hi\") 55)"));

        // Full core.clj defmacro body (with add-implicit-args and loop/recur)
        step("full-defmacro", """
            (do
              (def defmacro (fn [&form &env name & args]
                (let [prefix (loop [p (list name) args args]
                               (let [f (first args)]
                                 (if (string? f)
                                   (recur (cons f p) (next args))
                                   (if (map? f)
                                     (recur (cons f p) (next args))
                                     p))))
                      fdecl (loop [fd args]
                              (if (string? (first fd))
                                (recur (next fd))
                                (if (map? (first fd))
                                  (recur (next fd))
                                  fd)))
                      fdecl (if (vector? (first fdecl))
                              (list fdecl)
                              fdecl)
                      add-implicit-args (fn [fd]
                                (let [args (first fd)]
                                  (cons (vec (cons '&form (cons '&env args))) (next fd))))
                      add-args (fn [acc ds]
                                 (if (nil? ds)
                                   acc
                                   (let [d (first ds)]
                                     (if (map? d)
                                       (conj acc d)
                                       (recur (conj acc (add-implicit-args d)) (next ds))))))
                      fdecl (seq (add-args [] fdecl))
                      decl (loop [p prefix d fdecl]
                             (if p
                               (recur (next p) (cons (first p) d))
                               d))]
                  (list 'do
                        (cons 'defn decl)
                        (list '. (list 'var name) '(setMacro))
                        (list 'var name)))))
              (. (var defmacro) (setMacro)))
            """);
        step("use-full-defmacro", "(defmacro my-when4 [t & body] (list 'if t (cons 'do body)))");
        assertEquals(42L, step("call-full-defmacro", "(my-when4 true 42)"));

        System.err.println("[DONE] All macro levels passed!");
    }

    @Test
    public void selfRefFnInLet() {
        assertEquals(5L, step("self-ref-let",
                "(let [f (fn f [n] (if (zero? n) 0 (+ 1 (f (dec n)))))] (f 5))"));
    }

    @Test
    public void selfRefFnInNestedLet() {
        assertEquals(120L, step("self-ref-nested-let",
                "(let [g (fn [x] (* x 2))" +
                "      f (fn f [n] (if (zero? n) 1 (* n (f (dec n)))))]" +
                "  (f 5))"));
    }

    @Test
    public void selfRefFnCalledFromNestedFn() {
        assertEquals(6L, step("self-ref-from-inner",
                "(let [f (fn f [n] (if (zero? n) 0 (+ 1 (f (dec n)))))]" +
                "  (let [g (fn [x] (f x))]" +
                "    (g 6)))"));
    }

    @Test
    public void forComprehensionBasic() {
        step("for-basic", "(for [x [1 2 3]] x)");
    }

    @Test
    public void selfRefFnWithDestructuring() {
        assertEquals(clojure.lang.PersistentList.create(java.util.List.of(
                Symbol.intern("a"), Symbol.intern("b"))),
                step("self-ref-destruct",
                "(let [f (fn f [[[h] & r]] (if h (cons h (f r)) nil))]" +
                "  (f [['a 1] ['b 2]]))"));
    }

    @Test
    public void selfRefFnWithNestedLet() {
        step("self-ref-nested",
                "(let [g 42" +
                "      f (fn f [[[bind expr] & [[_ next-expr] :as next-groups]]]" +
                "           (if next-groups" +
                "             (list 'inner (f next-groups))" +
                "             (list 'leaf bind)))]" +
                "  (f [[:x 1] [:y 2]]))");
    }

    @Test
    public void forComprehensionNested() {
        step("for-nested", "(for [x [1 2] y [3 4]] [x y])");
    }

    @Test
    public void sortedMapEmpty() {
        step("sorted-map-empty", "(sorted-map)");
    }

    @Test
    public void caseWithStrings() {
        step("case-str", "(let [s \"true\"] (case s \"true\" true \"false\" false nil))");
    }

    @Test
    public void parseBooleanDirect() {
        step("parse-bool-direct",
                "(defn parse-boolean-test [^String s]" +
                "  (if (string? s)" +
                "    (case s \"true\" true \"false\" false nil)" +
                "    (throw (IllegalArgumentException. \"not a string\"))))");
    }

    @Test
    public void applyHashMapNil() {
        assertEquals(clojure.lang.PersistentArrayMap.EMPTY, step("apply-hash-map-nil", "(apply hash-map nil)"));
    }

}
