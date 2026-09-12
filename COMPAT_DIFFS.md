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
| `:inline` → `:cloffle/op` / `:cloffle/unchecked-op` | Intentional | `probe2` redef keys; `test-unchecked-math-compat` | Values match; Cloffle is *more* redefinable where stock had `:inline` |
| `chunked-seq?` always `false`; unchunked `concat`/`filter`/`for`/`doseq`/`keep`/`map-indexed` | Intentional | `probe1` `chunk/*` | Realization window 32→1; public API change |
| `map` → `MappedVectorSeq` / `EphemeralVectorSeq` / `MappedMapSeq` | Intentional | `probe1` `class/*`, `memo/*`, `ser/*`, `lazy/*` | Class / `realized?` drift OK if values/`=` match |
| `get-in` → `RT/getIn` | Match | `probe1` `getin/*` | Call-site `not-found` is eager (function args). Audit finding 8 fixed |
| `into` → `RT/into` | Match (values) | `probe1` / `run-clj-tests` | Lowering metadata only for call sites |
| `reduce1` prefers `IReduce`/`IReduceInit` | Intentional | `probe1` `reduce/*` | No chunked path |
| `constantly` single variadic arity | Intentional | `probe7` `constantly/*` | Behaviourally equal for normal calls |
| `definline` no longer attaches `:inline` | Intentional | `test-unchecked-math-compat` | Bodies still evaluate |
| `first` / `next` / `rest` / `peek` / `seq` / `conj` / `str` honour `with-redefs` | Match | `probe1` `var/*`, `probe2` `redef/*`, `probe7` `redef/nonlit-*` | Seq primitives no longer always-rewrite; conj/str folds gated off |
| `realized?` uses `instance?` (no cast CCE) | Intentional | `probe7` `ephemeral/*` | Soft fail on non-`IPending` (stock casts) |
| `future-call` host unwrapping | Intentional | `probe1` `exc/future-cause-class` | Truffle exception surface |
| `with-redefs-fn` restores via `Iterator` | Intentional | `audit-var-mutation-binding` | Avoids seq-redef trapdoor |
| `print-method` ignores `:type` under macroexpansion | Intentional | (macro / Malli paths) | Host safety |
| Synthetic `:arglists` on closures | Intentional | `probe1` `meta/*` | Pedestal / Reitit arity detection |

## Sibling clj files (vs `clojure-1.12.0`)

| File | Label | Notes |
|------|-------|-------|
| `core_deftype.clj` | Intentional | ShapeMap protocol aliasing as ArrayMap/HashMap |
| `core_print.clj` | Match (print-dup tuples/maps) | Concrete `PersistentTupleN` + ShapeMap `print-dup`; type hints |
| `core/protocols.clj` | Intentional | De-chunked `ISeq` InternalReduce |
| `math.clj` | Intentional | All `:inline` removed (redef / ASM surface) |
| `string.clj` / `instant.clj` | Match | Extra casts / hints for reflection |
| `genclass.clj` | Intentional | Force `Opcodes/V1_8` |
| `core_proxy.clj` | Match vs 1.12.0 | Same as tag (diff vs 1.13 master only) |
| `main.clj` / `test.clj` | Match | Minor formatting / nil-safe class name |
| `core/server.clj` | Intentional | No `DynamicClassLoader` / `*repl*` bind |
| `clojure/polyglot/error.clj` | Intentional | Cloffle-only |

## Java collection / seq layer (not in `core.clj`)

| Area | Label | Probe |
|------|-------|-------|
| `PersistentShapeMap` / `PersistentShapeMap16` for keyword map literals | Intentional | `probe1` `class/*`, `iface/*`, `protocol/*` |
| Insertion-order keys on shape maps | Match vs prior Keyword.id order | `probe1` `order/*` — finding 5 mitigated for shape maps |
| `PersistentTuple1..8` for small vectors | Intentional | `probe1` / `probe5` / `probe6` |
| Mapped*/Ephemeral*/Filtered* `writeReplace` → plain list | Match | `probe1` `ser/*`, `probe7` `ser/*` |
| `MappedMapSeq.reduce` honours memoized prefix | Match | `probe1` `memo/map-map-pull-then-reduce` |
| `FilteredEphemeralVectorSeq.reduce` does not re-test matched head | Match | `probe1` `memo/filter-pull-then-reduce` |
| `LazySeq.isRealized` only when `REALIZED` | Match | `probe1` `lazy/realized-after-thunk-throws-*` |
| LazySeq thunk retry + cycle detect | Intentional | `probe1` `lazy/thunk-throws-is-retryable`, `lazy/self-recursive-realization` |
| Vector literals &gt; 32 elements | Match | `probe5` — finding 1 fixed |
| Bytecode intrinsics honour Var redefs via assumptions | Match | `probe2` — finding 2 |

## 1.13-only upstream (out of scope)

Present on sibling `clojure` tip / absent from `clojure-1.12.0` and Cloffle:

- `some-vals`
- `req!` and richer map destructuring (`:select` / `:all` / …)
- `Inst` for `ZonedDateTime` / `OffsetDateTime`
- Various `core_print` / javadoc 1.13 tweaks

## Outer gates

| Gate | Command | Role |
|------|---------|------|
| Differential probes | `clj -T:build audit-compat` | Stock 1.12.0 vs Cloffle keyed diffs |
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

Use `clj -T:build audit-compat :strict false` to collect every probe result in one run.

### core.async

Configured in `compat-test` via submodule `src/external-projects/core.async`.
Run `clj -T:build update-submodules` before `compat-test :project :core.async` if the
checkout is missing.
