# FIXED — `locking` inside `deftype`/`reify` no longer breaks `set!` on mutable fields

Cloffle’s `clojure.core/locking` used to **always** expand to a host
`synchronized` via `CloffleMonitors/lock` plus a nested `(fn* [] …body…)`. That
is required for the Truffle bytecode path (bare `monitor-enter` /
`monitor-exit` cannot safely span bytecode frames). It is wrong for
`deftype`/`reify` method bodies, which still compile to JVM ASM in a single
method.

## Symptom

Loading `clojure.core.memoize` (pulled in by reitit) failed with:

```text
Syntax error (IllegalArgumentException) compiling fn* at (clojure/core/memoize.clj:41:7).
Cannot assign to non-mutable: value
```

`RetryingDelay` does `(set! value v)` / `(set! available? true)` on
`^:volatile-mutable` fields inside `(locking fun …)`. Under Cloffle that body
lived in a nested `fn*` closed over those locals; ASM emit treated the closed
copy as non-mutable.

Stock Clojure is fine: its `locking` keeps the body in the same method as the
`set!`, so the assignment hits the real deftype field.

Gate: `clojure -T:build compat-test :project :reitit` (Phase 2 Cloffle).

## Cause

1. **Truffle path needs `CloffleMonitors`.** JVMS structured locking and HotSpot
   refuse non-nested monitor pairs across host frames. See
   [`CloffleMonitors.java`](src/jvm/net/javacrumbs/cloffle/CloffleMonitors.java)
   and the throwing `MonitorEnter` / `MonitorExit` ops in
   `CloffleBytecodeRootNode`.

2. **`deftype`/`reify` still use ASM.** While analyzing those method bodies,
   `Compiler.IN_REIFY_OR_DEFTYPE` is true, and nested `fn*` forms are forced
   through `FnExpr.compile` (same flag drives `needsBytecode`). The locking
   `fn*` therefore became a real JVM class whose closed-overs are ordinary
   fields — not live mutable deftype slots.

3. **`set!` + close = wrong binding.** `ObjExpr.emitAssignLocal` only allows
   assign when `isMutable` (volatile-/unsynchronized-mutable **and** present in
   that ObjExpr’s `fields`). A nested `FnExpr` has no such fields for `value`,
   so emit threw.

## Fix (2026-09-13)

`locking` is dual-path at macroexpand time
([`core.clj`](src/clj/clojure/core.clj)):

| Context | Expansion |
| --- | --- |
| `Compiler/inReifyOrDeftype` | Stock `monitor-enter` / `try` / `monitor-exit` (body stays in the ASM method; `set!` works) |
| Otherwise (Truffle guest) | `(CloffleMonitors/lock x (fn* [] …))` as before |

`Compiler.inReifyOrDeftype()` exposes the thread-local used while building
`NewInstanceExpr` methods.

Regression: `DirectLinkingPinnedInvokeTest/deftypeVolatileMutableSetWorks`
(RetryingDelay-shaped deftype).

## What remains intentional

- Bare `monitor-enter` / `monitor-exit` in **guest** bytecode still throw at
  runtime; prefer `locking`.
- Nested `(fn [] (set! mutable-field …))` **inside** a deftype method (without
  going through this `locking` special case) would still hit the same
  non-mutable close rule — that matches stock for nested fns; only stock
  `locking` avoided the nested fn.
