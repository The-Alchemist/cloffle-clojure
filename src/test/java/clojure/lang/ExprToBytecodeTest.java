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
import java.nio.ByteBuffer;
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
 * without core-provided macros or vars: literals, the {@code if} special form, and collection
 * literals whose elements need no core (e.g. no {@code +}, {@code let}, {@code fn}).
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
