# `nth` — tier-3 call-site rewrite done; bytecode `:cloffle/op` lowering still rejected

`#'clojure.core/nth` carries **tier-3** `:cloffle/unchecked-op` metadata (`:checked-method`
`clojure.lang.RT/nth`, arities 2–3) so destructuring’s `(nth v i nil)` analyzes to
`StaticMethodExpr` → primitive `MethodHandle` (`43af52d0`), not `InvokeVar` on the Var.

Allocations were already **0 B/op** (PEA + dead-local clearing). Throughput on chained
destructure (`tuple2-transform`, `guestTuple2Transform`) was the open problem; see measured
before/after below.

The `with-redefs` trapdoor that used to make `nth` unredefinable is **fixed** — see
[`FIXME_with_redefs_trapdoor.md`](FIXME_with_redefs_trapdoor.md). This file is about **lowering and
performance** only.

## What exists today (primitives and call-site lowering)

Several layers landed after the original `VectorNth` revert; they are easy to confuse:

| Layer | Commit / location | Role for `nth` |
| --- | --- | --- |
| **Primitive-signature static calls** | `43af52d0`, `BytecodeStaticMethod` / `BytecodeInterop` | Once the analyzer emits `(. clojure.lang.RT (nth coll i))` as a `StaticMethodExpr`, the index stays a primitive `int` end-to-end via `MethodHandle` (`RT.nth(Object, int)`). |
| **`:cloffle/op` numeric bytecode** | `3dc22a14`, `ExprToBytecode` + `CloffleBytecodeRootNode` `Numbers*` ops | `+`, `inc`, compares, etc. specialize on `long`/`double`; guarded by `Var.loweringRoot` so `with-redefs` falls back to `InvokeVar`. |
| **`:cloffle/unchecked-op` + `:checked-method`** | `40835786`, `Compiler.uncheckedMathForm` (`Compiler.java` ~7860) | Stock-like **analyze-time** rewrite of call sites to host static calls (casts, `aget`/`aset` → `RtAget`/`RtAset`, `intCast`, many bit/math helpers). Documented as three tiers in `core.clj` ~931–938. **Not** general `:inline`; the compiler still has no `:inline` expansion. |
| **`NumbersNth` / `NumbersCount` ops** | `CloffleBytecodeRootNode` (~3576), `ExprToBytecode` `OP_NUMBERS_NTH` | Implemented in the bytecode DSL but **not** attached to `#'nth` or `#'count` — see rejections below. |
| **Dead `let*` locals** | `e84ddebd`, `cloffle.ClearDeadLocals` | Stops destructure temps from pinning tuples on the dispatch-loop phi; **0 B/op** on tuple snippets. |

Before the tier-3 metadata, destructuring’s `(nth v i nil)` stayed on **`InvokeVar`** (no
`:inline`, no `:checked-method`). Seafoam then showed `guest-tuple2-transform` with ~6× larger
low-tier graphs and ~25× more `continueAt` sites than single destructure — same one
`LoopBeginNode`, more work per trip.

## Rejected: `VectorNth2` / `VectorNth3` via `:cloffle/op` (2026-09-09)

`VectorNth2` / `VectorNth3` on `#'nth`, emitted like `KeywordAssoc`.

| Snippet | ops/s before | ops/s after | B/op before | B/op after |
| --- | --- | --- | --- | --- |
| `nth-literal` | ~120M | **7.4M** | 40.0 | **392.0** |

Bisected by deleting *only* the `:cloffle/op` metadata: throughput returned to ~120M. Cause:
bytecode operands are `Object`; the index boxes as `Long` and unboxes via `RT.intCast` — structural,
not tunable.

> **Rule:** `:cloffle/op` is for **reference-keyed** operations. Do not use it when the hot operand
> or result is numeric; prefer `StaticMethodExpr` + primitive `MethodHandle` (or tier-3 host rewrite).

Revert / diagnosis: `a78dcbcd`, `ea1df2f3`. Narrative: `TODO_lowering_layer.md` Phase 2 step 2.

## Rejected: `NumbersNth` on `#'nth` via `:cloffle/op` (2026-09-09, `3dc22a14`)

The numeric-op work wired `NumbersNth` in `ExprToBytecode` but **deliberately left `nth` and `count`
without `:cloffle/op`**: enabling them triggered a Graal **too-deep-inlining** bailout and regressed
the benchmark (different failure mode than `VectorNth` boxing). The `NumbersNth` operation remains
in `CloffleBytecodeRootNode` as an unwired experiment; `FIXME_nth.md` in the commit message is the
source of truth for that decision.

`count` was skipped for the same reason (`RT.count` returns primitive `int`; a bytecode op would box
every result).

## Tier-3 rewrite (landed)

`src/clj/clojure/core.clj` on `#'nth`:

```clojure
:cloffle/unchecked-op {:method "clojure.lang.RT/nth"
                       :checked-method "clojure.lang.RT/nth"
                       :min-arity 2 :max-arity 3}
```

Gate: `NthCallSiteRewriteIntrospectionTest.nthThreeArgRewritesToRtStaticMethod`.

**JMH (same machine, `-wi 5 -i 5`, Sep 2026) — metadata off vs on:**

| Benchmark | Before (ops/s) | After (ops/s) | Ratio |
| --- | ---: | ---: | ---: |
| `SnippetBenchmark.cloffle` `tuple2-transform` | 57.7M | **251M** | **4.3×** |
| `SnippetBenchmark.cloffle` `tuple-destructure` | 197M | 198M | ~1× |
| `KeywordMapBenchmark.guestTuple2Transform` | 52M | **157M** | **3.0×** |
| `KeywordMapBenchmark.guestTupleDestructure` | 133M | 137M | ~1× |

`count` is still unwired; try the same tier-3 pattern only after measuring (same inlining concern as
`NumbersNth`).

**Do not** re-enable `VectorNth2`/`3` or `:cloffle/op {… :cloffle.op/NumbersNth}` on the Var without a full
JMH + seafoam re-baseline.

## Bootstrap `first` / `next` / `rest` / `seq` (tier-3, provisional)

Same `:cloffle/unchecked-op` + `:checked-method` pattern on the bootstrap `def`s in
`core.clj` (~49–151): `RT/first`, `RT/next`, `RT/more` (`rest`), `RT/seq`. Gate:
`SeqCallSiteRewriteIntrospectionTest`.

**JMH (`SnippetBenchmark.cloffle`, `-wi 5 -i 5`, Sep 2026) — metadata off vs on:**

| Benchmark | Before (ops/s) | After (ops/s) | Notes |
| --- | ---: | ---: | --- |
| `lazy-seq-first` | 43.6M | **49.8M** | ~14% |
| `lazy-seq-vec-first` | 43.8M | 46.5M | ~6% |
| `hiccup-normalize` | 15.8M | 15.8M | ~flat (`nth` already tier-3) |
| `tuple2-transform` | 252M | 238M | ~noise |
| `KeywordMapBenchmark.guestLazySeqFirst` | 22M | 25M | ~14% |
| `conj-chain` | — | ~18.3M | unchanged vs `benchmark-results.md`; dominated by `conj`, not seq ops |

ROI is smaller than `nth` on destructure-heavy snippets; still worthwhile for lazy-seq and anything
that hammers `first`/`seq` on the Var path.

## Gates and probes

| Check | Notes |
| --- | --- |
| `SnippetBenchmark` `tuple-destructure` | `:alloc-budget 0` in `build.clj` (~1980) |
| `SeqCallSiteRewriteIntrospectionTest` | `first` / `next` / `rest` / `seq` → `StaticMethod1` |
| `KeywordMapBenchmark.guestTupleDestructure` / `guestTuple2Transform` | PEA catalog; **throughput** gap ~2.5–3× is visible here too |
| `nth-literal`, `nth-chain`, … | Deleted with the `VectorNth` revert; no dedicated throughput ratchet for `nth` |
| `dev/compare-snippet-graphs.clj` | Optional: `continueAt` / node counts on named `.bgv` dumps |

Refresh `benchmark-results.md` after meaningful `nth` changes; the Sep 2026 run shows `tuple2-transform`
at **0.11×** Clojure throughput vs **0.37×** for `tuple-destructure`.

## `with-redefs` (historical)

Before `root-bind` used host `Iterator` + `IMapEntry` (`core.clj` ~7665), redefining `nth`/`first`/
`seq` broke restore because destructuring and `doseq` compiled to those Vars. That is **fixed**;
details and stock vs fork analysis live in [`FIXME_with_redefs_trapdoor.md`](FIXME_with_redefs_trapdoor.md).

## Pointers

| Item | Location |
| --- | --- |
| Fork `nth` (no `:inline`, no lowering metadata yet) | `src/clj/clojure/core.clj` ~866 |
| Three-tier lowering comment | `src/clj/clojure/core.clj` ~931–938 |
| `uncheckedMathForm` (tier 3) | `src/jvm/clojure/lang/Compiler.java` ~7860 |
| `NumbersNth` (unwired on Var) | `CloffleBytecodeRootNode.java` ~3576, `ExprToBytecode.java` `OP_NUMBERS_NTH` |
| Primitive static `MethodHandle` | `BytecodeStaticMethod.java`, commit `43af52d0` |
| `RT.nth(Object, int)` | `src/jvm/clojure/lang/RT.java` ~1133 |
| Numeric `:cloffle/op` + `nth`/`count` exclusion | commit `3dc22a14` |
| `:checked-method` host rewrite batch | commit `40835786` |
| Tuple / dead-local PEA | `TODO_tuple.md`, `e84ddebd` |
| Full lowering narrative | `TODO_lowering_layer.md` |
| Trapdoor (fixed) | [`FIXME_with_redefs_trapdoor.md`](FIXME_with_redefs_trapdoor.md) |
| Stale graph doc mentioning `VectorNth` | `PARTIAL_ESCAPE_ANALYSIS.md` (not current) |
