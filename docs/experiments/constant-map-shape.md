# ConstantMapExpr MapShape experiment

Attach compile-time `MapShape` to `ConstantMapExpr` and lower nested constant keyword maps via `CreateMapShapedN`.

## Environment (baseline)

- **Date:** 2026-09-10
- **Git:** `4083578627e86015f8bda9118d4bcba433cb4983` (+ benchmark guest fns only; **before** ConstantMapExpr shape fields)
- **JVM:** OpenJDK 25.0.4.1 (GraalVM 25.3.4.1)

## Hypotheses (before → after)

| Benchmark | Prediction | Rationale |
|-----------|------------|-----------|
| `guestConstNestedHeaders` | Small throughput win; alloc unchanged or lower | Inner `ConstantMapExpr` uses unshaped `CreateMap2` today |
| `guestAllConstNested` | Clearer throughput win; alloc may drop toward 0 B/op | Both levels `ConstantMapExpr` → unshaped `CreateMapN` |
| `guestRingResponsePipeline` | Throughput flat or small win; alloc stay low | Nested constant headers; PEA may already hide much |
| `guestConstIntKeyMap` | No change; no `CreateMapShaped*` | Keyword-only invariant |

## Results — Before (pre-optimizer)

| Benchmark | ns/op | B/op | Notes |
|-----------|------:|-----:|-------|
| `KeywordMapBenchmark.guestConstNestedHeaders` | 7.41 | 88.0 | |
| `KeywordMapBenchmark.guestAllConstNested` | 6.04 | 64.0 | |
| `KeywordMapBenchmark.guestConstIntKeyMap` | 102.3 | 392.0 | Negative control |
| `KeywordMapBenchmark.guestRingResponsePipeline` | 6.77 | 24.0 | |

## Results — After (post-optimizer)

Same JVM; worktree includes ConstantMapExpr `shape`/`shape16`, `MapLikeExpr` accessors, shared `emitCreateMap(MapLikeExpr, …)`.

| Benchmark | ns/op (before → after) | B/op (before → after) | Δ ns/op |
|-----------|------------------------|------------------------|--------:|
| `guestConstNestedHeaders` | 7.41 → 7.53 | 88.0 → 88.0 | +1.6% (noise) |
| `guestAllConstNested` | 6.04 → 5.92 | 64.0 → 64.0 | −2.0% |
| `guestConstIntKeyMap` | 102.3 → 11.6* | 392.0 → ~0* | N/A |
| `guestRingResponsePipeline` | 6.77 → 6.91 | 24.0 → 24.0 | +2.1% (noise) |

\*Baseline `guest-const-int-key-map` used `(:a m)` on `{1 :a}` (miss lookup); corrected to `(get m 1)` in the same branch. After numbers reflect the fix, not shaped lowering (int keys still use `emitConstantValue`).

## Hypothesis verdict

| Hypothesis | Verdict | Evidence |
|------------|---------|----------|
| `guestConstNestedHeaders` throughput ↑, alloc ↓ | **Refuted / flat** | ns/op and B/op unchanged within noise; bytecode now `CreateMapShaped2` + `CreateMapShaped3` (introspection). |
| `guestAllConstNested` clearer win | **Partial** | ~2% ns/op; alloc flat at 64 B/op; `CreateMapShaped1` + `CreateMapShaped3`. |
| `guestRingResponsePipeline` flat alloc | **Confirmed** | 24 B/op unchanged; ns/op flat. |
| `guestConstIntKeyMap` no shaped ops | **Confirmed** | No `CreateMapShaped*` in bytecode; `shape()` null for non-keyword keys. |

**Interpretation:** PEA and hot loops already dominated; shaped constant lowering shows up in **bytecode** (`AssocLoweringIntrospectionTest`) more than in JMH ns/op or gc B/op for these microbenches.

## Complex benchmarks — main (no shape on `ConstantMapExpr`) vs worktree (optimizer)

Added guest workloads in [`setup.clj`](../../src/benchmark/resources/keyword-map-benchmark/setup.clj):

| Guest fn | What it stresses |
|----------|------------------|
| `guest-const-nested-deep` | 3-level constant keyword nest + `get-in` |
| `guest-const-nested-ring-plus` | Ring-like constant headers, chained `assoc`, destructure |
| `guest-const-nested-api-envelope` | JSON:API-ish tree, `assoc-in` + `get-in` |
| `guest-const-nested-multi-slot` | Outer map with 4 constant nested children + reads |

Reference workloads (many constant inner maps, not only `ConstantMapExpr` path): `guest-ring-request-nested`, `guest-jsonapi-document-nested`, `guest-middleware-pipeline`.

### Throughput (JMH avgt ns/op, same JVM session)

| Benchmark | Main | Worktree | Δ |
|-----------|-----:|---------:|--:|
| `guestConstNestedRingPlus` | 8.01 | 6.79 | **−15%** |
| `guestAllConstNested` | 6.72 | 5.88 | **−12%** |
| `guestConstNestedHeaders` | 8.51 | 7.96 | −6% |
| `guestRingResponsePipeline` | 7.31 | 6.88 | −6% |
| `guestMiddlewarePipeline` | 7.70 | 7.13 | −7% |
| `guestConstNestedDeep` | 8.54 | 8.08 | −5% |
| `guestConstNestedApiEnvelope` | 114.9 | 107.8 | −6% |
| `guestConstNestedMultiSlot` | 7.08 | 7.24 | noise |
| `guestRingRequestNested` | 6.98 | 7.54 | noise |
| `guestJsonapiDocumentNested` | 7.52 | 8.24 | noise |

Largest wins on **Ring-plus** and **all-const** nests (many `CreateMapShapedN` sites). `get-in` / `assoc-in` envelope stays slow and allocation-heavy on both builds.

### Allocation (`check-scalar-replacement`, B/op)

| Benchmark | Main | Worktree |
|-----------|-----:|---------:|
| `guestConstNestedDeep` | 24 | 24 |
| `guestConstNestedRingPlus` | 24 | 24 |
| `guestConstNestedApiEnvelope` | **808** | **808** |
| `guestConstNestedMultiSlot` | 72 | 72 |
| `guestRingRequestNested` | 24 | 24 |
| `guestJsonapiDocumentNested` | 24 | 24 |

**Takeaway:** Complex benches separate **literal construction** (modest ns/op gains after shaped lowering) from **path-based access** (`get-in` still allocates; not fixed by this change). Compare `guestConstNestedRingPlus` (~7 ns, 24 B/op) vs `guestConstNestedApiEnvelope` (~108 ns, 808 B/op).

## Best-case scenario (worktree overtakes main)

**Hypothesis:** Win when each invocation builds a **small number** of maps with **inline constant nested keyword headers** and uses **keyword lookup + `assoc`**, not `get-in` and not dozens of literals in one giant `let`.

**Hero benchmark:** `guestConstNestedRingPlus` (`guest-const-nested-ring-plus`).

| Build | `guestConstNestedRingPlus` (3 forks, 10×1s) |
|-------|-----------------------------------------------|
| Main (`CreateMap2` for inner `ConstantMapExpr`) | **7.112 ± 0.222 ns/op** |
| Worktree (`CreateMapShaped2` + shaped outer) | **6.652 ± 0.196 ns/op** |

Worktree is **~6.5% faster** (~1.07× throughput). This is the realistic Ring middleware shape from the experiment plan.

**What did *not* overtake main (amplified fanout):**

| Benchmark | Main | Worktree | Note |
|-----------|-----:|---------:|------|
| `guestConstNestedFanoutBest` (8× outer+inner literals) | 1245 ns/op | 1444 ns/op | Larger bytecode; shaped path slower |
| `guestConstInnerFanoutBest` (8× 2-key `ConstantMapExpr`) | 269 ns/op | 281 ns/op | Same |
| `guestConstInner4FanoutBest` (8× 4-key `ConstantMapExpr`) | 265 ns/op | 273 ns/op | Same |

Repeating many `ConstantMapExpr` constructions in one function does **not** multiply the win; it may hurt compile size and JIT. Prefer **one nested literal + a few assoc/read** per call (`guestConstNestedRingPlus`) for the best-case story.

## Bytecode introspection

`AssocLoweringIntrospectionTest`:

- `constantMapExprNestedHeadersEmitsCreateMapShaped` — `CreateMapShaped2`, `CreateMapShaped3`
- `constantMapExprAllConstNestedEmitsCreateMapShaped` — `CreateMapShaped1`, `CreateMapShaped3`; no `CreateMap3`
- `constantMapExprIntKeyDoesNotEmitCreateMapShaped` — no `CreateMapShaped*`
