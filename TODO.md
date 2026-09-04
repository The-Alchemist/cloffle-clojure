# Cloffle TODOs

## Re-enable PersistentShapeMap by default

Shape maps (`PersistentShapeMap` for 1–8 keys and `PersistentShapeMap16` for 9–16 keys) are currently **opt-in**. The default is disabled:

```properties
clojure.use_shape_map=false
```

To run a JVM process with shape maps enabled, pass:
```sh
-Dclojure.use_shape_map=true
```

`clojure -T:build run-tests` automatically adds `-Dclojure.use_shape_map=true` to ensure the ShapeMap unit test suite (`PersistentShapeMapTest`) continues to run and pass. However, general evaluation and compatibility test tasks (`compat-test`) default to `false`.

---

## Architectural Taxonomy (Cloffle vs Clojure Java vs Clojure .clj)

When modifying this repository, be aware of three distinct layers of code:

1. **Cloffle Truffle Bytecode Engine** (`src/jvm/net/javacrumbs/cloffle/*`):
   - `bytecode/CloffleBytecodeRootNode.java`: Truffle Bytecode DSL operations (`CreateMap0`..`CreateMap8`, `CreateStandardMap`, `InvokeProtocol`, `GetOuterFrame`, `WireLetFnClosures`, `KeywordLookup`, etc.).
   - `bytecode/ExprToBytecode.java`: Compiles Clojure `Compiler.Expr` AST nodes into Truffle bytecode operations. Contains the `MapExpr` translation logic that branches on `clojure.lang.RT.USE_SHAPE_MAP`.
   - `nodes/ClojureRootNode.java`: Base Truffle `RootNode`. Handles frame snapshotting (`snapshotFrame` using `virtualFrame.copyTo(...)`) so closures capture local variables across loop recurs.
   - `nodes/ClojureClosure.java`: Truffle closure representations and call targets.

2. **Clojure Host / Java Core Runtime** (`src/jvm/clojure/lang/*`):
   - `PersistentShapeMap.java` / `PersistentShapeMap16.java`: The shape-map implementations. Direct object fields (`k0..k7`, `v0..v7`), bitmasks (`mask0`, `mask1`), canonical `Keyword.id` sorting for GraalVM Partial Escape Analysis (PEA) and scalar replacement. Extends `APersistentMap`.
   - `PersistentArrayMap.java`: Upstream Clojure array map. Maintains **insertion order** (`seq()`, `iterator()`, `keys()`, `vals()`). Contains the `assoc` promotion check (`canPromoteToShapeMap`).
   - `RT.java`: Runtime helpers. Houses `USE_SHAPE_MAP = Boolean.parseBoolean(System.getProperty("clojure.use_shape_map", "false"))`, `RT.map(...)`, and default imports.
   - `Compiler.java`: Clojure compiler, macro expansion, local binding frames (`ObjMethod`, `LocalBinding`), and `InvokeExpr` protocol resolution (`:on` and `:on-interface`).
   - `Keyword.java`: Interned keyword registry; assigns monotonic `id` via `AtomicLong ID_GENERATOR`.

3. **Clojure Core Language Sources** (`src/clj/clojure/*`):
   - `core_deftype.clj`: Protocol dispatch infrastructure (`find-protocol-impl`, `extend-protocol`, `satisfies?`). Contains the shape-map protocol fallback alias to `PersistentArrayMap` and `PersistentHashMap`.
   - `core.clj`: Core standard library functions and macros (`loop`, `future-call`, `destructure`).

---

## What We Learned During This Debugging Session

### 1. The `core.async` Destructuring Failure (`Unable to resolve symbol: map__NNNN`)
- **Symptom**: `(require 'clojure.core.async)` failed during macro expansion of `go-loop` with:
  `Syntax error compiling at (clojure/core/async.clj:899:5). Unable to resolve symbol: map__11001 in this context`.
- **Mechanism**:
  - `clojure.core.async.impl.go` analyses forms into `tools.analyzer` ASTs.
  - The analyzer maintains scoped lexical locals in `:env :locals`.
  - When compiling state machine blocks, `go.clj` (`RawCode.reads-from` and `emit-instruction`) collects and filters locals using a transducer pipeline over `(-> ast :env :locals vals)`:
    ```clojure
    (into []
      (comp
        (map #(select-keys % [:op :name :form]))
        (filter (fn [local] (contains? locals (:name local))))
        (distinct)
        (mapcat (fn [local] `[~(:form local) ~(get locals (:name local))])))
      (-> ast :env :locals vals))
    ```
  - Standard Clojure uses `PersistentArrayMap` for small maps like `:locals`, preserving **insertion order** so earlier bindings and destructuring intermediate forms (`map__NNNN`) appear before their consumers.
  - When `USE_SHAPE_MAP` was enabled, either map literals or `PersistentArrayMap.assoc` promoted `:locals` into a `PersistentShapeMap`.
  - `PersistentShapeMap` orders entries by `Keyword.id` (which depends arbitrarily on JVM keyword intern order).
  - Iterating over `(vals locals)` yielded bindings in scrambled order. Downstream expressions referenced `map__NNNN` before its `let` binding was emitted in that block, causing compiler symbol resolution failure.

### 2. Concrete-Class Protocol Extensions (`reitit.core/Expand`)
- **Symptom**: Reitit compatibility tests crashed with:
  `IllegalArgumentException: No implementation of method: :expand of protocol: #'reitit.core/Expand found for class: clojure.lang.PersistentShapeMap`.
- **Mechanism**:
  - Reitit defines `(defprotocol Expand (expand [this opts]))` and extends it using `(extend-protocol Expand clojure.lang.PersistentArrayMap ... clojure.lang.PersistentHashMap ...)`.
  - Because `PersistentShapeMap` directly inherits from `APersistentMap` instead of `PersistentArrayMap`, Clojure's protocol dispatch found no match.
  - In `src/clj/clojure/core_deftype.clj`, we added a fallback in `find-protocol-impl`: if `x` is a `PersistentShapeMap`, try `(impl PersistentArrayMap)`; if `x` is a `PersistentShapeMap16`, try `(impl PersistentHashMap)`.

### 3. ArrayMap Promotion Mutation
- `PersistentArrayMap.assoc` was unconditionally promoting any array map with `<= 8` keyword keys to `PersistentShapeMap`.
- We added `canPromoteToShapeMap(array)` in `PersistentArrayMap.java` to verify that the keys are already sorted strictly by ascending `Keyword.id`. If not, it remains a `PersistentArrayMap`, preventing silent reordering of existing maps.

### 4. Bytecode Map Literals (`ExprToBytecode.java`)
- Literal map expressions (e.g. `{:a 1, :b 2}`) compiled unconditionally to `CreateMap1`..`CreateMap8`.
- We updated `ExprToBytecode.java` so that when `!RT.USE_SHAPE_MAP`, it emits `CreateStandardMap` (which delegates to `RT.map(...)`), preserving standard Clojure map semantics.

### 5. Truffle Closure Snapshotting Bug (Fixed)
- When a closure captures an outer frame across `loop`/`recur`, Truffle's Bytecode DSL leaves local variable slots with `FrameSlotKind.Illegal` until typed initialization.
- Previously, `ClojureRootNode.snapshotFrame` iterated over slots calling `virtualFrame.getValue(i)`, which failed or skipped illegal slots.
- We switched `snapshotFrame` to `virtualFrame.copyTo(0, snapshot, 0, fd.getNumberOfSlots())`. This properly clones slot kinds and values, fixing closure capture across loops (tested in `BytecodeFnArityAndClosureTest.closuresCreatedAcrossLoopRecurCaptureEachIteration`).

### 6. Protocol Resolution in `Compiler.java` and Bytecode (Fixed)
- In `Compiler.InvokeExpr`, Cloffle only looked up `:on` from protocol metadata to find interface methods.
- Upstream protocols often only define `:on-interface`.
- We added lookup for `:on-interface` in `Compiler.java` and added the `InvokeProtocol` node in `CloffleBytecodeRootNode.java` / `ExprToBytecode.java`.

---

## Instructions for a Future Agent: How to Re-enable Shape Maps by Default

To safely re-enable shape maps by default (`USE_SHAPE_MAP = true`), the following design problems must be addressed:

### Problem 1: Reconciling PEA `Keyword.id` Sorting with Insertion Order
**Root requirement**: Clojure code expects small maps to behave like `PersistentArrayMap` where `seq()`, `keys()`, `vals()`, and `iterator()` iterate in **insertion order**. Shape maps sort keys by `Keyword.id` so that GraalVM PEA can treat key slot indices as deterministic constants.

**Approaches to try**:
- **Approach A (Recommended - Dual Order / Insertion Indirection)**:
  - Keep fields `k0..k7` and `v0..v7` sorted by `Keyword.id` for $O(1)$ fast lookups, bitmask membership tests, and PEA scalar replacement.
  - Add an encoded byte or integer field (e.g. `byte order0, order1, ...` or a single 32-bit `int insertionOrder`) recording the insertion permutation (e.g. slot 0 was inserted first, slot 2 second, etc.).
  - Implement `ShapeMapSeq`, `ShapeMapIter`, `reduce`, and `kvreduce` to traverse slots according to `insertionOrder` rather than `0..count-1`.
  - For `assoc` that adds a key: append the new slot index to the insertion order.
  - For `without`: adjust the permutation order.
  - This satisfies both requirements: GraalVM PEA still virtualizes the direct fields, while external callers observe strict insertion order.
- **Approach B (ShapeMap as Subclass of PersistentArrayMap)**:
  - Explore whether `PersistentShapeMap` can extend `PersistentArrayMap` or maintain the exact array layout while overlaying shape accessors. Note that Graal PEA requires object fields (not array elements) for full scalar replacement, so direct fields `k0..k7` must remain.

### Problem 2: Protocol Dispatch Subtyping
- Ensure `(satisfies? SomeProtocol shape-map)` and `(extend-protocol SomeProtocol clojure.lang.PersistentArrayMap ...)` work seamlessly without requiring manual protocol hacks.
- If Approach A or B maintains compatibility with `PersistentArrayMap`, evaluate whether `instance? PersistentArrayMap` should return true or whether `core_deftype.clj`'s aliasing is sufficient for all Clojure protocols.

### Verification Checklist for the Future Agent
Run the following checks with `-Dclojure.use_shape_map=true`:

1. **PersistentShapeMap JUnit Tests**:
   ```sh
   export ENV=local && eval "$(direnv export zsh)"
   clojure -T:build run-tests :args '["--select-class=clojure.lang.PersistentShapeMapTest"]'
   ```
2. **`core.async` Load & Macroexpansion**:
   ```sh
   export ENV=local && eval "$(direnv export zsh)"
   SPATH=$(clojure -Sdeps '{:aliases {:async {:extra-deps {org.clojure/core.async {:mvn/version "1.8.741"}}}}}' -A:async -Spath)
   java -Xss4m --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -Dpolyglotimpl.AttachLibraryFailureAction=throw -Dclojure.use_shape_map=true -cp "target/classes:src/clj:${SPATH}" net.javacrumbs.cloffle.CloffleMain -e '(require (quote clojure.core.async)) (println :core-async-success)'
   ```
3. **Reitit Compatibility Tests**:
   ```sh
   export ENV=local && eval "$(direnv export zsh)"
   # Run reitit with shape maps enabled
   clojure -T:build compat-test :project :reitit
   ```
   Verify that `reitit.http-test/core-async-test` passes, and that OpenAPI/Swagger parameter order tests do not fail due to keyword ordering.

---

## Remaining Reitit Compatibility Issues (Independent of Shape Maps)

With shape maps disabled, `core.async` and `reitit.http-test/core-async-test` pass. The following issues in `clojure -T:build compat-test :project :reitit` are unrelated to shape maps:

- [ ] **`reitit.pedestal-test/arities-test`**:
  Cloffle functions (`ClojureClosure` / `RestFn`) report support for all arities `#{0..21}` when reflected by Pedestal arity inspection, whereas JVM Clojure fn classes only declare methods matching defined arities.
- [ ] **`reitit.walk-test/keywordize=walk-keywordize`**:
  Generative test failure with `test.check` when walking maps with special Unicode/null-character string keys.
- [ ] **Surefire XML Output with Non-XML Characters**:
  When tests fail with binary or null characters in test names/assertions (like `walk-keywordize`), `run_external_tests_surefire.clj` writes unescaped control chars into `TEST-results.xml`, causing Xerces `DOMParser` to fail with `SAXParseException: An invalid XML character (Unicode: 0x0 / 0x1d) was found`.
- [ ] **Swagger/OpenAPI Parameter Ordering**:
  Tests expecting specific parameter order in OpenAPI specs when endpoints define query, header, cookie, and path parameters.
