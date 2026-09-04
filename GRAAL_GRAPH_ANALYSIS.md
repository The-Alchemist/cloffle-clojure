# Graal Compiler Graph Analysis

This guide describes how to create and analyze Graal compiler graphs for Cloffle. It complements
the optimization background in [PARTIAL_ESCAPE_ANALYSIS.md](PARTIAL_ESCAPE_ANALYSIS.md).

Use graph inspection together with an allocation profiler. A low `gc.alloc.rate.norm` is useful
evidence, but it does not identify which object was removed. Conversely, the absence of an
allocation in one graph does not prove scalar replacement if the allocating callee was not inlined.

## 1. BGV readers

Cloffle inspects Graal `.bgv` dumps with the **Java** Seafoam API (`com.github.thealchemist.BgvDump`
from `seafoam-jruby` 0.20), not the original MRI Ruby `seafoam` gem. `clojure -T:build
check-scalar-replacement` and `analyze-graal-graph` open each dump in-process. Do not shell out to
`seafoam` for those checks, and do not add a Ruby or Graphviz runtime dependency for them.

[Shopify Seafoam](https://github.com/Shopify/seafoam) remains useful as an **optional** interactive
CLI. The `list` / `describe` / `search` / `props` examples later in this guide are that CLI. The
Java equivalents are `BgvDump.listGraphs()`, `describe(index)`, `search(term)` /
`search(index, term)`, and `nodeProps(index, nodeId)`. See [CLOFFLE_BGVDUMP_MIGRATION.md](CLOFFLE_BGVDUMP_MIGRATION.md).

To install the optional CLI, use a separately installed Ruby rather than macOS's system Ruby:

```bash
brew install ruby
export PATH="/opt/homebrew/opt/ruby/bin:$(ruby -e 'print Gem.bindir'):$PATH"
gem install seafoam
seafoam --version
```

The exact Homebrew prefix may differ on Intel macOS. Add the Ruby and gem executable directories to
the shell environment or project toolchain rather than modifying the system Ruby.

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

For a repeatable pass/fail check, use section 10 (`BgvDump`). The `seafoam …` commands below are
optional MRI CLI exploration; they are not what `build.clj` runs.

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
  - `vector2CreateThenNth`: negative control (`PersistentVector` tail/node arrays; ~328 B/op).

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

The checker uses Java `BgvDump` (`seafoam-jruby` 0.20 on the `:build` alias), not the Ruby gem.
It keeps one handle open while it runs list, describe, and property-text searches on the selected
dump. No MRI `seafoam` executable, Ruby install, or Graphviz is required for this check.

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

**Host PEA success (~0 B/op, primitive consume):**

- `shapeMap3EphemeralAssocThenLookup` — existing-key assoc; folds to `return 999`.
- `shapeMap3EphemeralInsertThenLookup` — new-key insert via unrolled field ctor (~0.32 ns/op).
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
- `guestShapeMapEphemeralInsert` — local `{:a 1 :b 2}` then `(assoc m :c x)`, scalar replaced via unrolled `PersistentShapeMap.assoc` and direct dispatch (PASS, ~12.1 ns/op).
- `guestTupleDestructure` — guest `(let [[a b] [x y]] ...)` vector destructuring; scalar replacement PASS via `IsSeq`, `VectorFirst`, `VectorRest` / `VectorNth2` devirtualization (PASS, ~13.3 ns/op).
- `GuestCompilationUnitTest` — `inCompiledCode` only, not allocation.

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

