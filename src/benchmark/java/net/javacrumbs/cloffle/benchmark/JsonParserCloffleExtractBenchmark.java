package net.javacrumbs.cloffle.benchmark;

import clojure.lang.IFn;
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

import java.util.concurrent.TimeUnit;

/**
 * Cloffle typed extract guests and Jackson-style counterparts.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Threads(1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 2, time = 1)
public class JsonParserCloffleExtractBenchmark extends JsonParserBenchmarkBase {

    private final JsonParserCloffleGuestSupport guests = new JsonParserCloffleGuestSupport();

    private IFn guestTypedJsonapi;
    private IFn guestTypedJsonapiBytes;
    private IFn guestJacksonJsonapiBytes;
    private IFn guestJackson3JsonapiBytes;
    private IFn guestJackson3JsonapiTruffleBytes;
    private IFn guestTypedJsonapiTruffleBytes;
    private IFn guestTypedPlaceholderBytes;
    private IFn guestJackson3PlaceholderBytes;
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
    private IFn guestJacksonGithubEarlyBytes;
    private IFn guestJackson3GithubEarlyBytes;
    private IFn guestTypedGithubLateBytes;
    private IFn guestJacksonGithubLateBytes;
    private IFn guestJackson3GithubLateBytes;
    private IFn guestJsonSchemaGithubBytes;
    private IFn guestJsonSchemaGithubEarlyBytes;
    private IFn guestJsonSchemaGithubLateBytes;
    private IFn guestTypedDoublesBytes;
    private IFn guestJackson3DoublesBytes;
    private IFn guestTypedJsonapiConsume;
    private IFn guestTypedPlaceholderConsume;
    private IFn guestTypedGithubConsume;
    private IFn guestTypedTwitterFirstConsume;
    private IFn guestTypedPopularApisBytes;
    private IFn guestTypedPopularApisConsume;
    private IFn guestJackson3PopularApisBytes;

    @Setup(Level.Trial)
    public void setupExtract() throws Exception {
        loadFixtures();
        guests.open();
        guestTypedJsonapi = guests.guest("guest-typed-jsonapi");
        guestTypedJsonapiBytes = guests.guest("guest-typed-jsonapi-bytes");
        guestJacksonJsonapiBytes = guests.guest("guest-jackson-jsonapi-bytes");
        guestJackson3JsonapiBytes = guests.guest("guest-jackson3-jsonapi-bytes");
        guestJackson3JsonapiTruffleBytes = guests.guest("guest-jackson3-jsonapi-truffle-bytes");
        guestTypedJsonapiTruffleBytes = guests.guest("guest-typed-jsonapi-truffle-bytes");
        guestTypedPlaceholderBytes = guests.guest("guest-typed-placeholder-bytes");
        guestJackson3PlaceholderBytes = guests.guest("guest-jackson3-placeholder-bytes");
        guestTypedGithub = guests.guest("guest-typed-github");
        guestTypedGithubBytes = guests.guest("guest-typed-github-bytes");
        guestJacksonGithubBytes = guests.guest("guest-jackson-github-bytes");
        guestJackson3GithubBytes = guests.guest("guest-jackson3-github-bytes");
        guestJackson3GithubTruffleBytes = guests.guest("guest-jackson3-github-truffle-bytes");
        guestTypedGithubTruffleBytes = guests.guest("guest-typed-github-truffle-bytes");
        guestTypedGithubBuffer = guests.guest("guest-typed-github-buffer");
        guestTypedGithubSumBytes = guests.guest("guest-typed-github-sum-bytes");
        guestJacksonGithubSumBytes = guests.guest("guest-jackson-github-sum-bytes");
        guestJackson3GithubSumBytes = guests.guest("guest-jackson3-github-sum-bytes");
        guestTypedTwitterFirst = guests.guest("guest-typed-twitter-first");
        guestTypedTwitterFirstBytes = guests.guest("guest-typed-twitter-first-bytes");
        guestJacksonTwitterFirstBytes = guests.guest("guest-jackson-twitter-first-bytes");
        guestJackson3TwitterFirstBytes = guests.guest("guest-jackson3-twitter-first-bytes");
        guestJackson3TwitterFirstTruffleBytes =
                guests.guest("guest-jackson3-twitter-first-truffle-bytes");
        guestJackson3TwitterFirstTruffleInput =
                guests.guest("guest-jackson3-twitter-first-truffle-input");
        guestTypedTwitterFirstTruffleBytes =
                guests.guest("guest-typed-twitter-first-truffle-bytes");
        guestTypedTwitterFirstTruffleInput =
                guests.guest("guest-typed-twitter-first-truffle-input");
        guestTypedTwitterFirstBuffer = guests.guest("guest-typed-twitter-first-buffer");
        guestTypedEscaped = guests.guest("guest-typed-escaped");
        guestUnschemedGithubBytes = guests.guest("guest-unschemed-github-bytes");
        guestTypedGithubEarlyBytes = guests.guest("guest-typed-github-early-bytes");
        guestJacksonGithubEarlyBytes = guests.guest("guest-jackson-github-early-bytes");
        guestJackson3GithubEarlyBytes = guests.guest("guest-jackson3-github-early-bytes");
        guestTypedGithubLateBytes = guests.guest("guest-typed-github-late-bytes");
        guestJacksonGithubLateBytes = guests.guest("guest-jackson-github-late-bytes");
        guestJackson3GithubLateBytes = guests.guest("guest-jackson3-github-late-bytes");
        guestJsonSchemaGithubBytes = guests.guest("guest-json-schema-github-bytes");
        guestJsonSchemaGithubEarlyBytes = guests.guest("guest-json-schema-github-early-bytes");
        guestJsonSchemaGithubLateBytes = guests.guest("guest-json-schema-github-late-bytes");
        guestTypedDoublesBytes = guests.guest("guest-typed-doubles-bytes");
        guestJackson3DoublesBytes = guests.guest("guest-jackson3-doubles-bytes");
        guestTypedJsonapiConsume = guests.guest("guest-typed-jsonapi-consume");
        guestTypedPlaceholderConsume = guests.guest("guest-typed-placeholder-consume");
        guestTypedGithubConsume = guests.guest("guest-typed-github-consume");
        guestTypedTwitterFirstConsume = guests.guest("guest-typed-twitter-first-consume");
        guestTypedPopularApisBytes = guests.guest("guest-typed-popular-apis-bytes");
        guestTypedPopularApisConsume = guests.guest("guest-typed-popular-apis-consume");
        guestJackson3PopularApisBytes = guests.guest("guest-jackson3-popular-apis-bytes");
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
        assertEquiv("placeholder", guestTypedPlaceholderBytes.invoke(),
                stream.jacksonStreamingPlaceholderShapeMap());
        assertEquiv("jsonapi", guestTypedJsonapiBytes.invoke(),
                stream.jacksonStreamingJsonapiShapeMap());
        assertEquiv("github", guestTypedGithubBytes.invoke(),
                stream.jacksonStreamingGithubShapeMap());
        assertEquiv("twitter", guestTypedTwitterFirstBytes.invoke(),
                stream.jacksonStreamingTwitterFirstShapeMap());
        assertEquiv("popular-apis", guestTypedPopularApisBytes.invoke(),
                stream.jacksonStreamingPopularApisShapeMap());
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
    public Object guestTypedDoublesBytes() {
        return guestTypedDoublesBytes.invoke();
    }

    @Benchmark
    public Object guestJackson3DoublesBytes() {
        return guestJackson3DoublesBytes.invoke();
    }

    @Benchmark
    public long guestTypedJsonapiConsume() {
        return ((Number) guestTypedJsonapiConsume.invoke()).longValue();
    }

    @Benchmark
    public long guestTypedPlaceholderConsume() {
        return ((Number) guestTypedPlaceholderConsume.invoke()).longValue();
    }

    @Benchmark
    public long guestTypedGithubConsume() {
        return ((Number) guestTypedGithubConsume.invoke()).longValue();
    }

    @Benchmark
    public long guestTypedTwitterFirstConsume() {
        return ((Number) guestTypedTwitterFirstConsume.invoke()).longValue();
    }

    @Benchmark
    public Object guestTypedPopularApisBytes() {
        return guestTypedPopularApisBytes.invoke();
    }

    @Benchmark
    public long guestTypedPopularApisConsume() {
        return ((Number) guestTypedPopularApisConsume.invoke()).longValue();
    }

    @Benchmark
    public Object guestJackson3PopularApisBytes() {
        return guestJackson3PopularApisBytes.invoke();
    }
}
