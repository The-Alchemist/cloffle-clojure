# Clojure Compatibility Risk Audit

Scope: the 100-commit restart range `f2fe075c` (2026-09-02) .. `81eb626e` (2026-09-07).
Baseline: official `org.clojure/clojure` 1.12.0.
Uncommitted work (`TODO.md`, `build.clj`, `tools/`) is excluded from the audit.

Method: commit classification, source review of the substituted collection and
sequence types plus the bytecode lowering, the repository's own suites, the
`compat-test` differential suites, and five targeted differential probes run
under both Cloffle and stock 1.12.0.

## Headline

`run-tests` (928 JUnit tests) and `run-clj-tests` (633 tests / 18,848 assertions)
passed completely, yet a vector literal of more than 32 elements was silently
corrupt, and the probes show 57 further observable divergences from stock
1.12.0. The in-repo suites are not currently a signal for backward
compatibility.

Findings 1, 2 and 6 have since been fixed, each covered by a regression test that
fails without its fix; the suite is now 636 tests / 19,027 assertions. Findings
3–5 and 7–11 stand.

`compat-test` covers eight external projects. As found, clj-http failed with one
error (finding 1), which aborted the run before Reitit and Sieppari. After the
fix, all seven projects with a checked-out submodule — Cheshire, Ring,
Compojure, clj-http, Hiccup, Reitit and Sieppari — report `IDENTICAL`. The
eighth, core.async, fails in the *stock* phase because its submodule is not
present locally; that is an environment gap, not a compatibility result.

Separately, Reitit carries four local patches to its own **library source** —
without them it does not run on Cloffle at all (see "Downstream evidence").

## Severity ranking

| # | Finding | Severity | Primary commits |
|---|---|---|---|
| 1 | Vector literals > 32 elements are corrupt — **fixed** | Critical | `4df3275c`, `8f1e60b0`, `39326473` |
| 2 | Bytecode intrinsics bypass Var redefinition — **fixed** | Critical | `55061c65`, `61887345`, `c24c0363`, `38d6a272`, `bd825439`, `5d9973ff`, `4e49dcdd`, `f6cb9d97` |
| 3 | New sequence types are not serializable | High | `10d68f6e`, `6e113496`, `34b8aba5`, `8ac8377a` |
| 4 | Concrete collection classes changed | High | `5d9973ff`, `c004e56d`, `cdda7278`, `4df3275c`, `bd825439` |
| 5 | Map iteration order changed | High | `5d9973ff`, `c004e56d`, `cdda7278` |
| 6 | `print-dup` does not round-trip vectors — **fixed** | High | `4df3275c`, `81eb626e` |
| 7 | Public chunking is switched off | Medium | `f6cb9d97` |
| 8 | `get-in`'s `not-found` became lazy | Medium | `9c69ba9c` |
| 9 | `MappedMapSeq.reduce` replays `f` | Medium | `6e113496` |
| 10 | `LazySeq` failure/realization semantics changed | Low–Medium | `98ac96f2` |
| 11 | Synthetic `:arglists` on closures | Low | `d0d4edba` |

---

## 1. Critical — vector literals larger than 32 elements are corrupt (fixed)

> **Status: fixed.** `PersistentTuple.createFromArray` now restricts `adopt` to
> arrays of at most 32 elements and builds a real trie above that. Regression
> coverage: `clojure.test-clojure.vectors/test-constant-vector-literal-sizes`.
> The description below is the state as found.

Any all-constant vector literal with more than 32 elements produces a
`PersistentVector` whose `cnt` is correct but whose trie is not built. Reads
below index 32 succeed, reads at or above index 32 silently return the wrong
element, and any full traversal throws.

```clojure
;; 40-element literal, all constants
(nth [0 1 2 ... 38 39] 39)   ; stock => 39     cloffle => 7        (silently wrong)
(count [0 1 2 ... 38 39])    ; stock => 40     cloffle => 40       (looks fine)
(seq   [0 1 2 ... 38 39])    ; stock => (0 …)  cloffle => NullPointerException
```

`nth` returns `tail[i & 0x1f]`, so index 39 yields element 7. `seq`, `reduce`,
`into`, `rseq`, `subvec`, `pr-str`, `=` and `hash` all throw
`NullPointerException: Cannot read field "array" because "<local2>" is null`.
`assoc` throws `NullPointerException: Cannot read field "edit"`. The corruption
is independent of element type (longs, keywords and strings all reproduce) and
survives `def`, nesting in a map or list, and `conj`/`pop`.

Boundary: sizes 0–32 are correct; 33 and above are corrupt.

### Root cause

`VectorExpr.parse` materialises every all-constant literal through
`PersistentTuple.createFromArray`, with no size cap:

```3933:3940:src/jvm/clojure/lang/Compiler.java
			Object[] items = new Object[args.count()];
			for(int i =0;i<args.count();i++)
				{
				LiteralExpr ve = (LiteralExpr)args.nth(i);
				items[i] = ve.val();
				}
			IPersistentVector rv = PersistentTuple.createFromArray(items);
			return new ConstantVectorExpr(args, rv);
```

whose default branch adopts the array as a tail:

```88:101:src/jvm/clojure/lang/PersistentTuple.java
    public static IPersistentVector createFromArray(Object[] items) {
        switch (items.length) {
            case 0: return PersistentVector.EMPTY;
            // … cases 1–8 return PersistentTuple1..8 …
            default: return PersistentVector.adopt(items);
        }
    }
```

```67:69:src/jvm/clojure/lang/PersistentVector.java
static public PersistentVector adopt(Object [] items){
	return new PersistentVector(items.length, 5, EMPTY_NODE, items);
}
```

`adopt` is only valid for `items.length <= 32`, because the array becomes the
32-slot tail and the root is left as `EMPTY_NODE`. Stock Clojure keeps that
guard in `LazilyPersistentVector.createOwning`, and this repository's copy still
does:

```21:27:src/jvm/clojure/lang/LazilyPersistentVector.java
static public IPersistentVector createOwning(Object... items){
    if(items.length <= Tuple.MAX_SIZE)
        return Tuple.createFromArray(items);
    else if(items.length <= 32)
        return new PersistentVector(items.length, 5, PersistentVector.EMPTY_NODE,items);
    return PersistentVector.create(items);
}
```

`Compiler.java:3939` is the only unguarded caller, so runtime vector
construction (`vec`, `vector`, `apply vector`, `into`) is unaffected — which is
why the suites miss it.

### Downstream impact

This is the cause of the one `compat-test` failure,
`clj-http.test.core-test/t-transit-output-coercion`, whose fixture is a
51-element literal fed through `(map byte)` and `byte-array`. `MappedVectorSeq`
appears in that stack trace only as the first consumer to touch the broken trie;
it is not at fault.

`4df3275c` introduced the unguarded `adopt`; `8f1e60b0` and `39326473` routed
constant literals into it and made the defect reachable.

### The fix

```88:104:src/jvm/clojure/lang/PersistentTuple.java
    public static IPersistentVector createFromArray(Object[] items) {
        switch (items.length) {
            case 0: return PersistentVector.EMPTY;
            // … cases 1–8 return PersistentTuple1..8 …
            // adopt() installs items as the tail over an empty root, which is only
            // a valid PersistentVector while the array fits in one tail node.
            default: return items.length <= 32 ? PersistentVector.adopt(items)
                                               : PersistentVector.create(items);
        }
    }
```

Sizes 0–32 keep the existing zero-copy `adopt` path, so the PEA work this
commit family was after is unaffected. After the fix every `raw/` and `ctx/`
probe in `probe5_vector_literals.clj` matches stock exactly, and clj-http
reports `IDENTICAL`.

---

## 2. Critical — bytecode intrinsics bypass Var redefinition (fixed)

> **Status: fixed as originally stated.** Re-measured 2026-09-09 with
> `clojure -T:build audit-probe2`. `isCoreVar` no longer exists. Lowering is
> opt-in via `:cloffle/op` on `assoc`, `get`, and `dissoc` only, and those
> operations retire to `doRedefined` when the Var root is no longer the
> sanctioned lowering root (`Var.getLoweringRoot` /
> `CloffleBytecodeRootNode.sanctionedRootAssumption`). The `with-redefs`
> restore-path trapdoor that blocked this re-measure is
> [`FIXME_with_redefs_trapdoor.md`](FIXME_with_redefs_trapdoor.md).
>
> `probe2` `redef/*` vs stock 1.12.0: every originally listed function now honours
> `with-redefs` on Cloffle. `get` matches stock (both ignore the redefinition at
> the call site, as upstream `:inline`). Remaining redef diffs are Cloffle
> being *more* redefinable than stock because this compiler has no `:inline`:
> `nth`, `count`, `nil?`, `identical?`, and `=`. Print-dup / tuple / seq-class
> mismatches in the same probe belong to findings 4–6, not this one.
>
> The description below is the state as found.

Fourteen `clojure.core` functions are lowered to bytecode operations by symbol
name alone, so call sites keep the original behaviour even when the Var is
rebound:

```2301:2306:src/jvm/net/javacrumbs/cloffle/bytecode/ExprToBytecode.java
    static boolean isCoreVar(Var var, String name) {
        return var != null
                && var.ns != null
                && "clojure.core".equals(var.ns.name.getName())
                && name.equals(var.sym.getName());
    }
```

There is no check that the Var still holds its original root value.

Affected: `cons`, `first`, `rest`, `next`, `assoc`, `dissoc`, `list`, `str`,
`name`, `namespace`, `keyword?`, `some?`, `seq?`, and `get-in`. All fourteen
honour redefinition on stock 1.12.0.

```clojure
(with-redefs [first (fn [& _] :redefined)]
  [(first [1 2 3])                 ; stock => :redefined   cloffle => 1
   (@#'clojure.core/first [1 2 3])]) ; both  => :redefined
```

The Var genuinely is rebound; only the lowered call site ignores it. The bypass
also defeats `with-redefs-fn` and `alter-var-root`, so mocking, stubbing and
tracing libraries silently observe the real function.

`get`, `nth` and `count` behave identically to stock, because stock already
marks them `:inline`. `get-in` is a genuine expansion of the non-redefinable
surface: stock has no `:inline` on it, and `9c69ba9c` added one here.

---

## 3. High — the new sequence types are not serializable

`map` now returns `MappedVectorSeq` and `MappedMapSeq` (formerly also `StreamSeq`,
which was removed). All are declared `Serializable`, but serialization fails in
practice:

| Value | Stock | Cloffle |
|---|---|---|
| unrealized `(map inc [1 2 3])` | round-trips | `NotSerializableException: java.lang.Object` |
| realized `(map inc [1 2 3])` | round-trips | `NotSerializableException: HotSpotOptimizedCallTarget` |
| unrealized `(map f {…})` | round-trips | `NotSerializableException: java.lang.Object` |
| unrealized `(filter odd? …)` | round-trips | `NotSerializableException: FrameWithoutBoxing` |
| unrealized `(take 2 …)` | round-trips | `NotSerializableException: FrameWithoutBoxing` |

Two distinct causes. The `UNREALIZED` sentinel is a bare `new Object()`
(`MappedVectorSeq.java:18` and the equivalents), which is not serializable. More
seriously, the retained `f`/xform is a Truffle closure, so even a **fully
realized** sequence drags a `HotSpotOptimizedCallTarget` or a
`FrameWithoutBoxing` into the object graph. Declaring `Serializable` on these
classes promises something they cannot deliver.

This affects anything that Java-serializes lazy values: caches, Nippy-adjacent
paths, distributed job queues, session stores.

---

## 4. High — concrete collection classes changed

| Expression | Stock | Cloffle |
|---|---|---|
| `{:a 1 :b 2}` | `PersistentArrayMap` | `PersistentShapeMap` |
| 9-entry map literal | `PersistentHashMap` | `PersistentShapeMap16` |
| `(assoc nil :a 1)` | `PersistentArrayMap` | `PersistentShapeMap` |
| `[1 2 3]`, `(vector 1 2 3)`, `(conj [1 2] 3)` | `PersistentVector` | `PersistentTuple$PersistentTuple3` |
| `'(1 2 3)`, `(list 1 2 3)` | `PersistentList` | `PersistentList$PersistentList3` |
| `(seq [1 2 3])` | `PersistentVector$ChunkedSeq` | `PersistentList$PersistentList3` |
| `(drop 1 [1 2 3])` | `PersistentVector$ChunkedSeq` | `APersistentVector$SubVector` |

Consequences that showed up in the probes:

- `(instance? clojure.lang.PersistentVector [1 2 3])` is now `false`; likewise
  `PersistentArrayMap` for map literals.
- Protocols extended to an exact class (`extend-protocol P PersistentVector …`)
  fall through to `Object`. Extending to `IPersistentVector` /
  `IPersistentMap` works, and is what the Reitit patches do.
- Multimethods dispatching on `class` hit `:default` instead of the specific
  method.
- `(seq [1 2 3])` returning a `PersistentList` makes list literals report
  `Indexed` and vector literals report no direct interfaces.

Exact-class dispatch is not something Clojure guarantees, but it is common in
the wild, and it is the failure mode behind two of the four Reitit patches.

`map` over a vector has a fourth result type: when `f` is a keyword, set, map,
or `identity`, `EphemeralVectorSeq` (`8a839bd2`) is returned instead of
`MappedVectorSeq`.

```105:113:src/jvm/clojure/lang/EphemeralVectorSeq.java
    public static boolean isPure(Object f) {
        if (f instanceof Keyword || f instanceof IPersistentSet || f instanceof IPersistentMap) {
            return true;
        }
        if (f instanceof ComposedFn cf) {
            return isPure(cf.g) && isPure(cf.f);
        }
        return false;
    }
```

Its `isRealized()` is hardcoded to `true`, so `(realized? (map :k some-vector))`
returns `true` where stock returns `false`. The purity restriction means `f` is
safe to re-invoke, so the lack of memoization is not itself a hazard, but the
`realized?` answer is observably wrong.

---

## 5. High — map iteration order changed

`PersistentShapeMap` orders entries by `Keyword.id` (allocation order), not by
insertion or by stock's array/hash order.

```clojure
(keys {:a 1 :b 2 :c 3 :d 4 :e 5})
;; stock   => (:e :d :c :b :a)
;; cloffle => (:a :b :e :d :c)
```

Clojure does not promise map order, so this is legal — but it is observable, it
changes serialized output byte-for-byte, and it has already broken two
downstream projects (`cheshire/0001`, `reitit/0004`). Anything that snapshots
JSON or EDN output will see churn.

---

## 6. High — `print-dup` does not round-trip vectors (fixed)

> **Status: fixed.** `core_print.clj` now registers `print-dup` on each concrete
> `PersistentTuple1..8` instead of the abstract base. Regression coverage is in
> `clojure.test-clojure.printer/print-dup-substituted-collections-readable` and
> `…-emit-literals`, which fail if the base-class registration is restored. The
> description below is the original finding.


```clojure
(binding [*print-dup* true] (pr-str [1 2 3]))
;; stock   => "[1 2 3]"
;; cloffle => "#=(clojure.lang.PersistentTuple$PersistentTuple3/create [1 2 3])"

(binding [*print-dup* true] (= [1 2 3] (read-string (pr-str [1 2 3]))))
;; stock => true    cloffle => false
```

`81eb626e` added `(defmethod print-dup clojure.lang.PersistentTuple …)`, but it
never fires. The concrete class is `PersistentTuple$PersistentTuple3`, which is
not a registered dispatch value, so resolution falls back to dominance — and
`print-dup`'s existing preference chain makes `IPersistentCollection`
transitively preferred over `PersistentTuple` (`IPersistentCollection` is
preferred to `java.util.Collection`, which is an ancestor of `PersistentTuple`).
The runtime states this directly when you try to add the missing preference:

```
IllegalStateException: Preference conflict in multimethod 'print-dup':
interface clojure.lang.IPersistentCollection is already preferred to
class clojure.lang.PersistentTuple
```

The companion `PersistentShapeMap` / `PersistentShapeMap16` methods in the same
commit *do* work, because those are the exact concrete classes — which is why
this looked fixed. Registering a `defmethod` on each concrete `PersistentTupleN`
restores `"[1 2 3]"`; a `prefer-method` cannot.

Nested vectors inside maps are affected too, so
`{:a [1 2]}` emits an unreadable ctor form for the inner vector.

---

## 7. Medium — public chunking is switched off

`f6cb9d97` makes `chunked-seq?` return `false` unconditionally, and no seq
reports `IChunkedSeq`. The realization window drops from 32 to 1 for `map`,
`filter`, `for`, `keep` and `map-indexed`.

Element-by-element realization is arguably *more* correct for side-effecting
transducers, and lower latency. But `chunked-seq?` and `IChunkedSeq` are public
API: libraries branch on them to pick a chunked fast path, and a permanent
`false` silently disables those paths. Code that relies on chunk-sized
over-realization (rare, but it exists in benchmark and prefetch code) changes
behaviour.

---

## 8. Medium — `get-in`'s `not-found` argument became lazy

`9c69ba9c` added an `:inline` to `get-in` that splices `not-found` into the
else-branch of the generated `if`:

```6146:6153:src/clj/clojure/core.clj
             ([m ks not-found]
              (if (vector? ks)
                (let [s (gensym "sentinel")
                      ret (reduce1 (fn [acc k] `(let [v# (get ~acc ~k ~s)] (if (identical? ~s v#) ~s v#))) m ks)]
                  `(let [~s (Object.)
                         res# ~ret]
                     (if (identical? ~s res#) ~not-found res#)))
                `(. clojure.lang.RT (getIn ~m ~ks ~not-found)))))
```

`not-found` is a function argument in stock and is therefore always evaluated.
Here it is only evaluated on a miss:

```clojure
(get-in {:a 1} [:a] (swap! calls inc))  ; stock: calls => 1   cloffle: calls => 0
(get-in {:a 1} [:a] (throw (ex-info …))) ; stock: throws      cloffle: returns 1
```

Skipped side effects and skipped exceptions on the hit path. This changes strict
argument evaluation into lazy evaluation, which Clojure does not do for
function calls.

---

## 9. Medium — `MappedMapSeq.reduce` replays `f`

Pulling elements and then reducing invokes the mapping function more times than
stock:

```clojure
;; pull two elements from (map f {…}), then reduce the rest
;; stock => f called 3 times    cloffle => 4
```

Memoization does not cover the already-realized prefix when reduction starts
mid-spine, so `f` runs again. Harmless for pure `f`, incorrect for
side-effecting or expensive `f`.

---

## 10. Low–Medium — `LazySeq` failure and realization semantics

`98ac96f2`'s state machine changes three observable behaviours:

| Probe | Stock | Cloffle |
|---|---|---|
| `realized?` after the thunk threw during seq conversion | `false` | `true` |
| thunk invocations across two failed realizations | 1 | 2 |
| self-recursive `lazy-seq` | no throw | `IllegalStateException: Recursive lazy-seq realization` |

The retry behaviour looks deliberate ("leave intermediates recoverable on
failure") and the cycle detection is an improvement over stock's hang, but
`realized?` returning `true` for a sequence that never produced a value is
misleading and is the one worth reconsidering.

---

## 11. Low — synthetic `:arglists` on closures

`d0d4edba` attaches `:arglists` metadata to Cloffle closures, so
`(meta (fn [x] x))` returns `{:arglists ([x])}` where stock returns `nil`.
Added to make Reitit's Pedestal arity detection work; it still needed
`reitit/0003`. Code that treats non-nil fn metadata as meaningful will see
something new.

---

## Downstream evidence

`clojure -T:build compat-test`:

| Project | Stock 1.12.0 | Cloffle | Notes |
|---|---|---|---|
| cheshire | 115 pass | 115 pass | requires `cheshire/0001` (test-side, map order) |
| ring | pass | pass | `ring/0001` is environmental (jar vs exploded classpath) |
| compojure | pass | pass | unpatched |
| clj-http | pass | **1 error** → pass | `t-transit-output-coercion`, finding 1; passes after the fix |
| hiccup | pass | pass | unpatched |
| reitit | pass | pass | requires the four library-source patches below |
| sieppari | pass | pass | unpatched |
| core.async | **not run** | **not run** | submodule absent locally; fails in the stock phase |

`compat-test` stops at the first failing project, so the initial run ended at
clj-http. The re-run after the fix reached every project with a checked-out
submodule and reported `IDENTICAL` for all seven. `core.async` is configured in
`build.clj` but `src/external-projects/core.async` does not exist, so Phase 1
cannot fork; running it would need `update-submodules` to fetch it.

The three projects that passed need only test-side patches. All four patches
that modify third-party **library source** belong to Reitit:

- `reitit/0001-expand-apersistent-map` — `PersistentArrayMap` broadened to `APersistentMap` (finding 4)
- `reitit/0002-keywordize-ipersistentvector` — exact-class protocol extend broadened to `IPersistentVector` (finding 4)
- `reitit/0003-pedestal-arities-arglists` — arity reflection over Cloffle closures (finding 11)
- `reitit/0004-deterministic-parameter-order` — works around map seq order (finding 5)

Unmodified Reitit does not currently run on Cloffle. That is the strongest
available signal on real-world compatibility, and it is invisible in the suite
output, because the suite runs the patched copy.

## Coverage gaps

- **Size boundaries.** Nothing exercised collection literals across the 32/33
  trie boundary. Now covered for vectors by
  `clojure.test-clojure.vectors/test-constant-vector-literal-sizes`, which walks
  sizes 0–1024 and fails without the fix. Map and set literals have no
  equivalent boundary test.
- **Var indirection.** Covered for the live `:cloffle/op` Vars by
  `AssocLoweringIntrospectionTest` (`with-redefs` and `alter-var-root` retire
  `assoc`/`dissoc`; `get` is the stock-inline control) and by
  `clojure -T:build audit-probe2`.
- **Serialization.** No round-trip test for the new sequence types, realized or
  unrealized.
- **Printing.** `print-dup` round-trip was untested for the substituted types;
  the `81eb626e` fix was verified on maps only, and the vector half silently did
  not work. Now covered — see finding 6.
- **Argument evaluation.** No test asserts that `not-found` arguments are
  evaluated eagerly.
- **Differential probing.** The six probe scripts used here are preserved under
  [`dev/compat-audit/`](dev/compat-audit) and cover 167 + 76 + 21 + 36 + 34 + 50
  behaviours; the repository has no equivalent stock-vs-Cloffle differential
  harness. Folding one into `compat-test` would convert most of this report into
  regression tests.

  Each script prints one `key<TAB>value` line per probe and is run under both
  runtimes, then diffed:

  ```sh
  # stock baseline
  clojure -Sdeps '{:deps {org.clojure/clojure {:mvn/version "1.12.0"}}}' \
    -M dev/compat-audit/probe5_vector_literals.clj > /tmp/stock5.txt

  # cloffle
  clojure -T:build compile-all
  CP="$(clojure -Spath -A:cloffle-java)"
  java -Xss4m --enable-native-access=ALL-UNNAMED \
       --sun-misc-unsafe-memory-access=allow \
       -cp "$CP" net.javacrumbs.cloffle.CloffleMain \
       dev/compat-audit/probe5_vector_literals.clj > /tmp/cloffle5.txt

  diff /tmp/stock5.txt /tmp/cloffle5.txt
  ```

  `clojure -T:build cloffle-main` cannot run these: it builds its classpath from
  the `:repl` alias, whose `:classpath-overrides` drop `org.clojure/spec.alpha`,
  and `clojure.main` fails with `Could not locate clojure/spec/alpha.clj`. Use
  the `:cloffle-java` alias as above.

  Probes avoid compile-time references to Cloffle-only classes (they resolve via
  `Class/forName`) so the same file runs on stock, and each ends with
  `(shutdown-agents)` so the process exits.

## Recommended containment order

1. ~~**Cap `createFromArray`.**~~ **Done.** `adopt` is now used only for
   `items.length <= 32`; larger arrays go to `PersistentVector.create`, matching
   `LazilyPersistentVector.createOwning`. Unblocked clj-http and removed a
   silent-wrong-answer class of bug.
2. ~~**Gate intrinsic lowering on Var identity.**~~ **Done.** Lowering is
   `:cloffle/op` plus a sanctioned-root assumption, not `isCoreVar`. `assoc`
   and `dissoc` honour `with-redefs` / `alter-var-root`; `get` matches stock
   `:inline`. Re-validated by `clojure -T:build audit-probe2`.
3. ~~**Fix `print-dup` for tuples** by registering a `defmethod` per concrete
   `PersistentTupleN`, then assert round-tripping for every substituted type.~~
   **Done.**
4. **Decide on serialization.** Either drop `Serializable` from the new sequence
   types and fail loudly, or implement `writeReplace` to serialize as a plain
   realized seq. The current state promises support that does not exist.
5. **Restore eager `not-found`** in the `get-in` inline by binding the argument
   in the generated `let` before the `if`.
6. **Revisit `chunked-seq?`.** Returning `false` unconditionally is a public API
   change; consider reporting honestly and letting the window stay at 1.
7. **Fix the `MappedMapSeq.reduce` replay** and `LazySeq`'s `realized?`-after-failure.
8. **Re-evaluate map ordering.** If `Keyword.id` order stays, treat downstream
   order churn as expected and keep the patches; if not, insertion order would
   remove two of them.

Items 1–3 are behaviour-preserving fixes with no design tradeoff (1–3 are now done).
Items 4–8 involve a deliberate choice between performance and stock fidelity.

## Reverted or never-live experiments

Confirmed absent at `HEAD`, and excluded from the ranking above:

- `PersistentShapeSet` (`61887345`) — removed by `957e9b59`.
- `ExprToBytecodeFusion` / AST sequence fusion (`3e0994ac`) — removed by `51c34a1a`.
- `ExprToBytecodeMapFusion` / pattern fusion — removed by `1abac1c6`.
- Shape maps were briefly made opt-in (`e58aa313`) and then re-enabled by
  default (`cdda7278`); default-on is the live state.
- `Symbol` / `Var` / `BigInt` / `Ratio` `ExportLibrary` removal (`0252e12b`,
  `d5d80391`) is live but affects only polyglot embedding, not Clojure semantics.
