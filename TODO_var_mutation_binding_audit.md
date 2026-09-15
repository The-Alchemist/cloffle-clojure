# Plan — audit Var mutation, thread bindings, and multimethod removal

Companion to [`FIXME_var_mutation_binding_audit.md`](FIXME_var_mutation_binding_audit.md).

The goal is to establish stock-compatible behavior for `alter-var-root`,
`push-thread-bindings` / `pop-thread-bindings`, and `remove-method`. Do not change production code
unless a differential probe or focused regression test demonstrates a mismatch.

## Phase 1 — add a poison-safe differential probe

Create `dev/compat-audit/probe_var_mutation_binding.clj` and run each stateful section in an isolated
fixture. Emit stable `key<TAB>value` records without depending on any function currently under
redefinition.

The probe must:

- save raw Var roots by identity;
- restore roots with direct `Var.bindRoot` calls in an outer `finally`;
- balance every successful `push-thread-bindings` with a direct
  `Var.popThreadBindings` in an outer `finally`;
- use fresh multimethods or restore their method tables between cases;
- avoid `test.clojure.test-helper/with-var-roots*`, whose `doseq` and destructuring make it unsafe
  for this audit;
- run destructive or frame-underflow cases in fresh threads/JVMs so one failure cannot contaminate
  later results.

Add a tools.build audit task that launches the same probe in fresh stock Clojure 1.12 and Cloffle
processes, captures both outputs, and fails on a semantic diff. Keep process construction and
classpath selection inside `build.clj`; do not introduce Ant or Maven commands. Preserve the
outputs or summarize every mismatch in the companion FIXME.

## Phase 2 — `alter-var-root`

Add focused assertions in `test/clojure/test_clojure/vars.clj` for:

1. **Basic update:** the alter function receives the old root followed by all supplied arguments;
   the API returns and installs the new root.
2. **Throwing function:** if the alter function throws, raw-root identity is unchanged.
3. **Validator rejection:** a rejected value leaves the raw root unchanged and does not notify
   watches.
4. **Watch notification:** one successful alteration reports the exact old and new values once.
5. **Dynamic binding interaction:** altering a root while the Var is thread-bound changes the root
   but not the current thread's bound value; popping reveals the new root.
6. **Atomicity:** concurrent increments through `alter-var-root` produce the expected final count.
7. **Unrelated core redefinitions:** repeat a minimal successful alteration while each of `seq`,
   `first`, `next`, and `nth` is temporarily redefined; assert the mock root is restored by identity.

Use disposable Vars and host-only cleanup so a failed assertion cannot poison the suite.

Add Cloffle-specific checks in
`src/test/java/net/javacrumbs/cloffle/AssocLoweringIntrospectionTest.java`:

- warm an `assoc` call site until `KeywordAssoc` specializes;
- alter `#'clojure.core/assoc` to a replacement function;
- prove the existing call site invokes the replacement and activates `doRedefined`;
- restore the original root directly and prove correctness remains while the call site stays on the
  generic path;
- repeat for `dissoc`;
- retain `get` only as a control because its lowering intentionally matches upstream `:inline`
  semantics.

Historical note (2026-09-15): `rootAssumption` was removed; current Var call sites use bounded
identity-guarded caches. Instead, extend `src/test/java/clojure/lang/VarInliningTest.java` with a
direct assertion that `Var.alterRoot` is observed by a warmed call site. That
case is absent from the current lifecycle test even though `bindRoot`, `swapRoot`, `unbindRoot`, and
`setDynamic` are covered.

## Phase 3 — thread-binding frames

Add focused assertions in `test/clojure/test_clojure/vars.clj` for:

1. one push/pop restores the root value and prior `get-thread-bindings` map;
2. nested frames restore the immediately preceding value in LIFO order;
3. multiple Vars are installed in parallel;
4. an exception in a `binding` body and an exception in `with-bindings*` both unwind the frame;
5. a raw child thread does not inherit the parent's dynamic binding;
6. `bound-fn`/future conveyance still sees the captured binding as stock does;
7. attempting to bind a non-dynamic Var throws without changing the current frame;
8. an unmatched pop throws `IllegalStateException` without making subsequent operations unusable;
9. operation and cleanup remain correct while each of `seq`, `first`, `next`, and `nth` is
   temporarily redefined.

Test unmatched-pop behavior on a fresh Java thread using `Var.popThreadBindings()` directly. Do not
pop the evaluator's ambient frame.

Where exact frame identity matters, add Java coverage beside the existing binding tests rather than
reflecting into `Var.dvals` from Clojure.

## Phase 4 — `remove-method`

Extend `test/clojure/test_clojure/multimethods.clj` with isolated multimethod fixtures:

1. preserve the existing warmed concrete-dispatch/default-fallback assertion and make its cache
   regression intent explicit;
2. warm a child dispatch, remove the child method, and prove the next call uses the applicable
   ancestor method;
3. assert `methods` no longer contains the removed dispatch value;
4. removing an absent dispatch value is idempotent and leaves behavior unchanged;
5. re-adding a removed method invalidates the cache again and restores its dispatch;
6. removal remains correct while each of `seq`, `first`, `next`, and `nth` is temporarily
   redefined.

The warmed-call assertions are essential: method-table inspection alone does not prove
`MultiFn.resetCache()` retired a cached dispatch.

Do not test direct redefinition of `remove-method`, `push-thread-bindings`, or
`pop-thread-bindings` as though cleanup should bypass the mock. Those APIs are legitimately
redefinable; replacing the cleanup operation itself is expected to alter behavior.

## Phase 5 — verification and disposition

Run fresh, focused tasks first:

```sh
clojure -T:build run-clj-tests :only-namespace '"clojure.test-clojure.vars"'
clojure -T:build run-clj-tests :only-namespace '"clojure.test-clojure.multimethods"'
clojure -T:build run-tests
```

Then run the full Clojure test suite with its default clean:

```sh
clojure -T:build run-clj-tests
```

Inspect diagnostics only for edited files and distinguish pre-existing warnings from introduced
ones.

Finally update `FIXME_var_mutation_binding_audit.md`:

- mark it **CLEAN** and record commands/results if every stock comparison and specialization check
  passes; or
- replace the audit framing with a concrete **OPEN** bug report for each mismatch, including
  reproduction, cause, blast radius, and fix options.

Keep unrelated fixes and documentation in separate commits.
