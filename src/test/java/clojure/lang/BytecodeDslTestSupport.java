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
 */
public final class BytecodeDslTestSupport {

    /** Default {@link Source} name used by {@link #compileRootNodes} / {@link #evalBytecode}. */
    public static final String DEFAULT_BYTECODE_SOURCE_NAME = "bytecode-test.clj";

    private BytecodeDslTestSupport() {
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

    private static BytecodeRootNodes<CloffleBytecodeRootNode> compileRootNodes(
            String code, String rootName, String sourceName, Compiler.C context) throws Exception {
        Object form = LispReader.read(
                new LineNumberingPushbackReader(new StringReader(code)), false, null, false, null);
        Var.pushThreadBindings(RT.map(Compiler.LOADER, RT.makeClassLoader()));
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

    /**
     * Runs {@code body} with {@code *compiler-options*} containing
     * {@code :locked-call-site-rewrites true}, so analyze-time folds that erase
     * {@code :cloffle/locked} call sites are enabled (fold-only; no direct-linking).
     */
    public static void withLockedCallSiteRewrites(Runnable body) {
        withCompilerOptions(RT.map(Keyword.lockedCallSiteRewritesKey, Boolean.TRUE), body);
    }

    /** Same as {@link #withLockedCallSiteRewrites(Runnable)} for callables that return a value. */
    public static <T> T withLockedCallSiteRewrites(java.util.concurrent.Callable<T> body) throws Exception {
        return withCompilerOptions(RT.map(Keyword.lockedCallSiteRewritesKey, Boolean.TRUE), body);
    }

    /**
     * Perf profile: {@code :direct-linking true} only — also enables {@code :cloffle/locked}
     * analyze-time folds unless {@code :locked-call-site-rewrites} is explicitly false.
     */
    public static void withDirectLinkingPerfProfile(Runnable body) {
        withCompilerOptions(RT.map(Keyword.directLinkingKey, Boolean.TRUE), body);
    }

    /** Same as {@link #withDirectLinkingPerfProfile(Runnable)} for callables that return a value. */
    public static <T> T withDirectLinkingPerfProfile(java.util.concurrent.Callable<T> body) throws Exception {
        return withCompilerOptions(RT.map(Keyword.directLinkingKey, Boolean.TRUE), body);
    }
}
