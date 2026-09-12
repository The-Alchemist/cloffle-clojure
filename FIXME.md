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
2. **Reitit Upstream Patch / Submodule Patch (Applied locally, PR open)**:
   - Upstream PR [metosin/reitit#795](https://github.com/metosin/reitit/pull/795) (`Detect variadic arities of :error interceptors`).
   - Local patch `src/external-projects/patches/reitit/0003-pedestal-arities-arglists.patch` — `update-submodules` / `compat-test` apply it after pinned checkout.
   - Updates `reitit.pedestal/arities` to consult `(:arglists (meta f))` first before falling back to Java reflection, and adds `accepts-arity?` to correctly handle variadic arities (`RestFn` and `&` arglists).
   - Drop the local patch when that lands on the submodule SHA.
3. **Dynamic Proxy / Subclass Generation (Long term / heavier)**:
   - If strict JVM class-level reflection compatibility is required across arbitrary third-party libraries, generate lightweight dynamic subclasses of `ClojureClosure` (or use ByteBuddy / ASM) exposing only the declared `invoke` overloads for each distinct arity signature.

---

## 2. Reitit Vector Walking (`reitit.walk-test/keywordize=walk-keywordize`)

> **Still red, for a different reason (2026-09-09).** The walking bug below is fixed and stays fixed;
> the *same test name* now fails on `compat-test :project :reitit` with a `long overflow` thrown from
> `test.check`'s `JavaUtilSplittableRandom.split`, i.e. generator setup, not keywordizing. That is the
> `*unchecked-math*` regression planned in [`TODO_reflection_math.md`](TODO_reflection_math.md) §2:
> `*unchecked-math*` is a no-op since `:inline` expansion was removed, so `test.check`'s
> `(set! *unchecked-math* true)` no longer suppresses the overflow check in its splitmix arithmetic.
> It is the only non-identical case in the whole compat suite and reproduces on a clean baseline, so
> it gates nothing new. Do not reopen the patches below for it. Re-verify with
> `clojure -T:build compat-test :project :reitit :only-var '"reitit.walk-test/keywordize=walk-keywordize"'`
> and read the stack: keywordize frames mean a real regression here; `JavaUtilSplittableRandom.split`
> means it is the unchecked-math ticket and belongs there.

### Symptom (walking bug: fixed locally)
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

## 2b. Reitit Expand on shape maps (`reitit.core/Expand`)

### Status
Resolved locally. Tracked patch `src/external-projects/patches/reitit/0001-expand-apersistent-map.patch` matches upstream [metosin/reitit#794](https://github.com/metosin/reitit/pull/794).

### Symptom
On JVM, `Expand` was extended to `PersistentArrayMap` and `PersistentHashMap` by exact class. Cloffle keyword map literals are `PersistentShapeMap` / `PersistentShapeMap16` (`APersistentMap` subclasses). Route data that is a shape map therefore missed `expand` and fell through.

### Remediation
Extend `Expand` to `clojure.lang.APersistentMap` on the JVM (CLJS keeps the concrete map extensions). Drop the local patch when #794 lands on the submodule SHA.

---

## 2c. Reitit OpenAPI/Swagger parameter location order

### Status
Resolved locally. Tracked patch `src/external-projects/patches/reitit/0004-deterministic-parameter-order.patch`. Standalone repro: `src/script/repro_param_order.clj`. Upstream PR [metosin/reitit#798](https://github.com/metosin/reitit/pull/798). Drop the local patch when that lands on the submodule SHA.

### Symptom
`reitit.openapi-test/all-parameter-types-test`, `reitit.openapi-test/openapi-test`, `reitit.swagger-test/all-parameter-types-test`, and `reitit.swagger-test/swagger-test` compared `:parameters` **vectors** (order-sensitive) against insertion-order expectations. Cloffle emitted `path` before `query` (and Swagger `formData`/`path` before `query`).

### Root Cause
Clojure maps do not guarantee seq order. JVM Clojure small `{}` literals happen to be `PersistentArrayMap` (insertion order). Cloffle keyword literals used to be `PersistentShapeMap` in `Keyword.id` order, which scrambled Reitit's `:parameters` walk. Shape maps now store keys in construction / insertion order, matching ArrayMap seq for those literals.

The Reitit patch remains useful: it still pins location order when a hash-map (or any unordered seq) is walked into a vector.

### Remediation
- OpenAPI `-get-apidocs-openapi`: emit remaining locations by looking up `:query`, `:header`, `:cookie`, `:path` in that order. Leave each coercion schema's property order unchanged.
- Swagger `-get-swagger-apidocs`: remap then collect into `(array-map)` in `:query`, `:body`, `:formData`, `:header`, `:path` order.
- Tests `parameter-location-order-independent-of-map-seq` construct `:parameters` in scrambled order and still expect spec location order.

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

Cloffle map literals `{…}` with keyword keys are `PersistentShapeMap`, which now iterates in **insertion order** (same as `PersistentArrayMap`). `:data` vs `:head` intern order no longer reorders the seq. The Cheshire patch remains useful for hash-maps and any caller that still assumes a specific seq independent of construction order.

Clojure’s map contract does not guarantee insertion order for `{}` literals; `(array-map …)` does. Shape maps currently do preserve it.

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

---

## 4. Reitit / Sieppari AsyncContext nil crash (`reitit.http-test/core-async-test` & `sieppari.async/catch`)

### Status
Resolved. Fixed `ILookupThunk` fault sentinel contract in `PersistentShapeMap` and `PersistentShapeMap16`. `reitit.http-test/core-async-test` and all 68 `sieppari` tests pass with identical results between official Clojure and Cloffle. See `FIXME_reitit.md` for full step-by-step investigation log.

### Symptom
When running `reitit.http-test/core-async-test` (or full `compat-test :project :reitit` Phase 2), an uncaught exception occurred in background thread pools:
```
No implementation of method: :async? of protocol: #'sieppari.async/AsyncContext found for class: nil
```
The test timed out waiting for the response promise (`::timeout`).

### Root Cause
1. **`ILookupThunk` Protocol Mismatch**:
   Clojure compiler keyword call sites (`KeywordLookupSite`) compile keyword lookups into:
   ```java
   Object res = thunk.get(target);
   if (res == thunk) { // fault!
       thunk = site.fault(target);
       res = thunk.get(target);
   }
   ```
   If `target` does not match the specialized map type/shape of `thunk`, the thunk **must return `this`** (the thunk itself) as a sentinel to signal a fault and trigger re-resolution.
2. **The Shape Map Bug**:
   `PersistentShapeMap.getLookupThunk` and `PersistentShapeMap16.getLookupThunk` erroneously returned `target` instead of `this` when `target` was not an instance of that exact shape map:
   ```java
   // PersistentShapeMap.java (prior to fix):
   return target; // BUG: returned target instead of `this`!
   ```
3. **Cascade into `core.async` and `sieppari`**:
   - `core.async/go` uses `clojure.tools.analyzer.jvm` to parse ASTs into maps.
   - Small AST maps are `PersistentShapeMap`, installing a shape-map thunk for `:env`.
   - When a larger AST node (`PersistentHashMap`) was encountered, `thunk.get(ast)` returned `ast` itself instead of triggering a fault!
   - Consequently, `(:locals (:env ast))` attempted to look up `:locals` on `ast` directly, which returned `nil`.
   - In `clojure.core.async.impl.go/reads-from`, local variable bindings could not be located, returning an empty list `()`.
   - The emitted state machine failed to bind local registers, causing `(let [c (cca/<! c)] (if (exception? c) (f c) c))` in `sieppari.async.core-async/catch` to return the input channel argument `c` instead of the unpacked value.
   - Sieppari then attempted to take from the channel a second time; since the channel was closed, the take returned `nil`, and Sieppari called `(async? nil)`, crashing with `No implementation of method: :async? ... found for class: nil`.

### Remediation & Verification
- Corrected `PersistentShapeMap.java` and `PersistentShapeMap16.java` to return `this` (the `ILookupThunk` instance) on target mismatch.
- Added regression test `testKeywordLookupThunkProtocol` in `PersistentShapeMapTest.java`.
- Verified `clojure -T:build compat-test :project :reitit :only-var '"reitit.http-test/core-async-test"'` passes (Phase 1 & Phase 2 identical).
- Verified `clojure -T:build compat-test :project :sieppari` passes all 68 tests (136 assertions) with 0 failures and 0 errors.

---

## 4b. Sieppari async value corruption (shared arity-1 argument array)

### Status
Resolved. `ClojureClosure.doCall1` allocates its argument array per call again; regression test `BytecodeFnArityAndClosureTest.hostInvokeOneArgIsNotCorruptedByConcurrentCalls`. Distinct from §4: the `ILookupThunk` sentinel fix stands and is not involved.

### Symptom
`clojure -T:build compat-test :project :sieppari` Phase 2 (Cloffle) failed intermittently, a different test each run, while Phase 1 (Maven Clojure 1.12.0) was always clean. Observed variants:

* `sieppari.async.core-async-test/core-async-catch-clj-promise-test` — `expected: (= "foo" (deref respond))`, `actual: (not (= "foo" false))`.
* `sieppari.core-async-test/execute-context-setup-async-test-test` — `ClassCastException: class java.lang.Boolean cannot be cast to class clojure.lang.IPersistentMap` from `RT.dissoc`, i.e. `remove-context-keys` received a boolean instead of the context map.
* `sieppari.manifold-test/async-failing-handler-test` and neighbours — an interceptor result that belonged to another callback.
* Whole-suite hangs: a `promise` never delivered, main parked in `CountDownLatch.await` inside `clojure.core/promise`'s `reify`.

Each variant is one value arriving where another belonged, always on a path where interceptor callbacks run on `future` / `core.async` threads. `-Dpolyglot.cloffle.ClearDeadLocals=false` did **not** help, so last-use local clearing was not the cause.

### Root Cause
`ClojureClosure` cached one `Object[2]` per closure and rewrote slot 1 on every host `IFn.invoke(arg)`. That array *becomes* the callee's `frame.getArguments()`, and a guest fn reads its parameter out of it in the prologue, after `CallTarget.call` returns control to the callee. A second invocation of the same closure on another thread overwrites the argument of a call whose prologue has not run yet, so the first call executes with the second call's argument.

Sieppari triggers it constantly: `sieppari.async` extends `AsyncContext` with `future`-based `continue` / `catch`, so one arity-1 fn (`exception?`, `remove-context-keys`, `deref`, an interceptor's `:enter` / `:leave` / `:error`) is invoked from several pool threads at once. A minimal probe — four threads calling `(apply f (list tag))` on one `(fn [x] x)` — mixed up ~88 of 200 000 arguments.

`callArgs0` (arity 0) stays cached: its only element is `capturedFrame`, which no call writes.

### Remediation & Verification
* `ClojureClosure.doCall1` builds `new Object[]{capturedFrame, a1}` per call; `callArgs1` is gone.
* `build.clj` benchmark budgets `guestShapeMapEphemeralPipeline` and `guestEventSanitizePipeline` go back to `:alloc-budget 24` — that `Object[2]` is the floor for a host arity-1 call into guest code and cannot be hoisted.
* `clojure -T:build compat-test :project :sieppari` — 68 tests, 136 assertions, Phase 1 and Phase 2 identical; five consecutive suite runs green where the same loop previously failed or hung on most runs.

---

## 5. Idiomatic Equality (`=`) in Benchmarks & `clojure.lang.Util.equiv` Fast Path

### Status
Resolved. Replaced non-idiomatic `identical?` checks in benchmarks with `=`, introduced `CloffleBytecodeRootNode.Equiv`, and lowered `clojure.lang.Util/equiv` and 2-arg `=` in `ExprToBytecode`.

### Symptom
`ComparePerformance` guest benchmarks (such as `ring-response`) originally used `(identical? status 201)`. In Clojure, `identical?` tests Java reference equality (`a == b`). For boxed numbers outside `[-128, 127]` (like `201`), reference equality fails, causing the test branch to fail and Truffle to hit deoptimization loops (`Reason: Deopt taken too many times`). When switching to idiomatic Clojure equality `(= status 201)`, Clojure inlined to `(clojure.lang.Util/equiv status 201)`. Because `Util.equiv` was not handled by `ExprToBytecode`, it fell back to generic `StaticMethod` invocation, executing reflection (`invokeReflective`) with `@TruffleBoundary` and allocating argument arrays.

### Remediation
1. Replaced all 11 instances of `identical?` in `SnippetBenchmarkSupport.java` and `KeywordMapBenchmark.java` with idiomatic `=`.
2. Added `@Operation` node `CloffleBytecodeRootNode.Equiv` calling `clojure.lang.Util.equiv(a, b)`.
3. Intercepted `Util.equiv` and 2-arg `=` calls in `ExprToBytecode` (`isUtilEquivMethod`, `isEquivCall`, `isEquivStatic`) to emit `beginEquiv()`.
4. Verified with `ComparePerformance`: `ring-response` throughput jumped to 209M ops/sec (6.43x faster than stock JVM Clojure) with only 24 B/op allocation.

