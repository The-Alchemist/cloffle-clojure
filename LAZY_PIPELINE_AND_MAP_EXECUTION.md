# Lazy Pipeline & Map Optimization Execution Log

This document tracks the execution, decisions, benchmarks, and test results for each phase of [LAZY_PIPELINE_AND_MAP_PLAN.md](LAZY_PIPELINE_AND_MAP_PLAN.md).

---

## Phase 1: `LazySeq` Correctness & Reduction Acceleration

### Scope & Objective (Step 1)
Modernize `src/jvm/clojure/lang/LazySeq.java` by:
1. Reverting the CAS spin lock to a bounded slow path with `@TruffleBoundary`.
2. Stabilizing state transitions using `UNREALIZED` (0), `FORCED` (1), and `REALIZED` (2).
3. Eliminating CPU burn on blocking thunks and preventing hangs on self-referential sequences.
4. Ensuring post-force transient failure does not corrupt nodes into empty sequences.
5. Implementing `IReduce` / `IReduceInit` for eager reduction acceleration.

### Implementation Details

#### 1. State Machine & Thread Safety
- **States:**
  - `UNREALIZED = 0`: Thunk `fn` not yet invoked.
  - `FORCED = 1`: Thunk `fn` invoked; raw result stored in `sv`; `s` not yet finalized.
  - `REALIZED = 2`: `s` finalized; published via volatile release.
- **Fast Path (`seq()`):**
  - Volatile acquire read of `state`. If `state == REALIZED`, returns `s` immediately without entering monitors or truffle boundaries.
- **Slow Path (`realize()`):**
  - Marked with `@TruffleBoundary` so the monitor synchronization, iterative trampoline, exception propagation, and class loading remain outside the Truffle guest partial evaluation graph.
  - Synchronizes on `this`. If already `REALIZED`, returns `s`.
  - Runs `force()`, which executes `fn` and sets `state = FORCED` while nulling `fn`.
  - Trampoline unrolls nested `LazySeq` instances iteratively in a `while (ls instanceof LazySeq lz)` loop.
  - Direct self-cycle guard: `if (lz == this) throw new IllegalStateException("Recursive lazy-seq realization");`.
  - Trampoline step `sval()` synchronizes on `lz`, forces if needed, and returns `lz.sv` without clearing it, keeping intermediate links valid and recoverable.
  - Computes `s = RT.seq(ls)`.
  - Sets `this.sv = null` only *after* `RT.seq(ls)` succeeds. A throw during `RT.seq(ls)` leaves `sv` intact and `state` at `FORCED`, making realization retriable rather than corrupting into `()`.
  - Sets volatile `state = REALIZED` to publish `s`.

#### 2. Accessors Routing & Protocols
- `first()`, `next()`, and `more()` route through the `ISeq` returned by `seq()` (`ISeq sq = seq(); return sq == null ? ... : sq.first();`) instead of re-reading the `s` field.
- `isRealized()` returns `state != UNREALIZED` to align with Clojure's specification (`fn == null`).
- Implemented `IReduce` and `IReduceInit`:
  - `reduce(IFn rf)`: seeds accumulator with `first()`, iterates through `next()`, and respects `RT.isReduced` short-circuiting. If empty seq, invokes `rf.invoke()`.
  - `reduce(IFn rf, Object start)`: seeds with `start`, traverses elements via `next()`, and unpacks `Reduced` values when short-circuited.

### Test Verification

#### Unit Tests (`src/test/java/clojure/lang/LazySeqLockFreeTest.java`)
Added comprehensive JUnit test suite covering all invariants:
- **`testConcurrentRealization`**: 16 concurrent threads race calling `.seq()`, `.first()`, `.count()` on an unrealized sequence. Asserts exactly-once thunk execution.
- **`testMemoization`**: Asserts repeated `first()` calls evaluate the thunk only once.
- **`testIPendingContract`**: Asserts `isRealized()` is `false` prior to dereference and `true` afterwards.
- **`testLazinessPreservation`**: Asserts thunk is not invoked upon sequence instantiation.
- **`testReduceAndReduceInit`**: Validates 1-arg `reduce`, 2-arg `reduce`, early termination on `Reduced`, and empty sequence handling.
- **`testPostForceExceptionRecovery`**: Simulates transient exception during `RT.seq(ls)`. Asserts sequence is not marked `REALIZED` (remains `FORCED`), and retrying after fixing the failure successfully produces the sequence.
- **`testNestedIntermediateExceptionRecovery`**: Confirms failure in nested thunks leaves intermediate chains recoverable.
- **`testDeepChain`**: Traverses a 100,000-deep nested `LazySeq` chain iteratively without `StackOverflowError` or $O(N)$ allocation.
- **`testConcurrentIntermediateAccess`**: Races 16 threads accessing `outer` and `middle` nodes concurrently to ensure consistent results without empty sequence corruption.
- **`testSelfReferenceTermination`**: Direct self-cycle `(def s (lazy-seq s))` throws `IllegalStateException("Recursive lazy-seq realization")` within timeout instead of hanging.
- **`testConsGuardedSelfReferenceWorks`**: Confirms standard recursive pattern `(lazy-seq (cons 1 ones))` works as expected.

#### Command Verifications
1. **JUnit Test Suite:**
   ```sh
   clojure -T:build run-tests
   ```
   **Result:** PASSED (11/11 in `LazySeqLockFreeTest`, all suite tests passing).

2. **Clojure Language Test Suite:**
   ```sh
   clojure -T:build run-clj-tests
   ```
   **Result:** PASSED (All Cloffle bytecode tests passed).

3. **Scalar Replacement Check:**
   ```sh
   clojure -T:build check-scalar-replacement :benchmark '"KeywordMapBenchmark.guestLazySeqFirst"' :guest true
   ```
   **Result:** `Scalar replacement check passed` (PASS, 0 B/op).
