package clojure.lang;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@code let*}, {@code loop*}/{@code recur}, basic {@code fn*}, {@code letfn*}, and closures.
 * <p>
 * No {@code clojure.core} load — forms limited to what {@link Compiler#analyze} handles natively.
 * <p>
 * Package {@code clojure.lang} for access to {@link Compiler} internals.
 * Helpers: {@link BytecodeDslTestSupport}.
 */
public class BytecodeBindingsAndLoopsTest {

    @Test
    public void letStarBindsLocals() {
        assertEquals(1L, BytecodeDslTestSupport.evalBytecode("(let* [a 1] a)"));
        assertEquals(1L, BytecodeDslTestSupport.evalBytecode("(let* [a 1 b a] b)"));
        assertEquals(2L, BytecodeDslTestSupport.evalBytecode("(let* [a 1 b 2] b)"));
    }

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

    /**
     * Multi-binding {@code loop*}/{@code recur}: several locals advance together.
     */
    @Test
    public void loopStarTwoBindingsRecur() {
        assertEquals(
                10L,
                BytecodeDslTestSupport.evalBytecode(
                        "(loop* [x 0 y 10] (if (clojure.lang.Util/equiv x 2) (clojure.lang.Numbers/add x y) (recur (clojure.lang.Numbers/add x 1) (clojure.lang.Numbers/minus y 1))))"));
    }

    /**
     * {@code recur} with {@code clojure.lang.RT/conj} on an accumulator — same pattern as
     * {@code core.clj} loops that build collections in the recur step.
     */
    @Test
    public void loopStarRecurWithRtConjAccumulator() {
        Object v =
                BytecodeDslTestSupport.evalBytecode(
                        "(loop* [coll nil x 0] (if (clojure.lang.Util/equiv x 2) (clojure.lang.RT/count coll) (recur (clojure.lang.RT/conj coll x) (clojure.lang.Numbers/add x 1))))");
        assertEquals(2L, RT.longCast(v));
    }

    /**
     * Walk a list with {@code RT.next}/{@code RT.first} until the tail — same shape as bootstrap
     * {@code last} in {@code core.clj}.
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

    // --- Basic fn* ---

    @Test
    public void fnStarZeroArityInvoke() {
        assertEquals(42L, BytecodeDslTestSupport.evalBytecode("((fn* ([] 42)))"));
    }

    @Test
    public void fnStarUnaryInvoke() {
        assertEquals(99L, BytecodeDslTestSupport.evalBytecode("((fn* ([x] x)) 99)"));
    }

    @Test
    public void fnStarBodyWithDo() {
        String f = "(fn* ([] (do 1 2 99)))";
        assertEquals(99L, BytecodeDslTestSupport.evalBytecode("(" + f + ")"));
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
    public void letStarClosureCapturesLocal() {
        assertEquals(7L, BytecodeDslTestSupport.evalBytecode("(let* [n 7] ((fn* [] n)))"));
    }

    /**
     * {@code fn*} with required + rest params: {@code recur} must rebind both the last fixed arg
     * and the rest seq.
     */
    @Test
    public void fnStarRestArgsRecurWalksSeq() {
        assertEquals(
                3L,
                BytecodeDslTestSupport.evalBytecode(
                        "((fn* [x & xs] (if (clojure.lang.Util/identical xs nil) x (if (clojure.lang.Util/identical xs clojure.lang.PersistentList/EMPTY) x (recur (clojure.lang.RT/first xs) (clojure.lang.RT/next xs))))) 0 1 2 3)"));
    }

    // --- letfn* ---

    /**
     * {@code letfn*} (not the {@code letfn} macro): local {@code fn*} bindings with mutual
     * recursion wired via bytecode {@code WireLetFnClosures}.
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
}
