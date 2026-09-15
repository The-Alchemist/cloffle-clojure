# FIXME — `DapTest#stepIntoWithDap` fails only under full-suite load

Open as of 2026-09-15 (`19b5ffbc`). `dapAttachAndSuspendHandshakeWorks` is **fixed**.
`stepIntoWithDap` is **not**; it is `@Ignore`d so the default `run-tests` gate stays green.

## Symptom

Full `clojure -T:build run-tests` (99 containers, ~1203 passing):

```
java.lang.AssertionError: step-into should produce two suspensions; observed 1:
  breakpoint: source=dap_stepin.clj line=2 col=1 text="(double-it 5)"
              anchor=BEFORE frame=null depth=1 breakpoints=1
expected:<2> but was:<1>
```

The eval still returns `10`. Execution does not hang. The breakpoint is correct; `prepareStepInto(1)`
produces **no second suspension**. The debugger never stops inside `double-it`.

## What is already fixed (do not re-fix)

`DapIntegrationTest#dapAttachAndSuspendHandshakeWorks` used to fail in mixed debugger/DAP runs
with:

```
PolyglotException: CompilerException: Unexpected error macroexpanding defn at
  (clojure/core_print.clj:576:1)
Suppressed: RuntimeException: java.net.SocketException: Socket closed
  DebugProtocolServer$Session.writeMessage
  ThreadsHandler.onThreadDisposed
```

Cause: first debugged eval in the JVM compiled part of `clojure.core` (printing) while a DAP client
was attached; session teardown raced macroexpansion. Whether that happened depended on which tests
had already loaded core.

Fix: `DapLifecycleSupport.warmUpRuntime()` (called from `@BeforeClass` in `DapTest`,
`DapIntegrationTest`, `DapMappingSensitiveTest`) evals `(pr-str …)` / `(println …)` **before** any
DAP server exists. `allocatePort()` never hands out a port twice in a JVM (the old
`ServerSocket(0)` + close window lets the OS reissue the same ephemeral port).

That group went from 190 pass / 1 fail to 191 pass. **`stepIntoWithDap` still fails in the full
suite after this fix.** Port recycling and lazy core load are **not** its cause.

## Reproduction matrix (measured)

| Scope | `stepIntoWithDap` |
| --- | --- |
| Isolation (`:filter '"DapTest#stepIntoWithDap"'`) | pass |
| Whole `DapTest` (78 tests) | pass |
| After `BytecodeSerializationRoundTripTest` + `CloffleCoreBytecodeArchiveTest` | pass |
| All 8 debugger/DAP classes together (~191 tests) | pass (`DapIntegrationTest` used to fail here; now pass) |
| `clojure.lang` + `compiler` + `bytecode` + `nodes` + `DapTest` (51 containers, 618 tests) | pass |
| Rest of `net.javacrumbs.cloffle.*` (`[E-T].*` + `DapTest`; 27 containers, 355 tests, slow) | pass |
| Full suite (~99 containers) | **fail**, 1 of 1204, same assertion, same trace |

Neither half of the suite plus `DapTest` reproduces it. The trigger is **cumulative JVM state**
that only crosses the threshold when nearly all test classes have run, not one polluting class.

Half B took ~7.6 min for 355 tests and still passed, so “slow / loaded JVM” is not enough by itself.

## The failing test (after diagnostics)

`src/test/java/net/javacrumbs/cloffle/DapTest.java` `stepIntoWithDap`:

1. Engine with `dap=:<port>`, `dap.Suspend=false`, `dap.WaitAttached=false`.
2. Source `dap_stepin.clj`: `(defn double-it [x] (* x 2))\n(double-it 5)\n`.
3. `DebuggerSession` with a recording callback; breakpoint on **line 2** of that source.
4. First suspend: `prepareStepInto(1)`. Later suspends: `prepareContinue()`.
5. Assert eval = 10 and **exactly two** suspensions.

`describeSuspension` records source, line, column, text, `SuspendAnchor`, top frame name, stack
depth, and breakpoint count. Keep that; a count-only assertion is useless.

Nearby tests that **pass** in the same full-suite run and are useful contrasts:

- `stepIntoThenStepOverWithDap` — `defn work` **and** the call live in one source; body is
  `(def tmp …)` / `(+ tmp 1)`, not a single `(* x 2)`.
- `stepIntoCountGreaterThanOneWithDap` — `defn a` / `defn b` in a **prior** eval
  (`dap_stepin2_setup.clj`); breakpoint is on a **second** source that only contains `(b 5)`.
- `scopeInspectionWithDap` — same split-source pattern (`defn` first, call later) plus
  `prepareStepInto(1)`.

The failing case is special: **the callee is defined in the same source as the call**, and the
callee body is a single arithmetic form. Suspect the root that would receive the step is either
not tagged yet, or is compiled in the same unit as the call site in a way that skips a stoppable
location.

## Leading theory (unproven)

Bytecode DSL roots (`CloffleBytecodeRootNode`, `enableTagInstrumentation = true`) materialize
debugger tags by **reparsing** a root when instrumentation is enabled. `prepareStepInto` needs a
stoppable tagged location **inside the callee**. If the callee’s root is not instrumented (or has
no `StatementTag` / `CallTag` / `RootBodyTag` at a location Truffle will stop on), the step is a
no-op: continue to completion, still return 10.

Why that would be **load-dependent**: something in the full suite changes whether that reparse
happens, or whether the first execution of `double-it` already bound a compiled/cached form that
later instrumentation does not replace. Direct linking was considered and is **off** by default
in `run-tests`; the fail happens with that default. Do not assume DL is the cause without pinning
`withDirectLinkingOff` / `On` around this test.

`frame=null` on the breakpoint suspension is recorded even in the passing isolation run? Check
that before treating it as a smoking gun. The diagnostic only ran in the failing full-suite case
and in isolation (pass, so no assertion dump). Log the same `describeSuspension` line on success
once if you need the comparison.

## Questions for the next agent

1. **Does the callee root have tags at the breakpoint?** From the first `onSuspend`, inspect
   `event.getTopStackFrame()`, walk `getStackFrames()`, and for each frame try
   `getSourceSection()` plus whether the underlying node / bytecode root is instrumented.
   Truffle may expose this via `DebuggerSession` / `SuspendedEvent.getSession()`. If `frame=null`
   is real, the call-site stack frame is already missing debug info.

2. **Is `double-it` a `ClojureClosure` whose `RootCallTarget` was created before the debugger
   session existed?** The session starts **before** `context.eval(code)`, so for this test the
   defn and the call happen with the session already live. Compare with `scopeInspectionWithDap`,
   which defines the fn **before** `startSession`. If you swap the failing test to define
   `double-it` in a prior eval (session already started vs not), which variant flakes?

3. **Does `(* x 2)` emit a statement tag?** If `*` is a Var call / intrinsic with no tagged
   wrapper, step-into into a one-form body may have nowhere to land. That would fail **always**,
   though, unless Graal compilation of that root in a hot JVM removes or skips the instrumented
   bytecode plan. Try making the body `(do (identity x) (* x 2))` or `(let [y (* x 2)] y)` and
   see if the full suite still fails.

4. **Is there a second `DebuggerSession` or DAP instrument attached to the same Engine?**
   Unlikely given unique ports, but log `engine.getInstruments()` and session count. A competing
   session that `prepareContinue`s first would steal the step. The recording callback now keeps
   going after the first unexpected suspend (it only `prepareContinue`s after the first), so extra
   suspensions would show up in the trace. The trace shows **exactly one**, so a stolen second
   stop is less likely than “never stopped”.

5. **Graal compilation of the callee?** `run-tests` sets `CompilationFailureAction=Throw` and
   `BackgroundCompilation=false`. A compiled-without-tags root would still be a candidate: first
   run in a huge suite may compile `InvokeVar` / `*` paths in a way that the instrumented
   interpreter plan is no longer used. Probe by adding
   `.option("engine.Compilation", "false")` on **this test’s** Engine only.

6. **Source URI / line mapping?** Breakpoint hit proves mapping for line 2 of `dap_stepin.clj`
   works. Line 1 (`defn` body) is a different root. If the inner fn’s `SourceSection` is missing
   or points at a synthetic source, step-into cannot bind a location.

## Workarounds (if you must ship a green gate)

Prefer fixing the engine, not silencing the test.

- **Do not** `assertTrue(suspensions >= 1)` — that would accept a broken step-into.
- **Do not** `@Ignore` without a JUnit 5 tag / comment pointing here.
- Acceptable temporary gates, in order:
  1. Fork DAP tests in their own JVM from `run-tests` (isolates cumulative state; does not fix
     production stepping after a long-lived Engine).
  2. Split the test like `stepIntoCountGreaterThanOneWithDap`: define the fn in a prior
     `context.eval`, breakpoint only on the call source. If that passes in the full suite, you
     have a **reproducer of the bug shape** (same-source defn+call) rather than a real fix.
  3. Tag `stepIntoWithDap` and exclude it from the default `run-tests` via `:exclude-tags`,
     keeping it in a nightly / DAP-only job.

## How to iterate without 14-minute full suites

Full suite is the only known repro. Do **not** parallelize multiple `run-tests` — they share
`target/`.

Bisection by package is exhausted for “one culprit class”. Next experiments should be
**in-test probes** (compilation off, split source, extra `do` in the body, dump tags) followed
by **one** full-suite run each. Log the `trace` list even on success (`System.err`) so isolation
vs full-suite traces can be diffed without failing.

`:args` example now that `run-tests` supplies `--scan-class-path` unless a selector is present:

```sh
clojure -T:build run-tests :fresh false \
  :args '["--include-classname=^net[.]javacrumbs[.]cloffle[.]Dap.*$" "--scan-class-path"]'
```

`--scan-class-path` is redundant after the `:args` fix but still valid. `:filter` remains ignored
when `:args` is non-empty.

`:filter '"DapTest#method"'` matches by substring on FQCN **and** simple name. A helper named
`DapTestSupport` collided; it was renamed to `DapLifecycleSupport`. Do not reintroduce a class
whose name contains `DapTest` as a prefix/substring of another test class.

## Files

| Path | Role |
| --- | --- |
| `src/test/java/net/javacrumbs/cloffle/DapTest.java` | failing `stepIntoWithDap` + `describeSuspension` |
| `src/test/java/net/javacrumbs/cloffle/DapLifecycleSupport.java` | warmup + unique ports |
| `src/test/java/net/javacrumbs/cloffle/DapIntegrationTest.java` | previously failing handshake; fixed |
| `src/jvm/net/javacrumbs/cloffle/bytecode/CloffleBytecodeRootNode.java` | `enableTagInstrumentation = true` |
| `src/jvm/net/javacrumbs/cloffle/Clojure.java` | `@ProvidedTags` for debugger |
| `src/test/java/net/javacrumbs/cloffle/CloffleEvalTestSupport.java` | `newDebuggerContext` (ClearDeadLocals off) |

Commit with the lifecycle fix + diagnostics: `19b5ffbc`.
