package net.javacrumbs.cloffle.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    @Benchmark
    public ObjectNode jacksonProjectJsonapiString() throws Exception {
        return jacksonProjectJsonapi(jacksonMapper.readTree(JSONAPI));
    }

    @Benchmark
    public ObjectNode jacksonProjectJsonapiBytes() throws Exception {
        return jacksonProjectJsonapi(jacksonMapper.readTree(jsonapiBytes));
    }

    @Benchmark
    public ObjectNode jacksonProjectEntity16String() throws Exception {
        return jacksonProjectEntity16(jacksonMapper.readTree(ENTITY16));
    }

    @Benchmark
    public ObjectNode jacksonProjectEntity16Bytes() throws Exception {
        return jacksonProjectEntity16(jacksonMapper.readTree(entity16Bytes));
    }

    @Benchmark
    public ObjectNode jacksonProjectPlaceholderBytes() throws Exception {
        return jacksonProjectPlaceholder(jacksonMapper.readTree(placeholderBytes));
    }

    @Benchmark
    public ObjectNode jacksonProjectGithubString() throws Exception {
        return jacksonProjectGithub(jacksonMapper.readTree(githubJson));
    }

    @Benchmark
    public ObjectNode jacksonProjectGithubBytes() throws Exception {
        return jacksonProjectGithub(jacksonMapper.readTree(githubBytes));
    }

    @Benchmark
    public ObjectNode jacksonProjectEscapedString() throws Exception {
        JsonNode root = jacksonMapper.readTree(ESCAPED);
        ObjectNode out = jacksonMapper.createObjectNode();
        out.set("message", root.get("message"));
        out.set("id", root.get("id"));
        return out;
    }

    @Benchmark
    public ObjectNode jacksonProjectTwitterFirstString() throws Exception {
        return jacksonProjectTwitterFirst(jacksonMapper.readTree(twitterJson));
    }

    @Benchmark
    public ObjectNode jacksonProjectTwitterFirstBytes() throws Exception {
        return jacksonProjectTwitterFirst(jacksonMapper.readTree(twitterBytes));
    }

    @Benchmark
    public Object guestProjectJsonapi() {
        return guestProjectJsonapi.invoke();
    }

    @Benchmark
    public Object guestProjectEntity16() {
        return guestProjectEntity16.invoke();
    }

    @Benchmark
    public Object guestProjectJsonapiBytes() {
        return guestProjectJsonapiBytes.invoke();
    }

    @Benchmark
    public Object guestProjectEntity16Bytes() {
        return guestProjectEntity16Bytes.invoke();
    }

    @Benchmark
    public Object guestProjectGithub() {
        return guestProjectGithub.invoke();
    }

    @Benchmark
    public Object guestProjectGithubBytes() {
        return guestProjectGithubBytes.invoke();
    }

    @Benchmark
    public Object guestProjectTwitterFirstBytes() {
        return guestProjectTwitterFirstBytes.invoke();
    }

    @Benchmark
    public Object guestProjectPlaceholderBytes() {
        return guestProjectPlaceholderBytes.invoke();
    }


    private ObjectNode jacksonProjectJsonapi(JsonNode root) {
        ObjectNode out = jacksonMapper.createObjectNode();
        out.set("title", root.get("data").get("attributes").get("title"));
        out.set("id", root.get("data").get("id"));
        out.set("rid", root.get("meta").get("request-id"));
        return out;
    }

    private ObjectNode jacksonProjectEntity16(JsonNode root) {
        ObjectNode out = jacksonMapper.createObjectNode();
        out.set("id", root.get("id"));
        out.set("email", root.get("email"));
        out.set("status", root.get("status"));
        return out;
    }

    private ObjectNode jacksonProjectPlaceholder(JsonNode root) {
        ObjectNode out = jacksonMapper.createObjectNode();
        out.set("id", root.get("id"));
        out.set("userId", root.get("userId"));
        out.set("title", root.get("title"));
        return out;
    }


    private ObjectNode jacksonProjectGithub(JsonNode root) {
        ObjectNode owner = jacksonMapper.createObjectNode();
        owner.set("login", root.get("owner").get("login"));
        ObjectNode out = jacksonMapper.createObjectNode();
        out.set("full_name", root.get("full_name"));
        out.set("stargazers_count", root.get("stargazers_count"));
        out.set("open_issues_count", root.get("open_issues_count"));
        out.set("owner", owner);
        return out;
    }


    private ObjectNode jacksonProjectTwitterFirst(JsonNode root) {
        JsonNode first = root.get("statuses").get(0);
        ObjectNode user = jacksonMapper.createObjectNode();
        user.set("screen_name", first.get("user").get("screen_name"));
        ObjectNode status = jacksonMapper.createObjectNode();
        status.set("id", first.get("id"));
        status.set("text", first.get("text"));
        status.set("user", user);
        ObjectNode out = jacksonMapper.createObjectNode();
        out.putArray("statuses").add(status);
        return out;
    }

}
