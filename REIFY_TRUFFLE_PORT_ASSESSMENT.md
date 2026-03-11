# Reify Port: Cloffle Compiler Bytecode → Cloffle/Truffle APIs

## Summary

Porting the `reify` form from its current implementation (Clojure Compiler ASM bytecode generation) to Truffle APIs is **feasible but non-trivial**. The main obstacle is that `reify` produces objects that must implement arbitrary Java interfaces (Runnable, Callable, ISeq, etc.), and the JVM requires real classes or `java.lang.reflect.Proxy` for interface dispatch.

## Current Implementation

```
reify form → Compiler.analyze() → NewInstanceExpr
           → Compiler.build() calls ret.compile() → ASM bytecode → JVM class
           → ExprToNode.convertNewInstance() → NewNode(compiledClass, ctorArgs)
           → NewNode.executeGeneric() → Reflector.invokeConstructor(clazz, argValues)
```

Key points:
- **Compiler.analyze()** triggers `ObjExpr.build()`, which invokes `ret.compile()` and `ret.getCompiledClass()` as part of parsing. Bytecode generation is wired into the analysis phase.
- **NewNode** simply invokes the constructor of the Compiler-generated class with closed-over values.
- Works for: Java interfaces (Runnable, Callable), Clojure interfaces (ISeq), protocols, metadata (IObj).

## Options for Truffle-Native Reify

### Option 1: java.lang.reflect.Proxy (Most Practical)

**Approach:** Replace bytecode-generated classes with `Proxy.newProxyInstance()`. The `InvocationHandler` holds a map of `Method` → Truffle callable (RootCallTarget) and dispatches to Truffle nodes.

**Pros:**
- No ASM/bytecode generation
- Works for any Java interface
- Closed-overs captured as constructor args to a "proxy factory" Truffle node

**Cons:**
- Proxy only supports interfaces, not classes — matches reify (reify only takes interfaces)
- InvocationHandler adds a reflection indirection on every method call
- Need to convert `NewInstanceExpr` method bodies to Truffle nodes *without* invoking `ret.compile()`

**Blocker:** `Compiler.analyze()` invokes `ObjExpr.build()` which calls `ret.compile()`. To use Proxy we must either:
- (a) Fork/modify Clojure's Compiler to have an "analyze-only" reify path that skips compile, or
- (b) Replicate reify parsing in Cloffle (ReifyParser logic + method body analysis) and bypass Compiler for reify entirely

### Option 2: Truffle DynamicObject

**Approach:** Use `DynamicObject` for the reify instance.

**Conclusion:** Not viable. DynamicObject implements `TruffleObject` for polyglot interop (property access, etc.). When Java code calls `runnable.run()`, it needs a real `Runnable` — the JVM dispatches on interface type. DynamicObject cannot satisfy `instanceof Runnable`.

### Option 3: Keep Compiler Bytecode, Wrap with Truffle Interop

**Approach:** Keep current flow but ensure generated classes export Truffle interop messages (TruffleObject, InteropLibrary).

**Conclusion:** Already partially true — Clojure types (Keyword, LazySeq, etc.) implement TruffleObject. Generated reify classes extend Object and implement interfaces; they don't need TruffleObject for Java interop. This is a "no change" option — improves interop but doesn't remove bytecode dependency.

### Option 4: GraalVM Native Image / AOT

**Approach:** At AOT compile time, generate reify classes ahead of time.

**Conclusion:** Does not remove the need for bytecode generation; it moves it to build time. Not a "Truffle API" replacement.

## Recommended Path: Proxy + Modified Analysis

1. **Create a reify-specific analysis path** that does not call `ret.compile()`:
   - Either add a hook in Clojure Compiler to skip compile when using Cloffle, or
   - Implement a Cloffle-side reify parser that produces a structure equivalent to NewInstanceExpr (interfaces, methods, closes) without compiling.
2. **Convert method bodies** from Expr to Truffle nodes via `ExprToNode` (method bodies are normal Expr trees).
3. **Create a `ReifyNode`** (or equivalent) that:
   - Evaluates closed-over args
   - Builds a `Map<Method, RootCallTarget>` from method name + descriptor to the Truffle call target
   - Returns `Proxy.newProxyInstance(interfaces, new ReifyInvocationHandler(methodTargets, closedOvers))`
4. **ReifyInvocationHandler** implements `InvocationHandler.invoke()`: lookup method in map, call the corresponding Truffle call target with (closedOvers..., args).

## Effort Estimate

| Task | Effort |
|------|--------|
| Reify-only analysis (skip compile) in Clojure or Cloffle | Medium–High |
| ExprToNode for method bodies (may already work if we can get FnExpr/body Expr) | Low |
| ReifyNode + ReifyInvocationHandler | Medium |
| Protocol dispatch (reify implements defprotocol) | Already handled by ProtocolInvokeNode |
| Testing, edge cases (multiple interfaces, variadic methods, primitives) | Medium |

**Total:** Roughly 1–2 weeks for a focused implementation, assuming Compiler changes are acceptable.

## Test Coverage

The new `ReifyPortTest.java` defines the contract:

- Java interface impl (Runnable, Callable, Comparable)
- Closed-over locals (single and multiple)
- Clojure interface (ISeq)
- Direct CloffleCompiler path
- Multiple interfaces on one reify
- Method with arguments
- Metadata (with-meta)
- Java interop (pass reify to FutureTask, etc.)

All tests should pass before and after the port.

## Verdict

**Reasonableness: 7/10**

- Technically feasible via Proxy
- Main friction: Clojure Compiler tightly couples reify parsing and bytecode emission
- Cleanest approach: Add a "cloffle mode" to Compiler that produces NewInstanceExpr without compiling, or extract reify parsing into a separate Cloffle-only path
- Performance: Proxy adds reflection per call; could be optimized with MethodHandles or cached dispatch, but unlikely to match generated bytecode

If the goal is to reduce Clojure Compiler dependency for reify specifically, the Proxy path is the way to go. If the goal is purely "use more Truffle APIs," the current design (Compiler generates classes, Cloffle runs the rest) is already a reasonable split.
