package net.javacrumbs.cloffle.benchmark;

import clojure.lang.Keyword;
import clojure.lang.RT;
import net.javacrumbs.cloffle.bytecode.JsonTypedProjectPlan;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Host (no Cloffle context) typed {@code json/project} on {@code byte[]}.
 * The benchmark returns the projected shape map, matching {@code guestTyped*Bytes}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Threads(1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 2, time = 1)
public class JsonParserHostTypedProjectBenchmark extends JsonParserBenchmarkBase {

    private static final Keyword MAP = Keyword.intern("map");
    private static final Keyword INT = Keyword.intern("int");
    private static final Keyword LONG = Keyword.intern("long");
    private static final Keyword DOUBLE = Keyword.intern("double");
    private static final Keyword BOOLEAN = Keyword.intern("boolean");
    private static final Keyword STRING = Keyword.intern("string");
    private static final Keyword INDEXES = Keyword.intern("cloffle", "indexes");

    @Param({
            "placeholder",
            "jsonapi",
            "github",
            "twitterFirst",
            "popularApis",
    })
    public String fixture;

    private JsonTypedProjectPlan plan;
    private byte[] source;

    @Setup(Level.Trial)
    public void setupHostTypedProject() throws Exception {
        loadFixtures();
        switch (fixture) {
            case "placeholder" -> {
                plan = JsonTypedProjectPlan.compile(null, placeholderSchema());
                source = placeholderBytes;
            }
            case "jsonapi" -> {
                plan = JsonTypedProjectPlan.compile(null, jsonapiSchema());
                source = jsonapiBytes;
            }
            case "github" -> {
                plan = JsonTypedProjectPlan.compile(null, githubSchema());
                source = githubBytes;
            }
            case "twitterFirst" -> {
                plan = JsonTypedProjectPlan.compile(null, twitterFirstSchema());
                source = twitterBytes;
            }
            case "popularApis" -> {
                plan = JsonTypedProjectPlan.compile(null, popularApisSchema());
                source = popularApisBytes;
            }
            default -> throw new IllegalStateException("Unknown fixture=" + fixture);
        }
        assertHostParity();
    }

    @Benchmark
    public Object hostTypedProject() {
        return plan.project(source);
    }

    private void assertHostParity() throws Exception {
        JsonParserJacksonStreamingBenchmark stream = new JsonParserJacksonStreamingBenchmark();
        copyFixturesTo(stream);
        stream.setupStreaming();
        Object projected = plan.project(source);
        switch (fixture) {
            case "placeholder" ->
                    assertEquiv("placeholder", projected, stream.jacksonStreamingPlaceholderShapeMap());
            case "jsonapi" ->
                    assertEquiv("jsonapi", projected, stream.jacksonStreamingJsonapiShapeMap());
            case "github" ->
                    assertEquiv("github", projected, stream.jacksonStreamingGithubShapeMap());
            case "twitterFirst" ->
                    assertEquiv("twitter", projected, stream.jacksonStreamingTwitterFirstShapeMap());
            case "popularApis" ->
                    assertEquiv("popular-apis", projected, stream.jacksonStreamingPopularApisShapeMap());
            default -> throw new IllegalStateException("Unknown fixture=" + fixture);
        }
    }

    private static Object placeholderSchema() {
        return map(entry(Keyword.intern("id"), INT),
                entry(Keyword.intern("userId"), INT),
                entry(Keyword.intern("title"), STRING));
    }

    private static Object jsonapiSchema() {
        return map(
                entry(Keyword.intern("data"), map(
                        entry(Keyword.intern("id"), STRING),
                        entry(Keyword.intern("attributes"), map(
                                entry(Keyword.intern("title"), STRING))))),
                entry(Keyword.intern("meta"), map(
                        entry(Keyword.intern("request-id"), STRING))));
    }

    private static Object githubSchema() {
        return map(
                entry(Keyword.intern("full_name"), STRING),
                entry(Keyword.intern("stargazers_count"), INT),
                entry(Keyword.intern("open_issues_count"), INT),
                entry(Keyword.intern("owner"), map(
                        entry(Keyword.intern("login"), STRING))));
    }

    private static Object twitterFirstSchema() {
        return map(entry(Keyword.intern("statuses"), RT.vector(
                INDEXES,
                RT.vector(0, map(
                        entry(Keyword.intern("id"), LONG),
                        entry(Keyword.intern("text"), STRING),
                        entry(Keyword.intern("user"), map(
                                entry(Keyword.intern("screen_name"), STRING))))))));
    }

    private static Object popularApisSchema() {
        Object item = map(
                entry(Keyword.intern("sku"), STRING),
                entry(Keyword.intern("quantity"), INT),
                entry(Keyword.intern("unit_amount"), DOUBLE));
        return map(
                entry(Keyword.intern("id"), STRING),
                entry(Keyword.intern("livemode"), BOOLEAN),
                entry(Keyword.intern("created"), LONG),
                entry(Keyword.intern("data"), map(
                        entry(Keyword.intern("type"), STRING),
                        entry(Keyword.intern("id"), STRING),
                        entry(Keyword.intern("attributes"), map(
                                entry(Keyword.intern("title"), STRING),
                                entry(Keyword.intern("amount_cents"), INT),
                                entry(Keyword.intern("fee_rate"), DOUBLE))))),
                entry(Keyword.intern("repository"), map(
                        entry(Keyword.intern("full_name"), STRING),
                        entry(Keyword.intern("stargazers_count"), INT),
                        entry(Keyword.intern("private"), BOOLEAN))),
                entry(Keyword.intern("geo"), map(
                        entry(Keyword.intern("lat"), DOUBLE),
                        entry(Keyword.intern("lon"), DOUBLE))),
                entry(Keyword.intern("line_items"), RT.vector(
                        INDEXES,
                        RT.vector(0, item),
                        RT.vector(1, item))),
                entry(Keyword.intern("meta"), map(
                        entry(Keyword.intern("request_id"), STRING),
                        entry(Keyword.intern("version"), STRING))));
    }

    private static Object map(Object... entries) {
        Object[] xs = new Object[entries.length + 1];
        xs[0] = MAP;
        System.arraycopy(entries, 0, xs, 1, entries.length);
        return RT.vector(xs);
    }

    private static Object entry(Keyword key, Object schema) {
        return RT.vector(key, schema);
    }
}
