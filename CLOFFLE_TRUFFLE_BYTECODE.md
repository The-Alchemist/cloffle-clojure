# Cloffle Truffle Bytecode DSL Migration

This document tracks the progress, implementation details, and remaining work for migrating Cloffle's AST interpreter to the [Truffle Bytecode DSL](https://github.com/oracle/graal/blob/master/truffle/docs/BytecodeDSL.md).

The primary goal of this migration is to significantly optimize Cloffle's startup time by generating a serializable bytecode representation of `clojure.core` Ahead-Of-Time (AOT).

## Infrastructure Implemented

*   **Java 21 Upgrade**: Upgraded the build environment to target Java 21 to support Truffle Bytecode DSL's code generation.
*   **Bytecode Root Node**: Created `CloffleBytecodeRootNode` utilizing `@GenerateBytecode` to define Clojure-specific bytecode operations.
*   **AST to Bytecode Compiler**: Created `ExprToBytecode` to traverse Clojure's `Compiler.Expr` AST nodes and translate them into Truffle Bytecode using `CloffleBytecodeRootNodeGen.Builder`.
*   **AOT Serialization**: Implemented `CloffleBytecodeSerializer` and `CloffleBytecodeDeserializer` to natively serialize the generated Truffle Bytecode and Clojure constants (Keywords, Symbols, Classes, etc.) to a binary format.
*   **Mini Core Test Environment**: Established `core_mini.clj` and `MiniCoreTest` for iterative, incremental testing of AST expression implementations.

## Implemented Expressions (`Compiler.Expr`)

The following forms from `Compiler.java` have been successfully mapped to Truffle Bytecode operations:

### Constants
*   `NilExpr`
*   `KeywordExpr`
*   `StringExpr`
*   `BooleanExpr` (With Clojure's truthiness rules handling `nil` and `false`)
*   `NumberExpr`
*   `EmptyExpr`

### Variables and Bindings
*   `LocalBindingExpr`: Loads local variables or function arguments. Falls back to Truffle MaterializedFrame reads via `LoadLocalMaterialized` when crossing lexical closure boundaries.
*   `LetExpr` & `BodyExpr`: Block scoped local variable assignments and sequential execution. Now supports `isLoop` configurations to act as jump targets.
*   `VarExpr` & `TheVarExpr`: Reading global `clojure.lang.Var` instances.
*   `DefExpr`: Binding values to global `clojure.lang.Var` instances, with support for `isDynamic` metadata configuration.

### Control Flow
*   `IfExpr`: Conditional branching with a custom `Truthiness` operation.
*   `RecurExpr`: Supported for `loop` boundaries, jumping to target `BytecodeLabel` instances updating local slots in sequence. Still needs support for `recur` to function head boundaries via tail call exceptions.

### Functions and Execution
*   `FnExpr` (Multi-Arity & Variadic): Compiles inner bodies as nested `RootNode`s. Built a multi-arity dispatch table using `beginConditional` / `endConditional` branches ordered intelligently to avoid Rest parameter shadowing over exact arities. Emits custom `ThrowArity` exceptions on fallthrough.
*   **Lexical Closures**: Implemented using Truffle's Materialized Frames (`@GenerateBytecode(enableMaterializedLocalAccesses = true)`) and custom `CreateClosure` / `GetOuterFrame` operations.
*   `InvokeExpr`: Variadic invocation of `clojure.lang.IFn`.

### Data Structures
*   `ListExpr`: `clojure.lang.RT.arrayToList`
*   `VectorExpr`: `clojure.lang.RT.vector`
*   `MapExpr`: `clojure.lang.RT.map`
*   `SetExpr`: `clojure.lang.RT.set`

### Java Interoperability
*   `NewExpr`: Object instantiation via `clojure.lang.Reflector`.
*   `InstanceMethodExpr`: Instance method invocation.
*   `StaticMethodExpr`: Static method invocation.
*   `InstanceFieldExpr`: Field access, falling back to `invokeNoArgInstanceMember` if a field is not found.
*   `StaticFieldExpr`: Static field access.
*   `InstanceOfExpr`: Type checking.
*   `StaticInvokeExpr`: Fast path for statically known `IFn` Var invocations.

### Exception Handling
*   `TryExpr`: Handles `try`, `catch`, and `finally` blocks utilizing Truffle Bytecode's native exception handler nodes (`beginTryCatch`, `beginTryFinally`).
*   `ThrowExpr`: Correctly unwraps/wraps Java Throwables into `ClojureException` Truffle errors.

### Metadata
*   `MetaExpr`: Attaching metadata to `IObj` instances.

## Pending / To Do

### Core Execution
*   **`RecurExpr`**: Tail call exceptions to function root bounds are not yet properly generated/caught for self-recursive function forms.
*   **Dynamic Bindings**: Support `binding` macros (`clojure.lang.Var.pushThreadBindings` / `popThreadBindings`).

### Further Expressions
*   `CaseExpr`: Switch/case-like optimized dispatch.
*   `MonitorArgsEnv` / `MonitorEnterExpr` / `MonitorExitExpr`: Synchronization blocks.
*   `AssignExpr`: Mutable local assignments (e.g., `set!`).

### Advanced JVM Forms (Deferred)
*   `reify`
*   `deftype`
*   `defrecord`
*   `proxy`

### Full Integration
*   Expand `core_mini.clj` until it encompasses all non-deferred forms in `clojure.core`.
*   Replace the current AST interpreter (`ExprToNode`) completely in the main codebase path for execution.
*   **Build Pipeline AOT**: Integrate the serialization step into `build.clj` so that `clojure.core` is pre-compiled to a binary `.truffle_bytecode` file.
*   Modify `ClojureLanguage` initialization to load and deserialize the pre-compiled binary instead of parsing `core.clj` from source.

## Notes & Observations

* The Truffle Bytecode DSL `Builder` is highly sensitive to correct `beginBlock()` / `endBlock()` scopes to safely match `produceValue` rules for AST expressions that might execute an arbitrary sequence of nested inner `LetExpr` stores. It's often required to wrap inner blocks inside `beginBlock` to encapsulate popped storage loads securely.
* `clojure.lang.PersistentVector/create` expects an `ISeq` or explicit varargs; utilizing Java interop reflection `clojure.lang.RT/list` alongside sequence construction logic proved necessary for bridging early macro form `&form` references directly from Clojure to Truffle JVM execution.
* `LocalBindingExpr` accesses local slots mapped during analysis. However, closures capturing variables from an outer frame required explicit structural Try-Catch fallback within compiler bytecode to load the frame as `Argument(0)` and process via Truffle's `@GenerateBytecode(enableMaterializedLocalAccesses = true)` feature with `beginLoadLocalMaterialized`.
* Clojure multi-arity methods (`IPersistentCollection.seq()`) naturally evaluate in arbitrary non-deterministic map orders. To bypass variadic methods greedily consuming non-variadic strict parameter counts inside `FnExpr`, explicitly sorting functions by `(isVariadic, argCount)` during `ExprToBytecode` traversal fixes `ArityException` regressions directly.
