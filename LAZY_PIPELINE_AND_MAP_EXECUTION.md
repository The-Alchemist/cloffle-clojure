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

---

## Phase 2: Composable View Sequences for `[]` (`MappedVectorSeq`)

### Scope & Objective (Step 2)
Implement `clojure.lang.MappedVectorSeq` for vector mapping pipelines to:
1. Bypass `lazy-seq` overhead for vector mapping, preserving the memoization and `IPending` invariants.
2. Implement algebraic function composition in `create(f, coll, index)` to collapse `(->> v (map f) (map g))` pipelines to a single node over the root vector.
3. Implement `IReduce` and `IReduceInit` for tight-loop reduction without sequence node allocation.
4. Implement `IndexedSeq`, `Counted`, `Indexed`, and `IPending`.
5. Address the memoization-vs-virtualizability question (§4A): drop the `COMPUTING` spinlock and use thread-safe double-checked locking.

### Implementation Details

#### 1. `MappedVectorSeq.java` Design
- **Interfaces:** `ASeq` subclass implementing `IndexedSeq`, `IReduce`, `Counted`, `IPending`, `Indexed`, and `Serializable`.
- **Fields:**
  - `public final IFn f;`
  - `public final IPersistentVector v;`
  - `public final int i;`
  - `private volatile Object _val = UNREALIZED;`
  - `private volatile ISeq _next = null;`
- **Memoization & Thread Safety:**
  - `first()`: volatile fast-path check `_val != UNREALIZED`. If unrealized, synchronizes on `this`, checks again, and computes `_val = f.invoke(v.nth(i))`.
  - `next()`: if `i + 1 >= v.count()`, returns `null` immediately. Otherwise double-checks `_next == null` under monitor before constructing `new MappedVectorSeq(f, v, i + 1)`.
  - `isRealized()`: returns `_val != UNREALIZED` (distinct sentinel object ensures `f` returning `null` remains correctly marked as realized).
- **Algebraic Composition (`create`):**
  - When wrapping an existing `MappedVectorSeq mvs`, creates `ComposedFn(g, mvs.f)` and shares `mvs.v` with offset index `mvs.i + i`.
  - Avoids chaining intermediate sequences; $N$ maps over a vector collapse to a single `MappedVectorSeq` over `v`.
- **Accelerated `IReduce` / `IReduceInit`:**
  - `reduce(rf, start)`: iterates `for (int x = i; x < n; x++)` directly invoking `rf.invoke(acc, f.invoke(v.nth(x)))`. Unpacks `Reduced` and exits early on short-circuit.
  - `reduce(rf)`: seeds accumulator with `f.invoke(v.nth(i))` and iterates remaining elements.
  - Allocates zero `MappedVectorSeq` or intermediate cons cells during reductions.
- **Empty & Bounds Handling:**
  - If `v == null || i >= v.count() || i < 0`, `create` returns `PersistentList.EMPTY`.

#### 2. Memoization vs. Virtualizability Resolution (§4A)
- Dropped the `COMPUTING` sentinel and spinlock in favor of monitor double-checked locking, avoiding CPU burn and starvation.
- The memoizing node strictly adheres to Clojure semantics (§2B memoization, §2C `IPending`), ensuring that side-effecting mapping functions are executed at most once per element across all threads.
- As analyzed in §4A, mutable sequence nodes with volatile caching fields cannot be `@ValueType`. Reduction optimizations bypass sequence materialization, enabling high throughput while preserving full language contract fidelity.

### Test Verification

#### Unit Tests (`src/test/java/clojure/lang/MappedVectorSeqTest.java`)
Added 9 comprehensive unit tests:
- **`testSequentialInterfaceContract`**: Tests `first()`, `next()`, `more()`, `count()`, `index()`, `nth()`, `equiv()`, `equals()`, `hashCode()`, and `hasheq()`.
- **`testMemoizationOnRealization`**: Tests side-effecting function; verifies `first()` evaluates once and subsequent calls return cached value without invoking function again.
- **`testIPendingContract`**: Asserts `isRealized()` transitions from `false` to `true` upon `first()`.
- **`testAlgebraicComposition`**: Verifies composing two and three mapping operations retains single-level indirection over the root vector.
- **`testNestedVectorTraversal`**: Verifies polyglot Cloffle execution over vectors of maps.
- **`testReduceAndReducedShortCircuiting`**: Tests 1-arg `reduce`, 2-arg `reduce`, and early short-circuiting on `Reduced`.
- **`testEmptyAndBoundsHandling`**: Verifies empty vectors return `PersistentList.EMPTY` and boundary indices return `null` / `EMPTY`.
- **`testConcurrentRealization`**: Races 16 threads accessing `first()` concurrently; verifies function executes exactly once.
- **`testClojureIntegrationAndAtomMemoization`**: Cloffle polyglot guest evaluation using an atom to assert single execution under Clojure runtime.

#### Command Verifications
1. **JUnit Test Suite:**
   ```sh
   clojure -T:build run-tests
   ```
   **Result:** PASSED (894 tests started, 894 successful, 0 failed).

2. **Clojure Language Test Suite:**
   ```sh
   clojure -T:build run-clj-tests
   ```
   **Result:** PASSED (633 tests containing 18,848 assertions, 0 failures, 0 errors).

