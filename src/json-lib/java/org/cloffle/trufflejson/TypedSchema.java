package org.cloffle.trufflejson;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a UTF-8 field/index trie without Clojure Keywords. Cloffle compiles Malli schemas
 * into this form, then scans with {@link JsonScan}.
 */
public final class TypedSchema {
    private TypedSchema() {
    }

    public static TrieBuilder trie() {
        return new TrieBuilder();
    }

    public static JsonScan.TypedTrieEdge keywordEdge(String name, JsonScan.TypedTrieNode child) {
        byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
        return new JsonScan.TypedTrieEdge(utf8, name, -1, child);
    }

    public static JsonScan.TypedTrieEdge indexEdge(int index, JsonScan.TypedTrieNode child) {
        return new JsonScan.TypedTrieEdge(null, index, child);
    }

    public static final class TrieBuilder {
        final Map<Object, TrieBuilder> children = new LinkedHashMap<>();
        int slot = -1;
        JsonScan.TypedLeaf leaf;

        public void insert(List<Object> path, int slot, JsonScan.TypedLeaf leaf) {
            TrieBuilder node = this;
            for (Object step : path) {
                if (node.slot >= 0) {
                    throw new IllegalArgumentException("Schema selects both a value and its child");
                }
                node = node.children.computeIfAbsent(step, ignored -> new TrieBuilder());
            }
            if (!node.children.isEmpty() || node.slot >= 0) {
                throw new IllegalArgumentException("Duplicate or overlapping JSON projection path");
            }
            node.slot = slot;
            node.leaf = leaf;
        }

        public JsonScan.TypedTrieNode toTrie() {
            List<JsonScan.TypedTrieEdge> edges = new ArrayList<>(children.size());
            for (Map.Entry<Object, TrieBuilder> entry : children.entrySet()) {
                Object key = entry.getKey();
                JsonScan.TypedTrieNode child = entry.getValue().toTrie();
                if (key instanceof byte[] utf8) {
                    edges.add(new JsonScan.TypedTrieEdge(utf8, -1, child));
                } else if (key instanceof Integer index) {
                    edges.add(new JsonScan.TypedTrieEdge(null, index, child));
                } else if (key instanceof String name) {
                    edges.add(keywordEdge(name, child));
                } else {
                    throw new IllegalArgumentException("Trie step must be UTF-8 name or index: " + key);
                }
            }
            return new JsonScan.TypedTrieNode(edges.toArray(new JsonScan.TypedTrieEdge[0]), slot, leaf);
        }
    }
}
