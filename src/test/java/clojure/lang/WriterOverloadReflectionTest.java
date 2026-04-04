package clojure.lang;

import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

/**
 * {@code clojure.instant} (and similar) use {@code (.write w calstr off len)} on {@link java.io.Writer}. The
 * Compiler may emit reflection warnings when it cannot prove {@code calstr} is a {@link String} (overload pick vs
 * {@code write(char[],int,int)}), depending on inference for {@code format} and numeric locals — see
 * {@link Compiler#getTypeStringForArgs} and {@link Compiler.InstanceMethodExpr} when {@code method} is null.
 * <p>
 * Those warnings come from analyzing <strong>source-loaded</strong> {@code .clj} files during bootstrap, not from
 * missing type data in Truffle bytecode serialization of {@code clojure.core} archive chunks. Hinting {@code calstr}
 * as {@code ^String} matches {@code Writer.write(String,int,int)} and avoids ambiguous resolution.
 */
public class WriterOverloadReflectionTest {

    private static final Object READ_EOF = new Object();

    @BeforeClass
    public static void initRt() {
        RT.init();
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
    }

    private static Object readOne(String src) {
        LineNumberingPushbackReader pbr = new LineNumberingPushbackReader(new java.io.StringReader(src));
        Object opts = RT.map(RT.READEVAL, RT.T);
        return LispReader.read(pbr, false, READ_EOF, false, opts);
    }

    /** Same analyzer frame as {@link CompilerTypeHintAnalysisTest#analyzeTopLevelExpression(String)}. */
    private static Compiler.Expr analyzeExpression(String src) {
        Object form = readOne(src);
        Object expanded = Compiler.macroexpand(form);
        Var warnOnReflection = Var.find(Symbol.intern("clojure.core", "*warn-on-reflection*"));
        Var.pushThreadBindings(
                RT.mapUniqueKeys(
                        Compiler.SOURCE_PATH,
                        "WriterOverloadReflectionTest.clj",
                        Compiler.SOURCE,
                        "WriterOverloadReflectionTest",
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
                        1,
                        Compiler.COLUMN_BEFORE,
                        0,
                        Compiler.LINE_AFTER,
                        1,
                        Compiler.COLUMN_AFTER,
                        0,
                        Compiler.LINE,
                        1,
                        Compiler.COLUMN,
                        0,
                        Compiler.CONSTANTS,
                        PersistentVector.EMPTY,
                        Compiler.CONSTANT_IDS,
                        new java.util.IdentityHashMap<>(),
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
                        Boolean.TRUE,
                        RT.DATA_READERS,
                        RT.DATA_READERS.deref(),
                        Compiler.LOADER,
                        RT.makeClassLoader()));
        ClassLoader oldCl = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader((ClassLoader) Compiler.LOADER.deref());
            return Compiler.analyze(Compiler.C.EXPRESSION, expanded);
        } finally {
            Thread.currentThread().setContextClassLoader(oldCl);
            Var.popThreadBindings();
        }
    }

    private static Compiler.InstanceMethodExpr findInstanceMethodNamed(Compiler.Expr e, String methodName) {
        if (e == null) {
            return null;
        }
        if (e instanceof Compiler.InstanceMethodExpr ime && methodName.equals(ime.methodName)) {
            return ime;
        }
        if (e instanceof Compiler.BodyExpr be) {
            for (Object o : be.exprs()) {
                Compiler.InstanceMethodExpr r = findInstanceMethodNamed((Compiler.Expr) o, methodName);
                if (r != null) {
                    return r;
                }
            }
        }
        if (e instanceof Compiler.LetExpr le) {
            return findInstanceMethodNamed(le.body, methodName);
        }
        if (e instanceof Compiler.FnExpr fe) {
            for (ISeq s = RT.seq(fe.methods()); s != null; s = s.next()) {
                Compiler.ObjMethod om = (Compiler.ObjMethod) s.first();
                Compiler.InstanceMethodExpr r = findInstanceMethodNamed(om.body(), methodName);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /**
     * Same shape as {@code clojure.instant/print-calendar}: {@code ^String} on the {@code format} local makes
     * {@link Compiler.InstanceMethodExpr#method} non-null for {@code Writer.write(String,int,int)}.
     */
    @Test
    public void printCalendarShapedWriterWriteResolvesWithStringHintOnCalstr() {
        Compiler.Expr root = analyzeExpression(
                "(fn [^java.io.Writer w]"
                        + "  (let [c (java.util.Calendar/getInstance)"
                        + "        ^String calstr (format \"%1$tFT%1$tT.%1$tL%1$tz\" c)"
                        + "        offset-minutes (- (.length calstr) 2)]"
                        + "    (.write w calstr 0 offset-minutes)))");
        Compiler.InstanceMethodExpr write = findInstanceMethodNamed(root, "write");
        assertNotNull(write);
        assertNotNull(
                "expected direct java.lang.reflect.Method when calstr is hinted ^String (Writer.write(String,int,int))",
                write.method);
    }
}
