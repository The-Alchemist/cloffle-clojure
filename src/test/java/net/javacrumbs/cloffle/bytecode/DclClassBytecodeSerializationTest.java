package net.javacrumbs.cloffle.bytecode;

import clojure.lang.BytecodeDslTestSupport;
import clojure.lang.DynamicClassLoader;
import clojure.lang.IDeref;
import clojure.lang.Namespace;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import net.javacrumbs.cloffle.Clojure;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Compiler-emitted classes ({@code reify}, {@code fn}, etc.) live in {@link DynamicClassLoader} and are not visible
 * to {@link Class#forName(String)} in a fresh JVM. Serialization must embed
 * {@link DynamicClassLoader#findClassBytes(String)} so deserialization can
 * {@link DynamicClassLoader#defineClass(String, byte[], Object)}.
 */
public class DclClassBytecodeSerializationTest {

    @BeforeClass
    public static void initRt() {
        RT.init();
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
    }

    @Test
    public void reifySerializesWithDclWireAndRoundTripsInSameJvm() throws Exception {
        BytecodeRootNodes<CloffleBytecodeRootNode> nodes =
                BytecodeDslTestSupport.compileRootNodes("(reify clojure.lang.IDeref (deref [_] 42))", "reifyRoot");
        byte[] wire = CloffleBytecodeSerialization.serializeRootNodes(nodes);
        assertTrue("expected embedded DCL class bytes in wire", containsTypeClassDcl(wire));

        BytecodeRootNodes<CloffleBytecodeRootNode> back = CloffleBytecodeSerialization.deserializeRootNodes(wire);
        Clojure.pushEvalThreadBindings();
        try {
            Object o = back.getNode(0).getCallTarget().call();
            assertTrue(o instanceof IDeref);
            assertEquals(42L, ((Number) ((IDeref) o).deref()).longValue());
        } finally {
            Var.popThreadBindings();
        }
    }

    @Test
    public void reifyRoundTripsInFreshJvm() throws Exception {
        BytecodeRootNodes<CloffleBytecodeRootNode> nodes =
                BytecodeDslTestSupport.compileRootNodes("(reify clojure.lang.IDeref (deref [_] 42))", "reifyRoot");
        byte[] wire = CloffleBytecodeSerialization.serializeRootNodes(nodes);
        Path tmp = Files.createTempFile("dcl-reify-wire-", ".bin");
        try {
            Files.write(tmp, wire);
            String javaExe = javaExecutable();
            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe);
            cmd.add("-Xss4m");
            cmd.add("--enable-native-access=ALL-UNNAMED");
            cmd.add("--sun-misc-unsafe-memory-access=allow");
            cmd.add("-cp");
            cmd.add(System.getProperty("java.class.path"));
            cmd.add(DclClassRoundTripChildMain.class.getName());
            cmd.add(tmp.toAbsolutePath().toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String childOut = new String(p.getInputStream().readAllBytes());
            assertTrue("child timed out", p.waitFor(2, TimeUnit.MINUTES));
            if (p.exitValue() != 0) {
                fail("child exited " + p.exitValue() + "\n" + childOut);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Best-effort: serialized output should contain {@link CloffleBytecodeSerializer#TYPE_CLASS_DCL}. */
    private static boolean containsTypeClassDcl(byte[] wire) {
        for (int i = 0; i < wire.length; i++) {
            if (wire[i] == CloffleBytecodeSerializer.TYPE_CLASS_DCL) {
                return true;
            }
        }
        return false;
    }

    private static String javaExecutable() {
        String home = System.getProperty("java.home");
        if (home == null || home.isEmpty()) {
            return "java";
        }
        return home + File.separator + "bin" + File.separator + "java";
    }
}
