# CLEAN AUDIT — Var mutation, thread bindings, and multimethod removal

Measured 2026-09-09. Cloffle matches stock Clojure 1.12.0 for `alter-var-root`,
`push-thread-bindings` / `pop-thread-bindings`, and `remove-method`. No production
change was required: the public wrappers still delegate to host methods that do not
call `clojure.core/seq`, `first`, `next`, or `nth` through Vars, and warmed
`:cloffle/op` call sites for `assoc`/`dissoc` retire on `alter-var-root` the same
way they do on `with-redefs`.

This closed the evidence gap left after
[`FIXME_with_redefs_trapdoor.md`](FIXME_with_redefs_trapdoor.md).

> Historical implementation note (2026-09-15): `rootAssumption` was subsequently removed.
> Var call sites now observe mutation through bounded root-identity guards.

## Commands and results

```sh
clojure -T:build audit-var-mutation-binding
```

26 `key<TAB>value` records, identical under stock 1.12.0 and Cloffle (written to
`target/compat-audit/` by that task).

```sh
clojure -T:build run-clj-tests :only-namespace '"clojure.test-clojure.vars"'
clojure -T:build run-clj-tests :only-namespace '"clojure.test-clojure.multimethods"'
clojure -T:build run-tests
clojure -T:build run-clj-tests
```

- `clojure.test-clojure.vars`: 30 tests, 104 assertions, 0 failures
- `clojure.test-clojure.multimethods`: 15 tests, 154 assertions, 0 failures
- JUnit (`run-tests`): 945 started, 0 failed, including `VarInliningTest`,
  `VarThreadBindingTest`, and `AssocLoweringIntrospectionTest`
- Full `run-clj-tests`: 661 tests, 19130 assertions, 0 failures

Reflection warnings observed during those runs came from `clojure/tools/reader.clj`,
`clojure/pprint/*`, `clojure/math.clj`, and `clojure/core/server.clj` — none from
the files edited for this audit.

## What was proven

1. **`alter-var-root`** — args/return, throw and validator leave the raw root
   unchanged (no watch on reject), one watch fire on success, thread-bound value
   is unchanged until pop, concurrent increments are atomic, and the operation
   still works while `seq`/`first`/`next`/`nth` are rebound. `Var.alterRoot`
   invalidates `rootAssumption`. Warmed `assoc`/`dissoc` sites invoke the
   replacement and stay on `doRedefined` after the original root is restored.
   `get` is a control: altering it does not divert a warmed `KeywordLookup` site.
2. **Thread bindings** — push/pop restore root and `get-thread-bindings`, nested
   LIFO, parallel Vars, exceptional unwind of `binding`/`with-bindings*`, raw
   child threads do not inherit, `bound-fn`/future conveyance matches stock,
   non-dynamic bind throws without changing the frame, unmatched pop on a fresh
   thread throws `IllegalStateException` and leaves the frame usable, and
   nested `Frame` identity is restored. Same cleanup under the four core redefs.
3. **`remove-method`** — warmed concrete dispatch falls back to `:default`;
   warmed child dispatch falls back to the ancestor; `methods` drops the key;
   absent removal is idempotent; re-add restores dispatch; removal still works
   while `seq`/`first`/`next`/`nth` are rebound.

## Pointers

| Item | Location |
| --- | --- |
| Differential probe | [`dev/compat-audit/probe_var_mutation_binding.clj`](dev/compat-audit/probe_var_mutation_binding.clj) |
| tools.build task | `clojure -T:build audit-var-mutation-binding` |
| Clojure Var tests | [`test/clojure/test_clojure/vars.clj`](test/clojure/test_clojure/vars.clj) |
| Multimethod tests | [`test/clojure/test_clojure/multimethods.clj`](test/clojure/test_clojure/multimethods.clj) |
| Root-assumption lifecycle | [`src/test/java/clojure/lang/VarInliningTest.java`](src/test/java/clojure/lang/VarInliningTest.java) |
| Frame identity | [`src/test/java/clojure/lang/VarThreadBindingTest.java`](src/test/java/clojure/lang/VarThreadBindingTest.java) |
| Lowering + `alter-var-root` | [`src/test/java/net/javacrumbs/cloffle/AssocLoweringIntrospectionTest.java`](src/test/java/net/javacrumbs/cloffle/AssocLoweringIntrospectionTest.java) |
| Audit plan | [`TODO_var_mutation_binding_audit.md`](TODO_var_mutation_binding_audit.md) |
| Triggering fixed bug | [`FIXME_with_redefs_trapdoor.md`](FIXME_with_redefs_trapdoor.md) |
