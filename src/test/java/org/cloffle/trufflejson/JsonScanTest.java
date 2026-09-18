package org.cloffle.trufflejson;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class JsonScanTest {
    @Test
    public void projectsUtf8FieldToTruffleString() {
        byte[] json = "{\"title\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
        JsonScan.TypedLeaf leaf = new JsonScan.TypedLeaf(
                JsonScan.TypedLeaf.TRUFFLE_STRING, false, false, null);
        TypedSchema.TrieBuilder trie = TypedSchema.trie();
        trie.insert(java.util.List.of("title"), 0, leaf);
        JsonScan.TypedScanResult scan = JsonScan.projectBytes(json, trie.toTrie(),
                new JsonScan.TypedLeaf[] {leaf}, true);
        assertEquals(JsonScan.TypedScanResult.SLICE, scan.states[0]);
        assertEquals(5, scan.lengths[0]);
    }

    @Test
    public void fixedSlots8IsNotClojure() {
        Utf8Layout layout = new Utf8Layout("id".getBytes(StandardCharsets.UTF_8));
        FixedSlots8 map = new FixedSlots8(layout, 1, null, null, null, null, null, null, null);
        assertEquals(1, layout.count);
        assertEquals(1, map.v0);
        assertEquals(1, FixedSlots8.fromSlots(layout, new Object[] {1}).v0);
        assertTrue(map.getClass().getName().startsWith("org.cloffle.trufflejson."));
    }

    @Test
    public void missingSentinelIsLibraryOwned() {
        assertSame(JsonScan.MISSING, JsonScan.MISSING);
    }
}
