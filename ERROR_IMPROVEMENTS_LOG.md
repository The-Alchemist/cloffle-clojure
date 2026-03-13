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

### Known limitations

- **Intermediate frames missing**: For deep call chains like `a → b → c → error`,
  Truffle's `IndirectCallNode` only produces the innermost and outermost guest frames.
  Intermediate call sites are dropped. This is a Truffle framework behavior with how
  `TruffleStackTrace` elements are collected for non-compiled (interpreter) execution.
  Root cause: the intermediate `IndirectCallNode`'s encapsulating source section
  resolves to `null` despite the parent InvokeNode having a valid source section.

- **Root name only appears on innermost frame**: `PolyglotStackFrame.getRootName()`
  returns the root name for the CallTarget where the exception originated. Outer
  frames don't carry the callee's name, only the caller's (which for top-level
  roots is null).

### What's NOT yet done (future work)

#### High impact — next priorities

1. **Intermediate stack frames**: Investigate why Truffle drops middle frames when
   using `IndirectCallNode`. Possible fix: use `DirectCallNode` for the non-static
   var-lookup path (after the first call resolves the target), or manually build
   an enriched stack trace by walking the Truffle node tree.

2. **ArityException → Truffle exception**: `clojure.lang.ArityException` extends
   `IllegalArgumentException`, not `AbstractTruffleException`. Arity errors from
   user-defined functions (thrown by `FnNode`) get no source location via Truffle's
   frame mechanism. Fix: catch in FnNode and re-throw as ClojureException with
   `this` as location, or make ArityException extend AbstractTruffleException.

3. **Wire up `didYouMean()` more broadly**: Currently only used in one place. Add to
   all "unable to resolve" error paths (vars, namespaces, protocol methods).

#### Medium impact

4. **Source sections for literal Expr types**: `NilExpr`, `BooleanExpr`, `NumberExpr`,
   `StringExpr`, `KeywordExpr`, `ConstantExpr`, `EmptyExpr`.

5. **NewNode exception wrapping**: Constructor failures from `(ClassName. args)`
   propagate raw exceptions.

6. **KeywordInvokeNode exception wrapping**: NPE when calling keyword on nil.

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
