# REJECTED — do not specialize `nth` / `count` as `:cloffle/op` bytecode operations

Two independent attempts to lower `#'clojure.core/nth` (and, by the same rule, `count`) through
the `:cloffle/op` bytecode layer failed. Neither is a tuning problem. Do not re-wire the
operations that remain in `CloffleBytecodeRootNode` unless a new design beats the existing
`MethodHandle` path *and* compiles under Graal without a bailout.

Related: [`FIXME_nth.md`](FIXME_nth.md) (the 17× boxed-index measurement, plus the separate
`with-redefs` trapdoor). This file is the performance / compilation record for the primitive-
specialization attempt.

---

## Current state (2026-09-09)

- `#'clojure.core/nth` and `#'clojure.core/count` carry **no** `:cloffle/op` metadata.
- `ExprToBytecode` still has a dispatch arm for `NumbersNth` / `NumbersCount`, but it is dead
  without the metadata.
- `CloffleBytecodeRootNode.NumbersNth` / `NumbersCount` exist as **unwired** operations, annotated
  `REJECTED` / experimental. They are kept so the next attempt does not rebuild the same shape
  from scratch.
- The hot path remains `(. clojure.lang.RT (nth coll index))` / `RT.count`, dispatched by
  `BytecodeStaticMethod` through an unreflected `MethodHandle` (`43af52d0`).
- `BytecodeNumbersLoweringIntrospectionTest` deliberately does **not** re-arm `nth` / `count`.

---

## Attempt 1 — boxed index (`VectorNth2` / `VectorNth3`, 2026-09-09)

The first lowering copied the keyword-assoc pattern: a bytecode operation with an `Object`
operand stack. Throughput on `nth-literal` dropped from ~120M ops/s to **7.4M** (~17×), and
allocation from 40 B/op to **392 B/op**.

Bisect: deleting *only* the `:cloffle/op` metadata, leaving the operations in the class file,
restored ~120M ops/s. The operations themselves were the cost.

**Cause.** `nth`'s hot operand is an *index*, not a constant keyword. The MethodHandle path
calls `RT.nth(Object, int)` with a genuine primitive index. A bytecode operation on the generic
`Object` stack boxes a `Long` on the way in and unboxes it again via `RT.intCast`. There is
nothing to fold — unlike `KeywordAssoc`, which caches a constant `Keyword`.

That established the rule used for `count` as well: `RT.count` *returns* a primitive `int`, so
a bytecode operation that produces `Object` would box every result for the same reason.

Full numbers and the keyword-vs-index argument: [`FIXME_nth.md`](FIXME_nth.md) §1.

---

## Attempt 2 — primitive index (`NumbersNth` / `NumbersCount`, primitive-specialization)

The primitive-specialization work tried again with a different hypothesis: if the index travels
as `long` (`ConstLong` / `UnboxLong`) and the operation calls `RT.nth(coll, RT.intCast(index))`
from a `doLong` specialization, the 17× boxing tax should disappear.

That hypothesis was never allowed to become the default path. Wiring `:cloffle/op` on `nth`
caused Graal to refuse compilation:

```text
com.oracle.truffle.compiler.nodes.OptimizationFailedException:
Too deep inlining, probably caused by recursive inlining
```

(also seen as `PermanentBailoutException`). The first place it showed up was
`GuestCompilationUnitTest` once `NumbersNth` was live on ordinary `(nth coll i)` call sites.

**Why that is structural.** `RT.nth` is a large, highly polymorphic host method (vectors,
strings, arrays, lists, sequences, maps-with-integer-keys, the not-found arity). Putting it
behind a Truffle operation that Graal can inline into every bytecode continuation gives the
compiler a recursive inlining problem: each specialized call site pulls in `RT.nth`, which
pulls in more call sites, which pull in more `nth`. The MethodHandle path does not do that —
it is one host call with a fixed signature, not a bytecode op Graal treats as a PE root.

`count` was never independently measured after that bailout. It was rejected with `nth` because:

1. The same `:cloffle/op` wiring pattern would put `RT.count` / `Counted.count()` on the same
   inlining surface.
2. Attempt 1 already predicted a boxing tax on the `int` *return* at any Object boundary
   (`EnsureObject` / store-local). `NumbersCount.doCounted` returning `int` only wins if the
   consumer stays unboxed; most Clojure call sites do not.

The plan's acceptance criterion was explicit: a lowering that regresses throughput or fails
Graal compilation is rejected, even if it is semantically correct.

---

## Correctness and stock compatibility

Rejecting the specialization does **not** change what `nth` / `count` *return* relative to stock.
Both Cloffle's current MethodHandle path and stock's `:inline` expansion compile to the same host
calls (`RT.nth`, `RT.count`). Bounds checks, `not-found`, `nil` receivers, strings, arrays, and
seqs stay on that shared implementation.

What *does* diverge from stock is call-site shape, not the collection contract:

| | Stock 1.12 | Cloffle (ops unwired, today) |
| --- | --- | --- |
| `nth` / `count` values and exceptions | `RT.nth` / `RT.count` via `:inline` | same host methods via Var → `StaticMethod` |
| `:inline` on `nth` (`#{2 3}`) and `count` | yes | **no** (stripped with the rest of core `:inline`) |
| `(with-redefs [nth f] (nth coll i))` | inlined sites **ignore** `f` | call sites **see** `f` |
| `(with-redefs [count f] (count coll))` | inlined sites **ignore** `f` | call sites **see** `f` |
| `with-redefs` teardown using `nth` | survives (inlined `RT.nth`) | historically a **trapdoor** (see below) |

**The trapdoor is a real Cloffle-vs-stock bug, independent of this lowering.** Stock inlines
`nth` inside `with-redefs-fn`'s restore destructuring, so redefining `nth` cannot poison
`.bindRoot`. Cloffle's `nth` is an ordinary Var, so a naive `with-redefs [nth ...]` used to
leave the mock as root for the rest of the JVM. That is tracked as **fixed** in
[`FIXME_with_redefs_trapdoor.md`](FIXME_with_redefs_trapdoor.md) (`count` was already clean).
Do not "fix" it by re-enabling `NumbersNth`; that would not restore stock `:inline` semantics
anyway.

**Had `NumbersNth` / `NumbersCount` been left wired**, values would still have gone through
`RT.nth` / `RT.count` (or `Counted.count()`), so the collection contract would still match
stock. Two compatibility caveats on that design:

1. **`with-redefs` would still not match stock.** The ops use `Var.loweringRoot` /
   `doRedefined`, so a redefined Var falls back to `IFn` — more redefinable than stock
   `:inline`, same as today's Var path. Stock inlined sites never consult the Var.
2. **Arity coverage was incomplete vs stock.** Stock inlines `nth` at arities 2 *and* 3.
   `NumbersNth` only implemented the two-argument form (`coll`, `index`). The `not-found`
   arity would have stayed a generic Var invoke. Wiring `{2 :NumbersNth}` without arity 3
   is therefore not a stock-inline equivalent even if Graal had accepted it.

The Graal `Too deep inlining` bailout is a Cloffle compilation failure, not a mismatch with
stock's numeric or collection semantics.

---

## Why the MethodHandle path already wins

| Path | Index / count | What Graal sees |
| --- | --- | --- |
| `StaticMethod` + `RT.nth(Object, int)` | primitive `int` end to end | one host MethodHandle |
| `NumbersNth.doLong` | `long` → `RT.intCast` → `RT.nth` | Truffle op + inlinable host graph |
| `VectorNth` (attempt 1) | boxed `Long` | Truffle op + extra box/unbox |

`tuple-destructure` sitting at 0 B/op (`build.clj` allocation gates) is the MethodHandle path
doing its job. A bytecode operation cannot fold an index the way it folds a keyword, so it has
no compensating win.

---

## What would have to be true to reopen this

All of:

1. `GuestCompilationUnitTest` (and a full `run-tests`) stay green with `:cloffle/op` wired on
   `nth` / `count` — no `OptimizationFailedException` / `PermanentBailoutException`.
2. `nth-literal` / `nth-chain` / equivalent snippets beat the MethodHandle baseline in
   throughput *and* B/op (the 17× / 392 B/op numbers are the floor to beat).
3. `count` is measured independently, including the Object-boundary boxing of the `int` result.
4. `with-redefs` still falls through `doRedefined` (the sanctioned-root assumption). This is
   independent of the trapdoor in [`FIXME_with_redefs_trapdoor.md`](FIXME_with_redefs_trapdoor.md).

Until then, leave the metadata off. The dead operations in `CloffleBytecodeRootNode` are a
warning, not a starting point to re-enable.

---

## Pointers

| Item | Location |
| --- | --- |
| Unwired ops | `CloffleBytecodeRootNode.java` (`NumbersNth`, `NumbersCount`) |
| Dead emit arms | `ExprToBytecode.java` (`OP_NUMBERS_NTH`, `OP_NUMBERS_COUNT`) |
| Core Vars (no `:cloffle/op`) | `src/clj/clojure/core.clj` `nth`, `count` |
| Why tests skip them | `BytecodeNumbersLoweringIntrospectionTest` `setUp` |
| Primitive MethodHandle path | `BytecodeStaticMethod.java`, commit `43af52d0` |
| `RT.nth(Object, int)` | `RT.java` |
| Guest compile bailout | `GuestCompilationUnitTest` (when ops were wired) |
| Attempt 1 numbers | [`FIXME_nth.md`](FIXME_nth.md) §1 |
| `with-redefs` trapdoor (nth, not count) | [`FIXME_with_redefs_trapdoor.md`](FIXME_with_redefs_trapdoor.md) |
| Lowering-layer write-up | `TODO_lowering_layer.md` Phase 2 step 2 |
