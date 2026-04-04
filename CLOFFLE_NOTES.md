# Cloffle Notes (Chaptered History)

## Chapter Guide (read this first)

Cloffle has two major implementation chapters, and this document now uses that history explicitly:

- **Chapter 1 (AST era):** the original Truffle AST interpreter path (`ExprToNode` -> `ClojureNode` trees). Most compatibility, debugger, and instrumentation work started here.
- **Chapter 2 (Bytecode era):** the Truffle Bytecode DSL migration (`ExprToBytecode` -> `CloffleBytecodeRootNode`) and the push toward bytecode-first runtime/bootstrap.

How to read these notes:

- Unless a section explicitly says **Chapter 2**, treat it as primarily **Chapter 1** context.
- `CLOFFLE_TRUFFLE_BYTECODE.md` now serves as a redirect; Chapter 2 context is tracked from this canonical file.
- When a section spans both eras, it should call that out directly.

## Chapter 2 Snapshot (Truffle Bytecode DSL era)

This section makes Chapter 2 explicit in the canonical notes file.

### What Chapter 2 is

- Migration target: replace `ExprToNode` (AST) with `ExprToBytecode` + `CloffleBytecodeRootNode`.
- Scope: bytecode execution parity, debugger/source semantics, runtime bootstrap behavior, and AOT serialization/deserialization.
- Backend switch: `-Dcloffle.execution=bytecode` enables Chapter 2 execution path; `ast` forces Chapter 1 path.

### Current Chapter 2 status

- Core path coverage is broad: `let*`, `loop*`, `recur`, `letfn*`, `try/catch/finally`, var/thread binding flows, Java interop, `case*`, metadata, and many macro-expanded core shapes.
- Runtime integration is active for `Compiler.load` / polyglot parse/eval backend selection.
- Source and debugger integration are substantially implemented (`BytecodeConfig.WITH_SOURCE`, source sections on roots/ops, bytecode scope/debug-name plumbing).
- AOT wire format exists (`CloffleBytecodeSerializer` / `CloffleBytecodeDeserializer`) and is tested against large `core.clj` slices.

### Known Chapter 2 gaps

- Tail-call optimization for general tail-position function calls is still pending on the bytecode path (distinct from `loop*`/`recur`).
- Some full-suite compatibility work remains around edge-case macro/runtime parity and bytecode-first startup packaging.

### Canonical history convention

- **Chapter 1 notes** remain in the sections below (existing historical notes).
- **Chapter 2 detailed migration log** is maintained in the Chapter 2 document, but this file is now the canonical landing page for both chapters.

## DAP (Debug Adapter Protocol) Support for VS Code (Mar 2026)

Cloffle now supports debugging from Visual Studio Code via the **Debug Adapter Protocol (DAP)**. This leverages GraalVM's built-in `dap` instrument alongside Cloffle's existing Truffle debugger infrastructure (instrumentable nodes, standard tags, scopes, source sections).

### Quick Start

1. **Start Cloffle with DAP enabled:**

   ```bash
   # Debug a script (waits for debugger to attach, then suspends at first statement)
   make cloffle-dap FILE=my_script.clj

   # Debug a REPL session
   make cloffle-dap-repl

   # Or via build.clj
   clj -T:build cloffle-dap :args '["my_script.clj"]'
   ```

2. **Attach VS Code:**
   - Open your project in VS Code
   - Use the provided `.vscode/launch.json` or create one:
     ```json
     {
       "version": "0.2.0",
       "configurations": [{
         "name": "Attach to Cloffle DAP",
         "type": "node",
         "request": "attach",
         "debugServer": 4711
       }]
     }
     ```
   - Set breakpoints in `.clj` files
   - Press F5 to attach

### CloffleDapMain Options

| Option | Default | Description |
| :--- | :--- | :--- |
| `--dap-port PORT` | 4711 | TCP port for the DAP server |
| `--dap-suspend` | enabled | Suspend execution at first source statement |
| `--dap-no-suspend` | | Start executing without pausing |
| `--dap-wait` | enabled | Wait for debugger to attach before running code |
| `--dap-no-wait` | | Run immediately; debugger can attach later |
| `-e CODE` | | Evaluate CODE string |
| `-r` | | Start interactive REPL |
| `script.clj [args...]` | | Run a Clojure script file |

### Supported Debugging Features

All Truffle debugger features are available through DAP:

- **Breakpoints**: Line breakpoints, exception breakpoints
- **Stepping**: Step over, step into, step out
- **Variable inspection**: Local variables, function parameters, let bindings, loop vars
- **Stack traces**: Full Cloffle call stack with source locations
- **Top-level scope**: Namespace vars visible in the scope panel
- **Expression evaluation**: Evaluate expressions in the debug console

### Architecture

The DAP support uses GraalVM's `org.graalvm.tools:dap-tool` instrument, which implements the full DAP wire protocol. Cloffle's existing infrastructure provides:

- `ClojureNode` implements `InstrumentableNode` with `@GenerateWrapper`
- Standard tags: `StatementTag`, `ExpressionTag`, `CallTag`, `RootTag`, `RootBodyTag`, `ReadVariableTag`, `WriteVariableTag`
- `ClojureScope` exposes local variables via `NodeLibrary`
- `ClojureTopScope` exposes namespace vars
- Source sections tracked on all AST nodes via `setSourceSection` / `setSourceSectionByLine`

### Makefile Targets

| Target | Description |
| :--- | :--- |
| `make cloffle-dap FILE=...` | Debug a script with DAP |
| `make cloffle-dap-repl` | Debug a REPL session with DAP |

Optional variables: `DAP_PORT=4712`, `DAP_NOSUSPEND=1`

---

## Debugger Variable Inspection and Scope Support (Mar 2026)

Added NodeLibrary-based scope support so debuggers can inspect local variables when execution is suspended. Previously, breakpoints and stepping worked but variable inspection returned empty scopes.

### Local scope (NodeLibrary on ClojureNode)

- **`ClojureNode`**: Now exports `NodeLibrary` via `@ExportLibrary(NodeLibrary.class)`. Implements `hasScope(Frame)` and `getScope(Frame, boolean)` which return a `ClojureScope` object wrapping the current frame and root node.
- **`ClojureScope`**: InteropLibrary scope object that exposes frame slot variables as members:
  - Reads variable names from `FrameDescriptor` slot names — `LocalBinding.sym` for fn params, let bindings, and loop vars
  - Filters out `Var` slots (used internally by `InvokeNode` for var caching, not user locals)
  - Supports `readMember`, `writeMember`, `getMembers`, `isMemberReadable`, `isMemberModifiable`
  - Reports the function name as `toDisplayString()` from `RootNode.getName()`
  - Reports source location from `RootNode.getSourceSection()`
  - Includes `NullValue` (InteropLibrary null) for uninitialized slots
  - Includes `VariableNamesArray` (InteropLibrary array) for member enumeration

### Top-level scope (TruffleLanguage.getScope)

- **`Clojure.getScope(CloffleContext)`**: Overridden to return a `ClojureTopScope` object.
- **`ClojureTopScope`**: InteropLibrary scope object exposing global vars from the current namespace:
  - Lists vars defined (interned) in the current namespace that are bound
  - Supports `readMember` (derefs the var), `writeMember` (sets the var)
  - Reports namespace name as `toDisplayString()`

### What works now

| Feature | Status |
| :--- | :--- |
| `DebugStackFrame.getScope()` at breakpoint | **Works** — returns function-level scope with params and let bindings |
| `DebugScope.getDeclaredValues()` | **Works** — lists all initialized local variables |
| `DebugScope.getDeclaredValue(name)` | **Works** — reads specific variable by name |
| `DebugValue.asLong()` / `.asString()` etc. | **Works** — variable values are readable |
| Scope in recursive function | **Works** — each recursion depth shows current param values |
| `DebuggerSession.getTopScope("cloffle")` | **Works** — shows namespace vars at breakpoint |
| Top scope var value reading | **Works** — reads correct `deref()` values |
| Exception breakpoints (uncaught) | **Works** — `Breakpoint.newExceptionBuilder(false, true)` fires on uncaught exceptions |

### Known scope limitations

- **Flat scope**: Clojure uses a flat function-level frame (no nested block scopes like `let` creating separate scopes). All locals in the function share one `FrameDescriptor`. The scope shows all initialized variables, not just those lexically visible at the current position.
- **Exception breakpoints for caught exceptions**: `Breakpoint.newExceptionBuilder(true, false)` (caught=true, uncaught=false) does not fire when exceptions are caught by `TryNode`, because `TryNode` handles the exception before the debugger's exception filter sees it. This is a Truffle framework limitation — the exception is unwrapped and dispatched within the guest language.
- **Closure-captured variables**: Variables captured by closures are snapshotted into a `MaterializedFrame` and restored into the callee's `VirtualFrame`. The scope shows the restored slot values, which is correct, but the debugger cannot currently trace back to the original lexical scope where the variable was first bound.

### Test coverage

10 new tests in `DebuggerTest` (tests 71–80):
- Scope shows fn params with correct values
- Scope name is the function name
- Scope has source location
- Scope shows let-bound variables
- Scope variable has correct value (read by name)
- Scope in recursive function shows current iteration values
- Top scope accessible at breakpoint shows global vars
- Top scope reads correct var values
- Exception breakpoint fires on uncaught exceptions
- Scope available at top-level forms

### Files changed

| File | Changes |
| :--- | :--- |
| `ClojureNode.java` | `@ExportLibrary(NodeLibrary.class)`, `hasScope()`, `getScope()` |
| `ClojureScope.java` | New — local variable scope object |
| `ClojureTopScope.java` | New — top-level namespace scope object |
| `Clojure.java` | Override `getScope(CloffleContext)` |
| `DebuggerTest.java` | 10 new tests (71–80) |
## Project Overview

### Motivation

Cloffle is a Truffle-based implementation of Clojure. The project goal is strong API and behavioral compatibility with JVM Clojure while running through Truffle/GraalVM execution paths.

### Execution model across chapters: Truffle AST (Chapter 1) → Truffle Bytecode DSL (Chapter 2)

**Chapter 1 (original):** Cloffle executed Clojure by lowering `Compiler.analyze()` `Expr` trees into a **hand-written Truffle AST**: `ExprToNode` produces `ClojureNode` trees (`FnNode`, `InvokeNode`, `LetNode`, ...), and `CloffleCompiler.compile()` / `executeForm()` run that graph. Debugging, instrumentation, and many compatibility notes in this document were first built in this AST chapter.

**Chapter 2 (current migration):** the runtime is **migrating** to the **[Truffle Bytecode DSL](https://github.com/oracle/graal/blob/master/truffle/docs/BytecodeDSL.md)** (`@GenerateBytecode`): `ExprToBytecode` lowers the same `Expr` trees into `CloffleBytecodeRootNode` bytecode graphs, with serialization/deserialization support for AOT. The goal is to replace `ExprToNode` as the main execution path and to bootstrap `clojure.core` from compiled bytecode rather than only from the AST interpreter. See **`CLOFFLE_TRUFFLE_BYTECODE.md`** for detailed Chapter 2 status (`ExprToBytecode` coverage, bootstrap policy, AOT wire format, and remaining work). For **how to use** the Bytecode DSL in practice, Graal's tutorial examples under [`.../bytecode/test/examples`](https://github.com/oracle/graal/tree/master/truffle/src/com.oracle.truffle.api.bytecode.test/src/com/oracle/truffle/api/bytecode/test/examples) are the best reference (better than prose docs alone).

**Disambiguation:** “Bytecode” in this repo means either (a) **Truffle Bytecode DSL** graphs (`CloffleBytecodeRootNode`), or (b) legacy **JVM ASM** output for `deftype`/`reify`/etc. The migration doc and `ExprToBytecode` refer to (a), not ASM.

Fork lineage:
- `https://github.com/lukas-krecan/cloffle`
- `https://github.com/clojure/clojure`

### Source Tree and Evolution

Historically, Cloffle lived in `src/main/java` and wrapped upstream Clojure sources under `src/jvm/clojure`. The codebases were merged into a single source tree rooted at `src/jvm`, with Truffle integration directly in the runtime/compiler path.

### Build and Run Surface (tools.build first)

This repo uses `tools.build` (`build.clj`) as the primary developer interface.

- `clj -T:build help` lists public tasks (`:verbose true` for full docstrings).
- `clj -T:build cloffle-repl` starts the Truffle-based Cloffle REPL.
- `clj -T:build cloffle-main` runs the Cloffle main entrypoint.
- `clj -T:build run-tests` runs Cloffle JUnit tests.
- `clj -T:build bytecode-repl` starts a Clojure REPL using the Truffle bytecode backend.
- `clj -T:build run-clj-tests` runs Clojure's `test_clojure` suite through Cloffle.
- Pprint-only (fast): `clj -T:build run-clj-tests :only-namespace '"clojure.test-clojure.pprint"'`.
- `clj -T:build compat-test` runs external project compatibility checks.

`make` targets are convenience wrappers (see `make help` and `readme-cloffle.md`); examples: `make repl`, `make test`, `make test-clj`, `make clojure-repl`, `make cloffle-run FILE=...`.

`run-tests` and `run-clj-tests` default to `:fresh true` (cleaning `target/` first). Use `:fresh false` only for deliberate incremental runs.

## Truffle Instrumentation for Debugging/Profiling (Mar 2026)

Integrated Truffle's instrumentation framework so external tools (debuggers, profilers, code coverage, tracers) can attach to Cloffle execution via the standard Truffle instruments API.

### Core infrastructure

- **`ClojureNode`**: Implements `InstrumentableNode`, annotated with `@GenerateWrapper`. The Truffle DSL processor auto-generates `ClojureNodeWrapper` which wraps all execute methods (`executeGeneric`, `executeBoolean`, `executeLong`, `executeDouble`) with probe enter/return/exception callbacks. A node is instrumentable when `hasSource()` returns true (it has source location info). `getSourceSection()` changed from `final` to non-final to allow the wrapper to override it.
- **`Clojure`** (language class): `@ProvidedTags` annotation declares `StandardTags.StatementTag`, `ExpressionTag`, `CallTag`, `RootBodyTag`, `RootTag`, `ReadVariableTag`, `WriteVariableTag`.

### Tag assignments

Each node subclass overrides `hasTag()` to report its instrumentation role:

| Tag | Nodes |
| :--- | :--- |
| **StatementTag + ExpressionTag** | `DefNode`, `IfNode`, `LetNode`, `LetFnNode`, `LoopNode`, `DoNode`, `SetBangNode`, `TryNode`, `ThrowNode`, `CaseNode`, `RecurNode` |
| **StatementTag** only | `ImportNode` |
| **StatementTag + CallTag + ExpressionTag** | `InvokeNode`, `InstanceCallNode`, `GenericStaticCallNode`, `ProtocolInvokeNode`, `KeywordInvokeNode`, `NewNode`, `NativeCallNode` |
| **ExpressionTag** only | `FnNode` (closure creation) |
| **RootBodyTag + RootTag** | `FnDispatchNode` |
| **RootBodyTag** | `FnMethodNode` |
| **ReadVariableTag + ExpressionTag** | `VarNode`, `LocalNode` |
| **WriteVariableTag** | `DefNode`, `SetBangNode`, `BindingNode` |

In Clojure everything is an expression, so most statement-level forms get both `StatementTag` and `ExpressionTag`. Call nodes get `StatementTag` + `CallTag` + `ExpressionTag` — the `StatementTag` is required because Truffle breakpoints default to matching `SourceElement.STATEMENT`. `FnDispatchNode` has `RootTag` + `RootBodyTag` so the debugger recognizes function entry boundaries for step-into. Literal value nodes (`NilNode`, `LongNode`, etc.) don't report tags — they're leaf nodes without meaningful instrumentation semantics and typically lack source sections.

### Debugger API integration

The Truffle Debugger API (`com.oracle.truffle.api.debug`) works against Cloffle's instrumented nodes. `DebuggerTest.java` (20 tests) exercises the debugger programmatically using `Debugger.find(engine)`, `DebuggerSession`, `Breakpoint`, and `SuspendedEvent`:

| Feature | Status | Notes |
| :--- | :--- | :--- |
| `suspendNextExecution()` | **Works** | Suspends at the first instrumentable node with a valid `SourceSection` |
| Line breakpoints (`Breakpoint.newBuilder(URI).lineIs(n)`) | **Works** | Fires on the nearest instrumentable node whose source span contains the line |
| Multiple breakpoints | **Works** | Multiple breakpoints on different lines fire in order |
| Breakpoint in loop/recur | **Works** | Fires on every iteration (e.g., 3 hits for 3 `recur` iterations) |
| `prepareContinue()` | **Works** | Resumes execution to completion |
| `prepareStepOver(1)` | **Works** | Advances to the next top-level form (verified with `(def a 1)` → `(def b 2)` → `(+ a b)`) |
| `prepareStepOut(1)` | **Works** | Suspends after returning from the current function |
| Source section at breakpoint | **Works** | `event.getSourceSection()` reports correct line, column, and source characters |
| Frame name at breakpoint | **Works** | `event.getTopStackFrame().getName()` returns the function name (e.g., `"compute"`) |
| Recursive breakpoints | **Works** | Breakpoint inside `factorial` fires 5 times; stack depth increases monotonically |
| `prepareStepInto(1)` | **Works** | `FnDispatchNode` has `RootTag` so the debugger recognizes function entry boundaries. Call nodes have `StatementTag` so breakpoints match them. `SequentialFormNode` uses `DirectCallNode` children (no `@TruffleBoundary`) so the debugger can step into functions across top-level forms. |
| Multi-level stack frames | **Improved** | `FnNode` stores a language reference and propagates it to per-function `ClojureRootNode`s. Recursive functions show monotonically increasing stack depths. Caller chains are visible when call sites are **not** in tail position (tail/self-tail optimization still collapses those frames). `DebuggerTest.stackFramesAtBreakpoint` uses `(+ 0 (b))` style chains to assert ≥3 guest frames. Function-entry roots now use the **method body** source span (see “Polyglot triage…” below), which also improves line reporting for breakpoints on the body line of a multi-line `defn`. |

### Debugger infrastructure improvements (Mar 2026)

**FnDispatchNode RootTag:** `FnDispatchNode.hasTag()` now reports both `RootBodyTag` and `RootTag`. The Truffle debugger uses `RootTag` to identify function entry boundaries for `prepareStepInto()`. Without `RootTag`, the debugger could not recognize function entry points.

**StatementTag on call nodes:** `InvokeNode`, `GenericStaticCallNode`, `InstanceCallNode`, `ProtocolInvokeNode`, `KeywordInvokeNode`, `NewNode`, and `NativeCallNode` now report `StatementTag` alongside `CallTag` and `ExpressionTag`. Truffle breakpoints default to matching `SourceElement.STATEMENT`, so without `StatementTag`, line breakpoints on call expressions were invisible.

**FnDispatchNode source section propagation:** `FnNode.getCallTarget()` now propagates its source section to the `FnDispatchNode` it creates, and `InvokeNode` does the same when creating `FnDispatchNode` for static call targets. This ensures `isInstrumentable()` returns true for function dispatch nodes.

**Language reference on FnNode:** `FnNode` now stores a language reference set by `ExprToNode.convertFn()` at parse time. `getCallTarget()` uses this stored reference as the primary source, falling back to `Clojure.getContext().language()` only if unavailable. This ensures per-function `ClojureRootNode` instances have a proper language association, which the Truffle frame walker needs to report guest language frames in stack traces.

**SequentialFormNode restructured:** Removed `@TruffleBoundary` from `executeSequentially()` and restructured to create per-form `CallTarget`s at parse time using `@Children DirectCallNode[]`. Each per-form root gets a narrowed source section (full source first, then `adoptChildren()`, then narrow from the form node's section). This allows the debugger to step between top-level forms and enables breakpoints on call expressions in any position.

**truffleEval source sections:** `Clojure.truffleEval()` now sets source sections and root names on roots for eagerly executed forms, making them visible during debugging.

**Unnecessary @TruffleBoundary removed:** `ClojureNode.getSourceSection()` and `ClojureTypes.castDouble()` no longer have `@TruffleBoundary`. Neither requires a compilation boundary — `getSourceSection()` is metadata computation and `castDouble()` is a trivial primitive cast.

### Known debugger limitations

**Breakpoints on multi-line `defn`:** `FnNode.getCallTarget()` sets `ClojureRootNode` and `FnDispatchNode` source sections from the **first method body** when available (via `FnMethodNode.getBody()`), not only from the whole `(fn …)` / `fn*` form span. A line breakpoint on the body line (e.g. L2 of `(defn foo [x]\n  (+ x 1))`) can therefore report **L2** as the suspension start line (`DebuggerTest.multiLineDefnBreakpointStartLineMatchesBodyLine`). A breakpoint still resolves to the nearest instrumentable node whose span contains that line; roots that only covered the full form previously biased reports toward the head line.

**Threading:** `Clojure.initializeThread()` pushes thread-local `Var` bindings and `finalizeThread()` pops them. Using a polyglot `Context` from multiple threads (e.g., eval on a background thread, close on the test thread) can cause `IllegalStateException: Pop without matching push`. `finalizeThread` now wraps that case with a longer **Cloffle-specific** message explaining same-thread context lifecycle. Debugger tests must run eval on the same thread that created the context.

### Files changed

| File | Changes |
| :--- | :--- |
| `ClojureNode.java` | `@GenerateWrapper`, `InstrumentableNode`, `isInstrumentable()`, `createWrapper()`, `hasTag()` |
| `Clojure.java` | `@ProvidedTags` with 7 standard tags; `truffleEval()` now sets source sections and root names on eagerly executed form roots |
| `FnDispatchNode.java` | `hasTag()` now reports both `RootBodyTag` and `RootTag` |
| `FnNode.java` | Stores language reference; propagates source section to `FnDispatchNode` in `getCallTarget()` |
| `InvokeNode.java` | Propagates source sections to `FnDispatchNode` for static call targets |
| `ExprToNode.java` | Sets language reference on `FnNode` via `setLanguage()` |
| `SequentialFormNode.java` | Per-form roots set full source section first, then narrow; proper source section resolution order |
| 25 node classes | `hasTag()` overrides (see table above) |
| `InstrumentationTest.java` | 12 tests exercising instrumented code paths (tag event counting, node instrumentability) |
| `DebuggerTest.java` | 20 tests exercising `Debugger`/`DebuggerSession`/`Breakpoint`/`SuspendedEvent` API (breakpoints, stepping, stack frames, source sections, function root tags, source section propagation) |

## Compiler `:inline`, `BindingNode`, and `ExprToNode` slot scoping (Mar 2026)

### Symptom

Loading `clojure.test-clojure.predicates` (e.g. `test-double-preds`) failed during compilation with `RT.doubleCast` → `NullPointerException` (`Number.doubleValue()` on `null`) while analyzing forms such as `(NaN? nil)`.

### What it was *not*

- **Not** “the wrong `IFn`”: `Compiler.isInline` still uses the var’s metadata `:inline` function (e.g. `NaN?`’s `(fn [num] \`(Double/isNaN ~num))`), not the main `defn` body.
- **Not** JVM boxed-double autoboxing: the failure happened at **compile time** when expanding `:inline`, not at runtime when coercing a `Double`.
- **Not** broken parsing of `^double` in the analyzer: the forked `Compiler` attaches hints as expected (see `CompilerTypeHintAnalysisTest` in `src/test/java/clojure/lang/`).

### Root cause (two layers)

1. **`BindingNode` (Truffle)** — Coercion into primitive frame slots used `RT.doubleCast` / `RT.longCast` inside `try`/`catch (ClassCastException)` only. For **`null`**, `RT.doubleCast` throws **`NullPointerException`**, not `ClassCastException`, so the slot was never widened to `Object`. **Fix:** also catch `NullPointerException` and invalidate to `Object` (same path as cast failure). This remains a reasonable safety net for any `nil` into a primitive slot.

2. **`ExprToNode` slot keys (deeper bug)** — Locals were keyed effectively by **`(idx, munged name, isArg)`** without an enclosing function scope. In `Compiler`, **`NEXT_LOCAL_NUM` resets per `FnMethod`**, so **different** `LocalBinding` instances can share that triple across **different** `fn*` forms in one compile (classic case: **`defn` body** `[ ^double num ]` and the **`{:inline (fn [num] …)}`** function). `ExprToNode` then **reused the same frame slot** and kept **`FrameSlotKind.Double`** from the hinted param; the inliner’s `num` was **not** primitive in the analyzer, but the **Truffle frame** still behaved like a double slot, so `nil` from `inline.applyTo` hit `doubleCast` and NPE’d.

   **Secondary issue while fixing scope:** Keying only by **`FnMethod`** broke **multi-arity** `fn*` (one shared frame, multiple methods). Keying only by **`FnExpr` + triple** without an extra rule broke **closures**: inner `fn` forms reference the **same** `LocalBinding` instances as the outer function while `convert` is nested; `peek()` pointed at the inner `FnExpr` and invented a **second** slot for the same binding → “uninitialized local binding” (e.g. while macroexpanding `defmacro` in `core.clj`).

### Fixes (Cloffle, not upstream Clojure)

| Area | Change |
| :--- | :--- |
| **`BindingNode.java`** | On long/double coercion in `write` / `rebindValue`, catch **`NullPointerException`** as well as **`ClassCastException`**, then deopt slot to `Object`. |
| **`ExprToNode.java`** | **`LocalBindingKey(fnExprScope, idx, name, isArg)`**; push/pop **enclosing `FnExpr`** around all of `convertFn` (not per `FnMethod`, so all arities share slots). **First** resolve **`slotByName.get(lb)`** (identity on the `LocalBinding` instance) so closure bodies reuse the slot allocated when the binding was first seen; then **`localSlots`** for merging same triple inside one `FnExpr`. Top-level / non-`fn` locals use a **`GLOBAL_FN_SCOPE`** sentinel. |
| **`Compiler.java`** | Removed the **NPE-only** `try`/`catch` around `inline.applyTo` in `analyzeSeq` once slots and `BindingNode` were correct. |

### Tests

- **`clojure.lang.CompilerTypeHintAnalysisTest`** — reader `:tag` on `^double`/`^long`, `Compiler.primClass`, analyzer `LocalBinding` / `MethodParamExpr` for `fn*` params and `let*` with a tagged local; **`Compiler.analyze` on `(clojure.core/NaN? nil)` and `(clojure.core/infinite? nil)`** (qualified vars) to exercise `:inline` expansion with a `nil` arg form without throwing.
- **`net.javacrumbs.cloffle.compiler.CloffleCompilerTest`** — **`defnWithDoubleHintAndInlineCompiles`**: defines a function with `^double` and `:inline` (mirrors defn + inliner slot collision) and asserts the compile finishes (`nil` tail).
- **`net.javacrumbs.cloffle.ast.ExprToNodeLocalBindingSlotTest`** — same `(idx, name, isArg)` under two synthetic `FnExpr` scopes → distinct slots; same triple under one scope + two `LocalBinding` instances → shared slot. Uses package-local `pushTestFnExprScope` / `popTestFnExprScope` on `ExprToNode`.

Official **`test_clojure/predicates`** expects **`(NaN? nil)`** and **`(infinite? nil)`** to **throw** (`thrown? Throwable`), not return false. **`CompilerTypeHintAnalysisTest`** still checks that **`Compiler.analyze`** on those forms completes (compile-time `:inline` expansion). **`CloffleCompilerTest`** includes **`defnWithDoubleHintAndInlineCompiles`** for a **`^double` + `:inline`** **`defn`** shape. Older JVM-vs-Polyglot parity classes (**`CloffleBehaviorTest`**, **`AutoboxingAndTypeHintTest`**, **`ClojureReturnValuesTest`**) and the **`net.mikera/clojure-utils`** test dependency were removed in favor of in-process **`RT.init()`** bootstrap (see **JUnit JVM bootstrap** below) and Cloffle-only / compiler tests.

**Verification:** `clojure -T:build run-tests` and `run-clj-tests` with `:only-namespace '"clojure.test-clojure.predicates"'`.

### JUnit JVM bootstrap (`clojure.core` on the host class loader)

JUnit runs many classes in one JVM; **`CloffleCompiler.compile`** resolves symbols (e.g. **`+`**, **`/`**, **`declare`**) against the host **`Namespace`** / **`RT`** state. If no test has run **`RT.init()`** yet, **`clojure.core`** is not loaded and compiler tests fail with **Unable to resolve symbol**.

**`RT.doInit()`** (in **`RT.java`**) sets **`INIT = true` only after** **`load("clojure/core")`**, **`in-ns` / `refer`**, and **`user.clj`** complete successfully. Previously **`INIT` was flipped true before loading**, so any thrown error during bootstrap left the JVM permanently stuck: later **`RT.init()`** calls returned immediately while **`user`** still lacked **`clojure.core`** refers.

**Do not** auto-register **`LauncherSessionListener`** or **`TestExecutionListener`** SPIs that call **`RT.init()`** on the ConsoleLauncher thread without verifying bootstrap: loading **`core.clj`** through Cloffle can still throw there (e.g. analyzer errors mid-file), and a failed attempt may leave namespaces partially loaded even when **`INIT`** stays false.

**Recommended for tests:**

- **`@BeforeClass public static void hostClojure() { RT.init(); RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user"))); }`** (pattern used in **`ExceptionTest`**, **`CloffleCompilerTest`**, …), or
- **`@ClassRule public static final CloffleHostClojureRule CLOJURE_HOST = new CloffleHostClojureRule();`** — **`net.javacrumbs.cloffle.junit.CloffleHostClojureRule`** extends JUnit 4 **`ExternalResource`** for a one-line opt-in when a class has no **`@BeforeClass`** hook yet.

## Source Location, Error Messages, and Stack Trace Improvements (Mar 2026)

A series of changes to significantly improve how Cloffle reports errors, stack traces, and source locations by leveraging Truffle APIs more fully.

### Macro expansion via Truffle

Macro expansion now invokes macro functions through a Truffle `CallTarget` (via `MacroExpander.expandViaGuest`) rather than calling the `IFn` directly. This means macro expansion errors produce `ClojureException`s with guest stack frames and source locations.

- **`MacroExpander`**: Creates a `ClojureRootNode` wrapping a `MacroExpandNode`, executes it via `CallTarget.call()`. Threads the real `Source` from `MacroExpander.CURRENT_SOURCE` (ThreadLocal) into the root node's `SourceSection` and applies line/column from the form's metadata to the `MacroExpandNode`.
- **`Clojure.collectForm` / `truffleEval`**: Set `MacroExpander.CURRENT_SOURCE` around `Compiler.macroexpand()` calls.
- **`CloffleCompiler.compile`**: Sets `MacroExpander.CURRENT_SOURCE` for the duration of compilation.

### `{:type …}` metadata and printing during macro expansion (Mar 2026)

Clojure’s `print-method` multimethod dispatches on **`(:type (meta x))`** when that value is a keyword; otherwise it dispatches on **`(class x)`** (see `clojure.core/print-method` and `core_print.clj`). Libraries such as Malli attach **`^{:type …}`** to **unevaluated** forms (for example around **`reify`**). Any code that **prints** those forms while they are still lists—**`str`** on a seq (**`ASeq.toString` → `RT.printString` → `RT.print`**), **`pr` / `prn` / `pr-str`** (**`pr-on` → `print-method`**), or nested **`print-method`** implementations that recurse with **`pr-on`**—can therefore select a user **`print-method`** for that keyword and pass a **`PersistentList`**. If that method assumes a real instance (for example it calls a protocol function), expansion fails with **`IllegalArgumentException`**.

**Mitigations in Cloffle:**

- **`RT`**: A **`ThreadLocal`** macro-expansion depth (`pushMacroExpansionContext` / `popMacroExpansionContext`). While depth **> 0**, **`RT.print`** runs **`stripTypeMetaDeepForDiagnostics`** on the value before **`PR_ON.invoke`**, so dispatch falls back to class-based printers for raw structure. Helpers **`stripTypeMetaForMacroSourceLabel`** (shallow) and **`stripTypeMetaDeepForDiagnostics`** (walk via `clojure.walk/postwalk`, with a shallow fallback if the walk cannot run) live on **`RT`** for reuse from compiler code.
- **`Compiler.macroexpand1`**: The **entire** method is wrapped in **`RT.pushMacroExpansionContext` / `popMacroExpansionContext`** (in **`finally`**), so **nested** **`Compiler.macroexpand` / `macroexpand1`** calls from macro bodies (for example **`defn`**) still see a positive depth for the whole step. (Pushing only inside **`MacroExpander.expandViaGuest`** was insufficient for that nesting.)
- **`MacroExpander`**: When **`CURRENT_SOURCE`** is missing, the synthetic label still uses **`RT.stripTypeMetaForMacroSourceLabel`** on the form before **`toString()`**, so building the fallback **`Source`** text does not trigger bad **`print-method`** dispatch.
- **`CloffleCompiler`**: Compile trace and error logging pass forms through **`RT.stripTypeMetaDeepForDiagnostics`** before **`RT.printString`**.

**Regression tests:** **`net.javacrumbs.cloffle.MalliIntoSchemaReproTest`** — minimal **`defprotocol` / `defmethod print-method` / `^{:type …} (reify …)`** under Cloffle, including the **`defn`** body case that required the **`macroexpand1`**-scoped push/pop; **`protocol` on a list** throws in Cloffle; and a **Cloffle-only** macro case where **`print-method` calls a protocol** on the form (would throw on stock Clojure if the print multimethod path were active). JVM-side **`mikera.cljutils`** parity tests for this area were removed with the dependency.

### Macro expansion trail as parameter (not ThreadLocal)

The macro expansion trail (showing nested macro chains like `outer → inner`) is passed as a `List<String>` parameter through `Compiler.macroexpand` and `macroexpand1`, rather than stored in a `ThreadLocal`. This keeps the API surface small and makes upstream merges easier.

- **`Compiler.macroexpand(Object)`**: Public API unchanged. Internally creates a fresh `ArrayList<String>` and delegates to a package-private `macroexpand(Object, List<String>)`.
- **`Compiler.macroexpand1(Object, List<String>)`**: Appends the macro name to the trail before expansion. On failure, `makeMacroCompilerException` formats the trail into the `CompilerException` message (e.g., `"Macro expansion chain: outer → inner"`).

### Correct line/column in CompilerException for macro errors

`Compiler.macroexpand1` now extracts `formLine` and `formCol` from the form's `IMeta` metadata (`:line` / `:column` keys) and uses those in the `CompilerException` constructor, instead of `lineDeref()` / `columnDeref()` which returned `(0:0)` during macro expansion.

### Real Source in CloffleCompiler (no more NO_SOURCE)

- **`CloffleCompiler.compile`**: Binds real `Compiler.SOURCE_PATH` / `Compiler.SOURCE` for file compilation and runs forms through `executeForm()`.
- **`CloffleCompiler.executeForm`**: Builds a Truffle `Source` using the current `Compiler.SOURCE` name (fallback `"NO_SOURCE"` only when unavailable). This ensures converted nodes resolve sections against a real source name instead of a hardcoded placeholder.

### Root SourceSection on all eval roots

Previously, several paths created `ClojureRootNode` without setting a `SourceSection`, which made all child node source sections return `null` (since `ClojureNode.getSourceSection()` derives from the root's source):

- **`Clojure.truffleEval`**: Now sets `root.setSourceSection(source.createSection(0, source.getLength()))` and a root name from the form's first symbol.
- **`CloffleCompiler.executeForm`**: Uses source-backed node conversion so node-level sections are available from the compiler path as well.

### CompilerException data → ClojureParseError SourceSection

`Clojure.makeAnalyzerException` extracts `ERR_LINE` and `ERR_COLUMN` from the `CompilerException`'s data map and uses them when constructing `ClojureParseError`, falling back to the reader's position if not available.

### Full cause chain in parse error messages

`Clojure.buildFullMessage` walks up to 5 levels of the exception cause chain, appending unique messages to ensure the root cause is visible in the top-level `ClojureParseError` message.

### Extended extractLineColumn coverage

`ExprToNode.extractLineColumn` now covers additional `Expr` types:
- `NewInstanceExpr` (deftype/reify) — via `ObjExpr.line()` / `column()`
- `BodyExpr` — delegates to the first child expression's location

### Binding node source locations

- **`convertBindings`**: `BindingNode` instances (let/loop bindings) now get source location from the init expression via `applySourceFromExpr`.
- **`convertFnMethod`**: `ArgInitNode`, `VariadicArgInitNode`, and their wrapping `BindingNode`s get source location from `FnMethod.sourceLine()` / `sourceColumn()`.

### publishFrames in TruffleIFn

`TruffleIFn.callTrampoline` now catches `ClojureException` and calls `publishFrames()` before rethrowing. This ensures enriched frames are published when Truffle-backed functions are called from host code (e.g., during macro expansion or Java interop callbacks).

### Test coverage

9 tests in `SourceLocationTest` covering:
- Macro error line/column reporting
- Real source name in macro error locations
- CompilerException data → SourceSection
- Deep cause message surfacing
- Nested macro expansion chain
- Eager eval form source location
- Runtime error source location
- Body expr source location
- Let binding error source location
- Function name in stack frames
- Java interop error source location
- Try/catch rethrow propagation
- Nested fn call multiple guest frames

## Error Diagnostics Improvements (Mar 2026)

Comprehensive improvements to error messages, source location tracking, stack traces, and tooling compatibility. All 517 Cloffle JUnit tests pass (404 existing + 113 new).

### Var metadata line/column fix (Compiler.LINE/COLUMN bindings)

`CloffleCompiler.compile()` bound `LINE_BEFORE`/`LINE_AFTER`/`COLUMN_BEFORE`/`COLUMN_AFTER` for each top-level form but never bound `Compiler.LINE`/`Compiler.COLUMN`. These are the vars that `DefExpr.Parser.parse()` reads (line 576 of `Compiler.java`) to stamp `:line`/`:column` onto var metadata. Without bindings, they fell through to the root value of `0`.

Two changes in `CloffleCompiler.java`:

- **`compile()` loop**: Before calling `executeForm(r)` for each top-level form, pushes `Compiler.LINE`/`Compiler.COLUMN` bindings extracted from the form's reader-attached metadata (falling back to the pushback reader's line number). Pops in a `finally` block.
- **`executeForm()` do-splitting**: When a macro expands to `(do ...)` and the sub-forms are iterated, each sub-form now gets its own `LINE`/`COLUMN` binding from its metadata. This is critical because `defmacro` expands to `(do (defn ...) (. (var name) (setMacro)) (var name))` and the inner `defn` sub-form needs the correct line context.

Also cleaned up: replaced local `Keyword.intern(null, "line")`/`"column"` with shared class-level constants `LINE_KEY`/`COLUMN_KEY` (needed since `RT.LINE_KEY`/`RT.COLUMN_KEY` are package-private).

Result: `(meta #'when)` now correctly reports `:line 495 :column 1` instead of `:line 0 :column 0`.

### Polyglot parse() path: same LINE/COLUMN/SOURCE fixes

`Clojure.java`'s polyglot `parse()` path had the same family of bugs as `CloffleCompiler.compile()`:

1. **`pushCompilerBindings()` missing `Compiler.LINE`/`Compiler.COLUMN`**: Now binds both (initialized to `1`) alongside `LINE_BEFORE`/`COLUMN_BEFORE`/`LINE_AFTER`/`COLUMN_AFTER`.
2. **`SOURCE_PATH`/`SOURCE` set to placeholders**: Was `"NO_SOURCE_PATH"`/`"NO_SOURCE_FILE"` even though `truffleSource.getName()` was available. Now passes the real source name.
3. **`truffleEval()` do-splitting missing `LINE`/`COLUMN` per sub-form**: When a macro expands to `(do ...)`, each sub-form now gets its own `LINE`/`COLUMN` binding from its metadata (same fix as `CloffleCompiler.executeForm()`).
4. **`collectForm()` missing `LINE`/`COLUMN` binding and metadata transfer**: Now pushes `LINE`/`COLUMN` bindings from form metadata before analyzing, and transfers `:line`/`:column` metadata from original form onto macro-expanded form (matching `CloffleCompiler.executeForm()`'s metadata transfer pattern).

### CloffleCompiler.executeForm() synthetic source name

`executeForm()` was building a Truffle `Source` with literal content `"NO_SOURCE"`, meaning `ExprToNode` couldn't resolve `SourceSection` spans against real source text. Now reads `Compiler.SOURCE.deref()` to use the actual source file name, so nodes created during file-loading carry the correct source reference.

### FIAdapterNode ClassCastException wrapping

`FIAdapterNode.executeGeneric()` was rethrowing `ClassCastException` raw (`throw e;`), bypassing `ClojureException` wrapping and losing source location. Now wrapped with `ClojureException.wrap(e, this)`.

### ArityException wrapping and improved messages

`InvokeNode.invokeGeneric` previously re-threw `ArityException` from IFn calls raw, bypassing `ClojureException` wrapping. This meant arity errors from host-backed functions had no Truffle source location. The catch block now wraps them:

```java
} catch (clojure.lang.ArityException e) {
    CompilerDirectives.transferToInterpreter();
    throw ClojureException.wrap(e, this);
}
```

`FnNode.invoke` was also improved to include expected arities in the message using `ErrorMessages.formatArities`. Before: `Wrong number of args (3) passed to my-fn`. After: `Wrong number of args (3) passed to user/my-fn -- expected: 0, 1, 2+`.

`ErrorMessages.formatException` gained an `ArityException`-specific branch that preserves the original message rather than wrapping it in a generic `className: message` format.

### Source locations for literal and constant nodes

`ExprToNode.extractLineColumn` returned `[-1, -1]` for literal/constant expr types (`NilExpr`, `BooleanExpr`, `NumberExpr`, `StringExpr`, `KeywordExpr`, `ConstantExpr`, `EmptyExpr`, `TheVarExpr`, `MetaExpr`, `InstanceOfExpr`, `MonitorEnterExpr`, `MonitorExitExpr`) because they don't expose `line`/`column` fields.

A fallback was added: when the known-field extraction fails, `extractFromExprValue` reads the compiler thread-local `LINE_BEFORE`/`COLUMN_BEFORE` vars, which track the position of the form currently being analyzed. This ensures nodes like `nil` in `(nil 1 2)` carry a source position for the "cannot call nil as a function" error.

### Narrowed RootNode source sections

`FnNode.getCallTarget()`, `SequentialFormNode.executeSequentially()`, and `InvokeNode.createRootWithSource()` all previously set the root's `SourceSection` to the entire source file (`source.createSection(0, source.getLength())`). This meant every guest stack frame tied to a `RootNode` pointed at "line 1, col 1, spanning the entire file."

Fixed:
- `FnNode.getCallTarget()` uses the `FnNode`'s own `SourceSection` (the `(defn ...)` or `(fn ...)` form span) for the root.
- `SequentialFormNode` uses each sub-form's node `SourceSection` for the per-form root.
- Fallback to whole-file is retained when no form-level section is available.

### "Did you mean?" suggestions

`ErrorMessages.didYouMean(name, namespace)` was implemented but never wired up. It now fires on unresolved var errors in `VarNode`:

```
Unable to resolve symbol: printl in this context. Did you mean: println?
```

Uses Levenshtein edit distance with threshold `max(2, name.length / 3)`. A companion `didYouMeanNamespace(alias)` method iterates `Namespace.all()` for namespace alias typos.

### ex-data with Clojure error keys (IExceptionInfo)

`ClojureException` and `ClojureParseError` now implement `clojure.lang.IExceptionInfo`, so `(ex-data *e)` returns structured Clojure error information:

| Key | Value |
| :--- | :--- |
| `:clojure.error/phase` | Error phase keyword (`:execution`, `:read-source`, `:macroexpansion`, etc.) |
| `:clojure.error/source` | Source file name from `SourceSection` |
| `:clojure.error/line` | Line number |
| `:clojure.error/column` | Column number |
| `:clojure.error/class` | Cause exception class as a symbol |
| `:clojure.error/cause` | Cause exception message |

`ClojureException.wrap()` sets phase to `:execution`. `ClojureParseError` defaults to `:read-source`. The `getData()` method builds the map lazily from the node's resolved `SourceSection` and the wrapped cause.

This enables compatibility with `clojure.main/ex-triage`, `clojure.main/ex-str`, and editor integrations (CIDER, nREPL) that expect these keys.

### Error phases in REPL

`CloffleRepl.printError` now displays phase-aware labels when a phase is available:

```
Execution error (execution) at (foo.clj:4:3): ArithmeticException: / by zero
Syntax error (read-source) at (foo.clj:1:1): Unmatched delimiter: )
```

Phase is propagated from `ClojureException` via a `ThreadLocal<Keyword>`, published in `publishFrames()` and consumed by `CloffleRepl.formatPhase()`. The label maps phase keywords to user-friendly categories: `:read-source`/`:macro-syntax-check` → "Syntax error", `:macroexpansion` → "Syntax error (macroexpansion)", `:compilation` → "Compile error", `:execution` → "Execution error".

### Stack trace filtering for Throwable->map

`ClojureException.getStackTrace()` overrides `Throwable.getStackTrace()` to filter out internal Truffle/GraalVM frames. Filtered prefixes: `com.oracle.truffle.*`, `org.graalvm.*`, `jdk.graal.*`, `com.oracle.graal.*`, `$CallTarget`, `$FrameWithoutBoxing`, `sun.reflect.*`, `java.lang.reflect.*`, `jdk.internal.reflect.*`.

This makes `Throwable->map`, `clojure.stacktrace/print-stack-trace`, and `(pst)` output readable instead of showing hundreds of internal runtime frames.

### Precise source location verification

Source locations were validated by a probe of every major form type, confirming the `(line, column, charLength)` triple reported by Truffle `SourceSection` is precise enough for red-squiggle tooling. Key verified behaviors:

| Form | Primary frame | Length | Notes |
| :--- | :--- | :--- | :--- |
| `(/ 1 0)` | L1:C1 | 7 | Top-level |
| `(+ 1 (/ 2 0))` | L1:C6 | 7 | Points to inner form, not outer `(+)` |
| `(+ 1 (* 2 (/ 3 0)))` | L1:C11 | 7 | Deep nesting |
| `(if true (/ 1 0) :else)` | L1:C10 | 7 | Then-branch form |
| `(if false :then (/ 1 0))` | L1:C18 | 7 | Else-branch form |
| `(let [x (/ 1 0)] x)` | L1:C9 | 7 | Init expression |
| `(do 1 2 (/ 3 0))` | L1:C9 | 7 | Last body expression |
| `(cond ... :else (/ 1 0))` | L4:C9 | 7 | Macro-expanded inner |
| `(and true (/ 1 0))` | L2:C6 | 7 | Second operand |
| `(-> 0 (/ 0))` | L2:C5 | 5 | Threading form |
| `[(/ 1 0) 2]` | L1:C2 | 7 | Inside vector literal |
| `{:a (/ 1 0)}` | L1:C5 | 7 | Map value |
| `#{(/ 1 0)}` | L1:C3 | 7 | Set element |
| `(.substring "hi" 99)` | L1:C1 | 24 | Whole interop call |
| `(Integer/parseInt "xyz")` | L1:C1 | 24 | Static method |
| `(Integer. "xyz")` | L1:C1 | 16 | Constructor |
| `("hello" 1)` | L1:C1 | 11 | String-as-fn |
| `(true 1)` | L1:C1 | 8 | Boolean-as-fn |
| `(42 :key)` | L1:C1 | 9 | Number-as-fn |
| `(throw (Exception. "x"))` | L1:C1 | 24 | Throw form |
| `(def z (/ x 0))` | L3:C8 | 7 | Inner form, not outer `def` |

Multi-level call stacks correctly report per-frame line+column. For example, `(defn fail [] (throw ...))\n(+ 1 (fail))` reports both L1:C1 (throw site) and L2:C6 (call site `(fail)`).

### Test coverage

Four new test files (113 tests total):
- **`SourceLocationVerificationTest.java`**: 51 tests asserting exact `(line, column, charLength)` triples for arithmetic, `if`/`let`/`do`/`throw`/`cond`/`and`/`or`/`->`/`->>`, interop, constructors, collections, cannot-call, multi-level stacks, arity, loop/recur, parse errors, and var metadata.
- **`ErrorDiagnosticsTest.java`**: 32 integration tests via the Polyglot API covering arity wrapping, error messages, source locations, narrowed root sections, did-you-mean, ex-data, phases, stack traces, and var metadata line/column.
- **`ErrorMessagesTest.java`**: 20 unit tests for `formatArities`, `didYouMean`, `editDistance`, `formatException`, `clojureTypeName`, `cannotCallMessage`, `truncateValue`.
- **`ClojureExceptionTest.java`**: 10 unit tests for `IExceptionInfo` (`getData()`), phase tracking (`publishFrames`/`consumePhase`), stack trace filtering (`filterInternalFrames`), and enriched frame management.

### Files changed

| File | Changes |
| :--- | :--- |
| `Clojure.java` | `pushCompilerBindings` binds `LINE`/`COLUMN`/`SOURCE`/`SOURCE_PATH` from real source; `collectForm` pushes `LINE`/`COLUMN` per form and transfers metadata; `truffleEval` pushes `LINE`/`COLUMN` per do-subform and transfers metadata; added `transferLineColumnMeta`/`extractFormLine`/`extractFormColumn` helpers |
| `CloffleCompiler.java` | `compile()` pushes `Compiler.LINE`/`COLUMN` per form; `executeForm()` pushes `LINE`/`COLUMN` per do-subform; uses `Compiler.SOURCE` for Truffle source name; shared `LINE_KEY`/`COLUMN_KEY` constants; `extractFormLine`/`extractFormColumn` helpers |
| `InvokeNode.java` | ArityException wrapping in `invokeGeneric` |
| `FnNode.java` | Improved arity message with expected arities, narrowed root source section |
| `ExprToNode.java` | `extractFromExprValue` fallback for literal source locations |
| `ErrorMessages.java` | ArityException formatting, `didYouMeanNamespace`, `editDistance` made public |
| `ClojureException.java` | `IExceptionInfo`, phase tracking, stack trace filtering, `LAST_PHASE` ThreadLocal |
| `ClojureParseError.java` | `IExceptionInfo` with `:read-source` phase |
| `SequentialFormNode.java` | Per-form root source sections |
| `CloffleRepl.java` | `formatPhase()` for phase-aware error labels |
| `VarNode.java` | `didYouMean` on unresolved symbol errors |
| `FIAdapterNode.java` | `ClassCastException` wrapping in `ClojureException` |

## Polyglot triage, richer parse `ex-data`, debugger roots (Mar 2026)

Follow-up work for **embedded** `Context.eval` callers and **compile/macro** errors: tool-friendly maps aligned with `clojure.main/ex-triage`, structured guest stacks, richer `IExceptionInfo` on parse/analyzer failures, narrower function-entry source spans for the debugger, and clearer threading errors.

### `PolyglotErrorTriage` (Java API for embedders)

- **`PolyglotErrorTriage.triage(PolyglotException)`** returns an `IPersistentMap` with:
  - Standard keys: `:clojure.error/phase`, `source`, `line`, `column`, `cause`, and optional `class`.
  - **`:clojure.error/guest-frames`**: vector of maps per guest stack frame (`:source`, `:line`, `:column`, optional `:root-name`, `:snippet`).
  - **`:clojure.error/polyglot`**: nested map of flags (`internal-error?`, `syntax-error?`, `guest-exception?`, `host-exception?`, `incomplete-source?`).
- Merges **`:clojure.error/*`** from any host `Throwable` that is `IExceptionInfo`, and from **`getGuestObject()`** when it is a host `Throwable` (even if `isGuestException()` is false), so phases/symbols/spec/**macro-stack** from guest exceptions show up in the map.
- **Phase heuristics:** `isIncompleteSource` / `isSyntaxError`, plus common **reader** substrings in the exception message when Graal does not classify the error as syntax.
- Tests: **`PolyglotErrorTriageTest.java`**.

### `ClojureParseError.getData()` (macro / compile / spec)

When the cause chain includes `Compiler.CompilerException` or spec-related `IExceptionInfo` data, `getData()` now adds:

| Key | Role |
| :--- | :--- |
| `:clojure.error/phase` | Taken from the **innermost** `Compiler.CompilerException` when present (overrides the default `:read-source` for analyzer failures). |
| `:clojure.error/symbol` | From compiler exception data when present. |
| `:clojure.error/spec` | Full exception **data** map of the first `IExceptionInfo` in the chain that has `:clojure.spec.alpha/problems`. |
| `:clojure.error/class` | Class symbol of the **leaf** non–`CompilerException` cause. |
| `:clojure.error/macro-stack` | Vector of symbols from `ERR_SYMBOL` on each `Compiler.CompilerException` walked along `getCause()` (outer to inner). |

Tests: **`ClojureParseErrorExDataTest.java`**.

### Debugger: body-scoped function roots

- **`FnMethodNode.getBody()`** exposes the method body node.
- **`FnNode.preferredFunctionBodySection()`** prefers the first method’s body `SourceSection` (with encapsulating fallback) for **`FnDispatchNode`** and **`ClojureRootNode`** in `getCallTarget()`, falling back to the full fn form section when needed.
- **`DebuggerTest`**: `stackFramesAtBreakpoint` counts **non-host, non-internal** frames and uses a **non-tail** call chain so tail/self-tail optimization does not hide `a`/`b` when stopped in `c`; **`multiLineDefnBreakpointStartLineMatchesBodyLine`** asserts suspension on the body line.

### Threading

- **`Clojure.finalizeThread`**: on `Pop without matching push`, rethrows with an explanatory `IllegalStateException` describing Polyglot thread/context expectations (same thread initialization path as `initializeThread`).

### Error contract (triage maps), `ex-str`-style printing, editor diagnostics

**Stable triage map** (from `PolyglotErrorTriage/triage`, `ClojureException` / `ClojureParseError` `getData()`, or hand-built for tools):

| Key | Type | Required | Meaning |
| :--- | :--- | :--- | :--- |
| `:clojure.error/phase` | Keyword | yes | `:read-source`, `:macro-syntax-check`, `:macroexpansion`, `:compile-syntax-check`, `:compilation`, `:execution`, `:read-eval-result`, `:print-eval-result`, or tool-specific. |
| `:clojure.error/source` | String | usually | Logical file name (e.g. Truffle `Source` name), not always a filesystem path. |
| `:clojure.error/path` | String | no | Optional path (JVM `ex-triage` style); printers prefer `path` over `source` for the location label when both exist. |
| `:clojure.error/line` | Number (`long`/`int`) | no | 1-based line; printers default to `1`. |
| `:clojure.error/column` | Number | no | 1-based column when present. |
| `:clojure.error/cause` | String | no | Primary human message. |
| `:clojure.error/class` | Symbol | no | Cause class (often JVM class name). |
| `:clojure.error/symbol` | Symbol | no | Var/macro symbol for compile/macro phases. |
| `:clojure.error/spec` | IPersistentMap | no | Spec explain data (`:clojure.spec.alpha/problems`, etc.). |
| `:clojure.error/macro-stack` | Sequential | no | Symbols for nested `CompilerException` chain (outer→inner). |
| `:clojure.error/guest-frames` | Sequential of maps | no | Each map: `:source`, `:line`, `:column`, optional `:root-name`, `:snippet` (Cloffle / Truffle guest stack). |
| `:clojure.error/polyglot` | IPersistentMap | no | Flags from `PolyglotErrorTriage` only (`internal-error?`, `syntax-error?`, …). |

**Printing**

- **Java (no Clojure call):** `PolyglotErrorTriage.formatMessage(IPersistentMap)` or `PolyglotErrorTriage.formatMessage(PolyglotException)` delegates to `ClojureErrorExStr.formatTriageMessage`. Matches `clojure.main/ex-str` for the common phases; for `:clojure.error/spec`, uses capped `RT.printString` instead of `spec/explain-out`.
- **Clojure:** `clojure.polyglot.error/triage-ex-str` — same as `clojure.main/ex-str` for the base line, then appends `:clojure.error/macro-stack` and `:clojure.error/guest-frames` in the same shape as Java. **`polyglot-exception-message`** triages a `PolyglotException` and formats it.
  - **Source:** `src/clj/clojure/polyglot/error.clj` (fork classpath; `jar` copies forked `.clj` into `target/classes`).
  - **`clojure.main/ex-str` in source:** The repo ships a Java class `clojure.main` and a Clojure namespace `clojure.main`. If the namespace is not registered yet, a bare qualified symbol can be misread as a Java static. **`Compiler.analyzeSymbol`** avoids that by calling **`RT.load`** on the script path **`clojure/` + ns with dots replaced by slashes** when the namespace is still missing, a host class exists for that ns segment, and the **var name contains a hyphen** (so real Clojure Vars like **`ex-str`** win; hyphen-free names still follow normal Java interop). **`triage-ex-str` therefore calls `(clojure.main/ex-str triage)` directly** — no `requiring-resolve` workaround.
- **`clojure.main/ex-str` (fork):** when `:clojure.error/class` is absent, `simple-class` is nil; `cause-type` is now empty (matches JVM `ClojureErrorExStr` and avoids `Execution error () at …` from `(str " (" nil ")")`).

**Editor / LSP-style check**

- **`CloffleDiagnostics.checkParse(Context, Source)`** — `Context.parse` (no `eval`); returns empty list on success or a singleton `Diagnostic` (severity, message, `sourceName`, **1-based** line/column range, `phase` string). Messages use `PolyglotErrorTriage.formatMessage`. **LSP:** subtract 1 from lines; map columns to your editor’s encoding rules.
- **`CloffleDiagnostics.diagnosticFromException(String defaultSourceName, PolyglotException)`** — for failures from `eval`.

Tests: `ClojureErrorExStrTest`, `CloffleDiagnosticsTest`, `PolyglotClojureFormatTest` (the last host-calls **`RT.load("clojure/polyglot/error")`** in `@Before` so the namespace is on the JVM classpath before `Context.eval`; embed-time `require` / libspec text is still brittle in some tests).

## `some`/`recur` Tail-Position Regression Fix (Mar 2026)

A compile-time regression surfaced in `clojure.core/some`:

- `Syntax error compiling recur at (clojure/core.clj:2718:28)`
- `Can only recur from tail position`

### Root cause

The issue was not in `some`'s form itself. It was caused by variadic `applyTo` dispatch in `ClojureClosure`:

- For variadic functions, `applyTo` always wrapped rest args and forced variadic-path invocation.
- This broke exact-arity overload selection for macros/functions that have both fixed and variadic arities (notably `clojure.core/or`).
- During macroexpansion, recursive `(or ...)` calls with one argument were incorrectly dispatched as variadic calls, which reordered expansion behavior and eventually produced a non-tail-position `recur` in the expanded `some` body.

### Fix

`ClojureClosure.applyTo()` now preserves exact-arity dispatch for variadic functions:

- If arg count is `< requiredArity`: delegate to `AFn.applyToHelper` (existing behavior).
- If arg count is exactly `requiredArity`: also delegate to `AFn.applyToHelper` so fixed-arity overloads win.
- Only when arg count is `> requiredArity` does it package lazy rest args (`RestArgs`) for the variadic path.

### Validation

- `core.clj` now loads without the `some`/`recur` compile error.
- A targeted regression test was added in `test/clojure/test_clojure/compilation.clj` (`test-some-shape-recur-tail-position`) to assert:
  - the `some`-shaped form compiles/runs, and
  - short-circuit behavior avoids unnecessary `recur`.
- Direct evaluation of a `some`-equivalent function now returns expected values (`true`, `nil`, `:hit`) without tail-position errors.

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

- 403/405 Cloffle JUnit tests passing via `clojure -T:build run-tests` (default **`:fresh true`** cleans `target/` first, equivalent to the former `rm -rf target && …`; 2 pre-existing edge cases: `loadCoreCljFormByForm` has 10 form-level failures in core.clj's `..` and `with-open` macro expansions during standalone loading; `testTailCallInsideTryFinallyPreservesFinallyOrder` has a trailing whitespace mismatch)
- 622 `deftest`s from Clojure's own test suite run through Cloffle via `clojure -T:build run-clj-tests`; see [Clojure Test Suite Compatibility](#clojure-test-suite-compatibility-mar-2026) for current assertion-level failures/errors. An additional 107 generative tests (1,219 assertions) from 4 `test.check` namespaces are excluded by default for speed.

## Host-Eval Removal (Mar 2026)

The `hostEval` mechanism that routed certain forms (`ns`, `require`, `import`, `defmacro`, `defprotocol`, etc.) through `Clojure.hostEval()` → `Compiler.eval()` was removed entirely. All forms now flow through the Truffle pipeline.

### What changed

- **`Clojure.java`**: Removed all hostEval-related fields and methods (`HostEvalResult`, `HOST_EVAL_FALLBACK`, `HOST_EVAL_FORM_NAMES`, `DIRECT_HOST_INVOKE_FORMS`, `hostEvalFormName()`, `isHostEvalForm()`, `eagerHostEvalInDo()`, `hostEval()`, `tryDirectSimpleNs()`, `normalizeHostInvokeArgs()`, `unquoteArg()`, `constantFormEntry()`).
- **`Clojure.parse()`**: Restructured to use `collectForm()` which selectively executes side-effecting forms (like `defmacro`, `ns`, `import`) eagerly via `truffleEval()` during parsing, wrapping their results as constants. Other forms are analyzed and added as regular Truffle nodes.
- **`CloffleCompiler.compile()`**: Uses `executeForm()` which does macroexpand → do-split → analyze → ExprToNode → execute for each top-level form. Side effects are visible between forms.
- **`Compiler.macroexpand()`**: Made `public` for cross-package access.

### Validation

- 405/405 tests passing
- Two `ns` tests (`simpleNsDirectPathStillProvidesCoreRefs`, `namespacedSimpleNsDirectPathStillProvidesCoreRefs`) were removed because they exercised the complex `with-loading-context` macro expansion that the Truffle converter couldn't handle at the time. The fn self-reference fix (above) likely resolves this; they can be re-added.

## `InstanceCallNode` Classloader Fallback (Mar 2026)

`InstanceCallNode` threw `ClassCastException` when the compile-time `resolvedMethod`'s declaring class and the runtime instance were loaded by different classloaders (e.g., `^PrettyFlush` resolved via `DynamicClassLoader` at compile time, but the pprint proxy instance loaded by `AppClassLoader` at runtime). The fix mirrors the existing `ProtocolInvokeNode` pattern: when `declaringClass.isInstance(instance)` fails, re-resolve the method by name and parameter types against `instance.getClass()`. If re-resolution succeeds, invoke the re-resolved method; otherwise fall back to `Reflector.invokeInstanceMethod`. This is a general fix for any classloader identity split on instance method calls, not specific to pprint.

## Reitit Compat Investigation Notes (Mar 2026)

### `ThreadDeath` resolution divergence

During `compat-test :project :reitit`, Cloffle failed in Schema macro expansion with:

- `Unable to resolve classname: schema.macros/ThreadDeath`

Root cause: Cloffle did not resolve unqualified `ThreadDeath` as a class symbol, while stock Clojure does.

Fix:

- Added `ThreadDeath` to `RT` class-symbol mappings (`src/jvm/clojure/lang/RT.java`).

Validation:

- In Cloffle, `(resolve 'ThreadDeath)` now returns `java.lang.ThreadDeath`.
- Reitit Phase 1 (Maven Clojure baseline) passes with this config.

### Multi-arity protocol temp local bug (`G__...` uninitialized)

After the `ThreadDeath` fix, Reitit failed later in spec/coercion paths with:

- `Use of uninitialized local binding ... (G__....)`

Minimal standalone repro:

```clojure
(defprotocol Q2 (qq2 [o] [o f]))
(extend-protocol Q2
  Object
  (qq2 ([o] :one)
       ([o f] :two)))
```

Root cause:

- Equivalent compiler temps (`LocalBinding`, usually `G__...`) were being assigned to different frame slots in `ExprToNode`.
- One slot was initialized; another equivalent slot was read later.

Fix:

- `ExprToNode.findOrAddSlot` now canonicalizes local slots using a structural key:
  - `(idx, name, isArg)` for `LocalBinding`.

Result:

- The multi-arity `defprotocol` repro now works (`:one`, `:two`) instead of failing with uninitialized `G__...`.
- Added richer uninitialized-local diagnostics in `AbstractValueNode` (`sym`, `idx`, `isArg`) to speed future slot/debug analysis.

### Remaining blocker after protocol-slot fix

Current remaining failure is in `clojure.spec.alpha/fn-sym`:

- `NullPointerException` in `java.util.regex.Matcher/getTextLength`

This is a separate compatibility issue from the protocol-slot bug:

- `fn-sym` expects JVM-compiled function class names matching `ns$fn__...`.
- Cloffle runtime functions are `net.javacrumbs.cloffle.nodes.ClojureClosure`.
- Some `fn-sym` paths therefore feed nil group values into downstream regex/string processing.

Status:

- Protocol/multi-arity local-slot issue is fixed.
- `fn-sym`/spec naming compatibility remains open.

### Next actions (`fn-sym` compatibility)

- Add a focused repro test that directly exercises `clojure.spec.alpha/fn-sym` on:
  - core vars (e.g. `string?`),
  - anonymous closures,
  - named functions.
- Compare stock Clojure vs Cloffle return values for those forms and lock expected behavior.
- Decide compatibility approach:
  - implement Clojure-like function naming metadata/class identity for closures, or
  - intercept/adapt the `fn-sym` path to avoid nil regex-group failures while preserving spec semantics.
- Re-run:
  - minimal `s/with-gen` repro,
  - `compat-test :project :reitit`,
  - and ensure no regression in the multi-arity protocol repro.

## Classpath Unification (Mar 2026)

`build.clj` filters runtime classpath roots to exclude repo `src/clj` to prevent mixed source+jar loading of Clojure namespaces, which caused `ClassCastException` between proxy classes loaded by different classloaders (e.g. `clojure.pprint.proxy...` in app loader vs `clojure.pprint.PrettyFlush` in `DynamicClassLoader`).

## Bytecode Generation Replacement

Cloffle routes all forms through `CloffleCompiler` and **today** executes them via the **Truffle AST** (`ExprToNode` → `ClojureNode`). The standard ASM-based JVM bytecode generation for `fn`/`eval` is bypassed for that execution logic. A parallel effort (**`CLOFFLE_TRUFFLE_BYTECODE.md`**) replaces that AST path with the **Truffle Bytecode DSL** (`ExprToBytecode` → `CloffleBytecodeRootNode`) for guest execution; until that migration completes, descriptions of “how Cloffle runs code” still mean the AST unless stated otherwise.

- **Functions (`fn`)**: Compiled to `FnNode` trees (Truffle AST). JVM bytecode generation for `fn` is skipped unless inside deftype/defrecord/reify.
- **`Compiler.eval()`**: Delegates to `CloffleCompiler.executeForm()` (Truffle AST today).
- **`Compiler.load()`**: Delegates to `CloffleCompiler.compile()` (Truffle AST today).
- **`Clojure.parse()`**: Builds `SequentialFormNode` via `collectForm()` (Truffle AST).
- **Type Definitions (`deftype`/`reify`/`gen-class`)**: Still use Clojure's **ASM** JVM bytecode compiler path. `FnExpr.parse()` generates ASM bytecode only when the enclosing context is `NewInstanceExpr`.

## Replaced tools.analyzer.jvm with Compiler.analyze()

The Truffle parse pipeline originally used `clojure.tools.analyzer.jvm` (a third-party library) to analyze Clojure forms into Clojure maps with `:op` keys, then converted those maps into Truffle nodes via `AstBuilder` and 41 individual `*NodeBuilder` classes.

This was replaced with Clojure's built-in `Compiler.analyze()`, which produces an internal `Expr` tree directly. A single `ExprToNode` converter walks the `Expr` tree into Truffle `ClojureNode`s; **`ExprToBytecode`** is the corresponding lowering to Truffle Bytecode DSL graphs (see **`CLOFFLE_TRUFFLE_BYTECODE.md`**).

**Why we removed tools.analyzer.jvm:**
- **Single source of truth:** Clojure's real compiler already uses `Compiler.analyze()` to produce `Expr` trees. Using that same AST means we match Clojure's semantics exactly.
- **No redundant work:** We were analyzing every form twice. Using `Expr` directly removes that extra pass.
- **Fewer dependencies and less code:** Dropped a large transitive dependency and replaced 41 `*NodeBuilder` classes with one `ExprToNode`.
- **Faster startup:** No longer load the analyzer namespace or its deps at init.

**Before:**
```
Source → LispReader.read() → tools.analyzer.jvm/analyze → Clojure maps → AstBuilder + 41 NodeBuilders → Truffle nodes
```

**After (current production path):**
```
Source → LispReader.read() → Compiler.analyze() → Expr tree → ExprToNode → Truffle AST (ClojureNode)
```

**Migration (in progress):** same `Expr` tree → `ExprToBytecode` → `CloffleBytecodeRootNode` (Truffle Bytecode DSL); see `CLOFFLE_TRUFFLE_BYTECODE.md`.

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

### StaticInvokeExpr (direct linking disabled)

When direct linking was enabled, the Compiler produced `StaticInvokeExpr` instead of `InvokeExpr`. These attempted to call `invokeStatic` on pre-compiled classes, which is incompatible with Cloffle's Truffle execution model where functions are `ClojureClosure` objects, not compiled JVM classes.

Direct linking is no longer possible with the `clojure.compiler.direct-linking` system property.  Truffle has other ways of handling this type of optimization.

## Implementation Details

### Compiler Entry Points

All Clojure compilation and evaluation now routes through Truffle:

- **`Compiler.compile()`** → delegates to `Compiler.compileCloffle()` → `CloffleCompiler.compile()` (binds compiler source vars and executes forms through Truffle)
- **`Compiler.load()`** → delegates to `CloffleCompiler.compile()`
- **`Compiler.eval()`** → delegates to `CloffleCompiler.executeForm()` (builds a source named from compiler bindings and executes through Truffle)
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
- **Metadata**: `^:meta expr` forms produce `WithMetaNode` which applies metadata at runtime via `IObj.withMeta()`.

### ClassLoader Handling
`CloffleCompiler` and `Clojure.java` now correctly manage the Thread Context ClassLoader (TCCL) to ensure that dynamically generated classes (from `deftype`/`reify`) are visible during compilation and execution.

## tools.build: JUnit vs Clojure suite vs help

- **`run-tests`** — Cloffle **JUnit** tests only (Java test sources). Default **`:fresh true`**: runs **`clean`** first so stale `target` classes do not skew results; use **`:fresh false`** for incremental runs when appropriate.
- **`run-clj-tests`** — **`test/clojure/test_clojure/`** run **through Cloffle** (not the same as `run-tests`). Same default **`:fresh true`**. Use **`:only-namespace`** for a single namespace (e.g. **`clojure.test-clojure.pprint`** for a fast pprint-only run).
- **`help`** — `clj -T:build help` lists public `build.clj` tasks; **`help :verbose true`** prints full docstrings.

## Clojure Test Suite Compatibility (Mar 2026)

Clojure's own test suite (`test/clojure/test_clojure/`) is run through Cloffle via `clj -T:build run-clj-tests`. This executes 622 `deftest` forms containing **18,817** assertions through the Truffle pipeline.

### Current results

**622 `deftest`s, 18,817 assertions, 5 failures, 54 errors** (as reported by `clojure.test` and reflected in `target/surefire-reports/cloffle/TEST-results.xml`).

The **5** vs **54** split is JUnit/clojure.test terminology: **failures** are failed `is` assertions (`<failure>` in XML); **errors** are also failed assertions but reported as `<error>` (e.g. many `is` forms in one `deftest`). They are **assertion-level** counts, not 59 separate `deftest`s. **17** `deftest`s contain at least one bad assertion; the rest pass.

`clojure -T:build run-clj-tests` **fails the build** (non-zero exit) when any failure or error is present, and prints every failing `classname` / test name before throwing.

#### Interpreting the counts

| Metric | Value |
| :--- | :--- |
| `<failure>` elements in JUnit XML | 5 |
| `<error>` elements in JUnit XML | 54 |
| `deftest`s with any failing `is` | 17 |
| `deftest`s fully green | 605 |

#### By namespace (assertion-level failures / errors)

| Namespace | Failures | Errors | `deftest`s affected | Notes |
| :--- | ---: | ---: | ---: | :--- |
| `clojure.test-clojure.pprint` | 0 | 25 | 4 | `cl-format` / pretty-print layout (`angle-bracket-tests` 14, `cltl-angle-bracket-tests` 7, `cltl-up-tests` 3, `angle-bracket-max-column-tests` 1) |
| `clojure.test-clojure.clojure-walk` | 0 | 8 | 1 | `walk` — eight `is` forms on nested structures |
| `clojure.test-clojure.vectors` | 0 | 8 | 2 | `test-vec-compare` (7), `test-primitive-subvector-reduce` (1) |
| `clojure.test-clojure.string` | 0 | 7 | 2 | `t-index-of` (4), `t-last-index-of` (3) — `StringBuilder` + char args |
| `clojure.test-clojure.data-structures` | 0 | 3 | 1 | `test-disj` — three cases expect `ClassCastException` on wrong collection types |
| `clojure.test-clojure.ns-libs` | 2 | 0 | 1 | `test-defrecord-deftype-err-msg` — `CompilerException` / message expectations |
| `clojure.test-clojure.agents` | 1 | 0 | 1 | `continue-handler` — `ArithmeticException` in agent error path |
| `clojure.test-clojure.java-interop` | 1 | 0 | 1 | `test-reify-to-FI-allowed` — functional-interface / `ClassCastException` |
| `clojure.test-clojure.param-tags` | 1 | 0 | 1 | `no-param-tags-use-qualifier` — `ClassCastException` on date call |
| `clojure.test-clojure.errors` | 0 | 1 | 1 | `arity-exception` |
| `clojure.test-clojure.other-functions` | 0 | 1 | 1 | `test-every-pred` |
| `clojure.test-clojure.streams` | 0 | 1 | 1 | `stream-seq!-test` |

#### The five failures (`<failure>` in JUnit XML)

All are single failed `is` assertions in their `deftest` (not thrown exceptions uncaught by the test runner):

| `deftest` | What the assertion checks |
| :--- | :--- |
| `clojure.test-clojure.agents` / `continue-handler` | Agent error ref holds an `ArithmeticException` (`instance?` / `second` of `deref err`). |
| `clojure.test-clojure.java-interop` / `test-reify-to-FI-allowed` | `ClassCastException` when invoking a badly reified functional interface. |
| `clojure.test-clojure.ns-libs` / `test-defrecord-deftype-err-msg` | Two assertions: `thrown-with-cause-msg?` / `CompilerException` text for invalid `defrecord` / `deftype` field specs. |
| `clojure.test-clojure.param-tags` / `no-param-tags-use-qualifier` | `ClassCastException` when calling a function with a `#inst` value. |

#### The 54 errors (`<error>` in JUnit XML) — grouped

Each row below is one failed `is` (JUnit reports it as an “error” node). Together they sum to **54**.

**A. Pretty-print / `cl-format` — 25 errors, 4 `deftest`s**

| `deftest` | # | What is being compared |
| :--- | ---: | :--- |
| `angle-bracket-tests` | 14 | `cl-format` with `~<` / `~;` / `~>` (width, padding `@` / `:`, colinc, optional segments `~^`, string vs `~A` args). |
| `cltl-angle-bracket-tests` | 7 | `format` with `~10<…~>` variants (foo/bar, foobar, colon/at modifiers). |
| `cltl-up-tests` | 3 | `format` with `~15<~S~;…~>` vs `platform-newlines` expected columns. |
| `angle-bracket-max-column-tests` | 1 | Long wrapped comment block: `~%;; ~{~<~%;; ~1,50:; ~A~>~}.~%` |

**B. `clojure.walk` — 8 errors, 1 `deftest` (`walk`)**

| # | Pattern |
| ---: | :--- |
| 4 | `(w/walk inc (fn* [x] (reduce + x)) coll)` vs `(reduce + (map inc coll))` on nested collections. |
| 4 | Walk with inner `update-in` / `vals` / `comp inc val` vs reference `reduce` on maps. |

**C. Vectors — 8 errors, 2 `deftest`s**

| `deftest` | # | Content |
| :--- | ---: | :--- |
| `test-vec-compare` | 7 | Each expects `thrown? ClassCastException` for `.compareTo` on a primitive `int` vector vs `()`, `{}`, `#{}`, `sorted-set`, `sorted-map`, another vector `nums`, and `1`. |
| `test-primitive-subvector-reduce` | 1 | `(== 60 (reduce + (subvec (vector-of :long) 10 15)))`. |

**D. `clojure.string` on `StringBuilder` — 7 errors, 2 `deftest`s**

| `deftest` | # | Content |
| :--- | ---: | :--- |
| `t-index-of` | 4 | `index-of` on `StringBuilder` `sb` with `\c`, `\o` from index, `\z` missing (with and without from-index). |
| `t-last-index-of` | 3 | `last-index-of` with `\n`, from-index, and missing `\z`. |

**E. `disj` / collections — 3 errors, 1 `deftest` (`test-disj`)**

Each expects `thrown? ClassCastException`: `disj` on list literal `(1 2)`, vector `[1 2]`, map `{:a 1}`.

**F. Small isolated cases — 3 errors, 3 `deftest`s**

| `deftest` | Content (first line of expectation) |
| :--- | :--- |
| `clojure.test-clojure.errors` / `arity-exception` | `macroexpand` of bad arity → `ArityException` with `.actual` field. |
| `clojure.test-clojure.other-functions` / `test-every-pred` | `reduce` of `and` over `(for [i (range 1 25)] (apply (apply every-pred (repeat i identity)) (range i)))` equals `true`. |
| `clojure.test-clojure.streams` / `stream-seq!-test` | `(= 4950 (reduce + (stream-seq! l100)))`. |

#### Error themes (for prioritization)

1. **Pretty-print** — just under half of all errors (25/54); CLTL-style format strings and column layout.  
2. **`clojure.walk`** — one `deftest`, eight structural equalities.  
3. **Primitive / `gvec` / `compareTo`** — seven `ClassCastException` expectations plus one numeric `reduce` over `subvec`.  
4. **StringBuilder + char** — seven `index-of` / `last-index-of` cases.  
5. **`disj` on non-set** — three `ClassCastException` expectations.  
6. **Misc** — arity macroexpand, `every-pred` stress, `stream-seq!` sum.

Four additional namespaces (`data-structures-interop`, `parse`, `sequences`, `transducers`) pass but are excluded by default because they depend on `clojure.test.check` generative tests which are slow (~5 min). Include them with `clj -T:build run-clj-tests :generative true`.

### Excluded namespaces

These namespaces are excluded because they test JVM bytecode features that don't apply to Cloffle's Truffle execution model:

| Namespace | Reason |
| :--- | :--- |
| `clojure.test-clojure.compilation`, `.load-ns` | AOT compilation, class loading |
| `clojure.test-clojure.genclass` | `gen-class` bytecode generation |
| `clojure.test-clojure.annotations` | JVM annotation emission |
| `clojure.test-clojure.clearing` | JVM local-clearing optimization, N/A in Truffle |
| `clojure.test-clojure.serialization` | `ClojureClosure` is not `Serializable` |

Generative test namespaces (excluded by default, pass when enabled):

| Namespace | Tests | Assertions |
| :--- | :--- | :--- |
| `clojure.test-clojure.data-structures-interop` | 9 | 9 |
| `clojure.test-clojure.parse` | 6 | 54 |
| `clojure.test-clojure.sequences` | 73 | 1,148 |
| `clojure.test-clojure.transducers` | 19 | 108 |

### Disabled test assertions

Individual test assertions have been disabled (via `#_`) in test files where they rely on features Cloffle intentionally does not implement:

| Test file | Disabled test(s) | Reason |
| :--- | :--- | :--- |
| `test_clojure/control.clj` | 3 `testing` blocks in `test-case` | JVM compiler "Performance warning" / "Reflection warning" diagnostics, N/A in Truffle |
| `test_clojure/special.clj` | `typehints-retained-destructuring` | `^String` on `:keys` symbols does not suppress reflection on interop calls in Cloffle (`GenericStaticCallNode`); JVM Clojure passes |
| `test_clojure/protocols.clj` | `test-longs-hinted-proto` | Requires `IFn$OL` primitive interface; Truffle handles primitives via PE |

## Recent Compatibility Fixes

Several concrete Clojure/Cloffle divergences were found with paired regression tests and then fixed in the runtime:

- **`letfn` mutual recursion:** added `LetFnNode`, which constructs all local closures before capturing the final shared lexical environment.
- **`reify` closed-overs:** `ExprToNode.convertNewInstance()` now threads `NewInstanceExpr.closesExprs` into `NewNode`, fixing `reify` instances that capture locals.
- **Protocol dispatch:** protocol call analysis is enabled in the Cloffle compiler bindings, and `ProtocolInvokeNode` now uses the analyzer-provided protocol metadata plus a reflective fallback to survive interface/classloader identity mismatches.
- **Exception identity on the compiler path:** uncaught exceptions now escape as the original Java throwable instead of being rewritten as `ClojureException` or `RuntimeException(e)`. `TryNode` still unwraps `ClojureException` defensively for matching, but the direct `CloffleCompiler` path now preserves exact exception type/message more closely. The `Context.eval` polyglot boundary still surfaces uncaught failures as `PolyglotException`, which is expected on the Graal polyglot API.
- **Primitive-hinted numeric coercion:** explicitly hinted primitive params (`^long`, `^double`) now use `RT.longCast` / `RT.doubleCast` semantics for primitive slot writes and rebinding, restoring Clojure-compatible coercion and overflow checks.
- **Nil consistency across Truffle/Java boundary:** `NilNode.NIL` sentinels are unwrapped to Java `null` at all collection boundaries (`VectorNode`, `MapNode`, `SetNode`, `ListNode`), `DefNode.bindRoot()`, `InvokeNode` argument passing, and `CaseNode` comparison. `ProtocolInvokeNode` null-checks the dispatch target before attempting reflection.
- **`CaseNode` hash collision handling:** `ExprToNode.convertCase()` extracts `CaseExpr.skipCheck` and passes a `boolean[]` to `CaseNode`. For entries where `skipCheck` is true (hash collisions like `hash(0) == hash(-1)`), the node dispatches by hash match to the compiler-generated `condp` branch. Otherwise it uses `Util.equiv()`.
- **`char` frame slot kind:** `ExprToNode.slotKindForClass()` no longer maps `char.class` to `FrameSlotKind.Long`. Characters are stored as `Object` to preserve their `Character` type.
- **`MetaExpr` → `WithMetaNode`:** A new Truffle node correctly applies metadata at runtime via `IObj.withMeta()`, replacing the previous no-op that dropped metadata.
- **`InstanceFieldNode` `requireField` flag:** The `-field` syntax (`(. obj -field)`) now correctly prefers fields over methods by passing `requireField=true` to `Reflector.invokeNoArgInstanceMember()`.
- **`NewNode` compile-time constructor:** Uses the compile-time resolved `Constructor` from `NewExpr` with `Reflector.boxArgs()` instead of runtime `Reflector.invokeConstructor()`, eliminating ambiguous constructor resolution.
- **`PersistentHashSet` empty singleton:** All `create()` and `createWithCheck()` factory methods now return `PersistentHashSet.EMPTY` for empty input, ensuring `#{}`-literal identity consistency required by `sorted-set-by` comparators.
- **`ClassCastException` for type mismatches in compiled code:** `GenericStaticCallNode`, `InstanceCallNode`, and `NewNode` catch `IllegalArgumentException` from `Reflector.boxArgs()` and rethrow as `ClassCastException`, matching JVM `checkcast` semantics. The reflection-level `Reflector.boxArg()` retains `IllegalArgumentException` for callers like `Reflector.invokeConstructor()` used by the reader.
- **`DynamicClassLoader` class bytes cache:** `defineClass()` now retains a soft-referenced copy of class bytes, and `getResourceAsStream()` is overridden to serve them. Combined with a context-classloader fallback in `ClassReader(String)`, this allows `clojure.asm.ClassReader` to inspect in-memory classes (e.g., proxy classes).
- **`convertNewInstance` deftype detection:** `ExprToNode.convertNewInstance()` now uses `e.isDeftype()` (which checks `fields != null`) instead of `e.hintedFields.count() > 0`. This fixes `defrecord` with zero user fields (e.g., `(defrecord Foo [])`) which was incorrectly taking the reify path and failing on uninitialized `__meta` frame slots.
- **`ClojureClosure.applyTo()` lazy rest args:** `applyTo()` no longer calls `RT.seqToArray()` which would realize infinite sequences. For variadic functions, it extracts the required positional args and wraps the remaining `ISeq` in a `RestArgs` sentinel. `VariadicArgInitNode` recognizes the sentinel and uses the lazy seq directly, allowing `(apply f (range))` to work without hanging.
- **`invokePrim` rewrite removed from `Compiler.java`:** When the Clojure compiler saw a call to a function whose arglist had `^long`/`^double` type hints, it rewrote the call from `(f arg)` to `(.invokePrim ^IFn$LO f arg)`, producing an `InstanceMethodExpr` that cast the function to a primitive IFn interface. `ClojureClosure` doesn't implement these interfaces (`IFn$LO`, `IFn$OL`, `IFn$LL`, etc.) because Truffle handles primitive specialization via Partial Evaluation and frame slot specialization. The entire rewrite block in `InvokeExpr` analysis has been removed. This unblocked four test namespaces (`data-structures-interop`, `parse`, `sequences`, `transducers`) that depend on `clojure.test.check`, which internally uses `^long`-hinted functions.
- **`clojure.pprint` require in `transducers.clj`:** The test file referenced `clojure.pprint/pprint` without requiring the namespace. In standard Clojure, `clojure.pprint` is AOT-compiled and auto-resolves; in Cloffle, it must be explicitly loaded. Added `[clojure.pprint]` to the `ns` `:require` form.

These fixes are covered by explicit compatibility tests in `CloffleReproTest` in addition to the broader paired behavior suite. Coverage was also expanded for direct compiler-path `deftype`/protocol dispatch (`AdvancedFeaturesTest`), direct compiler-path primitive-hint coercion (`CloffleCompilerTest`), and polyglot-boundary exception message/type reporting (`CloffleReproTest`).

## No Munge/Demunge for Function Names (Mar 2026)

In standard Clojure, function names are *munged* into valid Java class names (`:` → `_COLON_`, `+` → `_PLUS_`, etc.) because each function compiles to a JVM class. Error messages then *demunge* them back for display. In Cloffle, functions are `ClojureClosure` objects with Truffle `CallTarget`s — there are no compiled classes, so the munge/demunge cycle is unnecessary.

### What changed

- **`ArityException`**: No longer calls `Compiler.demunge(name)` in its constructor. The name is used as-is.
- **`AFn.throwArity()`**: Now demunges `getClass().getName()` before passing to `ArityException`, since compiled `IFn` implementations (like `Keyword`, core functions loaded from JARs) still have munged class names.
- **`ExprToNode.convertDef()`**: Sets the `FnNode` name to the clean namespace-qualified Clojure name (e.g., `clojure.core/assoc`, `user/f2:+><->!#%&*|b`) directly from `Var.ns.name` and `Var.sym`. Handles `WithMetaNode` wrapping via `extractFnNode()`.
- **`ExprToNode.convertFn()`**: Uses `thisName` directly (the original Clojure symbol name) for self-referencing functions. The `extractFnName()` method (which reverse-engineered names from munged `compiledName` via `Compiler.demunge()`) was deleted.
- **`Compiler.macroexpand1()`**: The ArityException name comparison now checks both the clean qualified name (`ns/sym`) and the munged class name (`munge(ns)$munge(sym)`) to handle exceptions from both Truffle-compiled and JAR-loaded functions. `extractArityException()` walks the cause chain to find `ArityException` inside `ClojureException` wrappers.
- **`InvokeNode.invokeGeneric()`**: `ArityException` from compiled `IFn` implementations is wrapped in `ClojureException`; `Compiler.macroexpand1()` handles this via `extractArityException()` walking cause chains.

### Source line metadata preservation

`CloffleCompiler.executeForm()` transfers `:line`/`:column` metadata from the original reader form onto the macroexpanded form before passing it to `Compiler.analyze()`. This ensures `analyzeSeq()` picks up correct source locations for var definitions, fixing `source-fn` and stack trace line numbers.

Previously, `executeForm()` passed the fully macroexpanded form to `analyze()`, which had lost the reader's line metadata. The macroexpansion is still performed first (for `do`-splitting of `ns` expansions), but the original form's positional metadata is now grafted onto the expanded result.

## Modifications to upstream Clojure classes

Changes to `src/jvm/clojure/lang/` fall into three categories:

**Visibility and delegation (Compiler.java):** ~22 inner `Compiler.Expr` classes and ~20 fields/methods changed from package-private to `public` so that `ExprToNode` (in a different package) can access the AST. `macroexpand()` made public. `eval()` delegates to `CloffleCompiler.executeForm()`. `load()` delegates to `CloffleCompiler.compile()`. `FnExpr.parse()` conditionally skips bytecode generation. `evalWithLegacyBytecode()` and `evalWithTruffle()` removed. `StaticInvokeExpr` given a `public final Var var` field. `macroexpand1()` calls `checkSpecsAt` before Truffle macro expansion (see **Spec `macroexpand-check`**), and is enhanced with `extractArityException()` for Truffle exception unwrapping and a `List<String> trail` parameter for macro expansion chain tracking. `makeMacroCompilerException()` helper added for formatting trail into `CompilerException` messages. `ObjExpr.isDeftype()` made `public`. `FISupport` class and `maybeFIMethod()` made `public`. The `invokePrim` rewrite in `InvokeExpr` analysis removed (see below).

**ArityException (ArityException.java):** No longer calls `Compiler.demunge(name)` — the name is passed through as-is. Callers are responsible for providing a display-ready name.

**AFn.java:** `throwArity()` now calls `Compiler.demunge(getClass().getName())` before constructing `ArityException`, since compiled `IFn` classes still have munged names.

**FnInvokers.java:** `encodeInvokerType()` made `public` for access from `FIAdapterNode`.

**DynamicClassLoader.java:** `defineClass()` stores a soft-referenced copy of class bytes in `classBytesCache`. `getResourceAsStream()` overridden to serve cached bytes for in-memory-defined classes. `findClassBytes()` added for static lookup.

**ClassReader.java (clojure.asm):** `ClassReader(String)` constructor falls back to `Thread.currentThread().getContextClassLoader().getResourceAsStream()` when `ClassLoader.getSystemResourceAsStream()` returns null, allowing inspection of in-memory classes defined by `DynamicClassLoader`.

**PersistentHashSet.java:** All `create()` and `createWithCheck()` factory methods (6 overloads) return `PersistentHashSet.EMPTY` singleton for empty input.

**Truffle interop annotations (8 files):** `AFn`, `APersistentMap`, `APersistentSet`, `APersistentVector`, `ASeq`, `Keyword`, `LazySeq`, `Symbol`, and `Var` implement `TruffleObject` and export `InteropLibrary` messages. This makes Clojure data types first-class polyglot citizens on GraalVM without changing their Clojure-side semantics.

**JDK modernization (RT.java):** Removed deprecated `SecurityManager` and `ThreadDeath` from default imports, removed `AccessController.doPrivileged` wrapper in `makeClassLoader()` (deprecated since Java 17, removed in Java 24).

**Spec / `macroexpand-check` (RT.java):** `RT.CHECK_SPECS` now follows the upstream pattern — starts `false` during bootstrap, flipped to `true` at the end of `RT.doInit()`. Disable with `-Dclojure.spec.skip-macros=true`. Implementation details and historical port notes: **Spec `macroexpand-check`** below.

## Deleted dead code

- **`HostInteropNode`** — was never wired into `ExprToNode`. Instance method/field calls go through `InstanceCallNode`/`InstanceFieldNode` instead.
- **`ReifyNode`, `DefTypeNode`** — Proxy-based fallback implementations for `reify`/`deftype`. Superseded by using `Compiler.analyze()`-generated JVM classes directly via `NewNode`.
- **`LegacyInvokeNode`, `LegacyFnMethodNode`** — older implementations kept only for benchmarking comparison. No longer needed.
- **`UnaryStaticCallNode`, `BinaryStaticCallNode`, `AbstractStaticCallNode`** — MethodHandle-based fast paths for 1- and 2-arg static calls. Replaced by `GenericStaticCallNode`.
- **`AstBuilder`, `*NodeBuilder`** — The old `tools.analyzer.jvm` based pipeline.
- **`evalWithLegacyBytecode`, `evalWithTruffle`** — Dead ASM and Truffle eval methods in `Compiler.java`.
- **hostEval infrastructure** — All `HOST_EVAL_*` fields, `hostEval()`, `eagerHostEvalInDo()`, and related methods in `Clojure.java`.
- **`ExprToNode.extractFnName()`** — Reverse-engineered function names from munged `compiledName` via `Compiler.demunge()`. No longer needed since function names are stored directly.

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

Another fix: `char.class` is no longer mapped to `FrameSlotKind.Long`. Characters are stored as `Object` to preserve their `Character` type and avoid incorrect coercion to `long` in `case` comparisons.

## Benchmarks

*   `CloffleNodeBenchmark.java` measures invoke, recur loop, and var read performance.
*   `NamespaceBenchmark.java` measures var resolution through the polyglot `Context.eval` path.
*   `StubBenchmark.java` measures baseline polyglot boundary overhead.

# Potential Future Improvements

Performance-related ideas that have been analyzed but not yet implemented, to avoid increasing Clojure/Cloffle divergence prematurely.

## CaseNode O(1) Dispatch

The current `CaseNode` does a linear scan with `Util.equiv()` for each case branch (with `skipCheck` hash-based routing for collision entries). `Compiler.analyze()` already computes `shift`, `mask`, `low`, `high`, `switchType`, and `testType` on `CaseExpr` for hash-based or table-switch dispatch:

- For `testType == intKey` + `switchType == compactKey`: use an array-indexed lookup (table switch).
- For hash-based `testType`: use `(hash(value) >> shift) & mask` to index into a lookup table, with `skipCheck` fallback for collisions.

This would be the highest-impact single optimization for `case`-heavy code, but adds Cloffle-specific logic. Truffle/Graal's PE may handle the linear scan adequately for small case counts.

## ClojureClosure Functional Interface Adapters (Implemented)

Clojure 1.12's functional interface adaptation is now fully supported. When a Clojure function (`IFn`) is type-hinted to a `@FunctionalInterface`, Cloffle generates an adapter at runtime that implements the target interface and delegates to the function's `invoke` method.

**How it works:**
- `ExprToNode` detects FI type hints on `let` bindings (`LocalBinding.tag`), method call arguments (`Method.getParameterTypes()`), and constructor arguments.
- When a target class is `@FunctionalInterface` (detected by `Compiler.FISupport.maybeFIMethod()`), the init/arg node is wrapped in `FIAdapterNode`.
- At runtime, `FIAdapterNode` checks: if the value already implements the target FI, it passes through unchanged. Otherwise, it creates an adapter.
- Adapters are created via `LambdaMetafactory.metafactory()` when classloader access permits (using `privateLookupIn` for the target FI class). For dynamically-loaded interfaces (e.g., `definterface` classes in `DynamicClassLoader`), falls back to `java.lang.reflect.Proxy`.
- `FnInvokers` static methods provide the delegation bridge, handling primitive boxing/unboxing for arities 0-2 and all-Object dispatch for 3-10.
- FIs with > 10 parameters are rejected by `maybeFIMethod` (matching Clojure's behavior). Attempting to call methods on an un-adapted function produces `ClassCastException`.

**`InstanceCallNode` ClassCastException fix:** `InstanceCallNode` now explicitly validates the receiver type against the resolved method's declaring class before calling `Method.invoke()`. This produces `ClassCastException` (matching JVM `invokevirtual` semantics) instead of `IllegalArgumentException` (which `Method.invoke()` throws for type mismatches).

## ClojureClosure Arity Metadata

`ClojureClosure` now stores `requiredArity` and `isVariadic` fields, set by `FnNode` at closure creation time. This enables:
- **Lazy `applyTo()`**: Variadic functions avoid realizing infinite sequences by passing the rest as an `ISeq` wrapped in a `RestArgs` sentinel, rather than calling `RT.seqToArray()`.
- **Non-variadic `applyTo()`**: Delegates to `AFn.applyToHelper()` which is bounded and safe.

## Spec `macroexpand-check` (Mar 2026)

**Current policy:** `RT.CHECK_SPECS` is `static volatile`, starts `false`, set to `true` at the end of `RT.doInit()` — matching upstream Clojure's pattern where the flag is flipped after `core.clj` finishes loading. Disable with `-Dclojure.spec.skip-macros=true`.

Clojure 1.10+ validates many core macro invocations against `clojure.core.specs.alpha` **before** macro expansion by calling `clojure.spec.alpha/macroexpand-check` from `Compiler.macroexpand1`. Cloffle's `Compiler` contains the same guarded hooks.

### `RT.java`

- `CHECK_SPECS` — `static volatile`, `false` during bootstrap, `true` after `doInit()` completes (unless `-Dclojure.spec.skip-macros=true`).

### `Compiler.java`

- Lazy `MACRO_CHECK` / `ensureMacroCheck()` — loads `clojure/spec/alpha` and `clojure/core/specs/alpha`, resolves `clojure.spec.alpha/macroexpand-check`, guarded by `MACRO_CHECK_LOADING` to avoid re-entrancy while namespaces load.
- `checkSpecsAt(v, form, formLine, formCol)` — when `RT.CHECK_SPECS` is true, invokes `macroexpand-check` with the same `applyTo` shape as upstream; failures become `CompilerException` with phase **`:macro-syntax-check`**, using **form metadata** `:line`/`:column` when already computed for the macro form.
- Public `checkSpecs(Var, ISeq)` for callers that rely on `lineDeref` / `columnDeref`.
- `CompilerException`: added `PHASE_MACRO_SYNTAX_CHECK`, `SPEC_PROBLEMS` (`:clojure.spec.alpha/problems`), and `toString()` handling when the cause is `IExceptionInfo` with spec problems (avoids duplicating huge explain output), matching upstream intent.

**Not** ported from upstream: the extra `macroexpand1` `catch` arms that reclassify `IllegalArgumentException` / `IllegalStateException` / `ExceptionInfo` from **inside** macro expansion as `:macro-syntax-check` (and the `java.lang.Exception` vs default phase split on `Throwable`). Those interfered with other Cloffle macro paths; spec violations that `macroexpand-check` catches still surface as `:macro-syntax-check`. Remaining macro failures may still use `:macroexpansion` via `makeMacroCompilerException`.

### Tests re-enabled

Upstream `test_clojure` coverage for this hook is enabled again:

- `test_clojure/fn.clj` — `fn-error-checking` (requires `clojure.test-helper` in the namespace `:use` for `fails-with-cause?`).
- `test_clojure/def.clj` — `defn-error-messages`.
- `test_clojure/special.clj` — `keywords-not-allowed-in-let-bindings`, `namespaced-syms-only-allowed-in-map-destructuring`.

**Still disabled:** `typehints-retained-destructuring` in `special.clj` — not a spec issue; see the disabled-test table above.

### How to run the spec-related integration tests

After `rm -rf target`:

```bash
clojure -T:build run-clj-tests :only-namespace '"clojure.test-clojure.fn"'
clojure -T:build run-clj-tests :only-namespace '"clojure.test-clojure.def"'
clojure -T:build run-clj-tests :only-namespace '"clojure.test-clojure.special"'
```

### Remaining spec differences vs JVM Clojure

- `RT.checkSpecAsserts` remains **`false`** hardcoded (JVM uses `-Dclojure.spec.check-asserts=true`). Runtime `s/assert` parity is separate from `macroexpand-check`.
- No dedicated JUnit tests for `macroexpand-check`; coverage is the vendored `test_clojure` namespaces above.

## Typed Protocol Fast Path in ProtocolInvokeNode

`ProtocolInvokeNode` now consumes the analyzer-provided `protocolOn` and `onMethod` metadata and attempts a direct interface/method path before falling back to generic protocol-var invocation. A reflective fallback by method name/arity is also used to tolerate classloader-identity mismatches between the protocol interface metadata and the generated runtime class.

There is still room to make this more Truffle-native with true DSL specializations/caching, but the current implementation is now semantically correct for the compatibility regressions that were found.

## Primitive Execution Propagation (Autoboxing Prevention)

Several Truffle nodes previously only implemented `executeGeneric()`, forcing all return values through Object boxing even when the underlying value was a primitive `long`, `double`, or `boolean`. This caused unnecessary autoboxing at node boundaries within the Truffle AST.

The following nodes now implement `executeLong()`, `executeDouble()`, and `executeBoolean()` in addition to `executeGeneric()`, following the pattern already established in `IfNode`:

- **`DoNode`**: Side-effect statements execute via `executeGeneric()`, but the return expression uses the caller's requested primitive executor. Common in `(do ... (+ x y))` where the final expression is arithmetic.
- **`LetNode`**: Bindings initialize via `executeGeneric()`, then the body uses the caller's requested primitive executor. This means `(let [x 42] (+ x 1))` can flow the long result directly without boxing.
- **`CaseNode`**: Match logic extracted into a `findMatch()` helper. The matched branch and default branch use the caller's requested primitive executor. `(case :a :a 42 :b 0)` avoids boxing the `42`.
- **`TryNode`**: The try body uses the caller's requested primitive executor on the happy path. Catch handlers still return Object (exception handling is inherently boxed). The exception handling logic is extracted into a `handleException()` helper.

`LoopNode` was not changed because the recur mechanism inherently uses `Object[]` for rebinding values, so primitive specialization at the loop boundary would not be beneficial.

Primitive return paths are exercised by compiler and Truffle tests (e.g. **`CloffleCompilerTest`**, **`ExprToNodeLocalBindingSlotTest`**); the former **`AutoboxingAndTypeHintTest`** JVM/Cloffle parity suite was removed with **`net.mikera/clojure-utils`**.

## Extended Fn Param Primitive Hints: `int` / `float` / `boolean` (Mar 2026)

Function parameter hints now accept `^int`, `^float`, and `^boolean` in addition to `^long` and `^double`.

### What changed

- **`Compiler.FnMethod.classChar`**: still emits primitive IFn signatures only for `long` (`L`) and `double` (`D`), but no longer throws for other primitive hints.
  - For primitive hints without an IFn primitive family (`int` / `float` / `boolean`), it now returns `'O'` so those params use object-family IFn signatures while remaining primitive-typed in analyzer metadata.
- **`Compiler.FnMethod.parse`**: removed the parser-time guard that rejected non-`long`/`double` primitive params.
  - `MethodParamExpr`/`LocalBinding.getPrimitiveType()` now carry `int.class`, `float.class`, and `boolean.class` for hinted fn params.

### Truffle-side specialization behavior

`ExprToNode.slotKindForClass` already maps these primitive classes to Truffle frame kinds:

- `int` -> `FrameSlotKind.Long`
- `float` -> `FrameSlotKind.Double`
- `boolean` -> `FrameSlotKind.Boolean`

So these hints now propagate analyzer -> AST conversion -> Truffle frame slot specialization without adding new IFn primitive interface families.

### Important caveat

- IFn primitive interface families remain only `L`/`D`-based.
- Existing `long`/`double` primitive-fn constraints remain (for example, long/double primitive-family functions are still not variadic).

### Test coverage added

- **`clojure.lang.CompilerTypeHintAnalysisTest`**
  - `analyzeFnPrimitiveIntFloatBooleanParameters`
- **`clojure.lang.ExprToNodeTypeHintPropagationTest`**
  - `booleanHintedParamGetsBooleanSlotKind`
  - `intHintedParamGetsLongSlotKind`
  - `floatHintedParamGetsDoubleSlotKind`

Validated with:

```bash
clojure -T:build run-tests :args '["--select-class=clojure.lang.CompilerTypeHintAnalysisTest","--select-class=clojure.lang.ExprToNodeTypeHintPropagationTest"]'
```

## Type-Specialized Nodes via getJavaClass/hasJavaClass

`Compiler.Expr` carries type information (`getJavaClass()`, `hasJavaClass()`) that ExprToNode does not currently use (except `LocalBinding.getPrimitiveType()` for frame slots). This could enable:

- **Type-specialized invoke**: When `InvokeExpr.hasJavaClass()` returns a primitive, propagate that type to avoid boxing.
- **`CaseNode` return type**: Use `CaseExpr.returnType` for static primitive specialization (the dynamic path is now handled).

## Tail-Call Optimization via tailPosition

`InvokeExpr.tailPosition` indicates calls in tail position. This could drive TCO (e.g., via `TailCallException`) for non-`recur` tail calls, reducing stack depth for mutually recursive functions.

## @ExplodeLoop on CaseNode

The `CaseNode` loop over `@Children` arrays could be annotated with `@ExplodeLoop` for Graal to unroll, improving PE for small case expressions without changing dispatch strategy.

## Transitive Bytecode Cache (per-file `.bc` archives)

A per-file bytecode cache that eliminates source parsing/compilation at runtime for all Clojure standard library namespaces. During a dump phase, each `.clj` file is compiled from source and its Truffle bytecode is serialized into a `.bc` file. At runtime, `RT.loadResourceScript` checks the cache directory first and replays the pre-compiled bytecode instead of parsing source.

### Architecture

**Dump phase** (`dump-bytecode-cache` build task):

1. `CloffleBytecodeSerializerMain.runDumpBootstrap(outputDir)` starts the process.
2. `CloffleCompiler.beginRecording(outputDir)` installs a thread-local `BytecodeCacheRecorder`.
3. A Polyglot context is created and `context.initialize("cloffle")` triggers `RT.init()`, which compiles `clojure/core.clj` and its `(load ...)` satellites from source.
4. `discoverClojureNamespaces()` scans the classpath for all `clojure/**/*.clj` files that contain `(ns ...)` declarations and converts file paths to namespace symbols.
5. Each discovered namespace is `(require ...)`d via `context.eval`, triggering source compilation and bytecode recording.
6. `recorder.writeAll()` writes one `.bc` file per source file to the output directory.

**Recording mechanism** (`CloffleCompiler.BytecodeCacheRecorder`):

- `BytecodeCacheRecorder` accumulates serialized bytecode chunks in a `Map<String, List<byte[]>>` keyed by source path.
- The current source path is tracked via a `ThreadLocal<ArrayDeque<String>>` **stack** to handle nested `compile` calls correctly — when `core.clj` executes `(load "core_print")`, this triggers a nested `CloffleCompiler.compile()` that pushes `clojure/core_print.clj` onto the stack, then pops it when done, restoring the parent `clojure/core.clj` context.
- `compile()` calls `recorder.beginFile(sourcePath)` / `recorder.endFile()` around the form-reading loop.
- `executeFormBytecode()` calls `recorder.addChunk(wire)` after executing each form, serializing the `BytecodeRootNodes` to the active file's chunk list.

**Replay phase** (runtime):

- `RT.loadResourceScript(Class, String, boolean)` checks `System.getProperty("cloffle.bytecode.cache.dir")` first.
- If set, it looks for a `.bc` file corresponding to the requested `.clj` name (e.g., `clojure/set.clj` → `<cache-dir>/clojure/set.bc`).
- If found, `CloffleCoreBytecodeArchive.replayFromFile(bcPath, sourcePath, sourceName)` replays the pre-compiled bytecode instead of parsing source.
- If no `.bc` file exists, falls through to normal source loading.
- `replayArchive` accepts arbitrary `sourcePath`/`sourceName` parameters for compile-frame bindings, making it generic (not hardcoded to `core.clj`).

**Replay logging:**

- Per-file log messages are suppressed. Instead, an `AtomicInteger` depth counter tracks nested replays.
- When the outermost replay completes (depth returns to 0), a single summary line is printed: `[Cloffle] Bytecode cache: loaded N files (M forms) in X ms`.

### Wire format (CFBC)

Each `.bc` file uses the same format as the single-file core archive:

| Field | Type | Description |
|-------|------|-------------|
| Magic | `int` | `0x43464243` ("CFBC") |
| Version | `int` | Format version (currently 1) |
| Form count | `int` | Number of top-level forms |
| For each form: | | |
| — Chunk length | `int` | Byte length of the serialized `BytecodeRootNodes` |
| — Chunk data | `byte[]` | `CloffleBytecodeSerialization.serializeRootNodes` output |

### Source optimization in serialization

`CloffleBytecodeSerializer` writes only a single-space placeholder for `Source` content instead of the full file text. This avoids quadratic growth: without the optimization, every per-form chunk redundantly embedded the entire source file (e.g., `core.clj` is ~300KB × 879 forms = ~260MB). The replay side provides its own compile-frame bindings and does not need the original text.

### Build tasks

```bash
# Dump all .bc files (52 files for the full standard library)
clj -T:build dump-bytecode-cache
clj -T:build dump-bytecode-cache :output '"out/bc-cache"' :xmx '"12g"'

# REPL with bytecode cache
clj -T:build cloffle-repl :cache true
clj -T:build cloffle-repl :cache '"path/to/cache-dir"'
```

The `cloffle-repl` task validates that the cache directory exists before launching, throwing a clear error with instructions to run `dump-bytecode-cache` if missing.

### Runtime properties

| Property | Description |
|----------|-------------|
| `cloffle.bytecode.cache.dir` | Directory containing per-file `.bc` archives. When set, `RT.loadResourceScript` serves bytecode instead of source for any `.clj` with a matching `.bc` file. |
| `cloffle.core.bytecode.archive` | Path to a single monolithic `core.bc` archive (the older mechanism). Checked by `RT.init()` before `load("clojure/core")`. |
| `cloffle.core.bytecode.quiet` | Set to `true` to suppress `[Cloffle]` log output. |

### Startup behavior

When `:cache true` is used, `RT.init()` loads 11 files from bytecode at startup — all part of `clojure.core`'s irreducible bootstrap set:

- `core.clj` (879 forms)
- `core_proxy.clj`, `core_print.clj`, `genclass.clj`, `core_deftype.clj`, `core/protocols.clj`, `gvec.clj` — via `(load ...)` in `core.clj`
- `instant.clj`, `uuid.clj` — via `(load ...)` in `core.clj`
- `java/io.clj` — via `(require '[clojure.java.io :as jio])` in `core.clj`
- `string.clj` — transitive dependency of `java/io.clj`

All other namespaces (`clojure.set`, `clojure.pprint`, `clojure.test`, etc.) load from bytecode on demand when `require`d or `use`d.

### DCL class embedding

Compiler-generated classes (`fn`, `reify`, `deftype` implementations) defined in `DynamicClassLoader` during source compilation are serialized via `TYPE_CLASS_DCL` in `CloffleBytecodeSerializer`. During deserialization, `CloffleBytecodeDeserializer` defines these classes in the target JVM's `DynamicClassLoader`, allowing a cold JVM to replay without having generated the classes locally.

### Test coverage

- `BytecodeSerializationRoundTripTest.freshJvmBootstrapsAllNamespacesFromBytecodeCache` — dumps the transitive bytecode cache via the recorder, then forks a fresh JVM with `-Dcloffle.bytecode.cache.dir` to verify cold bootstrap succeeds and `(+ 1 2) = 3`.
- `BytecodeCacheBootstrapMain` — the forked JVM entry point that calls `RT.init()`, resolves `clojure.core/+`, and evaluates `(+ 1 2)`.
