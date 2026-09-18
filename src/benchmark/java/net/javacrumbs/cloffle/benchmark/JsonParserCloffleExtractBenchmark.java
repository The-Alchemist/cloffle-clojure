package net.javacrumbs.cloffle.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
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
