# Cloffle Plan: LazySeq and MappedVectorSeq / MappedMapSeq PEA

## 1. Status: re-baseline before acting on this plan

**The pass/fail numbers this plan was originally built on are not trustworthy, and the first step is
to reproduce them.** This plan previously opened with "44 of 52 scalar replacement benchmarks pass
with 0 heap allocations". That figure came from a `check-scalar-replacement` that could report a
clean pass without ever looking at the code being measured. Three separate faults, all since fixed
in [build.clj](build.clj):

- **The wrong compilation was analyzed.** A hot root node is compiled many times, and a tier can be
  recompiled into a near-empty graph after a deoptimization. Selecting the candidate with the most
  graphs once picked a 9-node deoptimized recompile over the real 909-node one, then reported it
  clean.
- **Dumps were silently truncated.** Graal writes from compiler threads, so a JVM that exits first
  leaves the file ending mid-record. The phase that would have shown the allocation was simply never
  written, and its absence was read as success.
- **JMH runs were too short**, which is what caused the truncation: the final-tier compilation
  happens last, so it was usually the one still being written at exit.

A pass on a graph too small to contain the benchmark's work proves nothing. The checker now warns
below 25 nodes, reports truncation as its own failure, and defaults to `-wi 3 -i 2 -w 2s -r 3s`.

**Action:** re-run the six benchmarks below and record what actually fails, and on which node, before
writing any code. See [Verification](#5-verification).

## 2. What we now know actually allocates

Investigating the related `guestPipeline*` benchmarks with a working harness overturned the
assumption behind much of this plan. Method and probe scripts are in
[HOWTO_SEAFOAM.md](HOWTO_SEAFOAM.md).

**Sequence wrappers were not the surviving cost.** In `guest-pipeline-reduce`, partial evaluation
inlined the whole pipeline into one graph and PEA virtualized the sequence objects successfully.
What survived into `CommitAllocationNode`s was:

```
object(4095) = ClojureClosure[...]
object(4082) = FrameWithoutBoxing[...]
object(4083) = Object[][...]     ; 35 elements
object(4084) = long[][...]       ; 35 elements
```

Tracing `nodeSourcePosition` named the cause exactly:

```
com.oracle.truffle.runtime.OptimizedTruffleRuntime#createMaterializedFrame
  net.javacrumbs.cloffle.nodes.ClojureRootNode#snapshotFrame
    net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode$GetOuterFrame#doGet
```

`snapshotFrame` copies the **entire** frame unconditionally — it clones the arguments array and
`copyTo`s every slot in the descriptor, whether or not the closure captures them:

```69:81:src/jvm/net/javacrumbs/cloffle/nodes/ClojureRootNode.java
    public static MaterializedFrame snapshotFrame(VirtualFrame virtualFrame) {
        FrameDescriptor fd = virtualFrame.getFrameDescriptor();
        MaterializedFrame snapshot =
                Truffle.getRuntime().createMaterializedFrame(
                        virtualFrame.getArguments().clone(), fd);
        if (fd.getNumberOfSlots() > 0) {
            // copyTo preserves each slot's runtime tag as well as its value. That matters for
            // Bytecode DSL object locals whose immutable descriptor still reports Illegal.
            virtualFrame.copyTo(0, snapshot, 0, fd.getNumberOfSlots());
        }

        return snapshot;
    }
```

That is why the backing arrays are 35 elements wide in a function that captures a couple of
variables.

**Why this matters here.** `(lazy-seq body)` expands to `(new LazySeq (fn* [] body))`, so every
`lazy-seq` creates a closure. If that closure captures anything, the closure's materialized frame —
not the `LazySeq` wrapper — may well be the dominant allocation. **Confirm which of the two is
actually surviving in these six benchmarks before choosing a fix.** Narrowing `snapshotFrame` to the
captured slots would be a more general lever than anything `lazy-seq`-specific, and would benefit
every closure in the language.

**Truffle PE already inlines across compilation units.** A callee having its own
`TruffleHotSpotCompilation` dump does not mean it was not inlined into the caller; it only means it
got hot on its own. Any plan step justified by "let Truffle inline this" is probably solving a
non-problem — check `After PE Tier` of the caller instead of assuming.

## 3. Root causes (verified against current source)

### A. `lazy-seq`

`(lazy-seq <expr>)` expands to `(new clojure.lang.LazySeq (fn* [] <expr>))`, which in Cloffle emits
a `CreateClosure` (allocating a `ClojureClosure`) and a `NewLazySeq`.

`LazySeq` cannot be `@ValueType`: it holds `volatile int state` plus mutable `sv`/`s`/`fn`, and both
`seq()` and `realize()` use `synchronized (this)`. `realize()` is additionally `@TruffleBoundary`,
which stops partial evaluation from inlining the thunk, so the `LazySeq` and its closure escape at
that boundary. All still true as of this writing.

### B. `MappedVectorSeq` / `MappedMapSeq`

Both carry memoization state that defeats scalar replacement:

```java
private volatile Object _val = UNREALIZED;
private volatile ISeq _next = null;
```

with `synchronized (this)` in `first()` and `next()`.

`EphemeralVectorSeq` is the immutable counterpart: `@ValueType`, `final` fields `f`/`v`/`i`, no
volatiles, no locks. But **`MappedVectorSeq.create` never returns one.** It always constructs a
`MappedVectorSeq`, and when handed an `EphemeralVectorSeq` it actively *downgrades* it:

```77:83:src/jvm/clojure/lang/MappedVectorSeq.java
        if (coll instanceof EphemeralVectorSeq evs) {
            int newIdx = evs.i + i;
            if (evs.v == null || newIdx >= evs.v.count() || newIdx < 0) {
                return null;
            }
            IFn composed = new ComposedFn(g, evs.f);
            return new MappedVectorSeq(composed, evs.v, newIdx);
        }
```

There is no `EphemeralMapSeq`.

## 4. Plan

Ordered so that each step is justified by evidence from the step before it.

### Step 0. Re-baseline, and fix the benchmarks' element types

Re-run the six benchmarks and record, per benchmark, the surviving types and their source positions.

While doing so, **replace the boxed integers.** These benchmarks currently measure integer
arithmetic as much as sequences:

```55:80:src/benchmark/resources/keyword-map-benchmark/setup.clj
(defn guest-lazy-seq-first [x]
  (first (lazy-seq [x])))

;; ... other lazy-seq shapes ...

(defn guest-mapped-vector-reduce [x y]
  (reduce + 0 (clojure.lang.MappedVectorSeq/create inc [x y] 0)))
```

with `guestLazySeqFirstFn.invoke(1)` on the Java side. A real PEA graph from the pipeline work showed
`7 x java.lang.Integer` surviving, so `Integer` boxes contaminate exactly the allocation counts these
are meant to attribute to sequence wrappers. Switch to reference operations — interned keywords, set
membership, `Keyword.getName` — as the `guestPipeline*` benchmarks already do.

Only after this does "0 allocations" mean "the sequence wrapper was eliminated".

### Step 1. Decide between the closure/frame fix and the `lazy-seq` fix

From Step 0's traces, determine whether the surviving allocation in the four `lazy-seq` benchmarks
is the `LazySeq` itself, the `ClojureClosure`, or the materialized frame behind it.

If it is the frame, fix `snapshotFrame` to copy only the slots the closure captures. That is one
change benefiting every closure, and it likely subsumes much of Step 2.

### Step 2. `lazy-seq` consumer inlining (only if Step 1 says the wrapper is the cost)

Where a `lazy-seq` is created and consumed within one compilation unit — `(first (lazy-seq body))`,
`(seq (lazy-seq body))` — rewrite to consume `body` directly, so neither the thunk nor the wrapper is
built.

**Carry this history into the design.** An earlier `ExprToBytecodeFusion.java` was written and later
removed, and an `ExprToBytecodePipeline.java` attempt was abandoned incomplete. Recognizing shapes in
the AST is not new ground here and has not stuck. Anything along these lines needs conservative,
purely syntactic guards, a kill switch, and a test that proves the rewrite actually fired rather than
inferring it from a timing change.

### Step 3. Route non-escaping vector maps through `EphemeralVectorSeq`

Have `MappedVectorSeq.create` return `EphemeralVectorSeq` where the memoization is not observable,
and at minimum stop downgrading an `EphemeralVectorSeq` input into a `MappedVectorSeq`.

Note the correction: the blocker is the volatile/synchronized memoization state, **not** a missing
inline. Do not justify this step with "let Truffle inline the reduction loop".

### Step 4. `EphemeralMapSeq`, if Step 0 still shows `guestMappedMapFirst` failing

A `@ValueType`, fully immutable map-seq view mirroring `EphemeralVectorSeq`, with `MappedMapSeq.create`
delegating to it for unshared sequences.

## 5. Verification

Six benchmarks in scope:

| Benchmark | Guest shape |
| --- | --- |
| `guestLazySeqFirst` | `(first (lazy-seq [x]))` |
| `guestLazySeqConsFirst` | `(first (lazy-seq (cons x nil)))` |
| `guestLazySeqApplyFirst` | `(first (lazy-seq [(inc x)]))` |
| `guestLazySeqWhenSeqFirst` | `(first (lazy-seq (when-let [s (seq [x])] [(first s)])))` |
| `guestMappedVectorReduce` | `(reduce + 0 (MappedVectorSeq/create inc [x y] 0))` |
| `guestMappedMapFirst` | `(val (first (MappedMapSeq/create identity {k v})))` |

All six at once:

```sh
clojure -T:build check-scalar-replacements :suite :guest :filter '"guestLazySeq|guestMapped"'
```

One benchmark, keeping the dump for inspection:

```sh
clojure -T:build check-scalar-replacement \
  :benchmark '"KeywordMapBenchmark.guestLazySeqFirst"' \
  :guest true ":throw?" false
```

Quote `":throw?"` in zsh, which otherwise globs the `?`.

**Do not pass `:quiet true`.** Suppressing the output is what made the original vacuous passes
invisible; use `:verbose true` on the plural task when a result looks surprising.

Note that the two tasks take different options. `:quiet` and `:compile` exist only on
`check-scalar-replacement` (singular); the plural `check-scalar-replacements` takes `:suite`,
`:filter`, `:fail-fast`, `:verbose`, and `:list`. The original form of this plan passed
`:compile false` to the plural task, where it is silently ignored.

Read and believe the warnings: a truncation report or a "graph this small cannot contain a
benchmark's work" warning means the result is not evidence, regardless of pass or fail.

To identify **what** survives and where it comes from, which is what Step 0 and Step 1 turn on:

```sh
clojure -T:build explain-allocations \
  :benchmark '"KeywordMapBenchmark.guestLazySeqFirst"' :guest true
```

That reports the virtual objects at PEA split into scalar replaced versus committed, the type of
each survivor with its inlined source frames, and `relativeFrequency` so a cold deopt-path
allocation is not mistaken for a hot one. It is reporting only and never fails. For anything it does
not cover, [HOWTO_SEAFOAM.md](HOWTO_SEAFOAM.md) has the manual method.

Full regression:

```sh
clojure -T:build run-tests
```

## 6. File impact

- `src/benchmark/resources/keyword-map-benchmark/setup.clj` and `KeywordMapBenchmark.java` — replace
  boxed integer elements with reference operations (Step 0).
- `src/jvm/net/javacrumbs/cloffle/nodes/ClojureRootNode.java` — narrow `snapshotFrame` to captured
  slots (Step 1, likely the highest-leverage change here).
- `src/jvm/net/javacrumbs/cloffle/bytecode/ExprToBytecode.java` — `lazy-seq` consumer inlining
  (Step 2, only if justified).
- `src/jvm/clojure/lang/MappedVectorSeq.java` — return / preserve `EphemeralVectorSeq` (Step 3).
- `src/jvm/clojure/lang/EphemeralMapSeq.java` (new) and `MappedMapSeq.java` (Step 4).

## 7. Open questions

- In these six benchmarks specifically, is the surviving allocation the sequence wrapper or the
  closure's materialized frame? Everything above branches on this and it is unanswered.
- Can `snapshotFrame` know which slots a closure captures at the point it runs, or does that
  information need to be threaded down from `CreateClosure`?
- Is `@TruffleBoundary` on `LazySeq.realize()` load-bearing for correctness, given the
  `synchronized` block it contains, or is it defensive?
