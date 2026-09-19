package net.javacrumbs.cloffle.bytecode;

import net.javacrumbs.cloffle.benchmark.JsonParserBenchmarkBase;
import net.javacrumbs.cloffle.json.JsonTypedSchemas;
import org.cloffle.trufflejson.JsonScan;
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
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Stages the typed projection pipeline so {@code gc.alloc.rate.norm} attributes the guest
 * {@code json/project} budget by subtraction: scan, then scan + decode, then the full
 * scan + decode + materialize. Lives in the {@code bytecode} package for package-private
 * access to {@link JsonTypedProjectPlan#decodeUncached} rather than widening it.
 *
 * <p>Every stage hands its product to a {@link Blackhole}, so each stage's product escapes
 * and none of them is scalar replaced. The numbers are total allocation per stage, which is
 * what the guest path pays.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Threads(1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
public class JsonTypedStagedAllocBenchmark extends JsonParserBenchmarkBase {

    @Param({"twitterLate", "popularApis"})
    public String fixture;

    private JsonTypedProjectPlan plan;
    private JsonScan.TypedTrieNode root;
    private JsonScan.TypedLeaf[] leaves;
    private byte[] source;

    @Setup(Level.Trial)
    public void setupStagedAlloc() {
        loadFixtures();
        Object schema;
        switch (fixture) {
            case "twitterLate" -> {
                schema = JsonTypedSchemas.twitterLate();
                source = twitterBytes;
            }
            case "popularApis" -> {
                schema = JsonTypedSchemas.popularApis();
                source = popularApisBytes;
            }
            default -> throw new IllegalStateException("Unknown fixture=" + fixture);
        }
        plan = JsonTypedProjectPlan.compile(null, schema);
        root = plan.root();
        leaves = plan.leaves();
    }

    @Benchmark
    public void scanOnly(Blackhole bh) {
        bh.consume(JsonScan.projectBytesPartialEvaluated(source, root, leaves, true));
    }

    @Benchmark
    public void scanAndDecode(Blackhole bh) {
        JsonScan.TypedScanResult scan =
                JsonScan.projectBytesPartialEvaluated(source, root, leaves, true);
        JsonTypedProjectPlan.decodeUncached(scan, leaves);
        bh.consume(scan);
    }

    @Benchmark
    public void scanDecodeAndMaterialize(Blackhole bh) {
        JsonScan.TypedScanResult scan =
                JsonScan.projectBytesPartialEvaluated(source, root, leaves, true);
        JsonTypedProjectPlan.decodeUncached(scan, leaves);
        bh.consume(CloffleBytecodeRootNode.JsonTypedProject.materializeOutput(plan, scan.values));
    }
}
