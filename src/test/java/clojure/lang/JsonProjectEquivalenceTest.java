package clojure.lang;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

/**
 * Multi-path SAX project vs full parse plus accessors: missing keys stay {@link JsonParser#MISSING},
 * present keys match {@code RT.get} / {@code get-in}.
 */
public class JsonProjectEquivalenceTest {

    private static final String JSONAPI =
            "{\"data\":{\"type\":\"articles\",\"id\":\"article-101\",\"attributes\":{\"title\":\"Shape maps in practice\"}},\"meta\":{\"request-id\":\"req-101\"}}";

    private static JsonParser.TrieNode leaf(int slot) {
        return new JsonParser.TrieNode(new JsonParser.TrieEdge[0], slot);
    }

    private static JsonParser.TrieEdge kw(String name, JsonParser.TrieNode child) {
        return new JsonParser.TrieEdge(name.getBytes(StandardCharsets.UTF_8), -1, child);
    }

    @Test
    public void twoNestedPaths() {
        JsonParser.TrieNode title = leaf(0);
        JsonParser.TrieNode attrs = new JsonParser.TrieNode(new JsonParser.TrieEdge[] {kw("title", title)}, -1);
        JsonParser.TrieNode id = leaf(1);
        JsonParser.TrieNode data = new JsonParser.TrieNode(
                new JsonParser.TrieEdge[] {kw("attributes", attrs), kw("id", id)}, -1);
        JsonParser.TrieNode rid = leaf(2);
        JsonParser.TrieNode meta = new JsonParser.TrieNode(new JsonParser.TrieEdge[] {kw("request-id", rid)}, -1);
        JsonParser.TrieNode root = new JsonParser.TrieNode(
                new JsonParser.TrieEdge[] {kw("data", data), kw("meta", meta)}, -1);

        Object[] slots = (Object[]) JsonParser.projectString(JSONAPI, root, 3);
        IPersistentMap parsed = (IPersistentMap) JsonParser.parseString(JSONAPI);
        assertEquals(RT.getIn(parsed, RT.vector(Keyword.intern("data"), Keyword.intern("attributes"), Keyword.intern("title"))),
                slots[0]);
        assertEquals(RT.getIn(parsed, RT.vector(Keyword.intern("data"), Keyword.intern("id"))), slots[1]);
        assertEquals(RT.getIn(parsed, RT.vector(Keyword.intern("meta"), Keyword.intern("request-id"))), slots[2]);
    }

    @Test
    public void malformedStillThrows() {
        JsonParser.TrieNode leaf = leaf(0);
        JsonParser.TrieNode root = new JsonParser.TrieNode(
                new JsonParser.TrieEdge[] {kw("a", leaf)}, -1);
        String expected;
        try {
            JsonParser.parseString("{");
            org.junit.Assert.fail("expected ParseException");
            return;
        } catch (JsonParser.ParseException parseEx) {
            expected = parseEx.getMessage();
        }
        try {
            JsonParser.projectString("{", root, 1);
            org.junit.Assert.fail("expected ParseException");
        } catch (JsonParser.ParseException projectEx) {
            assertEquals(expected, projectEx.getMessage());
        }
    }
}
