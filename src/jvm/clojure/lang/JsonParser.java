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
 * <p>Instances are request-local and not thread-safe. Nested objects may reuse a
 * per-request layout inline cache keyed by interned keyword insertion order.
 */
public final class JsonParser {

    public static final int MAX_DEPTH = 512;
    private static final int IC_SIZE = 16;
    private static final int KEY_CACHE_SIZE = 128;
    private static final int INITIAL_PAIRS = 8;

    private byte[] buf;
    private TruffleString truffleBuf;
    private TruffleString.ReadByteNode readByte;
    private int pos;
    private int end;
    private int depth;
    private boolean keywordize;
    private IFn keyFn;

    /** Body of the string {@link #scanString} last validated; {@code pos} is then the closing quote. */
    private int strStart;
    private boolean strEscaped;

    private Object[] keys;
    private Object[] vals;
    private Object[] projectSlots;
    private Object[][] objectKeyFrames;
    private Object[][] objectValFrames;
    private Object[][] arrayFrames;
    private Layout[] ic;
    private int icNext;
    private KeyEntry[] keyCache;

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

    /**
     * Accepts any {@link CharSequence} that is not a {@link String} (which binds to
     * {@link #parseString(String)}). {@link TruffleString} is not a {@code CharSequence}; use
     * {@link #parseInput(Object)} or {@link #parseString(TruffleString)}.
     */
    @TruffleBoundary
    public static Object parseString(CharSequence json) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        if (json instanceof String s) {
            return parseString(s);
        }
        byte[] bytes = utf8Bytes(json);
        return parseBytes(bytes, 0, bytes.length, true, null);
    }

    @TruffleBoundary
    public static Object parseByteBuffer(ByteBuffer json) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        byte[] bytes = bytesOf(json);
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
    public static Object parseString(CharSequence json, Object keyFn) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        if (json instanceof String s) {
            return parseString(s, keyFn);
        }
        byte[] bytes = utf8Bytes(json);
        return parseBytes(bytes, 0, bytes.length, false, asKeyFn(keyFn));
    }

    @TruffleBoundary
    public static Object parseString(TruffleString json) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        byte[] bytes = utf8Bytes(json);
        return parseBytes(bytes, 0, bytes.length, true, null);
    }

    /**
     * Dispatches on {@link String}, {@link CharSequence}, {@link TruffleString}, {@code byte[]},
     * or {@link ByteBuffer}.
     */
    @TruffleBoundary
    public static Object parseInput(Object json) {
        return parseInput(json, true, null);
    }

    @TruffleBoundary
    public static Object parseInput(Object json, Object keyFn) {
        return parseInput(json, false, asKeyFn(keyFn));
    }

    private static Object parseInput(Object json, boolean keywordize, IFn keyFn) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        byte[] bytes;
        if (json instanceof byte[] b) {
            bytes = b;
        } else if (json instanceof ByteBuffer bb) {
            bytes = bytesOf(bb);
        } else if (json instanceof String s) {
            bytes = s.getBytes(StandardCharsets.UTF_8);
        } else if (json instanceof TruffleString ts) {
            bytes = utf8Bytes(ts);
        } else if (json instanceof CharSequence cs) {
            bytes = utf8Bytes(cs);
        } else {
            throw new IllegalArgumentException(
                    "JSON source must be a CharSequence, TruffleString, byte[], or ByteBuffer, got "
                            + json.getClass().getName());
        }
        return parseBytes(bytes, 0, bytes.length, keywordize, keyFn);
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

    public static byte[] utf8Bytes(CharSequence json) {
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] utf8Bytes(TruffleString json) {
        return json.switchEncodingUncached(TruffleString.Encoding.UTF_8)
                .copyToByteArrayUncached(TruffleString.Encoding.UTF_8);
    }

    public static byte[] bytesOf(ByteBuffer json) {
        ByteBuffer dup = json.duplicate();
        byte[] bytes = new byte[dup.remaining()];
        dup.get(bytes);
        return bytes;
    }

    static Object parseBytes(byte[] json, int off, int len, boolean keywordize, IFn keyFn) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        if (off < 0 || len < 0 || off + len > json.length) {
            throw new IndexOutOfBoundsException();
        }
        JsonParser p = new JsonParser();
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
        if (objectKeyFrames == null) {
            objectKeyFrames = new Object[8][];
            objectValFrames = new Object[8][];
        }
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
        if (keyCache == null) {
            return null;
        }
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
        if (keyCache == null) {
            keyCache = new KeyEntry[KEY_CACHE_SIZE];
        }
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
        if (ic == null) {
            return null;
        }
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
        if (ic == null) {
            ic = new Layout[IC_SIZE];
        }
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
        if (arrayFrames == null) {
            arrayFrames = new Object[8][];
        }
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
        if (pos >= end || sourceByte(pos) != '"') {
            throw err("Expected string");
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
                    throw err("Unterminated string escape");
                }
                byte escaped = sourceByte(pos++);
                switch (escaped) {
                    case '"', '\\', '/', 'b', 'f', 'n', 'r', 't':
                        break;
                    case 'u':
                        if (pos + 4 > end) {
                            throw err("Invalid unicode escape");
                        }
                        for (int i = 0; i < 4; i++) {
                            hexDigit(sourceByte(pos + i));
                        }
                        pos += 4;
                        break;
                    default:
                        throw err("Invalid escape");
                }
                continue;
            }
            if ((c & 0xff) < 0x20) {
                throw err("Unescaped control character");
            }
            pos++;
        }
        throw err("Unterminated string");
    }

    /**
     * Decode an unescaped UTF-8 slice via {@link TruffleString} so the same path can wrap
     * Netty/{@link ByteBuffer} input and compact substrings without a separate charset decoder.
     */
    private String utf8Slice(int start, int len) {
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

    private String parseStringValue() {
        scanString();
        int start = strStart;
        int closeQuote = pos;
        String s = strEscaped
                ? unescape(start, closeQuote)
                : utf8Slice(start, closeQuote - start);
        pos = closeQuote + 1;
        return s;
    }

    /** Same validation as {@link #parseStringValue}, without building the {@link String}. */
    private void skipStringValue() {
        scanString();
        pos++;
    }

    private String unescape(int start, int strEnd) {
        if (truffleBuf != null) {
            return unescapeTruffleString(start, strEnd).toJavaStringUncached();
        }
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
            v = (v << 4) | hexDigit(sourceByte(i + k));
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
        if (sourceByte(pos) == '-') {
            pos++;
            if (pos >= end || !isDigit(sourceByte(pos))) {
                throw err("Invalid number");
            }
        }
        if (pos >= end) {
            throw err("Invalid number");
        }
        if (sourceByte(pos) == '0') {
            pos++;
            if (pos < end && isDigit(sourceByte(pos))) {
                throw err("Leading zeros are not allowed");
            }
        } else {
            if (!isDigit(sourceByte(pos))) {
                throw err("Invalid number");
            }
            while (pos < end && isDigit(sourceByte(pos))) {
                pos++;
            }
        }
        boolean isFloat = false;
        if (pos < end && sourceByte(pos) == '.') {
            isFloat = true;
            pos++;
            if (pos >= end || !isDigit(sourceByte(pos))) {
                throw err("Invalid number");
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
                throw err("Invalid number");
            }
            while (pos < end && isDigit(sourceByte(pos))) {
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

    /** Slot not present; distinct from JSON {@code null}. */
    public static final Object MISSING = new Object();

    /** One edge in a compile-time keyword/index trie used by {@link #project}. */
    public static final class TrieEdge {
        public final byte[] utf8;
        public final int index;
        public final TrieNode child;

        public TrieEdge(byte[] utf8, int index, TrieNode child) {
            this.utf8 = utf8;
            this.index = index;
            this.child = child;
        }

        public boolean isKeyword() {
            return utf8 != null;
        }
    }

    /** Compile-time path automaton. {@code slot >= 0} means this node is extracted. */
    public static final class TrieNode {
        public final TrieEdge[] edges;
        public final int slot;

        public TrieNode(TrieEdge[] edges, int slot) {
            this.edges = edges;
            this.slot = slot;
        }
    }

    /** Typed leaf requested by a schema-compiled projection. */
    public static final class TypedLeaf {
        public static final int INT = 1;
        public static final int LONG = 2;
        public static final int DOUBLE = 3;
        public static final int BOOLEAN = 4;
        public static final int STRING = 5;
        public static final int TRUFFLE_STRING = 6;
        public static final int DYNAMIC = 7;
        public static final int NULL = 8;
        /**
         * Whatever the input holds. A JSON Pointer names a position, not a type, so
         * {@code cloffle.json/select} leaves the decision to the scanner.
         */
        public static final int ANY = 9;

        public final int kind;
        public final boolean nullable;
        public final boolean materialize;
        public final TypedValueNode dynamic;

        public TypedLeaf(int kind, boolean nullable, boolean materialize, TypedValueNode dynamic) {
            this.kind = kind;
            this.nullable = nullable;
            this.materialize = materialize;
            this.dynamic = dynamic;
        }
    }

    /** Runtime schema for dynamic-length homogeneous arrays. */
    public static final class TypedValueNode {
        public static final int LEAF = 1;
        public static final int VECTOR = 2;

        public final int kind;
        public final TypedLeaf leaf;
        public final TypedValueNode child;

        private TypedValueNode(int kind, TypedLeaf leaf, TypedValueNode child) {
            this.kind = kind;
            this.leaf = leaf;
            this.child = child;
        }

        public static TypedValueNode leaf(TypedLeaf leaf) {
            return new TypedValueNode(LEAF, leaf, null);
        }

        public static TypedValueNode vector(TypedValueNode child) {
            return new TypedValueNode(VECTOR, null, child);
        }
    }

    public static final class TypedTrieEdge {
        @CompilationFinal(dimensions = 1) public final byte[] utf8;
        public final String name;
        public final int index;
        public final TypedTrieNode child;
        final int first4;

        public TypedTrieEdge(byte[] utf8, int index, TypedTrieNode child) {
            this(utf8, utf8 == null ? null : new String(utf8, StandardCharsets.UTF_8),
                    index, child);
        }

        public TypedTrieEdge(byte[] utf8, String name, int index, TypedTrieNode child) {
            this.utf8 = utf8;
            this.name = name;
            this.index = index;
            this.child = child;
            if (utf8 != null && utf8.length >= 4) {
                this.first4 = ((utf8[0] & 0xff) << 24)
                            | ((utf8[1] & 0xff) << 16)
                            | ((utf8[2] & 0xff) << 8)
                            | (utf8[3] & 0xff);
            } else {
                this.first4 = 0;
            }
        }

        boolean isKeyword() {
            return utf8 != null;
        }
    }

    public static final class TypedTrieNode {
        /** No edges: whatever is here is skipped. */
        public static final int LEAF_ONLY = 0;
        /** Only keyword edges: the value must be an object. */
        public static final int OBJECT_ONLY = 1;
        /** Only index edges: the value must be an array. */
        public static final int ARRAY_ONLY = 2;
        /**
         * Both edge kinds. A JSON Pointer token is a member name and an array index at the same
         * time, so which applies is decided from the input byte rather than from the schema.
         */
        public static final int EITHER = 3;

        @CompilationFinal(dimensions = 1) public final TypedTrieEdge[] edges;
        public final int slot;
        public final TypedLeaf leaf;
        @CompilationFinal(dimensions = 1) public final int[] subtreeSlots;
        @CompilationFinal public final int containerKind;

        public TypedTrieNode(TypedTrieEdge[] edges, int slot, TypedLeaf leaf) {
            this.edges = edges;
            this.slot = slot;
            this.leaf = leaf;
            this.subtreeSlots = computeSubtreeSlots(edges, slot);
            this.containerKind = computeContainerKind(edges);
        }

        private static int computeContainerKind(TypedTrieEdge[] edges) {
            boolean object = false;
            boolean array = false;
            if (edges != null) {
                for (TypedTrieEdge edge : edges) {
                    object |= edge.isKeyword();
                    array |= !edge.isKeyword();
                }
            }
            if (object && array) {
                return EITHER;
            }
            if (object) {
                return OBJECT_ONLY;
            }
            return array ? ARRAY_ONLY : LEAF_ONLY;
        }

        private static int[] computeSubtreeSlots(TypedTrieEdge[] edges, int slot) {
            if (slot >= 0) {
                return new int[] { slot };
            }
            if (edges == null || edges.length == 0) {
                return new int[0];
            }
            java.util.BitSet bs = new java.util.BitSet();
            for (TypedTrieEdge edge : edges) {
                if (edge != null && edge.child != null && edge.child.subtreeSlots != null) {
                    for (int s : edge.child.subtreeSlots) {
                        bs.set(s);
                    }
                }
            }
            int[] result = new int[bs.cardinality()];
            int idx = 0;
            for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1)) {
                result[idx++] = i;
            }
            return result;
        }
    }

    /**
     * Fresh result carrier for one typed scan. Ranges refer to {@link #source}, or to
     * {@link #truffleSource} for native TruffleString input. No scratch slot array is copied.
     */
    public static final class TypedScanResult {
        public static final byte MISSING = 0;
        public static final byte SLICE = 1;
        public static final byte VALUE = 2;
        public static final byte ESCAPED_SLICE = 3;
        /** Raw JSON text the untyped parser still has to read: an object, array, or wide number. */
        public static final byte RAW = 4;

        public final byte[] source;
        public final int[] starts;
        public final int[] lengths;
        public final byte[] states;
        public final Object[] values;
        public TruffleString truffleSource;
        public int truffleOffset;

        TypedScanResult(byte[] source, int slots) {
            this.source = source;
            this.starts = new int[slots];
            this.lengths = new int[slots];
            this.states = new byte[slots];
            this.values = new Object[slots];
            Arrays.fill(values, JsonParser.MISSING);
        }
    }

    @TruffleBoundary
    public static TypedScanResult projectTypedString(
            String json, TypedTrieNode root, TypedLeaf[] leaves) {
        return projectTypedString(json, root, leaves, true);
    }

    @TruffleBoundary
    public static TypedScanResult projectTypedString(
            String json, TypedTrieNode root, TypedLeaf[] leaves, boolean firstWins) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return projectTypedBytes(bytes, 0, bytes.length, root, leaves, firstWins);
    }

    @TruffleBoundary
    public static TypedScanResult projectTypedBytes(
            byte[] json, TypedTrieNode root, TypedLeaf[] leaves) {
        return projectTypedBytes(json, root, leaves, true);
    }

    @TruffleBoundary
    public static TypedScanResult projectTypedBytes(
            byte[] json, TypedTrieNode root, TypedLeaf[] leaves, boolean firstWins) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        return projectTypedBytes(json, 0, json.length, root, leaves, firstWins);
    }

    @TruffleBoundary
    public static TypedScanResult projectTypedByteBuffer(
            ByteBuffer json, TypedTrieNode root, TypedLeaf[] leaves) {
        return projectTypedByteBuffer(json, root, leaves, true);
    }

    @TruffleBoundary
    public static TypedScanResult projectTypedByteBuffer(
            ByteBuffer json, TypedTrieNode root, TypedLeaf[] leaves, boolean firstWins) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        ByteBuffer dup = json.duplicate();
        if (dup.hasArray()) {
            int offset = dup.arrayOffset() + dup.position();
            return projectTypedBytes(dup.array(), offset, dup.remaining(), root, leaves, firstWins);
        }
        byte[] bytes = new byte[dup.remaining()];
        dup.get(bytes);
        return projectTypedBytes(bytes, 0, bytes.length, root, leaves, firstWins);
    }

    static TypedScanResult projectTypedBytes(
            byte[] json, int off, int len, TypedTrieNode root, TypedLeaf[] leaves) {
        return projectTypedBytes(json, off, len, root, leaves, true);
    }

    private byte sourceByte(int index) {
        if (buf != null) {
            return buf[index];
        }
        return (byte) readByte.execute(
                truffleBuf, index, TruffleString.Encoding.UTF_8);
    }

    static TypedScanResult projectTypedBytes(
            byte[] json, int off, int len, TypedTrieNode root, TypedLeaf[] leaves,
            boolean firstWins) {
        if (off < 0 || len < 0 || off + len > json.length) {
            throw new IndexOutOfBoundsException();
        }
        return new TypedScanner().scan(json, off, off + len, root, leaves, firstWins);
    }

    /**
     * Same scan as {@link #projectTypedBytes}, but deliberately not a {@code @TruffleBoundary}: the
     * caller's compilation unit inlines the schema walk so a constant trie folds the key matching
     * and leaf decoding. Data-recursive helpers inside the scanner remain boundaries.
     */
    @CompilerDirectives.EarlyEscapeAnalysis
    public static TypedScanResult projectTypedBytesPartialEvaluated(
            byte[] json, TypedTrieNode root, TypedLeaf[] leaves, boolean firstWins) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        return new TypedScanner().scan(json, 0, json.length, root, leaves, firstWins);
    }

    @TruffleBoundary
    public static TypedScanResult projectTypedTruffleString(
            TruffleString json, TypedTrieNode root, TypedLeaf[] leaves, boolean firstWins) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        TruffleString utf8 = json.switchEncodingUncached(TruffleString.Encoding.UTF_8);
        if (utf8.isManaged()) {
            TruffleString.MaterializeNode.getUncached().execute(
                    utf8, TruffleString.Encoding.UTF_8);
            InternalByteArray internal = TruffleString.GetInternalByteArrayNode.getUncached()
                    .execute(utf8, TruffleString.Encoding.UTF_8);
            TypedScanResult result = new TypedScanner().scan(
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

        TypedScanResult scan(
                byte[] json, int from, int to, TypedTrieNode root, TypedLeaf[] leaves,
                boolean firstWins) {
            this.buf = json;
            return scan(from, to, root, leaves, firstWins, new TypedScanResult(json, leaves.length));
        }

        TypedScanResult scan(
                TruffleString json, int from, int to, TypedTrieNode root, TypedLeaf[] leaves,
                boolean firstWins) {
            this.truffleBuf = json;
            this.readByte = TruffleString.ReadByteNode.getUncached();
            TypedScanResult result = new TypedScanResult(null, leaves.length);
            result.truffleSource = json;
            return scan(from, to, root, leaves, firstWins, result);
        }

        private TypedScanResult scan(
                int from, int to, TypedTrieNode root, TypedLeaf[] leaves,
                boolean firstWins, TypedScanResult result) {
            this.pos = from;
            this.end = to;
            this.depth = 0;
            this.typedFirstWins = firstWins;
            this.typedPending = leaves.length;
            this.typedComplete = leaves.length == 0;
            try {
                skipWs();
                if (pos >= end) {
                    throw err("Empty JSON");
                }
                projectTypedValue(root, result);
                if (!typedComplete) {
                    skipWs();
                    if (pos != end) {
                        throw err("Trailing content");
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
        private ParseException err(String message) {
            CompilerDirectives.transferToInterpreter();
            return new ParseException(message, pos);
        }

        private void projectTypedValue(TypedTrieNode node, TypedScanResult result) {
            if (node.slot >= 0) {
                captureTypedLeaf(node.slot, node.leaf, result);
                return;
            }
            if (pos >= end) {
                throw err("Unexpected end of JSON");
            }
            switch (node.containerKind) {
                case TypedTrieNode.OBJECT_ONLY -> {
                    if (sourceByte(pos) != '{') {
                        throw err("Expected object");
                    }
                    projectTypedObject(node, result);
                }
                case TypedTrieNode.ARRAY_ONLY -> {
                    if (sourceByte(pos) != '[') {
                        throw err("Expected array");
                    }
                    projectTypedArray(node, result);
                }
                case TypedTrieNode.EITHER -> {
                    byte c = sourceByte(pos);
                    if (c == '{') {
                        projectTypedObject(node, result);
                    } else if (c == '[') {
                        projectTypedArray(node, result);
                    } else {
                        throw err("Expected object or array");
                    }
                }
                default -> skipValueOnDemand();
            }
        }

        private void captureTypedLeaf(int slot, TypedLeaf leaf, TypedScanResult result) {
            if (typedFirstWins && result.states[slot] != TypedScanResult.MISSING) {
                skipValueOnDemand();
                return;
            }
            if (leaf.nullable && startsWithLiteral("null")) {
                pos += 4;
                result.values[slot] = null;
                result.states[slot] = TypedScanResult.VALUE;
                markTypedFilled();
                return;
            }
            switch (leaf.kind) {
                case TypedLeaf.STRING, TypedLeaf.TRUFFLE_STRING ->
                        captureTypedString(slot, leaf, result);
                case TypedLeaf.INT, TypedLeaf.LONG -> captureTypedInteger(slot, leaf, result);
                case TypedLeaf.DOUBLE -> captureTypedDouble(slot, result);
                case TypedLeaf.BOOLEAN -> {
                    if (startsWithLiteral("true")) {
                        pos += 4;
                        result.values[slot] = Boolean.TRUE;
                    } else if (startsWithLiteral("false")) {
                        pos += 5;
                        result.values[slot] = Boolean.FALSE;
                    } else {
                        throw err("Expected boolean");
                    }
                    result.states[slot] = TypedScanResult.VALUE;
                }
                case TypedLeaf.NULL -> {
                    if (!startsWithLiteral("null")) {
                        throw err("Expected null");
                    }
                    pos += 4;
                    result.values[slot] = null;
                    result.states[slot] = TypedScanResult.VALUE;
                }
                case TypedLeaf.DYNAMIC -> {
                    result.values[slot] = parseDynamicTyped(leaf.dynamic);
                    result.states[slot] = TypedScanResult.VALUE;
                }
                case TypedLeaf.ANY -> captureTypedAny(slot, leaf, result);
                default -> throw err("Unsupported typed leaf");
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

        private void captureTypedString(int slot, TypedLeaf leaf, TypedScanResult result) {
            if (pos >= end || sourceByte(pos) != '"') {
                throw err("Expected string");
            }
            scanString();
            int close = pos;
            if (strEscaped) {
                result.starts[slot] = strStart;
                result.lengths[slot] = close - strStart;
                result.states[slot] = TypedScanResult.ESCAPED_SLICE;
            } else {
                result.starts[slot] = strStart;
                result.lengths[slot] = close - strStart;
                result.states[slot] = TypedScanResult.SLICE;
            }
            pos = close + 1;
        }

        /**
         * Capture a value whose type the query did not state. Scalars take the same paths the
         * typed leaves use; objects, arrays, and numbers too wide for a {@code long} are recorded
         * as raw text for the untyped parser to read during decode.
         */
        private void captureTypedAny(int slot, TypedLeaf leaf, TypedScanResult result) {
            if (pos >= end) {
                throw err("Unexpected end of JSON");
            }
            byte c = sourceByte(pos);
            if (c == '"') {
                captureTypedString(slot, leaf, result);
                return;
            }
            if (c == 't' && startsWithLiteral("true")) {
                pos += 4;
                result.values[slot] = Boolean.TRUE;
                result.states[slot] = TypedScanResult.VALUE;
                return;
            }
            if (c == 'f' && startsWithLiteral("false")) {
                pos += 5;
                result.values[slot] = Boolean.FALSE;
                result.states[slot] = TypedScanResult.VALUE;
                return;
            }
            if (c == 'n' && startsWithLiteral("null")) {
                pos += 4;
                result.values[slot] = null;
                result.states[slot] = TypedScanResult.VALUE;
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
            result.states[slot] = TypedScanResult.RAW;
        }

        /** {@code long} when the literal is an integer that fits, raw text otherwise. */
        private void captureTypedAnyNumber(int slot, TypedScanResult result) {
            if (sourceByte(pos) != '-' && !isDigit(sourceByte(pos))) {
                throw err("Expected value");
            }
            int start = pos;
            boolean negative = false;
            if (sourceByte(pos) == '-') {
                negative = true;
                pos++;
                if (pos >= end || !isDigit(sourceByte(pos))) {
                    throw err("Invalid number");
                }
            }
            long val = 0;
            boolean overflow = false;
            if (sourceByte(pos) == '0') {
                pos++;
                if (pos < end && isDigit(sourceByte(pos))) {
                    throw err("Leading zeros are not allowed");
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
                    throw err("Invalid number");
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
                    throw err("Invalid number");
                }
                while (pos < end && isDigit(sourceByte(pos))) {
                    pos++;
                }
            }
            if (!isFloat && !overflow) {
                result.values[slot] = Long.valueOf(negative ? -val : val);
                result.states[slot] = TypedScanResult.VALUE;
            } else {
                result.starts[slot] = start;
                result.lengths[slot] = pos - start;
                result.states[slot] = TypedScanResult.RAW;
            }
        }

        private void captureTypedInteger(int slot, TypedLeaf leaf, TypedScanResult result) {
            if (pos >= end || (sourceByte(pos) != '-' && !isDigit(sourceByte(pos)))) {
                throw err("Expected integer");
            }
            int start = pos;
            boolean negative = false;
            if (sourceByte(pos) == '-') {
                negative = true;
                pos++;
                if (pos >= end || !isDigit(sourceByte(pos))) {
                    throw err("Invalid number");
                }
            }
            long val = 0;
            boolean overflow = false;
            if (sourceByte(pos) == '0') {
                pos++;
                if (pos < end && isDigit(sourceByte(pos))) {
                    throw err("Leading zeros are not allowed");
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
                throw err("Expected integer");
            }
            long finalVal = negative ? -val : val;
            boolean intRange = leaf.kind == TypedLeaf.INT
                    && finalVal >= Integer.MIN_VALUE && finalVal <= Integer.MAX_VALUE;
            if (overflow || (leaf.kind == TypedLeaf.INT && !intRange)) {
                // Out of the leaf's range: leave a slice so decode reports the range error, and so
                // integers beyond Long.MAX_VALUE can still be parsed as BigInt.
                result.starts[slot] = start;
                result.lengths[slot] = pos - start;
                result.states[slot] = TypedScanResult.SLICE;
            } else {
                // Box once, in the leaf's own type, so decode never has to re-box.
                result.values[slot] = intRange
                        ? (Object) Integer.valueOf((int) finalVal)
                        : (Object) Long.valueOf(finalVal);
                result.states[slot] = TypedScanResult.VALUE;
            }
        }

        /**
         * Reads the literal once, keeping the significant digits and the decimal exponent as it
         * goes, and converts them with {@link EiselLemire}. Only literals wider than
         * {@link EiselLemire#MAX_SIGNIFICANT_DIGITS} significant digits are left as a slice for
         * {@code TruffleString.ParseDoubleNode} to read a second time.
         */
        private void captureTypedDouble(int slot, TypedScanResult result) {
            if (pos >= end || (sourceByte(pos) != '-' && !isDigit(sourceByte(pos)))) {
                throw err("Expected number");
            }
            int start = pos;
            boolean isFloat = false;
            boolean negative = false;
            if (sourceByte(pos) == '-') {
                negative = true;
                pos++;
                if (pos >= end || !isDigit(sourceByte(pos))) {
                    throw err("Invalid number");
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
                    throw err("Leading zeros are not allowed");
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
                    throw err("Invalid number");
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
                    throw err("Invalid number");
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
                result.states[slot] = TypedScanResult.VALUE;
            } else if (!tooManyDigits) {
                result.values[slot] = Double.valueOf(EiselLemire.toDouble(
                        negative, digits, literalExponent - fractionDigits));
                result.states[slot] = TypedScanResult.VALUE;
            } else {
                // Wider than the fast path is exact for: keep a slice for the full parser.
                result.starts[slot] = start;
                result.lengths[slot] = pos - start;
                result.states[slot] = TypedScanResult.SLICE;
            }
        }

        private void projectTypedObject(TypedTrieNode node, TypedScanResult result) {
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
                        throw err("Expected object key");
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
                        throw err("Expected ':'");
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
                        throw err("Unclosed object");
                    }
                    byte c = sourceByte(pos++);
                    if (c == '}') {
                        return;
                    }
                    if (c != ',') {
                        throw err("Expected ',' or '}'");
                    }
                }
            } finally {
                depth--;
            }
        }

        private void projectTypedArray(TypedTrieNode node, TypedScanResult result) {
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
                        throw err("Unclosed array");
                    }
                    byte c = sourceByte(pos++);
                    if (c == ']') {
                        return;
                    }
                    if (c != ',') {
                        throw err("Expected ',' or ']'");
                    }
                }
            } finally {
                depth--;
            }
        }

        private void enterDepth() {
            depth++;
            if (depth > MAX_DEPTH) {
                throw err("Nesting too deep");
            }
        }

        private int findTypedKeywordEdge(TypedTrieNode node, int start, int len) {
            TypedTrieEdge[] edges = node.edges;
            for (int i = 0; i < edges.length; i++) {
                if (edges[i].isKeyword() && sameBytes(edges[i], start, len)) {
                    return i;
                }
            }
            return -1;
        }

        @TruffleBoundary
        private static int findTypedKeywordEdge(TypedTrieNode node, String decoded) {
            TypedTrieEdge[] edges = node.edges;
            for (int i = 0; i < edges.length; i++) {
                if (edges[i].isKeyword() && decoded.equals(edges[i].name)) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * Recurse into one edge's child. Written as an exploded loop rather than an indexed load so
         * that partial evaluation sees a constant {@link TypedTrieNode} in each unrolled body; a
         * phi over the edges would make the child opaque and stop the schema from specializing.
         */
        @ExplodeLoop
        private void projectTypedEdge(TypedTrieNode node, int edgeIndex, TypedScanResult result) {
            TypedTrieEdge[] edges = node.edges;
            for (int i = 0; i < edges.length; i++) {
                if (i == edgeIndex) {
                    projectTypedValue(edges[i].child, result);
                    return;
                }
            }
            throw err("Unknown schema edge");
        }

        private static boolean isSubtreeComplete(TypedTrieNode node, TypedScanResult result) {
            int[] slots = node.subtreeSlots;
            for (int i = 0; i < slots.length; i++) {
                if (result.states[slots[i]] == TypedScanResult.MISSING) {
                    return false;
                }
            }
            return true;
        }

        @TruffleBoundary
        private void skipObjectRemainder() {
            int openBraces = 1;
            while (pos < end) {
                byte c = sourceByte(pos++);
                if (c == '"') {
                    pos--;
                    skipStringValue();
                } else if (c == '{') {
                    openBraces++;
                } else if (c == '}') {
                    openBraces--;
                    if (openBraces == 0) {
                        return;
                    }
                }
            }
            throw err("Unclosed object");
        }

        @TruffleBoundary
        private void skipArrayRemainder() {
            int openBrackets = 1;
            while (pos < end) {
                byte c = sourceByte(pos++);
                if (c == '"') {
                    pos--;
                    skipStringValue();
                } else if (c == '[') {
                    openBrackets++;
                } else if (c == ']') {
                    openBrackets--;
                    if (openBrackets == 0) {
                        return;
                    }
                }
            }
            throw err("Unclosed array");
        }

        private static int findTypedIndexEdge(TypedTrieNode node, int index) {
            TypedTrieEdge[] edges = node.edges;
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
        @TruffleBoundary
        private void skipValueOnDemand() {
            if (pos >= end) {
                throw err("Unexpected end of JSON");
            }
            if (sourceByte(pos) == '"') {
                skipStringValue();
                return;
            }
            if (sourceByte(pos) == '{') {
                skipObjectOnDemand();
                return;
            }
            if (sourceByte(pos) == '[') {
                skipArrayOnDemand();
                return;
            }
            int start = pos;
            while (pos < end && !isValueDelimiter(sourceByte(pos))) {
                pos++;
            }
            if (pos == start) {
                throw err("Expected value");
            }
        }

        private void skipObjectOnDemand() {
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
                        throw err("Expected object key");
                    }
                    skipStringValue();
                    skipWs();
                    if (pos >= end || sourceByte(pos) != ':') {
                        throw err("Expected ':'");
                    }
                    pos++;
                    skipWs();
                    skipValueOnDemand();
                    skipWs();
                    if (pos >= end) {
                        throw err("Unclosed object");
                    }
                    byte c = sourceByte(pos++);
                    if (c == '}') {
                        return;
                    }
                    if (c != ',') {
                        throw err("Expected ',' or '}'");
                    }
                }
            } finally {
                depth--;
            }
        }

        private void skipArrayOnDemand() {
            pos++;
            enterDepth();
            try {
                skipWs();
                if (pos < end && sourceByte(pos) == ']') {
                    pos++;
                    return;
                }
                while (true) {
                    skipWs();
                    skipValueOnDemand();
                    skipWs();
                    if (pos >= end) {
                        throw err("Unclosed array");
                    }
                    byte c = sourceByte(pos++);
                    if (c == ']') {
                        return;
                    }
                    if (c != ',') {
                        throw err("Expected ',' or ']'");
                    }
                }
            } finally {
                depth--;
            }
        }

        /**
         * Skips a string without decoding it. On byte[] input this jumps to the next quote or
         * backslash with {@link ArrayUtils#indexOf} instead of walking byte by byte. Unlike
         * {@link #scanString} it does not reject unescaped control characters, matching Cloffle's
         * permissive treatment of skipped scalars.
         *
         * <p>The same substitution measured slower in {@code scanRawKey} and in the two
         * {@code skip*Remainder} loops, where the searched runs are short, so they keep their
         * byte loops.
         */
        private void skipStringValue() {
            if (buf == null) {
                scanString();
                pos++;
                return;
            }
            if (pos >= end || buf[pos] != '"') {
                throw err("Expected string");
            }
            int i = pos + 1;
            while (i <= end) {
                int hit = ArrayUtils.indexOf(buf, i, end, STRING_END);
                if (hit < 0) {
                    break;
                }
                if (buf[hit] == '"') {
                    pos = hit + 1;
                    return;
                }
                i = hit + 2;
            }
            throw err("Unterminated string");
        }

        private void scanString() {
            if (pos >= end || sourceByte(pos) != '"') {
                throw err("Expected string");
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
                        throw err("Unterminated string escape");
                    }
                    byte escaped = sourceByte(pos++);
                    switch (escaped) {
                        case '"', '\\', '/', 'b', 'f', 'n', 'r', 't':
                            break;
                        case 'u':
                            if (pos + 4 > end) {
                                throw err("Invalid unicode escape");
                            }
                            for (int i = 0; i < 4; i++) {
                                hexDigit(sourceByte(pos + i));
                            }
                            pos += 4;
                            break;
                        default:
                            throw err("Invalid escape");
                    }
                    continue;
                }
                if ((c & 0xff) < 0x20) {
                    throw err("Unescaped control character");
                }
                pos++;
            }
            throw err("Unterminated string");
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
                    throw err("Unescaped control character");
                }
                i++;
            }
            throw err("Unterminated string");
        }

        @CompilerDirectives.EarlyInline
        private boolean sameBytes(TypedTrieEdge edge, int start, int len) {
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
        private Object parseDynamicTyped(TypedValueNode node) {
            if (node.kind == TypedValueNode.VECTOR) {
                if (pos >= end || sourceByte(pos) != '[') {
                    throw err("Expected array");
                }
                pos++;
                enterDepth();
                ArrayList<Object> values = new ArrayList<>();
                try {
                    skipWs();
                    if (pos < end && sourceByte(pos) == ']') {
                        pos++;
                        return RT.vector();
                    }
                    while (true) {
                        skipWs();
                        values.add(parseDynamicTyped(node.child));
                        skipWs();
                        if (pos >= end) {
                            throw err("Unclosed array");
                        }
                        byte c = sourceByte(pos++);
                        if (c == ']') {
                            return RT.vector(values.toArray());
                        }
                        if (c != ',') {
                            throw err("Expected ',' or ']'");
                        }
                    }
                } finally {
                    depth--;
                }
            }
            TypedLeaf leaf = node.leaf;
            if (leaf.nullable && startsWithLiteral("null")) {
                pos += 4;
                return null;
            }
            return switch (leaf.kind) {
                case TypedLeaf.INT -> {
                    if (pos >= end || (sourceByte(pos) != '-' && !isDigit(sourceByte(pos)))) {
                        throw err("Expected integer");
                    }
                    int start = pos;
                    boolean negative = false;
                    if (sourceByte(pos) == '-') {
                        negative = true;
                        pos++;
                        if (pos >= end || !isDigit(sourceByte(pos))) {
                            throw err("Invalid number");
                        }
                    }
                    long val = 0;
                    boolean overflow = false;
                    if (sourceByte(pos) == '0') {
                        pos++;
                        if (pos < end && isDigit(sourceByte(pos))) {
                            throw err("Leading zeros are not allowed");
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
                        throw err("Expected integer");
                    }
                    long finalVal = negative ? -val : val;
                    if (overflow || finalVal < Integer.MIN_VALUE || finalVal > Integer.MAX_VALUE) {
                        throw err("Integer outside schema range");
                    }
                    yield Integer.valueOf((int) finalVal);
                }
                case TypedLeaf.LONG -> {
                    if (pos >= end || (sourceByte(pos) != '-' && !isDigit(sourceByte(pos)))) {
                        throw err("Expected integer");
                    }
                    int start = pos;
                    boolean negative = false;
                    if (sourceByte(pos) == '-') {
                        negative = true;
                        pos++;
                        if (pos >= end || !isDigit(sourceByte(pos))) {
                            throw err("Invalid number");
                        }
                    }
                    long val = 0;
                    boolean overflow = false;
                    if (sourceByte(pos) == '0') {
                        pos++;
                        if (pos < end && isDigit(sourceByte(pos))) {
                            throw err("Leading zeros are not allowed");
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
                        throw err("Expected integer");
                    }
                    if (overflow) {
                        throw err("Integer outside schema range");
                    }
                    yield Long.valueOf(negative ? -val : val);
                }
                case TypedLeaf.DOUBLE -> {
                    int start = pos;
                    scanNumber();
                    TruffleString number = truffleSlice(start, pos - start, true);
                    try {
                        yield Double.valueOf(
                                TruffleString.ParseDoubleNode.getUncached().execute(number));
                    } catch (TruffleString.NumberFormatException e) {
                        throw err("Number outside schema range");
                    }
                }
                case TypedLeaf.BOOLEAN -> {
                    if (startsWithLiteral("true")) {
                        pos += 4;
                        yield Boolean.TRUE;
                    }
                    if (startsWithLiteral("false")) {
                        pos += 5;
                        yield Boolean.FALSE;
                    }
                    throw err("Expected boolean");
                }
                case TypedLeaf.STRING -> parseStringValue();
                case TypedLeaf.NULL -> {
                    if (!startsWithLiteral("null")) {
                        throw err("Expected null");
                    }
                    pos += 4;
                    yield null;
                }
                case TypedLeaf.TRUFFLE_STRING -> {
                    if (pos >= end || sourceByte(pos) != '"') {
                        throw err("Expected string");
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
                default -> throw err("Unsupported dynamic array leaf");
            };
        }

        private boolean scanNumber() {
            if (sourceByte(pos) == '-') {
                pos++;
                if (pos >= end || !isDigit(sourceByte(pos))) {
                    throw err("Invalid number");
                }
            }
            boolean isFloat = false;
            if (sourceByte(pos) == '0') {
                pos++;
                if (pos < end && isDigit(sourceByte(pos))) {
                    throw err("Leading zeros are not allowed");
                }
            } else {
                if (!isDigit(sourceByte(pos))) {
                    throw err("Invalid number");
                }
                while (pos < end && isDigit(sourceByte(pos))) {
                    pos++;
                }
            }
            if (pos < end && sourceByte(pos) == '.') {
                isFloat = true;
                pos++;
                if (pos >= end || !isDigit(sourceByte(pos))) {
                    throw err("Invalid number");
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
                    throw err("Invalid number");
                }
                while (pos < end && isDigit(sourceByte(pos))) {
                    pos++;
                }
            }
            return isFloat;
        }

        @TruffleBoundary
        private String parseStringValue() {
            scanString();
            int start = strStart;
            int closeQuote = pos;
            String s = strEscaped
                    ? unescape(start, closeQuote)
                    : utf8Slice(start, closeQuote - start);
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
                    throw err("Unterminated string escape");
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
                            throw err("Invalid unicode escape");
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
                        throw err("Invalid escape");
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
            throw err("Invalid hex digit");
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
                    throw new ParseException("Unterminated string escape", escapePosition);
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
                    default -> throw new ParseException("Invalid escape", escapePosition);
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

        private String utf8Slice(int start, int len) {
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
                throw new ParseException("Unterminated string escape", escapePosition);
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
                default -> throw new ParseException("Invalid escape", escapePosition);
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
        return new JsonParser().extract(json, off, off + len, path, keyUtf8);
    }

    @TruffleBoundary
    public static Object projectString(String json, TrieNode root, int slotCount) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return projectBytes(bytes, 0, bytes.length, root, slotCount);
    }

    @TruffleBoundary
    public static Object projectCharSequence(CharSequence json, TrieNode root, int slotCount) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        if (json instanceof String s) {
            return projectString(s, root, slotCount);
        }
        byte[] bytes = utf8Bytes(json);
        return projectBytes(bytes, 0, bytes.length, root, slotCount);
    }

    @TruffleBoundary
    public static Object projectBytes(byte[] json, TrieNode root, int slotCount) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        return projectBytes(json, 0, json.length, root, slotCount);
    }

    @TruffleBoundary
    public static Object projectByteBuffer(ByteBuffer json, TrieNode root, int slotCount) {
        if (json == null) {
            throw new NullPointerException("json");
        }
        byte[] bytes = bytesOf(json);
        return projectBytes(bytes, 0, bytes.length, root, slotCount);
    }

    static Object projectBytes(byte[] json, int off, int len, TrieNode root, int slotCount) {
        if (off < 0 || len < 0 || off + len > json.length) {
            throw new IndexOutOfBoundsException();
        }
        return new JsonParser().project(json, off, off + len, root, slotCount);
    }

    /**
     * One SAX pass filling {@code slotCount} slots. Returns the filled array, or {@link #FALLBACK}.
     * Slots that were never written remain {@link #MISSING}.
     */
    Object project(byte[] json, int from, int to, TrieNode root, int slotCount) {
        this.buf = json;
        this.pos = from;
        this.end = to;
        this.depth = 0;
        this.keywordize = true;
        this.keyFn = null;
        if (projectSlots == null || projectSlots.length < slotCount) {
            projectSlots = new Object[slotCount];
        }
        Arrays.fill(projectSlots, 0, slotCount, MISSING);
        try {
            skipWs();
            if (pos >= end) {
                throw err("Empty JSON");
            }
            if (!projectValue(root)) {
                return FALLBACK;
            }
            skipWs();
            if (pos != end) {
                throw err("Trailing content");
            }
            return projectSlots;
        } finally {
            this.buf = null;
            this.keyFn = null;
        }
    }

    private boolean projectValue(TrieNode node) {
        if (node.slot >= 0) {
            projectSlots[node.slot] = parseValue();
            return true;
        }
        if (pos >= end) {
            throw err("Unexpected end of JSON");
        }
        byte c = buf[pos];
        boolean wantObject = false;
        boolean wantArray = false;
        for (TrieEdge edge : node.edges) {
            if (edge.isKeyword()) {
                wantObject = true;
            } else {
                wantArray = true;
            }
        }
        if (wantObject && wantArray) {
            // A JSON Pointer token is a name and an index at once; let the input decide.
            if (c == '{') {
                return projectFromObject(node);
            }
            if (c == '[') {
                return projectFromArray(node);
            }
            return false;
        }
        if (wantObject) {
            if (c != '{') {
                return false;
            }
            return projectFromObject(node);
        }
        if (wantArray) {
            if (c != '[') {
                return false;
            }
            return projectFromArray(node);
        }
        skipValue();
        return true;
    }

    private boolean projectFromObject(TrieNode node) {
        pos++;
        depth++;
        if (depth > MAX_DEPTH) {
            throw err("Nesting too deep");
        }
        try {
            skipWs();
            if (pos < end && buf[pos] == '}') {
                pos++;
                return true;
            }
            while (true) {
                skipWs();
                if (pos >= end || buf[pos] != '"') {
                    throw err("Expected object key");
                }
                int keyStart = pos + 1;
                int keyEnd = scanRawKey();
                if (keyEnd < 0) {
                    return false;
                }
                TrieEdge match = findKeywordEdge(node, keyStart, keyEnd - keyStart);
                pos = keyEnd + 1;
                skipWs();
                if (pos >= end || buf[pos] != ':') {
                    throw err("Expected ':'");
                }
                pos++;
                skipWs();
                if (match != null) {
                    if (!projectValue(match.child)) {
                        return false;
                    }
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
        return true;
    }

    private TrieEdge findKeywordEdge(TrieNode node, int start, int len) {
        for (TrieEdge edge : node.edges) {
            if (edge.isKeyword() && sameBytes(edge.utf8, start, len)) {
                return edge;
            }
        }
        return null;
    }

    private boolean projectFromArray(TrieNode node) {
        pos++;
        depth++;
        if (depth > MAX_DEPTH) {
            throw err("Nesting too deep");
        }
        int maxIndex = -1;
        for (TrieEdge edge : node.edges) {
            if (!edge.isKeyword() && edge.index > maxIndex) {
                maxIndex = edge.index;
            }
        }
        int i = 0;
        try {
            skipWs();
            if (pos < end && buf[pos] == ']') {
                pos++;
                return maxIndex < 0;
            }
            while (true) {
                skipWs();
                TrieEdge match = findIndexEdge(node, i);
                if (match != null) {
                    if (!projectValue(match.child)) {
                        return false;
                    }
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
        return maxIndex < i;
    }

    private TrieEdge findIndexEdge(TrieNode node, int index) {
        for (TrieEdge edge : node.edges) {
            if (!edge.isKeyword() && edge.index == index) {
                return edge;
            }
        }
        return null;
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
            byte c = sourceByte(i);
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
            if (want[i] != sourceByte(start + i)) {
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
            byte c = sourceByte(pos);
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
