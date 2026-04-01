package clojure.lang;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.StringReader;
import java.util.IdentityHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Asserts that the <strong>forked</strong> {@link Compiler} in this repo (the same one Cloffle loads)
 * parses {@code ^double} / {@code ^long} as the JVM compiler does: reader metadata,
 * {@link Compiler#primClass}, and analyzer {@link Compiler.LocalBinding} for {@code fn*} parameters
 * and {@code let*} locals.
 * <p>
 * Lives in {@code clojure.lang} so tests can use {@link Compiler.Expr}, {@link RT#TAG_KEY}, and
 * {@link Symbol#name} (package-private). This is not stock Maven Clojure.
 * <p>
 * {@code :inline} + {@code ^double} regressions: see {@code ExprToBytecode} slot scoping and
 * {@code BindingNode} primitive coercion in Cloffle notes.
 */
public class CompilerTypeHintAnalysisTest {

    private static final Object READ_EOF = new Object();

    @BeforeClass
    public static void initRt() {
        RT.init();
        RT.CURRENT_NS.bindRoot(Namespace.findOrCreate(Symbol.intern("user")));
    }

    private static Object readOne(String src) {
        LineNumberingPushbackReader pbr = new LineNumberingPushbackReader(new StringReader(src));
        Object opts = RT.map(RT.READEVAL, RT.T);
        return LispReader.read(pbr, false, READ_EOF, false, opts);
    }

    /** {@link Compiler.FnMethod#reqParms} uses {@link PersistentVector#cons}, which appends — same order as source. */
    private static Compiler.LocalBinding reqParamInSourceOrder(Compiler.FnMethod fm, int i) {
        IPersistentVector rp = fm.reqParms();
        return (Compiler.LocalBinding) rp.nth(i);
    }

    private static Compiler.Expr analyzeTopLevelExpression(String src) {
        Object form = readOne(src);
        Object expanded = Compiler.macroexpand(form);
        Var warnOnReflection = Var.find(Symbol.intern("clojure.core", "*warn-on-reflection*"));
        Var.pushThreadBindings(
                RT.mapUniqueKeys(
                        Compiler.SOURCE_PATH,
                        "NO_SOURCE_PATH",
                        Compiler.SOURCE,
                        "CompilerTypeHintAnalysisTest",
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
        ClassLoader oldCl = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader((ClassLoader) Compiler.LOADER.deref());
            return Compiler.analyze(Compiler.C.EXPRESSION, expanded);
        } finally {
            Thread.currentThread().setContextClassLoader(oldCl);
            Var.popThreadBindings();
        }
    }

    @Test
    public void readerAttachesTagMetadataToHintedParameterSymbol() {
        IPersistentVector params = (IPersistentVector) readOne("[ ^double x ]");
        Symbol x = (Symbol) params.nth(0);
        assertThat(RT.get(RT.meta(x), RT.TAG_KEY)).isEqualTo(Symbol.intern(null, "double"));
    }

    @Test
    public void readerAttachesLongTagToHintedParameterSymbol() {
        IPersistentVector params = (IPersistentVector) readOne("[^long a]");
        Symbol a = (Symbol) params.nth(0);
        assertThat(RT.get(RT.meta(a), RT.TAG_KEY)).isEqualTo(Symbol.intern(null, "long"));
    }

    @Test
    public void primClassRecognizesPrimitiveNameSymbols() {
        assertThat(Compiler.primClass(Symbol.intern(null, "double"))).isEqualTo(double.class);
        assertThat(Compiler.primClass(Symbol.intern(null, "long"))).isEqualTo(long.class);
        assertThat(Compiler.primClass((Symbol) null)).isNull();
    }

    @Test
    public void analyzeFnPrimitiveDoubleParameterUsesMethodParamExprPrimitive() {
        Compiler.FnExpr fn = (Compiler.FnExpr) analyzeTopLevelExpression("(fn [^double x] x)");
        Compiler.FnMethod m = (Compiler.FnMethod) RT.first(fn.methods());
        Compiler.LocalBinding x = reqParamInSourceOrder(m, 0);
        assertThat(x.sym.name).isEqualTo("x");
        assertThat(x.isArg).isTrue();
        assertNull(x.tag);
        assertThat(x.init).isInstanceOf(Compiler.MethodParamExpr.class);
        assertThat(x.getPrimitiveType()).isEqualTo(double.class);
    }

    @Test
    public void analyzeFnPrimitiveLongAndDoubleParametersPreserveSourceOrder() {
        Compiler.FnExpr fn = (Compiler.FnExpr) analyzeTopLevelExpression("(fn [^long a ^double b] (+ a b))");
        Compiler.FnMethod m = (Compiler.FnMethod) RT.first(fn.methods());
        assertThat(reqParamInSourceOrder(m, 0).getPrimitiveType()).isEqualTo(long.class);
        assertThat(reqParamInSourceOrder(m, 0).sym.name).isEqualTo("a");
        assertThat(reqParamInSourceOrder(m, 1).getPrimitiveType()).isEqualTo(double.class);
        assertThat(reqParamInSourceOrder(m, 1).sym.name).isEqualTo("b");
    }

    @Test
    public void analyzeFnPrimitiveIntFloatBooleanParameters() {
        Compiler.FnExpr fn = (Compiler.FnExpr) analyzeTopLevelExpression("(fn [^int i ^float f ^boolean p] (if p (+ i f) 0.0))");
        Compiler.FnMethod m = (Compiler.FnMethod) RT.first(fn.methods());
        assertThat(reqParamInSourceOrder(m, 0).getPrimitiveType()).isEqualTo(int.class);
        assertThat(reqParamInSourceOrder(m, 1).getPrimitiveType()).isEqualTo(float.class);
        assertThat(reqParamInSourceOrder(m, 2).getPrimitiveType()).isEqualTo(boolean.class);
    }

    @Test
    public void analyzeLetLocalWithDoubleTagKeepsTagWhenInitIsNotPrimitiveExpr() {
        Compiler.FnExpr fn = (Compiler.FnExpr) analyzeTopLevelExpression("(fn [y] (let [^double x y] x))");
        Compiler.FnMethod fm = (Compiler.FnMethod) RT.first(fn.methods());
        Compiler.BodyExpr body = (Compiler.BodyExpr) fm.body();
        Compiler.LetExpr let = (Compiler.LetExpr) body.exprs().nth(body.exprs().count() - 1);
        Compiler.BindingInit bi = (Compiler.BindingInit) let.bindingInits.nth(0);
        Compiler.LocalBinding lb = bi.binding();
        assertThat(lb.sym.name).isEqualTo("x");
        assertEquals(Symbol.intern(null, "double"), lb.tag);
        assertThat(lb.getPrimitiveType()).isNull();
    }

    /**
     * Qualified core vars: {@code analyzeSeq} runs {@code :inline} with {@code nil} as the unevaluated
     * arg form. Must not throw (regression: NPE from primitive slot / {@code doubleCast} during expansion).
     */
    @Test
    public void analyzeQualifiedNaNQuestionWithNilArgCompletes() {
        Compiler.Expr expr = analyzeTopLevelExpression("(clojure.core/NaN? nil)");
        assertThat(expr).isNotNull();
    }

    @Test
    public void analyzeQualifiedInfiniteQuestionWithNilArgCompletes() {
        Compiler.Expr expr = analyzeTopLevelExpression("(clojure.core/infinite? nil)");
        assertThat(expr).isNotNull();
    }
}
