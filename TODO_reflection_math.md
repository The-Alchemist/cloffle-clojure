# Reflection warnings and `*unchecked-math*` compatibility

Seven reflection warnings appear during bootstrap:

```
clojure/instant.clj:195:5 - .write on java.io.Writer (String, long, unknown)
clojure/instant.clj:197:5 - .write on java.io.Writer (String, unknown, long)
clojure/string.clj:125:11 - .indexOf on java.lang.String (unknown)
clojure/string.clj:334:11 - .indexOf on java.lang.String (int, unknown)
clojure/string.clj:335:11 - .indexOf on java.lang.String (java.lang.String, unknown)
clojure/string.clj:355:11 - .lastIndexOf on java.lang.String (int, unknown)
clojure/string.clj:356:11 - .lastIndexOf on java.lang.String (java.lang.String, unknown)
```

Upstream (`clojure-1.13.0-alpha6`, `/Users/karl-medplum/Development/digital-alchemy/clojure`) is byte-identical to this fork at all five `string.clj` sites and has no `^String calstr` in `instant.clj`. Both these warnings and the loss of `*unchecked-math*` trace to one cause: commit `608169993` removed `isInline`. Upstream relies on `:inline` plus `preserveTag` to rewrite `(int x)` into a `StaticMethodExpr` on `RT.intCast`, which gives the analyzer a real primitive return type. Without it, `(int x)` is an ordinary Var invoke returning `Object`, so no `int` overload can be selected.

Upstream has **zero** primitive-tagged defns, so tagging `clojure.core/int` is not an option: it would make `(meta #'int)` diverge from stock.

## 1. Call-site hints for the seven warnings

Five sites in `src/clj/clojure/string.clj`. Use the `^int` idiom already present in `index-of` on the adjacent `(.charValue ...)` argument, so the diff stays in upstream style:

- Line 125: `(.indexOf s ^int (int match))`
- Lines 334, 335, 355, 356: `^int (unchecked-int from-index)`

One site in `src/clj/clojure/instant.clj` covers both `print-calendar` warnings. Tag the local rather than the two use sites:

```clojure
(let [^String calstr (format "%1$tFT%1$tT.%1$tL%1$tz" c)
      ^long offset-minutes (- (.length calstr) 2)]
```

`Reflector.paramArgTypeMatch` accepts `long` for an `int` parameter (`Reflector.java:661-666`), so the `0` and `2` literals need no change — they analyze as `long` and already match. Once `offset-minutes` is typed, `Writer.write(String,int,int)` is the only candidate, since the `char[]` overload fails on a `String` first argument.

`LocalBinding` will not throw on the `^long` tag because `(- ...)` is an `InvokeExpr`, not a `MaybePrimitiveExpr` (`Compiler.java:6810`).

## 2. Restore `*unchecked-math*` via a gated inline

**This has a live consequence, not just a test-suite one.** `reitit.walk-test/keywordize=walk-keywordize`
is the only non-identical case in the entire `compat-test` suite, and it fails with a `long overflow`
out of `test.check`'s `JavaUtilSplittableRandom.split`: that code sets `*unchecked-math*` and relies
on it to make its splitmix arithmetic wrap. Since the flag is inert, the checked op throws. Any
library that opts into unchecked math for hashing or PRNG work will hit this. Tracked from the
compat side in [`FIXME.md`](FIXME.md) §2; the fix is here.

`*unchecked-math*` is currently a no-op for core arithmetic. Upstream's `int`, `inc`, `dec`, `+`, `-`, `*`, `float`, `short`, `byte`, and `char` all pick an unchecked op inside their `:inline` closure; the plain fn bodies always use the checked op. With inlining removed, `(set! *unchecked-math* true)` no longer changes anything.

Re-add `inlineKey`, `inlineAritiesKey`, and `isInline` to `src/jvm/clojure/lang/Compiler.java`, reverting that portion of `608169993`, but gate the `analyzeSeq` hook so expansion only happens when unchecked math is active:

```java
if(RT.booleanCast(RT.UNCHECKED_MATH.deref()))
	{
	IFn inline = isInline(op, RT.count(RT.next(form)));
	if(inline != null)
		return analyze(context, preserveTag(form, inline.applyTo(RT.next(form))));
	}
```

| `*unchecked-math*` | Expansion | Boxed math warning |
|---|---|---|
| `false` (default) | none — `InvokeExpr` on `clojure.core/+` | no |
| `true` | `StaticMethodExpr` `Numbers/unchecked_add` | no |
| `:warn-on-boxed` | `StaticMethodExpr` `Numbers/unchecked_add` | yes |

This reproduces upstream for all three states that `check-warn-on-box` asserts, because `nary-inline` keys on truthiness and the boxed-math warning fires only when `warnOnBoxedKeyword.equals(RT.UNCHECKED_MATH.deref())` (`Compiler.java:2244-2249`). The default path keeps `608169993`'s design intent: no inlining, ordinary Var calls.

In `src/clj/clojure/core.clj`, restore `:inline` metadata **only** on the `*unchecked-math*`-sensitive vars listed above, matching upstream text exactly, plus the `nary-inline`, `>1?`, and `>0?` private helpers. Leave every other var's `:inline` removed, and leave `definline` as the current no-inline shim.

## 3. Un-relax the two upstream tests

Commit `608169993` relaxed two stock tests. Both are the same class of regression as the seven warnings.

- `test/clojure/test_clojure/numbers.clj` — restore `(check-warn-on-box true ...)` on the `inc` and `>` cases and drop the Cloffle comment. Step 2 is what makes these pass again; call-site hints alone would not, since `:warn-on-boxed` requires a `Numbers/*` `StaticMethodExpr`.
- `test/clojure/test_clojure/java_interop.clj` — `should-not-reflect #(clojure.test-clojure.java-interop/return-long)` is commented out. **Investigate before changing.** The comment attributes it to `:inline`, but `return-long` contains no inlinable core call, so the cause is more likely the disabled `invokePrim` rewrite near `Compiler.java:4573` or FI adaptation. Capture the actual stderr first, then either restore the assertion or replace the comment with the real root cause.

## 4. Check whether the local `instant.clj` hints are still needed

Commit `df949d27ca` added three `^String (format ...)` hints not present upstream (`print-calendar`, the `Timestamp` printer, and `TimeZone/getTimeZone`). `format` is declared `^String [fmt & args]` and `sigTag` matches variadic arities, so those hints look redundant. Remove them one at a time and keep any whose removal reintroduces a warning. Goal is byte-identity with upstream where achievable.

## 5. Verification

- Extend `src/test/java/clojure/lang/WriterOverloadReflectionTest.java` to assert `InstanceMethodExpr.method` is non-null with the expected parameter types for both `Writer.write` calls and the `String.indexOf` / `lastIndexOf` shapes.
- Add a `should-not-reflect` case for the `clojure.string` fns using the existing helper at `test/clojure/test_helper.clj:128`.
- Run `clojure -T:build run-tests` and `clojure -T:build run-clj-tests` with the default `:fresh true`. Capture bootstrap stderr before and after to confirm all seven warnings are gone and none appeared.

## Numeric contract: `long` / `Long` only

Clojure 1.3 unified integers around **64-bit `long`**. That is the official language, not a Java-style tower. Cloffle only has to match that.

### What user code can observe

Clojure-produced integers (literals, `+`/`inc`/`count`/`range`, boxed math results):

- boxed class is `java.lang.Long`
- unboxed type is `long`
- `(class 1)` / `(instance? Long 1)` / `(int? 1)` are true
- `+` `-` `*` `inc` `dec` throw on overflow; `+'` etc. → `BigInt`
- primitive fn hints are **only** `long` and `double` (`IFn$L*`, `IFn$D*`). `^int` on a param vector throws (`Compiler.java:6220`)

`Integer` is a **host leftover**, not a Clojure integer type. `Numbers.ops(Integer)` uses `LONG_OPS` and **returns a Long**. `=` / `hasheq` still treat `Integer` and `Long` as the same number, so map keys work. You never have to keep producing `Integer` from Cloffle arithmetic.

Java `int` returns box as `Integer` (1.4). That is the one interop fork: matching it keeps `(instance? Integer (Integer/parseInt "1"))`; boxing those as `Long` would break `instance?`/`class` but not `=`. Prefer the 1.4 behavior at the interop boundary only.

Reader: `LispReader.matchNumber` → `Numbers.num(long)` → `Long.valueOf`. Bigger than 64 bits or a trailing `N` → `BigInt`. Compiler `NumberExpr.getJavaClass()` is `long.class` for both `Integer` and `Long` literals.

This is why the seven warnings exist. Literals analyze as `long`. `Writer.write` / `String.indexOf` want `int`. Upstream gets `int` from `:inline` of `clojure.core/int` → `RT.intCast`. We don't inline, so the analyzer sees `Object` and reflection fires. The hints in §1 and the gated inline in §2 are patches around that contract, not a reason to make `int` a Cloffle primitive.

### Options (all official)

1. **Always-boxed `Long`.** Today's bytecode path: `NumberExpr` → `emitLoadConstant(ne.val())`. Reader already boxed a `Long`. Correct, slow (no primitive locals, no `IFn$L*`).
2. **Unbox internally, box as `Long` at Object boundaries.** Official compiler model. Locals, loops, and `^long` args stay primitive `long`. At unhinted `IFn.invoke`, `instance?`, `conj`, `RT.get`, box with `Long.valueOf` — never `Integer.valueOf`. This is the option worth taking for Truffle/bytecode.
3. **Long-only fast paths; `Integer`/`Short`/`Byte` are generic `Number`.** No `Integer` specializations in maps, `RT.get`, `int?`, or `BytecodeStaticMethod`. Official Clojure almost never hands you `Integer` unless it called Java. Keep one `Numbers.ops` / `longValue()` slow path so `(= 1 (Integer/parseInt "1"))` still works.
4. **Do not add `int` as a Cloffle primitive.** No `IFn$I`, no `int` loop locals for Clojure code. `int` exists for arrays, `case` int dispatch, and Java overloads via `RT.intCast`.

Do **not**:

- box Clojure arithmetic as `Integer` when the value fits in 32 bits (1.2 world; breaks `instance? Long`, `int?` Long-first, `LongRange`)
- tag `#'clojure.core/int` (upstream has zero primitive-tagged defns)
- treat `Integer` as a compiled type just because Java has it

### How this should steer the rest of this file

§1–§2 stay the right analyzer/bootstrap fix. They restore *types the analyzer can see*, not a new numeric tower.

The Truffle speedup that actually matters is not “accept `Integer`.” It is:

- emit primitive `long`/`double` from `NumberExpr` / hinted locals (option 2)
- widen `BytecodeStaticMethod` to primitive signatures **as `long`/`double`/`int` host edges**, with Clojure values already `long`, casting to `int` only at the Java call (same as `Reflector.paramArgTypeMatch` accepting `long` for `int`)
- leave `Integer` on the reflective / `Numbers` path

Fixing the seven warnings does **not** by itself speed up the guest, and the converse also held: `BytecodeStaticMethod.computeMethodHandle` used to reject any method with a primitive parameter or return, so `Writer.write(String,int,int)` and `String.indexOf(int)` stayed on `@TruffleBoundary` `BytecodeInterop`. That half is now done (`43af52d0`), on the stated assumption that **Clojure ints are `long`**, not a family of boxed widths. What remains is the analyzer side: without a primitive return type at the call site there is still no `int` overload to select, so the reflection warnings persist regardless of how the handle is built.

## Verified along the way

- `unchecked-cast-char` still passes. `RT.uncheckedIntCast(Object)` handles `Character` explicitly (`RT.java:1709-1713`), and the `^Number x` param hint only steers overload selection, where `Number` matches just the `Object` overload.
- `^int` on a `defn` name *would* propagate (`VarExpr` falls back to `var.getTag()` at `Compiler.java:651`, and `sigs`/`resolve-tag` deliberately skips `maybeSpecialTag` symbols), but it is rejected here because upstream does not do it. Note `^int` on a *param vector* throws "Only long and double primitives are supported" (`Compiler.java:6220`).

## Checklist

- [ ] `^int` hints at the five `clojure/string.clj` sites, `^long` on `instant.clj` `offset-minutes`
- [ ] Re-add `isInline` to `Compiler.java`, gated on `*unchecked-math*` truthiness
- [ ] Restore upstream `:inline` metadata on the unchecked-math-sensitive core vars only
- [ ] Restore `warn-on-boxed` expectations in `numbers.clj`
- [ ] Capture real stderr for `return-long`, then resolve the commented-out `should-not-reflect`
- [ ] Test-remove the three local `instant.clj` `format` hints for upstream byte-identity
- [ ] Analyzer + `should-not-reflect` coverage, both suites clean, bootstrap stderr diffed

Later (not the seven-warning patch):

- [ ] Primitive `long`/`double` emission from `NumberExpr` / hinted locals; box with `Long.valueOf` at Object boundaries
- [x] `BytecodeStaticMethod` primitive signatures, Clojure ints as `long`, `int` only at the host call — done in `43af52d0`; `computeMethodHandle` now adapts primitive params with a `Reflector.boxArg` filter and lets `asType` box primitive returns. See [`TODO_tuple.md`](TODO_tuple.md).
- [ ] No `Integer` fast paths; one generic `Numbers` path for host leftovers
