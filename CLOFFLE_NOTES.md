# Generic Cloffle / Clojure Notes

## Recent Truffle-Only Cutover (Mar 2026)

This repo was moved further toward a Truffle/Cloffle-only workflow to reduce confusion from Clojure AOT/bootstrap paths.

### What changed

- **Compiler entrypoint**: `Compiler.compile(...)` now delegates to `Compiler.compileCloffle(...)` instead of running the ASM loader-class generation path.
- **Expr conversion fallback**: `ExprToNode` now uses host-eval fallback for unknown `Expr` variants, and `NewInstanceExpr` deftype handling falls back to host eval instead of returning `nil`.
- **RT loading behavior**: `RT.load(...)` no longer branches to `compile(scriptfile)`; it source-loads scripts directly when needed.
- **tools.build defaults**:
  - `compile-clojure` is retired in Truffle-only mode.
  - `compile-all` is Java-only (`compile-java`).
  - `compile-tests` no longer runs `clojure.lang.Compile`.
  - `run-tests` runs Cloffle JUnit phase only.
  - `compat-test` and `compat-check` are retired (Truffle-only mode messaging).
- **REPL class naming fix**: `CloffleRepl.java` now declares `CloffleRepl` (matching filename).

### Validation snapshot

- `clojure -T:build compile-all` passes after the REPL classname fix.
- `clojure -T:build run-tests` runs, but there is still a large failure cluster tied to runtime/language bootstrapping (notably `Duplicate language id cloffle`), so test stability is still in progress.

### Important caveat

An attempted deeper cutover of non-def `Compiler.eval(...)` to always execute through Truffle caused bootstrap regressions (`clojure.core` init/deftype-related failures). The compile/build cutover remains, but eval-path migration still needs a staged strategy.

## Bytecode Generation Replacement

Cloffle now successfully routes forms through `CloffleCompiler` and executes them via the Truffle AST, replacing the standard ASM-based bytecode generation for **execution logic** when the caller explicitly chooses the Cloffle compiler path.

- **Functions (`fn`)**: Compiled to `FnNode` trees (Truffle AST) instead of JVM bytecode classes.
- **Scripts / Eval**: Executed via Truffle AST interpretation/JIT.
- **Type Definitions (`deftype`/`reify`/`gen-class`)**: Still use Clojure's ASM bytecode compiler path to generate the necessary JVM classes for type definition, as this is required by the JVM platform. Cloffle leverages `Compiler.analyze()`'s existing side-effect of generating these classes.

## Replaced tools.analyzer.jvm with Compiler.analyze()


The Truffle parse pipeline originally used `clojure.tools.analyzer.jvm` (a third-party library) to analyze Clojure forms into Clojure maps with `:op` keys, then converted those maps into Truffle nodes via `AstBuilder` and 41 individual `*NodeBuilder` classes.

This was replaced with Clojure's built-in `Compiler.analyze()`, which produces an internal `Expr` tree directly. A single `ExprToNode` converter class walks the `Expr` tree and produces Truffle `ClojureNode`s.

**Why we removed tools.analyzer.jvm:**
- **Single source of truth:** Clojure's real compiler already uses `Compiler.analyze()` to produce `Expr` trees. Using that same AST means we match Clojure's semantics exactly instead of relying on a second, separate analyzer (tools.analyzer.jvm) whose output we had to map to Truffle. One analysis pass, one AST.
- **No redundant work:** We were effectively analyzing every form twice—once in tools.analyzer.jvm (to get `:op` maps) and again implicitly when we built Truffle nodes. Using `Expr` directly removes that extra pass and keeps one representation from parse to Truffle.
- **Fewer dependencies and less code:** tools.analyzer.jvm brings its own dependency tree and is aimed at tools (linters, optimizers) that need a portable AST. We don't need that; we need the same AST the compiler uses. Dropping it removed a large transitive dependency and let us replace 41 `*NodeBuilder` classes with one `ExprToNode`.
- **Faster startup:** We no longer load the analyzer namespace or its deps at init, which improves startup time.

**Before:**
```
Source → LispReader.read() → tools.analyzer.jvm/analyze → Clojure maps → AstBuilder + 41 NodeBuilders → Truffle nodes
```

**After:**
```
Source → LispReader.read() → Compiler.analyze() → Expr tree → ExprToNode → Truffle nodes
```

**Key changes:**
- Created `ExprToNode` in `net.javacrumbs.cloffle.ast` — dispatches on `Expr` type via `instanceof` checks
- Made ~20 package-private `Compiler.Expr` inner classes and fields `public` for cross-package access
- Refactored `FnNode` and `InvokeNode` to accept `FrameDescriptor`/`Source` directly instead of depending on `AstBuilder`
- Used `Supplier<FrameDescriptor>` for lazy resolution to avoid premature freezing during nested conversions
- Used `LocalBinding` identity (not symbol name) as slot keys to handle let shadowing correctly
- Added `RT.init()` and `pushCompilerBindings()` to set up the thread-local vars that `Compiler.analyze()` expects
- Removed `tools.analyzer.jvm` and `clojure-utils` from main deps (clojure-utils kept in test scope)
- Deleted `AstBuilder`, `AstBuildException`, `AbstractNodeBuilder`, and 40 `*NodeBuilder` classes

**Benefits:** Eliminated a redundant analysis pass, removed a large transitive dependency tree, achieved closer alignment with Clojure's actual compiler semantics, faster startup (no longer loads the analyzer namespace at class init), and reduced code from ~42 files down to 1.

**Dependency note:** `org.clojure/tools.analyzer.jvm` is no longer used anywhere in the codebase; the parse pipeline uses only `Compiler.analyze()` and `ExprToNode`. If the dependency still appears in `pom.xml` or `deps.edn`, it can be removed as dead weight.

## Minimizing Clojure/Cloffle Divergence

Cloffle reuses as much of the standard Clojure runtime as possible. The guiding principle is to delegate to Clojure's own implementations wherever feasible, keeping Cloffle-specific code to the minimum needed for Truffle integration.

### deftype and reify use Compiler-generated JVM classes

`Compiler.analyze()` generates real JVM classes for both `deftype` and `reify` as a side effect. Cloffle reuses these classes directly:

- **deftype:** The definition form returns `nil` (matching Clojure). Subsequent `(new Type ...)` calls produce `NewExpr` with the generated Class, handled by `convertNew` → `NewNode`.
- **reify:** The generated class is instantiated with the closed-over constructor arguments recorded in `NewInstanceExpr.closesExprs`, not a hard-coded zero-arg constructor. This fixes `reify` forms that capture surrounding locals.
- **letfn closures:** `letfn` is not lowered as a plain `let`. `LetFnNode` binds all local function closures first, then repoints them at one shared captured frame snapshot so mutually recursive locals can see each other, matching Clojure's label-style semantics.

The former Proxy-based fallback nodes (`ReifyNode`, `DefTypeNode`) have been deleted since `compiledClass()` always returns a non-null class for standard Clojure code.

### InvokeNode and ProtocolInvokeNode cover IFn arities 0-20

Both nodes dispatch via explicit `invoke` calls for arities 0-20, matching the `IFn` interface exactly. Arity 21+ falls through to `applyTo`. This avoids the overhead of building a seq for common higher-arity calls.

### TryNode catches Throwable

`TryNode` uses `catch (Throwable e)` so that `(catch Throwable t ...)` works for `Error` subclasses (e.g. `StackOverflowError`, `OutOfMemoryError`), matching standard Clojure JVM behavior.

### set! supports all target types

`SetBangNode` handles Vars, static fields, instance fields (via `Reflector.setInstanceField`), and local bindings (for deftype mutable fields). This covers all `AssignableExpr` implementations.

### StaticInvokeExpr (direct linking)

When direct linking is enabled, the Compiler produces `StaticInvokeExpr` instead of `InvokeExpr`. ExprToNode converts these to `GenericStaticCallNode` with the resolved class and `"invokeStatic"` method name, matching Clojure's direct-linked invocation path.

### HOST_EVAL_FORMS

Top-level `ns`, `require`, `use`, `import`, `refer`, `in-ns`, `defprotocol`, `defmulti`, `defmethod`, `extend-protocol`, `extend-type`, `extend`, and `load` bypass Truffle and run through Clojure's host `eval`. This is intentional because these forms involve macro expansion, file loading, and namespace mutations that are best handled by the standard Clojure runtime.

Important compatibility detail: host-eval forms are no longer silently discarded. Cloffle now preserves their return values both at top level and when they appear inside `do`, by replacing eagerly host-evaluated subforms with pre-evaluated constants. This fixed mismatches such as top-level `defmacro`/`defprotocol` returning `nil` instead of the same value Clojure returns.

## Implementation Details

### Explicit Compiler Selection
The integration point is in `clojure.lang.Compiler`:

- `Compiler.compile(...)` runs the standard Clojure bytecode compiler.
- `Compiler.compileCloffle(...)` delegates to `net.javacrumbs.cloffle.compiler.CloffleCompiler`.

No system-property switch is used. The caller explicitly chooses which compiler path to run.
There is no legacy compatibility shim anymore; `CloffleCompiler` is the only Cloffle compiler entrypoint.

### Core Language Support
The following Clojure features are fully implemented in Truffle nodes:
- **Literals**: Numbers, strings, keywords, booleans, nil.
- **Control Flow**: `if`, `do`, `loop`/`recur` (with tail call optimization via `RecurNode`), `case`.
- **Vars & Bindings**: `def` (global vars), `let` (local bindings), `var` lookup.
- **Functions**: `fn` (anonymous functions) and invocation via `InvokeNode`.
    - **`FnDispatchNode`**: Introduced to handle the distinction between evaluating a `fn` form (returning the function object) and invoking it.
- **Java Interop**: Static/instance methods/fields, constructors (`new`), `import`, `set!`.
- **Exceptions**: `try`/`catch`/`throw`/`finally`.
- **Synchronization**: `locking` (via `MonitorEnterNode`/`MonitorExitNode`).
- **Data Structures**: Vector `[]`, Map `{}`, Set `#{}` literals.

### ClassLoader Handling
`CloffleCompiler` and `Clojure.java` now correctly manage the Thread Context ClassLoader (TCCL) to ensure that dynamically generated classes (from `deftype`/`reify`) are visible during compilation and execution.

### Compatibility
The Cloffle compiler path passes **100% (730/730)** of the standard Clojure test suite, ensuring high fidelity with standard Clojure semantics.

## Recent Compatibility Fixes

Several concrete Clojure/Cloffle divergences were found with paired regression tests and then fixed in the runtime:

- **Host-eval return values:** `hostEval()` now returns the host-evaluated result, and parse-time eager host evaluation inside `do` preserves those values instead of dropping them.
- **`letfn` mutual recursion:** added `LetFnNode`, which constructs all local closures before capturing the final shared lexical environment.
- **`reify` closed-overs:** `ExprToNode.convertNewInstance()` now threads `NewInstanceExpr.closesExprs` into `NewNode`, fixing `reify` instances that capture locals.
- **Protocol dispatch:** protocol call analysis is enabled in the Cloffle compiler bindings, and `ProtocolInvokeNode` now uses the analyzer-provided protocol metadata plus a reflective fallback to survive interface/classloader identity mismatches.
- **Exception identity on the compiler path:** uncaught exceptions now escape as the original Java throwable instead of being rewritten as `ClojureException` or `RuntimeException(e)`. `TryNode` still unwraps `ClojureException` defensively for matching, but the direct `CloffleCompiler` path now preserves exact exception type/message more closely. The `Context.eval` polyglot boundary still surfaces uncaught failures as `PolyglotException`, which is expected on the Graal polyglot API.
- **Primitive-hinted numeric coercion:** the real mismatch was narrower than first feared. Plain inferred numeric locals already preserved `Ratio`/`BigInt` correctly on the direct compiler path, but explicitly hinted primitive params (`^long`, `^double`) were not coercing like Clojure. `BindingNode` now uses `RT.longCast` / `RT.doubleCast` semantics for primitive slot writes and rebinding, restoring Clojure-compatible coercion and overflow checks.

These fixes are covered by explicit compatibility tests in `CloffleReproTest` in addition to the broader paired behavior suite. Coverage was also expanded for direct compiler-path `deftype`/protocol dispatch (`AdvancedFeaturesTest`), direct compiler-path primitive-hint coercion (`CloffleCompilerTest`), and polyglot-boundary exception message/type reporting (`CloffleReproTest`).

## Modifications to upstream Clojure classes

Changes to `src/jvm/clojure/lang/` fall into three categories:

**Visibility-only (Compiler.java):** ~22 inner `Compiler.Expr` classes and ~20 fields/methods changed from package-private to `public` so that `ExprToNode` (in a different package) can access the AST. Zero behavioral changes.

**Truffle interop annotations (8 files):** `AFn`, `APersistentMap`, `APersistentSet`, `APersistentVector`, `ASeq`, `Keyword`, `LazySeq`, `Symbol`, and `Var` implement `TruffleObject` and export `InteropLibrary` messages. This makes Clojure data types first-class polyglot citizens on GraalVM without changing their Clojure-side semantics.

**JDK modernization (RT.java):** Removed deprecated `SecurityManager` and `ThreadDeath` from default imports, removed `AccessController.doPrivileged` wrapper in `makeClassLoader()` (deprecated since Java 17, removed in Java 24). These are not Cloffle-specific — they're needed on modern JDKs.

**Reflector.java is now unmodified** from upstream. A previous Cloffle change widened `paramArgTypeMatch()` to accept `Double`/`Float` for `int`/`long`/`double` parameters (to handle Truffle polyglot boundary types). This was reverted because the interop layer (`ClojureInterop.unwrapFromPolyglot`) handles type unwrapping before values reach `Reflector`, and Clojure internally uses `Long`/`Double` exclusively.

## Deleted dead code

- **`HostInteropNode`** — was never wired into `ExprToNode`. Instance method/field calls go through `InstanceCallNode`/`InstanceFieldNode` instead.
- **`ReifyNode`, `DefTypeNode`** — Proxy-based fallback implementations for `reify`/`deftype`. Superseded by using `Compiler.analyze()`-generated JVM classes directly via `NewNode`.
- **`LegacyInvokeNode`, `LegacyFnMethodNode`** — older implementations kept only for benchmarking comparison. No longer needed.
- **`UnaryStaticCallNode`, `BinaryStaticCallNode`, `AbstractStaticCallNode`** — MethodHandle-based fast paths for 1- and 2-arg static calls. Replaced by `GenericStaticCallNode`.
- **`AstBuilder`, `*NodeBuilder`** — The old `tools.analyzer.jvm` based pipeline.

## Compile-time vs Runtime Evaluation Discrepancy Fix

A semantic discrepancy was identified where certain legacy or complex expressions (like `ListExpr` and `QualifiedMethodExpr`) were handled in `ExprToNode` by calling `eval()` at compile-time instead of generating AST nodes for runtime execution. This meant side effects or instance creation happened once during compilation rather than on every execution.

**Fixes applied:**
- **ListExpr**: Implemented `ListNode`, which evaluates items at runtime and creates a list. Replaced the `eval()` call in `ExprToNode` with `convertList` -> `ListNode`.
- **QualifiedMethodExpr**: Updated `ExprToNode` to properly handle thunk generation. It now calls `Compiler.buildThunk` (exposed as public) to get the `FnExpr` AST and converts that to Truffle nodes, ensuring the thunk is instantiated at runtime.
- **Fallback safety**: Removed the dangerous fallback `return new ObjectNode(expr.eval())` for unhandled `Expr` types. `ExprToNode` now throws `UnsupportedOperationException` for unknown types, forcing a fail-fast behavior instead of silent incorrect semantics.

## Compatibility Testing Framework

A robust regression testing framework has been added to verify Cloffle against popular 3rd-party Clojure libraries. This ensures that Cloffle remains compatible with the broader ecosystem beyond just the core Clojure language tests.

### Framework Features (`build.clj`)

- **Configuration:** Projects are defined in the `external-projects` map in `build.clj`. Each entry specifies dependencies, source/test directories, and exclusions. Projects live as git submodules in `src/external-projects/`.
- **Task `compat-check`:** A single task that automates the entire verification process:
    1.  **Submodules:** Updates submodules via `git submodule update --init --recursive` (pinned SHAs for reproducible local builds). Use `:latest true` or `COMPAT_CHECK_LATEST=true` to fetch latest remote commits (for CI full builds).
    2.  **Compile:** Compiles any Java sources required by the project.
    3.  **Run (Clojure):** Runs the project's tests using the standard Clojure compiler (ground truth).
    4.  **Run (Cloffle):** Runs the same tests using the Cloffle compiler path (Truffle).
    5.  **Report & Compare:** Generates JUnit XML reports for both runs, parses them, and prints a diff of any discrepancies.

### Verified Projects

The following projects have been verified to have **identical behavior** (pass/fail parity) on both Clojure and Cloffle:

| Project | Tests | Status | Notes |
| :--- | :--- | :--- | :--- |
| **Cheshire** | 116 | **PASS** | JSON encoding/decoding. Includes generative tests. |
| **Ring (Core)** | 190 | **PASS** | Web library. Includes middleware, cookies, sessions. (2 failures match baseline). |
| **Compojure** | 21 | **PASS** | Routing library. |
| **clj-http** | 196 | **PASS** | HTTP client. (1 error matches baseline). |
| **Hiccup** | 64 | **PASS** | HTML generation library. |

### How to Run

```bash
# Run checks for all projects (uses pinned submodule SHAs)
clj -T:build compat-check

# Run check for a specific project
clj -T:build compat-check :project :cheshire

# CI: run against latest remote commits of each submodule
clj -T:build compat-check :latest true
# or: COMPAT_CHECK_LATEST=true clj -T:build compat-check
```

# GraalVM Specific Optimizations in Cloffle

## `@ExplodeLoop` for Argument Evaluation

`InvokeNode`, `ProtocolInvokeNode`, and `FnMethodNode` use `@ExplodeLoop` on argument evaluation and parameter initialization loops. This allows GraalVM's Partial Escape Analysis (PEA) to keep arguments in registers (Scalar Replacement) and eliminates loop overhead and array bounds checks.

## Primitive Frame Slot Kinds

`ExprToNode` inspects `LocalBinding.getPrimitiveType()` (from Clojure compiler analysis). If a primitive type is known statically, the frame slot is initialized with the correct `FrameSlotKind` (`Long`, `Double`, `Boolean`) rather than defaulting to `Illegal`. This avoids deoptimization on first write and enables scalar replacement from the start.

One subtle compatibility bug was found here: primitive-hinted function params were originally using Java-style `longValue()` / `doubleValue()` coercion, which diverged from Clojure for values like `Ratio` and out-of-range `BigInt`. The runtime now uses Clojure's own `RT.longCast` / `RT.doubleCast` rules for primitive slot writes and rebinds. That means:

- `^long` now truncates/coerces ratios the same way Clojure does
- `^long` now rejects out-of-range `BigInt` with the same `IllegalArgumentException` behavior
- `^double` now coerces ratios and big integers the same way Clojure does

The direct compiler-path tests also showed that non-hinted numeric locals such as plain `loop [x 0 ...]` were already preserving `Ratio` and `BigInt` correctly, so the actual issue was with explicit primitive hints rather than the entire primitive-slot optimization strategy.

## Benchmarks

*   `CloffleNodeBenchmark.java` measures invoke, recur loop, and var read performance.
*   `NamespaceBenchmark.java` measures var resolution through the polyglot `Context.eval` path.
*   `StubBenchmark.java` measures baseline polyglot boundary overhead.

# Potential Future Improvements

Performance-related ideas that have been analyzed but not yet implemented, to avoid increasing Clojure/Cloffle divergence prematurely.

## CaseNode O(1) Dispatch

The current `CaseNode` does a linear scan with `Util.equiv()` for each case branch. `Compiler.analyze()` already computes `shift`, `mask`, `low`, `high`, `switchType`, and `testType` on `CaseExpr` for hash-based or table-switch dispatch:

- For `testType == intKey` + `switchType == compactKey`: use an array-indexed lookup (table switch).
- For hash-based `testType`: use `(hash(value) >> shift) & mask` to index into a lookup table, with `skipCheck` fallback for collisions.

This would be the highest-impact single optimization for `case`-heavy code, but adds Cloffle-specific logic. Truffle/Graal's PE may handle the linear scan adequately for small case counts.

## Typed Protocol Fast Path in ProtocolInvokeNode

This is no longer just a future idea: `ProtocolInvokeNode` now consumes the analyzer-provided `protocolOn` and `onMethod` metadata and attempts a direct interface/method path before falling back to generic protocol-var invocation. A reflective fallback by method name/arity is also used to tolerate classloader-identity mismatches between the protocol interface metadata and the generated runtime class.

There is still room to make this more Truffle-native with true DSL specializations/caching, but the current implementation is now semantically correct for the compatibility regressions that were found.

## Type-Specialized Nodes via getJavaClass/hasJavaClass

`Compiler.Expr` carries type information (`getJavaClass()`, `hasJavaClass()`) that ExprToNode does not currently use (except `LocalBinding.getPrimitiveType()` for frame slots). This could enable:

- **Type-specialized `IfNode`**: When both branches have the same primitive type, add `executeLong`/`executeDouble` to avoid boxing.
- **Type-specialized invoke**: When `InvokeExpr.hasJavaClass()` returns a primitive, propagate that type to avoid boxing.
- **`CaseNode` return type**: Use `CaseExpr.returnType` for primitive specialization.

## Tail-Call Optimization via tailPosition

`InvokeExpr.tailPosition` indicates calls in tail position. This could drive TCO (e.g., via `TailCallException`) for non-`recur` tail calls, reducing stack depth for mutually recursive functions.

## @ExplodeLoop on CaseNode

The `CaseNode` loop over `@Children` arrays could be annotated with `@ExplodeLoop` for Graal to unroll, improving PE for small case expressions without changing dispatch strategy.
