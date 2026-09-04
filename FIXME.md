# Cloffle FIXMEs & Known Compatibility Issues

## 1. Pedestal Arity Reflection (`reitit.pedestal-test/arities-test`)

### Symptom
When running `clojure -T:build compat-test :project :reitit`, `reitit.pedestal-test/arities-test` fails:
```clojure
FAIL in (arities-test) (pedestal_test.clj:10)
expected: (= #{0} ((var pedestal/arities) (fn [])))
  actual: (not (= #{0} #{0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21}))

FAIL in (arities-test) (pedestal_test.clj:11)
expected: (= #{1} ((var pedestal/arities) (fn [_])))
  actual: (not (= #{1} #{0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21}))

FAIL in (arities-test) (pedestal_test.clj:12)
expected: (= #{0 1 2} ((var pedestal/arities) (fn ([]) ([_]) ([_ _]))))
  actual: (not (= #{0 1 2} #{0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21}))
```

### Root Cause
In Reitit Pedestal (`modules/reitit-pedestal/src/reitit/pedestal.clj`), interceptors introspect error handler functions via Java reflection to determine supported arities:
```clojure
;; TODO: variadic
(defn- arities [f]
  (->> (class f)
       .getDeclaredMethods
       (filter (fn [^Method m] (= "invoke" (.getName m))))
       (map #(alength (.getParameterTypes ^Method %)))
       (set)))
```

- **Stock JVM Clojure**: Every `(fn ...)` compiles to its own dynamic bytecode class (e.g. `user$eval123$fn__456`). That class only generates `invoke(...)` methods for the arities declared in the form. Unhandled arities inherit from `clojure.lang.AFn` (throwing `ArityException`), so `.getDeclaredMethods` only sees the declared arities (e.g. `#{0}` or `#{1}`).
- **Cloffle**: All closures are represented at runtime by instances of `net.javacrumbs.cloffle.nodes.ClojureClosure`. To satisfy host interop, `ClojureClosure` statically implements all 22 overloaded `invoke` methods (0–20 arguments, plus variadic 21-argument `invoke(..., Object... rest)`). When reflection queries `.getDeclaredMethods` on `(class f)`, it returns all 22 methods declared on `ClojureClosure`, resulting in `#{0..21}`.

### Remediation Suggestions & Status
1. **Metadata Attachment on `ClojureClosure` (Completed in Cloffle)**:
   - `CloffleBytecodeRootNode`'s `CreateClosure` / `CreateClosurePendingCapture` and `ExprToBytecode.convertFnExpr` now build and attach `:arglists` metadata to every `ClojureClosure` instance at creation time.
   - Fixed, multi-arity, variadic, and named-recursive closures correctly expose their signatures via `(-> f meta :arglists)`.
   - `ClojureClosure` implements `meta()` and `withMeta(newMeta)` using standard `IObj` replacement semantics.
2. **Reitit Upstream Patch / Submodule Patch (PR Submitted)**:
   - Opened upstream draft PR [metosin/reitit#795](https://github.com/metosin/reitit/pull/795) (`Detect variadic arities of :error interceptors`).
   - Updates `reitit.pedestal/arities` to consult `(:arglists (meta f))` first before falling back to Java reflection, and adds `accepts-arity?` to correctly handle variadic arities (`RestFn` and `&` arglists).
   - Once merged upstream (or applied as a patch under `src/external-projects/patches/reitit/`), `reitit.pedestal-test/arities-test` will pass in Cloffle.
3. **Dynamic Proxy / Subclass Generation (Long term / heavier)**:
   - If strict JVM class-level reflection compatibility is required across arbitrary third-party libraries, generate lightweight dynamic subclasses of `ClojureClosure` (or use ByteBuddy / ASM) exposing only the declared `invoke` overloads for each distinct arity signature.

---

## 2. Reitit Vector Walking (`reitit.walk-test/keywordize=walk-keywordize`)

### Symptom (fixed locally)
Without the submodule patch, `compat-test :project :reitit` Phase 2 failed `keywordize=walk-keywordize`. Shrink was nested small vectors wrapping a map, e.g. `(vector (vector {"" 0}))`. With `0002-keywordize-ipersistentvector.patch` applied, both `keywordize-subvec` and the generative spec pass on Cloffle.

(`SAXParseException` for `0x1b` after a full reitit compat run is matcher-combinators ANSI in remaining OpenAPI/Swagger failures, not this test.)

### Root Cause
`reitit.walk` extended `IKeywordize` to `clojure.lang.PersistentVector` by exact class. Cloffle `vector` / `RT.vector` values are `PersistentTuple` (`IPersistentVector`, subclass of `APersistentVector`, not `PersistentVector`). Protocol lookup fell through to `Object` (`identity`), so nested maps were not keywordized. `clojure.walk` uses `coll?` and does walk tuples; it also rebuilds via `(empty coll)` → `PersistentVector.EMPTY`.

The same gap exists on JVM Clojure for `subvec` (`APersistentVector$SubVector`). `gen/any-equatable` never produces subvecs, so the generative spec still passed upstream.

### Remediation & Status
1. **Local submodule patch (Completed)**:
   - `src/external-projects/patches/reitit/0002-keywordize-ipersistentvector.patch` — `update-submodules` / `compat-test` apply it after pinned checkout.
   - Replaces `PersistentVector` with `IPersistentVector` in the `extend` doseq (same pattern as `IPersistentMap`) and adds `keywordize-subvec`.
2. **Upstream PR (In progress)**:
   - [metosin/reitit#796](https://github.com/metosin/reitit/pull/796) (`Extend reitit.walk keywordize to IPersistentVector`).
   - Motivation: Cloffle small vectors are `PersistentTuple`; `subvec` is the JVM-Clojure analogue. Drop the local patch when that lands on the submodule SHA.

---

## 3. Cheshire Serial JSON (`cheshire.test.core/serial-writing`)

### Status
Resolved for test compatibility via local patch `src/external-projects/patches/cheshire/0001-start-inner-map-order.patch`. Both Phase 1 (Maven Clojure 1.12.0) and Phase 2 (Cloffle) pass 115 tests with 0 failures and 0 errors.

### Symptom
`clojure -T:build compat-test :project :cheshire` Phase 2 (Cloffle) reports **1 error** in `cheshire.test.core/serial-writing`. Phase 1 (Maven Clojure 1.12.0) is clean. The first four assertions in that `deftest` pass; the last one throws:

```
com.fasterxml.jackson.core.JsonGenerationException: Can not write a field name, expecting a value
  at WriterBasedJsonGenerator.writeFieldName
  at PersistentShapeMap.reduce
```

Expected JSON:

```json
{"head":"head info","data":[1,2,3],"tail":"tail info"}
```

### Root Cause
The assertion incrementally writes one object and leaves the nested `:data` array open for later `json/write` calls:

```clojure
(json/write {:head "head info" :data []} :start-inner)
(json/write 1) (json/write 2) (json/write 3)
(json/write [] :end)
(json/write {:tail "tail info"} :end)
```

`:start-inner` is implemented in `cheshire.generate-seq/generate-basic-map` by walking the map with `reduce` and passing `:start` (open, do not close) to **every** child value. That only works if the nested collection to leave open is the **last key in iteration order**. The test assumes array-map insertion order (`:head` then `:data`).

Cloffle map literals `{…}` with keyword keys are `PersistentShapeMap`, which iterates in **`Keyword.id` intern order**, not insertion order. `:data` is interned before `:head`, so Cheshire writes `"data":[` first, then tries `writeFieldName("head")` while Jackson is still inside the array.

This is the same shape-map vs. accidental insertion-order coupling as Reitit OpenAPI/Swagger parameter maps (`TODO.md`). Clojure’s map contract does not guarantee insertion order for `{}` literals; `(array-map …)` does.

Do **not** “fix” this by making `PersistentShapeMap` preserve insertion order (that breaks Graal PEA / scalar replacement; see `TODO.md` Domain Separation Architecture).

### Recommendations

Prefer a Cheshire library change so `:start-inner` does not depend on map iteration order. Use an `array-map` in the test only as a smaller local compat patch if upstream is slow.

#### 1. Preferred: make `:start-inner` order-independent (`cheshire.generate-seq`)

In `src/cheshire/generate_seq.clj`, `generate-basic-map` / `generate-key-fn-map` currently do:

```clojure
(generate jg# v# … :wholeness (if (= wholeness :start-inner) :start :all))
```

for **every** entry. Change `:start-inner` on maps to:

1. Split entries into complete fields vs. the inner collection: the inner is the last value that is sequential (`sequential?` / `IPersistentCollection` that is not a map), typically the empty vector the caller intends to extend.
2. Emit **complete** fields first with `:all` (full start+end).
3. Emit the **inner** field last with `:start` (open only).
4. Do not call `writeEndObject` (unchanged `:start-inner` object semantics).

That way `{:head "head info" :data []}` leaves `"data":[` open regardless of whether `:data` or `:head` comes first in `seq`. Apply the same idea to `generate-key-fn-map`. Arrays already leave the last nested collection open when it is last in the vector; leave that path alone unless a similar multi-nested case appears.

Upstream: dakrone/cheshire. Until merged, drop a submodule patch (see below).

#### 2. Smaller compat-only patch: pin order in the test

In `test/cheshire/test/core.clj` `serial-writing`, replace the map literal with an explicit ordered map:

```clojure
(json/write (array-map :head "head info" :data []) :start-inner)
```

Cloffle never promotes `(array-map …)` to `PersistentShapeMap`, so iteration stays `:head` then `:data`. This unblocks `compat-test` without changing encoder semantics. It does not fix other callers that use `{}` with `:start-inner`.

#### 3. Local submodule patch (either change)

Patches under `src/external-projects/patches/<project>/` are applied by `update-submodules` / `compat-test` after checkout.

```sh
export ENV=local && eval "$(direnv export zsh)"
mkdir -p src/external-projects/patches/cheshire
# After editing files in the cheshire submodule:
( cd src/external-projects/cheshire && git diff > ../patches/cheshire/0001-start-inner-map-order.patch )
```

Use a unified git diff with paths relative to the submodule root (`src/cheshire/generate_seq.clj` and/or `test/cheshire/test/core.clj`). `apply-external-project-patches` is idempotent (`git apply --check` forward, else reverse). When bumping the cheshire submodule SHA onto a commit that already contains the change, delete the patch file.

#### 4. Verify

```sh
export ENV=local && eval "$(direnv export zsh)"
clojure -T:build compat-test :project :cheshire
```

Expect Phase 1 and Phase 2 identical: 115 tests, 221 assertions, 0 failures, 0 errors.
