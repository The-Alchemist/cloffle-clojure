package net.javacrumbs.cloffle.bytecode;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.util.function.Supplier;

import clojure.lang.Compiler;
import clojure.lang.LineNumberingPushbackReader;
import clojure.lang.LispReader;
import clojure.lang.RT;
import clojure.lang.Var;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.serialization.SerializationUtils;
import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.Clojure;
import net.javacrumbs.cloffle.compiler.CloffleCompiler;
import org.graalvm.polyglot.Context;

/**
 * Manual driver for Truffle bytecode AOT serialize/deserialize using a GraalVM Polyglot
 * {@link Context}. The context boots the Cloffle language (and thus {@link RT#init()}); guest
 * evaluation also exercises the same engine path as {@code cloffle-repl} / {@code CloffleReplTest}.
 * <p>
 * Bytecode roots are compiled and executed with {@link Clojure#pushEvalThreadBindings()} — the same
 * dynamic-var frame as {@link clojure.lang.BytecodeDslTestSupport#evalBytecode} and Truffle
 * {@link Clojure#initializeThread}. {@link com.oracle.truffle.api.TruffleLanguage#getCurrentContext}
 * is only set while guest code runs; this driver does not rely on it, so
 * {@link ExprToBytecode} / {@link CloffleBytecodeRootNodeGen#deserialize} use a {@code null}
 * language reference (supported; see {@code ExprToBytecodeSourceLocationTest}).
 * <p>
 * Forms that embed resolved {@link java.lang.reflect.Method} operands (e.g. static calls lowered
 * from {@code +} to {@code Numbers/add}) cannot be serialized until {@link CloffleBytecodeSerializer}
 * supports them — use a simple literal branch for this demo.
 *
 * @see net.javacrumbs.cloffle.compiler.BytecodeRuntimeIntegrationTest#bytecodeSerializationRoundTripPreservesEvalResult()
 */
public class BytecodeSerializationTest {

    /** Clojure snippet that round-trips through AOT bytecode serialization today (no Method constants). */
    private static final String CLJ_CODE = "(if true 30 :false)";

    public static void main(String[] args) throws Exception {
        String prevExec = System.getProperty(CloffleCompiler.EXECUTION_PROPERTY);
        System.setProperty(CloffleCompiler.EXECUTION_PROPERTY, CloffleCompiler.EXECUTION_BYTECODE);
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            // Language bootstrap calls RT.init() → Compiler.load(core); must use bytecode backend.
            // Avoid top-level `nil` — Polyglot requires a non-null interop return from eval.
            context.eval("cloffle", "0");

            Clojure.pushEvalThreadBindings();
            try {
                runSerializationDemo();
            } finally {
                Var.popThreadBindings();
            }
        } finally {
            if (prevExec != null) {
                System.setProperty(CloffleCompiler.EXECUTION_PROPERTY, prevExec);
            } else {
                System.clearProperty(CloffleCompiler.EXECUTION_PROPERTY);
            }
        }
    }

    private static void runSerializationDemo() throws Exception {
        System.out.println("Compiling: " + CLJ_CODE);
        Object form = LispReader.read(
                new LineNumberingPushbackReader(new StringReader(CLJ_CODE)), false, null, false, null);

        Object expanded = Compiler.macroexpand(form);
        Var.pushThreadBindings(RT.map(Compiler.LOADER, RT.makeClassLoader()));
        ClassLoader oldCcl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader((ClassLoader) Compiler.LOADER.deref());
        Compiler.Expr expr;
        try {
            expr = Compiler.analyze(Compiler.C.EVAL, expanded);
        } finally {
            Thread.currentThread().setContextClassLoader(oldCcl);
            Var.popThreadBindings();
        }

        Source source = Source.newBuilder("cloffle", CLJ_CODE, "test").build();
        ExprToBytecode converter = new ExprToBytecode(null, source);
        BytecodeRootNodes<CloffleBytecodeRootNode> nodes = converter.convertRoot(expr, "testEval");

        CloffleBytecodeRootNode rootNode = nodes.getNode(0);
        System.out.println("Before serialization, running node…");
        Object result1 = rootNode.getCallTarget().call();
        System.out.println("Result 1: " + result1);

        System.out.println("Serializing…");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        nodes.serialize(out, new CloffleBytecodeSerializer());
        byte[] serialized = baos.toByteArray();
        System.out.println("Serialization successful. Size: " + serialized.length + " bytes");

        System.out.println("Deserializing…");
        Supplier<DataInput> supplier = () -> SerializationUtils.createDataInput(ByteBuffer.wrap(serialized));
        BytecodeRootNodes<CloffleBytecodeRootNode> deserializedNodes =
                CloffleBytecodeRootNodeGen.deserialize(
                        null,
                        ExprToBytecode.BYTECODE_CONFIG,
                        supplier,
                        new CloffleBytecodeDeserializer());

        CloffleBytecodeRootNode deserializedRoot = deserializedNodes.getNode(0);
        System.out.println("Deserialization successful: " + deserializedRoot.getName());
        Object result2 = deserializedRoot.getCallTarget().call();
        System.out.println("Result after deserialize: " + result2);
    }
}
