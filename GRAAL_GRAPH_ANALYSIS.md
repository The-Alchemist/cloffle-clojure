# Graal Compiler Graph Analysis

This guide describes how to create and analyze Graal compiler graphs for Cloffle. It complements
the optimization background in [PARTIAL_ESCAPE_ANALYSIS.md](PARTIAL_ESCAPE_ANALYSIS.md).

Use graph inspection together with an allocation profiler. A low `gc.alloc.rate.norm` is useful
evidence, but it does not identify which object was removed. Conversely, the absence of an
allocation in one graph does not prove scalar replacement if the allocating callee was not inlined.

## 1. BGV readers

**Use the Java Seafoam API for everything.** `com.github.thealchemist.BgvDump` (from
`seafoam-jruby` 0.31 on the `:build` alias) is the supported path, and it is sufficient for a
complete investigation: listing phases, counting nodes, finding allocations, and tracing a node back
to the source position responsible. `clojure -T:build check-scalar-replacement` and
`analyze-graal-graph` open each dump in-process.

For the end-to-end debugging method, with working probe scripts, see
[HOWTO_SEAFOAM.md](HOWTO_SEAFOAM.md). For the API contract, see
[HOWTO_SEAFOAM.md](HOWTO_SEAFOAM.md).

Do not shell out to the MRI `seafoam` CLI, and do not add a Ruby or Graphviz runtime dependency.
Drop below the Java API only when it appears to be *buggy* — a `SeafoamException` or a result that
makes no sense. Seafoam is a fork we control, so the response to a reader bug is to fix it and add
tests on both sides, not to work around it from the CLI; see the last section of
[HOWTO_SEAFOAM.md](HOWTO_SEAFOAM.md).

The `seafoam …` command lines later in this guide are retained as historical illustration of the
same queries. The Java equivalents are `BgvDump.listGraphs()`, `describe(index)`, `search(term)` /
`search(index, term)`, `nodeProps(index, nodeId)`, and `isTruncated()`. Prefer them.

## 2. Select a benchmark with an observable, non-escaping result

Choose a benchmark that constructs the candidate object and consumes it inside guest code. Returning
the object through `Value.execute`, reflection, or polyglot interop forces materialization. Also avoid
assuming that a benchmark with multiple guest functions tests inter-procedural PEA: those functions
must actually be inlined into one compilation unit.

First record allocation behavior:

```bash
clojure -T:build run-benchmarks \
  :args '["VarBenchmark.crossFunctionShapeMapPEA" "-prof" "gc"]'
```

## 3. Dump compiler graphs to files

The following short JMH run is sufficient to trigger compilation while limiting dump size:

```bash
clojure -T:build run-benchmarks \
  :args '["VarBenchmark.crossFunctionShapeMapPEA"
           "-wi" "2" "-i" "1" "-w" "500ms" "-r" "100ms" "-f" "1"
           "-jvmArgsAppend"
           "-Djdk.graal.Dump=:2 -Djdk.graal.PrintGraph=File -Djdk.graal.DumpPath=target/graal-dumps"]'
```

Notes:

- This project uses the embedded libgraal runtime. GraalVM 25 uses the `-Djdk.graal.*` option names
  above to produce dumps; the older `-Dgraal.*` aliases are deprecated. Verify the effective
  options in JMH's `# VM options` line.
- `Dump=:2` is intentionally verbose and includes host JVM compilations. `Dump=:3` produces still
  larger dumps.
- `run-benchmarks` rebuilds the project, and the dump directory lives under ignored `target/`.
- A `TruffleHotSpotCompilation-*.bgv` file is a compiled guest root (`CloffleBytecodeRootNode`).
- A `HotSpotCompilation-*.bgv` file is an ordinary JVM host method compilation, including the JMH harness or host benchmark stubs.

When analyzing guest optimizations such as `PersistentShapeMap` PEA, always focus on
`TruffleHotSpotCompilation-*.bgv` to examine the specialized guest bytecode pipeline without host stub interference.

For example, dumping `KeywordMapBenchmark.guestShapeMapEphemeralPipeline`:

```bash
clojure -T:build run-benchmarks \
  :args '["KeywordMapBenchmark.guestShapeMapEphemeralPipeline"
           "-wi" "2" "-i" "1" "-w" "500ms" "-r" "100ms" "-f" "1"
           "-jvmArgsAppend"
           "-Djdk.graal.Dump=:2 -Djdk.graal.PrintGraph=File -Djdk.graal.DumpPath=target/graal-dumps"]'
```

List the guest graphs:

```bash
rg --files --hidden --no-ignore target/graal-dumps \
  | rg '/TruffleHotSpotCompilation.*\.bgv$'
```

## 4. Find the relevant graph and phase numbers

Use `BgvDump` (section 10) for this, and see [HOWTO_SEAFOAM.md](HOWTO_SEAFOAM.md) for ready-made
probe scripts. The `seafoam …` command lines below illustrate the same queries in MRI CLI form; they
are not what `build.clj` runs and are not the recommended path.

Search candidate guest graphs for a class, field, or operation specific to the workload:

```bash
seafoam "$graph" search PersistentShapeMap
seafoam "$graph" search InvokeVar2
```

Then list its phases:

```bash
seafoam "$graph" list \
  | rg 'Call Tree/(Before|After) Inline|FinalPartialEscapePhase|After low tier'
```

Phase indices vary by compilation and tier; never copy an index from another `.bgv` file. For
example, if `FinalPartialEscapePhase` is graph 32, compare graph 31 with graph 32:

```bash
seafoam "$graph:31" describe \
  | rg 'nodes|CommitAllocation|NewArrayNode|NewInstanceNode|Virtual|TruffleNew'
seafoam "$graph:32" describe \
  | rg 'nodes|CommitAllocation|NewArrayNode|NewInstanceNode|Virtual|TruffleNew'
```

Useful node meanings:

- `NewInstanceNode` / `NewArrayNode`: explicit object or array allocations before lowering.
- `VirtualInstanceNode` / `VirtualArrayNode`: allocations represented as virtual state.
- `CommitAllocationNode`: one or more virtual objects are being materialized.
- `TruffleNew`: Seafoam's presentation of a Truffle-level allocation; follow it through later
  phases instead of assuming it is eliminated.

## 5. Check the final lowered graph

Allocation nodes are eventually lowered into runtime calls, so checking only
`FinalPartialEscapePhase` can produce a false zero. Inspect the graph named `After low tier`:

```bash
seafoam "$graph:$low_tier_index" describe \
  | rg 'ForeignCallNode|CommitAllocation|NewArrayNode|NewInstanceNode'
seafoam "$graph:$low_tier_index" search ForeignCallNode
seafoam "$graph:$low_tier_index:$node_id" props
```

Allocation descriptors such as `new_array_or_null` and `new_instance_or_null` are surviving heap
allocations. The `nodeSourcePosition` chain identifies their origin—for example,
`InvokeVar2.doClojureClosure` indicates a surviving call-target argument array.

`check-scalar-replacement` (section 10) searches the low-tier graph for those descriptor names
via `BgvDump.search()`. `BgvDump.describe()` / CLI `seafoam describe` only count `ForeignCallNode`
and will not fail the check by themselves.

## 6. Verify that the producer and consumer were inlined

Compare the `Call Tree/Before Inline` and `Call Tree/After Inline` graphs:

```bash
seafoam "$graph:$before_inline_index" describe | rg 'nodes|CallNode'
seafoam "$graph:$after_inline_index" describe | rg 'nodes|CallNode'
```

If the relevant `CallNode`s remain after inlining, PEA cannot scalar-replace an object across those
guest function boundaries. An allocation may be absent from the consumer graph simply because it
exists in the separately compiled producer.

## 7. Reject failed or incomplete compilations

When diagnosing compilation, add:

```text
-Dpolyglot.engine.TraceCompilation=true
```

Check for `opt failed`, `PermanentBailoutException`, `NeverPartOfCompilationException`, or `.bgv`
graphs whose only entry is an `Exception`. Such graphs did not reach final PEA and cannot establish
scalar replacement.

For a successful candidate, the strongest evidence is:

1. producer and consumer are in one inlined compilation unit;
2. the candidate allocation exists before PEA and is absent after PEA;
3. no corresponding allocation descriptor exists after low-tier lowering; and
4. `-prof gc` independently shows the expected allocation reduction.

IGV remains useful for visually following virtual-object fields to their SSA producers. Repeatable
phase and allocation checks belong on `BgvDump` (section 10), not on the MRI `seafoam` CLI.

## 8. Testing and inspecting guest compilations directly in JUnit

Beyond JMH benchmarks, guest code compilation units can be verified and tested directly in unit tests
using `GuestCompilationUnitTest` (`src/test/java/net/javacrumbs/cloffle/GuestCompilationUnitTest.java`).

### Synchronous JIT compilation in tests

Configure Polyglot `Context` to trigger immediate synchronous compilation of guest bytecode roots:

```java
Context context = Context.newBuilder("cloffle")
    .allowAllAccess(true)
    .option("engine.CompileImmediately", "true")
    .option("engine.BackgroundCompilation", "false")
    .option("engine.CompileOnly", "CloffleBytecode")
    .build();
```

- `engine.CompileImmediately`: forces compilation threshold to 0.
- `engine.BackgroundCompilation`: forces synchronous compilation on the caller thread.
- `engine.CompileOnly`: restricts compilation to Cloffle bytecode roots (`CloffleBytecode`), preventing
  eager compilation of top-level macroexpansion and compiler forms.

### Asserting execution in compiled code

Guest code can query execution mode via `(com.oracle.truffle.api.CompilerDirectives/inCompiledCode)`:

```clojure
(defn assoc-and-lookup [v]
  (let [m {:a 1 :b 2 :c 3}
        updated (assoc m :a v)]
    [(:a updated)
     (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)]))
```

The first execution in the interpreter triggers synchronous compilation. The second execution runs
inside compiled machine code, where `inCompiledCode` returns `true`.

### Inspecting CallTargets programmatically

To verify the compiled state or bytecode root node directly:

```java
Var v = Var.find(Symbol.intern("my.ns", "my-fn"));
ClojureClosure closure = (ClojureClosure) v.deref();
RootCallTarget ct = (RootCallTarget) closure.getCallTarget();
CloffleBytecodeRootNode root = (CloffleBytecodeRootNode) ct.getRootNode();

if (ct instanceof com.oracle.truffle.runtime.OptimizedCallTarget oct) {
    boolean isCompiled = oct.isValid();
    long codeAddress = oct.getCodeAddress();
}
```

Run the focused test suite:

```bash
clojure -T:build run-tests :args '["--select-class=net.javacrumbs.cloffle.GuestCompilationUnitTest"]'
```

## 9. Baseline scalar replacement verification

To verify that GraalVM Partial Escape Analysis (PEA) and scalar replacement are functioning correctly
independent of Clojure map dispatch or branch merges, baselines are available:

- `ScalarReplacementBenchmark`: tests pure Java object scalar replacement (`SimpleBox` and `SimplePair`):
  - `baselineScalarReplacementLiteral`: creates `new SimpleBox(2 + 3)` and reads `box.k`.
  - `baselineScalarReplacementFields`: creates `new SimplePair(argA, argB)` and returns `pair.a + pair.b`.
- `PersistentTypeScalarReplacementBenchmark`: tests Clojure persistent collection scalar replacement:
  - `baselineTuple2ScalarReplacement` / `baselineTuple3ScalarReplacement` / `baselineTuple4ScalarReplacement`:
    `Tuple.create` + `nth` consumed as `int`.
  - `tuple2AssocNThenNth` / `tuple2ConsThenNth`: same-class rewrite and Tuple2→Tuple3 promotion.

### Verifying 0 B/op allocation in JMH

```bash
clojure -T:build run-benchmarks :args '["ScalarReplacementBenchmark.*" "-f" "1" "-wi" "2" "-i" "2" "-prof" "gc"]'
clojure -T:build run-benchmarks :args '["PersistentTypeScalarReplacementBenchmark.*" "-f" "1" "-wi" "2" "-i" "2" "-prof" "gc"]'
```

Expected result:
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

### Verifying compiler graph elimination in Seafoam

Dump the graph for `baselineScalarReplacementFields`:
```bash
clojure -T:build run-benchmarks :args '["ScalarReplacementBenchmark.baselineScalarReplacementFields" \
  "-f" "1" "-wi" "2" "-i" "1" "-w" "500ms" "-r" "100ms" "-jvmArgsAppend" \
  "-Djdk.graal.Dump=:2 -Djdk.graal.PrintGraph=File -Djdk.graal.DumpPath=target/graal-dumps-baseline -Djdk.graal.MethodFilter=*baselineScalarReplacement*"]'
```

Inspect with Seafoam:
- **Phase 3 (After parsing)**: contains `NewInstanceNode(SimplePair)` — explicit heap allocation.
- **Phase 12 (FinalPartialEscapePhase)**: object is virtualized into registers and eliminated.
- **Phase 65 (After low tier)**: exactly 8 linear nodes:
  ```text
  8 nodes, linear
  AArch64AddressNode: 2
  ReadNode: 2
  AddNode: 1
  ParameterNode: 1
  ReturnNode: 1
  StartNode: 1
  ```
The object allocation and constructor call are completely eliminated into a single scalar CPU addition.

## 10. Programmatic PEA / scalar-replacement check

`build.clj` can dump a named JMH benchmark and fail if the low-tier graph still
contains allocation nodes (`CommitAllocation`, `NewInstanceNode`, `NewArrayNode`,
`new_instance_or_null`, `new_array_or_null`, `TruffleNew`, `AllocatingBoxNode`).

The checker uses Java `BgvDump` (`seafoam-jruby` 0.31 on the `:build` alias), not the Ruby gem.
It keeps one handle open while it runs list, describe, and property-text searches on the selected
dump. No MRI `seafoam` executable, Ruby install, or Graphviz is required for this check.

It also rejects a dump that `isTruncated()`, because a phase missing from a partially written dump
is not evidence that the allocation is absent. See the traps in [HOWTO_SEAFOAM.md](HOWTO_SEAFOAM.md)
before lowering the JMH iteration defaults.

Dump and analyze a host compilation (for example `PersistentTuple2`):

```bash
clojure -T:build check-scalar-replacement \
  :benchmark '"PersistentTypeScalarReplacementBenchmark.baselineTuple2ScalarReplacement"'
```

Analyze a guest (`TruffleHotSpotCompilation`) graph instead of the host method.
`:guest true` dumps `*CloffleBytecode*` (not the JMH method name; that filter drops Truffle graphs):

```bash
clojure -T:build check-scalar-replacement \
  :benchmark '"KeywordMapBenchmark.guestShapeMapEphemeralPipeline"' \
  :guest true
```

### Running all known scalar replacement checks

To run the entire catalog of known scalar replacement benchmarks (or a subset by suite/filter):

```bash
# Run all known scalar replacement checks:
clojure -T:build check-scalar-replacements

# Run only host benchmarks (23 checks):
clojure -T:build check-scalar-replacements :suite :host

# Run only guest Truffle benchmarks (27 checks):
clojure -T:build check-scalar-replacements :suite :guest

# Filter benchmarks by name or regex:
clojure -T:build check-scalar-replacements :filter '"Tuple"'

# List all matching benchmarks without running:
clojure -T:build check-scalar-replacements :list true
```

Do not treat a MethodFilter dump as equivalent to a GC profile. Filtering to one host method
can PEA more aggressively than a full JMH fork. Always confirm with `gc.alloc.rate.norm`.

Analyze an already-dumped `.bgv` file:

```bash
clojure -T:build analyze-graal-graph \
  :bgv '"target/graal-dumps-pea/HotSpotCompilation-926[…].bgv"'
```

The check passes when compilation succeeded and the **After low tier** graph has no
allocation descriptors. Phase indices are discovered from `BgvDump.listGraphs()` and are not
hard-coded. Low-tier allocations are lowered to `ForeignCallNode` descriptors
(`new_instance_or_null`, `new_array_or_null`); the checker searches those names as
well as the high-tier node types. `BgvDump.describe()` alone is not enough (it only
counts `ForeignCallNode` without exposing the descriptor), so the checker also uses
graph-scoped `BgvDump.search()`.

## 11. KeywordMapBenchmark: shared update vs PEA

Do not treat every `assoc` microbench as a PEA claim. Opaque `@State` maps and keys
keep `PersistentShapeMap.assoc` demote/insert/promote arms live, so Graal commits the
virtual object. Local create plus `static final` keywords lets those arms fold away.

**Shared-map update cost** (keep; label as opacity / heap-update stress, not "PEA failed"):

- `arrayMap3DirectAssoc`, `shapeMap3DirectAssoc` — field map, `assoc` result escapes.
- `shapeMap3DirectAssocThenLookup` — field `shapeMap` / `kwA`; ~128 B/op negative control.
- `assocPipeline`, `assocPipeline12` — guest `assoc` on a shared polyglot `Value` map.
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
- `shapeMap3EphemeralSeqSum` — **104 B/op**; `seq` of MapEntry objects.

**Guest ephemeral (PEA / scalar replacement verified via `check-scalar-replacement :guest true`):**

- `guestShapeMapEphemeralPipeline` — existing-key assoc; low-tier graph verified allocation-free (PASS, ~12.2 ns/op).
- `guestShapeMapEphemeralInsert` — local `{:a 1 :b 2}` then `(assoc m :c x)`, scalar replaced via unrolled `PersistentShapeMap.assoc` and direct dispatch (PASS, ~12.1 ns/op). A transition cache is not required for this in-CU insert.
- `guestShapeMapEphemeralPromote8` — local 8-key map then `(assoc m :p8 x)` consumed as a scalar; `KeywordAssoc` `Promote16Transition` (PASS, ~12.9 ns/op, 19 low-tier nodes).
- `guestShapeMapEphemeralDissoc` — local 3-key map then `(dissoc m :b)` consumed as a scalar; `KeywordDissoc` `RemoveTransition` (PASS, ~16.7 ns/op, 19 low-tier nodes).
- `guestEventSanitizePipeline` — chained dissoc sanitization pipeline `(-> event (dissoc :secret) (dissoc :temp))` (PASS, ~12.5 ns/op, 19 low-tier nodes).
- `guestTupleDestructure` — guest `(let [[a b] [x y]] ...)` vector destructuring; scalar replacement PASS via `IsSeq`, `VectorFirst`, `VectorRest` / `VectorNth2` devirtualization (PASS, ~13.3 ns/op).
- `GuestCompilationUnitTest` — `inCompiledCode` only, not allocation. Includes `testCachedShapeMapAssocAndPromotionInGuestCode` and `testCachedShapeMapDissocAndDemotionInGuestCode`.

## 12. Minimal Strictly-Necessary PEA Architecture

Empirical testing proved that only the following components are strictly required for full guest & host PEA / scalar replacement:

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

## 13. Simplification Experiments & Empirical Conclusions

The following components were implemented, systematically tested for removal, and confirmed **safe to drop with zero impact on PEA and identical/improved performance**:

| Component Tested | Dropped? | Impact on PEA | Impact on Latency | Conclusion / Rationale |
| :--- | :--- | :--- | :--- | :--- |
| **`PersistentShapeSet` (1..8 keys)** + `CreateSet0..8` | **YES** (dropped ~800 lines) | None (0 allocations maintained) | None (benches identical) | Orthogonal set type. Clojure destructuring and map pipelines do not touch set operations. |
| **`VectorConj`, `VectorPop`, `VectorPeek`** ops | **YES** | None (0 allocations maintained) | None (13.3 ns vs 14.0 ns) | Destructuring lowers to `first`, `rest`, `nth`, and `seq?`. Vector conj/pop/peek operations are not used by destructuring macros. |
| **`PersistentTuple1..8` `peek()` overrides** | **YES** | None (0 allocations maintained) | None | `APersistentVector.peek()` already computes `nth(count() - 1)`, which GraalVM constant-folds when tuple count is fixed. |
| **`Numbers` dispatch in `StaticMethod`** | **YES** | None | None | Current benches do not use reflective `Numbers.add`; `@TruffleBoundary` on `invokeReflective` already eliminates inlining bailouts cleanly. |
| **`uncapturedClosure` memoization** | **YES** | None | None | CallTarget caching in `Invoke0..4` already shares direct call nodes across pure closure instances. Caching closure objects on the root node is redundant. |
| **`ClojureClosure` `InteropLibrary` export** | **NO** (Retained) | Guest PEA passes without it, but... | **3x speedup on host `Value.execute`** | Kept because without it, host invocations degrade from ~12.2 ns to ~39.0 ns due to `AFn.execute` varargs allocation. |

### Quantitative Before vs. After Benchmark Verification

| Benchmark | HEAD (`61887345`) Before | Streamlined After | PEA Low-Tier Allocations | Status |
| :--- | :--- | :--- | :--- | :--- |
| `guestShapeMapEphemeralPipeline` | 12.468 ns/op | **12.218 ns/op** | 0 allocations (PASS) | Equivalent / slightly faster |
| `guestShapeMapEphemeralInsert` | 13.160 ns/op | **12.153 ns/op** | 0 allocations (PASS) | Equivalent / slightly faster |
| `guestTupleDestructure` | 14.001 ns/op | **13.323 ns/op** | 0 allocations (PASS) | Equivalent / slightly faster |
| `baselineTuple2ScalarReplacement` | 0.392 ns/op | **0.324 ns/op** | 0 allocations (PASS) | Equivalent |

**Lookup-only** (`*ValAt*` on `@State` maps, `*Lookup*`, `keywordDirectInvoke`, `nestedGetIn`,
`keywordIdEquals`, `shapeMap16ClojureLookup`): no update.

To claim ShapeMap PEA, use the host ephemeral methods above plus a GC profile. Use
`:guest true` only for guest IR. Do not use field-based assoc benches for that claim.

## 14. Real-World Clojure Idiom Opportunities (Ring, Hiccup, Map Reduction)

Building on real-world patterns identified in `src/external-projects/` (Ring, Hiccup, Cheshire), three high-impact allocation sites were targeted, optimized, and verified for full PEA and scalar replacement:

### 1. Opportunity 1: Canonical Ring Response Map PEA
- **Pattern**: Handler emits response map literal `{:status 200 :headers {:content-type "text/plain"} :body body}`. Middleware updates headers via `(assoc resp :headers (assoc (:headers resp) :server "cloffle"))`. Adapter destructures `(let [{:keys [status headers body]} resp] ...)` and reads header fields.
- **Verification**: `KeywordMapBenchmark.guestRingResponsePipeline` (`:guest true`).
- **Result**: **PASS** (0 allocations, 19 low-tier nodes, **12.82 ns/op**).
- **Impact**: Ephemeral response map (3 keys) and nested headers map (2 keys) are virtualized into CPU registers without heap allocations or GC pressure.

### 2. Opportunity 2: Hiccup Tag Vector & Attribute Map Scalar Replacement
- **Pattern**: Elements written as `[tag-name {:class "btn" :href "/home"} content-str]` are passed to normalization, tested for attribute maps via `(instance? clojure.lang.IPersistentMap ...)`, normalized to `[t attrs content]`, and destructured to extract attributes and children.
- **Verification**: `KeywordMapBenchmark.guestHiccupNormalizeTag` (`:guest true`).
- **Result**: **PASS** (0 allocations, 21 low-tier nodes, **13.28 ns/op**).
- **Impact**: Two intermediate `PersistentTuple3` vectors and one `PersistentShapeMap` are 100% scalar-replaced into registers.

### 3. Opportunity 3: Zero-Allocation Reduction & MapEntry Virtualization
- **Implementation**:
  - `PersistentShapeMap` and `PersistentShapeMap16` now implement `clojure.lang.IReduce` and `clojure.lang.IReduceInit`.
  - Unrolled `kvreduce(IFn f, Object init)` switches on `count` to perform direct field access (`k0, v0`, etc.) without loop counters, `getKey(i)` switch overhead, or `MapEntry` allocations.
  - Implemented `ShapeMapSeq` and `ShapeMapIter` (and 16-key variants), completely eliminating the previous `toArray()` (`new Object[count * 2]`) heap array allocation upon every `seq` / iteration.
  - In `reduce(IFn f, Object start)`, `MapEntry.create(k, v)` is passed to reducing functions and virtualized by GraalVM PEA when inlined.
- **Verification**:
  - `KeywordMapBenchmark.shapeMap3EphemeralKvReduce`: **PASS** (0 allocations, 3 low-tier nodes, **0.35 ns/op**).
  - `KeywordMapBenchmark.shapeMap3EphemeralReduce`: **PASS** (0 allocations, 3 low-tier nodes, **0.26 ns/op**).
- **Impact**: Iterating and reducing small maps drops from 104 B/op to **0 B/op**, running at raw hardware CPU arithmetic speed.

## 15. Keyword Arguments, Ephemeral Request Maps, and Tuple2 Transformations (Opportunities 4, 5, 6)

### 1. Opportunity 4: Keyword Arguments Destructuring Lowering
- **Problem**: Clojure's macro `destructure` previously lowered map destructuring over sequences into `clojure.lang.PersistentArrayMap/createAsIfByAssoc(to-array ~gmapseq)`. This forced a heap `Object[]` allocation via `to-array` and a `PersistentArrayMap` allocation which escapes PEA. Furthermore, `GetRestArgs` in the interpreter/bytecode runtime packaged rest arguments using an intermediate `java.util.ArrayList`.
- **Implementation**:
  - `RT.mapForDestructuring`: Directly accepts collections/arrays, creating `PersistentShapeMap` or `PersistentShapeMap16` for even-sized keyword arguments with up to 16 keys. Falls back to `PersistentArrayMap` only when duplicate keys or non-keyword keys exist.
  - `CloffleBytecodeRootNode.GetRestArgs`: Fast-paths rest args directly into `clojure.lang.ArraySeq.create(rest)` avoiding the `ArrayList` allocation.
  - `clojure.core/destructure`: Emits `(clojure.lang.RT/mapForDestructuring ~gmapseq)` when destructuring maps from sequences.
- **Verification**: `KeywordMapBenchmark.guestKwargsDestructure` (`:guest true`).
- **Result**: **PASS** (0 allocations, 19 low-tier nodes, **12.55 ns/op**).
- **Impact**: Keyword argument destructuring maps are 100% scalar-replaced into CPU registers with zero heap allocations.

### 2. Opportunity 5: Ephemeral Intermediate Ring Request Maps
- **Pattern**: Middleware functions receive an incoming request map (`{:uri "/api/data" :request-method :post :headers {:content-type "application/json"} :body body}`), wrap it with intermediate keys such as `(assoc req :params {:query "search"})` and `(assoc req2 :session {:user "alice"})`, and pass it to downstream handlers which destructure the map and read fields.
- **Verification**: `KeywordMapBenchmark.guestMiddlewarePipeline` (`:guest true`).
- **Result**: **PASS** (0 allocations, 19 low-tier nodes, **13.59 ns/op**).
- **Impact**: The outer request map, headers map, query params map, and session map are all virtualized into registers simultaneously without committing to the heap.

### 3. Opportunity 6: Intra-Function Pair Transformations & Tuple Destructuring
- **Pattern**: Coordinate transforms, swap patterns, and intermediate multi-value bundles represented as 2-element vectors: `(let [[a b] [x y] [c d] [b a]] c)`.
- **Verification**: `KeywordMapBenchmark.guestTuple2Transform` (`:guest true`).
- **Result**: **PASS** (0 allocations, 21 low-tier nodes, **13.87 ns/op**).
- **Architecture Note**: Intra-function pair allocations and destructuring virtualize cleanly into registers. However, cross-function (`defn`) multi-returns require Truffle `FrameState` materialization at call boundaries when the return value is bound to a caller local variable, so intra-function vector transformations remain the primary target for 0 B/op scalar replacement.

## 16. Option Map Accumulators with `cond->` / `->` (Opportunity 9)

### 1. Opportunity 9: Option Map Accumulator PEA
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

## 17. Event Enrichment & 8→9 ShapeMap16 Transition Promotion PEA (Opportunity 10)

### 1. Opportunity 10: Event Enrichment & 8→9 Promotion PEA
- **Pattern**: Functions receiving or constructing an 8-attribute domain record or event map (`{:id 101 :type :auth :user "alice" :tenant "org-1" :ip "127.0.0.1" :status :ok :timestamp 1700000000 :version 1}`), enriching it with a 9th key via `(assoc event :payload payload-str)`, and destructuring the fields (`(let [{:keys [id status user payload]} enriched] ...)`).
- **Problem & Bottlenecks Resolved**:
  1. **Truffle Boundary Bypass**: In host Java code, `PersistentShapeMap.assoc` at `count == 8` delegates to `assocPromote16`, which is annotated with `@TruffleBoundary` to prevent bytecode inlining bloat. Previously, any 8→9 key addition was forced across the native host boundary, preventing JIT compilation and triggering heap commits for both maps.
  2. **Assoc Transition Caching**: Bytecode `KeywordAssoc` now caches a `Promote16Transition` directly. Because the 8 incoming keys, the new keyword, and the sorted positions are compile-time constants on the cached transition, Graal JIT emits the direct `PersistentShapeMap16` constructor in compiled machine code without `@TruffleBoundary`.
  3. **Full PEA of 8-key and 9-key Maps**: GraalVM PEA completely eliminates both the 8-key `PersistentShapeMap` and the 9-key `PersistentShapeMap16`, virtualizing all 9 fields into CPU registers.
- **Verification**: `KeywordMapBenchmark.guestEventEnrichPipeline` (`:guest true`).
- **Result**: **PASS** (0 allocations, 30 PEA nodes -> 9 nodes linear, 19 low-tier nodes, **14.11 ns/op**).
- **Impact**: Enriching 8-key maps beyond the tier-1 boundary into 9-key `PersistentShapeMap16` instances is 100% scalar-replaced into registers with zero heap allocations (0 B/op).

## 18. Sanitization Pipelines & KeywordDissoc Transition Caching (Opportunity 11)

### 1. Opportunity 11: Dissoc Transition Caching & 9→8 Demotion PEA
- **Pattern**: Functions receiving or creating domain maps and sanitizing fields via `(dissoc m :k)` or chained pipelines `(-> m (dissoc :secret) (dissoc :temp))`.
- **Problem & Bottlenecks Resolved**:
  1. **Dynamic Slot Finding and Re-indexing Overhead**: Generic `without` requires inspecting 128-bit bitmasks, calling `Long.bitCount`, recomputing `hasHighKeys`, and running multi-case switches to shift keys and values into a new map. In chained dissocs, this complex branching exceeds inlining heuristics and prevents escape analysis.
  2. **Dissoc Transition Caching**: Bytecode `KeywordDissoc` caches `PersistentShapeMap.DissocTransition` (sizes 0..8) and `PersistentShapeMap16.Dissoc16Transition` (size 9→8 demotion). Precomputed destination keys (`toK0..toK6`) and updated bitmasks are compilation-final constants on the transition instance. Graal JIT emits direct scalar assignments into the new map constructor or returns the target untouched on a no-op.
  3. **Full PEA of Sanitized Maps**: In compiled guest code, all intermediate and result maps are completely scalar replaced (0 B/op).
- **Verification**: `KeywordMapBenchmark.guestShapeMapEphemeralDissoc` and `KeywordMapBenchmark.guestEventSanitizePipeline` (`:guest true`).
- **Result**: **PASS** (0 allocations, 19 low-tier nodes, **12.56 ns/op**).
- **Impact**: Sanitization pipelines with chained `dissoc` operations execute with 0 heap allocation and full register scalar replacement.

## 19. ComparePerformance `nested-get-in` IGV (2026-09-04)

Snippet from `SnippetBenchmark` / `ComparePerformance` (`target/test-consume.md`):

```clojure
(get-in {:user {:profile {:name "Alice"}}} [:user :profile :name])
```

Cloffle was **32.7x slower** than JVM Clojure with **3200 B/op** vs **64 B/op**. Dump:

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

## 20. ComparePerformance `ring-response` Analysis & Constant Map Lowering (2026-09-05)

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




