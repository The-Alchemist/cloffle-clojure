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
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.HashMap;
import java.util.Map;
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

    private static final String[][] GUEST_BINDINGS = {
            {"guestSelectGithubBytes", "guest-select-github-bytes"},
            {"guestSelectJsonapiBytes", "guest-select-jsonapi-bytes"},
            {"guestJackson3SelectGithubBytes", "guest-jackson3-select-github-bytes"},
            {"guestJackson3SelectJsonapiBytes", "guest-jackson3-select-jsonapi-bytes"},
            {"guestSelectPlaceholderBytes", "guest-select-placeholder-bytes"},
            {"guestJackson3SelectPlaceholderBytes", "guest-jackson3-select-placeholder-bytes"},
            {"guestSimdjsonSelectGithubBytes", "guest-simdjson-select-github-bytes"},
            {"guestSimdjsonSelectJsonapiBytes", "guest-simdjson-select-jsonapi-bytes"},
            {"guestSimdjsonSelectPlaceholderBytes", "guest-simdjson-select-placeholder-bytes"},
    };

    @Param({
            "guestSelectGithubBytes",
            "guestSelectJsonapiBytes",
            "guestJackson3SelectGithubBytes",
            "guestJackson3SelectJsonapiBytes",
            "guestSelectPlaceholderBytes",
            "guestJackson3SelectPlaceholderBytes",
            "guestSimdjsonSelectGithubBytes",
            "guestSimdjsonSelectJsonapiBytes",
            "guestSimdjsonSelectPlaceholderBytes",
    })
    public String guest;

    private final JsonParserCloffleGuestSupport guests = new JsonParserCloffleGuestSupport();
    private final JsonParserJacksonSupport jackson = new JsonParserJacksonSupport();
    private final Map<String, IFn> guestFns = new HashMap<>();
    private JsonPointer[] githubPointers;
    private JsonPointer[] jsonapiPointers;
    private JsonPointer[] placeholderPointers;

    @Setup(Level.Trial)
    public void setupSelect() {
        loadFixtures();
        guests.open();
        for (String[] binding : GUEST_BINDINGS) {
            guestFns.put(binding[0], guests.guest(binding[1]));
        }
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
    public Object guestSelect() {
        IFn fn = guestFns.get(guest);
        if (fn == null) {
            throw new IllegalStateException("No guest IFn for param guest=" + guest);
        }
        return fn.invoke();
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
