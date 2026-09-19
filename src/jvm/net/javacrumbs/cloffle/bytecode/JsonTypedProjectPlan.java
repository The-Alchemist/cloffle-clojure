package net.javacrumbs.cloffle.bytecode;

import org.cloffle.trufflejson.JsonScan;
import org.cloffle.trufflejson.JsonScanV1ColdError;
import org.cloffle.trufflejson.JsonScanV2StaticSkip;
import org.cloffle.trufflejson.JsonScanV3BytesOnly;

import clojure.lang.IMapEntry;
import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentVector;
import clojure.lang.ISeq;
import clojure.lang.JacksonJson3Projector;
import clojure.lang.JacksonJsonProjector;
import clojure.lang.JsonParser;
import clojure.lang.Keyword;
import clojure.lang.MapShape;
import clojure.lang.PersistentShapeMap;
import clojure.lang.PersistentTuple;
import clojure.lang.RT;
import clojure.lang.SimdJsonProjector;
import clojure.lang.Var;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.api.strings.TruffleStringBuilder;
import org.simdjson.ProjectionSchema;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, compile-time schema projection plan. Malli vectors and JSON Schema maps compile to
 * the same trie; neither library runs on the scan path.
 */
public final class JsonTypedProjectPlan {
    private static final Keyword MAP = Keyword.intern("map");
    private static final Keyword VECTOR = Keyword.intern("vector");
    private static final Keyword TUPLE = Keyword.intern("tuple");
    private static final Keyword INDEXES = Keyword.intern("cloffle", "indexes");
    private static final Keyword MAYBE = Keyword.intern("maybe");
    private static final Keyword OPTIONAL = Keyword.intern("optional");
    private static final Keyword DEFAULT = Keyword.intern("default");

    /**
     * Opt-in: run the Jackson 3 projector without a Truffle boundary so PEA can
     * see parser locals. Off by default; graph size may explode.
     */
    private static final boolean PE_JACKSON3 =
            Boolean.getBoolean("cloffle.json.jackson3.pe-scan");
    private static final boolean PE_SIMDJSON =
            Boolean.getBoolean("cloffle.json.simdjson.pe-scan");
    private static final Keyword MATERIALIZE = Keyword.intern("cloffle", "materialize");
    private static final Keyword STRINGS = Keyword.intern("cloffle", "strings");
    private static final Keyword JAVA = Keyword.intern("java");
    private static final Keyword TRUFFLE = Keyword.intern("truffle");
    private static final Keyword DUPLICATES = Keyword.intern("cloffle", "duplicates");
    private static final Keyword FIRST = Keyword.intern("first");
    private static final Keyword LAST = Keyword.intern("last");
    private static final Keyword BACKEND = Keyword.intern("cloffle", "backend");
    private static final Keyword CUSTOM = Keyword.intern("custom");
    private static final Keyword JACKSON = Keyword.intern("jackson");
    private static final Keyword JACKSON3 = Keyword.intern("jackson3");
    private static final Keyword SIMDJSON = Keyword.intern("simdjson");
    /** PEA experiment selector: {@code :baseline} (default), {@code :cold-error}, {@code :static-skip}, {@code :bytes-only}. */
    private static final Keyword SCANNER = Keyword.intern("cloffle", "scanner");
    private static final Keyword SCANNER_BASELINE = Keyword.intern("baseline");
    private static final Keyword SCANNER_COLD_ERROR = Keyword.intern("cold-error");
    private static final Keyword SCANNER_STATIC_SKIP = Keyword.intern("static-skip");
    private static final Keyword SCANNER_BYTES_ONLY = Keyword.intern("bytes-only");
    static final int SCANNER_KIND_BASELINE = 0;
    static final int SCANNER_KIND_COLD_ERROR = 1;
    static final int SCANNER_KIND_STATIC_SKIP = 2;
    static final int SCANNER_KIND_BYTES_ONLY = 3;
    private static final Keyword JS_TYPE = Keyword.intern("type");
    private static final Keyword JS_PROPERTIES = Keyword.intern("properties");
    private static final Keyword JS_REQUIRED = Keyword.intern("required");
    private static final Keyword JS_ITEMS = Keyword.intern("items");
    private static final Keyword JS_PREFIX_ITEMS = Keyword.intern("prefixItems");
    private static final Keyword JS_DEFAULT = Keyword.intern("default");

    final Var projectVar;
    final Object schema;
    final Object options;
    final boolean firstWins;
    final boolean jacksonBackend;
    final boolean jackson3Backend;
    final boolean simdjsonBackend;
    final ProjectionSchema simdjsonSchema;
    final int scannerKind;
    final JsonScan.TypedTrieNode root;
    @CompilerDirectives.CompilationFinal(dimensions = 1) final JsonScan.TypedLeaf[] leaves;
    final OutputNode output;
    @CompilerDirectives.CompilationFinal(dimensions = 1)
    final MaterializeStep[] materializeSteps;
    public final MapShape outShape;
    public final int outKeysLength;
    @CompilerDirectives.CompilationFinal(dimensions = 1) final EntryOutput[] outEntries;

    private JsonTypedProjectPlan(Var projectVar, Object schema, Object options, boolean firstWins,
                                 boolean jacksonBackend, boolean jackson3Backend,
                                 boolean simdjsonBackend, int scannerKind,
                                 JsonScan.TypedTrieNode root,
                                 JsonScan.TypedLeaf[] leaves, OutputNode output) {
        this.projectVar = projectVar;
        this.schema = schema;
        this.options = options;
        this.firstWins = firstWins;
        this.jacksonBackend = jacksonBackend;
        this.jackson3Backend = jackson3Backend;
        this.root = root;
        this.simdjsonBackend = simdjsonBackend;
        this.simdjsonSchema = simdjsonBackend ? compileSimdJson(root) : null;
        this.scannerKind = scannerKind;
        this.leaves = leaves;
        this.output = output;
        this.materializeSteps = MaterializeStep.compile(output);
        if (output instanceof MapOutput mapOutput && mapOutput.shape != null) {
            this.outShape = mapOutput.shape;
            this.outKeysLength = mapOutput.keys.length;
            this.outEntries = mapOutput.entries;
        } else {
            this.outShape = null;
            this.outKeysLength = 0;
            this.outEntries = null;
        }
    }

    private static ProjectionSchema compileSimdJson(JsonScan.TypedTrieNode root) {
        try {
            return SimdJsonProjector.compile(root);
        } catch (SimdJsonProjector.Fallback ignored) {
            return null;
        }
    }

    public Object buildEntry(int i, Object[] slots) {
        return outEntries[i].build(slots);
    }

    public static JsonTypedProjectPlan compile(Var projectVar, Object schema) {
        return compile(projectVar, schema, null);
    }

    public static JsonTypedProjectPlan compile(Var projectVar, Object schema, Object options) {
        PlanOptions parsed = PlanOptions.from(options);
        Compiler c = new Compiler(parsed.stringKind);
        OutputNode output = c.compile(schema, new ArrayList<>(), EntryOptions.REQUIRED);
        JsonScan.TypedLeaf[] leaves = c.leaves.toArray(new JsonScan.TypedLeaf[0]);
        boolean jacksonBackend = parsed.jacksonBackend && supportsJackson(leaves);
        boolean jackson3Backend = parsed.jackson3Backend;
        return new JsonTypedProjectPlan(
                projectVar, schema, options, parsed.firstWins, jacksonBackend, jackson3Backend,
                parsed.simdjsonBackend, parsed.scannerKind,
                c.root.toTrie(),
                leaves, output);
    }

    /**
     * Compile a set of RFC 6901 pointers into the same trie a schema produces, so all of them
     * resolve in one early-exiting scan. Every target is untyped, since a pointer names a position
     * and not a type.
     */
    public static JsonTypedProjectPlan compileSelect(Var selectVar, Object pointers,
                                                     Object options) {
        PlanOptions parsed = PlanOptions.from(options);
        Compiler c = new Compiler(parsed.stringKind);
        OutputNode output = c.compileSelect(pointers);
        JsonScan.TypedLeaf[] leaves = c.leaves.toArray(new JsonScan.TypedLeaf[0]);
        return new JsonTypedProjectPlan(
                selectVar, pointers, options, parsed.firstWins, false, parsed.jackson3Backend,
                parsed.simdjsonBackend, parsed.scannerKind,
                c.root.toTrie(),
                leaves, output);
    }

    /** Dynamic entry point used when the compiler cannot see a constant pointer vector. */
    @TruffleBoundary
    public static Object select(Object source, Object pointers) {
        return select(source, pointers, null);
    }

    @TruffleBoundary
    public static Object select(Object source, Object pointers, Object options) {
        JsonTypedProjectPlan plan = compileSelect(null, pointers, options);
        JsonScan.TypedScanResult scan = plan.scan(source);
        decodeUncached(scan, plan.leaves);
        return plan.build(scan.values);
    }

    private record PlanOptions(int stringKind, boolean firstWins, boolean jacksonBackend,
                               boolean jackson3Backend, boolean simdjsonBackend, int scannerKind) {
        static PlanOptions from(Object options) {
            int stringKind = JsonScan.TypedLeaf.STRING;
            boolean firstWins = true;
            boolean jacksonBackend = false;
            boolean jackson3Backend = false;
            boolean simdjsonBackend = false;
            int scannerKind = SCANNER_KIND_BASELINE;
            if (options == null) {
                return new PlanOptions(stringKind, firstWins, jacksonBackend, jackson3Backend,
                        simdjsonBackend, scannerKind);
            }
            if (!(options instanceof IPersistentMap map)) {
                throw new IllegalArgumentException("JSON projection options must be a map");
            }
            Object representation = map.valAt(STRINGS, JAVA);
            if (TRUFFLE.equals(representation)) {
                stringKind = JsonScan.TypedLeaf.TRUFFLE_STRING;
            } else if (!JAVA.equals(representation)) {
                throw new IllegalArgumentException(
                        ":cloffle/strings must be :java or :truffle, got " + representation);
            }
            Object duplicates = map.valAt(DUPLICATES, FIRST);
            if (LAST.equals(duplicates)) {
                firstWins = false;
            } else if (!FIRST.equals(duplicates)) {
                throw new IllegalArgumentException(
                        ":cloffle/duplicates must be :first or :last, got " + duplicates);
            }
            Object backend = map.valAt(BACKEND, CUSTOM);
            if (JACKSON.equals(backend)) {
                jacksonBackend = true;
            } else if (JACKSON3.equals(backend)) {
                jackson3Backend = true;
            } else if (SIMDJSON.equals(backend)) {
                simdjsonBackend = true;
            } else if (!CUSTOM.equals(backend)) {
                throw new IllegalArgumentException(
                        ":cloffle/backend must be :custom, :jackson, :jackson3, or :simdjson, got "
                                + backend);
            }
            Object scanner = map.valAt(SCANNER, SCANNER_BASELINE);
            if (SCANNER_COLD_ERROR.equals(scanner)) {
                scannerKind = SCANNER_KIND_COLD_ERROR;
            } else if (SCANNER_STATIC_SKIP.equals(scanner)) {
                scannerKind = SCANNER_KIND_STATIC_SKIP;
            } else if (SCANNER_BYTES_ONLY.equals(scanner)) {
                scannerKind = SCANNER_KIND_BYTES_ONLY;
            } else if (!SCANNER_BASELINE.equals(scanner)) {
                throw new IllegalArgumentException(
                        ":cloffle/scanner must be :baseline, :cold-error, :static-skip, or :bytes-only, got "
                                + scanner);
            }
            return new PlanOptions(stringKind, firstWins, jacksonBackend, jackson3Backend,
                    simdjsonBackend, scannerKind);
        }
    }

    private static boolean supportsJackson(JsonScan.TypedLeaf[] leaves) {
        for (JsonScan.TypedLeaf leaf : leaves) {
            if (!supportsJackson(leaf)) {
                return false;
            }
        }
        return true;
    }

    private static boolean supportsJackson(JsonScan.TypedLeaf leaf) {
        if (leaf.kind == JsonScan.TypedLeaf.TRUFFLE_STRING
                || leaf.kind == JsonScan.TypedLeaf.ANY) {
            return false;
        }
        return leaf.kind != JsonScan.TypedLeaf.DYNAMIC || supportsJackson(leaf.dynamic);
    }

    private static boolean supportsJackson(JsonScan.TypedValueNode node) {
        if (node.kind == JsonScan.TypedValueNode.VECTOR) {
            return supportsJackson(node.child);
        }
        return supportsJackson(node.leaf);
    }

    public Assumption loweringAssumption() {
        return BytecodeLowering.sanctionedRootAssumption(projectVar);
    }

    public JsonScan.TypedTrieNode root() {
        return root;
    }

    public JsonScan.TypedLeaf[] leaves() {
        return leaves;
    }

    public JsonScan.TypedScanResult scan(Object source) {
        if (simdjsonBackend && simdjsonSchema != null && PE_SIMDJSON
                && source instanceof byte[] bytes) {
            try {
                return SimdJsonProjector.projectPartialEvaluated(
                        bytes, simdjsonSchema, leaves, firstWins);
            } catch (SimdJsonProjector.Fallback ignored) {
                return scanCustom(source);
            }
        }
        if (jackson3Backend && PE_JACKSON3 && source instanceof byte[] bytes) {
            try {
                return JacksonJson3Projector.projectPartialEvaluated(
                        bytes, root, leaves, firstWins);
            } catch (JacksonJson3Projector.Fallback ignored) {
                return scanCustom(source);
            }
        }
        if (!jacksonBackend && !jackson3Backend && !simdjsonBackend
                && source instanceof byte[] bytes) {
            return switch (scannerKind) {
                case SCANNER_KIND_COLD_ERROR -> {
                    try {
                        yield JsonScanV1ColdError.projectBytesPartialEvaluated(
                                bytes, root, leaves, firstWins);
                    } catch (org.cloffle.trufflejson.JsonException e) {
                        throw new JsonParser.ParseException(e.detail, e.position);
                    }
                }
                case SCANNER_KIND_STATIC_SKIP -> {
                    try {
                        yield JsonScanV2StaticSkip.projectBytesPartialEvaluated(
                                bytes, root, leaves, firstWins);
                    } catch (org.cloffle.trufflejson.JsonException e) {
                        throw new JsonParser.ParseException(e.detail, e.position);
                    }
                }
                case SCANNER_KIND_BYTES_ONLY -> {
                    try {
                        yield JsonScanV3BytesOnly.projectBytesPartialEvaluated(
                                bytes, root, leaves, firstWins);
                    } catch (org.cloffle.trufflejson.JsonException e) {
                        throw new JsonParser.ParseException(e.detail, e.position);
                    }
                }
                default -> JsonParser.projectTypedBytesPartialEvaluated(
                        bytes, root, leaves, firstWins);
            };
        }
        return scanBoundary(source);
    }

    @TruffleBoundary
    private JsonScan.TypedScanResult scanBoundary(Object source) {
        if (simdjsonBackend) {
            try {
                if (simdjsonSchema == null) {
                    return scanCustom(source);
                }
                return SimdJsonProjector.project(source, simdjsonSchema, leaves, firstWins);
            } catch (SimdJsonProjector.Fallback ignored) {
                // SIMD stage 1 is an optimization only; custom remains semantic authority.
            }
        }
        if (jackson3Backend) {
            try {
                return JacksonJson3Projector.project(source, root, leaves, firstWins);
            } catch (JacksonJson3Projector.Fallback ignored) {
                // Jackson 3 is experimental. Retry to preserve Cloffle semantics.
            }
        }
        if (jacksonBackend) {
            try {
                return JacksonJsonProjector.project(source, root, leaves, firstWins);
            } catch (JacksonJsonProjector.Fallback ignored) {
                // Jackson is an optimization only. Retry to preserve Cloffle's permissive
                // unselected-scalar handling and established parse errors.
            }
        }
        return scanCustom(source);
    }

    private JsonScan.TypedScanResult scanCustom(Object source) {
        if (source instanceof byte[] bytes) {
            return JsonParser.projectTypedBytes(bytes, root, leaves, firstWins);
        }
        if (source instanceof ByteBuffer buffer) {
            return JsonParser.projectTypedByteBuffer(buffer, root, leaves, firstWins);
        }
        if (source instanceof String string) {
            return JsonParser.projectTypedString(string, root, leaves, firstWins);
        }
        if (source instanceof TruffleString string) {
            return JsonParser.projectTypedTruffleString(string, root, leaves, firstWins);
        }
        if (source instanceof CharSequence chars) {
            return JsonParser.projectTypedString(chars.toString(), root, leaves, firstWins);
        }
        throw new IllegalArgumentException("JSON source must be String, byte[], ByteBuffer, "
                + "CharSequence, or TruffleString");
    }

    public Object build(Object[] slots) {
        return output.build(slots);
    }

    boolean isPrimitiveRoot(int kind) {
        return output instanceof LeafOutput leafOutput
                && leaves[leafOutput.slot].kind == kind
                && !leaves[leafOutput.slot].nullable;
    }

    int rootSlot() {
        return ((LeafOutput) output).slot;
    }

    TruffleString directSlice(JsonScan.TypedScanResult scan, int slot,
                              TruffleString.FromByteArrayNode from) {
        if (scan.truffleSource != null) {
            return scan.truffleSource.substringByteIndexUncached(
                    scan.starts[slot] - scan.truffleOffset, scan.lengths[slot],
                    TruffleString.Encoding.UTF_8, true);
        }
        return from.execute(scan.source, scan.starts[slot], scan.lengths[slot],
                TruffleString.Encoding.UTF_8, false);
    }

    int decodeRootInt(JsonScan.TypedScanResult scan,
                      TruffleString.FromByteArrayNode from,
                      TruffleString.ParseIntNode parseInt) {
        int slot = rootSlot();
        if (scan.states[slot] == JsonScan.TypedScanResult.VALUE) {
            long l = ((Number) scan.values[slot]).longValue();
            if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                throw numberFormatException(scan.starts[slot]);
            }
            return (int) l;
        }
        try {
            return parseInt.execute(directSlice(scan, slot, from), 10);
        } catch (TruffleString.NumberFormatException e) {
            throw numberFormatException(scan.starts[slot]);
        }
    }

    long decodeRootLong(JsonScan.TypedScanResult scan,
                        TruffleString.FromByteArrayNode from,
                        TruffleString.ParseLongNode parseLong) {
        int slot = rootSlot();
        if (scan.states[slot] == JsonScan.TypedScanResult.VALUE) {
            return ((Number) scan.values[slot]).longValue();
        }
        try {
            return parseLong.execute(directSlice(scan, slot, from), 10);
        } catch (TruffleString.NumberFormatException e) {
            throw numberFormatException(scan.starts[slot]);
        }
    }

    double decodeRootDouble(JsonScan.TypedScanResult scan,
                            TruffleString.FromByteArrayNode from,
                            TruffleString.ParseDoubleNode parseDouble) {
        int slot = rootSlot();
        if (scan.states[slot] == JsonScan.TypedScanResult.VALUE) {
            return ((Number) scan.values[slot]).doubleValue();
        }
        try {
            return parseDouble.execute(directSlice(scan, slot, from));
        } catch (TruffleString.NumberFormatException e) {
            throw numberFormatException(scan.starts[slot]);
        }
    }

    boolean decodeRootBoolean(JsonScan.TypedScanResult scan) {
        return (Boolean) scan.values[rootSlot()];
    }

    @TruffleBoundary
    public Object fallback(Object source, Object schema) {
        return project(source, schema);
    }

    /**
     * Execute a precompiled plan. Schema compilation stays in {@link #compile}; this is the
     * scan + decode + assemble path Cloffle guests run per request.
     */
    @TruffleBoundary
    public Object project(Object source) {
        JsonScan.TypedScanResult result = scan(source);
        decodeUncached(result, leaves);
        return build(result.values);
    }

    /** Dynamic/interpreted entry point used when the compiler cannot see a constant schema. */
    @TruffleBoundary
    public static Object project(Object source, Object schema) {
        JsonTypedProjectPlan plan = compile(null, schema);
        JsonScan.TypedScanResult scan = plan.scan(source);
        decodeUncached(scan, plan.leaves);
        return plan.build(scan.values);
    }

    /** Interpreted three-argument entry point with projection-level representation options. */
    @TruffleBoundary
    public static Object project(Object source, Object schema, Object options) {
        JsonTypedProjectPlan plan = compile(null, schema, options);
        JsonScan.TypedScanResult scan = plan.scan(source);
        decodeUncached(scan, plan.leaves);
        return plan.build(scan.values);
    }

    /** Library dynamic arrays are {@code Object[]}; Cloffle guests expect {@link RT#vector}. */
    @TruffleBoundary
    static Object clojurizeDynamic(Object value) {
        if (value instanceof Object[] items) {
            Object[] mapped = new Object[items.length];
            for (int i = 0; i < items.length; i++) {
                mapped[i] = clojurizeDynamic(items[i]);
            }
            return RT.vector(mapped);
        }
        return value;
    }

    static void decodeUncached(JsonScan.TypedScanResult scan, JsonScan.TypedLeaf[] leaves) {
        TruffleString.FromByteArrayNode from = TruffleString.FromByteArrayNode.getUncached();
        TruffleString.SubstringByteIndexNode substring =
                TruffleString.SubstringByteIndexNode.getUncached();
        TruffleString.ToJavaStringNode toJava = TruffleString.ToJavaStringNode.getUncached();
        TruffleString.ParseIntNode parseInt = TruffleString.ParseIntNode.getUncached();
        TruffleString.ParseLongNode parseLong = TruffleString.ParseLongNode.getUncached();
        TruffleString.ParseDoubleNode parseDouble = TruffleString.ParseDoubleNode.getUncached();
        TruffleString.MaterializeSubstringNode materialize =
                TruffleString.MaterializeSubstringNode.getUncached();
        TruffleStringBuilder.AppendSubstringByteIndexNode appendSubstring =
                TruffleStringBuilder.AppendSubstringByteIndexNode.getUncached();
        TruffleStringBuilder.AppendCodePointNode appendCodePoint =
                TruffleStringBuilder.AppendCodePointNode.getUncached();
        TruffleStringBuilder.ToStringNode builderToString =
                TruffleStringBuilder.ToStringNode.getUncached();
        TruffleString.ReadByteNode readByte = TruffleString.ReadByteNode.getUncached();
        decode(scan, leaves, from, substring, toJava, parseInt, parseLong, parseDouble,
                materialize, appendSubstring, appendCodePoint, builderToString, readByte);
    }

    static void decode(JsonScan.TypedScanResult scan, JsonScan.TypedLeaf[] leaves,
                       TruffleString.FromByteArrayNode from,
                       TruffleString.SubstringByteIndexNode substring,
                       TruffleString.ToJavaStringNode toJava,
                       TruffleString.ParseIntNode parseInt,
                       TruffleString.ParseLongNode parseLong,
                       TruffleString.ParseDoubleNode parseDouble,
                       TruffleString.MaterializeSubstringNode materialize,
                       TruffleStringBuilder.AppendSubstringByteIndexNode appendSubstring,
                       TruffleStringBuilder.AppendCodePointNode appendCodePoint,
                       TruffleStringBuilder.ToStringNode builderToString,
                       TruffleString.ReadByteNode readByte) {
        int n = leaves.length;
        int chunks = (n + 7) >> 3;
        decodeChunks(scan, leaves, chunks, from, substring, toJava, parseInt, parseLong, parseDouble,
                materialize, appendSubstring, appendCodePoint, builderToString, readByte);
    }

    /** At most four chunks of eight leaves so PE graphs stay compilable. */
    @ExplodeLoop
    private static void decodeChunks(JsonScan.TypedScanResult scan, JsonScan.TypedLeaf[] leaves,
                                     int chunks,
                                     TruffleString.FromByteArrayNode from,
                                     TruffleString.SubstringByteIndexNode substring,
                                     TruffleString.ToJavaStringNode toJava,
                                     TruffleString.ParseIntNode parseInt,
                                     TruffleString.ParseLongNode parseLong,
                                     TruffleString.ParseDoubleNode parseDouble,
                                     TruffleString.MaterializeSubstringNode materialize,
                                     TruffleStringBuilder.AppendSubstringByteIndexNode appendSubstring,
                                     TruffleStringBuilder.AppendCodePointNode appendCodePoint,
                                     TruffleStringBuilder.ToStringNode builderToString,
                                     TruffleString.ReadByteNode readByte) {
        for (int c = 0; c < 4; c++) {
            if (c < chunks) {
                decodeChunk(scan, leaves, c << 3, from, substring, toJava, parseInt, parseLong,
                        parseDouble, materialize, appendSubstring, appendCodePoint, builderToString,
                        readByte);
            }
        }
    }

    @ExplodeLoop
    private static void decodeChunk(JsonScan.TypedScanResult scan, JsonScan.TypedLeaf[] leaves,
                                    int offset,
                                    TruffleString.FromByteArrayNode from,
                                    TruffleString.SubstringByteIndexNode substring,
                                    TruffleString.ToJavaStringNode toJava,
                                    TruffleString.ParseIntNode parseInt,
                                    TruffleString.ParseLongNode parseLong,
                                    TruffleString.ParseDoubleNode parseDouble,
                                    TruffleString.MaterializeSubstringNode materialize,
                                    TruffleStringBuilder.AppendSubstringByteIndexNode appendSubstring,
                                    TruffleStringBuilder.AppendCodePointNode appendCodePoint,
                                    TruffleStringBuilder.ToStringNode builderToString,
                                    TruffleString.ReadByteNode readByte) {
        int n = leaves.length;
        for (int j = 0; j < 8; j++) {
            int i = offset + j;
            if (i >= n) {
                continue;
            }
            byte state = scan.states[i];
            if (state == JsonScan.TypedScanResult.VALUE) {
                JsonScan.TypedLeaf leaf = leaves[i];
                if (leaf != null && leaf.kind == JsonScan.TypedLeaf.DYNAMIC) {
                    scan.values[i] = clojurizeDynamic(scan.values[i]);
                }
                continue;
            }
            if (state == JsonScan.TypedScanResult.RAW) {
                scan.values[i] = parseRaw(scan, i, from, substring);
                continue;
            }
            if (state != JsonScan.TypedScanResult.SLICE
                    && state != JsonScan.TypedScanResult.ESCAPED_SLICE) {
                continue;
            }
            JsonScan.TypedLeaf leaf = leaves[i];
            TruffleString slice;
            if (state == JsonScan.TypedScanResult.ESCAPED_SLICE) {
                TruffleString escapedSource;
                int sourceBase;
                if (scan.truffleSource != null) {
                    escapedSource = scan.truffleSource;
                    sourceBase = scan.truffleOffset;
                } else {
                    escapedSource = from.execute(
                            scan.source, scan.starts[i], scan.lengths[i],
                            TruffleString.Encoding.UTF_8, false);
                    sourceBase = scan.starts[i];
                }
                slice = decodeEscaped(
                        scan, escapedSource, sourceBase, i,
                        appendSubstring, appendCodePoint, builderToString, readByte);
            } else if (scan.truffleSource != null) {
                slice = substring.execute(
                        scan.truffleSource, scan.starts[i] - scan.truffleOffset, scan.lengths[i],
                        TruffleString.Encoding.UTF_8, true);
            } else {
                slice = from.execute(
                        scan.source, scan.starts[i], scan.lengths[i],
                        TruffleString.Encoding.UTF_8, false);
            }
            try {
                switch (leaf.kind) {
                    case JsonScan.TypedLeaf.INT ->
                            scan.values[i] = Integer.valueOf(parseInt.execute(slice, 10));
                    case JsonScan.TypedLeaf.LONG ->
                            scan.values[i] = Long.valueOf(parseLong.execute(slice, 10));
                    case JsonScan.TypedLeaf.DOUBLE ->
                            scan.values[i] = Double.valueOf(parseDouble.execute(slice));
                    case JsonScan.TypedLeaf.STRING ->
                            scan.values[i] = toJava.execute(slice);
                    case JsonScan.TypedLeaf.TRUFFLE_STRING ->
                            scan.values[i] = leaf.materialize
                                    ? materialize.execute(slice, TruffleString.Encoding.UTF_8)
                                    : slice;
                    case JsonScan.TypedLeaf.ANY -> scan.values[i] = toJava.execute(slice);
                    default -> throw new IllegalStateException("Unexpected sliced leaf " + leaf.kind);
                }
            } catch (TruffleString.NumberFormatException e) {
                throw numberFormatException(scan.starts[i]);
            }
        }
    }

    /**
     * A pointer can land on an object, an array, or a number wider than a {@code long}. Those are
     * left as raw text by the scan and read here by the ordinary untyped parser, so the result
     * matches what {@code cloffle.json/project} with no schema would have produced.
     */
    @TruffleBoundary
    private static Object parseRaw(JsonScan.TypedScanResult scan, int slot,
                                   TruffleString.FromByteArrayNode from,
                                   TruffleString.SubstringByteIndexNode substring) {
        TruffleString raw = scan.truffleSource != null
                ? substring.execute(scan.truffleSource, scan.starts[slot] - scan.truffleOffset,
                        scan.lengths[slot], TruffleString.Encoding.UTF_8, true)
                : from.execute(scan.source, scan.starts[slot], scan.lengths[slot],
                        TruffleString.Encoding.UTF_8, false);
        return JsonParser.parseInput(raw);
    }

    @TruffleBoundary
    private static JsonParser.ParseException numberFormatException(int pos) {
        CompilerDirectives.transferToInterpreter();
        return new JsonParser.ParseException("Number outside schema range", pos);
    }

    private static TruffleString decodeEscaped(
            JsonScan.TypedScanResult scan,
            TruffleString source,
            int sourceBase,
            int slot,
            TruffleStringBuilder.AppendSubstringByteIndexNode appendSubstring,
            TruffleStringBuilder.AppendCodePointNode appendCodePoint,
            TruffleStringBuilder.ToStringNode builderToString,
            TruffleString.ReadByteNode readByte) {
        int start = scan.starts[slot];
        int end = start + scan.lengths[slot];
        TruffleStringBuilder builder = TruffleStringBuilder.createUTF8(end - start);
        int runStart = start;
        int i = start;
        while (i < end) {
            if (sourceByte(scan, source, sourceBase, i, readByte) != '\\') {
                i++;
                continue;
            }
            if (i > runStart) {
                appendSubstring.execute(
                        builder, source, runStart - sourceBase, i - runStart);
            }
            int escapePosition = i++;
            if (i >= end) {
                throw malformedEscape("Unterminated string escape", escapePosition);
            }
            int escaped = sourceByte(scan, source, sourceBase, i++, readByte) & 0xff;
            int codePoint = switch (escaped) {
                case '"', '\\', '/' -> escaped;
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> {
                    int high = hex4(scan, source, sourceBase, i, end, readByte);
                    i += 4;
                    if (high >= 0xD800 && high <= 0xDBFF
                            && i + 6 <= end
                            && sourceByte(scan, source, sourceBase, i, readByte) == '\\'
                            && sourceByte(scan, source, sourceBase, i + 1, readByte) == 'u') {
                        int low = hex4(scan, source, sourceBase, i + 2, end, readByte);
                        if (low >= 0xDC00 && low <= 0xDFFF) {
                            i += 6;
                            yield Character.toCodePoint((char) high, (char) low);
                        }
                    }
                    yield high;
                }
                default -> throw malformedEscape("Invalid escape", escapePosition);
            };
            appendCodePoint.execute(builder, codePoint, 1, true);
            runStart = i;
        }
        if (end > runStart) {
            appendSubstring.execute(
                    builder, source, runStart - sourceBase, end - runStart);
        }
        return builderToString.execute(builder, true);
    }

    private static int hex4(
            JsonScan.TypedScanResult scan,
            TruffleString source,
            int sourceBase,
            int start,
            int end,
            TruffleString.ReadByteNode readByte) {
        if (start + 4 > end) {
            throw malformedEscape("Invalid unicode escape", start);
        }
        int value = 0;
        for (int i = start; i < start + 4; i++) {
            int c = sourceByte(scan, source, sourceBase, i, readByte) & 0xff;
            int digit;
            if (c >= '0' && c <= '9') {
                digit = c - '0';
            } else if (c >= 'a' && c <= 'f') {
                digit = c - 'a' + 10;
            } else if (c >= 'A' && c <= 'F') {
                digit = c - 'A' + 10;
            } else {
                throw malformedEscape("Invalid hex digit", i);
            }
            value = (value << 4) | digit;
        }
        return value;
    }

    /**
     * Escape validation failures are cold. Keep {@link JsonParser.ParseException} construction
     * (and its message concat) out of the PEA'd decode graph so the hot escaped-string path stays
     * small enough to scalar-replace its builder.
     */
    @TruffleBoundary
    private static JsonParser.ParseException malformedEscape(String message, int position) {
        CompilerDirectives.transferToInterpreter();
        return new JsonParser.ParseException(message, position);
    }

    private static byte sourceByte(
            JsonScan.TypedScanResult scan,
            TruffleString source,
            int sourceBase,
            int index,
            TruffleString.ReadByteNode readByte) {
        if (scan.source != null) {
            return scan.source[index];
        }
        return (byte) readByte.execute(
                source, index - sourceBase, TruffleString.Encoding.UTF_8);
    }

    abstract static class OutputNode {
        abstract Object build(Object[] slots);

        abstract void collectSlots(List<Integer> out);
    }

    /**
     * A post-order output recipe. Child indexes always refer to earlier steps, allowing the
     * Truffle instruction to explode one finite loop instead of recursively inlining OutputNode.
     */
    static final class MaterializeStep {
        final OutputNode node;
        @CompilerDirectives.CompilationFinal(dimensions = 1) final int[] children;

        MaterializeStep(OutputNode node, int[] children) {
            this.node = node;
            this.children = children;
        }

        static MaterializeStep[] compile(OutputNode output) {
            List<MaterializeStep> steps = new ArrayList<>();
            append(output, steps);
            return steps.toArray(new MaterializeStep[0]);
        }

        private static int append(OutputNode node, List<MaterializeStep> steps) {
            int[] children;
            if (node instanceof EntryOutput entry) {
                children = new int[] {append(entry.child, steps)};
            } else if (node instanceof MapOutput map && map.shape != null) {
                children = new int[map.entries.length];
                for (int i = 0; i < children.length; i++) {
                    children[i] = append(map.entries[i], steps);
                }
            } else if (node instanceof TupleOutput tuple && tuple.children.length <= 8) {
                children = new int[tuple.children.length];
                for (int i = 0; i < children.length; i++) {
                    children[i] = append(tuple.children[i], steps);
                }
            } else {
                children = new int[0];
            }
            int index = steps.size();
            steps.add(new MaterializeStep(node, children));
            return index;
        }
    }

    static final class LeafOutput extends OutputNode {
        final int slot;

        LeafOutput(int slot) {
            this.slot = slot;
        }

        @Override
        Object build(Object[] slots) {
            return slots[slot];
        }

        @Override
        void collectSlots(List<Integer> out) {
            out.add(slot);
        }
    }

    static final class ConstantOutput extends OutputNode {
        final Object value;

        ConstantOutput(Object value) {
            this.value = value;
        }

        @Override
        Object build(Object[] slots) {
            return value;
        }

        @Override
        void collectSlots(List<Integer> out) {
        }
    }

    static final class EntryOutput extends OutputNode {
        final OutputNode child;
        final int[] presenceSlots;
        final boolean optional;
        final boolean hasDefault;
        final Object defaultValue;

        EntryOutput(OutputNode child, boolean optional, boolean hasDefault, Object defaultValue) {
            this.child = child;
            this.optional = optional;
            this.hasDefault = hasDefault;
            this.defaultValue = defaultValue;
            List<Integer> slots = new ArrayList<>();
            child.collectSlots(slots);
            this.presenceSlots = slots.stream().mapToInt(Integer::intValue).toArray();
        }

        boolean missing(Object[] slots) {
            for (int slot : presenceSlots) {
                if (slots[slot] != JsonScan.MISSING) {
                    return false;
                }
            }
            return true;
        }

        @Override
        Object build(Object[] slots) {
            if (missing(slots)) {
                if (hasDefault) {
                    return defaultValue;
                }
                if (optional) {
                    return JsonParser.MISSING;
                }
                throw missingRequiredField();
            }
            return child.build(slots);
        }

        @TruffleBoundary
        private static IllegalArgumentException missingRequiredField() {
            CompilerDirectives.transferToInterpreter();
            return new IllegalArgumentException("Required JSON field is missing");
        }

        @Override
        void collectSlots(List<Integer> out) {
            child.collectSlots(out);
        }
    }

    static final class MapOutput extends OutputNode {
        final Keyword[] keys;
        final EntryOutput[] entries;
        final MapShape shape;
        final boolean omitsMissing;

        MapOutput(Keyword[] keys, EntryOutput[] entries) {
            this.keys = keys;
            this.entries = entries;
            boolean omit = false;
            for (EntryOutput entry : entries) {
                omit |= entry.optional && !entry.hasDefault;
            }
            this.omitsMissing = omit;
            this.shape = !omit && keys.length <= PersistentShapeMap.MAX_SHAPE_KEYS
                    ? MapShape.of(keys) : null;
        }

        @Override
        Object build(Object[] slots) {
            if (shape != null) {
                int n = keys.length;
                Object v0 = n > 0 ? entries[0].build(slots) : null;
                Object v1 = n > 1 ? entries[1].build(slots) : null;
                Object v2 = n > 2 ? entries[2].build(slots) : null;
                Object v3 = n > 3 ? entries[3].build(slots) : null;
                Object v4 = n > 4 ? entries[4].build(slots) : null;
                Object v5 = n > 5 ? entries[5].build(slots) : null;
                Object v6 = n > 6 ? entries[6].build(slots) : null;
                Object v7 = n > 7 ? entries[7].build(slots) : null;
                return new PersistentShapeMap(null, shape, v0, v1, v2, v3, v4, v5, v6, v7);
            }
            List<Object> kvs = new ArrayList<>(keys.length * 2);
            for (int i = 0; i < keys.length; i++) {
                Object value = entries[i].build(slots);
                if (value != JsonParser.MISSING) {
                    kvs.add(keys[i]);
                    kvs.add(value);
                }
            }
            return RT.map(kvs.toArray());
        }

        @Override
        void collectSlots(List<Integer> out) {
            for (EntryOutput entry : entries) {
                entry.collectSlots(out);
            }
        }
    }

    /**
     * Result of {@code cloffle.json/select}: a map keyed by the pointer strings themselves.
     * {@link MapShape} keys are keywords, so this builds an ordinary map and gives up the
     * shape-map fast path. Pointers that matched nothing are left out.
     */
    static final class StringKeyMapOutput extends OutputNode {
        @CompilerDirectives.CompilationFinal(dimensions = 1) final String[] keys;
        @CompilerDirectives.CompilationFinal(dimensions = 1) final EntryOutput[] entries;

        StringKeyMapOutput(String[] keys, EntryOutput[] entries) {
            this.keys = keys;
            this.entries = entries;
        }

        @Override
        @ExplodeLoop
        Object build(Object[] slots) {
            Object[] kvs = new Object[keys.length * 2];
            int n = 0;
            for (int i = 0; i < keys.length; i++) {
                Object value = entries[i].build(slots);
                if (value != JsonParser.MISSING) {
                    kvs[n++] = keys[i];
                    kvs[n++] = value;
                }
            }
            return n == kvs.length ? RT.map(kvs) : RT.map(java.util.Arrays.copyOf(kvs, n));
        }

        @Override
        void collectSlots(List<Integer> out) {
            for (EntryOutput entry : entries) {
                entry.collectSlots(out);
            }
        }
    }

    static final class TupleOutput extends OutputNode {
        final OutputNode[] children;

        TupleOutput(OutputNode[] children) {
            this.children = children;
        }

        @Override
        Object build(Object[] slots) {
            Object[] values = new Object[children.length];
            for (int i = 0; i < values.length; i++) {
                values[i] = children[i].build(slots);
            }
            return switch (values.length) {
                case 0 -> PersistentTuple.EMPTY;
                case 1 -> PersistentTuple.create(values[0]);
                case 2 -> PersistentTuple.create(values[0], values[1]);
                case 3 -> PersistentTuple.create(values[0], values[1], values[2]);
                case 4 -> PersistentTuple.create(values[0], values[1], values[2], values[3]);
                case 5 -> PersistentTuple.create(values[0], values[1], values[2], values[3], values[4]);
                case 6 -> PersistentTuple.create(values[0], values[1], values[2], values[3], values[4], values[5]);
                case 7 -> PersistentTuple.create(values[0], values[1], values[2], values[3], values[4], values[5], values[6]);
                case 8 -> PersistentTuple.create(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7]);
                default -> RT.vector(values);
            };
        }

        @Override
        void collectSlots(List<Integer> out) {
            for (OutputNode child : children) {
                child.collectSlots(out);
            }
        }
    }

    private record EntryOptions(boolean optional, boolean hasDefault, Object defaultValue,
                                boolean materialize) {
        static final EntryOptions REQUIRED = new EntryOptions(false, false, null, false);
    }

    private static final class Compiler {
        final TrieBuilder root = new TrieBuilder();
        final List<JsonScan.TypedLeaf> leaves = new ArrayList<>();
        final int defaultStringKind;

        Compiler(int defaultStringKind) {
            this.defaultStringKind = defaultStringKind;
        }

        OutputNode compileSelect(Object pointers) {
            List<Object> raw = new ArrayList<>();
            if (pointers instanceof IPersistentVector vector) {
                for (int i = 0; i < vector.count(); i++) {
                    raw.add(vector.nth(i));
                }
            } else if (pointers instanceof Iterable<?> iterable) {
                for (Object pointer : iterable) {
                    raw.add(pointer);
                }
            } else {
                throw new IllegalArgumentException(
                        "cloffle.json/select takes a collection of pointer strings");
            }
            String[] keys = new String[raw.size()];
            EntryOutput[] entries = new EntryOutput[raw.size()];
            for (int i = 0; i < keys.length; i++) {
                Object pointer = raw.get(i);
                if (!(pointer instanceof CharSequence text)) {
                    throw new IllegalArgumentException(
                            "JSON Pointer must be a string, got " + pointer);
                }
                keys[i] = text.toString();
                entries[i] = new EntryOutput(
                        compilePointer(JsonPointer.parse(keys[i])), true, false, null);
            }
            return new StringKeyMapOutput(keys, entries);
        }

        private OutputNode compilePointer(JsonPointer pointer) {
            List<Object> path = new ArrayList<>(pointer.size());
            for (JsonPointer.Token token : pointer.tokens) {
                path.add(new PointerStep(token.name, token.index));
            }
            JsonScan.TypedLeaf leaf =
                    new JsonScan.TypedLeaf(JsonScan.TypedLeaf.ANY, true, false, null);
            int slot = leaves.size();
            leaves.add(leaf);
            root.insert(path, slot, leaf);
            return new LeafOutput(slot);
        }

        OutputNode compile(Object schema, List<Object> path, EntryOptions options) {
            if (schema instanceof IPersistentMap map) {
                return compileJsonSchema(map, path, options);
            }
            if (schema instanceof Keyword keyword) {
                return compileLeaf(keyword, path, options, false);
            }
            if (!(schema instanceof IPersistentVector vector) || vector.count() == 0
                    || !(vector.nth(0) instanceof Keyword op)) {
                throw new IllegalArgumentException("Unsupported JSON projection schema: " + schema);
            }
            if (MAYBE.equals(op)) {
                if (vector.count() != 2) {
                    throw new IllegalArgumentException("[:maybe schema] requires one child");
                }
                return compileMaybe(vector.nth(1), path, options);
            }
            if (MAP.equals(op)) {
                return compileMap(vector, path);
            }
            if (TUPLE.equals(op)) {
                return compileTuple(vector, path);
            }
            if (INDEXES.equals(op)) {
                return compileIndexes(vector, path);
            }
            if (VECTOR.equals(op)) {
                return compileDynamic(vector, path, options);
            }
            throw new IllegalArgumentException("Unsupported JSON projection operator: " + op);
        }

        private OutputNode compileMaybe(Object schema, List<Object> path, EntryOptions options) {
            if (schema instanceof Keyword keyword) {
                return compileLeaf(keyword, path, options, true);
            }
            throw new IllegalArgumentException("[:maybe ...] currently supports scalar leaves");
        }

        private OutputNode compileLeaf(Keyword type, List<Object> path, EntryOptions options,
                                       boolean nullable) {
            int kind = leafKind(type);
            JsonScan.TypedLeaf leaf =
                    new JsonScan.TypedLeaf(kind, nullable, options.materialize, null);
            int slot = leaves.size();
            leaves.add(leaf);
            root.insert(path, slot, leaf);
            return new LeafOutput(slot);
        }

        private OutputNode compileDynamic(IPersistentVector schema, List<Object> path,
                                          EntryOptions options) {
            JsonScan.TypedValueNode dynamic = compileDynamicNode(schema);
            JsonScan.TypedLeaf leaf = new JsonScan.TypedLeaf(
                    JsonScan.TypedLeaf.DYNAMIC, false, options.materialize, dynamic);
            int slot = leaves.size();
            leaves.add(leaf);
            root.insert(path, slot, leaf);
            return new LeafOutput(slot);
        }

        private OutputNode compileMap(IPersistentVector schema, List<Object> path) {
            int n = schema.count() - 1;
            Keyword[] keys = new Keyword[n];
            EntryOutput[] outputs = new EntryOutput[n];
            for (int i = 0; i < n; i++) {
                Object raw = schema.nth(i + 1);
                if (!(raw instanceof IPersistentVector entry)
                        || (entry.count() != 2 && entry.count() != 3)
                        || !(entry.nth(0) instanceof Keyword key)) {
                    throw new IllegalArgumentException("Map entries are [keyword schema] or "
                            + "[keyword properties schema]");
                }
                EntryOptions options = entry.count() == 3
                        ? entryOptions(entry.nth(1)) : EntryOptions.REQUIRED;
                Object childSchema = entry.nth(entry.count() - 1);
                List<Object> childPath = append(path, key);
                OutputNode child = compile(childSchema, childPath, options);
                keys[i] = key;
                outputs[i] = new EntryOutput(
                        child, options.optional, options.hasDefault, options.defaultValue);
            }
            return new MapOutput(keys, outputs);
        }

        private OutputNode compileTuple(IPersistentVector schema, List<Object> path) {
            OutputNode[] children = new OutputNode[schema.count() - 1];
            for (int i = 0; i < children.length; i++) {
                children[i] = compile(schema.nth(i + 1), append(path, Integer.valueOf(i)),
                        EntryOptions.REQUIRED);
            }
            return new TupleOutput(children);
        }

        private OutputNode compileIndexes(IPersistentVector schema, List<Object> path) {
            Map<Integer, OutputNode> selected = new LinkedHashMap<>();
            int max = -1;
            for (int i = 1; i < schema.count(); i++) {
                Object raw = schema.nth(i);
                if (!(raw instanceof IPersistentVector entry) || entry.count() != 2
                        || !(entry.nth(0) instanceof Number number)) {
                    throw new IllegalArgumentException(
                            ":cloffle/indexes entries are [index schema]");
                }
                int index = number.intValue();
                if (index < 0 || number.longValue() != index || selected.containsKey(index)) {
                    throw new IllegalArgumentException("Array index must be unique and non-negative");
                }
                selected.put(index, compile(entry.nth(1), append(path, Integer.valueOf(index)),
                        EntryOptions.REQUIRED));
                max = Math.max(max, index);
            }
            OutputNode[] children = new OutputNode[max + 1];
            for (int i = 0; i <= max; i++) {
                children[i] = selected.getOrDefault(i, new ConstantOutput(null));
            }
            return new TupleOutput(children);
        }

        private OutputNode compileJsonSchema(IPersistentMap schema, List<Object> path,
                                             EntryOptions options) {
            String type = jsonType(schema);
            Object defaultValue = jsonGet(schema, JS_DEFAULT, "default");
            EntryOptions withDefault = defaultValue != null
                    ? new EntryOptions(options.optional, true, defaultValue, options.materialize)
                    : options;
            if (type == null) {
                if (jsonGet(schema, JS_PROPERTIES, "properties") != null) {
                    type = "object";
                } else if (jsonGet(schema, JS_ITEMS, "items") != null
                        || jsonGet(schema, JS_PREFIX_ITEMS, "prefixItems") != null) {
                    type = "array";
                } else {
                    throw new IllegalArgumentException("JSON Schema requires type: " + schema);
                }
            }
            return switch (type) {
                case "object" -> compileJsonObject(schema, path);
                case "array" -> compileJsonArray(schema, path, withDefault);
                case "string" -> compileLeaf(Keyword.intern("string"), path, withDefault, false);
                case "boolean" -> compileLeaf(Keyword.intern("boolean"), path, withDefault, false);
                case "integer" -> compileLeaf(Keyword.intern("long"), path, withDefault, false);
                case "number" -> compileLeaf(Keyword.intern("double"), path, withDefault, false);
                case "null" -> compileNull(path, withDefault);
                default -> throw new IllegalArgumentException("Unsupported JSON Schema type: " + type);
            };
        }

        private OutputNode compileJsonObject(IPersistentMap schema, List<Object> path) {
            Object rawProps = jsonGet(schema, JS_PROPERTIES, "properties");
            if (rawProps != null && !(rawProps instanceof IPersistentMap)) {
                throw new IllegalArgumentException("JSON Schema properties must be a map");
            }
            IPersistentMap properties = rawProps == null
                    ? PersistentShapeMap.EMPTY : (IPersistentMap) rawProps;
            java.util.Set<String> required = jsonRequired(schema);
            List<Keyword> keys = new ArrayList<>();
            List<EntryOutput> outputs = new ArrayList<>();
            for (ISeq s = RT.seq(properties); s != null; s = s.next()) {
                if (!(s.first() instanceof IMapEntry me)) {
                    continue;
                }
                Keyword key = jsonKeyword(me.key());
                Object childSchema = me.val();
                boolean optional = !required.contains(key.getName());
                Object childDefault = childSchema instanceof IPersistentMap childMap
                        ? jsonGet(childMap, JS_DEFAULT, "default") : null;
                EntryOptions childOpts = new EntryOptions(
                        optional, childDefault != null, childDefault, false);
                OutputNode child = compile(childSchema, append(path, key), childOpts);
                keys.add(key);
                outputs.add(new EntryOutput(
                        child, childOpts.optional, childOpts.hasDefault, childOpts.defaultValue));
            }
            return new MapOutput(keys.toArray(new Keyword[0]), outputs.toArray(new EntryOutput[0]));
        }

        private OutputNode compileJsonArray(IPersistentMap schema, List<Object> path,
                                            EntryOptions options) {
            Object prefix = jsonGet(schema, JS_PREFIX_ITEMS, "prefixItems");
            if (prefix instanceof IPersistentVector vector) {
                OutputNode[] children = new OutputNode[vector.count()];
                for (int i = 0; i < children.length; i++) {
                    children[i] = compile(vector.nth(i), append(path, Integer.valueOf(i)),
                            EntryOptions.REQUIRED);
                }
                return new TupleOutput(children);
            }
            Object items = jsonGet(schema, JS_ITEMS, "items");
            if (items == null) {
                throw new IllegalArgumentException("JSON Schema array requires items or prefixItems");
            }
            return compileDynamic(RT.vector(VECTOR, items), path, options);
        }

        private OutputNode compileNull(List<Object> path, EntryOptions options) {
            JsonScan.TypedLeaf leaf = new JsonScan.TypedLeaf(
                    JsonScan.TypedLeaf.NULL, true, options.materialize, null);
            int slot = leaves.size();
            leaves.add(leaf);
            root.insert(path, slot, leaf);
            return new LeafOutput(slot);
        }

        private static String jsonType(IPersistentMap schema) {
            Object type = jsonGet(schema, JS_TYPE, "type");
            if (type == null) {
                return null;
            }
            if (type instanceof Keyword keyword) {
                return keyword.getName();
            }
            if (type instanceof String string) {
                return string;
            }
            throw new IllegalArgumentException("JSON Schema type must be a string");
        }

        private static Object jsonGet(IPersistentMap map, Keyword keyword, String name) {
            if (map.containsKey(keyword)) {
                return map.valAt(keyword);
            }
            if (map.containsKey(name)) {
                return map.valAt(name);
            }
            return null;
        }

        private static java.util.Set<String> jsonRequired(IPersistentMap schema) {
            java.util.Set<String> required = new java.util.HashSet<>();
            Object raw = jsonGet(schema, JS_REQUIRED, "required");
            if (raw == null) {
                return required;
            }
            if (!(raw instanceof IPersistentVector vector)) {
                throw new IllegalArgumentException("JSON Schema required must be an array");
            }
            for (int i = 0; i < vector.count(); i++) {
                required.add(jsonKeyword(vector.nth(i)).getName());
            }
            return required;
        }

        private static Keyword jsonKeyword(Object key) {
            if (key instanceof Keyword keyword) {
                return keyword;
            }
            if (key instanceof String string) {
                return Keyword.intern(string);
            }
            throw new IllegalArgumentException("JSON Schema property names must be strings");
        }

        private JsonScan.TypedValueNode compileDynamicNode(Object schema) {
            if (schema instanceof IPersistentMap map) {
                return compileJsonSchemaDynamic(map);
            }
            if (schema instanceof Keyword keyword) {
                return JsonScan.TypedValueNode.leaf(
                        new JsonScan.TypedLeaf(leafKind(keyword), false, false, null));
            }
            if (!(schema instanceof IPersistentVector vector) || vector.count() < 2
                    || !(vector.nth(0) instanceof Keyword op) || !VECTOR.equals(op)
                    || vector.count() != 2) {
                throw new IllegalArgumentException(
                        "Dynamic arrays currently support nested [:vector ...] and scalar leaves");
            }
            return JsonScan.TypedValueNode.vector(compileDynamicNode(vector.nth(1)));
        }

        private JsonScan.TypedValueNode compileJsonSchemaDynamic(IPersistentMap schema) {
            String type = jsonType(schema);
            if (type == null) {
                if (jsonGet(schema, JS_ITEMS, "items") != null) {
                    type = "array";
                } else {
                    throw new IllegalArgumentException(
                            "JSON Schema array items require a scalar or array type");
                }
            }
            return switch (type) {
                case "array" -> {
                    Object items = jsonGet(schema, JS_ITEMS, "items");
                    if (items == null) {
                        throw new IllegalArgumentException(
                                "JSON Schema nested array requires items");
                    }
                    yield JsonScan.TypedValueNode.vector(compileDynamicNode(items));
                }
                case "string" -> JsonScan.TypedValueNode.leaf(new JsonScan.TypedLeaf(
                        defaultStringKind, false, false, null));
                case "boolean" -> JsonScan.TypedValueNode.leaf(new JsonScan.TypedLeaf(
                        JsonScan.TypedLeaf.BOOLEAN, false, false, null));
                case "integer" -> JsonScan.TypedValueNode.leaf(new JsonScan.TypedLeaf(
                        JsonScan.TypedLeaf.LONG, false, false, null));
                case "number" -> JsonScan.TypedValueNode.leaf(new JsonScan.TypedLeaf(
                        JsonScan.TypedLeaf.DOUBLE, false, false, null));
                case "null" -> JsonScan.TypedValueNode.leaf(new JsonScan.TypedLeaf(
                        JsonScan.TypedLeaf.NULL, true, false, null));
                default -> throw new IllegalArgumentException(
                        "JSON Schema items currently support scalar and nested array types, not "
                                + type);
            };
        }

        private int leafKind(Keyword type) {
            String ns = type.getNamespace();
            String name = type.getName();
            if (ns == null) {
                return switch (name) {
                    case "int" -> JsonScan.TypedLeaf.INT;
                    case "long" -> JsonScan.TypedLeaf.LONG;
                    case "double" -> JsonScan.TypedLeaf.DOUBLE;
                    case "boolean" -> JsonScan.TypedLeaf.BOOLEAN;
                    case "string" -> defaultStringKind;
                    default -> throw new IllegalArgumentException("Unsupported JSON leaf type: " + type);
                };
            }
            if ("cloffle".equals(ns) && "truffle-string".equals(name)) {
                return JsonScan.TypedLeaf.TRUFFLE_STRING;
            }
            throw new IllegalArgumentException("Unsupported JSON leaf type: " + type);
        }

        private static EntryOptions entryOptions(Object raw) {
            if (!(raw instanceof IPersistentMap map)) {
                throw new IllegalArgumentException("Map entry properties must be a map");
            }
            boolean optional = RT.booleanCast(map.valAt(OPTIONAL, Boolean.FALSE));
            Object defaultValue = map.valAt(DEFAULT, JsonParser.MISSING);
            boolean hasDefault = defaultValue != JsonParser.MISSING;
            boolean materialize = RT.booleanCast(map.valAt(MATERIALIZE, Boolean.FALSE));
            return new EntryOptions(optional, hasDefault, defaultValue, materialize);
        }

        private static List<Object> append(List<Object> path, Object step) {
            List<Object> out = new ArrayList<>(path.size() + 1);
            out.addAll(path);
            out.add(step);
            return out;
        }
    }

    /**
     * A trie step from a JSON Pointer token. Equality covers both forms so two pointers that share
     * a prefix share one subtree, and {@link TrieBuilder#toTrie} emits both edge kinds for a
     * numeric token.
     */
    private record PointerStep(String name, int index) {
    }

    private static final class TrieBuilder {
        final Map<Object, TrieBuilder> children = new LinkedHashMap<>();
        int slot = -1;
        JsonScan.TypedLeaf leaf;

        void insert(List<Object> path, int slot, JsonScan.TypedLeaf leaf) {
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

        JsonScan.TypedTrieNode toTrie() {
            List<JsonScan.TypedTrieEdge> edges = new ArrayList<>(children.size());
            for (Map.Entry<Object, TrieBuilder> entry : children.entrySet()) {
                Object key = entry.getKey();
                JsonScan.TypedTrieNode child = entry.getValue().toTrie();
                if (key instanceof Keyword keyword) {
                    edges.add(keywordEdge(keyword.sym.toString(), child));
                } else if (key instanceof PointerStep step) {
                    edges.add(keywordEdge(step.name(), child));
                    if (step.index() != JsonPointer.NOT_AN_INDEX) {
                        // The same token addresses a member and an element; the input decides.
                        edges.add(new JsonScan.TypedTrieEdge(null, step.index(), child));
                    }
                } else {
                    edges.add(new JsonScan.TypedTrieEdge(
                            null, ((Integer) key).intValue(), child));
                }
            }
            return new JsonScan.TypedTrieNode(
                    edges.toArray(new JsonScan.TypedTrieEdge[0]), slot, leaf);
        }

        private static JsonScan.TypedTrieEdge keywordEdge(String name,
                                                            JsonScan.TypedTrieNode child) {
            return new JsonScan.TypedTrieEdge(
                    name.getBytes(StandardCharsets.UTF_8), name, -1, child);
        }
    }
}
