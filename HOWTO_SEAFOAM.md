# How to use Seafoam in Cloffle

How to produce Graal `.bgv` dumps and answer "what is this code actually allocating, and where does
it come from".

Recorded PEA / scalar-replacement findings live in
[GRAAL_GRAPH_ANALYSIS.md](GRAAL_GRAPH_ANALYSIS.md). Optimization background is in
[PARTIAL_ESCAPE_ANALYSIS.md](PARTIAL_ESCAPE_ANALYSIS.md).

**Use the Java Seafoam API.** This repo does not use the MRI `seafoam` CLI, Ruby gems at runtime, or
Graphviz. The supported reader is `com.github.thealchemist.BgvDump` from
`com.github.the-alchemist/seafoam-jruby` on the `:build` alias. `build.clj` already imports it and
opens dumps in-process; that is the path to follow.

```clojure
;; build.clj
(:import [com.github.thealchemist BgvDump])
```

```clojure
;; deps.edn, :build alias
com.github.the-alchemist/seafoam-jruby {:mvn/version "0.31"}
```

The tasks that wrap it:

| Task | What it does |
| --- | --- |
| `clojure -T:build check-scalar-replacement` | **the gate**: measure `gc.alloc.rate.norm`, fail over budget, then dump and diagnose |
| `clojure -T:build check-scalar-replacements` | the catalog of known host/guest checks |
| `clojure -T:build record-alloc-budgets` | measure the catalog and print `:alloc-budget` entries |
| `clojure -T:build analyze-graal-graph` | pass/fail on an existing `.bgv` |
| `clojure -T:build explain-allocations` | report what allocated and which source frames produced it |

## Graphs diagnose; they do not gate

**The gate is `gc.alloc.rate.norm`, not the graph.** Read this before using a clean graph as
evidence that something does not allocate.

`relativeFrequency` is a static estimate scoped to a single compilation unit, and one Clojure
pipeline routinely compiles into several units plus interpreted frames. Measured on
`KeywordMapBenchmark.guestPipelineReduce`:

| Compilation unit | Low-tier nodes | Alloc stubs | Max `relativeFrequency` |
| --- | --- | --- | --- |
| `guest-pipeline-reduce` | 1280 | 13 | 0.0100 |
| `clojure.core_filter` | 1693 | 18 | 0.0050 |
| `clojure.core_filter` inner `fn` | 1048 | 3 | 0.0033 |

Every stub in all three units looks cold. The benchmark allocates **6168 B/op**. The same blindness
produced the opposite error earlier, when a 9-node delegating wrapper was selected and passed.

So the graph answers *what allocated and where did it come from*, which is what you need to fix a
failure. Only the GC profile answers *does it matter*, which is what you need to gate one.

Probe scripts use the same classpath: `clojure -M:build /tmp/probe.clj path/to.bgv`. Drop to the
Ruby Seafoam checkout only when the Java API looks *broken*; see
[If the Java API looks wrong](#if-the-java-api-looks-wrong). If you find yourself in Ruby for any
other reason, the fix belongs in the Java wrapper.

IGV remains the right tool for *interactive visual* inspection; see [Using IGV](#using-igv).

Read [Traps](#traps) before trusting any result. Every one has produced a confident, wrong answer in
this repo.

- [Graphs diagnose; they do not gate](#graphs-diagnose-they-do-not-gate)
- [Java Seafoam in this repo](#java-seafoam-in-this-repo)
- [Producing a dump](#producing-a-dump)
- [The BgvDump API](#the-bgvdump-api)
- [The workflow](#the-workflow)
- [Reading the graph](#reading-the-graph)
- [Why an allocation survived](#why-an-allocation-survived)
- [Asserting compilation in JUnit](#asserting-compilation-in-junit)
- [Case studies](#case-studies)
- [Using IGV](#using-igv)
- [Traps](#traps)
- [If the Java API looks wrong](#if-the-java-api-looks-wrong)
- [Cheat sheet](#cheat-sheet)

## Java Seafoam in this repo

`BgvDump` parses a dump once and answers queries from memory: list phases, filter nodes by class,
walk edges, search property text, and read `nodeSourcePosition`. No GUI, no MRI `seafoam`
executable, no Graphviz.

Use graph inspection together with an allocation profiler. A low `gc.alloc.rate.norm` is useful
evidence, but it does not identify which object was removed. Conversely, the absence of an
allocation in one graph does not prove scalar replacement if the allocating callee was not inlined.

Choose a benchmark that constructs the candidate object and consumes it inside guest code. Returning
the object through `Value.execute`, reflection, or polyglot interop forces materialization. Do not
assume that a benchmark with multiple guest functions tests inter-procedural PEA: those functions
must actually be inlined into one compilation unit.

First record allocation behavior:

```bash
clojure -T:build run-benchmarks \
  :args '["VarBenchmark.crossFunctionShapeMapPEA" "-prof" "gc"]'
```

When diagnosing whether compilation even happened, add `-Dpolyglot.engine.TraceCompilation=true`
and look for `opt failed`, `PermanentBailoutException`, `NeverPartOfCompilationException`, or
`.bgv` graphs whose only entry is an `Exception`. Those dumps did not reach final PEA.

For a successful candidate, the strongest evidence is:

1. producer and consumer are in one inlined compilation unit;
2. the candidate allocation exists before PEA and is absent after PEA;
3. no corresponding allocation descriptor exists after low-tier lowering; and
4. `-prof gc` independently shows the expected allocation reduction.

Do not treat a MethodFilter dump as equivalent to a GC profile. Filtering to one host method can
PEA more aggressively than a full JMH fork.

## Concepts

- **BGV (`.bgv`)** — GraalVM's binary graph dump: the full compiler IR at every phase, from parsing
  through PEA to low-tier lowering.
- **Java Seafoam (`BgvDump`)** — `com.github.thealchemist.BgvDump` from `seafoam-jruby` on `:build`.
  This is what `build.clj` uses. Do not shell out to MRI `seafoam`.
- **IGV** — Oracle's NetBeans-based GUI for browsing graphs visually.

## Producing a dump

### The MethodFilter trap

Guest Clojure functions compile as `CloffleBytecodeRootNode[ns_function-name]`. A filter naming only
the JMH method dumps the *host* harness and **silently drops every guest
`TruffleHotSpotCompilation` graph**. Always include `*CloffleBytecode*`:

```
-Djdk.graal.MethodFilter=*CloffleBytecode*,*my-guest-fn*
```

### Via build.clj (recommended)

These tasks use Java `BgvDump` in-process. They do not invoke a `seafoam` binary.

```bash
clojure -T:build check-scalar-replacement \
  :benchmark '"KeywordMapBenchmark.guestPipelineReduce"' \
  :guest true :alloc-budget 0 ":throw?" false
```

That runs JMH under `-prof gc` and fails when `gc.alloc.rate.norm` exceeds `:alloc-budget`. Only
then does it dump graphs and itemize what allocated. A passing benchmark never dumps, which is why
the gate takes ~15s where the old graph check took ~60s and wrote hundreds of megabytes.

Diagnostic dumps land in `target/graal-dumps-pea/`; pass `:dump-path` to put them elsewhere, which
is worth doing since `run-tests` cleans `target`. Quote `":throw?"` in zsh, which otherwise globs
the `?`. The task prints which compilation it chose and warns when that choice is doubtful — read
those warnings, they exist because ignoring them wasted a lot of time.

`:guest true` dumps `*CloffleBytecode*` (not the JMH method name; that filter drops Truffle graphs).

**Budgets.** `:alloc-budget` is B/op. `0` asserts full scalar replacement; a non-zero budget is a
ratchet pinning today's behavior so it cannot regress. A benchmark with no budget is measured,
reported, and passed with a warning, so the catalog can be filled in incrementally:

```bash
clojure -T:build record-alloc-budgets                 # measure the whole catalog
clojure -T:build record-alloc-budgets :missing true   # only the unbudgeted ones
clojure -T:build record-alloc-budgets :filter '"Tuple"'
clojure -T:build record-alloc-budgets :snippet '"keyword-invoke"'  # ad-hoc or catalog snippet
```

It prints suggested entries and flags regressions; it never edits `build.clj`, because
snapshotting a regression into the catalog would silently bless it. Paste in the budgets you accept.

```bash
# Catalog of known checks
clojure -T:build check-scalar-replacements
clojure -T:build check-scalar-replacements :suite :host
clojure -T:build check-scalar-replacements :suite :guest
clojure -T:build check-scalar-replacements :filter '"Tuple"'
clojure -T:build check-scalar-replacements :list true   # names plus their budgets

# Snippet shortcut (expands to SnippetBenchmark.cloffle with -p name=... and :guest true)
clojure -T:build check-scalar-replacement :snippet '"keyword-invoke"' :alloc-budget 0

# Host compilation (for example PersistentTuple2)
clojure -T:build check-scalar-replacement \
  :benchmark '"PersistentTypeScalarReplacementBenchmark.baselineTuple2ScalarReplacement"' \
  :alloc-budget 0
```

To analyze a dump that already exists:

```bash
clojure -T:build analyze-graal-graph :bgv '"target/graal-dumps-pea/TruffleHotSpotCompilation-6744[...].bgv"'
```

`analyze-graal-graph` passes when compilation succeeded and the **After low tier** graph has no
allocation descriptors. That is a statement about one compilation unit, not about the benchmark;
see [Graphs diagnose; they do not gate](#graphs-diagnose-they-do-not-gate). Phase indices come from
`BgvDump.listGraphs()` and are not hard-coded. Low-tier allocations are `ForeignCallNode`
descriptors (`new_instance_or_null`, `new_array_or_null`); `BgvDump.describe()` alone is not enough.

### Manually via JMH

Prefer the `build.clj` tasks above. A raw dump is useful when you want the files without a pass/fail
check. This project uses the embedded libgraal runtime; GraalVM 25 uses `-Djdk.graal.*`. Verify the
effective options in JMH's `# VM options` line. `Dump=:2` is verbose and includes host JVM
compilations; `:3` is larger still. `run-benchmarks` rebuilds the project, and dumps live under
ignored `target/`.

```bash
clojure -T:build run-benchmarks :args '["KeywordMapBenchmark.guestPipelineReduce"
  "-wi" "3" "-i" "2" "-w" "2s" "-r" "3s" "-f" "1"
  "-jvmArgsAppend"
  "-Djdk.graal.Dump=:2 -Djdk.graal.PrintGraph=File -Djdk.graal.DumpPath=target/graal-dumps -Djdk.graal.MethodFilter=*CloffleBytecode*,*guest-pipeline-reduce*"]'
```

`-Djdk.graal.Dump=:2` covers high tier, PEA, and low tier. `:3` adds scheduling and LIR at the cost
of very large files. Do not shorten the iteration settings; see the truncation trap below.

### Which file is which

- `TruffleHotSpotCompilation-<id>[CloffleBytecodeRootNode[...]].bgv` — **the guest Clojure
  compilation**, where your Clojure logic lives.
- `HotSpotCompilation-<id>[...].bgv` — host JVM Java code such as the JMH harness.

## The BgvDump API

Scripts run against the `:build` alias (same `seafoam-jruby` dependency `build.clj` uses):

```bash
clojure -M:build /tmp/probe.clj "target/graal-dumps-pea/<dump>.bgv"
```

| Call | Returns | Notes |
| --- | --- | --- |
| `open(Path)` / `open(byte[])` | `BgvDump` | bytes may be raw BGV or gzip |
| `isTruncated()` | `boolean` | dump ended mid-write; check before any negative conclusion |
| `listGraphs()` | `List<GraphInfo>` | `index`, `name` = slash-joined phase path |
| `describe(i)` | `DescribeResult` | `summary`, `nodeCounts` by **simple** class name |
| `nodes(i)` | `List<NodeInfo>` | `id`, `nodeClass`, `label`, `synthetic`; `isClass("AddNode")` |
| `edges(i)` | `List<EdgeInfo>` | `from`, `to`, `props` |
| `nodeEdges(i, id)` | `NodeEdges` | one node's `inputs()` / `outputs()` |
| `graphProps(i)` | `Map<String,Object>` | graph header properties |
| `blocks(i)` | `List<BlockInfo>` | control-flow basic blocks |
| `search(term)` / `search(i, term)` | `List<SearchHit>` | case-insensitive text over property JSON |
| `nodeProps(i, id)` | `Map<String,Object>` | one node's full properties |

`BgvDump` is not thread-safe; use one instance per thread, and close it.

### Choosing a graph

Never hard-code indices. Index `0` is `After parsing` only on a trivial dump-level-1 file; at `:2` or
`:3` there are dozens of `Before phase`/`After phase` graphs first, and graph `0` can be an empty
`Before phase …PhaseSuite%s` with 0 nodes. Match on the name, taking the **last**:

- `After parsing`
- `FinalPartialEscapePhase`
- `After low tier`, else `/After phase jdk.graal.compiler.core.phases.LowTier`

A missing needle means the phase is absent, not an error. Failed compilations have `Exception` in
the graph name; `BgvDump` does not special-case it, so check yourself.

### Filter by node class, not by text

`search` matches anywhere in a node's serialized properties. It is right for *descriptors* that
exist only in property text, and wrong for asking "which nodes are of type X": searching a real PEA
graph for `ClojureClosure` returned 957 hits, nearly all nodes that merely mention the type. Use
`nodes(i)` and filter on `nodeClass`, then call `nodeProps` on the survivors.

### Gotchas

- **`nodeProps` returns `java.util.Map`, not a Clojure map.** It arrives via Jackson, so
  `clojure.core/map?` is `false` and a `(when (map? ...))` guard silently skips everything. Test
  with `(instance? java.util.Map x)`; `get` works on both.
- **Allocation markers split across two APIs.** Class names (`CommitAllocationNode`,
  `NewInstanceNode`, `NewArrayNode`) are `nodeCounts()` keys — a count of 0 means the key is
  **absent**, not zero. Descriptors (`new_instance_or_null`, `new_array_or_null`) are never
  `nodeCounts()` keys; they live inside `ForeignCallNode` property text and are only reachable via
  `search`. A checker using `describe` alone cannot fail.
- **`describe` node ids are not `search`/`nodeProps` node ids.** `describe` runs Seafoam passes on a
  *copy* and hides nodes; the others use the raw parsed graph.
- **Use simple class names in searches.** GraalVM 25 dumps say `jdk.graal.compiler.*`, older ones
  `org.graalvm.compiler.*`. `NewInstanceNode` matches both; the FQN matches one. `nodeCounts()` keys
  were always simple names.
- **Hit counts are not stable across Graal versions.** Assert non-empty, not `size() == 4`.

## The workflow

### 0. Try `explain-allocations` first

The steps below are the manual method, and worth knowing because they generalize. But the common
case — "what survived, and where did it come from" — is already a build target:

```bash
clojure -T:build explain-allocations :benchmark '"KeywordMapBenchmark.guestPipelineReduce"' :guest true
clojure -T:build explain-allocations :bgv '"target/graal-dumps-pea/<file>.bgv"'
```

It reports virtual objects at PEA split into scalar replaced versus committed, the type of each
surviving object with the source frames it came from, low-tier allocation stubs, and
`relativeFrequency` so cold deopt-path allocations are distinguishable from hot ones. It is
reporting only and never fails a build; `check-scalar-replacement` remains the gate.

Write a probe by hand when you need something it does not cover.

### 1. Confirm the dump is trustworthy

- **Not truncated** — `isTruncated()`.
- **Big enough to contain the work** — a 9-node graph for a benchmark measuring 2500 ns/op is not
  the code you are looking for.
- **It reached the phase you care about** — economy-tier (`Tier1`) compilations never run PEA.

### 2. Find what survives PEA

`FinalPartialEscapePhase` is the phase that matters. Anything still inside a `CommitAllocationNode`
there is an allocation PEA could not remove, and it is the real cost.

```clojure
(require '[clojure.java.io :as io] '[clojure.string :as str])
(import '[com.github.thealchemist BgvDump])

(with-open [d (BgvDump/open (.toPath (io/file (first *command-line-args*))))]
  (when (.isTruncated d)
    (println "WARNING: dump is truncated"))
  (let [idx (->> (.listGraphs d)
                 (filter #(str/includes? (.name %) "FinalPartialEscapePhase"))
                 last
                 .index)
        ids (->> (.search d (int idx) "CommitAllocationNode")
                 (keep #(.nodeId %))
                 distinct)]
    (doseq [id ids]
      (let [props (.nodeProps d (int idx) (int id))]
        (println "node" id)
        (doseq [k (filter #(str/starts-with? % "object(") (keys props))]
          (println "  " k "=" (get props k)))))))
```

Each `object(N) = Type[...]` line names a type that gets heap-allocated. Real output:

```
node 5317
   object(4095) = ClojureClosure[41,3844,4082,22,5,3382,4097]
   object(4082) = FrameWithoutBoxing[3192,5134,4083,4084,5313,100]
   object(4083) = Object[][41,5305,2034,...]     ; 35 elements
   object(4084) = long[][142,142,142,...]        ; 35 elements
```

### 3. Enumerate the allocated types precisely

Filtering by node class avoids the text-search noise and tells you exactly what is being
materialized.

```clojure
(let [virtuals (filter #(or (.isClass % "VirtualInstanceNode")
                            (.isClass % "VirtualArrayNode"))
                       (.nodes d (int idx)))]
  (doseq [n virtuals]
    (let [p (.nodeProps d (int idx) (int (.id n)))]
      ;; VirtualInstanceNode carries "type"; VirtualArrayNode carries
      ;; "componentType" and "length" and has no "type" at all.
      (println (.id n) (or (get p "type")
                           (str (get p "componentType") "[" (get p "length") "]"))))))
```

Name the two classes rather than matching the substring `Virtual`: that would also pull in
`VirtualObjectState`, which is a *description* of virtual objects at a safepoint, not an object
being allocated, and it has neither `type` nor `componentType`.

To see which commit materializes which object, walk the edges. Edge props name the slot, so you can
tell a `virtualObjects` operand from a `values` operand:

```clojure
(doseq [e (.inputs (.nodeEdges d (int idx) (int commit-id)))]
  (println "  <-" (.from e) "via" (get (.props e) "name")))
```

### 4. Trace an allocation back to source

This turns a type name into an actionable finding. Every node carries a `nodeSourcePosition` chain
of inlined frames, innermost first.

```clojure
(defn source-chain [pos depth]
  (when (and (instance? java.util.Map pos) (< depth 12))
    (let [m (get pos "method")]
      (cons (when (instance? java.util.Map m)
              (str (apply str (repeat depth "  "))
                   (get m "declaring_class") "#" (get m "method_name")))
            (source-chain (get pos "caller") (inc depth))))))

(println (str/join "\n" (remove nil? (source-chain (get props "nodeSourcePosition") 0))))
```

A real result, which located a whole-frame copy behind every capturing closure:

```
com.oracle.truffle.runtime.OptimizedTruffleRuntime#createMaterializedFrame
  net.javacrumbs.cloffle.nodes.ClojureRootNode#snapshotFrame
    net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode$GetOuterFrame#doGet
```

That chain names the exact operation to go read. Two or three frames is usually enough.

### 5. Compare against `After PE Tier` when inlining is in question

`After PE Tier` is the graph immediately after Truffle partial evaluation, before Graal's own
optimization. It answers "did partial evaluation inline the callees into one graph". A modest node
count with few `Invoke` nodes means yes.

## Reading the graph

### The three phases to compare

| Phase | What to look for |
| --- | --- |
| `Call Tree / After Inline` | Did the callee inline? A surviving `CallNode` makes PEA across that boundary impossible. |
| `FinalPartialEscapePhase` | Did Graal eliminate the allocation? `NewInstanceNode` should be gone, replaced by `VirtualInstanceNode` / `VirtualObjectState`. |
| `After low tier` | **The ultimate truth.** Any `ForeignCallNode` with `new_instance_or_null` or `new_array_or_null` means an allocation was committed. |

### What the nodes mean

- **`NewInstanceNode` / `NewArrayNode`** — explicit heap allocation. Normal *before* PEA; after PEA
  it means scalar replacement failed.
- **`VirtualInstanceNode` / `VirtualArrayNode`** — a virtual object; its fields live in SSA values or
  registers. This is the success state.
- **`VirtualObjectState`** — state of virtual objects at a safepoint or deopt point.
- **`CommitAllocationNode`** — **failure signal.** Graal decided the virtual object must be
  rematerialized on the heap here, because of deoptimization, an escaping reference, or complex
  control flow.
- **`ForeignCallNode`** with `new_instance_or_null` / `new_array_or_null` — how a surviving
  allocation looks after low-tier lowering.
- **`TruffleNew`** — Seafoam's presentation of a Truffle-level allocation before PEA.
- **`FrameState`** — bytecode locals and expressions at an instruction. An object pinned in a local
  across a safepoint can force a `CommitAllocationNode`.
- **`ValuePhiNode` / `ValueProxyNode`** — a merge of values from several branches. Differing shapes
  or counts across branches stop Graal constant-folding the properties.
- **`FixedGuardNode`** — a type or profile check; a failed guard deoptimizes.

## Why an allocation survived

When `check-scalar-replacement` fails, it prints the itemized allocations for the compilation it
selected. Work through this list.

**0. Does the graph account for the bytes?** The gate measured the whole program; the diagnosis
covers one compilation unit. If every stub in the graph is cold but the benchmark allocates
kilobytes per operation, the allocation is somewhere the graph does not cover — a sibling
compilation unit, interpreted code, or repeated deoptimization. Check the other `.bgv` files in the
dump directory, and use `-prof async:event=alloc` for attribution or
`-Djdk.graal.TraceDeoptimization` to see whether it is deopting.

**1. Find the origin.** Read `nodeSourcePosition` on the `CommitAllocationNode` or `ForeignCallNode`
(step 4 above). It names the exact Java or Clojure line that produced the surviving object.

**2. Did it inline?** Check `Call Tree / After Inline`. A remaining invoke means Graal hit an
inlining budget such as `TruffleInliningMaxCallerSize`. Remedy: `@TruffleBoundary` on cold fallback
paths to shrink the inlined method.

**3. Is it on a cold or deopt path?** Check `relativeFrequency` on the node. `1.0` means every
execution; `0.0005` means an uncommon branch. Treat this as a hint about *where to look*, not as a
verdict: it is a static estimate, so a path marked 0.01 still allocates on every operation if the
code deoptimizes into it. Rematerializing a virtual object at a deopt point is normal and
unavoidable, which is exactly why cold stubs cannot by themselves fail a build.

**4. Is a count or key behind a phi?** If the value passed through an `if` or `cond->`, its `count`
input may be a `ValuePhiNode` rather than a constant `IntegerStamp[3]`. Graal then cannot rule out
the branch that promotes to a larger representation, and that branch forces an allocation.

**5. Where did the collection start?** A literal `{}` emitting `PersistentArrayMap.EMPTY` is a
static heap object, not a virtual one. In Cloffle `{}` must emit `CreateMap0` →
`PersistentShapeMap.EMPTY`.

## Asserting compilation in JUnit

Beyond JMH dumps, guest compilation units can be forced and inspected in
`GuestCompilationUnitTest` (`src/test/java/net/javacrumbs/cloffle/GuestCompilationUnitTest.java`).
This does not replace Seafoam; it confirms the guest root actually compiled.

Configure Polyglot `Context` for synchronous compilation of guest bytecode roots:

```java
Context context = Context.newBuilder("cloffle")
    .allowAllAccess(true)
    .option("engine.CompileImmediately", "true")
    .option("engine.BackgroundCompilation", "false")
    .option("engine.CompileOnly", "CloffleBytecode")
    .build();
```

- `engine.CompileImmediately`: compilation threshold 0.
- `engine.BackgroundCompilation`: compile on the caller thread.
- `engine.CompileOnly`: restrict to Cloffle bytecode roots so macroexpansion is not compiled.

Guest code can query `(com.oracle.truffle.api.CompilerDirectives/inCompiledCode)`. The first
execution compiles; the second should return `true`. To inspect the `CallTarget`:

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

```bash
clojure -T:build run-tests :args '["--select-class=net.javacrumbs.cloffle.GuestCompilationUnitTest"]'
```

## Case studies

**Inlining budget blowup in `PersistentShapeMap.assoc`.** `(-> {} (assoc :a 1) (assoc :b 2))` was
clean, but a third and fourth `assoc` produced `new_instance_or_null` and a `CommitAllocationNode`.
The cold branches `assocPromote16` (reached only when `count == 8`) and `assocNonKeyword` carried a
lot of bytecode, so Graal exhausted its budget after two `assoc` calls and left the third
un-inlined. Marking both cold paths `@TruffleBoundary` shrank `assoc`'s inlined IR by over 80% and
took low-tier allocations to zero for 4+ chained calls.

**Reflector static-field bailout.** `PermanentBailoutException: Too deep inlining` while compiling
`StaticField.doGet`; Graal was inlining Java reflection recursively through JVM internals. Fixed by
wrapping the reflective helper in a `@TruffleBoundary` method.

**ArraySeq allocation in keyword arguments.** Destructuring `& {:keys [method timeout]}` built an
intermediate `ArrayList` in `GetRestArgs` and called `PersistentArrayMap/createAsIfByAssoc
(to-array …)`, allocating both an `Object[]` and a `PersistentArrayMap`. Fixed by emitting
`ArraySeq.create(rest)` directly and adding `RT.mapForDestructuring` to build a `PersistentShapeMap`
without going through `PersistentArrayMap`.

## Using IGV

`BgvDump` is the right tool for anything repeatable or scripted. IGV is worth opening when you want
to *see* the graph — following control flow visually, or diffing two phases by eye.

IGV ships with GraalVM and starts with `./bin/igv`, listening on `127.0.0.1:4445`. You can stream
graphs live with `-Djdk.graal.PrintGraph=Network`, but dumping to file and opening it is more
reproducible and leaves an artifact you can archive. The phase tree in the sidebar mirrors the names
`listGraphs()` returns.

## Traps

**A pass on a tiny graph proves nothing.** If the analyzed graph has too few nodes to account for the
measured ns/op, the compilation being inspected is not the one doing the work, and its clean result
is meaningless. `check-scalar-replacement` warns below 25 nodes.

**Dumps get truncated.** Graal writes from compiler threads; when the JVM exits first the file ends
mid-record. Truncation is detected and earlier graphs stay valid, but *a phase missing from a
truncated dump is not evidence of absence*.

**Short JMH runs truncate the most interesting compilation.** This is the usual cause of the above:
the final-tier compilation happens last, so it is the one still being written at exit. The defaults
are `-wi 3 -i 2 -w 2s -r 3s` for that reason. Do not lower them without re-checking `isTruncated()`.

**One method has many compilations.** A hot root node is compiled repeatedly, and a tier can be
recompiled into a near-empty graph after a deoptimization. Selecting by "most graphs" once picked a
9-node deoptimized recompile over the real 909-node one. Selection now prefers the largest
*complete* compilation that ran PEA and reached low tier.

**A separate compilation for a callee does not mean it was not inlined.** `clojure.core_filter`
having its own dump only means `filter` got hot on its own; it can be separately compiled *and*
inlined elsewhere. Check `After PE Tier` of the caller rather than inferring from file names.

**Low-tier allocations are invisible to node counts.** They are `ForeignCallNode`s carrying
descriptors, so they never appear as allocation classes in `describe().nodeCounts()`. Search the
property text; that is why the checker uses both.

## If the Java API looks wrong

`BgvDump` is a thin JRuby façade, so a Ruby-level bug surfaces in Java as a `SeafoamException` or a
result that makes no sense. We own the fork, so fix it rather than working around it. The checkout is
at `~/Development/digital-alchemy/seafoam`; paths below are relative to it.

| What | Path |
| --- | --- |
| BGV parser | `lib/seafoam/bgv/bgv_parser.rb` |
| Binary reader | `lib/seafoam/binary/io_binary_reader.rb` |
| Query façade | `lib/seafoam/dump.rb` |
| Java wrapper | `java/seafoam-jruby/src/main/java/com/github/thealchemist/BgvDump.java` |
| Java tests | `java/seafoam-jruby/src/test/java/com/github/thealchemist/BgvDumpTest.java` |
| Ruby specs | `spec/seafoam/` |

To reproduce a suspected parser bug without the Java layer in the way:

```bash
cd ~/Development/digital-alchemy/seafoam
ruby -Ilib -e 'require "seafoam"; d = Seafoam::Dump.open(ARGV[0]); p d.truncated?, d.list_graphs.size' /path/to.bgv
```

Ruby-side property keys differ from the Java side: the nested source position uses **symbols**
(`:method`, `:caller`, `:declaring_class`), while the JSON that reaches Java uses strings.

Then test both layers and publish:

```bash
bundle exec rspec                      # Ruby specs
cd java/seafoam-jruby && mvn -o test   # Java tests
mvn -o install                         # publish to ~/.m2 for Cloffle
```

Bump the version in [deps.edn](deps.edn) to match after installing.

Add coverage on both sides. Behavior belongs in the Ruby specs, but the wrapper's conversion layer
has its own failure modes: `isTruncated()` cannot use the JSON round-trip the other calls use,
because JRuby converts a Ruby boolean to a Java `Boolean`, which has no `to_json`. That bug passed
every Ruby spec and failed only in Java.

Anything `Seafoam::Dump` exposes should be reachable from Java. If it is not, that is the gap to
close — add the Ruby method, the Java method, and tests on both sides, rather than reaching into
`@entries` from a one-off script.

## Cheat sheet

```bash
# Run all scalar replacement checks, or a suite / filter
clojure -T:build check-scalar-replacements
clojure -T:build check-scalar-replacements :suite :host
clojure -T:build check-scalar-replacements :filter '"Tuple"'
clojure -T:build check-scalar-replacements :list true

# Check one guest benchmark against a budget
clojure -T:build check-scalar-replacement :benchmark '"KeywordMapBenchmark.guestPipelineReduce"' \
  :guest true :alloc-budget 0 :dump-path '"/tmp/cloffle-dumps"'

# Check a guest snippet against a budget
clojure -T:build check-scalar-replacement :snippet '"keyword-invoke"' :alloc-budget 0

# Check one host benchmark
clojure -T:build check-scalar-replacement \
  :benchmark '"PersistentTypeScalarReplacementBenchmark.baselineTuple2ScalarReplacement"' :alloc-budget 0

# Measure budgets for the catalog (prints entries; never edits build.clj)
clojure -T:build record-alloc-budgets
clojure -T:build record-alloc-budgets :missing true
clojure -T:build record-alloc-budgets :snippet '"keyword-invoke"'

# Analyze an existing dump (one compilation unit, pass/fail)
clojure -T:build analyze-graal-graph :bgv '"target/graal-dumps-pea/TruffleHotSpotCompilation-6744[...].bgv"'

# Explain what allocates and where it came from (reporting only)
clojure -T:build explain-allocations :bgv '"target/graal-dumps-pea/TruffleHotSpotCompilation-6744[...].bgv"'
clojure -T:build explain-allocations :benchmark '"KeywordMapBenchmark.guestPipelineReduce"' :guest true

# Measure allocation rate directly
clojure -T:build run-benchmarks :args '["KeywordMapBenchmark.guestPipelineReduce" "-prof" "gc" "-wi" "2" "-i" "2"]'

# List guest graphs in the dump directory
rg --files --hidden --no-ignore target/graal-dumps-pea | rg 'TruffleHotSpotCompilation.*\.bgv$'
```
