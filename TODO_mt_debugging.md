# TODO — multi-thread breakpoint debugging tests

Deferred work: prove line breakpoints, scopes, and value interop behave correctly when guest code runs on **Truffle worker threads** (`future` / `agent` pools), not only on the thread that calls `context.eval()`.

Related: [`CLOFFLE_DEBUGGING.md`](CLOFFLE_DEBUGGING.md), [`CLOFFLE_NOTES.md`](CLOFFLE_NOTES.md) (debugger + threading), [`changes.md`](changes.md) (guest threads via `CloffleThreads`).

## Why this matters

- [`DebuggerTest.java`](src/test/java/net/javacrumbs/cloffle/DebuggerTest.java) assumes **single-threaded** eval: `SuspendedCallback` runs synchronously during `context.eval()` on the test thread.
- [`TruffleGuestThreadTest.java`](src/test/java/net/javacrumbs/cloffle/TruffleGuestThreadTest.java) shows agent/future pools are `Env.newTruffleThreadBuilder` threads with `initializeThread` / `getContext`, but does **not** install breakpoints.
- [`CLOFFLE_NOTES.md`](CLOFFLE_NOTES.md) does not yet claim “breakpoint on worker thread” coverage.

**Expected production behavior (untested):** breakpoints match `StatementTag` + source sections on bytecode roots regardless of thread; workers already get `Var` bindings via [`Clojure.initializeThread`](src/jvm/net/javacrumbs/cloffle/Clojure.java). [`Agent.java`](src/jvm/clojure/lang/Agent.java) → [`CloffleThreads.newThread`](src/jvm/net/javacrumbs/cloffle/CloffleThreads.java).

## Test design constraint

`onSuspend` usually runs on the **suspended thread** (often `clojure-agent-send-off-pool-*` or `clojure-agent-send-pool-*`), not the JUnit thread. Use `CountDownLatch`, `AtomicInteger`, and thread-safe handler queues — do not reuse [`DebuggerTest`](src/test/java/net/javacrumbs/cloffle/DebuggerTest.java) `OrderedCallback` unless the main thread hits the BP.

Context must allow thread creation: `allowAllAccess(true)` (or explicit create-thread permission).

## Interop / `Symbol` (scope on workers)

Locals in the debugger go through `InteropLibrary` ([`ClojureScope`](src/jvm/net/javacrumbs/cloffle/nodes/ClojureScope.java)).

[`Symbol`](src/jvm/clojure/lang/Symbol.java) extends [`AFn`](src/jvm/clojure/lang/AFn.java) (`isExecutable() == true` by default). `Symbol` overrides `isExecutable() → false` and string interop (like `Keyword`) so symbols are not shown as callable in the Variables panel.

- **Unit (no suspension):** [`DebuggerValueInteropTest.java`](src/test/java/net/javacrumbs/cloffle/DebuggerValueInteropTest.java) — `symbolIsStringNotExecutable`, etc.
- **Gap:** same rules at a **real** `SuspendedEvent` on a worker (e.g. `DebugValue` for `let [s 'my.ns/sym]`).

## Checklist — new test class

Add [`DebuggerMultiThreadTest.java`](src/test/java/net/javacrumbs/cloffle/DebuggerMultiThreadTest.java) (keep `DebuggerTest` single-thread contract).

Setup: shared `Engine`, `Context` with `allowAllAccess`, `Debugger.find(engine)`, `CloffleEvalTestSupport.bindFreshNamespace` or [`newDebuggerContext`](src/test/java/net/javacrumbs/cloffle/CloffleEvalTestSupport.java) for scope cases (`ClearDeadLocals=false`).

| Test | Intent |
|------|--------|
| `breakpointFiresOnFutureWorkerThread` | Line BP in `worker` body; `@(future (worker 41))`; latch; thread name `clojure-agent-send-off-pool-*`; result `42` |
| `breakpointFiresOnAgentSendPoolThread` | BP in `send` fn body; thread name `clojure-agent-send-pool-*` |
| `twoFuturesBothHitSameBreakpoint` | Two futures, same BP line, two suspensions (order-independent) |
| `breakpointOnWorkerExposesLocals` | `let`/`defn` locals via `DebugScope.getDeclaredValue` + numeric asserts (mirror `scopeVariableHasCorrectValueAfterEntryStep`) |
| `workerScopeSymbolLocalIsStringNotExecutable` | Symbol local: `isString`, correct `asString`, not executable via interop |
| *(stretch)* `mainThreadAndWorkerBothHitBreakpoints` | One BP on main eval path, one on future; at least one stop on pool thread |

**Run when implemented:**

```sh
export ENV=local
eval "$(direnv export zsh)"
clojure -T:build run-tests :filter DebuggerMultiThreadTest
```

## If tests fail — triage order

1. No suspension → `StatementTag` / line attribution; compare single-thread BP in `DebuggerTest`; [`BytecodeTagPolicy`](src/jvm/net/javacrumbs/cloffle/bytecode/BytecodeTagPolicy.java).
2. Wrong thread / plain `Thread` → `CloffleThreads` fallback when `!isCreateThreadAllowed()`.
3. Empty scope on worker → `newDebuggerContext`; `initializeThread` ([`TruffleGuestThreadTest.initializeThreadBindsNsOnGuestThread`](src/test/java/net/javacrumbs/cloffle/TruffleGuestThreadTest.java)).
4. Symbol callable in UI → [`Symbol.java`](src/jvm/clojure/lang/Symbol.java) interop vs `AFn`; fix then re-run `DebuggerValueInteropTest`.
5. Hang → missing `prepareContinue()` while another thread blocks in `deref` / `await`.

Avoid `@promise` / blocking edge cases in v1 (safepoint gaps in [`changes.md`](changes.md)).

## Docs when done

- Short note under debugger limitations in `CLOFFLE_NOTES.md` → point at `DebuggerMultiThreadTest`.
- Optional row in `CLOFFLE_DEBUGGING.md` test matrix.

## Out of scope (later)

- DAP TCP wire test with `future` + multiple `threadId` in `stopped` events ([`DapIntegrationTest`](src/test/java/net/javacrumbs/cloffle/DapIntegrationTest.java) is single-thread eval today).
- Exception breakpoints on workers; stepping across main ↔ worker.
- Debugger behaviour when a guest thread is blocked on a contended `locking`. JVM monitor parity itself is done ([`CloffleMonitors`](src/jvm/net/javacrumbs/cloffle/CloffleMonitors.java), [`LockingMonitorTest`](src/test/java/net/javacrumbs/cloffle/LockingMonitorTest.java)), but `monitorenter` has no interruptible variant, so such a thread cannot reach a safepoint.

## Implementation todos

- [ ] `DebuggerMultiThreadTest` + thread-safe `SuspendedCallback` helper
- [ ] Future + agent pool breakpoint tests
- [ ] Dual future hit + worker locals + symbol interop at suspension
- [ ] `run-tests` green; update `CLOFFLE_NOTES.md`
