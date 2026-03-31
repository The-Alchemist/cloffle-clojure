package clojure.lang;

import net.javacrumbs.cloffle.bytecode.CloffleBytecodeRootNode;
import org.junit.Test;

import java.math.BigInteger;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Literals, collection literals, {@code quote}, metadata, regex, and bytecode root naming.
 * <p>
 * No {@code clojure.core} load — forms limited to what {@link Compiler#analyze} handles natively.
 * <p>
 * Package {@code clojure.lang} for access to {@link Compiler} internals.
 * Helpers: {@link BytecodeDslTestSupport}.
 */
public class BytecodeLiteralsTest {

    @Test
    public void nilConstant() {
        assertNull(BytecodeDslTestSupport.evalBytecode("nil"));
    }

    @Test
    public void longConstant() {
        assertEquals(42L, BytecodeDslTestSupport.evalBytecode("42"));
    }

    @Test
    public void keywordConstant() {
        Object k = BytecodeDslTestSupport.evalBytecode(":hello/bytecode");
        assertTrue(k instanceof Keyword);
        assertEquals("hello", ((Keyword) k).getNamespace());
        assertEquals("bytecode", ((Keyword) k).getName());
    }

    @Test
    public void stringConstant() {
        assertEquals("truffle", BytecodeDslTestSupport.evalBytecode("\"truffle\""));
    }

    @Test
    public void booleanConstants() {
        assertSame(RT.T, BytecodeDslTestSupport.evalBytecode("true"));
        assertSame(RT.F, BytecodeDslTestSupport.evalBytecode("false"));
    }

    @Test
    public void emptyVectorConstant() {
        Object v = BytecodeDslTestSupport.evalBytecode("[]");
        assertTrue(v instanceof IPersistentVector);
        assertTrue(((IPersistentVector) v).count() == 0);
    }

    @Test
    public void doubleConstant() {
        assertEquals(3.14, (Double) BytecodeDslTestSupport.evalBytecode("3.14"), 0.0);
    }

    @Test
    public void characterConstant() {
        assertEquals(Character.valueOf('z'), BytecodeDslTestSupport.evalBytecode("\\z"));
    }

    @Test
    public void quotedList() {
        Object x = BytecodeDslTestSupport.evalBytecode("(quote (1 2 3))");
        assertTrue(x instanceof ISeq);
        ISeq s = (ISeq) x;
        assertEquals(1L, s.first());
        assertEquals(2L, RT.second(s));
        assertEquals(3L, RT.third(s));
    }

    @Test
    public void quotedSymbol() {
        Object x = BytecodeDslTestSupport.evalBytecode("(quote abcd)");
        assertTrue(x instanceof Symbol);
        assertEquals("abcd", ((Symbol) x).getName());
    }

    @Test
    public void ratioConstant() {
        Object r = BytecodeDslTestSupport.evalBytecode("1/2");
        assertTrue(r instanceof Ratio);
        assertEquals(BigInteger.ONE, ((Ratio) r).numerator);
        assertEquals(BigInteger.TWO, ((Ratio) r).denominator);
    }

    @Test
    public void emptyMapAndSetLiterals() {
        Object m = BytecodeDslTestSupport.evalBytecode("{}");
        assertTrue(m instanceof IPersistentMap);
        assertEquals(0, ((IPersistentMap) m).count());
        Object st = BytecodeDslTestSupport.evalBytecode("#{}");
        assertTrue(st instanceof IPersistentSet);
        assertEquals(0, ((IPersistentSet) st).count());
    }

    @Test
    public void setLiteralWithoutCoreFns() {
        Object st = BytecodeDslTestSupport.evalBytecode("#{1 2 3}");
        assertTrue(st instanceof IPersistentSet);
        IPersistentSet set = (IPersistentSet) st;
        assertEquals(3, set.count());
        assertTrue(set.contains(1L));
        assertTrue(set.contains(2L));
        assertTrue(set.contains(3L));
    }

    @Test
    public void vectorLiteralWithoutCoreFns() {
        Object v = BytecodeDslTestSupport.evalBytecode("[1 2 3]");
        assertTrue(v instanceof IPersistentVector);
        IPersistentVector vec = (IPersistentVector) v;
        assertEquals(3, vec.count());
        assertEquals(1L, vec.nth(0));
        assertEquals(2L, vec.nth(1));
        assertEquals(3L, vec.nth(2));
    }

    @Test
    public void mapLiteralWithoutCoreFns() {
        Object m = BytecodeDslTestSupport.evalBytecode("{:a 1 :b 2}");
        assertTrue(m instanceof IPersistentMap);
        IPersistentMap map = (IPersistentMap) m;
        assertEquals(2, map.count());
        assertEquals(1L, map.valAt(Keyword.intern("a")));
        assertEquals(2L, map.valAt(Keyword.intern("b")));
    }

    @Test
    public void quotedEmptyList() {
        Object x = BytecodeDslTestSupport.evalBytecode("(quote ())");
        assertTrue(x instanceof IPersistentCollection);
        assertEquals(0, ((IPersistentCollection) x).count());
    }

    @Test
    public void bigintLiteral() {
        Object n = BytecodeDslTestSupport.evalBytecode("10000000000000000000N");
        assertTrue(n instanceof BigInt);
        assertEquals(new BigInteger("10000000000000000000"), ((BigInt) n).toBigInteger());
    }

    @Test
    public void regexLiteral() {
        Object p = BytecodeDslTestSupport.evalBytecode("#\"a+\"");
        assertTrue(p instanceof Pattern);
        assertTrue(((Pattern) p).matcher("aaa").matches());
    }

    @Test
    public void vectorWithMetadata() {
        Object v = BytecodeDslTestSupport.evalBytecode("^{:x 1} [1 2]");
        assertTrue(v instanceof IPersistentVector);
        IPersistentVector vec = (IPersistentVector) v;
        assertEquals(2, vec.count());
        Object meta = RT.meta(vec);
        assertNotNull(meta);
        assertEquals(1L, RT.get(meta, Keyword.intern("x")));
    }

    @Test
    public void rootNodeNameIsSet() throws Exception {
        CloffleBytecodeRootNode root = BytecodeDslTestSupport.compileRoot("(if true 3 4)");
        assertEquals("namedRoot", root.getName());
    }
}
