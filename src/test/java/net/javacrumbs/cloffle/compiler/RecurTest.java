package net.javacrumbs.cloffle.compiler;

import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.assertEquals;

public class RecurTest {

    @BeforeClass
    public static void setUp() {
        RT.init();
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
    }

    private Object compileAndRun(String code) {
        try {
            return CloffleCompiler.compile(new StringReader(code), "test-recur", "test-recur.clj");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testFnRecur() {
        // ( (fn [x] (if (< x 5) (recur (inc x)) x)) 0 )
        assertEquals(5L, compileAndRun("((fn [x] (if (< x 5) (recur (inc x)) x)) 0)"));
    }

    @Ignore("Truffle bytecode backend has no TCO yet (deep self-tail calls hit StackOverflowError). "
            + "Re-enable when ExprToBytecode supports tail calls; see CLOFFLE_TRUFFLE_BYTECODE.md (TODO: TCO).")
    @Test
    public void testSelfTailCallWithoutRecur() {
        assertEquals(0L, compileAndRun("(do (defn down [n] (if (zero? n) 0 (down (dec n)))) (down 20000))"));
    }

    @Test
    public void testSelfTailCallFibonacciWithoutRecur() {
        assertEquals(55L, compileAndRun(
                "(do (defn fib [n a b] (if (zero? n) a (fib (dec n) b (+ a b)))) (fib 10 0 1))"));
    }

    @Test
    public void testSelfTailCallFibonacci100WithoutRecur() {
        assertEquals(clojure.lang.BigInt.fromBigInteger(new java.math.BigInteger("354224848179261915075")),
                compileAndRun("(do (defn fib [n a b] (if (zero? n) a (fib (dec n) b (+ a b)))) (fib 100 0N 1N))"));
    }

    @Ignore("Truffle bytecode backend has no TCO yet (deep self-tail calls hit StackOverflowError). "
            + "Re-enable when ExprToBytecode supports tail calls; see CLOFFLE_TRUFFLE_BYTECODE.md (TODO: TCO).")
    @Test
    public void testSelfTailCallDeepRecursionNoStackOverflow() {
        assertEquals(0L, compileAndRun(
                "(do (defn down [n] (if (zero? n) 0 (down (dec n)))) (down 20000))"));
    }

    @Ignore("Truffle bytecode backend has no TCO yet (deep mutual tail recursion hits StackOverflowError). "
            + "Re-enable when ExprToBytecode supports tail calls; see CLOFFLE_TRUFFLE_BYTECODE.md (TODO: TCO).")
    @Test
    public void testMutualTailRecursionNoStackOverflow() {
        assertEquals(Boolean.TRUE, compileAndRun(
                "(do " +
                        "(declare odd-tail?) " +
                        "(defn even-tail? [n] (if (zero? n) true (odd-tail? (dec n)))) " +
                        "(defn odd-tail? [n] (if (zero? n) false (even-tail? (dec n)))) " +
                        "(even-tail? 20000))"));
    }

    @Ignore("Truffle bytecode backend has no TCO yet (deep mutual tail recursion hits StackOverflowError). "
            + "Re-enable when ExprToBytecode supports tail calls; see CLOFFLE_TRUFFLE_BYTECODE.md (TODO: TCO).")
    @Test
    public void testLetfnMutualTailRecursionNoStackOverflow() {
        assertEquals(Boolean.TRUE, compileAndRun(
                "(letfn [(even-tail? [n] (if (zero? n) true (odd-tail? (dec n)))) " +
                        "        (odd-tail? [n] (if (zero? n) false (even-tail? (dec n))))] " +
                        "  (even-tail? 20000))"));
    }

    @Ignore("Truffle bytecode backend has no TCO yet (deep cross-arity self-tail calls hit StackOverflowError). "
            + "Re-enable when ExprToBytecode supports tail calls; see CLOFFLE_TRUFFLE_BYTECODE.md (TODO: TCO).")
    @Test
    public void testCrossAritySelfTailCall() {
        assertEquals(0L, compileAndRun(
                "(do " +
                        "(defn down " +
                        "  ([n] (down n :unused)) " +
                        "  ([n ignored] (if (zero? n) 0 (down (dec n))))) " +
                        "(down 20000))"));
    }

    @Test
    public void testTailCallInsideTryCatchIsNotCaughtAsException() {
        assertEquals(Boolean.TRUE, compileAndRun(
                "(do " +
                        "(declare odd-tail?) " +
                        "(defn even-tail? [n] " +
                        "  (try " +
                        "    (if (zero? n) true (odd-tail? (dec n))) " +
                        "    (catch Exception e :caught))) " +
                        "(defn odd-tail? [n] (if (zero? n) false (even-tail? (dec n)))) " +
                        "(even-tail? 10))"));
    }

    @Test
    public void testTailCallInsideTryFinallyPreservesFinallyOrder() {
        assertEquals("[0 [0 1 2 3]]", compileAndRun(
                "(do " +
                        "(def log (atom [])) " +
                        "(defn down [n] " +
                        "  (try " +
                        "    (if (zero? n) 0 (down (dec n))) " +
                        "    (finally (swap! log conj n)))) " +
                        "(pr-str [(down 3) @log]))"));
    }
}
