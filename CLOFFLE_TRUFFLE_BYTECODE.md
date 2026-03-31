# Cloffle Truffle Bytecode DSL Migration

This document tracks the progress, implementation details, and remaining work for migrating Cloffle's AST interpreter to the [Truffle Bytecode DSL](https://github.com/oracle/graal/blob/master/truffle/docs/BytecodeDSL.md).

**Upstream references**

* **Concepts / API:** [BytecodeDSL.md](https://github.com/oracle/graal/blob/master/truffle/docs/BytecodeDSL.md) — language and builder model.
* **Best practical examples:** [Graal `com.oracle.truffle.api.bytecode.test` … `/examples`](https://github.com/oracle/graal/tree/master/truffle/src/com.oracle.truffle.api.bytecode.test/src/com/oracle/truffle/api/bytecode/test/examples) — tutorial-style Java (`GettingStarted`, `BuiltinsTutorial`, `SerializationTutorial`, `ContinuationsTutorial`, `InstrumentationTutorial`, `ParsingTutorial`, …). **Use this directory as the primary reference** for how to apply the Truffle Bytecode DSL; it is more instructive than the prose doc alone.

## Bootstrap and `clojure.core`

*   **`RT` static initialization does not load `clojure/core`**. Classloading `clojure.lang.RT` must not imply that any particular execution backend (Truffle AST vs bytecode DSL) has bootstrapped `clojure.core`. Call sites that need `clojure.core` must invoke `RT.load("clojure/core")` (or a future bytecode-based bootstrap) explicitly.
*   **Today, `Compiler.load` → `CloffleCompiler.compile` still evaluates `core.clj` via the Truffle AST interpreter (`ExprToNode`)**, not via `ExprToBytecode`. Replacing that with AOT bytecode load/deserialize is tracked under **Full Integration** below.
*   **`RT.CHECK_SPECS` is permanently `false`**: Cloffle never runs `clojure.spec.alpha/macroexpand-check` during macro expansion (`Compiler.checkSpecs` / `checkSpecsAt`). This avoids spec machinery during bootstrap and keeps macro expansion independent of `clojure.spec.alpha` loading order.


## Infrastructure Implemented

*   **Java 21 Upgrade**: Upgraded the build environment to target Java 21 to support Truffle Bytecode DSL's code generation.
*   **Bytecode Root Node**: Created `CloffleBytecodeRootNode` utilizing `@GenerateBytecode` to define Clojure-specific bytecode operations.
*   **AST to Bytecode Compiler**: Created `ExprToBytecode` to traverse Clojure's `Compiler.Expr` AST nodes and translate them into Truffle Bytecode using `CloffleBytecodeRootNodeGen.Builder`.
*   **AOT Serialization**: Implemented `CloffleBytecodeSerializer` and `CloffleBytecodeDeserializer` to natively serialize the generated Truffle Bytecode and Clojure constants (Keywords, Symbols, Classes, etc.) to a binary format.
*   **Mini Core Test Environment**: Established `core_mini.clj` and `MiniCoreTest` (Java `main`) for iterative, incremental testing by piping `core.clj` (or slices) through `ExprToBytecode` when exploring full-core behavior.
*   **JUnit: minimal bytecode DSL suite**: `clojure.lang.ExprToBytecodeTest` exercises `ExprToBytecode` → `CloffleBytecodeRootNode` **without** loading `clojure.core` and **without** running `CloffleCompiler` / `ExprToNode`. Serialization round-trip is covered on a simple constant. This avoids implying that bytecode bootstrapped core or that the AST interpreter validated the DSL.
*   **Build**: `clojure -T:build run-bytecode-dsl-tests` runs that JUnit class (optional `:fresh`, `:args` for JUnit discovery). Reports under `target/surefire-reports`.

### `ExprToBytecodeTest` — core-free forms exercised (2026-03)

Run `clojure -T:build run-bytecode-dsl-tests` (default selects `ExprToBytecodeTest`). These pass today; they are the practical “no `clojure.core`” surface for analyzer + bytecode (not an exhaustive list of every `Compiler.Expr` type).

| Area | Examples / notes |
|------|------------------|
| Literals | `nil`, booleans, longs, doubles, **ratios** (`1/2`), strings, keywords, chars (`\z`), empty and non-empty vector/map/set |
| Special forms | `if` (including nested), `do`, `quote` (lists **and symbols**), **`let*`** (not the `let` macro), **`def`** + unqualified symbol read, **`var`**, `try`/`catch` (including `throw`), `try`/`finally` |
| Functions | **`fn*`** only — the **`fn` macro is not available** without `clojure.core`; **multi-arity** direct calls `((fn* ([] …) ([x] …) …))` / `((fn* …) arg)` (read as two open parens before `fn*`, not three) and **`let*`** + symbol invoke |
| More literals | **BigInt** (`…N`), **regex** (`#"…"`) |
| Java interop | `new`, static methods (`Long/valueOf`), **static fields** (`Long/MAX_VALUE`), instance methods (`.length` → `Integer`), `instance?` |
| Metadata | e.g. `^{:x 1} [1 2]` (`MetaExpr`) |
| Not in this suite | `loop`/`recur` (bytecode builder backward-branch limitation); **`let`** / **`fn`** and other **core macros** — use **`let*`** / **`fn*`** in tests instead |

**Gotchas:** (1) Java interop return types follow Reflector / JVM rules (e.g. `.length` → `Integer`, not `Long`). (2) In **`let*`**, later bindings see earlier locals (e.g. `(let* [a 1 b a] b)` is `1`, not “increment”).

**Implementation note:** Multi-arity **`fn*`** dispatch in `ExprToBytecode` uses **nested** Truffle `Conditional` nodes (each branch is `CheckArity` + body + else chain ending in `ThrowArity`), not a flat list of broken `Conditional`s. The **arg-count** temp slot for dispatch is allocated **before** the inner `beginBlock` so `endBlock`’s `CLEAR_LOCAL` does not clear a slot index reused with outer binding stores (e.g. **`let*`** initializers).

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
*   `RecurExpr`: Intended for `loop` boundaries with `BytecodeLabel` targets and slot updates. **The Truffle Bytecode DSL builder rejects backward branches** (`IllegalStateException`: use a While-style pattern instead). Until `ExprToBytecode` is refactored accordingly, loop/recur-style control flow is not reliably usable from generated bytecode; the minimal JUnit suite does not cover `loop`/`recur`. Still needs support for `recur` to function head boundaries via tail call exceptions.

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

### ExprToBytecode

*(No open items tracked here; multi-arity `fn*` direct calls are covered by `ExprToBytecodeTest`.)*

### Core Execution
*   **`RecurExpr`**: Tail call exceptions to function root bounds are not yet properly generated/caught for self-recursive function forms. We only successfully process loop/recur. Currently throws unhandled exceptions during fallback.

### Dynamic Bindings
*   Support `binding` macros (`clojure.lang.Var.pushThreadBindings` / `popThreadBindings`).

### Java Interoperability
* The loading macros (e.g. `with-loading-context` and `ns`) use dynamic thread bindings which work partially, but loading nested class instances like macros requires full evaluation via Truffle, which gets bogged down by classloader lookup complexities.

### Standard Macros & Forms Implementation Updates

Following up on the original compiler progress, we tested the evaluation of the actual initial lines of `src/clj/clojure/core.clj` within the new Truffle Bytecode DSL using a `MiniCoreTest` suite that bootstraps the native Clojure environment and pipes `core.clj` directly to the `ExprToBytecode` converter.

Significant portions of `clojure.core` forms are successfully handled natively by the compiled Java implementations, including:
1. `unquote`
2. `unquote-splicing`
3. `list`
4. `cons`
5. `let` (Macro)
6. `loop` (Macro)
7. `fn` (Macro)
8. `first`, `next`, `rest`, `conj`, `second`, `ffirst`, `nfirst`, `fnext`, `nnext`, `seq`
9. `instance?`
10. `seq?`, `char?`, `string?`, `map?`, `vector?`
11. `assoc`
12. `meta`, `with-meta`
13. `last`, `butlast`
14. `defn` (Macro)
15. `defmacro` (Macro)
16. `when`, `when-not` (Macro)
17. `false?`, `true?`, `boolean?`, `not`, `some?`, `any?`
18. `str`, `symbol?`, `keyword?`, `symbol`, `gensym`, `keyword`
19. `cond` (Macro)

#### Progress Bottlenecks & Fixes Made

*   **List Creation in `clojure.lang.APersistentVector` Construction**: `core.clj` macros like `defn` and `defmacro` frequently evaluate `vector` syntax explicitly across internal compilation paths using forms like `&form`. When compiling this dynamically down to Truffle, standard execution defaults to `clojure.lang.PersistentList` evaluations which clash with `clojure.lang.APersistentVector$create` casting if they haven't explicitly been coerced. Calling `clojure.lang.RT.seq(to-array(clojure.lang.RT.list(...)))` resolved these dynamic type conversion failures during macro-eval.
*   **Recur loop bounds**: Implemented proper resolution for loop blocks to intercept jump recursion through exact `BytecodeLabel` execution, overriding outer frames correctly in Truffle stack loops. Stack overflow bugs during tail calls to internal closure evaluation operations directly in `doCall` wrapper loops were resolved by enforcing loop depth checking directly alongside strict Try/Catch stack-frame bounds inside `ClojureClosure`.
*   **Recursive macro execution logic evaluation limits**: Bootstrapping `clojure.core` directly triggers massive recursive calls of inner `FnExpr` executions (especially for self-referential helper methods dynamically evaluated into metadata maps). Implementing a depth counter cut-off within `ClojureClosure.doCall` allows isolating specific inner infinite loops before they trigger JVM-level stack overflow panics.
*   **Vector Instantiation within let evaluation**: When `defmacro` creates inner blocks with sequence logic using `&form` context, we found explicit `.clojure.lang.RT.vector` resolution needed an intermediate evaluation array layer `(to-array)` when dealing with nested lists that previously threw `ClassCastException` inside the Truffle execution model since it attempts to invoke the specific builder signatures directly against internal lists rather than evaluated interface arguments.

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
*   **Explicit `clojure.core` bootstrap**: Align runtime startup with the RT policy above—no silent `load("clojure/core")` in `<clinit>`; one clear path (deserialize bytecode vs interpret source) so tests and production behavior stay honest about which backend materialized core.

## Notes & Observations

* **`ExprToBytecodeTest` vs `MiniCoreTest`**: The JUnit class is intentionally small and core-free. `MiniCoreTest` (and similar) are for stress-testing against real `core.clj` through `ExprToBytecode` and remain useful, but they require a host where core is already loaded—typically via the AST interpreter today.
* The Truffle Bytecode DSL `Builder` is highly sensitive to correct `beginBlock()` / `endBlock()` scopes to safely match `produceValue` rules for AST expressions that might execute an arbitrary sequence of nested inner `LetExpr` stores. It's often required to wrap inner blocks inside `beginBlock` to encapsulate popped storage loads securely.
* `clojure.lang.PersistentVector/create` expects an `ISeq` or explicit varargs; utilizing Java interop reflection `clojure.lang.RT/list` alongside sequence construction logic proved necessary for bridging early macro form `&form` references directly from Clojure to Truffle JVM execution.
* `LocalBindingExpr` accesses local slots mapped during analysis. However, closures capturing variables from an outer frame required explicit structural Try-Catch fallback within compiler bytecode to load the frame as `Argument(0)` and process via Truffle's `@GenerateBytecode(enableMaterializedLocalAccesses = true)` feature with `beginLoadLocalMaterialized`.
* Clojure multi-arity methods (`IPersistentCollection.seq()`) naturally evaluate in arbitrary non-deterministic map orders. To bypass variadic methods greedily consuming non-variadic strict parameter counts inside `FnExpr`, explicitly sorting functions by `(isVariadic, argCount)` during `ExprToBytecode` traversal fixes `ArityException` regressions directly.
