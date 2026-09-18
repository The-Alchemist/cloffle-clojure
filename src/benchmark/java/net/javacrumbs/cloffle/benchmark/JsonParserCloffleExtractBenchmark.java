package net.javacrumbs.cloffle.benchmark;

import clojure.lang.IFn;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Cloffle typed extract guests and Jackson-style counterparts.
 * <p>
 * One {@link Benchmark} method; each row is a {@link Param} value (legacy per-method names
 * like {@code guestTypedGithubBytes} are kept for stable filters and reports).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Threads(1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 2, time = 1)
public class JsonParserCloffleExtractBenchmark extends JsonParserBenchmarkBase {

    /**
     * {@link Param} id (camelCase) → Clojure guest var in {@code setup.clj} (kebab-case).
     */
    private static final String[][] GUEST_BINDINGS = {
            {"guestTypedJsonapi", "guest-typed-jsonapi"},
            {"guestTypedJsonapiBytes", "guest-typed-jsonapi-bytes"},
            {"guestJacksonJsonapiBytes", "guest-jackson-jsonapi-bytes"},
            {"guestJackson3JsonapiBytes", "guest-jackson3-jsonapi-bytes"},
            {"guestJackson3JsonapiTruffleBytes", "guest-jackson3-jsonapi-truffle-bytes"},
            {"guestTypedJsonapiTruffleBytes", "guest-typed-jsonapi-truffle-bytes"},
            {"guestTypedPlaceholderBytes", "guest-typed-placeholder-bytes"},
            {"guestJackson3PlaceholderBytes", "guest-jackson3-placeholder-bytes"},
            {"guestTypedGithub", "guest-typed-github"},
            {"guestTypedGithubBytes", "guest-typed-github-bytes"},
            {"guestJacksonGithubBytes", "guest-jackson-github-bytes"},
            {"guestJackson3GithubBytes", "guest-jackson3-github-bytes"},
            {"guestJackson3GithubTruffleBytes", "guest-jackson3-github-truffle-bytes"},
            {"guestTypedGithubTruffleBytes", "guest-typed-github-truffle-bytes"},
            {"guestTypedGithubBuffer", "guest-typed-github-buffer"},
            {"guestTypedGithubSumBytes", "guest-typed-github-sum-bytes"},
            {"guestJacksonGithubSumBytes", "guest-jackson-github-sum-bytes"},
            {"guestJackson3GithubSumBytes", "guest-jackson3-github-sum-bytes"},
            {"guestTypedTwitterFirst", "guest-typed-twitter-first"},
            {"guestTypedTwitterFirstBytes", "guest-typed-twitter-first-bytes"},
            {"guestJacksonTwitterFirstBytes", "guest-jackson-twitter-first-bytes"},
            {"guestJackson3TwitterFirstBytes", "guest-jackson3-twitter-first-bytes"},
            {"guestJackson3TwitterFirstTruffleBytes", "guest-jackson3-twitter-first-truffle-bytes"},
            {"guestJackson3TwitterFirstTruffleInput", "guest-jackson3-twitter-first-truffle-input"},
            {"guestTypedTwitterFirstTruffleBytes", "guest-typed-twitter-first-truffle-bytes"},
            {"guestTypedTwitterFirstTruffleInput", "guest-typed-twitter-first-truffle-input"},
            {"guestTypedTwitterFirstBuffer", "guest-typed-twitter-first-buffer"},
            {"guestTypedEscaped", "guest-typed-escaped"},
            {"guestUnschemedGithubBytes", "guest-unschemed-github-bytes"},
            {"guestTypedGithubEarlyBytes", "guest-typed-github-early-bytes"},
            {"guestJacksonGithubEarlyBytes", "guest-jackson-github-early-bytes"},
            {"guestJackson3GithubEarlyBytes", "guest-jackson3-github-early-bytes"},
            {"guestTypedGithubLateBytes", "guest-typed-github-late-bytes"},
            {"guestJacksonGithubLateBytes", "guest-jackson-github-late-bytes"},
            {"guestJackson3GithubLateBytes", "guest-jackson3-github-late-bytes"},
            {"guestJsonSchemaGithubBytes", "guest-json-schema-github-bytes"},
            {"guestJsonSchemaGithubEarlyBytes", "guest-json-schema-github-early-bytes"},
            {"guestJsonSchemaGithubLateBytes", "guest-json-schema-github-late-bytes"},
            {"guestTypedDoublesBytes", "guest-typed-doubles-bytes"},
            {"guestJackson3DoublesBytes", "guest-jackson3-doubles-bytes"},
            {"guestTypedJsonapiConsume", "guest-typed-jsonapi-consume"},
            {"guestTypedPlaceholderConsume", "guest-typed-placeholder-consume"},
            {"guestTypedGithubConsume", "guest-typed-github-consume"},
            {"guestTypedTwitterFirstConsume", "guest-typed-twitter-first-consume"},
            {"guestTypedPopularApisBytes", "guest-typed-popular-apis-bytes"},
            {"guestTypedPopularApisConsume", "guest-typed-popular-apis-consume"},
            {"guestJackson3PopularApisBytes", "guest-jackson3-popular-apis-bytes"},
    };

    @Param({
            "guestTypedJsonapi",
            "guestTypedJsonapiBytes",
            "guestJacksonJsonapiBytes",
            "guestJackson3JsonapiBytes",
            "guestJackson3JsonapiTruffleBytes",
            "guestTypedJsonapiTruffleBytes",
            "guestTypedPlaceholderBytes",
            "guestJackson3PlaceholderBytes",
            "guestTypedGithub",
            "guestTypedGithubBytes",
            "guestJacksonGithubBytes",
            "guestJackson3GithubBytes",
            "guestJackson3GithubTruffleBytes",
            "guestTypedGithubTruffleBytes",
            "guestTypedGithubBuffer",
            "guestTypedGithubSumBytes",
            "guestJacksonGithubSumBytes",
            "guestJackson3GithubSumBytes",
            "guestTypedTwitterFirst",
            "guestTypedTwitterFirstBytes",
            "guestJacksonTwitterFirstBytes",
            "guestJackson3TwitterFirstBytes",
            "guestJackson3TwitterFirstTruffleBytes",
            "guestJackson3TwitterFirstTruffleInput",
            "guestTypedTwitterFirstTruffleBytes",
            "guestTypedTwitterFirstTruffleInput",
            "guestTypedTwitterFirstBuffer",
            "guestTypedEscaped",
            "guestUnschemedGithubBytes",
            "guestTypedGithubEarlyBytes",
            "guestJacksonGithubEarlyBytes",
            "guestJackson3GithubEarlyBytes",
            "guestTypedGithubLateBytes",
            "guestJacksonGithubLateBytes",
            "guestJackson3GithubLateBytes",
            "guestJsonSchemaGithubBytes",
            "guestJsonSchemaGithubEarlyBytes",
            "guestJsonSchemaGithubLateBytes",
            "guestTypedDoublesBytes",
            "guestJackson3DoublesBytes",
            "guestTypedJsonapiConsume",
            "guestTypedPlaceholderConsume",
            "guestTypedGithubConsume",
            "guestTypedTwitterFirstConsume",
            "guestTypedPopularApisBytes",
            "guestTypedPopularApisConsume",
            "guestJackson3PopularApisBytes",
    })
    public String guest;

    private final JsonParserCloffleGuestSupport guests = new JsonParserCloffleGuestSupport();
    private final Map<String, IFn> guestFns = new HashMap<>();

    @Setup(Level.Trial)
    public void setupExtract() throws Exception {
        loadFixtures();
        guests.open();
        for (String[] binding : GUEST_BINDINGS) {
            guestFns.put(binding[0], guests.guest(binding[1]));
        }
        assertTypedParity();
    }

    @TearDown(Level.Trial)
    public void teardownExtract() {
        guests.close();
    }

    private void assertTypedParity() throws Exception {
        JsonParserJacksonStreamingBenchmark stream = new JsonParserJacksonStreamingBenchmark();
        copyFixturesTo(stream);
        stream.setupStreaming();
        assertEquiv("placeholder", guestFns.get("guestTypedPlaceholderBytes").invoke(),
                stream.jacksonStreamingPlaceholderShapeMap());
        assertEquiv("jsonapi", guestFns.get("guestTypedJsonapiBytes").invoke(),
                stream.jacksonStreamingJsonapiShapeMap());
        assertEquiv("github", guestFns.get("guestTypedGithubBytes").invoke(),
                stream.jacksonStreamingGithubShapeMap());
        assertEquiv("twitter", guestFns.get("guestTypedTwitterFirstBytes").invoke(),
                stream.jacksonStreamingTwitterFirstShapeMap());
        assertEquiv("popular-apis", guestFns.get("guestTypedPopularApisBytes").invoke(),
                stream.jacksonStreamingPopularApisShapeMap());
    }

    @Benchmark
    public Object guestExtract() {
        IFn fn = guestFns.get(guest);
        if (fn == null) {
            throw new IllegalStateException("No guest IFn for param guest=" + guest);
        }
        return fn.invoke();
    }
}
