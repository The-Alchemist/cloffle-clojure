package net.javacrumbs.cloffle.compiler;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import clojure.lang.Compiler;
import clojure.lang.Compiler.C;
import clojure.lang.IMeta;
import clojure.lang.IObj;
import clojure.lang.IPersistentMap;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.LineNumberingPushbackReader;
import clojure.lang.LispReader;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentHashMap;
import clojure.lang.PersistentVector;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeSerialization;
import net.javacrumbs.cloffle.bytecode.CloffleCoreBytecodeArchive;
import net.javacrumbs.cloffle.bytecode.ExprToBytecode;
import net.javacrumbs.cloffle.nodes.value.NilNode;

import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.source.Source;

public final class CloffleCompiler {
    private static final Object EOF = new Object();
    private static final Keyword LINE_KEY = Keyword.intern(null, "line");
    private static final Keyword COLUMN_KEY = Keyword.intern(null, "column");

    /**
     * Thread-local recording state for the transitive bytecode cache. When non-null, {@link #executeFormBytecode}
     * serializes each form's bytecode alongside executing it, and {@link #compile} writes the per-file archive
     * after all forms have been processed.
     */
    private static final ThreadLocal<BytecodeCacheRecorder> RECORDER = new ThreadLocal<>();

    private CloffleCompiler() {
    }

    /**
     * Accumulates serialized bytecode chunks for one source file and tracks the output directory.
     * Created by {@link #beginRecording(Path)} and consumed by {@link #compile}.
     * <p>
     * Uses a stack for the current source path so nested {@code compile} calls (triggered by
     * {@code (load "core_print")} inside {@code core.clj}) correctly restore the parent file
     * when the child finishes.
     */
    public static final class BytecodeCacheRecorder {
        private final Path outputDir;
        private final Map<String, List<byte[]>> fileChunks = new ConcurrentHashMap<>();
        private final ThreadLocal<java.util.ArrayDeque<String>> sourcePathStack =
                ThreadLocal.withInitial(java.util.ArrayDeque::new);

        BytecodeCacheRecorder(Path outputDir) {
            this.outputDir = outputDir;
        }

        void beginFile(String sourcePath) {
            sourcePathStack.get().push(sourcePath);
            fileChunks.putIfAbsent(sourcePath, new ArrayList<>());
        }

        void addChunk(byte[] serialized) {
            String sp = sourcePathStack.get().peek();
            if (sp != null) {
                fileChunks.computeIfAbsent(sp, k -> new ArrayList<>()).add(serialized);
            }
        }

        void endFile() {
            java.util.ArrayDeque<String> stack = sourcePathStack.get();
            if (!stack.isEmpty()) {
                stack.pop();
            }
        }

        /** Write all recorded per-file archives to the output directory. */
        public void writeAll() throws IOException {
            for (Map.Entry<String, List<byte[]>> entry : fileChunks.entrySet()) {
                String sourcePath = entry.getKey();
                List<byte[]> chunks = entry.getValue();
                String bcName = sourcePath.replaceFirst("\\.(clj|cljc)$", ".bc");
                Path target = outputDir.resolve(bcName);
                Files.createDirectories(target.getParent());
                try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(target))) {
                    out.writeInt(CloffleCoreBytecodeArchive.MAGIC);
                    out.writeInt(CloffleCoreBytecodeArchive.VERSION);
                    out.writeInt(chunks.size());
                    for (byte[] c : chunks) {
                        out.writeInt(c.length);
                        out.write(c);
                    }
                }
            }
        }

        public Map<String, List<byte[]>> getFileChunks() {
            return fileChunks;
        }

        public Path getOutputDir() {
            return outputDir;
        }
    }

    /**
     * Enable bytecode cache recording: all subsequent {@link #compile} calls on this thread will serialize
     * each top-level form's bytecode to the recorder. Call {@link #endRecording()} when done.
     */
    public static BytecodeCacheRecorder beginRecording(Path outputDir) {
        BytecodeCacheRecorder recorder = new BytecodeCacheRecorder(outputDir);
        RECORDER.set(recorder);
        return recorder;
    }

    /** Disable recording on the current thread. */
    public static void endRecording() {
        RECORDER.remove();
    }

    /** Return the active recorder, or null. */
    static BytecodeCacheRecorder activeRecorder() {
        return RECORDER.get();
    }


    /**
     * Map for {@link Var#pushThreadBindings(Object)} matching {@link #compile}'s outer frame: fresh Compiler
     * constants/callsites, {@code sourcePath}/{@code sourceName}, line/column from {@code reader}, current
     * namespace and dynamic reader-related vars, and a new class loader. Pair with {@link Var#popThreadBindings()}.
     */
    public static IPersistentMap compileFrameBindings(
            LineNumberingPushbackReader reader, String sourcePath, String sourceName) {
        Var warnOnReflection = Var.find(Symbol.intern("clojure.core", "*warn-on-reflection*"));
        return RT.mapUniqueKeys(
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
                RT.makeClassLoader());
    }

    public static Object compile(Reader rdr, String sourcePath, String sourceName) throws IOException {
        LineNumberingPushbackReader pushbackReader =
                (rdr instanceof LineNumberingPushbackReader) ? (LineNumberingPushbackReader) rdr
                        : new LineNumberingPushbackReader(rdr);

        Object ret = null;
        Object readerOpts = (sourceName != null && sourceName.endsWith(".cljc"))
                ? RT.mapUniqueKeys(RT.READEVAL, RT.T,
                        LispReader.OPT_READ_COND, LispReader.COND_ALLOW)
                : RT.map(RT.READEVAL, RT.T);

        Var.pushThreadBindings(compileFrameBindings(pushbackReader, sourcePath, sourceName));

        ClassLoader parentLoader = (ClassLoader) Compiler.LOADER.deref();
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(parentLoader);

        BytecodeCacheRecorder recorder = activeRecorder();
        if (recorder != null && sourcePath != null) {
            recorder.beginFile(sourcePath);
        }

        boolean trace = Boolean.getBoolean("cloffle.trace.compile");
        int formIndex = 0;
        try {
            for (Object r = LispReader.read(pushbackReader, false, EOF, false, readerOpts); r != EOF;
                 r = LispReader.read(pushbackReader, false, EOF, false, readerOpts)) {

                int line = pushbackReader.getLineNumber();
                Compiler.LINE_AFTER.set(line);
                Compiler.COLUMN_AFTER.set(pushbackReader.getColumnNumber());
                formIndex++;

                if (trace) {
                    String formStr = RT.printString(RT.stripTypeMetaDeepForDiagnostics(r));
                    if (formStr.length() > 120) formStr = formStr.substring(0, 120) + "...";
                    System.err.println("[compile " + sourceName + "] form#" + formIndex + " line " + line + ": " + formStr);
                }

                int formLine = extractFormLine(r, line);
                int formColumn = extractFormColumn(r, 1);
                Var.pushThreadBindings(RT.mapUniqueKeys(
                        Compiler.LINE, formLine,
                        Compiler.COLUMN, formColumn));
                try {
                    ret = executeForm(r);
                } catch (Exception e) {
                    throw e;
                } finally {
                    Var.popThreadBindings();
                }

                Compiler.LINE_BEFORE.set(pushbackReader.getLineNumber());
                Compiler.COLUMN_BEFORE.set(pushbackReader.getColumnNumber());
            }
        } finally {
            if (recorder != null) {
                recorder.endFile();
            }
            Var.popThreadBindings();
            Thread.currentThread().setContextClassLoader(oldLoader);
        }

        return ret;
    }

    /**
     * Analyze, convert, and execute a single form via Truffle.
     * {@code do} blocks are split so that each subform is fully
     * executed (side effects visible) before the next is analyzed.
     */
    public static Object executeForm(Object form) throws IOException {
        Object expanded = Compiler.macroexpand(form);
        if (expanded instanceof ISeq seq) {
            Object first = seq.first();
            if (first instanceof Symbol sym
                    && "do".equals(sym.getName())
                    && sym.getNamespace() == null) {
                Object ret = null;
                for (ISeq s = seq.next(); s != null; s = s.next()) {
                    Object subForm = s.first();
                    int subLine = extractFormLine(subForm, 0);
                    int subCol = extractFormColumn(subForm, 0);
                    if (subLine > 0 || subCol > 0) {
                        Var.pushThreadBindings(RT.mapUniqueKeys(
                                Compiler.LINE, subLine > 0 ? subLine : Compiler.LINE.deref(),
                                Compiler.COLUMN, subCol > 0 ? subCol : Compiler.COLUMN.deref()));
                        try {
                            ret = executeForm(subForm);
                        } finally {
                            Var.popThreadBindings();
                        }
                    } else {
                        ret = executeForm(subForm);
                    }
                }
                return ret;
            }
        }

        // Transfer line/column metadata from original form onto the expanded form
        // so analyzeSeq() can pick it up, without re-macroexpanding.
        if (form instanceof IMeta origMeta
                && expanded instanceof IObj expandedObj) {
            IPersistentMap meta = origMeta.meta();
            if (meta != null && (meta.containsKey(LINE_KEY) || meta.containsKey(COLUMN_KEY))) {
                IPersistentMap eMeta = RT.meta(expanded);
                if (eMeta == null || !eMeta.containsKey(LINE_KEY)) {
                    IPersistentMap newMeta = eMeta != null ? eMeta : PersistentArrayMap.EMPTY;
                    Object line = meta.valAt(LINE_KEY);
                    Object col = meta.valAt(COLUMN_KEY);
                    if (line != null) newMeta = newMeta.assoc(LINE_KEY, line);
                    if (col != null) newMeta = newMeta.assoc(COLUMN_KEY, col);
                    expanded = expandedObj.withMeta(newMeta);
                }
            }
        }

        Compiler.Expr expr = Compiler.analyze(C.EVAL, expanded);
        return executeFormBytecode(expr, expanded);
    }

    /**
     * Evaluates a single analyzed form via Truffle Bytecode DSL (same semantics as {@code BytecodeDslTestSupport#evalBytecode}).
     * Source text is {@link RT#printString(Object)} of the macroexpanded form so {@link Source#getLength()} matches
     * the root {@code beginSourceSection} span.
     * @throws IOException 
     */
    public static Object executeFormBytecode(Compiler.Expr expr, Object expanded) throws IOException {
        String sourceName = "NO_SOURCE";
        try {
            Object srcPath = Compiler.SOURCE.deref();
            if (srcPath instanceof String s && !s.isEmpty() && !"NO_SOURCE_FILE".equals(s)) {
                sourceName = s;
            }
        } catch (Exception ignored) {
        }
        String text = RT.printString(expanded);
        Source source = Source.newBuilder("cloffle", text, sourceName).build();
        ExprToBytecode converter = new ExprToBytecode(null, source);
        BytecodeRootNodes<CloffleBytecodeRootNode> nodes = converter.convertRoot(expr, "compileRoot");
        Object result = nodes.getNode(0).getCallTarget().call();

        BytecodeCacheRecorder recorder = activeRecorder();
        if (recorder != null) {
            try {
                byte[] wire = CloffleBytecodeSerialization.serializeRootNodes(nodes);
                recorder.addChunk(wire);
            } catch (IOException e) {
                throw e;
            }
        }

        return result instanceof NilNode.Nil ? null : result;
    }

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
