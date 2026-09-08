package clojure.lang;

import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import net.javacrumbs.cloffle.bytecode.archive.CloffleBytecodeSerialization;
import org.junit.Test;

import static org.junit.Assert.*;

public class BytecodeStaticMethodTest {

    public static class TestTargets {
        public static String echo0() {
            return "hello0";
        }

        public static String echo1(String a) {
            return "hello1:" + a;
        }

        public static String echo2(String a, String b) {
            return "hello2:" + a + "," + b;
        }

        public static String echo3(String a, String b, String c) {
            return "hello3:" + a + "," + b + "," + c;
        }

        public static String echo4(String a, String b, String c, String d) {
            return "hello4:" + a + "," + b + "," + c + "," + d;
        }

        public static String failWithCustom(String msg) {
            throw new IllegalArgumentException(msg);
        }
    }

    @Test
    public void fixedArityReferenceMethods0To4() {
        assertEquals("hello0", BytecodeDslTestSupport.evalBytecode(
                "(clojure.lang.BytecodeStaticMethodTest$TestTargets/echo0)"));
        assertEquals("hello1:world", BytecodeDslTestSupport.evalBytecode(
                "(clojure.lang.BytecodeStaticMethodTest$TestTargets/echo1 \"world\")"));
        assertEquals("hello2:a,b", BytecodeDslTestSupport.evalBytecode(
                "(clojure.lang.BytecodeStaticMethodTest$TestTargets/echo2 \"a\" \"b\")"));
        assertEquals("hello3:a,b,c", BytecodeDslTestSupport.evalBytecode(
                "(clojure.lang.BytecodeStaticMethodTest$TestTargets/echo3 \"a\" \"b\" \"c\")"));
        assertEquals("hello4:a,b,c,d", BytecodeDslTestSupport.evalBytecode(
                "(clojure.lang.BytecodeStaticMethodTest$TestTargets/echo4 \"a\" \"b\" \"c\" \"d\")"));
    }

    @Test
    public void nullArgumentsWorkCorrectly() {
        assertEquals("hello2:null,world", BytecodeDslTestSupport.evalBytecode(
                "(clojure.lang.BytecodeStaticMethodTest$TestTargets/echo2 nil \"world\")"));
        assertEquals("hello2:world,null", BytecodeDslTestSupport.evalBytecode(
                "(clojure.lang.BytecodeStaticMethodTest$TestTargets/echo2 \"world\" nil)"));
    }

    @Test
    public void rtGetReferenceCalls() {
        PersistentArrayMap map = PersistentArrayMap.createWithCheck(new Object[]{
                Keyword.intern(null, "foo"), "bar"
        });
        Object result = BytecodeDslTestSupport.evalBytecode(
                "(clojure.lang.RT/get {:foo \"bar\"} :foo)");
        assertEquals("bar", result);

        Object notFound = BytecodeDslTestSupport.evalBytecode(
                "(clojure.lang.RT/get {:foo \"bar\"} :missing :fallback)");
        assertEquals(Keyword.intern(null, "fallback"), notFound);
    }

    @Test
    public void targetExceptionCatchableAsOriginalClass() {
        Object result = BytecodeDslTestSupport.evalBytecode(
                "(try (clojure.lang.BytecodeStaticMethodTest$TestTargets/failWithCustom \"boom\") " +
                "  (catch IllegalArgumentException e (.getMessage e)))");
        assertEquals("boom", result);
    }

    @Test
    public void primitiveSignaturesFallbackGracefully() {
        Object absResult = BytecodeDslTestSupport.evalBytecode(
                "(Math/abs -42)");
        assertEquals(42L, ((Number) absResult).longValue());

        Object parseResult = BytecodeDslTestSupport.evalBytecode(
                "(Integer/parseInt \"123\")");
        assertEquals(123, ((Number) parseResult).intValue());
    }

    @Test
    public void serializationRoundTripRecreatesMethodHandle() throws Exception {
        String code = "(clojure.lang.BytecodeStaticMethodTest$TestTargets/echo2 \"x\" \"y\")";
        BytecodeRootNodes<CloffleBytecodeRootNode> nodes =
                BytecodeDslTestSupport.compileRootNodes(code, "testStaticMh");

        Object direct = nodes.getNode(0).getCallTarget().call();
        assertEquals("hello2:x,y", direct);

        byte[] wire = CloffleBytecodeSerialization.serializeRootNodes(nodes);
        assertTrue(wire.length > 0);

        BytecodeRootNodes<CloffleBytecodeRootNode> back =
                CloffleBytecodeSerialization.deserializeRootNodes(wire);
        Object after = back.getNode(0).getCallTarget().call();
        assertEquals("hello2:x,y", after);
    }
}
