# Cloffle Notes (history & internals)

## Chapter Guide (read this first)

Cloffle’s **guest execution** is **Truffle Bytecode DSL only**: `Compiler.analyze()` → `ExprToBytecode` → `CloffleBytecodeRootNode`. The old hand-written Truffle AST interpreter (`ExprToNode` and dozens of `*Node` classes) has been **removed** (Apr 2026 — see below).

What remains under `net.javacrumbs.cloffle.nodes` is a **thin** Truffle `Node` layer: polyglot parse wrappers (`SequentialFormNode`, `NilNode`, …), macro expansion (`MacroExpandNode`), shared types (`ClojureClosure`, `ClojureException`, scopes), and `ClojureNode` as the instrumentable base for those wrappers — not a second execution engine.

How to read these notes:

- Sections dated **before Apr 2026** may still name removed classes (`FnNode`, `InvokeNode`, `ExprToNode`, …). Treat those as **historical** unless the section was updated; behavior now lives in `ExprToBytecode` / `CloffleBytecodeRootNode`.
- `CLOFFLE_TRUFFLE_BYTECODE.md` redirects here; bytecode details stay in this file.

## Bytecode backend snapshot (Truffle Bytecode DSL)

Quick reference for the **current** guest runtime (`ExprToBytecode` / `CloffleBytecodeRootNode`).

### What the bytecode path is

- **Lowering:** `ExprToBytecode` + `CloffleBytecodeRootNode` (`@GenerateBytecode`).
- **Scope:** execution parity, debugger/source semantics, `Compiler.load` / `CloffleCompiler`, polyglot `Clojure.parse`, AOT serialization (`CloffleBytecodeSerializer` / `CloffleBytecodeDeserializer`).

### Current status

- Core shapes are covered: `let`*, `loop*`, `recur`, `letfn*`, `try`/`catch`/`finally`, vars, Java interop, `case*`, metadata, and typical macro-expanded code.
- Debugger integration uses bytecode tags (`BytecodeTagPolicy`, source on roots/ops) plus the thin `ClojureNode` wrappers where applicable.
- AOT wire format is tested against large `core.clj` slices.

### Known gaps

- General tail-call optimization for tail-position calls (outside `loop*`/`recur`) is still pending on the bytecode path.
- Remaining work: edge-case macro/runtime parity and bytecode-first packaging.

### History in this file

Older sections record decisions from the AST-interpreter era; they are **not** a description of the current codebase unless noted.

## Full Truffle AST interpreter removal (Apr 2026)

**Why:** After `CloffleCompiler` and `Clojure.collectFormInner` switched to `ExprToBytecode`, the original interpreter (`ExprToNode` building `FnNode`, `InvokeNode`, `LetNode`, …) was no longer used for compilation or evaluation. Keeping it duplicated `Expr` lowering, confused “which path runs?”, and dragged along DSL-generated classes (`BindingNodeGen`, …) and JMH benchmarks that only exercised deleted nodes.

**What was removed**

- `ExprToNode.java` and the large set of hand-written `ClojureNode` subclasses that existed only to interpret `Compiler.Expr` (control flow, interop, `invoke`, defs, data-structure literals, etc.), including `nodes/binding`, `nodes/invoke`, `nodes/staticcall`, and `nodes/vars` packages.
- Tests that targeted the AST converter only: `ExprToNodeTypeHintPropagationTest`, `ExprToNodeLocalBindingSlotTest`, `BindingNodeTest`.
- `BytecodeDslTestSupport.evalAst` and tests that asserted AST/bytecode print-string parity (those checks were rewritten against bytecode-only expectations).
- `CloffleNodeBenchmark` (JMH), which micro-benchmarked removed nodes.
- `TruffleIFn` / `ClojureInvoke` (only referenced from the deleted `InvokeNode` path).

**What stayed**

- **`ClojureNode` / `ClojureRootNode`:** polyglot multi-form shell (`SequentialFormNode` + `DirectCallNode` into bytecode roots), eager top-level results (`ObjectNode`), empty-script `NilNode`; still `InstrumentableNode` + `@GenerateWrapper` for stepping/breakpoints on that shell.
- **`MacroExpandNode`:** macro bodies still run under a small `ClojureRootNode` so failures get `ClojureException` and guest frames (`MacroExpander`).
- **Shared runtime:** `ClojureClosure`, `MonitorRegistry`, `ClojureException`, `ClojureScope` / `ClojureTopScope`, `PolyglotNilSafeRootNode`, `NilNode` / `ObjectNode`, `ErrorMessages` (with `formatArities` reworked to `FnArity` records instead of `FnMethodNode`).

**Production wiring**

- `Clojure.parse()` no longer constructs `ExprToNode` only to call `buildFrameDescriptor()`; it uses an empty `FrameDescriptor` for the wrapper root (child bytecode roots carry their own descriptors).
- `ClojureInterop` no longer unwraps a special `FnNode` type; guest functions are normal `IFn` / `ClojureClosure`.

**Docs/tests:** Historical paragraphs elsewhere in this file that refer to `ExprToNode`, per-node tag tables, or removed class names describe the **old** architecture unless explicitly refreshed.

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


| Option                 | Default | Description                                     |
| ---------------------- | ------- | ----------------------------------------------- |
| `--dap-port PORT`      | 4711    | TCP port for the DAP server                     |
| `--dap-suspend`        | enabled | Suspend execution at first source statement     |
| `--dap-no-suspend`     |         | Start executing without pausing                 |
| `--dap-wait`           | enabled | Wait for debugger to attach before running code |
| `--dap-no-wait`        |         | Run immediately; debugger can attach later      |
| `-e CODE`              |         | Evaluate CODE string                            |
| `-r`                   |         | Start interactive REPL                          |
| `script.clj [args...]` |         | Run a Clojure script file                       |


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
- Source sections on the polyglot wrapper nodes and (for guest code) on bytecode roots/ops via `BytecodeConfig` / tag policy

### Makefile Targets


| Target                      | Description                   |
| --------------------------- | ----------------------------- |
| `make cloffle-dap FILE=...` | Debug a script with DAP       |
| `make cloffle-dap-repl`     | Debug a REPL session with DAP |


Optional variables: `DAP_PORT=4712`, `DAP_NOSUSPEND=1`

---

## Debugger Variable Inspection and Scope Support (Mar 2026)

Added NodeLibrary-based scope support so debuggers can inspect local variables when execution is suspended. Previously, breakpoints and stepping worked but variable inspection returned empty scopes.

### Local scope (NodeLibrary on ClojureNode)

- `**ClojureNode`**: Now exports `NodeLibrary` via `@ExportLibrary(NodeLibrary.class)`. Implements `hasScope(Frame)` and `getScope(Frame, boolean)` which return a `ClojureScope` object wrapping the current frame and root node.
- `**ClojureScope**`: InteropLibrary scope object that exposes frame slot variables as members:
  - Reads variable names from `FrameDescriptor` slot names — `LocalBinding.sym` for fn params, let bindings, and loop vars
  - Filters out `Var` slots used internally for var caching in compiled code, not user locals
  - Supports `readMember`, `writeMember`, `getMembers`, `isMemberReadable`, `isMemberModifiable`
  - Reports the function name as `toDisplayString()` from `RootNode.getName()`
  - Reports source location from `RootNode.getSourceSection()`
  - Includes `NullValue` (InteropLibrary null) for uninitialized slots
  - Includes `VariableNamesArray` (InteropLibrary array) for member enumeration

### Top-level scope (TruffleLanguage.getScope)

- `**Clojure.getScope(CloffleContext)**`: Overridden to return a `ClojureTopScope` object.
- `**ClojureTopScope**`: InteropLibrary scope object exposing global vars from the current namespace:
  - Lists vars defined (interned) in the current namespace that are bound
  - Supports `readMember` (derefs the var), `writeMember` (sets the var)
  - Reports namespace name as `toDisplayString()`

### What works now


| Feature                                    | Status                                                                                 |
| ------------------------------------------ | -------------------------------------------------------------------------------------- |
| `DebugStackFrame.getScope()` at breakpoint | **Works** — returns function-level scope with params and let bindings                  |
| `DebugScope.getDeclaredValues()`           | **Works** — lists all initialized local variables                                      |
| `DebugScope.getDeclaredValue(name)`        | **Works** — reads specific variable by name                                            |
| `DebugValue.asLong()` / `.asString()` etc. | **Works** — variable values are readable                                               |
| Scope in recursive function                | **Works** — each recursion depth shows current param values                            |
| `DebuggerSession.getTopScope("cloffle")`   | **Works** — shows namespace vars at breakpoint                                         |
| Top scope var value reading                | **Works** — reads correct `deref()` values                                             |
| Exception breakpoints (uncaught)           | **Works** — `Breakpoint.newExceptionBuilder(false, true)` fires on uncaught exceptions |


### Known scope limitations

- **Flat scope**: Clojure uses a flat function-level frame (no nested block scopes like `let` creating separate scopes). All locals in the function share one `FrameDescriptor`. The scope shows all initialized variables, not just those lexically visible at the current position.
- **Exception breakpoints for caught exceptions**: `Breakpoint.newExceptionBuilder(true, false)` (caught=true, uncaught=false) may not fire when guest `try`/`catch` handles the exception inside the bytecode interpreter before the debugger's exception filter sees it (same practical limitation as the old `TryNode` era).
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


| File                   | Changes                                                         |
| ---------------------- | --------------------------------------------------------------- |
| `ClojureNode.java`     | `@ExportLibrary(NodeLibrary.class)`, `hasScope()`, `getScope()` |
| `ClojureScope.java`    | New — local variable scope object                               |
| `ClojureTopScope.java` | New — top-level namespace scope object                          |
| `Clojure.java`         | Override `getScope(CloffleContext)`                             |
| `DebuggerTest.java`    | 10 new tests (71–80)                                            |


## Project Overview

### Motivation

Cloffle is a Truffle-based implementation of Clojure. The project goal is strong API and behavioral compatibility with JVM Clojure while running through Truffle/GraalVM execution paths.

### Execution model: Truffle Bytecode DSL (+ thin `ClojureNode` shell)

**Guest evaluation** goes through the **[Truffle Bytecode DSL](https://github.com/oracle/graal/blob/master/truffle/docs/BytecodeDSL.md)** (`@GenerateBytecode`): `ExprToBytecode` lowers `Compiler.analyze()` `Expr` trees into `CloffleBytecodeRootNode`, with AOT serialization. The former hand-written AST interpreter (`ExprToNode` and friends) was removed in Apr 2026 (see **Full Truffle AST interpreter removal** above).

A small **`ClojureNode` subtree** still wraps top-level bytecode call targets for polyglot `parse` (stepping between forms, `StatementTag` on runtime statements) and runs macro bodies via `MacroExpandNode`; it is not a second full compiler backend.

**Disambiguation:** “Bytecode” means either (a) **Truffle Bytecode DSL** graphs (`CloffleBytecodeRootNode`), or (b) **JVM ASM** from the stock `Compiler` for `deftype` / `reify` / etc. `ExprToBytecode` refers to (a).

Graal’s Bytecode DSL tutorial examples under [`.../bytecode/test/examples`](https://github.com/oracle/graal/tree/master/truffle/src/com.oracle.truffle.api.bytecode.test/src/com/oracle/truffle/api/bytecode/test/examples) remain the best hands-on reference.

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

Integrated Truffle's instrumentation framework so external tools (debuggers, profilers, code coverage, tracers) can attach via the standard instruments API.

### Core infrastructure (current)

- **`ClojureNode`**: `InstrumentableNode` + `@GenerateWrapper` for the **thin** parse/macro subtree (`SequentialFormNode`, `MacroExpandNode`, …).
- **`Clojure`**: `@ProvidedTags` lists `StatementTag`, `ExpressionTag`, `CallTag`, `RootBodyTag`, `RootTag`, `ReadVariableTag`, `WriteVariableTag`, and (for bytecode breakpoints) `DebuggerTags.AlwaysHalt` where needed.
- **Guest function bodies:** `CloffleBytecodeRootNode` + `BytecodeTagPolicy` attach tags at bytecode operations (statements, calls, reads/writes, roots). The old per-`ClojureNode` tag table (`FnNode`, `InvokeNode`, …) applied only to the **removed** AST interpreter.

### Debugger API integration

`DebuggerTest.java` and related tests exercise `Debugger`, `DebuggerSession`, `Breakpoint`, and `SuspendedEvent` against the **bytecode** graph (and the polyglot wrapper where relevant). Representative behavior:


| Feature                                                   | Status    | Notes                                                                                                                                 |
| --------------------------------------------------------- | --------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| Line breakpoints                                          | **Works** | Nearest instrumentable site whose `SourceSection` contains the line (bytecode ops + wrapper nodes).                                 |
| Stepping (into/over/out)                                  | **Works** | `SequentialFormNode` uses `DirectCallNode` into per-form bytecode roots without blocking step-into across top-level forms.            |
| Stack frames / root names                                 | **Works** | Bytecode roots carry names and source; recursive and multi-level chains still subject to tail-call frame collapse where applicable.    |
| Variable scopes                                           | **Works** | `ClojureScope` reads `FrameDescriptor` slot names (see debugger scope section above); bytecode frame layout differs from the old AST. |

**Historical (pre–Apr 2026):** Much of the stepping/breakpoint polish was first done on `FnDispatchNode`, `InvokeNode`, and other interpreter nodes. Equivalent semantics are now owned by `ExprToBytecode` / `CloffleBytecodeRootNode` and `BytecodeTagPolicy`.

### Known debugger limitations

**Multi-line `defn` / body-line breakpoints:** Suspension line still follows whichever instrumentable site owns the best `SourceSection` for that line (bytecode source attribution + root naming). See `DebuggerTest` cases for concrete expectations.

**Threading:** `Clojure.initializeThread()` / `finalizeThread()` manage `Var` bindings; polyglot `Context` use from the wrong thread can still trigger binding stack imbalance — see the Cloffle-specific error message in `finalizeThread`.

### Files (evolving)

Primary touchpoints today: `CloffleBytecodeRootNode.java`, `BytecodeTagPolicy.java`, `ExprToBytecode.java`, `ClojureNode.java`, `SequentialFormNode.java`, `Clojure.java`, `DebuggerTest.java`, `InstrumentationTest.java`.


## `:inline`, `^double`, and local slot scoping (Mar 2026) — historical

**Symptom:** Loading `clojure.test-clojure.predicates` failed while analyzing e.g. `(NaN? nil)` with `RT.doubleCast` → `NullPointerException` during `:inline` expansion.

**Root cause (conceptual):** (1) Truffle-side primitive slot writes must treat `NullPointerException` like `ClassCastException` and widen the slot to `Object` when `nil` is stored in a primitive-typed slot. (2) The **removed** `ExprToNode` local-slot allocator originally keyed locals too weakly, so a `^double` param in one `fn*` could collide with a different `fn*` in the same compile (classic `defn` body vs `:inline` fn). That logic lived in `ExprToNode` + `BindingNode`; **today** the same `Expr` lowering must stay correct in **`ExprToBytecode`** (frame locals / `BytecodeLocal` scoping).

**Upstream / Compiler:** `Compiler.java` changes (e.g. removing an NPE-only shield around `inline.applyTo` once Truffle lowering was fixed) remain part of the story; see git history and `CompilerTypeHintAnalysisTest`.

**Tests today:** `clojure.lang.CompilerTypeHintAnalysisTest`, `CloffleCompilerTest` (`defnWithDoubleHintAndInlineCompiles`). The dedicated `ExprToNodeLocalBindingSlotTest` was **deleted** with `ExprToNode` (Apr 2026).

**Verification:** `clojure -T:build run-tests` and `run-clj-tests` with `:only-namespace '"clojure.test-clojure.predicates"'`.

### JUnit JVM bootstrap (`clojure.core` on the host class loader)

JUnit runs many classes in one JVM; `**CloffleCompiler.compile`** resolves symbols (e.g. `**+**`, `**/**`, `**declare**`) against the host `**Namespace**` / `**RT**` state. If no test has run `**RT.init()**` yet, `**clojure.core**` is not loaded and compiler tests fail with **Unable to resolve symbol**.

`**RT.doInit()`** (in `**RT.java**`) sets `**INIT = true` only after** `**load("clojure/core")`**, `**in-ns` / `refer**`, and `**user.clj**` complete successfully. Previously `**INIT` was flipped true before loading**, so any thrown error during bootstrap left the JVM permanently stuck: later `**RT.init()`** calls returned immediately while `**user**` still lacked `**clojure.core**` refers.

**Do not** auto-register `**LauncherSessionListener`** or `**TestExecutionListener**` SPIs that call `**RT.init()**` on the ConsoleLauncher thread without verifying bootstrap: loading `**core.clj**` through Cloffle can still throw there (e.g. analyzer errors mid-file), and a failed attempt may leave namespaces partially loaded even when `**INIT**` stays false.

**Recommended for tests:**

- `**@BeforeClass public static void hostClojure() { RT.init(); RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user"))); }`** (pattern used in `**ExceptionTest**`, `**CloffleCompilerTest**`, …), or
- `**@ClassRule public static final CloffleHostClojureRule CLOJURE_HOST = new CloffleHostClojureRule();**` — `**net.javacrumbs.cloffle.junit.CloffleHostClojureRule**` extends JUnit 4 `**ExternalResource**` for a one-line opt-in when a class has no `**@BeforeClass**` hook yet.

## Source Location, Error Messages, and Stack Trace Improvements (Mar 2026)

A series of changes to significantly improve how Cloffle reports errors, stack traces, and source locations by leveraging Truffle APIs more fully.

### Macro expansion via Truffle

Macro expansion now invokes macro functions through a Truffle `CallTarget` (via `MacroExpander.expandViaGuest`) rather than calling the `IFn` directly. This means macro expansion errors produce `ClojureException`s with guest stack frames and source locations.

- `**MacroExpander**`: Creates a `ClojureRootNode` wrapping a `MacroExpandNode`, executes it via `CallTarget.call()`. Threads the real `Source` from `MacroExpander.CURRENT_SOURCE` (ThreadLocal) into the root node's `SourceSection` and applies line/column from the form's metadata to the `MacroExpandNode`.
- `**Clojure.collectForm` / `truffleEval**`: Set `MacroExpander.CURRENT_SOURCE` around `Compiler.macroexpand()` calls.
- `**CloffleCompiler.compile**`: Sets `MacroExpander.CURRENT_SOURCE` for the duration of compilation.

### `{:type …}` metadata and printing during macro expansion (Mar 2026)

Clojure’s `print-method` multimethod dispatches on `**(:type (meta x))**` when that value is a keyword; otherwise it dispatches on `**(class x)**` (see `clojure.core/print-method` and `core_print.clj`). Libraries such as Malli attach `**^{:type …}**` to **unevaluated** forms (for example around `**reify`**). Any code that **prints** those forms while they are still lists—`**str`** on a seq (`**ASeq.toString` → `RT.printString` → `RT.print**`), `**pr` / `prn` / `pr-str**` (`**pr-on` → `print-method**`), or nested `**print-method**` implementations that recurse with `**pr-on**`—can therefore select a user `**print-method**` for that keyword and pass a `**PersistentList**`. If that method assumes a real instance (for example it calls a protocol function), expansion fails with `**IllegalArgumentException**`.

**Mitigations in Cloffle:**

- `**RT`**: A dynamic `**Var`** `***in-macro-expansion***` (`pushMacroExpansionContext` / `popMacroExpansionContext` use `**Var.pushThreadBindings` / `popThreadBindings`**). While bound to `**true**`, `**RT.print`** runs `**stripTypeMetaDeepForDiagnostics**` on the value before `**PR_ON.invoke**`, and the `**print-method**` defmulti dispatch function skips keyword `**:type**` dispatch—both fall back to class-based printers for raw structure. Helpers `**stripTypeMetaForMacroSourceLabel**` (shallow) and `**stripTypeMetaDeepForDiagnostics**` (walk via `clojure.walk/postwalk`, with a shallow fallback if the walk cannot run) live on `**RT**` for reuse from compiler code.
- `**Compiler.macroexpand1**`: The **entire** method is wrapped in `**RT.pushMacroExpansionContext` / `popMacroExpansionContext`** (in `**finally**`), which pushes/pops a `**Var**` binding frame. Nested `**Compiler.macroexpand` / `macroexpand1`** calls from macro bodies (for example `**defn**`) each push their own frame; the Var binding stack handles reentrancy naturally.
- `**MacroExpander**`: When `**CURRENT_SOURCE**` is missing, the synthetic label still uses `**RT.stripTypeMetaForMacroSourceLabel**` on the form before `**toString()**`, so building the fallback `**Source**` text does not trigger bad `**print-method**` dispatch.
- `**CloffleCompiler**`: Compile trace and error logging pass forms through `**RT.stripTypeMetaDeepForDiagnostics**` before `**RT.printString**`.

**Regression tests:** `**net.javacrumbs.cloffle.MalliIntoSchemaReproTest`** — minimal `**defprotocol` / `defmethod print-method` / `^{:type …} (reify …)**` under Cloffle, including the `**defn**` body case that required the `**macroexpand1**`-scoped push/pop; `**protocol` on a list** throws in Cloffle; and a **Cloffle-only** macro case where `**print-method` calls a protocol** on the form (would throw on stock Clojure if the print multimethod path were active). JVM-side `**mikera.cljutils`** parity tests for this area were removed with the dependency.

### Macro expansion trail as parameter (not ThreadLocal)

The macro expansion trail (showing nested macro chains like `outer → inner`) is passed as a `List<String>` parameter through `Compiler.macroexpand` and `macroexpand1`, rather than stored in a `ThreadLocal`. This keeps the API surface small and makes upstream merges easier.

- `**Compiler.macroexpand(Object)**`: Public API unchanged. Internally creates a fresh `ArrayList<String>` and delegates to a package-private `macroexpand(Object, List<String>)`.
- `**Compiler.macroexpand1(Object, List<String>)**`: Appends the macro name to the trail before expansion. On failure, `makeMacroCompilerException` formats the trail into the `CompilerException` message (e.g., `"Macro expansion chain: outer → inner"`).

### Correct line/column in CompilerException for macro errors

`Compiler.macroexpand1` now extracts `formLine` and `formCol` from the form's `IMeta` metadata (`:line` / `:column` keys) and uses those in the `CompilerException` constructor, instead of `lineDeref()` / `columnDeref()` which returned `(0:0)` during macro expansion.

### Real Source in CloffleCompiler (no more NO_SOURCE)

- `**CloffleCompiler.compile**`: Binds real `Compiler.SOURCE_PATH` / `Compiler.SOURCE` for file compilation and runs forms through `executeForm()`.
- `**CloffleCompiler.executeForm**`: Builds a Truffle `Source` using the current `Compiler.SOURCE` name (fallback `"NO_SOURCE"` only when unavailable) so bytecode lowering (`ExprToBytecode`) can attach source spans to a real file name.

### Root SourceSection on all eval roots

Previously, several paths created `ClojureRootNode` without setting a `SourceSection`, which made all child node source sections return `null` (since `ClojureNode.getSourceSection()` derives from the root's source):

- `**Clojure.truffleEval**`: Now sets `root.setSourceSection(source.createSection(0, source.getLength()))` and a root name from the form's first symbol.
- `**CloffleCompiler.executeForm**`: Uses the same source text for bytecode root conversion so roots/ops get consistent sections.

### CompilerException data → ClojureParseError SourceSection

`Clojure.makeAnalyzerException` extracts `ERR_LINE` and `ERR_COLUMN` from the `CompilerException`'s data map and uses them when constructing `ClojureParseError`, falling back to the reader's position if not available.

### Full cause chain in parse error messages

`Clojure.buildFullMessage` walks up to 5 levels of the exception cause chain, appending unique messages to ensure the root cause is visible in the top-level `ClojureParseError` message.

### Source spans on `Expr` (shared rules)

`ExprSourceSpans` (and `ExprToBytecode`) follow the same line/column heuristics the old `ExprToNode` path used for literals and non-obvious `Expr` types (e.g. `NewInstanceExpr`, `BodyExpr`). The AST converter is gone; span logic lives next to bytecode lowering.

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

- `**compile()` loop**: Before calling `executeForm(r)` for each top-level form, pushes `Compiler.LINE`/`Compiler.COLUMN` bindings extracted from the form's reader-attached metadata (falling back to the pushback reader's line number). Pops in a `finally` block.
- `**executeForm()` do-splitting**: When a macro expands to `(do ...)` and the sub-forms are iterated, each sub-form now gets its own `LINE`/`COLUMN` binding from its metadata. This is critical because `defmacro` expands to `(do (defn ...) (. (var name) (setMacro)) (var name))` and the inner `defn` sub-form needs the correct line context.

Also cleaned up: replaced local `Keyword.intern(null, "line")`/`"column"` with shared class-level constants `LINE_KEY`/`COLUMN_KEY` (needed since `RT.LINE_KEY`/`RT.COLUMN_KEY` are package-private).

Result: `(meta #'when)` now correctly reports `:line 495 :column 1` instead of `:line 0 :column 0`.

### Polyglot parse() path: same LINE/COLUMN/SOURCE fixes

`Clojure.java`'s polyglot `parse()` path had the same family of bugs as `CloffleCompiler.compile()`:

1. `**pushCompilerBindings()` missing `Compiler.LINE`/`Compiler.COLUMN`**: Now binds both (initialized to `1`) alongside `LINE_BEFORE`/`COLUMN_BEFORE`/`LINE_AFTER`/`COLUMN_AFTER`.
2. `**SOURCE_PATH`/`SOURCE` set to placeholders**: Was `"NO_SOURCE_PATH"`/`"NO_SOURCE_FILE"` even though `truffleSource.getName()` was available. Now passes the real source name.
3. `**truffleEval()` do-splitting missing `LINE`/`COLUMN` per sub-form**: When a macro expands to `(do ...)`, each sub-form now gets its own `LINE`/`COLUMN` binding from its metadata (same fix as `CloffleCompiler.executeForm()`).
4. `**collectForm()` missing `LINE`/`COLUMN` binding and metadata transfer**: Now pushes `LINE`/`COLUMN` bindings from form metadata before analyzing, and transfers `:line`/`:column` metadata from original form onto macro-expanded form (matching `CloffleCompiler.executeForm()`'s metadata transfer pattern).

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


| Key                     | Value                                                                       |
| ----------------------- | --------------------------------------------------------------------------- |
| `:clojure.error/phase`  | Error phase keyword (`:execution`, `:read-source`, `:macroexpansion`, etc.) |
| `:clojure.error/source` | Source file name from `SourceSection`                                       |
| `:clojure.error/line`   | Line number                                                                 |
| `:clojure.error/column` | Column number                                                               |
| `:clojure.error/class`  | Cause exception class as a symbol                                           |
| `:clojure.error/cause`  | Cause exception message                                                     |


`ClojureException.wrap()` sets phase to `:execution`. `ClojureParseError` defaults to `:read-source`. The `getData()` method builds the map lazily from the node's resolved `SourceSection` and the wrapped cause.

This enables compatibility with `clojure.main/ex-triage`, `clojure.main/ex-str`, and editor integrations (CIDER, nREPL) that expect these keys.

### Error phases in REPL

`PolyglotErrorConsoleDisplay.printError` displays phase-aware labels when a phase is available:

```
Execution error (execution) at (foo.clj:4:3): ArithmeticException: / by zero
Syntax error (read-source) at (foo.clj:1:1): Unmatched delimiter: )
```

Phase is propagated from `ClojureException` via a `ThreadLocal<Keyword>`, published in `publishFrames()` and consumed by `PolyglotErrorConsoleDisplay` (private `formatPhase` / `formatPhaseGuest` helpers). The label maps phase keywords to user-friendly categories: `:read-source`/`:macro-syntax-check` → "Syntax error", `:macroexpansion` → "Syntax error (macroexpansion)", `:compilation` → "Compile error", `:execution` → "Execution error".

### Guest REPL (`cloffle.repl`) and host polyglot context

`clj -T:build cloffle-repl` starts `CloffleRepl`: after `Context.initialize("cloffle")`, bootstrap runs an **install** `Context#eval` that `(require 'cloffle.repl)` and returns a Clojure function calling `cloffle.repl/install-host-eval!`, then **`Value.execute`** passes **two** host `clojure.lang.AFn` values (arity-2 string eval, arity-1 file eval) closed over the polyglot `Context`, then a **launcher** `Context#eval` runs `(cloffle.repl/run-from-launcher …)`. Interactive and script paths in `cloffle.repl` call those fns via `IFn` / `.invoke`, which evaluates user source with `Context#eval` so failures remain `PolyglotException`s and reuse `PolyglotErrorConsoleDisplay`.

Using `load-string` / `load-file` alone for user code was abandoned: caught throwables are often plain `java.lang.Exception` without a useful `.clj` stack for diagnostics. Passing a **Java** host object (e.g. `ReplEvalHost`) through the polyglot boundary wraps it as `com.oracle.truffle.host.HostObject`; guest Clojure’s Java interop then resolves methods on `HostObject`, not the delegate, so **use host `IFn` (or equivalent) instead of `.evalMethod` on a wrapped POJO**.

### Stack trace filtering for Throwable->map

`ClojureException.getStackTrace()` overrides `Throwable.getStackTrace()` to filter out internal Truffle/GraalVM frames. Filtered prefixes: `com.oracle.truffle.*`, `org.graalvm.*`, `jdk.graal.*`, `com.oracle.graal.*`, `$CallTarget`, `$FrameWithoutBoxing`, `sun.reflect.*`, `java.lang.reflect.*`, `jdk.internal.reflect.*`.

This makes `Throwable->map`, `clojure.stacktrace/print-stack-trace`, and `(pst)` output readable instead of showing hundreds of internal runtime frames.

### Precise source location verification

Source locations were validated by a probe of every major form type, confirming the `(line, column, charLength)` triple reported by Truffle `SourceSection` is precise enough for red-squiggle tooling. Key verified behaviors:


| Form                       | Primary frame | Length | Notes                                 |
| -------------------------- | ------------- | ------ | ------------------------------------- |
| `(/ 1 0)`                  | L1:C1         | 7      | Top-level                             |
| `(+ 1 (/ 2 0))`            | L1:C6         | 7      | Points to inner form, not outer `(+)` |
| `(+ 1 (* 2 (/ 3 0)))`      | L1:C11        | 7      | Deep nesting                          |
| `(if true (/ 1 0) :else)`  | L1:C10        | 7      | Then-branch form                      |
| `(if false :then (/ 1 0))` | L1:C18        | 7      | Else-branch form                      |
| `(let [x (/ 1 0)] x)`      | L1:C9         | 7      | Init expression                       |
| `(do 1 2 (/ 3 0))`         | L1:C9         | 7      | Last body expression                  |
| `(cond ... :else (/ 1 0))` | L4:C9         | 7      | Macro-expanded inner                  |
| `(and true (/ 1 0))`       | L2:C6         | 7      | Second operand                        |
| `(-> 0 (/ 0))`             | L2:C5         | 5      | Threading form                        |
| `[(/ 1 0) 2]`              | L1:C2         | 7      | Inside vector literal                 |
| `{:a (/ 1 0)}`             | L1:C5         | 7      | Map value                             |
| `#{(/ 1 0)}`               | L1:C3         | 7      | Set element                           |
| `(.substring "hi" 99)`     | L1:C1         | 24     | Whole interop call                    |
| `(Integer/parseInt "xyz")` | L1:C1         | 24     | Static method                         |
| `(Integer. "xyz")`         | L1:C1         | 16     | Constructor                           |
| `("hello" 1)`              | L1:C1         | 11     | String-as-fn                          |
| `(true 1)`                 | L1:C1         | 8      | Boolean-as-fn                         |
| `(42 :key)`                | L1:C1         | 9      | Number-as-fn                          |
| `(throw (Exception. "x"))` | L1:C1         | 24     | Throw form                            |
| `(def z (/ x 0))`          | L3:C8         | 7      | Inner form, not outer `def`           |


Multi-level call stacks correctly report per-frame line+column. For example, `(defn fail [] (throw ...))\n(+ 1 (fail))` reports both L1:C1 (throw site) and L2:C6 (call site `(fail)`).

### Test coverage

Four new test files (113 tests total):

- `**SourceLocationVerificationTest.java**`: 51 tests asserting exact `(line, column, charLength)` triples for arithmetic, `if`/`let`/`do`/`throw`/`cond`/`and`/`or`/`->`/`->>`, interop, constructors, collections, cannot-call, multi-level stacks, arity, loop/recur, parse errors, and var metadata.
- `**ErrorDiagnosticsTest.java**`: 32 integration tests via the Polyglot API covering arity wrapping, error messages, source locations, narrowed root sections, did-you-mean, ex-data, phases, stack traces, and var metadata line/column.
- `**ErrorMessagesTest.java**`: 20 unit tests for `formatArities`, `didYouMean`, `editDistance`, `formatException`, `clojureTypeName`, `cannotCallMessage`, `truncateValue`.
- `**ClojureExceptionTest.java**`: 10 unit tests for `IExceptionInfo` (`getData()`), phase tracking (`publishFrames`/`consumePhase`), stack trace filtering (`filterInternalFrames`), and enriched frame management.

### Files changed


| File                      | Changes                                                                                                                                                                                                                                                                                                              |
| ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Clojure.java`            | `pushCompilerBindings` binds `LINE`/`COLUMN`/`SOURCE`/`SOURCE_PATH` from real source; `collectForm` pushes `LINE`/`COLUMN` per form and transfers metadata; `truffleEval` pushes `LINE`/`COLUMN` per do-subform and transfers metadata; added `transferLineColumnMeta`/`extractFormLine`/`extractFormColumn` helpers |
| `CloffleCompiler.java`    | `compile()` pushes `Compiler.LINE`/`COLUMN` per form; `executeForm()` pushes `LINE`/`COLUMN` per do-subform; uses `Compiler.SOURCE` for Truffle source name; shared `LINE_KEY`/`COLUMN_KEY` constants; `extractFormLine`/`extractFormColumn` helpers                                                                 |
| `InvokeNode.java`         | ArityException wrapping in `invokeGeneric`                                                                                                                                                                                                                                                                           |
| `FnNode.java`             | Improved arity message with expected arities, narrowed root source section                                                                                                                                                                                                                                           |
| `ExprToNode.java`         | `extractFromExprValue` fallback for literal source locations                                                                                                                                                                                                                                                         |
| `ErrorMessages.java`      | ArityException formatting, `didYouMeanNamespace`, `editDistance` made public                                                                                                                                                                                                                                         |
| `ClojureException.java`   | `IExceptionInfo`, phase tracking, stack trace filtering, `LAST_PHASE` ThreadLocal                                                                                                                                                                                                                                    |
| `ClojureParseError.java`  | `IExceptionInfo` with `:read-source` phase                                                                                                                                                                                                                                                                           |
| `SequentialFormNode.java` | Per-form root source sections                                                                                                                                                                                                                                                                                        |
| `PolyglotErrorConsoleDisplay.java` | `printError`, `formatPhase` / `formatPhaseGuest` for phase-aware error labels                                                                                                                                                                                                                                |
| `VarNode.java`            | `didYouMean` on unresolved symbol errors                                                                                                                                                                                                                                                                             |
| `FIAdapterNode.java`      | `ClassCastException` wrapping in `ClojureException`                                                                                                                                                                                                                                                                  |


## Developer Experience Improvements (Apr 2026)

Four improvements spanning both Chapter 1 and Chapter 2, focused on error messages, stack traces, source location precision, and tooling metadata.

### Bytecode enriched frame tracking (Chapter 2)

`CloffleBytecodeRootNode.interceptTruffleException` now adds enriched frames to `ClojureException` as exceptions propagate through bytecode roots — matching the AST path's `InvokeNode.invokeTruffleTarget` behavior. On the AST path, `InvokeNode` uses `addFrame(Node)` to record call sites (including tail-call-eliminated frames via `TailCallException`). The bytecode path has no per-call-site `Node`, so a new `ClojureException.addFrame(SourceSection, String)` overload was added that accepts a source section and function name directly.

The `interceptTruffleException` hook fires for every `CloffleBytecodeRootNode` that an exception passes through, so this naturally captures intermediate frames in deep call chains. The bytecode source section is resolved via `BytecodeNode.getSourceLocation(bytecodeIndex)` (with `BytecodeLocation` fallback), and the root name comes from `CloffleBytecodeRootNode.name`.

Extracted `resolveBytecodeSourceSection(BytecodeNode, int)` helper to separate source resolution from exception handling logic.

**Result**: Deep call chains like `process → calculate → divide → error` now show all intermediate guest frames on the bytecode path, not just the innermost error site.

### Literal Expr source sections

`NilExpr`, `BooleanExpr`, `NumberExpr`, `StringExpr`, `KeywordExpr`, `ConstantExpr`, `EmptyExpr` — none of these had `line`/`column` fields in `Compiler.java`. Source locations for literals relied on `ExprSourceSpans.extractFromExprValue()` falling back to `Compiler.LINE_BEFORE`/`COLUMN_BEFORE` thread-locals, which are fragile (they may hold stale values from the previous form).

Added `line`/`column` fields to `NumberExpr`, `StringExpr`, `KeywordExpr`, `ConstantExpr`, and `EmptyExpr`, captured from `lineDeref()`/`columnDeref()` at construction time. `NilExpr` and `BooleanExpr` are singletons and continue to use the thread-local fallback.

`ExprSourceSpans.extractLineColumn()` now has explicit `instanceof` branches for `NumberExpr`, `StringExpr`, `KeywordExpr`, `ConstantExpr`, and `EmptyExpr`, reading their stored `line`/`column` fields instead of falling through to the thread-local fallback.

### `didYouMean` / `didYouMeanNamespace` at more resolution sites

`ErrorMessages.didYouMean(name, ns)` was wired into `VarNode` (runtime unbound var) but not into compile-time resolution paths. `ErrorMessages.didYouMeanNamespace(alias)` was implemented but never called anywhere.

Now wired into three `Compiler.java` error paths:

| Error path | Suggestion method | Example |
| :--- | :--- | :--- |
| `Compiler.resolveIn` — "No such namespace: X" | `didYouMeanNamespace(sym.ns)` | `(clojure.strng/join ...)` → "Did you mean: clojure.string?" |
| `Compiler.resolveIn` — "No such var: ns/X" | `didYouMean(sym.name, ns)` | `(clojure.string/jon ...)` → "Did you mean: clojure.string/join?" |
| `Compiler.analyzeSymbol` — "Unable to resolve symbol: X" | `didYouMean(sym.name, currentNS())` | `(printl "hi")` → "Did you mean: println?" |

### ex-data span metadata for editor tooling

`ClojureException.buildExData()` previously emitted `:clojure.error/line` and `:clojure.error/column` but not the span extent. Editors drawing red squiggles need the end position or length to highlight the exact form.

Added three new keys:

| Key | Value | Source |
| :--- | :--- | :--- |
| `:clojure.error/length` | Character length of the source span | `SourceSection.getCharLength()` |
| `:clojure.error/end-line` | End line of the source section | `SourceSection.getEndLine()` |
| `:clojure.error/end-column` | End column of the source section | `SourceSection.getEndColumn()` |

These keys are only present when the `SourceSection` is available and has line information. The `CallFrame` record already carried `length` for guest-frame display; these new keys surface equivalent data in `(ex-data *e)` for tooling compatibility.

### Files changed

| File | Changes |
| :--- | :--- |
| `CloffleBytecodeRootNode.java` | Enriched frame tracking in `interceptTruffleException`; `resolveBytecodeSourceSection` helper |
| `ClojureException.java` | `addFrame(SourceSection, String)` overload; `:clojure.error/length`, `/end-line`, `/end-column` in `buildExData` |
| `Compiler.java` | `line`/`column` on `NumberExpr`, `StringExpr`, `KeywordExpr`, `ConstantExpr`, `EmptyExpr`; `didYouMean` at `analyzeSymbol` and `resolveIn` (3 sites) |
| `ExprSourceSpans.java` | `extractLineColumn` handles `NumberExpr`, `StringExpr`, `KeywordExpr`, `ConstantExpr`, `EmptyExpr` |
| `DxImprovementsTest.java` | 20 new polyglot integration tests |
| `ErrorMessagesTest.java` | 2 new `didYouMeanNamespace` unit tests |

### Test coverage

22 new tests across `DxImprovementsTest` (20) and `ErrorMessagesTest` (2). 766/770 tests passing (4 pre-existing failures in `PolyglotErrorLocationsTest`).

---

## Polyglot triage, richer parse `ex-data`, debugger roots (Mar 2026)

Follow-up work for **embedded** `Context.eval` callers and **compile/macro** errors: tool-friendly maps aligned with `clojure.main/ex-triage`, structured guest stacks, richer `IExceptionInfo` on parse/analyzer failures, narrower function-entry source spans for the debugger, and clearer threading errors.

### `PolyglotErrorTriage` (Java API for embedders)

- `**PolyglotErrorTriage.triage(PolyglotException)`** returns an `IPersistentMap` with:
  - Standard keys: `:clojure.error/phase`, `source`, `line`, `column`, `cause`, and optional `class`.
  - `**:clojure.error/guest-frames**`: vector of maps per guest stack frame (`:source`, `:line`, `:column`, optional `:root-name`, `:snippet`).
  - `**:clojure.error/polyglot**`: nested map of flags (`internal-error?`, `syntax-error?`, `guest-exception?`, `host-exception?`, `incomplete-source?`).
- Merges `**:clojure.error/***` from any host `Throwable` that is `IExceptionInfo`, and from `**getGuestObject()**` when it is a host `Throwable` (even if `isGuestException()` is false), so phases/symbols/spec/**macro-stack** from guest exceptions show up in the map.
- **Phase heuristics:** `isIncompleteSource` / `isSyntaxError`, plus common **reader** substrings in the exception message when Graal does not classify the error as syntax.
- Tests: `**PolyglotErrorTriageTest.java`**.

### `ClojureParseError.getData()` (macro / compile / spec)

When the cause chain includes `Compiler.CompilerException` or spec-related `IExceptionInfo` data, `getData()` now adds:


| Key                          | Role                                                                                                                                 |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| `:clojure.error/phase`       | Taken from the **innermost** `Compiler.CompilerException` when present (overrides the default `:read-source` for analyzer failures). |
| `:clojure.error/symbol`      | From compiler exception data when present.                                                                                           |
| `:clojure.error/spec`        | Full exception **data** map of the first `IExceptionInfo` in the chain that has `:clojure.spec.alpha/problems`.                      |
| `:clojure.error/class`       | Class symbol of the **leaf** non–`CompilerException` cause.                                                                          |
| `:clojure.error/macro-stack` | Vector of symbols from `ERR_SYMBOL` on each `Compiler.CompilerException` walked along `getCause()` (outer to inner).                 |


Tests: `**ClojureParseErrorExDataTest.java`**.

### Debugger: body-scoped function roots

- `**FnMethodNode.getBody()**` exposes the method body node.
- `**FnNode.preferredFunctionBodySection()**` prefers the first method’s body `SourceSection` (with encapsulating fallback) for `**FnDispatchNode**` and `**ClojureRootNode**` in `getCallTarget()`, falling back to the full fn form section when needed.
- `**DebuggerTest**`: `stackFramesAtBreakpoint` counts **non-host, non-internal** frames and uses a **non-tail** call chain so tail/self-tail optimization does not hide `a`/`b` when stopped in `c`; `**multiLineDefnBreakpointStartLineMatchesBodyLine`** asserts suspension on the body line.

### Threading

- `**Clojure.finalizeThread**`: on `Pop without matching push`, rethrows with an explanatory `IllegalStateException` describing Polyglot thread/context expectations (same thread initialization path as `initializeThread`).

### Error contract (triage maps), `ex-str`-style printing, editor diagnostics

**Stable triage map** (from `PolyglotErrorTriage/triage`, `ClojureException` / `ClojureParseError` `getData()`, or hand-built for tools):


| Key                           | Type                  | Required | Meaning                                                                                                                                                                       |
| ----------------------------- | --------------------- | -------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `:clojure.error/phase`        | Keyword               | yes      | `:read-source`, `:macro-syntax-check`, `:macroexpansion`, `:compile-syntax-check`, `:compilation`, `:execution`, `:read-eval-result`, `:print-eval-result`, or tool-specific. |
| `:clojure.error/source`       | String                | usually  | Logical file name (e.g. Truffle `Source` name), not always a filesystem path.                                                                                                 |
| `:clojure.error/path`         | String                | no       | Optional path (JVM `ex-triage` style); printers prefer `path` over `source` for the location label when both exist.                                                           |
| `:clojure.error/line`         | Number (`long`/`int`) | no       | 1-based line; printers default to `1`.                                                                                                                                        |
| `:clojure.error/column`       | Number                | no       | 1-based column when present.                                                                                                                                                  |
| `:clojure.error/cause`        | String                | no       | Primary human message.                                                                                                                                                        |
| `:clojure.error/class`        | Symbol                | no       | Cause class (often JVM class name).                                                                                                                                           |
| `:clojure.error/symbol`       | Symbol                | no       | Var/macro symbol for compile/macro phases.                                                                                                                                    |
| `:clojure.error/spec`         | IPersistentMap        | no       | Spec explain data (`:clojure.spec.alpha/problems`, etc.).                                                                                                                     |
| `:clojure.error/macro-stack`  | Sequential            | no       | Symbols for nested `CompilerException` chain (outer→inner).                                                                                                                   |
| `:clojure.error/guest-frames` | Sequential of maps    | no       | Each map: `:source`, `:line`, `:column`, optional `:root-name`, `:snippet` (Cloffle / Truffle guest stack).                                                                   |
| `:clojure.error/polyglot`     | IPersistentMap        | no       | Flags from `PolyglotErrorTriage` only (`internal-error?`, `syntax-error?`, …).                                                                                                |


**Printing**

- **Java (no Clojure call):** `PolyglotErrorTriage.formatMessage(IPersistentMap)` or `PolyglotErrorTriage.formatMessage(PolyglotException)` delegates to `ClojureErrorExStr.formatTriageMessage`. Matches `clojure.main/ex-str` for the common phases; for `:clojure.error/spec`, uses capped `RT.printString` instead of `spec/explain-out`.
- **Clojure:** `clojure.polyglot.error/triage-ex-str` — same as `clojure.main/ex-str` for the base line, then appends `:clojure.error/macro-stack` and `:clojure.error/guest-frames` in the same shape as Java. `**polyglot-exception-message`** triages a `PolyglotException` and formats it.
  - **Source:** `src/clj/clojure/polyglot/error.clj` (fork classpath; `jar` copies forked `.clj` into `target/classes`).
  - `**clojure.main/ex-str` in source:** The repo ships a Java class `clojure.main` and a Clojure namespace `clojure.main`. If the namespace is not registered yet, a bare qualified symbol can be misread as a Java static. `**Compiler.analyzeSymbol`** avoids that by calling `**RT.load**` on the script path `**clojure/` + ns with dots replaced by slashes** when the namespace is still missing, a host class exists for that ns segment, and the **var name contains a hyphen** (so real Clojure Vars like `**ex-str`** win; hyphen-free names still follow normal Java interop). `**triage-ex-str` therefore calls `(clojure.main/ex-str triage)` directly** — no `requiring-resolve` workaround.
- `**clojure.main/ex-str` (fork):** when `:clojure.error/class` is absent, `simple-class` is nil; `cause-type` is now empty (matches JVM `ClojureErrorExStr` and avoids `Execution error () at …` from `(str " (" nil ")")`).

**Editor / LSP-style check**

- `**CloffleDiagnostics.checkParse(Context, Source)`** — `Context.parse` (no `eval`); returns empty list on success or a singleton `Diagnostic` (severity, message, `sourceName`, **1-based** line/column range, `phase` string). Messages use `PolyglotErrorTriage.formatMessage`. **LSP:** subtract 1 from lines; map columns to your editor’s encoding rules.
- `**CloffleDiagnostics.diagnosticFromException(String defaultSourceName, PolyglotException)`** — for failures from `eval`.

Tests: `ClojureErrorExStrTest`, `CloffleDiagnosticsTest`, `PolyglotClojureFormatTest` (the last host-calls `**RT.load("clojure/polyglot/error")**` in `@Before` so the namespace is on the JVM classpath before `Context.eval`; embed-time `require` / libspec text is still brittle in some tests).

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

`**Compiler.eval()` ported to Truffle:**

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

`**ClojureClosure` now extends `AFunction`:**

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

- 403/405 Cloffle JUnit tests passing via `clojure -T:build run-tests` (default `**:fresh true`** cleans `target/` first, equivalent to the former `rm -rf target && …`; 2 pre-existing edge cases: `loadCoreCljFormByForm` has 10 form-level failures in core.clj's `..` and `with-open` macro expansions during standalone loading; `testTailCallInsideTryFinallyPreservesFinallyOrder` has a trailing whitespace mismatch)
- 622 `deftest`s from Clojure's own test suite run through Cloffle via `clojure -T:build run-clj-tests`; see [Clojure Test Suite Compatibility](#clojure-test-suite-compatibility-mar-2026) for current assertion-level failures/errors. An additional 107 generative tests (1,219 assertions) from 4 `test.check` namespaces are excluded by default for speed.

## Host-Eval Removal (Mar 2026)

The `hostEval` mechanism that routed certain forms (`ns`, `require`, `import`, `defmacro`, `defprotocol`, etc.) through `Clojure.hostEval()` → `Compiler.eval()` was removed entirely. All forms now flow through the Truffle pipeline.

### What changed

- `**Clojure.java**`: Removed all hostEval-related fields and methods (`HostEvalResult`, `HOST_EVAL_FALLBACK`, `HOST_EVAL_FORM_NAMES`, `DIRECT_HOST_INVOKE_FORMS`, `hostEvalFormName()`, `isHostEvalForm()`, `eagerHostEvalInDo()`, `hostEval()`, `tryDirectSimpleNs()`, `normalizeHostInvokeArgs()`, `unquoteArg()`, `constantFormEntry()`).
- `**Clojure.parse()**`: Restructured to use `collectForm()` which selectively executes side-effecting forms (like `defmacro`, `ns`, `import`) eagerly via `truffleEval()` during parsing, wrapping their results as constants. Other forms are analyzed and compiled to **bytecode** roots wrapped in `SequentialFormNode`.
- `**CloffleCompiler.compile()**`: Uses `executeForm()` which does macroexpand → do-split → analyze → **ExprToBytecode** → execute for each top-level form. Side effects are visible between forms.
- `**Compiler.macroexpand()**`: Made `public` for cross-package access.

### Validation

- 405/405 tests passing
- Two `ns` tests (`simpleNsDirectPathStillProvidesCoreRefs`, `namespacedSimpleNsDirectPathStillProvidesCoreRefs`) were removed because they exercised the complex `with-loading-context` macro expansion that the Truffle converter couldn't handle at the time. The fn self-reference fix (above) likely resolves this; they can be re-added.

## Instance method classloader fallback (Mar 2026)

When compile-time `resolvedMethod.getDeclaringClass()` and the runtime receiver were loaded by different classloaders (e.g. pprint proxies), naive `Method.invoke` could fail. The fix re-resolves the method on `instance.getClass()` by name/signature, then falls back to `Reflector.invokeInstanceMethod` — same pattern previously used for protocol dispatch. Implemented on the **bytecode** instance-call path (the old `InstanceCallNode` was removed Apr 2026).

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

## Guest execution pipeline (Truffle Bytecode DSL)

`CloffleCompiler.compile()` / `executeForm()` and `Clojure.collectFormInner` run guest code as **`ExprToBytecode` → `CloffleBytecodeRootNode`**. Stock **ASM** in `Compiler` is still used where Clojure always did (`deftype`, `reify`, etc.); `FnExpr.parse()` emits JVM bytecode only in `NewInstanceExpr` contexts.

`Clojure.parse()` still builds a **`SequentialFormNode`** shell: each analyzed top-level form becomes a **bytecode** `CallTarget` wrapped for stepping/tags; the old `ExprToNode` interpreter is **gone** (Apr 2026).

## Replaced tools.analyzer.jvm with `Compiler.analyze()`

The original third-party analyzer + `AstBuilder` + 41 `*NodeBuilder` classes were replaced by **`Compiler.analyze()` → `Expr`**. That `Expr` tree is now lowered only through **`ExprToBytecode`** (the former `ExprToNode` → hand-written `ClojureNode` interpreter was removed).

**Why `Compiler.analyze()`:** single source of truth with Clojure’s compiler, no second analysis pass, smaller dependency surface.

**Pipelines:**

```
# Historical (removed Apr 2026)
Source → read → Compiler.analyze() → Expr → ExprToNode → interpreter ClojureNode graph

# Current
Source → read → Compiler.analyze() → Expr → ExprToBytecode → CloffleBytecodeRootNode
```

Fork visibility changes (~20 `Expr` types/fields `public`) were done for cross-package lowering; bytecode lowering continues to depend on them.

## Minimizing Clojure/Cloffle Divergence

Cloffle reuses as much of the standard Clojure runtime as possible. The guiding principle is to delegate to Clojure's own implementations wherever feasible, keeping Cloffle-specific code to the minimum needed for Truffle integration.

### deftype, reify, and `letfn*`

`Compiler.analyze()` still generates real JVM classes for `deftype` / `reify`. Guest instantiation and closed-overs are handled in **`ExprToBytecode`** (e.g. `NewInstanceExpr`, `NewExpr`), not the deleted `NewNode` / `LetFnNode` classes.

**`letfn*`:** mutual recursion is wired in bytecode via operations such as **`WireLetFnClosures`** (materialize frame + attach each `ClojureClosure`), matching the intent of the old `LetFnNode` + `ClojureRootNode.snapshotFrame` story.

The Proxy-based `ReifyNode` / `DefTypeNode` fallbacks remain deleted.

### Calls, `try`/`catch`, `set!`

Arity dispatch, `Throwable` catching for `(catch Throwable t ...)`, and `set!` targets are implemented in **`CloffleBytecodeRootNode`** / `ExprToBytecode`, not in removed nodes (`InvokeNode`, `TryNode`, `SetBangNode`).

### StaticInvokeExpr (direct linking disabled)

`StaticInvokeExpr` vs `InvokeExpr` and `clojure.compiler.direct-linking` are unchanged at the **Compiler** layer; guest calls resolve to `ClojureClosure` / `IFn`, not precompiled JVM `fn` classes.

## Implementation Details

### Compiler Entry Points

All Clojure compilation and evaluation now routes through Truffle:

- `**Compiler.compile()`** → delegates to `Compiler.compileCloffle()` → `CloffleCompiler.compile()` (binds compiler source vars and executes forms through Truffle)
- `**Compiler.load()**` → delegates to `CloffleCompiler.compile()`
- `**Compiler.eval()**` → delegates to `CloffleCompiler.executeForm()` (builds a source named from compiler bindings and executes through Truffle)
- `**Clojure.parse()**` → builds `SequentialFormNode` via `collectForm()`, with selective eager execution for side-effecting forms

### Core language support (bytecode backend)

Special forms and macro-expanded core shapes listed throughout this file are implemented in **`ExprToBytecode`** and **`CloffleBytecodeRootNode`** operations (literals, control flow, `loop*`/`recur`, `case*`, vars, `fn*` / call sites, Java interop, `try`/`catch`/`finally`, `locking` via `MonitorRegistry`, collections, metadata, `letfn*`, etc.). The old per-feature **`ClojureNode`** classes (`IfNode`, `InvokeNode`, …) were removed with the interpreter.

### ClassLoader Handling

`CloffleCompiler` and `Clojure.java` now correctly manage the Thread Context ClassLoader (TCCL) to ensure that dynamically generated classes (from `deftype`/`reify`) are visible during compilation and execution.

## tools.build: JUnit vs Clojure suite vs help

- `**run-tests`** — Cloffle **JUnit** tests only (Java test sources). Default `**:fresh true`**: runs `**clean**` first so stale `target` classes do not skew results; use `**:fresh false**` for incremental runs when appropriate.
- `**run-clj-tests**` — `**test/clojure/test_clojure/**` run **through Cloffle** (not the same as `run-tests`). Same default `**:fresh true`**. Use `**:only-namespace**` for a single namespace (e.g. `**clojure.test-clojure.pprint**` for a fast pprint-only run).
- `**help**` — `clj -T:build help` lists public `build.clj` tasks; `**help :verbose true**` prints full docstrings.

## Clojure Test Suite Compatibility (Mar 2026)

Clojure's own test suite (`test/clojure/test_clojure/`) is run through Cloffle via `clj -T:build run-clj-tests`. This executes 622 `deftest` forms containing **18,817** assertions through the Truffle pipeline.

### Current results

**622 `deftest`s, 18,817 assertions, 5 failures, 54 errors** (as reported by `clojure.test` and reflected in `target/surefire-reports/cloffle/TEST-results.xml`).

The **5** vs **54** split is JUnit/clojure.test terminology: **failures** are failed `is` assertions (`<failure>` in XML); **errors** are also failed assertions but reported as `<error>` (e.g. many `is` forms in one `deftest`). They are **assertion-level** counts, not 59 separate `deftest`s. **17** `deftest`s contain at least one bad assertion; the rest pass.

`clojure -T:build run-clj-tests` **fails the build** (non-zero exit) when any failure or error is present, and prints every failing `classname` / test name before throwing.

#### Interpreting the counts


| Metric                            | Value |
| --------------------------------- | ----- |
| `<failure>` elements in JUnit XML | 5     |
| `<error>` elements in JUnit XML   | 54    |
| `deftest`s with any failing `is`  | 17    |
| `deftest`s fully green            | 605   |


#### By namespace (assertion-level failures / errors)


| Namespace                              | Failures | Errors | `deftest`s affected | Notes                                                                                                                                             |
| -------------------------------------- | -------- | ------ | ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| `clojure.test-clojure.pprint`          | 0        | 25     | 4                   | `cl-format` / pretty-print layout (`angle-bracket-tests` 14, `cltl-angle-bracket-tests` 7, `cltl-up-tests` 3, `angle-bracket-max-column-tests` 1) |
| `clojure.test-clojure.clojure-walk`    | 0        | 8      | 1                   | `walk` — eight `is` forms on nested structures                                                                                                    |
| `clojure.test-clojure.vectors`         | 0        | 8      | 2                   | `test-vec-compare` (7), `test-primitive-subvector-reduce` (1)                                                                                     |
| `clojure.test-clojure.string`          | 0        | 7      | 2                   | `t-index-of` (4), `t-last-index-of` (3) — `StringBuilder` + char args                                                                             |
| `clojure.test-clojure.data-structures` | 0        | 3      | 1                   | `test-disj` — three cases expect `ClassCastException` on wrong collection types                                                                   |
| `clojure.test-clojure.ns-libs`         | 2        | 0      | 1                   | `test-defrecord-deftype-err-msg` — `CompilerException` / message expectations                                                                     |
| `clojure.test-clojure.agents`          | 1        | 0      | 1                   | `continue-handler` — `ArithmeticException` in agent error path                                                                                    |
| `clojure.test-clojure.java-interop`    | 1        | 0      | 1                   | `test-reify-to-FI-allowed` — functional-interface / `ClassCastException`                                                                          |
| `clojure.test-clojure.param-tags`      | 1        | 0      | 1                   | `no-param-tags-use-qualifier` — `ClassCastException` on date call                                                                                 |
| `clojure.test-clojure.errors`          | 0        | 1      | 1                   | `arity-exception`                                                                                                                                 |
| `clojure.test-clojure.other-functions` | 0        | 1      | 1                   | `test-every-pred`                                                                                                                                 |
| `clojure.test-clojure.streams`         | 0        | 1      | 1                   | `stream-seq!-test`                                                                                                                                |


#### The five failures (`<failure>` in JUnit XML)

All are single failed `is` assertions in their `deftest` (not thrown exceptions uncaught by the test runner):


| `deftest`                                                         | What the assertion checks                                                                                            |
| ----------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| `clojure.test-clojure.agents` / `continue-handler`                | Agent error ref holds an `ArithmeticException` (`instance?` / `second` of `deref err`).                              |
| `clojure.test-clojure.java-interop` / `test-reify-to-FI-allowed`  | `ClassCastException` when invoking a badly reified functional interface.                                             |
| `clojure.test-clojure.ns-libs` / `test-defrecord-deftype-err-msg` | Two assertions: `thrown-with-cause-msg?` / `CompilerException` text for invalid `defrecord` / `deftype` field specs. |
| `clojure.test-clojure.param-tags` / `no-param-tags-use-qualifier` | `ClassCastException` when calling a function with a `#inst` value.                                                   |


#### The 54 errors (`<error>` in JUnit XML) — grouped

Each row below is one failed `is` (JUnit reports it as an “error” node). Together they sum to **54**.

**A. Pretty-print / `cl-format` — 25 errors, 4 `deftest`s**


| `deftest`                        | #   | What is being compared                                                                                               |
| -------------------------------- | --- | -------------------------------------------------------------------------------------------------------------------- |
| `angle-bracket-tests`            | 14  | `cl-format` with `~<` / `~;` / `~>` (width, padding `@` / `:`, colinc, optional segments `~^`, string vs `~A` args). |
| `cltl-angle-bracket-tests`       | 7   | `format` with `~10<…~>` variants (foo/bar, foobar, colon/at modifiers).                                              |
| `cltl-up-tests`                  | 3   | `format` with `~15<~S~;…~>` vs `platform-newlines` expected columns.                                                 |
| `angle-bracket-max-column-tests` | 1   | Long wrapped comment block: `~%;; ~{~<~%;; ~1,50:; ~A~>~}.~%`                                                        |


**B. `clojure.walk` — 8 errors, 1 `deftest` (`walk`)**


| #   | Pattern                                                                                          |
| --- | ------------------------------------------------------------------------------------------------ |
| 4   | `(w/walk inc (fn* [x] (reduce + x)) coll)` vs `(reduce + (map inc coll))` on nested collections. |
| 4   | Walk with inner `update-in` / `vals` / `comp inc val` vs reference `reduce` on maps.             |


**C. Vectors — 8 errors, 2 `deftest`s**


| `deftest`                         | #   | Content                                                                                                                                                                  |
| --------------------------------- | --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `test-vec-compare`                | 7   | Each expects `thrown? ClassCastException` for `.compareTo` on a primitive `int` vector vs `()`, `{}`, `#{}`, `sorted-set`, `sorted-map`, another vector `nums`, and `1`. |
| `test-primitive-subvector-reduce` | 1   | `(== 60 (reduce + (subvec (vector-of :long) 10 15)))`.                                                                                                                   |


**D. `clojure.string` on `StringBuilder` — 7 errors, 2 `deftest`s**


| `deftest`         | #   | Content                                                                                                    |
| ----------------- | --- | ---------------------------------------------------------------------------------------------------------- |
| `t-index-of`      | 4   | `index-of` on `StringBuilder` `sb` with `\c`, `\o` from index, `\z` missing (with and without from-index). |
| `t-last-index-of` | 3   | `last-index-of` with `\n`, from-index, and missing `\z`.                                                   |


**E. `disj` / collections — 3 errors, 1 `deftest` (`test-disj`)**

Each expects `thrown? ClassCastException`: `disj` on list literal `(1 2)`, vector `[1 2]`, map `{:a 1}`.

**F. Small isolated cases — 3 errors, 3 `deftest`s**


| `deftest`                                                  | Content (first line of expectation)                                                                                     |
| ---------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| `clojure.test-clojure.errors` / `arity-exception`          | `macroexpand` of bad arity → `ArityException` with `.actual` field.                                                     |
| `clojure.test-clojure.other-functions` / `test-every-pred` | `reduce` of `and` over `(for [i (range 1 25)] (apply (apply every-pred (repeat i identity)) (range i)))` equals `true`. |
| `clojure.test-clojure.streams` / `stream-seq!-test`        | `(= 4950 (reduce + (stream-seq! l100)))`.                                                                               |


#### Error themes (for prioritization)

1. **Pretty-print** — just under half of all errors (25/54); CLTL-style format strings and column layout.
2. `**clojure.walk`** — one `deftest`, eight structural equalities.
3. **Primitive / `gvec` / `compareTo`** — seven `ClassCastException` expectations plus one numeric `reduce` over `subvec`.
4. **StringBuilder + char** — seven `index-of` / `last-index-of` cases.
5. `**disj` on non-set** — three `ClassCastException` expectations.
6. **Misc** — arity macroexpand, `every-pred` stress, `stream-seq!` sum.

Four additional namespaces (`data-structures-interop`, `parse`, `sequences`, `transducers`) pass but are excluded by default because they depend on `clojure.test.check` generative tests which are slow (~5 min). Include them with `clj -T:build run-clj-tests :generative true`.

### Excluded namespaces

These namespaces are excluded because they test JVM bytecode features that don't apply to Cloffle's Truffle execution model:


| Namespace                                      | Reason                                          |
| ---------------------------------------------- | ----------------------------------------------- |
| `clojure.test-clojure.compilation`, `.load-ns` | AOT compilation, class loading                  |
| `clojure.test-clojure.genclass`                | `gen-class` bytecode generation                 |
| `clojure.test-clojure.annotations`             | JVM annotation emission                         |
| `clojure.test-clojure.clearing`                | JVM local-clearing optimization, N/A in Truffle |
| `clojure.test-clojure.serialization`           | `ClojureClosure` is not `Serializable`          |


Generative test namespaces (excluded by default, pass when enabled):


| Namespace                                      | Tests | Assertions |
| ---------------------------------------------- | ----- | ---------- |
| `clojure.test-clojure.data-structures-interop` | 9     | 9          |
| `clojure.test-clojure.parse`                   | 6     | 54         |
| `clojure.test-clojure.sequences`               | 73    | 1,148      |
| `clojure.test-clojure.transducers`             | 19    | 108        |


### Disabled test assertions

Individual test assertions have been disabled (via `#_`) in test files where they rely on features Cloffle intentionally does not implement:


| Test file                    | Disabled test(s)                   | Reason                                                                                                                              |
| ---------------------------- | ---------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| `test_clojure/control.clj`   | 3 `testing` blocks in `test-case`  | JVM compiler "Performance warning" / "Reflection warning" diagnostics, N/A in Truffle                                               |
| `test_clojure/special.clj`   | `typehints-retained-destructuring` | `^String` on `:keys` symbols does not suppress reflection on interop calls in Cloffle (`GenericStaticCallNode`); JVM Clojure passes |
| `test_clojure/protocols.clj` | `test-longs-hinted-proto`          | Requires `IFn$OL` primitive interface; Truffle handles primitives via PE                                                            |


## Recent Compatibility Fixes

Several concrete Clojure/Cloffle divergences were found with paired regression tests and then fixed in the runtime:

- `**letfn` mutual recursion:** added `LetFnNode`, which constructs all local closures before capturing the final shared lexical environment.
- `**reify` closed-overs:** `ExprToNode.convertNewInstance()` now threads `NewInstanceExpr.closesExprs` into `NewNode`, fixing `reify` instances that capture locals.
- **Protocol dispatch:** protocol call analysis is enabled in the Cloffle compiler bindings, and `ProtocolInvokeNode` now uses the analyzer-provided protocol metadata plus a reflective fallback to survive interface/classloader identity mismatches.
- **Exception identity on the compiler path:** uncaught exceptions now escape as the original Java throwable instead of being rewritten as `ClojureException` or `RuntimeException(e)`. `TryNode` still unwraps `ClojureException` defensively for matching, but the direct `CloffleCompiler` path now preserves exact exception type/message more closely. The `Context.eval` polyglot boundary still surfaces uncaught failures as `PolyglotException`, which is expected on the Graal polyglot API.
- **Primitive-hinted numeric coercion:** explicitly hinted primitive params (`^long`, `^double`) now use `RT.longCast` / `RT.doubleCast` semantics for primitive slot writes and rebinding, restoring Clojure-compatible coercion and overflow checks.
- **Nil consistency across Truffle/Java boundary:** `NilNode.NIL` sentinels are unwrapped to Java `null` at all collection boundaries (`VectorNode`, `MapNode`, `SetNode`, `ListNode`), `DefNode.bindRoot()`, `InvokeNode` argument passing, and `CaseNode` comparison. `ProtocolInvokeNode` null-checks the dispatch target before attempting reflection.
- `**CaseNode` hash collision handling:** `ExprToNode.convertCase()` extracts `CaseExpr.skipCheck` and passes a `boolean[]` to `CaseNode`. For entries where `skipCheck` is true (hash collisions like `hash(0) == hash(-1)`), the node dispatches by hash match to the compiler-generated `condp` branch. Otherwise it uses `Util.equiv()`.
- `**char` frame slot kind:** `ExprToNode.slotKindForClass()` no longer maps `char.class` to `FrameSlotKind.Long`. Characters are stored as `Object` to preserve their `Character` type.
- `**MetaExpr` → `WithMetaNode`:** A new Truffle node correctly applies metadata at runtime via `IObj.withMeta()`, replacing the previous no-op that dropped metadata.
- `**InstanceFieldNode` `requireField` flag:** The `-field` syntax (`(. obj -field)`) now correctly prefers fields over methods by passing `requireField=true` to `Reflector.invokeNoArgInstanceMember()`.
- `**NewNode` compile-time constructor:** Uses the compile-time resolved `Constructor` from `NewExpr` with `Reflector.boxArgs()` instead of runtime `Reflector.invokeConstructor()`, eliminating ambiguous constructor resolution.
- `**PersistentHashSet` empty singleton:** All `create()` and `createWithCheck()` factory methods now return `PersistentHashSet.EMPTY` for empty input, ensuring `#{}`-literal identity consistency required by `sorted-set-by` comparators.
- `**ClassCastException` for type mismatches in compiled code:** `GenericStaticCallNode`, `InstanceCallNode`, and `NewNode` catch `IllegalArgumentException` from `Reflector.boxArgs()` and rethrow as `ClassCastException`, matching JVM `checkcast` semantics. The reflection-level `Reflector.boxArg()` retains `IllegalArgumentException` for callers like `Reflector.invokeConstructor()` used by the reader.
- `**DynamicClassLoader` class bytes cache:** `defineClass()` now retains a soft-referenced copy of class bytes, and `getResourceAsStream()` is overridden to serve them. Combined with a context-classloader fallback in `ClassReader(String)`, this allows `clojure.asm.ClassReader` to inspect in-memory classes (e.g., proxy classes).
- `**convertNewInstance` deftype detection:** `ExprToNode.convertNewInstance()` now uses `e.isDeftype()` (which checks `fields != null`) instead of `e.hintedFields.count() > 0`. This fixes `defrecord` with zero user fields (e.g., `(defrecord Foo [])`) which was incorrectly taking the reify path and failing on uninitialized `__meta` frame slots.
- `**ClojureClosure.applyTo()` lazy rest args:** `applyTo()` no longer calls `RT.seqToArray()` which would realize infinite sequences. For variadic functions, it extracts the required positional args and wraps the remaining `ISeq` in a `RestArgs` sentinel. `VariadicArgInitNode` recognizes the sentinel and uses the lazy seq directly, allowing `(apply f (range))` to work without hanging.
- `**invokePrim` rewrite removed from `Compiler.java`:** When the Clojure compiler saw a call to a function whose arglist had `^long`/`^double` type hints, it rewrote the call from `(f arg)` to `(.invokePrim ^IFn$LO f arg)`, producing an `InstanceMethodExpr` that cast the function to a primitive IFn interface. `ClojureClosure` doesn't implement these interfaces (`IFn$LO`, `IFn$OL`, `IFn$LL`, etc.) because Truffle handles primitive specialization via Partial Evaluation and frame slot specialization. The entire rewrite block in `InvokeExpr` analysis has been removed. This unblocked four test namespaces (`data-structures-interop`, `parse`, `sequences`, `transducers`) that depend on `clojure.test.check`, which internally uses `^long`-hinted functions.
- `**clojure.pprint` require in `transducers.clj`:** The test file referenced `clojure.pprint/pprint` without requiring the namespace. In standard Clojure, `clojure.pprint` is AOT-compiled and auto-resolves; in Cloffle, it must be explicitly loaded. Added `[clojure.pprint]` to the `ns` `:require` form.

These fixes are covered by explicit compatibility tests in `CloffleReproTest` in addition to the broader paired behavior suite. Coverage was also expanded for direct compiler-path `deftype`/protocol dispatch (`AdvancedFeaturesTest`), direct compiler-path primitive-hint coercion (`CloffleCompilerTest`), and polyglot-boundary exception message/type reporting (`CloffleReproTest`).

## No Munge/Demunge for Function Names (Mar 2026)

In standard Clojure, function names are *munged* into valid Java class names (`:` → `_COLON_`, `+` → `_PLUS_`, etc.) because each function compiles to a JVM class. Error messages then *demunge* them back for display. In Cloffle, functions are `ClojureClosure` objects with Truffle `CallTarget`s — there are no compiled classes, so the munge/demunge cycle is unnecessary.

### What changed

- `**ArityException`**: No longer calls `Compiler.demunge(name)` in its constructor. The name is used as-is.
- `**AFn.throwArity()**`: Now demunges `getClass().getName()` before passing to `ArityException`, since compiled `IFn` implementations (like `Keyword`, core functions loaded from JARs) still have munged class names.
- `**ExprToNode.convertDef()**`: Sets the `FnNode` name to the clean namespace-qualified Clojure name (e.g., `clojure.core/assoc`, `user/f2:+><->!#%&*|b`) directly from `Var.ns.name` and `Var.sym`. Handles `WithMetaNode` wrapping via `extractFnNode()`.
- `**ExprToNode.convertFn()**`: Uses `thisName` directly (the original Clojure symbol name) for self-referencing functions. The `extractFnName()` method (which reverse-engineered names from munged `compiledName` via `Compiler.demunge()`) was deleted.
- `**Compiler.macroexpand1()**`: The ArityException name comparison now checks both the clean qualified name (`ns/sym`) and the munged class name (`munge(ns)$munge(sym)`) to handle exceptions from both Truffle-compiled and JAR-loaded functions. `extractArityException()` walks the cause chain to find `ArityException` inside `ClojureException` wrappers.
- `**InvokeNode.invokeGeneric()**`: `ArityException` from compiled `IFn` implementations is wrapped in `ClojureException`; `Compiler.macroexpand1()` handles this via `extractArityException()` walking cause chains.

### Source line metadata preservation

`CloffleCompiler.executeForm()` transfers `:line`/`:column` metadata from the original reader form onto the macroexpanded form before passing it to `Compiler.analyze()`. This ensures `analyzeSeq()` picks up correct source locations for var definitions, fixing `source-fn` and stack trace line numbers.

Previously, `executeForm()` passed the fully macroexpanded form to `analyze()`, which had lost the reader's line metadata. The macroexpansion is still performed first (for `do`-splitting of `ns` expansions), but the original form's positional metadata is now grafted onto the expanded result.

## Modifications to upstream Clojure classes

Changes to `src/jvm/clojure/lang/` fall into three categories:

**Visibility and delegation (Compiler.java):** ~22 inner `Compiler.Expr` classes and ~20 fields/methods changed from package-private to `public` so that Truffle lowering (`ExprToBytecode`, etc., in a different package) can read the `Expr` AST. `macroexpand()` made public. `eval()` delegates to `CloffleCompiler.executeForm()`. `load()` delegates to `CloffleCompiler.compile()`. `FnExpr.parse()` conditionally skips bytecode generation. `evalWithLegacyBytecode()` and `evalWithTruffle()` removed. `StaticInvokeExpr` given a `public final Var var` field. `macroexpand1()` calls `checkSpecsAt` before Truffle macro expansion (see **Spec `macroexpand-check`**), and is enhanced with `extractArityException()` for Truffle exception unwrapping and a `List<String> trail` parameter for macro expansion chain tracking. `makeMacroCompilerException()` helper added for formatting trail into `CompilerException` messages. `ObjExpr.isDeftype()` made `public`. `FISupport` class and `maybeFIMethod()` made `public`. The `invokePrim` rewrite in `InvokeExpr` analysis removed (see below).

**ArityException (ArityException.java):** No longer calls `Compiler.demunge(name)` — the name is passed through as-is. Callers are responsible for providing a display-ready name.

**AFn.java:** `throwArity()` now calls `Compiler.demunge(getClass().getName())` before constructing `ArityException`, since compiled `IFn` classes still have munged names.

**FnInvokers.java:** `encodeInvokerType()` made `public` for functional-interface adaptation paths used from bytecode lowering.

**DynamicClassLoader.java:** `defineClass()` stores a soft-referenced copy of class bytes in `classBytesCache`. `getResourceAsStream()` overridden to serve cached bytes for in-memory-defined classes. `findClassBytes()` added for static lookup.

**ClassReader.java (clojure.asm):** `ClassReader(String)` constructor falls back to `Thread.currentThread().getContextClassLoader().getResourceAsStream()` when `ClassLoader.getSystemResourceAsStream()` returns null, allowing inspection of in-memory classes defined by `DynamicClassLoader`.

**PersistentHashSet.java:** All `create()` and `createWithCheck()` factory methods (6 overloads) return `PersistentHashSet.EMPTY` singleton for empty input.

**Truffle interop annotations (8 files):** `AFn`, `APersistentMap`, `APersistentSet`, `APersistentVector`, `ASeq`, `Keyword`, `LazySeq`, `Symbol`, and `Var` implement `TruffleObject` and export `InteropLibrary` messages. This makes Clojure data types first-class polyglot citizens on GraalVM without changing their Clojure-side semantics.

**JDK modernization (RT.java):** Removed deprecated `SecurityManager` and `ThreadDeath` from default imports, removed `AccessController.doPrivileged` wrapper in `makeClassLoader()` (deprecated since Java 17, removed in Java 24).

**Spec / `macroexpand-check` (RT.java):** `RT.CHECK_SPECS` stays `false` during bootstrap, then after `RT.doInit()` remains `false` unless `-Dclojure.spec.check-specs=true` (Cloffle defaults macro spec checks off; enable to mirror stock Clojure). Implementation details: **Spec `macroexpand-check`** below.

## Deleted dead code

- **Apr 2026 — full Truffle AST interpreter:** `ExprToNode.java` and the large `ClojureNode` interpreter hierarchy (`FnNode`, `InvokeNode`, `LetNode`, `BindingNode` DSL, `vars/*`, `invoke/*`, `staticcall/*`, …), plus AST-only tests and `CloffleNodeBenchmark`. See **Full Truffle AST interpreter removal (Apr 2026)** at the top of this file.
- `**HostInteropNode**` — never wired in; interop lives in bytecode operations.
- `**ReifyNode`, `DefTypeNode**` — Proxy fallbacks; superseded by `Compiler`-generated classes + bytecode `New*` handling.
- `**LegacyInvokeNode`, `LegacyFnMethodNode**` — removed benchmarking leftovers.
- `**UnaryStaticCallNode`, `BinaryStaticCallNode`, `AbstractStaticCallNode**` — replaced by generic static-call paths in bytecode lowering.
- `**AstBuilder`, `*NodeBuilder**` — old `tools.analyzer.jvm` pipeline.
- `**evalWithLegacyBytecode`, `evalWithTruffle**` — removed from `Compiler.java`.
- **hostEval infrastructure** — removed from `Clojure.java`.
- `**ExprToNode.extractFnName()`** — removed with `ExprToNode`; naming is handled without demunge-based recovery.

## Compile-time vs Runtime Evaluation Discrepancy Fix — historical

`ListExpr`, `QualifiedMethodExpr`, and similar shapes used to be mis-handled when lowering assumed compile-time `eval()` instead of runtime code. Fixes were applied in **`ExprToNode`** (runtime `ListNode`, thunk/`buildThunk`, `convertHostEval` fallback). **Today** the same `Expr` types must be correct in **`ExprToBytecode`**; the interpreter nodes named here are deleted.

## Compatibility Testing Framework

A regression testing framework was added to verify Cloffle against popular 3rd-party Clojure libraries. These tasks (`compat-check`, `compat-test`) are retired in Truffle-only mode but the infrastructure remains.

### Verified Projects (historical)


| Project         | Tests | Status   | Notes                                                                             |
| --------------- | ----- | -------- | --------------------------------------------------------------------------------- |
| **Cheshire**    | 116   | **PASS** | JSON encoding/decoding. Includes generative tests.                                |
| **Ring (Core)** | 190   | **PASS** | Web library. Includes middleware, cookies, sessions. (2 failures match baseline). |
| **Compojure**   | 21    | **PASS** | Routing library.                                                                  |
| **clj-http**    | 196   | **PASS** | HTTP client. (1 error matches baseline).                                          |
| **Hiccup**      | 64    | **PASS** | HTML generation library.                                                          |


# GraalVM Specific Optimizations in Cloffle

## `@ExplodeLoop` for Argument Evaluation — historical note

The **removed** interpreter used `@ExplodeLoop` on arg-evaluation loops in nodes such as `InvokeNode` / `FnMethodNode`. The **bytecode** backend uses different loop shapes inside `CloffleBytecodeRootNode` / `ExprToBytecode`; Graal still applies PEA where the generated graphs allow.

## Primitive Frame Slot Kinds

`**ExprToBytecode**` (and frame builders shared with bytecode locals) consults `LocalBinding.getPrimitiveType()` from the analyzer. Primitive slots use the appropriate `FrameSlotKind` (`Long`, `Double`, `Boolean`) where applicable instead of staying `Illegal` forever, reducing deopt on first write.

One subtle compatibility bug was found here: primitive-hinted function params were originally using Java-style `longValue()` / `doubleValue()` coercion, which diverged from Clojure for values like `Ratio` and out-of-range `BigInt`. The runtime now uses Clojure's own `RT.longCast` / `RT.doubleCast` rules for primitive slot writes and rebinds.

Another fix: `char.class` is no longer mapped to `FrameSlotKind.Long`. Characters are stored as `Object` to preserve their `Character` type and avoid incorrect coercion to `long` in `case` comparisons.

## Benchmarks

- `NamespaceBenchmark.java` — var resolution through the polyglot `Context.eval` path.
- `StubBenchmark.java` — baseline polyglot boundary overhead.
- (Removed Apr 2026) `CloffleNodeBenchmark` — depended on deleted AST nodes.

# Potential Future Improvements

Performance-related ideas that have been analyzed but not yet implemented, to avoid increasing Clojure/Cloffle divergence prematurely.

## `case*` O(1) dispatch (bytecode)

Bytecode lowering for `case*` may still use a linear scan with `Util.equiv()` per branch (plus `skipCheck` collision handling). `Compiler.analyze()` already computes `shift`, `mask`, `low`, `high`, `switchType`, and `testType` on `CaseExpr` for hash-based or table-switch dispatch:

- For `testType == intKey` + `switchType == compactKey`: use an array-indexed lookup (table switch).
- For hash-based `testType`: use `(hash(value) >> shift) & mask` to index into a lookup table, with `skipCheck` fallback for collisions.

This would be the highest-impact single optimization for `case`-heavy code, but adds Cloffle-specific logic. Truffle/Graal's PE may handle the linear scan adequately for small case counts.

## ClojureClosure Functional Interface Adapters (Implemented)

Clojure 1.12's functional interface adaptation is now fully supported. When a Clojure function (`IFn`) is type-hinted to a `@FunctionalInterface`, Cloffle generates an adapter at runtime that implements the target interface and delegates to the function's `invoke` method.

**How it works:**

- `ExprToBytecode` detects FI type hints on `let` bindings (`LocalBinding.tag`), method/ctor arguments (`Method.getParameterTypes()`), etc.
- When a target class is `@FunctionalInterface` (detected by `Compiler.FISupport.maybeFIMethod()`), lowering emits the same adapter path the old `FIAdapterNode` implemented.
- At runtime, the adapter checks: if the value already implements the target FI, it passes through unchanged. Otherwise, it creates an adapter.
- Adapters are created via `LambdaMetafactory.metafactory()` when classloader access permits (using `privateLookupIn` for the target FI class). For dynamically-loaded interfaces (e.g., `definterface` classes in `DynamicClassLoader`), falls back to `java.lang.reflect.Proxy`.
- `FnInvokers` static methods provide the delegation bridge, handling primitive boxing/unboxing for arities 0-2 and all-Object dispatch for 3-10.
- FIs with > 10 parameters are rejected by `maybeFIMethod` (matching Clojure's behavior). Attempting to call methods on an un-adapted function produces `ClassCastException`.

Instance interop validates receiver types so JVM reflection errors match `invokevirtual`-style `ClassCastException` where appropriate (see instance-call lowering / `Reflector` paths).

## ClojureClosure Arity Metadata

`ClojureClosure` stores `requiredArity` and `isVariadic`, set when the closure is created from bytecode (same fields the old `FnNode` path set). This enables:

- **Lazy `applyTo()`**: Variadic functions avoid realizing infinite sequences by passing the rest as an `ISeq` wrapped in a `RestArgs` sentinel, rather than calling `RT.seqToArray()`.
- **Non-variadic `applyTo()`**: Delegates to `AFn.applyToHelper()` which is bounded and safe.

## Spec `macroexpand-check` (Mar 2026)

**Current policy:** `RT.CHECK_SPECS` is `static volatile`, starts `false`, and after `RT.doInit()` is set from `-Dclojure.spec.check-specs` (`Boolean.getBoolean`, default **off**). Use `-Dclojure.spec.check-specs=true` to enable macro spec checks (stock Clojure behavior). `run-clj-tests` / compat-test phase 2 pass this flag so `test_clojure` stays aligned with upstream.

Clojure 1.10+ validates many core macro invocations against `clojure.core.specs.alpha` **before** macro expansion by calling `clojure.spec.alpha/macroexpand-check` from `Compiler.macroexpand1`. Cloffle's `Compiler` contains the same guarded hooks.

### `RT.java`

- `CHECK_SPECS` — `static volatile`, `false` during bootstrap; after `doInit()`, `true` only if `-Dclojure.spec.check-specs=true`.

### `Compiler.java`

- Lazy `MACRO_CHECK` / `ensureMacroCheck()` — loads `clojure/spec/alpha` and `clojure/core/specs/alpha`, resolves `clojure.spec.alpha/macroexpand-check`, guarded by `MACRO_CHECK_LOADING` to avoid re-entrancy while namespaces load.
- `checkSpecsAt(v, form, formLine, formCol)` — when `RT.CHECK_SPECS` is true, invokes `macroexpand-check` with the same `applyTo` shape as upstream; failures become `CompilerException` with phase `**:macro-syntax-check`**, using **form metadata** `:line`/`:column` when already computed for the macro form.
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

- `RT.checkSpecAsserts` remains `**false**` hardcoded (JVM uses `-Dclojure.spec.check-asserts=true`). Runtime `s/assert` parity is separate from `macroexpand-check`.
- No dedicated JUnit tests for `macroexpand-check`; coverage is the vendored `test_clojure` namespaces above.

## Typed protocol fast path — historical `ProtocolInvokeNode`

The old `ProtocolInvokeNode` consumed `protocolOn` / `onMethod` and used direct interface dispatch with a reflective fallback for classloader mismatches. **Today** the same metadata must be honored in **`ExprToBytecode`** / bytecode call operations; the AST node is gone.

## Primitive execution / autoboxing — historical AST note

The **removed** interpreter specialized `executeLong` / `executeDouble` / `executeBoolean` on chains through `IfNode`, `LetNode`, `DoNode`, `CaseNode`, `TryNode`. **Bytecode** lowering uses different representation; Graal may still unbox through partial evaluation. Regression coverage: `CloffleCompilerTest`, `CompilerTypeHintAnalysisTest`, and bytecode DSL tests — not `ExprToNodeLocalBindingSlotTest` (deleted Apr 2026).

## Extended Fn Param Primitive Hints: `int` / `float` / `boolean` (Mar 2026)

Function parameter hints now accept `^int`, `^float`, and `^boolean` in addition to `^long` and `^double`.

### What changed

- `**Compiler.FnMethod.classChar**`: still emits primitive IFn signatures only for `long` (`L`) and `double` (`D`), but no longer throws for other primitive hints.
  - For primitive hints without an IFn primitive family (`int` / `float` / `boolean`), it now returns `'O'` so those params use object-family IFn signatures while remaining primitive-typed in analyzer metadata.
- `**Compiler.FnMethod.parse**`: removed the parser-time guard that rejected non-`long`/`double` primitive params.
  - `MethodParamExpr`/`LocalBinding.getPrimitiveType()` now carry `int.class`, `float.class`, and `boolean.class` for hinted fn params.

### Truffle-side specialization behavior

Bytecode / frame lowering maps these primitive classes to Truffle frame kinds (same table the old `ExprToNode.slotKindForClass` used): `int`→`Long`, `float`→`Double`, `boolean`→`Boolean` slot kinds, without new IFn primitive interface families.

### Important caveat

- IFn primitive interface families remain only `L`/`D`-based.
- Existing `long`/`double` primitive-fn constraints remain (for example, long/double primitive-family functions are still not variadic).

### Test coverage

- `**clojure.lang.CompilerTypeHintAnalysisTest**` (including `analyzeFnPrimitiveIntFloatBooleanParameters`).
- (Removed Apr 2026) `ExprToNodeTypeHintPropagationTest` — deleted with `ExprToNode`.

```bash
clojure -T:build run-tests :args '["--select-class=clojure.lang.CompilerTypeHintAnalysisTest"]'
```

## Type specialization via `getJavaClass` / `hasJavaClass`

`Compiler.Expr` still carries `getJavaClass()` / `hasJavaClass()`. Further use (primitive invoke, `case*` return typing) belongs in **`ExprToBytecode`**, not the removed AST converter.

## Tail-Call Optimization via tailPosition

`InvokeExpr.tailPosition` indicates calls in tail position. This could drive TCO (e.g., via `TailCallException`) for non-`recur` tail calls, reducing stack depth for mutually recursive functions.

## @ExplodeLoop on `case*` — idea

The old `CaseNode` `@Children` loop was a candidate for `@ExplodeLoop`. Bytecode-generated `case*` dispatch may benefit from similar unrolling hints inside `ExprToBytecode` / the DSL if we add them explicitly.

## Transitive Bytecode Cache (per-file `.bc` archives)

A per-file bytecode cache that eliminates source parsing/compilation at runtime for all Clojure standard library namespaces. During a dump phase, each `.clj` file is compiled from source and its Truffle bytecode is serialized into a `.bc` file that sits alongside the `.clj` on the classpath. At runtime, `RT.loadResourceScript` checks for a `.bc` resource on the classpath first and replays the pre-compiled bytecode instead of parsing source.

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

- `RT.loadResourceScript(Class, String, boolean)` looks for a `.bc` resource on the classpath corresponding to the requested `.clj` name (e.g., `clojure/set.clj` → `clojure/set.bc`).
- If found, `CloffleCoreBytecodeArchive.replayArchive(stream, label, sourcePath, sourceName)` replays the pre-compiled bytecode instead of parsing source.
- If no `.bc` resource exists, falls through to normal source loading.
- `replayArchive` accepts arbitrary `sourcePath`/`sourceName` parameters for compile-frame bindings, making it generic (not hardcoded to `core.clj`).

**Replay logging:**

- Per-file log messages are suppressed. Instead, an `AtomicInteger` depth counter tracks nested replays.
- When the outermost replay completes (depth returns to 0), a single summary line is printed: `[Cloffle] Bytecode cache: loaded N files (M forms) in X ms`.

### Wire format (CFBC)

Each `.bc` file uses the same format as the single-file core archive:


| Field          | Type     | Description                                              |
| -------------- | -------- | -------------------------------------------------------- |
| Magic          | `int`    | `0x43464243` ("CFBC")                                    |
| Version        | `int`    | Format version (currently 1)                             |
| Form count     | `int`    | Number of top-level forms                                |
| For each form: |          |                                                          |
| — Chunk length | `int`    | Byte length of the serialized `BytecodeRootNodes`        |
| — Chunk data   | `byte[]` | `CloffleBytecodeSerialization.serializeRootNodes` output |


### Source optimization in serialization

`CloffleBytecodeSerializer` writes only a single-space placeholder for `Source` content instead of the full file text. This avoids quadratic growth: without the optimization, every per-form chunk redundantly embedded the entire source file (e.g., `core.clj` is ~300KB × 879 forms = ~260MB). The replay side provides its own compile-frame bindings and does not need the original text.

### Build tasks

```bash
# Dump all .bc files into target/classes (52 files for the full standard library)
clj -T:build dump-bytecode-cache
clj -T:build dump-bytecode-cache :output '"out/bc-cache"' :xmx '"12g"'

# REPL — .bc files in target/classes are on the classpath automatically
clj -T:build cloffle-repl
```

Since `.bc` files are written to `target/classes` (the default output), they sit alongside compiled `.class` files and forked `.clj` sources, and are automatically on the classpath for all build tasks.

### Docker images

The root `**Dockerfile**` (`make docker-build-cloffle-repl`) does **not** run the versioned `clojure-*.jar` at runtime. It copies `**target/`** into the image and starts the REPL with the same classpath shape as `**clj -Spath -M:cloffle-java**` (see `Makefile` `runtime_cp`): `target/classes`, `src/clj`, Truffle JARs from the copied Maven repo, etc. `**compile-all` alone leaves `target/classes` without any `.bc` files**, so `RT.loadResourceScript` falls back to loading `.clj` from `src/clj` only. The Docker build must run `**clojure -T:build dump-bytecode-cache`** (or equivalently `**jar**`, which invokes the dump step before packaging) so per-file caches exist under `target/classes` before the image is assembled.

`**Dockerfile.jlink**` uses `**clj -T:build build-jar**`, which already runs the full `**jar**` task (including `dump-bytecode-cache`); bytecode lives inside `**cloffle.jar**` on that image’s classpath.

### Runtime properties


| Property                        | Description                                                                                                                |
| ------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| `cloffle.core.bytecode.archive` | Path to a single monolithic `core.bc` archive (the older mechanism). Checked by `RT.init()` before `load("clojure/core")`. |
| `cloffle.core.bytecode.quiet`   | Set to `true` to suppress `[Cloffle]` log output.                                                                          |


The per-file `.bc` cache no longer needs a system property — `.bc` files are discovered automatically from the classpath.

### Startup behavior

When `.bc` files are on the classpath, `RT.init()` loads 11 files from bytecode at startup — all part of `clojure.core`'s irreducible bootstrap set:

- `core.clj` (879 forms)
- `core_proxy.clj`, `core_print.clj`, `genclass.clj`, `core_deftype.clj`, `core/protocols.clj`, `gvec.clj` — via `(load ...)` in `core.clj`
- `instant.clj`, `uuid.clj` — via `(load ...)` in `core.clj`
- `java/io.clj` — via `(require '[clojure.java.io :as jio])` in `core.clj`
- `string.clj` — transitive dependency of `java/io.clj`

All other namespaces (`clojure.set`, `clojure.pprint`, `clojure.test`, etc.) load from bytecode on demand when `require`d or `use`d.

### DCL class embedding

Compiler-generated classes (`fn`, `reify`, `deftype` implementations) defined in `DynamicClassLoader` during source compilation are serialized via `TYPE_CLASS_DCL` in `CloffleBytecodeSerializer`. During deserialization, `CloffleBytecodeDeserializer` defines these classes in the target JVM's `DynamicClassLoader`, allowing a cold JVM to replay without having generated the classes locally.

### Test coverage

- `BytecodeSerializationRoundTripTest.freshJvmBootstrapsAllNamespacesFromBytecodeCache` — dumps the transitive bytecode cache via the recorder, then forks a fresh JVM with `.bc` files on the classpath to verify cold bootstrap succeeds and `(+ 1 2) = 3`.
- `BytecodeCacheBootstrapMain` — the forked JVM entry point that calls `RT.init()`, resolves `clojure.core/+`, and evaluates `(+ 1 2)`.

### Centralized debugger tag policy (`BytecodeTagPolicy`)

Debugger line breakpoints previously fired on `defn` definition lines at load time and could halt multiple times on the same runtime line (e.g., `(run 11)` stopped three times). This was because two independent layers — `TopLevelEvalNode` (AST wrapper) and the inner bytecode root's `emitWithLineColumnSection` — both applied `StatementTag` to overlapping source sections.

**Root cause:** Truffle line breakpoints match *all* nodes with `StatementTag` whose source section overlaps the line. With two layers of `StatementTag` on the same line, `Resume` just hit the second one. Additionally, `emitDefExpr` unconditionally applied `StatementTag` to `defn` heads, causing definition-time halts that don't match Java/Python/JS debugger behavior.

**Solution:** A centralized `BytecodeTagPolicy` class now encodes all tagging decisions:

| File | Change |
|------|--------|
| `bytecode/BytecodeTagPolicy.java` | New file. `FormKind` enum (`FN_DEFINITION`, `SIMPLE_DEF`, `CALL`, `COMPOUND`, `LITERAL`), `classify()`, `isRuntimeStatement()`, `defHeadIsStatement()`, `inhibitDefInitTags()`, `inhibitCalleeArgTags()`. |
| `nodes/TopLevelFormEntry.java` | Added `boolean isRuntimeStatement` field. |
| `nodes/SequentialFormNode.java` | `TopLevelEvalNode.hasTag()` now conditionally reports `StatementTag` based on `isRuntimeStatement`. Definitions → no `StatementTag` → invisible to debugger. |
| `Clojure.java` | Removed single-form fast path; all top-level forms go through `SequentialFormNode`. Passes `inhibitRootStatementTag = true` to `convertRoot` so the inner bytecode's outermost `StatementTag` is suppressed (provided by `TopLevelEvalNode` instead). |
| `bytecode/ExprToBytecode.java` | `emitDefExpr` uses `BytecodeTagPolicy.defHeadIsStatement()` for conditional `StatementTag`. `convertCalleeOrArgForInvoke` delegates to `BytecodeTagPolicy.inhibitCalleeArgTags()`. New `skipNextStatementTag` flag consumed once by `emitWithLineColumnSection`/`emitDefExpr` to suppress the root-level duplicate. |
| `DebuggerTest.java` | `breakpointOnDefnFires` → `breakpointOnDefnDoesNotFire`. Step-out tests updated to BP on call lines. New `breakpointOnCallAfterDefnsStopsOnce` test. |

**Key architectural decisions:**

1. **`TopLevelEvalNode` is the sole `StatementTag` owner for runtime forms in multi-form scripts.** The inner bytecode root's outermost `StatementTag` is suppressed via `skipNextStatementTag`, preventing duplicate breakpoint halts.
2. **All top-level forms go through `SequentialFormNode`** (the single-form fast path was removed). This ensures consistent tagging — `TopLevelEvalNode` always wraps every form, and `inhibitRootStatementTag` can safely suppress the inner bytecode's `StatementTag` without risk of leaving a form with zero `StatementTag` nodes.
3. **`defn`/`defmacro` forms get zero `StatementTag` at both layers** — `TopLevelEvalNode` reports `isRuntimeStatement = false` and `emitDefExpr` returns `defHeadIsStatement = false`. This makes definitions invisible to line breakpoints and step-over, matching Java/Python/JS UX.

### DAP startup source attribution + call-stack preservation (Apr 2026)

Follow-up debugger work focused on two runtime UX issues:

1. **Suspend-on-start opened/generated source instead of user script line**
   - Eager setup forms (`ns`/`require`/`in-ns`) were evaluated from macroexpanded text paths that could surface as synthetic editor buffers or map to surprising end-of-file lines on first suspend.
   - Runtime fix:
     - Eager setup forms are no longer emitted as runtime top-level statements in the `SequentialFormNode` wrapper.
     - Eager-eval compilation supports suppressing statement tags for internal setup roots.
     - Eager roots use narrowed source spans so debugger location anchors stay on the actual form span instead of whole-file fallback.
     - When a real script `Source` exists, eager setup evaluation keeps that source attribution rather than forcing synthetic file identity.

2. **Leaf breakpoint stack only showed callee (missing caller chain)**
   - User function invocation in bytecode `Invoke` previously flowed through generic `IFn.invoke(...)`, which could reduce visible guest-to-guest call edges in debugger stacks.
   - Runtime fix:
     - `CloffleBytecodeRootNode.Invoke` now specializes `ClojureClosure` calls through Truffle call nodes (`DirectCallNode` cached + `IndirectCallNode` fallback), preserving call boundaries for stack reconstruction.
     - Non-closure `IFn` values continue using the previous invoke path.
