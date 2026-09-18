# Cloffle ↔ Clojure 1.12.0 semantic catalog

Baseline: Maven / tag **`clojure-1.12.0`** (`compat-official-clojure-version`).
Do **not** diff against the sibling `digital-alchemy/clojure` tip (currently 1.13 master);
that mixes Cloffle changes with upstream lag.

Source of truth for live findings: [COMPATIBILITY_RISK_AUDIT.md](COMPATIBILITY_RISK_AUDIT.md).
Regression gate: `clj -T:build audit-compat` (stock-vs-Cloffle differential probes).

Labels:

- **Intentional** — accepted divergence; must be allowlisted in probes and documented here
- **Bug** — should match stock; probe must fail until fixed (no allowlist)
- **Match** — previously divergent, now matches stock
- **1.13-only** — present on upstream master, absent on 1.12.0; out of scope for this baseline

## `src/clj/clojure/core.clj` (vs `clojure-1.12.0`)

| Area | Label | Probe / gate | Notes |
|------|-------|--------------|-------|
| Stock `:inline` metadata restored (not expanded) | Intentional | `probe2` `redef/get` `redef/nth` `redef/count` `redef/nil?` `redef/identical?` `redef/equals`; `test-unchecked-math-compat` | Catalog of upstream non-redefinable call sites (BC license). Compiler does **not** expand `:inline`. Lowering stays `:cloffle/op` / `:cloffle/unchecked-op` / `:cloffle/locked`. Former `definline` array casts are `defn` + `{:inline (fn …)}` |
| `chunked-seq?` always `false`; unchunked `concat`/`filter`/`for`/`doseq`/`keep`/`map-indexed` | Intentional | `probe1` `chunk/*` | Realization window 32→1; public API change |
| `map` → `MappedVectorSeq` / `EphemeralVectorSeq` / `MappedMapSeq` | Intentional | `probe1` `pred/*`, `memo/*`, `ser/*`, `lazy/*` | Predicate / `realized?` drift OK if values/`=` match; concrete JVM class names are not probed |
| `get-in` → `RT/getIn` | Match | `probe1` `getin/*` | Call-site `not-found` is eager (function args). Audit finding 8 fixed |
| `into` body → `RT/into` | Match | `probe2` `redef/into` | Values via host `RT.into`; **call sites stay Var invokes** (no `:checked-method` rewrite). Stock has no `:inline` on `into` |
| `:cloffle/locked` analyze folds | Intentional | `probe2` `redef/map-*` etc. | On when `:direct-linking` is on. When on, folds erase call sites (stock `:inline`-like) |
| `reduce1` prefers `IReduce`/`IReduceInit` | Intentional | `probe1` `reduce/*` | No chunked path |
| `constantly` single variadic arity | Intentional | `probe7` `constantly/*` | Behaviourally equal for normal calls |
| `definline` shim (no `:inline` attach) | Intentional | — | Prefer explicit `{:inline (fn …)}` on `defn`; array casts `booleans`/`bytes`/… use that |
| `first` / `next` / `rest` / `peek` / `seq` / `conj` / `str` / `map` / `filter` / `into` honour `with-redefs` | Match | `probe1` `var/*`, `probe2` `redef/*`, `probe7` `redef/nonlit-*` | Seq primitives + locked shapes when folds off; conj/str folds gated off |
| `realized?` uses `instance?` (no cast CCE) | Intentional | `probe7` `ephemeral/*` | Soft fail on non-`IPending` (stock casts) |
| `future-call` host unwrapping | Intentional | `probe1` `exc/future-cause-class` | Truffle exception surface |
| `with-redefs-fn` restores via `Iterator` | Intentional | `audit-var-mutation-binding` | Avoids seq-redef trapdoor |
| `print-method` ignores `:type` under macroexpansion | Intentional | (macro / Malli paths) | Host safety |
| Synthetic `:arglists` on closures | Intentional | `probe1` `meta/*` | Pedestal / Reitit arity detection |
| `locking` → `CloffleMonitors/lock` (host `synchronized`) | Intentional | `LockingMonitorTest` | Real JVM monitor: interlocks with host `synchronized`, supports `wait`/`notify`. Bare `monitor-enter` / `monitor-exit` throw `UnsupportedOperationException` at run time — structured locking (JVMS 2.11.10) forbids splitting them across bytecode operations. No occurrences in any compat project |

## Sibling clj files (vs `clojure-1.12.0`)

| File | Label | Notes |
|------|-------|-------|
| `core_deftype.clj` | Intentional | ShapeMap protocol aliasing as ArrayMap/HashMap |
| `core_print.clj` | Match (print-dup tuples/maps) | Concrete `PersistentTupleN` + ShapeMap `print-dup`; type hints |
| `core/protocols.clj` | Intentional | De-chunked `ISeq` InternalReduce |
| `math.clj` | Intentional | Stock `:inline` metas restored as catalog only (not expanded) |
| `string.clj` / `instant.clj` | Match | Extra casts / hints for reflection |
| `genclass.clj` | Intentional | Force `Opcodes/V1_8` |
| `core_proxy.clj` | Match vs 1.12.0 | Same as tag (diff vs 1.13 master only) |
| `main.clj` / `test.clj` | Match | Minor formatting / nil-safe class name |
| `core/server.clj` | Intentional | No `DynamicClassLoader` / `*repl*` bind |
| `clojure/polyglot/error.clj` | Intentional | Cloffle-only |

## Java collection / seq layer (not in `core.clj`)

| Area | Label | Probe |
|------|-------|-------|
| `PersistentShapeMap` / `PersistentShapeMap16` for keyword map literals | Intentional | `probe1` `pred/*`, `order/*`, `printdup/*`; `probe2` `ext/*` (interface extend) |
| Insertion-order keys on shape maps | Match vs prior Keyword.id order | `probe1` `order/*` — finding 5 mitigated for shape maps |
| `PersistentTuple1..8` for small vectors | Intentional | `probe5` / `probe6` (`print-dup` round-trips); public `pred/*` on literals |
| `indexed?` / `counted?` on unrolled lists and mapped/ephemeral seqs | Intentional | `probe1` `pred/list-*` `pred/map-result-*` `pred/vector-seq` `pred/drop-result` | Replaces former `iface/list-is-Indexed` / class-name probes |
| Mapped*/Ephemeral*/Filtered* `writeReplace` → plain list | Match | `probe1` `ser/*`, `probe7` `ser/*` |
| `MappedMapSeq.reduce` honours memoized prefix | Match | `probe1` `memo/map-map-pull-then-reduce` |
| `FilteredEphemeralVectorSeq.reduce` does not re-test matched head | Match | `probe1` `memo/filter-pull-then-reduce` |
| `LazySeq.isRealized` only when `REALIZED` | Match | `probe1` `lazy/realized-after-thunk-throws-*` |
| LazySeq thunk retry + cycle detect | Intentional | `probe1` `lazy/thunk-throws-is-retryable`, `lazy/self-recursive-realization` |
| Vector literals &gt; 32 elements | Match | `probe5` — finding 1 fixed |
| Bytecode `:cloffle/op` intrinsics | Intentional | `probe2` — finding 2 | Product default DL on → ops emit and ignore redef. Stock-parity audits force `-Dclojure.compiler.direct-linking=false` so Var/redefs match. No sanctioned-root / `doRedefined` |

## 1.13-only upstream (out of scope)

Present on sibling `clojure` tip / absent from `clojure-1.12.0` and Cloffle:

- `some-vals`
- `req!` and richer map destructuring (`:select` / `:all` / …)
- `Inst` for `ZonedDateTime` / `OffsetDateTime`
- Various `core_print` / javadoc 1.13 tweaks

## Outer gates

| Gate | Command | Role |
|------|---------|------|
| Differential probes | `clj -T:build audit-compat` | Stock 1.12.0 vs Cloffle keyed diffs; Cloffle leg forces DL **off** for redef parity |
| Vendored `test_clojure` | `clj -T:build run-clj-tests` | Cloffle-only; **serialization excluded** by default |
| External libraries | `clj -T:build compat-test` | Pass/fail parity; Reitit uses library-source patches (compat debt) |

Reitit patches under `src/external-projects/patches/reitit/` (compat debt, not environmental):

1. `0001-expand-apersistent-map` — exact-class `PersistentArrayMap` → `APersistentMap`
2. `0002-keywordize-ipersistentvector` — exact-class vector protocol extend
3. `0003-pedestal-arities-arglists` — closure `:arglists`
4. `0004-deterministic-parameter-order` — map order defense in depth

### `audit-compat` status

Gate is green when intentional allowlists are applied. No open Bug rows for the
prior serialization / reduce-replay / LazySeq-`realized?` / with-redefs bypasses.

`audit-probe8` covers nil / empty / false edge cases for predicates, seq accessors,
`str`, lookup/update, and empty pipelines (values only — no class-name keys).
Allowlisted: `edge/conj-scalar/f` / `edge/conj-pair/f` — same `ClassCastException` on
`(conj false …)`; HotSpot vs Truffle message wording differs.

Stock-parity probes force Cloffle `-Dclojure.compiler.direct-linking=false`. Under that
profile, extra redefinability vs stock `:inline` includes `var/with-redefs-get` (probe1)
alongside `var/with-redefs-count`. Mapped/ephemeral `realized?` keys on `map identity`
(`ephemeral/realized-map-identity`, probe7) match the existing keyword/inc Intentional
rows.

`audit-probe9` is wave-2: truthiness/`=`, expanded subjects (`true`, `""`, `0`,
`[nil]`, lazy empties, …), `nth`/`peek`/`pop`, `keys`/`merge`/`concat`, `fnil`,
destructuring, `apply`, and nil math throw shapes.

Bug fixed while adding probe9: `PersistentTuple.drop` past-end returned
`PersistentVector.EMPTY` instead of `null`, so `nthnext`/`drop`/`nthrest` on
small vector literals yielded `[]` instead of stock `nil`/`()`.

`audit-probe10` is wave-3: arrays/host, `clojure.set`, range/cycle, sort/hash/meta,
subvec/rseq, string blank?, partition/group-by, NaN/Inf, bit/casts, for/case,
sorted empties.

`audit-probe11` is wave-4: regex, edn/read-string, ex-info, if-let/if-some,
quot/ratios, array converters, tree-seq/walk, halt-when, isa?/type, threading,
watches, pmap empty.

`audit-probe12` is wave-5: reduce-kv, merge-with, get/assoc/dissoc/conj on
literals, delay/promise, meta, get-in family, small tuple literal sizes, number edges.

`audit-probe13` is wave-6: run!/dorun, condp, subs, ref/volatile, queues,
keyword-as-fn, subseq/rseq, bit/negatives, transducer compose, local with-redefs.

`audit-probe14` is wave-7: transients, multimethods, agents, find-keyword,
if-not/when-first, vector-of, shape-map/tuple literals, reduce/transduce edges.
(Does not realize `partition-all 0` — infinite on stock.)

`audit-probe15` is wave-8: zipmap/frequencies, group-by, interleave/mapcat,
update-in, `some->`/`cond->`, splitv-at/partitionv, mapv/filterv, reduced.

`audit-probe16` is wave-9: list peek/pop, replicate/cycle, flatten/tree-seq,
walk, array-map size boundaries, ratio/bigint, vary-meta.

`audit-probe17` is wave-10: type/instance?, string seqs, into-array, clojure.set,
nthrest/butlast, lazy-cat, delay/realized?, atom/ref (no concrete class names).

`audit-probe18` is wave-11: `for` :when/:while, `case`, vary-meta dissoc, String/format.

`audit-probe19` is map+filter pipelines: chained seq ops, transducers, mapcat, map
entries, literal tuple/map lowering, lazy take/drop.

`audit-probe20` is advanced seq: sets/queues, partition-by/group-by after map,
transducer take/drop, halt-when, eduction vs lazy equivalence.

`audit-probe21` is nested composition: stacked xfs, partial/comp, frequencies,
merge-with on piped maps, completing+reduced, bounded pmap.

`audit-probe22` is ETL-style row pipelines, group-by pivots, batch literals,
join-ish merge, subvec/rseq transforms.

`audit-probe23` is higher-order map/filter: dynamic fns, juxt branches,
iterate/take-while, dedup via atom in filter, entry mapcat pipes.

`audit-probe24`–`33`: string/regex pipes; math/bigint transduces; metadata;
binding/with-redefs + lazy; sorted order; arrays/amap; delay/promise/future;
transient builds; reduce/merge aggregates; case/cond inside map/filter.

`audit-probe34`–`53`: edn read; ex-data; predicates; symbols/keywords; coll
conversions; vector stack; deep map update; for comprehensions; walk; tree/partition;
split/take; hash/=; assoc/dissoc; seq utils; regex groups; literal sizes 8/9;
lazy concat; transducer ops; ref/volatile; bit/casts.

`audit-probe54`–`63` (Java interop): String/Character; boxed numbers; `Math`;
`ArrayList`/`Collections`; `HashMap`; instanceof/cast/`Comparable`; `StringBuilder`;
`BigInteger`/`BigDecimal`; `Arrays`/`Objects`/`System`; `Pattern`/`Matcher`/`UUID`.

`audit-probe64`–`73` (advanced Java interop): `Optional`; `Stream`; `Comparator`;
`java.time`; NIO `Charset`/`ByteBuffer`; enums/varargs; `java.io` closeables;
`ExecutorService`/`Callable`; `reify`/`proxy`; `BitSet`, static fields, `locking`.

`audit-probe74`–`83`: NIO `Files`/`Path`; `Map.merge`/`computeIfAbsent`; atomics/
locks; Base64/URL encode; `DecimalFormat`; deques/`PriorityQueue`; stream collectors;
`Throwable`; `clojure.java.io`; `URI`/`InetAddress` (no network I/O).

`audit-probe84`: Clojure host types — `defrecord`, `deftype`, `defprotocol`, `reify`,
`extend-protocol`, map ops on records (values only, no concrete class names).

`audit-probe85`–`94` (more Java interop): `Scanner`/`Formatter`; byte/data streams;
`ConcurrentHashMap`; `CompletableFuture`; `MessageDigest`; `MathContext`;
`ThreadLocal`; `StringJoiner`; `forEach`/`replaceAll`; reflection `Method.invoke`.

Use `clj -T:build audit-compat :strict false` to collect every probe result in one run.

### core.async

Configured in `compat-test` via submodule `src/external-projects/core.async`.
Run `clj -T:build update-submodules` before `compat-test :project :core.async` if the
checkout is missing.
