package clojure.lang;

import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Multi-arity {@code fn*} dispatch, variadic params, closures (including deeply nested and named-fn
 * self-reference), and {@code defmacro}-body regression tests.
 * <p>
 * No {@code clojure.core} load — forms limited to what {@link Compiler#analyze} handles natively.
 * <p>
 * Package {@code clojure.lang} for access to {@link Compiler} internals.
 * Helpers: {@link BytecodeDslTestSupport}.
 */
public class BytecodeFnArityAndClosureTest {

    // --- Multi-arity dispatch ---

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

    /**
     * Same param shape as core {@code defmacro} ({@code &form}, {@code &env}, name, {@code & args}).
     */
    @Test
    public void macroLikeVariadicBindsNameParam() {
        Object name = BytecodeDslTestSupport.evalBytecode(
                "((fn* [&form &env name & args] name) 'whole {} 'myname 1 2)");
        assertEquals(Symbol.intern("myname"), name);
    }

    /**
     * Nested {@code let*} must not clear {@code fn*} param slots before the tail reads them.
     */
    @Test
    public void fnStarParamSurvivesNestedLetStar() {
        assertEquals(
                42L,
                BytecodeDslTestSupport.evalBytecode(
                        "((fn* [x] (let* [a 1] (let* [b 2] (let* [c 3] x)))) 42)"));
    }

    @Test
    public void fnStarParamReadAfterLoopStar() {
        assertEquals(
                99L,
                BytecodeDslTestSupport.evalBytecode(
                        "((fn* [x] (loop* [i 0] (if (clojure.lang.Util/equiv i 2) x"
                                + " (recur (clojure.lang.Numbers/add i 1))))) 99)"));
    }

    @Test
    public void fnStarTwoArgRecurToHead() {
        assertEquals(
                6L,
                BytecodeDslTestSupport.evalBytecode(
                        "((fn* [acc ds]"
                                + "   (if (clojure.lang.Util/identical ds nil)"
                                + "     acc"
                                + "     (recur (clojure.lang.Numbers/add acc (clojure.lang.RT/first ds))"
                                + "            (clojure.lang.RT/next ds))))"
                                + " 0 (clojure.lang.RT/list 1 2 3))"));
    }

    // --- Closures ---

    @Test
    public void deeplyNestedClosureReachesGrandparentBinding() {
        String code =
                "((fn* [x] "
                        + "(let* [v x] "
                        + "  ((fn* [] v)))) "
                        + "42)";
        assertEquals(42L, BytecodeDslTestSupport.evalBytecode(code));
    }

    @Test
    public void triplyNestedClosureReachesGrandparentViaParentCopy() {
        String code =
                "((fn* [x] "
                        + "(let* [v x] "
                        + "  ((fn* [y] ((fn* [] v))) 99))) "
                        + "42)";
        assertEquals(42L, BytecodeDslTestSupport.evalBytecode(code));
    }

    @Test
    public void fnInsideLetMetadataDoesNotReadOuterFrame() {
        String code =
                "(let* [inline-fn (fn* [x y] (clojure.lang.RT/list (quote .) (quote clojure.lang.Util) (quote equiv) x y))]"
                        + "  (inline-fn (quote a) (quote b)))";
        Object bc = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals("(. clojure.lang.Util equiv a b)", RT.printString(bc));
    }

    @Test
    public void multiArityFnClosureCapturesParam() {
        String code = "((fn* ([] 0) ([x] ((fn* [] x)))) 42)";
        assertEquals(42L, BytecodeDslTestSupport.evalBytecode(code));
    }

    @Test
    public void multiArityFnClosureCapturesLetBinding() {
        String code = "((fn* ([] 0) ([x] (let* [v x] ((fn* [] v))))) 42)";
        assertEquals(42L, BytecodeDslTestSupport.evalBytecode(code));
    }

    @Test
    public void multiArityFnClosureCapturesOuterLetBinding() {
        String code = "(let* [outer 99] ((fn* ([] 0) ([x] ((fn* [] outer)))) 42))";
        assertEquals(99L, BytecodeDslTestSupport.evalBytecode(code));
    }

    // --- Named fn self-reference ---

    @Test
    public void namedFnSelfReferenceThroughClosureLazySeqPattern() {
        String code =
                "((fn* [xs]"
                + "  (let* [cat (fn* cat [items]"
                + "              (if (clojure.lang.RT/seq items)"
                + "                (clojure.lang.RT/cons"
                + "                  (clojure.lang.RT/first items)"
                + "                  (cat (clojure.lang.RT/next items)))"
                + "                nil))]"
                + "    (cat xs)))"
                + " (clojure.lang.RT/list 1 2 3))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals("(1 2 3)", RT.printString(result));
    }

    @Test
    public void namedFnSelfReferenceRecursiveLikeConcatTwoArity() {
        String code =
                "(let* [my-concat (fn* my-concat"
                + "  ([x y]"
                + "    ((fn* [] "
                + "      (let* [s (clojure.lang.RT/seq x)]"
                + "        (if s"
                + "          (clojure.lang.RT/cons"
                + "            (clojure.lang.RT/first s)"
                + "            (my-concat (clojure.lang.RT/next s) y))"
                + "          y))))))]"
                + "  (my-concat (clojure.lang.RT/list 1 2) (clojure.lang.RT/list 3 4)))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals("(1 2 3 4)", RT.printString(result));
    }

    @Test
    public void namedFnSelfReferenceInInnerClosure() {
        String code =
                "((fn* [xs]"
                + "  (let* [cat (fn* cat [items]"
                + "              (if (clojure.lang.RT/seq items)"
                + "                (let* [inner-fn (fn* [] (cat (clojure.lang.RT/next items)))]"
                + "                  (clojure.lang.RT/cons"
                + "                    (clojure.lang.RT/first items)"
                + "                    (inner-fn)))"
                + "                nil))]"
                + "    (cat xs)))"
                + " (clojure.lang.RT/list 1 2 3))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals("(1 2 3)", RT.printString(result));
    }

    // --- Multi-arity named fn with closures (concat-like patterns) ---

    @Test
    public void multiArityNamedFnWithClosuresInEachArityMethod() {
        String code =
                "(let* [my-concat (fn* my-concat"
                + "  ([] nil)"
                + "  ([x] x)"
                + "  ([x y]"
                + "    ((fn* [] "
                + "      (let* [s (clojure.lang.RT/seq x)]"
                + "        (if s"
                + "          (clojure.lang.RT/cons"
                + "            (clojure.lang.RT/first s)"
                + "            (my-concat (clojure.lang.RT/next s) y))"
                + "          y)))))"
                + "  ([x y & zs]"
                + "    (let* [cat (fn* cat [xys zs]"
                + "              ((fn* []"
                + "                (let* [xys2 (clojure.lang.RT/seq xys)]"
                + "                  (if xys2"
                + "                    (clojure.lang.RT/cons"
                + "                      (clojure.lang.RT/first xys2)"
                + "                      (cat (clojure.lang.RT/next xys2) zs))"
                + "                    (if zs"
                + "                      (cat (clojure.lang.RT/first zs) (clojure.lang.RT/next zs))"
                + "                      nil))))))]"
                + "      (cat (my-concat x y) zs))))]"
                + "  (my-concat"
                + "    (clojure.lang.RT/list 1)"
                + "    (clojure.lang.RT/list 2)"
                + "    (clojure.lang.RT/list 3)"
                + "    (clojure.lang.RT/list 4)"
                + "    (clojure.lang.RT/list 5)))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals("(1 2 3 4 5)", RT.printString(result));
    }

    @Test
    public void multiArityNamedFnWithNestedNamedFnAndLazySeqLikeClosure() {
        String code =
                "(let* [my-concat (fn* my-concat"
                + "  ([] nil)"
                + "  ([x] x)"
                + "  ([x y]"
                + "    ((fn* [] "
                + "      (let* [s (clojure.lang.RT/seq x)]"
                + "        (if s"
                + "          (clojure.lang.RT/cons"
                + "            (clojure.lang.RT/first s)"
                + "            (my-concat (clojure.lang.RT/next s) y))"
                + "          y)))))"
                + "  ([x y & zs]"
                + "    (let* [cat (fn* cat [xys zs]"
                + "              ((fn* []"
                + "                (let* [xys2 (clojure.lang.RT/seq xys)]"
                + "                  (if xys2"
                + "                    (clojure.lang.RT/cons"
                + "                      (clojure.lang.RT/first xys2)"
                + "                      (cat (clojure.lang.RT/next xys2) zs))"
                + "                    (if zs"
                + "                      (cat (clojure.lang.RT/first zs) (clojure.lang.RT/next zs))"
                + "                      nil))))))]"
                + "      (cat (my-concat x y) zs))))]"
                + "  (my-concat"
                + "    (clojure.lang.RT/list 1)"
                + "    (clojure.lang.RT/list 2)"
                + "    (clojure.lang.RT/list 3)"
                + "    (clojure.lang.RT/list 4)"
                + "    (clojure.lang.RT/list 5)))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals("(1 2 3 4 5)", RT.printString(result));
    }

    @Test
    public void multiArityWithLazySeqNewNotEager() {
        String code =
                "(let* [my-concat (fn* my-concat"
                + "  ([] nil)"
                + "  ([x] (new clojure.lang.LazySeq (fn* [] x)))"
                + "  ([x y]"
                + "    (new clojure.lang.LazySeq (fn* [] "
                + "      (let* [s (clojure.lang.RT/seq x)]"
                + "        (if s"
                + "          (clojure.lang.RT/cons"
                + "            (clojure.lang.RT/first s)"
                + "            (my-concat (clojure.lang.RT/next s) y))"
                + "          y)))))"
                + "  ([x y & zs]"
                + "    (let* [cat (fn* cat [xys zs]"
                + "              (new clojure.lang.LazySeq (fn* []"
                + "                (let* [xys2 (clojure.lang.RT/seq xys)]"
                + "                  (if xys2"
                + "                    (clojure.lang.RT/cons"
                + "                      (clojure.lang.RT/first xys2)"
                + "                      (cat (clojure.lang.RT/next xys2) zs))"
                + "                    (if zs"
                + "                      (cat (clojure.lang.RT/first zs) (clojure.lang.RT/next zs))"
                + "                      nil))))))]"
                + "      (cat (my-concat x y) zs))))]"
                + "  (clojure.lang.RT/seq"
                + "    (my-concat"
                + "      (clojure.lang.RT/list 1)"
                + "      (clojure.lang.RT/list 2)"
                + "      (clojure.lang.RT/list 3)"
                + "      (clojure.lang.RT/list 4)"
                + "      (clojure.lang.RT/list 5))))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals("(1 2 3 4 5)", RT.printString(result));
    }

    // --- defmacro body regression tests ---

    /**
     * Full defmacro body shape: prefix loop, fdecl loop, inner fn* closures (add-implicit-args,
     * add-args with recur), seq, decl loop, and final (cons 'defn decl). Bytecode must match AST.
     */
    @Test
    public void defmacroFullBodyEvaluates() {
        String code =
                "((fn* [&form &env name & args]"
                        + "  (let* [prefix (loop* [p (clojure.lang.RT/list name) args args]"
                        + "                 (let* [f (clojure.lang.RT/first args)]"
                        + "                   (if (instance? String f)"
                        + "                     (recur (clojure.lang.RT/cons f p) (clojure.lang.RT/next args))"
                        + "                     (if (instance? clojure.lang.IPersistentMap f)"
                        + "                       (recur (clojure.lang.RT/cons f p) (clojure.lang.RT/next args))"
                        + "                       p))))"
                        + "        fdecl (loop* [fd args]"
                        + "                (if (instance? String (clojure.lang.RT/first fd))"
                        + "                  (recur (clojure.lang.RT/next fd))"
                        + "                  (if (instance? clojure.lang.IPersistentMap (clojure.lang.RT/first fd))"
                        + "                    (recur (clojure.lang.RT/next fd))"
                        + "                    fd)))"
                        + "        fdecl (if (instance? clojure.lang.IPersistentVector (clojure.lang.RT/first fdecl))"
                        + "                (clojure.lang.RT/list fdecl)"
                        + "                fdecl)"
                        + "        add-implicit-args (fn* [fd]"
                        + "                 (let* [args (clojure.lang.RT/first fd)]"
                        + "                   (clojure.lang.RT/cons"
                        + "                     (clojure.lang.LazilyPersistentVector/create"
                        + "                       (clojure.lang.RT/cons (quote &form)"
                        + "                         (clojure.lang.RT/cons (quote &env) args)))"
                        + "                     (clojure.lang.RT/next fd))))"
                        + "        add-args (fn* [acc ds]"
                        + "                  (if (clojure.lang.Util/identical ds nil)"
                        + "                    acc"
                        + "                    (let* [d (clojure.lang.RT/first ds)]"
                        + "                      (if (instance? clojure.lang.IPersistentMap d)"
                        + "                        (clojure.lang.RT/conj acc d)"
                        + "                        (recur (clojure.lang.RT/conj acc (add-implicit-args d))"
                        + "                               (clojure.lang.RT/next ds))))))"
                        + "        fdecl (clojure.lang.RT/seq (add-args [] fdecl))"
                        + "        decl (loop* [p prefix d fdecl]"
                        + "               (if p"
                        + "                 (recur (clojure.lang.RT/next p)"
                        + "                        (clojure.lang.RT/cons (clojure.lang.RT/first p) d))"
                        + "                 d))]"
                        + "    (clojure.lang.RT/list (quote do)"
                        + "      (clojure.lang.RT/cons (quote defn) decl)"
                        + "      (clojure.lang.RT/list (quote .) (clojure.lang.RT/list (quote var) name) (quote (setMacro)))"
                        + "      (clojure.lang.RT/list (quote var) name))))"
                        + " 'whole {} 'when \"doc\" {:a 1} '[test & body] '(clojure.lang.RT/list (quote if) test (clojure.lang.RT/cons (quote do) body)))";
        Object bc = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals(
                "(do (defn when \"doc\" {:a 1} ([&form &env test & body] (clojure.lang.RT/list (quote if) test (clojure.lang.RT/cons (quote do) body))))"
                        + " (. (var when) (setMacro)) (var when))",
                RT.printString(bc));
    }

    @Test
    public void defmacroNameVisibleAfterInnerFnClosures() {
        String code =
                "((fn* [&form &env name & args]"
                        + "  (let* [prefix (loop* [p (clojure.lang.RT/list name) args args]"
                        + "                 (let* [f (clojure.lang.RT/first args)]"
                        + "                   (if (instance? String f)"
                        + "                     (recur (clojure.lang.RT/cons f p) (clojure.lang.RT/next args))"
                        + "                     (if (instance? clojure.lang.IPersistentMap f)"
                        + "                       (recur (clojure.lang.RT/cons f p) (clojure.lang.RT/next args))"
                        + "                       p))))"
                        + "        fdecl (loop* [fd args]"
                        + "                (if (instance? String (clojure.lang.RT/first fd))"
                        + "                  (recur (clojure.lang.RT/next fd))"
                        + "                  (if (instance? clojure.lang.IPersistentMap (clojure.lang.RT/first fd))"
                        + "                    (recur (clojure.lang.RT/next fd))"
                        + "                    fd)))"
                        + "        fdecl (if (instance? clojure.lang.IPersistentVector (clojure.lang.RT/first fdecl))"
                        + "                (clojure.lang.RT/list fdecl)"
                        + "                fdecl)"
                        + "        add-implicit-args (fn* [fd]"
                        + "                 (let* [args (clojure.lang.RT/first fd)]"
                        + "                   (clojure.lang.RT/cons"
                        + "                     (clojure.lang.LazilyPersistentVector/create"
                        + "                       (clojure.lang.RT/cons (quote &form)"
                        + "                         (clojure.lang.RT/cons (quote &env) args)))"
                        + "                     (clojure.lang.RT/next fd))))"
                        + "        add-args (fn* [acc ds]"
                        + "                  (if (clojure.lang.Util/identical ds nil)"
                        + "                    acc"
                        + "                    (let* [d (clojure.lang.RT/first ds)]"
                        + "                      (if (instance? clojure.lang.IPersistentMap d)"
                        + "                        (clojure.lang.RT/conj acc d)"
                        + "                        (recur (clojure.lang.RT/conj acc (add-implicit-args d))"
                        + "                               (clojure.lang.RT/next ds))))))"
                        + "        fdecl (clojure.lang.RT/seq (add-args [] fdecl))"
                        + "        decl (loop* [p prefix d fdecl]"
                        + "               (if p"
                        + "                 (recur (clojure.lang.RT/next p)"
                        + "                        (clojure.lang.RT/cons (clojure.lang.RT/first p) d))"
                        + "                 d))]"
                        + "    decl))"
                        + " 'whole {} 'when \"doc\" {:a 1} '[test & body] '(clojure.lang.RT/list (quote if) test (clojure.lang.RT/cons (quote do) body)))";
        Object bc = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals(
                "(when \"doc\" {:a 1} ([&form &env test & body] (clojure.lang.RT/list (quote if) test (clojure.lang.RT/cons (quote do) body))))",
                RT.printString(bc));
    }

    @Test
    public void defmacroPrefixOnlyEvaluates() {
        String code =
                "((fn* [&form &env name & args]"
                        + "  (let* [prefix (loop* [p (clojure.lang.RT/list name) args args]"
                        + "                 (let* [f (clojure.lang.RT/first args)]"
                        + "                   (if (instance? String f)"
                        + "                     (recur (clojure.lang.RT/cons f p) (clojure.lang.RT/next args))"
                        + "                     (if (instance? clojure.lang.IPersistentMap f)"
                        + "                       (recur (clojure.lang.RT/cons f p) (clojure.lang.RT/next args))"
                        + "                       p))))]"
                        + "    prefix))"
                        + " 'whole {} 'when \"doc\" {:a 1} '[test & body] 0)";
        Object bc = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals("({:a 1} \"doc\" when)", RT.printString(bc));
    }

    @Test
    public void defmacroPrefixAndFdeclAfterAddArgs() {
        String code =
                "((fn* [&form &env name & args]"
                        + "  (let* [prefix (loop* [p (clojure.lang.RT/list name) args args]"
                        + "                 (let* [f (clojure.lang.RT/first args)]"
                        + "                   (if (instance? String f)"
                        + "                     (recur (clojure.lang.RT/cons f p) (clojure.lang.RT/next args))"
                        + "                     (if (instance? clojure.lang.IPersistentMap f)"
                        + "                       (recur (clojure.lang.RT/cons f p) (clojure.lang.RT/next args))"
                        + "                       p))))"
                        + "        fdecl (loop* [fd args]"
                        + "                (if (instance? String (clojure.lang.RT/first fd))"
                        + "                  (recur (clojure.lang.RT/next fd))"
                        + "                  (if (instance? clojure.lang.IPersistentMap (clojure.lang.RT/first fd))"
                        + "                    (recur (clojure.lang.RT/next fd))"
                        + "                    fd)))"
                        + "        fdecl (if (instance? clojure.lang.IPersistentVector (clojure.lang.RT/first fdecl))"
                        + "                (clojure.lang.RT/list fdecl)"
                        + "                fdecl)"
                        + "        add-implicit-args (fn* [fd]"
                        + "                 (let* [args (clojure.lang.RT/first fd)]"
                        + "                   (clojure.lang.RT/cons"
                        + "                     (clojure.lang.LazilyPersistentVector/create"
                        + "                       (clojure.lang.RT/cons (quote &form)"
                        + "                         (clojure.lang.RT/cons (quote &env) args)))"
                        + "                     (clojure.lang.RT/next fd))))"
                        + "        add-args (fn* [acc ds]"
                        + "                  (if (clojure.lang.Util/identical ds nil)"
                        + "                    acc"
                        + "                    (let* [d (clojure.lang.RT/first ds)]"
                        + "                      (if (instance? clojure.lang.IPersistentMap d)"
                        + "                        (clojure.lang.RT/conj acc d)"
                        + "                        (recur (clojure.lang.RT/conj acc (add-implicit-args d))"
                        + "                               (clojure.lang.RT/next ds))))))"
                        + "        fdecl (clojure.lang.RT/seq (add-args [] fdecl))]"
                        + "    [prefix fdecl]))"
                        + " 'whole {} 'when \"doc\" {:a 1} '[test & body] '(clojure.lang.RT/list (quote if) test (clojure.lang.RT/cons (quote do) body)))";
        Object bc = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals(
                "[({:a 1} \"doc\" when) (([&form &env test & body] (clojure.lang.RT/list (quote if) test (clojure.lang.RT/cons (quote do) body))))]",
                RT.printString(bc));
    }

    @Test
    public void declLoopReversesPrefix() {
        String code =
                "(let* [prefix (clojure.lang.RT/list :a :b :c)"
                        + "       fdecl (clojure.lang.RT/list :x :y)"
                        + "       decl (loop* [p prefix d fdecl]"
                        + "              (if p"
                        + "                (recur (clojure.lang.RT/next p)"
                        + "                       (clojure.lang.RT/cons (clojure.lang.RT/first p) d))"
                        + "                d))]"
                        + "  decl)";
        Object bc = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals("(:c :b :a :x :y)", RT.printString(bc));
    }

    @Test
    public void declLoopAfterFnClosureBindings() {
        String code =
                "((fn* [&form &env name & args]"
                        + "  (let* [prefix (clojure.lang.RT/list name)"
                        + "        inner-fn (fn* [x] x)"
                        + "        decl (loop* [p prefix d nil]"
                        + "               (if p"
                        + "                 (recur (clojure.lang.RT/next p)"
                        + "                        (clojure.lang.RT/cons (clojure.lang.RT/first p) d))"
                        + "                 d))]"
                        + "    decl))"
                        + " 'whole {} 'myname 1 2)";
        Object bc = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals("(myname)", RT.printString(bc));
    }

    @Test
    public void defmacroNameAfterAddArgsFn() {
        String code =
                "((fn* [&form &env name & args]"
                        + "  (let* [add-implicit-args (fn* [fd] fd)"
                        + "        add-args (fn* [acc ds]"
                        + "                  (if (clojure.lang.Util/identical ds nil)"
                        + "                    acc"
                        + "                    (let* [d (clojure.lang.RT/first ds)]"
                        + "                      (if (instance? clojure.lang.IPersistentMap d)"
                        + "                        (clojure.lang.RT/conj acc d)"
                        + "                        (recur (clojure.lang.RT/conj acc (add-implicit-args d))"
                        + "                               (clojure.lang.RT/next ds))))))]"
                        + "    name))"
                        + " 'whole {} 'when 1 2)";
        Object bc = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals(Symbol.intern("when"), bc);
    }

    @Test
    public void defmacroPrefixAndFdeclLoopsMatchAst() {
        String code =
                "((fn* [&form &env name & args]"
                        + "  (let* [prefix (loop* [p (clojure.lang.RT/list name) args args]"
                        + "                 (let* [f (clojure.lang.RT/first args)]"
                        + "                   (if (instance? String f)"
                        + "                     (recur (clojure.lang.RT/cons f p) (clojure.lang.RT/next args))"
                        + "                     (if (instance? clojure.lang.IPersistentMap f)"
                        + "                       (recur (clojure.lang.RT/cons f p) (clojure.lang.RT/next args))"
                        + "                       p))))"
                        + "        fdecl (loop* [fd args]"
                        + "                (if (instance? String (clojure.lang.RT/first fd))"
                        + "                  (recur (clojure.lang.RT/next fd))"
                        + "                  (if (instance? clojure.lang.IPersistentMap (clojure.lang.RT/first fd))"
                        + "                    (recur (clojure.lang.RT/next fd))"
                        + "                    fd)))]"
                        + "    [prefix fdecl])) 'whole {} 'when \"doc\" {:a 1} '[x] 0)";
        Object bc = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals("[({:a 1} \"doc\" when) ([x] 0)]", RT.printString(bc));
    }

    /**
     * Multi-arity fn* with variadic and recur — same shape as {@code =} in core.clj.
     */
    @Test
    public void defMultiArityVariadicRecurFnStarEvaluates() {
        String sym = "expr_to_bytecode_varrecur_" + System.nanoTime();
        String code =
                "(do (def " + sym + " (fn* "
                        + "([x] true) "
                        + "([x y] (clojure.lang.Util/equiv x y)) "
                        + "([x y & more] "
                        + "  (if (clojure.lang.Util/equiv x y) "
                        + "    (if (clojure.lang.RT/next more) "
                        + "      (recur y (clojure.lang.RT/first more) (clojure.lang.RT/next more)) "
                        + "      (clojure.lang.Util/equiv y (clojure.lang.RT/first more))) "
                        + "    false)))) "
                        + "[(" + sym + " 1) (" + sym + " 1 1) (" + sym + " 1 1 1)])";
        Object bc = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals("[true true true]", RT.printString(bc));
    }

    // --- ArityException catch in try/catch ---

    /**
     * Reproducer for the ArityException propagation issue.
     * A multi-arity fn called with wrong arity should throw ArityException,
     * and try/catch should be able to catch it.
     * This mirrors what happens in clojure.test's (is (thrown? IllegalArgumentException (/)))
     * pattern — the (/) call hits ThrowArity, and the try/catch from try-expr should catch it.
     */
    @Test
    public void tryCatchArityExceptionFromMultiArityFn() {
        String code = "(try " +
                "  ((fn* ([x] x) ([x y] y))) " +
                "  (catch Exception e :caught))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals(Keyword.intern(null, "caught"), result);
    }

    @Test
    public void tryCatchArityExceptionFromMultiArityFnWithVariadic() {
        String code = "(try " +
                "  ((fn* ([x] x) ([x y] (. clojure.lang.Numbers (divide x y))) ([x y & more] x))) " +
                "  (catch Exception e :caught))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals(Keyword.intern(null, "caught"), result);
    }

    @Test
    public void arityExceptionPreservedThroughLazySeqForce() {
        String code = "(try " +
                "  (let* [f (fn* ([x] x) ([x y] y)) " +
                "         s (new clojure.lang.LazySeq (fn* [] (f)))] " +
                "    (.seq s)) " +
                "  (catch Exception e :caught))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals(Keyword.intern(null, "caught"), result);
    }

    // --- InvocationTargetException unwrapping ---

    @Test
    public void tryCatchClassNotFoundFromStaticMethod() {
        // Mirrors: (try (resolve 'NonExistentClass/foo) (catch ClassNotFoundException e :caught))
        // resolve calls RT/classForNameNonLoading which throws ClassNotFoundException
        // wrapped in InvocationTargetException by reflection
        String code = "(try " +
                "  (. Class forName \"com.nonexistent.DoesNotExist\") " +
                "  (catch ClassNotFoundException e :caught))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals(Keyword.intern(null, "caught"), result);
    }

    @Test
    public void tryCatchExceptionFromNewObject() {
        // Constructor that throws — wraps in InvocationTargetException
        String code = "(try " +
                "  (throw (new Exception \"boom\")) " +
                "  (catch Exception e (.getMessage e)))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals("boom", result);
    }

    @Test
    public void tryCatchInstanceMethodException() {
        // Instance method that throws
        String code = "(try " +
                "  (.charAt \"hello\" 99) " +
                "  (catch StringIndexOutOfBoundsException e :caught))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals(Keyword.intern(null, "caught"), result);
    }

    @Test
    public void orMacroWithStaticMethodCalls() {
        // This is the pattern from run_test_surefire.clj:
        // (or (System/getProperty "xxx") (throw ...))
        // or expands to let + if, with System/getProperty as a StaticMethod call
        String code = "(let* [v (. System getProperty \"java.version\")] " +
                "  (if v v \"fallback\"))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertTrue("Should return java version string, got: " + result,
                result instanceof String && ((String) result).length() > 0);
    }

    @Test
    public void ifLetPatternWithStaticMethod() {
        // Pattern from run_test_surefire.clj: (if-let [s (System/getProperty ...)] ...)
        String code = "(let* [s (. System getProperty \"nonexistent.property.12345\")] " +
                "  (if s s :default))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals(Keyword.intern(null, "default"), result);
    }

    @Test
    public void tryCatchFromOrMacroExpansion() {
        // (or x y) expands to (let [v x] (if v v y)), tests the typical harness pattern
        // This uses the full Clojure compiler path with macroexpand
        String code = "(let* [result (try " +
                "  (. System getProperty \"nonexistent.test.prop.xyz\") " +
                "  (catch Exception e nil))] " +
                "  (if result result \"default-value\"))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals("default-value", result);
    }

    @Test
    public void nestedLetWithMultipleStaticMethodCalls() {
        // Mirrors: (let [a (System/getProperty "x") b (if-let [s (System/getProperty "y")] (read-string s) #{})] ...)
        String code = "(let* [a (. System getProperty \"java.version\") " +
                "       b (. System getProperty \"nonexistent.prop.abc\")] " +
                "  (if b b a))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertTrue("Should return java version, got: " + result, result instanceof String);
    }

    @Test
    public void complexLetWithThrowAndTryCatch() {
        // Mirrors: (or (System/getProperty "surefire.reports.dir") (throw (ex-info ...)))
        String code = "(let* [dir (. System getProperty \"java.home\")] " +
                "  (if dir dir (throw (new Exception \"not set\"))))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertTrue("Should return java.home path, got: " + result, result instanceof String);
    }

    /**
     * Reproduces the exact ArrayIndexOutOfBoundsException crash from run_test_surefire.clj form#3.
     * The crash occurs at LOAD_ARGUMENT in CloffleBytecodeRootNodeGen because a compiled
     * function expects argument at index 1 but was called with only 1 argument.
     * This requires core.clj to be loaded (for macros like or, if-let, some->, etc.)
     * and then compiling form#3 in bytecode mode.
     */
    @Test
    public void surefireHarnessForm3Reproducer() {
        // Manual expansion of:
        // (let [reports-dir (or (System/getProperty "surefire.reports.dir") (throw ...))
        //       only-ns (some-> (System/getProperty "clojure.test-clojure.only-namespace") .trim)]
        //   [reports-dir only-ns])
        //
        // (or x y) => (let* [or__auto x] (if or__auto or__auto y))
        // (some-> x .trim) => (let* [g x] (if (nil? g) nil (. g trim)))
        System.setProperty("surefire.reports.dir", "/tmp/test-dir-" + System.nanoTime());
        try {
            String code =
                    "(let* [or__auto (. System getProperty \"surefire.reports.dir\")] " +
                    "  (let* [reports-dir (if or__auto or__auto (throw (new RuntimeException \"not set\")))] " +
                    "    (let* [g (. System getProperty \"clojure.test-clojure.only-namespace\")] " +
                    "      (let* [only-ns (if (. clojure.lang.Util identical g nil) nil (.trim g))] " +
                    "        reports-dir))))";
            Object result = BytecodeDslTestSupport.evalBytecode(code);
            assertTrue("Should return reports-dir string", result instanceof String);
        } finally {
            System.clearProperty("surefire.reports.dir");
        }
    }

    @Test
    public void someThreadMacroPattern() {
        // Manual expansion of: (some-> (System/getProperty "x") .trim)
        // some-> expands to: (let [g (System/getProperty "x")]
        //                      (if (nil? g) nil (-> g .trim)))
        String code =
                "(let* [g (. System getProperty \"java.home\")] " +
                "  (if (. clojure.lang.Util identical g nil) nil (.trim g)))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertTrue("Should return trimmed java.home, got: " + result, result instanceof String);
    }

    @Test
    public void lambdaCaptureInLetPattern() {
        // Closure captures outer let binding
        String code =
                "(let* [n \"hello\"] " +
                "  ((fn* [x] (. clojure.lang.Util (equiv n x))) " +
                "   \"hello\"))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals(true, result);
    }

    @Test
    public void reduceFnWithLambdaClosure() {
        // Pattern: (reduce (fn [acc n] ...) [] coll) using fn with closure
        String code =
                "(let* [acc (. clojure.lang.PersistentVector EMPTY)] " +
                "  (. acc cons 42))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertTrue("Should return a vector, got: " + result, result != null);
    }

    @Test
    public void tryCatchStaticMethodWithExceptionUnwrapping() {
        // The real harness calls (System/getProperty ...) which is a StaticMethod.
        // We test that InvocationTargetException from static methods is properly unwrapped.
        // This uses the surefire-harness pattern: let binding + if + static method
        String code =
                "(let* [a (. System getProperty \"java.version\")" +
                "       b (. System getProperty \"nonexistent.prop.xyz\")" +
                "       c (. System getProperty \"java.home\")]" +
                "  (if b b (if a a c)))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertTrue("Should return java version, got: " + result, result instanceof String);
    }

    @Test
    public void throwAndCatchNewExceptionFromBytecode() {
        // (throw (Exception. "msg")) creates via NewObject, and the exception
        // must survive through try/catch properly
        String code = "(try " +
                "  (throw (new Exception \"test-msg\")) " +
                "  (catch Exception e (.getMessage e)))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals("test-msg", result);
    }

    @Test
    public void instanceMethodThrowsClassCastException() {
        // sorted-set-by pattern: instance method that throws ClassCastException
        String code = "(try " +
                "  (.compareTo \"abc\" (new Object)) " +
                "  (catch ClassCastException e :caught))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals(Keyword.intern(null, "caught"), result);
    }

    @Test
    public void staticMethodThrowsAndCatchSpecificType() {
        // Integer/parseInt with bad input throws NumberFormatException
        String code = "(try " +
                "  (. Integer parseInt \"not-a-number\") " +
                "  (catch NumberFormatException e :caught))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals(Keyword.intern(null, "caught"), result);
    }

    @Test
    public void arityExceptionDuringBoundedLengthOnVariadicApplyTo() {
        String code = "(try " +
                "  (let* [bad-thunk (fn* [] ((fn* ([x] x) ([x y] y)))) " +
                "         lz (new clojure.lang.LazySeq bad-thunk) " +
                "         pair (new clojure.lang.Cons :first lz) " +
                "         variadic-fn (fn* ([a & rest] rest))] " +
                "    (.applyTo variadic-fn pair)) " +
                "  (catch Exception e :caught))";
        Object result = BytecodeDslTestSupport.evalBytecode(code);
        assertEquals(Keyword.intern(null, "caught"), result);
    }
}
