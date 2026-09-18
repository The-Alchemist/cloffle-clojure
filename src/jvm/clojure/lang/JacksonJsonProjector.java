/**
 * Copyright (c) Rich Hickey. All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php).
 */
package clojure.lang;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Experimental Jackson Core scanner for {@link JsonParser.TypedTrieNode} plans.
 *
 * <p>All failures are reported as {@link Fallback}; the caller then retries the custom scanner.
 * This makes Jackson an optimization only, preserving Cloffle's deliberately permissive handling
 * of unselected scalar spelling and its established errors.
 */
public final class JacksonJsonProjector {
    private static final JsonFactory FACTORY = new JsonFactory();
    private static final byte[] NO_SOURCE = new byte[0];

    private JacksonJsonProjector() {
    }

    public static final class Fallback extends RuntimeException {
        Fallback(Throwable cause) {
            super(cause);
        }

        Fallback(String message) {
            super(message);
        }
    }

    public static JsonParser.TypedScanResult project(
            Object source, JsonParser.TypedTrieNode root, JsonParser.TypedLeaf[] leaves,
            boolean firstWins) {
        try (com.fasterxml.jackson.core.JsonParser parser = parser(source)) {
            State state = new State(parser, leaves.length, firstWins, sourceBytes(source));
            JsonToken token = parser.nextToken();
            if (token == null) {
                throw new Fallback("Empty JSON");
            }
            state.visit(root, 0);
            if (!state.complete) {
                if (parser.nextToken() != null) {
                    throw new Fallback("Trailing content");
                }
            }
            return state.result;
        } catch (Fallback fallback) {
            throw fallback;
        } catch (IOException | RuntimeException failure) {
            throw new Fallback(failure);
        }
    }

    private static com.fasterxml.jackson.core.JsonParser parser(Object source) throws IOException {
        if (source instanceof byte[] bytes) {
            return FACTORY.createParser(bytes);
        }
        if (source instanceof ByteBuffer buffer) {
            ByteBuffer duplicate = buffer.duplicate();
            if (duplicate.hasArray()) {
                return FACTORY.createParser(
                        duplicate.array(),
                        duplicate.arrayOffset() + duplicate.position(),
                        duplicate.remaining());
            }
            byte[] bytes = new byte[duplicate.remaining()];
            duplicate.get(bytes);
            return FACTORY.createParser(bytes);
        }
        if (source instanceof String string) {
            return FACTORY.createParser(string);
        }
        if (source instanceof com.oracle.truffle.api.strings.TruffleString string) {
            return FACTORY.createParser(JsonParser.utf8Bytes(string));
        }
        if (source instanceof CharSequence chars) {
            return FACTORY.createParser(chars.toString());
        }
        throw new IllegalArgumentException("JSON source must be String, byte[], ByteBuffer, "
                + "CharSequence, or TruffleString");
    }

    private static byte[] sourceBytes(Object source) {
        return source instanceof byte[] bytes ? bytes : NO_SOURCE;
    }

    private static final class State {
        final com.fasterxml.jackson.core.JsonParser parser;
        final JsonParser.TypedScanResult result;
        final boolean firstWins;
        int pending;
        boolean complete;

        State(com.fasterxml.jackson.core.JsonParser parser, int slots, boolean firstWins,
              byte[] source) {
            this.parser = parser;
            this.result = new JsonParser.TypedScanResult(source, slots);
            this.firstWins = firstWins;
            this.pending = slots;
            this.complete = slots == 0;
        }

        void visit(JsonParser.TypedTrieNode node, int depth) throws IOException {
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
                    // A pointer token is both a name and an index; the input decides which.
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

        void visitObject(JsonParser.TypedTrieNode node, int depth) throws IOException {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                throw new Fallback("Expected object");
            }
            while (!complete && parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    throw new Fallback("Expected object key");
                }
                JsonParser.TypedTrieEdge edge = keywordEdge(node, parser.currentName());
                if (parser.nextToken() == null) {
                    throw new Fallback("Unclosed object");
                }
                if (edge == null) {
                    parser.skipChildren();
                } else {
                    visit(edge.child, depth);
                }
            }
        }

        void visitArray(JsonParser.TypedTrieNode node, int depth) throws IOException {
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
                }
            }
        }

        void capture(int slot, JsonParser.TypedLeaf leaf, int depth) throws IOException {
            if (firstWins && result.states[slot] != JsonParser.TypedScanResult.MISSING) {
                parser.skipChildren();
                return;
            }
            JsonToken token = parser.currentToken();
            Object value;
            if (leaf.nullable && token == JsonToken.VALUE_NULL) {
                value = null;
            } else {
                value = switch (leaf.kind) {
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
                        yield parser.getText();
                    }
                    case JsonParser.TypedLeaf.NULL -> {
                        require(token == JsonToken.VALUE_NULL, "Expected null");
                        yield null;
                    }
                    case JsonParser.TypedLeaf.DYNAMIC -> dynamic(leaf.dynamic, depth);
                    default -> throw new Fallback("Unsupported Jackson typed leaf");
                };
            }
            result.values[slot] = value;
            result.states[slot] = JsonParser.TypedScanResult.VALUE;
            if (firstWins && --pending == 0) {
                complete = true;
            }
        }

        Object dynamic(JsonParser.TypedValueNode node, int depth) throws IOException {
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
                    yield parser.getText();
                }
                case JsonParser.TypedLeaf.NULL -> {
                    require(token == JsonToken.VALUE_NULL, "Expected null");
                    yield null;
                }
                default -> throw new Fallback("Unsupported Jackson dynamic leaf");
            };
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new Fallback(message);
            }
        }

        private static JsonParser.TypedTrieEdge keywordEdge(
                JsonParser.TypedTrieNode node, String name) {
            for (JsonParser.TypedTrieEdge edge : node.edges) {
                if (edge.utf8 != null && edge.name.equals(name)) {
                    return edge;
                }
            }
            return null;
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
