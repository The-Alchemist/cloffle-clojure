# Complete Guide to Creating and Analyzing GraalVM IGV / BGV Dumps in Cloffle

This guide provides step-by-step instructions for dumping, visualizing, and analyzing Graal compiler graphs using **Ideal Graph Visualizer (IGV)**, the **in-process `BgvDump` API**, and the **`seafoam` CLI**.

It synthesizes the practical patterns, compiler node heuristics, and debugging techniques developed while optimizing Cloffle's Partial Escape Analysis (PEA) and scalar replacement pipelines.

---

## 1. Concepts: BGV vs. IGV vs. Seafoam

- **BGV (`.bgv`)**: The binary graph dump file format produced by GraalVM. It contains the full compiler Intermediate Representation (IR) across all compiler phases (parsing, inlining, PEA, loop unrolling, low-tier lowering, code generation).
- **IGV (Ideal Graph Visualizer)**: Oracle GraalVM's GUI application (based on NetBeans platform) for opening `.bgv` files or streaming compiler graphs live over the network. It allows visual graph diffing, node searching, control-flow coloring, and metadata inspection.
- **In-process `BgvDump` (`build.clj`)**: Cloffle's automated Java reader (`com.github.thealchemist.BgvDump`) used by `check-scalar-replacement` and `analyze-graal-graph` for CI and headless testing without GUI or Ruby dependencies.
- **Seafoam CLI**: A command-line inspection tool for querying `.bgv` files (`list`, `describe`, `search`, `props`).

---

## 2. Setting Up Ideal Graph Visualizer (IGV)

### A. Downloading / Running IGV
IGV is available as part of GraalVM or via the `mx` development tool:
- If you use the standalone IGV distribution or GraalVM SDK:
  ```bash
  # Start IGV (runs on Java 17+ or 21+)
  ./bin/igv
  ```
- By default, IGV starts and listens on `127.0.0.1:4445` for network graph streams.

### B. Two Ways to Feed IGV
1. **Live Network Streaming**: GraalVM sends graphs over a TCP socket directly to a running IGV window (`-Djdk.graal.PrintGraph=Network`).
2. **File Dump & Open (Recommended)**: GraalVM writes `.bgv` files to disk (`-Djdk.graal.PrintGraph=File`). You then open them via `File -> Open...` (or drag and drop) in IGV. This is safer for reproducibility and allows archiving.

---

## 3. How to Dump Graal Compiler Graphs in Cloffle

GraalVM 25 uses modern `-Djdk.graal.*` property prefixes (the old `-Dgraal.*` prefixes are deprecated).

### A. The MethodFilter Trap for Truffle Guest Code!

> **CRITICAL RULE**: In Cloffle, guest Clojure functions compile as `CloffleBytecodeRootNode[ns_function-name]`.
> If you set:
> `-Djdk.graal.MethodFilter=*guestCondOptionPipeline*`
> GraalVM will **ONLY** dump the JMH host Java harness method, and will **SILENTLY DROP** all guest `TruffleHotSpotCompilation` graphs!
>
> **Always include `*CloffleBytecode*` in the filter when inspecting guest Clojure code:**
> `-Djdk.graal.MethodFilter=*CloffleBytecode*,*my-guest-fn*`

### B. Dumping via `build.clj` (Automated & Recommended)

`build.clj` already provides integrated tasks that automatically configure the proper flags, JVM arguments, and filters:

#### 1. Check Scalar Replacement & Dump (Guest AST)
```bash
clojure -T:build check-scalar-replacement \
  :benchmark '"KeywordMapBenchmark.guestCondOptionPipeline"' \
  :guest true
```
* Dumps graphs to `target/graal-dumps-pea/`.
* Discovers the guest root graph (`TruffleHotSpotCompilation-*.bgv`).
* Automatically verifies that the low-tier graph contains zero allocations.

#### 2. Analyze an Existing `.bgv` File Headless
```bash
clojure -T:build analyze-graal-graph \
  :bgv '"target/graal-dumps-pea/TruffleHotSpotCompilation-6744[CloffleBytecodeRootNode[clojure.core_guest-cond-option-pipeline]].bgv"'
```

### C. Manual Dumping via JMH
If running JMH manually:

```bash
clojure -T:build run-benchmarks :args '["KeywordMapBenchmark.guestCondOptionPipeline"
  "-wi" "2" "-i" "1" "-w" "500ms" "-r" "100ms" "-f" "1"
  "-jvmArgsAppend"
  "-Djdk.graal.Dump=:2 -Djdk.graal.PrintGraph=File -Djdk.graal.DumpPath=target/graal-dumps -Djdk.graal.MethodFilter=*CloffleBytecode*,*guest-cond-option-pipeline*"]'
```

* `-Djdk.graal.Dump=:2`: Verbosity level 2 (includes high tier, PEA, low tier). Level `:3` includes low-level scheduling and LIR, but produces very large files.
* `-Djdk.graal.PrintGraph=File`: Writes `.bgv` files into `target/graal-dumps/`.

### D. File Types in the Dump Directory
- `TruffleHotSpotCompilation-<id>[CloffleBytecodeRootNode[...]].bgv`:
  **This is the guest Clojure function compilation.** This is where your Clojure logic, destructuring, and map/vector transformations live.
- `HotSpotCompilation-<id>[...].bgv`:
  This is host JVM Java code (e.g., JMH harness, `KeywordMapBenchmark.java` methods, or standard Java classes).

---

## 4. Navigating Graphs in IGV GUI

When you open a `TruffleHotSpotCompilation-*.bgv` file in IGV, the left sidebar displays a tree of compiler phases.

```text
📁 TruffleHotSpotCompilation-6744 [CloffleBytecodeRootNode[clojure.core_guest-cond-option-pipeline]]
   📁 HighTier
      📄 After parsing
      📄 Inlining
      📄 Call Tree / Before Inline
      📄 Call Tree / After Inline
   📁 MidTier
      📄 PartialEscapePhase
      📄 FinalPartialEscapePhase   <-- [CRUCIAL FOR PEA]
   📁 LowTier
      📄 Before low tier
      📄 After low tier            <-- [CRUCIAL FOR ZERO ALLOCATION]
```

### The 3 Key Phases to Compare

To verify whether an object was scalar replaced:

| Phase | What to Look For |
| :--- | :--- |
| **1. `Inlining` / `Call Tree / After Inline`** | Did the callee inline into the caller? If a `CallNode` remains, PEA across that boundary is impossible. |
| **2. `FinalPartialEscapePhase`** | Did Graal eliminate the allocation? `NewInstanceNode` should be gone and replaced with virtual states (`VirtualInstanceNode` / `VirtualObjectState`). |
| **3. `After low tier`** | **The ultimate truth.** Are there any `ForeignCallNode` calls to `new_instance_or_null` or `new_array_or_null`? If yes, PEA failed or an allocation was committed. |

---

## 5. What Nodes to Look For (and What They Mean)

### High-Tier & PEA Nodes

- **`NewInstanceNode` / `NewArrayNode`**: Explicit allocation of a Java object or array on the heap. Seeing this *before* PEA is normal; seeing this *after* PEA means scalar replacement failed.
- **`VirtualInstanceNode` / `VirtualArrayNode`**: A virtual object representation created by PEA. The object's fields are held in SSA scalar variables or CPU registers.
- **`VirtualObjectState`**: Describes the state of virtual objects at safepoints / deoptimization points.
- **`CommitAllocationNode` / `Alloc {virtualObjects}`**:
  **DANGER / FAILURE SIGNAL**: GraalVM determined that the virtual object must be re-materialized back into a real heap object at this point in execution (e.g., due to deoptimization, escaping reference, or complex control flow).
- **`TruffleNew`**: Seafoam's presentation of a Truffle allocation node before PEA.

### Low-Tier Lowered Nodes

In low-tier IR, all surviving allocations are lowered to runtime foreign calls:
- **`ForeignCallNode` with descriptor `new_instance_or_null`**: Surviving heap object allocation (`new PersistentShapeMap(...)`, `new Object()`, etc.).
- **`ForeignCallNode` with descriptor `new_array_or_null`**: Surviving heap array allocation (`new Object[]`, `new long[]`, etc.).

### Control Flow & Frame State Nodes

- **`FrameState`**: Contains bytecode locals, expressions, and locks at a given instruction. If an object is "pinned" in a local variable across an uncommon branch or safepoint, it can force a `CommitAllocationNode`.
- **`ValuePhiNode` / `ValueProxyNode`**: Merge point of values from two or more branches. If branches produce different shapes, counts, or types, the PHI node prevents GraalVM from constant-folding properties.
- **`FixedGuardNode` / `GuardingNode`**: Type or profile checks (e.g. `checkcast`, instanceof). Failed guards trigger deoptimization.

---

## 6. How to Diagnose Why an Allocation Failed to Disappear

When `check-scalar-replacement` fails with `Low-tier graph still contains allocation nodes`, follow this systematic 5-step checklist:

### Step 1: Find the Origin via `nodeSourcePosition`
In IGV, click on the `CommitAllocationNode` or `ForeignCallNode` and look at the **Properties** panel on the right.
Inspect `nodeSourcePosition`. It shows the exact stack trace:
```text
clojure.lang.PersistentShapeMap.assoc(PersistentShapeMap.java:955)
  -> CloffleBytecodeRootNode$KeywordAssoc.doAssociativeCached(...)
  -> guest-cond-option-pipeline (line 46)
```
This tells you which exact line of Java or Clojure produced the surviving object.

### Step 2: Check the Call Tree (Did It Inline?)
Look at `Call Tree / After Inline`.
- If your function called another function (e.g., a helper function, macro expansion, or `assoc`), did that call actually inline?
- If there is still an invoke/call node, GraalVM hit an inlining budget threshold (`TruffleInliningMaxCallerSize` or deep recursion).
- **Remedy**: Place `@TruffleBoundary` on cold fallback paths to shrink the inlined method size (see Section 7).

### Step 3: Check Branch Relative Frequency (Is it on a Cold/Deopt Path?)
In the node properties of the `ForeignCallNode`, inspect `relativeFrequency`:
- `relativeFrequency = 1.0`: Allocation happens on every normal execution.
- `relativeFrequency = 0.0005` or `0.01`: Allocation is on an "uncommon branch" or deoptimization fallback path.
- *Why does a cold branch matter?* If GraalVM cannot prove that a virtual object in a local variable is dead at that deoptimization point, it will generate a `CommitAllocationNode` along the deopt path, causing `check-scalar-replacement` to fail!

### Step 4: Check for PHI Nodes on Map Counts or Keys
Did the map pass through an `if` or `cond->`?
- Look at the `count` input of `PersistentShapeMap`. Is it a constant `IntegerStamp[3]` or a `ValuePhiNode`?
- If it is a `ValuePhiNode`, GraalVM does not know whether `count == 8` at compile time. It must retain the branch for `count == 8 -> assocPromote16`, and that branch forces an allocation!

### Step 5: Check Initial Collection Creation (`EmptyExpr`)
- Did the pipeline start from `{}` or `[]`?
- If literal `{}` emits `clojure.lang.PersistentArrayMap.EMPTY` via a static field, it is a static heap object, not a virtual object!
- In Cloffle, `{}` must emit `CreateMap0` $\to$ `PersistentShapeMap.EMPTY`.

---

## 7. Real-World Case Studies & Proven Fixes in Cloffle

### Case Study A: The Inlining Budget Blowup in `PersistentShapeMap.assoc` (Opportunity 9)
* **Symptom**: `(-> {} (assoc :a 1) (assoc :b 2))` worked with 2 keys, but adding a 3rd and 4th `assoc` caused `new_instance_or_null` and `CommitAllocationNode` to appear in low tier.
* **IGV Finding**: In `PersistentShapeMap.assoc`, the cold branches `assocPromote16` (only called when `count == 8`) and `assocNonKeyword` contained large amounts of bytecode. GraalVM hit its inlining budget after inlining two `assoc` calls. The 3rd `assoc` was not inlined, leaving a surviving `PersistentShapeMap` allocation.
* **The Fix**:
  ```java
  @TruffleBoundary
  private PersistentShapeMap16 assocPromote16(...) { ... }

  @TruffleBoundary
  private IPersistentMap assocNonKeyword(...) { ... }
  ```
  Adding `@TruffleBoundary` to these cold paths shrank `assoc`'s inlined IR by >80%. GraalVM was immediately able to inline 4+ chained `assoc` calls, reducing low-tier allocations to **0**.

### Case Study B: Reflector Static Field Inlining Bailout
* **Symptom**: `PermanentBailoutException: Too deep inlining` during guest compilation of `StaticField.doGet`.
* **IGV Finding**: Graal tried to inline Java reflection (`Reflector.getStaticField`) recursively through JVM internals.
* **The Fix**: Wrap reflective helper calls in a `@TruffleBoundary` private method.

### Case Study C: ArraySeq Allocation in Keyword Arguments (Opportunity 4)
* **Symptom**: Destructuring `& {:keys [method timeout]}` created an intermediate `java.util.ArrayList` in `GetRestArgs` and called `PersistentArrayMap/createAsIfByAssoc(to-array ~gmapseq)`.
* **IGV Finding**: `to-array` allocated `Object[]` and `PersistentArrayMap` on the heap.
* **The Fix**:
  1. Updated `GetRestArgs` to emit `ArraySeq.create(rest)` directly without `ArrayList`.
  2. Created `RT.mapForDestructuring` to build `PersistentShapeMap` directly from the rest array, bypassing `PersistentArrayMap`.

---

## 8. Command Cheat Sheet

```bash
# 1. Run all known scalar replacement checks (or by suite :host / :guest):
clojure -T:build check-scalar-replacements
clojure -T:build check-scalar-replacements :suite :host
clojure -T:build check-scalar-replacements :filter '"Tuple"'
clojure -T:build check-scalar-replacements :list true

# 2. Run automated scalar replacement check on a specific guest benchmark:
clojure -T:build check-scalar-replacement :benchmark '"KeywordMapBenchmark.guestCondOptionPipeline"' :guest true

# 3. Run automated scalar replacement check on a specific host benchmark:
clojure -T:build check-scalar-replacement :benchmark '"PersistentTypeScalarReplacementBenchmark.baselineTuple2ScalarReplacement"'

# 4. Analyze a specific .bgv file in terminal:
clojure -T:build analyze-graal-graph :bgv '"target/graal-dumps-pea/TruffleHotSpotCompilation-6744[...].bgv"'

# 5. Measure allocation rate in JMH (verify 0 B/op):
clojure -T:build run-benchmarks :args '["KeywordMapBenchmark.guestCondOptionPipeline" "-prof" "gc" "-wi" "2" "-i" "2"]'

# 6. List guest graphs in dump directory:
rg --files --hidden --no-ignore target/graal-dumps-pea | rg 'TruffleHotSpotCompilation.*\.bgv$'
```
