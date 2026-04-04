package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

import net.javacrumbs.cloffle.bytecode.CloffleCoreBytecodeArchive;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI for producing and validating Truffle core bytecode archives ({@link CloffleCoreBytecodeArchive}).
 * Used by build tasks (for example {@code dump-core-bytecode} in {@code build.clj}); not tied to the
 * interactive REPL.
 *
 * <p>Commands:
 * <ul>
 *   <li>{@code dump-core &lt;output-path&gt;} — open a Cloffle Polyglot context (runs {@code RT.init} from
 *       source), then serialize classpath {@code clojure/core.clj} top-level forms to the given file.</li>
 *   <li>{@code info-archive &lt;archive-path&gt;} — read and validate the CFBC header (magic, version, form count).</li>
 *   <li>{@code verify-archive &lt;archive-path&gt;} — set {@link #CORE_BYTECODE_ARCHIVE_PROP}, open a context
 *       so {@code clojure.core} loads from the archive, then evaluate {@code (+ 1 2)}. If replay falls back to
 *       source (see {@code RT}), this still passes when Clojure loads — use {@code info-archive} for a strict
 *       on-disk format check.</li>
 * </ul>
 *
 * <p>To run the Polyglot REPL or scripts with an archive, set {@code -D}{@value #CORE_BYTECODE_ARCHIVE_PROP}
 * {@code =/path/to/core.bc} on the JVM when launching {@link CloffleRepl} or another entry point.
 */
public final class CloffleBytecodeSerializerMain {

    /**
     * Bootstrap {@code clojure.core} from this file at {@link clojure.lang.RT#init()} (see {@code RT}).
     */
    public static final String CORE_BYTECODE_ARCHIVE_PROP = "cloffle.core.bytecode.archive";

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String TAG = "[Cloffle bytecode archive]";

    private static void log(String message) {
        System.err.println(BOLD + TAG + RESET + " " + message);
        System.err.flush();
    }

    private static void usageAndExit() {
        System.err.println("Usage:");
        System.err.println("  java … " + CloffleBytecodeSerializerMain.class.getName() + " dump-core <output-path>");
        System.err.println("  java … " + CloffleBytecodeSerializerMain.class.getName() + " info-archive <archive-path>");
        System.err.println("  java … " + CloffleBytecodeSerializerMain.class.getName() + " verify-archive <archive-path>");
        System.exit(2);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            usageAndExit();
        }
        String cmd = args[0];
        Path path = Path.of(args[1].trim());
        switch (cmd) {
            case "dump-core" -> runDumpCore(path);
            case "info-archive" -> runInfoArchive(path);
            case "verify-archive" -> runVerifyArchive(path);
            default -> usageAndExit();
        }
    }

    private static void runDumpCore(Path outputPath) throws Exception {
        log("Creating Polyglot context (RT.init from source, then dump)…");
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            log("Writing classpath clojure/core.clj bytecode archive to:");
            log("  " + outputPath.toAbsolutePath());
            CloffleCoreBytecodeArchive.writeFromClasspathCore(outputPath);
            log("Wrote core bytecode archive.");
        }
    }

    private static void runInfoArchive(Path archivePath) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(archivePath))) {
            int magic = in.readInt();
            if (magic != CloffleCoreBytecodeArchive.MAGIC) {
                System.err.println(TAG + " wrong magic (not a CFBC archive): " + archivePath.toAbsolutePath());
                System.exit(1);
            }
            int version = in.readInt();
            if (version != CloffleCoreBytecodeArchive.VERSION) {
                System.err.println(
                        TAG + " unsupported format version " + version + " (expected " + CloffleCoreBytecodeArchive.VERSION + ")");
                System.exit(1);
            }
            int formCount = in.readInt();
            if (formCount < 0) {
                System.err.println(TAG + " invalid form count: " + formCount);
                System.exit(1);
            }
            log("OK: " + formCount + " top-level forms (format version " + version + ").");
        }
    }

    private static void runVerifyArchive(Path archivePath) throws Exception {
        if (!java.nio.file.Files.isRegularFile(archivePath)) {
            System.err.println(TAG + " not a regular file: " + archivePath.toAbsolutePath());
            System.exit(1);
        }
        System.setProperty(CORE_BYTECODE_ARCHIVE_PROP, archivePath.toAbsolutePath().toString());
        log("Bootstrapping with archive:");
        log("  " + archivePath.toAbsolutePath());
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Source src = Source.newBuilder("cloffle", "(+ 1 2)", "verify-archive").buildLiteral();
            Object v = context.eval(src).as(Object.class);
            long n = (v instanceof Number num) ? num.longValue() : Long.MIN_VALUE;
            if (n != 3L) {
                System.err.println(TAG + " unexpected eval result: " + v);
                System.exit(1);
            }
            log("verify-archive OK (eval (+ 1 2) => 3).");
        }
    }
}
