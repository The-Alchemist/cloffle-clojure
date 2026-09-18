package net.javacrumbs.cloffle.benchmark;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.filter.FilteringParserDelegate;
import com.fasterxml.jackson.core.filter.JsonPointerBasedFilter;
import com.fasterxml.jackson.core.filter.TokenFilter;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
/**
 * Cloffle json/select guests and Jackson JSON Pointer baselines.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Threads(1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 2, time = 1)
public class JsonParserCloffleSelectBenchmark extends JsonParserBenchmarkBase {
    @Benchmark
    public Object guestSimdjsonSelectGithubBytes() {
        return guestSimdjsonSelectGithubBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonSelectJsonapiBytes() {
        return guestSimdjsonSelectJsonapiBytes.invoke();
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


}
