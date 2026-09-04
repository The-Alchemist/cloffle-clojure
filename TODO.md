# Cloffle TODOs

## PersistentShapeMap Enabled by Default

Shape maps (`PersistentShapeMap` for 1–8 keys and `PersistentShapeMap16` for 9–16 keys) are **enabled by default**:

```properties
clojure.use_shape_map=true
```

To run a JVM process with shape maps explicitly disabled, pass:
```sh
-Dclojure.use_shape_map=false
```

---

## Architectural Taxonomy (Cloffle vs Clojure Java vs Clojure .clj)

When modifying this repository, be aware of three distinct layers of code:

1. **Cloffle Truffle Bytecode Engine** (`src/jvm/net/javacrumbs/cloffle/*`):
   - `bytecode/CloffleBytecodeRootNode.java`: Truffle Bytecode DSL operations (`CreateMap0`..`CreateMap8`, `CreateStandardMap`, `InvokeProtocol`, `GetOuterFrame`, `WireLetFnClosures`, `KeywordLookup`, etc.).
   - `bytecode/ExprToBytecode.java`: Compiles Clojure `Compiler.Expr` AST nodes into Truffle bytecode operations. `MapExpr` emits `CreateMap0`..`CreateMap8` / `CreateMapN` bytecode nodes.
   - `nodes/ClojureRootNode.java`: Base Truffle `RootNode`. Handles frame snapshotting (`snapshotFrame` using `virtualFrame.copyTo(...)`) so closures capture local variables across loop recurs.
   - `nodes/ClojureClosure.java`: Truffle closure representations and call targets.

2. **Clojure Host / Java Core Runtime** (`src/jvm/clojure/lang/*`):
   - `PersistentShapeMap.java` / `PersistentShapeMap16.java`: The shape-map implementations. Direct object fields (`k0..k7`, `v0..v7`), bitmasks (`mask0`, `mask1`), canonical `Keyword.id` sorting for GraalVM Partial Escape Analysis (PEA) and scalar replacement. Extends `APersistentMap`. Iterates direct slots without permutation indirection.
   - `PersistentArrayMap.java`: Upstream Clojure array map. Strictly maintains **insertion order** (`seq()`, `iterator()`, `keys()`, `vals()`). **Never promotes to `PersistentShapeMap`**.
   - `RT.java`: Runtime helpers. Houses default `USE_SHAPE_MAP = Boolean.parseBoolean(System.getProperty("clojure.use_shape_map", "true"))`, `RT.map(...)`, and default imports.
   - `Compiler.java`: Clojure compiler, macro expansion, local binding frames (`ObjMethod`, `LocalBinding`), and `InvokeExpr` protocol resolution (`:on` and `:on-interface`). Constant keyword maps evaluate via `RT.mapUniqueKeys`.
   - `Keyword.java`: Interned keyword registry; assigns monotonic `id` via `AtomicLong ID_GENERATOR`.

3. **Clojure Core Language Sources** (`src/clj/clojure/*`):
   - `core_deftype.clj`: Protocol dispatch infrastructure (`find-protocol-impl`, `extend-protocol`, `satisfies?`). Contains the shape-map protocol fallback alias to `PersistentArrayMap` and `PersistentHashMap`.
   - `core.clj`: Core standard library functions and macros (`loop`, `future-call`, `destructure`).

---

## Clean Separation Architecture: Resolving PEA Keyword.id Sorting vs Insertion Order

### Clojure Map Contract vs Insertion Order
Official Clojure documentation and specifications confirm that general maps do not guarantee insertion order:
- **Map Contract (`clojure.lang.IPersistentMap`)**: Maps associate keys to values. Upstream Clojure map literals (`{}`) with 9+ pairs evaluate directly to `PersistentHashMap`, discarding insertion order and iterating in 32-bit hash order.
- **`keys` and `vals`**: Standard Clojure guarantees only mutual consistency with `(seq map)`, never insertion order.
- **`array-map`**: Described as a specialized map for small code form manipulation where key order is desired, but explicitly documented to lose sort order upon modification once it transitions to a hash-map.

### Domain Separation Architecture
Rather than adding permutation words or indirection arrays to `PersistentShapeMap` (which degrades GraalVM PEA and scalar replacement):

```
+-------------------------------------------------------------+
| Map Literals {:k v}, (hash-map ...), RT.map(k, v)           |
| -> PersistentShapeMap (1..8) / PersistentShapeMap16 (9..16) |
| Canonical Keyword.id sorting for GraalVM PEA                |
| Iteration order: Keyword.id (O(1) direct slot traversal)    |
+-------------------------------------------------------------+

+-------------------------------------------------------------+
| Explicit (array-map ...), createAsIfByAssoc                 |
| -> PersistentArrayMap (OrderedArrayMap semantics)           |
| NEVER promotes to PersistentShapeMap                        |
| Strictly preserves insertion order                          |
| Converts to PersistentHashMap only at threshold >= 16       |
+-------------------------------------------------------------+
```

1. **`PersistentArrayMap`**: Removed `canPromoteToShapeMap` from `PersistentArrayMap.assoc`. Explicit `(array-map ...)` invocations strictly maintain insertion order across operations.
2. **`PersistentShapeMap`**: Retains pure, un-indirected direct slot traversal (`0..count-1`) sorted by `Keyword.id` for maximal GraalVM PEA and scalar replacement efficiency.
3. **`Compiler.java` & `ExprToBytecode.java`**: Map literals emit `CreateMap0`..`CreateMap8` bytecode operations or evaluate constant keyword maps via `RT.mapUniqueKeys`.
4. **Reitit Swagger/OpenAPI Parameter Ordering**: In Reitit, route parameter tests (e.g. `reitit.openapi-test/all-parameter-types-test` and `reitit.swagger-test/all-parameter-types-test`) construct route definitions with map literals like `{:parameters {:query ..., :body ..., :header ..., :cookie ..., :path ...}}`. When compiled with shape maps, keys are traversed in `Keyword.id` order. A patch was verified that sorts parameter emission deterministically (`:query`, `:header`, `:cookie`, `:path` for OpenAPI; `:query`, `:body`, `:formData`, `:header`, `:path` for Swagger). See details under Remaining Reitit Compatibility Issues below.

---

## Verification

The following suites pass with shape maps enabled by default:

1. **PersistentShapeMap JUnit Tests**:
   ```sh
   export ENV=local && eval "$(direnv export zsh)"
   clojure -T:build run-tests :args '["--select-class=clojure.lang.PersistentShapeMapTest"]'
   ```
2. **`core.async` Load & Execution**:
   ```sh
   export ENV=local && eval "$(direnv export zsh)"
   java -Xss4m --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -cp target/classes:$(clojure -Sdeps '{:deps {org.clojure/core.async {:mvn/version "1.8.741"}}}' -Spath) net.javacrumbs.cloffle.CloffleMain -e '(require (quote [clojure.core.async :as a])) (a/<!! (a/go (+ 1 2))) (println :core-async-success)'
   ```
3. **Reitit Compatibility Tests**:
   ```sh
   export ENV=local && eval "$(direnv export zsh)"
   clojure -T:build compat-test :project :reitit
   ```

---

## Remaining Reitit Compatibility Issues (Independent of Shape Maps)

The following issues in `clojure -T:build compat-test :project :reitit` are unrelated to shape maps:

- [ ] **`reitit.pedestal-test/arities-test`**:
  Cloffle functions (`ClojureClosure` / `RestFn`) report support for all arities `#{0..21}` when reflected by Pedestal arity inspection, whereas JVM Clojure fn classes only declare methods matching defined arities.
- [ ] **`reitit.walk-test/keywordize=walk-keywordize`**:
  Generative test failure with `test.check` when walking maps with special Unicode/null-character string keys.
- [ ] **Surefire XML Output with Non-XML Characters**:
  When tests fail with binary or null characters in test names/assertions (like `walk-keywordize`), `run_external_tests_surefire.clj` writes unescaped control chars into `TEST-results.xml`, causing Xerces `DOMParser` to fail with `SAXParseException: An invalid XML character (Unicode: 0x0 / 0x1d) was found`.
- [ ] **Swagger/OpenAPI Parameter Ordering**:
  Reitit route definitions construct `{:parameters {:query ..., :body ..., :header ..., :cookie ..., :path ...}}` using map literals. In stock Clojure, these happen to iterate in insertion order only because there are $\le 8$ parameters. With shape maps enabled, keys iterate in `Keyword.id` order.
  This can be addressed upstream in Reitit or via a patch in `src/external-projects/patches/reitit/`:
  - `modules/reitit-openapi/src/reitit/openapi.clj`: in `-get-apidocs-openapi`, sort `parameters` by `{:query 0 :header 1 :cookie 2 :path 3}`.
  - `modules/reitit-swagger/src/reitit/swagger.cljc`: in `-get-swagger-apidocs`, sort `parameters` by `{:query 0 :body 1 :form 2 :formData 2 :multipart 2 :header 3 :path 4}` and collect into `(array-map)`.
