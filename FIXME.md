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

## 2. Reitit Generative Map Key Walking (`reitit.walk-test/keywordize=walk-keywordize`)

### Symptom
Generative property-based testing with `test.check` fails on `reitit.walk-test/keywordize=walk-keywordize` when generating arbitrary string/unicode keys containing null bytes or binary characters.

### Root Cause & Impact
- Tests walking nested structures with special or null characters produce failures that get written into JUnit XML report files.
- `run_external_tests_surefire.clj` writes these raw characters into `target/surefire-reports/reitit-cloffle/TEST-results.xml`, causing Xerces `DOMParser` to fail with `SAXParseException: An invalid XML character (Unicode: 0x0 / 0xe / 0x1d) was found`.

### Remediation Suggestions
1. **Sanitize XML Output in Test Runner**:
   - In `run_external_tests_surefire.clj`, escape or strip non-XML characters (Unicode control characters `< 0x20` except `\t`, `\n`, `\r`) before serializing test failure strings into XML.
2. **Investigate Walk Semantics**:
   - Determine if the generative failure is due to keyword interning differences on empty/null-byte strings (`""`, `"\0"`) between Cloffle and JVM Clojure.
