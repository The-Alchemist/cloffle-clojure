package net.javacrumbs.cloffle.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.filter.FilteringParserDelegate;
import com.fasterxml.jackson.core.filter.JsonPointerBasedFilter;
import com.fasterxml.jackson.core.filter.TokenFilter;
import clojure.lang.AFn;
import clojure.lang.IFn;
import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentVector;
import clojure.lang.JsonParser;
import clojure.lang.Keyword;
import clojure.lang.RT;
import clojure.lang.Symbol;
import org.graalvm.polyglot.Context;
import org.simdjson.SimdJsonParser;
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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Parse + lookup for typical HTTP JSON. Run with {@code -prof gc} to compare allocation.
 * Typed extract, project, select, and Jackson streaming baselines are in sibling classes.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Threads(1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 2, time = 1)
public class JsonParserBenchmark extends JsonParserBenchmarkBase {
    @Benchmark
    public Object cloffleParseJsonapi() {
        return JsonParser.parseBytes(jsonapiBytes);
    }

    @Benchmark
    public Object cloffleParseLookupJsonapi() {
        IPersistentMap doc = (IPersistentMap) JsonParser.parseBytes(jsonapiBytes);
        IPersistentMap data = (IPersistentMap) doc.valAt(kwData);
        IPersistentMap attrs = (IPersistentMap) data.valAt(kwAttributes);
        return attrs.valAt(kwTitle);
    }

    @Benchmark
    public Object cloffleParseLookupEntity16() {
        IPersistentMap m = (IPersistentMap) JsonParser.parseBytes(entity16Bytes);
        return m.valAt(kwEmail);
    }

    @Benchmark
    public Object cloffleParseLookupRows() {
        IPersistentVector v = (IPersistentVector) JsonParser.parseBytes(rowsBytes);
        IPersistentMap row = (IPersistentMap) v.nth(3);
        return row.valAt(kwName);
    }

    @Benchmark
    public Object cloffleParseLookupPlaceholder() {
        IPersistentMap doc = (IPersistentMap) JsonParser.parseBytes(placeholderBytes);
        return doc.valAt(kwTitle);
    }

    @Benchmark
    public Object cheshireParseLookupJsonapi() {
        IPersistentMap doc = (IPersistentMap) cheshireParse.invoke(JSONAPI, Boolean.TRUE);
        IPersistentMap data = (IPersistentMap) doc.valAt(kwData);
        IPersistentMap attrs = (IPersistentMap) data.valAt(kwAttributes);
        return attrs.valAt(kwTitle);
    }

    @Benchmark
    public Object jsonistaParseLookupJsonapi() {
        IPersistentMap doc = (IPersistentMap) jsonistaRead.invoke(JSONAPI, jsonistaMapper);
        IPersistentMap data = (IPersistentMap) doc.valAt(kwData);
        IPersistentMap attrs = (IPersistentMap) data.valAt(kwAttributes);
        return attrs.valAt(kwTitle);
    }

    @Benchmark
    public Object cheshireParseLookupEntity16() {
        IPersistentMap m = (IPersistentMap) cheshireParse.invoke(ENTITY16, Boolean.TRUE);
        return m.valAt(kwEmail);
    }

    @Benchmark
    public Object jsonistaParseLookupEntity16() {
        IPersistentMap m = (IPersistentMap) jsonistaRead.invoke(ENTITY16, jsonistaMapper);
        return m.valAt(kwEmail);
    }

    @Benchmark
    public Object cheshireParseLookupPlaceholder() {
        IPersistentMap doc = (IPersistentMap) cheshireParse.invoke(placeholderJson, Boolean.TRUE);
        return doc.valAt(kwTitle);
    }

    @Benchmark
    public Object jsonistaParseLookupPlaceholder() {
        IPersistentMap doc =
                (IPersistentMap) jsonistaRead.invoke(placeholderJson, jsonistaMapper);
        return doc.valAt(kwTitle);
    }

    @Benchmark
    public String jacksonParseLookupJsonapiString() throws Exception {
        return jacksonMapper.readTree(JSONAPI)
                .get("data").get("attributes").get("title").textValue();
    }

    @Benchmark
    public String jacksonParseLookupJsonapiBytes() throws Exception {
        return jacksonMapper.readTree(jsonapiBytes)
                .get("data").get("attributes").get("title").textValue();
    }

    @Benchmark
    public String jacksonParseLookupEntity16String() throws Exception {
        return jacksonMapper.readTree(ENTITY16).get("email").textValue();
    }

    @Benchmark
    public String jacksonParseLookupEntity16Bytes() throws Exception {
        return jacksonMapper.readTree(entity16Bytes).get("email").textValue();
    }

    @Benchmark
    public String jacksonParseLookupRowsString() throws Exception {
        return jacksonMapper.readTree(ROWS).get(3).get("name").textValue();
    }

    @Benchmark
    public String jacksonParseLookupRowsBytes() throws Exception {
        return jacksonMapper.readTree(rowsBytes).get(3).get("name").textValue();
    }

    @Benchmark
    public String jacksonParseLookupPlaceholderBytes() throws Exception {
        return jacksonMapper.readTree(placeholderBytes).get("title").textValue();
    }

    @Benchmark
    public Object malliParseCoerceGithub() {
        return malliGithub.invoke(githubJson);
    }

    @Benchmark
    public Object malliParseCoerceTwitter() {
        return malliTwitter.invoke(twitterJson);
    }

    @Benchmark
    public Object guestParseLookupJsonapi() {
        return guestJsonapi.invoke();
    }

    @Benchmark
    public Object guestParseLookupEntity16() {
        return guestEntity16.invoke();
    }

    @Benchmark
    public Object guestParseLookupRows() {
        return guestRows.invoke();
    }

    @Benchmark
    public Object guestParseLookupPlaceholder() {
        return guestPlaceholder.invoke();
    }

    /**
     * Control for the fused parse-plus-access rewrite: the guest binds the parse result and returns
     * it, so the rewrite declines and this pays full construction cost.
     */
    @Benchmark
    public Object guestParseEscapeJsonapi() {
        return guestEscapeJsonapi.invoke();
    }

    @Benchmark
    public Object guestParseEscapeEntity16() {
        return guestEscapeEntity16.invoke();
    }

    @Benchmark
    public JsonNode jacksonParseGithubBytes() throws Exception {
        return jacksonMapper.readTree(githubBytes);
    }

    @Benchmark
    public String jacksonGithubEarlyBytes() throws Exception {
        return jacksonMapper.readTree(githubBytes).get("full_name").textValue();
    }

    @Benchmark
    public int jacksonGithubLateBytes() throws Exception {
        return jacksonMapper.readTree(githubBytes).get("network_count").intValue();
    }

    /**
     * Jackson pull parser ({@code JsonParser}): walk root fields, skip unselected values,
     * stop at the first matching key. This is the streaming counterpart to Cloffle first-wins.
     */
    @Benchmark
    public Object guestSimdjsonJsonapiBytes() {
        return guestSimdjsonJsonapiBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonJsonapiTruffleBytes() {
        return guestSimdjsonJsonapiTruffleBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonGithubBytes() {
        return guestSimdjsonGithubBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonGithubTruffleBytes() {
        return guestSimdjsonGithubTruffleBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonGithubSumBytes() {
        return guestSimdjsonGithubSumBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonGithubEarlyBytes() {
        return guestSimdjsonGithubEarlyBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonGithubLateBytes() {
        return guestSimdjsonGithubLateBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonTwitterFirstBytes() {
        return guestSimdjsonTwitterFirstBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonTwitterFirstTruffleBytes() {
        return guestSimdjsonTwitterFirstTruffleBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonTwitterFirstTruffleInput() {
        return guestSimdjsonTwitterFirstTruffleInput.invoke();
    }

    @Benchmark
    public Object guestSimdjsonDoublesBytes() {
        return guestSimdjsonDoublesBytes.invoke();
    }

    @Benchmark
    public Object guestSimdjsonPlaceholderBytes() {
        return guestSimdjsonPlaceholderBytes.invoke();
    }

    /**
     * Record components are matched to JSON field names, and anything unlisted is skipped.
     * simdjson still indexes the whole document first, so there is no early exit to compare
     * against; {@code gc.alloc.rate.norm} is also not comparable, because the parser pads its
     * input and holds a large reusable index buffer allocated once in setup.
     */
    public record SimdGithubOwner(String login) {
    }

    public record SimdGithubRepo(String full_name, int stargazers_count, int open_issues_count,
                               SimdGithubOwner owner) {
    }

    public record SimdTwitterUser(String screen_name) {
    }

    public record SimdTwitterStatus(long id, String text, SimdTwitterUser user) {
    }

    /** simdjson materializes every status; the Cloffle counterpart stops after the first. */
    public record SimdTwitter(java.util.List<SimdTwitterStatus> statuses) {
    }

    public record SimdPlaceholderPost(int userId, int id, String title, String body) {
    }

    @Benchmark
    public SimdPlaceholderPost simdjsonSchemaPlaceholderBytes() {
        return simdjsonParser.parse(placeholderBytes, placeholderBytes.length, SimdPlaceholderPost.class);
    }

    @Benchmark
    public SimdGithubRepo simdjsonSchemaGithubBytes() {
        return simdjsonParser.parse(githubBytes, githubBytes.length, SimdGithubRepo.class);
    }

    @Benchmark
    public SimdTwitterStatus simdjsonSchemaTwitterFirstBytes() {
        SimdTwitter twitter =
                simdjsonParser.parse(twitterBytes, twitterBytes.length, SimdTwitter.class);
        return twitter.statuses().get(0);
    }

    // ── JSON Pointer: cloffle.json/select against Jackson's two pointer paths ──────────

    @Benchmark
    public Object charredParseGithubString() {
        return charredParse.invoke(githubJson);
    }

    @Benchmark
    public Object charredLookupGithubEarly() {
        IPersistentMap doc = (IPersistentMap) charredParse.invoke(githubJson);
        return doc.valAt(kwFullName);
    }

    @Benchmark
    public Object charredLookupGithubLate() {
        IPersistentMap doc = (IPersistentMap) charredParse.invoke(githubJson);
        return doc.valAt(kwNetworkCount);
    }

    @Benchmark
    public Object charredLookupGithubFour() {
        IPersistentMap doc = (IPersistentMap) charredParse.invoke(githubJson);
        IPersistentMap owner = (IPersistentMap) doc.valAt(kwOwner);
        return RT.vector(
                doc.valAt(kwFullName),
                doc.valAt(kwStargazers),
                doc.valAt(kwOpenIssues),
                owner.valAt(kwLogin));
    }

    @Benchmark
    public Object lazyJsonGithubEarly() {
        lazyJsonConsume.invoke(
                lazyJsonEarlyAutomaton, lazyJsonParse.invoke(githubJson), RT.vector(jsonRoot));
        return lazyJsonSlot[0];
    }

    @Benchmark
    public Object lazyJsonGithubLate() {
        lazyJsonConsume.invoke(
                lazyJsonLateAutomaton, lazyJsonParse.invoke(githubJson), RT.vector(jsonRoot));
        return lazyJsonSlot[0];
    }

    @Benchmark
    public Object lazyJsonGithubFour() {
        lazyJsonConsume.invoke(
                lazyJsonFourAutomaton, lazyJsonParse.invoke(githubJson), RT.vector(jsonRoot));
        return RT.vector(lazyJsonSlot[0], lazyJsonSlot[1], lazyJsonSlot[2], lazyJsonSlot[3]);
    }

    @Benchmark
    public void cloffleParseEntity16Blackhole(Blackhole bh) {
        bh.consume(JsonParser.parseBytes(entity16Bytes));
    }
}
