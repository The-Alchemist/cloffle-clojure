# Fusion in GraalVM, Truffle, and Cloffle

## 1. Executive Summary

In compiler design, **fusion** is an optimization technique that merges two or more operations that normally communicate via intermediate data structures into a single compound operation.

In **Cloffle** (Clojure on Truffle / GraalVM), fusion bridges the gap between idiomatic, high-level Clojure code and GraalVM's JIT compilation pipeline. Cloffle's compiler (`ExprToBytecode`) analyzes Clojure AST patterns and lowers them directly into fused, specialized Truffle bytecode operations. This eliminates intermediate allocations, drastically reduces Graal IR node counts, and guarantees **100% scalar replacement** under Partial Escape Analysis (PEA).

---

## 2. Theoretical Foundations: Fusion in Computer Science

Functional and dynamic languages encourage composing small, modular functions:

```clojure
(-> data (step-one) (step-two) (step-three))
```

While elegant and maintainable, naive execution incurs an **intermediate representation penalty**: each step produces a transient object that exists solely to be consumed by the next step and immediately discarded.

Compiler theory addresses this with various forms of fusion:

| Domain | Technique | Mechanism |
| :--- | :--- | :--- |
| **Functional Languages** | **Deforestation** (Wadler, 1990) | Eliminates intermediate trees and lists in expressions like `(map f (filter p xs))`. |
| **Stream Processing** | **Stream Fusion** (Haskell / Java Streams) | Collapses multi-stage pipelines into a single loop or state machine operating on registers. |
| **Deep Learning** | **Kernel / Operator Fusion** (XLA, PyTorch Inductor) | Merges operations like `ReLU(BiasAdd(MatMul(A, B)))` into a single GPU kernel to avoid VRAM round-trips. |
| **Cloffle / Truffle** | **AST Pattern & Collection Fusion** | Replaces multi-step Clojure runtime calls with fused bytecode operations targeting JVM intrinsics and pre-computed data. |

**Core Rule:** Fusion transforms *"allocate intermediate representation $\to$ consume intermediate representation"* into direct computation in registers and stack frames.

---

## 3. The GraalVM & Truffle Perspective

To understand why Cloffle performs frontend fusion rather than relying solely on the JIT, consider how Truffle and GraalVM compile code:

```
Guest Code (Clojure)
       │
       ▼  ExprToBytecode compiler
Truffle Bytecode Operations
       │
       ▼  Partial Evaluation (PE)
High-Tier Graal IR (Intermediate Representation)
       │
       ▼  Partial Escape Analysis (PEA) & Inlining
Low-Tier Machine Code (x86 / ARM64)
```

### Partial Escape Analysis (PEA) and Scalar Replacement
Graal's primary optimization engine is **Partial Escape Analysis (PEA)**. When Graal can prove that an allocated object does not "escape" the current compilation scope (i.e., it is not stored in a heap field, passed to unknown foreign code, or passed across non-inlined safepoints), it **scalar-replaces** the object:
- The heap allocation is removed entirely.
- The object's fields become local variables residing in CPU registers.
- Access latency drops from ~800 ns (heap allocation + GC tracking) to ~5 ns (register operations).

### Why Pure JIT Optimization Struggles with Dynamic Clojure
If GraalVM has PEA, why can't it optimize standard Clojure automatically?

1. **Call Depth & Inlining Budgets:** Clojure idioms rely on core library abstractions. For instance, `(get-in m [:a :b])` calls `clojure.core/get-in`, which calls `clojure.core/reduce1`, which calls `clojure.core/get`, which checks `ILookup`, `Map`, etc. Graal imposes strict inlining limits. If inlining stops before reaching leaf nodes, objects escape.
2. **Polymorphic Call Sites:** Clojure functions often accept polymorphic inputs (`IPersistentMap`, `IType`, `Object`). Polymorphic call sites prevent inlining and defeat escape analysis.
3. **Graph Complexity:** Passing collections through loops and seq abstractions inflates the Graal IR graph, pushing methods past the complexity threshold for aggressive optimization.

### Cloffle's Solution: Ahead-of-Time Bytecode Fusion
Rather than hoping Graal can inline deep runtime call trees, **Cloffle detects patterns during bytecode generation (`ExprToBytecode`) and emits fused bytecode sequences.**

Because the bytecode itself contains no intermediate allocations and no dynamic dispatch, Graal receives a clean, linear IR of 4–19 nodes, enabling 100% scalar replacement.

---

## 4. Categories of Fusion in Cloffle

Cloffle organizes fusion into two main areas: **Pattern & String Fusion** and **Associative Collection (Map) Fusion**.

---

### A. Idiomatic Pattern & String Fusion

Idiomatic Clojure code and serialization libraries (such as Cheshire JSON) frequently use defensive patterns or macros that generate redundant intermediate strings.

#### Case Study: `KeywordFieldName` (Cheshire JSON Encoding)
In Cheshire JSON serialization, converting a keyword into a JSON field string executes:
```clojure
(if (keyword? k) (.substring (str k) 1) (str k))
```

- **Without Fusion (Standard Clojure / Host Execution):**
  1. `(str k)` converts `:user/id` to `":user/id"` $\to$ **Allocates String 1**.
  2. `(.substring ... 1)` strips the leading `:` $\to$ **Allocates String 2**.
- **With Cloffle Pattern Fusion:**
  `ExprToBytecode` detects this AST structure (`IfExpr` checking `keyword?`, calling `substring(str, 1)` on the truthy branch and `str` on the falsy branch):
  ```java
  b.beginKeywordFieldName();
  convert(target, b);
  b.endKeywordFieldName();
  ```
  At runtime, `Keyword.getFieldName()` directly returns `k.sym.toString()`, which is **already pre-computed, interned, and cached** on the keyword.
- **Outcome:** **0 heap allocations**, **~5.7 ns latency**, **19 Graal IR nodes**, and **100% scalar replacement**.

#### Case Study: Multi-Argument String Concatenation (`CoreStr4`)
```clojure
(str "/" tenant "/" entity "/" id)
```
- **Without Fusion:** Exceeds fixed-arity `str` (1, 2, or 3 args) and falls back to variadic `(str x & ys)`, allocating an `ArraySeq` and dynamic `StringBuilder`.
- **With Fusion:** Emitters recognize the 4-argument call and lower it to a direct `CoreStr4` bytecode operation that compiles to JVM invokedynamic string concatenation (`StringConcatFactory`).

---

### B. Associative Collection (Map) Fusion

Clojure maps are persistent and immutable. Many common operations appear to require multiple steps or intermediate allocations. Cloffle collapses these pipelines into direct bytecode sequences.

#### 1. Map Literal Merge Fusion (`emitUnrolledMergeMapLiteral`)
```clojure
(merge current-config {:port 8080 :env "prod"})
```
- **Without Fusion:**
  1. The literal map `{:port 8080 :env "prod"}` is compiled into a bytecode constructor that allocates a `PersistentHashMap` on the heap.
  2. `clojure.core/merge` is called.
  3. `merge` creates sequence iterators (`PersistentHashMap$NodeSeq`) over the literal map.
  4. Each key-value pair is read and `assoc`'d into `current-config`.
- **With Fusion:**
  `ExprToBytecode` inspects the AST. When the second argument is a map literal with keys and values known at compile time:
  - The literal map is **never instantiated**.
  - The compiler emits nested `KeywordAssoc` / `MapAssoc` operations directly against `current-config`:
    ```java
    b.beginKeywordAssoc(:env);
      b.beginKeywordAssoc(:port);
        emitLoad(current-config);
        emitLoad(8080);
      b.endKeywordAssoc();
      emitLoad("prod");
    b.endKeywordAssoc();
    ```
- **Outcome:** Zero intermediate map allocation, zero sequence iterators, and direct constant/local propagation.

#### 2. Deep Access Unrolling (`emitUnrolledGetIn` & `emitUnrolledAssocIn`)
```clojure
(get-in user [:profile :address :city] "Unknown")
```
- **Without Fusion:**
  Passes a `PersistentVector` of 3 keywords to `clojure.core/get-in`, which iterates through the vector at runtime with bounds checks and dynamic dispatch at each level.
- **With Fusion:**
  At compile time, `ExprToBytecode` extracts the vector literal and emits a chained lookup directly:
  ```
  KeywordLookupDefault(:city, "Unknown")
    └── KeywordLookup(:address)
          └── KeywordLookup(:profile)
                └── Load(user)
  ```
- **Outcome:** No vector allocation or traversal; compiles to a straight pipeline of single-instruction hash-map lookups.

#### 3. Read-Modify-Write Fusion (`emitUnrolledUpdate` & `emitUnrolledUpdateIn`)
```clojure
(update counts :visitors inc)
```
- **Without Fusion:**
  Calls the higher-order function `update`, creating an invocation frame, reading the key, applying the function, and associating the result.
- **With Fusion:**
  Cloffle stores the target map in a tracked bytecode local, performs `KeywordLookup(:visitors)`, passes that value directly to `inc`, and feeds the output into `KeywordAssoc(:visitors)`:
  ```
  LocalStore(m)
  KeywordAssoc(:visitors, Load(m), Invoke1(inc, KeywordLookup(:visitors, Load(m))))
  ```

---

## 5. Summary Comparison

| Scenario | Naive / Standard Execution | Cloffle Fusion Execution | Truffle / GraalVM Advantage |
| :--- | :--- | :--- | :--- |
| **JSON Keyword Key** | `(str k)` $\to$ `(.substring ... 1)` (2 heap strings) | `KeywordFieldName` (uses interned `sym.name`) | Bypasses all intermediate strings; 0 B allocated. |
| **Map Merge** | Allocates map literal $\to$ seq iteration $\to$ `merge` | `emitUnrolledMergeMapLiteral` (unrolls to `assoc`) | Map literal never instantiated; zero seq allocations. |
| **Nested Lookup** | Allocates path vector $\to$ runtime loop in `get-in` | `emitUnrolledGetIn` (chains `KeywordLookup`) | Eliminates path vector traversal; inlines cleanly. |
| **Map Update** | Calls higher-order function `update` | `emitUnrolledUpdate` (local $\to$ lookup $\to$ fn $\to$ assoc) | Direct register flow without closure overhead. |

---

## 6. Implementation Architecture

Every fusion in Cloffle coordinates across three tiers:

1. **Bytecode Operation (`CloffleBytecodeRootNode.java` / helpers):**
   - Uses Truffle Bytecode DSL (`@Operation(storeBytecodeIndex = true)`).
   - Keeps methods static and specialized on common types (`Keyword`, `String`, primitives).
2. **Compiler Recognition & Emission (`ExprToBytecode.java`):**
   - **Pattern Detection:** Identifies candidate AST structures (`isKeywordFieldNamePattern`, `isUnrollableGetIn`, etc.).
   - **Local Variable Accounting (`countExprLocals`):** Reserves frame slots only for expressions evaluated in the fused path.
   - **Emission (`convert`):** Emits builder instructions (`b.begin...()`, `b.end...()`).
3. **Bytecode Archive Versioning (`CloffleCoreBytecodeArchive.java`):**
   - Increments `VERSION` when new bytecode operations are introduced to ensure cached bytecode archives recompile cleanly.

---

## 7. Verification and Measuring Success

A fusion optimization is successful when it achieves **scalar replacement** in GraalVM:

1. **GC Allocation Rate — the gate:**
   `gc.alloc.rate.norm` is the measure of success, because it covers the whole program.
   ```sh
   export ENV=local && eval "$(direnv export zsh)"
   clojure -T:build check-scalar-replacement :benchmark '"<BenchmarkName>"' :guest true :alloc-budget 0
   ```
   The task runs JMH under `-prof gc` and fails when B/op exceeds `:alloc-budget`:
   ```text
     5.69 ns/op
     PASS  0.0 B/op (budget 0.0)
   ```
   Record budgets for the catalog with `clojure -T:build record-alloc-budgets`.

2. **Graal graph inspection — the diagnosis, not the gate:**
   When the gate fails it automatically dumps graphs and itemizes what survived PEA, with the
   inlined source frames each object came from.

   Do **not** use a clean graph as evidence that a fusion worked. A graph covers one compilation
   unit, while a Clojure pipeline compiles into several units plus interpreted frames. A measured
   case: `guestPipelineReduce` had no allocation stub above `relativeFrequency` 0.01 in any of its
   three largest units while allocating 6168 B/op. See
   [HOWTO_SEAFOAM.md](HOWTO_SEAFOAM.md#graphs-diagnose-they-do-not-gate).

3. **Node counts** remain a useful secondary signal that the fused bytecode reached Graal as the
   clean linear IR it was supposed to, but they are not pass/fail.

---

## 8. Removal & Lessons Learned (Post-Mortem & Re-implementation Guide)

In commit `f42438f6`, AST map and pattern fusion (`ExprToBytecodeMapFusion.java` and `isKeywordFieldNamePattern`) were removed. Below is what was learned, why it interfered with benchmarks, and how to approach fusion in the future.

### Why Fusion Was Backed Out
1. **Benchmark Profile Distortions:**
   - Eager frontend unrolling in `ExprToBytecode` hard-coded AST assumptions (e.g. expanding multi-arg `assoc` or `update` into nested bytecode blocks with temporary local variables).
   - In microbenchmarks and snippet benchmarks (such as JMH comparisons), the introduction of extra bytecode blocks (`beginBlock`), tracked locals (`createTrackedLocal`), and store/load sequences changed bytecode shapes in ways that bloated frame sizes and generated more bytecode nodes than the naive standard runtime dispatch.
   - For idiomatic patterns like Cheshire's `(if (keyword? k) (.substring (str k) 1) (str k))`, matching deep AST shapes at compiler frontend level added compilation-time friction and brittleness without offering generalized speedups across varied guest call patterns.

2. **Frame Local Accounting Complexity:**
   - Truffle bytecode interpreters pre-allocate frame local slots based on `ExprToBytecodeLocals.countExprLocals`.
   - Every unrolled transformation required strict manual local counting (e.g., reserving slots for intermediate maps, key locals, function invoke arguments, plus exit branches). If the counting in `ExprToBytecodeLocals` drifted by even 1 from `ExprToBytecode.convert`, stack frame indices corrupted or threw `IllegalStateException` during compilation.

3. **Interference with Graal's Inlining & Polymorphic Caching:**
   - When operations like `assoc` or `get-in` are lowered into chains of specialized Truffle bytecode operations (`KeywordAssoc`, `KeywordLookup`), each operation has its own node dispatch / polymorphic inline cache.
   - For standard map instances (e.g. `PersistentArrayMap`, `PersistentHashMap`, `PersistentShapeMap`), standard method invocations like `RT.assoc(m, k, v)` or `m.assoc(k, v)` already have megamorphic guards and well-tuned JVM bytecode intrinsics that Graal can optimize directly without interpreter AST expansion.

### Re-implementation Blueprint (For Future Work)

When re-implementing fusion in the future, adhere to the following principles:

1. **JIT-Driven / Node-Level Fusion over AST Frontend Expansion:**
   - Instead of emitting synthetic blocks and locals in `ExprToBytecode`, consider performing fusion in Truffle node specializations (e.g., inside `@Specialization` or `@Cached` nodes in `CloffleBytecodeRootNode`).
   - Let the frontend emit standard semantic operations, and allow runtime specializations to collapse consecutive lookups or update-in paths when target types and shapes stabilize.

2. **Opt-In Compiler Flags or Compilation Hints:**
   - Gate AST-level unrolling behind explicit flags (e.g. `-Dcloffle.fusion.maps=true` or `:fusion true` in compiler options).
   - This allows isolating benchmark suites between pure Clojure runtime semantics and fused bytecode pipelines without contaminating baseline measurements.

3. **Decoupled Local Allocation:**
   - If frontend unrolling is reintroduced, abstract local allocation so that unrolling generators automatically register and compute their required temporary locals rather than maintaining duplicate parallel AST traversal logic in both `ExprToBytecode.java` and `ExprToBytecodeLocals.java`.

4. **Reference Implementation Artifacts:**
   - Prior implementation: commit `ad3187e9` / `ea09a107` (`ExprToBytecodeMapFusion.java`).
   - Removal commit: `f42438f6` (`refactor(bytecode): remove ExprToBytecodeMapFusion and pattern fusion`).

