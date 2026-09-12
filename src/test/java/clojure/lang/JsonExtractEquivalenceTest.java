package clojure.lang;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The fused extraction must be indistinguishable from a full parse followed by the accessors:
 * same value, and for malformed input the same {@link JsonParser.ParseException} message (which
 * carries the failure position). Verified by hand-picked cases and by a mutation fuzzer, because a
 * skip-scan that validated less than the materializing parse would silently accept bad JSON.
 */
public class JsonExtractEquivalenceTest {

    private static final String JSONAPI =
            "{\"data\":{\"type\":\"articles\",\"id\":\"article-101\",\"attributes\":{\"title\":\"Shape maps in practice\",\"slug\":\"shape-maps\",\"status\":\"published\",\"author\":\"Avery\"},\"relationships\":{\"author\":{\"type\":\"people\",\"id\":\"person-7\"}},\"links\":{\"self\":\"/articles/article-101\"}},\"meta\":{\"request-id\":\"req-101\",\"version\":\"v1\"}}";

    private static final String ENTITY16 =
            "{\"id\":\"user-101\",\"type\":\"user\",\"tenant-id\":\"org-3\",\"email\":\"avery@example.test\",\"username\":\"avery\",\"status\":\"pending\",\"role\":\"admin\",\"created-at\":\"2026-01-10\",\"updated-at\":\"2026-09-09\",\"version\":\"v7\",\"locale\":\"en-US\",\"timezone\":\"America/New_York\",\"profile\":\"x\",\"settings\":\"y\",\"organization\":\"z\",\"audit\":\"w\"}";

    private static final String ROWS =
            "[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"},{\"id\":3,\"name\":\"c\"},{\"id\":4,\"name\":\"d\"},{\"id\":5,\"name\":\"e\"},{\"id\":6,\"name\":\"f\"},{\"id\":7,\"name\":\"g\"},{\"id\":8,\"name\":\"h\"}]";

    private static Object[] path(Object... steps) {
        return steps;
    }

    private static byte[][] keyUtf8(Object[] steps) {
        byte[][] out = new byte[steps.length][];
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] instanceof Keyword kw) {
                out[i] = kw.sym.toString().getBytes(StandardCharsets.UTF_8);
            }
        }
        return out;
    }

    /** What the lowered operation does: scan, and on a decline parse fully and apply the accessors. */
    private static Object fused(String json, Object[] steps) {
        Object v = JsonParser.extractString(json, steps, keyUtf8(steps));
        return v == JsonParser.FALLBACK ? apply(JsonParser.parseString(json), steps) : v;
    }

    private static Object full(String json, Object[] steps) {
        return apply(JsonParser.parseString(json), steps);
    }

    private static Object apply(Object parsed, Object[] steps) {
        Object cur = parsed;
        for (Object step : steps) {
            cur = step instanceof Keyword kw ? RT.get(cur, kw) : RT.nth(cur, ((Integer) step).intValue());
        }
        return cur;
    }

    /** Asserts both paths agree, whether they return a value or throw. */
    private static void assertSameOutcome(String json, Object[] steps) {
        Object expected;
        String expectedError = null;
        try {
            expected = full(json, steps);
        } catch (Throwable t) {
            expected = null;
            expectedError = t.getClass().getName() + ": " + t.getMessage();
        }
        Object actual;
        String actualError = null;
        try {
            actual = fused(json, steps);
        } catch (Throwable t) {
            actual = null;
            actualError = t.getClass().getName() + ": " + t.getMessage();
        }
        assertEquals("error for " + json, expectedError, actualError);
        assertEquals("value for " + json, expected, actual);
    }

    @Test
    public void nestedObjectPath() {
        Object[] steps = path(Keyword.intern("data"), Keyword.intern("attributes"), Keyword.intern("title"));
        assertEquals("Shape maps in practice", JsonParser.extractString(JSONAPI, steps, keyUtf8(steps)));
        assertSameOutcome(JSONAPI, steps);
    }

    @Test
    public void singleKeyOnShapeMap16() {
        Object[] steps = path(Keyword.intern("email"));
        assertEquals("avery@example.test", JsonParser.extractString(ENTITY16, steps, keyUtf8(steps)));
        assertSameOutcome(ENTITY16, steps);
    }

    @Test
    public void indexThenKey() {
        Object[] steps = path(Integer.valueOf(3), Keyword.intern("name"));
        assertEquals("d", JsonParser.extractString(ROWS, steps, keyUtf8(steps)));
        assertSameOutcome(ROWS, steps);
    }

    @Test
    public void namespacedKeyMatchesPrintedName() {
        Object[] steps = path(Keyword.intern("user", "id"));
        assertEquals(7L, JsonParser.extractString("{\"user/id\":7}", steps, keyUtf8(steps)));
    }

    @Test
    public void duplicateKeysLastWins() {
        Object[] steps = path(Keyword.intern("a"));
        assertEquals(3L, JsonParser.extractString("{\"a\":1,\"b\":2,\"a\":3}", steps, keyUtf8(steps)));
        assertSameOutcome("{\"a\":1,\"b\":2,\"a\":3}", steps);

        Object[] nested = path(Keyword.intern("a"), Keyword.intern("b"));
        assertEquals(2L, JsonParser.extractString("{\"a\":{\"b\":1},\"a\":{\"b\":2}}", nested, keyUtf8(nested)));
        assertSameOutcome("{\"a\":{\"b\":1},\"a\":{\"b\":2}}", nested);
    }

    /** A later duplicate whose value no longer fits the path must not leave the earlier hit standing. */
    @Test
    public void duplicateKeyWithMismatchedLaterValueDeclines() {
        Object[] steps = path(Keyword.intern("a"), Keyword.intern("b"));
        String json = "{\"a\":{\"b\":1},\"a\":7}";
        assertSame(JsonParser.FALLBACK, JsonParser.extractString(json, steps, keyUtf8(steps)));
        assertNull(fused(json, steps));
        assertSameOutcome(json, steps);
    }

    @Test
    public void missingKeyDeclinesAndIsNil() {
        Object[] steps = path(Keyword.intern("nope"));
        assertSame(JsonParser.FALLBACK, JsonParser.extractString("{\"a\":1}", steps, keyUtf8(steps)));
        assertNull(fused("{\"a\":1}", steps));
        assertSameOutcome("{\"a\":1}", steps);
        assertSameOutcome("{}", steps);
        assertSameOutcome("[1,2]", steps);
        assertSameOutcome("7", steps);
        assertSameOutcome("null", steps);
        assertSameOutcome("\"s\"", steps);
    }

    @Test
    public void escapedKeyDeclinesButStillResolves() {
        Object[] steps = path(Keyword.intern("escaped-key"));
        String json = "{\"escaped\\u002dkey\":2}";
        assertSame(JsonParser.FALLBACK, JsonParser.extractString(json, steps, keyUtf8(steps)));
        assertEquals(2L, fused(json, steps));
        assertSameOutcome(json, steps);
    }

    @Test
    public void outOfRangeIndexThrowsLikeNth() {
        Object[] steps = path(Integer.valueOf(9), Keyword.intern("name"));
        assertSame(JsonParser.FALLBACK, JsonParser.extractString(ROWS, steps, keyUtf8(steps)));
        try {
            fused(ROWS, steps);
            fail("expected IndexOutOfBoundsException");
        } catch (IndexOutOfBoundsException expected) {
            // nth semantics, produced by the full-parse fallback rather than reimplemented
        }
        assertSameOutcome(ROWS, steps);
        assertSameOutcome("[]", path(Integer.valueOf(0)));
        assertSameOutcome("{\"a\":1}", path(Integer.valueOf(0)));
    }

    @Test
    public void skippedValuesAreStillValidated() {
        Object[] steps = path(Keyword.intern("a"));
        assertSameOutcome("{\"a\":1,\"b\":01}", steps);
        assertSameOutcome("{\"a\":1,\"b\":\"\\q\"}", steps);
        assertSameOutcome("{\"a\":1,\"b\":\"\\u00zz\"}", steps);
        assertSameOutcome("{\"a\":1,\"b\":tru}", steps);
        assertSameOutcome("{\"a\":1,\"b\":[1,]}", steps);
        assertSameOutcome("{\"a\":1,\"b\":{\"c\" 1}}", steps);
        assertSameOutcome("{\"a\":1,\"b\":1e}", steps);
        assertSameOutcome("{\"a\":1,\"b\":-}", steps);
        assertSameOutcome("{\"a\":1,\"b\":\"unterminated}", steps);
        assertSameOutcome("{\"a\":1,\"b\\\u0001\":2}", steps);
        assertSameOutcome("{\"a\":1} trailing", steps);
        assertSameOutcome("{\"a\":1", steps);
        assertSameOutcome("", steps);
        assertSameOutcome("   ", steps);
    }

    /** The target value itself is parsed by the ordinary materializing path. */
    @Test
    public void targetValueKeepsItsNormalRepresentation() {
        Object[] steps = path(Keyword.intern("data"), Keyword.intern("attributes"));
        Object attrs = JsonParser.extractString(JSONAPI, steps, keyUtf8(steps));
        assertTrue(attrs instanceof PersistentShapeMap);
        assertEquals(full(JSONAPI, steps), attrs);

        Object[] rowSteps = path(Integer.valueOf(2));
        assertEquals(full(ROWS, rowSteps), JsonParser.extractString(ROWS, rowSteps, keyUtf8(rowSteps)));
    }

    @Test
    public void deepNestingRejectedAtTheSameDepth() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < JsonParser.MAX_DEPTH + 4; i++) {
            sb.append("{\"a\":");
        }
        sb.append('1');
        for (int i = 0; i < JsonParser.MAX_DEPTH + 4; i++) {
            sb.append('}');
        }
        String json = sb.toString();
        assertSameOutcome(json, path(Keyword.intern("a")));
        assertSameOutcome(json, path(Keyword.intern("a"), Keyword.intern("a")));
    }

    @Test
    public void mutationFuzzAgreesWithFullParse() {
        Object[][] pathsPerDoc = {
                path(Keyword.intern("data"), Keyword.intern("attributes"), Keyword.intern("title")),
                path(Keyword.intern("email")),
                path(Integer.valueOf(3), Keyword.intern("name")),
        };
        String[] docs = {JSONAPI, ENTITY16, ROWS};
        Random random = new Random(20260912L);
        char[] pokes = {'"', '\\', '{', '}', '[', ']', ':', ',', '0', 'e', ' ', '\u0001', 'x', 'u'};
        for (int d = 0; d < docs.length; d++) {
            String doc = docs[d];
            Object[] steps = pathsPerDoc[d];
            for (int i = 0; i < 4000; i++) {
                String mutated = mutate(doc, random, pokes);
                assertSameOutcome(mutated, steps);
                // Cross-check every path against every document too: mismatched shapes must decline,
                // not misreport.
                assertSameOutcome(mutated, pathsPerDoc[random.nextInt(pathsPerDoc.length)]);
            }
        }
    }

    private static String mutate(String doc, Random random, char[] pokes) {
        int mode = random.nextInt(4);
        int at = random.nextInt(doc.length());
        return switch (mode) {
            case 0 -> doc.substring(0, at);
            case 1 -> doc.substring(0, at) + pokes[random.nextInt(pokes.length)] + doc.substring(at + 1);
            case 2 -> doc.substring(0, at) + pokes[random.nextInt(pokes.length)] + doc.substring(at);
            default -> doc.substring(0, at) + doc.substring(Math.min(at + 1, doc.length()));
        };
    }
}
