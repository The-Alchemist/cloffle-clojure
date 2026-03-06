package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

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

    // ========== Reflector migration: BinaryStaticCallNode ==========

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

    // ========== Reflector migration: HostInteropNode ==========

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
}
