package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for boxing/autoboxing correctness and type hint behavior.
 *
 * Ensures that:
 * - Primitive operations stay unboxed through node pipelines
 * - Type hints (^long, ^double) are respected on fn params and let bindings
 * - Numeric types propagate correctly through control flow (if, do, let, case, try)
 * - Java interop handles boxing/unboxing at method boundaries
 * - Return types match Clojure JVM behavior
 */
public class AutoboxingAndTypeHintTest {

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

    private Value cloffleValue(String expr) {
        return context.eval("cloffle", expr);
    }

    private Object cloffle(String expr) {
        Value result = cloffleValue(expr);
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

    // ========== Primitive type hints on fn params ==========

    @Test
    public void fnLongHintReturnsLong() {
        assertBothEqual("((fn [^long x] x) 42)");
    }

    @Test
    public void fnDoubleHintReturnsDouble() {
        Object clj = clojure("((fn [^double x] x) 3.14)");
        Object cfl = cloffle("((fn [^double x] x) 3.14)");
        assertThat(((Number) cfl).doubleValue()).isCloseTo(((Number) clj).doubleValue(), within(1e-10));
    }

    @Test
    public void fnLongHintWithArithmetic() {
        assertBothEqual("((fn [^long x ^long y] (+ x y)) 10 20)");
    }

    @Test
    public void fnDoubleHintWithArithmetic() {
        Object clj = clojure("((fn [^double x ^double y] (+ x y)) 1.5 2.5)");
        Object cfl = cloffle("((fn [^double x ^double y] (+ x y)) 1.5 2.5)");
        assertThat(((Number) cfl).doubleValue()).isCloseTo(((Number) clj).doubleValue(), within(1e-10));
    }

    @Test
    public void fnMixedHintLongAndDouble() {
        Object clj = clojure("((fn [^long x ^double y] (+ x y)) 10 2.5)");
        Object cfl = cloffle("((fn [^long x ^double y] (+ x y)) 10 2.5)");
        assertThat(((Number) cfl).doubleValue()).isCloseTo(((Number) clj).doubleValue(), within(1e-10));
    }

    @Test
    public void fnLongHintCoercesFromBoxed() {
        assertBothEqual("((fn [^long x] (inc x)) (identity 42))");
    }

    @Test
    public void fnDoubleHintCoercesRatio() {
        assertBothEqual("((fn [^double x] x) 1/2)");
    }

    @Test
    public void fnLongHintOverflowThrows() {
        assertBothEqual("(try ((fn [^long x] x) 9223372036854775808N) (catch Exception e (.getSimpleName (class e))))");
    }

    // ========== Primitive let bindings ==========

    @Test
    public void letLongBinding() {
        assertBothEqual("(let [x 42] (+ x 1))");
    }

    @Test
    public void letDoubleBinding() {
        Object clj = clojure("(let [x 3.14] (* x 2.0))");
        Object cfl = cloffle("(let [x 3.14] (* x 2.0))");
        assertThat(((Number) cfl).doubleValue()).isCloseTo(((Number) clj).doubleValue(), within(1e-10));
    }

    @Test
    public void letBooleanBinding() {
        assertBothEqual("(let [x true] (if x 1 2))");
    }

    @Test
    public void letDependentPrimitiveBindings() {
        assertBothEqual("(let [a 5 b (+ a 3) c (* b 2)] c)");
    }

    @Test
    public void letMixedPrimitiveBindings() {
        Object clj = clojure("(let [a 10 b 2.5 c (+ a b)] c)");
        Object cfl = cloffle("(let [a 10 b 2.5 c (+ a b)] c)");
        assertThat(((Number) cfl).doubleValue()).isCloseTo(((Number) clj).doubleValue(), within(1e-10));
    }

    @Test
    public void letPreservesPrimitiveTypeInNestedUse() {
        assertBothEqual("(let [x 100] (let [y (+ x 1)] y))");
    }

    // ========== do block primitive propagation ==========

    @Test
    public void doReturnsLongFromLastExpr() {
        assertBothEqual("(do (+ 1 1) 42)");
    }

    @Test
    public void doReturnsDoubleFromLastExpr() {
        Object clj = clojure("(do (+ 1 1) 3.14)");
        Object cfl = cloffle("(do (+ 1 1) 3.14)");
        assertThat(((Number) cfl).doubleValue()).isCloseTo(((Number) clj).doubleValue(), within(1e-10));
    }

    @Test
    public void doReturnsBooleanFromLastExpr() {
        assertBothEqual("(do (+ 1 1) true)");
    }

    @Test
    public void doWithSideEffectsPreservesLong() {
        assertBothEqual("(do (def do-side-test 1) (+ 2 3))");
    }

    @Test
    public void nestedDoPreservesLong() {
        assertBothEqual("(do (do 1) (do (+ 3 4)))");
    }

    // ========== if primitive propagation ==========

    @Test
    public void ifBothBranchesLong() {
        assertBothEqual("(if true 42 99)");
    }

    @Test
    public void ifBothBranchesDouble() {
        Object clj = clojure("(if true 1.5 2.5)");
        Object cfl = cloffle("(if true 1.5 2.5)");
        assertThat(((Number) cfl).doubleValue()).isCloseTo(((Number) clj).doubleValue(), within(1e-10));
    }

    @Test
    public void ifWithComparisonReturnsLong() {
        assertBothEqual("(if (> 5 3) (+ 10 20) (- 100 50))");
    }

    @Test
    public void nestedIfPreservesLong() {
        assertBothEqual("(if (< 1 2) (if (> 3 0) 42 0) -1)");
    }

    // ========== case primitive propagation ==========

    @Test
    public void caseLongBranches() {
        assertBothEqual("(case 2 1 10 2 20 3 30)");
    }

    @Test
    public void caseDefaultLong() {
        assertBothEqual("(case 99 1 10 2 20 42)");
    }

    @Test
    public void caseWithKeywordsReturnsLong() {
        assertBothEqual("(case :b :a 1 :b 2 :c 3 0)");
    }

    @Test
    public void caseWithStringKeysReturnsLong() {
        assertBothEqual("(let [x \"b\"] (case x \"a\" 1 \"b\" 2 0))");
    }

    // ========== try/catch primitive propagation ==========

    @Test
    public void tryNoExceptionReturnsLong() {
        assertBothEqual("(try (+ 1 2))");
    }

    @Test
    public void tryCatchNoThrowReturnsLong() {
        assertBothEqual("(try (+ 10 20) (catch Exception e 0))");
    }

    @Test
    public void tryFinallyReturnsLong() {
        assertBothEqual("(try (+ 5 5) (finally (+ 0 0)))");
    }

    @Test
    public void tryCatchWithThrowReturnsLong() {
        assertBothEqual("(try (throw (Exception.)) (catch Exception e 42))");
    }

    // ========== loop/recur primitive handling ==========

    @Test
    public void loopRecurWithLongAccumulator() {
        assertBothEqual("(loop [i 0 acc 0] (if (>= i 10) acc (recur (inc i) (+ acc i))))");
    }

    @Test
    public void loopRecurWithDoubleAccumulator() {
        Object clj = clojure("(loop [x 0.0 n 5] (if (zero? n) x (recur (+ x 1.5) (dec n))))");
        Object cfl = cloffle("(loop [x 0.0 n 5] (if (zero? n) x (recur (+ x 1.5) (dec n))))");
        assertThat(((Number) cfl).doubleValue()).isCloseTo(((Number) clj).doubleValue(), within(1e-10));
    }

    @Test
    public void recurInFnWithLongParams() {
        assertBothEqual("((fn [n acc] (if (<= n 1) acc (recur (dec n) (* acc n)))) 6 1)");
    }

    // ========== Numeric type identity ==========

    @Test
    public void longLiteralIsLong() {
        assertBothEqual("(.getName (class 42))");
    }

    @Test
    public void doubleLiteralIsDouble() {
        assertBothEqual("(.getName (class 3.14))");
    }

    @Test
    public void additionOfLongsIsLong() {
        assertBothEqual("(.getName (class (+ 1 2)))");
    }

    @Test
    public void additionOfDoubleIsDouble() {
        assertBothEqual("(.getName (class (+ 1.0 2.0)))");
    }

    @Test
    public void additionMixedIsDouble() {
        assertBothEqual("(.getName (class (+ 1 2.0)))");
    }

    @Test
    public void multiplicationOfLongsIsLong() {
        assertBothEqual("(.getName (class (* 3 4)))");
    }

    @Test
    public void divisionReturnsRatio() {
        assertBothEqual("(.getName (class (/ 1 3)))");
    }

    @Test
    public void intCastPreservesType() {
        assertBothEqual("(int 42)");
    }

    @Test
    public void longCastPreservesType() {
        assertBothEqual("(long 42)");
    }

    @Test
    public void doubleCastFromLong() {
        Object clj = clojure("(double 42)");
        Object cfl = cloffle("(double 42)");
        assertThat(((Number) cfl).doubleValue()).isCloseTo(((Number) clj).doubleValue(), within(1e-10));
    }

    // ========== Java interop boxing boundaries ==========

    @Test
    public void staticMethodLongArgs() {
        assertBothEqual("(Math/max 10 20)");
    }

    @Test
    public void staticMethodDoubleArgs() {
        Object clj = clojure("(Math/sqrt 144.0)");
        Object cfl = cloffle("(Math/sqrt 144.0)");
        assertThat(((Number) cfl).doubleValue()).isCloseTo(((Number) clj).doubleValue(), within(1e-10));
    }

    @Test
    public void staticMethodLongToIntCoercion() {
        assertBothEqual("(Integer/bitCount 255)");
    }

    @Test
    public void staticMethodResultUnboxes() {
        assertBothEqual("(Math/abs -42)");
    }

    @Test
    public void instanceMethodReturnsInt() {
        Object clj = clojure("(.length \"hello\")");
        Object cfl = cloffle("(.length \"hello\")");
        assertThat(((Number) cfl).intValue()).isEqualTo(((Number) clj).intValue());
    }

    @Test
    public void instanceMethodWithLongArgCoercion() {
        assertBothEqual("(.substring \"hello world\" 6)");
    }

    @Test
    public void staticMethodWithBoxedResult() {
        assertBothEqual("(Math/max (+ 1 2) (+ 3 4))");
    }

    @Test
    public void staticMethodWithIfResult() {
        assertBothEqual("(Integer/bitCount (if true 255 0))");
    }

    // ========== Numeric overflow / boundary values ==========

    @Test
    public void longMaxValue() {
        assertBothEqual("Long/MAX_VALUE");
    }

    @Test
    public void longMinValue() {
        assertBothEqual("Long/MIN_VALUE");
    }

    @Test
    public void longOverflowWithPlus() {
        assertBothEqual("(try (+' Long/MAX_VALUE 1) (catch Exception e :overflow))");
    }

    @Test
    public void uncheckedAddOverflow() {
        assertBothEqual("(unchecked-add Long/MAX_VALUE 1)");
    }

    // ========== Truthiness and boolean boxing ==========

    @Test
    public void zeroIsTruthy() {
        assertBothEqual("(if 0 1 2)");
    }

    @Test
    public void emptyStringIsTruthy() {
        assertBothEqual("(if \"\" 1 2)");
    }

    @Test
    public void nilIsFalsy() {
        assertBothEqual("(if nil 1 2)");
    }

    @Test
    public void falseIsFalsy() {
        assertBothEqual("(if false 1 2)");
    }

    @Test
    public void numberIsTruthy() {
        assertBothEqual("(if 42 1 2)");
    }

    @Test
    public void booleanReturnFromComparison() {
        assertBothEqual("(boolean? (< 1 2))");
    }

    // ========== Type predicates ==========

    @Test
    public void numberPredicateOnLong() {
        assertBothEqual("(number? 42)");
    }

    @Test
    public void numberPredicateOnDouble() {
        assertBothEqual("(number? 3.14)");
    }

    @Test
    public void integerPredicateOnLong() {
        assertBothEqual("(integer? 42)");
    }

    @Test
    public void integerPredicateOnDouble() {
        assertBothEqual("(integer? 3.14)");
    }

    @Test
    public void floatPredicateOnDouble() {
        assertBothEqual("(float? 3.14)");
    }

    @Test
    public void floatPredicateOnLong() {
        assertBothEqual("(float? 42)");
    }

    // ========== Compound expressions - boxing boundaries ==========

    @Test
    public void letInsideDoReturnsLong() {
        assertBothEqual("(do (let [x 10] (+ x 5)))");
    }

    @Test
    public void ifInsideLetReturnsLong() {
        assertBothEqual("(let [x (if true 42 0)] (+ x 1))");
    }

    @Test
    public void caseInsideLetReturnsLong() {
        assertBothEqual("(let [x (case :a :a 10 :b 20 0)] (+ x 5))");
    }

    @Test
    public void doInsideIfReturnsLong() {
        assertBothEqual("(if true (do 1 2 42) 0)");
    }

    @Test
    public void tryInsideLetReturnsLong() {
        assertBothEqual("(let [x (try (+ 10 20) (catch Exception e 0))] x)");
    }

    @Test
    public void letInsideIfInsideDo() {
        assertBothEqual("(do (if (> 5 3) (let [x 42] x) (let [y 0] y)))");
    }

    @Test
    public void caseInsideDoInsideLet() {
        assertBothEqual("(let [v :b] (do (+ 1 1) (case v :a 10 :b 20 0)))");
    }

    @Test
    public void tryInsideDoInsideIf() {
        assertBothEqual("(if true (do 1 (try (+ 2 3) (catch Exception e 0))) -1)");
    }

    // ========== defn with type hints ==========

    @Test
    public void defnWithLongHintedParam() {
        assertBothEqual("(do (defn my-inc-long [^long x] (inc x)) (my-inc-long 41))");
    }

    @Test
    public void defnWithDoubleHintedParam() {
        Object clj = clojure("(do (defn my-double-fn [^double x] (* x 2.0)) (my-double-fn 3.5))");
        Object cfl = cloffle("(do (defn my-double-fn [^double x] (* x 2.0)) (my-double-fn 3.5))");
        assertThat(((Number) cfl).doubleValue()).isCloseTo(((Number) clj).doubleValue(), within(1e-10));
    }

    @Test
    public void defnMultipleLongHintedParams() {
        assertBothEqual("(do (defn add3 [^long a ^long b ^long c] (+ a b c)) (add3 10 20 30))");
    }

    // ========== Higher-order functions and boxing ==========

    @Test
    public void mapIncPreservesLong() {
        assertBothEqual("(first (map inc [1 2 3]))");
    }

    @Test
    public void reduceWithLongAccumulator() {
        assertBothEqual("(reduce + 0 [1 2 3 4 5])");
    }

    @Test
    public void filterWithNumericPredicate() {
        assertBothEqual("(count (filter #(> % 3) [1 2 3 4 5]))");
    }

    @Test
    public void mapWithLongArithmeticFn() {
        String expr = "(do (defn double-it-auto [x] (* x 2)) (str (vec (map double-it-auto [1 2 3]))))";
        assertBothEqual(expr);
    }

    // ========== Closure captured primitives ==========

    @Test
    public void closureCapturesLong() {
        assertBothEqual("(let [x 42] ((fn [] x)))");
    }

    @Test
    public void closureCapturesDouble() {
        Object clj = clojure("(let [x 3.14] ((fn [] x)))");
        Object cfl = cloffle("(let [x 3.14] ((fn [] x)))");
        assertThat(((Number) cfl).doubleValue()).isCloseTo(((Number) clj).doubleValue(), within(1e-10));
    }

    @Test
    public void closureCapturesAndOperatesOnLong() {
        assertBothEqual("(let [base 100] ((fn [x] (+ base x)) 42))");
    }

    @Test
    public void closureCapturedPrimitiveInLoop() {
        assertBothEqual("""
            (let [step 2]
              (loop [i 0 acc 0]
                (if (>= i 10)
                  acc
                  (recur (+ i step) (+ acc i)))))""");
    }

    // ========== Numeric equality and identity ==========

    @Test
    public void longEquality() {
        assertBothEqual("(= 42 42)");
    }

    @Test
    public void longDoubleEquality() {
        assertBothEqual("(= 42 42.0)");
    }

    @Test
    public void doubleEquality() {
        assertBothEqual("(= 3.14 3.14)");
    }

    @Test
    public void numericIdentical() {
        assertBothEqual("(identical? 42 42)");
    }

    @Test
    public void zeroEquality() {
        assertBothEqual("(= 0 0.0)");
    }

    @Test
    public void negativeEquality() {
        assertBothEqual("(= -1 -1)");
    }

    // ========== Bit operations on long ==========

    @Test
    public void bitAnd() {
        assertBothEqual("(bit-and 0xFF 0x0F)");
    }

    @Test
    public void bitOr() {
        assertBothEqual("(bit-or 0xF0 0x0F)");
    }

    @Test
    public void bitXor() {
        assertBothEqual("(bit-xor 0xFF 0x0F)");
    }

    @Test
    public void bitShiftLeft() {
        assertBothEqual("(bit-shift-left 1 10)");
    }

    @Test
    public void bitShiftRight() {
        assertBothEqual("(bit-shift-right 1024 5)");
    }

    @Test
    public void bitNot() {
        assertBothEqual("(bit-not 0)");
    }

    // ========== Numeric coercion edge cases ==========

    @Test
    public void byteToLong() {
        assertBothEqual("(long (byte 42))");
    }

    @Test
    public void shortToLong() {
        assertBothEqual("(long (short 1000))");
    }

    @Test
    public void intToLong() {
        assertBothEqual("(long (int 100000))");
    }

    @Test
    public void doubleToLong() {
        assertBothEqual("(long 3.14)");
    }

    @Test
    public void longToDouble() {
        Object clj = clojure("(double 42)");
        Object cfl = cloffle("(double 42)");
        assertThat(((Number) cfl).doubleValue()).isCloseTo(((Number) clj).doubleValue(), within(1e-10));
    }

    // ========== Math operations stay primitive ==========

    @Test
    public void remLong() {
        assertBothEqual("(rem 17 5)");
    }

    @Test
    public void modLong() {
        assertBothEqual("(mod 17 5)");
    }

    @Test
    public void quotLong() {
        assertBothEqual("(quot 17 5)");
    }

    @Test
    public void maxLong() {
        assertBothEqual("(max 10 20)");
    }

    @Test
    public void minLong() {
        assertBothEqual("(min 10 20)");
    }

    @Test
    public void absLong() {
        assertBothEqual("(abs -42)");
    }

    // ========== Nil interop with primitives ==========

    @Test
    public void nilToPrimitiveLongMethodThrows() {
        assertBothEqual("(try (Math/abs nil) (catch Exception e (.getSimpleName (class e))))");
    }

    @Test
    public void nilInArithmeticThrows() {
        assertBothEqual("(try (+ nil 1) (catch Exception e (.getSimpleName (class e))))");
    }

    // ========== Complex multi-node primitive chains ==========

    @Test
    public void complexPrimitiveChain() {
        assertBothEqual("""
            (let [a 10
                  b (+ a 5)
                  c (if (> b 12) (* b 2) b)]
              (do (+ 1 1)
                  (+ c 100)))""");
    }

    @Test
    public void fibonacciPreservesPrimitive() {
        assertBothEqual("(loop [a 0 b 1 n 20] (if (= n 0) a (recur b (+ a b) (dec n))))");
    }

    @Test
    public void nestedFnCallsPreservePrimitive() {
        assertBothEqual("""
            (do
              (defn sq-auto [x] (* x x))
              (defn sum-sq [a b] (+ (sq-auto a) (sq-auto b)))
              (sum-sq 3 4))""");
    }

    @Test
    public void primeCheckPreservesPrimitive() {
        assertBothEqual("""
            (do
              (defn prime-auto? [n]
                (loop [i 2]
                  (if (> (* i i) n)
                    true
                    (if (= 0 (rem n i))
                      false
                      (recur (inc i))))))
              (str [(prime-auto? 997) (prime-auto? 100)]))""");
    }

    // ========== Value identity through polyglot boundary ==========

    @Test
    public void polyglotLongFitsInLong() {
        Value v = cloffleValue("42");
        assertThat(v.fitsInLong()).isTrue();
        assertThat(v.asLong()).isEqualTo(42L);
    }

    @Test
    public void polyglotDoubleFitsInDouble() {
        Value v = cloffleValue("3.14");
        assertThat(v.fitsInDouble()).isTrue();
        assertThat(v.asDouble()).isCloseTo(3.14, within(1e-10));
    }

    @Test
    public void polyglotBooleanIsBoolean() {
        Value v = cloffleValue("true");
        assertThat(v.isBoolean()).isTrue();
        assertThat(v.asBoolean()).isTrue();
    }

    @Test
    public void polyglotArithmeticResultIsLong() {
        Value v = cloffleValue("(+ 10 20)");
        assertThat(v.fitsInLong()).isTrue();
        assertThat(v.asLong()).isEqualTo(30L);
    }

    @Test
    public void polyglotLetResultIsLong() {
        Value v = cloffleValue("(let [x 42] x)");
        assertThat(v.fitsInLong()).isTrue();
        assertThat(v.asLong()).isEqualTo(42L);
    }

    @Test
    public void polyglotIfResultIsLong() {
        Value v = cloffleValue("(if true 42 0)");
        assertThat(v.fitsInLong()).isTrue();
        assertThat(v.asLong()).isEqualTo(42L);
    }

    @Test
    public void polyglotDoResultIsLong() {
        Value v = cloffleValue("(do 1 2 42)");
        assertThat(v.fitsInLong()).isTrue();
        assertThat(v.asLong()).isEqualTo(42L);
    }

    @Test
    public void polyglotCaseResultIsLong() {
        Value v = cloffleValue("(case :a :a 42 :b 0 -1)");
        assertThat(v.fitsInLong()).isTrue();
        assertThat(v.asLong()).isEqualTo(42L);
    }

    @Test
    public void polyglotTryResultIsLong() {
        Value v = cloffleValue("(try (+ 10 20) (catch Exception e 0))");
        assertThat(v.fitsInLong()).isTrue();
        assertThat(v.asLong()).isEqualTo(30L);
    }

    // ========== Warn-on-boxed math ==========

    @Test
    public void warnOnBoxedMathSetting() {
        assertBothEqual("(do (binding [*unchecked-math* :warn-on-boxed] (+ 1 2)))");
    }

    // ========== Type hint on return values ==========

    @Test
    public void defnReturnsHintedLong() {
        assertBothEqual("(do (defn ^long ret-long [] 42) (ret-long))");
    }

    @Test
    public void defnReturnsHintedDouble() {
        Object clj = clojure("(do (defn ^double ret-double [] 3.14) (ret-double))");
        Object cfl = cloffle("(do (defn ^double ret-double [] 3.14) (ret-double))");
        assertThat(((Number) cfl).doubleValue()).isCloseTo(((Number) clj).doubleValue(), within(1e-10));
    }

    // ========== Varargs and primitive interaction ==========

    @Test
    public void variadicFnWithNonPrimitiveRest() {
        assertBothEqual("(do (defn sum-all-auto [& nums] (apply + nums)) (sum-all-auto 1 2 3 4 5))");
    }

    @Test
    public void variadicFnMixedWithFixed() {
        assertBothEqual("(do (defn add-first-auto [a & rest] (+ a (count rest))) (add-first-auto 10 20 30))");
    }

    // ========== Numeric widening in collections ==========

    @Test
    public void vectorOfLongsPreservesType() {
        assertBothEqual("(.getName (class (first [1 2 3])))");
    }

    @Test
    public void mapValuesPreserveType() {
        assertBothEqual("(.getName (class (:a {:a 42})))");
    }

    // ========== Edge: nested closures with primitives ==========

    @Test
    public void nestedClosuresCapturePrimitives() {
        assertBothEqual("""
            (let [x 10]
              (let [f (fn [] (+ x 1))]
                (let [g (fn [] (+ (f) 2))]
                  (g))))""");
    }

    @Test
    public void closureOverLoopVariable() {
        assertBothEqual("""
            (let [fns (loop [i 0 acc []]
                        (if (= i 3) acc
                          (recur (inc i) (conj acc (let [v i] (fn [] v))))))]
              (+ ((first fns)) ((second fns)) ((nth fns 2))))""");
    }
}
