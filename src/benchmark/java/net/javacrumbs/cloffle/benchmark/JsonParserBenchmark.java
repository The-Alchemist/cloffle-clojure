package net.javacrumbs.cloffle.benchmark;

import clojure.lang.IFn;
import clojure.lang.IPersistentMap;
import clojure.lang.IPersistentVector;
import clojure.lang.JsonParser;
import clojure.lang.Keyword;
import clojure.lang.RT;
import clojure.lang.Symbol;
import org.graalvm.polyglot.Context;
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
 * SIMD stage-1 is not on this path; add it only if large payloads show the scalar scan.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Threads(1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 2, time = 1)
public class JsonParserBenchmark {

    private static final ThreadLocal<Map<String, Object>> CAPTURED =
            ThreadLocal.withInitial(HashMap::new);

    static final String JSONAPI =
            "{\"data\":{\"type\":\"articles\",\"id\":\"article-101\",\"attributes\":{\"title\":\"Shape maps in practice\",\"slug\":\"shape-maps\",\"status\":\"published\",\"author\":\"Avery\"},\"relationships\":{\"author\":{\"type\":\"people\",\"id\":\"person-7\"}},\"links\":{\"self\":\"/articles/article-101\"}},\"meta\":{\"request-id\":\"req-101\",\"version\":\"v1\"}}";

    static final String ENTITY16 =
            "{\"id\":\"user-101\",\"type\":\"user\",\"tenant-id\":\"org-3\",\"email\":\"avery@example.test\",\"username\":\"avery\",\"status\":\"pending\",\"role\":\"admin\",\"created-at\":\"2026-01-10\",\"updated-at\":\"2026-09-09\",\"version\":\"v7\",\"locale\":\"en-US\",\"timezone\":\"America/New_York\",\"profile\":\"x\",\"settings\":\"y\",\"organization\":\"z\",\"audit\":\"w\"}";

    static final String ROWS =
            "[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"},{\"id\":3,\"name\":\"c\"},{\"id\":4,\"name\":\"d\"},{\"id\":5,\"name\":\"e\"},{\"id\":6,\"name\":\"f\"},{\"id\":7,\"name\":\"g\"},{\"id\":8,\"name\":\"h\"}]";

    public static Object captureGuestValue(String name, Object value) {
        CAPTURED.get().put(name, value);
        return value;
    }

    private Context context;
    private byte[] jsonapiBytes;
    private byte[] entity16Bytes;
    private byte[] rowsBytes;
    private IFn cheshireParse;
    private IFn jsonistaRead;
    private Object jsonistaMapper;
    private IFn guestJsonapi;
    private IFn guestEntity16;
    private IFn guestRows;
    private final Keyword kwData = Keyword.intern("data");
    private final Keyword kwAttributes = Keyword.intern("attributes");
    private final Keyword kwTitle = Keyword.intern("title");
    private final Keyword kwEmail = Keyword.intern("email");
    private final Keyword kwName = Keyword.intern("name");

    @Setup(Level.Trial)
    public void setup() {
        RT.init();
        jsonapiBytes = JSONAPI.getBytes(StandardCharsets.UTF_8);
        entity16Bytes = ENTITY16.getBytes(StandardCharsets.UTF_8);
        rowsBytes = ROWS.getBytes(StandardCharsets.UTF_8);

        IFn require = RT.var("clojure.core", "require");
        require.invoke(Symbol.intern("cheshire.core"));
        require.invoke(Symbol.intern("jsonista.core"));
        cheshireParse = RT.var("cheshire.core", "parse-string");
        jsonistaRead = RT.var("jsonista.core", "read-value");
        jsonistaMapper = RT.var("jsonista.core", "keyword-keys-object-mapper").deref();

        context = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .build();
        context.eval("cloffle", ClojureClasspathResources.read("json-parser-benchmark/setup.clj"));
        context.enter();
        guestJsonapi = guestFn("guest-parse-lookup-jsonapi");
        guestEntity16 = guestFn("guest-parse-lookup-entity16");
        guestRows = guestFn("guest-parse-lookup-rows");
        CAPTURED.remove();
    }

    private IFn guestFn(String name) {
        context.eval("cloffle",
                "(net.javacrumbs.cloffle.benchmark.JsonParserBenchmark/captureGuestValue \""
                        + name + "\" " + name + ")");
        Object value = CAPTURED.get().remove(name);
        if (value == null) {
            throw new IllegalStateException("Guest value was not captured: " + name);
        }
        return (IFn) value;
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (context != null) {
            context.leave();
            context.close();
        }
    }

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

    /** Consumes parse-only so the JIT cannot dead-eliminate construction. */
    @Benchmark
    public void cloffleParseEntity16Blackhole(Blackhole bh) {
        bh.consume(JsonParser.parseBytes(entity16Bytes));
    }
}
