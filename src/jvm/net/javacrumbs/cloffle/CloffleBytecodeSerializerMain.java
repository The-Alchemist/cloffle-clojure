package net.javacrumbs.cloffle;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

import net.javacrumbs.cloffle.bytecode.CloffleCoreBytecodeArchive;
import net.javacrumbs.cloffle.compiler.CloffleCompiler;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * CLI for producing and validating Truffle core bytecode archives ({@link CloffleCoreBytecodeArchive}).
 * Per-form bytecode chunks use {@link net.javacrumbs.cloffle.bytecode.CloffleBytecodeSerialization} (same
 * serialize/deserialize entry points as JVM tests). Used by build tasks (for example {@code dump-core-bytecode}
 * in {@code build.clj}); not tied to the interactive REPL.
 *
 * <p>Commands:
 * <ul>
 *   <li>{@code dump-core &lt;output-path&gt;} — open a Cloffle Polyglot context (runs {@link clojure.lang.RT#init()}
 *       from source), then serialize classpath {@code clojure/core.clj} top-level forms to the given file.</li>
 *   <li>{@code dump-bootstrap &lt;output-dir&gt;} — delete every {@code .bc} file under the output directory, then
 *       open a Cloffle Polyglot context with bytecode recording enabled, capturing per-file {@code .bc} archives for
 *       every {@code .clj} loaded during bootstrap (core + satellites). Clearing on-disk {@code .bc} avoids replaying
 *       them from the classpath (which would bypass the recorder).</li>
 *   <li>{@code info-archive &lt;archive-path&gt;} — read and validate the CFBC header (magic, version, form count).</li>
 *   <li>{@code verify-archive} / {@code load-archive} {@code &lt;archive-path&gt;} — set
 *       {@link #CORE_BYTECODE_ARCHIVE_PROP}, open a context so {@code clojure.core} loads from the archive, then
 *       evaluate {@code (+ 1 2)}. Same behavior; {@code load-archive} is the descriptive alias. Does not fall back
 *       to source; bootstrap failure throws. Use {@code info-archive} for an on-disk header check only.</li>
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

    private static String usageMessage() {
        String cn = CloffleBytecodeSerializerMain.class.getName();
        return String.join(
                "\n",
                "Usage:",
                "  java … " + cn + " dump-core <output-path>",
                "  java … " + cn + " dump-bootstrap <output-dir>",
                "  java … " + cn + " info-archive <archive-path>",
                "  java … " + cn + " verify-archive|load-archive <archive-path>");
    }

    private static void usageError() {
        throw new IllegalArgumentException(usageMessage());
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            usageError();
        }
        String cmd = args[0];
        Path path = Path.of(args[1].trim());
        switch (cmd) {
            case "dump-core" -> runDumpCore(path);
            case "dump-bootstrap" -> runDumpBootstrap(path);
            case "info-archive" -> runInfoArchive(path);
            case "verify-archive", "load-archive" -> runVerifyArchive(path);
            default -> usageError();
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

    private static void runDumpBootstrap(Path outputDir) throws Exception {
        Files.createDirectories(outputDir);
        log("Dumping all .bc files to: " + outputDir.toAbsolutePath());
        log("Removing existing .bc files under output dir (so bootstrap compiles from source)…");
        deleteExistingBytecodeArchives(outputDir);
        log("Enabling bytecode recording, then booting RT.init from source…");

        List<String> extraNamespaces = discoverClojureNamespaces();

        CloffleCompiler.BytecodeCacheRecorder recorder = CloffleCompiler.beginRecording(outputDir);
        try {
            try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
                context.initialize("cloffle");

                if (!extraNamespaces.isEmpty()) {
                    log("Requiring " + extraNamespaces.size() + " additional namespaces…");
                    for (String ns : extraNamespaces) {
                        try {
                            Source req = Source.newBuilder("cloffle",
                                    "(require '" + ns + ")", "dump-require").buildLiteral();
                            context.eval(req);
                        } catch (Exception e) {
                            log("  WARN: failed to require " + ns + ": " + e.getMessage());
                        }
                    }
                }
            }
        } finally {
            CloffleCompiler.endRecording();
        }

        recorder.writeAll();

        Map<String, List<byte[]>> files = recorder.getFileChunks();
        log("Wrote " + files.size() + " bytecode cache files:");
        for (Map.Entry<String, List<byte[]>> e : files.entrySet()) {
            String bcName = e.getKey().replaceFirst("\\.(clj|cljc)$", ".bc");
            log("  " + bcName + " (" + e.getValue().size() + " forms)");
        }
    }

    /** Deletes every regular file ending in {@code .bc} under {@code root} (recursive). */
    private static void deleteExistingBytecodeArchives(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> bcFiles =
                    walk.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".bc")).toList();
            for (Path p : bcFiles) {
                Files.delete(p);
            }
        }
    }

    /**
     * Scan the classpath for {@code clojure/**\/*.clj} files that declare {@code (ns ...)} and convert
     * file paths to namespace symbols. Helper files loaded via {@code (load ...)} use {@code (in-ns ...)}
     * instead and are captured transitively when their parent is required.
     */
    private static List<String> discoverClojureNamespaces() throws IOException {
        String clojureSrcDir = System.getProperty("cloffle.dump.clojure.src");
        if (clojureSrcDir == null) {
            for (String entry : System.getProperty("java.class.path", "").split(System.getProperty("path.separator"))) {
                Path candidate = Path.of(entry, "clojure", "core.clj");
                if (Files.isRegularFile(candidate)) {
                    clojureSrcDir = entry;
                    break;
                }
            }
        }
        if (clojureSrcDir == null) {
            log("Could not locate clojure source root on classpath; skipping extra namespace discovery.");
            return List.of();
        }

        Path srcRoot = Path.of(clojureSrcDir);
        Path clojureDir = srcRoot.resolve("clojure");
        if (!Files.isDirectory(clojureDir)) {
            return List.of();
        }

        List<String> namespaces = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(clojureDir)) {
            walk.filter(p -> p.toString().endsWith(".clj") && Files.isRegularFile(p))
                .sorted()
                .forEach(p -> {
                    try {
                        String first1k = Files.readString(p).substring(0, Math.min(1024, (int) Files.size(p)));
                        if (!first1k.contains("(ns ") && !first1k.contains("(ns\n") && !first1k.contains("(ns\r")) {
                            return;
                        }
                        String rel = srcRoot.relativize(p).toString();
                        String ns = rel.replaceFirst("\\.clj$", "")
                                       .replace('/', '.')
                                       .replace('_', '-');
                        if (ns.equals("clojure.core") || ns.equals("clojure.parallel")) {
                            return;
                        }
                        namespaces.add(ns);
                    } catch (IOException ignored) {
                    }
                });
        }
        return namespaces;
    }

    private static void runInfoArchive(Path archivePath) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(archivePath))) {
            int magic = in.readInt();
            if (magic != CloffleCoreBytecodeArchive.MAGIC) {
                throw new IllegalStateException(
                        TAG + " wrong magic (not a CFBC archive): " + archivePath.toAbsolutePath());
            }
            int version = in.readInt();
            if (version != CloffleCoreBytecodeArchive.VERSION) {
                throw new IllegalStateException(
                        TAG
                                + " unsupported format version "
                                + version
                                + " (expected "
                                + CloffleCoreBytecodeArchive.VERSION
                                + ")");
            }
            int formCount = in.readInt();
            if (formCount < 0) {
                throw new IllegalStateException(TAG + " invalid form count: " + formCount);
            }
            log("OK: " + formCount + " top-level forms (format version " + version + ").");
        }
    }

    private static void runVerifyArchive(Path archivePath) throws Exception {
        if (!Files.isRegularFile(archivePath)) {
            throw new IllegalArgumentException(
                    TAG + " not a regular file: " + archivePath.toAbsolutePath());
        }
        System.setProperty(CORE_BYTECODE_ARCHIVE_PROP, archivePath.toAbsolutePath().toString());
        log("Bootstrapping with archive:");
        log("  " + archivePath.toAbsolutePath());
        try (Context context = Context.newBuilder("cloffle").allowAllAccess(true).build()) {
            Source src = Source.newBuilder("cloffle", "(+ 1 2)", "verify-archive").buildLiteral();
            Object v = context.eval(src).as(Object.class);
            long n = (v instanceof Number num) ? num.longValue() : Long.MIN_VALUE;
            if (n != 3L) {
                throw new IllegalStateException(TAG + " unexpected eval result: " + v);
            }
            log("verify-archive OK (eval (+ 1 2) => 3).");
        }
    }
}
