# Generic Cloffle / Clojure Notes

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
- **reify:** The generated class has a no-arg constructor with method implementations from Clojure's bytecode emit path. `ExprToNode` instantiates it via `NewNode`, producing objects with correct method bodies, `equals`/`hashCode`/`toString` support, etc.

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

Top-level `ns`, `require`, `use`, `import`, `refer`, `in-ns`, `defprotocol`, `defmulti`, `defmethod`, `extend-protocol`, `extend-type`, `extend`, and `load` bypass Truffle and run through Clojure's host `eval`. This is intentional — these forms involve macro expansion, file loading, and namespace mutations that are best handled by the standard Clojure runtime, ensuring exact semantic parity.

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

# GraalVM Specific Optimizations in Cloffle

## `@ExplodeLoop` for Argument Evaluation

`InvokeNode`, `ProtocolInvokeNode`, and `FnMethodNode` use `@ExplodeLoop` on argument evaluation and parameter initialization loops. This allows GraalVM's Partial Escape Analysis (PEA) to keep arguments in registers (Scalar Replacement) and eliminates loop overhead and array bounds checks.

## Primitive Frame Slot Kinds

`ExprToNode` inspects `LocalBinding.getPrimitiveType()` (from Clojure compiler analysis). If a primitive type is known statically (e.g. `^long x` or `(let [x 1] ...)`), the frame slot is initialized with the correct `FrameSlotKind` (`Long`, `Double`, `Boolean`) rather than defaulting to `Illegal`. This avoids deoptimization on first write and enables scalar replacement from the start.

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

`ProtocolInvokeNode` always dispatches through `IFn.invoke()`. The Compiler provides `protocolOn` (the interface the target implements) and `onMethod` (the resolved Method) on `InvokeExpr`. These could drive a `@Specialization` fast path:

```java
if (target instanceof protocolOn) {
    return onMethod.invoke(target, args...);  // direct interface call
}
```

This mirrors what Clojure's bytecode emitter does (`emitProto`), but adds Cloffle-specific node complexity. Current behavior is semantically correct.

## Type-Specialized Nodes via getJavaClass/hasJavaClass

`Compiler.Expr` carries type information (`getJavaClass()`, `hasJavaClass()`) that ExprToNode does not currently use (except `LocalBinding.getPrimitiveType()` for frame slots). This could enable:

- **Type-specialized `IfNode`**: When both branches have the same primitive type, add `executeLong`/`executeDouble` to avoid boxing.
- **Type-specialized invoke**: When `InvokeExpr.hasJavaClass()` returns a primitive, propagate that type to avoid boxing.
- **`CaseNode` return type**: Use `CaseExpr.returnType` for primitive specialization.

## Tail-Call Optimization via tailPosition

`InvokeExpr.tailPosition` indicates calls in tail position. This could drive TCO (e.g., via `TailCallException`) for non-`recur` tail calls, reducing stack depth for mutually recursive functions.

## @ExplodeLoop on CaseNode

The `CaseNode` loop over `@Children` arrays could be annotated with `@ExplodeLoop` for Graal to unroll, improving PE for small case expressions without changing dispatch strategy.
