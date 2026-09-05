# Agent brief: dedicated `ConstantVectorExpr` in `Compiler`

**Branch:** `feat/constant-vector-expr`
**Worktree:** this directory
**Base commit:** `a49357f0` (`Document nested-get-in IGV: get-in unroll misses ConstantExpr paths.`)
**Parent worktree (do not edit unless asked):**
`/Users/karl-medplum/.cursor/worktrees/assoc-transition-cache-a3f7c219/cloffle-clojure-f4a422fda87a`
(`feat/assoc-transition-cache`)

You are implementing the **principled AST fix** for Cloffle's `get-in` / `assoc-in` / `update-in` unroll miss. Do **not** ship a marker interface on `ConstantExpr`. Do **not** treat "reconstruct `KeywordExpr` from a generic `ConstantExpr` of `IPersistentVector`" as the long-term design (that workaround exists only as **uncommitted** code in the parent worktree).

Read this whole file before editing. Then read the cited sources.

---

## 1. Goal

When Clojure analyzes a **literal vector whose elements are all `LiteralExpr`**, `VectorExpr.parse` currently returns:

```java
return new ConstantExpr(rv);  // rv is IPersistentVector of runtime values
```

That type-erases "this is a vector of analyzed keys" into "this is some constant object." Cloffle's bytecode unroll for `get-in` / `assoc-in` / `update-in` only fired on `instanceof VectorExpr`, so **the common case** `(get-in m [:user :profile :name])` never unrolled.

**Replace that fold** with a dedicated AST node, e.g. `Compiler.ConstantVectorExpr`, that:

1. Still **is** a `LiteralExpr` (eval / JVM emit can load a constant vector).
2. Still **exposes the per-element `Expr`s** (or at least keyword identity) so Cloffle can emit `KeywordLookup` without reconstructing AST from runtime values.
3. Is **not** a marker on `ConstantExpr` — `ConstantExpr` remains the bucket for numbers, strings, maps, sets, quoted junk, etc.

Success looks like:

- `(get-in {:user {:profile {:name "Alice"}}} [:user :profile :name])` compiles to nested `KeywordLookup`, **not** `clojure.core/get-in` → `reduce1` → `get`.
- Cloffle throughput ~**200M ops/s** and **~24 B/op** on `SnippetBenchmark.cloffle` `-p name=nested-get-in` (same residual as `consume-assoc`), not ~1.5M ops/s and 3200 B/op.
- JVM Clojure `emit` / `eval` for literal vectors remains correct (stock Clojure tests + Cloffle JUnit).
- The parent-worktree `isUnrollablePath` / `pathKeyExprs` / `literalPathKeyExpr` **workaround is unnecessary** and must **not** be copied as the final state. Prefer `instanceof ConstantVectorExpr` (or `VectorExpr`) in `ExprToBytecode`.

---

## 2. Why this exists (history)

### 2.1 Symptom

`ComparePerformance` / `SnippetBenchmark` sample `nested-get-in`:

```clojure
(get-in {:user {:profile {:name "Alice"}}} [:user :profile :name])
```

Catalog: `SnippetBenchmarkSupport.NESTED_GET_IN`.

Measured (before unroll, 5 warmup / 2 measure, Mac aarch64, Java 25):

| | Clojure JVM | Cloffle |
| :--- | ---: | ---: |
| Throughput | ~55.6M ops/s | **~1.53M ops/s** (~0.03×) |
| p95 | 42 ns | **750 ns** |
| Alloc | 64 B/op | **3,200 B/op** |

Do **not** confuse with `KeywordMapBenchmark.nestedGetIn`: that is **lookup-only** on a prebuilt `nested-m`:

```clojure
(defn get-in-nested [m] (get-in m [:user :profile :name]))
```

That still hits the same unroll miss on the **path**, but it does **not** explain the 3200 B/op create+lookup cliff (the map is already allocated).

### 2.2 Intended Cloffle lowering (already in tree)

`ExprToBytecode` already has:

- `isGetInCall` / `isGetInStatic` / `isAssocInCall` / `isAssocInStatic` / `isUpdateInCall` / `isUpdateInStatic`
- `emitUnrolledGetIn` → `emitGetChain` → `KeywordLookup` / `KeywordLookupDefault` for `KeywordExpr` keys, else `RT.get`
- Same idea for `emitUnrolledAssocIn` / `emitUnrolledUpdateIn`

Stock `clojure.core/get-in` 2-arity is:

```clojure
([m ks] (reduce1 get m ks))
```

(`src/clj/clojure/core.clj` ~6204). Unroll exists **specifically** to avoid that.

### 2.3 IGV / BgvDump (before any ExprToBytecode workaround)

Dump command (MethodFilter **must** include `*CloffleBytecode*` or guest graphs are silently dropped — see `INSTRUCTIONS-IGV.md`):

```bash
clojure -T:build run-benchmarks \
  :args '["SnippetBenchmark.cloffle" "-p" "name=nested-get-in"
           "-wi" "2" "-i" "1" "-w" "500ms" "-r" "100ms" "-f" "1"
           "-jvmArgsAppend"
           "-Djdk.graal.Dump=:2 -Djdk.graal.PrintGraph=File -Djdk.graal.DumpPath=target/graal-dumps-nested-get-in -Djdk.graal.MethodFilter=*CloffleBytecode*"]'
```

Guest root of the anonymous snippet `(fn [] …)` was:

`TruffleHotSpotCompilation-6553[CloffleBytecodeRootNode[clojure.core_fn--3291]].bgv`

Also dumped: `clojure.core_get-in`, `clojure.core_reduce1`, `clojure.core_get` — proof the **stock** pipeline compiled hot.

| Phase | Finding |
| :--- | :--- |
| After Inline | Remaining CallNodes to `get-in` / `reduce1`; `get` ×12; **zero** `KeywordLookup` / `CreateMap` |
| FinalPartialEscapePhase | Loops (`LoopBegin` 2), `ValuePhiNode`, `CommitAllocation` freq **1.0**, `Object[]` from `InvokeVar3.doClojureClosure` |
| After low tier | `PrefetchAllocateNode` ×3 at freq **0.99**; leftover DirectCalls |

Hot alloc origin:

```java
// CloffleBytecodeRootNode$InvokeVar3.doClojureClosure
return ClojureInterop.unwrapFromPolyglot(
    callNode.call(new Object[]{cachedFn.getCapturedFrame(), a0, a1, a2}));
```

`relativeFrequency` 0.99–1.0 = every op, not a deopt tail.

Write-up already in this tree: `GRAAL_GRAPH_ANALYSIS.md` §19.

### 2.4 Root cause (Compiler, not Graal)

`KeywordExpr extends LiteralExpr`.

`VectorExpr.parse` (`Compiler.java` ~3859):

1. Analyze each element.
2. If **every** element is `LiteralExpr`, build `IPersistentVector rv` of `.val()` and **`return new ConstantExpr(rv)`**.
3. Else return `new VectorExpr(args)` (element **Expr**s preserved).

So `[:user :profile :name]` — the exact case `emitGetChain` was written for — **never** remains `VectorExpr`.

Cloffle (on `a49357f0`, this worktree) still had:

```java
args.nth(1) instanceof VectorExpr
```

in `isGetInCall` / `isGetInStatic` / `isAssocIn*` / `isUpdateIn*`.

Maps have the **same fold**: `MapExpr.parse` all-constant keys+vals → `new ConstantExpr(RT.mapUniqueKeys(a))`. Nested `{:user {:profile {:name "Alice"}}}` is **not** `CreateMap1` in the snippet; it is a load of a compile-time constant map. That is related but **out of scope** unless you need it for PEA of *creating* nested maps. For `nested-get-in`, after unroll the lookups hit an already-constant map; the 3200 B/op was the **get-in/reduce1/InvokeVar Object[]** path, not three ShapeMaps (IGV had no `CreateMap`).

Sets: `SetExpr.parse` similarly returns `ConstantExpr(set)`. Do not "fix" that unless needed.

### 2.5 Workaround in the parent worktree (uncommitted on `feat/assoc-transition-cache`)

**Do not copy this as the end state.** It is a Cloffle-only band-aid that reconstructs `KeywordExpr` / `ConstantExpr` from `ConstantExpr.v`.

It added roughly:

- `isUnrollablePath(Object)` = `VectorExpr` **or** (`ConstantExpr` whose `.v instanceof IPersistentVector`)
- `pathKeyCount` / `pathKeyExprs` / `literalPathKeyExpr` (`Keyword` → `new KeywordExpr(kw)`, else `new ConstantExpr(k)`)
- `emitUnrolledGetIn` / `AssocIn` / `UpdateIn` take `Expr path` and use `pathKeyExprs`
- `countExprLocals` for assoc-in/update-in no longer casts to `VectorExpr`
- Test: `GuestCompilationUnitTest.testNestedGetInLiteralPathInCompiledCode`

Measured **after** that workaround (Cloffle only, `-p name=nested-get-in`, 5+2, `-prof gc`): **~211M ops/s**, **24.0 B/op**.

Why it is not principled:

- `ConstantExpr` is not "a vector." Reconstructing AST from runtime values duplicates `VectorExpr.parse` in reverse and re-`registerConstant`s keys.
- A **marker interface on `ConstantExpr` is wrong**: `42`, `"Alice"`, maps, sets would all be "unrollable paths."
- `instanceof VectorExpr` is already the honest type for non-constant vectors.

Your job: make the **AST** honest so Cloffle can `instanceof ConstantVectorExpr` (and keep element Exprs).

---

## 3. Design: `ConstantVectorExpr`

### 3.1 Shape (recommended)

Place next to `VectorExpr` / `ConstantExpr` in `src/jvm/clojure/lang/Compiler.java`.

Suggested fields (names yours, semantics not):

- `IPersistentVector args` — same as `VectorExpr.args`: analyzed **element `Expr`s**, all `LiteralExpr`.
- `IPersistentVector val` — the folded runtime vector (what `ConstantExpr` used to hold), for `val()` / JVM `emitConstant`.
- `int id` — `registerConstant(val)` if JVM emit still uses the constant pool (match `ConstantExpr`).
- `line` / `column` — same as other exprs.

It **must** `extend LiteralExpr` so:

- `VectorExpr.parse`'s `v instanceof LiteralExpr` checks for *nested* vectors still work if a vector contains a nested literal vector.
- `eval()` can be `return val()`.

Optional: implement a small interface **only this node and `VectorExpr` share**, e.g. `VectorLikeExpr { IPersistentVector elementExprs(); }`, if that simplifies Cloffle. **Do not** put that interface on `ConstantExpr`.

### 3.2 `VectorExpr.parse` change

Today:

```text
all LiteralExpr elements && no meta → ConstantExpr(runtime vector)
else if meta → MetaExpr wrapping VectorExpr
else → VectorExpr
```

Target:

```text
all LiteralExpr elements && no meta → ConstantVectorExpr(element Exprs, runtime vector)
else … unchanged
```

Keep `MetaExpr` wrapping `VectorExpr` (or wrap `ConstantVectorExpr` if that is more accurate for `^:foo [:a :b]` — check existing meta+vector tests). Do not silently drop meta.

### 3.3 JVM `emit` / `eval`

`ConstantExpr.emit` loads a pooled constant. `ConstantVectorExpr.emit` should do the **same** for the runtime vector (`objx.emitConstant(gen, id)`), **not** rebuild via `RT.vector` / `Tuple.create` unless you prove parity.

`VectorExpr.emit` still uses `Tuple.create` / `RT.vector` for **non-constant** vectors.

If any host code does `instanceof ConstantExpr` expecting **all** folded vectors, you must update those sites. Search before coding:

```bash
rg -n "instanceof ConstantExpr" src/jvm
rg -n "ConstantExpr" src/jvm/clojure/lang/Compiler.java src/jvm/net/javacrumbs/cloffle
```

Pay special attention to:

- `ExprToBytecode.convert` — `ConstantExpr` branch (load constant) vs `VectorExpr` branch (`CreateVector0..N`).
- Any `hasJavaClass` / `getJavaClass` (`ConstantExpr` special-cases `APersistentVector`).

### 3.4 Cloffle `ExprToBytecode`

After the AST change:

- Treat `ConstantVectorExpr` like a vector path: use `.args` (element Exprs) directly in `emitGetChain` / assoc-in / update-in.
- `VectorExpr` still for non-literal elements (`[:user x :name]`).
- **Do not** keep `ConstantExpr && v instanceof IPersistentVector` unless you need a **temporary** compatibility path for quoted vectors / other producers of `ConstantExpr(IPersistentVector)`.

**Quoted vectors:** `'[:a :b]` is `quote` → `ConstantExpr` of a vector **without** going through `VectorExpr.parse` (see `ConstantExpr.Parser`). Those are **not** `get-in` paths from source `[:a :b]`. Decide explicitly:

- Either leave quoted vectors as `ConstantExpr` (get-in of a quoted path is rare; unroll may still miss `(get-in m '[:a :b])`).
- Or teach quote of IPersistentVector to produce `ConstantVectorExpr` with reconstructed KeywordExprs — only if tests demand it.

Default: **quoted** stays `ConstantExpr`; **analyzer-built literal vectors** become `ConstantVectorExpr`.

### 3.5 What not to do

- Marker interface on `ConstantExpr`.
- Stopping constant-folding entirely (always `VectorExpr`) without measuring: that would emit `CreateVector*` every time instead of a pooled constant, and can **re-introduce** per-op vector allocation on `nested-get-in` even if lookups unroll.
- Changing `MapExpr.parse` / `SetExpr.parse` in the same PR unless required for a failing test. Nested map literals as `ConstantExpr` are **OK** for `nested-get-in` once the **path** unrolls.
- Dumping IGV with `MethodFilter=*nested-get-in*` only — that drops guest `TruffleHotSpotCompilation` graphs.

---

## 4. Files you will almost certainly touch

| File | Why |
| :--- | :--- |
| `src/jvm/clojure/lang/Compiler.java` | `ConstantVectorExpr` + `VectorExpr.parse` |
| `src/jvm/net/javacrumbs/cloffle/bytecode/ExprToBytecode.java` | `convert`, `isGetIn*`, unroll emitters, `countExprLocals` |
| `src/test/java/net/javacrumbs/cloffle/GuestCompilationUnitTest.java` | compiled `get-in` literal path (`inCompiledCode`) |
| `src/test/java/net/javacrumbs/cloffle/CloffleReproTest.java` | already has get-in / assoc-in correctness |
| `src/test/java/clojure/lang/PersistentShapeMapTest.java` | get-in/assoc-in/update-in snippets |

Also search Cloffle AST visitors / `instanceof VectorExpr` / `instanceof ConstantExpr`.

---

## 5. Tests and verification

### 5.1 Correctness

```bash
export ENV=local
eval "$(direnv export zsh 2>/dev/null)"
clojure -T:build run-tests :fresh false :args '[
  "--select-class=net.javacrumbs.cloffle.GuestCompilationUnitTest"
  "--select-class=net.javacrumbs.cloffle.CloffleReproTest"
  "--select-class=clojure.lang.PersistentShapeMapTest"]'
```

Add / keep a compiled-code test like:

```clojure
(defn nested []
  [(get-in {:user {:profile {:name "Alice"}}} [:user :profile :name])
   (com.oracle.truffle.api.CompilerDirectives/inCompiledCode)])
```

Second invoke must be compiled and return `"Alice"`.

Also test:

- `(get-in m [:a] "nf")` default
- `(get-in m [])` empty path → `m`
- mixed path `(get-in m [:a x :b])` still `VectorExpr`, still correct
- `(assoc-in m [:user :profile :name] "Bob")` / `(update-in m [:a :b :c] * 3)`
- Runtime path binding: `(let [ks [:a :b]] (get-in m ks))` — `ks` is a **local**, not a literal path arg; **must not** pretend to unroll unless the arg expr is the vector node. Correctness > speed.

### 5.2 Performance (this is the product check)

```bash
clojure -T:build run-benchmarks :args '["SnippetBenchmark.cloffle"
  "-p" "name=nested-get-in" "-wi" "5" "-i" "2" "-w" "1s" "-r" "1s" "-f" "1" "-prof" "gc"]'
```

Expect ~**2e8 ops/s** and **~24 B/op** on Cloffle, not 3200.

Optional full suite (slow):

```bash
clojure -T:build compare-performance :output 'target/test-consume.md' :warmup 5 :iterations 2
```

`ring-response` was also slow (~0.09×, 1176 B/op) for **nested map assoc**, not this path-unroll bug. Do not treat it as a ConstantVectorExpr success criterion.

### 5.3 IGV (prove unroll, not just speed)

Follow `INSTRUCTIONS-IGV.md` and `GRAAL_GRAPH_ANALYSIS.md` §3.

After the fix, the snippet guest graph should show:

- `KeywordLookup` present; **no** hot `get-in` / `reduce1` CallNodes after inline
- After low tier: **no** hot `PrefetchAllocate` from `InvokeVar3.doClojureClosure` for this snippet
- `analyze-graal-graph` may still FAIL on 24 B/op residual (JMH/`IFn.invoke` wrapper) — interpret that; 24 B/op matching other ephemeral benches is OK

Use `BgvDump` via `clojure -T:build analyze-graal-graph`, not Ruby seafoam, for CI-style checks.

---

## 6. Environment / repo notes

- Clojure fork + Cloffle Truffle bytecode (`ExprToBytecode` → `CloffleBytecodeRootNode`).
- Build: `clojure -T:build …`. Use `export ENV=local` + `direnv` before commands (project convention).
- Do not edit files outside this worktree.
- Do not commit unless the user asks.
- `registerConstant` has a comment that it is hostile to static AOT; mimic `ConstantExpr` unless you know a better Cloffle-only path.

---

## 7. Suggested implementation order

1. Search all `instanceof ConstantExpr` / `instanceof VectorExpr` consumers.
2. Add `ConstantVectorExpr` with `eval` / `emit` / `val` / `hasJavaClass` matching constant vectors.
3. Change **only** the `else if (constant)` return in `VectorExpr.parse`.
4. Teach `ExprToBytecode.convert` if the new type would otherwise fall through to a generic/unknown expr path.
5. Point get-in/assoc-in/update-in unroll at `VectorExpr` **or** `ConstantVectorExpr` (shared `elementExprs()` if you add an interface).
6. Tests, then JMH `nested-get-in`, then optional IGV.
7. Update `GRAAL_GRAPH_ANALYSIS.md` §19 verdict: unroll now has an honest AST node; delete the "require VectorExpr" sentence.

---

## 8. Pointers

| Doc / code | Use |
| :--- | :--- |
| `INSTRUCTIONS-IGV.md` | MethodFilter trap, 3 phases, 5-step alloc diagnosis |
| `GRAAL_GRAPH_ANALYSIS.md` §19 | Pre-fix IGV findings |
| `PARTIAL_ESCAPE_ANALYSIS.md` | Constant path unroll intent |
| `src/jvm/clojure/lang/Compiler.java` `VectorExpr.parse` | Fold to replace |
| `src/jvm/net/javacrumbs/cloffle/bytecode/ExprToBytecode.java` | Unroll + convert |
| `src/jvm/net/javacrumbs/cloffle/bytecode/CloffleBytecodeRootNode.java` `InvokeVar3.doClojureClosure` | Pre-fix alloc |
| `src/benchmark/java/.../SnippetBenchmarkSupport.java` | `NESTED_GET_IN` |
| Parent worktree uncommitted `ExprToBytecode.java` | Workaround to **supersede**, not copy |

If something in this brief conflicts with `HEAD` after you pull, trust the code and keep the **AST honesty** constraint: constant literal vectors are not generic `ConstantExpr`.

---

## 9. Implementation Summary (What Was Done and Why)

### Why

In stock Clojure, literal vector expressions whose elements are all `LiteralExpr` (e.g. `[:user :profile :name]`) were folded in `VectorExpr.parse` to `new ConstantExpr(rv)`:
- This type-erased "this is an analyzed vector of keys" into an opaque `ConstantExpr` containing a runtime `IPersistentVector`.
- Cloffle's bytecode unroll optimization for `get-in`, `assoc-in`, and `update-in` specifically checked `instanceof VectorExpr`, so the common case `(get-in m [:user :profile :name])` never unrolled.
- As a result, execution fell through to stock `clojure.core/get-in` → `reduce1` → `get`, suffering a massive performance cliff (~1.53M ops/s and 3,200 B/op due to un-escaped `InvokeVar` argument arrays).

The prior workaround (`15954e16`) left `Compiler.java` untouched and attempted to reconstruct synthetic `KeywordExpr` AST nodes from runtime values in `ConstantExpr.v` inside `ExprToBytecode`. That was an unprincipled band-aid because:
1. It blurred the semantics of `ConstantExpr` (treating any `ConstantExpr` holding a vector as a vector path).
2. It reverse-engineered AST nodes from runtime values, duplicating `VectorExpr.parse` in reverse.
3. It allocated fresh synthetic expressions on every bytecode compilation pass.

### What Was Done

1. **Introduced `Compiler.VectorLikeExpr` & `Compiler.ConstantVectorExpr`** (`src/jvm/clojure/lang/Compiler.java`):
   - Defined `public static interface VectorLikeExpr extends Expr { IPersistentVector args(); }`, shared by `VectorExpr` and `ConstantVectorExpr`.
   - Implemented `ConstantVectorExpr extends LiteralExpr implements VectorLikeExpr`:
     - Holds the analyzed element expressions (`public final IPersistentVector args`) as well as the folded runtime vector (`public final IPersistentVector val`).
     - Preserves full JVM Clojure semantics: implements `emit(...)` via `objx.emitConstant(gen, id)` (constant pool load) and `eval()` returning `val`.
     - Extends `LiteralExpr` so nested literal vectors (e.g. `[[:a :b]]`) fold recursively without losing AST honesty.
   - Updated `VectorExpr.parse`: when all elements are literal and no metadata is attached, returns `new ConstantVectorExpr(args, rv)` instead of `new ConstantExpr(rv)`.

2. **Updated Cloffle Bytecode Lowering** (`src/jvm/net/javacrumbs/cloffle/bytecode/ExprToBytecode.java`):
   - Changed path detection (`isGetInCall`, `isGetInStatic`, `isAssocInCall`, `isAssocInStatic`, `isUpdateInCall`, `isUpdateInStatic`) to check `instanceof VectorLikeExpr`.
   - Updated unroll emitters (`emitUnrolledGetIn`, `emitUnrolledAssocIn`, `emitUnrolledUpdateIn`) to accept `VectorLikeExpr` and use `pathExpr.args()` directly to emit `KeywordLookup` / `KeywordAssoc` chains.
   - In `convert`: added a direct handler for `ConstantVectorExpr` delegating to `emitConstantValue(cve.val, b)`.
   - In `countExprLocals`: updated vector traversal to use `VectorLikeExpr.args()`.

3. **Source Spans & Tooling** (`src/jvm/net/javacrumbs/cloffle/ast/ExprSourceSpans.java`):
   - Added line/column extraction for `ConstantVectorExpr`.

4. **Testing and Verification**:
   - Added `GuestCompilationUnitTest.testNestedGetInLiteralPathInCompiledCode` verifying compiled Truffle execution of literal nested `get-in`.
   - Added tests in `CloffleReproTest` covering `get-in` with defaults, empty vector paths `[]`, mixed literal/local paths (`[:a x]`), `update-in`, and runtime-bound vector paths.
   - Verified benchmarks (`SnippetBenchmark.cloffle` `-p name=nested-get-in`):
     - **Before:** ~1.53M ops/s, 3,200 B/op
     - **After:** **211.76M ops/s**, **24.00 B/op** (138× speedup, 133× allocation reduction).
   - Verified Graal compilation graph (`analyze-graal-graph` on BGV dump):
     - Confirmed `PASS` with 0 allocations in PEA and low-tier phases; all `get-in` / `reduce1` calls completely eliminated.
   - Verified compatibility suites (`compat-test`): 100% identical pass rate with Maven Clojure across Cheshire, Ring, Compojure, clj-http, and Hiccup.
