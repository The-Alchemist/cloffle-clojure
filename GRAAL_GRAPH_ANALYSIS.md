# Graal Compiler Graph Analysis

Recorded PEA / scalar-replacement findings for Cloffle. How to dump graphs and read them is in
[HOWTO_SEAFOAM.md](HOWTO_SEAFOAM.md). Optimization background is in
[PARTIAL_ESCAPE_ANALYSIS.md](PARTIAL_ESCAPE_ANALYSIS.md).

All graph inspection in this file was done with the **Java** Seafoam API
(`com.github.thealchemist.BgvDump` from `seafoam-jruby` on the `:build` alias). `build.clj` opens
dumps in-process via `check-scalar-replacement`, `analyze-graal-graph`, and `explain-allocations`.
Do not use the MRI `seafoam` CLI.

- [Baseline scalar replacement](#baseline-scalar-replacement)
- [KeywordMapBenchmark: shared update vs PEA](#keywordmapbenchmark-shared-update-vs-pea)
- [Minimal strictly-necessary PEA architecture](#minimal-strictly-necessary-pea-architecture)
- [Simplification experiments](#simplification-experiments)
- [Real-world Clojure idiom opportunities](#real-world-clojure-idiom-opportunities-ring-hiccup-map-reduction)
- [Keyword arguments, request maps, Tuple2](#keyword-arguments-ephemeral-request-maps-and-tuple2-transformations-opportunities-4-5-6)
- [Option map accumulators](#option-map-accumulators-with-cond--opportunity-9)
- [Event enrichment 8→9](#event-enrichment--89-shapemap16-transition-promotion-pea-opportunity-10)
- [Sanitization pipelines](#sanitization-pipelines--keyworddissoc-transition-caching-opportunity-11)
- [ComparePerformance `nested-get-in`](#compareperformance-nested-get-in-igv-2026-09-04)
- [ComparePerformance `ring-response`](#compareperformance-ring-response-analysis--constant-map-lowering-2026-09-05)
- [Graph evidence does not predict allocation](#graph-evidence-does-not-predict-allocation-2026-09-07)
- [TuplePeaBenchmark: for-fold vs while-fold carry](#tuplepeabenchmark-for-fold-vs-while-fold-carry-2026-09-10)

## Graph evidence does not predict allocation (2026-09-07)

Why `check-scalar-replacement` gates on `gc.alloc.rate.norm` rather than on the low-tier graph.

One JMH run of `KeywordMapBenchmark.guestPipelineReduce`
(`(reduce (fn [acc k] k) :none (filter pipeline-keys [k1 k2]))`), 2770 ns/op, **6168 B/op**:

| Compilation unit | Low-tier nodes | Alloc stubs | Max `relativeFrequency` | Σ freq | Stubs > 0.05 |
| --- | --- | --- | --- | --- | --- |
| `guest-pipeline-reduce` | 1280 | 13 | 0.0100 | 0.130 | 0 |
| `clojure.core_filter` | 1693 | 18 | 0.0050 | 0.090 | 0 |
| `clojure.core_filter` inner `fn` | 1048 | 3 | 0.0033 | 0.007 | 0 |

Every allocation stub in all three units is cold, yet the benchmark allocates 6 KB per operation.
Three properties of graph evidence explain the gap, and all three are general:

1. `relativeFrequency` is a **static estimate**, not a measurement. The uniform 0.0100 / 0.0050
   values are Graal's defaults for uncommon paths. Code that deoptimizes runs those paths
   constantly and rematerializes on each one.
2. It is **scoped to one compilation unit**. This pipeline became four Truffle roots; allocation in
   a sibling unit, in a unit the `MethodFilter` never dumped, or in interpreted code is invisible.
3. Frequencies are **not comparable across units**, so they cannot be summed into a per-operation
   figure.

The same blindness produced the opposite error earlier in this benchmark's history: selection
picked a 9-node delegating wrapper and reported a pass while the work happened elsewhere.

A structural alternative was tried and rejected. Classifying stubs by whether their control flow
reaches a `DeoptimizeNode` separated only 3 of the 13, and none reached the graph's single
`ReturnNode`; raw edges carry no `kind` property (Seafoam's `GraalPass` adds that only to the
described copy), so control flow has to be rebuilt from input slot names. Fragile, and it would not
have caught this case.

Calibration measurements taken at the same time:

```text
PersistentTypeScalarReplacementBenchmark.baselineTuple2ScalarReplacement  0.34 ns/op   ≈10⁻⁶ B/op
KeywordMapBenchmark.guestShapeMapEphemeralPipeline                        5.69 ns/op       24 B/op
KeywordMapBenchmark.guestPipelineReduce                                2769.71 ns/op     6168 B/op
```

The 24 B/op case is why budgets are per-benchmark rather than a single global zero.

Where the 6168 B/op originates is still open; no compiled unit accounts for it. Attribution needs
`-prof async:event=alloc`, and `-Djdk.graal.TraceDeoptimization` would show whether this is a
deoptimization storm.

## TuplePeaBenchmark: for-fold vs while-fold carry (2026-09-10)

Host microbench in `TuplePeaBenchmark`: fold two `PersistentTuple2` values with `sum(acc, step)`
where `sum` allocates a fresh tuple each time. Same logic, two loop shapes:

- `loopFoldSumCarry` — `for (i = 1; i <= 16; i++)`
- `whileFoldSumCarry` — `while (n > 0) { …; n--; }` with `@Param trips` (16 for this dump)

JMH `-prof gc` (`trips = 16`):

```text
loopFoldSumCarry     ~0.02 ns/op   ≈ 0 B/op
whileFoldSumCarry   ~30 ns/op      ~160 B/op
```

`explain-allocations` (see [HOWTO_SEAFOAM.md](HOWTO_SEAFOAM.md)):

```bash
clojure -T:build explain-allocations \
  :benchmark '"TuplePeaBenchmark.loopFoldSumCarry"' \
  :dump-path '"target/graal-dumps-pea-loop-fold"'

clojure -T:build explain-allocations \
  :benchmark '"TuplePeaBenchmark.whileFoldSumCarry"' \
  :params '{"trips" "16"}' \
  :dump-path '"target/graal-dumps-pea-while-fold"'
```

**`loopFoldSumCarry`** — `target/graal-dumps-pea-loop-fold/HotSpotCompilation-*[loopFoldSumCarry()int].bgv`

- PEA: 1 × `PersistentTuple2` **scalar replaced**, 0 committed.
- Low tier: no `new_instance_or_null` stubs.

**`whileFoldSumCarry` (trips=16)** — `target/graal-dumps-pea-while-fold/HotSpotCompilation-*[whileFoldSumCarry(TripParam)int].bgv`

- PEA: 2 × `PersistentTuple2` **committed** (0 scalar replaced).
- Both commits feed **`ValuePhiNode #257 (values) [branch merge]`** — the carried `acc` tuple
  cannot stay virtual when the loop header merges entry and back-edge values that PEA treats as
  disagreeing shapes (same mechanism as loop-carried interpreter state in the tuple-destructure
  case study in HOWTO).
- Low tier: six `new_instance_or_null` stubs (mostly cold `relativeFrequency`); measured allocation
  is still ~160 B/op, so the hot path is materializing the fold carrier, not only deopt stubs.

Takeaway: a **constant-bound `for`** lets Graal unroll/scalar-replace the entire fold; a **`while`
with a carried `IPersistentVector`** is a much sharper test of PEA through loop phis and matches
Clojure-style reducers that thread a small vector through a loop.

## Baseline scalar replacement

Independent of Clojure map dispatch or branch merges:

- `ScalarReplacementBenchmark`: pure Java (`SimpleBox`, `SimplePair`).
  - `baselineScalarReplacementLiteral`: `new SimpleBox(2 + 3)` then `box.k`.
  - `baselineScalarReplacementFields`: `new SimplePair(argA, argB)` then `pair.a + pair.b`.
- `PersistentTypeScalarReplacementBenchmark`: persistent collection PEA.
  - `baselineTuple2ScalarReplacement` / `baselineTuple3ScalarReplacement` / `baselineTuple4ScalarReplacement`:
    `Tuple.create` + `nth` consumed as `int`.
  - `tuple2AssocNThenNth` / `tuple2ConsThenNth`: same-class rewrite and Tuple2→Tuple3 promotion.

JMH (`-prof gc`, `-f 1 -wi 2 -i 2`):

```text
Benchmark                                                                Score        gc.alloc.rate.norm
ScalarReplacementBenchmark.baselineScalarReplacementLiteral              0.25 ns/op   ≈ 10⁻⁴ B/op (0 B/op)
ScalarReplacementBenchmark.baselineScalarReplacementFields               0.32 ns/op   ≈ 10⁻⁴ B/op (0 B/op)
PersistentTypeScalarReplacementBenchmark.baselineTuple2ScalarReplacement 0.34 ns/op   ≈ 10⁻⁴ B/op (0 B/op)
PersistentTypeScalarReplacementBenchmark.baselineTuple3ScalarReplacement 0.41 ns/op   ≈ 10⁻⁶ B/op (0 B/op)
PersistentTypeScalarReplacementBenchmark.baselineTuple4ScalarReplacement 0.41 ns/op   ≈ 10⁻⁶ B/op (0 B/op)
PersistentTypeScalarReplacementBenchmark.tuple2AssocNThenNth             0.32 ns/op   ≈ 10⁻⁶ B/op (0 B/op)
PersistentTypeScalarReplacementBenchmark.tuple2ConsThenNth               0.42 ns/op   ≈ 10⁻⁶ B/op (0 B/op)
PersistentTypeScalarReplacementBenchmark.vector2CreateThenNth            ~18 ns/op    328 B/op (must allocate)
```

`baselineScalarReplacementFields` graph (Java `BgvDump`):

- **After parsing**: `NewInstanceNode(SimplePair)` — explicit heap allocation.
- **FinalPartialEscapePhase**: object virtualized into registers and eliminated.
- **After low tier**: exactly 8 linear nodes:
  ```text
  8 nodes, linear
  AArch64AddressNode: 2
  ReadNode: 2
  AddNode: 1
  ParameterNode: 1
  ReturnNode: 1
  StartNode: 1
  ```

The object allocation and constructor call are eliminated into a single scalar CPU addition.

## KeywordMapBenchmark: shared update vs PEA

Do not treat every `assoc` microbench as a PEA claim. Opaque `@State` maps and keys
keep `PersistentShapeMap.assoc` demote/insert/promote arms live, so Graal commits the
virtual object. Local create plus `static final` keywords lets those arms fold away.

**Shared-map update cost** (keep; label as opacity / heap-update stress, not "PEA failed"):

- `arrayMap3DirectAssoc`, `shapeMap3DirectAssoc` — field map, `assoc` result escapes.
- `shapeMap3DirectAssocThenLookup` — field `shapeMap` / `kwA`; ~128 B/op negative control.
- `assocPipeline`, `assocPipeline12`, `guestShapeMap16InsertShared` — guest `assoc` on a shared polyglot `Value` map (heap-update stress; alloc unchanged by Insert16Transition).
- ShapeMap16 **insert** and **16→hash promote** use cached `Assoc16Transition` on `KeywordAssoc.doShapeMap16` (same rail as updates).
- `guestShapeMap8Promote` — shared 8-key ShapeMap input; guest `KeywordAssoc` 8→9 promotion (heap-update / shared-map cost, not a local PEA claim). Worktree JMH ~14.9 ns/op; no pre-change baseline was collected.

**Host PEA success (~0 B/op, primitive consume):**

- `shapeMap3EphemeralAssocThenLookup` — existing-key assoc; folds to `return 999`.
- `shapeMap3EphemeralInsertThenLookup` — new-key insert via unrolled field ctor (~0.32 ns/op).
- `shapeMap2EphemeralTransitionInsertThenLookup` — host `AssocTransition.apply` insert (2→3); PASS (~0.63 ns/op).
- `shapeMap8EphemeralTransitionPromoteThenLookup` — host `Promote16Transition.apply` 8→9 (no `assocPromote16` boundary); PASS (~0.34 ns/op).
- `shapeMap3EphemeralTransitionDissocThenLookup` — host `DissocTransition.apply` remove (3→2); PASS (~0.27 ns/op).
- `shapeMap3EphemeralWithoutThenLookup` — unrolled `without`; host PEA target.
- `shapeMap3EphemeralValAtOnly` — create + `valAt` only.
- `shapeMap3EphemeralKeywordInvoke` — `Keyword.invoke` on a local ShapeMap.
- `shapeMap3EphemeralNestedValAt` — nested local ShapeMaps, inner int consume.
- `shapeMap16EphemeralAssocThenLookup` — 9-key local ctor + existing-key assoc.
- `shapeMap16EphemeralInsertThenLookup` — ShapeMap16 new-key insert; host PEA target.
- `shapeMap5EphemeralValAtOnly` — 5-key cached create + valAt; host PEA target.
- `baselineTuple2ScalarReplacement` — `PersistentTuple2` scalar field access; scalar replacement PASS (~0.32 ns/op).

**Host still allocates (ephemeral recipe, not shared-field opacity):**

- `arrayMap3EphemeralAssocThenLookup` — **232 B/op**; low-tier `new_instance_or_null` +
  `new_array_or_null`. Array clone is why ShapeMap exists.
- `shapeMap3EphemeralSeqWalk` — **104 B/op**; `seq` of MapEntry objects. (Recorded as
  `shapeMap3EphemeralSeqSum` before 2026-09-09, when its `int` sum was replaced by a
  reference-valued walk to keep boxing off the measured path.)

**Guest ephemeral (PEA / scalar replacement verified via `check-scalar-replacement :guest true`):**

- `guestShapeMapEphemeralPipeline` — existing-key assoc; low-tier graph verified allocation-free (PASS, ~12.2 ns/op).
- `guestShapeMapEphemeralInsert` — local `{:a 1 :b 2}` then `(assoc m :c x)`, scalar replaced via unrolled `PersistentShapeMap.assoc` and direct dispatch (PASS, ~12.1 ns/op). A transition cache is not required for this in-CU insert.
- `guestShapeMapEphemeralPromote8` — local 8-key map then `(assoc m :p8 x)` consumed as a scalar; `KeywordAssoc` `Promote16Transition` (PASS, ~12.9 ns/op, 19 low-tier nodes).
- `guestShapeMapEphemeralDissoc` — local 3-key map then `(dissoc m :b)` consumed as a scalar; `KeywordDissoc` `RemoveTransition` (PASS, ~16.7 ns/op, 19 low-tier nodes).
- `guestEventSanitizePipeline` — chained dissoc sanitization pipeline `(-> event (dissoc :secret) (dissoc :temp))` (PASS, ~12.5 ns/op, 19 low-tier nodes).
- `guestTupleDestructure` — guest `(let [[a b] [x y]] ...)` vector destructuring; scalar replacement PASS via `IsSeq`, `VectorFirst`, `VectorRest` / `VectorNth2` devirtualization (PASS, ~13.3 ns/op).
- `GuestCompilationUnitTest` — `inCompiledCode` only, not allocation. Includes `testCachedShapeMapAssocAndPromotionInGuestCode` and `testCachedShapeMapDissocAndDemotionInGuestCode`.

To claim ShapeMap PEA, use the host ephemeral methods above plus a GC profile. Use
`:guest true` only for guest IR. Do not use field-based assoc benches for that claim.

**Lookup-only** (`*ValAt*` on `@State` maps, `*Lookup*`, `keywordDirectInvoke`, `nestedGetIn`,
`keywordIdEquals`, `shapeMap16ClojureLookup`): no update.

## Minimal strictly-necessary PEA architecture

Empirical testing proved that only the following components are strictly required for full guest & host PEA / scalar replacement:

> **Stale-doc correction (2026-09-09).** This list was written when a bytecode lowering layer
> existed. It no longer does. `KeywordAssoc`, `MapAssoc`, `KeywordDissoc`, `MapDissoc`,
> `VectorNth2`, `VectorNth3`, `CollectionCount`, `IsSeq`, `Identical`, `IsNil`, and `IsSome`
> **do not exist anywhere in `src/jvm`** — verified by grep. `0af1e162`, `a08ab505`, and
> `60816999` deleted them. The only surviving collection operations are `KeywordLookup`
> (`CloffleBytecodeRootNode.java:1930`) and `KeywordLookupDefault` (`:1978`), plus `CreateMap0`
> (`:1006`). Items 3 and 4 below are therefore aspirational, not descriptive. The
> `PersistentShapeMap` transition classes they reference *do* still exist and are still tested;
> nothing in the bytecode layer calls them. See [`TODO_lowering_layer.md`](TODO_lowering_layer.md).

1. **CallTarget Caching in `Invoke0..4` and `InvokeN`**:
   - Cache `fn.getCallTarget()` instead of closure object identity (`fn == cachedFn`). Allows closures from the same AST to share cached `DirectCallNode` call sites without thrashing.
2. **ClojureClosure Direct Polyglot Execution**:
   - `ClojureClosure` exports `InteropLibrary` directly (`doCall0..4`), bypassing `AFn.execute` and avoiding intermediate `ArraySeq` / `Object[]` allocations when called from Java (`Value.execute`).
   - Essential for host-to-guest benchmark latency (12.2 ns vs ~39.0 ns without it).
3. **Empty Map Shape Preservation**:
   - `CreateMap0` returns `PersistentShapeMap.EMPTY`, preventing maps initialized from `{}` from demoting to array cloning.
   - `KeywordAssoc.doNull` and `MapAssoc.doNull` return `PersistentShapeMap.create(k, v)`.
   - `KeywordAssoc.doShapeMapTransition` (`limit = 4`) caches `PersistentShapeMap.AssocTransition` for update, insert, and compiled 8→9 promotion. Host `PersistentShapeMap.assoc` still uses unrolled fields; `assocPromote16` remains `@TruffleBoundary` for that Java method.
4. **Core Destructuring & Hot Predicate Intrinsics**:
   - `IsSeq` (`seq?`) and `Identical` (`identical?`), plus lean intrinsics `IsNil` (`nil?`), `IsSome` (`some?`), and `CollectionCount` (`count`).
   - `seq?` folding allows Graal to fold Clojure's macroexpanded `(if (seq? m) ...)` in destructuring, enabling full scalar replacement.
5. **Reflection Boundary Optimization**:
   - Moving `invokeReflective` to `@TruffleBoundary` in `StaticMethod` prevents GraalVM inlining blowup (`PermanentBailoutException: Too deep inlining`).
6. **Unrolled Field Constructors & Methods**:
   - Unrolled `without` in `PersistentShapeMap` and `PersistentShapeMap16`, unrolled field `assoc`, and `Shape5`..`Shape8`.

## Simplification experiments

The following components were implemented, systematically tested for removal, and confirmed **safe to drop with zero impact on PEA and identical/improved performance**:

| Component Tested | Dropped? | Impact on PEA | Impact on Latency | Conclusion / Rationale |
| :--- | :--- | :--- | :--- | :--- |
| **`PersistentShapeSet` (1..8 keys)** + `CreateSet0..8` | **YES** (dropped ~800 lines) | None (0 allocations maintained) | None (benches identical) | Orthogonal set type. Clojure destructuring and map pipelines do not touch set operations. |
| **`VectorConj`, `VectorPop`, `VectorPeek`** ops | **YES** | None (0 allocations maintained) | None (13.3 ns vs 14.0 ns) | Destructuring lowers to `first`, `rest`, `nth`, and `seq?`. Vector conj/pop/peek operations are not used by destructuring macros. **Caveat (2026-09-09):** "no impact" was measured against destructuring only, which never calls `conj`. A dedicated conj probe ladder shows the Var path allocating 32–584 B/op. Re-lowering `conj` was tried and made every probe worse, so the drop still stands — but for a different reason than this row gives; see `TODO_lowering_layer.md` "Phase 2 step 5". |
| **`PersistentTuple1..8` `peek()` overrides** | **YES** | None (0 allocations maintained) | None | `APersistentVector.peek()` already computes `nth(count() - 1)`, which GraalVM constant-folds when tuple count is fixed. |
| **`Numbers` dispatch in `StaticMethod`** | **YES** | None | None | Current benches do not use reflective `Numbers.add`; `@TruffleBoundary` on `invokeReflective` already eliminates inlining bailouts cleanly. |
| **`uncapturedClosure` memoization** | **YES** | None | None | CallTarget caching in `Invoke0..4` already shares direct call nodes across pure closure instances. Caching closure objects on the root node is redundant. |
| **`ClojureClosure` `InteropLibrary` export** | **NO** (Retained) | Guest PEA passes without it, but... | **3x speedup on host `Value.execute`** | Kept because without it, host invocations degrade from ~12.2 ns to ~39.0 ns due to `AFn.execute` varargs allocation. |

### Quantitative before vs after

| Benchmark | HEAD (`61887345`) Before | Streamlined After | PEA Low-Tier Allocations | Status |
| :--- | :--- | :--- | :--- | :--- |
| `guestShapeMapEphemeralPipeline` | 12.468 ns/op | **12.218 ns/op** | 0 allocations (PASS) | Equivalent / slightly faster |
| `guestShapeMapEphemeralInsert` | 13.160 ns/op | **12.153 ns/op** | 0 allocations (PASS) | Equivalent / slightly faster |
| `guestTupleDestructure` | 14.001 ns/op | **13.323 ns/op** | 0 allocations (PASS) | Equivalent / slightly faster |
| `baselineTuple2ScalarReplacement` | 0.392 ns/op | **0.324 ns/op** | 0 allocations (PASS) | Equivalent |

## Real-world Clojure idiom opportunities (Ring, Hiccup, Map Reduction)

Building on real-world patterns identified in `src/external-projects/` (Ring, Hiccup, Cheshire), three high-impact allocation sites were targeted, optimized, and verified for full PEA and scalar replacement:

### Opportunity 1: Canonical Ring Response Map PEA

- **Pattern**: Handler emits response map literal `{:status 200 :headers {:content-type "text/plain"} :body body}`. Middleware updates headers via `(assoc resp :headers (assoc (:headers resp) :server "cloffle"))`. Adapter destructures `(let [{:keys [status headers body]} resp] ...)` and reads header fields.
- **Verification**: `KeywordMapBenchmark.guestRingResponsePipeline` (`:guest true`).
- **Result**: **PASS** (0 allocations, 19 low-tier nodes, **12.82 ns/op**).
- **Impact**: Ephemeral response map (3 keys) and nested headers map (2 keys) are virtualized into CPU registers without heap allocations or GC pressure.

### Opportunity 2: Hiccup Tag Vector & Attribute Map Scalar Replacement

- **Pattern**: Elements written as `[tag-name {:class "btn" :href "/home"} content-str]` are passed to normalization, tested for attribute maps via `(instance? clojure.lang.IPersistentMap ...)`, normalized to `[t attrs content]`, and destructured to extract attributes and children.
- **Verification**: `KeywordMapBenchmark.guestHiccupNormalizeTag` (`:guest true`).
- **Result**: **PASS** (0 allocations, 21 low-tier nodes, **13.28 ns/op**).
- **Impact**: Two intermediate `PersistentTuple3` vectors and one `PersistentShapeMap` are 100% scalar-replaced into registers.

### Opportunity 3: Zero-Allocation Reduction & MapEntry Virtualization

- **Implementation**:
  - `PersistentShapeMap` and `PersistentShapeMap16` now implement `clojure.lang.IReduce` and `clojure.lang.IReduceInit`.
  - Unrolled `kvreduce(IFn f, Object init)` switches on `count` to perform direct field access (`k0, v0`, etc.) without loop counters, `getKey(i)` switch overhead, or `MapEntry` allocations.
  - Implemented `ShapeMapSeq` and `ShapeMapIter` (and 16-key variants), completely eliminating the previous `toArray()` (`new Object[count * 2]`) heap array allocation upon every `seq` / iteration.
  - In `reduce(IFn f, Object start)`, `MapEntry.create(k, v)` is passed to reducing functions and virtualized by GraalVM PEA when inlined.
- **Verification**:
  - `KeywordMapBenchmark.shapeMap3EphemeralKvReduce`: **PASS** (0 allocations, 3 low-tier nodes, **0.35 ns/op**).
  - `KeywordMapBenchmark.shapeMap3EphemeralReduce`: **PASS** (0 allocations, 3 low-tier nodes, **0.26 ns/op**).
- **Impact**: Iterating and reducing small maps drops from 104 B/op to **0 B/op**, running at raw hardware CPU arithmetic speed.

## Keyword arguments, ephemeral request maps, and Tuple2 transformations (Opportunities 4, 5, 6)

### Opportunity 4: Keyword Arguments Destructuring Lowering

- **Problem**: Clojure's macro `destructure` previously lowered map destructuring over sequences into `clojure.lang.PersistentArrayMap/createAsIfByAssoc(to-array ~gmapseq)`. This forced a heap `Object[]` allocation via `to-array` and a `PersistentArrayMap` allocation which escapes PEA. Furthermore, `GetRestArgs` in the interpreter/bytecode runtime packaged rest arguments using an intermediate `java.util.ArrayList`.
- **Implementation**:
  - `RT.mapForDestructuring`: Directly accepts collections/arrays, creating `PersistentShapeMap` or `PersistentShapeMap16` for even-sized keyword arguments with up to 16 keys. Falls back to `PersistentArrayMap` only when duplicate keys or non-keyword keys exist.
  - `CloffleBytecodeRootNode.GetRestArgs`: Fast-paths rest args directly into `clojure.lang.ArraySeq.create(rest)` avoiding the `ArrayList` allocation.
  - `clojure.core/destructure`: Emits `(clojure.lang.RT/mapForDestructuring ~gmapseq)` when destructuring maps from sequences.
- **Verification**: `KeywordMapBenchmark.guestKwargsDestructure` (`:guest true`).
- **Result**: **PASS** (0 allocations, 19 low-tier nodes, **12.55 ns/op**).
- **Impact**: Keyword argument destructuring maps are 100% scalar-replaced into CPU registers with zero heap allocations.

### Opportunity 5: Ephemeral Intermediate Ring Request Maps

- **Pattern**: Middleware functions receive an incoming request map (`{:uri "/api/data" :request-method :post :headers {:content-type "application/json"} :body body}`), wrap it with intermediate keys such as `(assoc req :params {:query "search"})` and `(assoc req2 :session {:user "alice"})`, and pass it to downstream handlers which destructure the map and read fields.
- **Verification**: `KeywordMapBenchmark.guestMiddlewarePipeline` (`:guest true`).
- **Result**: **PASS** (0 allocations, 19 low-tier nodes, **13.59 ns/op**).
- **Impact**: The outer request map, headers map, query params map, and session map are all virtualized into registers simultaneously without committing to the heap.

### Opportunity 6: Intra-Function Pair Transformations & Tuple Destructuring

- **Pattern**: Coordinate transforms, swap patterns, and intermediate multi-value bundles represented as 2-element vectors: `(let [[a b] [x y] [c d] [b a]] c)`.
- **Verification**: `KeywordMapBenchmark.guestTuple2Transform` (`:guest true`).
- **Result**: **PASS** (0 allocations, 21 low-tier nodes, **13.87 ns/op**).
- **Architecture Note**: Intra-function pair allocations and destructuring virtualize cleanly into registers. However, cross-function (`defn`) multi-returns require Truffle `FrameState` materialization at call boundaries when the return value is bound to a caller local variable, so intra-function vector transformations remain the primary target for 0 B/op scalar replacement.

## Option map accumulators with `cond->` / `->` (Opportunity 9)

### Opportunity 9: Option Map Accumulator PEA

- **Pattern**: Functions accepting optional parameters that build an option map incrementally starting from `{}` and conditionally populating keys via `cond->` and `assoc`:
  ```clojure
  (defn guest-cond-option-pipeline [raw-timeout]
    (let [opts (-> {}
                   (cond-> true (assoc :id "btn"))
                   (cond-> true (assoc :role "primary"))
                   (cond-> true (assoc :href "/submit"))
                   (cond-> raw-timeout (assoc :timeout raw-timeout)))
          {:keys [id role href timeout]} opts]
      (if (and (identical? id "btn")
               (identical? role "primary")
               (identical? href "/submit"))
        timeout
        nil)))
  ```
- **Problem & Bottlenecks Resolved**:
  1. **Empty Map Lowering**: Literal `{}` was previously compiled to `PersistentArrayMap.EMPTY` via static field access. In `ExprToBytecode.convertEmptyExpr`, empty maps are now emitted via `b.emitCreateMap0()`, producing `PersistentShapeMap.EMPTY`. This guarantees that subsequent `assoc` operations stay within the `PersistentShapeMap` fast path.
  2. **Inlining Budget & Cold-Path Bloat**: In `PersistentShapeMap.assoc`, cold promotion branches (`assocPromote16`, called only when `count == 8`) and non-keyword fallbacks (`assocNonKeyword`) generated heavy bytecode. This caused GraalVM to hit method complexity and inlining budget thresholds after only 2 sequential `assoc` operations, causing the 3rd and 4th `assoc` to remain un-inlined and triggering `CommitAllocationNode` at deopt points. Annotating `assocPromote16` and `assocNonKeyword` with `@TruffleBoundary` reduced the inlined bytecode size of `assoc` by over 80%, allowing 4+ chained `assoc` calls to inline cleanly. Guest bytecode `(assoc …)` that hits `KeywordAssoc`'s `Promote16Transition` constructs `PersistentShapeMap16` in compiled code instead of crossing that boundary; host Java `.assoc` still uses the boundary.
  3. **Reflector Static Field Inlining Boundaries**: Added `@TruffleBoundary` to `CloffleBytecodeRootNode.StaticField.doGet` and `SetStaticField.doSet` reflective calls to eliminate `tooDeepInlining` warnings and premature `CommitAllocationNode` deoptimization commits.
- **Verification**: `KeywordMapBenchmark.guestCondOptionPipeline` (`:guest true`).
- **Result**: **PASS** (0 allocations, 29 PEA nodes -> 4 nodes linear, 19 low-tier nodes, **13.10 ns/op**).
- **Impact**: Accumulating options from `{}` with up to 4 chained `cond->` / `assoc` operations is 100% scalar-replaced into CPU registers with zero heap allocations (0 B/op).

## Event enrichment & 8→9 ShapeMap16 Transition Promotion PEA (Opportunity 10)

### Opportunity 10: Event Enrichment & 8→9 Promotion PEA

- **Pattern**: Functions receiving or constructing an 8-attribute domain record or event map (`{:id 101 :type :auth :user "alice" :tenant "org-1" :ip "127.0.0.1" :status :ok :timestamp 1700000000 :version 1}`), enriching it with a 9th key via `(assoc event :payload payload-str)`, and destructuring the fields (`(let [{:keys [id status user payload]} enriched] ...)`).
- **Problem & Bottlenecks Resolved**:
  1. **Truffle Boundary Bypass**: In host Java code, `PersistentShapeMap.assoc` at `count == 8` delegates to `assocPromote16`, which is annotated with `@TruffleBoundary` to prevent bytecode inlining bloat. Previously, any 8→9 key addition was forced across the native host boundary, preventing JIT compilation and triggering heap commits for both maps.
  2. **Assoc Transition Caching**: Bytecode `KeywordAssoc` now caches a `Promote16Transition` directly. Because the 8 incoming keys, the new keyword, and the sorted positions are compile-time constants on the cached transition, Graal JIT emits the direct `PersistentShapeMap16` constructor in compiled machine code without `@TruffleBoundary`.
  3. **Full PEA of 8-key and 9-key Maps**: GraalVM PEA completely eliminates both the 8-key `PersistentShapeMap` and the 9-key `PersistentShapeMap16`, virtualizing all 9 fields into CPU registers.
- **Verification**: `KeywordMapBenchmark.guestEventEnrichPipeline` (`:guest true`).
- **Result**: **PASS** (0 allocations, 30 PEA nodes -> 9 nodes linear, 19 low-tier nodes, **14.11 ns/op**).
- **Impact**: Enriching 8-key maps beyond the tier-1 boundary into 9-key `PersistentShapeMap16` instances is 100% scalar-replaced into registers with zero heap allocations (0 B/op).

## Sanitization pipelines & KeywordDissoc Transition Caching (Opportunity 11)

### Opportunity 11: Dissoc Transition Caching & 9→8 Demotion PEA

- **Pattern**: Functions receiving or creating domain maps and sanitizing fields via `(dissoc m :k)` or chained pipelines `(-> m (dissoc :secret) (dissoc :temp))`.
- **Problem & Bottlenecks Resolved**:
  1. **Dynamic Slot Finding and Re-indexing Overhead**: Generic `without` requires inspecting 128-bit bitmasks, calling `Long.bitCount`, recomputing `hasHighKeys`, and running multi-case switches to shift keys and values into a new map. In chained dissocs, this complex branching exceeds inlining heuristics and prevents escape analysis.
  2. **Dissoc Transition Caching**: Bytecode `KeywordDissoc` caches `PersistentShapeMap.DissocTransition` (sizes 0..8) and `PersistentShapeMap16.Dissoc16Transition` (size 9→8 demotion). Precomputed destination keys (`toK0..toK6`) and updated bitmasks are compilation-final constants on the transition instance. Graal JIT emits direct scalar assignments into the new map constructor or returns the target untouched on a no-op.
  3. **Full PEA of Sanitized Maps**: In compiled guest code, all intermediate and result maps are completely scalar replaced (0 B/op).
- **Verification**: `KeywordMapBenchmark.guestShapeMapEphemeralDissoc` and `KeywordMapBenchmark.guestEventSanitizePipeline` (`:guest true`).
- **Result**: **PASS** (0 allocations, 19 low-tier nodes, **12.56 ns/op**).
- **Impact**: Sanitization pipelines with chained `dissoc` operations execute with 0 heap allocation and full register scalar replacement.

## ComparePerformance `nested-get-in` IGV (2026-09-04)

Snippet from `SnippetBenchmark` / `ComparePerformance` (`target/test-consume.md`):

```clojure
(get-in {:user {:profile {:name "Alice"}}} [:user :profile :name])
```

Cloffle was **32.7x slower** than JVM Clojure with **3200 B/op** vs **64 B/op**. Dump (see
[HOWTO_SEAFOAM.md](HOWTO_SEAFOAM.md); guest filter `*CloffleBytecode*`):

```bash
clojure -T:build run-benchmarks \
  :args '["SnippetBenchmark.cloffle" "-p" "name=nested-get-in"
           "-wi" "2" "-i" "1" "-w" "500ms" "-r" "100ms" "-f" "1"
           "-jvmArgsAppend"
           "-Djdk.graal.Dump=:2 -Djdk.graal.PrintGraph=File -Djdk.graal.DumpPath=target/graal-dumps-nested-get-in -Djdk.graal.MethodFilter=*CloffleBytecode*"]'
```

**Guest root (not the JMH stub):** `target/graal-dumps-nested-get-in/TruffleHotSpotCompilation-6553[CloffleBytecodeRootNode[clojure.core_fn--3291]].bgv` (96 phases, no Exception). Separate hot compilations of `clojure.core_get-in`, `clojure.core_reduce1`, and `clojure.core_get` were also written — they are the stock `get-in` pipeline, not `KeywordLookup`.

This is **not** `KeywordMapBenchmark.nestedGetIn` (`get-in-nested` on a prebuilt `nested-m`).

### Looks-ok checklist

| Phase | Result |
| :--- | :--- |
| `Call Tree / After Inline` [9] | **3 remaining CallNodes**: `get-in`, `reduce1`, plus DirectCall. Search hits: `get-in` 1, `reduce1` 1, `get` 12. **Zero** `KeywordLookup` / `CreateMap`. |
| `FinalPartialEscapePhase` [34] | 69 nodes, **loops** (`LoopBegin` 2), `ValuePhiNode` 6, `CommitAllocationNode` freq **1.0**, `TruffleNew` 1. `AllocatedObjectNode` of `Object[]` from `InvokeVar3.doClojureClosure`. |
| `After low tier` [95] | 160 nodes, branches/calls/**loops**. `PrefetchAllocateNode` **3** at freq **0.99** (`InvokeVar3.doClojureClosure`). Cold `new_array_or_null` at freq 0.01. Leftover `InvokeNode` 2 + `InvokeWithExceptionNode` 1 + `HotSpotDirectCallTargetNode` 3. `ValuePhiNode` 8. |

### Five-step diagnosis

1. **Origin:** Hot TLAB prefetches and the PEA `Object[]` commit come from `CloffleBytecodeRootNode$InvokeVar3.doClojureClosure` (`callNode.call(new Object[]{capturedFrame, a0, a1, a2})` at bytecode index 3). Call-stack locations are get-in (cloffle bci 237) inlined into the snippet (bci 141).
2. **Inlining:** Truffle After Inline still lists `get-in` and `reduce1`. Graal later inlines some of that (loops appear), but DirectCalls remain at low tier, so PEA cannot scalar-replace across the full `reduce1`/`get` chain.
3. **relativeFrequency:** PrefetchAllocate **0.99** and CommitAllocation **1.0** — every op, not a deopt tail.
4. **PHI / loops:** `ValuePhiNode` + `LoopBegin` match stock `(reduce1 get m ks)`, not a constant-length KeywordLookup chain.
5. **Creation path:** Prior to `ConstantVectorExpr`, `Compiler.VectorExpr.parse` constant-folded `[:user :profile :name]` to `ConstantExpr` of `PersistentVector`, which type-erased the analyzed element expressions and prevented unrolling. With `Compiler.ConstantVectorExpr`, literal vectors retain their analyzed element expressions while remaining a constant literal for emission/evaluation. `ExprToBytecode` matches `VectorLikeExpr` (shared by `VectorExpr` and `ConstantVectorExpr`), allowing the unroll to fire.

### Verdict

Resolved by introducing `Compiler.ConstantVectorExpr` (implementing `VectorLikeExpr` alongside `VectorExpr`). Unrolled `KeywordLookup` nest fires directly for literal keyword vector paths, eliminating the stock `clojure.core/get-in` → `reduce1` → `get` loop and its associated `InvokeVar` allocations. Throughput reaches ~211M ops/s and alloc drops to ~24 B/op matching flat `consume-assoc`.

> **Stale-doc correction (2026-09-09).** The `get-in` unroll described above was removed with the
> rest of the `:inline` layer (`60816999`); `ConstantVectorExpr` survives but no longer feeds it.
> The "~24 B/op matching flat `consume-assoc`" figure is also obsolete: `consume-assoc` measures
> **128.0 B/op** as of `0755d652`.

## ComparePerformance `ring-response` Analysis & Constant Map Lowering (2026-09-05)

### Pattern

Snippet from `SnippetBenchmark` / `ComparePerformance`:

```clojure
(let [resp {:status 200 :headers {:content-type "text/plain"} :body "ok"}
      resp2 (assoc resp :headers (assoc (:headers resp) :server "cloffle"))
      resp3 (assoc resp2 :status 201)
      {:keys [status headers body]} resp3]
  (if (and (identical? status 201)
           (identical? (:server headers) "cloffle")
           (identical? (:content-type headers) "text/plain"))
    body
    nil))
```

### Analysis & Resolution

1. **Constant Map Representation & Lowering**:
   - **Problem**: When a map literal consisted entirely of compile-time constants (e.g. `{:status 200 :headers {:content-type "text/plain"} :body "ok"}`), `Compiler.MapExpr.parse` folded it into an opaque `ConstantExpr` wrapping `PersistentArrayMap`. In `ExprToBytecode`, this was emitted via `emitConstantValue`/`emitLoadIdentityConstant`, bypassing the `PersistentShapeMap` fast path (`CreateMap0..8`) and preventing scalar replacement during subsequent `assoc` and `KeywordLookup` operations.
   - **Fix**: Introduced `Compiler.ConstantMapExpr` implementing `MapLikeExpr` (shared with `MapExpr`). `Compiler.MapExpr.parse` now returns `ConstantMapExpr` when all keys and values are constant. Updated `ExprToBytecode` to recognize `ConstantMapExpr` and lower small keyword maps to `emitCreateMap0..8`, and updated `emitUnrolledMergeMapLiteral` to accept `MapLikeExpr`.

2. **Root Cause of the Performance Cliff (`(identical? status 201)`)**:
   - Investigation using isolated probes revealed that `Long` values outside the JVM `LongCache` (`[-128, 127]`) do not possess object identity when boxed separately.
   - Specifically, comparing `status` (retrieved from the map) with the literal `201` via `identical?` (`a == b`) fails reference identity in Clojure.
   - Probing with status values within `[-128, 127]` (e.g. `127`) or keyword values (`:ok`) confirmed that scalar replacement and `PersistentShapeMap` transitions work cleanly: Cloffle achieves **217M–227M ops/sec with only 24 B/op** (matching JVM Clojure throughput and latency).
   - In the `201` case, the failed condition and unexpected branch profiling in the benchmark harness triggered a Tier 1 deoptimization trap loop (`Reason: Deopt taken too many times. Deopt Node: 107|Deopt`), causing Truffle to fall back to interpreter execution and incur interpreter allocation (~528–1400 B/op).

3. **Idiomatic Equality (`=`) & Truffle `Equiv` Operation**:
   - `identical?` checks reference equality (`a == b`), which is non-idiomatic in Clojure and fundamentally broken for boxed `Long` numbers outside `[-128, 127]` as well as distinct String instances. Real Ring handlers, middleware, and tests use Clojure equality (`=`).
   - Clojure 2-arg `=` inlines to `(clojure.lang.Util/equiv a b)`. Previously, `Util.equiv` was unhandled in `ExprToBytecode` and dispatched through reflection (`invokeReflective`) with `@TruffleBoundary` overhead.
   - Introduced `CloffleBytecodeRootNode.Equiv` and updated `ExprToBytecode` to intercept `clojure.lang.Util/equiv` and 2-arg `=` calls, emitting `b.beginEquiv()` / `b.endEquiv()`.
   - Replaced all non-idiomatic `identical?` checks across `KeywordMapBenchmark` and `SnippetBenchmarkSupport` with `=`.
   - In full `ComparePerformance` benchmarking, `ring-response` reaches **209M ops/sec** (vs **32.4M ops/sec** on stock JVM Clojure — **6.43x speedup**) and allocates only **24 B/op** (vs **232 B/op** on stock Clojure).
