package org.cloffle.trufflejson;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

import com.oracle.truffle.api.ArrayUtils;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.strings.InternalByteArray;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.api.strings.TruffleStringBuilder;

/**
 * PEA experiment V2 (static skip): V1 plus skip/parse boundary methods that do not
 * take the scanner as a receiver. Instance wrappers assign {@code pos} from the
 * returned cursor so PEA can keep the scanner virtual across the boundary.
 */
public final class JsonScanV2StaticSkip {

    private JsonScanV2StaticSkip() {
    }

    public static JsonScan.TypedScanResult projectString(String json, JsonScan.TypedTrieNode root, JsonScan.TypedLeaf[] leaves) {
        return projectString(json, root, leaves, true);
    }

    public static JsonScan.TypedScanResult projectString(String json, JsonScan.TypedTrieNode root, JsonScan.TypedLeaf[] leaves,
                                                boolean firstWins) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return projectBytes(bytes, 0, bytes.length, root, leaves, firstWins);
    }

    @TruffleBoundary
    public static JsonScan.TypedScanResult projectBytes(byte[] json, JsonScan.TypedTrieNode root, JsonScan.TypedLeaf[] leaves) {
        return projectBytes(json, root, leaves, true);
    }

    @TruffleBoundary
    public static JsonScan.TypedScanResult projectBytes(byte[] json, JsonScan.TypedTrieNode root, JsonScan.TypedLeaf[] leaves,
                                               boolean firstWins) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        return projectBytes(json, 0, json.length, root, leaves, firstWins);
    }

    @TruffleBoundary
    public static JsonScan.TypedScanResult projectByteBuffer(ByteBuffer json, JsonScan.TypedTrieNode root, JsonScan.TypedLeaf[] leaves) {
        return projectByteBuffer(json, root, leaves, true);
    }

    @TruffleBoundary
    public static JsonScan.TypedScanResult projectByteBuffer(ByteBuffer json, JsonScan.TypedTrieNode root, JsonScan.TypedLeaf[] leaves,
                                                    boolean firstWins) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        ByteBuffer dup = json.duplicate();
        if (dup.hasArray()) {
            int offset = dup.arrayOffset() + dup.position();
            return projectBytes(dup.array(), offset, dup.remaining(), root, leaves, firstWins);
        }
        byte[] bytes = new byte[dup.remaining()];
        dup.get(bytes);
        return projectBytes(bytes, 0, bytes.length, root, leaves, firstWins);
    }

    public static JsonScan.TypedScanResult projectBytes(byte[] json, int off, int len, JsonScan.TypedTrieNode root,
                                               JsonScan.TypedLeaf[] leaves, boolean firstWins) {
        if (off < 0 || len < 0 || off + len > json.length) {
            throw new IndexOutOfBoundsException();
        }
        return new TypedScanner().scan(json, off, off + len, root, leaves, firstWins);
    }

    @CompilerDirectives.EarlyEscapeAnalysis
    public static JsonScan.TypedScanResult projectBytesPartialEvaluated(byte[] json, JsonScan.TypedTrieNode root,
                                                               JsonScan.TypedLeaf[] leaves, boolean firstWins) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        return new TypedScanner().scan(json, 0, json.length, root, leaves, firstWins);
    }

    @TruffleBoundary
    public static JsonScan.TypedScanResult projectTruffleString(TruffleString json, JsonScan.TypedTrieNode root,
                                                       JsonScan.TypedLeaf[] leaves, boolean firstWins) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        TruffleString utf8 = json.switchEncodingUncached(TruffleString.Encoding.UTF_8);
        if (utf8.isManaged()) {
            TruffleString.MaterializeNode.getUncached().execute(utf8, TruffleString.Encoding.UTF_8);
            InternalByteArray internal = TruffleString.GetInternalByteArrayNode.getUncached()
                    .execute(utf8, TruffleString.Encoding.UTF_8);
            JsonScan.TypedScanResult result = new TypedScanner().scan(
                    internal.getArray(), internal.getOffset(), internal.getEnd(),
                    root, leaves, firstWins);
            result.truffleSource = utf8;
            result.truffleOffset = internal.getOffset();
            return result;
        }
        return new TypedScanner().scan(
                utf8, 0, utf8.byteLength(TruffleString.Encoding.UTF_8),
                root, leaves, firstWins);
    }


static final class TypedScanner {
    // Hoisted so the byte... varargs does not allocate a needle array per call.
    @CompilationFinal(dimensions = 1)
    private static final byte[] STRING_END = {'"', '\\'};
    private static final int MAX_DEPTH = 512;

    private static boolean isDigit(byte b) {
        int c = b & 0xff;
        return c >= '0' && c <= '9';
    }

    private static void append(StringBuilder sb, char c) {
        sb.append(c);
    }

    private byte[] buf;
    private TruffleString truffleBuf;
    private TruffleString.ReadByteNode readByte;
    private int pos;
    private int end;
    private int depth;
    private boolean typedFirstWins;
    private int typedPending;
    private boolean typedComplete;
    private int strStart;
    private boolean strEscaped;

    JsonScan.TypedScanResult scan(
            byte[] json, int from, int to, JsonScan.TypedTrieNode root, JsonScan.TypedLeaf[] leaves,
            boolean firstWins) {
        this.buf = json;
        return scan(from, to, root, leaves, firstWins, new JsonScan.TypedScanResult(json, leaves.length));
    }

    JsonScan.TypedScanResult scan(
            TruffleString json, int from, int to, JsonScan.TypedTrieNode root, JsonScan.TypedLeaf[] leaves,
            boolean firstWins) {
        this.truffleBuf = json;
        this.readByte = TruffleString.ReadByteNode.getUncached();
        JsonScan.TypedScanResult result = new JsonScan.TypedScanResult(null, leaves.length);
        result.truffleSource = json;
        return scan(from, to, root, leaves, firstWins, result);
    }

    private JsonScan.TypedScanResult scan(
            int from, int to, JsonScan.TypedTrieNode root, JsonScan.TypedLeaf[] leaves,
            boolean firstWins, JsonScan.TypedScanResult result) {
        this.pos = from;
        this.end = to;
        this.depth = 0;
        this.typedFirstWins = firstWins;
        this.typedPending = leaves.length;
        this.typedComplete = leaves.length == 0;
        try {
            skipWs();
            if (pos >= end) {
                { CompilerDirectives.transferToInterpreter(); throw err("Empty JSON", pos); }
            }
            projectTypedValue(root, result);
            if (!typedComplete) {
                skipWs();
                if (pos != end) {
                    { CompilerDirectives.transferToInterpreter(); throw err("Trailing content", pos); }
                }
            }
            return result;
        } finally {
            this.buf = null;
            this.truffleBuf = null;
            this.readByte = null;
            this.typedComplete = false;
            this.typedPending = 0;
        }
    }

    @CompilerDirectives.EarlyInline
    private byte sourceByte(int index) {
        if (buf != null) {
            return buf[index];
        }
        return (byte) readByte.execute(
                truffleBuf, index, TruffleString.Encoding.UTF_8);
    }

    @CompilerDirectives.EarlyInline
    private void skipWs() {
        while (pos < end) {
            byte c = sourceByte(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pos++;
            } else {
                break;
            }
        }
    }

    @TruffleBoundary
    private static JsonException err(String message, int pos) {
        return new JsonException(message, pos);
    }

    private void projectTypedValue(JsonScan.TypedTrieNode node, JsonScan.TypedScanResult result) {
        if (node.slot >= 0) {
            captureTypedLeaf(node.slot, node.leaf, result);
            return;
        }
        if (pos >= end) {
            { CompilerDirectives.transferToInterpreter(); throw err("Unexpected end of JSON", pos); }
        }
        switch (node.containerKind) {
            case JsonScan.TypedTrieNode.OBJECT_ONLY -> {
                if (sourceByte(pos) != '{') {
                    { CompilerDirectives.transferToInterpreter(); throw err("Expected object", pos); }
                }
                projectTypedObject(node, result);
            }
            case JsonScan.TypedTrieNode.ARRAY_ONLY -> {
                if (sourceByte(pos) != '[') {
                    { CompilerDirectives.transferToInterpreter(); throw err("Expected array", pos); }
                }
                projectTypedArray(node, result);
            }
            case JsonScan.TypedTrieNode.EITHER -> {
                byte c = sourceByte(pos);
                if (c == '{') {
                    projectTypedObject(node, result);
                } else if (c == '[') {
                    projectTypedArray(node, result);
                } else {
                    { CompilerDirectives.transferToInterpreter(); throw err("Expected object or array", pos); }
                }
            }
            default -> skipValueOnDemand();
        }
    }

    private void captureTypedLeaf(int slot, JsonScan.TypedLeaf leaf, JsonScan.TypedScanResult result) {
        if (typedFirstWins && result.states[slot] != JsonScan.TypedScanResult.MISSING) {
            skipValueOnDemand();
            return;
        }
        if (leaf.nullable && startsWithLiteral("null")) {
            pos += 4;
            result.values[slot] = null;
            result.states[slot] = JsonScan.TypedScanResult.VALUE;
            markTypedFilled();
            return;
        }
        switch (leaf.kind) {
            case JsonScan.TypedLeaf.STRING, JsonScan.TypedLeaf.TRUFFLE_STRING ->
                    captureTypedString(slot, leaf, result);
            case JsonScan.TypedLeaf.INT, JsonScan.TypedLeaf.LONG -> captureTypedInteger(slot, leaf, result);
            case JsonScan.TypedLeaf.DOUBLE -> captureTypedDouble(slot, result);
            case JsonScan.TypedLeaf.BOOLEAN -> {
                if (startsWithLiteral("true")) {
                    pos += 4;
                    result.values[slot] = Boolean.TRUE;
                } else if (startsWithLiteral("false")) {
                    pos += 5;
                    result.values[slot] = Boolean.FALSE;
                } else {
                    { CompilerDirectives.transferToInterpreter(); throw err("Expected boolean", pos); }
                }
                result.states[slot] = JsonScan.TypedScanResult.VALUE;
            }
            case JsonScan.TypedLeaf.NULL -> {
                if (!startsWithLiteral("null")) {
                    { CompilerDirectives.transferToInterpreter(); throw err("Expected null", pos); }
                }
                pos += 4;
                result.values[slot] = null;
                result.states[slot] = JsonScan.TypedScanResult.VALUE;
            }
            case JsonScan.TypedLeaf.DYNAMIC -> {
                result.values[slot] = parseDynamicTyped(leaf.dynamic);
                result.states[slot] = JsonScan.TypedScanResult.VALUE;
            }
            case JsonScan.TypedLeaf.ANY -> captureTypedAny(slot, leaf, result);
            default -> { CompilerDirectives.transferToInterpreter(); throw err("Unsupported typed leaf", pos); }
        }
        markTypedFilled();
    }

    private void markTypedFilled() {
        if (!typedFirstWins || typedComplete) {
            return;
        }
        typedPending--;
        if (typedPending <= 0) {
            typedComplete = true;
        }
    }

    private boolean startsWithLiteral(String literal) {
        if (end - pos < literal.length()) {
            return false;
        }
        for (int i = 0; i < literal.length(); i++) {
            if (sourceByte(pos + i) != (byte) literal.charAt(i)) {
                return false;
            }
        }
        int after = pos + literal.length();
        return after == end || isValueDelimiter(sourceByte(after));
    }

    private static boolean isValueDelimiter(byte c) {
        return c == ',' || c == ']' || c == '}' || c == ' '
                || c == '\t' || c == '\r' || c == '\n';
    }

    private void captureTypedString(int slot, JsonScan.TypedLeaf leaf, JsonScan.TypedScanResult result) {
        if (pos >= end || sourceByte(pos) != '"') {
            { CompilerDirectives.transferToInterpreter(); throw err("Expected string", pos); }
        }
        scanString();
        int close = pos;
        if (strEscaped) {
            result.starts[slot] = strStart;
            result.lengths[slot] = close - strStart;
            result.states[slot] = JsonScan.TypedScanResult.ESCAPED_SLICE;
        } else {
            result.starts[slot] = strStart;
            result.lengths[slot] = close - strStart;
            result.states[slot] = JsonScan.TypedScanResult.SLICE;
        }
        pos = close + 1;
    }

    /**
     * Capture a value whose type the query did not state. Scalars take the same paths the
     * typed leaves use; objects, arrays, and numbers too wide for a {@code long} are recorded
     * as raw text for the untyped parser to read during decode.
     */
    private void captureTypedAny(int slot, JsonScan.TypedLeaf leaf, JsonScan.TypedScanResult result) {
        if (pos >= end) {
            { CompilerDirectives.transferToInterpreter(); throw err("Unexpected end of JSON", pos); }
        }
        byte c = sourceByte(pos);
        if (c == '"') {
            captureTypedString(slot, leaf, result);
            return;
        }
        if (c == 't' && startsWithLiteral("true")) {
            pos += 4;
            result.values[slot] = Boolean.TRUE;
            result.states[slot] = JsonScan.TypedScanResult.VALUE;
            return;
        }
        if (c == 'f' && startsWithLiteral("false")) {
            pos += 5;
            result.values[slot] = Boolean.FALSE;
            result.states[slot] = JsonScan.TypedScanResult.VALUE;
            return;
        }
        if (c == 'n' && startsWithLiteral("null")) {
            pos += 4;
            result.values[slot] = null;
            result.states[slot] = JsonScan.TypedScanResult.VALUE;
            return;
        }
        if (c != '{' && c != '[') {
            captureTypedAnyNumber(slot, result);
            return;
        }
        int start = pos;
        skipValueOnDemand();
        result.starts[slot] = start;
        result.lengths[slot] = pos - start;
        result.states[slot] = JsonScan.TypedScanResult.RAW;
    }

    /** {@code long} when the literal is an integer that fits, raw text otherwise. */
    private void captureTypedAnyNumber(int slot, JsonScan.TypedScanResult result) {
        if (sourceByte(pos) != '-' && !isDigit(sourceByte(pos))) {
            { CompilerDirectives.transferToInterpreter(); throw err("Expected value", pos); }
        }
        int start = pos;
        boolean negative = false;
        if (sourceByte(pos) == '-') {
            negative = true;
            pos++;
            if (pos >= end || !isDigit(sourceByte(pos))) {
                { CompilerDirectives.transferToInterpreter(); throw err("Invalid number", pos); }
            }
        }
        long val = 0;
        boolean overflow = false;
        if (sourceByte(pos) == '0') {
            pos++;
            if (pos < end && isDigit(sourceByte(pos))) {
                { CompilerDirectives.transferToInterpreter(); throw err("Leading zeros are not allowed", pos); }
            }
        } else {
            while (pos < end && isDigit(sourceByte(pos))) {
                int digit = sourceByte(pos) - '0';
                if (!overflow) {
                    if (val > (Long.MAX_VALUE - digit) / 10) {
                        overflow = true;
                    } else {
                        val = val * 10 + digit;
                    }
                }
                pos++;
            }
        }
        boolean isFloat = false;
        if (pos < end && sourceByte(pos) == '.') {
            isFloat = true;
            pos++;
            if (pos >= end || !isDigit(sourceByte(pos))) {
                { CompilerDirectives.transferToInterpreter(); throw err("Invalid number", pos); }
            }
            while (pos < end && isDigit(sourceByte(pos))) {
                pos++;
            }
        }
        if (pos < end && (sourceByte(pos) == 'e' || sourceByte(pos) == 'E')) {
            isFloat = true;
            pos++;
            if (pos < end && (sourceByte(pos) == '+' || sourceByte(pos) == '-')) {
                pos++;
            }
            if (pos >= end || !isDigit(sourceByte(pos))) {
                { CompilerDirectives.transferToInterpreter(); throw err("Invalid number", pos); }
            }
            while (pos < end && isDigit(sourceByte(pos))) {
                pos++;
            }
        }
        if (!isFloat && !overflow) {
            result.values[slot] = Long.valueOf(negative ? -val : val);
            result.states[slot] = JsonScan.TypedScanResult.VALUE;
        } else {
            result.starts[slot] = start;
            result.lengths[slot] = pos - start;
            result.states[slot] = JsonScan.TypedScanResult.RAW;
        }
    }

    private void captureTypedInteger(int slot, JsonScan.TypedLeaf leaf, JsonScan.TypedScanResult result) {
        if (pos >= end || (sourceByte(pos) != '-' && !isDigit(sourceByte(pos)))) {
            { CompilerDirectives.transferToInterpreter(); throw err("Expected integer", pos); }
        }
        int start = pos;
        boolean negative = false;
        if (sourceByte(pos) == '-') {
            negative = true;
            pos++;
            if (pos >= end || !isDigit(sourceByte(pos))) {
                { CompilerDirectives.transferToInterpreter(); throw err("Invalid number", pos); }
            }
        }
        long val = 0;
        boolean overflow = false;
        if (sourceByte(pos) == '0') {
            pos++;
            if (pos < end && isDigit(sourceByte(pos))) {
                { CompilerDirectives.transferToInterpreter(); throw err("Leading zeros are not allowed", pos); }
            }
        } else {
            while (pos < end && isDigit(sourceByte(pos))) {
                int digit = sourceByte(pos) - '0';
                if (!overflow) {
                    if (val > (Long.MAX_VALUE - digit) / 10) {
                        overflow = true;
                    } else {
                        val = val * 10 + digit;
                    }
                }
                pos++;
            }
        }
        if (pos < end && (sourceByte(pos) == '.' || sourceByte(pos) == 'e' || sourceByte(pos) == 'E')) {
            { CompilerDirectives.transferToInterpreter(); throw err("Expected integer", pos); }
        }
        long finalVal = negative ? -val : val;
        boolean intRange = leaf.kind == JsonScan.TypedLeaf.INT
                && finalVal >= Integer.MIN_VALUE && finalVal <= Integer.MAX_VALUE;
        if (overflow || (leaf.kind == JsonScan.TypedLeaf.INT && !intRange)) {
            // Out of the leaf's range: leave a slice so decode reports the range error, and so
            // integers beyond Long.MAX_VALUE can still be parsed as BigInt.
            result.starts[slot] = start;
            result.lengths[slot] = pos - start;
            result.states[slot] = JsonScan.TypedScanResult.SLICE;
        } else {
            // Box once, in the leaf's own type, so decode never has to re-box.
            result.values[slot] = intRange
                    ? (Object) Integer.valueOf((int) finalVal)
                    : (Object) Long.valueOf(finalVal);
            result.states[slot] = JsonScan.TypedScanResult.VALUE;
        }
    }

    /**
     * Reads the literal once, keeping the significant digits and the decimal exponent as it
     * goes, and converts them with {@link EiselLemire}. Only literals wider than
     * {@link EiselLemire#MAX_SIGNIFICANT_DIGITS} significant digits are left as a slice for
     * {@code TruffleString.ParseDoubleNode} to read a second time.
     */
    private void captureTypedDouble(int slot, JsonScan.TypedScanResult result) {
        if (pos >= end || (sourceByte(pos) != '-' && !isDigit(sourceByte(pos)))) {
            { CompilerDirectives.transferToInterpreter(); throw err("Expected number", pos); }
        }
        int start = pos;
        boolean isFloat = false;
        boolean negative = false;
        if (sourceByte(pos) == '-') {
            negative = true;
            pos++;
            if (pos >= end || !isDigit(sourceByte(pos))) {
                { CompilerDirectives.transferToInterpreter(); throw err("Invalid number", pos); }
            }
        }
        long intPart = 0;
        boolean intOverflow = false;
        long digits = 0;
        int significantDigits = 0;
        boolean tooManyDigits = false;
        if (sourceByte(pos) == '0') {
            pos++;
            if (pos < end && isDigit(sourceByte(pos))) {
                { CompilerDirectives.transferToInterpreter(); throw err("Leading zeros are not allowed", pos); }
            }
        } else {
            while (pos < end && isDigit(sourceByte(pos))) {
                int digit = sourceByte(pos) - '0';
                if (!intOverflow) {
                    if (intPart > (Long.MAX_VALUE - digit) / 10) {
                        intOverflow = true;
                    } else {
                        intPart = intPart * 10 + digit;
                    }
                }
                if (significantDigits < EiselLemire.MAX_SIGNIFICANT_DIGITS) {
                    digits = digits * 10 + digit;
                    significantDigits++;
                } else {
                    tooManyDigits = true;
                }
                pos++;
            }
        }
        long fractionDigits = 0;
        if (pos < end && sourceByte(pos) == '.') {
            isFloat = true;
            pos++;
            if (pos >= end || !isDigit(sourceByte(pos))) {
                { CompilerDirectives.transferToInterpreter(); throw err("Invalid number", pos); }
            }
            while (pos < end && isDigit(sourceByte(pos))) {
                int digit = sourceByte(pos) - '0';
                fractionDigits++;
                if (digits == 0 && digit == 0) {
                    // Leading zeros of a value below one carry no significance of their own;
                    // fractionDigits already accounts for them in the exponent.
                    pos++;
                    continue;
                }
                if (significantDigits < EiselLemire.MAX_SIGNIFICANT_DIGITS) {
                    digits = digits * 10 + digit;
                    significantDigits++;
                } else {
                    tooManyDigits = true;
                }
                pos++;
            }
        }
        long literalExponent = 0;
        if (pos < end && (sourceByte(pos) == 'e' || sourceByte(pos) == 'E')) {
            isFloat = true;
            pos++;
            boolean negativeExponent = false;
            if (pos < end && (sourceByte(pos) == '+' || sourceByte(pos) == '-')) {
                negativeExponent = sourceByte(pos) == '-';
                pos++;
            }
            if (pos >= end || !isDigit(sourceByte(pos))) {
                { CompilerDirectives.transferToInterpreter(); throw err("Invalid number", pos); }
            }
            while (pos < end && isDigit(sourceByte(pos))) {
                if (literalExponent < 100_000) {
                    literalExponent = literalExponent * 10 + (sourceByte(pos) - '0');
                }
                pos++;
            }
            if (negativeExponent) {
                literalExponent = -literalExponent;
            }
        }
        if (!isFloat && !intOverflow) {
            // Integer literal captured for double slot (e.g. 42 -> 42.0)
            double d = negative ? (double) -intPart : (double) intPart;
            result.values[slot] = Double.valueOf(d);
            result.states[slot] = JsonScan.TypedScanResult.VALUE;
        } else if (!tooManyDigits) {
            result.values[slot] = Double.valueOf(EiselLemire.toDouble(
                    negative, digits, literalExponent - fractionDigits));
            result.states[slot] = JsonScan.TypedScanResult.VALUE;
        } else {
            // Wider than the fast path is exact for: keep a slice for the full parser.
            result.starts[slot] = start;
            result.lengths[slot] = pos - start;
            result.states[slot] = JsonScan.TypedScanResult.SLICE;
        }
    }

    private void projectTypedObject(JsonScan.TypedTrieNode node, JsonScan.TypedScanResult result) {
        pos++;
        enterDepth();
        try {
            skipWs();
            if (pos < end && sourceByte(pos) == '}') {
                pos++;
                return;
            }
            while (true) {
                skipWs();
                if (pos >= end || sourceByte(pos) != '"') {
                    { CompilerDirectives.transferToInterpreter(); throw err("Expected object key", pos); }
                }
                int quote = pos;
                int keyStart = pos + 1;
                int keyEnd = scanRawKey();
                int match;
                if (keyEnd >= 0) {
                    match = findTypedKeywordEdge(node, keyStart, keyEnd - keyStart);
                    pos = keyEnd + 1;
                } else {
                    pos = quote;
                    String decoded = parseStringValue();
                    match = findTypedKeywordEdge(node, decoded);
                }
                skipWs();
                if (pos >= end || sourceByte(pos) != ':') {
                    { CompilerDirectives.transferToInterpreter(); throw err("Expected ':'", pos); }
                }
                pos++;
                skipWs();
                if (match < 0) {
                    skipValueOnDemand();
                } else {
                    projectTypedEdge(node, match, result);
                }
                if (typedComplete) {
                    return;
                }
                if (typedFirstWins && match >= 0 && isSubtreeComplete(node, result)) {
                    skipObjectRemainder();
                    return;
                }
                skipWs();
                if (pos >= end) {
                    { CompilerDirectives.transferToInterpreter(); throw err("Unclosed object", pos); }
                }
                byte c = sourceByte(pos++);
                if (c == '}') {
                    return;
                }
                if (c != ',') {
                    { CompilerDirectives.transferToInterpreter(); throw err("Expected ',' or '}'", pos); }
                }
            }
        } finally {
            depth--;
        }
    }

    private void projectTypedArray(JsonScan.TypedTrieNode node, JsonScan.TypedScanResult result) {
        pos++;
        enterDepth();
        int index = 0;
        try {
            skipWs();
            if (pos < end && sourceByte(pos) == ']') {
                pos++;
                return;
            }
            while (true) {
                skipWs();
                int match = findTypedIndexEdge(node, index++);
                if (match < 0) {
                    skipValueOnDemand();
                } else {
                    projectTypedEdge(node, match, result);
                }
                if (typedComplete) {
                    return;
                }
                if (typedFirstWins && match >= 0 && isSubtreeComplete(node, result)) {
                    skipArrayRemainder();
                    return;
                }
                skipWs();
                if (pos >= end) {
                    { CompilerDirectives.transferToInterpreter(); throw err("Unclosed array", pos); }
                }
                byte c = sourceByte(pos++);
                if (c == ']') {
                    return;
                }
                if (c != ',') {
                    { CompilerDirectives.transferToInterpreter(); throw err("Expected ',' or ']'", pos); }
                }
            }
        } finally {
            depth--;
        }
    }

    private void enterDepth() {
        depth++;
        if (depth > MAX_DEPTH) {
            { CompilerDirectives.transferToInterpreter(); throw err("Nesting too deep", pos); }
        }
    }

    private int findTypedKeywordEdge(JsonScan.TypedTrieNode node, int start, int len) {
        JsonScan.TypedTrieEdge[] edges = node.edges;
        for (int i = 0; i < edges.length; i++) {
            if (edges[i].isKeyword() && sameBytes(edges[i], start, len)) {
                return i;
            }
        }
        return -1;
    }

    @TruffleBoundary
    private static int findTypedKeywordEdge(JsonScan.TypedTrieNode node, String decoded) {
        JsonScan.TypedTrieEdge[] edges = node.edges;
        for (int i = 0; i < edges.length; i++) {
            if (edges[i].isKeyword() && decoded.equals(edges[i].name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Recurse into one edge's child. Written as an exploded loop rather than an indexed load so
     * that partial evaluation sees a constant {@link JsonScan.TypedTrieNode} in each unrolled body; a
     * phi over the edges would make the child opaque and stop the schema from specializing.
     */
    @ExplodeLoop
    private void projectTypedEdge(JsonScan.TypedTrieNode node, int edgeIndex, JsonScan.TypedScanResult result) {
        JsonScan.TypedTrieEdge[] edges = node.edges;
        for (int i = 0; i < edges.length; i++) {
            if (i == edgeIndex) {
                projectTypedValue(edges[i].child, result);
                return;
            }
        }
        { CompilerDirectives.transferToInterpreter(); throw err("Unknown schema edge", pos); }
    }

    private static boolean isSubtreeComplete(JsonScan.TypedTrieNode node, JsonScan.TypedScanResult result) {
        int[] slots = node.subtreeSlots;
        for (int i = 0; i < slots.length; i++) {
            if (result.states[slots[i]] == JsonScan.TypedScanResult.MISSING) {
                return false;
            }
        }
        return true;
    }

    private static int findTypedIndexEdge(JsonScan.TypedTrieNode node, int index) {
        JsonScan.TypedTrieEdge[] edges = node.edges;
        for (int i = 0; i < edges.length; i++) {
            if (!edges[i].isKeyword() && edges[i].index == index) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Recursion here is bounded by document depth, not by the schema, so it stays a boundary
     * even on the partial-evaluation path; otherwise graph size would track the input.
     */

    /**
     * Thin non-boundary wrapper: the static boundary never receives {@code this}.
     */
    private void skipValueOnDemand() {
        if (buf != null) {
            pos = skipValueBytes(buf, pos, end, depth);
        } else {
            pos = skipValueTruffle(truffleBuf, readByte, pos, end, depth);
        }
    }

    @TruffleBoundary
    private static int skipValueBytes(byte[] buf, int pos, int end, int depth) {
        if (pos >= end) {
            CompilerDirectives.transferToInterpreter();
            throw err("Unexpected end of JSON", pos);
        }
        byte c = buf[pos];
        if (c == '"') {
            return skipStringBytes(buf, pos, end);
        }
        if (c == '{') {
            return skipObjectBytes(buf, pos, end, depth);
        }
        if (c == '[') {
            return skipArrayBytes(buf, pos, end, depth);
        }
        int start = pos;
        while (pos < end && !isValueDelimiter(buf[pos])) {
            pos++;
        }
        if (pos == start) {
            CompilerDirectives.transferToInterpreter();
            throw err("Expected value", pos);
        }
        return pos;
    }

    @TruffleBoundary
    private static int skipValueTruffle(TruffleString truffleBuf,
                                        TruffleString.ReadByteNode readByte,
                                        int pos, int end, int depth) {
        if (pos >= end) {
            CompilerDirectives.transferToInterpreter();
            throw err("Unexpected end of JSON", pos);
        }
        byte c = (byte) readByte.execute(truffleBuf, pos, TruffleString.Encoding.UTF_8);
        if (c == '"') {
            return skipStringTruffle(truffleBuf, readByte, pos, end);
        }
        if (c == '{') {
            return skipObjectTruffle(truffleBuf, readByte, pos, end, depth);
        }
        if (c == '[') {
            return skipArrayTruffle(truffleBuf, readByte, pos, end, depth);
        }
        int start = pos;
        while (pos < end) {
            c = (byte) readByte.execute(truffleBuf, pos, TruffleString.Encoding.UTF_8);
            if (isValueDelimiter(c)) {
                break;
            }
            pos++;
        }
        if (pos == start) {
            CompilerDirectives.transferToInterpreter();
            throw err("Expected value", pos);
        }
        return pos;
    }

    private static int enterDepthStatic(int depth, int pos) {
        depth++;
        if (depth > MAX_DEPTH) {
            CompilerDirectives.transferToInterpreter();
            throw err("Nesting too deep", pos);
        }
        return depth;
    }

    private static int skipObjectBytes(byte[] buf, int pos, int end, int depth) {
        pos++;
        depth = enterDepthStatic(depth, pos);
        pos = skipWsBytes(buf, pos, end);
        if (pos < end && buf[pos] == '}') {
            return pos + 1;
        }
        while (true) {
            pos = skipWsBytes(buf, pos, end);
            if (pos >= end || buf[pos] != '"') {
                CompilerDirectives.transferToInterpreter();
                throw err("Expected object key", pos);
            }
            pos = skipStringBytes(buf, pos, end);
            pos = skipWsBytes(buf, pos, end);
            if (pos >= end || buf[pos] != ':') {
                CompilerDirectives.transferToInterpreter();
                throw err("Expected ':'", pos);
            }
            pos++;
            pos = skipWsBytes(buf, pos, end);
            pos = skipValueBytes(buf, pos, end, depth);
            pos = skipWsBytes(buf, pos, end);
            if (pos >= end) {
                CompilerDirectives.transferToInterpreter();
                throw err("Unclosed object", pos);
            }
            byte c = buf[pos++];
            if (c == '}') {
                return pos;
            }
            if (c != ',') {
                CompilerDirectives.transferToInterpreter();
                throw err("Expected ',' or '}'", pos);
            }
        }
    }

    private static int skipObjectTruffle(TruffleString truffleBuf,
                                         TruffleString.ReadByteNode readByte,
                                         int pos, int end, int depth) {
        pos++;
        depth = enterDepthStatic(depth, pos);
        pos = skipWsTruffle(truffleBuf, readByte, pos, end);
        if (pos < end && (byte) readByte.execute(truffleBuf, pos, TruffleString.Encoding.UTF_8) == '}') {
            return pos + 1;
        }
        while (true) {
            pos = skipWsTruffle(truffleBuf, readByte, pos, end);
            if (pos >= end || (byte) readByte.execute(truffleBuf, pos, TruffleString.Encoding.UTF_8) != '"') {
                CompilerDirectives.transferToInterpreter();
                throw err("Expected object key", pos);
            }
            pos = skipStringTruffle(truffleBuf, readByte, pos, end);
            pos = skipWsTruffle(truffleBuf, readByte, pos, end);
            if (pos >= end || (byte) readByte.execute(truffleBuf, pos, TruffleString.Encoding.UTF_8) != ':') {
                CompilerDirectives.transferToInterpreter();
                throw err("Expected ':'", pos);
            }
            pos++;
            pos = skipWsTruffle(truffleBuf, readByte, pos, end);
            pos = skipValueTruffle(truffleBuf, readByte, pos, end, depth);
            pos = skipWsTruffle(truffleBuf, readByte, pos, end);
            if (pos >= end) {
                CompilerDirectives.transferToInterpreter();
                throw err("Unclosed object", pos);
            }
            byte c = (byte) readByte.execute(truffleBuf, pos++, TruffleString.Encoding.UTF_8);
            if (c == '}') {
                return pos;
            }
            if (c != ',') {
                CompilerDirectives.transferToInterpreter();
                throw err("Expected ',' or '}'", pos);
            }
        }
    }

    private static int skipArrayBytes(byte[] buf, int pos, int end, int depth) {
        pos++;
        depth = enterDepthStatic(depth, pos);
        pos = skipWsBytes(buf, pos, end);
        if (pos < end && buf[pos] == ']') {
            return pos + 1;
        }
        while (true) {
            pos = skipWsBytes(buf, pos, end);
            pos = skipValueBytes(buf, pos, end, depth);
            pos = skipWsBytes(buf, pos, end);
            if (pos >= end) {
                CompilerDirectives.transferToInterpreter();
                throw err("Unclosed array", pos);
            }
            byte c = buf[pos++];
            if (c == ']') {
                return pos;
            }
            if (c != ',') {
                CompilerDirectives.transferToInterpreter();
                throw err("Expected ',' or ']'", pos);
            }
        }
    }

    private static int skipArrayTruffle(TruffleString truffleBuf,
                                        TruffleString.ReadByteNode readByte,
                                        int pos, int end, int depth) {
        pos++;
        depth = enterDepthStatic(depth, pos);
        pos = skipWsTruffle(truffleBuf, readByte, pos, end);
        if (pos < end && (byte) readByte.execute(truffleBuf, pos, TruffleString.Encoding.UTF_8) == ']') {
            return pos + 1;
        }
        while (true) {
            pos = skipWsTruffle(truffleBuf, readByte, pos, end);
            pos = skipValueTruffle(truffleBuf, readByte, pos, end, depth);
            pos = skipWsTruffle(truffleBuf, readByte, pos, end);
            if (pos >= end) {
                CompilerDirectives.transferToInterpreter();
                throw err("Unclosed array", pos);
            }
            byte c = (byte) readByte.execute(truffleBuf, pos++, TruffleString.Encoding.UTF_8);
            if (c == ']') {
                return pos;
            }
            if (c != ',') {
                CompilerDirectives.transferToInterpreter();
                throw err("Expected ',' or ']'", pos);
            }
        }
    }

    private static int skipWsBytes(byte[] buf, int pos, int end) {
        while (pos < end) {
            byte c = buf[pos];
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pos++;
            } else {
                break;
            }
        }
        return pos;
    }

    private static int skipWsTruffle(TruffleString truffleBuf,
                                     TruffleString.ReadByteNode readByte,
                                     int pos, int end) {
        while (pos < end) {
            byte c = (byte) readByte.execute(truffleBuf, pos, TruffleString.Encoding.UTF_8);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pos++;
            } else {
                break;
            }
        }
        return pos;
    }

    private void skipObjectRemainder() {
        if (buf != null) {
            pos = skipObjectRemainderBytes(buf, pos, end);
        } else {
            pos = skipObjectRemainderTruffle(truffleBuf, readByte, pos, end);
        }
    }

    private void skipArrayRemainder() {
        if (buf != null) {
            pos = skipArrayRemainderBytes(buf, pos, end);
        } else {
            pos = skipArrayRemainderTruffle(truffleBuf, readByte, pos, end);
        }
    }

    @TruffleBoundary
    private static int skipObjectRemainderBytes(byte[] buf, int pos, int end) {
        int openBraces = 1;
        while (pos < end) {
            byte c = buf[pos++];
            if (c == '"') {
                pos = skipStringBytes(buf, pos - 1, end);
            } else if (c == '{') {
                openBraces++;
            } else if (c == '}') {
                openBraces--;
                if (openBraces == 0) {
                    return pos;
                }
            }
        }
        CompilerDirectives.transferToInterpreter();
        throw err("Unclosed object", pos);
    }

    @TruffleBoundary
    private static int skipObjectRemainderTruffle(TruffleString truffleBuf,
                                                  TruffleString.ReadByteNode readByte,
                                                  int pos, int end) {
        int openBraces = 1;
        while (pos < end) {
            byte c = (byte) readByte.execute(truffleBuf, pos++, TruffleString.Encoding.UTF_8);
            if (c == '"') {
                pos = skipStringTruffle(truffleBuf, readByte, pos - 1, end);
            } else if (c == '{') {
                openBraces++;
            } else if (c == '}') {
                openBraces--;
                if (openBraces == 0) {
                    return pos;
                }
            }
        }
        CompilerDirectives.transferToInterpreter();
        throw err("Unclosed object", pos);
    }

    @TruffleBoundary
    private static int skipArrayRemainderBytes(byte[] buf, int pos, int end) {
        int openBrackets = 1;
        while (pos < end) {
            byte c = buf[pos++];
            if (c == '"') {
                pos = skipStringBytes(buf, pos - 1, end);
            } else if (c == '[') {
                openBrackets++;
            } else if (c == ']') {
                openBrackets--;
                if (openBrackets == 0) {
                    return pos;
                }
            }
        }
        CompilerDirectives.transferToInterpreter();
        throw err("Unclosed array", pos);
    }

    @TruffleBoundary
    private static int skipArrayRemainderTruffle(TruffleString truffleBuf,
                                                 TruffleString.ReadByteNode readByte,
                                                 int pos, int end) {
        int openBrackets = 1;
        while (pos < end) {
            byte c = (byte) readByte.execute(truffleBuf, pos++, TruffleString.Encoding.UTF_8);
            if (c == '"') {
                pos = skipStringTruffle(truffleBuf, readByte, pos - 1, end);
            } else if (c == '[') {
                openBrackets++;
            } else if (c == ']') {
                openBrackets--;
                if (openBrackets == 0) {
                    return pos;
                }
            }
        }
        CompilerDirectives.transferToInterpreter();
        throw err("Unclosed array", pos);
    }

    /**
     * Skips a string without decoding it. Static boundary for the byte[] path so
     * the scanner is not the receiver.
     */
    private void skipStringValue() {
        if (buf == null) {
            scanString();
            pos++;
            return;
        }
        pos = skipStringBytes(buf, pos, end);
    }

    @TruffleBoundary
    private static int skipStringBytes(byte[] buf, int pos, int end) {
        if (pos >= end || buf[pos] != '"') {
            CompilerDirectives.transferToInterpreter();
            throw err("Expected string", pos);
        }
        int i = pos + 1;
        while (i <= end) {
            int hit = ArrayUtils.indexOf(buf, i, end, STRING_END);
            if (hit < 0) {
                break;
            }
            if (buf[hit] == '"') {
                return hit + 1;
            }
            i = hit + 2;
        }
        CompilerDirectives.transferToInterpreter();
        throw err("Unterminated string", pos);
    }

    private static int skipStringTruffle(TruffleString truffleBuf,
                                         TruffleString.ReadByteNode readByte,
                                         int pos, int end) {
        if (pos >= end || (byte) readByte.execute(truffleBuf, pos, TruffleString.Encoding.UTF_8) != '"') {
            CompilerDirectives.transferToInterpreter();
            throw err("Expected string", pos);
        }
        pos++;
        while (pos < end) {
            byte c = (byte) readByte.execute(truffleBuf, pos, TruffleString.Encoding.UTF_8);
            if (c == '"') {
                return pos + 1;
            }
            if (c == '\\') {
                pos++;
                if (pos >= end) {
                    CompilerDirectives.transferToInterpreter();
                    throw err("Unterminated string escape", pos);
                }
                pos++;
                continue;
            }
            pos++;
        }
        CompilerDirectives.transferToInterpreter();
        throw err("Unterminated string", pos);
    }


    private void scanString() {
        if (pos >= end || sourceByte(pos) != '"') {
            { CompilerDirectives.transferToInterpreter(); throw err("Expected string", pos); }
        }
        pos++;
        strStart = pos;
        strEscaped = false;
        while (pos < end) {
            byte c = sourceByte(pos);
            if (c == '"') {
                return;
            }
            if (c == '\\') {
                strEscaped = true;
                pos++;
                if (pos >= end) {
                    { CompilerDirectives.transferToInterpreter(); throw err("Unterminated string escape", pos); }
                }
                byte escaped = sourceByte(pos++);
                switch (escaped) {
                    case '"', '\\', '/', 'b', 'f', 'n', 'r', 't':
                        break;
                    case 'u':
                        if (pos + 4 > end) {
                            { CompilerDirectives.transferToInterpreter(); throw err("Invalid unicode escape", pos); }
                        }
                        for (int i = 0; i < 4; i++) {
                            hexDigit(sourceByte(pos + i));
                        }
                        pos += 4;
                        break;
                    default:
                        { CompilerDirectives.transferToInterpreter(); throw err("Invalid escape", pos); }
                }
                continue;
            }
            if ((c & 0xff) < 0x20) {
                { CompilerDirectives.transferToInterpreter(); throw err("Unescaped control character", pos); }
            }
            pos++;
        }
        { CompilerDirectives.transferToInterpreter(); throw err("Unterminated string", pos); }
    }

    /**
     * Returns the index of the closing quote of the key starting at {@code pos}, or -1 if the
     * key contains an escape and needs the slow decoding path. Keys are short, so a vector
     * search measured slower here than the byte loop and is deliberately not used.
     */
    @CompilerDirectives.EarlyInline
    private int scanRawKey() {
        int i = pos + 1;
        while (i < end) {
            byte c = sourceByte(i);
            if (c == '"') {
                return i;
            }
            if (c == '\\') {
                return -1;
            }
            if ((c & 0xff) < 0x20) {
                { CompilerDirectives.transferToInterpreter(); throw err("Unescaped control character", pos); }
            }
            i++;
        }
        { CompilerDirectives.transferToInterpreter(); throw err("Unterminated string", pos); }
    }

    @CompilerDirectives.EarlyInline
    private boolean sameBytes(JsonScan.TypedTrieEdge edge, int start, int len) {
        byte[] want = edge.utf8;
        if (want.length != len) {
            return false;
        }
        if (buf != null) {
            if (len >= 4) {
                int inputFirst4 = ((buf[start] & 0xff) << 24)
                                | ((buf[start + 1] & 0xff) << 16)
                                | ((buf[start + 2] & 0xff) << 8)
                                | (buf[start + 3] & 0xff);
                if (edge.first4 != inputFirst4) {
                    return false;
                }
                if (len == 4) {
                    return true;
                }
                return Arrays.equals(want, 4, len, buf, start + 4, start + len);
            }
            if (len == 3) {
                return want[0] == buf[start] && want[1] == buf[start + 1] && want[2] == buf[start + 2];
            }
            if (len == 2) {
                return want[0] == buf[start] && want[1] == buf[start + 1];
            }
            if (len == 1) {
                return want[0] == buf[start];
            }
            return true;
        }
        for (int i = 0; i < len; i++) {
            if (want[i] != (byte) readByte.execute(truffleBuf, start + i, TruffleString.Encoding.UTF_8)) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    private Object parseDynamicTyped(JsonScan.TypedValueNode node) {
        if (node.kind == JsonScan.TypedValueNode.VECTOR) {
            if (pos >= end || sourceByte(pos) != '[') {
                { CompilerDirectives.transferToInterpreter(); throw err("Expected array", pos); }
            }
            pos++;
            enterDepth();
            ArrayList<Object> values = new ArrayList<>();
            try {
                skipWs();
                if (pos < end && sourceByte(pos) == ']') {
                    pos++;
                    return JsonScan.EMPTY_ARRAY;
                }
                while (true) {
                    skipWs();
                    values.add(parseDynamicTyped(node.child));
                    skipWs();
                    if (pos >= end) {
                        { CompilerDirectives.transferToInterpreter(); throw err("Unclosed array", pos); }
                    }
                    byte c = sourceByte(pos++);
                    if (c == ']') {
                        return values.toArray();
                    }
                    if (c != ',') {
                        { CompilerDirectives.transferToInterpreter(); throw err("Expected ',' or ']'", pos); }
                    }
                }
            } finally {
                depth--;
            }
        }
        JsonScan.TypedLeaf leaf = node.leaf;
        if (leaf.nullable && startsWithLiteral("null")) {
            pos += 4;
            return null;
        }
        return switch (leaf.kind) {
            case JsonScan.TypedLeaf.INT -> {
                if (pos >= end || (sourceByte(pos) != '-' && !isDigit(sourceByte(pos)))) {
                    { CompilerDirectives.transferToInterpreter(); throw err("Expected integer", pos); }
                }
                int start = pos;
                boolean negative = false;
                if (sourceByte(pos) == '-') {
                    negative = true;
                    pos++;
                    if (pos >= end || !isDigit(sourceByte(pos))) {
                        { CompilerDirectives.transferToInterpreter(); throw err("Invalid number", pos); }
                    }
                }
                long val = 0;
                boolean overflow = false;
                if (sourceByte(pos) == '0') {
                    pos++;
                    if (pos < end && isDigit(sourceByte(pos))) {
                        { CompilerDirectives.transferToInterpreter(); throw err("Leading zeros are not allowed", pos); }
                    }
                } else {
                    while (pos < end && isDigit(sourceByte(pos))) {
                        int digit = sourceByte(pos) - '0';
                        if (!overflow) {
                            if (val > (Long.MAX_VALUE - digit) / 10) {
                                overflow = true;
                            } else {
                                val = val * 10 + digit;
                            }
                        }
                        pos++;
                    }
                }
                if (pos < end && (sourceByte(pos) == '.' || sourceByte(pos) == 'e' || sourceByte(pos) == 'E')) {
                    { CompilerDirectives.transferToInterpreter(); throw err("Expected integer", pos); }
                }
                long finalVal = negative ? -val : val;
                if (overflow || finalVal < Integer.MIN_VALUE || finalVal > Integer.MAX_VALUE) {
                    { CompilerDirectives.transferToInterpreter(); throw err("Integer outside schema range", pos); }
                }
                yield Integer.valueOf((int) finalVal);
            }
            case JsonScan.TypedLeaf.LONG -> {
                if (pos >= end || (sourceByte(pos) != '-' && !isDigit(sourceByte(pos)))) {
                    { CompilerDirectives.transferToInterpreter(); throw err("Expected integer", pos); }
                }
                int start = pos;
                boolean negative = false;
                if (sourceByte(pos) == '-') {
                    negative = true;
                    pos++;
                    if (pos >= end || !isDigit(sourceByte(pos))) {
                        { CompilerDirectives.transferToInterpreter(); throw err("Invalid number", pos); }
                    }
                }
                long val = 0;
                boolean overflow = false;
                if (sourceByte(pos) == '0') {
                    pos++;
                    if (pos < end && isDigit(sourceByte(pos))) {
                        { CompilerDirectives.transferToInterpreter(); throw err("Leading zeros are not allowed", pos); }
                    }
                } else {
                    while (pos < end && isDigit(sourceByte(pos))) {
                        int digit = sourceByte(pos) - '0';
                        if (!overflow) {
                            if (val > (Long.MAX_VALUE - digit) / 10) {
                                overflow = true;
                            } else {
                                val = val * 10 + digit;
                            }
                        }
                        pos++;
                    }
                }
                if (pos < end && (sourceByte(pos) == '.' || sourceByte(pos) == 'e' || sourceByte(pos) == 'E')) {
                    { CompilerDirectives.transferToInterpreter(); throw err("Expected integer", pos); }
                }
                if (overflow) {
                    { CompilerDirectives.transferToInterpreter(); throw err("Integer outside schema range", pos); }
                }
                yield Long.valueOf(negative ? -val : val);
            }
            case JsonScan.TypedLeaf.DOUBLE -> {
                int start = pos;
                scanNumber();
                TruffleString number = truffleSlice(start, pos - start, true);
                try {
                    yield Double.valueOf(
                            TruffleString.ParseDoubleNode.getUncached().execute(number));
                } catch (TruffleString.NumberFormatException e) {
                    { CompilerDirectives.transferToInterpreter(); throw err("Number outside schema range", pos); }
                }
            }
            case JsonScan.TypedLeaf.BOOLEAN -> {
                if (startsWithLiteral("true")) {
                    pos += 4;
                    yield Boolean.TRUE;
                }
                if (startsWithLiteral("false")) {
                    pos += 5;
                    yield Boolean.FALSE;
                }
                { CompilerDirectives.transferToInterpreter(); throw err("Expected boolean", pos); }
            }
            case JsonScan.TypedLeaf.STRING -> parseStringValue();
            case JsonScan.TypedLeaf.NULL -> {
                if (!startsWithLiteral("null")) {
                    { CompilerDirectives.transferToInterpreter(); throw err("Expected null", pos); }
                }
                pos += 4;
                yield null;
            }
            case JsonScan.TypedLeaf.TRUFFLE_STRING -> {
                if (pos >= end || sourceByte(pos) != '"') {
                    { CompilerDirectives.transferToInterpreter(); throw err("Expected string", pos); }
                }
                scanString();
                int close = pos;
                TruffleString value;
                if (strEscaped) {
                    value = unescapeTruffleString(strStart, close);
                } else {
                    value = truffleSlice(strStart, close - strStart, true);
                }
                pos = close + 1;
                yield value;
            }
            default -> { CompilerDirectives.transferToInterpreter(); throw err("Unsupported dynamic array leaf", pos); }
        };
    }

    private boolean scanNumber() {
        if (sourceByte(pos) == '-') {
            pos++;
            if (pos >= end || !isDigit(sourceByte(pos))) {
                { CompilerDirectives.transferToInterpreter(); throw err("Invalid number", pos); }
            }
        }
        boolean isFloat = false;
        if (sourceByte(pos) == '0') {
            pos++;
            if (pos < end && isDigit(sourceByte(pos))) {
                { CompilerDirectives.transferToInterpreter(); throw err("Leading zeros are not allowed", pos); }
            }
        } else {
            if (!isDigit(sourceByte(pos))) {
                { CompilerDirectives.transferToInterpreter(); throw err("Invalid number", pos); }
            }
            while (pos < end && isDigit(sourceByte(pos))) {
                pos++;
            }
        }
        if (pos < end && sourceByte(pos) == '.') {
            isFloat = true;
            pos++;
            if (pos >= end || !isDigit(sourceByte(pos))) {
                { CompilerDirectives.transferToInterpreter(); throw err("Invalid number", pos); }
            }
            while (pos < end && isDigit(sourceByte(pos))) {
                pos++;
            }
        }
        if (pos < end && (sourceByte(pos) == 'e' || sourceByte(pos) == 'E')) {
            isFloat = true;
            pos++;
            if (pos < end && (sourceByte(pos) == '+' || sourceByte(pos) == '-')) {
                pos++;
            }
            if (pos >= end || !isDigit(sourceByte(pos))) {
                { CompilerDirectives.transferToInterpreter(); throw err("Invalid number", pos); }
            }
            while (pos < end && isDigit(sourceByte(pos))) {
                pos++;
            }
        }
        return isFloat;
    }

    private String parseStringValue() {
        scanString();
        int start = strStart;
        int closeQuote = pos;
        String s = strEscaped
                ? unescape(start, closeQuote)
                : utf8Slice(buf, truffleBuf, start, closeQuote - start);
        pos = closeQuote + 1;
        return s;
    }

    @TruffleBoundary
    private String unescape(int start, int strEnd) {
        if (truffleBuf != null) {
            return unescapeTruffleString(start, strEnd).toJavaStringUncached();
        }
        StringBuilder sb = new StringBuilder(strEnd - start);
        unescapeInto(sb, start, strEnd);
        return sb.toString();
    }

    private void unescapeInto(StringBuilder sb, int start, int strEnd) {
        int i = start;
        while (i < strEnd) {
            byte c = sourceByte(i);
            if (c != '\\') {
                int run = i;
                while (run < strEnd && sourceByte(run) != '\\') {
                    run++;
                }
                if (sb != null) {
                    sb.append(new String(buf, i, run - i, StandardCharsets.UTF_8));
                }
                i = run;
                continue;
            }
            i++;
            if (i >= strEnd) {
                { CompilerDirectives.transferToInterpreter(); throw err("Unterminated string escape", pos); }
            }
            byte e = sourceByte(i++);
            switch (e) {
                case '"':
                case '\\':
                case '/':
                    append(sb, (char) e);
                    break;
                case 'b':
                    append(sb, '\b');
                    break;
                case 'f':
                    append(sb, '\f');
                    break;
                case 'n':
                    append(sb, '\n');
                    break;
                case 'r':
                    append(sb, '\r');
                    break;
                case 't':
                    append(sb, '\t');
                    break;
                case 'u':
                    if (i + 4 > strEnd) {
                        { CompilerDirectives.transferToInterpreter(); throw err("Invalid unicode escape", pos); }
                    }
                    int cp = hex4(i);
                    i += 4;
                    if (cp >= 0xD800 && cp <= 0xDBFF) {
                        if (i + 6 <= strEnd && sourceByte(i) == '\\' && sourceByte(i + 1) == 'u') {
                            int low = hex4(i + 2);
                            if (low >= 0xDC00 && low <= 0xDFFF) {
                                append(sb, (char) cp);
                                append(sb, (char) low);
                                i += 6;
                                break;
                            }
                        }
                    }
                    append(sb, (char) cp);
                    break;
                default:
                    { CompilerDirectives.transferToInterpreter(); throw err("Invalid escape", pos); }
            }
        }
    }

    private int hexDigit(byte b) {
        int c = b & 0xff;
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        { CompilerDirectives.transferToInterpreter(); throw err("Invalid hex digit", pos); }
    }

    private int hex4(int i) {
        int v = 0;
        for (int k = 0; k < 4; k++) {
            v = (v << 4) | hexDigit(sourceByte(i + k));
        }
        return v;
    }

    @TruffleBoundary
    private TruffleString unescapeTruffleString(int start, int strEnd) {
        TruffleString source;
        int sourceBase;
        if (truffleBuf != null) {
            source = truffleBuf;
            sourceBase = 0;
        } else {
            source = TruffleString.fromByteArrayUncached(
                    buf, start, strEnd - start, TruffleString.Encoding.UTF_8, false);
            sourceBase = start;
        }
        TruffleStringBuilder builder = TruffleStringBuilder.createUTF8(strEnd - start);
        int runStart = start;
        int i = start;
        while (i < strEnd) {
            if (sourceByte(i) != '\\') {
                i++;
                continue;
            }
            if (i > runStart) {
                builder.appendSubstringByteIndexUncached(
                        source, runStart - sourceBase, i - runStart);
            }
            int escapePosition = i++;
            if (i >= strEnd) {
                throw new JsonException("Unterminated string escape", escapePosition);
            }
            int escaped = sourceByte(i++) & 0xff;
            int codePoint = switch (escaped) {
                case '"', '\\', '/' -> escaped;
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> {
                    int high = hex4(i);
                    i += 4;
                    if (high >= 0xD800 && high <= 0xDBFF
                            && i + 6 <= strEnd
                            && sourceByte(i) == '\\'
                            && sourceByte(i + 1) == 'u') {
                        int low = hex4(i + 2);
                        if (low >= 0xDC00 && low <= 0xDFFF) {
                            i += 6;
                            yield Character.toCodePoint((char) high, (char) low);
                        }
                    }
                    yield high;
                }
                default -> throw new JsonException("Invalid escape", escapePosition);
            };
            builder.appendCodePointUncached(codePoint, 1, true);
            runStart = i;
        }
        if (strEnd > runStart) {
            builder.appendSubstringByteIndexUncached(
                    source, runStart - sourceBase, strEnd - runStart);
        }
        return TruffleStringBuilder.ToStringNode.getUncached().execute(builder, true);
    }

    private TruffleString truffleSlice(int start, int len, boolean lazy) {
        if (truffleBuf != null) {
            return truffleBuf.substringByteIndexUncached(
                    start, len, TruffleString.Encoding.UTF_8, lazy);
        }
        return TruffleString.fromByteArrayUncached(
                buf, start, len, TruffleString.Encoding.UTF_8, !lazy);
    }

    @TruffleBoundary
    private static String utf8Slice(
            byte[] buf, TruffleString truffleBuf, int start, int len) {
        if (len == 0) {
            return "";
        }
        if (truffleBuf != null) {
            return truffleBuf.substringByteIndexUncached(
                            start, len, TruffleString.Encoding.UTF_8, true)
                    .toJavaStringUncached();
        }
        return TruffleString.fromByteArrayUncached(buf, start, len, TruffleString.Encoding.UTF_8, true)
                .toJavaStringUncached();
    }
}
}
