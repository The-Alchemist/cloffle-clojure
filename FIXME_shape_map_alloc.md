# `guestShapeMapEphemeralPipeline` allocates 152 B/op against a 24 B/op budget

The only failing entry in `clojure -T:build check-scalar-replacements :suite :guest`. It is **not**
caused by the tuple work: it reproduces identically with that change stashed, and with the whole
working tree clean.

Sibling ticket: [`FIXME_keyword_invoke_perf.md`](FIXME_keyword_invoke_perf.md). Both suspect the
`MapShape` extraction (`a73cbecc`), but they are different code paths — lookup there, `assoc` here —
so do not assume one fix closes both. A third `MapShape` ticket,
[`FIXME_mapshape_cache_race.md`](FIXME_mapshape_cache_race.md), is a correctness bug in the same
class and is independent of both. Analysis technique for everything below is
[`HOWTO_SEAFOAM.md`](HOWTO_SEAFOAM.md).

---

## Symptom

```sh
clojure -T:build check-scalar-replacement \
  :benchmark '"KeywordMapBenchmark.guestShapeMapEphemeralPipeline"' \
  :guest true :alloc-budget 24
```

fails at **152.0 B/op** against the catalog budget of 24 (`build.clj`, `scalar-replacement-catalog`).
Latency has regressed with it: **15.1 ns/op** today versus the 5.69 ns/op recorded in
[`GRAAL_GRAPH_ANALYSIS.md`](GRAAL_GRAPH_ANALYSIS.md) §"Guest ephemeral", which also states the
low-tier graph was once "verified allocation-free".

The benchmark is a one-line guest fn (`src/benchmark/resources/keyword-map-benchmark/setup.clj`):

```clojure
(defn guest-ephemeral-pipeline [x]
  (let [m {:a x :b 2 :c 3}]
    (:a (assoc m :a "replacement"))))
```

Nothing escapes: the fn returns the looked-up value, not the map. Both maps should be scalar
replaced, which is what the 24 B/op budget was recorded against.

## Evidence already gathered (2026-09-09, clean tree)

```sh
clojure -T:build explain-allocations \
  :benchmark '"KeywordMapBenchmark.guestShapeMapEphemeralPipeline"' \
  :guest true :dump-path '"/tmp/cloffle-dumps-shape"'
```

At `FinalPartialEscapePhase`: 13 virtual objects, 10 scalar replaced, **3 committed**. Everything
frame-related is eliminated (both `FrameWithoutBoxing`, all the `Object[]`/`long[]`/`byte[]` frame
arrays). All three survivors are `clojure.lang.PersistentShapeMap`:

| Commit | Source chain |
| --- | --- |
| 3479 | `BytecodeCreateMap#createShaped3` ← `CreateMapShaped3#doCreate` — the `{:a x :b 2 :c 3}` literal |
| 3485 | `PersistentShapeMap#assoc` ← `RT#assoc` ← `LambdaForm$DMH#invokeStatic` |
| 3487 | same chain again |

Two things to notice in that table.

**`assoc` is arriving through `RT.assoc` on a MethodHandle**, not through a shaped bytecode
operation. `GRAAL_GRAPH_ANALYSIS.md` describes `KeywordAssoc` with `AssocTransition` /
`Promote16Transition` handling exactly this shape, and those frames are absent. A `LambdaForm$DMH`
frame means `BytecodeStaticMethod`'s MethodHandle path, i.e. the guest call went to
`clojure.core/assoc` → `RT.assoc` as an ordinary host static call.

**One guest `assoc` produced two commits**, so the path is duplicated across a merge — the same
shape of problem as the tuple loop phi in [`TODO_tuple.md`](TODO_tuple.md).

**Confirmed, not just suspected.** `check-scalar-replacement` now reports why each survivor was
materialized, and both `PersistentShapeMap` commits (3485, 3487) say the same thing:

```
      used by ValuePhiNode #1874 (values) [loop merge]
        loop-carried: a phi at a loop header cannot stay virtual.
```

Both commits feed the *same* phi. That is hypothesis 3's mechanism, observed rather than inferred,
so start there — though hypothesis 2 still has to be ruled out first, since the low-tier stubs are
cold and may not account for the 152 bytes.

At low tier all three stubs are cold (`relativeFrequency` 0.0100, 0.0051, 0.0048), which per
`HOWTO_SEAFOAM.md` step 0 means **the graph may not account for the 152 bytes**. Settle that before
chasing the commits above.

## Hypotheses, most to least likely

1. **`assoc` lost its shaped lowering the same way `nth` did.** `TODO_tuple.md` documents that
   `0af1e162`, `a08ab505`, and `60816999` removed the bytecode intrinsics and `:inline` expansion,
   which sent `RT.nth` down a generic host-call path and cost 30x. The `RT#assoc` ← `DMH` chain here
   is that same signature. Check what `ExprToBytecode` emits for `(assoc m :a v)` today and whether
   `KeywordAssoc` is still reachable at all.
2. **The bytes are outside this compilation unit.** Cold stubs plus a 152 B/op measurement is the
   textbook case from the guide: look at the other `.bgv` files in the dump directory (a separately
   compiled `clojure.core/assoc`), and run with `-Djdk.graal.TraceDeoptimization` to see whether the
   benchmark is deoptimizing into those cold paths on every operation.
3. **`m` is pinned in its frame slot across the dispatch-loop merge.** This is the tuple bug's
   mechanism. `e84ddebd` clears `let*` bindings the body *cannot* read, and here the body does read
   `m`, so nothing is cleared even though the last read happens before the return. Clearing at last
   use rather than at "never read" is the obvious generalization and was deliberately not attempted.
   The two-commits-for-one-`assoc` observation is what makes this worth testing.
4. **`a73cbecc` regressed it.** That commit shrank `PersistentShapeMap` to a single `MapShape`
   reference and added `CreateMapShaped1..8`.    `CreateMapShaped3` is the source of survivor 3479, so
   the literal's allocation site is new code.
5. **`PersistentShapeMap.create(...)` crosses a Truffle boundary.** Every `create` overload routes
   through the legacy 18-argument constructor, which calls `MapShape.fromSorted` — and that is
   `@TruffleBoundary`, as is `intern` beneath it. `GRAAL_GRAPH_ANALYSIS.md` records that
   `KeywordAssoc.doNull` and `MapAssoc.doNull` return `PersistentShapeMap.create(k, v)`, so that
   boundary sits directly in compiled guest code, and an object flowing into a boundary call cannot
   stay virtual. `Util.clearCache` also polls the `ReferenceQueue` on *every* intern and walks the
   whole table when it is non-empty. Check whether any `create` call is reachable from this
   benchmark's compilation unit; if so, taking the `MapShape`-typed primary constructor with a
   pre-interned shape removes the boundary entirely.

## Plan

1. **Date the regression.** `GRAAL_GRAPH_ANALYSIS.md` says this was allocation-free at 5.69 ns/op, so
   there is a good commit to find. Bisect in a worktree (do not disturb the main tree) with the gate
   itself as the predicate:

   ```sh
   git worktree add /tmp/bisect-shape <sha>
   cd /tmp/bisect-shape && clojure -T:build check-scalar-replacement \
     :benchmark '"KeywordMapBenchmark.guestShapeMapEphemeralPipeline"' \
     :guest true :alloc-budget 24 ":throw?" false
   ```

   Candidates worth trying first, newest last: `a73cbecc` (MapShape), `60816999` (`:inline` removal),
   `a08ab505` and `0af1e162` (intrinsic removals). Each run is ~30 s plus a build.
2. **Answer hypothesis 2 before hypothesis 1**, because it is cheap and it decides whether the graph
   above is even the right object of study.
3. **Establish which operation `assoc` compiles to.** A `GuestCompilationUnitTest`-style assertion is
   better than reading the dump: it will keep the answer from silently changing again.
4. **Fix at the emitter, not at the budget.** If `assoc` should be lowering to `KeywordAssoc`, restore
   that lowering; if it should stay a host static call, then the fix belongs in
   `BytecodeStaticMethod` (as `43af52d0` did for primitive signatures) or in locals clearing. Do
   **not** raise the 24 B/op budget to make the gate green — that blesses the regression, which is
   exactly what `record-alloc-budgets` refuses to do automatically.

## Success criteria

- [ ] `check-scalar-replacements :suite :guest` is fully green with the budget still at 24.
- [ ] `guestShapeMapEphemeralPipeline` back near 12 ns/op (the number in `GRAAL_GRAPH_ANALYSIS.md`).
- [ ] The regressing commit is named in this file, with the mechanism, even if the fix lands elsewhere.
- [ ] `run-tests`, `run-clj-tests`, and `compat-test` unchanged.

## Pointers

| Item | Location |
| --- | --- |
| Guest fn | `src/benchmark/resources/keyword-map-benchmark/setup.clj` `guest-ephemeral-pipeline` |
| JMH method | `src/benchmark/java/net/javacrumbs/cloffle/benchmark/KeywordMapBenchmark.java` |
| Catalog entry + budget | `build.clj`, `scalar-replacement-catalog`, `:hint "guest-ephemeral-pipeline"` |
| Shaped map creation | `net.javacrumbs.cloffle.bytecode.BytecodeCreateMap#createShaped3`, `CloffleBytecodeRootNode$CreateMapShaped3` |
| Assoc operations | `CloffleBytecodeRootNode$KeywordAssoc`, `clojure.lang.PersistentShapeMap#assoc`, `clojure.lang.MapShape` |
| Host static dispatch | `net.javacrumbs.cloffle.bytecode.BytecodeStaticMethod#computeMethodHandle` |
| Prior art (same class of bug) | [`TODO_tuple.md`](TODO_tuple.md) "Guest tuple ops were slow for an unrelated reason" |
| Recorded good numbers | [`GRAAL_GRAPH_ANALYSIS.md`](GRAAL_GRAPH_ANALYSIS.md) §"Guest ephemeral" |
