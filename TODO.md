# Cloffle TODOs

## Re-enable PersistentShapeMap by default

Shape maps (`PersistentShapeMap` / `PersistentShapeMap16`) are **opt-in**. Default is off:

```
-Dclojure.use_shape_map=true
```

`run-tests` already sets that flag so shape-map unit tests still run. Compat runs and normal `CloffleMain` do not.

### Why they cannot be the default yet

Shape maps sort keys by interned `Keyword.id`. That is not Clojure's insertion/array-map order. Two concrete failures:

1. **`core.async` `go` compilation** (`Unable to resolve symbol: map__NNNN`).
   `clojure.core.async.impl.go` walks analyzer locals with `vals` / transducers and emits `let` bindings in that order. Promoting an insertion-ordered array map to a shape map reorders those locals, so a later `map__` gensym is emitted without a matching binding.
2. **Libraries that `extend-protocol` to concrete map classes** (`PersistentArrayMap`, `PersistentHashMap`).
   Small keyword maps become `PersistentShapeMap*` instead, so dispatch misses unless `find-protocol-impl` aliases them (already done, but still a semantic mismatch).

`PersistentArrayMap.assoc` now refuses to promote unless existing keys are already in `Keyword.id` order, which avoids (1) for maps that grew from array maps. Literal `CreateMapN` bytecode still builds shape maps when the flag is on, which is enough to break `core.async` and some reitit tests.

### Remaining work to turn the default back on

- [ ] Preserve insertion order in shape-map `seq` / `kvreduce` / iterators, or stop using `Keyword.id` order as the public iteration order.
- [ ] Confirm `clojure.core.async` loads and its tests pass with `-Dclojure.use_shape_map=true`.
- [ ] Confirm `clj -T:build compat-test :project :reitit` with the flag on (reitit `Expand` protocol, swagger/openapi parameter order, `walk-keywordize`).
- [ ] Decide whether protocol extensions to `PersistentArrayMap` / `PersistentHashMap` should keep working via aliases, or whether shape maps should be subclasses / same types.

Enable locally with `-Dclojure.use_shape_map=true` on any Cloffle JVM.

## Reitit compat (unrelated leftovers)

With shape maps **off**, `core.async` loads and reitit's `core-async-test` passes. Full `compat-test :project :reitit` still has failures outside this change, including:

- [ ] `reitit.pedestal-test/arities-test` — Cloffle `IFn` reports extra arities (`#{0..21}` instead of declared arities).
- [ ] `reitit.walk-test/keywordize=walk-keywordize` — generative walk mismatch.
- [ ] OpenAPI/Swagger parameter-list **order** and one malli schema error.
