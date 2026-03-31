package clojure.lang;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Reproducer for "StoreLocal expected a value-producing child but void was provided"
 * triggered at core.clj:6026 (load-lib form).
 *
 * Root cause: {@code containsRecur} traverses into nested {@code loop*} (LetExpr with
 * isLoop=true), finding a {@code recur} that belongs to the inner loop. This makes the
 * outer {@code convert(IfExpr)} take the void {@code emitLoopIfExpr} path instead of
 * the value-producing {@code Conditional} path.
 */
public class StoreLocalVoidTest {

    /**
     * Minimal reproducer: fn body whose last expression is an {@code if} that contains
     * a nested {@code loop*}/{@code recur}. The {@code recur} targets the inner loop,
     * but {@code containsRecur} incorrectly reports it as belonging to the outer fn,
     * causing the fn's result to be emitted as void.
     */
    @Test
    public void ifWithNestedLoopRecurInFnBody() {
        // (fn* [x] (if x (loop* [i 0] (if (< i 3) (recur (inc i)) i)) nil))
        String code =
                "((fn* [x]"
                + "  (if x"
                + "    (loop* [i 0]"
                + "      (if (clojure.lang.Numbers/lt i 3)"
                + "        (recur (clojure.lang.Numbers/inc i))"
                + "        i))"
                + "    nil))"
                + " true)";
        assertEquals(3L, BytecodeDslTestSupport.evalBytecode(code));
    }

    /**
     * Pattern from load-lib: try/finally body with BodyExpr containing multiple ifs,
     * one of which has a nested loop/recur (doseq expansion).
     */
    @Test
    public void tryFinallyWithIfContainingNestedLoopRecur() {
        String code =
                "(try"
                + "  (do"
                + "    (if true nil nil)"  // side effect if
                + "    (if true"           // last if with nested loop/recur
                + "      (loop* [i 0]"
                + "        (if (clojure.lang.Numbers/lt i 3)"
                + "          (recur (clojure.lang.Numbers/inc i))"
                + "          i))"
                + "      nil))"
                + "  (finally nil))";
        assertEquals(3L, BytecodeDslTestSupport.evalBytecode(code));
    }

    /**
     * The binding macro pattern: try/finally (no catches) with a body containing
     * multiple if/when forms, one containing a doseq (loop/recur).
     */
    @Test
    public void bindingLikePatternWithDoseqInWhen() {
        // Models: (binding [...] (when cond1 ...) (when cond2 (doseq [...] body)) result)
        String code =
                "(try"
                + "  (do"
                + "    (if true nil nil)"
                + "    (if true"
                + "      (do"
                + "        (loop* [s (clojure.lang.RT/seq (clojure.lang.RT/list 1 2))]"
                + "          (if s"
                + "            (let* [x (clojure.lang.RT/first s)]"
                + "              (recur (clojure.lang.RT/next s)))"
                + "            nil))"
                + "        42)"
                + "      nil))"
                + "  (finally nil))";
        assertEquals(42L, BytecodeDslTestSupport.evalBytecode(code));
    }

    /**
     * Ensure the fix does not break real fn-level recur.
     */
    @Test
    public void fnBodyWithDirectRecurStillWorks() {
        String code =
                "((fn* [i]"
                + "  (if (clojure.lang.Numbers/lt i 5)"
                + "    (recur (clojure.lang.Numbers/inc i))"
                + "    i))"
                + " 0)";
        assertEquals(5L, BytecodeDslTestSupport.evalBytecode(code));
    }

    /**
     * Multi-arity fn with nested loop in the body.
     */
    @Test
    public void multiArityFnWithNestedLoopInBody() {
        String code =
                "((fn* ([x]"
                + "  (if x"
                + "    (loop* [i 0]"
                + "      (if (clojure.lang.Numbers/lt i x)"
                + "        (recur (clojure.lang.Numbers/inc i))"
                + "        i))"
                + "    nil)))"
                + " 4)";
        assertEquals(4L, BytecodeDslTestSupport.evalBytecode(code));
    }
}
