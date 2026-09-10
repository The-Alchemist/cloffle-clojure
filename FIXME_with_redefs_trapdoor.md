# FIXED — `with-redefs` on `nth`, `first`, `seq`, or `next` no longer permanently corrupts the JVM

Redefining any of four `clojure.core` functions used to make `with-redefs` **fail to restore the
original root** (or, for `next`, fail the body while still recovering). The Var kept the mock for the
life of the process, and because every later `with-redefs` ran through the same machinery, all of
them broke too — including ones on completely unrelated Vars. Stock Clojure handles all four without
incident.

This is [`COMPATIBILITY_RISK_AUDIT.md`](COMPATIBILITY_RISK_AUDIT.md) Finding 2 territory, but it was
the *opposite* failure: that finding is about lowered call sites ignoring a redefinition, whereas
this was about the redefinition never being undone.

## Measured 2026-09-09

Each Var probed in a **fresh JVM**, because one failure poisons the process. `restored` compares
`(.getRawRoot v)` before and after by identity, so it does not depend on the function under test.

| Var | Cloffle (before fix) | restored? | Stock |
| --- | --- | --- | --- |
| **`nth`** | THREW `ClassCastException` | **NO** | `body-ran`, restored |
| **`first`** | THREW `UnsupportedOperationException` | **NO** | `body-ran`, restored |
| **`seq`** | THREW `IllegalArgumentException` | **NO** | `body-ran`, restored |
| **`next`** | THREW `IllegalArgumentException` | yes | `body-ran`, restored |
| `rest`, `count` | `body-ran` | yes | `body-ran`, restored |

Also verified clean (body ran, root restored): `=`, `apply`, `assoc`, `chunked-seq?`, `concat`,
`conj`, `cons`, `dissoc`, `doall`, `get`, `identical?`, `instance?`, `key`, `keys`, `list`, `map`,
`meta`, `reduce`, `str`, `val`, `vals`, `zipmap`.

So the blast radius was **four** Vars that `root-bind` depends on, not three. `next` looked milder
because it restored, but it is the same self-hosting defect: `doseq` advances with `next` *after*
`.bindRoot` installs the mock, so setup throws and the body never runs; the `finally` restores
`next` before its own iteration advances, so recovery succeeds. `rest`/`count` are clean.

### Reproduce (historical)

```sh
clojure -T:build cloffle-repl :args '["dev/compat-audit/probe2_intrinsics_printdup.clj"]'
```

Probe 1 prints. Probe 2 (`redef/nth`) threw, and probes 3–40 all died with
`ClassCastException: clojure.lang.Keyword cannot be cast to clojure.lang.Var` inside their own
`with-redefs`, not in the thing they were testing. Minimal form:

```clojure
(with-redefs [nth (fn [& _] :redefined)] (nth [:a :b :c] 0))  ; threw
(nth [:a :b :c] 0)                                            ; => :redefined, forever
(with-redefs [str (fn [& _] :x)] :ok)                         ; threw, though str is unrelated
```

Write probe output through `System/out` and `String.concat` rather than `println`/`str`. With
`first` redefined, `str`'s own varargs path is compromised and the probe silently reports garbage —
the first pass of this investigation misread `first` as healthy for exactly that reason.

## Cause

`with-redefs-fn` (`src/clj/clojure/core.clj`) restored roots with:

```clojure
(let [root-bind (fn [m]
                  (doseq [[a-var a-val] m]
                    (.bindRoot ^clojure.lang.Var a-var a-val)))
```

That one expression depended on all four Vars: `doseq` expands to `seq` / `first` / `next`,
and destructuring `[a-var a-val]` from a `MapEntry` compiles to `nth`. When any of them was the Var
being mocked, the mock returned `:redefined`, `a-var` was no longer a `Var`, and the exception
aborted the `finally` before `bindRoot` ran (or, for `next`, aborted setup after bind). **The
teardown that would restore the function was written in terms of that function.**

### Why stock is immune — two independent mechanisms, and we have neither

**1. `clojure.core` is direct-linked.** Stock builds core with
`clojure.compiler.direct-linking=true` (`clojure/build.xml:57`), so core's internal calls to `seq`,
`first`, `next`, and `nth` are static invocations that never consult a Var. Cloffle loads `core.clj`
from source through the Truffle bytecode backend with no AOT step, and nothing sets that option
(`build.clj` and `deps.edn` have no `direct-linking` key), so **every internal call goes through the
Var**. The `Compiler.java` support exists at `:4579` but is never switched on.

**2. `nth` is `:inline`.** Upstream (`clojure/core.clj:896`) carries
`:inline-arities #{2 3}`, so destructuring compiles to `RT.nth` regardless of direct linking. The
fork strips **all** `:inline` from `core.clj` — 129 occurrences upstream, zero here — and
`Compiler.java` implements no `:inline` expansion at all; see its own comments at `:5872`, `:7288`,
`:7509`, which read "without `:inline`". `:cloffle/op` is the deliberate replacement.

Mechanism 1 is the root cause and covers all four Vars. Mechanism 2 is a second layer that would
independently save `nth`. Losing both is what made this reachable.

## Fix applied

Reinstating `:inline` is **not** an option — this compiler ignores it.

**Rewrite `root-bind` to avoid redefinable Vars** (done). Iterate the map with host interop only
(`.iterator()`, `.hasNext`, `.next`, and `IMapEntry.key` / `IMapEntry.val`). Do **not** call
Clojure `key`/`val` — those would introduce two new redefinable dependencies. Smallest change, fixes
all four, no design tradeoff. Diverges from upstream source text while staying behaviourally
identical on stock.

Rejected / deferred alternatives:

1. **Move `root-bind` into a Java helper** on `Var` or `RT`. Same effect, immune by construction,
   and harder to accidentally reintroduce — but a larger divergence.
2. **Turn on direct linking for `clojure.core`.** Fixes the whole class of bug rather than this
   instance, and matches stock exactly. Much bigger change, interacts with the bytecode backend and
   with `:cloffle/op`, and would *reduce* redefinability elsewhere to stock levels. Its own project.
3. **Document and leave.** Was previously in force; no longer.

Regression coverage lives in `clojure.test-clojure.vars`:
`test-alter-var-root-under-seq-first-next-nth-redef` and
`test-thread-bindings-under-seq-first-next-nth-redef` assert the raw root is restored by identity
while each of `seq` / `first` / `next` / `nth` is mocked (host `.bindRoot`, not through those Vars).
Hot call sites lowered to `RT.*` or bytecode ops are not expected to observe `with-redefs` mocks.

## Follow-up

- Nearby mutation/cleanup APIs (`alter-var-root`, thread bindings, `remove-method`) were audited
  and match stock. See [`FIXME_var_mutation_binding_audit.md`](FIXME_var_mutation_binding_audit.md).
- `probe2_intrinsics_printdup.clj` completes again (`clojure -T:build audit-probe2`). Finding 2
  in [`COMPATIBILITY_RISK_AUDIT.md`](COMPATIBILITY_RISK_AUDIT.md) is closed; remaining probe2
  diffs are extra redefinability (`nth`, `count`, `nil?`, `identical?`, `=`) and print-dup /
  substituted types.
- Direct linking for `clojure.core` remains a separate project, not required for this restore path.

## Pointers

| Item | Location |
| --- | --- |
| `with-redefs-fn` (host-interop `root-bind`) | `src/clj/clojure/core.clj` |
| Fork `nth` (no `:inline`) | `src/clj/clojure/core.clj` |
| Upstream `nth` (`:inline`) | `/Users/karl-medplum/Development/digital-alchemy/clojure/src/clj/clojure/core.clj:891` |
| Stock direct-linking switch | `/Users/karl-medplum/Development/digital-alchemy/clojure/build.xml:57` |
| Direct-linking support (unused) | `src/jvm/clojure/lang/Compiler.java:267`, `:4579` |
| "without `:inline`" comments | `src/jvm/clojure/lang/Compiler.java:5872`, `:7288`, `:7509` |
| Redefinition probe | `dev/compat-audit/probe2_intrinsics_printdup.clj` |
| Regression tests | `test/clojure/test_clojure/vars.clj` |
| `nth`-specific narrative | [`FIXME_nth.md`](FIXME_nth.md) |
| Related audit finding | [`COMPATIBILITY_RISK_AUDIT.md`](COMPATIBILITY_RISK_AUDIT.md) Finding 2 |
