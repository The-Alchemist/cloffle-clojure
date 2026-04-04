package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Compiler;
import clojure.lang.IMeta;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.LineNumberingPushbackReader;
import clojure.lang.LispReader;
import clojure.lang.RT;
import clojure.lang.Var;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.compiler.CloffleCompiler;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Experimental AOT bundle: one Truffle-serialized {@link BytecodeRootNodes} chunk per top-level form in
 * {@code clojure/core.clj}, produced with the same {@link ExprToBytecode} source span as
 * {@link clojure.lang.BytecodeSerializationRoundTripTest} (same {@link #compileEachTopLevelForm} spine).
 * <p>
 * Enable at runtime with {@code -Dcloffle.core.bytecode.archive=/path/to/core.bc} (handled in {@link
 * clojure.lang.RT#init()}). If that property is set, the file must exist (regular file) and replay must succeed;
 * otherwise init fails (no source fallback).
 * Generate via {@link net.javacrumbs.cloffle.CloffleBytecodeSerializerMain}{@code dump-core} (see
 * {@code build.clj} {@code dump-core-bytecode}).
 * <p>
 * Successful replay logs start, duration, and form count to stderr ({@code [Cloffle]} prefix). Set
 * {@code -Dcloffle.core.bytecode.quiet=true} to disable.
 * <p>
 * Invalid headers, I/O errors, or failures while deserializing or executing a form throw (no silent
 * {@code false} return). Deserialization or evaluation failures use {@link RuntimeException} with cause.
 * <p>
 * Serialized chunks embed {@link clojure.lang.DynamicClassLoader}-defined classes (e.g. {@code reify}) via
 * {@link CloffleBytecodeSerializer}{@code TYPE_CLASS_DCL} so a cold JVM can
 * {@link clojure.lang.DynamicClassLoader#defineClass(String, byte[], Object)} without having generated those classes
 * locally (see {@link CloffleBytecodeDeserializer}).
 */
public final class CloffleCoreBytecodeArchive {

    /** Magic {@code "CFBC"} — Cloffle core bytecode cache. */
    public static final int MAGIC = 0x43464243;
    public static final int VERSION = 1;

    /**
     * Truffle {@link Source} path and short name for classpath {@code clojure/core.clj} — must stay aligned with
     * {@link #replayArchive(InputStream, String)} dummy reader bindings and with
     * {@link clojure.lang.BytecodeSerializationRoundTripTest}.
     */
    public static final String CORE_BYTECODE_SOURCE_PATH = "clojure/core.clj";
    public static final String CORE_BYTECODE_SOURCE_NAME = "core.clj";

    private static final String CORE_TOPLEVEL_ROOT_PREFIX = "core_form_";

    private static final Object EOF = new Object();

    /**
     * Invoked for each top-level form after {@link ExprToBytecode#convertRoot}; {@code formIndex} is 1-based.
     */
    @FunctionalInterface
    public interface CoreTopLevelFormConsumer {
        void accept(int formIndex, BytecodeRootNodes<CloffleBytecodeRootNode> nodes) throws Exception;
    }
    private static final Keyword LINE_KEY = Keyword.intern(null, "line");
    private static final Keyword COLUMN_KEY = Keyword.intern(null, "column");

    private CloffleCoreBytecodeArchive() {}

    /**
     * Reads {@code clojure/core.clj} from the classpath (same resource as {@link clojure.lang.RT#load}).
     * Used by {@link #writeFromClasspathCore} and tests that must match the dump archive input exactly.
     */
    public static String readClasspathCoreCljText() throws IOException {
        ClassLoader cl = CloffleCoreBytecodeArchive.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream("clojure/core.clj")) {
            if (in == null) {
                throw new IOException("classpath resource clojure/core.clj not found");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Reads {@code clojure/core.clj} from the classpath (same resource as {@link clojure.lang.RT#load}),
     * compiles each top-level form to bytecode, and writes an archive. Requires a bootstrapped runtime
     * (call {@link RT#init()} first) so {@code macroexpand} / analysis see {@code clojure.core}.
     */
    public static void writeFromClasspathCore(Path outputPath) throws Exception {
        writeArchive(outputPath, readClasspathCoreCljText(), CORE_BYTECODE_SOURCE_PATH, CORE_BYTECODE_SOURCE_NAME);
    }

    /**
     * Same top-level-form compile spine as {@link #writeArchive(Path, String, String, String)}: reader options,
     * {@link CloffleCompiler#compileFrameBindings}, LINE_AFTER/BEFORE/COLUMN, {@link ExprToBytecode} over full
     * {@code text}. Each root is named {@code core_form_}{@code <formIndex>} (1-based).
     */
    public static void compileEachTopLevelForm(
            String text, String sourcePath, String sourceName, CoreTopLevelFormConsumer consumer) throws Exception {
        LineNumberingPushbackReader reader = new LineNumberingPushbackReader(new StringReader(text));
        Source source = Source.newBuilder("cloffle", text, sourcePath).build();
        ExprToBytecode converter = new ExprToBytecode(null, source);
        Object readerOpts = RT.map(RT.READEVAL, RT.T);

        Var.pushThreadBindings(CloffleCompiler.compileFrameBindings(reader, sourcePath, sourceName));

        ClassLoader parentLoader = (ClassLoader) Compiler.LOADER.deref();
        ClassLoader oldCcl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(parentLoader);

        try {
            int formIndex = 0;
            for (Object form = LispReader.read(reader, false, EOF, false, readerOpts);
                    form != EOF;
                    form = LispReader.read(reader, false, EOF, false, readerOpts)) {
                formIndex++;
                int line = reader.getLineNumber();
                Compiler.LINE_AFTER.set(line);
                Compiler.COLUMN_AFTER.set(reader.getColumnNumber());
                int formLine = extractFormLine(form, line);
                int formColumn = extractFormColumn(form, 1);
                Var.pushThreadBindings(RT.mapUniqueKeys(Compiler.LINE, formLine, Compiler.COLUMN, formColumn));
                try {
                    Object expanded = Compiler.macroexpand(form);
                    Compiler.Expr expr = Compiler.analyze(Compiler.C.EVAL, expanded);
                    BytecodeRootNodes<CloffleBytecodeRootNode> nodes =
                            converter.convertRoot(expr, CORE_TOPLEVEL_ROOT_PREFIX + formIndex);
                    consumer.accept(formIndex, nodes);
                } finally {
                    Var.popThreadBindings();
                }
                Compiler.LINE_BEFORE.set(reader.getLineNumber());
                Compiler.COLUMN_BEFORE.set(reader.getColumnNumber());
            }
        } finally {
            Thread.currentThread().setContextClassLoader(oldCcl);
            Var.popThreadBindings();
        }
    }

    /**
     * Serialize every top-level form in {@code text} (full {@code core.clj} body) into {@code outputPath}.
     * Nested evaluation during archive build matches the same {@link CloffleCompiler} bytecode path as
     * {@link #compileEachTopLevelForm} / {@link clojure.lang.BytecodeSerializationRoundTripTest}.
     */
    public static void writeArchive(Path outputPath, String text, String sourcePath, String sourceName)
            throws Exception {
        List<byte[]> chunks = new ArrayList<>();
        compileEachTopLevelForm(text, sourcePath, sourceName, (formIndex, nodes) -> {
            nodes.getNode(0).getCallTarget().call();
            chunks.add(CloffleBytecodeSerialization.serializeRootNodes(nodes));
        });

        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(outputPath))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(chunks.size());
            for (byte[] c : chunks) {
                out.writeInt(c.length);
                out.write(c);
            }
        }
    }

    public static void replayFromFile(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            replayArchive(in, path.toAbsolutePath().toString());
        }
    }

    /**
     * Replay a per-file bytecode cache from disk, using the given source path/name for compile-frame bindings.
     */
    public static void replayFromFile(Path path, String sourcePath, String sourceName) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            replayArchive(in, path.toAbsolutePath().toString(), sourcePath, sourceName);
        }
    }

    /**
     * Same as {@link #replayArchive(InputStream, String)} with source label {@code "(stream)"} for logs.
     */
    public static void replayArchive(InputStream rawIn) throws IOException {
        replayArchive(rawIn, "(stream)");
    }

    /**
     * Replays a bytecode archive using {@code clojure/core.clj} as the source path (legacy entry point).
     */
    public static void replayArchive(InputStream rawIn, String sourceLabel) throws IOException {
        replayArchive(rawIn, sourceLabel, CORE_BYTECODE_SOURCE_PATH, CORE_BYTECODE_SOURCE_NAME);
    }

    /**
     * Replays a bytecode archive from a stream. Pushes {@link CloffleCompiler}-style compile bindings using the
     * given {@code sourcePath}/{@code sourceName}, executes each deserialized root, then pops them.
     * <p>
     * Logs start/end and duration to stderr on success unless {@code -Dcloffle.core.bytecode.quiet=true}.
     * <p>
     * Throws {@link IOException} if the header is invalid or the stream ends early; throws
     * {@link RuntimeException} if a form fails to deserialize or execute.
     *
     * @param sourceLabel shown in log lines (e.g. absolute file path or {@code resource:clojure/core.bc})
     * @param sourcePath  classpath-style path for {@link Compiler#SOURCE_PATH} (e.g. {@code clojure/core_print.clj})
     * @param sourceName  short file name for {@link Compiler#SOURCE} (e.g. {@code core_print.clj})
     */
    public static void replayArchive(InputStream rawIn, String sourceLabel,
                                     String sourcePath, String sourceName) throws IOException {
        DataInputStream in = new DataInputStream(rawIn);
        if (in.readInt() != MAGIC) {
            throw new IOException(
                    "bytecode cache: wrong magic (not a CFBC archive): " + sourceLabel);
        }
        if (in.readInt() != VERSION) {
            throw new IOException(
                    "bytecode cache: unsupported format version (expected "
                            + VERSION
                            + "): "
                            + sourceLabel);
        }
        int formCount = in.readInt();
        if (formCount < 0) {
            throw new IOException("bytecode cache: invalid form count: " + formCount + ": " + sourceLabel);
        }

        int depth = replayDepth.incrementAndGet();
        if (depth == 1) {
            replayStartNanosOuter.set(System.nanoTime());
        }

        Source sourceOverride = loadSourceFromClasspath(sourcePath, sourceName);
        if (sourceOverride != null) {
            CloffleBytecodeDeserializer.setSourceOverride(sourceOverride);
        }

        LineNumberingPushbackReader dummyReader = new LineNumberingPushbackReader(new StringReader(""));

        Var.pushThreadBindings(
                CloffleCompiler.compileFrameBindings(dummyReader, sourcePath, sourceName));

        ClassLoader parentLoader = (ClassLoader) Compiler.LOADER.deref();
        ClassLoader oldCcl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(parentLoader);
        try {
            for (int i = 0; i < formCount; i++) {
                int len = in.readInt();
                byte[] wire = new byte[len];
                in.readFully(wire);
                Var.pushThreadBindings(RT.mapUniqueKeys(Compiler.LINE, i + 1, Compiler.COLUMN, 1));
                try {
                    BytecodeRootNodes<CloffleBytecodeRootNode> nodes =
                            CloffleBytecodeSerialization.deserializeRootNodes(wire);
                    Object result = nodes.getNode(0).getCallTarget().call();
                    keep(result);
                } finally {
                    Var.popThreadBindings();
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("bytecode replay failed for " + sourcePath, e);
        } finally {
            CloffleBytecodeDeserializer.clearSourceOverride();
            Thread.currentThread().setContextClassLoader(oldCcl);
            Var.popThreadBindings();
        }
        replayFileCount.incrementAndGet();
        replayFormCount.addAndGet(formCount);
        if (replayDepth.decrementAndGet() == 0) {
            printReplaySummary();
        }
    }

    private static final AtomicInteger replayDepth = new AtomicInteger(0);
    private static final AtomicInteger replayFileCount = new AtomicInteger(0);
    private static final AtomicInteger replayFormCount = new AtomicInteger(0);
    private static final AtomicLong replayStartNanosOuter = new AtomicLong(0);

    /**
     * Print a one-line summary of all bytecode cache replays so far, then reset counters.
     * Called automatically when the outermost replay finishes.
     */
    public static void printReplaySummary() {
        int files = replayFileCount.getAndSet(0);
        int forms = replayFormCount.getAndSet(0);
        long startNanos = replayStartNanosOuter.getAndSet(0);
        if (files > 0 && startNanos > 0) {
            long ms = (System.nanoTime() - startNanos) / 1_000_000L;
            archiveLog("Bytecode cache: loaded " + files + " file(s) (" + forms + " forms) in " + ms + " ms");
        }
    }

    private static boolean archiveLogQuiet() {
        return Boolean.parseBoolean(System.getProperty("cloffle.core.bytecode.quiet", "false"));
    }

    private static void archiveLog(String message) {
        if (!archiveLogQuiet()) {
            System.err.println("[Cloffle] " + message);
        }
    }

    /**
     * Try to load the original source text from the classpath so deserialized bytecode nodes
     * carry the real source content rather than the serialization placeholder.
     */
    private static Source loadSourceFromClasspath(String sourcePath, String sourceName) {
        try {
            InputStream ins = RT.resourceAsStream(RT.baseLoader(), sourcePath);
            if (ins == null) return null;
            try (ins) {
                String text = new String(ins.readAllBytes(), StandardCharsets.UTF_8);
                return Source.newBuilder("cloffle", text, sourceName).build();
            }
        } catch (IOException e) {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private static void keep(Object o) {}

    private static int extractFormLine(Object form, int fallback) {
        if (form instanceof IMeta m) {
            IPersistentMap meta = m.meta();
            if (meta != null) {
                Object line = meta.valAt(LINE_KEY);
                if (line instanceof Number n && n.intValue() > 0) {
                    return n.intValue();
                }
            }
        }
        return fallback;
    }

    private static int extractFormColumn(Object form, int fallback) {
        if (form instanceof IMeta m) {
            IPersistentMap meta = m.meta();
            if (meta != null) {
                Object col = meta.valAt(COLUMN_KEY);
                if (col instanceof Number n && n.intValue() > 0) {
                    return n.intValue();
                }
            }
        }
        return fallback;
    }
}
