# OPEN — `nth` cannot be lowered, and it lost `:inline`, which breaks `with-redefs`

Two independent findings about the same Var, recorded together because the second is caused by the
same design decision that makes the first unfixable.

1. **Lowering `nth` to a bytecode operation is a 17x regression.** Measured and reverted
   2026-09-09. Do not rebuild. *(Closed — the answer is "don't".)*
2. **The fork dropped upstream's `:inline` on `nth`, which makes `with-redefs [nth ...]` a one-way
   trapdoor** that permanently corrupts `#'nth` and every later `with-redefs` in the JVM.
   *(Open — needs a decision.)*

## 1. Lowering: measured, rejected, do not rebuild

`VectorNth2` / `VectorNth3` operations plus `:cloffle/op {2 :VectorNth2, 3 :VectorNth3}` on
`#'clojure.core/nth`, emitted from `ExprToBytecode`'s `InvokeExpr` branch like `KeywordAssoc`.

| Snippet | ops/s before | ops/s after | B/op before | B/op after |
| --- | --- | --- | --- | --- |
| `nth-literal` | ~120M | **7.4M** | 40.0 | **392.0** |

Bisected by deleting *only* the `:cloffle/op` metadata and leaving the operations in place:
throughput returned to ~120M. That pins the cause on the operations themselves, not on anything else
in the change. `keyword-invoke` and `consume-assoc` never moved, so the damage was local.

### Cause

`nth`'s hot operand is an **index**, not a keyword.

`43af52d0` ("perf(interop): dispatch primitive-signature static methods via MethodHandle") already
routes `(. clojure.lang.RT (nth coll index))` through an unreflected `MethodHandle` whose signature
is `RT.nth(Object, int)` (`RT.java:1133`), so the index stays a genuine primitive end to end.

A bytecode operation cannot. Operands travel the generic `Object` stack, so every call boxes a
`Long` on the way in and unboxes it again via `RT.intCast` on the way out. That is the entire
regression, and it is structural rather than a tuning problem.

The lowering layer's leverage is folding a **constant keyword** into a constant operand. An index has
no equivalent to fold. `TODO_tuple.md`'s note that `43af52d0` had already recovered this ground was
correct: `tuple-destructure` sits at 0 B/op (`build.clj:1911`).

### The rule this establishes

> The `:cloffle/op` layer is for **reference-keyed** operations. Do not extend it to operations whose
> hot operand or result is numeric — those are already better served by the primitive-signature
> MethodHandle path, and a bytecode operation can only add boxing.

This is why `count` was skipped without being built: `RT.count` *returns* a primitive `int`, so it
would box every result for the same reason.

## 2. The `:inline` divergence — OPEN

The fork strips **all** `:inline` metadata from `core.clj`: 129 occurrences upstream, zero here (the
single `grep` hit is a docstring). `Compiler.java` implements no `:inline` expansion at all — see its
own comments at `:5872`, `:7288`, and `:7509`, which all read "without `:inline`". `:cloffle/op` is
the deliberate replacement mechanism.

For most Vars this makes us *more* redefinable than stock, which is a feature. For `nth` it is a
correctness bug, because core's own machinery is written in terms of `nth`.

Upstream (`clojure/core.clj:896`):

```clojure
{:inline (fn  [c i & nf] `(. clojure.lang.RT (nth ~c ~i ~@nf)))
 :inline-arities #{2 3}
 :added "1.0"}
```

Ours (`core.clj:859`) has only `{:added "1.0"}`, so `(nth coll i)` always goes through the Var.

Now `with-redefs-fn` (`core.clj:7438`), whose restore path is:

```clojure
(let [root-bind (fn [m]
                  (doseq [[a-var a-val] m]
                    (.bindRoot ^clojure.lang.Var a-var a-val)))
```

Destructuring `[a-var a-val]` from a `MapEntry` compiles to `nth` calls. On stock those inline to
`RT.nth` and never touch the Var, so redefining `nth` is survivable. Here they hit the **redefined**
Var, `a-var` becomes `:redefined`, and the `ClassCastException` aborts the `finally`.

So the teardown that would restore `nth` is itself written in terms of `nth`. `#'nth` keeps the mock
as its root for the life of the JVM, and because *every* later `with-redefs` runs the same
destructuring, all of them throw too.

### Reproduce

```sh
clojure -T:build cloffle-repl :args '["dev/compat-audit/probe2_intrinsics_printdup.clj"]'
```

Probe 1 (`redef/get`) prints. Probe 2 (`redef/nth`) throws, and probes 3–40 all die with
`ClassCastException: clojure.lang.Keyword cannot be cast to clojure.lang.Var` — in their own
`with-redefs` machinery, not in the thing they were testing.

Minimal version, verified side by side:

| Step | Cloffle | Stock |
| --- | --- | --- |
| `(nth [:a :b :c] 0)` | `:a` | `:a` |
| `(with-redefs [nth ...] (nth [:a :b :c] 0))` | **THREW ClassCastException** | `:a` |
| `(nth [:a :b :c] 0)` afterwards | **`:redefined`** | `:a` |
| `(with-redefs [str ...] :ok)` afterwards | **THREW ClassCastException** | `:ok` |

`(.getRawRoot #'clojure.core/nth)` still returns the mock closure after the failed restore, which
confirms the `finally` never completed.

### Fix options

**Reinstating `:inline` will not work** — this compiler ignores it. Real options:

- **Make `with-redefs-fn`'s bind/restore independent of redefinable Vars.** Iterate with host
  interop and `key`/`val`, or move `root-bind` into a Java helper. Smallest change that closes the
  trapdoor. Diverges from upstream source text while staying behaviourally identical on stock.
- **Give `nth` a `:cloffle/op`-era equivalent of `:inline`** so arities 2 and 3 compile straight to
  `RT.nth` and ignore redefinition, matching stock exactly. Note this *reduces* redefinability to
  stock levels rather than fixing the general trapdoor.
- **Do nothing, and document it.** Currently in force.

The same trapdoor latently applies to any core fn that `with-redefs-fn`, `doseq`, or `zipmap`
themselves depend on — `first`, `next`, `seq`, and `count` are all candidates and are **not yet
tested**. Worth enumerating before choosing an option, since it decides whether the fix has to be
general or can be `nth`-specific.

## Still open

- The decision above. `probe2_intrinsics_printdup.clj` cannot go into CI until it is made, because
  probe 2 poisons the rest of the run.
- Whether `first` / `next` / `seq` / `count` share the trapdoor. Unmeasured.
- No throughput gate exists for `nth`. The probe snippets that caught the 17x regression
  (`nth-literal`, `nth-chain`, `nth-default`, `nth-tuple2`) were deleted with the revert, so nothing
  would catch a re-introduction. The `conj` ladder was kept for exactly this reason; `nth`'s was not.

## Pointers

| Item | Location |
| --- | --- |
| Fork `nth` (no `:inline`) | `src/clj/clojure/core.clj:859` |
| Upstream `nth` (`:inline`) | `/Users/karl-medplum/Development/digital-alchemy/clojure/src/clj/clojure/core.clj:891` |
| `with-redefs-fn` restore path | `src/clj/clojure/core.clj:7438`, `root-bind` at `:7447` |
| Primitive-signature dispatch | `src/jvm/net/javacrumbs/cloffle/bytecode/BytecodeStaticMethod.java`, commit `43af52d0` |
| `RT.nth(Object, int)` | `src/jvm/clojure/lang/RT.java:1133` |
| "without `:inline`" comments | `src/jvm/clojure/lang/Compiler.java:5872`, `:7288`, `:7509` |
| Redefinition probe | `dev/compat-audit/probe2_intrinsics_printdup.clj` |
| Full lowering narrative | `TODO_lowering_layer.md`, "Phase 2 step 2 — RESULTS" |
| Revert / diagnosis commits | `a78dcbcd` (revert), `ea1df2f3` (diagnosis) |
