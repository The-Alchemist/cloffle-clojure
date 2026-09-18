package net.javacrumbs.cloffle.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.filter.FilteringParserDelegate;
import com.fasterxml.jackson.core.filter.JsonPointerBasedFilter;
import com.fasterxml.jackson.core.filter.TokenFilter;
import clojure.lang.AFn;
import clojure.lang.IFn;
import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentVector;
import clojure.lang.JsonParser;
import clojure.lang.Keyword;
import clojure.lang.RT;
import clojure.lang.Symbol;
import org.graalvm.polyglot.Context;
import org.simdjson.SimdJsonParser;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Parse + lookup for typical HTTP JSON. Run with {@code -prof gc} to compare allocation.
 * SIMD stage-1 is not on this path; add it only if large payloads show the scalar scan.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Threads(1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 2, time = 1)
public class JsonParserBenchmark {

    private static final ThreadLocal<Map<String, Object>> CAPTURED =
            ThreadLocal.withInitial(HashMap::new);

    static final String JSONAPI =
            "{\"data\":{\"type\":\"articles\",\"id\":\"article-101\",\"attributes\":{\"title\":\"Shape maps in practice\",\"slug\":\"shape-maps\",\"status\":\"published\",\"author\":\"Avery\"},\"relationships\":{\"author\":{\"type\":\"people\",\"id\":\"person-7\"}},\"links\":{\"self\":\"/articles/article-101\"}},\"meta\":{\"request-id\":\"req-101\",\"version\":\"v1\"}}";

    static final String ENTITY16 =
            "{\"id\":\"user-101\",\"type\":\"user\",\"tenant-id\":\"org-3\",\"email\":\"avery@example.test\",\"username\":\"avery\",\"status\":\"pending\",\"role\":\"admin\",\"created-at\":\"2026-01-10\",\"updated-at\":\"2026-09-09\",\"version\":\"v7\",\"locale\":\"en-US\",\"timezone\":\"America/New_York\",\"profile\":\"x\",\"settings\":\"y\",\"organization\":\"z\",\"audit\":\"w\"}";

    static final String ROWS =
            "[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"},{\"id\":3,\"name\":\"c\"},{\"id\":4,\"name\":\"d\"},{\"id\":5,\"name\":\"e\"},{\"id\":6,\"name\":\"f\"},{\"id\":7,\"name\":\"g\"},{\"id\":8,\"name\":\"h\"}]";

    static final String ESCAPED =
            "{\"message\":\"line\\nvalue \\u263a\",\"id\":123}";

    public static Object captureGuestValue(String name, Object value) {
        CAPTURED.get().put(name, value);
        return value;
    }

    public static String fixture(String path) {
        return ClojureClasspathResources.read(path);
    }

    private Context context;
    private byte[] jsonapiBytes;
    private byte[] entity16Bytes;
    private byte[] rowsBytes;
    private IFn cheshireParse;
    private IFn jsonistaRead;
    private Object jsonistaMapper;
    private ObjectMapper jacksonMapper;
    private JsonFactory jacksonFactory;
    private IFn guestJsonapi;
    private IFn guestEntity16;
    private IFn guestRows;
    private IFn guestEscapeJsonapi;
    private IFn guestEscapeEntity16;
    private IFn guestProjectJsonapi;
    private IFn guestProjectEntity16;
    private IFn guestProjectJsonapiBytes;
    private IFn guestProjectEntity16Bytes;
    private IFn guestProjectGithub;
    private IFn guestProjectGithubBytes;
    private IFn guestProjectTwitterFirstBytes;
    private IFn guestTypedJsonapi;
    private IFn guestTypedJsonapiBytes;
    private IFn guestJacksonJsonapiBytes;
    private IFn guestJackson3JsonapiBytes;
    private IFn guestJackson3JsonapiTruffleBytes;
    private IFn guestTypedJsonapiTruffleBytes;
    private IFn guestTypedGithub;
    private IFn guestTypedGithubBytes;
    private IFn guestJacksonGithubBytes;
    private IFn guestJackson3GithubBytes;
    private IFn guestJackson3GithubTruffleBytes;
    private IFn guestTypedGithubTruffleBytes;
    private IFn guestTypedGithubBuffer;
    private IFn guestTypedGithubSumBytes;
    private IFn guestJacksonGithubSumBytes;
    private IFn guestJackson3GithubSumBytes;
    private IFn guestTypedTwitterFirst;
    private IFn guestTypedTwitterFirstBytes;
    private IFn guestJacksonTwitterFirstBytes;
    private IFn guestJackson3TwitterFirstBytes;
    private IFn guestJackson3TwitterFirstTruffleBytes;
    private IFn guestJackson3TwitterFirstTruffleInput;
    private IFn guestTypedTwitterFirstTruffleBytes;
    private IFn guestTypedTwitterFirstTruffleInput;
    private IFn guestTypedTwitterFirstBuffer;
    private IFn guestTypedEscaped;
    private IFn guestUnschemedGithubBytes;
    private IFn guestTypedGithubEarlyBytes;
    private IFn guestTypedGithubLateBytes;
    private IFn guestJacksonGithubEarlyBytes;
    private IFn guestJacksonGithubLateBytes;
    private IFn guestJackson3GithubEarlyBytes;
    private IFn guestJackson3GithubLateBytes;
    private byte[] doublesBytes;
    private IFn guestTypedDoublesBytes;
    private IFn guestJackson3DoublesBytes;
    private SimdJsonParser simdjsonParser;
    private IFn guestSelectGithubBytes;
    private IFn guestSelectJsonapiBytes;
    private IFn guestJackson3SelectGithubBytes;
    private IFn guestJackson3SelectJsonapiBytes;
    private JsonPointer[] githubPointers;
    private JsonPointer[] jsonapiPointers;
    private IFn guestJsonSchemaGithubBytes;
    private IFn guestJsonSchemaGithubEarlyBytes;
    private IFn guestJsonSchemaGithubLateBytes;
    private IFn guestSimdjsonJsonapiBytes;
    private IFn guestSimdjsonJsonapiTruffleBytes;
    private IFn guestSimdjsonGithubBytes;
    private IFn guestSimdjsonGithubTruffleBytes;
    private IFn guestSimdjsonGithubSumBytes;
    private IFn guestSimdjsonGithubEarlyBytes;
    private IFn guestSimdjsonGithubLateBytes;
    private IFn guestSimdjsonTwitterFirstBytes;
    private IFn guestSimdjsonTwitterFirstTruffleBytes;
    private IFn guestSimdjsonTwitterFirstTruffleInput;
    private IFn guestSimdjsonDoublesBytes;
    private IFn guestSimdjsonSelectGithubBytes;
    private IFn guestSimdjsonSelectJsonapiBytes;
    private IFn guestPlaceholder;
    private IFn guestProjectPlaceholderBytes;
    private IFn guestTypedPlaceholderBytes;
    private IFn guestJackson3PlaceholderBytes;
    private IFn guestSimdjsonPlaceholderBytes;
    private IFn guestSelectPlaceholderBytes;
    private IFn guestJackson3SelectPlaceholderBytes;
    private IFn guestSimdjsonSelectPlaceholderBytes;
    private JsonPointer[] placeholderPointers;
    private IFn malliGithub;
    private IFn malliTwitter;
    private IFn charredParse;
    private IFn lazyJsonParse;
    private IFn lazyJsonConsume;
    private Object lazyJsonEarlyAutomaton;
    private Object lazyJsonLateAutomaton;
    private Object lazyJsonFourAutomaton;
    private final Object[] lazyJsonSlot = new Object[4];
    private final Keyword jsonRoot = Keyword.intern("$");
    private String githubJson;
    private byte[] githubBytes;
    private String twitterJson;
    private byte[] twitterBytes;
    private String placeholderJson;
    private byte[] placeholderBytes;
    private final Keyword kwData = Keyword.intern("data");
    private final Keyword kwAttributes = Keyword.intern("attributes");
    private final Keyword kwTitle = Keyword.intern("title");
    private final Keyword kwId = Keyword.intern("id");
    private final Keyword kwUserId = Keyword.intern("userId");
    private final Keyword kwEmail = Keyword.intern("email");
    private final Keyword kwName = Keyword.intern("name");
    private final Keyword kwFullName = Keyword.intern("full_name");
    private final Keyword kwNetworkCount = Keyword.intern("network_count");
    private final Keyword kwOwner = Keyword.intern("owner");
    private final Keyword kwLogin = Keyword.intern("login");
    private final Keyword kwStargazers = Keyword.intern("stargazers_count");
    private final Keyword kwOpenIssues = Keyword.intern("open_issues_count");

    @Setup(Level.Trial)
    public void setup() throws Exception {
        RT.init();
        jsonapiBytes = JSONAPI.getBytes(StandardCharsets.UTF_8);
        entity16Bytes = ENTITY16.getBytes(StandardCharsets.UTF_8);
        rowsBytes = ROWS.getBytes(StandardCharsets.UTF_8);
        githubJson = fixture("json-parser-benchmark/data/github-clojure-repo.json");
        githubBytes = githubJson.getBytes(StandardCharsets.UTF_8);
        twitterJson = fixture("json-parser-benchmark/data/twitter.json");
        twitterBytes = twitterJson.getBytes(StandardCharsets.UTF_8);
        placeholderJson = fixture("json-parser-benchmark/data/jsonplaceholder-post-1.json");
        placeholderBytes = placeholderJson.getBytes(StandardCharsets.UTF_8);
        doublesBytes = fixture("json-parser-benchmark/data/doubles.json")
                .getBytes(StandardCharsets.UTF_8);

        IFn require = RT.var("clojure.core", "require");
        require.invoke(Symbol.intern("cheshire.core"));
        require.invoke(Symbol.intern("jsonista.core"));
        require.invoke(Symbol.intern("charred.api"));
        require.invoke(Symbol.intern("clj-lazy-json.core"));
        cheshireParse = RT.var("cheshire.core", "parse-string");
        jsonistaRead = RT.var("jsonista.core", "read-value");
        jsonistaMapper = RT.var("jsonista.core", "keyword-keys-object-mapper").deref();
        jacksonMapper = new ObjectMapper();
        jacksonFactory = jacksonMapper.getFactory();
        charredParse = (IFn) RT.var("charred.api", "parse-json-fn").invoke(
                RT.map(Keyword.intern("key-fn"), RT.var("clojure.core", "keyword")));
        lazyJsonParse = RT.var("clj-lazy-json.core", "parse-string");
        lazyJsonConsume = RT.var("clj-lazy-json.core", "consume-json");
        IFn buildAutomaton = RT.var("clj-lazy-json.core", "build-automaton");
        lazyJsonEarlyAutomaton = buildAutomaton.invoke(
                RT.map(),
                RT.vector(RT.vector(
                        RT.vector(jsonRoot, "full_name"),
                        lazyJsonCapture(0))));
        lazyJsonLateAutomaton = buildAutomaton.invoke(
                RT.map(),
                RT.vector(RT.vector(
                        RT.vector(jsonRoot, "network_count"),
                        lazyJsonCapture(0))));
        lazyJsonFourAutomaton = buildAutomaton.invoke(
                RT.map(),
                RT.vector(
                        RT.vector(RT.vector(jsonRoot, "full_name"), lazyJsonCapture(0)),
                        RT.vector(RT.vector(jsonRoot, "stargazers_count"), lazyJsonCapture(1)),
                        RT.vector(RT.vector(jsonRoot, "open_issues_count"), lazyJsonCapture(2)),
                        RT.vector(RT.vector(jsonRoot, "owner", "login"), lazyJsonCapture(3))));
        RT.load("json-parser-benchmark/malli");
        malliGithub = RT.var("json-parser-benchmark.malli", "parse-github");
        malliTwitter = RT.var("json-parser-benchmark.malli", "parse-twitter");

        context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build();
        context.eval("cloffle", ClojureClasspathResources.read("json-parser-benchmark/setup.clj"));
        context.enter();
        guestJsonapi = guestFn("guest-parse-lookup-jsonapi");
        guestEntity16 = guestFn("guest-parse-lookup-entity16");
        guestRows = guestFn("guest-parse-lookup-rows");
        guestEscapeJsonapi = guestFn("guest-parse-escape-jsonapi");
        guestEscapeEntity16 = guestFn("guest-parse-escape-entity16");
        guestProjectJsonapi = guestFn("guest-project-jsonapi");
        guestProjectEntity16 = guestFn("guest-project-entity16");
        guestProjectJsonapiBytes = guestFn("guest-project-jsonapi-bytes");
        guestProjectEntity16Bytes = guestFn("guest-project-entity16-bytes");
        guestProjectGithub = guestFn("guest-project-github");
        guestProjectGithubBytes = guestFn("guest-project-github-bytes");
        guestProjectTwitterFirstBytes = guestFn("guest-project-twitter-first-bytes");
        guestTypedJsonapi = guestFn("guest-typed-jsonapi");
        guestTypedJsonapiBytes = guestFn("guest-typed-jsonapi-bytes");
        guestJacksonJsonapiBytes = guestFn("guest-jackson-jsonapi-bytes");
        guestJackson3JsonapiBytes = guestFn("guest-jackson3-jsonapi-bytes");
        guestJackson3JsonapiTruffleBytes = guestFn("guest-jackson3-jsonapi-truffle-bytes");
        guestTypedJsonapiTruffleBytes = guestFn("guest-typed-jsonapi-truffle-bytes");
        guestTypedGithub = guestFn("guest-typed-github");
        guestTypedGithubBytes = guestFn("guest-typed-github-bytes");
        guestJacksonGithubBytes = guestFn("guest-jackson-github-bytes");
        guestJackson3GithubBytes = guestFn("guest-jackson3-github-bytes");
        guestJackson3GithubTruffleBytes = guestFn("guest-jackson3-github-truffle-bytes");
        guestTypedGithubTruffleBytes = guestFn("guest-typed-github-truffle-bytes");
        guestTypedGithubBuffer = guestFn("guest-typed-github-buffer");
        guestTypedGithubSumBytes = guestFn("guest-typed-github-sum-bytes");
        guestJacksonGithubSumBytes = guestFn("guest-jackson-github-sum-bytes");
        guestJackson3GithubSumBytes = guestFn("guest-jackson3-github-sum-bytes");
        guestTypedTwitterFirst = guestFn("guest-typed-twitter-first");
        guestTypedTwitterFirstBytes = guestFn("guest-typed-twitter-first-bytes");
        guestJacksonTwitterFirstBytes = guestFn("guest-jackson-twitter-first-bytes");
        guestJackson3TwitterFirstBytes = guestFn("guest-jackson3-twitter-first-bytes");
        guestJackson3TwitterFirstTruffleBytes =
                guestFn("guest-jackson3-twitter-first-truffle-bytes");
        guestJackson3TwitterFirstTruffleInput =
                guestFn("guest-jackson3-twitter-first-truffle-input");
        guestTypedTwitterFirstTruffleBytes =
                guestFn("guest-typed-twitter-first-truffle-bytes");
        guestTypedTwitterFirstTruffleInput =
                guestFn("guest-typed-twitter-first-truffle-input");
        guestTypedTwitterFirstBuffer = guestFn("guest-typed-twitter-first-buffer");
        guestTypedEscaped = guestFn("guest-typed-escaped");
        guestUnschemedGithubBytes = guestFn("guest-unschemed-github-bytes");
        guestTypedGithubEarlyBytes = guestFn("guest-typed-github-early-bytes");
        guestTypedGithubLateBytes = guestFn("guest-typed-github-late-bytes");
        guestJacksonGithubEarlyBytes = guestFn("guest-jackson-github-early-bytes");
        guestJacksonGithubLateBytes = guestFn("guest-jackson-github-late-bytes");
        guestJackson3GithubEarlyBytes = guestFn("guest-jackson3-github-early-bytes");
        guestJackson3GithubLateBytes = guestFn("guest-jackson3-github-late-bytes");
        // Sized for the largest payload plus simdjson's 64-byte padding, rather than its 34 MiB
        // default, so the reusable buffers stay in a plausible range.
        simdjsonParser = new SimdJsonParser(1 << 20, 1024);
        guestTypedDoublesBytes = guestFn("guest-typed-doubles-bytes");
        guestJackson3DoublesBytes = guestFn("guest-jackson3-doubles-bytes");
        guestSelectGithubBytes = guestFn("guest-select-github-bytes");
        guestSelectJsonapiBytes = guestFn("guest-select-jsonapi-bytes");
        guestJackson3SelectGithubBytes = guestFn("guest-jackson3-select-github-bytes");
        guestJackson3SelectJsonapiBytes = guestFn("guest-jackson3-select-jsonapi-bytes");
        githubPointers = compilePointers(
                "/full_name", "/stargazers_count", "/open_issues_count", "/owner/login");
        jsonapiPointers = compilePointers(
                "/data/id", "/data/attributes/title", "/meta/request-id");
        placeholderPointers = compilePointers("/id", "/userId", "/title");
        guestPlaceholder = guestFn("guest-parse-lookup-placeholder");
        guestProjectPlaceholderBytes = guestFn("guest-project-placeholder-bytes");
        guestTypedPlaceholderBytes = guestFn("guest-typed-placeholder-bytes");
        guestJackson3PlaceholderBytes = guestFn("guest-jackson3-placeholder-bytes");
        guestSimdjsonPlaceholderBytes = guestFn("guest-simdjson-placeholder-bytes");
        guestSelectPlaceholderBytes = guestFn("guest-select-placeholder-bytes");
        guestJackson3SelectPlaceholderBytes = guestFn("guest-jackson3-select-placeholder-bytes");
        guestSimdjsonSelectPlaceholderBytes = guestFn("guest-simdjson-select-placeholder-bytes");
        guestJsonSchemaGithubBytes = guestFn("guest-json-schema-github-bytes");
        guestJsonSchemaGithubEarlyBytes = guestFn("guest-json-schema-github-early-bytes");
        guestJsonSchemaGithubLateBytes = guestFn("guest-json-schema-github-late-bytes");
        guestSimdjsonJsonapiBytes = guestFn("guest-simdjson-jsonapi-bytes");
        guestSimdjsonJsonapiTruffleBytes = guestFn("guest-simdjson-jsonapi-truffle-bytes");
        guestSimdjsonGithubBytes = guestFn("guest-simdjson-github-bytes");
        guestSimdjsonGithubTruffleBytes = guestFn("guest-simdjson-github-truffle-bytes");
        guestSimdjsonGithubSumBytes = guestFn("guest-simdjson-github-sum-bytes");
        guestSimdjsonGithubEarlyBytes = guestFn("guest-simdjson-github-early-bytes");
        guestSimdjsonGithubLateBytes = guestFn("guest-simdjson-github-late-bytes");
        guestSimdjsonTwitterFirstBytes = guestFn("guest-simdjson-twitter-first-bytes");
        guestSimdjsonTwitterFirstTruffleBytes =
                guestFn("guest-simdjson-twitter-first-truffle-bytes");
        guestSimdjsonTwitterFirstTruffleInput =
                guestFn("guest-simdjson-twitter-first-truffle-input");
        guestSimdjsonDoublesBytes = guestFn("guest-simdjson-doubles-bytes");
        guestSimdjsonSelectGithubBytes = guestFn("guest-simdjson-select-github-bytes");
        guestSimdjsonSelectJsonapiBytes = guestFn("guest-simdjson-select-jsonapi-bytes");
        CAPTURED.remove();
    }

    private IFn guestFn(String name) {
        context.eval("cloffle",
                "(net.javacrumbs.cloffle.benchmark.JsonParserBenchmark/captureGuestValue \""
                        + name + "\" " + name + ")");
        Object value = CAPTURED.get().remove(name);
        if (value == null) {
            throw new IllegalStateException("Guest value was not captured: " + name);
        }
        return (IFn) value;
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (context != null) {
            context.leave();
            context.close();
        }
    }

    @Benchmark
    public Object cloffleParseJsonapi() {
        return JsonParser.parseBytes(jsonapiBytes);
    }

    @Benchmark
    public Object cloffleParseLookupJsonapi() {
        IPersistentMap doc = (IPersistentMap) JsonParser.parseBytes(jsonapiBytes);
        IPersistentMap data = (IPersistentMap) doc.valAt(kwData);
        IPersistentMap attrs = (IPersistentMap) data.valAt(kwAttributes);
        return attrs.valAt(kwTitle);
    }

    @Benchmark
    public Object cloffleParseLookupEntity16() {
        IPersistentMap m = (IPersistentMap) JsonParser.parseBytes(entity16Bytes);
        return m.valAt(kwEmail);
    }

    @Benchmark
    public Object cloffleParseLookupRows() {
        IPersistentVector v = (IPersistentVector) JsonParser.parseBytes(rowsBytes);
        IPersistentMap row = (IPersistentMap) v.nth(3);
        return row.valAt(kwName);
    }

    @Benchmark
    public Object cloffleParseLookupPlaceholder() {
        IPersistentMap doc = (IPersistentMap) JsonParser.parseBytes(placeholderBytes);
        return doc.valAt(kwTitle);
    }

    @Benchmark
    public Object cheshireParseLookupJsonapi() {
        IPersistentMap doc = (IPersistentMap) cheshireParse.invoke(JSONAPI, Boolean.TRUE);
        IPersistentMap data = (IPersistentMap) doc.valAt(kwData);
        IPersistentMap attrs = (IPersistentMap) data.valAt(kwAttributes);
        return attrs.valAt(kwTitle);
    }

    @Benchmark
    public Object jsonistaParseLookupJsonapi() {
        IPersistentMap doc = (IPersistentMap) jsonistaRead.invoke(JSONAPI, jsonistaMapper);
        IPersistentMap data = (IPersistentMap) doc.valAt(kwData);
        IPersistentMap attrs = (IPersistentMap) data.valAt(kwAttributes);
        return attrs.valAt(kwTitle);
    }

    @Benchmark
    public Object cheshireParseLookupEntity16() {
        IPersistentMap m = (IPersistentMap) cheshireParse.invoke(ENTITY16, Boolean.TRUE);
        return m.valAt(kwEmail);
    }

    @Benchmark
    public Object jsonistaParseLookupEntity16() {
        IPersistentMap m = (IPersistentMap) jsonistaRead.invoke(ENTITY16, jsonistaMapper);
        return m.valAt(kwEmail);
    }

    @Benchmark
    public Object cheshireParseLookupPlaceholder() {
        IPersistentMap doc = (IPersistentMap) cheshireParse.invoke(placeholderJson, Boolean.TRUE);
        return doc.valAt(kwTitle);
    }

    @Benchmark
    public Object jsonistaParseLookupPlaceholder() {
        IPersistentMap doc =
                (IPersistentMap) jsonistaRead.invoke(placeholderJson, jsonistaMapper);
        return doc.valAt(kwTitle);
    }

    @Benchmark
    public String jacksonParseLookupJsonapiString() throws Exception {
        return jacksonMapper.readTree(JSONAPI)
                .get("data").get("attributes").get("title").textValue();
    }

    @Benchmark
    public String jacksonParseLookupJsonapiBytes() throws Exception {
        return jacksonMapper.readTree(jsonapiBytes)
                .get("data").get("attributes").get("title").textValue();
    }

    @Benchmark
    public String jacksonParseLookupEntity16String() throws Exception {
        return jacksonMapper.readTree(ENTITY16).get("email").textValue();
    }

    @Benchmark
    public String jacksonParseLookupEntity16Bytes() throws Exception {
        return jacksonMapper.readTree(entity16Bytes).get("email").textValue();
    }

    @Benchmark
    public String jacksonParseLookupRowsString() throws Exception {
        return jacksonMapper.readTree(ROWS).get(3).get("name").textValue();
    }

    @Benchmark
    public String jacksonParseLookupRowsBytes() throws Exception {
        return jacksonMapper.readTree(rowsBytes).get(3).get("name").textValue();
    }

    @Benchmark
    public String jacksonParseLookupPlaceholderBytes() throws Exception {
        return jacksonMapper.readTree(placeholderBytes).get("title").textValue();
    }

    @Benchmark
    public ObjectNode jacksonProjectJsonapiString() throws Exception {
        return jacksonProjectJsonapi(jacksonMapper.readTree(JSONAPI));
    }

    @Benchmark
    public ObjectNode jacksonProjectJsonapiBytes() throws Exception {
        return jacksonProjectJsonapi(jacksonMapper.readTree(jsonapiBytes));
    }

    @Benchmark
    public ObjectNode jacksonProjectEntity16String() throws Exception {
        return jacksonProjectEntity16(jacksonMapper.readTree(ENTITY16));
    }

    @Benchmark
    public ObjectNode jacksonProjectEntity16Bytes() throws Exception {
        return jacksonProjectEntity16(jacksonMapper.readTree(entity16Bytes));
    }

    @Benchmark
    public ObjectNode jacksonProjectPlaceholderBytes() throws Exception {
        return jacksonProjectPlaceholder(jacksonMapper.readTree(placeholderBytes));
    }

    private ObjectNode jacksonProjectJsonapi(JsonNode root) {
        ObjectNode out = jacksonMapper.createObjectNode();
        out.set("title", root.get("data").get("attributes").get("title"));
        out.set("id", root.get("data").get("id"));
        out.set("rid", root.get("meta").get("request-id"));
        return out;
    }

    private ObjectNode jacksonProjectEntity16(JsonNode root) {
        ObjectNode out = jacksonMapper.createObjectNode();
        out.set("id", root.get("id"));
        out.set("email", root.get("email"));
        out.set("status", root.get("status"));
        return out;
    }

    private ObjectNode jacksonProjectPlaceholder(JsonNode root) {
        ObjectNode out = jacksonMapper.createObjectNode();
        out.set("id", root.get("id"));
        out.set("userId", root.get("userId"));
        out.set("title", root.get("title"));
        return out;
    }

    @Benchmark
    public ObjectNode jacksonProjectGithubString() throws Exception {
        return jacksonProjectGithub(jacksonMapper.readTree(githubJson));
    }

    @Benchmark
    public ObjectNode jacksonProjectGithubBytes() throws Exception {
        return jacksonProjectGithub(jacksonMapper.readTree(githubBytes));
    }

    private ObjectNode jacksonProjectGithub(JsonNode root) {
        ObjectNode owner = jacksonMapper.createObjectNode();
        owner.set("login", root.get("owner").get("login"));
        ObjectNode out = jacksonMapper.createObjectNode();
        out.set("full_name", root.get("full_name"));
        out.set("stargazers_count", root.get("stargazers_count"));
        out.set("open_issues_count", root.get("open_issues_count"));
        out.set("owner", owner);
        return out;
    }

    @Benchmark
    public ObjectNode jacksonStreamingProjectGithubBytes() throws Exception {
        String fullName = null;
        String login = null;
        int stars = 0;
        int issues = 0;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(githubBytes)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    continue;
                }
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                switch (field) {
                    case "full_name" -> fullName = parser.getValueAsString();
                    case "stargazers_count" -> stars = parser.getIntValue();
                    case "open_issues_count" -> issues = parser.getIntValue();
                    case "login" -> {
                        if (login == null) {
                            login = parser.getValueAsString();
                        }
                    }
                    default -> {
                        if (value == JsonToken.START_ARRAY) {
                            parser.skipChildren();
                        }
                    }
                }
            }
        }
        ObjectNode owner = jacksonMapper.createObjectNode();
        owner.put("login", login);
        ObjectNode out = jacksonMapper.createObjectNode();
        out.put("full_name", fullName);
        out.put("stargazers_count", stars);
        out.put("open_issues_count", issues);
        out.set("owner", owner);
        return out;
    }

    @Benchmark
    public ObjectNode jacksonProjectEscapedString() throws Exception {
        JsonNode root = jacksonMapper.readTree(ESCAPED);
        ObjectNode out = jacksonMapper.createObjectNode();
        out.set("message", root.get("message"));
        out.set("id", root.get("id"));
        return out;
    }

    @Benchmark
    public ObjectNode jacksonProjectTwitterFirstString() throws Exception {
        return jacksonProjectTwitterFirst(jacksonMapper.readTree(twitterJson));
    }

    @Benchmark
    public ObjectNode jacksonProjectTwitterFirstBytes() throws Exception {
        return jacksonProjectTwitterFirst(jacksonMapper.readTree(twitterBytes));
    }

    private ObjectNode jacksonProjectTwitterFirst(JsonNode root) {
        JsonNode first = root.get("statuses").get(0);
        ObjectNode user = jacksonMapper.createObjectNode();
        user.set("screen_name", first.get("user").get("screen_name"));
        ObjectNode status = jacksonMapper.createObjectNode();
        status.set("id", first.get("id"));
        status.set("text", first.get("text"));
        status.set("user", user);
        ObjectNode out = jacksonMapper.createObjectNode();
        out.putArray("statuses").add(status);
        return out;
    }

    @Benchmark
    public Object malliParseCoerceGithub() {
        return malliGithub.invoke(githubJson);
    }

    @Benchmark
    public Object malliParseCoerceTwitter() {
        return malliTwitter.invoke(twitterJson);
    }

    @Benchmark
    public Object guestParseLookupJsonapi() {
        return guestJsonapi.invoke();
    }

    @Benchmark
    public Object guestParseLookupEntity16() {
        return guestEntity16.invoke();
    }

    @Benchmark
    public Object guestParseLookupRows() {
        return guestRows.invoke();
    }

    @Benchmark
    public Object guestParseLookupPlaceholder() {
        return guestPlaceholder.invoke();
    }

    /**
     * Control for the fused parse-plus-access rewrite: the guest binds the parse result and returns
     * it, so the rewrite declines and this pays full construction cost.
     */
    @Benchmark
    public Object guestParseEscapeJsonapi() {
        return guestEscapeJsonapi.invoke();
    }

    @Benchmark
    public Object guestParseEscapeEntity16() {
        return guestEscapeEntity16.invoke();
    }

    @Benchmark
    public Object guestProjectJsonapi() {
        return guestProjectJsonapi.invoke();
    }

    @Benchmark
    public Object guestProjectEntity16() {
        return guestProjectEntity16.invoke();
    }

    @Benchmark
    public Object guestProjectJsonapiBytes() {
        return guestProjectJsonapiBytes.invoke();
    }

    @Benchmark
    public Object guestProjectEntity16Bytes() {
        return guestProjectEntity16Bytes.invoke();
    }

    @Benchmark
    public Object guestProjectGithub() {
        return guestProjectGithub.invoke();
    }

    @Benchmark
    public Object guestProjectGithubBytes() {
        return guestProjectGithubBytes.invoke();
    }

    @Benchmark
    public Object guestProjectTwitterFirstBytes() {
        return guestProjectTwitterFirstBytes.invoke();
    }

    @Benchmark
    public Object guestProjectPlaceholderBytes() {
        return guestProjectPlaceholderBytes.invoke();
    }

    @Benchmark
    public Object guestTypedJsonapi() {
        return guestTypedJsonapi.invoke();
    }

    @Benchmark
    public Object guestTypedJsonapiBytes() {
        return guestTypedJsonapiBytes.invoke();
    }

    @Benchmark
    public Object guestJacksonJsonapiBytes() {
        return guestJacksonJsonapiBytes.invoke();
    }

    @Benchmark
    public Object guestJackson3JsonapiBytes() {
        return guestJackson3JsonapiBytes.invoke();
    }

    @Benchmark
    public Object guestJackson3JsonapiTruffleBytes() {
        return guestJackson3JsonapiTruffleBytes.invoke();
    }

    @Benchmark
    public Object guestTypedJsonapiTruffleBytes() {
        return guestTypedJsonapiTruffleBytes.invoke();
    }

    @Benchmark
    public Object guestTypedPlaceholderBytes() {
        return guestTypedPlaceholderBytes.invoke();
    }

    @Benchmark
    public Object guestJackson3PlaceholderBytes() {
        return guestJackson3PlaceholderBytes.invoke();
    }

    @Benchmark
    public Object guestTypedGithub() {
        return guestTypedGithub.invoke();
    }

    @Benchmark
    public Object guestTypedGithubBytes() {
        return guestTypedGithubBytes.invoke();
    }

    @Benchmark
    public Object guestJacksonGithubBytes() {
        return guestJacksonGithubBytes.invoke();
    }

    @Benchmark
    public Object guestJackson3GithubBytes() {
        return guestJackson3GithubBytes.invoke();
    }

    @Benchmark
    public Object guestJackson3GithubTruffleBytes() {
        return guestJackson3GithubTruffleBytes.invoke();
    }

    @Benchmark
    public Object guestTypedGithubTruffleBytes() {
        return guestTypedGithubTruffleBytes.invoke();
    }

    @Benchmark
    public Object guestTypedGithubBuffer() {
        return guestTypedGithubBuffer.invoke();
    }

    @Benchmark
    public Object guestTypedGithubSumBytes() {
        return guestTypedGithubSumBytes.invoke();
    }

    @Benchmark
    public Object guestJacksonGithubSumBytes() {
        return guestJacksonGithubSumBytes.invoke();
    }

    @Benchmark
    public Object guestJackson3GithubSumBytes() {
        return guestJackson3GithubSumBytes.invoke();
    }

    @Benchmark
    public Object guestTypedTwitterFirst() {
        return guestTypedTwitterFirst.invoke();
    }

    @Benchmark
    public Object guestTypedTwitterFirstBytes() {
        return guestTypedTwitterFirstBytes.invoke();
    }

    @Benchmark
    public Object guestJacksonTwitterFirstBytes() {
        return guestJacksonTwitterFirstBytes.invoke();
    }

    @Benchmark
    public Object guestJackson3TwitterFirstBytes() {
        return guestJackson3TwitterFirstBytes.invoke();
    }

    @Benchmark
    public Object guestJackson3TwitterFirstTruffleBytes() {
        return guestJackson3TwitterFirstTruffleBytes.invoke();
    }

    @Benchmark
    public Object guestJackson3TwitterFirstTruffleInput() {
        return guestJackson3TwitterFirstTruffleInput.invoke();
    }

    @Benchmark
    public Object guestTypedTwitterFirstTruffleBytes() {
        return guestTypedTwitterFirstTruffleBytes.invoke();
    }

    @Benchmark
    public Object guestTypedTwitterFirstTruffleInput() {
        return guestTypedTwitterFirstTruffleInput.invoke();
    }

    @Benchmark
    public Object guestTypedTwitterFirstBuffer() {
        return guestTypedTwitterFirstBuffer.invoke();
    }

    @Benchmark
    public Object guestTypedEscaped() {
        return guestTypedEscaped.invoke();
    }

    @Benchmark
    public Object guestUnschemedGithubBytes() {
        return guestUnschemedGithubBytes.invoke();
    }

    @Benchmark
    public Object guestTypedGithubEarlyBytes() {
        return guestTypedGithubEarlyBytes.invoke();
    }

    @Benchmark
    public Object guestJacksonGithubEarlyBytes() {
        return guestJacksonGithubEarlyBytes.invoke();
    }

    @Benchmark
    public Object guestJackson3GithubEarlyBytes() {
        return guestJackson3GithubEarlyBytes.invoke();
    }

    @Benchmark
    public Object guestTypedGithubLateBytes() {
        return guestTypedGithubLateBytes.invoke();
    }

    @Benchmark
    public Object guestJacksonGithubLateBytes() {
        return guestJacksonGithubLateBytes.invoke();
    }

    @Benchmark
    public Object guestJackson3GithubLateBytes() {
        return guestJackson3GithubLateBytes.invoke();
    }

    @Benchmark
    public Object guestJsonSchemaGithubBytes() {
        return guestJsonSchemaGithubBytes.invoke();
    }

    @Benchmark
    public Object guestJsonSchemaGithubEarlyBytes() {
        return guestJsonSchemaGithubEarlyBytes.invoke();
    }

    @Benchmark
    public Object guestJsonSchemaGithubLateBytes() {
        return guestJsonSchemaGithubLateBytes.invoke();
    }

    @Benchmark
    public JsonNode jacksonParseGithubBytes() throws Exception {
        return jacksonMapper.readTree(githubBytes);
    }

    @Benchmark
    public String jacksonGithubEarlyBytes() throws Exception {
        return jacksonMapper.readTree(githubBytes).get("full_name").textValue();
    }

    @Benchmark
    public int jacksonGithubLateBytes() throws Exception {
        return jacksonMapper.readTree(githubBytes).get("network_count").intValue();
    }

    /**
     * Jackson pull parser ({@code JsonParser}): walk root fields, skip unselected values,
     * stop at the first matching key. This is the streaming counterpart to Cloffle first-wins.
     */
    @Benchmark
    public String jacksonStreamingGithubEarlyBytes() throws Exception {
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(githubBytes)) {
            return jacksonStreamingRootString(parser, "full_name");
        }
    }

    @Benchmark
    public int jacksonStreamingGithubLateBytes() throws Exception {
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(githubBytes)) {
            return jacksonStreamingRootInt(parser, "network_count");
        }
    }

    @Benchmark
    public ObjectNode jacksonStreamingGithubFirstWinsBytes() throws Exception {
        String fullName = null;
        String login = null;
        int stars = 0;
        int issues = 0;
        int remaining = 4;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(githubBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("expected object");
            }
            while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "full_name" -> {
                        fullName = parser.getValueAsString();
                        remaining--;
                    }
                    case "stargazers_count" -> {
                        stars = parser.getIntValue();
                        remaining--;
                    }
                    case "open_issues_count" -> {
                        issues = parser.getIntValue();
                        remaining--;
                    }
                    case "owner" -> {
                        login = jacksonStreamingObjectString(parser, "login");
                        if (login != null) {
                            remaining--;
                        }
                    }
                    default -> parser.skipChildren();
                }
            }
        }
        ObjectNode owner = jacksonMapper.createObjectNode();
        owner.put("login", login);
        ObjectNode out = jacksonMapper.createObjectNode();
        out.put("full_name", fullName);
        out.put("stargazers_count", stars);
        out.put("open_issues_count", issues);
        out.set("owner", owner);
        return out;
    }

    /**
     * Streaming counterpart to {@code guestTypedJsonapiBytes}: data.id, data.attributes.title,
     * meta.request-id, first-key-wins.
     */
    @Benchmark
    public ObjectNode jacksonStreamingJsonapiBytes() throws Exception {
        String id = null;
        String title = null;
        String requestId = null;
        int remaining = 2;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(jsonapiBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("expected object");
            }
            while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "data" -> {
                        String[] pair = jacksonStreamingJsonapiData(parser);
                        id = pair[0];
                        title = pair[1];
                        remaining--;
                    }
                    case "meta" -> {
                        requestId = jacksonStreamingObjectString(parser, "request-id");
                        remaining--;
                    }
                    default -> parser.skipChildren();
                }
            }
        }
        ObjectNode attributes = jacksonMapper.createObjectNode();
        attributes.put("title", title);
        ObjectNode data = jacksonMapper.createObjectNode();
        data.put("id", id);
        data.set("attributes", attributes);
        ObjectNode meta = jacksonMapper.createObjectNode();
        meta.put("request-id", requestId);
        ObjectNode out = jacksonMapper.createObjectNode();
        out.set("data", data);
        out.set("meta", meta);
        return out;
    }

    /**
     * Streaming counterpart to {@code guestTypedPlaceholderBytes}: id, userId, title, first-key-wins.
     */
    @Benchmark
    public ObjectNode jacksonStreamingPlaceholderBytes() throws Exception {
        int id = 0;
        int userId = 0;
        String title = null;
        int remaining = 3;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(placeholderBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("expected object");
            }
            while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "id" -> {
                        id = parser.getIntValue();
                        remaining--;
                    }
                    case "userId" -> {
                        userId = parser.getIntValue();
                        remaining--;
                    }
                    case "title" -> {
                        title = parser.getValueAsString();
                        remaining--;
                    }
                    default -> parser.skipChildren();
                }
            }
        }
        ObjectNode out = jacksonMapper.createObjectNode();
        out.put("id", id);
        out.put("userId", userId);
        out.put("title", title);
        return out;
    }

    // ── Fractional literals: the Eisel-Lemire fast path against Jackson ──────────────

    @Benchmark
    public Object guestTypedDoublesBytes() {
        return guestTypedDoublesBytes.invoke();
    }

    @Benchmark
    public Object guestJackson3DoublesBytes() {
        return guestJackson3DoublesBytes.invoke();
    }

    @Benchmark
    public double jacksonStreamingDoublesBytes() throws Exception {
        double sum = 0;
        int remaining = 8;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(doublesBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("expected object");
            }
            while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "lat", "lon", "altitude", "speed", "heading", "accuracy", "temp_c",
                         "humidity" -> {
                        sum += parser.getDoubleValue();
                        remaining--;
                    }
                    default -> parser.skipChildren();
                }
            }
        }
        return sum;
    }

    // ── simdjson-java: SIMD structural index plus schema-directed walk ────────────────

    @Benchmark
    public Object guestSimdjsonJsonapiBytes() {
        return guestSimdjsonJsonapiBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonJsonapiTruffleBytes() {
        return guestSimdjsonJsonapiTruffleBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonGithubBytes() {
        return guestSimdjsonGithubBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonGithubTruffleBytes() {
        return guestSimdjsonGithubTruffleBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonGithubSumBytes() {
        return guestSimdjsonGithubSumBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonGithubEarlyBytes() {
        return guestSimdjsonGithubEarlyBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonGithubLateBytes() {
        return guestSimdjsonGithubLateBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonTwitterFirstBytes() {
        return guestSimdjsonTwitterFirstBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonTwitterFirstTruffleBytes() {
        return guestSimdjsonTwitterFirstTruffleBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonTwitterFirstTruffleInput() {
        return guestSimdjsonTwitterFirstTruffleInput.invoke();
    }

    @Benchmark
    public Object guestSimdjsonDoublesBytes() {
        return guestSimdjsonDoublesBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonSelectGithubBytes() {
        return guestSimdjsonSelectGithubBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonSelectJsonapiBytes() {
        return guestSimdjsonSelectJsonapiBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonPlaceholderBytes() {
        return guestSimdjsonPlaceholderBytes.invoke();
    }

    @Benchmark
    public Object guestSelectPlaceholderBytes() {
        return guestSelectPlaceholderBytes.invoke();
    }

    @Benchmark
    public Object guestJackson3SelectPlaceholderBytes() {
        return guestJackson3SelectPlaceholderBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonSelectPlaceholderBytes() {
        return guestSimdjsonSelectPlaceholderBytes.invoke();
    }

    /**
     * Record components are matched to JSON field names, and anything unlisted is skipped.
     * simdjson still indexes the whole document first, so there is no early exit to compare
     * against; {@code gc.alloc.rate.norm} is also not comparable, because the parser pads its
     * input and holds a large reusable index buffer allocated once in setup.
     */
    public record SimdGithubOwner(String login) {
    }

    public record SimdGithubRepo(String full_name, int stargazers_count, int open_issues_count,
                                 SimdGithubOwner owner) {
    }

    public record SimdTwitterUser(String screen_name) {
    }

    public record SimdTwitterStatus(long id, String text, SimdTwitterUser user) {
    }

    /** simdjson materializes every status; the Cloffle counterpart stops after the first. */
    public record SimdTwitter(java.util.List<SimdTwitterStatus> statuses) {
    }

    public record SimdPlaceholderPost(int userId, int id, String title, String body) {
    }

    @Benchmark
    public SimdPlaceholderPost simdjsonSchemaPlaceholderBytes() {
        return simdjsonParser.parse(placeholderBytes, placeholderBytes.length, SimdPlaceholderPost.class);
    }

    @Benchmark
    public SimdGithubRepo simdjsonSchemaGithubBytes() {
        return simdjsonParser.parse(githubBytes, githubBytes.length, SimdGithubRepo.class);
    }

    @Benchmark
    public SimdTwitterStatus simdjsonSchemaTwitterFirstBytes() {
        SimdTwitter twitter =
                simdjsonParser.parse(twitterBytes, twitterBytes.length, SimdTwitter.class);
        return twitter.statuses().get(0);
    }

    // ── JSON Pointer: cloffle.json/select against Jackson's two pointer paths ──────────

    @Benchmark
    public Object guestSelectGithubBytes() {
        return guestSelectGithubBytes.invoke();
    }

    @Benchmark
    public Object guestJackson3SelectGithubBytes() {
        return guestJackson3SelectGithubBytes.invoke();
    }

    @Benchmark
    public Object guestSelectJsonapiBytes() {
        return guestSelectJsonapiBytes.invoke();
    }

    @Benchmark
    public Object guestJackson3SelectJsonapiBytes() {
        return guestJackson3SelectJsonapiBytes.invoke();
    }

    /** {@code JsonNode.at} needs the whole tree first, then walks it once per pointer. */
    @Benchmark
    public void jacksonPointerGithubBytes(Blackhole bh) throws Exception {
        JsonNode tree = jacksonMapper.readTree(githubBytes);
        for (JsonPointer pointer : githubPointers) {
            bh.consume(tree.at(pointer));
        }
    }

    @Benchmark
    public void jacksonPointerJsonapiBytes(Blackhole bh) throws Exception {
        JsonNode tree = jacksonMapper.readTree(jsonapiBytes);
        for (JsonPointer pointer : jsonapiPointers) {
            bh.consume(tree.at(pointer));
        }
    }

    @Benchmark
    public void jacksonPointerPlaceholderBytes(Blackhole bh) throws Exception {
        JsonNode tree = jacksonMapper.readTree(placeholderBytes);
        for (JsonPointer pointer : placeholderPointers) {
            bh.consume(tree.at(pointer));
        }
    }

    /**
     * The streaming alternative: no tree, but {@link JsonPointerBasedFilter} resolves one pointer,
     * so N pointers cost N passes over the document. That asymmetry against one early-exiting scan
     * is the point of the comparison.
     */
    @Benchmark
    public void jacksonFilteringPointerGithubBytes(Blackhole bh) throws Exception {
        for (JsonPointer pointer : githubPointers) {
            bh.consume(jacksonFilteredPointer(githubBytes, pointer));
        }
    }

    @Benchmark
    public void jacksonFilteringPointerJsonapiBytes(Blackhole bh) throws Exception {
        for (JsonPointer pointer : jsonapiPointers) {
            bh.consume(jacksonFilteredPointer(jsonapiBytes, pointer));
        }
    }

    @Benchmark
    public void jacksonFilteringPointerPlaceholderBytes(Blackhole bh) throws Exception {
        for (JsonPointer pointer : placeholderPointers) {
            bh.consume(jacksonFilteredPointer(placeholderBytes, pointer));
        }
    }

    private String jacksonFilteredPointer(byte[] json, JsonPointer pointer) throws Exception {
        try (com.fasterxml.jackson.core.JsonParser raw = jacksonFactory.createParser(json);
             com.fasterxml.jackson.core.JsonParser parser = new FilteringParserDelegate(
                     raw, new JsonPointerBasedFilter(pointer), TokenFilter.Inclusion.ONLY_INCLUDE_ALL,
                     false)) {
            JsonToken token = parser.nextToken();
            return token == null ? null : parser.getValueAsString();
        }
    }

    private static JsonPointer[] compilePointers(String... pointers) {
        JsonPointer[] compiled = new JsonPointer[pointers.length];
        for (int i = 0; i < pointers.length; i++) {
            compiled[i] = JsonPointer.compile(pointers[i]);
        }
        return compiled;
    }

    /**
     * Streaming counterpart to {@code guestTypedTwitterFirstBytes}: first status id, text,
     * and user.screen_name, then stop (first-key-wins).
     */
    @Benchmark
    public ObjectNode jacksonStreamingTwitterFirstBytes() throws Exception {
        long id = 0L;
        String text = null;
        String screenName = null;
        try (com.fasterxml.jackson.core.JsonParser parser = jacksonFactory.createParser(twitterBytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("expected object");
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                if ("statuses".equals(field)) {
                    long[] idSlot = new long[1];
                    String[] strings = jacksonStreamingFirstStatus(parser, idSlot);
                    id = idSlot[0];
                    text = strings[0];
                    screenName = strings[1];
                    break;
                }
                parser.skipChildren();
            }
        }
        ObjectNode user = jacksonMapper.createObjectNode();
        user.put("screen_name", screenName);
        ObjectNode status = jacksonMapper.createObjectNode();
        status.put("id", id);
        status.put("text", text);
        status.set("user", user);
        ObjectNode out = jacksonMapper.createObjectNode();
        out.putArray("statuses").add(status);
        return out;
    }

    @Benchmark
    public Object charredParseGithubString() {
        return charredParse.invoke(githubJson);
    }

    @Benchmark
    public Object charredLookupGithubEarly() {
        IPersistentMap doc = (IPersistentMap) charredParse.invoke(githubJson);
        return doc.valAt(kwFullName);
    }

    @Benchmark
    public Object charredLookupGithubLate() {
        IPersistentMap doc = (IPersistentMap) charredParse.invoke(githubJson);
        return doc.valAt(kwNetworkCount);
    }

    @Benchmark
    public Object charredLookupGithubFour() {
        IPersistentMap doc = (IPersistentMap) charredParse.invoke(githubJson);
        IPersistentMap owner = (IPersistentMap) doc.valAt(kwOwner);
        return RT.vector(
                doc.valAt(kwFullName),
                doc.valAt(kwStargazers),
                doc.valAt(kwOpenIssues),
                owner.valAt(kwLogin));
    }

    @Benchmark
    public Object lazyJsonGithubEarly() {
        lazyJsonConsume.invoke(
                lazyJsonEarlyAutomaton, lazyJsonParse.invoke(githubJson), RT.vector(jsonRoot));
        return lazyJsonSlot[0];
    }

    @Benchmark
    public Object lazyJsonGithubLate() {
        lazyJsonConsume.invoke(
                lazyJsonLateAutomaton, lazyJsonParse.invoke(githubJson), RT.vector(jsonRoot));
        return lazyJsonSlot[0];
    }

    @Benchmark
    public Object lazyJsonGithubFour() {
        lazyJsonConsume.invoke(
                lazyJsonFourAutomaton, lazyJsonParse.invoke(githubJson), RT.vector(jsonRoot));
        return RT.vector(lazyJsonSlot[0], lazyJsonSlot[1], lazyJsonSlot[2], lazyJsonSlot[3]);
    }

    private AFn lazyJsonCapture(int slot) {
        return new AFn() {
            @Override
            public Object invoke(Object path, Object value) {
                lazyJsonSlot[slot] = value;
                return null;
            }
        };
    }

    private static String jacksonStreamingRootString(
            com.fasterxml.jackson.core.JsonParser parser, String key) throws Exception {
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            throw new IllegalStateException("expected object");
        }
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            if (key.equals(field)) {
                return parser.getValueAsString();
            }
            parser.skipChildren();
        }
        return null;
    }

    private static int jacksonStreamingRootInt(
            com.fasterxml.jackson.core.JsonParser parser, String key) throws Exception {
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            throw new IllegalStateException("expected object");
        }
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            if (key.equals(field)) {
                return parser.getIntValue();
            }
            parser.skipChildren();
        }
        return 0;
    }

    private static String jacksonStreamingObjectString(
            com.fasterxml.jackson.core.JsonParser parser, String key) throws Exception {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return null;
        }
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            if (key.equals(field)) {
                String value = parser.getValueAsString();
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    parser.nextToken();
                    parser.skipChildren();
                }
                return value;
            }
            parser.skipChildren();
        }
        return null;
    }

    private static String[] jacksonStreamingJsonapiData(
            com.fasterxml.jackson.core.JsonParser parser) throws Exception {
        String id = null;
        String title = null;
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return new String[] {null, null};
        }
        int remaining = 2;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "id" -> {
                    id = parser.getValueAsString();
                    remaining--;
                }
                case "attributes" -> {
                    title = jacksonStreamingObjectString(parser, "title");
                    remaining--;
                }
                default -> parser.skipChildren();
            }
            if (remaining == 0) {
                // Leave the parser on this object's END_OBJECT so the caller resumes at a sibling.
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    parser.nextToken();
                    parser.skipChildren();
                }
                break;
            }
        }
        return new String[] {id, title};
    }

    /**
     * Reads {@code statuses[0]} then skips the rest of the array. {@code idSlot[0]} receives the
     * tweet id; the returned array is {@code [text, screen_name]}.
     */
    private static String[] jacksonStreamingFirstStatus(
            com.fasterxml.jackson.core.JsonParser parser, long[] idSlot) throws Exception {
        String text = null;
        String screenName = null;
        if (parser.currentToken() != JsonToken.START_ARRAY) {
            parser.skipChildren();
            return new String[] {null, null};
        }
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return new String[] {null, null};
        }
        int remaining = 3;
        while (remaining > 0 && parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "id" -> {
                    idSlot[0] = parser.getLongValue();
                    remaining--;
                }
                case "text" -> {
                    text = parser.getValueAsString();
                    remaining--;
                }
                case "user" -> {
                    screenName = jacksonStreamingObjectString(parser, "screen_name");
                    remaining--;
                }
                default -> parser.skipChildren();
            }
        }
        if (parser.currentToken() == JsonToken.FIELD_NAME) {
            parser.skipChildren();
        }
        parser.skipChildren();
        return new String[] {text, screenName};
    }

    /** Consumes parse-only so the JIT cannot dead-eliminate construction. */
    @Benchmark
    public void cloffleParseEntity16Blackhole(Blackhole bh) {
        bh.consume(JsonParser.parseBytes(entity16Bytes));
    }
}
