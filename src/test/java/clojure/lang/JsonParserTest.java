package clojure.lang;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class JsonParserTest {

    private static final String JSONAPI = loadResource("jsonapi.json");
    private static final String ENTITY16 = loadResource("entity16.json");

    @Test
    public void emptyObjectIsShapeMap() {
        Object v = JsonParser.parseString("{}");
        assertTrue(v instanceof PersistentShapeMap);
        assertEquals(0, ((PersistentShapeMap) v).count());
        assertSame(PersistentShapeMap.EMPTY, v);
    }

    @Test
    public void smallObjectIsShapeMapInInsertionOrder() {
        PersistentShapeMap m = (PersistentShapeMap) JsonParser.parseString("{\"b\":2,\"a\":1}");
        assertEquals(2, m.count());
        assertEquals(Keyword.intern("b"), m.shape.k0);
        assertEquals(Keyword.intern("a"), m.shape.k1);
        assertEquals(2L, m.v0);
        assertEquals(1L, m.v1);
        ISeq s = m.seq();
        assertEquals(Keyword.intern("b"), ((IMapEntry) s.first()).key());
        assertEquals(Keyword.intern("a"), ((IMapEntry) s.next().first()).key());
    }

    @Test
    public void namespacedKeywordFromSlash() {
        IPersistentMap m = (IPersistentMap) JsonParser.parseString("{\"user/id\":7}");
        assertEquals(7L, m.valAt(Keyword.intern("user", "id")));
    }

    @Test
    public void utf8AndEscapedKeywordKeys() {
        IPersistentMap m = (IPersistentMap) JsonParser.parseString(
                "{\"café\":1,\"escaped\\u002dkey\":2}");
        assertEquals(1L, m.valAt(Keyword.intern("café")));
        assertEquals(2L, m.valAt(Keyword.intern("escaped-key")));

        IPersistentMap cached = (IPersistentMap) JsonParser.parseString(
                "{\"café\":3,\"escaped\\u002dkey\":4}");
        assertEquals(3L, cached.valAt(Keyword.intern("café")));
        assertEquals(4L, cached.valAt(Keyword.intern("escaped-key")));
    }

    @Test
    public void duplicateKeysLastWinsKeepFirstOrder() {
        PersistentShapeMap m = (PersistentShapeMap) JsonParser.parseString("{\"a\":1,\"b\":2,\"a\":3}");
        assertEquals(2, m.count());
        assertEquals(Keyword.intern("a"), m.shape.k0);
        assertEquals(Keyword.intern("b"), m.shape.k1);
        assertEquals(3L, m.v0);
        assertEquals(2L, m.v1);
    }

    @Test
    public void sixteenKeysAreShapeMap16() {
        Object v = JsonParser.parseString(ENTITY16);
        assertTrue(v instanceof PersistentShapeMap16);
        assertEquals(16, ((PersistentShapeMap16) v).count());
        assertEquals("user-101", ((IPersistentMap) v).valAt(Keyword.intern("id")));
    }

    @Test
    public void seventeenKeysAreHashMap() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 17; i++) {
            if (i > 0) sb.append(',');
            sb.append("\"k").append(i).append("\":").append(i);
        }
        sb.append('}');
        Object v = JsonParser.parseString(sb.toString());
        assertTrue(v instanceof PersistentHashMap);
        assertEquals(17, ((IPersistentMap) v).count());
        assertEquals(16L, ((IPersistentMap) v).valAt(Keyword.intern("k16")));
    }

    @Test
    public void smallArrayIsTuple() {
        Object v = JsonParser.parseString("[1,2,3]");
        assertTrue(v instanceof PersistentTuple);
        IPersistentVector vec = (IPersistentVector) v;
        assertEquals(3, vec.count());
        assertEquals(1L, vec.nth(0));
        assertEquals(3L, vec.nth(2));
    }

    @Test
    public void emptyArrayIsEmptyVector() {
        Object v = JsonParser.parseString("[]");
        assertTrue(v instanceof IPersistentVector);
        assertEquals(0, ((IPersistentVector) v).count());
    }

    @Test
    public void nestedJsonApiDocument() {
        IPersistentMap doc = (IPersistentMap) JsonParser.parseString(JSONAPI);
        assertTrue(doc instanceof PersistentShapeMap);
        IPersistentMap data = (IPersistentMap) doc.valAt(Keyword.intern("data"));
        IPersistentMap attrs = (IPersistentMap) data.valAt(Keyword.intern("attributes"));
        assertEquals("Shape maps in practice", attrs.valAt(Keyword.intern("title")));
        assertTrue(attrs instanceof PersistentShapeMap);
    }

    @Test
    public void scalars() {
        assertNull(JsonParser.parseString("null"));
        assertEquals(Boolean.TRUE, JsonParser.parseString("true"));
        assertEquals(Boolean.FALSE, JsonParser.parseString("false"));
        assertEquals("hi", JsonParser.parseString("\"hi\""));
        assertEquals(0L, JsonParser.parseString("0"));
        assertEquals(-42L, JsonParser.parseString("-42"));
        assertEquals(1.5d, (Double) JsonParser.parseString("1.5"), 0.0);
        assertEquals(1e10d, (Double) JsonParser.parseString("1e10"), 0.0);
        Object big = JsonParser.parseString("9223372036854775808");
        assertTrue(big instanceof BigInt);
        assertEquals(new BigInteger("9223372036854775808"), ((BigInt) big).toBigInteger());
    }

    @Test
    public void stringEscapes() {
        assertEquals("a\"b\\c/d", JsonParser.parseString("\"a\\\"b\\\\c\\/d\""));
        assertEquals("\n\t", JsonParser.parseString("\"\\n\\t\""));
        assertEquals("A", JsonParser.parseString("\"\\u0041\""));
        assertEquals("🙂", JsonParser.parseString("\"\\uD83D\\uDE42\""));
    }

    @Test
    public void utf8Value() {
        assertEquals("café", JsonParser.parseString("\"café\""));
    }

    @Test
    public void parseBytesRoundTrip() {
        byte[] bytes = JSONAPI.getBytes(StandardCharsets.UTF_8);
        Object fromBytes = JsonParser.parseBytes(bytes);
        Object fromString = JsonParser.parseString(JSONAPI);
        assertEquals(fromString, fromBytes);
    }

    @Test
    public void stringKeysWhenNotKeywordized() {
        Object v = JsonParser.parseString("{\"a\":1}", new AFn() {
            @Override
            public Object invoke(Object arg1) {
                return arg1;
            }
        });
        assertTrue(v instanceof PersistentArrayMap);
        assertEquals(1L, ((IPersistentMap) v).valAt("a"));
        assertNull(((IPersistentMap) v).valAt(Keyword.intern("a")));
    }

    @Test
    public void whitespaceAndTrailingRejected() {
        Object v = JsonParser.parseString("  { \"a\" : 1 }  ");
        assertEquals(1L, ((IPersistentMap) v).valAt(Keyword.intern("a")));
        try {
            JsonParser.parseString("1 2");
            fail();
        } catch (JsonParser.ParseException e) {
            assertTrue(e.getMessage().contains("Trailing"));
        }
    }

    @Test
    public void leadingZerosRejected() {
        try {
            JsonParser.parseString("01");
            fail();
        } catch (JsonParser.ParseException e) {
            assertTrue(e.getMessage().contains("Leading zeros"));
        }
    }

    @Test
    public void shapeCacheHitsSameLayout() {
        String json = "{\"x\":1,\"y\":2,\"z\":3}";
        PersistentShapeMap a = (PersistentShapeMap) JsonParser.parseString(json);
        PersistentShapeMap b = (PersistentShapeMap) JsonParser.parseString(json);
        assertTrue(a.shape.sameKeys(b.shape));
        assertEquals(a, b);
    }

    @Test
    public void arrayOfSmallObjects() {
        Object v = JsonParser.parseString("[{\"id\":1},{\"id\":2},{\"id\":3}]");
        IPersistentVector vec = (IPersistentVector) v;
        assertTrue(vec instanceof PersistentTuple);
        assertTrue(vec.nth(0) instanceof PersistentShapeMap);
        assertEquals(2L, ((IPersistentMap) vec.nth(1)).valAt(Keyword.intern("id")));
    }

    @Test
    public void cloffleJsonNsKeywordizeAndIdentity() throws Exception {
        RT.init();
        RT.load("cloffle/json");
        IFn project = RT.var("cloffle.json", "project");
        IPersistentMap m = (IPersistentMap) project.invoke("{\"a\":1}");
        assertTrue(m instanceof PersistentShapeMap);
        assertEquals(1L, m.valAt(Keyword.intern("a")));
    }

    @Test
    public void concurrentParses() throws Exception {
        byte[] bytes = JSONAPI.getBytes(StandardCharsets.UTF_8);
        List<Thread> threads = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            Thread th = new Thread(() -> {
                try {
                    for (int i = 0; i < 50; i++) {
                        IPersistentMap doc = (IPersistentMap) JsonParser.parseBytes(bytes);
                        IPersistentMap data = (IPersistentMap) doc.valAt(Keyword.intern("data"));
                        assertEquals("article-101", data.valAt(Keyword.intern("id")));
                    }
                } catch (Throwable e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                }
            });
            threads.add(th);
            th.start();
        }
        for (Thread th : threads) {
            th.join();
        }
        assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test
    public void parseCharSequenceAndByteBuffer() {
        CharSequence cs = new StringBuilder(JSONAPI);
        IPersistentMap fromCs = (IPersistentMap) JsonParser.parseString(cs);
        assertEquals("article-101",
                ((IPersistentMap) fromCs.valAt(Keyword.intern("data"))).valAt(Keyword.intern("id")));

        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(JSONAPI.getBytes(StandardCharsets.UTF_8));
        IPersistentMap fromBuf = (IPersistentMap) JsonParser.parseByteBuffer(buf);
        assertEquals("article-101",
                ((IPersistentMap) fromBuf.valAt(Keyword.intern("data"))).valAt(Keyword.intern("id")));
        assertEquals(0, buf.position());
    }

    @Test
    public void projectFillsSelectedSlotsOnly() {
        Keyword email = Keyword.intern("email");
        byte[] utf8 = email.sym.toString().getBytes(StandardCharsets.UTF_8);
        JsonParser.TrieNode leaf = new JsonParser.TrieNode(new JsonParser.TrieEdge[0], 0);
        JsonParser.TrieNode root = new JsonParser.TrieNode(
                new JsonParser.TrieEdge[] {new JsonParser.TrieEdge(utf8, -1, leaf)}, -1);
        Object raw = JsonParser.projectString(ENTITY16, root, 1);
        assertTrue(raw instanceof Object[]);
        Object[] slots = (Object[]) raw;
        assertEquals("avery@example.test", slots[0]);
    }

    @Test
    public void projectMissingKeyStaysMissing() {
        byte[] utf8 = "nope".getBytes(StandardCharsets.UTF_8);
        JsonParser.TrieNode leaf = new JsonParser.TrieNode(new JsonParser.TrieEdge[0], 0);
        JsonParser.TrieNode root = new JsonParser.TrieNode(
                new JsonParser.TrieEdge[] {new JsonParser.TrieEdge(utf8, -1, leaf)}, -1);
        Object[] slots = (Object[]) JsonParser.projectString("{\"email\":\"x\"}", root, 1);
        assertSame(JsonParser.MISSING, slots[0]);
    }


    private static String loadResource(String name) {
        try (InputStream in = JsonParserTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Resource not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
