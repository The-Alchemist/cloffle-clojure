# RESOLVED — `keyword-invoke` throughput regression at `a73cbecc`

A keyword lookup on a three-key map literal — the most common operation in Clojure code — had lost
more than half its throughput at a single known commit. It is now recovered.

```clojure
;; src/benchmark/resources/snippets/keyword-invoke.clj
(:b {:a :v1 :b :v2 :c :v3})
```

| Commit | ops/s |
| --- | --- |
| parent of `a73cbecc` | 237M |
| `a73cbecc` "Extract MapShape and pre-build shapes at analysis time" | 95.7M |
| `428cd3b4` | 102M |
| **after the `MapShape` simplification** | **243.7M**, 0 B/op |

Reproduce:

```sh
clojure -T:build check-scalar-replacement :snippet '"keyword-invoke"' :alloc-budget 0
```

## Cause

The ticket's first hypothesis was right about the mechanism and wrong about which half of the
snippet paid for it. Shape identity *was* unstable, but the cost was in **creation**, not lookup
(hypothesis 4's control was the one that mattered).

Every keyword-map literal rebuilt its `MapShape` on every single execution. The cached `Shape1`–
`Shape8` helpers sorted their keys once at specialization time, but `ShapeN.create` then routed
through `PersistentShapeMap`'s legacy 18-argument constructor, which called
`MapShape.fromSorted` — and that was `@TruffleBoundary`, with the intern table's
`ConcurrentHashMap` and `ReferenceQueue` sweep underneath it. So the hottest possible operation
crossed a Truffle boundary per execution, and the resulting shape could not be a compile-time
constant. Confirmed by stack trace, not inferred:

```
MapShape.<init> ← MapShape.fromSorted ← PersistentShapeMap.<init> (legacy 18-arg)
                ← PersistentShapeMap$Shape8.create ← CreateMap8.doKeywordCached
```

## Fix

Each `ShapeN` now builds its `MapShape` once in its own constructor and `create` uses the
`MapShape`-typed primary constructor. Since `ShapeN` is `@Cached`, the shape is compilation-final,
so the boundary is gone and the shape folds.

Landed alongside two related changes, so the 243.7M is the combined result and the attribution above
is by mechanism rather than by A/B:

- The intern table was deleted outright — it was keyed by an unverified XOR hash (the "worth closing
  while the file is open" item below, which turned out to be a real correctness hole, not just a
  wart), and none of its weak-reference machinery was needed once `ShapeN` stopped calling it per
  execution. Shapes are no longer canonicalized.
- Because identity is gone, the DSL guards compare key layouts: `cachedShape.sameKeys(target.shape)`
  in `KeywordLookup` / `KeywordLookupDefault`, and the same in `AssocTransition.matches`,
  `DissocTransition.matches`, and `getLookupThunk`. Without this the inline caches never hit and
  guest code never stays in compiled machine code.
- `@ValueType` came off `MapShape` (hypothesis 2). It declares `==` undefined while the guards
  depended on `==`; that contradiction is now moot but the annotation was still wrong.

## Ruled out, keep it ruled out

**`MapShape` fields not constant-folding.** `count`, `k0..k7`, and `tags` are `final` with no arrays,
so Graal folds them for a constant receiver. `@CompilationFinal` was never needed; that import is
gone.

## Still open

- Bytes are gated: `known-scalar-replacement-benchmarks` pins `keyword-invoke` at `:alloc-budget 0`.
  Throughput is not — a regression that still allocates 0 B/op but drops ops/s would not fail the
  catalog. No separate thrpt gate exists yet.
- `benchmark-results.md` still records 210M for this row from `fa53d1b9` and stock Clojure at 333M.
  History, not a baseline.
- ~~The sibling ticket [`FIXME_shape_map_alloc.md`](FIXME_shape_map_alloc.md) is **not** closed by
  this: `guestShapeMapEphemeralPipeline` re-measured at 152.0 B/op afterwards, exactly as before.~~
  Still accurate as written — this fix did not close it — but that ticket is now **resolved**
  separately by `2d6e5677`, which restored the `KeywordAssoc` lowering that `a08ab5051` had deleted.
  `guestShapeMapEphemeralPipeline` is at 24.0 B/op and 5.427 ns/op. The two tickets were indeed
  different code paths, as this file predicted.

## Pointers

| Item | Location |
| --- | --- |
| Snippet | `src/benchmark/resources/snippets/keyword-invoke.clj` |
| Snippet harness | `src/benchmark/java/net/javacrumbs/cloffle/benchmark/SnippetBenchmark.java`, `SnippetBenchmarkSupport.java` |
| Lookup operations | `CloffleBytecodeRootNode$KeywordLookup`, `$KeywordLookupDefault` |
| Cached shape helpers | `PersistentShapeMap$Shape1`–`$Shape8` |
| Shape type | `src/jvm/clojure/lang/MapShape.java`, `PersistentShapeMap.java`, `PersistentShapeMap16.java` |
| Culprit commit | `a73cbecc` "Extract MapShape and pre-build shapes at analysis time" |
| Stale report rows | `benchmark-results.md` (measured `fa53d1b9`) |
