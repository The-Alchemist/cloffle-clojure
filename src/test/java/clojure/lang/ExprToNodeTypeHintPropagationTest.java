package clojure.lang;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import net.javacrumbs.cloffle.ast.ExprToNode;
import net.javacrumbs.cloffle.nodes.ClojureNode;
import net.javacrumbs.cloffle.nodes.FnMethodNode;
import net.javacrumbs.cloffle.nodes.FnNode;
import net.javacrumbs.cloffle.nodes.InstanceCallNode;
import net.javacrumbs.cloffle.nodes.LetNode;
import net.javacrumbs.cloffle.nodes.NewNode;
import net.javacrumbs.cloffle.nodes.staticcall.GenericStaticCallNode;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies type-hint propagation across the full pipeline:
 * reader/analyzer AST -> ExprToNode conversion -> Truffle nodes/frame slots.
 */
public class ExprToNodeTypeHintPropagationTest {
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

    private static Compiler.Expr analyzeTopLevelExpression(String src) {
        Object form = readOne(src);
        Object expanded = Compiler.macroexpand(form);
        Var warnOnReflection = Var.find(Symbol.intern("clojure.core", "*warn-on-reflection*"));
        IPersistentMap threadBindings = RT.mapUniqueKeys(
                Compiler.SOURCE_PATH, "NO_SOURCE_PATH",
                Compiler.SOURCE, "ExprToNodeTypeHintPropagationTest",
                Compiler.METHOD, null,
                Compiler.LOCAL_ENV, null,
                Compiler.LOOP_LOCALS, null,
                Compiler.NEXT_LOCAL_NUM, 0,
                RT.READEVAL, RT.T,
                RT.CURRENT_NS, RT.CURRENT_NS.deref(),
                Compiler.LINE_BEFORE, 1,
                Compiler.COLUMN_BEFORE, 0,
                Compiler.LINE_AFTER, 1,
                Compiler.COLUMN_AFTER, 0,
                Compiler.LINE, 1,
                Compiler.COLUMN, 0,
                Compiler.CONSTANTS, PersistentVector.EMPTY,
                Compiler.CONSTANT_IDS, new IdentityHashMap<>(),
                Compiler.KEYWORD_CALLSITES, PersistentVector.EMPTY,
                Compiler.PROTOCOL_CALLSITES, PersistentVector.EMPTY,
                Compiler.KEYWORDS, PersistentHashMap.EMPTY,
                Compiler.VARS, PersistentHashMap.EMPTY,
                RT.UNCHECKED_MATH, RT.UNCHECKED_MATH.deref(),
                warnOnReflection, warnOnReflection.deref(),
                RT.DATA_READERS, RT.DATA_READERS.deref(),
                Compiler.LOADER, RT.makeClassLoader());

        ClassLoader oldCl = Thread.currentThread().getContextClassLoader();
        Var.pushThreadBindings(threadBindings);
        try {
            Thread.currentThread().setContextClassLoader((ClassLoader) Compiler.LOADER.deref());
            return Compiler.analyze(Compiler.C.EXPRESSION, expanded);
        } finally {
            Thread.currentThread().setContextClassLoader(oldCl);
            Var.popThreadBindings();
        }
    }

    private static FrameSlotKind slotKindForLocal(FrameDescriptor fd, String localName) {
        for (int i = 0; i < fd.getNumberOfSlots(); i++) {
            Object slotName = fd.getSlotName(i);
            if (slotName instanceof Compiler.LocalBinding lb
                    && localName.equals(lb.sym.getName())) {
                return fd.getSlotKind(i);
            }
        }
        throw new AssertionError("No local binding slot found for '" + localName + "'");
    }

    private static Method resolvedMethod(InstanceCallNode node) throws Exception {
        Field field = InstanceCallNode.class.getDeclaredField("resolvedMethod");
        field.setAccessible(true);
        return (Method) field.get(node);
    }

    private static Method resolvedMethod(GenericStaticCallNode node) throws Exception {
        Field field = GenericStaticCallNode.class.getDeclaredField("resolvedMethod");
        field.setAccessible(true);
        return (Method) field.get(node);
    }

    private static Constructor<?> resolvedCtor(NewNode node) throws Exception {
        Field field = NewNode.class.getDeclaredField("resolvedCtor");
        field.setAccessible(true);
        return (Constructor<?>) field.get(node);
    }

    private static ClojureNode letBody(LetNode node) throws Exception {
        Field field = LetNode.class.getDeclaredField("body");
        field.setAccessible(true);
        return (ClojureNode) field.get(node);
    }

    private static boolean messageInCauseChain(Throwable t, String text) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null && c.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void fnArgumentPrimitiveHintsSetPrimitiveFrameSlotKinds() {
        Compiler.Expr expr = analyzeTopLevelExpression("(fn [^long a ^double b] (+ a b))");
        ExprToNode converter = new ExprToNode(null, null);
        ClojureNode node = converter.convert(expr);
        FrameDescriptor fd = converter.buildFrameDescriptor();

        assertThat(node).isInstanceOf(FnNode.class);
        assertThat(slotKindForLocal(fd, "a")).isEqualTo(FrameSlotKind.Long);
        assertThat(slotKindForLocal(fd, "b")).isEqualTo(FrameSlotKind.Double);
    }

    @Test
    public void letLocalInitializedFromPrimitiveArgGetsPrimitiveSlotKind() {
        Compiler.Expr expr = analyzeTopLevelExpression("(fn [^long y] (let [x y] x))");
        ExprToNode converter = new ExprToNode(null, null);
        converter.convert(expr);
        FrameDescriptor fd = converter.buildFrameDescriptor();

        assertThat(slotKindForLocal(fd, "y")).isEqualTo(FrameSlotKind.Long);
        assertThat(slotKindForLocal(fd, "x")).isEqualTo(FrameSlotKind.Long);
    }

    @Test
    public void classHintOnFnArgPropagatesToResolvedInteropMethodOnNode() throws Exception {
        Compiler.Expr expr = analyzeTopLevelExpression("(fn [^String s] (.substring s 1))");
        ExprToNode converter = new ExprToNode(null, null);
        FnNode fnNode = (FnNode) converter.convert(expr);

        FnMethodNode[] methods = fnNode.getMethods();
        assertThat(methods).hasSize(1);
        ClojureNode body = methods[0].getBody();
        assertThat(body).isInstanceOf(InstanceCallNode.class);

        Method method = resolvedMethod((InstanceCallNode) body);
        assertThat(method).isNotNull();
        assertThat(method.getDeclaringClass()).isEqualTo(String.class);
        assertThat(method.getName()).isEqualTo("substring");
    }

    @Test
    public void interopMethodResolutionUsesHintedFastPathButUnhintedFallsBack() throws Exception {
        ExprToNode hintedConverter = new ExprToNode(null, null);
        FnNode hintedFn = (FnNode) hintedConverter.convert(
                analyzeTopLevelExpression("(fn [^String s] (.substring s 1))"));
        Method hintedMethod = resolvedMethod((InstanceCallNode) hintedFn.getMethods()[0].getBody());
        assertThat(hintedMethod).isNotNull();
        assertThat(hintedMethod.getDeclaringClass()).isEqualTo(String.class);
        assertThat(hintedMethod.getName()).isEqualTo("substring");

        ExprToNode unhintedConverter = new ExprToNode(null, null);
        FnNode unhintedFn = (FnNode) unhintedConverter.convert(
                analyzeTopLevelExpression("(fn [s] (.substring s 1))"));
        Method unhintedMethod = resolvedMethod((InstanceCallNode) unhintedFn.getMethods()[0].getBody());
        assertThat(unhintedMethod).isNull();
    }

    @Test
    public void longHintedArgGetsLongSlotButUnhintedArgIsObjectSlot() {
        ExprToNode hintedConverter = new ExprToNode(null, null);
        hintedConverter.convert(analyzeTopLevelExpression("(fn [^long p] p)"));
        FrameDescriptor hintedFd = hintedConverter.buildFrameDescriptor();
        assertThat(slotKindForLocal(hintedFd, "p")).isEqualTo(FrameSlotKind.Long);

        ExprToNode unhintedConverter = new ExprToNode(null, null);
        unhintedConverter.convert(analyzeTopLevelExpression("(fn [p] p)"));
        FrameDescriptor unhintedFd = unhintedConverter.buildFrameDescriptor();
        assertThat(slotKindForLocal(unhintedFd, "p")).isEqualTo(FrameSlotKind.Object);
    }

    @Test
    public void nonPrimitiveHintedLetLocalDoesNotForcePrimitiveSlotKind() {
        ExprToNode converter = new ExprToNode(null, null);
        converter.convert(analyzeTopLevelExpression("(fn [y] (let [^double x y] x))"));
        FrameDescriptor fd = converter.buildFrameDescriptor();

        // Analyzer keeps primitiveType null for this pattern; slot should stay Object.
        assertThat(slotKindForLocal(fd, "x")).isEqualTo(FrameSlotKind.Object);
    }

    @Test
    public void classHintOnLetLocalAlsoResolvesInteropMethod() throws Exception {
        ExprToNode converter = new ExprToNode(null, null);
        FnNode fnNode = (FnNode) converter.convert(
                analyzeTopLevelExpression("(fn [x] (let [^String s x] (.substring s 1)))"));

        ClojureNode body = fnNode.getMethods()[0].getBody();
        assertThat(body).isInstanceOf(LetNode.class);
        ClojureNode inner = letBody((LetNode) body);
        assertThat(inner).isInstanceOf(InstanceCallNode.class);
        Method method = resolvedMethod((InstanceCallNode) inner);
        assertThat(method).isNotNull();
        assertThat(method.getDeclaringClass()).isEqualTo(String.class);
        assertThat(method.getName()).isEqualTo("substring");
    }

    @Test
    public void multiArityFunctionKeepsDistinctPrimitiveKindsAcrossDifferentArities() {
        ExprToNode converter = new ExprToNode(null, null);
        converter.convert(analyzeTopLevelExpression("(fn ([^long a] a) ([b c] b))"));
        FrameDescriptor fd = converter.buildFrameDescriptor();

        assertThat(slotKindForLocal(fd, "a")).isEqualTo(FrameSlotKind.Long);
        assertThat(slotKindForLocal(fd, "b")).isEqualTo(FrameSlotKind.Object);
        assertThat(slotKindForLocal(fd, "c")).isEqualTo(FrameSlotKind.Object);
    }

    @Test
    public void analyzerRejectsVariadicFunctionWithPrimitiveHintedParam() {
        try {
            analyzeTopLevelExpression("(fn [^long x & more] (+ x (count more)))");
        } catch (Throwable t) {
            assertThat(t).isInstanceOf(Compiler.CompilerException.class);
            assertThat(messageInCauseChain(t, "fns taking primitives cannot be variadic")).isTrue();
            return;
        }
        throw new AssertionError("Expected CompilerException for variadic fn with primitive hint");
    }

    @Test
    public void mapStyleTagMetadataOnParameterBehavesLikeCaretHint() {
        ExprToNode converter = new ExprToNode(null, null);
        converter.convert(analyzeTopLevelExpression("(fn [^{:tag long} x] x)"));
        FrameDescriptor fd = converter.buildFrameDescriptor();

        assertThat(slotKindForLocal(fd, "x")).isEqualTo(FrameSlotKind.Long);
    }

    @Test
    public void typeHintAppliedAtMacroUseSiteStillResolvesInteropMethod() throws Exception {
        ExprToNode converter = new ExprToNode(null, null);
        FnNode fnNode = (FnNode) converter.convert(
                analyzeTopLevelExpression("(fn [s] (-> ^String s (.substring 1)))"));

        ClojureNode body = fnNode.getMethods()[0].getBody();
        assertThat(body).isInstanceOf(InstanceCallNode.class);
        Method method = resolvedMethod((InstanceCallNode) body);
        assertThat(method).isNotNull();
        assertThat(method.getDeclaringClass()).isEqualTo(String.class);
        assertThat(method.getName()).isEqualTo("substring");
    }

    @Test
    public void staticOverloadResolutionUsesHintedFastPathButUnhintedFallsBack() throws Exception {
        ExprToNode hintedConverter = new ExprToNode(null, null);
        FnNode hintedFn = (FnNode) hintedConverter.convert(
                analyzeTopLevelExpression("(fn [^long x] (Math/abs x))"));
        ClojureNode hintedBody = hintedFn.getMethods()[0].getBody();
        assertThat(hintedBody).isInstanceOf(GenericStaticCallNode.class);
        Method hintedMethod = resolvedMethod((GenericStaticCallNode) hintedBody);
        assertThat(hintedMethod).isNotNull();
        assertThat(hintedMethod.getName()).isEqualTo("abs");
        assertThat(hintedMethod.getParameterTypes()).containsExactly(long.class);

        ExprToNode unhintedConverter = new ExprToNode(null, null);
        FnNode unhintedFn = (FnNode) unhintedConverter.convert(
                analyzeTopLevelExpression("(fn [x] (Math/abs x))"));
        ClojureNode unhintedBody = unhintedFn.getMethods()[0].getBody();
        assertThat(unhintedBody).isInstanceOf(GenericStaticCallNode.class);
        Method unhintedMethod = resolvedMethod((GenericStaticCallNode) unhintedBody);
        assertThat(unhintedMethod).isNull();
    }

    @Test
    public void constructorResolutionUsesHintedFastPathButUnhintedFallsBack() throws Exception {
        ExprToNode hintedConverter = new ExprToNode(null, null);
        FnNode hintedFn = (FnNode) hintedConverter.convert(
                analyzeTopLevelExpression("(fn [^String s] (java.math.BigInteger. s))"));
        ClojureNode hintedBody = hintedFn.getMethods()[0].getBody();
        assertThat(hintedBody).isInstanceOf(NewNode.class);
        Constructor<?> hintedCtor = resolvedCtor((NewNode) hintedBody);
        assertThat(hintedCtor).isNotNull();
        assertThat(hintedCtor.getParameterTypes()).containsExactly(String.class);

        ExprToNode unhintedConverter = new ExprToNode(null, null);
        FnNode unhintedFn = (FnNode) unhintedConverter.convert(
                analyzeTopLevelExpression("(fn [s] (java.math.BigInteger. s))"));
        ClojureNode unhintedBody = unhintedFn.getMethods()[0].getBody();
        assertThat(unhintedBody).isInstanceOf(NewNode.class);
        Constructor<?> unhintedCtor = resolvedCtor((NewNode) unhintedBody);
        assertThat(unhintedCtor).isNull();
    }

    @Test
    public void booleanHintedParamGetsBooleanSlotKind() {
        ExprToNode converter = new ExprToNode(null, null);
        converter.convert(analyzeTopLevelExpression("(fn [^boolean p] p)"));
        FrameDescriptor fd = converter.buildFrameDescriptor();

        assertThat(slotKindForLocal(fd, "p")).isEqualTo(FrameSlotKind.Boolean);
    }

    @Test
    public void intHintedParamGetsLongSlotKind() {
        ExprToNode converter = new ExprToNode(null, null);
        converter.convert(analyzeTopLevelExpression("(fn [^int p] p)"));
        FrameDescriptor fd = converter.buildFrameDescriptor();

        // Truffle type system specializes integers through long slots.
        assertThat(slotKindForLocal(fd, "p")).isEqualTo(FrameSlotKind.Long);
    }

    @Test
    public void floatHintedParamGetsDoubleSlotKind() {
        ExprToNode converter = new ExprToNode(null, null);
        converter.convert(analyzeTopLevelExpression("(fn [^float p] p)"));
        FrameDescriptor fd = converter.buildFrameDescriptor();

        // Truffle type system specializes float through double slots.
        assertThat(slotKindForLocal(fd, "p")).isEqualTo(FrameSlotKind.Double);
    }
}
