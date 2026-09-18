package net.javacrumbs.cloffle.benchmark;

import clojure.lang.Keyword;
import clojure.lang.RT;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared fixtures and keywords for JSON parser JMH benchmarks.
 * Suite-specific Cloffle guests and Jackson state live on the leaf classes / helpers.
 */
public abstract class JsonParserBenchmarkBase {

    static final ThreadLocal<Map<String, Object>> CAPTURED =
            ThreadLocal.withInitial(HashMap::new);

    protected String jsonapi;
    protected String entity16;
    protected String rows;
    protected String escaped;
    protected byte[] jsonapiBytes;
    protected byte[] entity16Bytes;
    protected byte[] rowsBytes;
    protected String githubJson;
    protected byte[] githubBytes;
    protected String twitterJson;
    protected byte[] twitterBytes;
    protected String placeholderJson;
    protected byte[] placeholderBytes;
    protected byte[] popularApisBytes;
    protected byte[] doublesBytes;

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

    public static Object captureGuestValue(String name, Object value) {
        CAPTURED.get().put(name, value);
        return value;
    }

    public static String fixture(String path) {
        return ClojureClasspathResources.read(path);
    }

    @Setup(Level.Trial)
    public void setupFixtures() {
        loadFixtures();
    }

    /** Load fixture payloads. Leaf suites call this at the start of their own {@code @Setup}. */
    protected final void loadFixtures() {
        RT.init();
        if (jsonapi != null) {
            return;
        }
        jsonapi = fixture("json-parser-benchmark/data/jsonapi.json");
        entity16 = fixture("json-parser-benchmark/data/entity16.json");
        rows = fixture("json-parser-benchmark/data/rows.json");
        escaped = fixture("json-parser-benchmark/data/escaped.json");
        jsonapiBytes = jsonapi.getBytes(StandardCharsets.UTF_8);
        entity16Bytes = entity16.getBytes(StandardCharsets.UTF_8);
        rowsBytes = rows.getBytes(StandardCharsets.UTF_8);
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
    }

    /** Copy fixture payloads onto another state object (e.g. for fairness checks). */
    void copyFixturesTo(JsonParserBenchmarkBase other) {
        other.jsonapi = jsonapi;
        other.entity16 = entity16;
        other.rows = rows;
        other.escaped = escaped;
        other.jsonapiBytes = jsonapiBytes;
        other.entity16Bytes = entity16Bytes;
        other.rowsBytes = rowsBytes;
        other.githubJson = githubJson;
        other.githubBytes = githubBytes;
        other.twitterJson = twitterJson;
        other.twitterBytes = twitterBytes;
        other.placeholderJson = placeholderJson;
        other.placeholderBytes = placeholderBytes;
        other.popularApisBytes = popularApisBytes;
        other.doublesBytes = doublesBytes;
    }

    protected static void assertEquiv(String label, Object cloffle, Object jackson) {
        if (!clojure.lang.Util.equiv(cloffle, jackson)) {
            throw new IllegalStateException(
                    "Fair JSON shape mismatch for " + label + ": cloffle=" + cloffle
                            + " jackson=" + jackson);
        }
    }
}
