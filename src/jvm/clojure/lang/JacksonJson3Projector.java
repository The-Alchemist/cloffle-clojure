/**
 * Copyright (c) Rich Hickey. All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php).
 */
package clojure.lang;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.InternalByteArray;
import com.oracle.truffle.api.strings.TruffleString;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.TokenStreamFactory;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.sym.PropertyNameMatcher;
import tools.jackson.core.util.Named;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Experimental Jackson 3 scanner: fused {@code nextNameMatchAndToken} plus raw
 * byte-string slices that {@link net.javacrumbs.cloffle.bytecode.JsonTypedProjectPlan}
 * already knows how to decode. Failures are {@link Fallback}; the caller retries
 * the custom scanner.
 */
public final class JacksonJson3Projector {
    /** Matcher-driven schemas do not need per-document name canonicalization. */
    private static final JsonFactory FACTORY = JsonFactory.builder()
            .disable(TokenStreamFactory.Feature.CANONICALIZE_PROPERTY_NAMES)
            .build();
    private static final byte[] NO_SOURCE = new byte[0];

    private JacksonJson3Projector() {
    }

    public static final class Fallback extends RuntimeException {
        Fallback(Throwable cause) {
            super(cause);
        }

        Fallback(String message) {
            super(message);
        }
    }

    @TruffleBoundary
    public static JsonParser.TypedScanResult project(
            Object source, JsonParser.TypedTrieNode root, JsonParser.TypedLeaf[] leaves,
            boolean firstWins) {
        return scan(source, root, leaves, firstWins);
    }

    /**
     * Same scan without a Truffle boundary so PEA can see parser locals when the
     * caller is a PE-visible guest operation. Gated separately; may bloat IR.
     */
    @CompilerDirectives.EarlyEscapeAnalysis
    public static JsonParser.TypedScanResult projectPartialEvaluated(
            byte[] source, JsonParser.TypedTrieNode root, JsonParser.TypedLeaf[] leaves,
            boolean firstWins) {
        return scan(source, root, leaves, firstWins);
    }

    private static JsonParser.TypedScanResult scan(
            Object source, JsonParser.TypedTrieNode root, JsonParser.TypedLeaf[] leaves,
            boolean firstWins) {
        SourceWindow window = window(source);
        try (tools.jackson.core.JsonParser parser = parser(window)) {
            State state = new State(parser, leaves.length, firstWins, window);
            JsonToken token = parser.nextToken();
            if (token == null) {
                throw new Fallback("Empty JSON");
            }
            state.prepare(root);
            state.visit(root, 0);
            if (!state.complete) {
                if (parser.nextToken() != null) {
                    throw new Fallback("Trailing content");
                }
            }
            return state.result;
        } catch (Fallback fallback) {
            throw fallback;
        } catch (RuntimeException failure) {
            throw new Fallback(failure);
        }
    }

    private record SourceWindow(byte[] bytes, int offset, int length, TruffleString truffle,
                                int truffleOffset) {
        boolean hasBytes() {
            return bytes != null;
        }
    }

    private static SourceWindow window(Object source) {
        if (source instanceof byte[] bytes) {
            return new SourceWindow(bytes, 0, bytes.length, null, 0);
        }
        if (source instanceof ByteBuffer buffer) {
            ByteBuffer duplicate = buffer.duplicate();
            if (duplicate.hasArray()) {
                return new SourceWindow(duplicate.array(),
                        duplicate.arrayOffset() + duplicate.position(),
                        duplicate.remaining(), null, 0);
            }
            byte[] copy = new byte[duplicate.remaining()];
            duplicate.get(copy);
            return new SourceWindow(copy, 0, copy.length, null, 0);
        }
        if (source instanceof TruffleString string) {
            TruffleString utf8 = string.switchEncodingUncached(TruffleString.Encoding.UTF_8);
            if (!utf8.isManaged()) {
                throw new Fallback("Native TruffleString has no stable byte[] window");
            }
            TruffleString.MaterializeNode.getUncached()
                    .execute(utf8, TruffleString.Encoding.UTF_8);
            InternalByteArray internal = TruffleString.GetInternalByteArrayNode.getUncached()
                    .execute(utf8, TruffleString.Encoding.UTF_8);
            return new SourceWindow(internal.getArray(), internal.getOffset(),
                    internal.getEnd() - internal.getOffset(), utf8, internal.getOffset());
        }
        if (source instanceof CharSequence) {
            throw new Fallback("Jackson 3 slice path requires byte[] or TruffleString");
        }
        throw new IllegalArgumentException("JSON source must be String, byte[], ByteBuffer, "
                + "CharSequence, or TruffleString");
    }

    private static tools.jackson.core.JsonParser parser(SourceWindow window) throws JacksonException {
        if (!window.hasBytes()) {
            throw new Fallback("No byte window");
        }
        return FACTORY.createParser(ObjectReadContext.empty(),
                window.bytes, window.offset, window.length);
    }

    private static final class ObjectSchema {
        final PropertyNameMatcher matcher;
        final JsonParser.TypedTrieEdge[] keywordEdges;

        ObjectSchema(PropertyNameMatcher matcher, JsonParser.TypedTrieEdge[] keywordEdges) {
            this.matcher = matcher;
            this.keywordEdges = keywordEdges;
        }
    }

    private static final class State {
        final tools.jackson.core.JsonParser parser;
        final JsonParser.TypedScanResult result;
        final boolean firstWins;
        final IdentityHashMap<JsonParser.TypedTrieNode, ObjectSchema> schemas = new IdentityHashMap<>();
        int pending;
        boolean complete;

        State(tools.jackson.core.JsonParser parser, int slots, boolean firstWins,
              SourceWindow window) {
            this.parser = parser;
            this.result = new JsonParser.TypedScanResult(
                    window.bytes != null ? window.bytes : NO_SOURCE, slots);
            this.result.truffleSource = window.truffle;
            this.result.truffleOffset = window.truffleOffset;
            this.firstWins = firstWins;
            this.pending = slots;
            this.complete = slots == 0;
        }

        void prepare(JsonParser.TypedTrieNode node) {
            if (node == null || schemas.containsKey(node)) {
                return;
            }
            List<Named> names = new ArrayList<>();
            List<JsonParser.TypedTrieEdge> keywordEdges = new ArrayList<>();
            if (node.edges != null) {
                for (JsonParser.TypedTrieEdge edge : node.edges) {
                    if (edge != null && edge.isKeyword()) {
                        names.add(Named.fromString(edge.name));
                        keywordEdges.add(edge);
                    }
                    if (edge != null) {
                        prepare(edge.child);
                    }
                }
            }
            if (!names.isEmpty()) {
                schemas.put(node, new ObjectSchema(
                        FACTORY.constructNameMatcher(names, false),
                        keywordEdges.toArray(new JsonParser.TypedTrieEdge[0])));
            } else {
                schemas.put(node, null);
            }
        }

        void visit(JsonParser.TypedTrieNode node, int depth) throws JacksonException {
            if (depth > JsonParser.MAX_DEPTH) {
                throw new Fallback("Nesting too deep");
            }
            if (node.slot >= 0) {
                capture(node.slot, node.leaf, depth);
                return;
            }
            switch (node.containerKind) {
                case JsonParser.TypedTrieNode.OBJECT_ONLY -> visitObject(node, depth + 1);
                case JsonParser.TypedTrieNode.ARRAY_ONLY -> visitArray(node, depth + 1);
                case JsonParser.TypedTrieNode.EITHER -> {
                    if (parser.currentToken() == JsonToken.START_OBJECT) {
                        visitObject(node, depth + 1);
                    } else if (parser.currentToken() == JsonToken.START_ARRAY) {
                        visitArray(node, depth + 1);
                    } else {
                        throw new Fallback("Expected object or array");
                    }
                }
                default -> parser.skipChildren();
            }
        }

        void visitObject(JsonParser.TypedTrieNode node, int depth) throws JacksonException {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                throw new Fallback("Expected object");
            }
            ObjectSchema schema = schemas.get(node);
            if (schema == null) {
                parser.skipChildren();
                return;
            }
            while (!complete) {
                int match = parser.nextNameMatchAndToken(schema.matcher);
                if (match == PropertyNameMatcher.MATCH_END_OBJECT) {
                    return;
                }
                if (match == PropertyNameMatcher.MATCH_UNKNOWN_NAME) {
                    if (parser.nextToken() == null) {
                        throw new Fallback("Unclosed object");
                    }
                    parser.skipChildren();
                    continue;
                }
                if (match == PropertyNameMatcher.MATCH_ODD_TOKEN || match < 0) {
                    throw new Fallback("Expected object key");
                }
                visit(schema.keywordEdges[match].child, depth);
                if (complete) {
                    return;
                }
                if (firstWins && isSubtreeComplete(node)) {
                    skipObjectRemainder();
                    return;
                }
            }
        }

        void visitArray(JsonParser.TypedTrieNode node, int depth) throws JacksonException {
            if (parser.currentToken() != JsonToken.START_ARRAY) {
                throw new Fallback("Expected array");
            }
            int index = 0;
            while (!complete && parser.nextToken() != JsonToken.END_ARRAY) {
                JsonParser.TypedTrieEdge edge = indexEdge(node, index++);
                if (edge == null) {
                    parser.skipChildren();
                } else {
                    visit(edge.child, depth);
                    if (complete) {
                        return;
                    }
                    if (firstWins && isSubtreeComplete(node)) {
                        skipArrayRemainder();
                        return;
                    }
                }
            }
        }

        void capture(int slot, JsonParser.TypedLeaf leaf, int depth) throws JacksonException {
            if (firstWins && result.states[slot] != JsonParser.TypedScanResult.MISSING) {
                parser.skipChildren();
                return;
            }
            JsonToken token = parser.currentToken();
            if (leaf.nullable && token == JsonToken.VALUE_NULL) {
                result.values[slot] = null;
                result.states[slot] = JsonParser.TypedScanResult.VALUE;
                markFilled();
                return;
            }
            switch (leaf.kind) {
                case JsonParser.TypedLeaf.INT -> {
                    require(token == JsonToken.VALUE_NUMBER_INT, "Expected integer");
                    result.values[slot] = Integer.valueOf(parser.getIntValue());
                    result.states[slot] = JsonParser.TypedScanResult.VALUE;
                }
                case JsonParser.TypedLeaf.LONG -> {
                    require(token == JsonToken.VALUE_NUMBER_INT, "Expected integer");
                    result.values[slot] = Long.valueOf(parser.getLongValue());
                    result.states[slot] = JsonParser.TypedScanResult.VALUE;
                }
                case JsonParser.TypedLeaf.DOUBLE -> {
                    require(token == JsonToken.VALUE_NUMBER_INT
                            || token == JsonToken.VALUE_NUMBER_FLOAT, "Expected number");
                    result.values[slot] = Double.valueOf(parser.getDoubleValue());
                    result.states[slot] = JsonParser.TypedScanResult.VALUE;
                }
                case JsonParser.TypedLeaf.BOOLEAN -> {
                    require(token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE,
                            "Expected boolean");
                    result.values[slot] = Boolean.valueOf(token == JsonToken.VALUE_TRUE);
                    result.states[slot] = JsonParser.TypedScanResult.VALUE;
                }
                case JsonParser.TypedLeaf.NULL -> {
                    require(token == JsonToken.VALUE_NULL, "Expected null");
                    result.values[slot] = null;
                    result.states[slot] = JsonParser.TypedScanResult.VALUE;
                }
                case JsonParser.TypedLeaf.STRING, JsonParser.TypedLeaf.TRUFFLE_STRING,
                     JsonParser.TypedLeaf.ANY -> captureStringOrAny(slot, leaf, token, depth);
                case JsonParser.TypedLeaf.DYNAMIC -> {
                    result.values[slot] = dynamic(leaf.dynamic, depth);
                    result.states[slot] = JsonParser.TypedScanResult.VALUE;
                }
                default -> throw new Fallback("Unsupported Jackson typed leaf");
            }
            markFilled();
        }

        private void captureStringOrAny(int slot, JsonParser.TypedLeaf leaf, JsonToken token,
                                        int depth) throws JacksonException {
            if (leaf.kind == JsonParser.TypedLeaf.ANY) {
                if (token == JsonToken.VALUE_NULL) {
                    result.values[slot] = null;
                    result.states[slot] = JsonParser.TypedScanResult.VALUE;
                    return;
                }
                if (token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE) {
                    result.values[slot] = Boolean.valueOf(token == JsonToken.VALUE_TRUE);
                    result.states[slot] = JsonParser.TypedScanResult.VALUE;
                    return;
                }
                if (token == JsonToken.VALUE_NUMBER_INT) {
                    result.values[slot] = Long.valueOf(parser.getLongValue());
                    result.states[slot] = JsonParser.TypedScanResult.VALUE;
                    return;
                }
                if (token == JsonToken.VALUE_NUMBER_FLOAT) {
                    result.values[slot] = Double.valueOf(parser.getDoubleValue());
                    result.states[slot] = JsonParser.TypedScanResult.VALUE;
                    return;
                }
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    throw new Fallback("ANY container uses the custom scanner");
                }
            }
            require(token == JsonToken.VALUE_STRING, "Expected string");
            if (parser.finishRawStringSlice()) {
                if (result.source != parser.getRawStringBuffer()) {
                    throw new Fallback("String slice is not the projection source array");
                }
                result.starts[slot] = parser.getRawStringOffset();
                result.lengths[slot] = parser.getRawStringLength();
                result.states[slot] = parser.isRawStringEscaped()
                        ? JsonParser.TypedScanResult.ESCAPED_SLICE
                        : JsonParser.TypedScanResult.SLICE;
                return;
            }
            if (leaf.kind == JsonParser.TypedLeaf.TRUFFLE_STRING) {
                throw new Fallback("No raw slice for TruffleString leaf");
            }
            result.values[slot] = parser.getString();
            result.states[slot] = JsonParser.TypedScanResult.VALUE;
        }

        Object dynamic(JsonParser.TypedValueNode node, int depth) throws JacksonException {
            if (depth > JsonParser.MAX_DEPTH) {
                throw new Fallback("Nesting too deep");
            }
            if (node.kind == JsonParser.TypedValueNode.VECTOR) {
                require(parser.currentToken() == JsonToken.START_ARRAY, "Expected array");
                ArrayList<Object> values = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (parser.currentToken() == null) {
                        throw new Fallback("Unclosed array");
                    }
                    values.add(dynamic(node.child, depth + 1));
                }
                return RT.vector(values.toArray());
            }
            JsonParser.TypedLeaf leaf = node.leaf;
            JsonToken token = parser.currentToken();
            if (leaf.nullable && token == JsonToken.VALUE_NULL) {
                return null;
            }
            return switch (leaf.kind) {
                case JsonParser.TypedLeaf.INT -> {
                    require(token == JsonToken.VALUE_NUMBER_INT, "Expected integer");
                    yield Integer.valueOf(parser.getIntValue());
                }
                case JsonParser.TypedLeaf.LONG -> {
                    require(token == JsonToken.VALUE_NUMBER_INT, "Expected integer");
                    yield Long.valueOf(parser.getLongValue());
                }
                case JsonParser.TypedLeaf.DOUBLE -> {
                    require(token == JsonToken.VALUE_NUMBER_INT
                            || token == JsonToken.VALUE_NUMBER_FLOAT, "Expected number");
                    yield Double.valueOf(parser.getDoubleValue());
                }
                case JsonParser.TypedLeaf.BOOLEAN -> {
                    require(token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE,
                            "Expected boolean");
                    yield Boolean.valueOf(token == JsonToken.VALUE_TRUE);
                }
                case JsonParser.TypedLeaf.STRING -> {
                    require(token == JsonToken.VALUE_STRING, "Expected string");
                    if (parser.finishRawStringSlice()) {
                        throw new Fallback("Dynamic string slices decode in the plan");
                    }
                    yield parser.getString();
                }
                case JsonParser.TypedLeaf.NULL -> {
                    require(token == JsonToken.VALUE_NULL, "Expected null");
                    yield null;
                }
                default -> throw new Fallback("Unsupported Jackson dynamic leaf");
            };
        }

        private void markFilled() {
            if (firstWins && --pending == 0) {
                complete = true;
            }
        }

        private boolean isSubtreeComplete(JsonParser.TypedTrieNode node) {
            int[] slots = node.subtreeSlots;
            for (int slot : slots) {
                if (result.states[slot] == JsonParser.TypedScanResult.MISSING) {
                    return false;
                }
            }
            return true;
        }

        private void skipObjectRemainder() throws JacksonException {
            while (true) {
                JsonToken token = parser.nextToken();
                if (token == JsonToken.END_OBJECT) {
                    return;
                }
                if (token == null) {
                    throw new Fallback("Unclosed object");
                }
                if (token == JsonToken.PROPERTY_NAME) {
                    if (parser.nextToken() == null) {
                        throw new Fallback("Unclosed object");
                    }
                    parser.skipChildren();
                    continue;
                }
                throw new Fallback("Expected object key");
            }
        }

        private void skipArrayRemainder() throws JacksonException {
            while (true) {
                JsonToken token = parser.nextToken();
                if (token == JsonToken.END_ARRAY) {
                    return;
                }
                if (token == null) {
                    throw new Fallback("Unclosed array");
                }
                parser.skipChildren();
            }
        }

        @CompilerDirectives.EarlyInline
        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new Fallback(message);
            }
        }

        private static JsonParser.TypedTrieEdge indexEdge(
                JsonParser.TypedTrieNode node, int index) {
            for (JsonParser.TypedTrieEdge edge : node.edges) {
                if (edge.utf8 == null && edge.index == index) {
                    return edge;
                }
            }
            return null;
        }
    }
}
