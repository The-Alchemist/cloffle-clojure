package net.javacrumbs.cloffle.bytecode;

import clojure.lang.Compiler;
import clojure.lang.IMeta;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.LineNumberingPushbackReader;
import clojure.lang.LispReader;
import clojure.lang.PersistentHashMap;
import clojure.lang.PersistentVector;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.serialization.SerializationUtils;
import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.compiler.CloffleCompiler;
import net.javacrumbs.cloffle.nodes.value.NilNode;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Supplier;

/**
 * Experimental AOT bundle: one Truffle-serialized {@link BytecodeRootNodes} chunk per top-level form in
 * {@code clojure/core.clj}, produced with the same {@link ExprToBytecode} source span as
 * {@link clojure.lang.CoreCljBytecodeSerializationRoundTripTest} (full-file {@link Source}).
 * <p>
 * Enable at runtime with {@code -Dcloffle.core.bytecode.archive=/path/to/core.cfbc} (handled in {@link
 * clojure.lang.RT#init()}). If that property is set, the file must exist (regular file) or init fails.
 * Generate via {@link CloffleRepl} and {@code -Dcloffle.core.bytecode.dump=...}.
 * <p>
 * Replay logs start, duration, and form count to stderr ({@code [Cloffle]} prefix). Set
 * {@code -Dcloffle.core.bytecode.quiet=true} to disable.
 */
public final class CloffleCoreBytecodeArchive {

    /** Magic {@code "CFBC"} — Cloffle core bytecode cache. */
    public static final int MAGIC = 0x43464243;
    public static final int VERSION = 1;

    /**
     * After a successful replay, milliseconds spent executing deserialized forms (not including header read).
     * Cleared at the start of each {@link #replayArchive(InputStream, String)}. Survives {@code quiet} mode so
     * UIs can show timing (see {@link net.javacrumbs.cloffle.CloffleRepl}).
     */
    public static final String PROP_LAST_REPLAY_MS = "cloffle.core.bytecode.lastReplayMs";

    /** Form count from the archive header; pair with {@link #PROP_LAST_REPLAY_MS}. */
    public static final String PROP_LAST_REPLAY_FORM_COUNT = "cloffle.core.bytecode.lastReplayFormCount";

    private static final Object EOF = new Object();
    private static final Keyword LINE_KEY = Keyword.intern(null, "line");
    private static final Keyword COLUMN_KEY = Keyword.intern(null, "column");

    private CloffleCoreBytecodeArchive() {}

    /**
     * Reads {@code clojure/core.clj} from the classpath (same resource as {@link clojure.lang.RT#load}),
     * compiles each top-level form to bytecode, and writes an archive. Requires a bootstrapped runtime
     * (call {@link RT#init()} first) so {@code macroexpand} / analysis see {@code clojure.core}.
     */
    public static void writeFromClasspathCore(Path outputPath) throws Exception {
        ClassLoader cl = CloffleCoreBytecodeArchive.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream("clojure/core.clj")) {
            if (in == null) {
                throw new IOException("classpath resource clojure/core.clj not found");
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            writeArchive(outputPath, text, "clojure/core.clj", "core.clj");
        }
    }

    /**
     * Serialize every top-level form in {@code text} (full {@code core.clj} body) into {@code outputPath}.
     * Uses {@link CloffleCompiler#EXECUTION_AST} for {@link ExprToBytecode} conversion to match serialization tests.
     */
    public static void writeArchive(Path outputPath, String text, String sourcePath, String sourceName)
            throws Exception {
        System.setProperty(CloffleCompiler.EXECUTION_PROPERTY, CloffleCompiler.EXECUTION_AST);
        try {
            List<byte[]> chunks = new ArrayList<>();
            LineNumberingPushbackReader reader = new LineNumberingPushbackReader(new StringReader(text));
            Source source = Source.newBuilder("cloffle", text, sourcePath).build();
            ExprToBytecode converter = new ExprToBytecode(null, source);
            Object readerOpts = RT.map(RT.READEVAL, RT.T);
            Var warnOnReflection = Var.find(Symbol.intern("clojure.core", "*warn-on-reflection*"));

            Var.pushThreadBindings(
                    RT.mapUniqueKeys(
                            Compiler.SOURCE_PATH,
                            sourcePath,
                            Compiler.SOURCE,
                            sourceName,
                            Compiler.METHOD,
                            null,
                            Compiler.LOCAL_ENV,
                            null,
                            Compiler.LOOP_LOCALS,
                            null,
                            Compiler.NEXT_LOCAL_NUM,
                            0,
                            RT.READEVAL,
                            RT.T,
                            RT.CURRENT_NS,
                            RT.CURRENT_NS.deref(),
                            Compiler.LINE_BEFORE,
                            reader.getLineNumber(),
                            Compiler.COLUMN_BEFORE,
                            reader.getColumnNumber(),
                            Compiler.LINE_AFTER,
                            reader.getLineNumber(),
                            Compiler.COLUMN_AFTER,
                            reader.getColumnNumber(),
                            Compiler.CONSTANTS,
                            PersistentVector.EMPTY,
                            Compiler.CONSTANT_IDS,
                            new IdentityHashMap<>(),
                            Compiler.KEYWORD_CALLSITES,
                            PersistentVector.EMPTY,
                            Compiler.PROTOCOL_CALLSITES,
                            PersistentVector.EMPTY,
                            Compiler.KEYWORDS,
                            PersistentHashMap.EMPTY,
                            Compiler.VARS,
                            PersistentHashMap.EMPTY,
                            RT.UNCHECKED_MATH,
                            RT.UNCHECKED_MATH.deref(),
                            warnOnReflection,
                            warnOnReflection.deref(),
                            RT.DATA_READERS,
                            RT.DATA_READERS.deref(),
                            Compiler.LOADER,
                            RT.makeClassLoader()));

            ClassLoader parentLoader = (ClassLoader) Compiler.LOADER.deref();
            ClassLoader oldCcl = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(parentLoader);
            try {
                for (Object form = LispReader.read(reader, false, EOF, false, readerOpts);
                        form != EOF;
                        form = LispReader.read(reader, false, EOF, false, readerOpts)) {
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
                                converter.convertRoot(expr, "core_archive_form_" + (chunks.size() + 1));
                        Object evaluated = nodes.getNode(0).getCallTarget().call();
                        keep(evaluated instanceof NilNode.Nil ? null : evaluated);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        nodes.serialize(new DataOutputStream(baos), new CloffleBytecodeSerializer());
                        chunks.add(baos.toByteArray());
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

            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(outputPath))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(chunks.size());
                for (byte[] c : chunks) {
                    out.writeInt(c.length);
                    out.write(c);
                }
            }
        } finally {
            System.clearProperty(CloffleCompiler.EXECUTION_PROPERTY);
        }
    }

    public static boolean replayFromFile(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return replayArchive(in, path.toAbsolutePath().toString());
        }
    }

    /**
     * Same as {@link #replayArchive(InputStream, String)} with source label {@code "(stream)"} for logs.
     */
    public static boolean replayArchive(InputStream rawIn) throws IOException {
        return replayArchive(rawIn, "(stream)");
    }

    /**
     * Replays a core bootstrap from an archive stream. Caller must have pushed the same outer bindings as
     * {@link clojure.lang.RT#doInit()} (before {@code clojure.core} load). This method pushes
     * {@link CloffleCompiler}-style compile bindings, executes each deserialized root, then pops them.
     * <p>
     * Logs start/end and duration to stderr unless {@code -Dcloffle.core.bytecode.quiet=true}.
     *
     * @param sourceLabel shown in log lines (e.g. absolute file path or {@code resource:clojure/core.cfbc})
     */
    public static boolean replayArchive(InputStream rawIn, String sourceLabel) throws IOException {
        clearReplayResultProperties();
        DataInputStream in = new DataInputStream(rawIn);
        if (in.readInt() != MAGIC) {
            archiveLog("clojure.core bytecode cache: wrong magic (not a CFBC archive), skipping: " + sourceLabel);
            return false;
        }
        if (in.readInt() != VERSION) {
            archiveLog(
                    "clojure.core bytecode cache: unsupported format version (expected "
                            + VERSION
                            + "), skipping: "
                            + sourceLabel);
            return false;
        }
        int formCount = in.readInt();
        if (formCount < 0) {
            archiveLog("clojure.core bytecode cache: invalid form count, skipping: " + sourceLabel);
            return false;
        }

        archiveLog(
                "Loading clojure.core from bytecode cache: "
                        + sourceLabel
                        + " ("
                        + formCount
                        + " top-level forms)…");
        long replayStartNanos = System.nanoTime();

        LineNumberingPushbackReader dummyReader = new LineNumberingPushbackReader(new StringReader(""));
        Var warnOnReflection = Var.find(Symbol.intern("clojure.core", "*warn-on-reflection*"));

        Var.pushThreadBindings(
                RT.mapUniqueKeys(
                        Compiler.SOURCE_PATH,
                        "clojure/core.clj",
                        Compiler.SOURCE,
                        "core.clj",
                        Compiler.METHOD,
                        null,
                        Compiler.LOCAL_ENV,
                        null,
                        Compiler.LOOP_LOCALS,
                        null,
                        Compiler.NEXT_LOCAL_NUM,
                        0,
                        RT.READEVAL,
                        RT.T,
                        RT.CURRENT_NS,
                        RT.CURRENT_NS.deref(),
                        Compiler.LINE_BEFORE,
                        dummyReader.getLineNumber(),
                        Compiler.COLUMN_BEFORE,
                        dummyReader.getColumnNumber(),
                        Compiler.LINE_AFTER,
                        dummyReader.getLineNumber(),
                        Compiler.COLUMN_AFTER,
                        dummyReader.getColumnNumber(),
                        Compiler.CONSTANTS,
                        PersistentVector.EMPTY,
                        Compiler.CONSTANT_IDS,
                        new IdentityHashMap<>(),
                        Compiler.KEYWORD_CALLSITES,
                        PersistentVector.EMPTY,
                        Compiler.PROTOCOL_CALLSITES,
                        PersistentVector.EMPTY,
                        Compiler.KEYWORDS,
                        PersistentHashMap.EMPTY,
                        Compiler.VARS,
                        PersistentHashMap.EMPTY,
                        RT.UNCHECKED_MATH,
                        RT.UNCHECKED_MATH.deref(),
                        warnOnReflection,
                        warnOnReflection.deref(),
                        RT.DATA_READERS,
                        RT.DATA_READERS.deref(),
                        Compiler.LOADER,
                        RT.makeClassLoader()));

        ClassLoader parentLoader = (ClassLoader) Compiler.LOADER.deref();
        ClassLoader oldCcl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(parentLoader);
        try {
            for (int i = 0; i < formCount; i++) {
                int len = in.readInt();
                if (len < 0 || len > 512 * 1024 * 1024) {
                    long partialMs = (System.nanoTime() - replayStartNanos) / 1_000_000L;
                    archiveLog(
                            "clojure.core bytecode cache aborted after "
                                    + partialMs
                                    + " ms at form "
                                    + (i + 1)
                                    + "/"
                                    + formCount
                                    + " (bad chunk length): "
                                    + sourceLabel);
                    return false;
                }
                byte[] wire = new byte[len];
                in.readFully(wire);
                Var.pushThreadBindings(RT.mapUniqueKeys(Compiler.LINE, i + 1, Compiler.COLUMN, 1));
                try {
                    Supplier<DataInput> supplier =
                            () -> SerializationUtils.createDataInput(ByteBuffer.wrap(wire));
                    BytecodeRootNodes<CloffleBytecodeRootNode> nodes =
                            CloffleBytecodeRootNodeGen.deserialize(
                                    null, ExprToBytecode.BYTECODE_CONFIG, supplier, new CloffleBytecodeDeserializer());
                    Object result = nodes.getNode(0).getCallTarget().call();
                    keep(result instanceof NilNode.Nil ? null : result);
                } finally {
                    Var.popThreadBindings();
                }
            }
        } catch (Exception e) {
            long failedMs = (System.nanoTime() - replayStartNanos) / 1_000_000L;
            archiveLog(
                    "clojure.core bytecode cache failed after "
                            + failedMs
                            + " ms ("
                            + sourceLabel
                            + "): "
                            + e.getMessage());
            throw new IOException("core bytecode replay failed at bootstrap", e);
        } finally {
            Thread.currentThread().setContextClassLoader(oldCcl);
            Var.popThreadBindings();
        }
        long replayMs = (System.nanoTime() - replayStartNanos) / 1_000_000L;
        archiveLog(
                "clojure.core bytecode cache loaded in "
                        + replayMs
                        + " ms ("
                        + formCount
                        + " forms) — "
                        + sourceLabel);
        System.setProperty(PROP_LAST_REPLAY_MS, Long.toString(replayMs));
        System.setProperty(PROP_LAST_REPLAY_FORM_COUNT, Integer.toString(formCount));
        return true;
    }

    private static void clearReplayResultProperties() {
        System.clearProperty(PROP_LAST_REPLAY_MS);
        System.clearProperty(PROP_LAST_REPLAY_FORM_COUNT);
    }

    private static boolean archiveLogQuiet() {
        return Boolean.parseBoolean(System.getProperty("cloffle.core.bytecode.quiet", "false"));
    }

    private static void archiveLog(String message) {
        if (!archiveLogQuiet()) {
            System.err.println("[Cloffle] " + message);
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
