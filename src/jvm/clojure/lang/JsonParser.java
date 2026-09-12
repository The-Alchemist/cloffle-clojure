/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/
package clojure.lang;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Host JSON parser that materializes keywordized objects as
 * {@link PersistentShapeMap} / {@link PersistentShapeMap16} (else
 * {@link PersistentHashMap}) and arrays via {@link RT#vector} so small
 * arrays are {@link PersistentTuple}.
 *
 * <p>SIMD structural indexing (simdjson stage 1) is intentionally omitted:
 * typical HTTP bodies are small enough that keyword intern and map
 * construction dominate. Revisit behind this {@code @TruffleBoundary} if
 * large-payload profiles show the scalar scan as the cost.
 *
 * <p>Instances are not thread-safe; the static entry points use a
 * {@link ThreadLocal}. Nested objects reuse a per-parser layout inline
 * cache keyed by interned keyword insertion order.
 */
public final class JsonParser {

    public static final int MAX_DEPTH = 512;
    private static final int IC_SIZE = 16;
    private static final int KEY_CACHE_SIZE = 128;
    private static final int INITIAL_PAIRS = 8;

    private static final ThreadLocal<JsonParser> LOCAL =
            ThreadLocal.withInitial(JsonParser::new);

    private byte[] buf;
    private int pos;
    private int end;
    private int depth;
    private boolean keywordize;
    private IFn keyFn;

    /** Body of the string {@link #scanString} last validated; {@code pos} is then the closing quote. */
    private int strStart;
    private boolean strEscaped;

    private Object[] keys = new Object[INITIAL_PAIRS];
    private Object[] vals = new Object[INITIAL_PAIRS];
    private Object[][] objectKeyFrames = new Object[8][];
    private Object[][] objectValFrames = new Object[8][];
    private Object[][] arrayFrames = new Object[8][];
    private final Layout[] ic = new Layout[IC_SIZE];
    private int icNext;
    private final KeyEntry[] keyCache = new KeyEntry[KEY_CACHE_SIZE];

    private static final class Layout {
        final int n;
        final Keyword[] keys;
        final MapShape shape;

        Layout(int n, Keyword[] keys, MapShape shape) {
            this.n = n;
            this.keys = keys;
            this.shape = shape;
        }
    }

    private static final class KeyEntry {
        final int hash;
        final byte[] utf8;
        final Keyword keyword;

        KeyEntry(int hash, byte[] utf8, Keyword keyword) {
            this.hash = hash;
            this.utf8 = utf8;
            this.keyword = keyword;
        }
    }

    public static final class ParseException extends RuntimeException {
        public final int position;

        public ParseException(String message, int position) {
            super(message + " at " + position);
            this.position = position;
        }
    }

    public JsonParser() {
    }

    @TruffleBoundary
    public static Object parseString(String json) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return parseBytes(bytes, 0, bytes.length, true, null);
    }

    @TruffleBoundary
    public static Object parseString(String json, Object keyFn) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return parseBytes(bytes, 0, bytes.length, false, asKeyFn(keyFn));
    }

    @TruffleBoundary
    public static Object parseBytes(byte[] json) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        return parseBytes(json, 0, json.length, true, null);
    }

    @TruffleBoundary
    public static Object parseBytes(byte[] json, Object keyFn) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        return parseBytes(json, 0, json.length, false, asKeyFn(keyFn));
    }

    @TruffleBoundary
    public static Object parseBytes(byte[] json, int off, int len) {
        return parseBytes(json, off, len, true, null);
    }

    @TruffleBoundary
    public static Object parseBytes(byte[] json, int off, int len, Object keyFn) {
        return parseBytes(json, off, len, false, asKeyFn(keyFn));
    }

    private static IFn asKeyFn(Object keyFn) {
        if (keyFn instanceof IFn) {
            return (IFn) keyFn;
        }
        throw new IllegalArgumentException("key-fn must be an IFn, got "
                + (keyFn == null ? "null" : keyFn.getClass().getName()));
    }

    static Object parseBytes(byte[] json, int off, int len, boolean keywordize, IFn keyFn) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        if (off < 0 || len < 0 || off + len > json.length) {
            throw new IndexOutOfBoundsException();
        }
        JsonParser p = LOCAL.get();
        return p.parse(json, off, off + len, keywordize, keyFn);
    }

    Object parse(byte[] json, int from, int to, boolean keywordize, IFn keyFn) {
        this.buf = json;
        this.pos = from;
        this.end = to;
        this.depth = 0;
        this.keywordize = keywordize;
        this.keyFn = keyFn;
        skipWs();
        if (pos >= end) {
            throw err("Empty JSON");
        }
        Object v = parseValue();
        skipWs();
        if (pos != end) {
            throw err("Trailing content");
        }
        this.buf = null;
        this.keyFn = null;
        return v;
    }

    private Object parseValue() {
        if (pos >= end) {
            throw err("Unexpected end of JSON");
        }
        byte c = buf[pos];
        switch (c) {
            case '{':
                return parseObject();
            case '[':
                return parseArray();
            case '"':
                return parseStringValue();
            case 't':
                return parseLiteral("true", Boolean.TRUE);
            case 'f':
                return parseLiteral("false", Boolean.FALSE);
            case 'n':
                return parseLiteral("null", null);
            case '-':
            case '0':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
            case '9':
                return parseNumber();
            default:
                throw err("Unexpected '" + ((char) (c & 0xff)) + "'");
        }
    }

    private Object parseObject() {
        pos++;
        depth++;
        if (depth > MAX_DEPTH) {
            throw err("Nesting too deep");
        }
        skipWs();
        if (pos < end && buf[pos] == '}') {
            pos++;
            depth--;
            if (keywordize && keyFn == null) {
                return PersistentShapeMap.EMPTY;
            }
            return PersistentArrayMap.EMPTY;
        }

        Object[] savedKeys = keys;
        Object[] savedVals = vals;
        int frameIndex = depth - 1;
        ensureObjectFrame(frameIndex);
        keys = objectKeyFrames[frameIndex];
        vals = objectValFrames[frameIndex];
        int n = 0;
        try {
            while (true) {
                skipWs();
                if (pos >= end || buf[pos] != '"') {
                    throw err("Expected object key");
                }
                Object key = keywordize && keyFn == null
                        ? parseKeywordKey()
                        : decodeKey(parseStringValue());
                skipWs();
                if (pos >= end || buf[pos] != ':') {
                    throw err("Expected ':'");
                }
                pos++;
                skipWs();
                Object val = parseValue();
                n = assocLastWins(n, key, val);
                skipWs();
                if (pos >= end) {
                    throw err("Unclosed object");
                }
                byte c = buf[pos];
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    break;
                }
                throw err("Expected ',' or '}'");
            }
            return finishObject(n);
        } finally {
            Arrays.fill(keys, 0, n, null);
            Arrays.fill(vals, 0, n, null);
            keys = savedKeys;
            vals = savedVals;
            depth--;
        }
    }

    private void ensureObjectFrame(int frameIndex) {
        if (frameIndex >= objectKeyFrames.length) {
            int size = Math.max(frameIndex + 1, objectKeyFrames.length * 2);
            objectKeyFrames = Arrays.copyOf(objectKeyFrames, size);
            objectValFrames = Arrays.copyOf(objectValFrames, size);
        }
        if (objectKeyFrames[frameIndex] == null) {
            objectKeyFrames[frameIndex] = new Object[INITIAL_PAIRS];
            objectValFrames[frameIndex] = new Object[INITIAL_PAIRS];
        }
    }

    private Object decodeKey(String rawKey) {
        if (keywordize && keyFn == null) {
            return Keyword.intern(rawKey);
        }
        if (keyFn != null) {
            return keyFn.invoke(rawKey);
        }
        return rawKey;
    }

    private Keyword parseKeywordKey() {
        int start = pos + 1;
        int i = start;
        int hash = 1;
        while (i < end) {
            byte c = buf[i];
            if (c == '"') {
                int len = i - start;
                Keyword cached = lookupKeyword(start, len, hash);
                pos = i + 1;
                if (cached != null) {
                    return cached;
                }
                String rawKey = new String(buf, start, len, StandardCharsets.UTF_8);
                Keyword keyword = Keyword.intern(rawKey);
                rememberKeyword(start, len, hash, keyword);
                return keyword;
            }
            if (c == '\\') {
                return Keyword.intern(parseStringValue());
            }
            if ((c & 0xff) < 0x20) {
                throw err("Unescaped control character");
            }
            hash = 31 * hash + (c & 0xff);
            i++;
        }
        throw err("Unterminated string");
    }

    private Keyword lookupKeyword(int start, int len, int hash) {
        KeyEntry entry = keyCache[(hash ^ (hash >>> 16)) & (KEY_CACHE_SIZE - 1)];
        if (entry == null || entry.hash != hash || entry.utf8.length != len) {
            return null;
        }
        for (int i = 0; i < len; i++) {
            if (entry.utf8[i] != buf[start + i]) {
                return null;
            }
        }
        return entry.keyword;
    }

    private void rememberKeyword(int start, int len, int hash, Keyword keyword) {
        byte[] utf8 = Arrays.copyOfRange(buf, start, start + len);
        keyCache[(hash ^ (hash >>> 16)) & (KEY_CACHE_SIZE - 1)] =
                new KeyEntry(hash, utf8, keyword);
    }

    private int assocLastWins(int n, Object key, Object val) {
        for (int i = 0; i < n; i++) {
            if (sameKey(keys[i], key)) {
                vals[i] = val;
                return n;
            }
        }
        if (n == keys.length) {
            int next = n * 2;
            keys = Arrays.copyOf(keys, next);
            vals = Arrays.copyOf(vals, next);
            objectKeyFrames[depth - 1] = keys;
            objectValFrames[depth - 1] = vals;
        }
        keys[n] = key;
        vals[n] = val;
        return n + 1;
    }

    private static boolean sameKey(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a instanceof Keyword || b instanceof Keyword) {
            return false;
        }
        return Util.equiv(a, b);
    }

    private Object finishObject(int n) {
        if (allKeywords(n)) {
            return finishKeywordObject(n);
        }
        if (n == 0) {
            return PersistentArrayMap.EMPTY;
        }
        Object[] init = new Object[n * 2];
        for (int i = 0; i < n; i++) {
            init[i * 2] = keys[i];
            init[i * 2 + 1] = vals[i];
        }
        if (n * 2 <= PersistentArrayMap.HASHTABLE_THRESHOLD) {
            return new PersistentArrayMap(init);
        }
        return PersistentHashMap.create(init);
    }

    private boolean allKeywords(int n) {
        for (int i = 0; i < n; i++) {
            if (!(keys[i] instanceof Keyword)) {
                return false;
            }
        }
        return true;
    }

    private Object finishKeywordObject(int n) {
        if (n == 0) {
            return PersistentShapeMap.EMPTY;
        }
        Layout layout = lookupLayout(n);
        if (n <= PersistentShapeMap.MAX_SHAPE_KEYS) {
            if (layout == null) {
                Keyword[] kws = copyKeywordKeys(n);
                layout = new Layout(n, kws, MapShape.fromKeys(n, kws));
                rememberLayout(layout);
            }
            return new PersistentShapeMap(null, layout.shape,
                    n > 0 ? vals[0] : null,
                    n > 1 ? vals[1] : null,
                    n > 2 ? vals[2] : null,
                    n > 3 ? vals[3] : null,
                    n > 4 ? vals[4] : null,
                    n > 5 ? vals[5] : null,
                    n > 6 ? vals[6] : null,
                    n > 7 ? vals[7] : null);
        }
        if (n <= PersistentShapeMap16.MAX_SHAPE16_KEYS) {
            if (layout == null) {
                layout = new Layout(n, copyKeywordKeys(n), null);
                rememberLayout(layout);
            }
            Object[] vs = Arrays.copyOf(vals, n);
            return PersistentShapeMap16.createFromKeys(null, n, layout.keys, vs);
        }
        Keyword[] kws = copyKeywordKeys(n);
        Object[] init = new Object[n * 2];
        for (int i = 0; i < n; i++) {
            init[i * 2] = kws[i];
            init[i * 2 + 1] = vals[i];
        }
        return PersistentHashMap.create(init);
    }

    private Keyword[] copyKeywordKeys(int n) {
        Keyword[] kws = new Keyword[n];
        for (int i = 0; i < n; i++) {
            kws[i] = (Keyword) keys[i];
        }
        return kws;
    }

    private Layout lookupLayout(int n) {
        for (int i = 0; i < IC_SIZE; i++) {
            Layout L = ic[i];
            if (L == null || L.n != n) {
                continue;
            }
            boolean hit = true;
            for (int k = 0; k < n; k++) {
                if (L.keys[k] != keys[k]) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                return L;
            }
        }
        return null;
    }

    private void rememberLayout(Layout layout) {
        ic[icNext] = layout;
        icNext = (icNext + 1) & (IC_SIZE - 1);
    }

    private Object parseArray() {
        pos++;
        depth++;
        if (depth > MAX_DEPTH) {
            throw err("Nesting too deep");
        }
        skipWs();
        if (pos < end && buf[pos] == ']') {
            pos++;
            depth--;
            return PersistentVector.EMPTY;
        }
        int frameIndex = depth - 1;
        ensureArrayFrame(frameIndex);
        Object[] items = arrayFrames[frameIndex];
        int n = 0;
        try {
            while (true) {
                skipWs();
                Object v = parseValue();
                if (n == items.length) {
                    items = Arrays.copyOf(items, n * 2);
                    arrayFrames[frameIndex] = items;
                }
                items[n++] = v;
                skipWs();
                if (pos >= end) {
                    throw err("Unclosed array");
                }
                byte c = buf[pos];
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    break;
                }
                throw err("Expected ',' or ']'");
            }
            return finishArray(items, n);
        } finally {
            Arrays.fill(items, 0, n, null);
            depth--;
        }
    }

    private void ensureArrayFrame(int frameIndex) {
        if (frameIndex >= arrayFrames.length) {
            int size = Math.max(frameIndex + 1, arrayFrames.length * 2);
            arrayFrames = Arrays.copyOf(arrayFrames, size);
        }
        if (arrayFrames[frameIndex] == null) {
            arrayFrames[frameIndex] = new Object[INITIAL_PAIRS];
        }
    }

    private static IPersistentVector finishArray(Object[] items, int n) {
        return switch (n) {
            case 0 -> PersistentVector.EMPTY;
            case 1 -> PersistentTuple.create(items[0]);
            case 2 -> PersistentTuple.create(items[0], items[1]);
            case 3 -> PersistentTuple.create(items[0], items[1], items[2]);
            case 4 -> PersistentTuple.create(items[0], items[1], items[2], items[3]);
            case 5 -> PersistentTuple.create(items[0], items[1], items[2], items[3], items[4]);
            case 6 -> PersistentTuple.create(items[0], items[1], items[2], items[3], items[4], items[5]);
            case 7 -> PersistentTuple.create(items[0], items[1], items[2], items[3], items[4], items[5], items[6]);
            case 8 -> PersistentTuple.create(
                    items[0], items[1], items[2], items[3],
                    items[4], items[5], items[6], items[7]);
            default -> RT.vector(Arrays.copyOf(items, n));
        };
    }

    /**
     * Validates the string starting at {@link #pos} (which must be the opening quote) and leaves
     * {@code pos} <em>on</em> the closing quote. {@link #strStart} and {@link #strEscaped} describe
     * the body. Shared by {@link #parseStringValue} and {@link #skipStringValue} so the fused
     * extraction path cannot accept input the materializing path rejects.
     */
    private void scanString() {
        if (pos >= end || buf[pos] != '"') {
            throw err("Expected string");
        }
        pos++;
        strStart = pos;
        strEscaped = false;
        while (pos < end) {
            byte c = buf[pos];
            if (c == '"') {
                return;
            }
            if (c == '\\') {
                strEscaped = true;
                pos++;
                if (pos >= end) {
                    throw err("Unterminated string escape");
                }
                pos++;
                continue;
            }
            if ((c & 0xff) < 0x20) {
                throw err("Unescaped control character");
            }
            pos++;
        }
        throw err("Unterminated string");
    }

    private String parseStringValue() {
        scanString();
        int start = strStart;
        int closeQuote = pos;
        String s = strEscaped
                ? unescape(start, closeQuote)
                : new String(buf, start, closeQuote - start, StandardCharsets.UTF_8);
        pos = closeQuote + 1;
        return s;
    }

    /** Same validation as {@link #parseStringValue}, without building the {@link String}. */
    private void skipStringValue() {
        scanString();
        if (strEscaped) {
            unescapeInto(null, strStart, pos);
        }
        pos++;
    }

    private String unescape(int start, int strEnd) {
        StringBuilder sb = new StringBuilder(strEnd - start);
        unescapeInto(sb, start, strEnd);
        return sb.toString();
    }

    /** Escape validation and (when {@code sb} is non-null) decoding; one code path for both callers. */
    private void unescapeInto(StringBuilder sb, int start, int strEnd) {
        int i = start;
        while (i < strEnd) {
            byte c = buf[i];
            if (c != '\\') {
                int run = i;
                while (run < strEnd && buf[run] != '\\') {
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
                throw err("Unterminated string escape");
            }
            byte e = buf[i++];
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
                        throw err("Invalid unicode escape");
                    }
                    int cp = hex4(i);
                    i += 4;
                    if (cp >= 0xD800 && cp <= 0xDBFF) {
                        if (i + 6 <= strEnd && buf[i] == '\\' && buf[i + 1] == 'u') {
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
                    throw err("Invalid escape");
            }
        }
    }

    private static void append(StringBuilder sb, char c) {
        if (sb != null) {
            sb.append(c);
        }
    }

    private int hex4(int i) {
        int v = 0;
        for (int k = 0; k < 4; k++) {
            v = (v << 4) | hexDigit(buf[i + k]);
        }
        return v;
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
        throw err("Invalid hex digit");
    }

    private Object parseLiteral(String lit, Object value) {
        int n = lit.length();
        if (pos + n > end) {
            throw err("Unexpected end of JSON");
        }
        for (int i = 0; i < n; i++) {
            if (buf[pos + i] != (byte) lit.charAt(i)) {
                throw err("Invalid literal");
            }
        }
        pos += n;
        return value;
    }

    private Object parseNumber() {
        int start = pos;
        boolean isFloat = scanNumber();
        String slice = new String(buf, start, pos - start, StandardCharsets.US_ASCII);
        if (isFloat) {
            return Double.parseDouble(slice);
        }
        try {
            return Long.parseLong(slice);
        } catch (NumberFormatException e) {
            return BigInt.fromBigInteger(new BigInteger(slice));
        }
    }

    /**
     * Validates the number at {@link #pos} and advances past it; returns whether it is a float.
     * Shared with {@link #skipValue} so skipping validates exactly what parsing validates.
     */
    private boolean scanNumber() {
        if (buf[pos] == '-') {
            pos++;
            if (pos >= end || !isDigit(buf[pos])) {
                throw err("Invalid number");
            }
        }
        if (pos >= end) {
            throw err("Invalid number");
        }
        if (buf[pos] == '0') {
            pos++;
            if (pos < end && isDigit(buf[pos])) {
                throw err("Leading zeros are not allowed");
            }
        } else {
            if (!isDigit(buf[pos])) {
                throw err("Invalid number");
            }
            while (pos < end && isDigit(buf[pos])) {
                pos++;
            }
        }
        boolean isFloat = false;
        if (pos < end && buf[pos] == '.') {
            isFloat = true;
            pos++;
            if (pos >= end || !isDigit(buf[pos])) {
                throw err("Invalid number");
            }
            while (pos < end && isDigit(buf[pos])) {
                pos++;
            }
        }
        if (pos < end && (buf[pos] == 'e' || buf[pos] == 'E')) {
            isFloat = true;
            pos++;
            if (pos < end && (buf[pos] == '+' || buf[pos] == '-')) {
                pos++;
            }
            if (pos >= end || !isDigit(buf[pos])) {
                throw err("Invalid number");
            }
            while (pos < end && isDigit(buf[pos])) {
                pos++;
            }
        }
        return isFloat;
    }

    // ---------------------------------------------------------------------
    // Fused extraction: validate the whole document, materialize one path.
    // ---------------------------------------------------------------------

    /**
     * Returned when the requested path cannot be resolved by scanning alone — a missing key, an
     * out-of-range index, an escaped key at a path level, or a structural type that does not match
     * the step. The caller must then do a full parse and apply the accessors generically, so
     * {@code get} / {@code nth} semantics for those cases are never reimplemented here.
     */
    public static final Object FALLBACK = new Object();

    private static final Object MISSING = new Object();

    /**
     * The value at {@code path} in the JSON text, without building any map or vector along the way,
     * or {@link #FALLBACK}.
     *
     * <p>The document is validated in full exactly as {@link #parseString} validates it: every
     * skipped value goes through the same scanners, so malformed input fails with the same
     * {@link ParseException} message and position. Duplicate keys are last-wins.
     *
     * @param path     {@link Keyword} steps (map lookups) and {@link Integer} steps (vector indices)
     * @param keyUtf8  UTF-8 bytes of each keyword step's printed name, {@code null} for index steps
     */
    @TruffleBoundary
    public static Object extractString(String json, Object[] path, byte[][] keyUtf8) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return extractBytes(bytes, 0, bytes.length, path, keyUtf8);
    }

    @TruffleBoundary
    public static Object extractBytes(byte[] json, Object[] path, byte[][] keyUtf8) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        return extractBytes(json, 0, json.length, path, keyUtf8);
    }

    static Object extractBytes(byte[] json, int off, int len, Object[] path, byte[][] keyUtf8) {
        if (off < 0 || len < 0 || off + len > json.length) {
            throw new IndexOutOfBoundsException();
        }
        return LOCAL.get().extract(json, off, off + len, path, keyUtf8);
    }

    Object extract(byte[] json, int from, int to, Object[] path, byte[][] keyUtf8) {
        this.buf = json;
        this.pos = from;
        this.end = to;
        this.depth = 0;
        this.keywordize = true;
        this.keyFn = null;
        try {
            skipWs();
            if (pos >= end) {
                throw err("Empty JSON");
            }
            Object v = extractValue(path, keyUtf8, 0);
            if (v == FALLBACK) {
                return FALLBACK;
            }
            skipWs();
            if (pos != end) {
                throw err("Trailing content");
            }
            return v;
        } finally {
            this.buf = null;
            this.keyFn = null;
        }
    }

    /**
     * Extracts from the value at {@link #pos}. A {@link #FALLBACK} return abandons the scan
     * immediately: validation of the remainder is left to the full parse the caller then runs.
     */
    private Object extractValue(Object[] path, byte[][] keyUtf8, int step) {
        if (step == path.length) {
            return parseValue();
        }
        if (pos >= end) {
            throw err("Unexpected end of JSON");
        }
        boolean wantObject = path[step] instanceof Keyword;
        byte c = buf[pos];
        if (wantObject ? c != '{' : c != '[') {
            return FALLBACK;
        }
        return wantObject
                ? extractFromObject(path, keyUtf8, step)
                : extractFromArray(path, keyUtf8, step);
    }

    private Object extractFromObject(Object[] path, byte[][] keyUtf8, int step) {
        pos++;
        depth++;
        if (depth > MAX_DEPTH) {
            throw err("Nesting too deep");
        }
        byte[] want = keyUtf8[step];
        Object found = MISSING;
        try {
            skipWs();
            if (pos < end && buf[pos] == '}') {
                pos++;
                return FALLBACK;
            }
            while (true) {
                skipWs();
                if (pos >= end || buf[pos] != '"') {
                    throw err("Expected object key");
                }
                int keyStart = pos + 1;
                int keyEnd = scanRawKey();
                if (keyEnd < 0) {
                    return FALLBACK;
                }
                boolean match = sameBytes(want, keyStart, keyEnd - keyStart);
                pos = keyEnd + 1;
                skipWs();
                if (pos >= end || buf[pos] != ':') {
                    throw err("Expected ':'");
                }
                pos++;
                skipWs();
                if (match) {
                    // Keep going after a hit: a later duplicate of the same key must win.
                    Object v = extractValue(path, keyUtf8, step + 1);
                    if (v == FALLBACK) {
                        return FALLBACK;
                    }
                    found = v;
                } else {
                    skipValue();
                }
                skipWs();
                if (pos >= end) {
                    throw err("Unclosed object");
                }
                byte c = buf[pos];
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    break;
                }
                throw err("Expected ',' or '}'");
            }
        } finally {
            depth--;
        }
        return found == MISSING ? FALLBACK : found;
    }

    private Object extractFromArray(Object[] path, byte[][] keyUtf8, int step) {
        int want = (Integer) path[step];
        pos++;
        depth++;
        if (depth > MAX_DEPTH) {
            throw err("Nesting too deep");
        }
        Object found = MISSING;
        try {
            skipWs();
            if (pos < end && buf[pos] == ']') {
                pos++;
                return FALLBACK;
            }
            int i = 0;
            while (true) {
                skipWs();
                if (i == want) {
                    Object v = extractValue(path, keyUtf8, step + 1);
                    if (v == FALLBACK) {
                        return FALLBACK;
                    }
                    found = v;
                } else {
                    skipValue();
                }
                i++;
                skipWs();
                if (pos >= end) {
                    throw err("Unclosed array");
                }
                byte c = buf[pos];
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    break;
                }
                throw err("Expected ',' or ']'");
            }
        } finally {
            depth--;
        }
        return found == MISSING ? FALLBACK : found;
    }

    /**
     * Index of the closing quote of the key at {@link #pos}, or -1 when the key contains an escape
     * (such a key needs decoding to compare, so the caller falls back). Leaves {@code pos} alone so
     * error positions match {@link #parseKeywordKey}.
     */
    private int scanRawKey() {
        int i = pos + 1;
        while (i < end) {
            byte c = buf[i];
            if (c == '"') {
                return i;
            }
            if (c == '\\') {
                return -1;
            }
            if ((c & 0xff) < 0x20) {
                throw err("Unescaped control character");
            }
            i++;
        }
        throw err("Unterminated string");
    }

    private boolean sameBytes(byte[] want, int start, int len) {
        if (want.length != len) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (want[i] != buf[start + i]) {
                return false;
            }
        }
        return true;
    }

    /** Validates the value at {@link #pos} exactly as {@link #parseValue} does, allocating nothing. */
    private void skipValue() {
        if (pos >= end) {
            throw err("Unexpected end of JSON");
        }
        byte c = buf[pos];
        switch (c) {
            case '{':
                skipObject();
                return;
            case '[':
                skipArray();
                return;
            case '"':
                skipStringValue();
                return;
            case 't':
                parseLiteral("true", Boolean.TRUE);
                return;
            case 'f':
                parseLiteral("false", Boolean.FALSE);
                return;
            case 'n':
                parseLiteral("null", null);
                return;
            case '-':
            case '0':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
            case '9':
                scanNumber();
                return;
            default:
                throw err("Unexpected '" + ((char) (c & 0xff)) + "'");
        }
    }

    private void skipObject() {
        pos++;
        depth++;
        if (depth > MAX_DEPTH) {
            throw err("Nesting too deep");
        }
        try {
            skipWs();
            if (pos < end && buf[pos] == '}') {
                pos++;
                return;
            }
            while (true) {
                skipWs();
                if (pos >= end || buf[pos] != '"') {
                    throw err("Expected object key");
                }
                skipKey();
                skipWs();
                if (pos >= end || buf[pos] != ':') {
                    throw err("Expected ':'");
                }
                pos++;
                skipWs();
                skipValue();
                skipWs();
                if (pos >= end) {
                    throw err("Unclosed object");
                }
                byte c = buf[pos];
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return;
                }
                throw err("Expected ',' or '}'");
            }
        } finally {
            depth--;
        }
    }

    /** Key validation matching {@link #parseKeywordKey} without interning a {@link Keyword}. */
    private void skipKey() {
        int keyEnd = scanRawKey();
        if (keyEnd < 0) {
            skipStringValue();
            return;
        }
        pos = keyEnd + 1;
    }

    private void skipArray() {
        pos++;
        depth++;
        if (depth > MAX_DEPTH) {
            throw err("Nesting too deep");
        }
        try {
            skipWs();
            if (pos < end && buf[pos] == ']') {
                pos++;
                return;
            }
            while (true) {
                skipWs();
                skipValue();
                skipWs();
                if (pos >= end) {
                    throw err("Unclosed array");
                }
                byte c = buf[pos];
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return;
                }
                throw err("Expected ',' or ']'");
            }
        } finally {
            depth--;
        }
    }

    private static boolean isDigit(byte b) {
        int c = b & 0xff;
        return c >= '0' && c <= '9';
    }

    private void skipWs() {
        while (pos < end) {
            byte c = buf[pos];
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                pos++;
            } else {
                return;
            }
        }
    }

    private ParseException err(String message) {
        return new ParseException(message, pos);
    }
}
