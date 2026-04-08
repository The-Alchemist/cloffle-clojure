# Cloffle Error Messages & Stack Traces — Improvement Log

## Session 1: Source Section Coverage + Exception Wrapping

### What was done

#### 1. Expanded source section coverage in `ExprToNode.java`

Added `line`/`column` fields to 6 Expr types in `Compiler.java` that previously
had no source location, then handled them in `applySourceFromExpr()`:

| Expr Type | Node Type | Why it matters |
|-----------|-----------|----------------|
| `VarExpr` | `VarNode` | "Unable to resolve symbol" errors now have location |
| `LocalBindingExpr` | `LocalNode` | Local variable access errors get frames |
| `LetFnExpr` | `LetFnNode` | `letfn` binding errors get location |
| `ImportExpr` | `ImportNode` | "Cannot import class" errors get location |
| `AssignExpr` | `SetBangNode` | `set!` errors get location |
| `ListExpr` | `ListNode` | Quoted list construction errors get location |

Also refactored `applySourceFromExpr()` — extracted `extractLineColumn()` to
eliminate the repetitive instanceof-cascade. Grouped by category (invocation,
control flow, definitions, vars, bindings, interop, collections, etc.).

#### 2. Wrapped exceptions in call nodes

Raw Java exceptions (ClassCastException, ArithmeticException, NPE, etc.)
from native Clojure functions were propagating as "Internal errors" with
zero guest stack frames. Now they're wrapped in `ClojureException` so Truffle
tracks them as guest exceptions with source location.

| Node | What was wrapped |
|------|-----------------|
| `NativeCallNode` | IFn.invoke() exceptions |
| `InstanceCallNode` | Reflector.invokeInstanceMethod() exceptions |
| `GenericStaticCallNode` | Reflector.invokeStaticMethod() exceptions |
| `InvokeNode.invokeIFnDirect` | Direct IFn dispatch exceptions |
| `ProtocolInvokeNode` | Both `invokeDirect` and `invokeProtocol` paths |

All wrapping sites pass through `AbstractTruffleException` unchanged
(so ClojureExceptions from deeper in the stack aren't double-wrapped).

#### 3. Fixed ProtocolInvokeNode null location

`ProtocolInvokeNode.invokeDirect()` was passing `null` as the Node location
to `ClojureException`, losing all source info. Now passes `this`.

#### 4. Updated `ClojureException.wrap()` to use fully qualified class names

Changed from `getSimpleName()` to `getName()` so messages show
`java.lang.ClassCastException: ...` instead of `ClassCastException: ...`.
This matches what Java developers expect and what the Polyglot boundary tests check.

> **Note**: Superseded by session 2 — `ErrorMessages.formatException()` now uses simple
> names for common `java.lang.*` exception types (e.g. `ArithmeticException: Divide by zero`
> instead of `java.lang.ArithmeticException: Divide by zero`).

### What worked well

- **Divide by zero**: `(defn foo [x] (/ x 0)) (foo 42)` now shows TWO frames:
  `(/ x 0)` at the error site AND `(foo 42)` at the call site. Before: zero frames.
- **Type errors**: `(+ "hello" 42)` now shows exact location. Before: zero frames.
- **Arity errors on maps**: `(let [m {:a 1}] (m :a :b :c))` shows location.
- **TryNode unwrapping still works**: `(try (/ 1 0) (catch ArithmeticException e "caught"))`
  correctly catches the original exception type even though it's wrapped during propagation.
- All 378 tests pass (744 Clojure tests, 378 JUnit tests, generative tests).

### What needed adjustment

- **Two exception tests broke** because they checked raw Java exception types at the
  call boundary. Fixed `ExceptionTest.expectThrown()` to also check the cause chain.
  `CloffleReproTest.polyglotBoundaryPreservesInteropExceptionDetails` passed after
  switching to fully qualified class names.

---

## Session 2: Function Names, ThrowNode, Cleaner Messages

### What was done

#### 1. Function names in stack traces

Extracted function names from `ObjExpr.compiledName()` (added public accessor) and
demangled them (`user$boom__123` → `boom`). Names flow through:

`Compiler.ObjExpr.compiledName()` → `ExprToNode.extractFnName()` → `FnNode.setFnName()`
→ `ClojureRootNode.setName()` → `PolyglotStackFrame.getRootName()`

Also propagated names through `InvokeNode.initializeCallNode()` — when the InvokeNode
wraps native IFns or FnNodes in root nodes, it sets the call target name from the VarNode
symbol or FnNode's stored name.

REPL call stack now shows `in foo` dimmed after the frame location.

#### 2. ThrowNode wraps user-thrown exceptions

`ThrowNode` used `Util.sneakyThrow(t)` to throw raw Java exceptions, completely
bypassing Truffle's guest exception system. Every `(throw (Exception. "msg"))` lost
its source location — zero guest frames, zero source annotations.

Now wraps in `ClojureException.wrap(t, this)` (re-throws `AbstractTruffleException`
subclasses as-is). This means:

```
;; Before: 0 guest frames, "Internal error: IllegalArgumentException: ..."
;; After:  2 guest frames pointing to (throw ...) and call site
(defn validate [x]
  (if (string? x)
    (throw (IllegalArgumentException. (str "Expected number, got: " x)))
    x))
(validate "oops")
```

#### 3. Cleaner error messages

Moved exception formatting to `ErrorMessages.formatException()`. Common `java.lang.*`
exception types (ArithmeticException, ClassCastException, NPE, etc.) no longer show
the package prefix. Less common exceptions still show the fully qualified name.

Before: `java.lang.ArithmeticException: Divide by zero`
After:  `ArithmeticException: Divide by zero`

### Example output (user's scenario)

```
(defn foo [x] (/ x 0)) (foo 42)

    1 │ (defn foo [x] (/ x 0)) (foo 42)
      │               ^~~~~~~ repl:1:15 → (/ x 0)
      │                        ~~~~~~~~ repl:1:24 → (foo 42)

Error: ArithmeticException: Divide by zero

  Call stack (guest frames):
  ──▶ repl:1:15 → (/ x 0)  in foo
      repl:1:24 → (foo 42)
```

### What worked well

- `ObjExpr.thisName` was often null for `defn`-defined functions (the compiler sets
  it via a slightly different path). Falling back to `compiledName()` with demangling
  catches all named functions reliably.
- ThrowNode fix was high leverage — every user `(throw ...)` now gets proper frames.
- The `formatException()` approach is extensible — easy to add more friendly messages.

### Known limitations (session 2)

- **Root name only appears on innermost frame**: `PolyglotStackFrame.getRootName()`
  returns the root name for the CallTarget where the exception originated. Outer
  frames don't carry the callee's name, only the caller's (which for top-level
  roots is null). Mitigated by enriched frames (see session 3).

### Files modified (session 2)

```
src/jvm/clojure/lang/Compiler.java                              — Added compiledName() public accessor
src/jvm/net/javacrumbs/cloffle/ast/ExprToNode.java              — extractFnName() + set fnName on FnNode
src/jvm/net/javacrumbs/cloffle/nodes/FnNode.java                — fnName field, set name on root node
src/jvm/net/javacrumbs/cloffle/nodes/invoke/InvokeNode.java     — Pass fn names to createRootWithSource
src/jvm/net/javacrumbs/cloffle/nodes/ThrowNode.java             — Wrap in ClojureException instead of sneakyThrow
src/jvm/net/javacrumbs/cloffle/nodes/ClojureException.java      — Delegate to ErrorMessages.formatException()
src/jvm/net/javacrumbs/cloffle/nodes/ErrorMessages.java         — formatException() with clean java.lang stripping
src/jvm/net/javacrumbs/cloffle/CloffleREPL.java                 — fnName in Annotation record + "in foo" display
src/test/java/net/javacrumbs/cloffle/CloffleReproTest.java       — Updated for simplified exception class names
```

---

## Session 3: Intermediate Stack Frames + ArityException

### What was done

#### 1. Fixed intermediate stack frames (enriched frame tracking)

**Root cause**: Cloffle's **tail call optimization** (`TailCallException`) collapses intermediate
call sites into a single `invokeTruffleTarget` while loop — so Truffle never sees the intermediate
`CallNode` boundaries and can't record them in the stack trace. For a chain like
`process → calculate → divide → error`, the `(calculate x)` and `(divide x 0)` InvokeNodes
throw `TailCallException` (they're in tail position), and all three function bodies end up
being dispatched from the single outermost `indirectCallNode.call()` loop. Truffle only sees
one CallNode boundary, not three.

**Fix**: Built a custom enriched frame tracking system that runs alongside Truffle's native
stack trace collection:

1. **`ClojureException.addFrame(Node)`** — records call site source sections, snippets, and
   function names on the exception as it propagates through InvokeNodes.

2. **`InvokeNode.invokeTruffleTarget()`** — catches `ClojureException` at each call boundary
   and adds the InvokeNode's source section via `addFrame(this)`.

3. **Tail call tracking** — `TailCallException` now carries a list of eliminated call sites.
   When an InvokeNode throws a `TailCallException` from tail position, it records itself.
   When `invokeTruffleTarget` catches a `TailCallException`, it accumulates the eliminated
   sites. When a `ClojureException` eventually arrives, all accumulated sites are added
   in the correct order (innermost first).

4. **Thread-local publishing** — `ClojureRootNode.execute()` (wrapResult path) catches
   `ClojureException` and calls `publishFrames()` to store the enriched frames on a
   thread-local before the exception crosses the Polyglot API boundary.

5. **REPL merging** — `collectAnnotations()` reads the Truffle-native frames AND the
   enriched frames, deduplicates by `line:column`, and presents a unified call stack.

**Result**: Deep call chains now show ALL intermediate frames, even through tail calls:

```
(defn divide [a b] (/ a b))
(defn calculate [x] (divide x 0))
(defn process [x] (calculate x))
(process 42)

    1 │ (defn divide [a b]
    2 │   (/ a b))
      │   ^~~~~~~ test_deep.clj:2:3 → (/ a b)
    ...
    5 │   (divide x 0))
      │   ~~~~~~~~~~~~ test_deep.clj:5:3 → (divide x 0)
    ...
    8 │   (calculate x))
      │   ~~~~~~~~~~~~~ test_deep.clj:8:3 → (calculate x)
    ...
   10 │ (process 42)
      │ ~~~~~~~~~~~~ test_deep.clj:10:1 → (process 42)

Error: ArithmeticException: Divide by zero

  Call stack (guest frames):
  ──▶ test_deep.clj:2:3 → (/ a b)  in divide
      test_deep.clj:5:3 → (divide x 0)  in calculate
      test_deep.clj:8:3 → (calculate x)  in process
      test_deep.clj:10:1 → (process 42)
```

#### 2. ArityException → ClojureException

`FnNode.invoke()` previously threw `clojure.lang.ArityException` (extends
`IllegalArgumentException`), which Truffle doesn't recognize as a guest exception.
This meant arity errors had no source location and appeared as "Internal error".

Now throws `ClojureException` directly with a clear message:

```
ArityException: Wrong number of args (2) passed to greet. Expected: 1
```

The function name (`fnName`) is included in the message when available, falling back
to "fn" for anonymous functions. The exception is thrown with `this` (the FnNode) as
the location, so the call stack shows the function definition as the innermost frame
and the call site as an outer frame.

### Example output

```
(defn greet [name] (str "Hello, " name)) (greet "Alice" "Bob")

    1 │ (defn greet [name] (str "Hello, " name)) (greet "Alice" "Bob")
      │ ^~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ repl:1:1
      │                                          ~~~~~~~~~~~~~~~~~~~~~ repl:1:42

Error: ArityException: Wrong number of args (2) passed to greet. Expected: 1

  Call stack (guest frames):
  ──▶ repl:1:1 → (defn greet [name] ...)  in greet
      repl:1:42 → (greet "Alice" "Bob")
```

### What worked well

- The enriched frame approach is decoupled from Truffle's native stack trace — it
  supplements rather than replaces. If Truffle starts providing better frames in
  future versions, the enriched frames will be deduplicated and won't cause issues.
- Tail call tracking required minimal changes to `TailCallException` (just a list of
  eliminated call site nodes) and the InvokeNode's exception handling.
- The thread-local publishing pattern avoids needing to access internal Truffle types
  from the Polyglot API boundary.

### Files modified (session 3)

```
src/jvm/net/javacrumbs/cloffle/nodes/ClojureException.java      — CallFrame record, addFrame(), thread-local publish/consume
src/jvm/net/javacrumbs/cloffle/nodes/invoke/InvokeNode.java     — Catch ClojureException in invokeTruffleTarget, tail call tracking
src/jvm/net/javacrumbs/cloffle/nodes/TailCallException.java     — eliminatedCallSites list for tail-call-eliminated frames
src/jvm/net/javacrumbs/cloffle/nodes/ClojureRootNode.java       — publishFrames() on ClojureException in wrapResult path
src/jvm/net/javacrumbs/cloffle/nodes/FnNode.java                — Throw ClojureException instead of ArityException
src/jvm/net/javacrumbs/cloffle/CloffleREPL.java                 — Merge enriched frames into annotations, dedup
```

---

## Session 4: TryNode Fix, Exception Wrapping Sweep, Message Cleanup

### What was done

#### 1. Fixed TryNode re-throw (critical bug)

When a `try` block's `catch` clauses didn't match the exception type, `TryNode`
used `Util.sneakyThrow(unwrapped)` to re-throw the raw Java exception. This
**stripped the ClojureException wrapper**, losing all source location, enriched
frames, and function names. The exception then appeared as "Internal error" with
zero guest frames.

Now re-throws the original `AbstractTruffleException` if the incoming exception
was already a guest exception. Otherwise wraps the raw throwable in
`ClojureException.wrap(t, this)`. This preserves stack trace enrichment through
`try` blocks with non-matching catches.

#### 2. Converted raw RuntimeExceptions to ClojureException in 7 nodes

All of these previously threw `RuntimeException`, `IllegalArgumentException`, or
`UnsupportedOperationException` — none of which are `AbstractTruffleException`.
Users saw "Internal error" with no source location.

| Node | Old message | New message |
|------|-----------|-------------|
| `VarNode` | `"Unable to resolve var: #'ns/name"` | `"Unable to resolve symbol: name in this context"` |
| `AbstractValueNode` | `"Unresolved value at slot 3"` | `"Use of uninitialized local binding"` |
| `FnMethodNode` (recur) | `"Arity mismatch in recur: expected N but got M"` | `"Wrong number of args to recur: expected N, got M"` |
| `FnMethodNode` (tail) | `"Arity mismatch in tail self call: ..."` | `"Wrong number of args to recur: expected N, got M"` |
| `LoopNode` | `"Arity mismatch in recur: ..."` | `"Wrong number of args to recur: expected N, got M"` |
| `CaseNode` | `"No matching clause for case: val"` | Same, with value truncated to 40 chars |
| `ImportNode` | `"Cannot import class: X"` (cause hidden) | `"Cannot import class: X (ClassNotFoundException)"` |
| `SetBangNode` | `"set! target type not supported: InvokeNode"` | `"Invalid target for set! -- must be a var, field, or local binding"` |

#### 3. Wrapped interop exceptions in 4 more nodes

Raw Java exceptions from reflection/interop calls now produce guest exceptions
with source location:

| Node | What's wrapped |
|------|---------------|
| `NewNode` | `Reflector.invokeConstructor()` — e.g. `(Integer. "not-a-number")` |
| `StaticFieldNode` | `Reflector.getStaticField()` — e.g. `Integer/NONEXISTENT` |
| `InstanceFieldNode` | `Reflector.getInstanceField()` — e.g. `(.nonexistent obj)` |
| `KeywordInvokeNode` | `RT.get()` / `ILookup.valAt()` — e.g. `(:key nil)` |

All use the same pattern: pass through `AbstractTruffleException`, catch
`Throwable`, wrap in `ClojureException.wrap(t, this)`.

Also wrapped `Reflector.setStaticField()` and `Reflector.setInstanceField()`
inside `SetBangNode`'s `try`/`catch`.

#### 4. Added source sections to FnMethodNode

`FnMethodNode` could throw on recur arity mismatches but had no source section,
so error frames were invisible. Added `ObjMethod.sourceLine()` / `sourceColumn()`
public accessors in `Compiler.java`, and set the source section in
`ExprToNode.convertFnMethod()` using the method's line/column metadata.

#### 5. Added source section to NativeCallNode

`NativeCallNode` is created inside `InvokeNode.initializeCallNode()`, not by
`ExprToNode`, so it never got a source section. Added `copySourceSection()` helper
in `InvokeNode` to propagate the InvokeNode's source section onto the
NativeCallNode when it's created.

#### 6. Added `RuntimeException` to `JAVA_LANG_EXCEPTIONS`

`ErrorMessages.formatException()` was missing `RuntimeException` from its set of
common exceptions. This caused user-thrown `RuntimeException`s (via `(throw ...)`)
to display as `java.lang.RuntimeException: msg` instead of `RuntimeException: msg`.

### Example output

```
;; Constructor error — before: "Internal error: java.lang.NumberFormatException"
;; After: proper guest exception with source location
(Integer. "not-a-number")

    1 │ (Integer. "not-a-number")
      │ ^~~~~~~~~~~~~~~~~~~~~~~~~ repl:1:1 → (Integer. "not-a-number")

Error: NumberFormatException: For input string: "not-a-number"

  Call stack (guest frames):
  ──▶ repl:1:1 → (Integer. "not-a-number")
```

```
;; Deep chain through try — exceptions now preserve frames through try blocks
(defn divide [a b] (/ a b))
(defn calculate [x] (divide x 0))
(defn process [x]
  (try (calculate x)
    (catch java.io.IOException e "not this")))
(process 42)

Error: ArithmeticException: Divide by zero

  Call stack (guest frames):
  ──▶ test_try_deep.clj:2:3 → (/ a b)  in divide
      test_try_deep.clj:5:3 → (divide x 0)  in calculate
      test_try_deep.clj:9:5 → (calculate x)  in process
      test_try_deep.clj:13:1 → (process 42)
```

### What's NOT yet done (future work)

#### Medium impact (DONE — Apr 2026)

1. **Source sections for literal Expr types**: ~~`NilExpr`, `BooleanExpr`, `NumberExpr`,
   `StringExpr`, `KeywordExpr`, `ConstantExpr`, `EmptyExpr` — none of these have
   `line`/`column` in `Compiler.java` yet.~~ **Done**: `NumberExpr`, `StringExpr`,
   `KeywordExpr`, `ConstantExpr`, `EmptyExpr` now store `line`/`column` from
   `lineDeref()`/`columnDeref()`. `NilExpr`/`BooleanExpr` are singletons and use
   the thread-local fallback. `ExprSourceSpans.extractLineColumn` handles all five types.

2. **`didYouMean()` wiring**: ~~Implemented in `ErrorMessages.java` (along with
   `editDistance()`) but never called. Could be wired into `VarNode`, `Compiler.java`
   symbol/class resolution, and protocol method lookups.~~ **Done**: `didYouMean` wired
   into `VarNode` (runtime, previous session), `Compiler.analyzeSymbol` (compile-time
   unresolved symbol), `Compiler.resolveIn` (no such var). `didYouMeanNamespace` wired
   into `Compiler.resolveIn` (no such namespace).

### Files modified (session 4)

```
src/jvm/net/javacrumbs/cloffle/nodes/TryNode.java               — Re-throw as ClojureException instead of sneakyThrow
src/jvm/net/javacrumbs/cloffle/nodes/vars/VarNode.java           — ClojureException with "Unable to resolve symbol"
src/jvm/net/javacrumbs/cloffle/nodes/vars/AbstractValueNode.java — ClojureException with "uninitialized local binding"
src/jvm/net/javacrumbs/cloffle/nodes/FnMethodNode.java           — ClojureException for recur arity + source section
src/jvm/net/javacrumbs/cloffle/nodes/LoopNode.java               — ClojureException for recur arity
src/jvm/net/javacrumbs/cloffle/nodes/CaseNode.java               — ClojureException for no matching clause
src/jvm/net/javacrumbs/cloffle/nodes/ImportNode.java             — ClojureException with cause class name
src/jvm/net/javacrumbs/cloffle/nodes/SetBangNode.java            — ClojureException + interop wrapping
src/jvm/net/javacrumbs/cloffle/nodes/NewNode.java                — Interop exception wrapping
src/jvm/net/javacrumbs/cloffle/nodes/StaticFieldNode.java        — Interop exception wrapping
src/jvm/net/javacrumbs/cloffle/nodes/InstanceFieldNode.java      — Interop exception wrapping
src/jvm/net/javacrumbs/cloffle/nodes/KeywordInvokeNode.java      — Interop exception wrapping
src/jvm/net/javacrumbs/cloffle/nodes/ErrorMessages.java          — Added RuntimeException to JAVA_LANG_EXCEPTIONS
src/jvm/net/javacrumbs/cloffle/nodes/invoke/InvokeNode.java      — copySourceSection for NativeCallNode
src/jvm/net/javacrumbs/cloffle/ast/ExprToNode.java               — Source section for FnMethodNode
src/jvm/clojure/lang/Compiler.java                               — sourceLine() / sourceColumn() accessors on ObjMethod
src/test/java/net/javacrumbs/cloffle/CloffleReproTest.java       — Updated for simplified RuntimeException message
```

---

## Session 5: Bytecode Stack-Trace Parity, Literal Source Sections, didYouMean Wiring, ex-data Spans

### What was done

#### 1. Bytecode enriched frame tracking (Chapter 2 parity)

On the AST path (Chapter 1), `InvokeNode.invokeTruffleTarget` catches `ClojureException`
and calls `addFrame(this)` to record call-site source sections as the exception propagates
through each invoke boundary. The bytecode path (Chapter 2) had no equivalent — exceptions
passed through `CloffleBytecodeRootNode.interceptTruffleException` only to fix a missing
innermost location, never recording intermediate frames.

Now `interceptTruffleException` unconditionally calls `ce.addFrame(instrSS, this.name)`
with the current bytecode instruction's resolved `SourceSection` and the root's function
name. This fires for every `CloffleBytecodeRootNode` the exception unwinds through, so
deep call chains like `process → calculate → divide → error` now show all intermediate
guest frames on the bytecode path.

Added `ClojureException.addFrame(SourceSection, String)` — a new overload that accepts a
`SourceSection` and function name directly, since bytecode operations don't have a
per-call-site `Node` like AST `InvokeNode` does. The existing `addFrame(Node)` now
delegates to this overload after extracting the source section and root name from the node.

Extracted `resolveBytecodeSourceSection(BytecodeNode, int)` as a static helper in
`CloffleBytecodeRootNode` to separate source-section resolution from exception handling.

#### 2. Literal Expr source sections in `Compiler.java`

Added `line`/`column` fields to five literal `Expr` types that previously had none:

| Expr Type | Constructor change |
|-----------|-------------------|
| `NumberExpr` | `this.line = lineDeref(); this.column = columnDeref();` |
| `StringExpr` | Same |
| `KeywordExpr` | Same |
| `ConstantExpr` | Same |
| `EmptyExpr` | Same |

`NilExpr` and `BooleanExpr` are singletons (`NIL_EXPR`, `TRUE_EXPR`, `FALSE_EXPR`) and
cannot store per-instance line/column — they continue to use the thread-local fallback in
`ExprSourceSpans.extractFromExprValue`.

Updated `ExprSourceSpans.extractLineColumn` with explicit `instanceof` branches for all
five types, reading their stored `line`/`column` instead of falling through to the
thread-local fallback (which could hold stale values from a previous form).

#### 3. `didYouMean` / `didYouMeanNamespace` wired into `Compiler.java`

`ErrorMessages.didYouMean(name, ns)` was only called from `VarNode` (runtime unbound var
error). `ErrorMessages.didYouMeanNamespace(alias)` was implemented but never called
anywhere.

Now wired into three compile-time error paths in `Compiler.java`:

| Error site | Method | Error message |
|-----------|--------|--------------|
| `resolveIn` — namespace not found | `didYouMeanNamespace(sym.ns)` | "No such namespace: X. Did you mean: Y?" |
| `resolveIn` — var not found in namespace | `didYouMean(sym.name, ns)` | "No such var: ns/X. Did you mean: ns/Y?" |
| `analyzeSymbol` — unqualified symbol not found | `didYouMean(sym.name, currentNS())` | "Unable to resolve symbol: X. Did you mean: Y?" |

#### 4. ex-data span metadata for editor tooling

`ClojureException.buildExData()` previously emitted `:clojure.error/line` and
`:clojure.error/column` but not the span extent. Editors need end position or length to
draw red squiggles under the exact form.

Added three new keys to `buildExData()`:

| Key | Value | Source |
|-----|-------|--------|
| `:clojure.error/length` | Character count of the source span | `SourceSection.getCharLength()` |
| `:clojure.error/end-line` | End line of the source section | `SourceSection.getEndLine()` |
| `:clojure.error/end-column` | End column of the source section | `SourceSection.getEndColumn()` |

Only emitted when the `SourceSection` is available with line information.

### Example output

```
;; didYouMean for unresolved symbol (compile-time)
(printl "hello")

Syntax error compiling at (repl:1:1).
Unable to resolve symbol: printl in this context. Did you mean: println?
```

```
;; didYouMean for wrong namespace (compile-time)
(clojure.strng/join "," [1 2 3])

Syntax error compiling at (repl:1:1).
No such namespace: clojure.strng. Did you mean: clojure.string?
```

```
;; ex-data now includes span information
(try (/ 1 0) (catch Exception e (ex-data e)))
;; => {:clojure.error/phase :execution,
;;     :clojure.error/source "repl",
;;     :clojure.error/line 1,
;;     :clojure.error/column 1,
;;     :clojure.error/length 7,
;;     :clojure.error/end-line 1,
;;     :clojure.error/end-column 7,
;;     :clojure.error/class java.lang.ArithmeticException,
;;     :clojure.error/cause "Divide by zero"}
```

### Test coverage

22 new tests:
- `DxImprovementsTest` (20 tests): bytecode deep call chains with multiple guest frames,
  enriched frames with source locations, exception propagation through try/catch, literal
  source info for numbers/strings/keywords, `didYouMean` suggestions for unresolved vars
  and namespaces, ex-data phase/length/end-line/end-column keys, ex-info preservation,
  nested call arity errors, interop exception source locations.
- `ErrorMessagesTest` (2 tests): `didYouMeanNamespace` close match and null result.

766/770 tests passing (4 pre-existing failures in `PolyglotErrorLocationsTest`).

### Files modified (session 5)

```
src/jvm/net/javacrumbs/cloffle/bytecode/CloffleBytecodeRootNode.java — Enriched frame tracking in interceptTruffleException; resolveBytecodeSourceSection helper
src/jvm/net/javacrumbs/cloffle/nodes/ClojureException.java           — addFrame(SourceSection, String) overload; LENGTH_KEY/END_LINE_KEY/END_COLUMN_KEY in buildExData
src/jvm/clojure/lang/Compiler.java                                   — line/column on NumberExpr, StringExpr, KeywordExpr, ConstantExpr, EmptyExpr; didYouMean at analyzeSymbol and resolveIn (3 sites)
src/jvm/net/javacrumbs/cloffle/ast/ExprSourceSpans.java              — extractLineColumn handles NumberExpr, StringExpr, KeywordExpr, ConstantExpr, EmptyExpr
src/test/java/net/javacrumbs/cloffle/DxImprovementsTest.java         — 20 new polyglot integration tests
src/test/java/net/javacrumbs/cloffle/ErrorMessagesTest.java          — 2 new didYouMeanNamespace unit tests
```

---

## Guest REPL host callbacks (`cloffle.repl`) — Apr 2026

The Truffle-based REPL (`CloffleRepl` → `cloffle.repl`) evaluates user forms and script files through the **polyglot** `Context#eval` path so errors stay `PolyglotException`s and terminal rendering stays in `PolyglotErrorConsoleDisplay` / `PolyglotErrorLocations` (same UX as embedding from Java).

**Bootstrap:** `CloffleRepl` evaluates a small Clojure snippet that `(require 'cloffle.repl)` and returns `(fn [s f] (cloffle.repl/install-host-eval! s f))`. The host then calls `Value.execute` with **two** `clojure.lang.AFn` instances: arity-2 for `(code, source-name)` and arity-1 for `(file-path)`, both delegating to helpers closed over the active `Context`. Guest code stores those fns and invokes them with `IFn` / `.invoke` for each REPL form or file.

**Why not pass a plain Java “host” object?** Values crossing into the guest language are wrapped as `com.oracle.truffle.host.HostObject`. Guest Clojure’s `.method` interop resolves against `HostObject`, not the underlying Java class, which leads to errors like *No matching method … for class HostObject*. **Host `IFn` implementations avoid that** because invocation uses Clojure’s normal `invoke` path.

**Why not `load-file` / `load-string` for user code in the guest REPL?** Those paths often catch throwables that surface as bare `java.lang.Exception` without the polyglot guest stack and `ClojureException` chain, which degrades source attribution compared to a top-level `Context#eval` of the file or string.

> Historical notes in earlier sessions list `CloffleREPL.java` for REPL display work; the current sources are `CloffleRepl.java` (launcher + bootstrap) and `PolyglotErrorConsoleDisplay.java` (shared error rendering).
