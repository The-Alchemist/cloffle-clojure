# Cloffle Pattern Fusion & String Lowering Optimization Plan

## 1. Executive Summary & Core Philosophy

Cloffle's proven performance breakthroughs come from **Pattern Fusion and Direct AST Lowering**: recognizing common Clojure idioms, macros, and serialization patterns at compile time and fusing them into specialized bytecode operations that execute with **0 heap allocations**, **4–19 Graal IR nodes**, and **~5 ns latency**.

### The Breakthrough Example: Opportunity 10
In Cheshire JSON encoding, every keyword key executes:
```clojure
(if (keyword? k) (.substring (str k) 1) (str k))
```
- **Standard Clojure / Host Execution:** Allocates `(str k)` (allocating `":foo"`), then calls `(.substring ... 1)` (allocating a second string `"foo"`).
- **Cloffle Pattern Fusion (`KeywordFieldName`):** `ExprToBytecode.java` detects this exact AST pattern and compiles it to `b.beginKeywordFieldName()`. At runtime, `Keyword.getFieldName()` directly returns the pre-existing, interned `sym.name` or `sym.toString()`.
- **Result:** **0 allocations**, **5.7 ns latency**, **19 low-tier Graal IR nodes**, and **100% scalar replacement** in `KeywordMapBenchmark.guestCheshireFieldNamePipeline`.

### Why Pattern Fusion Beats Generic Wrapping
1. **Zero Semantic Friction:** Returns native `java.lang.String` instances; preserves `(string? x)`, `^String` type hints, Java interop, map lookup equality, and reader/printer behavior.
2. **Eliminates the "Materialization Penalty":** Avoids converting between Java strings and wrapper representations (e.g. `TruffleString`), which adds object wrappers, encoding checks, and node bloat.
3. **Exploits HotSpot/Graal Intrinsics:** GraalVM deeply intrinsifies `java.lang.String` operations (compact Latin-1 byte arrays, vector-accelerated comparisons, and `StringConcatFactory` / `makeConcatWithConstants`).

This plan provides the architectural roadmap and concrete instructions for the next wave of high-impact pattern fusion optimizations in Cloffle.

---

## 2. Workspace Constraints & Developer Experience (DX) Rules

Any agent implementing this plan **must adhere to these workspace conventions**:

1. **Build Tooling:** Use `tools.build` (`build.clj`) exclusively. Never use Ant or Maven.
   - Run tests: `clojure -T:build run-tests` (cleans `target` by default).
   - Run benchmarks: `clojure -T:build run-benchmarks :args '["StringBenchmark.guestStr", "-prof", "gc"]'`.
   - Check scalar replacement: `clojure -T:build check-scalar-replacement :benchmark '"<BenchmarkName>"' :guest true`.
2. **Environment & Direnv:** Set `ENV=local` and evaluate direnv before shell invocations:
   ```sh
   export ENV=local && eval "$(direnv export zsh)"
   ```
3. **Git Commits:** Always sign commits with `git commit -s`.
4. **Bytecode Versioning:** If adding new bytecode operations to `CloffleBytecodeRootNode.java`, increment `CloffleCoreBytecodeArchive.VERSION` so cached bytecode archives recompile cleanly.
5. **Exact AST Sizing:** Always update **both** `countExprLocals` and `convert` in `ExprToBytecode.java`. `countExprLocals` must count locals only for the expressions that will actually be evaluated in the fused operation.

---

## 3. High-Impact Pattern Fusion Opportunities

### Target 1: Namespaced Keyword JSON Field Names (Cheshire Custom Encoders)

- **Pattern in Codebase (`src/external-projects/cheshire/src/cheshire/custom.clj:38`):**
  ```clojure
  (if-let [ns (namespace k)]
    (str ns "/" (name k))
    (name k))
  ```
- **The Inefficiency:**
  For a keyword `:user/name`, this allocates:
  1. `(namespace k)` $\to$ `"user"` (String 1)
  2. `(name k)` $\to$ `"name"` (String 2)
  3. `(str ns "/" (name k))` $\to$ variadic seq + StringBuilder + `"user/name"` (String 3)
- **The Cloffle Insight:**
  For any `Keyword k`, the fully-qualified name `"user/name"` is **already pre-computed, final, and interned** in `k.sym.toString()`!
  If `ns` is null, `k.sym.toString()` is `name`.
  If `ns` is present, `k.sym.toString()` is `ns + "/" + name`.
  Therefore, for any `Keyword`, `(if-let [ns (namespace k)] (str ns "/" (name k)) (name k))` is **identically equal to `k.getFieldName()`**!
- **Fusion Implementation:**
  In `ExprToBytecode.java`, recognize `if-let` / `let` patterns where a keyword's namespace and name are checked and recombined with `"/"`, and lower them directly to `KeywordFieldName`.
- **Expected Impact:**
  Eliminates 3 heap string allocations per namespaced keyword key during JSON encoding.

---

### Target 2: Expanding Fixed-Arity `(str a b c d)` (`CoreStr4`)

- **Current Status:**
  `CoreStr1`, `CoreStr2`, and `CoreStr3` are already implemented in `CloffleBytecodeRootNode.java` and lowered in `ExprToBytecode.java`.
  Benchmark results:
  - `guestStr2Result`: **10.87 ns/op**, **96 B/op** (vs ~800 ns for variadic `(str a b)`).
  - `guestStr3Result`: **23.28 ns/op**, **104 B/op**.
- **The Opportunity:**
  In routing, templating, and key generation, 4-argument concatenation is extremely common:
  ```clojure
  (str "/" tenant "/" entity "/" id)
  (str prefix ":" ns "/" name)
  ```
  Currently, 4 arguments exceed `CoreStr3` and fall back to `(apply str ...)` or variadic `(str x & ys)` in `core.clj`, which allocates an `ArraySeq` and dynamic `StringBuilder`.
- **Implementation Strategy:**
  1. Add `CoreStr4` operation to `CloffleBytecodeRootNode.java`:
     ```java
     @Operation(storeBytecodeIndex = true)
     public static final class CoreStr4 {
         @Specialization
         public static String doValues(Object a, Object b, Object c, Object d) {
             return coreStrValue(a) + coreStrValue(b) + coreStrValue(c) + coreStrValue(d);
         }
     }
     ```
  2. In `ExprToBytecode.java`:
     - Add `isStr4Call` and `isStr4Static`.
     - Wire into `countExprLocals` (counting locals for the 4 arguments).
     - Wire into `convert` for both `StaticInvokeExpr` and `InvokeExpr` emitting `beginCoreStr4` / `endCoreStr4`.
- **Expected Impact:**
  Bypasses `ArraySeq` and `StringBuilder` for 4-part URL and key construction.

---

### Target 3: Fast String Predicate Intrinsics (`clojure.string/blank?`)

- **Pattern in Codebase (`src/clj/clojure/string.clj:289`):**
  ```clojure
  (defn blank? [^CharSequence s]
    (if s
      (loop [index (int 0)]
        (if (= (.length s) index)
          true
          (if (Character/isWhitespace (.charAt s index))
            (recur (inc index))
            false)))
      true))
  ```
- **The Inefficiency:**
  In web middleware, parameter validation, and routing, `blank?` is called repeatedly. In interpreted code and early JIT tiers, the Clojure loop-recur over `.charAt` creates method invocation frames and boxed integer operations.
- **Proposed Intrinsic (`StringBlank`):**
  Add a bytecode operation:
  ```java
  @Operation(storeBytecodeIndex = true)
  public static final class StringBlank {
      @Specialization
      public static boolean doString(String s) {
          int len = s.length();
          for (int i = 0; i < len; i++) {
              if (!Character.isWhitespace(s.charAt(i))) {
                  return false;
              }
          }
          return true;
      }

      @Specialization(guards = "isNullLike(o)")
      public static boolean doNull(Object o) {
          return true;
      }

      @Specialization(guards = {"!isNullLike(o)", "!isString(o)"})
      public static boolean doCharSequence(CharSequence cs) {
          int len = cs.length();
          for (int i = 0; i < len; i++) {
              if (!Character.isWhitespace(cs.charAt(i))) {
                  return false;
              }
          }
          return true;
      }

      protected static boolean isString(Object o) { return o instanceof String; }
      protected static boolean isNullLike(Object o) {
          return o == null || (o instanceof com.oracle.truffle.api.interop.TruffleObject to
                  && com.oracle.truffle.api.interop.InteropLibrary.getUncached().isNull(to));
      }
  }
  ```
- **Lowering in `ExprToBytecode.java`:**
  Recognize calls where the function is `clojure.string/blank?` (via `VarExpr` pointing to `clojure.string/blank?`) and lower directly to `beginStringBlank`.

---

### Target 4: Direct `clojure.core/subs` AST Lowering to Java `String.substring`

- **Current Behavior:**
  `(subs s 0 5)` in `core.clj:5036` calls:
  ```clojure
  (^String [^String s start end] (. s (substring start end)))
  ```
  When invoked as `(subs s start end)`, it goes through standard function invocation dispatch.
- **Proposed Direct Lowering (`CoreSubs2`, `CoreSubs3`):**
  Lower direct calls to `clojure.core/subs` to dedicated operations:
  ```java
  @Operation(storeBytecodeIndex = true)
  public static final class CoreSubs2 {
      @Specialization
      public static String doString(String s, int start) {
          return s.substring(start);
      }
      @Specialization
      public static String doStringLong(String s, long start) {
          return s.substring((int) start);
      }
  }

  @Operation(storeBytecodeIndex = true)
  public static final class CoreSubs3 {
      @Specialization
      public static String doString(String s, int start, int end) {
          return s.substring(start, end);
      }
      @Specialization
      public static String doStringLong(String s, long start, long end) {
          return s.substring((int) start, (int) end);
      }
  }
  ```
- **Why this is implementation-safe:**
  - Preserves exact Java `StringIndexOutOfBoundsException` behavior.
  - Takes and returns `java.lang.String`.
  - Bypasses Clojure Var dereference and `AFn.invoke` dispatch.

---

### Target 5: Literal Prefix / Suffix Tests (`starts-with?` / `ends-with?`)

- **Pattern:**
  In routing and dispatch:
  ```clojure
  (str/starts-with? uri "/api/")
  (str/ends-with? filename ".clj")
  ```
- **Proposed Lowering:**
  Recognize `clojure.string/starts-with?` and `ends-with?` in `ExprToBytecode.java` when the target is a String, compiling directly to `StringStartsWith` and `StringEndsWith` bytecode nodes calling `.startsWith(...)` / `.endsWith(...)`.

---

## 4. Architectural Anatomy of an AST Pattern Fusion

Every pattern fusion optimization in Cloffle follows this strict structural blueprint across 3 files:

### File 1: `src/jvm/net/javacrumbs/cloffle/bytecode/CloffleBytecodeRootNode.java`
Define the Truffle Bytecode DSL operation:
1. Mark with `@Operation(storeBytecodeIndex = true)`.
2. Keep methods `public static`.
3. Specialize on common types (`String`, `Keyword`, `Symbol`, primitives).
4. Guard `null` and polyglot nulls with `isNullLike(Object o)`.
5. Return standard JVM types (`String`, `boolean`, `long`).

### File 2: `src/jvm/net/javacrumbs/cloffle/bytecode/ExprToBytecode.java`
Wire the compiler in two places:
1. **Helper detection:**
   ```java
   private static boolean isMyPattern(Expr fexpr, IPersistentVector args) {
       // match VarExpr, argument count, and operand types
   }
   ```
2. **Local variable accounting (`countExprLocals`):**
   ```java
   if (isMyPattern(ie.fexpr, ie.args)) {
       return countExprLocals((Expr) ie.args.nth(0)) + countExprLocals((Expr) ie.args.nth(1));
   }
   ```
3. **Bytecode emission (`convert`):**
   Wire **both** `StaticInvokeExpr` and `InvokeExpr`:
   ```java
   } else if (isMyPattern(sie)) {
       emitWithExprSection(b, sie, BC_TAG_CALL, () -> {
           b.beginMyPatternOperation();
           convert((Expr) sie.args.nth(0), b);
           convert((Expr) sie.args.nth(1), b);
           b.endMyPatternOperation();
       });
   }
   ```

### File 3: `src/jvm/net/javacrumbs/cloffle/bytecode/CloffleCoreBytecodeArchive.java`
Increment `public static final int VERSION` whenever a new `@Operation` is added.

---

## 5. Verification Protocol & Measuring Scalar Replacement

To confirm that a pattern fusion optimization is working and eliminating heap allocations:

### Step 1: Unit Testing (`GuestCompilationUnitTest.java`)
Add test cases in `src/test/java/net/javacrumbs/cloffle/GuestCompilationUnitTest.java`:
1. Test normal execution, type edge cases, and `null` handling.
2. Assert `(CompilerDirectives/inCompiledCode)` returns `true` to prove the path is JIT-compiled.
3. Assert `(string? result)` is `true`.
4. Run full test suite:
   ```sh
   export ENV=local && eval "$(direnv export zsh)"
   clojure -T:build run-tests
   ```

### Step 2: JMH Benchmark (`StringBenchmark.java`)
Add a guest benchmark function:
```clojure
(defn guest-my-pattern [a b]
  (my-pattern a b))
```
Measure throughput and GC allocation rate:
```sh
clojure -T:build run-benchmarks :args '["StringBenchmark.guestMyPattern", "-prof", "gc"]'
```
Verify that `gc.alloc.rate.norm` drops to 0 B/op (for predicates/fused extractions) or single-string size (for concatenations).

### Step 3: Graal Compiler Graph & Scalar Replacement Check
1. Register `"guestMyPattern" "guest-my-pattern"` in `guest-compilation-hints` in `build.clj`.
2. Run automated scalar replacement:
   ```sh
   clojure -T:build check-scalar-replacement :benchmark '"StringBenchmark.guestMyPattern"' :guest true
   ```
3. **Verify PASS output:**
   ```text
   Analyzing target/graal-dumps-pea/TruffleHotSpotCompilation-XXXX[CloffleBytecodeRootNode[...]].bgv
   PASS  target/graal-dumps-pea/TruffleHotSpotCompilation-XXXX[...].bgv
     FinalPartialEscapePhase [29]: 4 nodes, linear
     After low tier [84]: 19 nodes, branches, calls
   Scalar replacement check passed.
   ```
4. If allocations survive, open the `.bgv` file in Ideal Graph Visualizer (IGV) or run `clojure -T:build analyze-graal-graph` to inspect `nodeSourcePosition` on the surviving `ForeignCallNode`.

---

## 6. Checklist for the Implementing Agent

- [ ] Select target pattern (e.g. Target 1 Namespaced Keywords or Target 2 `CoreStr4`).
- [ ] Define `@Operation` in `CloffleBytecodeRootNode.java` with static specializations.
- [ ] Increment `CloffleCoreBytecodeArchive.VERSION`.
- [ ] Add AST recognizer in `ExprToBytecode.java`.
- [ ] Update `countExprLocals` in `ExprToBytecode.java` for both invoke types.
- [ ] Update `convert` in `ExprToBytecode.java` for `StaticInvokeExpr` and `InvokeExpr`.
- [ ] Add unit tests in `GuestCompilationUnitTest.java`.
- [ ] Verify test suite: `clojure -T:build run-tests`.
- [ ] Add benchmark in `StringBenchmark.java` and hint in `build.clj`.
- [ ] Verify zero allocations: `clojure -T:build check-scalar-replacement :benchmark '...' :guest true`.
- [ ] Commit with `git commit -s`.
