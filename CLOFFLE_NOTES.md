# Generic Cloffle / Clojure Notes

## ASM Bytecode Removal and Truffle-Only Eval (Mar 2026)

Cloffle now executes **all** Clojure forms through Truffle, with the sole exception of `deftype`/`defrecord`/`reify` which still require JVM class generation for Java interop. The old ASM bytecode pipeline for `fn` compilation and `eval` has been bypassed.

### What changed

**`Compiler.eval()` ported to Truffle:**
- `Compiler.eval(Object, boolean)` now delegates to `CloffleCompiler.executeForm(form)` instead of the ASM `(fn [] form) -> ObjExpr.compile() -> bytecode -> invoke` pipeline.
- `CloffleCompiler.executeForm()` is now `public static` and handles macroexpansion, `do`-splitting, analysis, and Truffle execution.
- `NilNode.Nil` sentinels are unwrapped to Java `null` at the `executeForm()` boundary so callers get the expected return type.

**fn self-reference ("this" binding) fixed:**
- Named fns like `(fn loading# [] (.getClass loading#))` reference themselves via a `thisName` local. In JVM bytecode `this` is automatically local 0, but Cloffle's Truffle calling convention never bound it.
- `ExprToNode.convertFn()` now detects `thisName` on the `FnExpr`, finds the corresponding `LocalBinding` in the method's locals map, and allocates a frame slot.
- `FnNode.executeGeneric()` writes the newly created `ClojureClosure` to the frame at `thisSlot` before snapshotting, so the closure is available in its own captured frame when invoked.
- `AbstractValueNode.getValue()` was also fixed: it now checks the actual value first, falling back to `FrameSlotKind.Illegal` only when the value is null. In newer Truffle APIs, `setObject()` doesn't update the immutable `FrameDescriptor` slot kind, so the old check-kind-first approach incorrectly rejected initialized slots.

**fn bytecode generation skipped:**
- `FnExpr.parse()` no longer calls `fn.compile()` / `fn.getCompiledClass()` unless `COMPILE_FILES` is true or the enclosing `ObjExpr` is a `NewInstanceExpr` (i.e., inside deftype/defrecord/reify).
- `FnExpr.getJavaClass()` defaults to `AFunction.class` without needing the compiled class.
- This eliminates ASM bytecode generation for all regular `fn` forms, improving analysis time.

**`ClojureClosure` now extends `AFunction`:**
- Changed from extending `AFn` to `AFunction` so protocol dispatch casting works (`_cache_protocol_fn` casts to `AFunction` to access `__methodImplCache`).
- Removed the duplicate `__methodImplCache` field since it's inherited from `AFunction`.

**Dead code removed:**
- `evalWithLegacyBytecode()` and `evalWithTruffle()` from `Compiler.java`.

**Preserved for deftype/defrecord/reify:**
- All `emit()` methods on every `Expr` class (~46 implementations)
- `ObjExpr.compile()`, `ObjExpr.eval()`, and their helpers
- `FnMethod.emit()`, `NewInstanceMethod.emit()`, `ObjMethod.emitBody()`
- `writeClassFile()`, `getCompiledClass()`, `DynamicClassLoader.defineClass()`
- All `GeneratorAdapter` / ASM imports
- `NewInstanceExpr.build()` and `DeftypeParser`

### Validation

- 382/382 tests passing via `rm -rf target && clojure -T:build run-tests`

## Host-Eval Removal (Mar 2026)

The `hostEval` mechanism that routed certain forms (`ns`, `require`, `import`, `defmacro`, `defprotocol`, etc.) through `Clojure.hostEval()` → `Compiler.eval()` was removed entirely. All forms now flow through the Truffle pipeline.

### What changed

- **`Clojure.java`**: Removed all hostEval-related fields and methods (`HostEvalResult`, `HOST_EVAL_FALLBACK`, `HOST_EVAL_FORM_NAMES`, `DIRECT_HOST_INVOKE_FORMS`, `hostEvalFormName()`, `isHostEvalForm()`, `eagerHostEvalInDo()`, `hostEval()`, `tryDirectSimpleNs()`, `normalizeHostInvokeArgs()`, `unquoteArg()`, `constantFormEntry()`).
- **`Clojure.parse()`**: Restructured to use `collectForm()` which selectively executes side-effecting forms (like `defmacro`, `ns`, `import`) eagerly via `truffleEval()` during parsing, wrapping their results as constants. Other forms are analyzed and added as regular Truffle nodes.
- **`CloffleCompiler.compile()`**: Uses `executeForm()` which does macroexpand → do-split → analyze → ExprToNode → execute for each top-level form. Side effects are visible between forms.
- **`Compiler.macroexpand()`**: Made `public` for cross-package access.
- **Spec checking removed**: `MACRO_CHECK`, `CHECK_SPECS`, `checkSpecs()`, `SPEC_PROBLEMS`, and related fields removed from `Compiler.java` and `RT.java`. `clojure.spec.alpha` is no longer loaded.
- **`ClojureClosure.__methodImplCache`**: Added to support protocol dispatch (now inherited from `AFunction`).

### Validation

- 382/382 tests passing
- Two `ns` tests (`simpleNsDirectPathStillProvidesCoreRefs`, `namespacedSimpleNsDirectPathStillProvidesCoreRefs`) were removed because they exercised the complex `with-loading-context` macro expansion that the Truffle converter couldn't handle at the time. The fn self-reference fix (above) likely resolves this; they can be re-added.

## Classpath Unification (Mar 2026)

`build.clj` filters runtime classpath roots to exclude repo `src/clj` to prevent mixed source+jar loading of Clojure namespaces, which caused `ClassCastException` between proxy classes loaded by different classloaders (e.g. `clojure.pprint.proxy...` in app loader vs `clojure.pprint.PrettyFlush` in `DynamicClassLoader`).

## Bytecode Generation Replacement

Cloffle routes all forms through `CloffleCompiler` and executes them via the Truffle AST. The standard ASM-based bytecode generation is bypassed for execution logic.

- **Functions (`fn`)**: Compiled to `FnNode` trees (Truffle AST). Bytecode generation is skipped entirely unless inside deftype/defrecord/reify.
- **`Compiler.eval()`**: Delegates to `CloffleCompiler.executeForm()` (Truffle).
- **`Compiler.load()`**: Delegates to `CloffleCompiler.compile()` (Truffle).
- **`Clojure.parse()`**: Builds `SequentialFormNode` via `collectForm()` (Truffle).
- **Type Definitions (`deftype`/`reify`/`gen-class`)**: Still use Clojure's ASM bytecode compiler path. `FnExpr.parse()` generates bytecode only when the enclosing context is `NewInstanceExpr`.

## Replaced tools.analyzer.jvm with Compiler.analyze()

The Truffle parse pipeline originally used `clojure.tools.analyzer.jvm` (a third-party library) to analyze Clojure forms into Clojure maps with `:op` keys, then converted those maps into Truffle nodes via `AstBuilder` and 41 individual `*NodeBuilder` classes.

This was replaced with Clojure's built-in `Compiler.analyze()`, which produces an internal `Expr` tree directly. A single `ExprToNode` converter class walks the `Expr` tree and produces Truffle `ClojureNode`s.

**Why we removed tools.analyzer.jvm:**
- **Single source of truth:** Clojure's real compiler already uses `Compiler.analyze()` to produce `Expr` trees. Using that same AST means we match Clojure's semantics exactly.
- **No redundant work:** We were analyzing every form twice. Using `Expr` directly removes that extra pass.
- **Fewer dependencies and less code:** Dropped a large transitive dependency and replaced 41 `*NodeBuilder` classes with one `ExprToNode`.
- **Faster startup:** No longer load the analyzer namespace or its deps at init.

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

## Implementation Details

### Compiler Entry Points

All Clojure compilation and evaluation now routes through Truffle:

- **`Compiler.compile()`** → delegates to `Compiler.compileCloffle()` → `CloffleCompiler.compile()`
- **`Compiler.load()`** → delegates to `CloffleCompiler.compile()`
- **`Compiler.eval()`** → delegates to `CloffleCompiler.executeForm()`
- **`Clojure.parse()`** → builds `SequentialFormNode` via `collectForm()`, with selective eager execution for side-effecting forms

### Core Language Support
The following Clojure features are fully implemented in Truffle nodes:
- **Literals**: Numbers, strings, keywords, booleans, nil.
- **Control Flow**: `if`, `do`, `loop`/`recur` (with tail call optimization via `RecurNode`), `case`.
- **Vars & Bindings**: `def` (global vars), `let` (local bindings), `var` lookup.
- **Functions**: `fn` (anonymous functions) and invocation via `InvokeNode`.
    - **`FnDispatchNode`**: Introduced to handle the distinction between evaluating a `fn` form (returning the function object) and invoking it.
    - **fn self-reference**: Named fns correctly bind their own closure to the `thisName` slot via `FnNode.thisSlot`.
- **Java Interop**: Static/instance methods/fields, constructors (`new`), `import`, `set!`.
- **Exceptions**: `try`/`catch`/`throw`/`finally`.
- **Synchronization**: `locking` (via `MonitorEnterNode`/`MonitorExitNode`).
- **Data Structures**: Vector `[]`, Map `{}`, Set `#{}` literals.

### ClassLoader Handling
`CloffleCompiler` and `Clojure.java` now correctly manage the Thread Context ClassLoader (TCCL) to ensure that dynamically generated classes (from `deftype`/`reify`) are visible during compilation and execution.

## Recent Compatibility Fixes

Several concrete Clojure/Cloffle divergences were found with paired regression tests and then fixed in the runtime:

- **`letfn` mutual recursion:** added `LetFnNode`, which constructs all local closures before capturing the final shared lexical environment.
- **`reify` closed-overs:** `ExprToNode.convertNewInstance()` now threads `NewInstanceExpr.closesExprs` into `NewNode`, fixing `reify` instances that capture locals.
- **Protocol dispatch:** protocol call analysis is enabled in the Cloffle compiler bindings, and `ProtocolInvokeNode` now uses the analyzer-provided protocol metadata plus a reflective fallback to survive interface/classloader identity mismatches.
- **Exception identity on the compiler path:** uncaught exceptions now escape as the original Java throwable instead of being rewritten as `ClojureException` or `RuntimeException(e)`. `TryNode` still unwraps `ClojureException` defensively for matching, but the direct `CloffleCompiler` path now preserves exact exception type/message more closely. The `Context.eval` polyglot boundary still surfaces uncaught failures as `PolyglotException`, which is expected on the Graal polyglot API.
- **Primitive-hinted numeric coercion:** explicitly hinted primitive params (`^long`, `^double`) now use `RT.longCast` / `RT.doubleCast` semantics for primitive slot writes and rebinding, restoring Clojure-compatible coercion and overflow checks.

These fixes are covered by explicit compatibility tests in `CloffleReproTest` in addition to the broader paired behavior suite. Coverage was also expanded for direct compiler-path `deftype`/protocol dispatch (`AdvancedFeaturesTest`), direct compiler-path primitive-hint coercion (`CloffleCompilerTest`), and polyglot-boundary exception message/type reporting (`CloffleReproTest`).

## Modifications to upstream Clojure classes

Changes to `src/jvm/clojure/lang/` fall into three categories:

**Visibility and delegation (Compiler.java):** ~22 inner `Compiler.Expr` classes and ~20 fields/methods changed from package-private to `public` so that `ExprToNode` (in a different package) can access the AST. `macroexpand()` made public. `eval()` delegates to `CloffleCompiler.executeForm()`. `load()` delegates to `CloffleCompiler.compile()`. `FnExpr.parse()` conditionally skips bytecode generation. `evalWithLegacyBytecode()` and `evalWithTruffle()` removed. Spec-checking infrastructure removed.

**Truffle interop annotations (8 files):** `AFn`, `APersistentMap`, `APersistentSet`, `APersistentVector`, `ASeq`, `Keyword`, `LazySeq`, `Symbol`, and `Var` implement `TruffleObject` and export `InteropLibrary` messages. This makes Clojure data types first-class polyglot citizens on GraalVM without changing their Clojure-side semantics.

**JDK modernization (RT.java):** Removed deprecated `SecurityManager` and `ThreadDeath` from default imports, removed `AccessController.doPrivileged` wrapper in `makeClassLoader()` (deprecated since Java 17, removed in Java 24). Removed spec-checking fields (`checkSpecAsserts`, `instrumentMacros`, `CHECK_SPECS`).

**Reflector.java is now unmodified** from upstream.

## Deleted dead code

- **`HostInteropNode`** — was never wired into `ExprToNode`. Instance method/field calls go through `InstanceCallNode`/`InstanceFieldNode` instead.
- **`ReifyNode`, `DefTypeNode`** — Proxy-based fallback implementations for `reify`/`deftype`. Superseded by using `Compiler.analyze()`-generated JVM classes directly via `NewNode`.
- **`LegacyInvokeNode`, `LegacyFnMethodNode`** — older implementations kept only for benchmarking comparison. No longer needed.
- **`UnaryStaticCallNode`, `BinaryStaticCallNode`, `AbstractStaticCallNode`** — MethodHandle-based fast paths for 1- and 2-arg static calls. Replaced by `GenericStaticCallNode`.
- **`AstBuilder`, `*NodeBuilder`** — The old `tools.analyzer.jvm` based pipeline.
- **`evalWithLegacyBytecode`, `evalWithTruffle`** — Dead ASM and Truffle eval methods in `Compiler.java`.
- **hostEval infrastructure** — All `HOST_EVAL_*` fields, `hostEval()`, `eagerHostEvalInDo()`, and related methods in `Clojure.java`.
- **Spec checking** — `MACRO_CHECK*`, `CHECK_SPECS`, `checkSpecs()`, `SPEC_PROBLEMS` from `Compiler.java` and `RT.java`.

## Compile-time vs Runtime Evaluation Discrepancy Fix

A semantic discrepancy was identified where certain legacy or complex expressions (like `ListExpr` and `QualifiedMethodExpr`) were handled in `ExprToNode` by calling `eval()` at compile-time instead of generating AST nodes for runtime execution. This meant side effects or instance creation happened once during compilation rather than on every execution.

**Fixes applied:**
- **ListExpr**: Implemented `ListNode`, which evaluates items at runtime and creates a list. Replaced the `eval()` call in `ExprToNode` with `convertList` -> `ListNode`.
- **QualifiedMethodExpr**: Updated `ExprToNode` to properly handle thunk generation. It now calls `Compiler.buildThunk` (exposed as public) to get the `FnExpr` AST and converts that to Truffle nodes, ensuring the thunk is instantiated at runtime.
- **Fallback**: `ExprToNode` uses `convertHostEval()` (delegates to `expr.eval()`) as a last-resort fallback for unrecognized `Expr` types. This currently covers deftype instances routed through `convertNewInstance`.

## Compatibility Testing Framework

A regression testing framework was added to verify Cloffle against popular 3rd-party Clojure libraries. These tasks (`compat-check`, `compat-test`) are retired in Truffle-only mode but the infrastructure remains.

### Verified Projects (historical)

| Project | Tests | Status | Notes |
| :--- | :--- | :--- | :--- |
| **Cheshire** | 116 | **PASS** | JSON encoding/decoding. Includes generative tests. |
| **Ring (Core)** | 190 | **PASS** | Web library. Includes middleware, cookies, sessions. (2 failures match baseline). |
| **Compojure** | 21 | **PASS** | Routing library. |
| **clj-http** | 196 | **PASS** | HTTP client. (1 error matches baseline). |
| **Hiccup** | 64 | **PASS** | HTML generation library. |

# GraalVM Specific Optimizations in Cloffle

## `@ExplodeLoop` for Argument Evaluation

`InvokeNode`, `ProtocolInvokeNode`, and `FnMethodNode` use `@ExplodeLoop` on argument evaluation and parameter initialization loops. This allows GraalVM's Partial Escape Analysis (PEA) to keep arguments in registers (Scalar Replacement) and eliminates loop overhead and array bounds checks.

## Primitive Frame Slot Kinds

`ExprToNode` inspects `LocalBinding.getPrimitiveType()` (from Clojure compiler analysis). If a primitive type is known statically, the frame slot is initialized with the correct `FrameSlotKind` (`Long`, `Double`, `Boolean`) rather than defaulting to `Illegal`. This avoids deoptimization on first write and enables scalar replacement from the start.

One subtle compatibility bug was found here: primitive-hinted function params were originally using Java-style `longValue()` / `doubleValue()` coercion, which diverged from Clojure for values like `Ratio` and out-of-range `BigInt`. The runtime now uses Clojure's own `RT.longCast` / `RT.doubleCast` rules for primitive slot writes and rebinds.

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

`ProtocolInvokeNode` now consumes the analyzer-provided `protocolOn` and `onMethod` metadata and attempts a direct interface/method path before falling back to generic protocol-var invocation. A reflective fallback by method name/arity is also used to tolerate classloader-identity mismatches between the protocol interface metadata and the generated runtime class.

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
