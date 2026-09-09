# OPEN — `with-redefs` on `nth`, `first`, or `seq` permanently corrupts the JVM

Redefining any of three `clojure.core` functions makes `with-redefs` **fail to restore the original
root**. The Var keeps the mock for the life of the process, and because every later `with-redefs`
runs through the same machinery, all of them break too — including ones on completely unrelated
Vars. Stock Clojure handles all three without incident.

This is [`COMPATIBILITY_RISK_AUDIT.md`](COMPATIBILITY_RISK_AUDIT.md) Finding 2 territory, but it is
the *opposite* failure: that finding is about lowered call sites ignoring a redefinition, whereas
this is about the redefinition never being undone.

## Measured 2026-09-09

Each Var probed in a **fresh JVM**, because one failure poisons the process. `restored` compares
`(.getRawRoot v)` before and after by identity, so it does not depend on the function under test.

| Var | Cloffle | restored? | Stock |
| --- | --- | --- | --- |
| **`nth`** | THREW `ClassCastException` | **NO** | `body-ran`, restored |
| **`first`** | THREW `UnsupportedOperationException` | **NO** | `body-ran`, restored |
| **`seq`** | THREW `IllegalArgumentException` | **NO** | `body-ran`, restored |
| `next` | THREW `IllegalArgumentException` | yes | `body-ran`, restored |
| `rest`, `count` | `body-ran` | yes | `body-ran`, restored |

Also verified clean (body ran, root restored): `=`, `apply`, `assoc`, `chunked-seq?`, `concat`,
`conj`, `cons`, `dissoc`, `doall`, `get`, `identical?`, `instance?`, `key`, `keys`, `list`, `map`,
`meta`, `reduce`, `str`, `val`, `vals`, `zipmap`.

So the blast radius is **three** Vars, not one. `next` is a lesser bug: it throws where stock
succeeds, but it does recover.

### Reproduce

```sh
clojure -T:build cloffle-repl :args '["dev/compat-audit/probe2_intrinsics_printdup.clj"]'
```

Probe 1 prints. Probe 2 (`redef/nth`) throws, and probes 3–40 all die with
`ClassCastException: clojure.lang.Keyword cannot be cast to clojure.lang.Var` inside their own
`with-redefs`, not in the thing they were testing. Minimal form:

```clojure
(with-redefs [nth (fn [& _] :redefined)] (nth [:a :b :c] 0))  ; throws
(nth [:a :b :c] 0)                                            ; => :redefined, forever
(with-redefs [str (fn [& _] :x)] :ok)                         ; throws, though str is unrelated
```

Write probe output through `System/out` and `String.concat` rather than `println`/`str`. With
`first` redefined, `str`'s own varargs path is compromised and the probe silently reports garbage —
the first pass of this investigation misread `first` as healthy for exactly that reason.

## Cause

`with-redefs-fn` (`src/clj/clojure/core.clj:7438`) restores roots with:

```clojure
(let [root-bind (fn [m]
                  (doseq [[a-var a-val] m]
                    (.bindRoot ^clojure.lang.Var a-var a-val)))
```

That one expression depends on all three broken Vars: `doseq` expands to `seq` / `first` / `next`,
and destructuring `[a-var a-val]` from a `MapEntry` compiles to `nth`. When any of them is the Var
being mocked, the mock returns `:redefined`, `a-var` is no longer a `Var`, and the exception aborts
the `finally` before `bindRoot` runs. **The teardown that would restore the function is written in
terms of that function.**

### Why stock is immune — two independent mechanisms, and we have neither

**1. `clojure.core` is direct-linked.** Stock builds core with
`clojure.compiler.direct-linking=true` (`clojure/build.xml:57`), so core's internal calls to `seq`,
`first`, and `nth` are static invocations that never consult a Var. Cloffle loads `core.clj` from
source through the Truffle bytecode backend with no AOT step, and nothing sets that option
(`build.clj` and `deps.edn` have no `direct-linking` key), so **every internal call goes through the
Var**. The `Compiler.java` support exists at `:4579` but is never switched on.

**2. `nth` is `:inline`.** Upstream (`clojure/core.clj:896`) carries
`:inline-arities #{2 3}`, so destructuring compiles to `RT.nth` regardless of direct linking. The
fork strips **all** `:inline` from `core.clj` — 129 occurrences upstream, zero here — and
`Compiler.java` implements no `:inline` expansion at all; see its own comments at `:5872`, `:7288`,
`:7509`, which read "without `:inline`". `:cloffle/op` is the deliberate replacement.

Mechanism 1 is the root cause and covers all three Vars. Mechanism 2 is a second layer that would
independently save `nth`. Losing both is what makes this reachable.

## Fix options

Reinstating `:inline` is **not** an option — this compiler ignores it. In rough order of cost:

1. **Rewrite `root-bind` to avoid redefinable Vars.** Iterate the map with host interop
   (`.iterator()`, `key`/`val`, or `RT.iter`) instead of `doseq` plus sequential destructuring.
   Smallest change, fixes all three, no design tradeoff. Diverges from upstream source text while
   staying behaviourally identical on stock. **Recommended.**
2. **Move `root-bind` into a Java helper** on `Var` or `RT`. Same effect, immune by construction,
   and harder to accidentally reintroduce — but a larger divergence.
3. **Turn on direct linking for `clojure.core`.** Fixes the whole class of bug rather than this
   instance, and matches stock exactly. Much bigger change, interacts with the bytecode backend and
   with `:cloffle/op`, and would *reduce* redefinability elsewhere to stock levels. Its own project.
4. **Document and leave.** Currently in force.

Options 1 and 2 fix `with-redefs` only. If other core machinery has the same shape, option 3 is the
general answer — see below.

## Still open

- **Only `with-redefs` has been audited.** `alter-var-root`, `binding`, `push-thread-bindings`, and
  `remove-method` plausibly share the pattern and are **untested**. Worth a pass before choosing
  between option 1 and option 3, since a second victim argues for the general fix.
- **`next` throws where stock succeeds** and is not explained by the trapdoor, since it restores
  correctly. Separate, milder bug; uninvestigated.
- **`probe2_intrinsics_printdup.clj` cannot go into CI** until this is fixed, because probe 2
  poisons the remaining 38 probes. That in turn blocks empirically re-validating
  `COMPATIBILITY_RISK_AUDIT.md` Finding 2, which looks stale — `isCoreVar` no longer exists and only
  `assoc`, `get`, and `dissoc` carry `:cloffle/op` today.
- **No regression test exists.** Any fix should land with a per-Var test that asserts the root is
  restored by identity, run for at least `nth`, `first`, `seq`, and `next`.

## Pointers

| Item | Location |
| --- | --- |
| `with-redefs-fn` | `src/clj/clojure/core.clj:7438`, `root-bind` at `:7447` |
| Fork `nth` (no `:inline`) | `src/clj/clojure/core.clj:859` |
| Upstream `nth` (`:inline`) | `/Users/karl-medplum/Development/digital-alchemy/clojure/src/clj/clojure/core.clj:891` |
| Stock direct-linking switch | `/Users/karl-medplum/Development/digital-alchemy/clojure/build.xml:57` |
| Direct-linking support (unused) | `src/jvm/clojure/lang/Compiler.java:267`, `:4579` |
| "without `:inline`" comments | `src/jvm/clojure/lang/Compiler.java:5872`, `:7288`, `:7509` |
| Redefinition probe | `dev/compat-audit/probe2_intrinsics_printdup.clj` |
| `nth`-specific narrative | [`FIXME_nth.md`](FIXME_nth.md) |
| Related audit finding | [`COMPATIBILITY_RISK_AUDIT.md`](COMPATIBILITY_RISK_AUDIT.md) Finding 2 |
