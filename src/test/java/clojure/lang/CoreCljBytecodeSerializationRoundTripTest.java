package clojure.lang;

import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.serialization.SerializationUtils;
import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeDeserializer;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNodeGen;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeSerializer;
import net.javacrumbs.cloffle.bytecode.ExprToBytecode;
import net.javacrumbs.cloffle.compiler.CloffleCompiler;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.Assert.assertTrue;

/**
 * AOT wire format: for every top-level form in {@code src/clj/clojure/core.clj}, compile with
 * {@link ExprToBytecode}, serialize with {@link CloffleBytecodeSerializer}, deserialize, and assert
 * the executed value matches the pre-serialize root.
 * <p>
 * Uses the same {@link Compiler} thread bindings as {@link net.javacrumbs.cloffle.compiler.CloffleCompiler#compile}
 * so {@code macroexpand} sees correct SOURCE / LINE / COLUMN / CONSTANTS state.
 * <p>
 * The file is truncated before the trailing {@code (load "core_proxy")} … {@code (load "gvec")} block: those forms
 * re-invoke {@code Compiler.compile} on helper sources; nested bytecode evaluation still has open edge cases unrelated
 * to AOT wire format. {@link RT#init()} has already loaded the full {@code clojure.core} namespace, so analysis of
 * the main body remains valid.
 */
public class CoreCljBytecodeSerializationRoundTripTest {

    private static final Path CORE_CLJ = Path.of("src/clj/clojure/core.clj");
    /** Inclusive line number — last line of the main {@code core.clj} body before helper {@code load} calls. */
    private static final int CORE_CLJ_TRUNCATE_AFTER_LINE = 6848;
    private static final Keyword LINE_KEY = Keyword.intern(null, "line");
    private static final Keyword COLUMN_KEY = Keyword.intern(null, "column");

    private static final Object EOF = new Object();

    @BeforeClass
    public static void initRtAndUserNs() {
        System.setProperty(CloffleCompiler.EXECUTION_PROPERTY, CloffleCompiler.EXECUTION_BYTECODE);
        try {
            RT.init();
        } finally {
            System.clearProperty(CloffleCompiler.EXECUTION_PROPERTY);
        }
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
        RT.CHECK_SPECS = false;
    }

    @Test
    public void serializeDeserializeEachTopLevelFormMatchesEval() throws Exception {
        System.setProperty(CloffleCompiler.EXECUTION_PROPERTY, CloffleCompiler.EXECUTION_AST);
        assertTrue("Expected " + CORE_CLJ.toAbsolutePath(), Files.isRegularFile(CORE_CLJ));
        List<String> allLines = Files.readAllLines(CORE_CLJ, StandardCharsets.UTF_8);
        assertTrue("core.clj shorter than truncate line", allLines.size() >= CORE_CLJ_TRUNCATE_AFTER_LINE);
        String text =
                String.join("\n", allLines.subList(0, CORE_CLJ_TRUNCATE_AFTER_LINE)) + "\n";
        LineNumberingPushbackReader reader = new LineNumberingPushbackReader(new StringReader(text));
        Source source = Source.newBuilder("cloffle", text, "src/clj/clojure/core.clj").build();
        ExprToBytecode converter = new ExprToBytecode(null, source);

        Object readerOpts = RT.map(RT.READEVAL, RT.T);
        Var warnOnReflection = Var.find(Symbol.intern("clojure.core", "*warn-on-reflection*"));

        Var.pushThreadBindings(
                RT.mapUniqueKeys(
                        Compiler.SOURCE_PATH,
                        "src/clj/clojure/core.clj",
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

        int formIndex = 0;
        try {
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
                            converter.convertRoot(expr, "core_form_" + formIndex);
                    CloffleBytecodeRootNode original = nodes.getNode(0);
                    Object expected = original.getCallTarget().call();

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    nodes.serialize(new DataOutputStream(baos), new CloffleBytecodeSerializer());
                    byte[] wire = baos.toByteArray();
                    Supplier<DataInput> supplier = () -> SerializationUtils.createDataInput(ByteBuffer.wrap(wire));
                    BytecodeRootNodes<CloffleBytecodeRootNode> deserialized =
                            CloffleBytecodeRootNodeGen.deserialize(
                                    null, ExprToBytecode.BYTECODE_CONFIG, supplier, new CloffleBytecodeDeserializer());
                    Object actual = deserialized.getNode(0).getCallTarget().call();
                    assertTrue(
                            "Form #" + formIndex + " round-trip mismatch: expected " + RT.printString(expected)
                                    + " got " + RT.printString(actual),
                            Util.equiv(expected, actual));
                } finally {
                    Var.popThreadBindings();
                }

                Compiler.LINE_BEFORE.set(reader.getLineNumber());
                Compiler.COLUMN_BEFORE.set(reader.getColumnNumber());
            }
        } finally {
            Thread.currentThread().setContextClassLoader(oldCcl);
            Var.popThreadBindings();
            System.clearProperty(CloffleCompiler.EXECUTION_PROPERTY);
        }
        assertTrue("expected at least one form in core.clj", formIndex > 0);
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
