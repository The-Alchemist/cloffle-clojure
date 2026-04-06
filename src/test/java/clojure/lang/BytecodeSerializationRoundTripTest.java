package clojure.lang;

import net.javacrumbs.cloffle.bytecode.CloffleCoreBytecodeArchive;
import net.javacrumbs.cloffle.compiler.CloffleCompiler;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Core bytecode archive wire format: same entry points as {@code build.clj}
 * {@code dump-bytecode-archive} ({@link net.javacrumbs.cloffle.CloffleBytecodeSerializerMain}{@code dump-core} →
 * {@link CloffleCoreBytecodeArchive#writeFromClasspathCore}) and {@code load-bytecode-archive}
 * ({@code verify-archive} → {@link clojure.lang.RT#init()} with {@code -Dcloffle.core.bytecode.archive=…} →
 * {@link CloffleCoreBytecodeArchive#replayFromFile}). This test runs the JVM-side write and replay without a
 * subprocess: writes classpath {@code clojure/core.clj} to a temp file, validates the CFBC header (same checks as
 * {@code info-archive}), then {@link CloffleCoreBytecodeArchive#replayFromFile replays} the archive (same loop as
 * {@link CloffleCoreBytecodeArchive#replayArchive}).
 * <p>
 * {@link RT#init()} has already loaded {@code clojure.core} from source; {@link CloffleCoreBytecodeArchiveTest}
 * shows replay after init is valid for small archives. Full core replay re-executes every top-level form from
 * deserialized bytecode (see {@link CloffleCoreBytecodeArchive#writeArchive}).
 * <p>
 * {@link #freshJvmBootstrapsCoreFromArchiveOnly} forks a <strong>new</strong> JVM with
 * {@code -Dcloffle.core.bytecode.archive} set <em>before</em> {@link RT#init()} so {@link RT#doInit()} uses
 * {@link net.javacrumbs.cloffle.bytecode.CloffleCoreBytecodeArchive} instead of {@link RT#load}{@code ("clojure/core")}.
 * DCL-emitted classes are embedded in the wire via {@link net.javacrumbs.cloffle.bytecode.CloffleBytecodeSerializer}
 * {@code TYPE_CLASS_DCL} (see {@link net.javacrumbs.cloffle.bytecode.DclClassBytecodeSerializationTest}).
 */
public class BytecodeSerializationRoundTripTest {

    @BeforeClass
    public static void initRtAndUserNs() {
        System.setProperty("cloffle.core.bytecode.quiet", "false");
        RT.init();
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
        RT.CHECK_SPECS = false;
    }

    @Test
    public void writeClasspathCoreArchiveAndReplayMatchesDumpAndLoadTasks() throws Exception {
        Path tmp = Files.createTempFile("core-bc-roundtrip-", ".bc");
        try {
            CloffleCoreBytecodeArchive.writeFromClasspathCore(tmp);
            assertArchiveHeaderOk(tmp);
            CloffleCoreBytecodeArchive.replayFromFile(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Child JVM: same flags as {@code build.clj} {@code test-jvm-opts} + archive path + quiet replay logs.
     */
    @Test
    public void freshJvmBootstrapsCoreFromArchiveOnly() throws Exception {
        Path tmp = Files.createTempFile("core-bc-bootstrap-", ".bc");
        try {
            CloffleCoreBytecodeArchive.writeFromClasspathCore(tmp);
            assertArchiveHeaderOk(tmp);

            String javaExe = javaExecutable();
            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe);
            cmd.add("-Xss4m");
            cmd.add("--enable-native-access=ALL-UNNAMED");
            cmd.add("--sun-misc-unsafe-memory-access=allow");
            cmd.add("-Dcloffle.core.bytecode.archive=" + tmp.toAbsolutePath());
            cmd.add("-Dcloffle.core.bytecode.quiet=true");
            cmd.add("-cp");
            cmd.add(System.getProperty("java.class.path"));
            cmd.add(ClojureCoreBytecodeBootstrapMain.class.getName());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            ByteArrayOutputStream childOut = new ByteArrayOutputStream();
            try (var in = p.getInputStream()) {
                in.transferTo(childOut);
            }
            boolean finished = p.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                p.destroyForcibly();
                fail("child JVM did not finish within timeout");
            }
            int exit = p.exitValue();
            if (exit != 0) {
                fail(
                        "archive-only bootstrap child exited with "
                                + exit
                                + "\n"
                                + childOut.toString(StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Dump all bootstrap .bc files via the recording mode, then replay in a fresh JVM with the
     * cache directory prepended to the classpath (so .bc files are found by the classloader).
     * The child evaluates {@code (+ 1 2)} to verify all satellite namespaces load from bytecode.
     */
    @Test
    public void freshJvmBootstrapsAllNamespacesFromBytecodeCache() throws Exception {
        Path cacheDir = Files.createTempDirectory("bc-cache-test-");
        try {
            CloffleCompiler.BytecodeCacheRecorder recorder = CloffleCompiler.beginRecording(cacheDir);
            try {
                RT.load("clojure/core");
            } finally {
                CloffleCompiler.endRecording();
            }
            recorder.writeAll();

            assertFalse("recorder should have captured at least one file",
                    recorder.getFileChunks().isEmpty());

            assertTrue("expected clojure/core.bc",
                    Files.isRegularFile(cacheDir.resolve("clojure/core.bc")));

            String javaExe = javaExecutable();
            String cpWithCache = cacheDir.toAbsolutePath()
                    + System.getProperty("path.separator")
                    + System.getProperty("java.class.path");
            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe);
            cmd.add("-Xss4m");
            cmd.add("--enable-native-access=ALL-UNNAMED");
            cmd.add("--sun-misc-unsafe-memory-access=allow");
            cmd.add("-Dcloffle.core.bytecode.quiet=true");
            cmd.add("-cp");
            cmd.add(cpWithCache);
            cmd.add(BytecodeCacheBootstrapMain.class.getName());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            ByteArrayOutputStream childOut = new ByteArrayOutputStream();
            try (var in = p.getInputStream()) {
                in.transferTo(childOut);
            }
            boolean finished = p.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                p.destroyForcibly();
                fail("child JVM did not finish within timeout");
            }
            int exit = p.exitValue();
            if (exit != 0) {
                fail("bytecode-cache bootstrap child exited with " + exit
                        + "\n" + childOut.toString(StandardCharsets.UTF_8));
            }
        } finally {
            deleteRecursive(cacheDir.toFile());
        }
    }

    private static void deleteRecursive(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        dir.delete();
    }

    private static String javaExecutable() {
        String home = System.getProperty("java.home");
        if (home == null || home.isEmpty()) {
            return "java";
        }
        return home + File.separator + "bin" + File.separator + "java";
    }

    /**
     * A child JVM sees {@code -Dcloffle.core.bytecode.archive} before any Clojure init — same command line as
     * {@link #freshJvmBootstrapsCoreFromArchiveOnly} but only checks the property (no {@link RT#init()}).
     */
    @Test
    public void freshJvmSeesBytecodeArchivePropertyBeforeInit() throws Exception {
        Path dummy = Files.createTempFile("core-bc-prop-", ".bc");
        try {
            String javaExe = javaExecutable();
            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe);
            cmd.add("-Xss4m");
            cmd.add("--enable-native-access=ALL-UNNAMED");
            cmd.add("--sun-misc-unsafe-memory-access=allow");
            cmd.add("-Dcloffle.core.bytecode.archive=" + dummy.toAbsolutePath());
            cmd.add("-cp");
            cmd.add(System.getProperty("java.class.path"));
            cmd.add(ClojureCoreBytecodeBootstrapMain.class.getName());
            cmd.add("check-property");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            assertTrue("child did not finish", p.waitFor(1, TimeUnit.MINUTES));
            assertEquals(0, p.exitValue());
        } finally {
            Files.deleteIfExists(dummy);
        }
    }

    /** Same header validation as {@code CloffleBytecodeSerializerMain} {@code info-archive}. */
    private static void assertArchiveHeaderOk(Path archivePath) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(archivePath))) {
            assertEquals(CloffleCoreBytecodeArchive.MAGIC, in.readInt());
            assertEquals(CloffleCoreBytecodeArchive.VERSION, in.readInt());
            int formCount = in.readInt();
            assertTrue("expected at least one top-level form in core.clj", formCount > 0);
        }
    }
}
