package net.javacrumbs.cloffle.compiler;

import clojure.lang.BytecodeDslTestSupport;
import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.serialization.SerializationUtils;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeDeserializer;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNodeGen;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeSerializer;
import net.javacrumbs.cloffle.bytecode.ExprToBytecode;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Runtime integration: {@link CloffleCompiler#compile} over classpath bootstrap scripts,
 * sequential multi-file compile (load-like), and Truffle bytecode AOT serialize/deserialize smoke.
 * Matches the {@code Compiler.load} pipeline (analyze → execute) without loading {@code clojure.core} first — same
 * constraint as {@link clojure.lang.BytecodeDslTestSupport}.
 * <p>
 * We only bind {@link RT#CURRENT_NS} to {@code user}; we do not call {@link RT#init()} (that requires
 * {@code clojure.core} to be loaded first).
 */
public class BytecodeRuntimeIntegrationTest {

    private static String readResource(String path) throws IOException {
        try (InputStream in = BytecodeRuntimeIntegrationTest.class.getResourceAsStream(path)) {
            assertNotNull("classpath resource " + path, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String readBootstrapSlice() throws IOException {
        return readResource("/cloffle/bootstrap_slice.clj");
    }

    private static String readBootstrapExtra() throws IOException {
        return readResource("/cloffle/bootstrap_extra.clj");
    }

    @BeforeClass
    public static void bindUserNamespace() {
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
    }

    @Test
    public void compileBootstrapSlice() throws Exception {
        String text = readBootstrapSlice();
        Object last =
                CloffleCompiler.compile(new StringReader(text), "bootstrap_slice.clj", "bootstrap_slice.clj");
        assertEquals(42L, last);
    }

    @Test
    public void compileBootstrapExtra() throws Exception {
        String text = readBootstrapExtra();
        Object last =
                CloffleCompiler.compile(new StringReader(text), "bootstrap_extra.clj", "bootstrap_extra.clj");
        assertEquals(7L, last);
    }

    /**
     * Two {@link CloffleCompiler#compile} runs on the same JVM thread (same {@code user} ns) — same shape as
     * sequential {@code load-file} / {@code require} without pulling in {@code clojure.core} loaders.
     */
    @Test
    public void compileBootstrapSliceThenExtraSequential() throws Exception {
        Object first = CloffleCompiler.compile(
                new StringReader(readBootstrapSlice()), "bootstrap_slice.clj", "bootstrap_slice.clj");
        assertEquals(42L, first);
        Object second = CloffleCompiler.compile(
                new StringReader(readBootstrapExtra()), "bootstrap_extra.clj", "bootstrap_extra.clj");
        assertEquals(7L, second);
    }

    /**
     * AOT wire format: serialize {@link CloffleBytecodeRootNode}s with {@link CloffleBytecodeSerializer}, deserialize
     * with {@link CloffleBytecodeRootNodeGen#deserialize}, execute — same pattern as {@link clojure.lang.ExprToBytecodeSourceLocationTest}
     * but kept here as runtime-integration smoke.
     */
    @Test
    public void bytecodeSerializationRoundTripPreservesEvalResult() throws Exception {
        BytecodeRootNodes<CloffleBytecodeRootNode> nodes =
                BytecodeDslTestSupport.compileRootNodes("(clojure.lang.Numbers/add 40 2)", "aotSmoke");
        CloffleBytecodeRootNode original = nodes.getNode(0);
        assertEquals(42L, original.getCallTarget().call());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        nodes.serialize(new DataOutputStream(baos), new CloffleBytecodeSerializer());
        byte[] serialized = baos.toByteArray();
        assertTrue(serialized.length > 0);

        Supplier<DataInput> supplier = () -> SerializationUtils.createDataInput(ByteBuffer.wrap(serialized));
        BytecodeRootNodes<CloffleBytecodeRootNode> deserialized =
                CloffleBytecodeRootNodeGen.deserialize(
                        null, ExprToBytecode.BYTECODE_CONFIG, supplier, new CloffleBytecodeDeserializer());
        assertEquals(42L, deserialized.getNode(0).getCallTarget().call());
    }
}
