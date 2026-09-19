package net.javacrumbs.cloffle.benchmark;

import net.javacrumbs.cloffle.bytecode.JsonTypedProjectPlan;
import net.javacrumbs.cloffle.json.JsonTypedSchemas;
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
                plan = JsonTypedProjectPlan.compile(null, JsonTypedSchemas.placeholder());
                source = placeholderBytes;
            }
            case "jsonapi" -> {
                plan = JsonTypedProjectPlan.compile(null, JsonTypedSchemas.jsonapi());
                source = jsonapiBytes;
            }
            case "github" -> {
                plan = JsonTypedProjectPlan.compile(null, JsonTypedSchemas.github());
                source = githubBytes;
            }
            case "twitterFirst" -> {
                plan = JsonTypedProjectPlan.compile(null, JsonTypedSchemas.twitterFirst());
                source = twitterBytes;
            }
            case "popularApis" -> {
                plan = JsonTypedProjectPlan.compile(null, JsonTypedSchemas.popularApis());
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
}
