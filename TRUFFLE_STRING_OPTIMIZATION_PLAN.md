# Cloffle String Optimization Plan & Architectural Guidance

## 1. Executive Summary & Architectural Reality

Cloffle is a high-performance **JVM-hosted Clojure implementation** running on GraalVM.

Initial explorations into `com.oracle.truffle.api.strings.TruffleString` suggested using it as a general-purpose string type or returning lazy substring views from functions like `clojure.core/subs`. **Rigorous architectural critique and empirical testing proved that this is an anti-pattern in Cloffle.**

### The Core Architectural Principles
1. **`java.lang.String` is Clojure's Public Contract:**
   Clojure semantics, standard library functions, JVM interop, reader/printer, collections, and third-party libraries require concrete `java.lang.String` instances.
2. **The "Materialization Penalty":**
   Converting a `java.lang.String` to a `TruffleString`, operating on it, and converting it back to `java.lang.String` (`toJavaStringUncached()`) does **not** remove the required public result allocation. It can also add wrappers, encoding checks, and control flow. Earlier 19-vs-99-node and 5.9-vs-16.2-ns observations were exploratory and are not reproducible from a checked-in benchmark or graph artifact; they are motivation for measurement, not acceptance criteria.
3. **HotSpot/Graal Intrinsics Already Favor `String`:**
   GraalVM contains deep compiler support for `java.lang.String` (compact Latin-1 byte representations, optimized comparisons, and intrinsified array copies). Interned strings and stable final fields can also expose constants to compilation, but folding must be confirmed for the measured call site.
4. **Viable Optimization Strategy:**
   String optimizations in Cloffle must either:
   - **Eliminate redundant allocations via pattern fusion** (as demonstrated by `KeywordFieldName` and `SubstringStr1`), or
   - **Bypass variadic/boxing overhead on JVM strings** (e.g., fixed-arity `str` lowering that avoids `ArraySeq` and dynamic `StringBuilder` resizing), or
   - **Use `TruffleString` strictly in closed internal subsystems** (e.g. streaming parser tokenizers) where no `TruffleString` ever escapes into guest Clojure code.

---

## 2. Why Returning `TruffleString` or `CharSequence` Breaks Clojure

Any plan proposing that `clojure.core/subs`, `clojure.core/str`, or `clojure.string` return `TruffleString` or a custom `CharSequence` wrapper violates fundamental invariants:

### A. Core Predicates and Type Hints
- `(string? x)` is defined in `clojure.core` (lines 162–167) as a static function whose predicate is:
  ```clojure
  (instance? String x)
  ```
  If `(subs "hello" 1 3)` returns a `TruffleString` or `CharSequenceView`, `(string? (subs ...))` evaluates to `false`.
- Both arities of `subs` (`clojure/core.clj:5036-5042`) and `str` (`clojure/core.clj:546-561`) declare `^String` return tags. Downstream code relying on compiler type hints for Java interop would fail with `ClassCastException`.

### B. Equality, Hashing, and Collection Keying
- `java.lang.String.equals(Object)` returns `true` **only** if the argument is another `java.lang.String` with matching characters. Even if a custom wrapper implements `CharSequence`, `"el".equals(wrapper)` is `false`.
- Map lookups `(get {"el" 1} (subs "hello" 1 3))` would fail to find the key.
- `TruffleString` is a final class extending `AbstractTruffleString`; it does **not** implement `java.lang.CharSequence`.

### C. Java Interop, Printing, and Debugging
- Direct method calls like `(.toUpperCase (subs s 0 5))` or `(.getBytes (subs s 0 5))` fail because the host JVM cannot resolve `java.lang.String` methods on non-`String` types without reflection or dynamic dispatch.
- `clojure.lang.RT.print` and formatters have explicit `instanceof String` branches.
- Debugger and DAP behavior is another compatibility consideration, but those suites are not a targeted acceptance gate for fixed-arity `str`.

---

## 3. Rectifying Asymmetric Benchmark Claims

Earlier claims that `truffleStringSubstring` was "~50x faster" than `clojureSubs` were based on an invalid comparison in `StringBenchmark.java`:

- **`truffleStringSubstring`:**
  A direct host method call on a pre-existing cached Java field slicing 5 characters (`substringUncached(5, 5, ...)`).
- **`clojureSubs`:**
  Invoked via polyglot `strSubsFn.execute(...)` crossing the guest-host boundary, performing unboxing and dynamic interop checks, and slicing 10 characters (`(subs s 5 15)`).

### Strict Benchmarking Rules for String Work:
1. **Equivalent Operations:** Compare identical slice lengths, character sets, and argument types.
2. **Equivalent Boundaries:** Do not compare host-level microbenchmarks directly to polyglot `Value.execute` calls. Capture a compiled guest `IFn`, enter the context once, invoke the `IFn` on the timed path, and consume a scalar result inside guest code. Follow the established pattern in `KeywordMapBenchmark.java`.
3. **Allocation Profiling:** Always pass `-prof gc` to JMH to measure `gc.alloc.rate.norm` (B/op). A low latency number is misleading if it hides high allocation churn.
4. **Stable Inputs:** Keep benchmark inputs in state fields and avoid constants that let Graal fold the entire concatenation.
5. **Register in `build.clj`:** When dumping guest graphs, register the JMH method and guest function in `guest-compilation-hints` so Graal selects the intended `CloffleBytecodeRootNode`.
6. **Do Not Publish Asymmetric Methods:** Remove or clearly quarantine host-vs-`Value.execute` comparisons so they cannot be interpreted as optimization results.

---

## 4. The Proven Cloffle Architecture (Pattern Fusion)

Cloffle already contains string operations that preserve `java.lang.String` semantics and eliminate allocation on selected paths:

```
src/jvm/net/javacrumbs/cloffle/bytecode/CloffleBytecodeRootNode.java:
  CoreName         -> clojure.core/name
  CoreNamespace    -> clojure.core/namespace
  CoreStr1         -> clojure.core/str (arity 1)
  KeywordFieldName -> Cheshire keyword field name pattern
  SubstringStr1    -> (.substring (str k) 1)
```

### Why These Succeed:
1. **They return `java.lang.String`:** They preserve Clojure compatibility.
2. **They eliminate work instead of wrapping it:**
   - `KeywordFieldName` detects `(if (keyword? k) (.substring (str k) 1) (str k))` and returns `kw.getFieldName()`, which delegates to `sym.toString()`. The cached keyword path can allocate 0 strings.
   - `SubstringStr1` detects `(.substring (str k) 1)` and returns `kw.getFieldName()`.
   - `CoreStr1` detects `(str x)` and returns `kw.toString()` (which is `final` and interned) or `s` directly.

`SubstringStr1` is not universally zero-allocation: its `String`, `Symbol`, and generic paths can create a substring. Claims must be tied to the exact specialization and measured pipeline.

### AST Lowering Pattern in `ExprToBytecode.java`:
When adding intrinsics, always wire both sides of the compiler:
1. **Local Variable Sizing (`countExprLocals`):**
   Give 2- and 3-argument forms independent branches that sum all operand requirements. Do not add them to the existing one-argument `str` branch, which only counts `args.nth(0)`.
2. **Bytecode Emission (`convert`):**
   Recognize calls in **both** `StaticInvokeExpr` (direct-linked calls) and `InvokeExpr` (Var invocation). Follow the existing paired `isStr1Static(...)` / `isStr1Call(...)` structure with separate `isStr2Static` / `isStr2Call` and `isStr3Static` / `isStr3Call` helpers.
3. **Semantic Safety:**
   Intrinsic recognition must account for Clojure Var redefinition semantics. A namespace/name match alone can bypass a redefined `clojure.core/str`; either use the same explicit policy as existing core intrinsics or add a root-identity guard before broadening the optimization.
4. **Archive Compatibility:**
   Generated operation changes can affect serialized bytecode. Review `CloffleCoreBytecodeArchive.VERSION` and either bump it or explicitly invalidate/regenerate stale archives.

---

## 5. Viable High-ROI String Optimizations

### Priority 1: Fixed-Arity `(str a b)` (`CoreStr2`) and `(str a b c)` (`CoreStr3`)

- **Current Clojure Bottleneck:**
  In `src/clj/clojure/core.clj` (lines 546–561), multi-argument `(str x & ys)` compiles as a variadic call:
  ```clojure
  (^String [x & ys]
     ((fn [^StringBuilder sb more]
          (if more
            (recur (. sb (append (str (first more)))) (next more))
            (str sb)))
      (new StringBuilder (str x)) ys))
  ```
  Calling `(str a b)` or `(str a b c)` allocates:
  1. An `ArraySeq` / rest sequence via variadic invocation lowering.
  2. A `java.lang.StringBuilder`.
  3. Intermediate string allocations for each element.
  4. The final `.toString()` result.

- **Required Cloffle Semantics:**
  In `CloffleBytecodeRootNode.java`, introduce `CoreStr2` and `CoreStr3`, but treat the implementation as a semantic matrix rather than a few typed examples:
  1. Every null-like operand contributes `""`, including `(str nil nil)`, `(str nil 1)`, `(str 1 nil)`, and all 3-argument positions.
  2. `String` values pass through unchanged as components.
  3. `Keyword` and `Symbol` use their Clojure string representations.
  4. Primitive and boxed numbers, booleans, and characters produce the same text as `clojure.core/str`.
  5. The generic fallback must apply Clojure `str` semantics to each component. `String.valueOf(null)` is invalid because it produces `"null"` rather than `""`.
  6. `CoreStr3` must define the same behavior for each operand without an exponential specialization matrix; use reusable component conversion or a measured concat helper where appropriate.
  7. Unsupported or uncommon values must preserve the existing behavior rather than throwing `UnsupportedSpecializationException`.

- **Expected GraalVM Advantage:**
  Java concatenation may compile through `StringConcatFactory` and allow Graal to size and copy compact-string storage directly. The expected result is removal of rest-sequence, closure, and `StringBuilder` overhead. It is not a promise of one allocation: an escaping result normally requires both a `String` and backing storage, and the actual graph and B/op must be measured.

---

### Priority 2: Follow-up Closed-Circuit Fusion

These are separate optimization projects, not part of the `CoreStr2`/`CoreStr3` change. Each requires its own baseline, semantic matrix, lowering design, tests, and acceptance results.

1. **`clojure.string/blank?`:**
   - Preserve the public `CharSequence` contract, including `StringBuilder` and `StringBuffer`, rather than specializing only on `String`.
   - Preserve nil behavior and the exact whitespace predicate used by the current implementation.
   - Keep a semantically equivalent fallback for uncommon `CharSequence` implementations and for any values that currently produce an error.
2. **Prefix/Suffix Checks:**
   - Preserve current receiver conversion, argument handling, nil/error behavior, and `CharSequence` support.
   - A direct `String.startsWith` / `String.endsWith` path is valid only after guards prove that it is equivalent; other cases need a fallback.
   - Run `clojure.test-clojure.string`, including its `StringBuffer`/`StringBuilder` cases.
3. **Substring-to-Number Parsing:**
   - Recognize the actual nested expression shapes, including `StaticMethodExpr` for `Long/parseLong` and the possible `subs` forms.
   - Preserve index coercion, bounds behavior, radix, and exact exception type/message behavior.
   - JDK 17's `Long.parseLong(CharSequence, int, int, int)` is a candidate implementation, but must be benchmarked against the allocating baseline.
   - Add independent local counting and emission paths; do not assume the fixed-arity `str` lowering covers this pattern.

---

### Priority 3: The True Role of `TruffleString`

`TruffleString` should not be used as Clojure's general string type. Its legitimate uses in Cloffle are:
1. **Lazy Polyglot Interop Views (Already in place):**
   `Keyword.asTruffleString()` and `Symbol.asTruffleString()` export Truffle interop so foreign languages can read Clojure keys while Clojure code continues to use `final String _str`. The first access converts and caches a `TruffleString`; subsequent accesses reuse the transient cache, so this is not universally allocation-free.
2. **Internal Byte-Stream Parsers (Future Subsystems):**
   A future EDN or JSON reader could scan UTF-8 bytes with `TruffleString.FromByteArrayNode(copy=false)` and parsing nodes such as `TruffleString.ParseLongNode`. This is viable only as a closed subsystem with explicit byte-array lifetime/ownership rules, encoding and malformed-input parity, and materialization of public Clojure values at the boundary.

---

## 6. Step-by-Step Implementation Sequence

### Phase 1: Benchmark Baseline (`StringBenchmark.java`)
1. Create fair compiled-guest pipelines in `src/benchmark/java/net/javacrumbs/cloffle/benchmark/StringBenchmark.java`:
   ```clojure
   (defn guest-str2-result [a b]
     (str a b))

   (defn guest-str2-length [a b]
     (.length ^String (str a b)))

   (defn guest-str3-result [a b c]
     (str a b c))

   (defn guest-str3-length [a b c]
     (.length ^String (str a b c)))
   ```
2. Capture each function as a raw `IFn`, enter the context outside the timed method, and invoke the `IFn` directly. Mirror `KeywordMapBenchmark.captureGuestValue`; do not time `Value.execute`. The `*-result` methods measure required public result materialization; the `*-length` methods measure a closed scalar-consuming pipeline.
3. Store non-constant strings, keywords, symbols, and numeric inputs in JMH state fields. Include separate representative methods rather than mixing types in one polymorphic call site.
4. Measure baseline latency and allocation:
   ```sh
   export ENV=local && eval "$(direnv export zsh)"
   clojure -T:build run-benchmarks :args '["StringBenchmark.guestStr2(Result|Length)", "-prof", "gc"]'
   ```
5. Register the exact JMH method-to-guest-function pairs in `guest-compilation-hints` in `build.clj`.
6. Record baseline score/error and `gc.alloc.rate.norm`. Do not compare these guest methods with host-only `TruffleString` methods.

### Phase 2: Define `CoreStr2` and `CoreStr3` Operations
1. Add `@Operation` classes in `src/jvm/net/javacrumbs/cloffle/bytecode/CloffleBytecodeRootNode.java`.
2. Implement the full per-operand conversion contract: null-like → `""`; strings unchanged; keywords/symbols via their Clojure representations; characters, primitive/boxed numbers, and booleans with Clojure-compatible text; generic objects via the same behavior as one-argument `str`.
3. Add fast specializations only after the generic behavior is correct. Ensure all null positions and combinations dispatch successfully.
4. Avoid `String.valueOf(null)` and any fallback that can emit `"null"`.

### Phase 3: Compiler Lowering in `ExprToBytecode.java`
1. Add paired helpers: `isStr2Static(...)` / `isStr2Call(...)` and `isStr3Static(...)` / `isStr3Call(...)`.
2. Add independent `countExprLocals` branches that sum exactly 2 or 3 operands. Do not reuse the one-argument `str` branch.
3. Update both `convert(StaticInvokeExpr, ...)` and `convert(InvokeExpr, ...)` to emit the matching operation and all operands in evaluation order.
4. Verify the policy for redefined Vars before enabling the `InvokeExpr` optimization.
5. Review `CloffleCoreBytecodeArchive.VERSION` after generated operation changes.

### Phase 4: Correctness and Test Suite Verification
1. Add compiled guest tests in `src/test/java/net/javacrumbs/cloffle/GuestCompilationUnitTest.java`. Follow the existing `core-str1-fn` pattern: warm up, invoke again, return both the value and `inCompiledCode`, and assert both.
2. Cover:
   - string/string and three-string concatenation;
   - nil/nil, nil/number, number/nil, and nil in every `CoreStr3` position;
   - keyword/keyword, symbol/string, characters, primitive and boxed values;
   - `(str)`, `(str x)`, `(apply str ["a" "b"])`, and four-argument `str` to prove unaffected forms retain their existing paths;
   - `(string? ...)` and exact Clojure result text.
3. Run the focused JUnit class while iterating:
   ```sh
   clojure -T:build run-tests :args '["--select-class=net.javacrumbs.cloffle.GuestCompilationUnitTest"]'
   ```
4. Run both clean full suites before accepting the change:
   ```sh
   clojure -T:build run-tests
   clojure -T:build run-clj-tests
   ```

### Phase 5: Performance and Graph Verification
1. Re-run the same JMH methods and compare score/error and `gc.alloc.rate.norm` with Phase 1:
   ```sh
   clojure -T:build run-benchmarks :args '["StringBenchmark.guestStr2(Result|Length)", "-prof", "gc"]'
   ```
2. Acceptance requires a reproducible improvement without correctness regressions. Expect `guestStr2Result` to allocate the escaping result; its target is removal of `RestFn`/rest-sequence and `StringBuilder` overhead, not 0 B/op. `guestStr2Length` may reach 0 B/op if the closed pipeline scalar-replaces all temporary state.
3. Use graph dumps as diagnostic evidence. `check-scalar-replacement` is not a valid pass/fail task for `guestStr2Result` because it rejects every surviving allocation; it may be used for `guestStr2Length` only when the selected graph is confirmed to be that exact closed guest root.
4. If graph inspection is needed, clean stale generated output first using a normal clean tools.build task or a default-fresh test task, then dump the exact registered guest root and inspect it with `analyze-graal-graph`.

---

## 7. How to Check Scalar Replacement and Inspect IGV Graphs

Compiler graphs are useful for explaining benchmark results, but JMH latency/allocation and correctness suites are the acceptance gates for concatenation.

### A. The MethodFilter Trap for Truffle Guest Code
> **CRITICAL RULE**: In Cloffle, guest Clojure functions compile as:
> `CloffleBytecodeRootNode[namespace_function-name]`
> If you specify a filter matching only the JMH benchmark name (e.g. `*guestStr2Result*`), GraalVM will **ONLY dump the host Java harness method** and **SILENTLY DROP** all guest Truffle compiler graphs!
>
> **Always ensure `*CloffleBytecode*` is included in the filter:**
> `build.clj` does this automatically when `:guest true` is passed:
> Filter: `*CloffleBytecode*,*<guest-hint>*`
> Register new benchmark methods in `guest-compilation-hints` in `build.clj` so the guest graph is selected automatically.

### B. Limits of `check-scalar-replacement`
`check-scalar-replacement` succeeds only when the selected low-tier graph has no allocation hits. A fixed-arity concatenation that materializes its result is therefore expected to fail this strict checker. Reserve the task for closed pipelines such as `guestStr2Length`, whose final scalar result may allow every temporary object to disappear. Never use it as the acceptance gate for a public result-producing `CoreStr2`/`CoreStr3` benchmark.

### C. Analyzing an Existing `.bgv` Dump Headless
To inspect a dumped graph without starting the IGV GUI:
```sh
clojure -T:build analyze-graal-graph \
  :bgv '"target/graal-dumps-pea/TruffleHotSpotCompilation-XXXX[CloffleBytecodeRootNode[...]].bgv"'
```
This prints the phase table, node counts, and search results for `NewInstance`, `Alloc`, `CommitAllocation`, and `new_instance_or_null`.

### D. Opening and Inspecting the Graph in Ideal Graph Visualizer (IGV)
1. **Start IGV if it is installed:**
   ```sh
   ./bin/igv
   ```
   The repository documents this launcher, but it is not checked into every workspace; install/configure IGV separately when absent.
2. **Open the Dump File:**
   - In IGV, click `File -> Open...`.
   - Navigate to `target/graal-dumps-pea/`.
   - Open `TruffleHotSpotCompilation-<id>[CloffleBytecodeRootNode[...]].bgv`.
3. **Navigate Compiler Phases in the Left Sidebar:**
   Expand the compilation tree:
   ```text
   📁 TruffleHotSpotCompilation-XXXX [CloffleBytecodeRootNode[...]]
      📁 HighTier
         📄 Inlining
         📄 Call Tree / After Inline   <-- Check if callees were inlined
      📁 MidTier
         📄 PartialEscapePhase
         📄 FinalPartialEscapePhase    <-- Check if allocations turned virtual
      📁 LowTier
         📄 After low tier             <-- Check for foreign allocation calls
   ```
4. **The 3 Critical Phases to Inspect:**
   - **`Call Tree / After Inline`**: Check whether the helper or string function inlined. If a `CallNode` remains un-inlined, escape analysis across the call boundary is impossible.
   - **`FinalPartialEscapePhase`**: Verify whether `NewInstanceNode` / `NewArrayNode` disappeared. They should be replaced by `VirtualInstanceNode` / `VirtualArrayNode`. If you see `CommitAllocationNode`, GraalVM was forced to re-materialize the object back to the heap.
   - **`After low tier`**: Look for `ForeignCallNode` with `new_instance_or_null` or `new_array_or_null`.
5. **Diagnosing Unwanted Allocations in IGV:**
   - Click on the surviving `ForeignCallNode` or `CommitAllocationNode`.
   - Open the **Properties** panel on the right.
   - Look at `nodeSourcePosition` to see the exact file, class, method, and line number that triggered the allocation.
   - Check `relativeFrequency`: if `0.005`, the allocation is on a cold deoptimization branch; if `1.0`, it is on the hot path.

---

## 8. Key Reference Documents & Files

- `src/clj/clojure/core.clj`: `str` (lines 546–561), `string?` (lines 162–167), `subs` (lines 5036–5042).
- `src/clj/clojure/string.clj`: Definition of string utilities (`join`, `split`, `replace`, `blank?`).
- `src/jvm/net/javacrumbs/cloffle/bytecode/CloffleBytecodeRootNode.java`: Working examples of `CoreName`, `CoreStr1`, `KeywordFieldName`.
- `src/jvm/net/javacrumbs/cloffle/bytecode/ExprToBytecode.java`: AST lowering to Truffle bytecode.
- `src/jvm/net/javacrumbs/cloffle/bytecode/CloffleCoreBytecodeArchive.java`: Serialized bytecode version and archive loading.
- `src/benchmark/java/net/javacrumbs/cloffle/benchmark/StringBenchmark.java`: Benchmark harness.
- `src/benchmark/java/net/javacrumbs/cloffle/benchmark/KeywordMapBenchmark.java`: Reference raw-`IFn` guest benchmark pattern.
- `src/test/java/net/javacrumbs/cloffle/GuestCompilationUnitTest.java`: Compiled guest correctness and allocation tests.
- `INSTRUCTIONS-IGV.md`: Comprehensive guide to IGV installation, BGV analysis, and phase navigation.
- `GRAAL_GRAPH_ANALYSIS.md`: GraalVM compiler IR, node types, and PEA patterns.
