package net.javacrumbs.cloffle.benchmark;

import net.javacrumbs.cloffle.benchmark.tuplepea.TuplePeaLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
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

import com.oracle.truffle.api.CallTarget;

import java.util.concurrent.TimeUnit;

/**
 * Truffle-PE {@link clojure.lang.JsonParser} (no Cloffle): parse + keyword {@code valAt},
 * and typed {@code project}, as {@code pea} roots. Parse/lookup also keep a
 * {@code *Boundary} pair ({@code @TruffleBoundary} public API).
 * <p>
 * GC / B/op: {@code clojure -T:build run-json-parser-benchmarks :profile :pea}
 * ({@code -prof gc}, same as {@code :host-typed}).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Threads(1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 2, time = 1)
public class JsonParserTruffleBenchmark extends JsonParserBenchmarkBase {

    private Context context;
    private CallTarget parseJsonapi;
    private CallTarget parseJsonapiBoundary;
    private CallTarget parseLookupJsonapi;
    private CallTarget parseLookupJsonapiBoundary;
    private CallTarget parseLookupEntity16;
    private CallTarget parseLookupRows;
    private CallTarget parseLookupPlaceholder;

    @State(Scope.Benchmark)
    public static class ProjectParam {
        @Param({
                "placeholder",
                "jsonapi",
                "github",
                "twitterFirst",
                "twitterNested",
                "popularApis",
        })
        public String fixture;

        byte[] source;
        CallTarget projectTyped;

        @Setup(Level.Trial)
        public void setup(JsonParserTruffleBenchmark parent) {
            projectTyped = parent.parse("json:projectTyped:" + fixture);
            source = parent.sourceFor(fixture);
        }
    }

    @Setup(Level.Trial)
    public void setupTruffleJson() throws Exception {
        loadFixtures();
        context = Context.newBuilder(TuplePeaLanguage.ID).allowAllAccess(true).build();
        context.enter();
        parseJsonapi = parse("json:parseBytes");
        parseJsonapiBoundary = parse("json:parseBytesBoundary");
        parseLookupJsonapi = parse("json:parseLookupJsonapi");
        parseLookupJsonapiBoundary = parse("json:parseLookupJsonapiBoundary");
        parseLookupEntity16 = parse("json:parseLookupEntity16");
        parseLookupRows = parse("json:parseLookupRows");
        parseLookupPlaceholder = parse("json:parseLookupPlaceholder");
    }

    @TearDown(Level.Trial)
    public void teardownTruffleJson() {
        if (context != null) {
            context.leave();
            context.close();
            context = null;
        }
    }

    CallTarget parse(String program) {
        try {
            context.parse(Source.newBuilder(TuplePeaLanguage.ID, program, program + ".pea").build());
        } catch (Exception e) {
            throw new RuntimeException("pea parse failed: " + program, e);
        }
        return TuplePeaLanguage.takeLastParsed();
    }

    byte[] sourceFor(String name) {
        return switch (name) {
            case "placeholder" -> placeholderBytes;
            case "jsonapi" -> jsonapiBytes;
            case "github" -> githubBytes;
            case "twitterFirst", "twitterNested" -> twitterBytes;
            case "popularApis" -> popularApisBytes;
            default -> throw new IllegalStateException("Unknown fixture=" + name);
        };
    }

    @Benchmark
    public Object parseJsonapiPe() {
        return parseJsonapi.call(jsonapiBytes);
    }

    @Benchmark
    public Object parseJsonapiBoundary() {
        return parseJsonapiBoundary.call(jsonapiBytes);
    }

    @Benchmark
    public Object parseLookupJsonapiPe() {
        return parseLookupJsonapi.call(jsonapiBytes);
    }

    @Benchmark
    public Object parseLookupJsonapiBoundary() {
        return parseLookupJsonapiBoundary.call(jsonapiBytes);
    }

    @Benchmark
    public Object parseLookupEntity16Pe() {
        return parseLookupEntity16.call(entity16Bytes);
    }

    @Benchmark
    public Object parseLookupRowsPe() {
        return parseLookupRows.call(rowsBytes);
    }

    @Benchmark
    public Object parseLookupPlaceholderPe() {
        return parseLookupPlaceholder.call(placeholderBytes);
    }

    @Benchmark
    public Object projectTypedPe(ProjectParam p) {
        return p.projectTyped.call(p.source);
    }
}
