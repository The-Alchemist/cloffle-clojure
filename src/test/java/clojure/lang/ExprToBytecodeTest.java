package clojure.lang;

import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import java.io.StringReader;
import java.math.BigInteger;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import net.javacrumbs.cloffle.bytecode.ExprToBytecode;

/**
 * {@link ExprToBytecode} / {@link CloffleBytecodeRootNode} tests with <b>no</b> {@code clojure.core}
 * load and <b>no</b> Cloffle AST execution ({@link net.javacrumbs.cloffle.compiler.CloffleCompiler}).
 * <p>
 * Forms are limited to what {@link Compiler#macroexpand} and {@link Compiler#analyze} can handle
 * without core-provided macros or vars — e.g. literals, {@code if}, {@code do}, {@code quote}, {@code try},
 * {@code monitor-enter}/{@code monitor-exit} (not the {@code locking} macro), {@code fn*} (not the {@code fn}
 * macro), {@code let*}, {@code letfn*} (not the {@code letfn} macro), {@code loop*}/{@code recur} (via Truffle
 * {@code While}),
 * {@code def}, {@code var}, {@code case*} (not the {@code case} macro), Java interop, and collection literals
 * whose elements need no core.
 * <p>
 * Tests are grouped in {@linkplain RunWith Enclosed} static nested classes by category.
 * <p>
 * Package {@code clojure.lang} for access to {@link Compiler#macroexpand} and {@link Compiler.Expr}.
 * <p>
 * Helpers: {@link BytecodeDslTestSupport}. Source sections and {@code Source} serialization:
 * {@link ExprToBytecodeSourceLocationTest}.
 */
@RunWith(Enclosed.class)
public class ExprToBytecodeTest {

    /** Public static field for {@link VarsDefsAndAssignment#setBangOnStaticField} (Java interop {@code set!}). */
    public static int bytecodeTestMutableStatic = 0;

    /** Literals, collection literals, {@code quote}, {@link BigInt}, regex. */
    public static class LiteralsAndQuoted {

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
        public void quotedEmptyList() {
            Object x = BytecodeDslTestSupport.evalBytecode("(quote ())");
            assertTrue(x instanceof IPersistentCollection);
            assertEquals(0, ((IPersistentCollection) x).count());
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
    }

    /** {@code if}, {@code do}, {@code case*}. */
    public static class ControlFlow {

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
    }

    /** {@link Compiler.KeywordInvokeExpr} — {@code (:kw map)}. */
    public static class KeywordInvoke {

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
    }

    /** {@code let*}, {@code loop*}/{@code recur}, {@code fn*}, {@code letfn*}, closures; bootstrap-style loops. */
    public static class BindingsLoopsAndFunctions {

        @Test
        public void letStarThreeBindings() {
            assertEquals(3L, BytecodeDslTestSupport.evalBytecode("(let* [a 1 b 2 c 3] c)"));
            assertEquals(2L, BytecodeDslTestSupport.evalBytecode("(let* [a 1 b 2 c 3] b)"));
        }

        @Test
        public void loopStarReturnsLastBodyValue() {
            assertEquals(7L, BytecodeDslTestSupport.evalBytecode("(loop* [x 7] x)"));
        }

        @Test
        public void loopStarEmptyBindings() {
            assertEquals(42L, BytecodeDslTestSupport.evalBytecode("(loop* [] 42)"));
        }

        @Test
        public void loopStarRecurBindsAndRepeats() {
            assertEquals(
                    1L,
                    BytecodeDslTestSupport.evalBytecode(
                            "(loop* [x 0] (if (clojure.lang.Util/equiv x 0) (recur 1) x))"));
        }

        @Test
        public void loopStarDoBodyWithRecurInTail() {
            assertEquals(
                    2L,
                    BytecodeDslTestSupport.evalBytecode(
                            "(loop* [n 0] (do (if (clojure.lang.Util/equiv n 0) (recur 2) n)))"));
        }

        @Test
        public void fnStarRecurToMethodHead() {
            assertEquals(
                    1L,
                    BytecodeDslTestSupport.evalBytecode(
                            "((fn* [x] (if (clojure.lang.Util/equiv x 0) (recur 1) x)) 0)"));
        }

        @Test
        public void fnStarRecurWithDoAroundIf() {
            assertEquals(
                    2L,
                    BytecodeDslTestSupport.evalBytecode(
                            "((fn* [n] (do (if (clojure.lang.Util/equiv n 0) (recur 2) n))) 0)"));
        }

        @Test
        public void fnStarZeroArityNoRecurNeeded() {
            assertEquals(1L, BytecodeDslTestSupport.evalBytecode("((fn* [] (if false (recur) 1)))"));
        }

        @Test
        public void loopStarNestedInFnStarRecurBindsToLoop() {
            assertEquals(
                    2L,
                    BytecodeDslTestSupport.evalBytecode(
                            "((fn* [] (loop* [i 0] (if (clojure.lang.Util/equiv i 0) (recur 2) i))) )"));
        }

        @Test
        public void fnStarBodyWithDo() {
            String f = "(fn* ([] (do 1 2 99)))";
            assertEquals(99L, BytecodeDslTestSupport.evalBytecode("(" + f + ")"));
        }

        @Test
        public void fnStarZeroArityInvoke() {
            assertEquals(42L, BytecodeDslTestSupport.evalBytecode("((fn* ([] 42)))"));
        }

        @Test
        public void letStarBindsLocals() {
            assertEquals(1L, BytecodeDslTestSupport.evalBytecode("(let* [a 1] a)"));
            assertEquals(1L, BytecodeDslTestSupport.evalBytecode("(let* [a 1 b a] b)"));
            assertEquals(2L, BytecodeDslTestSupport.evalBytecode("(let* [a 1 b 2] b)"));
        }

        /**
         * {@code letfn*} (not the {@code letfn} macro): local {@code fn*} bindings with mutual recursion wired
         * like {@link net.javacrumbs.cloffle.nodes.LetFnNode} via {@code WireLetFnClosures}.
         */
        @Test
        public void letFnStarSingleBinding() {
            assertEquals(
                    42L,
                    BytecodeDslTestSupport.evalBytecode("(letfn* [id (fn* ([x] x))] (id 42))"));
        }

        @Test
        public void letFnStarMutualRecursionEvenOdd() {
            String code =
                    """
                    (letfn* [even? (fn* ([n] (if (clojure.lang.Util/equiv n 0) true (odd? (clojure.lang.Numbers/minus n 1)))))
                             odd? (fn* ([n] (if (clojure.lang.Util/equiv n 0) false (even? (clojure.lang.Numbers/minus n 1)))))]
                      [(even? 4) (odd? 7)])""";
            Object v = BytecodeDslTestSupport.evalBytecode(code);
            assertTrue(v instanceof clojure.lang.IPersistentVector);
            clojure.lang.IPersistentVector vec = (clojure.lang.IPersistentVector) v;
            assertSame(RT.T, vec.nth(0));
            assertSame(RT.T, vec.nth(1));
        }

        @Test
        public void fnStarUnaryInvoke() {
            assertEquals(99L, BytecodeDslTestSupport.evalBytecode("((fn* ([x] x)) 99)"));
        }

        @Test
        public void letStarClosureCapturesLocal() {
            assertEquals(7L, BytecodeDslTestSupport.evalBytecode("(let* [n 7] ((fn* [] n)))"));
        }

        /**
         * Multi-binding {@code loop*}/{@code recur} like counting macros in {@code core.clj} (several locals advance
         * together).
         */
        @Test
        public void loopStarTwoBindingsRecur() {
            assertEquals(
                    10L,
                    BytecodeDslTestSupport.evalBytecode(
                            "(loop* [x 0 y 10] (if (clojure.lang.Util/equiv x 2) (clojure.lang.Numbers/add x y) (recur (clojure.lang.Numbers/add x 1) (clojure.lang.Numbers/minus y 1))))"));
        }

        /**
         * {@code recur} with {@code clojure.lang.RT/conj} on an accumulator — same idea as {@code core.clj} loops that
         * build collections in the recur step (e.g. {@code (recur (RT/conj coll x) ...)}).
         */
        @Test
        public void loopStarRecurWithRtConjAccumulator() {
            Object v =
                    BytecodeDslTestSupport.evalBytecode(
                            "(loop* [coll nil x 0] (if (clojure.lang.Util/equiv x 2) (clojure.lang.RT/count coll) (recur (clojure.lang.RT/conj coll x) (clojure.lang.Numbers/add x 1))))");
            assertEquals(2L, RT.longCast(v));
        }

        /**
         * Walk a list with {@code RT.next}/{@code RT.first} until the tail — same shape as bootstrap {@code last} in
         * {@code core.clj}.
         */
        @Test
        public void loopStarWalkPersistentListLikeLast() {
            assertEquals(
                    3L,
                    BytecodeDslTestSupport.evalBytecode(
                            "(loop* [s (clojure.lang.RT/list 1 2 3)] (if (clojure.lang.RT/next s) (recur (clojure.lang.RT/next s)) (clojure.lang.RT/first s)))"));
            assertEquals(
                    1L,
                    BytecodeDslTestSupport.evalBytecode(
                            "(loop* [s (clojure.lang.RT/list 1)] (if (clojure.lang.RT/next s) (recur (clojure.lang.RT/next s)) (clojure.lang.RT/first s)))"));
        }

        /**
         * {@code fn*} with required + rest params: {@code recur} must rebind both the last fixed arg and the rest seq
         * (roadmap item 1 — variadic {@code recur} alongside fixed params).
         * <p>
         * Invoke with {@code 0 1 2 3} (four args), not {@code 0 (list 1 2 3)} — otherwise {@code & xs} is a one-element
         * seq whose only cell is the whole list, and the “walk” returns that list. After the last rest cell,
         * {@link clojure.lang.PersistentList#next()} is {@code null}, not {@link clojure.lang.PersistentList#EMPTY}.
         */
        @Test
        public void fnStarRestArgsRecurWalksSeq() {
            assertEquals(
                    3L,
                    BytecodeDslTestSupport.evalBytecode(
                            "((fn* [x & xs] (if (clojure.lang.Util/identical xs nil) x (if (clojure.lang.Util/identical xs clojure.lang.PersistentList/EMPTY) x (recur (clojure.lang.RT/first xs) (clojure.lang.RT/next xs))))) 0 1 2 3)"));
        }
    }

    /** Multi-arity {@code fn*} dispatch and compiler shape. */
    public static class MultiArityFn {

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
         * A mismatch here surfaces as {@code (defn nil ...)} when loading {@code clojure/core.clj}.
         */
        @Test
        public void macroLikeVariadicBindsNameParam() {
            Object name = BytecodeDslTestSupport.evalBytecode(
                    "((fn* [&form &env name & args] name) 'whole {} 'myname 1 2)");
            assertEquals(Symbol.intern("myname"), name);
        }

        /**
         * Nested {@code let*} must not clear {@code fn*} param slots before the tail reads them (regression for
         * {@code clojure.core/defmacro}-sized bodies).
         */
        @Test
        public void fnStarParamSurvivesNestedLetStar() {
            assertEquals(
                    42L,
                    BytecodeDslTestSupport.evalBytecode(
                            "((fn* [x] (let* [a 1] (let* [b 2] (let* [c 3] x)))) 42)"));
        }

        /** Param read after {@code loop*} (fn body uses {@code emitRecurWhileBody} for both params and loops). */
        @Test
        public void fnStarParamReadAfterLoopStar() {
            assertEquals(
                    99L,
                    BytecodeDslTestSupport.evalBytecode(
                            "((fn* [x] (loop* [i 0] (if (clojure.lang.Util/equiv i 2) x"
                                    + " (recur (clojure.lang.Numbers/add i 1))))) 99)"));
        }

        /** {@code recur} to {@code fn*} head with two args (same shape as {@code add-args} in {@code defmacro}). */
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

        /**
         * Full defmacro body shape: prefix loop, fdecl loop, inner fn* closures (add-implicit-args, add-args with
         * recur), seq, decl loop, and final (cons 'defn decl). Bytecode must match AST.
         */
        @Test
        public void defmacroFullBodyMatchesAst() {
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
            Object ast = BytecodeDslTestSupport.evalAst(code);
            Object bc = BytecodeDslTestSupport.evalBytecode(code);
            assertEquals(RT.printString(ast), RT.printString(bc));
        }

        /**
         * Narrowing: name param after prefix+fdecl loops and inner fn closures with recur, then decl loop.
         */
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
            Object ast = BytecodeDslTestSupport.evalAst(code);
            Object bc = BytecodeDslTestSupport.evalBytecode(code);
            assertEquals(RT.printString(ast), RT.printString(bc));
        }

        /** Narrowing: does prefix alone produce the right value? */
        @Test
        public void defmacroPrefixOnlyMatchesAst() {
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
            Object ast = BytecodeDslTestSupport.evalAst(code);
            Object bc = BytecodeDslTestSupport.evalBytecode(code);
            assertEquals(RT.printString(ast), RT.printString(bc));
        }

        /** Narrowing: return [prefix fdecl] after add-args + inner fns, but without decl loop. */
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
            Object ast = BytecodeDslTestSupport.evalAst(code);
            Object bc = BytecodeDslTestSupport.evalBytecode(code);
            assertEquals(RT.printString(ast), RT.printString(bc));
        }

        /** Narrowing: just the decl loop with a known prefix. */
        @Test
        public void declLoopReversesPrefix() {
            // Simulates the decl loop: reverse a list onto another
            String code =
                    "(let* [prefix (clojure.lang.RT/list :a :b :c)"
                            + "       fdecl (clojure.lang.RT/list :x :y)"
                            + "       decl (loop* [p prefix d fdecl]"
                            + "              (if p"
                            + "                (recur (clojure.lang.RT/next p)"
                            + "                       (clojure.lang.RT/cons (clojure.lang.RT/first p) d))"
                            + "                d))]"
                            + "  decl)";
            Object ast = BytecodeDslTestSupport.evalAst(code);
            Object bc = BytecodeDslTestSupport.evalBytecode(code);
            assertEquals(RT.printString(ast), RT.printString(bc));
        }

        /** Narrowing: decl loop after fn* closures in let* bindings. */
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
            Object ast = BytecodeDslTestSupport.evalAst(code);
            Object bc = BytecodeDslTestSupport.evalBytecode(code);
            assertEquals(RT.printString(ast), RT.printString(bc));
        }

        /** Narrowing: does name survive after add-args fn closure is bound? */
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
            Object ast = BytecodeDslTestSupport.evalAst(code);
            Object bc = BytecodeDslTestSupport.evalBytecode(code);
            assertEquals(ast, bc);
        }

        /**
         * {@code clojure.core/defmacro} prefix + {@code fdecl} loops (see {@code core.clj}): bytecode must match AST.
         */
        @Test
        public void defmacroPrefixAndFdeclLoopsMatchAst() {
            // RT + instance? only — no core `list`/`string?`/etc. (bootstrap analyzer context).
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
            Object ast = BytecodeDslTestSupport.evalAst(code);
            Object bc = BytecodeDslTestSupport.evalBytecode(code);
            assertEquals(RT.printString(ast), RT.printString(bc));
        }
    }

    /** {@code try}/{@code catch}/{@code finally}, {@code throw}, monitors, multi-catch, core-like nesting. */
    public static class TryCatchFinallyAndMonitors {

        @Test
        public void tryCatchReturnsTryBodyWhenNoThrow() {
            assertEquals(7L, BytecodeDslTestSupport.evalBytecode("(try 7 (catch Throwable t 0))"));
        }

        @Test
        public void tryFinallyRunsAndReturnsBody() {
            assertEquals(1L, BytecodeDslTestSupport.evalBytecode("(try 1 (finally nil))"));
        }

        /**
         * {@code monitor-enter} / {@code monitor-exit} special forms (used by {@code locking} in
         * {@code clojure.core}). Bytecode uses {@link net.javacrumbs.cloffle.nodes.MonitorRegistry} like the AST.
         */
        @Test
        public void monitorEnterExitWithTryFinallyReturnsBody() {
            assertEquals(
                    42L,
                    BytecodeDslTestSupport.evalBytecode(
                            "(let* [x (Object.)] (do (monitor-enter x) (try 42 (finally (monitor-exit x)))))"));
        }

        @Test
        public void monitorEnterReentrantOnSameObject() {
            assertEquals(
                    1L,
                    BytecodeDslTestSupport.evalBytecode(
                            "(let* [x (Object.)] (do (monitor-enter x) (monitor-enter x) (monitor-exit x) (monitor-exit x) 1))"));
        }

        @Test
        public void tryCatchFinallyWhenNoThrow() {
            assertEquals(5L, BytecodeDslTestSupport.evalBytecode("(try 5 (catch Throwable t 0) (finally nil))"));
        }

        @Test
        public void throwCaughtInTry() {
            Object v = BytecodeDslTestSupport.evalBytecode(
                    "(try (throw (new Exception \"boom\")) (catch Exception e :caught))");
            assertEquals(Keyword.intern("caught"), v);
        }

        /**
         * {@code try} with multiple {@code catch} clauses (order matches JVM: first assignable handler wins), as in
         * {@code core.clj} error handling.
         */
        @Test
        public void tryMultipleCatchClausesMostSpecificWins() {
            assertEquals(
                    Keyword.intern(null, "ia"),
                    BytecodeDslTestSupport.evalBytecode(
                            "(try (throw (new java.lang.IllegalArgumentException \"x\")) (catch java.lang.IllegalArgumentException e :ia) (catch java.lang.Exception e :ex))"));
            assertEquals(
                    Keyword.intern(null, "ex"),
                    BytecodeDslTestSupport.evalBytecode(
                            "(try (throw (new java.lang.RuntimeException \"x\")) (catch java.lang.IllegalArgumentException e :ia) (catch java.lang.Exception e :ex))"));
        }

        /**
         * {@code finally} runs when the {@code try} body throws and an outer {@code catch} handles it — common
         * {@code with-open}/{@code locking}-style structure in {@code core.clj}.
         */
        @Test
        public void tryFinallyRunsWhenTryBodyThrowsAndCatchHandles() {
            ExprToBytecodeTest.bytecodeTestMutableStatic = 0;
            assertNull(
                    BytecodeDslTestSupport.evalBytecode(
                            "(try (throw (new Exception \"x\")) (catch Exception e nil) (finally (set! clojure.lang.ExprToBytecodeTest/bytecodeTestMutableStatic 7)))"));
            assertEquals(7, ExprToBytecodeTest.bytecodeTestMutableStatic);
        }

        /**
         * Nested {@code try}/{@code finally} under {@code monitor-enter}/{@code monitor-exit} (same nesting as
         * expanded {@code locking}).
         */
        @Test
        public void nestedTryFinallyUnderMonitorEnterExit() {
            assertEquals(
                    99L,
                    BytecodeDslTestSupport.evalBytecode(
                            "(let* [x (Object.)] (do (monitor-enter x) (try (try 99 (finally nil)) (finally (monitor-exit x)))))"));
        }
    }

    /** {@code def}, {@code var}, dynamic vars, {@code set!} on vars and fields. */
    public static class VarsDefsAndAssignment {

        /**
         * {@link clojure.lang.Var#pushThreadBindings} / {@link clojure.lang.Var#popThreadBindings} with {@code try}/
         * {@code finally} (same shape as {@code binding} after macroexpand). No {@code clojure.core} {@code binding}
         * macro — exercises bytecode {@link clojure.lang.Compiler.StaticMethodExpr} + {@link clojure.lang.Compiler.TryExpr}.
         */
        /**
         * {@link BytecodeDslTestSupport#evalBytecode} wraps evaluation with {@link net.javacrumbs.cloffle.Clojure#pushEvalThreadBindings()},
         * so {@code *ns*} is thread-bound like {@link net.javacrumbs.cloffle.compiler.CloffleCompiler} loads — no explicit
         * {@code pushThreadBindings} in the form.
         */
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

        /**
         * Same thread-local read as {@link #varPushThreadBindingsThreadLocalRead} but with the {@code binding} macro’s
         * outer {@code (let [])} shape: empty {@code let*}, then {@code pushThreadBindings} and {@code try}/{@code finally}
         * {@code popThreadBindings} (roadmap item 2).
         */
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

        /**
         * {@link clojure.lang.Var#set(Object)} via {@code set!} on a {@link clojure.lang.Compiler.VarExpr} while a
         * thread binding is active ({@code WriteVar} in bytecode), then {@link clojure.lang.Var#popThreadBindings}
         * restores the root. Requires {@code def ^:dynamic} so {@code set!} analyzes to {@code VarExpr}, not a local.
         */
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

        /**
         * {@code (def name (fn* ...))} then invoke the var by symbol — typical {@code core.clj} definition shape.
         */
        @Test
        public void defFnStarThenInvokeByName() {
            String sym = "expr_to_bytecode_corefn_" + System.nanoTime();
            assertEquals(
                    42L,
                    BytecodeDslTestSupport.evalBytecode(
                            "(do (def " + sym + " (fn* [n] (clojure.lang.Numbers/add n 1))) (" + sym + " 41))"));
        }

        /**
         * Multi-arity fn* defined via def, then invoked — same shape as core.clj {@code defn} with
         * multiple arities (e.g. {@code =}).
         */
        @Test
        public void defMultiArityFnStarThenInvoke() {
            String sym = "expr_to_bytecode_multi_" + System.nanoTime();
            assertEquals(
                    true,
                    BytecodeDslTestSupport.evalBytecode(
                            "(do (def " + sym + " (fn* ([x] true) ([x y] (clojure.lang.Util/equiv x y)))) (" + sym + " 1 1))"));
        }

        /**
         * Multi-arity fn* with variadic and recur — same shape as {@code =} in core.clj. Bytecode closure
         * must preserve arity info for applyTo.
         */
        @Test
        public void defMultiArityVariadicRecurFnStarMatchesAst() {
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
            Object ast = BytecodeDslTestSupport.evalAst(code);
            Object bc = BytecodeDslTestSupport.evalBytecode(code);
            assertEquals(RT.printString(ast), RT.printString(bc));
        }

        /**
         * Deeply nested closures: outer fn → let → named fn → lazy-seq-like inner fn.
         * The inner fn (depth 3) references a binding from depth 1 through depth 2.
         * Models the concat pattern from core.clj where an inner cat fn is called
         * recursively from a lazy-seq closure.
         */
        @Test
        public void deeplyNestedClosureReachesGrandparentBinding() {
            // Outer fn → inner closure that captures a let binding from the outer fn.
            // Tests that emitClosureCopies correctly copies ancestor locals.
            String code =
                    "((fn* [x] "
                            + "(let* [v x] "
                            + "  ((fn* [] v)))) "
                            + "42)";
            assertEquals(42L, BytecodeDslTestSupport.evalBytecode(code));
        }

        @Test
        public void triplyNestedClosureReachesGrandparentViaParentCopy() {
            // Depth 0: top, Depth 1: outer fn, Depth 2: middle fn, Depth 3: inner fn
            // Inner fn (depth 3) needs `v` which is a let-binding at depth 1.
            // Middle fn (depth 2) must copy `v` into its frame so depth 3 can read it.
            String code =
                    "((fn* [x] "
                            + "(let* [v x] "
                            + "  ((fn* [y] ((fn* [] v))) 99))) "
                            + "42)";
            assertEquals(42L, BytecodeDslTestSupport.evalBytecode(code));
        }

        /**
         * fn* with an inner fn* in let* metadata position — models the :inline fn in defn = from core.clj.
         * The inner fn must not read uninitialized frame slots from the outer fn's frame.
         */
        @Test
        public void fnInsideLetMetadataDoesNotReadOuterFrame() {
            String code =
                    "(let* [inline-fn (fn* [x y] (clojure.lang.RT/list (quote .) (quote clojure.lang.Util) (quote equiv) x y))]"
                            + "  (inline-fn (quote a) (quote b)))";
            Object ast = BytecodeDslTestSupport.evalAst(code);
            Object bc = BytecodeDslTestSupport.evalBytecode(code);
            assertEquals(RT.printString(ast), RT.printString(bc));
        }

        @Test
        public void namedFnSelfReferenceThroughClosureLazySeqPattern() {
            // Models concat's (fn cat [xys zs] (lazy-seq ...cat...))
            // where lazy-seq creates a 0-arg closure that calls cat recursively.
            // cat's thisBinding is stored after CreateClosure captures the frame.
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
            // Models concat's ([x y] ...) arity:
            // named fn that creates a lazy-seq (0-arg fn*) which calls itself recursively.
            // The lazy-seq closure must access the named fn's self-reference from the parent frame.
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
        public void multiArityNamedFnWithClosuresInEachArityMethod() {
            // Models concat: multi-arity named fn where each arity creates closures
            // that reference the fn recursively. The 4-arg arity creates a nested named fn (cat)
            // with its own closures.
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
        public void namedFnSelfReferenceInInnerClosure() {
            // named fn cat, inner 0-arg closure calls cat.
            // Tests that the inner closure can access cat's self-reference.
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

        @Test
        public void multiArityFnClosureCapturesParam() {
            // Multi-arity fn, one arity has a closure that captures a parameter.
            // Simplest case: 2 arities, closure in the 1-arg arity captures x.
            String code =
                    "((fn* ([] 0) ([x] ((fn* [] x)))) 42)";
            assertEquals(42L, BytecodeDslTestSupport.evalBytecode(code));
        }

        @Test
        public void multiArityFnClosureCapturesLetBinding() {
            // Multi-arity fn, closure captures a let binding (not a param).
            String code =
                    "((fn* ([] 0) ([x] (let* [v x] ((fn* [] v))))) 42)";
            assertEquals(42L, BytecodeDslTestSupport.evalBytecode(code));
        }

        @Test
        public void multiArityFnClosureCapturesOuterLetBinding() {
            // Outer let binding captured through a multi-arity fn's closure.
            String code =
                    "(let* [outer 99] ((fn* ([] 0) ([x] ((fn* [] outer)))) 42))";
            assertEquals(99L, BytecodeDslTestSupport.evalBytecode(code));
        }

        @Test
        public void multiArityNamedFnWithNestedNamedFnAndLazySeqLikeClosure() {
            // Models concat [x y & zs] with nested named fn cat and lazy-seq-like closure.
            // concat is multi-arity. The [x y & zs] arity defines cat via let*,
            // cat creates a 0-arg closure that calls cat recursively.
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
            // Same as above but uses (new clojure.lang.LazySeq (fn* [] ...)) instead of
            // eager ((fn* [] ...)) — matches the real lazy-seq macro expansion.
            // The LazySeq is forced by clojure.lang.RT/seq.
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

        @Test
        public void setBangOnStaticField() {
            ExprToBytecodeTest.bytecodeTestMutableStatic = 0;
            assertEquals(
                    9L,
                    BytecodeDslTestSupport.evalBytecode("(set! clojure.lang.ExprToBytecodeTest/bytecodeTestMutableStatic 9)"));
            assertEquals(9, ExprToBytecodeTest.bytecodeTestMutableStatic);
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
    }

    /**
     * {@code reify*} / {@code deftype*} ({@link clojure.lang.Compiler.NewInstanceExpr}) — MVP bytecode path for
     * {@code core.clj} (full Clojure semantics deferred).
     */
    public static class ReifyAndDeftypeStar {

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
    }

    /** Java interop: {@code new}, static/instance methods and fields, {@code import*}, {@code RT}, {@code Math}. */
    public static class JavaInterop {

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

        /** Static call pattern used throughout {@code core.clj} ({@code Math}, {@code RT}, etc.). */
        @Test
        public void javaMathAbsLong() {
            assertEquals(9L, BytecodeDslTestSupport.evalBytecode("(java.lang.Math/abs -9)"));
        }

        /**
         * Chained {@code RT} accessors like {@code (first (next ...))} definitions in bootstrap {@code core.clj}.
         */
        @Test
        public void rtFirstNextOnList() {
            assertEquals(
                    2L,
                    BytecodeDslTestSupport.evalBytecode(
                            "(clojure.lang.RT/first (clojure.lang.RT/next (clojure.lang.RT/seq (clojure.lang.RT/list 1 2 3))))"));
        }

        /** {@code instance?} on Java types beyond {@code String} (type predicates in {@code core.clj}). */
        @Test
        public void instanceOfJavaLangNumber() {
            assertSame(RT.T, BytecodeDslTestSupport.evalBytecode("(instance? java.lang.Number 5)"));
            assertSame(RT.F, BytecodeDslTestSupport.evalBytecode("(instance? java.lang.Number \"s\")"));
        }
    }

    /**
     * Analyzer placeholders: {@link ExprToBytecode} throws with the same {@link IllegalArgumentException} message as
     * {@link Compiler.UnresolvedVarExpr#eval()} on the JVM compiler.
     */
    public static class AnalyzerPlaceholders {

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

    /** {@link Compiler.MetaExpr}, bytecode root naming. */
    public static class MetadataAndInfrastructure {

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
}
