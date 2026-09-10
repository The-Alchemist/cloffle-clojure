# OPEN — `clojure.test-clojure.reducers/test-fold-runtime-exception`

Instructions for another agent. This is a **real Cloffle bug on `primitive-specialization`**, not a flaky harness. Stock JVM Clojure throws; Cloffle’s `r/fold` returns normally, so `thrown?` sees `nil`.

Do **not** conflate with [`FIXME.md`](FIXME.md) §2 / `*unchecked-math*`. That ticket was `reitit.walk-test/keywordize=walk-keywordize`. This one is a map `fold` that is supposed to surface `IndexOutOfBoundsException` from the reducing function.

---

## Symptom

```text
FAIL in (test-fold-runtime-exception)
expected: (thrown? IndexOutOfBoundsException
            (let [test-map-count 1234
                  k-fail (rand-int test-map-count)]
              (r/fold (fn ([])
                        ([ret [k v]])
                        ([ret k v] (when (= k k-fail)
                                     (throw (IndexOutOfBoundsException.)))))
                      (zipmap (range test-map-count) (repeat :dummy)))))
  actual: nil
      at: CloffleBytecodeRootNode.java:588
```

`actual: nil` means the `r/fold` form **completed without throwing**. `clojure.test/thrown?` only binds the exception if one escapes; a successful return is reported as `nil`. The `at:` line is `NewObject` (exception construction in the compiled test), not proof that the throw ever ran.

Phase 1 (Maven Clojure 1.12) of the same deftest is green when run on a tree that does not have this Cloffle path.

---

## Reproduction status (2026-09-09)

Isolated run is enough; the full Clojure suite is not required.

```sh
export ENV=local && eval "$(direnv export zsh)"
clojure -T:build run-clj-tests \
  :only-var '"clojure.test-clojure.reducers/test-fold-runtime-exception"'
```

| Tree | Result |
| --- | --- |
| `primitive-specialization` at `fdce679c` (after gated `:inline`) | **FAIL**, `actual: nil` |
| Same branch at `15451183` (primitives only, **before** unchecked-math) | **FAIL**, same assertion |
| `fix/unchecked-math-inline` at `b1eff140` (no `Numbers*` `:cloffle/op`) | **PASS** (1 test, 1 assertion, 0 failures) |

So this is **not** caused by restoring `isInline` / `*unchecked-math*`. It showed up on the primitives branch and is absent on the unchecked-math-only branch.

The test body (stock, `test/clojure/test_clojure/reducers.clj` **83–91**):

```clojure
(deftest test-fold-runtime-exception
  (is (thrown? IndexOutOfBoundsException
               (let [test-map-count 1234
                     k-fail (rand-int test-map-count)]
                 (r/fold (fn ([])
                           ([ret [k v]])
                           ([ret k v] (when (= k k-fail)
                                        (throw (IndexOutOfBoundsException.)))))
                         (zipmap (range test-map-count) (repeat :dummy)))))))
```

---

## What is going wrong (current understanding)

`r/fold` on a `PersistentHashMap` of 1234 entries (`zipmap` of `(range 1234)`) dispatches to `clojure.lang.PersistentHashMap.fold` (`PersistentHashMap.java` **247–260**), which runs `combinef` / `reducef` through `fjinvoke` / `ForkJoinTask`. Leaves call **3-arity** `reducef` from `BitmapIndexedNode.fold` → `NodeSeq.kvreduce`. Array nodes split work onto ForkJoin tasks (`ArrayNode.fold` / `foldTasks`).

The reducing fn throws **only** when `(= k k-fail)`. Keys from `(range n)` are boxed **Long**. `k-fail` is `(rand-int n)`, a boxed **Integer**. Stock `clojure.core/=` (`Util.equiv` → numeric equality) treats those as equal, so some leaf **must** throw, and the ForkJoin join must deliver that exception to the test thread as `IndexOutOfBoundsException` (or a wrapper `thrown?` still matches — here it does not, because **nothing** is thrown).

Two independent ways to get `actual: nil`:

1. **The `when` never fires.** `(= k k-fail)` is false for every key, so fold finishes. Candidate: a `=` / numeric comparison path that does not treat `Long` and `Integer` as `=` the way stock does. Note: `:cloffle/op` `NumbersEquiv` is on **`==`**, not `=`. Do not assume they share a lowering.
2. **The throw fires on a ForkJoin worker and never reaches the test thread.** Candidate: guest `throw` becomes `ClojureException` / Truffle exception that `ForkJoinTask.adapt` / `.join` does not rethrow onto the caller, so `fjinvoke` returns a combined value. `ClojureClosure.invoke` (`doCall3`) only special-cases `FrameSlotTypeException`; other guest throws should still propagate — verify on the **worker** thread, not just the test thread.

Do not treat “`at: CloffleBytecodeRootNode.java:588`” as evidence of (2). That is where `is` / `NewObject` for this form was compiled.

---

## Suggested investigation order

1. In a failing JVM, bind `k-fail` to a constant (e.g. `0`) and print `(= (long 0) (int 0))`, `(class k)`, `(class k-fail)` from inside the 3-arity. If `=` is false for `0`/`0`, hypothesis 1 is confirmed.
2. Replace `(= k k-fail)` with `(== k k-fail)` or `(identical? (long k) (long k-fail))`. If that makes the test throw, the defect is `=` (or unboxing of one operand), not fold/FJ.
3. Throw unconditionally from 3-arity. If `thrown?` still gets `nil`, the defect is exception transport across `fjinvoke` / `ForkJoinPool.invoke`.
4. Run the same fold **without** ForkJoin: `(r/reduce …)` / `kvreduce` on the same map and fn. If reduce throws and fold does not, isolate `fjtask` / worker `ClojureClosure`.
5. Confirm `zipmap` of 1234 entries is `PersistentHashMap` (not a shape map; those cap at 16 keys).

---

## Success criteria

- [ ] `clojure -T:build run-clj-tests :only-var '"clojure.test-clojure.reducers/test-fold-runtime-exception"'` is 1 test, 1 assertion, 0 failures under Cloffle.
- [ ] Stock still throws `IndexOutOfBoundsException` (do not weaken the assertion to `Exception` / `Throwable`).
- [ ] Isolated constant `k-fail` (no `rand-int`) also throws, so the fix is not “got lucky with equality”.

---

## Pointers

| Item | Location |
| --- | --- |
| Test | `test/clojure/test_clojure/reducers.clj` `test-fold-runtime-exception` **83–91** |
| Fold protocol / FJ wrappers | `src/clj/clojure/core/reducers.clj` `fjtask` **24–34**, `CollFold` `PersistentHashMap` **331–334** |
| Host fold | `src/jvm/clojure/lang/PersistentHashMap.java` `fold` **247–260**, `ArrayNode.fold` **476–489**, `BitmapIndexedNode.fold` **807–808** |
| Guest throw / `thrown?` unwrap | `CloffleBytecodeRootNode` catch matching (~526), `ClojureClosure.doCall3` |
| `=` vs `==` | `core.clj` `=` ~765 (no `:cloffle/op`); `==` `:cloffle/op {2 :NumbersEquiv}` |
| Isolated task | `build.clj` `run-clj-tests` `:only-var` |
