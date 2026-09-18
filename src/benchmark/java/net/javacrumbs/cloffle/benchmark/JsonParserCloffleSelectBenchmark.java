package net.javacrumbs.cloffle.benchmark;

import clojure.lang.IFn;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
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

    private final JsonParserCloffleGuestSupport guests = new JsonParserCloffleGuestSupport();
    private final JsonParserJacksonSupport jackson = new JsonParserJacksonSupport();

    private IFn guestSelectGithubBytes;
    private IFn guestSelectJsonapiBytes;
    private IFn guestJackson3SelectGithubBytes;
    private IFn guestJackson3SelectJsonapiBytes;
    private IFn guestSelectPlaceholderBytes;
    private IFn guestJackson3SelectPlaceholderBytes;
    private IFn guestSimdjsonSelectGithubBytes;
    private IFn guestSimdjsonSelectJsonapiBytes;
    private IFn guestSimdjsonSelectPlaceholderBytes;
    private JsonPointer[] githubPointers;
    private JsonPointer[] jsonapiPointers;
    private JsonPointer[] placeholderPointers;

    @Setup(Level.Trial)
    public void setupSelect() {
        loadFixtures();
        guests.open();
        guestSelectGithubBytes = guests.guest("guest-select-github-bytes");
        guestSelectJsonapiBytes = guests.guest("guest-select-jsonapi-bytes");
        guestJackson3SelectGithubBytes = guests.guest("guest-jackson3-select-github-bytes");
        guestJackson3SelectJsonapiBytes = guests.guest("guest-jackson3-select-jsonapi-bytes");
        guestSelectPlaceholderBytes = guests.guest("guest-select-placeholder-bytes");
        guestJackson3SelectPlaceholderBytes = guests.guest("guest-jackson3-select-placeholder-bytes");
        guestSimdjsonSelectGithubBytes = guests.guest("guest-simdjson-select-github-bytes");
        guestSimdjsonSelectJsonapiBytes = guests.guest("guest-simdjson-select-jsonapi-bytes");
        guestSimdjsonSelectPlaceholderBytes = guests.guest("guest-simdjson-select-placeholder-bytes");
        githubPointers = JsonParserJacksonSupport.compilePointers(
                "/full_name", "/stargazers_count", "/open_issues_count", "/owner/login");
        jsonapiPointers = JsonParserJacksonSupport.compilePointers(
                "/data/id", "/data/attributes/title", "/meta/request-id");
        placeholderPointers = JsonParserJacksonSupport.compilePointers("/id", "/userId", "/title");
    }

    @TearDown(Level.Trial)
    public void teardownSelect() {
        guests.close();
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
        JsonNode tree = jackson.mapper.readTree(githubBytes);
        for (JsonPointer pointer : githubPointers) {
            bh.consume(tree.at(pointer));
        }
    }

    @Benchmark
    public void jacksonPointerJsonapiBytes(Blackhole bh) throws Exception {
        JsonNode tree = jackson.mapper.readTree(jsonapiBytes);
        for (JsonPointer pointer : jsonapiPointers) {
            bh.consume(tree.at(pointer));
        }
    }

    @Benchmark
    public void jacksonPointerPlaceholderBytes(Blackhole bh) throws Exception {
        JsonNode tree = jackson.mapper.readTree(placeholderBytes);
        for (JsonPointer pointer : placeholderPointers) {
            bh.consume(tree.at(pointer));
        }
    }

    /**
     * The streaming alternative: no tree, but {@link com.fasterxml.jackson.core.filter.JsonPointerBasedFilter}
     * resolves one pointer, so N pointers cost N passes over the document. That asymmetry against one
     * early-exiting scan is the point of the comparison.
     */
    @Benchmark
    public void jacksonFilteringPointerGithubBytes(Blackhole bh) throws Exception {
        for (JsonPointer pointer : githubPointers) {
            bh.consume(jackson.filteredPointer(githubBytes, pointer));
        }
    }

    @Benchmark
    public void jacksonFilteringPointerJsonapiBytes(Blackhole bh) throws Exception {
        for (JsonPointer pointer : jsonapiPointers) {
            bh.consume(jackson.filteredPointer(jsonapiBytes, pointer));
        }
    }

    @Benchmark
    public void jacksonFilteringPointerPlaceholderBytes(Blackhole bh) throws Exception {
        for (JsonPointer pointer : placeholderPointers) {
            bh.consume(jackson.filteredPointer(placeholderBytes, pointer));
        }
    }
}
