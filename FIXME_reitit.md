# Reitit / Sieppari `AsyncContext` protocol dispatch on `nil`

Instructions for another agent. This is a **real Cloffle bug**, not a flaky test harness. Stock JVM Clojure Phase 1 of `compat-test` does not produce this exception.

Related but **separate** Reitit issues live in [`FIXME.md`](FIXME.md): Pedestal arity reflection (`arities-test`) and `reitit.walk` / `PersistentTuple`. Do not conflate those with this ticket.

---

## Symptom

During Cloffle Phase 2 of the Reitit compatibility suite, background `core.async` worker threads crash with:

```text
Exception in thread "async-io-3"
net.javacrumbs.cloffle.nodes.ClojureException: IllegalArgumentException:
No implementation of method: :async? of protocol: #'sieppari.async/AsyncContext found for class: nil
Caused by: java.lang.IllegalArgumentException:
No implementation of method: :async? of protocol: #'sieppari.async/AsyncContext found for class: nil
	at java.base/jdk.internal.reflect.DirectConstructorHandleAccessor.newInstance(...)
	at clojure.lang.Reflector.invokeConstructor(Reflector.java:334)
	at net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode$NewObject.doNew(CloffleBytecodeRootNode.java:560)
	at net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNodeGen$NewObject_Node.execute(...)
	...
```

The `async-io-*` thread numbers vary with the pool (this run: `async-io-3`, then `async-io-1`, then `async-io-2`).

Because the callback never completes, `reitit.http-test/core-async-test` times out:

```xml
<testcase name="core-async-test" classname="reitit.http-test">
    <failure>expected: (= response (deref respond 100 :reitit.http-test/timeout))
actual: (not (= {:status 200, :body "ok"} :reitit.http-test/timeout))
    at: CloffleBytecodeRootNodeGen.java:22127</failure>
</testcase>
```

Phase 1 (Maven Clojure 1.12.0) of the same project: **0 failures, 0 errors**.

A full `:reitit` Phase 2 run then fails XML parsing (`SAXParseException` / Unicode `0x1b`) **before** `compat-test` prints the Phase 1 vs Phase 2 diff table. That is matcher-combinators ANSI in **other** remaining Reitit failures (OpenAPI/Swagger), not the root of this protocol bug. Parse `target/surefire-reports/reitit-cloffle/TEST-results.xml` by hand (or strip `0x1b`) to list cases.

---

## Reproduction status (2026-09-04)

Reproduced on this tree. Nothing about the symptom has gone stale.

### Full suite (canonical, ~2.5 min after compile)

```sh
export ENV=local && eval "$(direnv export zsh)"
clojure -T:build compat-test :project :reitit
```

| Phase | Result |
| --- | --- |
| 1 Maven Clojure 1.12.0 | 59 tests, 440 assertions, **0 failures, 0 errors** |
| 2 Cloffle | 59 tests, 439 assertions, **9 failures, 1 error**, then `SAXParseException` on the Cloffle XML |

Phase 2 stderr (three times, uncaught on the `core.async` pool):

```text
Exception in thread "async-io-3" net.javacrumbs.cloffle.nodes.ClojureException: IllegalArgumentException:
No implementation of method: :async? of protocol: #'sieppari.async/AsyncContext found for class: nil
Caused by: java.lang.IllegalArgumentException: ... class: nil
	at clojure.lang.Reflector.invokeConstructor(Reflector.java:334)
	at net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode$NewObject.doNew(CloffleBytecodeRootNode.java:560)
```

Bottom of each stack is a `ClojureClosure` running as `AFn.run` on `ThreadPoolExecutor` (`async-io-*`). Host frames still stop at `NewObject` / `Reflector.invokeConstructor`; there is **no guest Clojure stack** in the dump yet.

Cloffle failures parsed from the XML (do not conflate):

| Case | This ticket? |
| --- | --- |
| `reitit.http-test/core-async-test` timeout (`::timeout` vs `{:status 200, :body "ok"}`) | **yes** |
| `reitit.pedestal-test/arities-test` | no — [`FIXME.md`](FIXME.md) / [reitit#795](https://github.com/metosin/reitit/pull/795) |
| `reitit.openapi-test/all-parameter-types-test`, `openapi-test` | no — OpenAPI ordering |
| `reitit.swagger-test/all-parameter-types-test` | no — Swagger ordering |
| `reitit.swagger-test/swagger-test` (error) | no — same family; this case is what injects `0x1b` into the XML |

### Isolated deftest (negative control, still green)

```sh
clojure -T:build compat-test :project :reitit :only-var '"reitit.http-test/core-async-test"'
```

Both phases: 1 test, 1 assertion, 0 failures. `RESULT: IDENTICAL`. The test body is not broken; the full-suite load set is.

### Load order on the full run

`run_external_tests_surefire.clj` prints `Running tests for:` then `require`s **all** namespaces before `run-tests`. Observed Phase 2 list:

```text
reitit.http-coercion-test reitit.http-test
reitit.http.interceptors.exception-test reitit.http.interceptors.multipart-test
reitit.http.interceptors.muuntaja-test reitit.http.interceptors.parameters-test
reitit.pedestal-test
... ring middleware / walk / openapi / swagger
```

`reitit.http-test` is listed before `reitit.pedestal-test`, but Pedestal (and thus `io.pedestal.http.impl.servlet-interceptor`) is loaded before `core-async-test` **executes**. That matches the hypothesized trigger. The Pedestal-require-then-call micro-repro was **not** re-run on this date.

---

## How to reproduce (reliable)

The failure is **load-order / JVM-state dependent**. Commands and latest numbers are in **Reproduction status** above. Isolated `core-async-test` **passes**; the full suite is the reliable trigger.

### Minimal load-order repro (from prior investigation; not re-run 2026-09-04)

Load Pedestal's servlet interceptor **before** running the test:

```clojure
(require 'io.pedestal.http.impl.servlet-interceptor)
(require 'reitit.http-test)
(reitit.http-test/core-async-test)
```

Requiring `io.pedestal.http.impl.servlet-interceptor` alone was enough to break a subsequent direct call of `core-async-test` under Cloffle.

---

## What is going wrong (current understanding)

Sieppari interceptor execution is protocol-driven (`sieppari.async/AsyncContext`, method `:async?`). Something on a `core.async` worker thread calls that protocol with **literal `nil`**. Clojure then constructs `IllegalArgumentException` via `NewObject` (the stack frames at `CloffleBytecodeRootNode$NewObject.doNew`).

There is **no public `-async?` var**. The exception’s `:async?` is `(.methodk cache)` in `-cache-protocol-fn`. The Java interface is `sieppari.async.AsyncContext`; the generated method is munged (`async_QMARK_`).

**`Object`’s `async?` never applies to `nil`.** `find-protocol-impl` (`src/clj/clojure/core_deftype.clj` 537–552) does `(and c (or … (impl Object)))`. When `x` is `nil`, `c` is `nil`, that branch is skipped. Stock Clojure throws the **same** `class: nil` message if `nil` is actually passed. Phase 1 staying green means JVM Clojure never feeds `nil` into `async?` for this test.

A **missing Channel impl** would name `ManyToManyChannel` (or `:catch`), not `nil`. Pedestal’s `WriteableBodyAsync` extend onto `Channel` is still a load-order suspect for cache/identity issues, but it does not by itself pass `nil` into `async?`.

Cloffle `InvokeProtocol` (`CloffleBytecodeRootNode.java` 615–644): `receiver == null` skips the interface invoke and does `var.applyTo(args)` → `-cache-protocol-fn` throws. That is **correct** handling of a nil receiver, not the bug.

The exception is uncaught on `async-io-*`, so the Ring `respond` promise is never delivered → 100ms timeout.

---

## Source-level flow (`core-async-test` → Sieppari)

Sources (extracted 2026-09-04; jars in `~/.m2`):

| What | Path |
| --- | --- |
| Test | `src/external-projects/reitit/test/clj/reitit/http_test.clj` `core-async-test` **302–314** |
| Sieppari 0.0.0-alpha13 | `~/.m2/repository/metosin/sieppari/0.0.0-alpha13/sieppari-0.0.0-alpha13.jar` (git `f46ed4be` in `pom.properties`) |
| Reitit executor | `reitit/interceptor/sieppari.clj` (`::handler` unwrap); 3-arity Ring in `reitit/http.cljc` **154–168** |
| core.async 1.8.741 (Reitit) | `clojure/core/async/impl/go.clj`, `ioc_macros.clj` `return-chan` **84–89**, `dispatch.clj` (`async-io-%d`) |
| Pedestal 0.6.4 (load trigger) | `io.pedestal/pedestal.service` jar `io/pedestal/http/impl/servlet_interceptor.clj` |
| Protocol throw | `src/clj/clojure/core_deftype.clj` `find-protocol-impl` **537–552**, `-cache-protocol-fn` **583–591** |

Test body:

```clojure
{:interceptors [{:enter #(a/go %)}]
 :handler (fn [_] (a/go response))}  ; response = {:status 200, :body "ok"}
```

1. **Interceptor `#(a/go %)`** — `%` is Sieppari `RequestResponseContext` (`sieppari.core` 13–15). Channel **value** must be that same context. `a/go` **synchronously** returns a `ManyToManyChannel` (`go-impl`).
2. **Handler** — Reitit’s executor prefers `::handler` (`reitit/interceptor/sieppari.clj` 10–15), so Sieppari wraps the fn (`sieppari/interceptor.cljc` 29–33): `(set-result ctx (handler (:request ctx)))`. Handler parks `{:status 200, :body "ok"}`. `set-result` (10–15) sees a channel → `continue` → later `assoc` `:response` on the original ctx.
3. 3-arity Ring → `sieppari.core/execute` 121–129: `RequestResponseContext` → `enter` → `leave` → `deliver-result` with `get-result` = `:response`. Reitit `respond'` only delivers if the body is truthy.

`a/async?` call sites in `sieppari.core`:

| Site | Lines | Argument |
| --- | --- | --- |
| `-try` | 17–26 | interceptor return `ctx*` |
| `enter` | 49–63 | current `ctx` (context **or** channel) |
| `leave` | 38–47 | same |
| `deliver-result` | 73–79 | same |
| `await-result` | 66–71 | **unused** (this test is 3-arity) |
| `set-result` | interceptor.cljc 10–12 | handler/channel **only if** `(some? response)` — **not** for `nil` |

Channel impl (`sieppari/async/core_async.cljc` 8–15):

```clojure
(async? [_] true)
(continue [c f] (go (f (cca/<! c))))
(catch [c f] (go (let [c (cca/<! c)]
                   (if (exception? c) (f c) c))))
```

Happy path:

1. `enter` runs `#(a/go %)` on the **caller** thread → `-try` sees a channel → `async?` true → `catch` wraps another `go`.
2. `enter` recast on that channel → `continue` → **`async-io-*`** runs `(enter (<! c))` with the context. Pool name is `dispatch.clj` 71–94 (`:core-async-dispatch` → `:io` → `async-io-%d`).
3. Handler `go` of the response map → `set-result` / `continue` → context with `:response {:status 200, :body "ok"}`.
4. `leave` (no `:leave` fns) → `deliver-result` → `(respond {:status 200, :body "ok"})`.

### Ranked `nil` insertion points

1. **`Channel/continue`**: `(go (f (cca/<! c)))` with `f` ∈ `{enter, leave, deliver-result}`. `return-chan` (`ioc_macros.clj` 84–89): **if the go body is `nil`, nothing is put; the channel is closed**. `<!` then yields `nil`. Next line is `(a/async? nil)`. That is the normal core.async meaning of “empty close”. Uncaught on the go thread → promise never delivered → 100ms `::timeout`.
2. **`-try` on the worker** after the first hop: if Cloffle’s compiled `a/go` **returns `nil` instead of the channel**, `(a/async? ctx*)` throws on `async-io-*`. The first interceptor `go` runs on the **test** thread; a nil-returning `go` there would **not** match `async-io-*`. The **handler** `go` runs after resume — that matches the thread name.
3. **`catch` go** (`core_async.cljc` 13–14): if `<!` is already `nil`, the body is `nil` → `return-chan` closes empty → (1) on the next `continue`.
4. **Not** `set-result` on `nil`: `some?` short-circuits. That would `assoc :response nil` and Reitit `respond'` would run the default handler — a **wrong status**, not a hang.
5. **Not** `put!` of `nil`: `channels.clj` throws `"Can't put nil on channel"`, a different exception.

---

## Pedestal load graph (why requiring `servlet-interceptor` matters)

`reitit.pedestal` does **not** require `servlet-interceptor` directly. It requires `io.pedestal.http`, which does (`pedestal.service` 0.6.4 `io/pedestal/http.clj` 13–24).

**This ns’s protocol extends** (`servlet_interceptor.clj`):

- `WriteableBody` (38–89) onto `[B`, `String`, `IPersistentCollection`, `Fn`, `File`, `InputStream`, NIO types, and **`nil`**.
- `WriteableBodyAsync` (95–135) onto **`clojure.core.async.impl.protocols.Channel`** (uses `async/go`), plus NIO Channel/ByteBuffer.

`Channel` is core.async’s protocol interface (`defprotocol Channel` `close!`/`closed?`). `ManyToManyChannel` implements it **inline**, not via `extend`. It is **not** `IDeref` (so `AsyncContext`’s `IDeref` impl is not a dispatch competitor after `sieppari.async.core-async` loads). Pedestal’s POM wants core.async **1.6.673**; Reitit compat uses **1.8.741** — same `Channel` interface, different jar if both resolve.

**Also loaded transitively (not sufficient by themselves):**

- `io.pedestal.log`: many **`nil`** extends (`LoggerSource`, MDC, metrics, tracing).
- `io.pedestal.http.request`: `ProxyDatastructure` / `ContainerRequest` onto **`nil`**.
- `clojure.java.io`: `Coercions`/`IOFactory` already extend **`nil`** on any Clojure boot. Isolated `:only-var` still passes, so “any nil protocol extend” is **not** a sufficient trigger.

**On JVM Clojure, Pedestal cannot legally pollute `AsyncContext`.** `extend` does `alter-var-root` on **that** protocol var (`core_deftype.clj` ~831). `MethodImplCache` is per protocol method fn. Two protocols extending the same `Channel` interface is normal and used on JVM every day.

**Cloffle-specific ways Pedestal could still interact:**

1. **Shared empty persistent map / broken COW** so `assoc-in [:impls atype]` on Pedestal protocols mutates a map still visible on `AsyncContext`. Then `(get (:impls AsyncContext) nil)` or Channel could be WriteableBody(Async)’s mmap. `(:async? mmap)` would be **nil** → **same exception text**.
2. **`MethodImplCache` / `InvokeProtocol` keyed by class only** (not protocol). Pedestal’s `nil` or `Channel` cache entry reused for `async?`.
3. **Not pollution:** `a/go` / `ClojureClosure` on `async-io-*` really delivers **`nil`** into `async?`. Pedestal load adds more `go` users (`write-body-async`, `interceptor.chain/go-async`) and may change timing without touching `:impls`.

---

## Suggested investigation order

1. ~~Reproduce with full `compat-test :project :reitit`. Confirm `core-async-test` timeout + `async-io-*` stderr. Confirm Phase 1 is clean.~~ **Done 2026-09-04.**
2. ~~Confirm `:only-var '"reitit.http-test/core-async-test"'` still passes.~~ **Done 2026-09-04** (still identical / green).
3. ~~Map Sieppari `async?` sites, go/`return-chan` nil semantics, Pedestal extend graph.~~ **Done 2026-09-04.**
4. ~~Re-establish the Pedestal require trigger & namespace-level bisect.~~ **Done 2026-09-04.** (See "Debugging Attempts & Strategy Pivot" below).
5. ~~Capture guest stack and inspect `:impls`.~~ **Done 2026-09-04.** (Stack captured; `:impls` clean and uncorrupted).
6. ~~Form-level bisect inside `servlet-interceptor`.~~ **Done 2026-09-04.** (Isolated to `sa/catch` channel-yield bug).

---

## Debugging Attempts & Strategy Pivot (2026-09-04)

### Strategy 1: Protocol Dispatch / Dynamic Method Cache / `:impls` Pollution (Completed - 3 Attempts)

**Hypothesis:** Pedestal's protocol extensions (`WriteableBody`, `WriteableBodyAsync`) pollute Cloffle's protocol method table or `MethodImplCache`, causing `sieppari.async/AsyncContext` to lose its Channel implementation or dispatch `async?` onto `nil`.

#### Attempt 1: Instrument Cloffle `InvokeProtocol` and Clojure `-cache-protocol-fn`
- **Action:** Added runtime logging to `CloffleBytecodeRootNode.java` (`InvokeProtocol.doInvoke`) and `core_deftype.clj` (`-cache-protocol-fn`) to dump protocol var, receiver class, protocol `:impls` keys, and guest Truffle call stack.
- **Evidence:**
  - Guest stack:
    ```text
    InvokeProtocol with null receiver: #'sieppari.async/async?
    Guest stack: sieppari.core/deliver-result/fn--19742
      at sieppari.async.core-async/.../state-machine--9955--auto----20169/fn--20171
      at clojure.core.async.impl.ioc-macros/run-state-machine
      at clojure.core.async.impl.ioc-macros/take!/fn--5256
      at clojure.core.async.impl.channels/appm/fn--5079
    ```
  - Protocol state at crash:
    `protocol: #'sieppari.async/AsyncContext`, `valType: "nil"`, `implKeys: (java.lang.Object clojure.lang.IDeref java.util.concurrent.CompletionStage clojure.core.async.impl.protocols.Channel)`.
- **Conclusion:** **REJECTED.** Protocol `:impls` is NOT corrupted and Channel is still registered. The receiver passed to `async?` is literally `nil`.

#### Attempt 2: Systematic Namespace-Level Bisect (Steps A through H)
- **Action:** Created a minimal execution harness via `write-reitit-repro-argfile` in `build.clj` and tested each hypothesized namespace require prior to running `core-async-test`:
  - Step 0: `reitit.http-test` alone -> **PASS** (`:DONE`)
  - Step A: `(require 'clojure.core.async)` -> **PASS** (`:DONE`)
  - Step B: `(require 'io.pedestal.log)` -> **PASS** (`:DONE`)
  - Step C: `(require 'io.pedestal.http.request)` -> **PASS** (`:DONE`)
  - Step D: `(require 'io.pedestal.interceptor)` -> **PASS** (`:DONE`)
  - Step E: `(require 'io.pedestal.http.route)` -> **PASS** (`:DONE`)
  - Step F: `(require 'io.pedestal.http.container)` -> **PASS** (`:DONE`)
  - Step G: `(require 'io.pedestal.http.impl.servlet-interceptor)` -> **FAILS** (immediately reproduces `AsyncContext found for class: nil` on `async-io-*`).
- **Conclusion:** **CONFIRMED.** The trigger is isolated specifically to `io.pedestal.http.impl.servlet-interceptor`.

#### Attempt 3: Form-Level Bisect and Sieppari Execution Step Tracing
- **Action:**
  - Tested isolated `WriteableBody` (`nil` extend) and `WriteableBodyAsync` (`Channel` extend) in a clean environment -> **BOTH PASS**.
  - Traced execution in `core-async-test`:
    - Interceptor `:enter` runs and produces `(a/go ctx)`.
    - Handler never runs (`HANDLER` never reached).
    - Isolated to Sieppari's `AsyncContext` `catch` method on `clojure.core.async.impl.protocols.Channel`:
      ```clojure
      (catch [c f] (go (let [c (cca/<! c)]
                         (if (exception? c) (f c) c))))
      ```
    - When `servlet-interceptor` is loaded:
      `(sa/catch ch1 (fn [e] :err))` yields `ch1` (the `ManyToManyChannel` itself! `identical? val ch1: true`), NOT the unpacked value from the channel!
    - **Mechanism of Failure Identified:**
      1. Interceptor returns Channel 1 (`ch1`).
      2. `-try` calls `(sa/catch ch1 ...)`, which returns Channel 2 (`ch2`).
      3. Due to the bug, `ch2` produces `ch1` (the channel) instead of the unwrapped context.
      4. `enter` sees `(a/async? ch1)` is true, so it calls `(a/continue ch1 enter)`.
      5. But `ch1` was ALREADY consumed by step 2! Taking from `ch1` a second time yields `nil` (closed channel).
      6. `enter` is called with `nil`.
      7. `(enter nil)` calls `(if (a/async? ctx) ...)` with `ctx == nil`.
      8. `No implementation of method: :async? of protocol: #'sieppari.async/AsyncContext found for class: nil` is thrown on `async-io-*`.
- **Conclusion:** Strategy 1 (protocol cache / dispatch corruption) has completed 3 attempts and yielded the true root cause: this is NOT a protocol dispatch bug; it is an issue with `core.async/go` state machine execution yielding the outer channel argument instead of the inner taken value.

---

### Strategy 2: `core.async` `go` State Machine / Lexical Shadowing & Macro Environment (RESOLVED)

**Root Cause Found:**
The issue was in `PersistentShapeMap.getLookupThunk` and `PersistentShapeMap16.getLookupThunk`:
- In standard Clojure keyword lookup call sites (`KeywordLookupSite`), an `ILookupThunk` is installed.
- The bytecode pattern emitted by Clojure compiler for keyword invocation is:
  ```java
  Object res = thunk.get(target);
  if (res == thunk) { // fault!
      thunk = site.fault(target);
      res = thunk.get(target);
  }
  ```
- The contract of `ILookupThunk.get(target)` requires that if `target` cannot be handled by the specialized thunk (e.g. `target` is not a `PersistentShapeMap` matching that shape, or is a `PersistentHashMap`), the thunk **must return `this`** (the thunk itself) as a sentinel to trigger `KeywordLookupSite.fault(target)`.
- However, `PersistentShapeMap.getLookupThunk` returned `target` instead of `this`!
  ```java
  // In PersistentShapeMap.java and PersistentShapeMap16.java:
  return sm.getVal(slot);
  // ...
  return target; // BUG! Returned target instead of this (the thunk instance)
  ```
- **How it caused the bug:**
  1. `core.async/go` internally invokes `clojure.tools.analyzer.jvm` to parse and analyze the AST.
  2. AST nodes are represented as Clojure maps. Small AST nodes are `PersistentShapeMap`, but large AST nodes (like complex `if` or `invoke` nodes) are `PersistentHashMap`.
  3. When `tools.analyzer` evaluates `(:env ast)` using call-site keyword lookup, the first small AST node created an `ILookupThunk` specialized for `PersistentShapeMap`.
  4. Later, when an AST node happened to be a `PersistentHashMap`, `thunk.get(ast)` returned `ast` itself instead of `thunk` (the fault sentinel).
  5. As a result, the code evaluated `(:locals (:env ast))` on `ast` directly! Since `ast` has no `:locals` key (only `:env` has `:locals`), `(:locals env)` returned `nil`.
  6. Because `(:locals env)` returned `nil`, `core.async.impl.go/reads-from` failed to find any local variables in `RawCode`, returning `()` instead of reading from the register storing the taken channel value.
  7. Consequently, the state machine fell back to reading the outer channel object `c` rather than the taken result of `(<! c)`.
  8. When `sieppari.async/catch` ran, it took from the channel and then returned the original channel object `ch1` instead of the unpacked value. Sieppari then tried to take from `ch1` a second time, which yielded `nil` and threw:
     `No implementation of method: :async? of protocol: #'sieppari.async/AsyncContext found for class: nil`

**Fix Applied:**
- Updated `PersistentShapeMap.getLookupThunk` and `PersistentShapeMap16.getLookupThunk` to return `this` (the `ILookupThunk` instance) on mismatch so `KeywordLookupSite.fault(target)` is properly invoked.
- Added regression test `testKeywordLookupThunkProtocol` in `PersistentShapeMapTest.java`.
- Verified that `reads-from` in `repro_shadow` now correctly returns `(inst_15961 inst_15958)` after `io.pedestal.http.impl.servlet-interceptor` is loaded.
- Verified that `reitit.http-test/core-async-test` passes completely under Cloffle.
- Verified that full `compat-test :project :sieppari` (68 tests, 136 assertions) passes with 0 failures and 0 errors.
- Verified all 850 Cloffle JUnit unit tests pass.

---

## Success criteria (ALL MET - RESOLVED)

- [x] Full `clojure -T:build compat-test :project :reitit` Phase 2: no `async-io-*` `AsyncContext` / `class: nil` exceptions.
- [x] `reitit.http-test/core-async-test` passes under Cloffle (response `{:status 200, :body "ok"}`, not `::timeout`).
- [x] Isolated `:only-var` for that deftest passes (`clojure -T:build compat-test :project :reitit :only-var '"reitit.http-test/core-async-test"'`).
- [x] Full `sieppari` test suite passes (`clojure -T:build compat-test :project :sieppari`: 68 tests, 136 assertions, 0 failures, 0 errors).
- [x] Phase 1 and Phase 2 identical / green for `core-async-test` and `sieppari`.
- [x] No debug prints left in `InvokeProtocol` / `NewObject` / `PersistentShapeMap`.

Existing Reitit gaps in `FIXME.md` (arity reflection until upstream [reitit#795](https://github.com/metosin/reitit/pull/795); OpenAPI/Swagger ordering) may still fail. This file is done when **this** async protocol crash is gone. Result: **RESOLVED**.

---

## Pointers

| Item | Location |
| --- | --- |
| Protocol invoke opcode | `src/jvm/net/javacrumbs/cloffle/bytecode/CloffleBytecodeRootNode.java` (`InvokeProtocol`) |
| Protocol lookup / throw | `src/clj/clojure/core_deftype.clj` `find-protocol-impl`, `-cache-protocol-fn` |
| Exception wrapping | `net.javacrumbs.cloffle.nodes.ClojureException` |
| Compat runner / `:only-var` | `build.clj` (`compat-test`, `parse-only-var-sym`) |
| Reitit submodule | `src/external-projects/reitit` (after `update-submodules`) |
| Sieppari dep | `metosin/sieppari` `0.0.0-alpha13` jar; `sieppari/core.cljc`, `async.cljc`, `async/core_async.cljc`, `interceptor.cljc` |
| Test ns | `reitit.http-test` / `core-async-test` (reitit-http module tests) |
| Test body | `src/external-projects/reitit/test/clj/reitit/http_test.clj` lines 302–314 |
| Reitit executor | `reitit/interceptor/sieppari.clj`; 3-arity `reitit/http.cljc` |
| Surefire runner | `src/script/run_external_tests_surefire.clj` (all namespaces `require`d before `run-tests`) |
| Pedestal trigger ns | `io.pedestal/pedestal.service` 0.6.4 `servlet_interceptor.clj` (`WriteableBody` +nil, `WriteableBodyAsync` +Channel) |
| Prior chats | Repro 2026-09-04; source-flow + Pedestal graph session 2026-09-04 (resume at investigation step 4). |
