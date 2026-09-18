package net.javacrumbs.cloffle.bytecode;

import clojure.lang.JsonParser;
import clojure.lang.Keyword;
import clojure.lang.MapShape;
import clojure.lang.PersistentShapeMap;
import clojure.lang.RT;
import clojure.lang.Var;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.IndirectCallNode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compile-time SAX projection: a keyword/index trie over interned keys, slot ids for selected
 * values, and an optional output {@link MapShape}. The scanner lives behind a
 * {@code @TruffleBoundary}; {@link #build} does not, so a {@code PersistentShapeMap} allocated
 * from the results is visible to partial escape analysis.
 */
final class JsonProjectPlan {

    enum Kind {
        SCALAR,
        MAP,
        SELECT_KEYS
    }

    final Var parseVar;
    final boolean bytesSource;
    final JsonParser.TrieNode root;
    final int slotCount;
    final Kind kind;
    final Keyword[] outKeys;
    final int[] outSlots;
    final MapShape outShape;
    final Object[][] fallbackPaths;
    final Var[] accessorVars;

    JsonProjectPlan(Var parseVar, boolean bytesSource, JsonParser.TrieNode root, int slotCount,
                    Kind kind, Keyword[] outKeys, int[] outSlots, MapShape outShape,
                    Object[][] fallbackPaths, Var[] accessorVars) {
        this.parseVar = parseVar;
        this.bytesSource = bytesSource;
        this.root = root;
        this.slotCount = slotCount;
        this.kind = kind;
        this.outKeys = outKeys;
        this.outSlots = outSlots;
        this.outShape = outShape;
        this.fallbackPaths = fallbackPaths;
        this.accessorVars = accessorVars;
    }

    Assumption[] loweringAssumptions() {
        List<Assumption> out = new ArrayList<>(2 + accessorVars.length);
        out.add(BytecodeLowering.sanctionedRootAssumption(parseVar));
        for (Var var : accessorVars) {
            if (var != null) {
                out.add(BytecodeLowering.sanctionedRootAssumption(var));
            }
        }
        return out.toArray(new Assumption[0]);
    }

    @TruffleBoundary
    Object scan(Object source) {
        if (!sourceMatches(source)) {
            return null;
        }
        Object raw = projectSource(source);
        if (raw == JsonParser.FALLBACK) {
            return null;
        }
        Object[] src = (Object[]) raw;
        Object[] copy = new Object[slotCount];
        System.arraycopy(src, 0, copy, 0, slotCount);
        return copy;
    }

    boolean anyMissing(Object[] slots) {
        for (int i = 0; i < outSlots.length; i++) {
            if (slots[outSlots[i]] == JsonParser.MISSING) {
                return true;
            }
        }
        return false;
    }

    Object slotOrNil(Object[] slots, int i) {
        Object v = slots[i];
        return v == JsonParser.MISSING ? null : v;
    }

    Object build(Object[] slots) {
        if (kind == Kind.SCALAR) {
            return slotOrNil(slots, 0);
        }
        int n = outKeys.length;
        if (n <= PersistentShapeMap.MAX_SHAPE_KEYS) {
            Object[] vals = new Object[n];
            for (int i = 0; i < n; i++) {
                vals[i] = slotOrNil(slots, outSlots[i]);
            }
            return PersistentShapeMap.createFromKeys(null, n, outKeys, vals);
        }
        Object[] kvs = new Object[n * 2];
        for (int i = 0; i < n; i++) {
            kvs[i * 2] = outKeys[i];
            kvs[i * 2 + 1] = slotOrNil(slots, outSlots[i]);
        }
        return RT.mapUniqueKeys(kvs);
    }

    @TruffleBoundary
    Object fallback(Object source) {
        Object parsed = parse(source);
        if (kind == Kind.SCALAR) {
            return applyPath(parsed, fallbackPaths[0]);
        }
        if (kind == Kind.SELECT_KEYS) {
            return selectKeysHost(parsed, outKeys);
        }
        Object[] kvs = new Object[outKeys.length * 2];
        for (int i = 0; i < outKeys.length; i++) {
            kvs[i * 2] = outKeys[i];
            kvs[i * 2 + 1] = applyPath(parsed, fallbackPaths[i]);
        }
        return RT.mapUniqueKeys(kvs);
    }

    @TruffleBoundary
    Object redefined(Object source, IndirectCallNode callNode) {
        Object parsed = BytecodeLowering.invokeRedefined(parseVar, callNode, source);
        if (kind == Kind.SCALAR) {
            return applyPath(parsed, fallbackPaths[0]);
        }
        if (kind == Kind.SELECT_KEYS) {
            Var sk = accessorVars.length > 0 ? accessorVars[0] : null;
            if (sk != null) {
                return BytecodeLowering.invokeRedefined(sk, callNode, parsed, RT.vector((Object[]) outKeys));
            }
            return selectKeysHost(parsed, outKeys);
        }
        Object[] kvs = new Object[outKeys.length * 2];
        for (int i = 0; i < outKeys.length; i++) {
            kvs[i * 2] = outKeys[i];
            kvs[i * 2 + 1] = applyPath(parsed, fallbackPaths[i]);
        }
        return RT.mapUniqueKeys(kvs);
    }

    private boolean sourceMatches(Object source) {
        if (bytesSource) {
            return source instanceof byte[] || source instanceof ByteBuffer;
        }
        return source instanceof CharSequence
                || source instanceof com.oracle.truffle.api.strings.TruffleString;
    }

    private Object projectSource(Object source) {
        if (source instanceof byte[] bytes) {
            return JsonParser.projectBytes(bytes, root, slotCount);
        }
        if (source instanceof ByteBuffer buf) {
            return JsonParser.projectByteBuffer(buf, root, slotCount);
        }
        if (source instanceof String s) {
            return JsonParser.projectString(s, root, slotCount);
        }
        if (source instanceof com.oracle.truffle.api.strings.TruffleString ts) {
            return JsonParser.projectBytes(JsonParser.utf8Bytes(ts), root, slotCount);
        }
        return JsonParser.projectCharSequence((CharSequence) source, root, slotCount);
    }

    private Object parse(Object source) {
        return JsonParser.parseInput(source);
    }

    static Object applyPath(Object parsed, Object[] steps) {
        Object cur = parsed;
        for (Object step : steps) {
            if (step instanceof Keyword kw) {
                cur = RT.get(cur, kw);
            } else {
                cur = RT.nth(cur, ((Integer) step).intValue());
            }
        }
        return cur;
    }

    static Object selectKeysHost(Object map, Keyword[] keys) {
        List<Object> kvs = new ArrayList<>(keys.length * 2);
        for (Keyword key : keys) {
            Object e = RT.find(map, key);
            if (e instanceof clojure.lang.IMapEntry me) {
                kvs.add(me.key());
                kvs.add(me.val());
            }
        }
        return RT.map(kvs.toArray());
    }

    static final class TrieBuilder {
        private final Node root = new Node();
        private int slots;

        int internPath(Object[] steps) {
            Node n = root;
            for (Object step : steps) {
                if (n.slot >= 0) {
                    return -1;
                }
                n = n.child(step);
            }
            if (!n.children.isEmpty()) {
                return -1;
            }
            if (n.slot >= 0) {
                return n.slot;
            }
            n.slot = slots++;
            return n.slot;
        }

        int slotCount() {
            return slots;
        }

        JsonParser.TrieNode finish() {
            return root.toTrie();
        }

        private static final class Node {
            final Map<Object, Node> children = new LinkedHashMap<>();
            int slot = -1;

            Node child(Object step) {
                return children.computeIfAbsent(step, k -> new Node());
            }

            JsonParser.TrieNode toTrie() {
                JsonParser.TrieEdge[] edges = new JsonParser.TrieEdge[children.size()];
                int i = 0;
                for (Map.Entry<Object, Node> e : children.entrySet()) {
                    Object key = e.getKey();
                    JsonParser.TrieNode child = e.getValue().toTrie();
                    if (key instanceof Keyword kw) {
                        byte[] utf8 = kw.sym.toString().getBytes(StandardCharsets.UTF_8);
                        edges[i++] = new JsonParser.TrieEdge(utf8, -1, child);
                    } else {
                        edges[i++] = new JsonParser.TrieEdge(null, ((Integer) key).intValue(), child);
                    }
                }
                return new JsonParser.TrieNode(edges, slot);
            }
        }
    }
}
