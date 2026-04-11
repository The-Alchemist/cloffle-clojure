# Cloffle Bytecode Debugging: investigation findings

## Duplicate breakpoint halts & defn-at-load-time (RESOLVED)

A breakpoint on `(run 11)` in a multi-defn script fired three times per `Resume`,
and `defn` lines halted the debugger at load time — unlike Java/Python/JS.

### Root cause

Two independent layers applied `StatementTag` to the same source line:

1. **`TopLevelEvalNode`** (AST wrapper in `SequentialFormNode`) — `hasTag()` unconditionally returned `true` for `StatementTag`.
2. **Inner bytecode root** — `emitWithLineColumnSection` in `ExprToBytecode` applied `StatementTag` to the outermost expression.
3. **`VarExpr` callee** — the `run` var load also carried `StatementTag` on the same line.

Truffle line breakpoints match *all* nodes with `StatementTag` whose source section overlaps the target line. Three `StatementTag` nodes on line 23 → three suspensions.

Additionally, `emitDefExpr` unconditionally wrapped the def head with `StatementTag`, causing `(defn f [x] ...)` to halt at load time — a runtime "assignment" that doesn't match traditional debugger UX.

### Fix: `BytecodeTagPolicy` + layered dedup

| Layer | Runtime form (call/simple-def/compound) | Definition (defn/defmacro) |
|-------|----------------------------------------|---------------------------|
| `TopLevelEvalNode` | `StatementTag` ✓ (sole provider) | no `StatementTag` |
| Inner bytecode root | `StatementTag` suppressed via `skipNextStatementTag` | `StatementTag` suppressed via `defHeadIsStatement()` |

**`BytecodeTagPolicy`** (`bytecode/BytecodeTagPolicy.java`) centralizes all decisions:
- `classify(Expr)` → `FormKind` enum
- `isRuntimeStatement(Expr)` → false for `FN_DEFINITION` and `LITERAL`
- `defHeadIsStatement(DefExpr)` → false when init is `FnExpr`
- `inhibitCalleeArgTags(Expr)` → true for `VarExpr`/`TheVarExpr`
- `inhibitDefInitTags(DefExpr)` → true for simple defs (non-FnExpr init)

**Single-form fast path removed:** all top-level forms now go through `SequentialFormNode` so `TopLevelEvalNode` consistently provides the `StatementTag`. This lets `convertRoot(..., inhibitRootStatementTag=true)` safely suppress the inner bytecode's outermost tag without leaving any form tag-less.

### Failed approaches

1. **Removing `StatementTag` from `TopLevelEvalNode` entirely** (no conditional) — broke `stepOverAdvancesToNextForm` (expected 3 stops, got 2). Step-over relies on `StatementTag` at the `TopLevelEvalNode` call depth to advance between forms.
2. **`statementTagInhibitDepth++` around entire `convert(rootExpr)`** — suppressed ALL nested tags (including inner `let` bindings, scope vars), breaking 54 tests.
3. **`skipNextStatementTag` without removing the single-form fast path** — single-form scripts had zero `StatementTag` nodes (no `TopLevelEvalNode` wrapper + inhibited inner tag), breaking `suspendNextExecutionStops` and most single-form debugger tests.

### Tests updated

- `breakpointOnDefnFires` → `breakpointOnDefnDoesNotFire` (verifies defn BP does NOT fire, call BP does)
- `stepOutReturnsToCaller`, `stepOutFromFunctionReturns` — BP moved from defn line to call line
- New: `breakpointOnCallAfterDefnsStopsOnce` — reproduces the original `t.clj` scenario, asserts exactly 1 halt

---

# Cloffle Bytecode Debugging: slot-debug investigation findings (RESOLVED)

Investigation into why `bytecodeLocalOffsetDebugNames` was empty on the root node
that the Truffle debugger observes at a suspend site. **Fixed** — see "Implemented fix" below.

## TL;DR

**Root cause:** `ExprToBytecode.registerSlotDebugName` called
`BytecodeLocal.getLocalOffset()` eagerly — while the builder was still open,
before `endRoot()` finalized the bytecode. On a reparse builder the offset
wasn't available yet, the call threw `IllegalStateException`, and the exception
was silently swallowed. This produced an empty `SlotDebug` list, so
`applySlotDebugNames` never set the field.

**Fix:** `SlotDebug` now stores the `BytecodeLocal` reference; the offset is
resolved in `applySlotDebugNames` *after* `endRoot()`, when offsets are valid.

The reparse flow itself is structurally sound — `endRoot()` returns the same
root instances from `builtNodes`, and `Node.copy()` shallow-copies all fields
including `bytecodeLocalOffsetDebugNames`.

## Detailed findings

### 1. Reparse flow is structurally sound

From the generated `CloffleBytecodeRootNodeGen`:

- `performUpdate` (line ~11082) fetches the stored parser, creates a new
  `Builder` with `reparseReason != null`, pre-populates `builtNodes` with
  existing root instances, then calls `parser.parse(builder)`.
- `endRoot()` on reparse (line ~4326) returns the **same** root from
  `builtNodes.get(index)`, calls `updateBytecode(...)`, and returns it to the
  parser lambda.
- The parser lambda (`ExprToBytecode.convertRoot` / `convertFnExpr`) then calls
  `applySlotDebugNames(rootNode, slotDebugByRoot.pop())`.
- `updateBytecode` (line ~648) propagates the new bytecode to **clones** via
  `this.clones.forEach(...)`, but does **not** propagate custom fields like
  `bytecodeLocalOffsetDebugNames`.

So the root-cause is **not** that `endRoot()` returns a different node.

### 2. `registerSlotDebugName` silently swallows failures

```java
private void registerSlotDebugName(BytecodeLocal local, LocalBinding lb) {
    // ...
    try {
        slotDebugByRoot.peek().add(new SlotDebug(local.getLocalOffset(), n));
    } catch (IllegalStateException ignored) {
        // swallowed — debug-name metadata is optional
    }
}
```

`BytecodeLocal.getLocalOffset()` can throw `IllegalStateException` on a reparse
builder when the local's internal state doesn't match the new builder's context.
This silently drops the entry, producing an **empty** `SlotDebug` list.  When
`applySlotDebugNames` receives an empty list, it returns immediately (line 344),
leaving `bytecodeLocalOffsetDebugNames` null on the reparsed root.

The generated serializer (line ~8576) **does** serialize/deserialize the field,
confirming the Bytecode DSL treats it as persistent data.  The issue is only at
runtime reparse time.

### 3. Observed identities in diagnostic tracing (initial investigation)

Early tracing added `System.err.println` to `setBytecodeLocalOffsetDebugNames`
and `getDirectBytecodeLocalOffsetDebugNames`. Results initially appeared to show
a root that was "never seen in any set call":

| Call                                | Node name          | Identity     | Notes                                |
|-------------------------------------|--------------------|--------------|--------------------------------------|
| `setBytecodeLocalOffsetDebugNames`  | `clojure.core/add` | `578451941`  | Core bootstrap's add (3× due to multi-pass) |
| `setBytecodeLocalOffsetDebugNames`  | `clojure.core/add` | `317966153`  | Another test's add                   |
| `getDirectBytecodeLocalOffsetDebugNames` | `add`         | `1617967142` | **Queried root — never in any set call** |

Later analysis revealed that `@1617967142` was the **calling wrapper root** for
`(add 10 20)`, not the `add` function body. `Clojure.collectFormInner` names the
outer root after the form's first symbol, so it was also named `"add"`. The
actual function root (named `clojure.core/add`) *did* receive its debug names
correctly — but only after the deferred-offset fix, since the original eager
`getLocalOffset()` call threw during reparse.

### 4. Why the Var fallback works today

`getBytecodeLocalOffsetDebugNames()` merges the direct field with
`debugNamesFromVarByRootName`, which looks up `RT.var(CURRENT_NS, getName())`
and walks to the original `ClojureClosure`'s root.  This **masks** the empty
field on the instrumented root, making tests pass.

## Proposed approaches (pre-implementation)

### Root cause fix: stop swallowing `getLocalOffset()` failures

The `IllegalStateException` catch in `registerSlotDebugName` was added for
"synthetic locals (e.g. serialization placeholders)."  But it also silences
legitimate failures during reparse where `BytecodeLocal` objects from the
**current** parse pass should have valid offsets.

**Approach A (preferred):** Instead of swallowing the exception, distinguish
between "truly synthetic local that can't have an offset" and "real parameter
local whose offset should be available."  Log at minimum when a named parameter
or `let*` binding's offset is unavailable, so failures are diagnosable.  If the
offset is structurally unavailable during reparse, cache the offset from the
first parse (e.g. on the `BytecodeLocal` wrapper or in a side map).

**Approach B:** Record debug names by ordinal position (not physical offset)
during the **first** parse and store them on the root.  On reparse,
`applySlotDebugNames` re-applies from the stored ordinal list.  This avoids
depending on `getLocalOffset()` during reparse entirely.

### API split

Add `getDirectBytecodeLocalOffsetDebugNames()` returning only the field (no Var
fallback).  Keep `getBytecodeLocalOffsetDebugNames()` as the resolved/merged
accessor for `BytecodeLocalScope`.  This makes it possible to write tests that
assert the field survives instrumentation without the fallback masking failures.

### Regression test

A `DebuggerSession` test that:
1. Defines `(defn add [a b] (+ a b))` via `Context.eval`.
2. Installs a breakpoint, steps into `add`.
3. Obtains the executing `CloffleBytecodeRootNode` from the halt site.
4. Asserts `getDirectBytecodeLocalOffsetDebugNames()` is **non-empty** and
   contains `a` and `b`.

This test **must fail** before the root-cause fix and **pass** after.

### Retire or narrow `debugNamesFromVarByRootName`

Once the direct field is populated on instrumented roots:

- Remove `debugNamesFromVarByRootName` entirely, **or**
- Keep it only for lexically anonymous `fn` roots (name = `"fn"`) where no
  better source exists, with a clear comment that it's best-effort.

### Document the fix

Update `CLOFFLE_TRUFFLE_BYTECODE.md` § "Instrumentation vs. the Var's root" to
describe the parser-idempotency fix and the direct-vs-resolved API.

---

## Implemented fix

**`SlotDebug` deferred-offset approach (neither Approach A nor B above):**
Instead of resolving `BytecodeLocal.getLocalOffset()` eagerly inside
`registerSlotDebugName` (where the builder is still open and offsets may be
invalid), `SlotDebug` now stores the `BytecodeLocal` reference itself.
`applySlotDebugNames` — which runs *after* `b.endRoot()` — calls
`getLocalOffset()` when the bytecode is finalized and offsets are always valid.
A catch for `IllegalStateException` remains only for truly synthetic locals.

**Changes made:**

| File | Change |
|------|--------|
| `src/jvm/…/ExprToBytecode.java` | `SlotDebug` stores `BytecodeLocal` instead of `int localOffset`; offset resolved in `applySlotDebugNames` after `endRoot()` |
| `src/jvm/…/CloffleBytecodeRootNode.java` | Added `getDirectBytecodeLocalOffsetDebugNames()` (no Var fallback); narrowed `debugNamesFromVarByRootName` javadoc to "safety net" |
| `src/test/…/BytecodePolyglotClosureDebugTest.java` | New `instrumentedRootCarriesDirectDebugNames` test: steps into `(defn add [a b] ...)`, asserts direct field contains `a` and `b` |
| `CLOFFLE_TRUFFLE_BYTECODE.md` | Updated § instrumentation to describe the fix and new API |

**Additional finding:** The regression test initially appeared to fail because
`prepareStepInto(1)` from a breakpoint on `(add 10 20)` doesn't immediately
enter the `add` function body — it first suspends inside the calling wrapper
root (which is also named `"add"` because `collectFormInner` derives the name
from the form's first symbol). A second `prepareStepInto(1)` is needed to
actually enter the function body where the params are visible.

## Out of scope

- **Replacing the side table with `Builder#createLocal(Object, Object)`.**
  `createLocal(Object, Object)` would let `BytecodeNode#getLocalNames(bci)`
  return debug names directly from the Truffle DSL's built-in local metadata,
  eliminating `bytecodeLocalOffsetDebugNames` entirely. However, it shifts the
  physical locals table: inserting debugger-facing locals changes the offsets of
  all subsequent locals, breaking emitted `StoreLocal`/`LoadLocal` bytecode that
  was generated relative to the earlier layout. `ExprToBytecode`'s local
  allocation strategy (`fillRootLocalPool`, `createTrackedLocal`, all offset
  arithmetic) would need a significant refactor, plus re-validation that no
  emitted code depends on the current slot layout. With the deferred-offset fix,
  the side table works correctly across all parse and reparse paths, so this is
  not worth the risk unless a concrete limitation surfaces (e.g. serialization
  edge cases or locals that `BytecodeLocalScope` can't resolve).
- Broader `DebuggerTest` / `DapTest` relaxations (only revisit if the new test
  or existing suite fails for unrelated reasons).
- Tail-call optimization effects on debug stack shape.

---

## DAP startup location + caller-chain visibility (RESOLVED)

Two debugger UX issues were fixed in the runtime:

### 1) Suspend-on-start location drift for scripts beginning with `ns`

Symptom in VS Code attach flow (`dap.Suspend=true` + wait attached):
- First suspend could open/land on synthetic macroexpanded content, or map to an unexpected line near file end.

Root causes:
- Eager setup forms (`ns`/`require`/`in-ns`) executed via macroexpanded roots that were treated like regular steppable statements.
- Some eager roots carried broad source sections (whole-file fallback), which made AFTER/return anchors appear at end-of-file.

Implemented runtime changes:
- Eager setup forms are marked non-runtime in top-level sequencing (`TopLevelEvalNode` no longer advertises them as runtime statement stops).
- Eager-eval roots can suppress statement tags for internal setup execution.
- Eager roots use narrowed source sections (`convertRoot(..., narrowRootSourceSection=true, ..., inhibitAllStatementTags=true)` path).
- When script `Source` exists, eager setup evaluation keeps that source attribution instead of forcing synthetic file identity.

### 2) Leaf breakpoint stack missing caller chain (`run -> trunk -> branch`)

Symptom:
- Breakpoint inside a leaf function could show only the leaf frame, omitting intermediate callers.

Root cause:
- Bytecode `Invoke` called closures through generic `IFn.invoke(...)`, which can obscure guest call edges from Truffle stack reporting.

Implemented runtime changes:
- `CloffleBytecodeRootNode.Invoke` now has `ClojureClosure` specializations that execute via Truffle call nodes:
  - cached `DirectCallNode` specialization
  - `IndirectCallNode` fallback
- Generic `IFn` invocation path remains for non-closure call targets.

Outcome:
- Attach/suspend startup resolves to user source locations predictably.
- Breakpoints inside nested call chains preserve caller frames in stack traces.
