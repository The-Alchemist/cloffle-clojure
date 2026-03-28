package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.Assert.fail;

/**
 * Paired tests: each expression is evaluated against real Clojure first to
 * establish ground truth, then against Cloffle to verify matching behavior.
 *
 * Tests are grouped by language feature and progress from simple to complex.
 */
public class CloffleBehaviorTest {

    private Context context;

    @Before
    public void setUp() {
        context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build();
    }

    @After
    public void tearDown() {
        context.close();
    }

    private Object clojure(String expr) {
        return mikera.cljutils.Clojure.eval(expr);
    }

    private Object cloffle(String expr) {
        Value result = context.eval("cloffle", expr);
        if (result.isNull()) return null;
        if (result.isNumber()) {
            if (result.fitsInLong()) return result.asLong();
            if (result.fitsInDouble()) return result.asDouble();
        }
        if (result.isBoolean()) return result.asBoolean();
        if (result.isString()) return result.asString();
        return result.as(Object.class);
    }

    private void assertBothEqual(String expr) {
        Object expected = normalize(clojure(expr));
        Object actual = normalize(cloffle(expr));
        assertThat(actual).as("Expression: %s", expr).isEqualTo(expected);
    }

    private static Object normalize(Object val) {
        if (val instanceof Integer i) return i.longValue();
        if (val instanceof Short s) return s.longValue();
        if (val instanceof Byte b) return b.longValue();
        return val;
    }

    // ========== Arithmetic ==========

    @Test
    public void addTwoLongs() {
        assertBothEqual("(+ 1 2)");
    }

    @Test
    public void addThreeLongs() {
        assertBothEqual("(+ 1 2 3)");
    }

    @Test
    public void addLongAndDouble() {
        assertBothEqual("(+ 1 2.5)");
    }

    @Test
    public void subtractLongs() {
        assertBothEqual("(- 10 3)");
    }

    @Test
    public void multiplyLongs() {
        assertBothEqual("(* 6 7)");
    }

    @Test
    public void nestedArithmetic() {
        assertBothEqual("(+ (* 3 4) (- 10 5))");
    }

    @Test
    public void negativeNumbers() {
        assertBothEqual("(+ -1 -2)");
    }

    @Test
    public void zeroArithmetic() {
        assertBothEqual("(+ 0 0)");
    }

    @Test
    public void decrement() {
        assertBothEqual("(dec 10)");
    }

    @Test
    public void increment() {
        assertBothEqual("(inc 10)");
    }

    // ========== Comparisons ==========

    @Test
    public void lessThan() {
        assertBothEqual("(< 1 2)");
    }

    @Test
    public void lessThanFalse() {
        assertBothEqual("(< 2 1)");
    }

    @Test
    public void greaterThan() {
        assertBothEqual("(> 5 3)");
    }

    @Test
    public void equalLongs() {
        assertBothEqual("(= 42 42)");
    }

    @Test
    public void equalLongsNotEqual() {
        assertBothEqual("(= 42 43)");
    }

    @Test
    public void lessThanOrEqual() {
        assertBothEqual("(<= 3 3)");
    }

    @Test
    public void greaterThanOrEqual() {
        assertBothEqual("(>= 5 5)");
    }

    // ========== If / conditionals ==========

    @Test
    public void ifTrue() {
        assertBothEqual("(if true 1 2)");
    }

    @Test
    public void ifFalse() {
        assertBothEqual("(if false 1 2)");
    }

    @Test
    public void ifNilIsFalsy() {
        assertBothEqual("(if nil 1 2)");
    }

    @Test
    public void ifZeroIsTruthy() {
        assertBothEqual("(if 0 1 2)");
    }

    @Test
    public void ifEmptyStringIsTruthy() {
        assertBothEqual("(if \"\" 1 2)");
    }

    @Test
    public void nestedIf() {
        assertBothEqual("(if (< 1 2) (if (> 3 4) 10 20) 30)");
    }

    @Test
    public void ifWithComparison() {
        assertBothEqual("(if (= 1 1) \"yes\" \"no\")");
    }

    // ========== Let bindings ==========

    @Test
    public void simpleLet() {
        assertBothEqual("(let [x 42] x)");
    }

    @Test
    public void letWithArithmetic() {
        assertBothEqual("(let [x 10 y 20] (+ x y))");
    }

    @Test
    public void letShadowing() {
        assertBothEqual("(let [x 1] (let [x 2] x))");
    }

    @Test
    public void letShadowingOuterStillAccessible() {
        // Clojure returns 5.0 (double), Cloffle returns 5L (long) due to implicit cast in +
        Object clj = clojure("(let [a 3.0] (+ (let [a 2] a) a))");
        assertThat(clj).isEqualTo(5.0);
        Object cfl = cloffle("(let [a 3.0] (+ (let [a 2] a) a))");
        assertThat(((Number) cfl).doubleValue()).isEqualTo(5.0);
    }

    @Test
    public void letWithMultipleBindings() {
        assertBothEqual("(let [a 1 b 2 c 3] (+ a b c))");
    }

    @Test
    public void letDependentBindings() {
        assertBothEqual("(let [a 5 b (+ a 1)] b)");
    }

    // ========== Do blocks ==========

    @Test
    public void doReturnsLast() {
        assertBothEqual("(do 1 2 3)");
    }

    @Test
    public void doWithSideEffectsAndResult() {
        assertBothEqual("(do (+ 1 1) (+ 2 2) (+ 3 3))");
    }

    @Test
    public void nestedDo() {
        assertBothEqual("(do (do 1 2) (do 3 4))");
    }

    // ========== Functions ==========

    @Test
    public void identityFn() {
        assertBothEqual("((fn [x] x) 42)");
    }

    @Test
    public void fnTwoArgs() {
        assertBothEqual("((fn [a b] (+ a b)) 10 20)");
    }

    @Test
    public void fnThreeArgs() {
        assertBothEqual("((fn [a b c] (+ a b c)) 1 2 3)");
    }

    @Test
    public void fnWithBody() {
        assertBothEqual("((fn [x] (let [y (+ x 1)] (* y 2))) 5)");
    }

    @Test
    public void closureOverLet() {
        assertBothEqual("(let [x 10] ((fn [y] (+ x y)) 5))");
    }

    @Test
    public void closureOverPrimitiveDoubleLet() {
        Object clj = clojure("((fn [^double x] ((fn [] (+ x 1.0)))) 1.25)");
        Object cfl = cloffle("((fn [^double x] ((fn [] (+ x 1.0)))) 1.25)");
        assertThat(((Number) cfl).doubleValue()).isCloseTo(((Number) clj).doubleValue(), within(1e-10));
    }

    @Test
    public void fnReturningString() {
        assertBothEqual("((fn [] \"hello\"))");
    }

    @Test
    public void fnReturningBool() {
        assertBothEqual("((fn [x] (< x 0)) -1)");
    }

    @Test
    public void fnReturningBoolFalse() {
        assertBothEqual("((fn [x] (< x 0)) 1)");
    }

    // ========== Def/Defn ==========

    @Test
    public void defnThenCall() {
        assertBothEqual("(do (defn sq [x] (* x x)) (sq 7))");
    }

    @Test
    public void defnWithIf() {
        assertBothEqual("(do (defn abs [x] (if (< x 0) (- 0 x) x)) (abs -5))");
    }

    @Test
    public void defnWithLet() {
        assertBothEqual("(do (defn f [x] (let [y (+ x 1)] (* y y))) (f 4))");
    }

    @Test
    public void defnRecursive() {
        assertBothEqual("(do (defn fact [n] (if (<= n 1) 1 (* n (fact (- n 1))))) (fact 6))");
    }

    @Test
    public void defnMutuallyUsingDef() {
        assertBothEqual("(do (def base 10) (defn add-base [x] (+ x base)) (add-base 5))");
    }

    @Test
    public void defString() {
        assertBothEqual("(do (def greeting \"hello\") greeting)");
    }

    @Test
    public void defBoolean() {
        assertBothEqual("(do (def flag true) (if flag 1 0))");
    }

    // ========== Loop / Recur ==========

    @Test
    public void loopSum1to10() {
        assertBothEqual("(loop [sum 0 cnt 10] (if (= cnt 0) sum (recur (+ cnt sum) (dec cnt))))");
    }

    @Test
    public void loopCountdown() {
        assertBothEqual("(loop [n 5 acc 1] (if (= n 0) acc (recur (dec n) (* acc n))))");
    }

    @Test
    public void loopWithComparison() {
        assertBothEqual("(loop [i 0] (if (>= i 10) i (recur (inc i))))");
    }

    // ========== Java Interop ==========

    @Test
    public void instanceMethodToUpperCase() {
        assertBothEqual("(.toUpperCase \"hello world\")");
    }

    @Test
    public void instanceMethodLength() {
        // Clojure returns Integer, Cloffle returns Long (all ints promoted to long)
        Object clj = clojure("(.length \"hello\")");
        assertThat(clj).isEqualTo(5);
        Object cfl = cloffle("(.length \"hello\")");
        assertThat(((Number) cfl).intValue()).isEqualTo(5);
    }

    @Test
    public void instanceMethodSubstring() {
        assertBothEqual("(.substring \"hello world\" 6)");
    }

    @Test
    public void staticFieldMathPI() {
        assertBothEqual("Math/PI");
    }

    @Test
    public void staticFieldMathE() {
        assertBothEqual("Math/E");
    }

    @Test
    public void staticFieldIntMaxValue() {
        // Clojure returns Integer, Cloffle returns Long via StaticFieldNode
        Object clj = clojure("Integer/MAX_VALUE");
        assertThat(clj).isEqualTo(Integer.MAX_VALUE);
        Object cfl = cloffle("Integer/MAX_VALUE");
        assertThat(((Number) cfl).intValue()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    public void staticFieldLongMaxValue() {
        assertBothEqual("Long/MAX_VALUE");
    }

    // ========== Keyword invoke ==========

    @Test
    public void keywordLookupInMap() {
        assertBothEqual("(do (def m {:a 1 :b 2 :c 3}) (:b m))");
    }

    @Test
    public void keywordLookupFirstKey() {
        assertBothEqual("(do (def m {:x 99}) (:x m))");
    }

    // ========== Constants / Literals ==========

    @Test
    public void longLiteral() {
        assertBothEqual("42");
    }

    @Test
    public void negativeLong() {
        assertBothEqual("-1");
    }

    @Test
    public void doubleLiteral() {
        assertBothEqual("3.14");
    }

    @Test
    public void booleanTrue() {
        assertBothEqual("true");
    }

    @Test
    public void booleanFalse() {
        assertBothEqual("false");
    }

    @Test
    public void stringLiteral() {
        assertBothEqual("\"hello\"");
    }

    @Test
    public void emptyString() {
        assertBothEqual("\"\"");
    }

    @Test
    public void charLiteral() {
        // Clojure returns Character; Polyglot may wrap char as string
        Object clj = clojure("\\a");
        assertThat(clj).isEqualTo('a');
        Object cfl = cloffle("\\a");
        assertThat(cfl.toString()).isEqualTo("a");
    }

    // ========== Combined / complex ==========

    @Test
    public void fibonacciIterative() {
        assertBothEqual("(loop [a 0 b 1 n 10] (if (= n 0) a (recur b (+ a b) (dec n))))");
    }

    @Test
    public void fibonacciRecursive() {
        assertBothEqual("(do (defn fib [n] (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2))))) (fib 10))");
    }

    @Test
    public void gcdRecursive() {
        assertBothEqual("(do (defn gcd [a b] (if (= b 0) a (gcd b (- a (* b (long (/ a b))))))) (gcd 48 18))");
    }

    @Test
    public void isPrime() {
        assertBothEqual("(do (defn prime? [n] (loop [i 2] (if (> (* i i) n) true (if (= 0 (rem n i)) false (recur (inc i)))))) (prime? 17))");
    }

    @Test
    public void isPrimeNot() {
        assertBothEqual("(do (defn prime? [n] (loop [i 2] (if (> (* i i) n) true (if (= 0 (rem n i)) false (recur (inc i)))))) (prime? 15))");
    }

    @Test
    public void nestedFnCalls() {
        assertBothEqual("(do (defn double-it [x] (* x 2)) (defn triple-it [x] (* x 3)) (+ (double-it 5) (triple-it 5)))");
    }

    @Test
    public void fnPassedResult() {
        assertBothEqual("(do (defn apply-twice [f x] (f (f x))) (defn add1 [x] (+ x 1)) (apply-twice add1 5))");
    }

    @Test
    public void letWithFnAndRecursion() {
        assertBothEqual("(let [n 5] (loop [i n acc 1] (if (<= i 1) acc (recur (dec i) (* acc i)))))");
    }

    @Test
    public void mapLookupChained() {
        assertBothEqual("(do (def config {:port 8080 :host \"localhost\"}) (:port config))");
    }

    @Test
    public void defnWithMapArg() {
        assertBothEqual("(do (defn get-port [m] (:port m)) (get-port {:port 3000}))");
    }

    @Test
    public void stringMethodsChained() {
        // Clojure returns Integer, Cloffle returns Long
        Object clj = clojure("(.length (.toUpperCase \"hello\"))");
        assertThat(clj).isEqualTo(5);
        Object cfl = cloffle("(.length (.toUpperCase \"hello\"))");
        assertThat(((Number) cfl).intValue()).isEqualTo(5);
    }

    // === Quote ===

    @Test
    public void quoteSymbol() {
        Object clj = clojure("'foo");
        assertThat(clj).isInstanceOf(clojure.lang.Symbol.class);
        assertThat(clj.toString()).isEqualTo("foo");
        Object cfl = cloffle("'foo");
        assertThat(cfl.toString()).isEqualTo("foo");
    }

    @Test
    public void quoteList() {
        Object clj = clojure("'(1 2 3)");
        assertThat(clj.toString()).isEqualTo("(1 2 3)");
        Object cfl = cloffle("'(1 2 3)");
        assertThat(cfl).isNotNull();
    }

    @Test
    public void quoteInDef() {
        Object clj = clojure("(do (def x 'hello) x)");
        assertThat(clj.toString()).isEqualTo("hello");
        Object cfl = cloffle("(do (def x 'hello) x)");
        assertThat(cfl.toString()).isEqualTo("hello");
    }

    @Test
    public void quoteNumber() {
        assertBothEqual("'42");
    }

    @Test
    public void quoteKeyword() {
        Object clj = clojure("':foo");
        assertThat(clj.toString()).isEqualTo(":foo");
        Object cfl = cloffle("':foo");
        assertThat(cfl.toString()).isEqualTo(":foo");
    }

    @Test
    public void quoteString() {
        assertBothEqual("'\"hello\"");
    }

    @Test
    public void quoteNil() {
        Object clj = clojure("'nil");
        assertThat(clj).isNull();
        Object cfl = cloffle("'nil");
        assertThat(cfl).isNull();
    }

    @Test
    public void quoteNestedInExpr() {
        Object clj = clojure("(do (defn identity-fn [x] x) (identity-fn 'hello))");
        assertThat(clj.toString()).isEqualTo("hello");
        Object cfl = cloffle("(do (defn identity-fn [x] x) (identity-fn 'hello))");
        assertThat(cfl.toString()).isEqualTo("hello");
    }

    // === Non-literal collections ===

    @Test
    public void mapWithExprValue() {
        assertBothEqual("(:a {:a (+ 1 2) :b 3})");
    }

    @Test
    public void mapWithExprKey() {
        assertBothEqual("(:x {(if true :x :y) (+ 10 20)})");
    }

    @Test
    public void vectorWithExprs() {
        Object clj = clojure("(do (def v [(+ 1 2) (* 3 4)]) (v 0))");
        assertThat(clj).isEqualTo(3L);
        Value cflVal = context.eval("cloffle", "[(+ 1 2) (* 3 4)]");
        assertThat(cflVal.hasArrayElements()).isTrue();
        assertThat(cflVal.getArraySize()).isEqualTo(2);
        assertThat(cflVal.getArrayElement(0).asLong()).isEqualTo(3L);
        assertThat(cflVal.getArrayElement(1).asLong()).isEqualTo(12L);
    }

    @Test
    public void setWithExprs() {
        Value cflVal = context.eval("cloffle", "#{(+ 1 2) (* 3 4)}");
        assertThat(cflVal.hasArrayElements()).isTrue();
        assertThat(cflVal.getArraySize()).isEqualTo(2);
    }

    // === Try/Catch/Finally ===

    @Test
    public void tryNoException() {
        assertBothEqual("(try (+ 1 2))");
    }

    @Test
    public void tryCatchNoThrow() {
        assertBothEqual("(try (+ 1 2) (catch Exception e 42))");
    }

    @Test
    public void tryFinally() {
        assertBothEqual("(try (+ 1 2) (finally (+ 3 4)))");
    }

    @Test
    public void tryCatchWithThrow() {
        assertBothEqual("(try (.substring \"hello\" 100) (catch StringIndexOutOfBoundsException e 99))");
    }

    @Test
    public void tryCatchFinallyWithThrow() {
        assertBothEqual("(try (.substring \"hello\" 100) (catch StringIndexOutOfBoundsException e 99) (finally (+ 0 0)))");
    }

    @Test
    public void tryCatchUsesExceptionBinding() {
        String expr = "(try (.substring \"hello\" 100) (catch StringIndexOutOfBoundsException e (.getMessage e)))";
        Object clj = clojure(expr);
        assertThat((String) clj).contains("100");
        Object cfl = cloffle(expr);
        assertThat((String) cfl).contains("100");
    }

    @Test
    public void tryMultipleCatches() {
        assertBothEqual("(try (.substring \"hello\" 100) (catch ArithmeticException e 1) (catch StringIndexOutOfBoundsException e 2))");
    }

    // === the-var (#') ===

    @Test
    public void theVarReturnsVar() {
        Object clj = clojure("(do (def myval 42) (var myval))");
        assertThat(clj).isInstanceOf(clojure.lang.Var.class);
        Object cfl = cloffle("(do (def myval 42) #'myval)");
        assertThat(cfl.toString()).contains("myval");
    }

    @Test
    public void theVarWithQuoteSyntax() {
        Object cfl = cloffle("(do (def foo 99) #'foo)");
        assertThat(cfl.toString()).contains("foo");
    }

    // === def without init ===

    @Test
    public void defWithoutInit() {
        Object cfl = cloffle("(def x)");
        assertThat(cfl.toString()).contains("x");
    }

    @Test
    public void defWithoutInitLeavesVarUnbound() {
        assertBothEqual("(do (def x-unbound-compat) (bound? #'x-unbound-compat))");
    }

    @Test
    public void varRedefinitionIsVisibleImmediately() {
        assertBothEqual("""
            (do
              (def redefine-var-compat 1)
              redefine-var-compat
              (def redefine-var-compat 2)
              redefine-var-compat)""");
    }

    @Test
    public void alterVarRootIsVisibleImmediately() {
        assertBothEqual("""
            (do
              (def alter-root-compat 1)
              (alter-var-root #'alter-root-compat (constantly 2))
              alter-root-compat)""");
    }

    @Test
    public void dynamicBindingUnwindsOnException() {
        assertBothEqual("""
            (do
              (def ^:dynamic *dyn-compat* 1)
              (try
                (binding [*dyn-compat* 2]
                  (throw (Exception. "boom")))
                (catch Exception _ nil))
              *dyn-compat*)""");
    }

    @Test
    public void nsReloadAndMutableNamespaceOpsStayCompatible() {
        assertBothEqual("(do (require 'clojure.string :reload) (clojure.string/upper-case \"abc\"))");
        assertBothEqual("""
            (do
              (create-ns 'cloffle.compat.ns.unmap)
              (binding [*ns* (the-ns 'cloffle.compat.ns.unmap)]
                (clojure.core/refer 'clojure.core)
                (def temp-unmap-compat 1)
                (ns-unmap *ns* 'temp-unmap-compat)
                (contains? (ns-publics *ns*) 'temp-unmap-compat)))""");
    }

    @Test
    public void topLevelFormsStopAfterError() {
        try {
            cloffle("""
                (def should-be-defined 1)
                (throw (RuntimeException. "boom"))
                (def should-not-be-defined 2)""");
            fail("Expected top-level error to be propagated");
        } catch (RuntimeException ignored) {
            // expected
        }

        Object firstDef = cloffle("(resolve 'should-be-defined)");
        Object secondDefBound = cloffle("(bound? #'should-not-be-defined)");
        assertThat(firstDef).isNotNull();
        assertThat(secondDefBound).isEqualTo(false);
    }

    // === instance? ===

    @Test
    public void instanceCheckTrue() {
        assertBothEqual("(instance? String \"hello\")");
    }

    @Test
    public void instanceCheckFalse() {
        assertBothEqual("(instance? String 42)");
    }

    @Test
    public void instanceCheckWithExpression() {
        assertBothEqual("(instance? Number (+ 1 2))");
    }

    // === throw ===

    @Test
    public void throwCaughtByTry() {
        assertBothEqual("(try (throw (Exception. \"boom\")) (catch Exception e (.getMessage e)))");
    }

    @Test
    public void throwRuntimeException() {
        assertBothEqual("(try (throw (RuntimeException. \"oops\")) (catch RuntimeException e (.getMessage e)))");
    }

    // === new ===

    @Test
    public void newStringBuilder() {
        assertBothEqual("(.toString (StringBuilder. \"hello\"))");
    }

    // === instance-field ===

    @Test
    public void instanceFieldAccess() {
        Object cfl = cloffle("(.sym :foo)");
        assertThat(cfl.toString()).isEqualTo("foo");
    }

    // === reify ===

    @Test
    public void reifyRunnable() {
        Object cfl = cloffle("(let [r (reify Runnable (run [this] 42))] (.run r))");
        assertThat(cfl).isNull();
    }

    @Test
    public void reifyCallable() {
        Object cfl = cloffle("(.call (reify java.util.concurrent.Callable (call [this] 99)))");
        assertThat(((Number) cfl).intValue()).isEqualTo(99);
    }

    // === case ===

    @Test
    public void caseMatchFirst() {
        assertBothEqual("(let [x :a] (case x :a 1 :b 2 99))");
    }

    @Test
    public void caseMatchSecond() {
        assertBothEqual("(let [x :b] (case x :a 1 :b 2 99))");
    }

    @Test
    public void caseDefault() {
        assertBothEqual("(let [x :c] (case x :a 1 :b 2 99))");
    }

    @Test
    public void caseWithStringKeys() {
        assertBothEqual("(let [x \"hello\"] (case x \"hello\" 1 \"world\" 2 99))");
    }

    @Test
    public void caseWithIntKeys() {
        assertBothEqual("(let [x 2] (case x 1 10 2 20 3 30))");
    }

    @Test
    public void caseDoesNotMatchDifferentNumericType() {
        assertBothEqual("(let [x 1] (case x 1.0 10 1 20 30))");
    }

    @Test
    public void variadicFnNoRestArgs() {
        assertBothEqual("((fn [a & rest] a) 42)");
    }

    @Test
    public void variadicFnWithRestArgs() {
        assertBothEqual("((fn [a & rest] (count rest)) 1 2 3 4)");
    }

    @Test
    public void variadicFnRestIsSeq() {
        assertBothEqual("((fn [a & rest] (first rest)) 1 2 3)");
    }

    @Test
    public void defnVariadic() {
        assertBothEqual("(do (defn my-list [& args] (count args)) (my-list 1 2 3))");
    }

    @Test
    public void multiArityFn() {
        assertBothEqual("(do (defn greet ([x] x) ([x y] (+ x y))) (greet 10 20))");
    }

    @Test
    public void multiArityFnSingleArg() {
        assertBothEqual("(do (defn greet ([x] x) ([x y] (+ x y))) (greet 42))");
    }

    @Test
    public void multiArityWithVariadic() {
        assertBothEqual("(do (defn f ([x] x) ([x & rest] (+ x (count rest)))) (f 10 20 30))");
    }

    @Test
    public void variadicApplyStyle() {
        assertBothEqual("(do (defn add-all [& nums] (apply + nums)) (add-all 1 2 3 4 5))");
    }

    @Test
    public void variadicRestNilWhenEmpty() {
        assertBothEqual("((fn [a & rest] (nil? rest)) 42)");
    }

    @Test
    public void recurInFnBody() {
        assertBothEqual("((fn [n acc] (if (<= n 1) acc (recur (- n 1) (* acc n)))) 5 1)");
    }

    @Test
    public void defnWithRecurNoLoop() {
        assertBothEqual("(do (defn countdown [n] (if (<= n 0) 0 (recur (- n 1)))) (countdown 10))");
    }

    @Test
    public void fnAsValueInMap() {
        assertBothEqual("(let [m {:f (fn [x] (+ x 1))}] ((:f m) 10))");
    }

    @Test
    public void fnAsValueInDef() {
        assertBothEqual("(do (def my-fn (fn [x] (* x 2))) (my-fn 5))");
    }

    @Test
    public void fnLiteralNotExecuted() {
        Object result = cloffle("(let [m {:f (fn [x] x)}] (:f m))");
        assertThat(result).isNotNull();
    }

    @Test
    public void immediateFnInvocation() {
        assertBothEqual("((fn [x y] (+ x y)) 3 4)");
    }

    // === core.clj interop: the-var + instance methods ===

    @Test
    public void theVarSetMacro() {
        Object cfl = cloffle("(do (defn test-macro-fn [] nil) (. (var test-macro-fn) (setMacro)) (.isMacro (var test-macro-fn)))");
        assertThat(cfl).isEqualTo(true);
    }

    @Test
    public void theVarAlterMeta() {
        Object cfl = cloffle("(do (def test-alter-meta-val 1) (. (var test-alter-meta-val) (alterMeta (fn [m] (assoc m :doc \"hello\")) nil)) (:doc (meta (var test-alter-meta-val))))");
        assertThat(cfl).isEqualTo("hello");
    }

    @Test
    public void fnPassedToInstanceCall() {
        Object cfl = cloffle("(do (def test-alter-fn-val 1) (.alterMeta (var test-alter-fn-val) (fn [m] (assoc m :tag \"x\")) nil) (:tag (meta (var test-alter-fn-val))))");
        assertThat(cfl).isEqualTo("x");
    }

    @Test
    public void nilNotLeakedAsNilNode() {
        Object cfl = cloffle("(if nil 1 2)");
        assertThat(cfl).isEqualTo(2L);
    }

    @Test
    public void nilInInstanceCall() {
        assertBothEqual("(.toString (StringBuilder. \"hello\"))");
    }

    @Test
    public void fnApplyTo() {
        assertBothEqual("(do (defn add-all [& nums] (apply + nums)) (add-all 1 2 3 4 5))");
    }

    // === Lazy seq / ISeq handling ===

    @Test
    public void mapReturnsLazySeq() {
        assertBothEqual("(first (map inc [1 2 3]))");
    }

    @Test
    public void filterReturnsLazySeq() {
        assertBothEqual("(first (filter odd? [1 2 3 4 5]))");
    }

    @Test
    public void rangeReturnsLazySeq() {
        assertBothEqual("(first (range 10))");
    }

    @Test
    public void lazySeqDoesNotStackOverflow() {
        assertBothEqual("(do (defn my-range [n] (if (<= n 0) nil (cons n (lazy-seq (my-range (dec n)))))) (first (my-range 5)))");
    }

    @Test
    public void takeFromLazySeq() {
        assertBothEqual("(apply + (take 5 (range 100)))");
    }

    @Test
    public void consReturnsSeq() {
        assertBothEqual("(first (cons 0 [1 2]))");
    }

    @Test
    public void seqOfVector() {
        assertBothEqual("(first (seq [10 20 30]))");
    }

    // === StackOverflow investigation ===

    @Test
    public void reduceWithNativeFn() {
        assertBothEqual("(reduce + 0 [1 2 3 4 5])");
    }

    @Test
    public void reduceWithCloffleFn() {
        assertBothEqual("(do (defn my-add [a b] (+ a b)) (reduce my-add 0 [1 2 3 4 5]))");
    }

    @Test
    public void reduceWithCloffleFnLarger() {
        assertBothEqual("(do (defn my-add2 [a b] (+ a b)) (reduce my-add2 0 (range 50)))");
    }

    @Test
    public void mergeSmallMap() {
        Object clj = clojure("(merge {:a 1} {:b 2})");
        assertThat(clj.toString()).contains(":a").contains(":b");
        Object cfl = cloffle("(merge {:a 1} {:b 2})");
        assertThat(cfl).isNotNull();
    }

    @Test
    public void alterMetaSimple() {
        Object cfl = cloffle("(do (def alter-test-val 1) (.alterMeta (var alter-test-val) (fn [m] (assoc m :x 1)) nil) (:x (meta (var alter-test-val))))");
        assertThat(cfl).isEqualTo(1L);
    }

    @Test
    public void alterMetaMerge() {
        Object cfl = cloffle("(do (def amt-val 1) (alter-meta! (var amt-val) merge {:doc \"hello\"}) (:doc (meta (var amt-val))))");
        assertThat(cfl).isEqualTo("hello");
    }

    @Test
    public void alterMetaAssoc() {
        Object cfl = cloffle("(do (def ama-val 1) (alter-meta! (var ama-val) assoc :doc \"hi\" :added \"1.0\") (:doc (meta (var ama-val))))");
        assertThat(cfl).isEqualTo("hi");
    }

    @Test
    public void addDocAndMetaPattern() {
        Object cfl = cloffle("(do (def adm-val 1) (alter-meta! (var adm-val) merge (assoc {:added \"1.0\"} :doc \"test doc\")) (:doc (meta (var adm-val))))");
        assertThat(cfl).isEqualTo("test doc");
    }

    // === Native core function interop ===

    @Test
    public void strConcatenation() {
        assertBothEqual("(str \"hello\" \" \" \"world\")");
    }

    @Test
    public void countVector() {
        assertBothEqual("(count [1 2 3 4 5])");
    }

    @Test
    public void nthVector() {
        assertBothEqual("(second [10 20 30])");
    }

    @Test
    public void conj() {
        assertBothEqual("(count (conj [1 2] 3))");
    }

    @Test
    public void assocMap() {
        assertBothEqual("(:b (assoc {:a 1} :b 2))");
    }

    @Test
    public void getFromMap() {
        assertBothEqual("(get {:a 1 :b 2} :b)");
    }

    @Test
    public void notFn() {
        assertBothEqual("(not false)");
    }

    @Test
    public void andOr() {
        assertBothEqual("(or false nil 42)");
    }

    // === Multi-form sequential evaluation ===

    @Test
    public void crossFnCalls() {
        assertBothEqual("(do (defn sq [x] (* x x)) (defn inc-sq [x] (sq (+ x 1))) (inc-sq 4))");
    }

    @Test
    public void multiDefnChainedCalls() {
        assertBothEqual("(do (defn triple [x] (* x 3)) (defn triple-inc [x] (+ (triple x) 1)) (triple-inc 4))");
    }

    @Test
    public void defnChain() {
        assertBothEqual("(do (defn a [x] (+ x 1)) (defn b [x] (a (a x))) (defn c [x] (b (b x))) (c 0))");
    }

    // === Higher-order with native fns ===

    @Test
    public void cloffleFnCallingNativeArgFn() {
        assertBothEqual("(do (defn apply-fn [f x] (f x)) (apply-fn inc 5))");
    }

    @Test
    public void cloffleFnCallingNativeArgFnTwoArgs() {
        assertBothEqual("(do (defn apply2 [f a b] (f a b)) (apply2 + 10 20))");
    }

    @Test
    public void nativeFnCallingCloffleFn() {
        assertBothEqual("(do (defn double-it [x] (* x 2)) (apply double-it [5]))");
    }

    @Test
    public void mapWithCloffleFn() {
        assertBothEqual("(do (defn sq [x] (* x x)) (apply + (map sq [1 2 3 4])))");
    }

    @Test
    public void filterWithCloffleFn() {
        assertBothEqual("(do (defn big? [x] (> x 3)) (count (filter big? [1 2 3 4 5])))");
    }

    @Test
    public void sortByWithCloffleFn() {
        assertBothEqual("(do (defn neg [x] (- 0 x)) (first (sort-by neg [3 1 2])))");
    }

    @Test
    public void dynamicBindingVisibleInsideNativeHigherOrderCall() {
        assertBothEqual("""
            (do
              (def ^:dynamic *dynamic-map-value* 1)
              (binding [*dynamic-map-value* 2]
                (first (map (fn [_] *dynamic-map-value*) [0]))))""");
    }

    @Test
    public void closuresCreatedInLoopCaptureDistinctValues() {
        assertBothEqual("""
            (let [fs (loop [i 0 acc []]
                       (if (= i 3)
                         acc
                         (recur (inc i) (conj acc (fn [] i)))))]
              (apply str (map (fn [f] (f)) fs)))""");
    }

    @Test
    public void closureCapturesValueBeforeLaterShadowing() {
        assertBothEqual("(let [x 1 f (fn [] x) x 2] (f))");
    }

    // === doseq / loop-over-seq (run_test.clj form #3 repro) ===

    @Test
    public void doseqSimple() {
        assertBothEqual("(do (def acc (atom 0)) (doseq [x [1 2 3]] (swap! acc + x)) @acc)");
    }

    @Test
    public void doseqOverRange() {
        assertBothEqual("(do (def acc2 (atom 0)) (doseq [x (range 5)] (swap! acc2 + x)) @acc2)");
    }

    @Test
    public void doseqReturnsNil() {
        Object clj = clojure("(doseq [x [1 2 3]] x)");
        assertThat(clj).isNull();
        Object cfl = cloffle("(doseq [x [1 2 3]] x)");
        assertThat(cfl).isNull();
    }

    // === doseq decomposition: isolate the failure ===

    @Test
    public void atomSwap() {
        assertBothEqual("(do (def a (atom 0)) (swap! a + 1) @a)");
    }

    @Test
    public void atomDeref() {
        assertBothEqual("@(atom 42)");
    }

    @Test
    public void swapReturnValue() {
        assertBothEqual("(swap! (atom 0) + 5)");
    }

    @Test
    public void resetAtom() {
        assertBothEqual("(do (def a4 (atom 0)) (reset! a4 99) @a4)");
    }

    @Test
    public void loopOverSeqManual() {
        assertBothEqual("(do (def a3 (atom 0)) (loop [s (seq [1 2 3])] (when s (swap! a3 + (first s)) (recur (next s)))) @a3)");
    }

    @Test
    public void whenWithSeq() {
        Object clj = clojure("(when (seq [1 2 3]) :yes)");
        assertThat(clj.toString()).isEqualTo(":yes");
        Object cfl = cloffle("(when (seq [1 2 3]) :yes)");
        assertThat(cfl.toString()).isEqualTo(":yes");
    }

    @Test
    public void seqOnVector() {
        assertBothEqual("(first (seq [10 20 30]))");
    }

    @Test
    public void nextOnSeq() {
        assertBothEqual("(first (next (seq [10 20 30])))");
    }

    @Test
    public void chunkedSeqCheck() {
        assertBothEqual("(chunked-seq? (seq [1 2 3]))");
    }

    // === apply with qualified var (run_test.clj form #4 repro) ===

    @Test
    public void applyPlus() {
        assertBothEqual("(apply + [1 2 3])");
    }

    @Test
    public void letWithApply() {
        assertBothEqual("(let [result (apply + [1 2 3])] result)");
    }

    @Test
    public void letWithApplyStr() {
        assertBothEqual("(let [s (apply str [\"a\" \"b\" \"c\"])] s)");
    }

    @Test
    public void applyWithLeadingArgs() {
        assertBothEqual("(apply + 1 2 [3 4])");
    }

    @Test
    public void applyCloffleVariadicFnWithLeadingArgs() {
        assertBothEqual("(do (defn add-all2 [& xs] (apply + xs)) (apply add-all2 1 2 [3 4]))");
    }

    // === int coercion for Java static methods (run_test.clj System/exit issue) ===

    @Test
    public void staticMethodWithIntLiteral() {
        // Integer.bitCount(int) - literal 0 or -1
        assertBothEqual("(Integer/bitCount 0)");
        assertBothEqual("(Integer/bitCount -1)");
    }

    @Test
    public void staticMethodWithIfResult() {
        // Same pattern as run_test.clj: (System/exit (if (test/successful? summary) 0 -1))
        // Use Integer/bitCount instead of System/exit so we can verify
        assertBothEqual("(Integer/bitCount (if true 0 -1))");
        assertBothEqual("(Integer/bitCount (if false 0 -1))");
    }

    // ========== Static method calls ==========

    @Test
    public void binaryStaticMathMaxLongs() {
        assertBothEqual("(Math/max 3 7)");
    }

    @Test
    public void binaryStaticMathMinLongs() {
        assertBothEqual("(Math/min 3 7)");
    }

    @Test
    public void binaryStaticMathPow() {
        Object clj = clojure("(Math/pow 2.0 10.0)");
        assertThat(clj).isEqualTo(1024.0);
        Object cfl = cloffle("(Math/pow 2.0 10.0)");
        assertThat(((Number) cfl).doubleValue()).isEqualTo(1024.0);
    }

    @Test
    public void binaryStaticMathAtan2() {
        assertBothEqual("(Math/atan2 1.0 1.0)");
    }

    @Test
    public void binaryStaticLongBoxedCoercion() {
        assertBothEqual("(Math/max (+ 1 2) (+ 3 4))");
    }

    // ========== Reflector migration: GenericStaticCallNode ==========

    @Test
    public void genericStaticStringValueOf() {
        Object clj = clojure("(String/valueOf true)");
        assertThat(clj).isEqualTo("true");
        Object cfl = cloffle("(String/valueOf true)");
        assertThat(cfl).isEqualTo("true");
    }

    // ========== Reflector migration: InstanceCallNode ==========

    @Test
    public void instanceCallSubstringTwoArgs() {
        assertBothEqual("(.substring \"hello world\" 0 5)");
    }

    @Test
    public void instanceCallCharAt() {
        Object clj = clojure("(.charAt \"hello\" 0)");
        assertThat(clj).isEqualTo('h');
        Object cfl = cloffle("(.charAt \"hello\" 0)");
        assertThat(cfl.toString()).isEqualTo("h");
    }

    @Test
    public void instanceCallReplace() {
        assertBothEqual("(.replace \"hello\" \"l\" \"r\")");
    }

    @Test
    public void instanceCallContains() {
        assertBothEqual("(.contains \"hello world\" \"world\")");
    }

    @Test
    public void instanceCallStartsWith() {
        assertBothEqual("(.startsWith \"hello\" \"he\")");
    }

    @Test
    public void instanceCallEndsWith() {
        assertBothEqual("(.endsWith \"hello\" \"lo\")");
    }

    @Test
    public void instanceCallOnStringBuilder() {
        assertBothEqual("(.toString (.append (StringBuilder. \"hello\") \" world\"))");
    }

    @Test
    public void instanceCallIndexOfWithCharCoercion() {
        assertBothEqual("(do (require '[clojure.string :as s]) (s/index-of (StringBuffer. \"tacos\") \\c))");
        assertBothEqual("(do (require '[clojure.string :as s]) (s/index-of (StringBuffer. \"tacos\") \\o 2))");
        assertBothEqual("(do (require '[clojure.string :as s]) (s/last-index-of (StringBuffer. \"banana\") \\n))");
    }

    @Test
    public void genericStaticCallCharOverloadValueOf() {
        // Ensures static overload resolution keeps char semantics (\"a\" not \"97\").
        assertBothEqual("(String/valueOf \\a)");
    }

    @Test
    public void genericStaticCallCharToIntCoercionBitCount() {
        assertBothEqual("(Integer/bitCount \\A)");
    }

    @Test
    public void constructorCharToIntCoercionAtomicInteger() {
        assertBothEqual("(.get (java.util.concurrent.atomic.AtomicInteger. \\a))");
    }

    @Test
    public void protocolInvokeCharArgumentNumericParity() {
        assertBothEqual("(do (defprotocol PCharArg (pca [x n])) (deftype PImpl [] PCharArg (pca [x n] (long n))) (pca (PImpl.) \\a))");
    }

    @Test
    public void narrowedLongOutOfRangeFailureParity() {
        assertBothEqual("(try ((fn [^long x] x) 9223372036854775808N) (catch Exception e (.getSimpleName (class e))))");
    }

    @Test
    public void narrowedDoubleCastsRatioParity() {
        assertBothEqual("((fn [^double x] x) 1/2)");
    }

    @Test
    public void nilToPrimitiveInteropFailureParity() {
        assertBothEqual("(try (Math/abs nil) (catch Exception e (.getSimpleName (class e))))");
        assertBothEqual("(try (.charAt \"abc\" nil) (catch Exception e (.getSimpleName (class e))))");
    }

    // ========== Reflector migration: NewNode ==========

    @Test
    public void newStringBuilderNoArgs() {
        assertBothEqual("(.toString (StringBuilder.))");
    }

    @Test
    public void newArrayList() {
        assertBothEqual("(.size (java.util.ArrayList.))");
    }

    @Test
    public void newExceptionWithMessage() {
        assertBothEqual("(.getMessage (Exception. \"boom\"))");
    }

    @Test
    public void newHashMap() {
        assertBothEqual("(.isEmpty (java.util.HashMap.))");
    }

    // ========== Reflective host interop ==========

    @Test
    public void hostInteropMethodCall() {
        assertBothEqual("(let [s \"hello world\"] (.toUpperCase s))");
    }

    @Test
    public void hostInteropMethodWithArg() {
        assertBothEqual("(let [s \"hello world\"] (.substring s 6))");
    }

    @Test
    public void hostInteropMethodWithTwoArgs() {
        assertBothEqual("(let [s \"hello world\"] (.substring s 0 5))");
    }

    @Test
    public void hostInteropFieldAccess() {
        Object cfl = cloffle("(let [k :foo] (.sym k))");
        assertThat(cfl.toString()).isEqualTo("foo");
    }

    // ========== Primitive frame slot kinds (regression: let/fn/loop with primitives) ==========
    // These exercises the optimization where LocalBinding.getPrimitiveType() is used to
    // set FrameSlotKind up front instead of Illegal, avoiding transferToInterpreterAndInvalidate
    // on first write. Behavior must remain identical.

    @Test
    public void letPrimitiveLongInBody() {
        assertBothEqual("(let [x 42] (inc x))");
    }

    @Test
    public void letPrimitiveDoubleInBody() {
        Object clj = clojure("(let [x 3.14] (+ x 1.0))");
        Object cfl = cloffle("(let [x 3.14] (+ x 1.0))");
        assertThat(((Number) cfl).doubleValue()).isCloseTo(((Number) clj).doubleValue(), within(1e-10));
    }

    @Test
    public void letPrimitiveBooleanInBody() {
        assertBothEqual("(let [x true] (and x false))");
    }

    @Test
    public void letPrimitiveMixedBindings() {
        assertBothEqual("(let [a 10 b 3.14 c true] (if c (+ a 1) 0))");
    }

    @Test
    public void fnWithPrimitiveTypeHintLong() {
        assertBothEqual("((fn [^long x] (inc x)) 42)");
    }

    @Test
    public void fnWithPrimitiveTypeHintDouble() {
        Object clj = clojure("((fn [^double x] (+ x 1.0)) 3.14)");
        Object cfl = cloffle("((fn [^double x] (+ x 1.0)) 3.14)");
        assertThat(((Number) cfl).doubleValue()).isCloseTo(((Number) clj).doubleValue(), within(1e-10));
    }

    @Test
    public void letNonPrimitiveInitStillWorks() {
        assertBothEqual("(let [x (identity 42)] (inc x))");
    }

    @Test
    public void fnParameterSlotCanChangeTypeAcrossInvocations() {
        assertBothEqual("""
            (do
              (defn param-type-shift [x] x)
              (and (number? (param-type-shift 1))
                   (keyword? (param-type-shift :keyword))))""");
    }

    @Test
    public void loopWithPrimitiveBindings() {
        assertBothEqual("(loop [i 0 acc 0] (if (>= i 5) acc (recur (inc i) (+ acc i))))");
    }

    @Test
    public void loopWithPrimitiveDoubleBindingOnRecur() {
        Object clj = clojure("(loop [x 1.5 n 2] (if (zero? n) x (recur (+ x 0.5) (dec n))))");
        Object cfl = cloffle("(loop [x 1.5 n 2] (if (zero? n) x (recur (+ x 0.5) (dec n))))");
        assertThat(((Number) cfl).doubleValue()).isCloseTo(((Number) clj).doubleValue(), within(1e-10));
    }

    @Test
    public void loopPrimitiveBindingCanBecomeObjectOnRecur() {
        assertBothEqual("(keyword? (loop [x 0 i 0] (if (zero? i) (recur :done 1) x)))");
    }

    @Test
    public void nestedLetPrimitiveShadowing() {
        assertBothEqual("(let [x 1] (let [x 2] (+ x 10)))");
    }

    // ---- defmacro ----

    @Test
    public void defmacroSameEval() {
        // Evaluate Cloffle first to ensure the macro isn't pre-defined
        // by the clojure() call (they share JVM-level namespaces).
        String expr = """
            (do
              (defmacro assert-demo! [pred msg]
                `(when-not ~pred
                   (throw (RuntimeException. ~msg))))
              (try
                (assert-demo! false "boom")
                :no-error
                (catch RuntimeException e
                  (.getMessage e))))""";
        Object actual = normalize(cloffle(expr));
        Object expected = normalize(clojure(expr));
        assertThat(actual).as("Expression: %s", expr).isEqualTo(expected);
    }

    @Test
    public void defmacroAcrossTopLevelForms() {
        Object cfl = cloffle("""
            (defmacro my-unless2 [pred body]
              `(if ~pred nil ~body))
            (my-unless2 true 42)""");
        Object clj = clojure("""
            (do
              (defmacro my-unless2 [pred body]
                `(if ~pred nil ~body))
              (my-unless2 true 42))""");
        assertThat(normalize(cfl)).as("defmacro across top-level forms").isEqualTo(normalize(clj));
    }

    @Test
    public void defmacroInDoSimpleExpansion() {
        String expr = """
            (do
              (defmacro double-it-m [x]
                `(+ ~x ~x))
              (double-it-m 21))""";
        Object actual = normalize(cloffle(expr));
        Object expected = normalize(clojure(expr));
        assertThat(actual).as("defmacro-in-do simple expansion").isEqualTo(expected);
    }

    @Test
    public void defmacroInDoWithWhenNot() {
        String expr = """
            (do
              (defmacro guard! [pred msg]
                `(when-not ~pred
                   (throw (RuntimeException. ~msg))))
              (try
                (guard! (> 1 2) "expected failure")
                :no-throw
                (catch RuntimeException e
                  (.getMessage e))))""";
        Object actual = normalize(cloffle(expr));
        Object expected = normalize(clojure(expr));
        assertThat(actual).as("defmacro-in-do with when-not").isEqualTo(expected);
    }

    @Test
    public void defmacroInDoUsedByDefn() {
        String expr = """
            (do
              (defmacro inc-m [x]
                `(+ ~x 1))
              (defn apply-inc-m [n]
                (inc-m n))
              (apply-inc-m 41))""";
        Object actual = normalize(cloffle(expr));
        Object expected = normalize(clojure(expr));
        assertThat(actual).as("defmacro-in-do used by defn").isEqualTo(expected);
    }

    @Test
    public void defmacroInDoSuccessPath() {
        String expr = """
            (do
              (defmacro when-pos [x body]
                `(when (pos? ~x) ~body))
              (when-pos 5 "positive"))""";
        Object actual = cloffle(expr);
        assertThat(actual).isEqualTo("positive");
    }

    @Test
    public void defmacroInDoNilPath() {
        String expr = """
            (do
              (defmacro when-pos2 [x body]
                `(when (pos? ~x) ~body))
              (when-pos2 -1 "positive"))""";
        Object actual = cloffle(expr);
        assertThat(actual).isNull();
    }

    @Test
    public void defmacroInNestedDo() {
        String expr = """
            (do
              (do
                (defmacro add3-m [x]
                  `(+ ~x 3)))
              (add3-m 39))""";
        Object actual = normalize(cloffle(expr));
        Object expected = normalize(clojure(expr));
        assertThat(actual).as("defmacro in nested do").isEqualTo(expected);
    }

    @Test
    public void twoDefmacrosInSameDo() {
        String expr = """
            (do
              (defmacro first-m [x] `(+ ~x 1))
              (defmacro second-m [x] `(* ~x 2))
              (+ (first-m 10) (second-m 10)))""";
        Object actual = normalize(cloffle(expr));
        Object expected = normalize(clojure(expr));
        assertThat(actual).as("two defmacros in same do").isEqualTo(expected);
    }

    @Test
    public void defmacroInDoCallingAnotherMacro() {
        String expr = """
            (do
              (defmacro base-m [x] `(+ ~x 1))
              (defmacro composed-m [x] `(base-m (base-m ~x)))
              (composed-m 10))""";
        Object actual = normalize(cloffle(expr));
        Object expected = normalize(clojure(expr));
        assertThat(actual).as("defmacro calling another defmacro in same do").isEqualTo(expected);
    }

    // ---- defrecord across top-level forms ----

    @Test
    public void defrecordThenConstructAcrossTopLevelForms() {
        Object clj = clojure("""
            (do
              (defrecord Point2 [x y])
              (.x (->Point2 3 4)))""");
        Object cfl = cloffle("""
            (defrecord Point2 [x y])
            (.x (->Point2 3 4))""");
        assertThat(normalize(cfl)).as("defrecord across top-level forms").isEqualTo(normalize(clj));
    }

    // ---- deftype across top-level forms ----

    @Test
    public void deftypeThenUseAcrossTopLevelForms() {
        Object clj = clojure("""
            (do
              (deftype Counter2 [^:volatile-mutable cnt]
                clojure.lang.IDeref
                (deref [_] cnt))
              @(Counter2. 99))""");
        Object cfl = cloffle("""
            (deftype Counter2 [^:volatile-mutable cnt]
              clojure.lang.IDeref
              (deref [_] cnt))
            @(Counter2. 99)""");
        assertThat(normalize(cfl)).as("deftype across top-level forms").isEqualTo(normalize(clj));
    }

    // ---- alias across top-level forms ----

    @Test
    public void aliasAcrossTopLevelForms() {
        Object clj = clojure("""
            (do
              (require 'clojure.string)
              (alias 'st2 'clojure.string)
              (st2/upper-case "hello"))""");
        Object cfl = cloffle("""
            (require 'clojure.string)
            (alias 'st2 'clojure.string)
            (st2/upper-case "hello")""");
        assertThat(cfl).as("alias across top-level forms").isEqualTo(clj);
    }

    // ========== Macro behavior (paired Clojure vs Cloffle) ==========

    @Test
    public void macroGensymPaired() {
        assertBothEqual("""
            (do
              (defmacro gensym-paired [& body]
                `(let [tmp# 42] (+ tmp# ~@body)))
              (gensym-paired 8))""");
    }

    @Test
    public void macroSplicingUnquotePaired() {
        assertBothEqual("""
            (do
              (defmacro sum-all-p [& exprs] `(+ ~@exprs))
              (sum-all-p 1 2 3 4 5))""");
    }

    @Test
    public void macroVariadicPaired() {
        assertBothEqual("""
            (do
              (defmacro log-ret [msg & body] `(do ~@body))
              (log-ret "test" (+ 1 2) (* 3 4)))""");
    }

    @Test
    public void macroExpandsToLetPaired() {
        assertBothEqual("""
            (do
              (defmacro bind-add [a b]
                `(let [x# ~a y# ~b] (+ x# y#)))
              (bind-add 17 25))""");
    }

    @Test
    public void macroExpandsToTryPaired() {
        assertBothEqual("""
            (do
              (defmacro safe-div-p [a b]
                `(try (/ ~a ~b) (catch ArithmeticException ~'e -1)))
              (safe-div-p 10 0))""");
    }

    @Test
    public void macroDestructuringPaired() {
        String expr = """
            (do
              (defmacro swap-pair-p [[a b]] `[~b ~a])
              (vec (swap-pair-p [1 2])))""";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl.toString()).as("macro destructuring").isEqualTo(clj.toString());
    }

    @Test
    public void macroReturnsLiteralPaired() {
        assertBothEqual("""
            (do
              (defmacro always-42-p [] 42)
              (+ (always-42-p) 8))""");
    }

    @Test
    public void macroReturnsNilPaired() {
        String expr = """
            (do
              (defmacro return-nil-p [] nil)
              (return-nil-p))""";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl).as("macro returning nil").isEqualTo(clj);
    }

    @Test
    public void recursiveMacroPaired() {
        assertBothEqual("""
            (do
              (defmacro count-down-p [n]
                (if (pos? n) `(+ 1 (count-down-p ~(dec n))) 0))
              (count-down-p 5))""");
    }

    @Test
    public void multipleCallsIsolatedPaired() {
        assertBothEqual("""
            (do
              (defmacro make-adder-p [n]
                `(fn [x#] (+ x# ~n)))
              (let [add3 (make-adder-p 3)
                    add7 (make-adder-p 7)]
                (+ (add3 10) (add7 10))))""");
    }

    @Test
    public void threeLevelMacroNestingPaired() {
        assertBothEqual("""
            (do
              (defmacro lvl3 [x] `(+ ~x 1))
              (defmacro lvl2 [x] `(lvl3 (+ ~x 10)))
              (defmacro lvl1 [x] `(lvl2 (+ ~x 100)))
              (lvl1 0))""");
    }

    @Test
    public void macroWithDocstringPaired() {
        assertBothEqual("""
            (do
              (defmacro documented-p "doubles arg" [x] `(+ ~x ~x))
              (documented-p 21))""");
    }

    @Test
    public void macroCompileTimeValidationPaired() {
        assertBothEqual("""
            (do
              (defmacro strict-pos-p [x]
                (when-not (number? x)
                  (throw (IllegalArgumentException. "not a number")))
                `(inc ~x))
              (strict-pos-p 5))""");
    }

    @Test
    public void macroResolveAtExpansionTimePaired() {
        String expr = """
            (do
              (defmacro resolved-p [sym]
                (let [v (resolve sym)] (str v)))
              (resolved-p +))""";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl.toString()).as("macro resolve at expansion time")
                .isEqualTo(clj.toString());
    }

    // ---- Core macros paired ----

    @Test
    public void doseqPaired() {
        assertBothEqual("(do (def doseq-acc (atom 0)) (doseq [i [1 2 3 4 5]] (swap! doseq-acc + i)) @doseq-acc)");
    }

    @Test
    public void forPaired() {
        String expr = "(vec (for [x [1 2 3] y [10 20]] (+ x y)))";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl.toString()).as("for comprehension").isEqualTo(clj.toString());
    }

    @Test
    public void whenLetPaired() {
        assertBothEqual("(when-let [x (get {:a 42} :a)] (+ x 8))");
    }

    @Test
    public void whenLetNilPaired() {
        String expr = "(when-let [x (get {:a 42} :b)] (+ x 8))";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl).as("when-let nil").isEqualTo(clj);
    }

    @Test
    public void ifLetPaired() {
        assertBothEqual("(if-let [x (get {:a 42} :a)] (+ x 8) -1)");
    }

    @Test
    public void ifLetElsePaired() {
        assertBothEqual("(if-let [x (get {:a 42} :b)] (+ x 8) -1)");
    }

    @Test
    public void threadLastPaired() {
        assertBothEqual("(->> [1 2 3 4 5] (filter odd?) (map inc) (reduce +))");
    }

    @Test
    public void asThreadPaired() {
        assertBothEqual("(as-> 0 v (+ v 10) (+ v 20) (* v 2))");
    }

    @Test
    public void letfnMutualRecursionPaired() {
        String expr = """
            (letfn [(even-p? [n] (if (zero? n) true (odd-p? (dec n))))
                    (odd-p? [n] (if (zero? n) false (even-p? (dec n))))]
              [(even-p? 10) (odd-p? 7)])""";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl.toString()).as("letfn mutual recursion").isEqualTo(clj.toString());
    }

    @Test
    public void someThreadPaired() {
        assertBothEqual("(some-> {:a {:b 42}} :a :b inc)");
    }

    @Test
    public void someThreadNilPaired() {
        String expr = "(some-> {:a {:b 42}} :c :b inc)";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl).as("some-> nil short-circuit").isEqualTo(clj);
    }

    @Test
    public void someThreadLastPaired() {
        assertBothEqual("(some->> [1 2 3] (map inc) (filter even?) (reduce +))");
    }

    @Test
    public void condThreadPaired() {
        assertBothEqual("(cond-> 1 true inc false (* 42) true (* 3))");
    }

    @Test
    public void condThreadLastPaired() {
        assertBothEqual("(cond->> 1 true inc false (+ 42) true (+ 3))");
    }

    @Test
    public void dotimesWithSideEffectPaired() {
        assertBothEqual("(do (def dotimes-acc (atom 0)) (dotimes [i 5] (swap! dotimes-acc + i)) @dotimes-acc)");
    }

    @Test
    public void whileLoopPaired() {
        assertBothEqual("""
            (do
              (def while-cnt (atom 0))
              (def while-sum (atom 0))
              (while (< @while-cnt 5)
                (swap! while-sum + @while-cnt)
                (swap! while-cnt inc))
              @while-sum)""");
    }

    @Test
    public void macroexpand1Paired() {
        String expr = "(str (macroexpand-1 '(when true 42)))";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl.toString()).as("macroexpand-1").isEqualTo(clj.toString());
    }

    @Test
    public void macroexpandFullPaired() {
        String expr = "(str (macroexpand '(when true 42)))";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl.toString()).as("macroexpand").isEqualTo(clj.toString());
    }

    // ---- macroexpand on more core macros ----

    @Test
    public void macroexpand1AndPaired() {
        String expr = "(str (macroexpand-1 '(and true false 42)))";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl.toString()).as("macroexpand-1 and").isEqualTo(clj.toString());
    }

    @Test
    public void macroexpand1OrPaired() {
        String expr = "(str (macroexpand-1 '(or nil false 42)))";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl.toString()).as("macroexpand-1 or").isEqualTo(clj.toString());
    }

    @Test
    public void macroexpand1CondPaired() {
        String expr = "(str (macroexpand-1 '(cond true 1 false 2)))";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl.toString()).as("macroexpand-1 cond").isEqualTo(clj.toString());
    }

    @Test
    public void macroexpand1ThreadFirstPaired() {
        String expr = "(str (macroexpand-1 '(-> x (a 1) (b 2))))";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl.toString()).as("macroexpand-1 ->").isEqualTo(clj.toString());
    }

    @Test
    public void macroexpand1ThreadLastPaired() {
        String expr = "(str (macroexpand-1 '(->> x (a 1) (b 2))))";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl.toString()).as("macroexpand-1 ->>").isEqualTo(clj.toString());
    }

    @Test
    public void macroexpand1DotoPaired() {
        String expr = """
            (do
              (let [expanded (macroexpand-1 '(doto (java.util.HashMap.) (.put :a 1)))]
                (first expanded)))""";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl.toString()).as("macroexpand-1 doto head symbol").isEqualTo(clj.toString());
    }

    // ---- &form and &env ----

    @Test
    public void macroFormAccessible() {
        assertBothEqual("""
            (do
              (defmacro form-count-test [x]
                (count &form))
              (form-count-test 42))""");
    }

    @Test
    public void macroEnvKeysPaired() {
        String expr = """
            (do
              (defmacro env-keys-test []
                `~(vec (keys &env)))
              (let [a 1 b 2]
                (count (env-keys-test))))""";
        assertBothEqual(expr);
    }

    // ---- macro that returns a map / set / vector ----

    @Test
    public void macroReturnsMapPaired() {
        assertBothEqual("""
            (do
              (defmacro make-map [k v] `{~k ~v})
              (:x (make-map :x 42)))""");
    }

    @Test
    public void macroReturnsSetPaired() {
        assertBothEqual("""
            (do
              (defmacro make-set [& xs] `#{~@xs})
              (contains? (make-set 1 2 3) 2))""");
    }

    @Test
    public void macroReturnsVectorPaired() {
        assertBothEqual("""
            (do
              (defmacro make-vec [& xs] `[~@xs])
              (nth (make-vec 10 20 30) 1))""");
    }

    // ---- macros with keyword args pattern ----

    @Test
    public void macroKeywordArgPatternPaired() {
        assertBothEqual("""
            (do
              (defmacro with-opts [& {:keys [x y] :or {x 0 y 0}}]
                `(+ ~x ~y))
              (with-opts :x 10 :y 32))""");
    }

    // ---- multiple arities via variadic ----

    @Test
    public void macroMultiAritySimulationPaired() {
        assertBothEqual("""
            (do
              (defmacro flex [a & [b]]
                (if b `(+ ~a ~b) `(* ~a ~a)))
              (+ (flex 5) (flex 3 7)))""");
    }

    // ---- macro generating defn ----

    @Test
    public void macroGeneratesDefnPaired() {
        assertBothEqual("""
            (do
              (defmacro def-adder [name n]
                `(defn ~name [x#] (+ x# ~n)))
              (def-adder add10 10)
              (add10 32))""");
    }

    @Test
    public void macroGeneratesMultipleDefnsPaired() {
        assertBothEqual("""
            (do
              (defmacro def-ops [prefix]
                `(do
                   (defn ~(symbol (str prefix "-add")) [a# b#] (+ a# b#))
                   (defn ~(symbol (str prefix "-mul")) [a# b#] (* a# b#))))
              (def-ops "math")
              (+ (math-add 3 4) (math-mul 5 6)))""");
    }

    // ---- anaphoric macro (it) ----

    @Test
    public void anaphoricMacroPaired() {
        assertBothEqual("""
            (do
              (defmacro aif [test then else]
                `(let [~'it ~test]
                   (if ~'it ~then ~else)))
              (aif (+ 1 2) (* it 10) -1))""");
    }

    // ---- threading macros with Java interop ----

    @Test
    public void threadFirstJavaInteropPaired() {
        assertBothEqual("""
            (-> "hello world"
                .toUpperCase
                (.replace "WORLD" "CLOJURE")
                .length)""");
    }

    @Test
    public void threadLastCollectionPaired() {
        assertBothEqual("(->> (range 10) (filter even?) (map #(* % %)) (reduce +))");
    }

    @Test
    public void dotoPaired() {
        assertBothEqual("""
            (let [m (doto (java.util.HashMap.)
                      (.put "a" 1)
                      (.put "b" 2))]
              (.size m))""");
    }

    // ---- condp ----

    @Test
    public void condpEqualsPaired() {
        assertBothEqual("""
            (condp = 3
              1 "one"
              2 "two"
              3 "three"
              "other")""");
    }

    @Test
    public void condpDefaultPaired() {
        assertBothEqual("""
            (condp = 99
              1 "one"
              2 "two"
              "default")""");
    }

    @Test
    public void condpInstancePaired() {
        assertBothEqual("""
            (condp instance? "hello"
              Long "long"
              String "string"
              "other")""");
    }

    // ---- binding / with-redefs ----

    @Test
    public void bindingDynamicVarPaired() {
        assertBothEqual("""
            (do
              (def ^:dynamic *factor* 1)
              (defn scaled [x] (* x *factor*))
              (binding [*factor* 10]
                (scaled 5)))""");
    }

    @Test
    public void withRedefsPaired() {
        assertBothEqual("""
            (do
              (defn orig-fn [] 1)
              (with-redefs [orig-fn (fn [] 999)]
                (orig-fn)))""");
    }

    // ---- delay / force ----

    @Test
    public void delayForcePaired() {
        assertBothEqual("(let [d (delay (+ 1 2))] @d)");
    }

    @Test
    public void delayRealizedPaired() {
        assertBothEqual("(let [d (delay 42)] (realized? d))");
    }

    @Test
    public void delayForceThenRealizedPaired() {
        assertBothEqual("(let [d (delay 42)] @d (realized? d))");
    }

    // ---- future / promise (value only, not timing) ----

    @Test
    public void promiseDeliverPaired() {
        assertBothEqual("(let [p (promise)] (deliver p 42) @p)");
    }

    // ---- with-open ----

    @Test
    public void withOpenPaired() {
        assertBothEqual("""
            (with-open [w (java.io.StringWriter.)]
              (.write w "hello")
              (.toString w))""");
    }

    // ---- assert ----

    @Test
    public void assertPassesPaired() {
        String expr = "(do (assert true) 42)";
        assertBothEqual(expr);
    }

    @Test
    public void assertWithMessagePassesPaired() {
        String expr = "(do (assert (= 1 1) \"should pass\") 42)";
        assertBothEqual(expr);
    }

    // ---- comment ----

    @Test
    public void commentFormReturnsNilPaired() {
        String expr = "(comment (/ 1 0) (throw (Exception.)) :whatever)";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl).as("comment returns nil").isEqualTo(clj);
    }

    // ---- declare + forward reference ----

    @Test
    public void declareThenDefnPaired() {
        assertBothEqual("""
            (do
              (declare fwd-fn)
              (defn calls-fwd [] (fwd-fn 5))
              (defn fwd-fn [x] (* x x))
              (calls-fwd))""");
    }

    // ---- when-first ----

    @Test
    public void whenFirstPaired() {
        assertBothEqual("(when-first [x [10 20 30]] (+ x 1))");
    }

    @Test
    public void whenFirstEmptyPaired() {
        String expr = "(when-first [x []] :nope)";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl).as("when-first on empty").isEqualTo(clj);
    }

    // ---- if-some / when-some ----

    @Test
    public void ifSomePaired() {
        assertBothEqual("(if-some [x (get {:a 42} :a)] (+ x 8) -1)");
    }

    @Test
    public void ifSomeNilPaired() {
        assertBothEqual("(if-some [x nil] (+ x 8) -1)");
    }

    @Test
    public void ifSomeFalsePaired() {
        assertBothEqual("(if-some [x false] x :was-nil)");
    }

    @Test
    public void whenSomePaired() {
        assertBothEqual("(when-some [x (get {:a 42} :a)] (+ x 8))");
    }

    @Test
    public void whenSomeNilPaired() {
        String expr = "(when-some [x nil] (+ x 8))";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl).as("when-some nil").isEqualTo(clj);
    }

    // ---- locking ----

    @Test
    public void lockingPaired() {
        assertBothEqual("""
            (let [lock (Object.)
                  a (atom 0)]
              (locking lock
                (swap! a inc))
              @a)""");
    }

    // ---- lazy-seq / lazy-cat ----

    @Test
    public void lazySeqTakePaired() {
        String expr = """
            (str
              (letfn [(fibs [a b] (lazy-seq (cons a (fibs b (+ a b)))))]
                (vec (take 8 (fibs 0 1)))))""";
        assertBothEqual(expr);
    }

    @Test
    public void lazyCatPaired() {
        String expr = "(vec (take 6 (lazy-cat [1 2] [3 4] [5 6 7])))";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl.toString()).as("lazy-cat").isEqualTo(clj.toString());
    }

    // ---- loop with destructuring ----

    @Test
    public void loopDestructuringPaired() {
        assertBothEqual("""
            (loop [[x & xs] [1 2 3 4 5]
                   acc 0]
              (if x
                (recur xs (+ acc x))
                acc))""");
    }

    // ---- for with :let, :when, :while ----

    @Test
    public void forWithWhenPaired() {
        String expr = "(vec (for [x (range 10) :when (odd? x)] x))";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl.toString()).as("for :when").isEqualTo(clj.toString());
    }

    @Test
    public void forWithLetPaired() {
        String expr = "(vec (for [x [1 2 3] :let [y (* x x)]] y))";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl.toString()).as("for :let").isEqualTo(clj.toString());
    }

    @Test
    public void forWithWhilePaired() {
        String expr = "(vec (for [x (range 10) :while (< x 5)] x))";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl.toString()).as("for :while").isEqualTo(clj.toString());
    }

    @Test
    public void forNestedWithModifiersPaired() {
        String expr = """
            (vec (for [x [1 2 3]
                       y [10 20 30]
                       :when (< (+ x y) 25)
                       :let [sum (+ x y)]]
                   sum))""";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl.toString()).as("for nested with modifiers").isEqualTo(clj.toString());
    }

    // ---- doseq with :when / :while ----

    @Test
    public void doseqWithWhenPaired() {
        assertBothEqual("""
            (do
              (def dw-acc (atom 0))
              (doseq [x (range 10) :when (even? x)]
                (swap! dw-acc + x))
              @dw-acc)""");
    }

    // ---- with-local-vars ----

    @Test
    public void withLocalVarsPaired() {
        assertBothEqual("""
            (with-local-vars [x 10 y 20]
              (+ (var-get x) (var-get y)))""");
    }

    // ---- time macro (returns value, ignores timing output) ----

    @Test
    public void timeMacroReturnValuePaired() {
        assertBothEqual("(time (+ 1 2))");
    }

    // ---- -> and ->> with anonymous fn ----

    @Test
    public void threadFirstWithAnonFnPaired() {
        assertBothEqual("(-> 5 ((fn [x] (* x x))) inc)");
    }

    @Test
    public void threadLastWithAnonFnPaired() {
        assertBothEqual("(->> 5 (range) (reduce +))");
    }

    // ---- definline ----

    @Test
    public void definlinePaired() {
        assertBothEqual("""
            (do
              (definline my-inc [x] `(+ ~x 1))
              (my-inc 41))""");
    }

    // ---- multi-level macroexpand correctness ----

    @Test
    public void macroexpandWhenLetPaired() {
        String expr = "(str (macroexpand-1 '(when-let [x 42] (inc x))))";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl.toString()).as("macroexpand-1 when-let").isEqualTo(clj.toString());
    }

    @Test
    public void macroexpandIfLetPaired() {
        String expr = "(str (macroexpand-1 '(if-let [x 42] :yes :no)))";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl.toString()).as("macroexpand-1 if-let").isEqualTo(clj.toString());
    }

    @Test
    public void macroexpandCustomPaired() {
        String expr = """
            (do
              (defmacro my-when [test & body]
                `(if ~test (do ~@body)))
              (str (macroexpand-1 '(my-when true 1 2 3))))""";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl.toString()).as("macroexpand-1 custom").isEqualTo(clj.toString());
    }

    // ---- macro-produced error matches Clojure ----

    @Test
    public void macroArityErrorMessagePaired() {
        String expr = """
            (do
              (defmacro need-two [a b] `(+ ~a ~b))
              (try
                (eval '(need-two 1))
                (catch Exception e
                  (.getMessage e))))""";
        Object clj = clojure(expr);
        Object cfl = cloffle(expr);
        assertThat(cfl.toString()).as("macro arity error message")
                .contains("Wrong number of args");
    }
}
