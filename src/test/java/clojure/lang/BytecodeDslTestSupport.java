package clojure.lang;

import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.Clojure;
import net.javacrumbs.cloffle.ast.ExprToNode;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import net.javacrumbs.cloffle.bytecode.ExprToBytecode;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.ClojureRootNode;

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
        Object form = LispReader.read(
                new LineNumberingPushbackReader(new StringReader(code)), false, null, false, null);
        // reify* / deftype* analyze generates stub classes via Compiler.LOADER (same as CloffleCompiler.compile).
        Var.pushThreadBindings(RT.map(Compiler.LOADER, RT.makeClassLoader()));
        ClassLoader oldCcl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader((ClassLoader) Compiler.LOADER.deref());
        try {
            Object expanded = Compiler.macroexpand(form);
            Compiler.Expr expr = Compiler.analyze(Compiler.C.EVAL, expanded);
            Source source = Source.newBuilder("cloffle", code, sourceName).build();
            ExprToBytecode converter = new ExprToBytecode(null, source);
            return converter.convertRoot(expr, rootName);
        } finally {
            Thread.currentThread().setContextClassLoader(oldCcl);
            Var.popThreadBindings();
        }
    }

    public static CloffleBytecodeRootNode compileRoot(String code, String rootName) throws Exception {
        return compileRootNodes(code, rootName).getNode(0);
    }

    public static CloffleBytecodeRootNode compileRoot(String code, String rootName, String sourceName)
            throws Exception {
        return compileRootNodes(code, rootName, sourceName).getNode(0);
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
     * Same pipeline as {@link #evalBytecode(String)} but {@link ExprToNode} → {@link ClojureRootNode}
     * for parity checks against the bytecode backend.
     */
    public static Object evalAst(String code) {
        Clojure.pushEvalThreadBindings();
        Var.pushThreadBindings(RT.map(Compiler.LOADER, RT.makeClassLoader()));
        ClassLoader oldCcl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader((ClassLoader) Compiler.LOADER.deref());
        try {
            Object form = LispReader.read(
                    new LineNumberingPushbackReader(new StringReader(code)), false, null, false, null);
            Object expanded = Compiler.macroexpand(form);
            Compiler.Expr expr = Compiler.analyze(Compiler.C.EVAL, expanded);
            Source source = Source.newBuilder("cloffle", code, DEFAULT_BYTECODE_SOURCE_NAME).build();
            ExprToNode converter = new ExprToNode(null, source);
            ClojureNode node = converter.convert(expr);
            FrameDescriptor fd = converter.buildFrameDescriptor();
            ClojureRootNode root = ClojureRootNode.create(node, fd, null);
            return root.getCallTarget().call();
        } catch (Exception e) {
            throw new RuntimeException("AST eval failed: " + code, e);
        } finally {
            Thread.currentThread().setContextClassLoader(oldCcl);
            Var.popThreadBindings();
            Var.popThreadBindings();
        }
    }
}
