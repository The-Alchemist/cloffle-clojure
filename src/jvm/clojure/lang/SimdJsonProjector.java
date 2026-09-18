/**
 * Copyright (c) Rich Hickey. All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php).
 */
package clojure.lang;

import org.cloffle.trufflejson.JsonScan;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.InternalByteArray;
import com.oracle.truffle.api.strings.TruffleString;
import org.simdjson.JsonParsingException;
import org.simdjson.ProjectionSchema;
import org.simdjson.ProjectionTarget;
import org.simdjson.SimdJsonParser;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Experimental SIMD stage-1 plus fixed-schema projection. All unsupported inputs and schema
 * features throw {@link Fallback}; {@code JsonTypedProjectPlan} then retries the custom scanner.
 */
public final class SimdJsonProjector {
    private static final int CAPACITY = Integer.getInteger(
            "cloffle.json.simdjson.capacity", 1 << 20);
    private static final ThreadLocal<SimdJsonParser> PARSERS =
            ThreadLocal.withInitial(() -> new SimdJsonParser(CAPACITY, JsonParser.MAX_DEPTH));

    private SimdJsonProjector() {
    }

    public static final class Fallback extends RuntimeException {
        Fallback(String message) {
            super(message);
        }

        Fallback(Throwable cause) {
            super(cause);
        }
    }

    @TruffleBoundary
    public static ProjectionSchema compile(JsonScan.TypedTrieNode root) {
        return compileNode(root);
    }

    @TruffleBoundary
    public static JsonScan.TypedScanResult project(
            Object source, ProjectionSchema schema, JsonScan.TypedLeaf[] leaves,
            boolean firstWins) {
        return scan(source, schema, leaves, firstWins);
    }

    @CompilerDirectives.EarlyEscapeAnalysis
    public static JsonScan.TypedScanResult projectPartialEvaluated(
            byte[] source, ProjectionSchema schema, JsonScan.TypedLeaf[] leaves,
            boolean firstWins) {
        return scan(source, schema, leaves, firstWins);
    }

    private static JsonScan.TypedScanResult scan(
            Object source, ProjectionSchema schema, JsonScan.TypedLeaf[] leaves,
            boolean firstWins) {
        SourceWindow window = window(source);
        JsonScan.TypedScanResult result =
                new JsonScan.TypedScanResult(window.bytes, leaves.length);
        result.truffleSource = window.truffle;
        result.truffleOffset = window.truffleOffset;
        try {
            PARSERS.get().project(window.bytes, window.length, schema,
                    new Target(result), firstWins);
            return result;
        } catch (JsonParsingException | IllegalArgumentException failure) {
            throw new Fallback(failure);
        }
    }

    private record SourceWindow(byte[] bytes, int length, TruffleString truffle,
                                int truffleOffset) {
    }

    private static SourceWindow window(Object source) {
        if (source instanceof byte[] bytes) {
            return new SourceWindow(bytes, bytes.length, null, 0);
        }
        if (source instanceof ByteBuffer buffer) {
            ByteBuffer duplicate = buffer.duplicate();
            if (duplicate.hasArray()
                    && duplicate.arrayOffset() + duplicate.position() == 0) {
                return new SourceWindow(duplicate.array(), duplicate.remaining(), null, 0);
            }
            throw new Fallback("SIMD projection requires a zero-offset heap byte window");
        }
        if (source instanceof TruffleString string) {
            TruffleString utf8 = string.switchEncodingUncached(TruffleString.Encoding.UTF_8);
            if (!utf8.isManaged()) {
                throw new Fallback("Native TruffleString has no stable byte array");
            }
            TruffleString.MaterializeNode.getUncached()
                    .execute(utf8, TruffleString.Encoding.UTF_8);
            InternalByteArray internal = TruffleString.GetInternalByteArrayNode.getUncached()
                    .execute(utf8, TruffleString.Encoding.UTF_8);
            if (internal.getOffset() != 0) {
                throw new Fallback("SIMD projection requires a zero-offset TruffleString");
            }
            return new SourceWindow(internal.getArray(), internal.getEnd(), utf8, 0);
        }
        if (source instanceof CharSequence) {
            throw new Fallback("SIMD projection requires byte-backed input");
        }
        throw new IllegalArgumentException("JSON source must be String, byte[], ByteBuffer, "
                + "CharSequence, or TruffleString");
    }

    private static ProjectionSchema compileNode(JsonScan.TypedTrieNode node) {
        if (node.slot >= 0) {
            return ProjectionSchema.leaf(kind(node.leaf), node.slot, node.leaf.nullable);
        }
        return switch (node.containerKind) {
            case JsonScan.TypedTrieNode.OBJECT_ONLY -> compileObject(node);
            case JsonScan.TypedTrieNode.ARRAY_ONLY -> compileArray(node);
            case JsonScan.TypedTrieNode.EITHER ->
                    ProjectionSchema.either(compileObject(node), compileArray(node));
            default -> throw new Fallback("Unsupported empty projection node");
        };
    }

    private static ProjectionSchema compileObject(JsonScan.TypedTrieNode node) {
        List<String> names = new ArrayList<>();
        List<ProjectionSchema> children = new ArrayList<>();
        for (JsonScan.TypedTrieEdge edge : node.edges) {
            if (edge != null && edge.utf8 != null) {
                names.add(edge.name);
                children.add(compileNode(edge.child));
            }
        }
        return ProjectionSchema.object(names.toArray(String[]::new),
                children.toArray(ProjectionSchema[]::new));
    }

    private static ProjectionSchema compileArray(JsonScan.TypedTrieNode node) {
        record Indexed(int index, ProjectionSchema child) {
        }
        List<Indexed> indexed = new ArrayList<>();
        for (JsonScan.TypedTrieEdge edge : node.edges) {
            if (edge != null && edge.utf8 == null) {
                indexed.add(new Indexed(edge.index, compileNode(edge.child)));
            }
        }
        indexed.sort(Comparator.comparingInt(Indexed::index));
        int[] indexes = new int[indexed.size()];
        ProjectionSchema[] children = new ProjectionSchema[indexed.size()];
        for (int i = 0; i < indexed.size(); i++) {
            indexes[i] = indexed.get(i).index;
            children[i] = indexed.get(i).child;
        }
        return ProjectionSchema.array(indexes, children);
    }

    private static ProjectionSchema.Kind kind(JsonScan.TypedLeaf leaf) {
        return switch (leaf.kind) {
            case JsonScan.TypedLeaf.INT -> ProjectionSchema.Kind.INT;
            case JsonScan.TypedLeaf.LONG -> ProjectionSchema.Kind.LONG;
            case JsonScan.TypedLeaf.DOUBLE -> ProjectionSchema.Kind.DOUBLE;
            case JsonScan.TypedLeaf.BOOLEAN -> ProjectionSchema.Kind.BOOLEAN;
            case JsonScan.TypedLeaf.STRING, JsonScan.TypedLeaf.TRUFFLE_STRING ->
                    ProjectionSchema.Kind.STRING;
            case JsonScan.TypedLeaf.NULL -> ProjectionSchema.Kind.NULL;
            case JsonScan.TypedLeaf.ANY -> ProjectionSchema.Kind.ANY;
            default -> throw new Fallback("Unsupported SIMD projection leaf");
        };
    }

    private static final class Target implements ProjectionTarget {
        private final JsonScan.TypedScanResult result;

        Target(JsonScan.TypedScanResult result) {
            this.result = result;
        }

        @Override
        public int slotCount() {
            return result.states.length;
        }

        @Override
        public boolean isMissing(int slot) {
            return result.states[slot] == JsonScan.TypedScanResult.MISSING;
        }

        @Override
        public void setValue(int slot, Object value) {
            result.values[slot] = value;
            result.states[slot] = JsonScan.TypedScanResult.VALUE;
        }

        @Override
        public void setSlice(int slot, int offset, int length, boolean escaped) {
            result.starts[slot] = offset;
            result.lengths[slot] = length;
            result.states[slot] = escaped
                    ? JsonScan.TypedScanResult.ESCAPED_SLICE
                    : JsonScan.TypedScanResult.SLICE;
        }
    }
}
