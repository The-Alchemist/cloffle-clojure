# Cloffle Truffle Bytecode DSL Migration

This document tracks the progress, implementation details, and remaining work for migrating Cloffle's AST interpreter to the [Truffle Bytecode DSL](https://github.com/oracle/graal/blob/master/truffle/docs/BytecodeDSL.md).

**Upstream references**

- **Concepts / API:** [BytecodeDSL.md](https://github.com/oracle/graal/blob/master/truffle/docs/BytecodeDSL.md) — language and builder model.
- **Best practical examples:** [Graal `com.oracle.truffle.api.bytecode.test` examples](https://github.com/oracle/graal/tree/master/truffle/src/com.oracle.truffle.api.bytecode.test/src/com/oracle/truffle/api/bytecode/test/examples) — tutorial-style Java (`GettingStarted`, `BuiltinsTutorial`, `SerializationTutorial`, `ContinuationsTutorial`, `InstrumentationTutorial`, `ParsingTutorial`, …). **Use this directory as the primary reference** for how to apply the Truffle Bytecode DSL; it is more instructive than the prose doc alone.

## Bootstrap and `clojure.core`

- **`RT` static initialization still does not load `clojure/core`** (no `<clinit>` load). Classloading `clojure.lang.RT` must not imply which execution backend has run.
- **`RT.init()` matches stock Clojure here:** `RT.doInit()` calls `load("clojure/core")` **before** `in-ns` / `refer`, so `#'clojure.core/refer` and the rest of `core.clj` are available after init. (Loading the full `src/clj/clojure/core.clj` through Cloffle’s compiler can still fail mid-file—e.g. analyzer/execution issues—until parity work lands; that is independent of the init wiring.)
- **Default `Compiler.load` → `CloffleCompiler.compile`** evaluates loaded source via **`ExprToNode`** or **`ExprToBytecode`** according to **`-Dcloffle.execution`** (not a separate “core only uses AST” rule). **AOT** bytecode deserialize for a packaged core is tracked under **Full Integration** below.
- `**RT.CHECK_SPECS` is permanently `false`**: Cloffle never runs `clojure.spec.alpha/macroexpand-check` during macro expansion (`Compiler.checkSpecs` / `checkSpecsAt`). This avoids spec machinery during bootstrap and keeps macro expansion independent of `clojure.spec.alpha` loading order.

## Roadmap: loading `src/clj/clojure/core.clj`

**Goal:** evaluate **`src/clj/clojure/core.clj`** through **`ExprToBytecode`** (and, per **Full Integration** below, optionally **AOT deserialize** instead of always interpreting source). Approximate **priority order** for remaining work:

| Order | Target | Notes |
| ----- | ------ | ----- |
| **1** | **`loop*` / `recur`** (including **`fn*`** tail **`recur`**) | **Implemented in `ExprToBytecode`** (While + continue flag; see **Implemented Expressions**). **JUnit:** `ExprToBytecodeTest` — multi-binding **`loop*`**, **`fn*`** tail **`recur`**, nested **`loop*`** in **`fn*`**, **`RT/conj`** accumulator **`recur`**, and **`fn* [x & xs] … recur`** with **multiple rest args** (`fnStarRestArgsRecurWalksSeq`). **Follow-on:** primitive unboxed **`recur`** only if **`core.clj`** on the bytecode path surfaces **`FrameSlotTypeException`** / bad numerics. |
| **2** | **Dynamic `binding` / thread-bound vars** | **`ReadVar`** / **`WriteVar`**, **`StaticMethodExpr`** on **`Var.pushThreadBindings`** / **`popThreadBindings`**, **`TryExpr`**. **`BytecodeDslTestSupport.evalBytecode`** wraps with **`Clojure.pushEvalThreadBindings`** / **`Var.popThreadBindings`**. **JUnit:** `varPushThreadBindingsThreadLocalRead`, `varSetBangThreadBoundThenPopRestoresRoot`, and **`emptyLetStarBindingMacroShapePushPopThreadBindings`** (empty **`let*`** + push / try / finally — same shape as expanded **`binding`** from **`clojure.core`**). Optional I/O (**`*out*`**) beyond **`Compiler`** load is not a separate bytecode gap. |
| **3** | **`locking` → monitors** (`MonitorEnterExpr` / `MonitorExitExpr`) | **Done in `ExprToBytecode`:** **`monitor-enter`** / **`monitor-exit`** lower to **`MonitorEnter`** / **`MonitorExit`** operations ( **`MonitorRegistry`**, same as the AST). The **`locking`** macro in **`core.clj`** expands to **`try`** / **`finally`** around these specials—no separate macro support needed on the bytecode path. |
| **4** | **`letfn*`** (`LetFnExpr`) | **Done in `ExprToBytecode`:** pre-register all binding **`BytecodeLocal`**s (matches **`Compiler`** pre-seed), emit each **`fn*`** init, then **`WireLetFnClosures`** (`**VirtualFrame#materialize()**` + **`ClojureClosure#setCapturedFrame`**) so sibling functions see each other — same idea as AST **`LetFnNode`**. |
| **5** | **Advanced JVM forms** (`reify`, `deftype`, `defrecord`, `proxy`) | **MVP in `ExprToBytecode`:** **`Compiler.NewInstanceExpr`** — **`deftype*`** → `nil` (same as **`ObjExpr.eval()`**); **`reify*`** → **`beginNewObject`** on the **compiled** class + **`closesExprs`** ctor args (mirrors **`ExprToNode#convertNewInstance`** / **`NewNode`**). **`ObjExpr#getCompiledClass`** is reached via reflection (package-private). **`proxy`** / **`defrecord`** usually expand to **`reify`** / **`deftype*`** + interop — not separate **`Expr`** types here. **`BytecodeDslTestSupport`** binds **`Compiler.LOADER`** during **`analyze`** so stub classes load (same need as **`CloffleCompiler.compile`**). Tests: **`ExprToBytecodeTest.ReifyAndDeftypeStar`**. |
| **—** | **Runtime integration** | **`Compiler.load`** → **`CloffleCompiler.compile`** with **`-Dcloffle.execution=bytecode`**; **`BytecodeRuntimeIntegrationTest`** + **`bootstrap_slice.clj`**; thread-binding stack (**`RT.pushThreadBindingsForEval`**, **`compile`**’s outer frame). See **Full Integration → Runtime integration (status)**. Remaining: **`require`** / **`load-file`** parity, **AOT deserialize**, **RT** bootstrap policy. |

Analyzer-only placeholders such as **`UnresolvedVarExpr`** are handled explicitly (see **Implemented Expressions** and **Pending** below), not via the generic fallback.

## Infrastructure Implemented

- **Java 21 Upgrade**: Upgraded the build environment to target Java 21 to support Truffle Bytecode DSL's code generation.
- **Bytecode Root Node**: Created `CloffleBytecodeRootNode` utilizing `@GenerateBytecode` to define Clojure-specific bytecode operations (including `**ReadVar` / `WriteVar` / `DefVar`**, `**ImportClass**` (`emitImportClass`), collection builders, `**Invoke**`, Java interop, `**SetStaticField` / `SetInstanceField**` for `set!` on fields, try/catch/finally, etc.).
- **AST to Bytecode Compiler**: Created `ExprToBytecode` to traverse Clojure's `Compiler.Expr` AST nodes and translate them into Truffle Bytecode using `CloffleBytecodeRootNodeGen.Builder`.
- **AOT Serialization**: Implemented `CloffleBytecodeSerializer` and `CloffleBytecodeDeserializer` to natively serialize the generated Truffle Bytecode and Clojure constants (Keywords, Symbols, Classes, etc.) to a binary format.
- **Mini Core Test Environment**: Established `core_mini.clj` and `MiniCoreTest` (Java `main`) for iterative, incremental testing by piping `core.clj` (or slices) through `ExprToBytecode` when exploring full-core behavior.
- **JUnit: minimal bytecode DSL suite**: `clojure.lang.ExprToBytecodeTest` exercises `ExprToBytecode` → `CloffleBytecodeRootNode` **without** loading `clojure.core` and **without** running `CloffleCompiler` / `ExprToNode`. Shared setup lives in `clojure.lang.BytecodeDslTestSupport` (`evalBytecode` installs **`Clojure.pushEvalThreadBindings`**; `compileRoot` / `compileRootNodes` compile only). **`ExprToBytecodeSourceLocationTest`** covers Truffle `Source` / `SourceSection` on the bytecode root and AOT serialization when the bytecode embeds a `Source` constant (see **Source locations** below). This avoids implying that bytecode bootstrapped core or that the AST interpreter validated the DSL.
- **Runtime integration (bytecode load path)**: **`net.javacrumbs.cloffle.compiler.BytecodeRuntimeIntegrationTest`** runs **`CloffleCompiler.compile`** on classpath **`/cloffle/bootstrap_slice.clj`** (bootstrap through **`str`**, **`symbol`**, **`keyword`**, **`cond`**, **`defn`**, **core.clj**-style variadic **`assoc`** (`let*` + **`recur`** in **`fn*`**), etc.). Smoke + **`-Dcloffle.execution=bytecode`**, **`user`** only (no **`RT.init()`** / no full **`clojure.core`** load). Same **`Compiler.load` → analyze → execute** pipeline as file loads.
- **Build**: `clojure -T:build run-bytecode-dsl-tests` runs the three JUnit classes above by default (optional `:fresh`, `:args` for JUnit discovery). **`clojure -T:build run-bytecode-runtime-integration`** runs only **`BytecodeRuntimeIntegrationTest`** (multi-file bootstrap + sequential compile + AOT serialization smoke). **`clojure -T:build run-bytecode-require-ns-integration`** runs **`RequireNsBytecodeIntegrationTest`** only: **`RT.init()`** then **`CloffleCompiler.compile`** with **`-Dcloffle.execution=bytecode`** on **`(require 'clojure.string)`** — expensive, optional; passes **`-Dcoffle.test.require-ns=true`**. Intended to chase real **`require`** / **`ns`** expansion coverage; succeeds only when full **`core.clj`** load and bytecode path are far enough along. Reports under `target/surefire-reports`. Run after bytecode or `Compiler` changes.

### Source locations (`BytecodeConfig.WITH_SOURCE`)

- **`ExprToBytecode.BYTECODE_CONFIG`** is **`BytecodeConfig.WITH_SOURCE`**, passed to `CloffleBytecodeRootNodeGen.create(...)`. The root conversion wraps the builder in **`beginSource(source)`** / **`beginSourceSection(0, source.getLength())`** … **`endSourceSection()`** / **`endSource()`** so the root node exposes a **`SourceSection`** spanning the full submitted source text (tests assert name, char index/length, and line/column in `ExprToBytecodeSourceLocationTest`).
- **Serialization**: With `WITH_SOURCE`, constants may include the Truffle **`Source`** instance. **`CloffleBytecodeSerializer`** / **`CloffleBytecodeDeserializer`** support **`TYPE_SOURCE`**: character sources only (`Source#hasBytes()` is rejected); wire format is **language id** (`Source#getLanguage()`), **name**, and **full character text**, rebuilt with `Source.newBuilder(language, content, name).build()`. Deserialize with the same config as generation (`ExprToBytecode.BYTECODE_CONFIG`). **`ExprToBytecodeSourceLocationTest`** covers execution round-trip, **`Source`** metadata after deserialize (name/language/text), **`SourceSection`** bounds vs `Source#createSection(0, length)` (multi-line, leading newline, unicode), custom **`Source`** names, language id **`cloffle`**, and full-span sections on **every** bytecode root when **`fn*`** produces inner roots (`BytecodeRootNodes#count`).

### `ExprToBytecodeTest` — core-free forms exercised (2026-03)

Run `clojure -T:build run-bytecode-dsl-tests` (default selects `ExprToBytecodeTest`, `ExprToBytecodeSourceLocationTest`, and `BytecodeRuntimeIntegrationTest`). These pass today; they are the practical “no `clojure.core`” surface for analyzer + bytecode (not an exhaustive list of every `Compiler.Expr` type), plus one multi-form **`CloffleCompiler.compile`** integration run. **Coverage includes** (among others): literals and collections, `if`/`do`/`quote`, `let*`/`letfn*`/`loop*`/`recur` (including **`recur`** with **`clojure.lang.RT/conj`** on an accumulator, **`core.clj`**-style), `fn*` (multi-arity, rest, tail **`recur`**), `def`/`var`, `try`/`catch`/`finally`/`throw`, Java `new` / interop / `instance?`, `KeywordInvokeExpr`, `AssignExpr` on Java fields, `MetaExpr`, `clojure.core/import*` (two-phase eval for short `new` names), `QualifiedMethodExpr` (`Long/valueOf` as value + invoke), `case*` / `CaseExpr` (see table row). Source attachment and bytecode serialization of `Source` are covered in **`ExprToBytecodeSourceLocationTest`**.


| Area              | Examples / notes                                                                                                                                                                                                                                                                                                                                                                                           |
| ----------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Literals          | `nil`, booleans, longs, doubles, **ratios** (`1/2`), strings, keywords, chars (`\z`), empty and non-empty vector/map/set                                                                                                                                                                                                                                                                                   |
| Special forms     | `if` (including nested), `do`, `quote` (lists **including empty** `(quote ())`, **and symbols**), `**let*`** / **`loop*`** / **`recur`** (not the `let` / `loop` macros), `**def**` + unqualified symbol read, `**var**`, `try`/`catch` (including `throw`), `try`/`finally`                                                                                                                                                                     |
| Keyword calls     | `**(:k map-or-lookup)**` (`KeywordInvokeExpr`) — e.g. `(:a {:a 1 :b 2})`, `(let* [m {:x 7}] (:x m))`, nested `(:b (:a {:a {:b 9}}))`                                                                                                                                                                                                                                                                       |
| Mutation          | `**set!**` on **Java static/instance fields** (`AssignExpr` + `Reflector`) — e.g. `clojure.lang.ExprToBytecodeTest/bytecodeTestMutableStatic`, `(set! (.x p) 42)` on `java.awt.Point`. **Var `set!`:** **`WriteVar`** with **`pushThreadBindings`** / **`popThreadBindings`** — see **`varSetBangThreadBoundThenPopRestoresRoot`** (no `clojure.core` **`binding`** macro; same bytecode shape as macroexpanded code). |
| Functions         | `**fn***` only — the `**fn` macro is not available** without `clojure.core`; **`letfn*`** (not `**letfn**`) for local mutual recursion; **multi-arity** and **`[x & xs]`** with **`recur`** (`fnStarRestArgsRecurWalksSeq`); **multi-arity** direct calls `((fn* ([] …) ([x] …) …))` / `((fn* …) arg)` (read as two open parens before `fn*`, not three) and `**let*`** + symbol invoke                                                                                                                                                                    |
| More literals     | **BigInt** (`…N`), **regex** (`#"…"`)                                                                                                                                                                                                                                                                                                                                                                      |
| Java interop      | `new`, static methods (`Long/valueOf`), **static fields** (`Long/MAX_VALUE`), instance methods (`.length` → `Integer`), `instance?`                                                                                                                                                                                                                                                                        |
| Host symbols      | `**Class/method`** as a **value** (`QualifiedMethodExpr`) compiles to a multi-arity `**fn*`** thunk via `QualifiedMethodExpr.buildThunkFnStar` (uses `**fn***`, not the `fn` macro — matches “no core” analysis). Example: `(let* [f Long/valueOf] (f 99))`.                                                                                                                                               |
| Namespace         | `**clojure.core/import***` (`ImportExpr`): bytecode `**emitImportClass**` (`RT.classForNameNonLoading` + `Namespace#importClass`). Short class names for `**new**` are only resolved at **analyze** time — import must be **evaluated** in a prior compilation (e.g. separate `evalBytecode` in tests), not bundled with `(new ShortName …)` in one `do` if analysis runs before the import side effect.   |
| `case*` dispatch  | **`CaseExpr`** — no `case` macro required; tests use hand-written `case*` (e.g. `:compact` + `:int`) with a map `{dispatch [test then] …}` as produced by `Compiler.CaseExpr.Parser`. Implementation: `CaseExprRuntime` dispatch keys + nested Truffle `Conditional`s, `Util.equiv` / identity in buckets, `skipCheck` honored. |
| Metadata          | e.g. `^{:x 1} [1 2]` (`MetaExpr`)                                                                                                                                                                                                                                                                                                                                                                          |
| Not in this suite | `**let`** / `**fn**` and other **core macros** — use `**let*`** / `**fn***` / `**loop***` in tests instead. **`binding`** is not macroexpanded from **`core`** here; use hand-written **`Var.pushThreadBindings`** / **`try`**/**`finally`** (and empty **`let*`** shape in **`emptyLetStarBindingMacroShapePushPopThreadBindings`**).                                                                                                                                                                                                                                 |


**Gotchas:** (1) Java interop return types follow Reflector / JVM rules (e.g. `.length` → `Integer`, not `Long`). (2) In `**let*`**, later bindings see earlier locals (e.g. `(let* [a 1 b a] b)` is `1`, not “increment”). (3) Small **int** fields (e.g. `Point.x`) may assert as `**Integer`**, not `**Long**`.

**Implementation note:** Multi-arity `**fn*`** dispatch in `ExprToBytecode` uses **nested** Truffle `Conditional` nodes (each branch is `CheckArity` + body + else chain ending in `ThrowArity`), not a flat list of broken `Conditional`s. The **arg-count** temp slot for dispatch is allocated **before** the inner `beginBlock` so `endBlock`’s `CLEAR_LOCAL` does not clear a slot index reused with outer binding stores (e.g. `**let*`** initializers).

## Implemented Expressions (`Compiler.Expr`)

The following forms from `Compiler.java` have been successfully mapped to Truffle Bytecode operations:

### Constants

- `NilExpr`
- `KeywordExpr`
- `StringExpr`
- `BooleanExpr` (With Clojure's truthiness rules handling `nil` and `false`)
- `NumberExpr`
- `EmptyExpr`

### Variables and Bindings

- `LocalBindingExpr`: Loads local variables or function arguments. Falls back to Truffle MaterializedFrame reads via `LoadLocalMaterialized` when crossing lexical closure boundaries.
- `LetExpr` & `BodyExpr`: Block-scoped locals and sequential execution. **`LetExpr.isLoop`** (**`loop*`**) and **`FnExpr`** method bodies (**`fn*`**) use the same **`beginWhile`** pattern: a continue flag (**`RT.T`** / **`RT.F`**) and **`result`** local replace **`Compiler`**’s **`GOTO`** loop head (**`emitBranch`** cannot target backward). **`BodyExpr`** inside the recur region uses **`convertLoopBody`** / **`emitRecurWhileBody`**; tail **`if`** uses **`beginIfThenElse`** so **`recur`** stays void. **`convertLoopTail`** handles tail **`let*`**: non-**`loop*`** forms go through **`emitLetExprAsLoopTail`** (bindings, then **`convertLoopBody`** for the body). A tail **`loop*`** inside an **outer** recur region is lowered with **`beginStoreLocal(outer.resultLocal); convert(LetExpr); endStoreLocal`** so the inner **`emitRecurWhileBody`**’s value is written to the enclosing **`LoopTarget`**—routing inner **`loop*`** only through **`emitLetExprAsLoopTail`** omitted that store and led to an uninitialized **`resultLocal`** (**`FrameSlotTypeException`**).
- **`LetFnExpr`** (**`letfn*`**): Pre-allocates a **`BytecodeLocal`** per binding and registers **`localSlots`** before emitting any init (so **`fn*`** bodies resolve sibling **`LocalBindingExpr`**s). Emits each **`fn*`**, then **`WireLetFnClosures`** to **`materialize()`** the current frame and **`setCapturedFrame`** on each **`ClojureClosure`**.
- `VarExpr` & `TheVarExpr`: Reading global `clojure.lang.Var` instances.
- **`UnresolvedVarExpr`**: Analyzer placeholder when **`resolve`** yields an unresolved symbol; **`ExprToBytecode`** throws **`IllegalArgumentException`** (`"UnresolvedVarExpr cannot be evalled"`) — same as **`Compiler.UnresolvedVarExpr.eval()`** (no bytecode emitted).
- `DefExpr`: Binding values to global `clojure.lang.Var` instances, with support for `isDynamic` metadata configuration.
- `AssignExpr` (`set!`): `**WriteVar**` (`Var.set`) for `**VarExpr**` targets — matches the JVM compiler (only valid when the var is **thread-bound**). `**SetStaticField`** / `**SetInstanceField**` (`Reflector.setStaticField` / `setInstanceField`) for field targets. `**LocalBindingExpr**` targets: store the new value in the mapped `BytecodeLocal`, then reload so the expression value is the assigned value.

### Control Flow

- `IfExpr`: Conditional branching with a custom `Truthiness` operation.
- `CaseExpr` (`case*`): Not evaluable in `Compiler` (`eval` throws); bytecode path evaluates the discriminant (`LocalBindingExpr`), computes a dispatch key via `CaseExprRuntime.intDispatchKey` or `hashDispatchKey` (mirrors JVM shift/mask), then nested `Conditional`s walk map keys; each bucket uses `Util.equiv` (or `identical` for `:hash-identity`) unless the key is in `skipCheck`. Does not emit JVM `tableswitch`/`lookupswitch`; correctness over matching `ObjExpr` bytecode exactly for edge cases (e.g. all primitive paths) is not guaranteed yet.
- `RecurExpr`: For **`loop*`** or **`fn*`**, **`ExprToBytecode`** rebinds the target locals (loop bindings or method parameters, including rest) and sets the continue flag (**same arg order as `Compiler.RecurExpr.emit`**). **`recur`** nested inside a tail **`if`** is emitted via **`emitLoopIfExpr`** / **`emitLoopBranchExpr`** (shared with **`loop*`** and **`fn*`** recur regions).
- `MonitorEnterExpr` / `MonitorExitExpr`: **`monitor-enter`** / **`monitor-exit`** → **`MonitorEnter`** / **`MonitorExit`** (`MonitorRegistry`); expression value **`nil`** (same semantics as AST `MonitorEnterNode` / `MonitorExitNode`).

### Functions and Execution

- `FnExpr` (Multi-Arity & Variadic): Compiles inner bodies as nested `RootNode`s. Built a multi-arity dispatch table using `beginConditional` / `endConditional` branches ordered intelligently to avoid Rest parameter shadowing over exact arities. Emits custom `ThrowArity` exceptions on fallthrough.
- **Lexical Closures**: Implemented using Truffle's Materialized Frames (`@GenerateBytecode(enableMaterializedLocalAccesses = true)`) and custom `CreateClosure` / `GetOuterFrame` operations.
- `InvokeExpr`: Variadic invocation of `clojure.lang.IFn`.

### Data Structures

- `ListExpr`: `clojure.lang.RT.arrayToList`
- `VectorExpr`: `clojure.lang.RT.vector`
- `MapExpr`: `clojure.lang.RT.map`
- `SetExpr`: `clojure.lang.RT.set`
- `KeywordInvokeExpr`: `**(:keyword target)`** — keyword as `IFn` on the evaluated target (`kw.invoke(target)`), emitted as `Invoke` after materializing the target in a temp local (same idea as `KeywordInvokeNode` on the AST side).

### Java Interoperability

- `NewExpr`: Object instantiation via `clojure.lang.Reflector`.
- **`NewInstanceExpr`** (`deftype*` / `reify*`): **`deftype*`** → `emitLoadNull`; **`reify*`** → **`NewObject`** op with compiled class + closure actuals (MVP; not full Clojure/JVM parity).
- `InstanceMethodExpr`: Instance method invocation.
- `StaticMethodExpr`: Static method invocation.
- `InstanceFieldExpr`: Field access, falling back to `invokeNoArgInstanceMember` if a field is not found.
- `StaticFieldExpr`: Static field access.
- `InstanceOfExpr`: Type checking.
- `StaticInvokeExpr`: Fast path for statically known `IFn` Var invocations.
- `QualifiedMethodExpr`: `**Class/method**` in **value** position — delegates to `**QualifiedMethodExpr.buildThunkFnStar`** (same arity bundle as `buildThunk`, but `**fn***` so analysis works without `clojure.core`’s `fn` macro), then `**FnExpr**` conversion. If `**preferOverloadedField()**`, emits the `**StaticFieldExpr**` overload instead.
- `ImportExpr` (`clojure.core/import*`): `**emitImportClass**` — imports the class into `**RT.CURRENT_NS**` for later analyzed forms.

### Exception Handling

- `TryExpr`: Handles `try`, `catch`, and `finally` blocks utilizing Truffle Bytecode's native exception handler nodes (`beginTryCatch`, `beginTryFinally`). Pairs with **`MonitorEnterExpr`** / **`MonitorExitExpr`** when the **`locking`** macro expands to **`try`**/`finally` around monitors.
- `ThrowExpr`: Correctly unwraps/wraps Java Throwables into `ClojureException` Truffle errors.

### Metadata

- `MetaExpr`: Attaching metadata to `IObj` instances.

## Pending / To Do

### ExprToBytecode (still unmapped → `emitLoadNull` / stderr warning)

| Status | `Compiler.Expr` / area | Notes |
| ------ | ---------------------- | ----- |
| *(none)* | — | **`UnresolvedVarExpr`** is no longer a silent fallback: **`ExprToBytecode`** throws **`IllegalArgumentException`** with the same message as **`Compiler.UnresolvedVarExpr.eval()`** (`ExprToBytecodeTest.AnalyzerPlaceholders`). |

**Follow-on / polish (roadmap #1, optional):**

- **`RT/conj` + `recur`:** Regression: **`clojure.lang.ExprToBytecodeTest.BindingsLoopsAndFunctions#loopStarRecurWithRtConjAccumulator`** (`recur` with **`clojure.lang.RT/conj`**). If something breaks later, suspect **collection / `conj` behavior** before **`recur`** wiring in **`ExprToBytecode`**.
- **Primitive `recur`:** **`BytecodeLocal`** / object frame slots for recur targets today. Change this **only** if **`src/clj/clojure/core.clj`** on the bytecode path surfaces **`FrameSlotTypeException`** or numeric wrongness—not as speculative work.

**Implemented and covered in `ExprToBytecodeTest`** (non-exhaustive): `CaseExpr` (`case*`), `ImportExpr`, `QualifiedMethodExpr` (via `buildThunkFnStar`), `KeywordInvokeExpr`, `AssignExpr` (vars / fields / locals), multi-arity `fn*` (including tail `recur`), `LetFnExpr` (`letfn*`), field `set!`, `UnresolvedVarExpr` (explicit throw), etc. Unknown expr types still hit the **`ExprToBytecode` fallback** (`WARNING: Unimplemented expression…`).

**Suggested next steps (2026-03):** (1) **Roadmap #5** — **`NewInstanceExpr`** / **`reify`** / **`deftype`** / **`proxy`** (and any other unmapped **`Compiler.Expr`** types) so more of **`core.clj`** analyzes and runs on the bytecode path. (2) **Full `core.clj` load** — unblock AST/bytecode execution past early **`defn`** forms (e.g. failures around **`=`** / locals in **`core.clj`**) so **`RT.init()`** + **`require`** integration tests are meaningful. (3) **Integration** — grow **`bootstrap_slice.clj`**, **`run-bytecode-require-ns-integration`**, and optional **AOT deserialize** per **Full Integration** below.

### Dynamic Bindings

- **Done for bytecode evaluation:** **`clojure.lang.RT.pushThreadBindingsForEval`** (six dynamic vars: **`*ns*`**, **`*warn-on-reflection*`**, … — same map as Truffle **`Clojure.initializeThread`** / **`Clojure.pushEvalThreadBindings`**), **`BytecodeDslTestSupport.evalBytecode`** wraps each run in push / **`Var.popThreadBindings`**, **`ExprToBytecodeTest`** (**`evalBytecodeThreadBindsCurrentNsForDeref`**, **`varPushThreadBindingsThreadLocalRead`**, **`varSetBangThreadBoundThenPopRestoresRoot`**, **`emptyLetStarBindingMacroShapePushPopThreadBindings`**). **`CloffleCompiler.compile`** already pushes its own (larger) compiler frame; **`executeFormBytecode`** does not add a second eval frame.

### Java Interoperability

- The loading macros (e.g. `with-loading-context` and `ns`) use dynamic thread bindings which work partially, but loading nested class instances like macros requires full evaluation via Truffle, which gets bogged down by classloader lookup complexities.

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

- **List Creation in `clojure.lang.APersistentVector` Construction**: `core.clj` macros like `defn` and `defmacro` frequently evaluate `vector` syntax explicitly across internal compilation paths using forms like `&form`. When compiling this dynamically down to Truffle, standard execution defaults to `clojure.lang.PersistentList` evaluations which clash with `clojure.lang.APersistentVector$create` casting if they haven't explicitly been coerced. Calling `clojure.lang.RT.seq(to-array(clojure.lang.RT.list(...)))` resolved these dynamic type conversion failures during macro-eval.
- **Recur loop bounds**: Implemented proper resolution for loop blocks to intercept jump recursion through exact `BytecodeLabel` execution, overriding outer frames correctly in Truffle stack loops. Stack overflow bugs during tail calls to internal closure evaluation operations directly in `doCall` wrapper loops were resolved by enforcing loop depth checking directly alongside strict Try/Catch stack-frame bounds inside `ClojureClosure`.
- **Recursive macro execution logic evaluation limits**: Bootstrapping `clojure.core` directly triggers massive recursive calls of inner `FnExpr` executions (especially for self-referential helper methods dynamically evaluated into metadata maps). Implementing a depth counter cut-off within `ClojureClosure.doCall` allows isolating specific inner infinite loops before they trigger JVM-level stack overflow panics.
- **Vector Instantiation within let evaluation**: When `defmacro` creates inner blocks with sequence logic using `&form` context, we found explicit `.clojure.lang.RT.vector` resolution needed an intermediate evaluation array layer `(to-array)` when dealing with nested lists that previously threw `ClassCastException` inside the Truffle execution model since it attempts to invoke the specific builder signatures directly against internal lists rather than evaluated interface arguments.

### Further Expressions (see **Pending → ExprToBytecode** table)

- `MonitorEnterExpr` / `MonitorExitExpr`: **`monitor-enter`** / **`monitor-exit`** → **`MonitorEnter`** / **`MonitorExit`** (`MonitorRegistry`, same as AST `MonitorEnterNode` / `MonitorExitNode`).
- Other mapped forms include **`CaseExpr`**, **`ImportExpr`**, **`QualifiedMethodExpr`**, **`AssignExpr`**, **`KeywordInvokeExpr`**, **`LetFnExpr`** (see **Implemented Expressions**).

### Advanced JVM Forms (Deferred beyond MVP)

- Full **`reify`** / **`deftype`** / **`defrecord`** semantics (protocols, **`^:volatile-mutable`**, **`proxy`** edge cases, etc.) — **`ExprToBytecode`** only handles analyzed **`NewInstanceExpr`** as above; remaining gaps show up as **`WARNING: Unimplemented expression`** or runtime errors when **`core.clj`** exercises unsupported shapes.

### Full Integration

Expression-level prerequisites for treating **`core.clj`** as loadable are ordered in **Roadmap: loading `src/clj/clojure/core.clj`** above; this section is the **build/runtime** side once those forms exist.

#### `Compiler.load` / `ns` / `require` (execution backend switch)

`clojure.lang.Compiler.load` delegates to **`CloffleCompiler.compile`**, which evaluates each top-level form via **`CloffleCompiler.executeForm`**. The JVM system property **`cloffle.execution`** selects the backend after **`Compiler.analyze`**:

| Value | Behavior |
| ----- | -------- |
| **`ast`** (default) | **`ExprToNode`** → **`ClojureRootNode`** (Truffle AST interpreter). |
| **`bytecode`** | **`ExprToBytecode`** → **`CloffleBytecodeRootNode`** (Truffle Bytecode DSL). |

Set for example with **`-Dcloffle.execution=bytecode`**. Nested loads (**`require`**, **`load-file`**, **`RT.load`** from expanded macros, etc.) use the **same** backend for every nested `compile` / `executeForm` call.

**`ns`** and **`require`** are **`clojure.core` macros**—they expand to ordinary analyzed forms; no separate `Compiler.Expr` type is required beyond whatever the expansions use. Whether **`require`** succeeds on the bytecode path depends on **`ExprToBytecode`** coverage for those expansions (same as any other loaded code).

Helpers: **`CloffleCompiler.useBytecodeExecution()`**, **`CloffleCompiler.executeFormBytecode(Compiler.Expr, Object)`** (public for tools/tests that already have an analyzed tree).

#### Runtime integration (status)

| Piece | Role |
| ----- | ---- |
| **`CloffleCompiler.compile`** | **`Compiler.load`** entry point: **`Var.pushThreadBindings`** for compiler vars + **`RT.CURRENT_NS`**, **`Compiler.LOADER`**, etc.; per-form **`executeForm`** → AST or bytecode per **`cloffle.execution`**. |
| **`RT.pushThreadBindingsForEval`** / **`CloffleCompiler.executeFormBytecode`** | **`BytecodeDslTestSupport.evalBytecode`** uses **`Clojure.pushEvalThreadBindings`** (same six dynamic vars as Truffle **`initializeThread`**), then **`ExprToBytecode` → root `call`**, then **`Var.popThreadBindings`**. **`executeFormBytecode`** does **not** add a second eval frame when already under **`compile`**’s outer push. |
| **`BytecodeRuntimeIntegrationTest`** | **`/cloffle/bootstrap_slice.clj`** (tail **`42`**) and **`/cloffle/bootstrap_extra.clj`** (tail **`7`**) — AST/bytecode parity per file; **`compileBootstrapSliceThenExtraSequentialBytecode`** (two compiles, load-like); **`bytecodeSerializationRoundTripPreservesEvalResult`** (AOT wire format). |
| **`clojure -T:build run-bytecode-dsl-tests`** | JUnit: **`ExprToBytecodeTest`**, **`ExprToBytecodeSourceLocationTest`**, **`BytecodeRuntimeIntegrationTest`**. |
| **`clojure -T:build run-bytecode-runtime-integration`** | Only **`BytecodeRuntimeIntegrationTest`** — quick integration + AOT smoke. |
| **`clojure -T:build run-bytecode-require-ns-integration`** | **`RequireNsBytecodeIntegrationTest`** only (`RT.init` + bytecode **`require`**); JVM **`-Dcoffle.test.require-ns=true`**. |

**Next (integration):** expand **`bootstrap_slice.clj`** / **`bootstrap_extra.clj`** / **`core_mini.clj`** as more **`Expr`** forms land; use **`run-bytecode-require-ns-integration`** when chasing real **`require`** parity; packaged core: **AOT deserialize** in **`build.clj`** beyond the **`run-bytecode-runtime-integration`** serialize round-trip smoke, and **`ClojureLanguage`** startup when you ship a binary core.

- Replace the current AST interpreter (`ExprToNode`) completely in the main codebase path for execution (or keep both and select via **`cloffle.execution`** until parity).
- **Build Pipeline AOT**: Integrate the serialization step into `build.clj` so that `clojure.core` is pre-compiled to a binary `.truffle_bytecode` file.
- Modify `ClojureLanguage` initialization to load and deserialize the pre-compiled binary instead of parsing `core.clj` from source.
- **`clojure.core` materialization:** static **`RT` class load** still does not pull in **`core.clj`**; **`RT.init()`** loads it via **`load("clojure/core")`**. Longer term, a single deliberate path (deserialize AOT bytecode vs interpret source) can replace repeated source loads for startup, without hiding which backend ran.

## Notes & Observations

- `**ExprToBytecodeTest` / `ExprToBytecodeSourceLocationTest` vs `MiniCoreTest`**: The bytecode DSL JUnit classes are intentionally small and core-free (with `BytecodeDslTestSupport` for shared compilation). `MiniCoreTest` (and similar) are for stress-testing against real `core.clj` through `ExprToBytecode` and remain useful, but they require a host where core is already loaded—typically via the AST interpreter today.
- The Truffle Bytecode DSL `Builder` is highly sensitive to correct `beginBlock()` / `endBlock()` scopes to safely match `produceValue` rules for AST expressions that might execute an arbitrary sequence of nested inner `LetExpr` stores. It's often required to wrap inner blocks inside `beginBlock` to encapsulate popped storage loads securely.
- **Nested recur regions:** **`fn*`** wraps each method body in **`emitRecurWhileBody`**. If the tail of that body is a **`loop*`**, the inner loop’s lowering must still assign the inner result to the **outer** region’s **`resultLocal`** (see **`convertLoopTail`** + **`LetExpr.isLoop`**). **`emitLoopBranchExpr`** still uses **`convert`** for non-**`recur`** / non-nested-**`if`** branches; tail **`let*`** (without **`loop*`**) is what **`convertLoopTail`** special-cases for void **`if`** / **`recur`**.
- `clojure.lang.PersistentVector/create` expects an `ISeq` or explicit varargs; utilizing Java interop reflection `clojure.lang.RT/list` alongside sequence construction logic proved necessary for bridging early macro form `&form` references directly from Clojure to Truffle JVM execution.
- `LocalBindingExpr` accesses local slots mapped during analysis. However, closures capturing variables from an outer frame required explicit structural Try-Catch fallback within compiler bytecode to load the frame as `Argument(0)` and process via Truffle's `@GenerateBytecode(enableMaterializedLocalAccesses = true)` feature with `beginLoadLocalMaterialized`.
- Clojure multi-arity methods (`IPersistentCollection.seq()`) naturally evaluate in arbitrary non-deterministic map orders. To bypass variadic methods greedily consuming non-variadic strict parameter counts inside `FnExpr`, explicitly sorting functions by `(isVariadic, argCount)` during `ExprToBytecode` traversal fixes `ArityException` regressions directly.

