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
import clojure.lang.MapShape;
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
 * Shared fixtures, guest setup, and state for JSON parser JMH benchmarks.
 */
public abstract class JsonParserBenchmarkBase {


    protected static final ThreadLocal<Map<String, Object>> CAPTURED =
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

    protected Context context;
    protected byte[] jsonapiBytes;
    protected byte[] entity16Bytes;
    protected byte[] rowsBytes;
    protected IFn cheshireParse;
    protected IFn jsonistaRead;
    protected Object jsonistaMapper;
    protected ObjectMapper jacksonMapper;
    protected JsonFactory jacksonFactory;
    protected IFn guestJsonapi;
    protected IFn guestEntity16;
    protected IFn guestRows;
    protected IFn guestEscapeJsonapi;
    protected IFn guestEscapeEntity16;
    protected IFn guestProjectJsonapi;
    protected IFn guestProjectEntity16;
    protected IFn guestProjectJsonapiBytes;
    protected IFn guestProjectEntity16Bytes;
    protected IFn guestProjectGithub;
    protected IFn guestProjectGithubBytes;
    protected IFn guestProjectTwitterFirstBytes;
    protected IFn guestTypedJsonapi;
    protected IFn guestTypedJsonapiBytes;
    protected IFn guestJacksonJsonapiBytes;
    protected IFn guestJackson3JsonapiBytes;
    protected IFn guestJackson3JsonapiTruffleBytes;
    protected IFn guestTypedJsonapiTruffleBytes;
    protected IFn guestTypedGithub;
    protected IFn guestTypedGithubBytes;
    protected IFn guestJacksonGithubBytes;
    protected IFn guestJackson3GithubBytes;
    protected IFn guestJackson3GithubTruffleBytes;
    protected IFn guestTypedGithubTruffleBytes;
    protected IFn guestTypedGithubBuffer;
    protected IFn guestTypedGithubSumBytes;
    protected IFn guestJacksonGithubSumBytes;
    protected IFn guestJackson3GithubSumBytes;
    protected IFn guestTypedTwitterFirst;
    protected IFn guestTypedTwitterFirstBytes;
    protected IFn guestJacksonTwitterFirstBytes;
    protected IFn guestJackson3TwitterFirstBytes;
    protected IFn guestJackson3TwitterFirstTruffleBytes;
    protected IFn guestJackson3TwitterFirstTruffleInput;
    protected IFn guestTypedTwitterFirstTruffleBytes;
    protected IFn guestTypedTwitterFirstTruffleInput;
    protected IFn guestTypedTwitterFirstBuffer;
    protected IFn guestTypedEscaped;
    protected IFn guestUnschemedGithubBytes;
    protected IFn guestTypedGithubEarlyBytes;
    protected IFn guestTypedGithubLateBytes;
    protected IFn guestJacksonGithubEarlyBytes;
    protected IFn guestJacksonGithubLateBytes;
    protected IFn guestJackson3GithubEarlyBytes;
    protected IFn guestJackson3GithubLateBytes;
    protected byte[] doublesBytes;
    protected IFn guestTypedDoublesBytes;
    protected IFn guestJackson3DoublesBytes;
    protected SimdJsonParser simdjsonParser;
    protected IFn guestSelectGithubBytes;
    protected IFn guestSelectJsonapiBytes;
    protected IFn guestJackson3SelectGithubBytes;
    protected IFn guestJackson3SelectJsonapiBytes;
    protected JsonPointer[] githubPointers;
    protected JsonPointer[] jsonapiPointers;
    protected IFn guestJsonSchemaGithubBytes;
    protected IFn guestJsonSchemaGithubEarlyBytes;
    protected IFn guestJsonSchemaGithubLateBytes;
    protected IFn guestSimdjsonJsonapiBytes;
    protected IFn guestSimdjsonJsonapiTruffleBytes;
    protected IFn guestSimdjsonGithubBytes;
    protected IFn guestSimdjsonGithubTruffleBytes;
    protected IFn guestSimdjsonGithubSumBytes;
    protected IFn guestSimdjsonGithubEarlyBytes;
    protected IFn guestSimdjsonGithubLateBytes;
    protected IFn guestSimdjsonTwitterFirstBytes;
    protected IFn guestSimdjsonTwitterFirstTruffleBytes;
    protected IFn guestSimdjsonTwitterFirstTruffleInput;
    protected IFn guestSimdjsonDoublesBytes;
    protected IFn guestSimdjsonSelectGithubBytes;
    protected IFn guestSimdjsonSelectJsonapiBytes;
    protected IFn guestPlaceholder;
    protected IFn guestProjectPlaceholderBytes;
    protected IFn guestTypedPlaceholderBytes;
    protected IFn guestJackson3PlaceholderBytes;
    protected IFn guestSimdjsonPlaceholderBytes;
    protected IFn guestSelectPlaceholderBytes;
    protected IFn guestJackson3SelectPlaceholderBytes;
    protected IFn guestSimdjsonSelectPlaceholderBytes;
    protected JsonPointer[] placeholderPointers;
    protected IFn malliGithub;
    protected IFn malliTwitter;
    protected IFn charredParse;
    protected IFn lazyJsonParse;
    protected IFn lazyJsonConsume;
    protected Object lazyJsonEarlyAutomaton;
    protected Object lazyJsonLateAutomaton;
    protected Object lazyJsonFourAutomaton;
    protected final Object[] lazyJsonSlot = new Object[4];
    protected final Keyword jsonRoot = Keyword.intern("$");
    protected String githubJson;
    protected byte[] githubBytes;
    protected String twitterJson;
    protected byte[] twitterBytes;
    protected String placeholderJson;
    protected byte[] placeholderBytes;
    protected byte[] popularApisBytes;
    protected IFn guestTypedPopularApisBytes;
    protected IFn guestTypedPopularApisConsume;
    protected IFn guestJackson3PopularApisBytes;
    protected IFn guestTypedPlaceholderConsume;
    protected IFn guestTypedJsonapiConsume;
    protected IFn guestTypedGithubConsume;
    protected IFn guestTypedTwitterFirstConsume;
    protected MapShape placeholderShape;
    protected MapShape jsonapiAttrShape;
    protected MapShape jsonapiDataShape;
    protected MapShape jsonapiMetaShape;
    protected MapShape jsonapiRootShape;
    protected MapShape githubOwnerShape;
    protected MapShape githubRootShape;
    protected MapShape twitterUserShape;
    protected MapShape twitterStatusShape;
    protected MapShape twitterRootShape;
    protected MapShape popularAttrShape;
    protected MapShape popularDataShape;
    protected MapShape popularRepoShape;
    protected MapShape popularGeoShape;
    protected MapShape popularItemShape;
    protected MapShape popularMetaShape;
    protected MapShape popularRootShape;
    protected final PopularLocals popularLocals = new PopularLocals();
    protected final Keyword kwData = Keyword.intern("data");
    protected final Keyword kwAttributes = Keyword.intern("attributes");
    protected final Keyword kwTitle = Keyword.intern("title");
    protected final Keyword kwId = Keyword.intern("id");
    protected final Keyword kwUserId = Keyword.intern("userId");
    protected final Keyword kwEmail = Keyword.intern("email");
    protected final Keyword kwName = Keyword.intern("name");
    protected final Keyword kwFullName = Keyword.intern("full_name");
    protected final Keyword kwNetworkCount = Keyword.intern("network_count");
    protected final Keyword kwOwner = Keyword.intern("owner");
    protected final Keyword kwLogin = Keyword.intern("login");
    protected final Keyword kwStargazers = Keyword.intern("stargazers_count");
    protected final Keyword kwOpenIssues = Keyword.intern("open_issues_count");
    protected final Keyword kwType = Keyword.intern("type");
    protected final Keyword kwLivemode = Keyword.intern("livemode");
    protected final Keyword kwCreated = Keyword.intern("created");
    protected final Keyword kwAmountCents = Keyword.intern("amount_cents");
    protected final Keyword kwFeeRate = Keyword.intern("fee_rate");
    protected final Keyword kwRepository = Keyword.intern("repository");
    protected final Keyword kwPrivate = Keyword.intern("private");
    protected final Keyword kwGeo = Keyword.intern("geo");
    protected final Keyword kwLat = Keyword.intern("lat");
    protected final Keyword kwLon = Keyword.intern("lon");
    protected final Keyword kwLineItems = Keyword.intern("line_items");
    protected final Keyword kwSku = Keyword.intern("sku");
    protected final Keyword kwQuantity = Keyword.intern("quantity");
    protected final Keyword kwUnitAmount = Keyword.intern("unit_amount");
    protected final Keyword kwMeta = Keyword.intern("meta");
    protected final Keyword kwRequestId = Keyword.intern("request_id");
    protected final Keyword kwRequestHyphenId = Keyword.intern("request-id");
    protected final Keyword kwVersion = Keyword.intern("version");
    protected final Keyword kwStatuses = Keyword.intern("statuses");
    protected final Keyword kwText = Keyword.intern("text");
    protected final Keyword kwUser = Keyword.intern("user");
    protected final Keyword kwScreenName = Keyword.intern("screen_name");

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
        popularApisBytes = fixture("json-parser-benchmark/data/popular-apis-composite.json")
                .getBytes(StandardCharsets.UTF_8);
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
        guestTypedPlaceholderConsume = guestFn("guest-typed-placeholder-consume");
        guestTypedJsonapiConsume = guestFn("guest-typed-jsonapi-consume");
        guestTypedGithubConsume = guestFn("guest-typed-github-consume");
        guestTypedTwitterFirstConsume = guestFn("guest-typed-twitter-first-consume");
        guestTypedPopularApisBytes = guestFn("guest-typed-popular-apis-bytes");
        guestTypedPopularApisConsume = guestFn("guest-typed-popular-apis-consume");
        guestJackson3PopularApisBytes = guestFn("guest-jackson3-popular-apis-bytes");
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
        placeholderShape = MapShape.of(kwId, kwUserId, kwTitle);
        jsonapiAttrShape = MapShape.of(kwTitle);
        jsonapiDataShape = MapShape.of(kwId, kwAttributes);
        jsonapiMetaShape = MapShape.of(kwRequestHyphenId);
        jsonapiRootShape = MapShape.of(kwData, kwMeta);
        githubOwnerShape = MapShape.of(kwLogin);
        githubRootShape = MapShape.of(kwFullName, kwStargazers, kwOpenIssues, kwOwner);
        twitterUserShape = MapShape.of(kwScreenName);
        twitterStatusShape = MapShape.of(kwId, kwText, kwUser);
        twitterRootShape = MapShape.of(kwStatuses);
        popularAttrShape = MapShape.of(kwTitle, kwAmountCents, kwFeeRate);
        popularDataShape = MapShape.of(kwType, kwId, kwAttributes);
        popularRepoShape = MapShape.of(kwFullName, kwStargazers, kwPrivate);
        popularGeoShape = MapShape.of(kwLat, kwLon);
        popularItemShape = MapShape.of(kwSku, kwQuantity, kwUnitAmount);
        popularMetaShape = MapShape.of(kwRequestId, kwVersion);
        popularRootShape = MapShape.of(
                kwId, kwLivemode, kwCreated, kwData, kwRepository, kwGeo, kwLineItems, kwMeta);
        JsonParserJacksonStreamingBenchmark.assertTypedParity(this);
        CAPTURED.remove();
    }

    protected IFn guestFn(String name) {
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

    protected AFn lazyJsonCapture(int slot) {
        return new AFn() {
            @Override
            public Object invoke(Object path, Object value) {
                lazyJsonSlot[slot] = value;
                return null;
            }
        };
    }

    protected static JsonPointer[] compilePointers(String... pointers) {
        JsonPointer[] compiled = new JsonPointer[pointers.length];
        for (int i = 0; i < pointers.length; i++) {
            compiled[i] = JsonPointer.compile(pointers[i]);
        }
        return compiled;
    }

    protected String jacksonFilteredPointer(byte[] json, JsonPointer pointer) throws Exception {
        try (com.fasterxml.jackson.core.JsonParser raw = jacksonFactory.createParser(json);
             com.fasterxml.jackson.core.JsonParser parser = new FilteringParserDelegate(
                     raw, new JsonPointerBasedFilter(pointer), TokenFilter.Inclusion.ONLY_INCLUDE_ALL,
                     false)) {
            JsonToken token = parser.nextToken();
            return token == null ? null : parser.getValueAsString();
        }
    }

    protected static void assertEquiv(String label, Object cloffle, Object jackson) {
        if (!clojure.lang.Util.equiv(cloffle, jackson)) {
            throw new IllegalStateException(
                    "Fair JSON shape mismatch for " + label + ": cloffle=" + cloffle
                            + " jackson=" + jackson);
        }
    }

    protected static final class PopularLocals {
        String id;
        boolean livemode;
        long created;
        String dataType;
        String dataId;
        String title;
        int amountCents;
        double feeRate;
        String fullName;
        int stargazers;
        boolean repoPrivate;
        double lat;
        double lon;
        String sku0;
        String sku1;
        int qty0;
        int qty1;
        double unit0;
        double unit1;
        String requestId;
        String version;
    }
}
