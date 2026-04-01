package clojure.lang;

import net.javacrumbs.cloffle.bytecode.ExprToBytecode;
import org.junit.Test;

import static clojure.lang.BytecodeDslTestSupport.evalBytecode;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Reproducer for {@code FrameSlotTypeException} (reading an uninitialized local) in
 * {@link ExprToBytecode}. Uses raw special forms — no {@code clojure.core} macros needed.
 * <p>
 * The root bug: {@code ExprToBytecode} may emit {@code LoadLocal} for a {@code BytecodeLocal}
 * that was never written on certain control-flow paths (e.g. the nil branch of {@code if},
 * or after a {@code recur} in a {@code loop*}). The Truffle Bytecode DSL tracks frame-slot
 * state precisely and throws {@code FrameSlotTypeException} when reading an {@code Illegal}
 * (never-written) slot.
 * <p>
 * Tests start with simple patterns and build up to the complexity found in
 * {@code core_proxy.clj}'s {@code generate-proxy} (large {@code let*} with closures,
 * nested loops, destructuring, lazy-seq thunks).
 * <p>
 * Package {@code clojure.lang} for access to {@link Compiler} internals.
 * Helpers: {@link BytecodeDslTestSupport}.
 */
public class FrameSlotUninitializedTest {

    // ========== 1. let* with many bindings ==========

    /**
     * Large flat {@code let*} — many locals in a single frame. No closures yet.
     * Just verifies we can allocate and read many locals without slot confusion.
     */
    @Test
    public void letStarManyBindings() {
        assertEquals(55L, evalBytecode(
                "(let* [a 1 b 2 c 3 d 4 e 5 f 6 g 7 h 8 i 9 j 10] " +
                "  (clojure.lang.Numbers/add a (clojure.lang.Numbers/add b " +
                "    (clojure.lang.Numbers/add c (clojure.lang.Numbers/add d " +
                "      (clojure.lang.Numbers/add e (clojure.lang.Numbers/add f " +
                "        (clojure.lang.Numbers/add g (clojure.lang.Numbers/add h " +
                "          (clojure.lang.Numbers/add i j))))))))))"));
    }

    /**
     * Large {@code let*} where later bindings are closures that capture earlier bindings.
     * Mirrors the shape of {@code generate-proxy}'s {@code gen-bridge} / {@code gen-method} closures.
     */
    @Test
    public void letStarWithClosuresCapturingEarlierBindings() {
        assertEquals(30L, evalBytecode(
                "(let* [base 10" +
                "       add-base (fn* [x] (clojure.lang.Numbers/add base x))" +
                "       mul (fn* [x y] (clojure.lang.Numbers/multiply x y))" +
                "       combined (fn* [x] (add-base (mul x 2)))]" +
                "  (combined 10))"));
    }

    // ========== 2. loop*/recur with if branches ==========

    /**
     * Simple loop with {@code if} — both branches produce a value. Baseline.
     */
    @Test
    public void loopIfBothBranchesReturnValues() {
        assertEquals(10L, evalBytecode(
                "(loop* [n 0 acc 0]" +
                "  (if (clojure.lang.Numbers/lt n 5)" +
                "    (recur (clojure.lang.Numbers/add n 1) (clojure.lang.Numbers/add acc n))" +
                "    acc))"));
    }

    /**
     * Loop where the then-branch recurs and the else-branch returns — the {@code recur}
     * is void, so the {@code if} overall must be treated as void in the recur path.
     * This is the core pattern that caused the original {@code case*+recur} bug.
     */
    @Test
    public void loopRecurInThenBranchOnly() {
        assertEquals(120L, evalBytecode(
                "(loop* [n 5 acc 1]" +
                "  (if (clojure.lang.Numbers/gt n 0)" +
                "    (recur (clojure.lang.Numbers/minus n 1) (clojure.lang.Numbers/multiply acc n))" +
                "    acc))"));
    }

    // ========== 3. Nested let* inside loop* ==========

    /**
     * Loop body has a {@code let*} that binds locals, then recurs.
     * The locals bound inside the loop body get new frame slots each iteration
     * (conceptually) and must be properly initialized.
     */
    @Test
    public void loopWithLetStarInBody() {
        assertEquals(15L, evalBytecode(
                "(loop* [xs (clojure.lang.RT/list 1 2 3 4 5) acc 0]" +
                "  (if (clojure.lang.RT/first xs)" +
                "    (let* [head (clojure.lang.RT/first xs)" +
                "           doubled (clojure.lang.Numbers/multiply head 2)" +
                "           rest (clojure.lang.RT/next xs)]" +
                "      (recur rest (clojure.lang.Numbers/add acc head)))" +
                "    acc))"));
    }

    // ========== 4. Closures inside loop bodies ==========

    /**
     * A closure is created inside a loop body, capturing both loop vars and
     * loop-body-local vars. This tests that captured locals from different
     * frame-slot scopes are all properly initialized before closure creation.
     */
    @Test
    public void closureInsideLoopCapturingLoopVarsAndLocals() {
        assertEquals(6L, evalBytecode(
                "(loop* [n 3 acc 0]" +
                "  (if (clojure.lang.Numbers/gt n 0)" +
                "    (let* [adder (fn* [] n)]" +
                "      (recur (clojure.lang.Numbers/minus n 1) " +
                "             (clojure.lang.Numbers/add acc (adder))))" +
                "    acc))"));
    }

    // ========== 5. Nested loops ==========

    /**
     * Nested loops — the inner loop's locals must not alias the outer loop's slots.
     */
    @Test
    public void nestedLoops() {
        assertEquals(6L, evalBytecode(
                "(loop* [i 1 total 0]" +
                "  (if (clojure.lang.Numbers/lte i 3)" +
                "    (let* [row-sum (loop* [j 1 s 0]" +
                "                    (if (clojure.lang.Numbers/lte j i)" +
                "                      (recur (clojure.lang.Numbers/add j 1) (clojure.lang.Numbers/add s 1))" +
                "                      s))]" +
                "      (recur (clojure.lang.Numbers/add i 1) (clojure.lang.Numbers/add total row-sum)))" +
                "    total))"));
    }

    // ========== 6. try/catch inside loop ==========

    /**
     * {@code try/catch} inside a loop — the try expression creates a separate
     * frame region; locals inside the try must not bleed out.
     */
    @Test
    public void tryCatchInsideLoop() {
        assertEquals(3L, evalBytecode(
                "(loop* [n 0]" +
                "  (if (clojure.lang.Numbers/lt n 3)" +
                "    (recur (try (clojure.lang.Numbers/add n 1) (catch Exception e 0)))" +
                "    n))"));
    }

    // ========== 7. LazySeq thunk with captured locals ==========

    /**
     * Manual lazy-seq (using {@code clojure.lang.LazySeq} constructor directly)
     * where the thunk fn closes over locals. When the seq is forced ({@code RT.first}),
     * the closure executes and must find all captured slots initialized.
     */
    @Test
    public void lazySeqThunkCapturesLocals() {
        Object result = evalBytecode(
                "(let* [x 42" +
                "       thunk (fn* [] (clojure.lang.RT/cons x nil))" +
                "       lz (new clojure.lang.LazySeq thunk)]" +
                "  (clojure.lang.RT/first lz))");
        assertEquals(42L, result);
    }

    /**
     * Lazy-seq inside a loop — the thunk captures the loop variable.
     * Forces the first element to verify the closure works.
     */
    @Test
    public void lazySeqInsideLoopCapturesLoopVar() {
        Object result = evalBytecode(
                "(let* [make-lazy (fn* [n] (new clojure.lang.LazySeq" +
                "                           (fn* [] (clojure.lang.RT/cons n nil))))]" +
                "  (loop* [i 3]" +
                "    (if (clojure.lang.Numbers/gt i 1)" +
                "      (recur (clojure.lang.Numbers/minus i 1))" +
                "      (clojure.lang.RT/first (make-lazy i)))))");
        assertEquals(1L, result);
    }

    // ========== 8. Complex pattern: let* + closures + loop + lazy-seq ==========

    /**
     * Combines all the ingredients found in {@code generate-proxy}:
     * <ul>
     *   <li>Large outer {@code let*} with helper closures</li>
     *   <li>A loop that iterates over a sequence</li>
     *   <li>Loop body creates closures that capture outer-let and loop-body bindings</li>
     *   <li>Nested conditionals ({@code if}) that may leave locals uninitialized on some paths</li>
     * </ul>
     */
    @Test
    public void generateProxyLikePattern() {
        assertEquals(10L, evalBytecode(
                "(let* [transform (fn* [x y] (clojure.lang.Numbers/add x y))" +
                "       format-val (fn* [label val] (clojure.lang.Numbers/multiply val 2))" +
                "       items (clojure.lang.RT/list 1 2 3 4)]" +
                "  (loop* [xs items acc 0]" +
                "    (if (clojure.lang.RT/first xs)" +
                "      (let* [head (clojure.lang.RT/first xs)" +
                "             processed (transform head acc)" +
                "             rest (clojure.lang.RT/next xs)]" +
                "        (recur rest processed))" +
                "      acc)))"));
    }

    /**
     * Two-level nested loop (like doseq with two bindings):
     * outer loop iterates over a list of pairs, inner loop iterates over pair elements.
     */
    @Test
    public void nestedDoseqLikePattern() {
        // Build: [[1 2] [3 4]] as nested lists, sum all elements = 10
        assertEquals(10L, evalBytecode(
                "(let* [pairs (clojure.lang.RT/list" +
                "               (clojure.lang.RT/list 1 2)" +
                "               (clojure.lang.RT/list 3 4))]" +
                "  (loop* [outer pairs total 0]" +
                "    (if (clojure.lang.RT/first outer)" +
                "      (let* [pair (clojure.lang.RT/first outer)" +
                "             pair-sum (loop* [inner pair s 0]" +
                "                       (if (clojure.lang.RT/first inner)" +
                "                         (let* [v (clojure.lang.RT/first inner)]" +
                "                           (recur (clojure.lang.RT/next inner)" +
                "                                  (clojure.lang.Numbers/add s v)))" +
                "                         s))]" +
                "        (recur (clojure.lang.RT/next outer)" +
                "               (clojure.lang.Numbers/add total pair-sum)))" +
                "      total)))"));
    }

    // ========== 9. reify* / deftype* with complex bodies ==========

    /**
     * {@code reify*} implementing multiple interfaces — the generated class'
     * method bodies may reference locals from the enclosing scope.
     */
    @Test
    public void reifyStarMultipleInterfacesClosingOverLocals() {
        Object result = evalBytecode(
                "(let* [x 42 y 10]" +
                "  (let* [obj (reify* [java.util.concurrent.Callable java.lang.Runnable]" +
                "               (call [this] (clojure.lang.Numbers/add x y))" +
                "               (run [this] nil))]" +
                "    (. obj (call))))");
        assertEquals(52L, result);
    }

    /**
     * {@code reify*} inside a loop — each iteration creates a new reify instance
     * capturing the current loop variable value.
     */
    @Test
    public void reifyInsideLoop() {
        Object result = evalBytecode(
                "(loop* [n 3 acc nil]" +
                "  (if (clojure.lang.Numbers/gt n 0)" +
                "    (let* [c (reify* [java.util.concurrent.Callable]" +
                "               (call [this] n))]" +
                "      (recur (clojure.lang.Numbers/minus n 1) c))" +
                "    (. acc (call))))");
        assertEquals(1L, result);
    }

    // ========== 10. if-nil patterns (when-let expansion shape) ==========

    /**
     * The {@code when-let} macro expands into {@code (let* [x expr] (if x body nil))}.
     * On the nil path, the binding {@code x} IS initialized (it holds the test value)
     * but any locals bound inside the then-branch are NOT (they are out of scope).
     * This tests that we don't accidentally read an uninitialized then-only local.
     */
    @Test
    public void whenLetExpansionShape() {
        assertNull(evalBytecode(
                "(let* [x nil]" +
                "  (if x" +
                "    (let* [y (clojure.lang.Numbers/add x 1)] y)" +
                "    nil))"));
        assertEquals(43L, evalBytecode(
                "(let* [x 42]" +
                "  (if x" +
                "    (let* [y (clojure.lang.Numbers/add x 1)] y)" +
                "    nil))"));
    }

    /**
     * Nested when-let shapes inside a loop — this is exactly what {@code doseq} expands to.
     * The outer loop checks {@code (seq coll)}, the inner let-if reads the first element.
     */
    @Test
    public void nestedWhenLetInsideLoop() {
        assertEquals(6L, evalBytecode(
                "(loop* [s (clojure.lang.RT/seq (clojure.lang.RT/list 1 2 3)) acc 0]" +
                "  (let* [x s]" +
                "    (if x" +
                "      (let* [v (clojure.lang.RT/first s)]" +
                "        (recur (clojure.lang.RT/next s) (clojure.lang.Numbers/add acc v)))" +
                "      acc)))"));
    }

    // ========== 11. doseq macro expansion shape ==========

    /**
     * Hand-expanded {@code (doseq [x coll] body)} using raw special forms.
     * This is the exact shape that the {@code doseq} macro produces: a {@code loop*} with 4 vars
     * ({@code seq_}, {@code chunk_}, {@code count_}, {@code i_}), a chunked-seq fast path
     * ({@code .nth} on chunk), and a sequential fallback path with when-let that shadows
     * the seq binding. Uses lists (non-chunked) so only the sequential path runs.
     */
    @Test
    public void doseqExpansionSingleBinding() {
        assertEquals(6L, evalBytecode(
                "(let* [result (new java.util.concurrent.atomic.AtomicLong 0)" +
                "       coll (clojure.lang.RT/list 1 2 3)]" +
                "  (loop* [seq_ (clojure.lang.RT/seq coll) chunk_ nil count_ 0 i_ 0]" +
                "    (if (clojure.lang.Numbers/lt i_ count_)" +
                "      (do" +
                "        (. result (addAndGet (. chunk_ (nth i_))))" +
                "        (recur seq_ chunk_ count_ (clojure.lang.Numbers/add i_ 1)))" +
                "      (let* [seq_2 (clojure.lang.RT/seq seq_)]" +
                "        (if seq_2" +
                "          (if (instance? clojure.lang.IChunkedSeq seq_2)" +
                "            (let* [c (. ^clojure.lang.IChunkedSeq seq_2 (chunkedFirst))]" +
                "              (recur (. ^clojure.lang.IChunkedSeq seq_2 (chunkedMore)) c (. c (count)) 0))" +
                "            (do" +
                "              (. result (addAndGet (clojure.lang.RT/first seq_2)))" +
                "              (recur (clojure.lang.RT/next seq_2) nil 0 0)))" +
                "          nil))))" +
                "  (. result (get)))"));
    }

    /**
     * Same doseq expansion but with a {@link clojure.lang.PersistentVector} input.
     * Vectors produce {@link clojure.lang.PersistentVector.ChunkedSeq} so the chunked
     * fast-path is exercised.
     */
    @Test
    public void doseqExpansionChunkedInput() {
        assertEquals(10L, evalBytecode(
                "(let* [result (new java.util.concurrent.atomic.AtomicLong 0)" +
                "       coll (clojure.lang.PersistentVector/create (clojure.lang.RT/list 1 2 3 4))]" +
                "  (loop* [seq_ (clojure.lang.RT/seq coll) chunk_ nil count_ 0 i_ 0]" +
                "    (if (clojure.lang.Numbers/lt i_ count_)" +
                "      (do" +
                "        (. result (addAndGet (. chunk_ (nth i_))))" +
                "        (recur seq_ chunk_ count_ (clojure.lang.Numbers/add i_ 1)))" +
                "      (let* [seq_2 (clojure.lang.RT/seq seq_)]" +
                "        (if seq_2" +
                "          (if (instance? clojure.lang.IChunkedSeq seq_2)" +
                "            (let* [c (. ^clojure.lang.IChunkedSeq seq_2 (chunkedFirst))]" +
                "              (recur (. ^clojure.lang.IChunkedSeq seq_2 (chunkedMore)) c (. c (count)) 0))" +
                "            (do" +
                "              (. result (addAndGet (clojure.lang.RT/first seq_2)))" +
                "              (recur (clojure.lang.RT/next seq_2) nil 0 0)))" +
                "          nil))))" +
                "  (. result (get)))"));
    }

    /**
     * Two-binding doseq expansion (nested loops) with sequential (list) inputs.
     * Outer loop iterates a list of pairs, inner loop iterates elements of each pair.
     * This is the shape from {@code generate-proxy}'s multi-binding {@code doseq}.
     */
    @Test
    public void doseqExpansionTwoBindings() {
        assertEquals(10L, evalBytecode(
                "(let* [result (new java.util.concurrent.atomic.AtomicLong 0)" +
                "       pairs (clojure.lang.RT/list" +
                "               (clojure.lang.RT/list 1 2)" +
                "               (clojure.lang.RT/list 3 4))]" +
                "  (loop* [s1 (clojure.lang.RT/seq pairs)]" +
                "    (if s1" +
                "      (let* [pair (clojure.lang.RT/first s1)]" +
                "        (loop* [s2 (clojure.lang.RT/seq pair)]" +
                "          (if s2" +
                "            (do" +
                "              (. result (addAndGet (clojure.lang.RT/first s2)))" +
                "              (recur (clojure.lang.RT/next s2)))" +
                "            nil))" +
                "        (recur (clojure.lang.RT/next s1)))" +
                "      nil))" +
                "  (. result (get)))"));
    }

    // ========== 12. lazy-seq chain (for comprehension shape) ==========

    /**
     * Hand-expanded {@code (for [x coll] (f x))} — a recursive lazy-seq chain.
     * Each step creates a {@code LazySeq} whose thunk captures the current head + rest.
     * This tests that the closure's captured locals are all initialized when the
     * thunk is forced.
     */
    @Test
    public void forComprehensionLazySeqShape() {
        Object result = evalBytecode(
                "(let* [my-map (fn* my-map [f coll]" +
                "                (new clojure.lang.LazySeq" +
                "                  (fn* []" +
                "                    (let* [s (clojure.lang.RT/seq coll)]" +
                "                      (if s" +
                "                        (clojure.lang.RT/cons" +
                "                          (f (clojure.lang.RT/first s))" +
                "                          (my-map f (clojure.lang.RT/next s)))" +
                "                        nil)))))]" +
                "  (let* [result (my-map (fn* [x] (clojure.lang.Numbers/multiply x 10))" +
                "                        (clojure.lang.RT/list 1 2 3))]" +
                "    (clojure.lang.RT/first (clojure.lang.RT/next result))))");
        assertEquals(20L, result);
    }

    // ========== 13. Root local pool overflow ==========

    /**
     * The root local pool has a fixed initial size ({@code ROOT_LOCAL_POOL_INITIAL_SIZE = 32}).
     * When a function has more locals than the pool size, overflow locals are created with
     * {@code b.createLocal()} inside a block scope, making them subject to {@code CLEAR_LOCAL}
     * when the block ends. If a closure captures the parent frame (via
     * {@code LoadLocalMaterialized}) and reads one of these overflow locals after the block
     * has ended, it gets {@code FrameSlotTypeException} because the slot was cleared.
     * <p>
     * This test exhausts the pool with 33+ locals in a single {@code let*} block, then
     * creates a lazy-seq closure that reads one of the later locals (beyond the pool).
     * The closure is forced AFTER the let block — if the local was block-scoped rather
     * than root-scoped, its slot will have been cleared.
     */
    @Test
    public void rootLocalPoolOverflowLazySeq() {
        StringBuilder sb = new StringBuilder("(let* [");
        for (int i = 0; i < 36; i++) {
            if (i > 0) sb.append(" ");
            sb.append("a").append(i).append(" ").append(i);
        }
        sb.append("]");
        sb.append(" (let* [thunk (fn* [] (clojure.lang.RT/cons a35 nil))");
        sb.append("        lz (new clojure.lang.LazySeq thunk)]");
        sb.append("   (clojure.lang.RT/first lz)))");

        assertEquals(35L, evalBytecode(sb.toString()));
    }

    /**
     * Same test but reading a local just within pool range (slot 31) — should always work.
     */
    @Test
    public void rootLocalPoolWithinRangeLazySeq() {
        StringBuilder sb = new StringBuilder("(let* [");
        for (int i = 0; i < 36; i++) {
            if (i > 0) sb.append(" ");
            sb.append("a").append(i).append(" ").append(i);
        }
        sb.append("]");
        sb.append(" (let* [thunk (fn* [] (clojure.lang.RT/cons a31 nil))");
        sb.append("        lz (new clojure.lang.LazySeq thunk)]");
        sb.append("   (clojure.lang.RT/first lz)))");

        assertEquals(31L, evalBytecode(sb.toString()));
    }

    /**
     * 40 locals with a closure that captures the outermost frame, then is called
     * after the let block ends. Without proper root-scoped allocation, the closure
     * will read cleared slots.
     */
    @Test
    public void manyLocalsClosureCalledAfterBlockEnds() {
        StringBuilder sb = new StringBuilder("(let* [");
        for (int i = 0; i < 40; i++) {
            if (i > 0) sb.append(" ");
            sb.append("v").append(i).append(" ").append(i);
        }
        sb.append(" f (fn* [] (clojure.lang.Numbers/add v38 v39))]");
        sb.append(" (f))");

        assertEquals(77L, evalBytecode(sb.toString()));
    }

    /**
     * Simulates the actual {@code generate-proxy} pattern: an outer {@code fn*} with a
     * large {@code let*} (14+ closures + data bindings), followed by a body that calls
     * those closures. The function is defined, and then a lazy-seq is created whose thunk
     * invokes the function. The thunk is forced after the defining scope ends.
     * <p>
     * This exercises the specific failure mode: with 35+ locals in the fn, some locals
     * spill past the root pool and become block-scoped. When the lazy-seq thunk forces
     * execution of a closure that reads those spilled locals from a materialized parent
     * frame, the slots may already be cleared.
     */
    @Test
    public void generateProxyScaleClosureSpill() {
        // Create a fn with 36 let-bound locals (some are closures), then return a lazy-seq
        // whose thunk calls one of the closures. Force the seq from outside.
        StringBuilder sb = new StringBuilder();
        sb.append("(let* [big-fn (fn* []");
        sb.append("  (let* [");
        for (int i = 0; i < 30; i++) {
            sb.append("v").append(i).append(" ").append(i).append(" ");
        }
        // Add some closure bindings that capture earlier locals
        sb.append("f0 (fn* [x] (clojure.lang.Numbers/add v0 x)) ");
        sb.append("f1 (fn* [x] (clojure.lang.Numbers/add v1 x)) ");
        sb.append("f2 (fn* [x] (clojure.lang.Numbers/add v2 x)) ");
        sb.append("f3 (fn* [x] (clojure.lang.Numbers/add v3 x)) ");
        sb.append("f4 (fn* [x] (clojure.lang.Numbers/add v4 x)) ");
        sb.append("f5 (fn* [x] (clojure.lang.Numbers/add v29 x))]");
        // body: call f5(100), returns 29+100=129
        sb.append("   (f5 100)))");
        sb.append("]");
        // Create a lazy-seq whose thunk calls big-fn and force it
        sb.append("  (clojure.lang.RT/first");
        sb.append("    (new clojure.lang.LazySeq");
        sb.append("      (fn* [] (clojure.lang.RT/cons (big-fn) nil)))))");

        assertEquals(129L, evalBytecode(sb.toString()));
    }

    /**
     * The key failure mode: a {@code fn*} with many locals where the pool is exhausted
     * in an outer let, then an inner let creates block-scoped overflow locals, and a
     * closure from the inner let captures one of those overflow locals. The closure is
     * forced AFTER the inner let block ends (via lazy-seq). If the overflow local was
     * block-scoped (not root-scoped), {@code CLEAR_LOCAL} fires at {@code endBlock()}
     * and the materialized-frame read in the closure gets {@code FrameSlotTypeException}.
     */
    @Test
    public void nestedBlockOverflowClosure() {
        // This fn has an outer let with enough bindings to exhaust the pool,
        // then returns a lazy-seq whose thunk captures an overflow local.
        //
        // The fn is:
        //   (fn* []
        //     (let* [x0 0 x1 1 ... x31 31]
        //       (let* [y0 100 y1 101 ... y5 105]    ;; overflow locals (block-scoped if pool empty)
        //         (new LazySeq (fn* [] (cons y5 nil))))))
        //
        // Then: (RT/first (the-fn))
        //  - the-fn returns the LazySeq
        //  - RT/first forces the thunk
        //  - the thunk reads y5 from the materialized parent frame
        //  - if y5's slot was block-scoped, it was CLEARed when the inner let ended
        //  - → FrameSlotTypeException
        StringBuilder sb = new StringBuilder();
        sb.append("(let* [the-fn (fn* []");
        sb.append("  (let* [");
        for (int i = 0; i < 32; i++) {
            sb.append("x").append(i).append(" ").append(i).append(" ");
        }
        sb.append("]");
        sb.append("    (let* [");
        for (int i = 0; i < 6; i++) {
            sb.append("y").append(i).append(" ").append(100 + i).append(" ");
        }
        sb.append("]");
        sb.append("      (new clojure.lang.LazySeq");
        sb.append("        (fn* [] (clojure.lang.RT/cons y5 nil))))))");
        sb.append("]");
        sb.append("  (clojure.lang.RT/first (the-fn)))");

        assertEquals(105L, evalBytecode(sb.toString()));
    }

    /**
     * Same structure but with fewer let bindings (well within pool range).
     * Verifies the pattern works when locals don't overflow the pool.
     */
    @Test
    public void nestedBlockWithinPoolRange() {
        StringBuilder sb = new StringBuilder();
        sb.append("(let* [the-fn (fn* []");
        sb.append("  (let* [");
        for (int i = 0; i < 10; i++) {
            sb.append("x").append(i).append(" ").append(i).append(" ");
        }
        sb.append("]");
        sb.append("    (let* [y0 100]");
        sb.append("      (new clojure.lang.LazySeq");
        sb.append("        (fn* [] (clojure.lang.RT/cons x9 nil))))))");
        sb.append("]");
        sb.append("  (clojure.lang.RT/first (the-fn)))");

        assertEquals(9L, evalBytecode(sb.toString()));
    }

    // ========== 14. Complex proxy-like pattern ==========

    @Test
    public void generateProxyLikePatternEvaluates() {
        assertEquals(10L, evalBytecode(
                "(let* [transform (fn* [x y] (clojure.lang.Numbers/add x y))" +
                "       format-val (fn* [label val] (clojure.lang.Numbers/multiply val 2))" +
                "       items (clojure.lang.RT/list 1 2 3 4)]" +
                "  (loop* [xs items acc 0]" +
                "    (if (clojure.lang.RT/first xs)" +
                "      (let* [head (clojure.lang.RT/first xs)" +
                "             processed (transform head acc)" +
                "             rest (clojure.lang.RT/next xs)]" +
                "        (recur rest processed))" +
                "      acc)))"));
    }
}
