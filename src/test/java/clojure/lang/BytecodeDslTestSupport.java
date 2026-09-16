package clojure.lang;

import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.Clojure;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import net.javacrumbs.cloffle.bytecode.ExprToBytecode;

import java.io.StringReader;

/**
 * Shared helpers for {@link ExprToBytecode} JUnit tests in {@code clojure.lang} (same package as
 * {@link Compiler} for macroexpand/analyze access).
 *
 * <p><strong>Test isolation:</strong> do not use {@link Var#bindRoot} on {@link RT#CURRENT_NS} or
 * {@link Compiler#COMPILER_OPTIONS} for setup — that leaks across JUnit classes in one JVM. Prefer
 * {@link Var#pushThreadBindings} / {@link #withDirectLinkingOn} / {@link #withDirectLinkingOff}, or a
 * Clojure {@code binding} on {@code *compiler-options*} inside guest {@code eval}. Analyze paths here
 * thread-bind {@code user} so they do not inherit a polluted {@code *ns*} root.
 */
public final class BytecodeDslTestSupport {

    /** Default {@link Source} name used by {@link #compileRootNodes} / {@link #evalBytecode}. */
    public static final String DEFAULT_BYTECODE_SOURCE_NAME = "bytecode-test.clj";

    /** Stock REPL semantics: no direct linking, no locked analyze folds (overrides JVM flags). */
    public static final IPersistentMap DIRECT_LINKING_OFF = RT.map(
            Keyword.directLinkingKey, Boolean.FALSE,
            Keyword.lockedCallSiteRewritesKey, Boolean.FALSE);

    /** Runtime/bench profile: direct linking on (locked folds follow {@link Compiler#lockedCallSiteRewritesEnabled()}). */
    public static final IPersistentMap DIRECT_LINKING_ON =
            RT.map(Keyword.directLinkingKey, Boolean.TRUE);

    private BytecodeDslTestSupport() {
    }

    private static Object readAndMacroexpand(String code) throws Exception {
        Object form = LispReader.read(
                new LineNumberingPushbackReader(new StringReader(code)), false, null, false, null);
        return Compiler.macroexpand(form);
    }

    /**
     * Reads, macroexpands, and analyzes {@code code}, then compiles to Truffle bytecode roots
     * (outer root named {@code rootName}) using {@link #DEFAULT_BYTECODE_SOURCE_NAME}.
     */
    public static BytecodeRootNodes<CloffleBytecodeRootNode> compileRootNodes(String code, String rootName)
            throws Exception {
        return compileRootNodes(code, rootName, DEFAULT_BYTECODE_SOURCE_NAME);
    }

    /**
     * Same as {@link #compileRootNodes(String, String)} but with an explicit Truffle {@link Source}
     * {@linkplain Source#getName() name} (language id {@code cloffle}).
     */
    public static BytecodeRootNodes<CloffleBytecodeRootNode> compileRootNodes(
            String code, String rootName, String sourceName) throws Exception {
        return compileRootNodes(code, rootName, sourceName, Compiler.C.EVAL);
    }

    public static CloffleBytecodeRootNode compileRoot(String code, String rootName) throws Exception {
        return compileRootNodes(code, rootName).getNode(0);
    }

    public static CloffleBytecodeRootNode compileRoot(String code, String rootName, String sourceName)
            throws Exception {
        return compileRootNodes(code, rootName, sourceName).getNode(0);
    }

    /**
     * Like {@link #compileRoot(String, String)} but analyzes as {@link Compiler.C#EXPRESSION} so
     * compile-time {@link Compiler.C#EVAL} constant folding does not erase call sites under test.
     */
    public static CloffleBytecodeRootNode compileRootExpression(String code, String rootName)
            throws Exception {
        return compileRootNodes(code, rootName, DEFAULT_BYTECODE_SOURCE_NAME, Compiler.C.EXPRESSION).getNode(0);
    }

    /**
     * Thread bindings for analyze/macroexpand: dedicated loader, {@code user} ns (not whatever
     * {@link RT#CURRENT_NS} root another test left via {@link Var#bindRoot}), and the usual eval
     * dynamic vars so resolution matches {@link Clojure#pushEvalThreadBindings()}.
     */
    private static IPersistentMap analyzeThreadBindings() {
        Namespace user = Namespace.findOrCreate(Symbol.intern("user"));
        return RT.mapUniqueKeys(
                Compiler.LOADER, RT.makeClassLoader(),
                RT.CURRENT_NS, user,
                RT.WARN_ON_REFLECTION, RT.WARN_ON_REFLECTION.deref(),
                RT.UNCHECKED_MATH, RT.UNCHECKED_MATH.deref(),
                RT.READEVAL, RT.READEVAL.deref(),
                RT.DATA_READERS, RT.DATA_READERS.deref(),
                RT.DEFAULT_DATA_READER_FN, RT.DEFAULT_DATA_READER_FN.deref());
    }

    private static BytecodeRootNodes<CloffleBytecodeRootNode> compileRootNodes(
            String code, String rootName, String sourceName, Compiler.C context) throws Exception {
        Object form = LispReader.read(
                new LineNumberingPushbackReader(new StringReader(code)), false, null, false, null);
        Var.pushThreadBindings(analyzeThreadBindings());
        ClassLoader oldCcl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader((ClassLoader) Compiler.LOADER.deref());
        try {
            Object expanded = Compiler.macroexpand(form);
            Compiler.Expr expr = Compiler.analyze(context, expanded);
            Source source = Source.newBuilder("cloffle", code, sourceName).build();
            ExprToBytecode converter = new ExprToBytecode(null, source, true);
            return converter.convertRoot(expr, rootName);
        } finally {
            Thread.currentThread().setContextClassLoader(oldCcl);
            Var.popThreadBindings();
        }
    }

    /** Same as {@link #compileRoot(String, String)} with root name {@code namedRoot}. */
    public static CloffleBytecodeRootNode compileRoot(String code) throws Exception {
        return compileRoot(code, "namedRoot");
    }

    /**
     * Evaluates Clojure source via bytecode (root name {@code testRoot}). Wraps checked exceptions
     * in {@link RuntimeException}.
     * <p>
     * Installs the same default dynamic var stack frame as Truffle {@link Clojure#initializeThread} /
     * {@link Clojure#pushEvalThreadBindings()} ({@code *ns*}, {@code *warn-on-reflection*}, …) so
     * {@code Var} reads / {@code set!} on thread-bound vars match {@link net.javacrumbs.cloffle.compiler.CloffleCompiler}
     * loads. Popped in {@code finally} after the root returns.
     */
    public static Object evalBytecode(String code) {
        Clojure.pushEvalThreadBindings();
        try {
            CloffleBytecodeRootNode root = compileRoot(code, "testRoot");
            return root.getCallTarget().call();
        } catch (Exception e) {
            throw new RuntimeException("bytecode eval failed: " + code, e);
        } finally {
            Var.popThreadBindings();
        }
    }

    /**
     * Merges {@code overrides} into the current {@code *compiler-options*} and runs {@code body}.
     */
    public static void withCompilerOptions(IPersistentMap overrides, Runnable body) {
        Object opts = Compiler.COMPILER_OPTIONS.deref();
        if (opts == null) {
            opts = PersistentHashMap.EMPTY;
        }
        Object merged = opts;
        for (ISeq s = overrides.seq(); s != null; s = s.next()) {
            IMapEntry e = (IMapEntry) s.first();
            merged = RT.assoc(merged, e.key(), e.val());
        }
        Var.pushThreadBindings(RT.map(Compiler.COMPILER_OPTIONS, merged));
        try {
            body.run();
        } finally {
            Var.popThreadBindings();
        }
    }

    /** Same as {@link #withCompilerOptions(IPersistentMap, Runnable)} for callables that return a value. */
    public static <T> T withCompilerOptions(IPersistentMap overrides, java.util.concurrent.Callable<T> body)
            throws Exception {
        Object opts = Compiler.COMPILER_OPTIONS.deref();
        if (opts == null) {
            opts = PersistentHashMap.EMPTY;
        }
        Object merged = opts;
        for (ISeq s = overrides.seq(); s != null; s = s.next()) {
            IMapEntry e = (IMapEntry) s.first();
            merged = RT.assoc(merged, e.key(), e.val());
        }
        Var.pushThreadBindings(RT.map(Compiler.COMPILER_OPTIONS, merged));
        try {
            return body.call();
        } finally {
            Var.popThreadBindings();
        }
    }

    public static void withDirectLinkingOff(Runnable body) {
        withCompilerOptions(DIRECT_LINKING_OFF, body);
    }

    public static <T> T withDirectLinkingOff(java.util.concurrent.Callable<T> body) throws Exception {
        return withCompilerOptions(DIRECT_LINKING_OFF, body);
    }

    public static void withDirectLinkingOn(Runnable body) {
        withCompilerOptions(DIRECT_LINKING_ON, body);
    }

    public static <T> T withDirectLinkingOn(java.util.concurrent.Callable<T> body) throws Exception {
        return withCompilerOptions(DIRECT_LINKING_ON, body);
    }

    public static Compiler.Expr analyzeExpressionDirectLinkingOff(String code) throws Exception {
        return withDirectLinkingOff(() -> {
            Object expanded = readAndMacroexpand(code);
            return Compiler.analyze(Compiler.C.EXPRESSION, expanded);
        });
    }

    public static Compiler.Expr analyzeExpressionDirectLinkingOn(String code) throws Exception {
        return withDirectLinkingOn(() -> {
            Object expanded = readAndMacroexpand(code);
            return Compiler.analyze(Compiler.C.EXPRESSION, expanded);
        });
    }

    public static Object evalBytecodeDirectLinkingOff(String code) throws Exception {
        return withDirectLinkingOff(() -> evalBytecode(code));
    }

    public static Object evalBytecodeDirectLinkingOn(String code) throws Exception {
        return withDirectLinkingOn(() -> evalBytecode(code));
    }

    /**
     * Fold-only profile ({@code :locked-call-site-rewrites true} without {@code :direct-linking}).
     * Prefer {@link #withDirectLinkingOn} when tests mean runtime direct-linking + folds.
     */
    public static void withLockedCallSiteRewrites(Runnable body) {
        withCompilerOptions(RT.map(Keyword.lockedCallSiteRewritesKey, Boolean.TRUE), body);
    }

    public static <T> T withLockedCallSiteRewrites(java.util.concurrent.Callable<T> body) throws Exception {
        return withCompilerOptions(RT.map(Keyword.lockedCallSiteRewritesKey, Boolean.TRUE), body);
    }

    /** @deprecated use {@link #withDirectLinkingOn} */
    @Deprecated
    public static void withDirectLinkingPerfProfile(Runnable body) {
        withDirectLinkingOn(body);
    }

    /** @deprecated use {@link #withDirectLinkingOn} */
    @Deprecated
    public static <T> T withDirectLinkingPerfProfile(java.util.concurrent.Callable<T> body) throws Exception {
        return withDirectLinkingOn(body);
    }
}
