# RESOLVED — `guestShapeMapEphemeralPipeline` allocated 152 B/op against a 24 B/op budget

Closed 2026-09-09 by `2d6e5677` ("perf(bytecode): rebuild the lowering layer for assoc and get").
**Hypothesis 1 was correct**: `assoc` had lost its shaped lowering exactly the way `nth` did.

| | Before | After |
| --- | --- | --- |
| Allocation | 152.0 B/op | **24.0 B/op** (budget 24) |
| Latency | 15.1 ns/op | **5.427 ± 0.314 ns/op** |
| PEA at `FinalPartialEscapePhase` | 13 virtual, 10 scalar replaced, **3 committed** | 5 virtual, 5 scalar replaced, **0 committed** |
| Low tier | 3 cold allocation stubs | no allocation stubs |

The latency beats the 5.69 ns/op that `GRAAL_GRAPH_ANALYSIS.md` recorded as the historical good
number, so this is a full recovery rather than a partial one.

Reproduce:

```sh
clojure -T:build check-scalar-replacement \
  :benchmark '"KeywordMapBenchmark.guestShapeMapEphemeralPipeline"' \
  :guest true :alloc-budget 24
```

## Cause

`a08ab5051` ("Remove hardcoded RT and Util method bytecode intrinsics") deleted the
`KeywordAssoc` operation class from `CloffleBytecodeRootNode` and, with it, the emitter's
`beginKeywordAssoc` / `endKeywordAssoc` calls in `ExprToBytecode`. Every guest `assoc` then fell
back to a single `InvokeVar3` → `clojure.core/assoc` → `RT.assoc` CallTarget reached through
`BytecodeStaticMethod`'s MethodHandle path — which is exactly the `RT#assoc` ← `LambdaForm$DMH`
frame chain the original investigation found in the dump and correctly read as the signature of a
missing lowering.

With one shared CallTarget serving every `assoc` in the program, no call site can hold a shape
cache, so the receiver type is never a compile-time constant and partial escape analysis cannot
virtualize either map.

`2d6e5677` rebuilt the operation behind declarative `:cloffle/op` metadata
(`core.clj:191`, `{3 :KeywordAssoc}`) rather than the old name-matching, and gated it on a
sanctioned-root assumption so `with-redefs` still works. Both `PersistentShapeMap` commits
disappeared with it.

## What the hypotheses got right and wrong

**Hypothesis 1 — correct, and the whole cause.** "Check what `ExprToBytecode` emits for
`(assoc m :a v)` today and whether `KeywordAssoc` is still reachable at all." It was not reachable;
the class no longer existed.

**Hypothesis 3 — a real observation, but a *symptom*, not an independent cause.** The ticket had
hard evidence for it — both surviving commits reported `used by ValuePhiNode #1874 (values)
[loop merge] / loop-carried: a phi at a loop header cannot stay virtual` — and reasonably said
"start there". That evidence was genuine and still pointed the wrong way. The loop-carried phi was
*downstream* of the missing lowering: values flowing through the generic dispatch merge are what
created the phi, and once `assoc` compiled to a shaped operation with a cached transition, the phi
went with it. No locals-clearing change was needed. Worth remembering as a case where a confirmed
mechanism was nonetheless not the root cause.

**Hypothesis 2 — now the description of the residual 24 B/op.** PEA reports "nothing survives PEA in
this compilation unit" while the benchmark still measures 24 B/op, so by the guide's own logic those
bytes are outside this unit (harness or a sibling compilation). That is the budgeted floor, not a
defect.

**Hypotheses 4 and 5 — ruled out and stay ruled out.** `a73cbecc` (`MapShape` extraction) and the
`@TruffleBoundary` `fromSorted` intern table were real problems, but they belonged to the sibling
ticket; fixing them left this number at exactly 152.0 B/op.

## Note on the benchmark source

The guest fn was de-numberized on 2026-09-09 so boxing cannot confound map measurements:

```clojure
(defn guest-ephemeral-pipeline [x]
  (let [m {:a x :b :vb :c :vc}]
    (:a (assoc m :a "replacement"))))
```

That change is not responsible for the result — the benchmark measured 24.0 B/op both before and
after it.

## Success criteria

- [x] `check-scalar-replacements :suite :guest` fully green with the budget still at 24 (31/31).
- [x] Back near 12 ns/op — landed at 5.427 ns/op, past the historical 5.69.
- [x] The regressing commit is named with its mechanism: `a08ab5051`, above.
- [x] `run-tests` 940/940 and `run-clj-tests` 636 tests / 19026 assertions unchanged.

## Follow-up worth keeping

The gate that would have caught this did not exist when `a08ab5051` landed: `run-tests` and
`run-clj-tests` both stayed green while the lowering vanished, because the generic Var path returns
identical results — just slower and allocating. `AssocLoweringIntrospectionTest` now asserts *which*
specialization is live, which is the invariant that actually rotted. See `TODO_lowering_layer.md`
"Gates".

## Pointers

| Item | Location |
| --- | --- |
| Guest fn | `src/benchmark/resources/keyword-map-benchmark/setup.clj` `guest-ephemeral-pipeline` |
| JMH method | `src/benchmark/java/net/javacrumbs/cloffle/benchmark/KeywordMapBenchmark.java` |
| Catalog entry + budget | `build.clj`, `scalar-replacement-catalog`, `:hint "guest-ephemeral-pipeline"` |
| Lowering metadata | `src/clj/clojure/core.clj:191` (`:cloffle/op {3 :KeywordAssoc}`) |
| Operation | `CloffleBytecodeRootNode$KeywordAssoc`; root guard `sanctionedRootAssumption` |
| Specialization gate | `src/test/java/net/javacrumbs/cloffle/AssocLoweringIntrospectionTest.java` |
| Regressing commit | `a08ab5051` "Remove hardcoded RT and Util method bytecode intrinsics" |
| Fixing commit | `2d6e5677` "perf(bytecode): rebuild the lowering layer for assoc and get" |
| Full narrative | [`TODO_lowering_layer.md`](TODO_lowering_layer.md), Phase 0 and Phase 1 results |
| Sibling ticket | [`FIXME_keyword_invoke_perf.md`](FIXME_keyword_invoke_perf.md) |
