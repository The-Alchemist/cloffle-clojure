# The MERGE_EXPLODE interpreter-state hypothesis — measured and rejected

`cross-call-validation-pipeline` allocates 712 B/op (736 on the hoisted-`defn` probe
`KeywordMapBenchmark.guestValidationPipeline`, the extra 24 being the host `IFn.invoke(arg)`
floor). Twelve objects commit across three `CommitAllocationNode`s, and two of them report the
familiar diagnosis:

```
      used by ValuePhiNode #7425 (values) [loop merge]
        loop-carried: a phi at a loop header cannot stay virtual. ... check whether a frame
        slot stays live past its last read.
```

That is the third time this mechanism has been reported — see [TODO_tuple.md](TODO_tuple.md),
[FIXME_shape_map_alloc.md](FIXME_shape_map_alloc.md), and the "the loop phi may be the real cause"
section of [TODO_lowering_layer.md](TODO_lowering_layer.md). The hypothesis under test was that
Cloffle could shrink the interpreter state `MERGE_EXPLODE` compares at a dispatch-loop merge so the
loop stops re-forming across the guest `and` chains.

**Verdict: no-go. The hypothesis is false, and so was the guest-level workaround previously
believed to fix it.** Four levers moved the number by zero. What actually distinguishes the 0 B/op
case is constant folding, not state, not clearing, and not branching.

## Measurements

| # | Variant | B/op | Note |
| :-- | :--- | --: | :--- |
| — | snippet baseline (`and` chains, computed fields) | 712 | 4 loops, 38 `MergeNode`, 3 commits, 12 objects |
| — | hoisted-`defn` baseline (`guestValidationPipeline`) | 736 | 712 + 24 host floor |
| L1 | `fillRootLocalPool` multiplier `*4` -> `*1` | 736 | no change |
| L2 | all dead-local clearing off (`cloffle.ClearDeadLocals=false`) | 736 | no change; **control moved 24 -> 280** |
| L4' | branchless conjunction via `Boolean/logicalAnd` | 712 | no change, and confounded |
| L3/L4 | no `and` anywhere in `validate`/`authorize`, fields still computed | 712 | no change |
| — | all stored fields constant, all branching in the consumer | **0** | 2 loops, 0 `MergeNode` |
| — | same, but **one** field computed instead of constant | 584 | one field is enough |

## Step 0 — the premise looked sound, and was not

Comparing the baseline against the 0 B/op terminal-branching variant at `FinalPartialEscapePhase`
seemed to confirm everything:

| PEA phase | baseline (712) | terminal (0) |
| :--- | --: | --: |
| `LoopBeginNode` | 4 | 2 |
| `MergeNode` | 38 | 0 |
| `IfNode` | 43 | 2 |
| `CommitAllocationNode` | 3 | 0 |
| `continueAt` refs | 12184 | 4473 |

Fewer loops, no merges, no commits. Step 1 then inverted it.

## Step 1 — a slot-level probe, and the inversion

The existing `explain-allocations` usage walk names the phi but not the frame slot. A probe that
walks `AllocatedObjectNode -> ValuePhiNode -> LoopBeginNode` and cross-references the
`VirtualObjectState` `values` edges of each frame's `Object[]` `VirtualArrayNode` (the edge's
`index` property is the slot) reports the slot directly.

It located the survivors precisely. Frames are per-inlined-guest-call; only 4 of 21 participate in
loops, and the two anchors are slot 2 of two different frames:

```
  commit 16473
    obj 10502 PersistentShapeMap   feeds loop phi 10385 at loop 10179 -> frame8878[116]#2
  commit 16502
    obj 7482  PersistentShapeMap   feeds loop phi 7425  at loop 7334  -> frame6779[50]#2
  commit 16436
    obj 12704 PersistentShapeMap   held directly in frame8878[116]#3,4,5,6,7,8
```

Two facts killed the premise:

1. **The oversized local pool is not in the carried set.** Frames are 50-116 slots, but only slots
   1-16 ever hold a loop phi. `fillRootLocalPool` hands locals out in order, so the surplus slots
   are constant-null and agree trivially at the merge. Predicted L1 to be a no-op.
2. **The 0 B/op variant carries *more* state than the 712 B/op one** — 113 distinct
   `(frame, slot, phi)` triples versus 57, with 26 carried slots in a single 156-slot frame versus
   15 in a 116-slot frame. Carried-state size does not predict allocation, so no state-shrinking
   lever can be the fix.

## L1 and L2 — the locals machinery is fine, and irrelevant here

L1 (`*4` -> `*1`) left the number bit-identical at 736.

L2 is the upper bound on the entire clearing mechanism: turning `cloffle.ClearDeadLocals` off
disables both `clearBindingsDeadInBody` and last-use `LoadAndClearLocal`. That is the *most*
clearing could ever be worth, with the sign flipped. It changed nothing (736 -> 736), while the
paired control proved the switch was live:

| Benchmark | clearing on | clearing off |
| :--- | --: | --: |
| `guestValidationPipeline` | 736 | 736 |
| `guestDefnPipeline` (control) | 24 | 280 |

So the machinery works and is load-bearing for allocation generally — it simply has no purchase on
this workload. Block-scoping non-captured locals (the actual L2 change) cannot beat turning the
whole mechanism off, so it was not built.

## L3 and L4 — the `and` chains are not the cause

The first attempt at a branchless conjunction used `Boolean/logicalAnd` in guest code as a proxy.
It measured 712 B/op, but it is **not** valid evidence: the graph still had 43 `IfNode` and 38
`MergeNode`, because the interop argument coercion reintroduced equivalent branching, and
throughput collapsed from 13M to 4.3M ops/s with `ClassCastException` rematerialisations. It
removed the guest `and` and kept the merges.

The clean test removes `and` from `validate` and `authorize` outright, storing three bare `=`
results into the map. Still **712 B/op, unchanged**. With no `and` to fuse, an
`@ShortCircuitOperation` has nothing to fix, so L3 was not built either. Both levers are dead for
this workload.

## What the 0 B/op case was actually doing

The variant that reaches 0 B/op has `validate` and `authorize` store literal `true`. Every field of
the carried map is then a compile-time constant, every downstream read folds, the maps become dead,
and the whole pipeline collapses (3049 nodes -> 1660). It was never about where the branching sat.

Changing exactly one of those three fields from `true` to `(= (:role (:actor req)) :clinician)`,
with the shape otherwise untouched, takes it from **0 to 584 B/op**. One non-constant field in one
carried map re-materialises most of the object graph.

So the rule is:

> A single value that does not constant-fold, stored into a map that is then carried across a
> function boundary, is enough to force the carried object graph onto the heap.

This also retracts a conclusion recorded earlier in this investigation: "moving the branching to
the consumer" was described as the one change that fixes the allocation. It does fix it in the
snippet, but only because the snippet has no runtime input, so relocating the predicates made every
stored field constant. In real code, where the validation verdict depends on a runtime value, that
refactor would not help.

## Why the loop-phi diagnosis misled again

Both readings were true and neither was the cause, which is now the pattern:
`TODO_tuple.md` found a genuinely stale frame slot, `FIXME_shape_map_alloc.md` found a missing
lowering, and here the loop phi is downstream of failed constant folding. The `explain-allocations`
hint text ("check whether a frame slot stays live past its last read") is sound advice for the
tuple case but points the wrong way whenever the phi'd object is a **live return value** — as both
anchors here are, being what `validate` and `authorize` hand to the next stage. There is no slot to
clear.

Worth considering: extend `explain-reason` so a loop-phi survivor that is also an input to a
`ReturnNode` is reported as "live across the merge, not stale" rather than suggesting a clearing
fix.

## Recommendation

Do not pursue `ExprToBytecode` or Bytecode DSL changes for this. Nothing in Cloffle's control over
frame layout, local clearing, or branch emission affects it.

The open question is a different one, closer to [FIXME_shape_map_alloc.md](FIXME_shape_map_alloc.md)
than to this document: **why can a `PersistentShapeMap` with one non-constant field not stay virtual
across an inlined Var call, when the same map with all-constant fields disappears entirely?** A
virtual object is allowed to hold non-constant fields, so the 584 B/op result is the anomaly worth
chasing next. Until then, `guestValidationPipeline` (736) and the
`cross-call-validation-pipeline` snippet (712) stand as catalogued ratchets.

## Reproducing

```sh
clojure -T:build check-scalar-replacement :snippet '"cross-call-validation-pipeline"' :alloc-budget 0
clojure -T:build check-scalar-replacement :benchmark '"KeywordMapBenchmark.guestValidationPipeline"' :guest true :alloc-budget 0
clojure -T:build explain-allocations :snippet '"cross-call-validation-pipeline"' :guest true :dump-path '"target/graal-dumps-spike"'
clojure -M:build dev/compare-snippet-graphs.clj <outer-root.bgv> label
```

The slot probe used for Step 1 is not committed; it is ~130 lines against `BgvDump`, keyed on
`VirtualObjectState` `values` edge `index` for slot numbers, and is worth rebuilding into
`explain-allocations` only if the "live across the merge" distinction above is wanted permanently.
