package net.javacrumbs.cloffle.benchmark;

import clojure.lang.IFn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Cloffle json/project guests and Jackson readTree projection baselines.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Threads(1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 2, time = 1)
public class JsonParserCloffleProjectBenchmark extends JsonParserBenchmarkBase {

    private static final String[][] GUEST_BINDINGS = {
            {"guestProjectJsonapi", "guest-project-jsonapi"},
            {"guestProjectEntity16", "guest-project-entity16"},
            {"guestProjectJsonapiBytes", "guest-project-jsonapi-bytes"},
            {"guestProjectEntity16Bytes", "guest-project-entity16-bytes"},
            {"guestProjectGithub", "guest-project-github"},
            {"guestProjectGithubBytes", "guest-project-github-bytes"},
            {"guestProjectTwitterFirstBytes", "guest-project-twitter-first-bytes"},
            {"guestProjectPlaceholderBytes", "guest-project-placeholder-bytes"},
    };

    @Param({
            "guestProjectJsonapi",
            "guestProjectEntity16",
            "guestProjectJsonapiBytes",
            "guestProjectEntity16Bytes",
            "guestProjectGithub",
            "guestProjectGithubBytes",
            "guestProjectTwitterFirstBytes",
            "guestProjectPlaceholderBytes",
    })
    public String guest;

    private final JsonParserCloffleGuestSupport guests = new JsonParserCloffleGuestSupport();
    private final JsonParserJacksonSupport jackson = new JsonParserJacksonSupport();
    private final Map<String, IFn> guestFns = new HashMap<>();

    @Setup(Level.Trial)
    public void setupProject() throws Exception {
        loadFixtures();
        guests.open();
        for (String[] binding : GUEST_BINDINGS) {
            guestFns.put(binding[0], guests.guest(binding[1]));
        }
        assertProjectParity();
    }

    private void assertProjectParity() throws Exception {
        assertEquiv("jsonapi-bytes", guestFns.get("guestProjectJsonapiBytes").invoke(),
                JsonParserJacksonSupport.toKeywordized(
                        jacksonProjectJsonapi(jackson.mapper.readTree(jsonapiBytes))));
        assertEquiv("entity16-bytes", guestFns.get("guestProjectEntity16Bytes").invoke(),
                JsonParserJacksonSupport.toKeywordized(
                        jacksonProjectEntity16(jackson.mapper.readTree(entity16Bytes))));
        assertEquiv("placeholder-bytes", guestFns.get("guestProjectPlaceholderBytes").invoke(),
                JsonParserJacksonSupport.toKeywordized(
                        jacksonProjectPlaceholder(jackson.mapper.readTree(placeholderBytes))));
        assertEquiv("github-bytes", guestFns.get("guestProjectGithubBytes").invoke(),
                JsonParserJacksonSupport.toKeywordized(
                        jacksonProjectGithub(jackson.mapper.readTree(githubBytes))));
        assertEquiv("twitter-first-bytes", guestFns.get("guestProjectTwitterFirstBytes").invoke(),
                JsonParserJacksonSupport.toKeywordized(
                        jacksonProjectTwitterFirst(jackson.mapper.readTree(twitterBytes))));
    }

    @TearDown(Level.Trial)
    public void teardownProject() {
        guests.close();
    }

    @Benchmark
    public Object guestProject() {
        IFn fn = guestFns.get(guest);
        if (fn == null) {
            throw new IllegalStateException("No guest IFn for param guest=" + guest);
        }
        return fn.invoke();
    }

    @Benchmark
    public ObjectNode jacksonProjectJsonapiString() throws Exception {
        return jacksonProjectJsonapi(jackson.mapper.readTree(jsonapi));
    }

    @Benchmark
    public ObjectNode jacksonProjectJsonapiBytes() throws Exception {
        return jacksonProjectJsonapi(jackson.mapper.readTree(jsonapiBytes));
    }

    @Benchmark
    public ObjectNode jacksonProjectEntity16String() throws Exception {
        return jacksonProjectEntity16(jackson.mapper.readTree(entity16));
    }

    @Benchmark
    public ObjectNode jacksonProjectEntity16Bytes() throws Exception {
        return jacksonProjectEntity16(jackson.mapper.readTree(entity16Bytes));
    }

    @Benchmark
    public ObjectNode jacksonProjectPlaceholderBytes() throws Exception {
        return jacksonProjectPlaceholder(jackson.mapper.readTree(placeholderBytes));
    }

    @Benchmark
    public ObjectNode jacksonProjectGithubString() throws Exception {
        return jacksonProjectGithub(jackson.mapper.readTree(githubJson));
    }

    @Benchmark
    public ObjectNode jacksonProjectGithubBytes() throws Exception {
        return jacksonProjectGithub(jackson.mapper.readTree(githubBytes));
    }

    @Benchmark
    public ObjectNode jacksonProjectEscapedString() throws Exception {
        JsonNode root = jackson.mapper.readTree(escaped);
        ObjectNode out = jackson.mapper.createObjectNode();
        out.set("message", root.get("message"));
        out.set("id", root.get("id"));
        return out;
    }

    @Benchmark
    public ObjectNode jacksonProjectTwitterFirstString() throws Exception {
        return jacksonProjectTwitterFirst(jackson.mapper.readTree(twitterJson));
    }

    @Benchmark
    public ObjectNode jacksonProjectTwitterFirstBytes() throws Exception {
        return jacksonProjectTwitterFirst(jackson.mapper.readTree(twitterBytes));
    }

    private ObjectNode jacksonProjectJsonapi(JsonNode root) {
        ObjectNode out = jackson.mapper.createObjectNode();
        out.set("title", root.get("data").get("attributes").get("title"));
        out.set("id", root.get("data").get("id"));
        out.set("rid", root.get("meta").get("request-id"));
        return out;
    }

    private ObjectNode jacksonProjectEntity16(JsonNode root) {
        ObjectNode out = jackson.mapper.createObjectNode();
        out.set("id", root.get("id"));
        out.set("email", root.get("email"));
        out.set("status", root.get("status"));
        return out;
    }

    private ObjectNode jacksonProjectPlaceholder(JsonNode root) {
        ObjectNode out = jackson.mapper.createObjectNode();
        out.set("id", root.get("id"));
        out.set("userId", root.get("userId"));
        out.set("title", root.get("title"));
        return out;
    }

    private ObjectNode jacksonProjectGithub(JsonNode root) {
        ObjectNode owner = jackson.mapper.createObjectNode();
        owner.set("login", root.get("owner").get("login"));
        ObjectNode out = jackson.mapper.createObjectNode();
        out.set("full_name", root.get("full_name"));
        out.set("stargazers_count", root.get("stargazers_count"));
        out.set("open_issues_count", root.get("open_issues_count"));
        out.set("owner", owner);
        return out;
    }

    private ObjectNode jacksonProjectTwitterFirst(JsonNode root) {
        JsonNode first = root.get("statuses").get(0);
        ObjectNode user = jackson.mapper.createObjectNode();
        user.set("screen_name", first.get("user").get("screen_name"));
        ObjectNode status = jackson.mapper.createObjectNode();
        status.set("id", first.get("id"));
        status.set("text", first.get("text"));
        status.set("user", user);
        ObjectNode out = jackson.mapper.createObjectNode();
        out.putArray("statuses").add(status);
        return out;
    }
}
