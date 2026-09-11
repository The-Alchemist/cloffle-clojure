# Tuple / PersistentTuple work

Binary compatibility, old bytecode, and the public `clojure.lang.Tuple` API are **out of scope**. `Tuple` was an indirection; `PersistentTuple` is the type that matters for PEA.

## Goal

Small vectors (0–8, and growth up to 8) should stay on the `@ValueType` `PersistentTupleN` ladder so Graal PEA can scalar-replace them. Overflow at 9 stays `PersistentVector`.

## Status

Steps 1 and 2 are **done**, and so are both follow-on performance problems (§"Guest tuple ops were slow" below). `Tuple.java` is deleted, the compiler emits `PersistentTuple.create` with concrete `PersistentTupleN` return types, growth from empty enters the ladder, and guest destructuring is fully scalar-replaced.

Landed as:

| Commit | What |
|---|---|
| `8d51e6c4` | delete the `Tuple` facade, grow empty vectors into tuples (§1, §2) |
| `43af52d0` | dispatch primitive-signature static methods via MethodHandle (unblocks `RT.nth`) |
| `e84ddebd` | clear `let*` bindings the body cannot read (unpins the tuple) |
| `494c67a0` | `cloffle.ClearDeadLocals` context option |
| `428cd3b4` | DAP scope test + `:alloc-budget 0` for the snippet |

Verification at `428cd3b4`:

- `run-tests`: 928 passed, 0 failed (the extra test over earlier runs is the new DAP one).
- `run-clj-tests`: 636 tests / 19026 assertions, 0 failures.
- `compat-test`: all projects identical to stock Clojure except one pre-existing `:reitit` error, `reitit.walk-test/keywordize=walk-keywordize`. It is a `long overflow` inside `test.check`'s `JavaUtilSplittableRandom.split`, i.e. the `*unchecked-math*` regression tracked in `TODO_reflection_math.md` §2, and it reproduces identically on a clean baseline.
- `check-scalar-replacements :suite :guest`: everything passes except `KeywordMapBenchmark.guestShapeMapEphemeralPipeline` at 152 B/op against a 24 B/op budget. That one is pre-existing — the guest fn is a pure map pipeline with no vectors, and a stashed baseline measures the same 152 B/op. It is tracked in [`FIXME_shape_map_alloc.md`](FIXME_shape_map_alloc.md) and still measures 152 B/op after the `MapShape` simplification, so it remains independent of everything in this file.
- `check-scalar-replacements :filter "uple"`, 8/8:

  | Check | B/op | Budget |
  |---|---|---|
  | `PersistentTypeScalarReplacementBenchmark` ×5 (incl. `tuple2ConsThenNth`, `tuple2AssocNThenNth`, the paths §2 touched) | 0.0 | 0 on `baselineTuple2`, none on the rest |
  | `KeywordMapBenchmark.guestTupleDestructure` | 32.0, was 416 | none |
  | `KeywordMapBenchmark.guestTuple2Transform` | 32.0, was 608 | none |
  | `SnippetBenchmark.cloffle` `tuple-destructure` (181.9M ops/s) | **0.0** | **0** |

  Six of the eight have no `:alloc-budget` and therefore cannot fail; only the snippet gates the destructuring win. Run `record-alloc-budgets :missing true` to pin the rest.

Observed classes after the change:

| Expression | Before | Now |
|---|---|---|
| `[]` | `PersistentVector` | `PersistentVector` (unchanged by design) |
| `[1]` | `PersistentTuple1` | `PersistentTuple1` |
| `(conj [] 1)` | `PersistentVector` | `PersistentTuple1` |
| `(conj [] 1 2)` | `PersistentVector` | `PersistentTuple2` |
| `(assoc [] 0 :a)` | `PersistentVector` | `PersistentTuple1` |
| `(vec (list 1 2))` | `PersistentTuple2` | `PersistentTuple2` |
| `(into [] [1 2])` | `PersistentVector` | `PersistentTuple2` |
| 9th `cons` | `PersistentVector` | `PersistentVector` |

## 1. `Tuple.java` deleted — done

`clojure.lang.Tuple` was a pure facade that widened every concrete `PersistentTupleN` back to `IPersistentVector`. `MAX_SIZE` and the 0-arity `create()` moved to `PersistentTuple`; `createFromArray` / `createFromColl` already lived there. `PersistentTuple.EMPTY` (previously dead) is now what 0-arity `create()` returns.

Retargeted:

- `Compiler.java` — `TUPLE_TYPE` is `PersistentTuple`, and `createTupleMethods` now names the **concrete** return types (`clojure.lang.PersistentTuple$PersistentTuple1 create(Object)`, …). The exact type at the call site is what lets PEA scalar-replace. Verified in emitted bytecode: `(defn f [a b] [a b])` compiles to `invokestatic clojure/lang/PersistentTuple.create:(LObject;LObject;)Lclojure/lang/PersistentTuple$PersistentTuple2;`.
  - Both emit sites moved: `VectorExpr.emit` and constant emission in `emitValue`. `VectorExpr.parse` already built constants with `PersistentTuple.createFromArray`.
  - `emitValue` still reaches `createTupleMethods[0]` for a constant `[]`, so the 0-arity `create()` has to stay.
  - Dead `EmptyExpr.TUPLE_TYPE` / `EmptyExpr.IVECTOR_TYPE` removed.
- `LazilyPersistentVector.createOwning` / `create` (backs `RT.vector`, `vec`, small counted `into`).
- `BytecodeCreateVector.create1`–`create8`. `create0` already returned `PersistentVector.EMPTY`.
- `PersistentTupleTest`, `TupleVectorBenchmark`, `PersistentTypeScalarReplacementBenchmark`.
- `test_clojure/method_thunks.clj` and `param_tags.clj` used `Tuple/create` only as a convenient static overload set (including the 0-arity for `^[] Tuple/create`); they now use `PersistentTuple/create`.

## 2. Empty growth — done, no `PersistentTuple0`

`PersistentTuple0` is **not** a PEA win: zero fields do not scalar-replace. Empty stays the `PersistentVector.EMPTY` singleton and `[]` still compiles to `GETSTATIC PersistentVector.EMPTY`.

What changed is growth *out of* empty. `PersistentVector.cons` now returns `PersistentTuple.create(val)` when `cnt == 0` (or `new PersistentTuple1(_meta, val)` when the empty vector carries meta, so `(meta (conj (with-meta [] {:a 1}) 1))` is preserved). `assocN` at `i == cnt == 0` routes through the same path.

Because of that, `cons` and `assocN` widened from `PersistentVector` to `IPersistentVector`. Internal builders that need the concrete type use the new package-private `PersistentVector.consVector` / `assocNVector`, which keep the old behavior:

- `Compiler` and `LispReader` analyzer accumulators (arg vectors, binding inits, loop locals, catch clauses, syntax-quote scratch). These are host builders, not guest values.
- `Compiler.registerConstant` — `CONSTANTS` is cast to `PersistentVector` in four places.
- `PersistentQueue`'s rear vector, whose field is typed `PersistentVector`.

`VectorExpr.eval` still loops `cons` from `PersistentVector.EMPTY`, but that now produces tuples for 1–8 elements, so interpreted vector literals land on the ladder too.

### If empty ever needs to be a tuple

Only worth it if Truffle specializations want `instanceof PersistentTuple` to mean “0–8, not overflow”. Then **one** empty singleton has to replace every site that currently returns `PersistentVector.EMPTY` for an empty tuple result: `PersistentTuple.create()` / `EMPTY`, `empty()`, `drop` past the end, `createFromArray` length 0, `createFromColl` length 0 / nil seq, `PersistentTuple1.pop()`, `EmptyExpr` for vectors, `BytecodeCreateVector.create0()`. Register `print-dup` on the concrete class the way Tuple1–8 are registered in `core_print.clj`.

Do **not** add `PersistentTuple0` without also switching `[]` to it — that just splits empty identity. Do **not** convert the internal `PersistentVector.EMPTY` builders above; they are host state.

## Guest tuple ops were slow for an unrelated reason — fixed generically

**Fixed** by widening the static-method fast path instead of re-adding a `nth` intrinsic. `BytecodeStaticMethod.computeMethodHandle` used to reject any method with a primitive parameter or return, and `RT.nth(Object,int)` has one, so every `nth` fell through to `BytecodeInterop.staticMethod` — a `@TruffleBoundary` reflective call with an argument array, which is an unconditional PEA barrier. It now adapts primitive parameters with a `Reflector.boxArg` filter and lets `asType` box a primitive return.

This is not `nth`-specific: it puts every primitive-signature host static call on the MethodHandle path.

| Snippet | Before | After this fix |
|---|---|---|
| `tuple-destructure` | 12.3M ops/s, 384 B/op | 159M ops/s, 32 B/op |
| `tuple2-transform` | 8.9M ops/s | 55M ops/s, 64 B/op |
| `nested-get-in` | 4.9M ops/s | 36.9M ops/s |
| `ephemeral-pipeline` | 20.7M ops/s | 54.6M ops/s |
| `(clojure.lang.RT/nth v 0)` direct | — | 234M ops/s, **0 B/op** |

The residual 32 B/op in that table was a second, unrelated problem; it is closed below.

Two details that matter:

- `CompilerDirectives.inCompiledCode()` must stay off the fast path. It is a compiler probe substituted at its own call site, and a MethodHandle adapter hides that, so it silently reported `false` and broke every `GuestCompilationUnitTest` compiled-code assertion. `BytecodeInterop.staticMethod` special-cases it, so `computeMethodHandle` now excludes `CompilerDirectives`.
- Argument coercion failures must throw `ClassCastException`, not `IllegalArgumentException`. `invokeReflective` converts one to the other, and `param_tags` / `method_thunks` assert it.

### The remaining 32 B/op — a dead frame local, closed

It was not the Var call boundary: the seafoam dump of `guest-tuple-destructure` shows both `Object[4]` argument arrays and all four `FrameWithoutBoxing` instances eliminated at `FinalPartialEscapePhase`, so `clojure.core/nth` does inline. The single hot survivor was the `PersistentTuple2` itself, and walking its edges found it feeding a `ValuePhiNode` on the `continueAt` dispatch loop's `LoopBeginNode` — a loop phi merging `null` with the tuple, which PEA cannot keep virtual.

The loop is inherent, not a missed fold. `MERGE_EXPLODE` merges two dispatch-loop iterations whose whole interpreter state matches (`GraphDecoder.handleLoopExplosionBegin` / `LoopExplosionState`), and Truffle's own javadoc says merging "can introduce loops again". Any guest `if` whose arms join produces one. What made the tuple part of that state is that the destructuring temp `vec__` stays in its frame slot for the rest of the `let` body even though only the `nth` inits read it — the Bytecode DSL already clears consumed *stack* slots in `handleBranchFalse` for exactly this reason, but locals are root-scoped here (see `fillRootLocalPool`) and never cleared.

Fix: `ExprToBytecode.clearBindingsDeadInBody` emits `ClearLocal` for `let*` bindings the body cannot read, on both the value path and `emitLetExprAsLoopTail` (the tail-position `let` has its own emitter; missing it is why the first attempt changed nothing). `ExprToBytecodeLocals.collectReadBindings` computes the reads and counts a binding captured by an inner `fn*` as read, because Cloffle closures read the parent frame when invoked rather than copying at creation. It bails out on unrecognized expression types so an unknown node is never read as "reads nothing".

| Measurement | Before | After |
|---|---|---|
| `SnippetBenchmark.cloffle` `tuple-destructure` | 158M / 32 B/op | 181M / **0 B/op** |
| `KeywordMapBenchmark.guestTupleDestructure` | 64 B/op | 32 B/op |

`guestTupleDestructure` still reports 32 B/op even though its guest compilation unit is clean: at `FinalPartialEscapePhase` the `PersistentTuple2` is in the *eliminated* list and the only survivors are two `ClassCastException` rematerializations on `coerceArg`'s deopt path at `relativeFrequency 0.0`. Per the "does the graph account for the bytes" check in [HOWTO_SEAFOAM.md](HOWTO_SEAFOAM.md), those bytes are outside the graph — most likely the host harness boxing its arguments. The snippet, which has no host wrapper, measures 0.

Clearing is controlled by the `cloffle.ClearDeadLocals` context option (default on, declared in `Clojure.getOptionDescriptors`). A cleared binding reads as nil in the debugger for the whole body, so REPL and debugger contexts turn it off; `CloffleEvalTestSupport.newDebuggerContext` does that for `DapTest`, and `DapTest.bindingDeadInBodyStaysVisibleWithDap` fails if the option stops taking effect. The value is read once per context in `createContext` and passed to `ExprToBytecode`, whose two-argument constructor was removed so each of the seven call sites states its own answer — host and build-time callers pass `true`. Two consequences worth remembering: `areOptionsCompatible` keeps contexts that disagree from sharing parsed code on one engine, and `clojure.core` is archived with clearing on, so stepping into core sees cleared temps regardless of the option.

`run-tests`, `run-clj-tests`, and the rest of the `:guest` catalog are unchanged. `guestShapeMapEphemeralPipeline` (152 vs 24 B/op) and `reitit.walk-test/keywordize=walk-keywordize` both fail identically with the change stashed.

### Original diagnosis

The tuple *types* are fine — every host tuple benchmark scalar-replaces at 0 B/op. What is slow is guest code that reads a tuple, and it is not caused by anything in this file.

Sequential destructuring expands to `(nth v 0 nil)`. Commit `a08ab505` ("Remove hardcoded RT and Util method bytecode intrinsics") deleted `isRtNthMethod` from `ExprToBytecode`, which used to lower `RT.nth` into the `VectorNth2` / `VectorNth3` bytecode ops. Without it the call leaves the bytecode interpreter as a generic static-method invoke, the tuple escapes, and PEA gives up.

Bisected on `SnippetBenchmark.cloffle` with `name=tuple-destructure`:

| Commit | ops/s | B/op |
|---|---|---|
| `cba6894e` (parent of the intrinsic removals) | 262M | ≈0 |
| `0af1e162` (core-fn intrinsics removed) | 232M | — |
| `a08ab505` (**RT/Util intrinsics removed**) | 11.9M | — |
| `8d51e6c4` (this work) | 12.3M | 384 |

`60816999` ("Remove `:inline` expansion") compounds it: `nth` no longer rewrites to a `StaticMethodExpr` on `RT.nth` at all, so the call now also goes through the `clojure.core/nth` Var. See `TODO_reflection_math.md` §2.

Note the numbers in `benchmark-results.md` for `tuple-destructure` (235M) and `tuple2-transform` (262M) are stale: they were last measured at `fa53d1b9`, and `0408da4e` edited that file without re-running those rows.

A separate regression found the same way: `keyword-invoke` went 237M → 95.7M at `a73cbecc` ("Extract MapShape and pre-build shapes at analysis time"), and 102M at `428cd3b4`. **Now fixed** at 243.7M ops/s / 0 B/op — every keyword-map literal had been rebuilding its `MapShape` through a `@TruffleBoundary` on each execution. See [`FIXME_keyword_invoke_perf.md`](FIXME_keyword_invoke_perf.md).

## 3. Transients reach the tuple ladder — done

`TransientVector.persistent()` returns `PersistentTuple.createFromArray` for `cnt` 1–8 (and `PersistentVector.EMPTY` at 0); `cnt` 9+ unchanged. Short `PersistentVector.create(ISeq)` / `create(List)` for size ≤ 8 use the same ladder. `(into [] [1 2])` is `PersistentTuple2`.

`PersistentTuple.asTransient()` still routes through `PersistentVector.EMPTY.asTransient()` before `conj`; round-trip back to a tuple now works via the updated `persistent()`.

**Alloc on `(into [] …)`:** `RT.into` fast-paths empty `IPersistentVector` + `Counted` `from` with `count ≤ 8` via `PersistentTuple.materializeFromCounted` (no transient/`conj!`/`reduce`). Tier-3 `:cloffle/unchecked-op` on `#'into` rewrites call sites to `RT.into`. Snippet **`into-empty-tuple2`** was ~5432 B/op before that bypass; ~496 B/op after (still not `tuple-destructure`-class 0 — follow-ups: constant fold, optional `:cloffle/op` lowering).

**Why `into-empty-tuple2` does not PEA to 0 B/op (2026-09-10):**

| Snippet | Analyze / bytecode | Measured |
|---------|-------------------|----------|
| `tuple-destructure` | `[:first :second]` → `ConstantVectorExpr`; destructure reads virtual tuple / scalars | **0 B/op** |
| `into-empty-tuple2` | `(into [] [:first :second])` → tier-3 `StaticMethodExpr` **`RT.into` returning `Object`**; each iteration **`materializeFromCounted`** builds a fresh `PersistentTuple2` | **~496 B/op** |

The fast path removed transients and Vars, but the hot loop still **heap-materializes** a tuple through a generic static call. Graal PEA scalar-replaces `PersistentTuple2` when the **concrete** `PersistentTuple.create` / constant-vector path is visible and the value does not escape (see §1 `createTupleMethods` return types). `RT.into` erases that to `Object`/`IPersistentVector`, so the result is treated as escaping; ~496 B/op matches one small object per op. **`explain-allocations :snippet`** now sets `:guest-hint` to `snippet-<name>` and passes **`-Dcloffle.bench.nameGuestFn=true`** on dumps so IGV targets the snippet root (e.g. `clojure.core_snippet-into-map-small--…`).

**`(into [] (map identity small-vector))` probe (`into-map-small`):** **`tryConstantFoldMapIdentity`** + **`tryConstantFoldRtIntoStaticMethod`** (2026-09-10) — **`into-map-small`**, **`into-empty-tuple2`**, and map probes at **0 B/op**, ~**240M ops/s** where destructure allows PEA.

**Smaller-than-`map` ladder (keywords):** isolates **`#'map`** from plain collection + Var work.

| Snippet | Form (roughly) | B/op | ops/s |
|---------|----------------|------|-------|
| `tuple-destructure` | `let [[a b] [:first :second]] …` | ~0 | ~185M |
| `ladder-identity-keyword` | `(identity :one)` | **0** | ~163M |
| `ladder-nth5-keywords` | `(nth [:one … :five] 4)` | **0** | ~152M |
| `ladder-first5-keywords` | `(first [:one … :five])` | **0** | ~161M |
| `map-first-one` | `(first (map identity [:one]))` | **0** | ~240M |
| `map-first-small` | `(first (map identity …))` five keywords | **0** | ~240M |
| `map-small-vector` | `(map identity …)` + destructure | **0** | ~182M |

**Constant fold (landed):** **`Compiler.tryConstantFoldMapIdentity`** — **`(map clojure.core/identity <literal vector ≤8>)` → `ConstantVectorExpr`**. **`InvokeExpr.tryConstantFoldRtIntoStaticMethod`** — **`(into [] <literal vector ≤8>)` / `RT.into` on same** → **`ConstantVectorExpr`**. Tests: **`MapIdentityConstantFoldTest`**, **`IntoEmptyTuple2AnalyzeTest`**, **`IntoCallSiteRewriteIntrospectionTest`**.

**Prior BGV diagnosis (pre-fold):** hot cost was **`InvokeVar2`/`#'map`**, **`LazySeq`**, literal tuple escape — not missing **`EphemeralVectorSeq`** at runtime.

**Next levers:** non-literal **`(map identity coll)` → `EphemeralVectorSeq.create`** at analyze time; dynamic **`(into [] coll)`** still uses **`RT.into`**. Snippet catalog: map/into literal probes at **0 B/op**.
