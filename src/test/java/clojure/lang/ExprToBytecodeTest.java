package clojure.lang;

import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.serialization.SerializationUtils;
import com.oracle.truffle.api.source.Source;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeDeserializer;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNodeGen;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeSerializer;
import net.javacrumbs.cloffle.bytecode.ExprToBytecode;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutputStream;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.regex.Pattern;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link ExprToBytecode} / {@link CloffleBytecodeRootNode} tests with <b>no</b> {@code clojure.core}
 * load and <b>no</b> Cloffle AST execution ({@link net.javacrumbs.cloffle.compiler.CloffleCompiler}).
 * <p>
 * Forms are limited to what {@link Compiler#macroexpand} and {@link Compiler#analyze} can handle
 * without core-provided macros or vars — e.g. literals, {@code if}, {@code do}, {@code quote}, {@code try},
 * {@code fn*} (not the {@code fn} macro), {@code let*}, {@code def}, {@code var}, Java interop, and
 * collection literals whose elements need no core.
 * <p>
 * Package {@code clojure.lang} for access to {@link Compiler#macroexpand} and {@link Compiler.Expr}.
 * {@code loop}/{@code recur} omitted: backward branches unsupported (see {@code CLOFFLE_TRUFFLE_BYTECODE.md}).
 */
public class ExprToBytecodeTest {

    private static Object evalBytecode(String code) {
        try {
            Object form = LispReader.read(
                    new LineNumberingPushbackReader(new StringReader(code)), false, null, false, null);
            Object expanded = Compiler.macroexpand(form);
            Compiler.Expr expr = Compiler.analyze(Compiler.C.EVAL, expanded);
            Source source = Source.newBuilder("cloffle", code, "bytecode-test.clj").build();
            ExprToBytecode converter = new ExprToBytecode(null, source);
            BytecodeRootNodes<CloffleBytecodeRootNode> nodes = converter.convertRoot(expr, "testRoot");
            CloffleBytecodeRootNode root = nodes.getNode(0);
            return root.getCallTarget().call();
        } catch (Exception e) {
            throw new RuntimeException("bytecode eval failed: " + code, e);
        }
    }

    private static CloffleBytecodeRootNode compileRoot(String code) throws Exception {
        Object form = LispReader.read(
                new LineNumberingPushbackReader(new StringReader(code)), false, null, false, null);
        Object expanded = Compiler.macroexpand(form);
        Compiler.Expr expr = Compiler.analyze(Compiler.C.EVAL, expanded);
        Source source = Source.newBuilder("cloffle", code, "bytecode-test.clj").build();
        ExprToBytecode converter = new ExprToBytecode(null, source);
        return converter.convertRoot(expr, "namedRoot").getNode(0);
    }

    @Test
    public void nilConstant() {
        assertNull(evalBytecode("nil"));
    }

    @Test
    public void longConstant() {
        assertEquals(42L, evalBytecode("42"));
    }

    @Test
    public void keywordConstant() {
        Object k = evalBytecode(":hello/bytecode");
        assertTrue(k instanceof Keyword);
        assertEquals("hello", ((Keyword) k).getNamespace());
        assertEquals("bytecode", ((Keyword) k).getName());
    }

    @Test
    public void stringConstant() {
        assertEquals("truffle", evalBytecode("\"truffle\""));
    }

    @Test
    public void booleanConstants() {
        assertSame(RT.T, evalBytecode("true"));
        assertSame(RT.F, evalBytecode("false"));
    }

    @Test
    public void emptyVectorConstant() {
        Object v = evalBytecode("[]");
        assertTrue(v instanceof IPersistentVector);
        assertTrue(((IPersistentVector) v).count() == 0);
    }

    @Test
    public void ifWithTruthiness() {
        assertEquals(1L, evalBytecode("(if true 1 2)"));
        assertEquals(2L, evalBytecode("(if false 1 2)"));
        assertEquals(1L, evalBytecode("(if :x 1 2)"));
        assertEquals(2L, evalBytecode("(if nil 1 2)"));
    }

    @Test
    public void nestedIf() {
        assertEquals(2L, evalBytecode("(if true (if false 1 2) 3)"));
        assertEquals(3L, evalBytecode("(if false (if true 1 2) 3)"));
    }

    @Test
    public void doReturnsLastValue() {
        assertEquals(3L, evalBytecode("(do 1 2 3)"));
        assertNull(evalBytecode("(do nil)"));
    }

    @Test
    public void doubleConstant() {
        assertEquals(3.14, (Double) evalBytecode("3.14"), 0.0);
    }

    @Test
    public void characterConstant() {
        assertEquals(Character.valueOf('z'), evalBytecode("\\z"));
    }

    @Test
    public void quotedList() {
        Object x = evalBytecode("(quote (1 2 3))");
        assertTrue(x instanceof ISeq);
        ISeq s = (ISeq) x;
        assertEquals(1L, s.first());
        assertEquals(2L, RT.second(s));
        assertEquals(3L, RT.third(s));
    }

    @Test
    public void quotedSymbol() {
        Object x = evalBytecode("(quote abcd)");
        assertTrue(x instanceof Symbol);
        assertEquals("abcd", ((Symbol) x).getName());
    }

    @Test
    public void ratioConstant() {
        Object r = evalBytecode("1/2");
        assertTrue(r instanceof Ratio);
        assertEquals(BigInteger.ONE, ((Ratio) r).numerator);
        assertEquals(BigInteger.TWO, ((Ratio) r).denominator);
    }

    @Test
    public void emptyMapAndSetLiterals() {
        Object m = evalBytecode("{}");
        assertTrue(m instanceof IPersistentMap);
        assertEquals(0, ((IPersistentMap) m).count());
        Object st = evalBytecode("#{}");
        assertTrue(st instanceof IPersistentSet);
        assertEquals(0, ((IPersistentSet) st).count());
    }

    @Test
    public void setLiteralWithoutCoreFns() {
        Object st = evalBytecode("#{1 2 3}");
        assertTrue(st instanceof IPersistentSet);
        IPersistentSet set = (IPersistentSet) st;
        assertEquals(3, set.count());
        assertTrue(set.contains(1L));
        assertTrue(set.contains(2L));
        assertTrue(set.contains(3L));
    }

    @Test
    public void vectorLiteralWithoutCoreFns() {
        Object v = evalBytecode("[1 2 3]");
        assertTrue(v instanceof IPersistentVector);
        IPersistentVector vec = (IPersistentVector) v;
        assertEquals(3, vec.count());
        assertEquals(1L, vec.nth(0));
        assertEquals(2L, vec.nth(1));
        assertEquals(3L, vec.nth(2));
    }

    @Test
    public void mapLiteralWithoutCoreFns() {
        Object m = evalBytecode("{:a 1 :b 2}");
        assertTrue(m instanceof IPersistentMap);
        IPersistentMap map = (IPersistentMap) m;
        assertEquals(2, map.count());
        assertEquals(1L, map.valAt(Keyword.intern("a")));
        assertEquals(2L, map.valAt(Keyword.intern("b")));
    }

    @Test
    public void tryCatchReturnsTryBodyWhenNoThrow() {
        assertEquals(7L, evalBytecode("(try 7 (catch Throwable t 0))"));
    }

    @Test
    public void tryFinallyRunsAndReturnsBody() {
        assertEquals(1L, evalBytecode("(try 1 (finally nil))"));
    }

    @Test
    public void fnStarZeroArityInvoke() {
        // `fn` is a core macro; `fn*` is the special form (no clojure.core).
        assertEquals(42L, evalBytecode("((fn* ([] 42)))"));
    }

    @Test
    public void javaStaticMethodCall() {
        assertEquals(99L, evalBytecode("(Long/valueOf 99)"));
    }

    @Test
    public void javaInstanceMethodCall() {
        // Reflector returns int boxed as Integer for .length
        assertEquals(Integer.valueOf(3), evalBytecode("(.length \"abc\")"));
    }

    @Test
    public void javaNewAndInstanceOf() {
        Object s = evalBytecode("(new String \"hi\")");
        assertTrue(s instanceof String);
        assertEquals("hi", s);
        assertSame(RT.T, evalBytecode("(instance? String \"a\")"));
        assertSame(RT.F, evalBytecode("(instance? String 1)"));
    }

    @Test
    public void letStarBindsLocals() {
        // `let` is a core macro; `let*` is the special form.
        assertEquals(1L, evalBytecode("(let* [a 1] a)"));
        // Later bindings see earlier locals: b uses a's value (both 1).
        assertEquals(1L, evalBytecode("(let* [a 1 b a] b)"));
        assertEquals(2L, evalBytecode("(let* [a 1 b 2] b)"));
    }

    @Test
    public void fnStarUnaryInvoke() {
        assertEquals(99L, evalBytecode("((fn* ([x] x)) 99)"));
    }

    @Test
    public void multiArityFnWithoutOuterCallReturnsIFn() {
        Object f = evalBytecode("(fn* ([] 10) ([x] x) ([x y] y))");
        assertTrue("multi-arity fn* should compile to IFn, got " + (f == null ? "null" : f.getClass()),
                f instanceof IFn);
    }

    @Test
    public void multiArityFnExprHasThreeMethods() throws Exception {
        String code = "((fn* ([] 10) ([x] x) ([x y] y)))";
        Object form = LispReader.read(
                new LineNumberingPushbackReader(new StringReader(code)), false, null, false, null);
        Compiler.Expr expr = Compiler.analyze(Compiler.C.EVAL, Compiler.macroexpand(form));
        assertTrue(expr instanceof Compiler.InvokeExpr);
        Compiler.Expr fexpr = ((Compiler.InvokeExpr) expr).fexpr;
        assertTrue(fexpr instanceof Compiler.FnExpr);
        assertEquals(3, ((Compiler.FnExpr) fexpr).methods().count());
    }

    @Test
    public void fnStarMultiArityDirectInvoke() {
        String f = "(fn* ([] 10) ([x] x) ([x y] y))";
        // f is already wrapped in parens as a fn* form; only one outer paren for invoke — not "((" + f + "))"
        // which would read as (((fn* ...))) and analyze to a different invoke shape.
        assertEquals(10L, evalBytecode("(" + f + ")"));
        assertEquals(5L, evalBytecode("(" + f + " 5)"));
        assertEquals(2L, evalBytecode("(" + f + " 1 2)"));
    }

    @Test
    public void fnStarMultiArityDispatchViaLetStarAndSymbolInvoke() {
        String f = "(fn* ([] 10) ([x] x) ([x y] y))";
        assertEquals(10L, evalBytecode("(let* [f " + f + "] (f))"));
        assertEquals(5L, evalBytecode("(let* [f " + f + "] (f 5))"));
        assertEquals(2L, evalBytecode("(let* [f " + f + "] (f 1 2))"));
    }

    @Test
    public void fnStarRestArgs() {
        Object seq = evalBytecode("((fn* ([x & rest] rest)) 1 2 3)");
        assertTrue(seq instanceof ISeq);
        assertEquals(2L, ((ISeq) seq).first());
        assertEquals(3L, RT.second((ISeq) seq));
    }

    @Test
    public void letStarClosureCapturesLocal() {
        assertEquals(7L, evalBytecode("(let* [n 7] ((fn* [] n)))"));
    }

    @Test
    public void tryCatchFinallyWhenNoThrow() {
        assertEquals(5L, evalBytecode("(try 5 (catch Throwable t 0) (finally nil))"));
    }

    @Test
    public void bigintLiteral() {
        Object n = evalBytecode("10000000000000000000N");
        assertTrue(n instanceof BigInt);
        assertEquals(new BigInteger("10000000000000000000"), ((BigInt) n).toBigInteger());
    }

    @Test
    public void regexLiteral() {
        Object p = evalBytecode("#\"a+\"");
        assertTrue(p instanceof Pattern);
        assertTrue(((Pattern) p).matcher("aaa").matches());
    }

    @Test
    public void throwCaughtInTry() {
        Object v = evalBytecode(
                "(try (throw (new Exception \"boom\")) (catch Exception e :caught))");
        assertEquals(Keyword.intern("caught"), v);
    }

    @Test
    public void javaStaticField() {
        assertEquals(Long.MAX_VALUE, evalBytecode("Long/MAX_VALUE"));
    }

    @Test
    public void defBindsRootAndSymbolReadsVar() {
        String sym = "expr_to_bytecode__def_test_" + System.nanoTime();
        String code = "(do (def " + sym + " 77) " + sym + ")";
        assertEquals(77L, evalBytecode(code));
    }

    @Test
    public void theVarSpecialForm() {
        String sym = "expr_to_bytecode__var_test_" + System.nanoTime();
        String defCode = "(def " + sym + " 88)";
        evalBytecode(defCode);
        Var v = (Var) evalBytecode("(var " + sym + ")");
        assertEquals(88L, v.get());
    }

    @Test
    public void vectorWithMetadata() {
        Object v = evalBytecode("^{:x 1} [1 2]");
        assertTrue(v instanceof IPersistentVector);
        IPersistentVector vec = (IPersistentVector) v;
        assertEquals(2, vec.count());
        Object meta = RT.meta(vec);
        assertNotNull(meta);
        assertEquals(1L, RT.get(meta, Keyword.intern("x")));
    }

    @Test
    public void serializationRoundTripPreservesExecution() throws Exception {
        String code = "42";
        Object form = LispReader.read(
                new LineNumberingPushbackReader(new StringReader(code)), false, null, false, null);
        Object expanded = Compiler.macroexpand(form);
        Compiler.Expr expr = Compiler.analyze(Compiler.C.EVAL, expanded);
        Source source = Source.newBuilder("cloffle", code, "bytecode-test.clj").build();
        ExprToBytecode converter = new ExprToBytecode(null, source);
        BytecodeRootNodes<CloffleBytecodeRootNode> nodes = converter.convertRoot(expr, "roundTrip");
        CloffleBytecodeRootNode original = nodes.getNode(0);
        Object before = original.getCallTarget().call();
        assertEquals(42L, before);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        nodes.serialize(new DataOutputStream(baos), new CloffleBytecodeSerializer());
        byte[] serialized = baos.toByteArray();
        assertTrue(serialized.length > 0);

        Supplier<DataInput> supplier = () -> SerializationUtils.createDataInput(ByteBuffer.wrap(serialized));
        BytecodeRootNodes<CloffleBytecodeRootNode> deserialized =
                CloffleBytecodeRootNodeGen.deserialize(null, BytecodeConfig.DEFAULT, supplier, new CloffleBytecodeDeserializer());
        CloffleBytecodeRootNode copy = deserialized.getNode(0);
        assertNotNull(copy);
        Object after = copy.getCallTarget().call();
        assertEquals(42L, after);
    }

    @Test
    public void rootNodeNameIsSet() throws Exception {
        CloffleBytecodeRootNode root = compileRoot("(if true 3 4)");
        assertEquals("namedRoot", root.getName());
    }
}
