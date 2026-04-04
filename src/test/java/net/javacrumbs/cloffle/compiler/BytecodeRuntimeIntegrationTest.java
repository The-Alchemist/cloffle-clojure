package net.javacrumbs.cloffle.compiler;

import clojure.lang.BytecodeDslTestSupport;
import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeSerialization;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Runtime integration: {@link CloffleCompiler#compile} over classpath bootstrap scripts,
 * sequential multi-file compile (load-like), and Truffle bytecode AOT serialize/deserialize smoke.
 * Matches the {@code Compiler.load} pipeline (analyze → execute) without loading {@code clojure.core} first — same
 * constraint as {@link clojure.lang.BytecodeDslTestSupport}.
 * <p>
 * We bind {@link RT#CURRENT_NS} to a dedicated empty namespace — not {@code user}. Other tests in the
 * same JVM call {@link RT#init()}, which refers {@code clojure.core} into {@code user}; compiling
 * {@code bootstrap_slice.clj} (core-style early {@code def}s) in {@code user} would then shadow those
 * refers and emit many "already refers" warnings.
 */
public class BytecodeRuntimeIntegrationTest {

    private static final Symbol BOOTSTRAP_NS = Symbol.intern("cloffle.bootstrap-runtime-integration");

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
    public static void bindBootstrapNamespace() {
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(BOOTSTRAP_NS));
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
     * Two {@link CloffleCompiler#compile} runs on the same JVM thread (same dedicated ns) — same shape as
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
     * AOT wire format: round-trip via {@link CloffleBytecodeSerialization} — same entry points as
     * {@link net.javacrumbs.cloffle.bytecode.CloffleCoreBytecodeArchive} per-form chunks.
     */
    @Test
    public void bytecodeSerializationRoundTripPreservesEvalResult() throws Exception {
        BytecodeRootNodes<CloffleBytecodeRootNode> nodes =
                BytecodeDslTestSupport.compileRootNodes("(clojure.lang.Numbers/add 40 2)", "aotSmoke");
        CloffleBytecodeRootNode original = nodes.getNode(0);
        assertEquals(42L, original.getCallTarget().call());

        byte[] serialized = CloffleBytecodeSerialization.serializeRootNodes(nodes);
        assertTrue(serialized.length > 0);

        BytecodeRootNodes<CloffleBytecodeRootNode> deserialized =
                CloffleBytecodeSerialization.deserializeRootNodes(serialized);
        assertEquals(42L, deserialized.getNode(0).getCallTarget().call());
    }
}
