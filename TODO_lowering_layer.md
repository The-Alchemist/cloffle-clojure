# Rebuild the lowering layer: `:cloffle/op` + Var root assumptions

Handoff plan. Written 2026-09-09, base commit `0755d652`. Nothing in this plan has been
implemented yet.

## Thesis

The PEA **type** layer is right and largely finished. `@ValueType` `PersistentTupleN`
(0–8), `PersistentShapeMap` / `PersistentShapeMap16` (1–16), and a plain trie
`PersistentVector` for the overflow remainder are the correct three-way split, and the
host benchmarks prove those types scalar-replace.

What is missing is the **lowering** layer between guest code and those types. It
existed, it was deleted, and nothing replaced it:

| Commit | What it removed |
| --- | --- |
| `0af1e162` | core-fn bytecode intrinsics |
| `a08ab505` | `RT` / `Util` bytecode intrinsics (incl. `isRtNthMethod` → `VectorNth2/3`) |
| `60816999` | `:inline` expansion |

Today `(assoc m :k v)` compiles to `InvokeVar3` → `clojure.core/assoc` → `RT.assoc`.
Every `assoc` in the program shares **one** CallTarget with **no** per-site shape
cache. That single fact explains the open allocation and throughput regressions better
than any tuning of a PIC `limit`.

The redesign: reinstate lowering as **declarative metadata on the Var**, guarded by
`Var.getRootAssumption()` so redefinition still works. That is strictly better than
upstream `:inline`, which silently ignores redefinition.

## Read first

- [`FIXME_shape_map_alloc.md`](FIXME_shape_map_alloc.md) — **open**, the primary evidence
  for Phase 1. Its hypothesis 1 ("`assoc` lost its shaped lowering the same way `nth`
  did") is this plan's thesis, arrived at independently. Hypothesis 3 is the loop phi.
  Hypothesis 5 is **ruled out** as of `ca1ba0c5`.
- [`TODO_tuple.md`](TODO_tuple.md) — §3 is Phase 3; the "guest tuple ops were slow"
  section is the closest worked precedent for the whole plan.
- [`FIXME_keyword_invoke_perf.md`](FIXME_keyword_invoke_perf.md) — **RESOLVED**. Read it
  as precedent, not as an open ticket: the same "a73cbecc regressed a hot map op"
  suspicion turned out to be shape *rebuilding*, not lowering, and was fixed by
  simplification. A useful reminder that hypothesis 1 here is not yet proven.

This plan is self-contained on the facts that matter.

## What `ca1ba0c5` / `28e2227a` changed (read before touching guards)

The `MapShape` simplification landed after this plan was first drafted and changes the
shape of any new operation:

- **The intern table is gone.** Shapes are no longer canonicalized. Its 64-bit XOR key
  was unverified, so a collision returned a shape with a different key set.
- **Guards compare layouts, not identity.** `MapShape#sameKeys` replaced
  `shape == cachedShape` everywhere. `KeywordLookup` now guards on
  `cachedShape.sameKeys(target.shape)` (`CloffleBytecodeRootNode.java:1936`), and
  `AssocTransition#matches` / `DissocTransition#matches` are
  `this.keyword == keyword && fromShape.sameKeys(map.shape)`
  (`PersistentShapeMap.java:623`, `:754`).
- **Do not reintroduce an identity guard on `MapShape`.** No code reads shape identity
  any more, which is what makes its `@ValueType` coherent — the compiler is free to
  merge or duplicate shape instances. A new `shape ==` guard silently re-breaks that.
- `keyword-invoke` recovered 102M → **243.7M ops/s at 0 B/op**, above the 237M it had
  before `a73cbecc`. The cause was every keyword-map literal rebuilding its `MapShape`
  per execution through a `@TruffleBoundary`, not the PIC limit.
- `guestShapeMapEphemeralPipeline` re-measured at **152.0 B/op, unchanged**. So the
  target of this plan is untouched by that work.

## Verified starting state

Confirmed by reading the tree on 2026-09-09. Do not re-derive; do re-verify if the
tree has moved.

**The operations do not exist.** There is no `KeywordAssoc`, `MapAssoc`,
`KeywordDissoc`, `MapDissoc`, `VectorNth2`, or `VectorNth3` class anywhere in
`src/jvm`. Only `KeywordLookup` (`CloffleBytecodeRootNode.java:1930`) and
`KeywordLookupDefault` (`:1978`) survive.

**The docs claim otherwise and are stale.** `PARTIAL_ESCAPE_ANALYSIS.md` §B lines
72–77, `GRAAL_GRAPH_ANALYSIS.md` lines 177–178, and the javadoc `{@link MapAssoc}` at
`CloffleBytecodeRootNode.java:218` all describe those ops in the present tense. This
misleads readers into assuming the ops are present.

**The expensive half survived.** `PersistentShapeMap.AssocTransition` (`:613`),
`DissocTransition` (`:744`), `Promote16Transition` (`:693`), and
`PersistentShapeMap16.Dissoc16Transition` (`:832`) all exist and are covered by
`PersistentShapeMapTest.testCachedAssocTransitionsAllRoutesAndGuards` (`:306`) and
`testCachedDissocTransitionsAllRoutesAndGuards` (`:364`). Rebuilding the ops is wiring,
not design.

**The correctness guard exists.** `Var.getRootAssumption()` (`Var.java:98`) is
invalidated by `bindRoot` / `alterRoot` / `unbindRoot` (`:302`, `:312`, `:319`,
`:328`, `:338`), and `InvokeVar0..N` already consume it via
`@Cached(value = "var.getRootAssumption()", neverDefault = true)`
(`CloffleBytecodeRootNode.java:1597` and 12 more sites).

**The emitter hook point** is the `VarExpr` branch of `InvokeExpr` in
`ExprToBytecode.convert`:

```1102:1105:src/jvm/net/javacrumbs/cloffle/bytecode/ExprToBytecode.java
            } else if (ie.fexpr instanceof VarExpr ve && !ve.var.isDynamic()) {
                emitWithExprSection(b, ie, BC_TAG_CALL, () -> {
                    ExprToBytecodeInvoke.emitInvokeVar(ve.var, ie.args, b, arg -> convertCalleeOrArgForInvoke(arg, b));
                });
```

A new branch goes **before** this one. `(:k m)` already gets this treatment via
`KeywordInvokeExpr` → `beginKeywordLookup` (`:898`), which is the reference pattern.

## Upstream `:inline` status — the risk split

Checked against `org.clojure/clojure` 1.12 at
`/Users/karl-medplum/Development/digital-alchemy/clojure`. This split is load-bearing;
the six candidate vars are **not** one risk class.

| Var | Upstream metadata | Redefinable upstream? | Tier |
| --- | --- | --- | --- |
| `get` | `:inline` + `:inline-arities #{2 3}` (core.clj:1512) | No | 1 |
| `nth` | `:inline` + `:inline-arities #{2 3}` (core.clj:891) | No | 1 |
| `count` | `:inline` (core.clj:876) | No | 1 |
| `assoc` | `:static true` only (core.clj:183) | **Yes** | 2 |
| `dissoc` | `:static true` only (core.clj:1531) | **Yes** | 2 |
| `conj` | `:static true` only (core.clj:84) | **Yes** | 2 |

**Tier 1** lowering is stock parity and needs no assumption.
`COMPATIBILITY_RISK_AUDIT.md` §2 says so explicitly: "`get`, `nth` and `count` behave
identically to stock, because stock already marks them `:inline`."

**Tier 2** lowering without a root check re-opens `COMPATIBILITY_RISK_AUDIT.md`
Finding 2 ("Bytecode intrinsics bypass Var redefinition", **Critical**), which the
intrinsic removals closed by deletion. That finding names `assoc` and `dissoc`. Its
diagnosis is one sentence: *"There is no check that the Var still holds its original
root value."* The assumption is that check.

Note `:inline-arities` is direct precedent for the arity-keyed map: you are replacing
a boolean gate with a target.

## Phase 0 — unblock (do this first, it is cheap)

**0a. Run the escape-probe ladder.** See "Escape-probe snippets" below. This is the
cheapest step in the plan and it decides whether the plan is even aimed at the right
cause. Do it before writing any code.

**0b. Truth up the stale docs.** Correct `PARTIAL_ESCAPE_ANALYSIS.md` §B,
`GRAAL_GRAPH_ANALYSIS.md` §"Minimal strictly-necessary PEA architecture", and the
`CloffleBytecodeRootNode.java:218` javadoc so they state that the assoc/dissoc/nth ops
were removed and describe the transition classes that remain. Doing this first prevents
the next reader from repeating the wrong assumption.

## Phase 0 — RESULTS (2026-09-09)

**0a. Ladder run.** `clojure -T:build record-alloc-budgets :snippet "<name>"`, thrpt, wi=3 i=3.

| Snippet | B/op | ops/s |
| --- | --- | --- |
| `assoc-only` | **128.0** | 85.1M |
| `assoc-return-nil` | **128.0** | 75.4M |
| `consume-assoc` | **128.0** | 80.9M |
| `consume-assoc-no-let` | **128.0** | 83.5M |
| `ephemeral-pipeline` | **128.0** | 80.9M |
| `keyword-invoke` (control) | **0.0** | 235.9M |
| `tuple-destructure` (control) | **0.0** | 173.1M |
| `array-map-lookup` (control) | **0.0** | 163.6M |
| `guestShapeMapEphemeralPipeline` | 152.0 | 13.90 ns/op |

**Verdict: hypothesis 1 (lowering) wins; hypothesis 3 (frame local) is ruled out for these
snippets.** Three readings, in order of force:

1. `consume-assoc` == `consume-assoc-no-let` **to the byte**. Removing the `let` local changes
   nothing, so the frame local is not the cause and last-use clearing would not help here.
2. `assoc-return-nil` == `assoc-only` **to the byte**. The result being *fully dead* does not
   reduce allocation by one byte. PEA cannot see this allocation at all — it is not an escape
   problem, it is happening behind an opaque call boundary (`InvokeVar3` → `RT.assoc`).
3. `keyword-invoke` is **0 B/op on the same 3-key literal map**. Map literal construction
   virtualizes perfectly. Adding one `assoc` costs a flat 128 B/op regardless of what the
   program then does with the result. The harness floor is 0, so all 128 B are the `assoc`.

Every value being *exactly* 128.0 is itself the signal: the cost is invariant to surrounding
context, which is what "one shared CallTarget, no per-site cache" predicts.

**0b. Stale docs corrected.** `PARTIAL_ESCAPE_ANALYSIS.md` §B/§C, `GRAAL_GRAPH_ANALYSIS.md`
§"Minimal strictly-necessary PEA architecture" and §`get-in` verdict, and the
`CloffleBytecodeRootNode.java:218` javadoc. Two findings beyond what this plan recorded:
`CollectionCount`, `IsSeq`, `Identical`, `IsNil`, `IsSome` **also do not exist** (Phase 2 step 3
must build `CollectionCount`, not reuse it), and the forked `core.clj` has **zero** `:inline`
metadata — `definline` is a shim that deliberately omits it (`core.clj:5075`), so
`COMPATIBILITY_RISK_AUDIT.md` §8's `get-in` `:inline` concern is moot.

## Phase 1 — the `assoc` slice

Scope: `(assoc m k v)`, arity 3 only. Chosen because it has a documented number to
beat, a surviving transition cache, and an existing guest test.

**1a. Write the operations** in `CloffleBytecodeRootNode.java`, modeled exactly on
`KeywordLookup`'s specialization ordering:

```java
@Operation(storeBytecodeIndex = true)
@ConstantOperand(type = Keyword.class, name = "keyword")
public static final class KeywordAssoc {
    // 1. null receiver — must match RT.assoc(null, kw, v)
    @Specialization(guards = "target == null")
    public static Object doNull(Keyword keyword, Object target, Object val) {
        return PersistentShapeMap.create(keyword, val);
    }

    // 2. hot: cached transition plan. Guard is keyword identity + sameKeys layout
    //    compare (NOT shape identity -- see the ca1ba0c5 note above).
    @Specialization(guards = "cached.matches(target, keyword)", limit = "4")
    public static Object doShapeMap(
            Keyword keyword, PersistentShapeMap target, Object val,
            @Cached("assocTransition(target, keyword)") AssocTransition cached) {
        return cached.apply(target, val);
    }

    // 3. same type, cache exhausted
    @Specialization(replaces = "doShapeMap")
    public static Object doShapeMapGeneric(Keyword keyword, PersistentShapeMap target, Object val) {
        return target.assoc(keyword, val);
    }

    // 4. other Associative classes, exact-cast
    @Specialization(guards = "target.getClass() == cachedClass", limit = "8")
    public static Object doAssociativeCached(
            Keyword keyword, Associative target, Object val,
            @Cached("target.getClass()") Class<? extends Associative> cachedClass) {
        return CompilerDirectives.castExact(target, cachedClass).assoc(keyword, val);
    }

    // 5. generic
}
```

Also add `MapAssoc` (no `ConstantOperand`; class cache only, no transition cache) for
non-constant keys.

Ordering discipline, which should be written down as policy: **concrete `@ValueType`
first with a cache that turns work into constants, then same-type uncached, then
`castExact` class cache, then generic.** The first arm must name
`PersistentShapeMap`, not `Associative`, or PEA sees an interface call and nothing
virtualizes.

Semantics must match `RT.assoc` exactly, including the nil case. `PersistentShapeMap16`
receivers need their own arm or must fall to `doAssociativeCached`.

**1b. Read the hint in the emitter.** In `ExprToBytecode`, before the `VarExpr` branch
at `:1102`, check `ve.var`'s meta for `:cloffle/op`, keyed by arity:

```clojure
(def ^{:cloffle/op {3 :KeywordAssoc}} assoc ...)
```

Emit `KeywordAssoc` when arg 1 is a constant `Keyword`, `MapAssoc` otherwise, and fall
through to `emitInvokeVar` whenever anything does not fit. The specialization must
carry `assumptions = "var.getRootAssumption()"` so a `with-redefs` deopts and
re-specializes to the generic Var call.

Whether user code may opt in is a policy call. Recommendation: **core-only and
undocumented** for now. `COMPATIBILITY_RISK_AUDIT.md` §8 already flags the locally-added
`get-in` `:inline` as an unwanted expansion of non-redefinable surface; do not repeat
that. Note also that `:cloffle/op` in Var meta is observable via `(meta #'assoc)`,
which is a (low-severity) divergence from stock in the same family as Finding 11.

**1c. Multi-arity.** `(assoc m :a 1 :b 2)` must not regress. Either unroll at compile
time into nested single-key ops (`PARTIAL_ESCAPE_ANALYSIS.md` §C describes this) and
feed each into the arity-3 lowering, or leave variadic on the Var path. Do not lower
`RestFn.applyTo` shapes.

## Phase 1 — RESULTS (2026-09-09), landed as the minimal slice

Scope taken: `KeywordAssoc` only — arity 3 with a **literal keyword** key. `MapAssoc` (non-constant
key) and 1c multi-arity unrolling were deliberately left out; `(assoc m k v)` with a computed key and
`(assoc m :a 1 :b 2)` both still take the Var path.

**What landed**

| File | Change |
| --- | --- |
| `CloffleBytecodeRootNode.java` | `KeywordAssoc` operation, constant operands `(Var, Keyword)` |
| `CloffleBytecodeRootNode.java` | `@GenerateBytecode(enableSpecializationIntrospection = true)` |
| `ExprToBytecode.java` | `:cloffle/op` reader + emit branch before the `VarExpr` branch |
| `Var.java` | `loweringRoot` — the sanctioned root, see the correctness note below |
| `core.clj` | `:cloffle/op {3 :KeywordAssoc}` on `#'clojure.core/assoc` |
| `AssocLoweringIntrospectionTest.java` | the missing "which specialization is live" gate |
| `build.clj` | `:alloc-budget 0` for `consume-assoc`, `consume-assoc-no-let`, `ephemeral-pipeline` |

**The plan was wrong about the guard, and the smoke test caught it.** This plan says the
specialization "must carry `assumptions = "var.getRootAssumption()"` so a `with-redefs` deopts and
re-specializes to the generic Var call." That is **not sufficient**, and the first build using it
silently ignored `with-redefs` on `assoc` — Finding 2, reopened exactly as feared.

The reason: `bindRoot` invalidates the old assumption **and installs a fresh valid one**
(`Var.java:102`). A call site guarded only on the assumption therefore deoptimizes, re-specializes,
caches the *new* assumption, and happily keeps running the intrinsic against a redefined root. The
assumption answers "has the root changed since I cached?", but the question that matters is "is the
root still the one that sanctioned this lowering?"

Fix: `Var.loweringRoot`, captured write-once the first time a Var has both a root and `:cloffle/op`
metadata (from `bindRoot` / `resetMeta` / `alterMeta`, so `def` ordering does not matter). The
operation's specializations bind `loweringAssumption(var)`, which returns the root assumption only
while `getLoweringRoot() == getRawRoot()` and `Assumption.NEVER_VALID` otherwise. An invalid
assumption makes the Truffle DSL decline to install the instance at all, so execution falls to
`doRedefined`. Restoring the original root afterwards leaves the site permanently generic — correct,
just not re-optimized. **Anyone doing Phase 2 must reuse `loweringAssumption`, not
`getRootAssumption` directly.**

**Measurements** (`record-alloc-budgets :snippet`, thrpt, wi=3 i=3; before/after this phase)

| Snippet | B/op before | B/op after | ops/s before | ops/s after |
| --- | --- | --- | --- | --- |
| `consume-assoc` | 128.0 | **0.0** | 80.9M | **214.7M** |
| `consume-assoc-no-let` | 128.0 | **0.0** | 83.5M | **240.2M** |
| `ephemeral-pipeline` | 128.0 | **0.0** | 80.9M | **230.1M** |
| `assoc-only` | 128.0 | 64.0 | 85.1M | 189.5M |
| `assoc-return-nil` | 128.0 | 64.0 | 75.4M | 151.1M |
| `guestShapeMapEphemeralPipeline` | 152.0 | **24.0** | 13.90 ns/op | **5.16 ns/op** |

`guestShapeMapEphemeralPipeline` lands exactly on its 24 B/op budget and beats the 12 ns/op that
`GRAAL_GRAPH_ANALYSIS.md` recorded as the target. `assoc-only` still allocates 64 B because the map
genuinely escapes as the return value; `assoc-return-nil` also stays at 64 B because its
`(= (:b m2) :v999)` goes through `clojure.core/=`, an opaque call that keeps the map alive.

**Gates**

- `check-scalar-replacements :suite :guest` — 28/28 pass, `guestShapeMapEphemeralPipeline` at budget.
- `run-tests` — 934/934 pass (fresh).
- `run-clj-tests` — 636 tests / 19026 assertions, 0 failures 0 errors.
- `compat-test` — only the known pre-existing `reitit.walk-test/keywordize=walk-keywordize` failure.
- `AssocLoweringIntrospectionTest` — 3/3. Asserts `doShapeMap` is live with exactly one cache entry
  for a stable shape, that a 5th layout exhausts the limit-4 cache into `doShapeMapGeneric`, and that
  `with-redefs` moves the site to `doRedefined` and *excludes* every fast specialization.

**`dev/compat-audit/probe2_intrinsics_printdup.clj` cannot be promoted to CI as-is.** Only its first
probe runs; every subsequent one dies with
`ClassCastException: clojure.lang.Keyword cannot be cast to clojure.lang.Var`. Verified identical on
a clean stash of this branch, so it is a pre-existing bug in the probe script, not a regression. The
Tier 2 acceptance test for `assoc` is `AssocLoweringIntrospectionTest#withRedefsRetiresTheLoweringPermanently`
instead, which is stronger anyway: it asserts the specialization state, not just the return value.
Repairing probe2 is worth doing before Phase 2 reaches `dissoc` and `conj`.

**Deferred from this phase:** `MapAssoc` for non-constant keys, and 1c multi-arity unrolling.

## Phase 2 — generalize (mechanical, only after Phase 1 is green)

In this order, because it is increasing risk:

1. `get` → `{2 :KeywordLookup, 3 :KeywordLookupDefault}` — Tier 1, and the ops already
   exist. Lowest risk change in the whole plan. **DONE (2026-09-09) — see results below.**
2. ~~`nth` → `{2 :VectorNth2, 3 :VectorNth3}`~~ — **ABANDONED (2026-09-09). Do not
   rebuild.** See "Phase 2 step 2 — RESULTS" below. The "measure before building" caveat
   was right and the measurement came back decisively negative.
3. `count` → `{1 :CollectionCount}` — Tier 1; a `CollectionCount` intrinsic is already
   listed as retained in `GRAAL_GRAPH_ANALYSIS.md` §4.
4. `dissoc` → `{2 :KeywordDissoc}` — Tier 2. `DissocTransition` and
   `Dissoc16Transition` (9→8 demotion) both exist.
5. `conj` → `{2 :?}` — Tier 2, and **lowest priority**. `GRAAL_GRAPH_ANALYSIS.md`
   records that `VectorConj` / `VectorPop` / `VectorPeek` were dropped with **zero**
   impact on PEA and identical latency, because destructuring lowers to `first` /
   `rest` / `nth` / `seq?`. Do not rebuild these without a benchmark that moves.

### Phase 2 step 1 — RESULTS (2026-09-09), `get` landed

`:cloffle/op {2 :KeywordLookup, 3 :KeywordLookupDefault}` on `#'clojure.core/get`, emitted when the
key is a literal `Keyword`. No new operations were written: `KeywordLookup` and `KeywordLookupDefault`
already existed for `(:k m)`, and they are exact matches for `get` because `Keyword.invoke(obj)` is
`RT.get(obj, this)` (`Keyword.java:180`) and the forked `get` is literally `(. clojure.lang.RT (get
map key))` (`core.clj:1391`). The emitter's `:cloffle/op` reader was generalized from a single
boolean check to an arity → operation lookup, which is the shape the rest of Phase 2 needs.

**No root guard, deliberately.** Verified against upstream at
`/Users/karl-medplum/Development/digital-alchemy/clojure`: `get` carries `:inline` with
`:inline-arities #{2 3}` (`core.clj:1512`), so stock already compiles `(get m :k)` to `RT.get` and
ignores `with-redefs`. Our lowering is *narrower* than stock's — it only fires for a literal keyword
key, so `(let [k :a] (get m k))` still honors redefinition where stock would not. We are strictly
more redefinable than stock here, never less.

**Allocation, guest suite, before → after this step** (unchanged rows omitted; 31/31 pass):

| Benchmark | B/op before | B/op after |
| --- | --- | --- |
| `guestMiddlewarePipeline` | 280.0 | **216.0** |
| `guestCondOptionPipeline` | 280.0 | **216.0** |
| `guestRingResponsePipeline` | 176.0 | **112.0** |

A flat 64 B/op off each of the three pipelines that read maps through `get`; nothing else moved and
nothing regressed. Snippets `rt-get-lookup` and `nested-get-in` both measure 0.0 B/op.

**Gates:** `run-tests` 936/936, `run-clj-tests` 636 tests / 19026 assertions 0 failures,
`compat-test` only the known reitit failure, `check-scalar-replacements :suite :guest` 31/31.
`AssocLoweringIntrospectionTest` grew to 5 tests: `(get m :k)` and `(get m :k default)` are asserted
to land on `KeywordLookup` / `KeywordLookupDefault` with `doShapeMap` live, and `(get m k)` with a
computed key is asserted **not** to emit a lowered instruction at all.

**Known divergence:** `(meta #'get)` now reports `:cloffle/op` and does not report `:inline` /
`:inline-arities`. This trades one metadata divergence from stock for another rather than adding a
new class of them, and is the same low-severity family as Finding 11.

### Phase 2 step 2 — RESULTS (2026-09-09), `nth` abandoned and reverted

Built `VectorNth2` / `VectorNth3`, wired `:cloffle/op {2 :VectorNth2, 3 :VectorNth3}` onto
`#'clojure.core/nth`, and added four probe snippets (`nth-literal`, `nth-chain`, `nth-default`,
`nth-tuple2`) to measure it. The result was a **17x throughput regression**, so the whole step was
reverted; the tree is back to the state after step 1.

| Snippet | ops/s before | ops/s after lowering | B/op before | B/op after |
| --- | --- | --- | --- | --- |
| `nth-literal` | ~120M | **7.4M** | 40.0 | **392.0** |

Bisected by deleting only the `:cloffle/op` metadata from `nth` and leaving the operations in place:
throughput returned to ~120M, which pins the cause on the operations themselves rather than on any
other change in the step. Non-`nth` snippets (`keyword-invoke`, `consume-assoc`) never moved,
confirming the damage was local.

**Why it failed, and why it was never going to work.** `nth`'s key operand is an *index*, not a
keyword. The existing fast path is the MethodHandle route for primitive-signature statics that
`43af52d0` installed, which calls `RT.nth(Object, int)` with the index as a genuine primitive.
Routing through a bytecode operation puts the index through the generic `Object` operand stack, so
every call boxes a `Long` and then unboxes it via `RT.intCast`. The lowering layer's leverage comes
from turning a *constant keyword* into a constant operand, and an index has no equivalent to fold.
`TODO_tuple.md`'s note that `43af52d0` already recovered this ground — `tuple-destructure` sits at
0 B/op — was the correct read.

**Rule this establishes:** the `:cloffle/op` lowering layer is for **reference-keyed** operations.
Do not extend it to operations whose hot operand is numeric; those are already better served by the
primitive-signature MethodHandle path, and a bytecode operation can only add boxing. This retires
step 2 permanently and is also why `count` (step 3) should be measured with suspicion.

### Benchmark fixture policy — no numbers (2026-09-09)

Adopted while reverting `nth`, because that step showed how easily a boxing cost gets misread as a
lowering result. Benchmark fixtures carry **reference values, not numbers**, so a measurement moving
means the map/collection work moved and not that `Integer`/`Long` boxing did.

Swept: `keyword-map-benchmark/setup.clj` and the snippets (map values → keywords, `inc` → `identity`,
`(reduce + 0 …)` → a reference fold, status codes `200`/`201` → `:ok`/`:created`, numeric ids and
timeouts → strings), the arguments `KeywordMapBenchmark` feeds guest functions, and the host-side
`PersistentShapeMap` controls (keyword values, `Object` returns instead of `public int` +
`((Integer) x).intValue()`; `shapeMap3EphemeralSeqSum` became `shapeMap3EphemeralSeqWalk`).

The host controls were the one real risk, since the `int` return and unbox were how they forced the
value to be consumed as a scalar and thereby demonstrated scalar replacement. Measurement says the
signal survived: all of them still report **0.0 B/op**, and `check-scalar-replacements` is 55/55 with
every recorded budget unchanged.

**Two knowingly-excluded cases.** `guest-hiccup-normalize` (indexes with `nth`) and
`guest-pipeline-take-drop` (numeric `take`/`drop` counts) keep their numbers, because the number *is*
the workload and removing it would delete what they model. Both are marked provisional in source and
in `build.clj`; they are workload samples, not benchmarks, until a primitive-specialization pass makes
numeric operands measurable. Revisit them then.

## Phase 3 — transients reach the tuple ladder (independent track)

`TODO_tuple.md` §3, open. `TransientVector.persistent()` unconditionally constructs a
`PersistentVector`, so `(into [] [1 2])` is a `PersistentVector` even for 1–8 elements,
and a tuple that goes transient never comes back as one
(`PersistentTuple.asTransient` re-conjes through `PersistentVector.EMPTY.asTransient()`).

Fix: have `persistent()` hand small results to `PersistentTuple.createFromArray`. Harder
than it sounds because the transient tail/root are already allocated by then. Independent
of Phases 0–2; schedule separately.

## Cross-cutting: the loop phi may be the real cause

Both `TODO_tuple.md` and `FIXME_shape_map_alloc.md` end at the **same** mechanism — a
`ValuePhiNode` at a `MERGE_EXPLODE` loop header holding the object. The ShapeMap
survivors report it verbatim:

```
      used by ValuePhiNode #1874 (values) [loop merge]
        loop-carried: a phi at a loop header cannot stay virtual.
```

For tuples the fix was **not** the type or the call path — it was
`ExprToBytecode.clearBindingsDeadInBody` (`e84ddebd`) clearing `let*` bindings the body
cannot read.

That fix **cannot** apply here as written, and `FIXME_shape_map_alloc.md` hypothesis 3
says why: in `guest-ephemeral-pipeline` the body *does* read `m`, so nothing is cleared
even though the last read happens before the return. The generalization would be
**clearing at last use** rather than at "never read", which was deliberately not
attempted. The two-commits-for-one-`assoc` observation is what makes it worth testing.

**Therefore:** it is entirely possible that lowering and last-use clearing are **both**
required and neither alone moves the number. The snippets below are built to separate
exactly this, and doing so is cheaper than either implementation.

## Escape-probe snippets to build on

Committed at `0755d652` ("test(benchmark): add assoc escape-probe snippets"), an
isolation ladder for the assoc allocation. Use it; do not duplicate it.

| Snippet | Body | Isolates |
| --- | --- | --- |
| `assoc-only.clj` | `(let [m {...}] (assoc m :b :v999))` | result escapes as return value |
| `assoc-return-nil.clj` | `(let [m2 (assoc {...} :b :v999)] (when (= (:b m2) :v999) nil))` | result fully dead |
| `consume-assoc.clj` | `(let [m {...}] (:a (assoc m :b :v999)))` | scalar consumed, `let`-bound source |
| `consume-assoc-no-let.clj` | `(:a (assoc {...} :b :v999))` | scalar consumed, no `let` local |
| `ephemeral-pipeline.clj` | `(let [m {...}] (:a (assoc m :a "replacement")))` | the budgeted case (update, not insert) |

`SnippetBenchmark.java` / `SnippetBenchmarkSupport.java` carry the registrations.

**Run this ladder before implementing anything.** The two readings that matter:

- `consume-assoc-no-let` clean but `consume-assoc` not → the frame local is the cause,
  hypothesis 3 wins, and the fix is last-use clearing rather than lowering.
- both allocate equally → the local is irrelevant and hypothesis 1 (lowering) is the
  live candidate.

Either result reorders this plan, and the measurement costs one benchmark run.

## Gates

Three orthogonal gates. One does not exist yet and is the reason this rotted.

**Redefinition (Tier 2 acceptance test).**
`dev/compat-audit/probe2_intrinsics_printdup.clj` already probes `with-redefs` on
`get`, `nth`, `assoc`, `dissoc`, and `conj` — exactly this var set — plus
`with-redefs-fn` and `apply` bypass in `probe3_root_cause.clj`. Promote these from dev
scripts to something CI runs. **This, not a benchmark, is the acceptance test for
Tier 2.**

**Allocation.** `clojure -T:build check-scalar-replacements :suite :guest`, budget for
`guestShapeMapEphemeralPipeline` left at **24** B/op (`build.clj:1841`). Do not raise
it to go green; `record-alloc-budgets` deliberately refuses to bless a regression.

**Which specialization is live — MISSING, build it.** A `GuestCompilationUnitTest`-style
assertion using `Introspection.getSpecializations` after warmup, asserting `doShapeMap`
is active and `doShapeMapGeneric` is not. There is currently **no** use of
`Introspection` anywhere in `src/`. This is the invariant that silently rotted through
four commits while `run-tests` and `run-clj-tests` stayed green, and no existing suite
catches it. `FIXME_keyword_invoke_perf.md` §Plan step 3 independently reaches the same
conclusion.

**Regression suites, unchanged:** `run-tests`, `run-clj-tests`, `compat-test`. Note
`compat-test` has one known pre-existing failure,
`reitit.walk-test/keywordize=walk-keywordize` (a `*unchecked-math*` issue tracked in
`TODO_reflection_math.md` §2) that reproduces on a clean baseline.

Per `.cursor/rules`: `run-tests` and `run-clj-tests` clean `target` by default; use
tools.build (`build.clj`) only, never Ant or Maven.

## Non-goals — do not do these

- **`IAssoc` / `IDisassoc` interfaces.** Widens return types (PEA needed
  `PersistentTuple.create` to return `PersistentTuple2`, not `IPersistentVector`),
  flattens map-vs-indexed semantics, and lists have no `assoc` at all.
  `Associative` + `IPersistentMap.without` + `Indexed`/`assocN` already match the
  language. The cache key that matters is `(MapShape × Keyword)`, which only exists on
  ShapeMap and cannot be expressed as an interface.
- **Making `PersistentVector` scalar-replace.** The trie has `Object[]` payloads, a
  layout that varies with `cnt`, path-copying `clone()` on every update, and
  `AtomicReference<Thread>` identity on `Node`. `@CompilationFinal` on `tail` is a
  *partial evaluation* hint for constant vectors, not a PEA claim. Treat this class as
  the intentional non-virtual remainder.
- **Destructuring metadata as a shape hint.** `{:keys [status headers body]}` is an
  open read-set; a `MapShape` is a closed, exact key layout. Compiling the former into a
  shape guard breaks the moment any middleware `assoc`s an extra key.
- **A global PIC `limit` bump as the primary fix.** Lowering multiplies the *number* of
  caches (one per call site); raising `limit` only deepens one shared cache. Do lowering
  first, then measure whether any site is still cache-starved.
- **Raising the 24 B/op budget.**
- **Touching the bytecode cache / archive**
  (`src/jvm/net/javacrumbs/cloffle/bytecode/archive/`). Leave that code alone. Whether a
  lowered, archived `clojure.core` call site honors `with-redefs` is a real question and
  a deliberately **separate** effort — do not let it block or reshape this plan, and do
  not "fix" it opportunistically here.

## Success criteria

- [x] Phase 0a run: the ladder points at **lowering**. The frame local is irrelevant
      (`consume-assoc` == `consume-assoc-no-let` to the byte).
- [x] `KeywordAssoc` exists, and `AssocLoweringIntrospectionTest` proves `doShapeMap` is the live
      specialization for a stable-shape guest `assoc`.
- [x] `with-redefs` on `assoc` is honored — but via `AssocLoweringIntrospectionTest`, **not**
      `probe2`, which is broken independently of this work.
- [x] `check-scalar-replacements :suite :guest` fully green with the budget still at 24;
      `guestShapeMapEphemeralPipeline` at 24.0 B/op and 5.16 ns/op, past the 12 ns/op target.
- [x] `run-tests`, `run-clj-tests`, `compat-test` unchanged (modulo the known reitit failure).
- [x] Stale docs corrected; Phase 0 and Phase 1 results recorded above.
- [x] The fix was lowering, not dead-local clearing. The cross-cutting loop-phi hypothesis is not
      disproven in general, but it is not what these snippets were hitting.
- [ ] Repair `probe2_intrinsics_printdup.clj` and put it in CI before Phase 2 reaches `dissoc`/`conj`.

## Pointers

| Item | Location |
| --- | --- |
| Emitter hook point | `ExprToBytecode.java:1102` (`VarExpr` branch of `InvokeExpr`) |
| Reference lowering | `ExprToBytecode.java:898` (`KeywordInvokeExpr` → `beginKeywordLookup`) |
| Reference op | `CloffleBytecodeRootNode.java:1930` (`KeywordLookup`) |
| Assumption plumbing | `Var.java:98` `getRootAssumption`; `CloffleBytecodeRootNode.java:1597` usage |
| Assoc transitions | `PersistentShapeMap.java:613`+; `assocTransition` at `:726`; `matches` at `:623` |
| Dissoc transitions | `PersistentShapeMap.java:744`+, `dissocTransition` at `:813`; `PersistentShapeMap16.java:832`+ |
| Transition tests | `PersistentShapeMapTest.java:306` (assoc), `:364` (dissoc), `:432` (16→8 demote) |
| Guest assoc test | `src/test/resources/guest-compilation/assoc-transition.clj` |
| Budgeted guest fn | `keyword-map-benchmark/setup.clj` `guest-ephemeral-pipeline` |
| Catalog + budget | `build.clj:1841` |
| Redefinition probes | `dev/compat-audit/probe2_intrinsics_printdup.clj`, `probe3_root_cause.clj` |
| Graph technique | `HOWTO_SEAFOAM.md` (note: snippets cannot be diagnosed from a dump — the guest root is anonymous; use a named `KeywordMapBenchmark` guest fn) |
| Upstream Clojure | `/Users/karl-medplum/Development/digital-alchemy/clojure` |
