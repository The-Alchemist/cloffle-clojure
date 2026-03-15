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

    /**
     * Load clojure/core.clj form-by-form through the Truffle pipeline,
     * printing progress for every form. Continues past failures (up to 5).
     */
    @Test
    public void loadCoreCljFormByForm() throws Exception {
        java.io.InputStream is = RT.class.getResourceAsStream("/clojure/core.clj");
        if (is == null) {
            System.err.println("[CoreCljLoad] /clojure/core.clj not on classpath, skipping");
            return;
        }

        String source = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        is.close();

        clojure.lang.LineNumberingPushbackReader rdr =
                new clojure.lang.LineNumberingPushbackReader(new java.io.StringReader(source));
        Object readerOpts = RT.map(RT.READEVAL, RT.T);
        Object EOF = new Object();

        Var warnOnReflection = Var.find(Symbol.intern("clojure.core", "*warn-on-reflection*"));

        Var.pushThreadBindings(
                RT.mapUniqueKeys(
                        clojure.lang.Compiler.SOURCE_PATH, "clojure/core.clj",
                        clojure.lang.Compiler.SOURCE, "core.clj",
                        clojure.lang.Compiler.METHOD, null,
                        clojure.lang.Compiler.LOCAL_ENV, null,
                        clojure.lang.Compiler.LOOP_LOCALS, null,
                        clojure.lang.Compiler.NEXT_LOCAL_NUM, 0,
                        RT.READEVAL, RT.T,
                        RT.CURRENT_NS, RT.CURRENT_NS.deref(),
                        clojure.lang.Compiler.LINE_BEFORE, rdr.getLineNumber(),
                        clojure.lang.Compiler.COLUMN_BEFORE, rdr.getColumnNumber(),
                        clojure.lang.Compiler.LINE_AFTER, rdr.getLineNumber(),
                        clojure.lang.Compiler.COLUMN_AFTER, rdr.getColumnNumber(),
                        clojure.lang.Compiler.COMPILE_STUB_SYM, null,
                        clojure.lang.Compiler.COMPILE_STUB_CLASS, null,
                        clojure.lang.Compiler.CONSTANT_IDS, new java.util.IdentityHashMap<>(),
                        clojure.lang.Compiler.KEYWORD_CALLSITES, clojure.lang.PersistentVector.EMPTY,
                        clojure.lang.Compiler.PROTOCOL_CALLSITES, clojure.lang.PersistentVector.EMPTY,
                        clojure.lang.Compiler.KEYWORDS, clojure.lang.PersistentHashMap.EMPTY,
                        clojure.lang.Compiler.VARS, clojure.lang.PersistentHashMap.EMPTY,
                        RT.UNCHECKED_MATH, RT.UNCHECKED_MATH.deref(),
                        warnOnReflection, warnOnReflection.deref(),
                        RT.DATA_READERS, RT.DATA_READERS.deref(),
                        clojure.lang.Compiler.LOADER, RT.makeClassLoader()));

        int formNum = 0;
        int failCount = 0;
        long totalStart = System.currentTimeMillis();
        try {
            for (Object r = clojure.lang.LispReader.read(rdr, false, EOF, false, readerOpts); r != EOF;
                 r = clojure.lang.LispReader.read(rdr, false, EOF, false, readerOpts)) {

                formNum++;
                int line = rdr.getLineNumber();
                clojure.lang.Compiler.LINE_AFTER.set(line);
                clojure.lang.Compiler.COLUMN_AFTER.set(rdr.getColumnNumber());

                String formPreview = RT.printString(r);
                if (formPreview.length() > 100) formPreview = formPreview.substring(0, 100) + "...";

                long t0 = System.currentTimeMillis();
                try {
                    CloffleCompiler.executeForm(r);
                } catch (Exception e) {
                    long elapsed = System.currentTimeMillis() - t0;
                    Throwable root = e;
                    while (root.getCause() != null) root = root.getCause();
                    System.err.printf("[CoreCljLoad] FAIL form#%d line %d (%dms): %s: %s%n",
                            formNum, line, elapsed,
                            root.getClass().getSimpleName(), root.getMessage());
                    System.err.println("[CoreCljLoad] Form: " + formPreview);
                    root.printStackTrace(System.err);
                    failCount++;
                    if (failCount >= 10) {
                        org.junit.Assert.fail("core.clj: 10+ failures, stopping. Last: form#" + formNum);
                    }
                    continue;
                }

                long elapsed = System.currentTimeMillis() - t0;
                System.err.printf("[CoreCljLoad] OK   form#%d line %d (%dms): %s%n",
                        formNum, line, elapsed, formPreview);

                clojure.lang.Compiler.LINE_BEFORE.set(rdr.getLineNumber());
                clojure.lang.Compiler.COLUMN_BEFORE.set(rdr.getColumnNumber());
            }
        } finally {
            Var.popThreadBindings();
        }

        long totalElapsed = System.currentTimeMillis() - totalStart;
        System.err.printf("[CoreCljLoad] DONE — %d forms, %d failures in %dms%n",
                formNum, failCount, totalElapsed);

        if (failCount > 0) {
            org.junit.Assert.fail("core.clj had " + failCount + " failures out of " + formNum + " forms");
        }
    }
}
