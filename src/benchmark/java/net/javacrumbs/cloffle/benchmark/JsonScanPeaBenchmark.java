package net.javacrumbs.cloffle.benchmark;

import net.javacrumbs.cloffle.bytecode.JsonTypedProjectPlan;
import net.javacrumbs.cloffle.json.JsonTypedSchemas;
import org.cloffle.trufflejson.JsonScan;
import org.cloffle.trufflejson.JsonScanV1ColdError;
import org.cloffle.trufflejson.JsonScanV2StaticSkip;
import org.cloffle.trufflejson.JsonScanV3BytesOnly;
import org.cloffle.trufflejson.JsonScanV4PrimSlots;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
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
 * PEA control for JsonScan. Each variant has a direct benchmark method so scanner and result
 * allocations cannot be pinned by the variant dispatch used by {@link JsonScanVariantBenchmark}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Threads(1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
public class JsonScanPeaBenchmark extends JsonParserBenchmarkBase {

    @Param({"twitterFirst", "twitterLate", "popularApis"})
    public String fixture;

    private JsonScan.TypedTrieNode root;
    private JsonScan.TypedLeaf[] leaves;
    private byte[] source;

    @Setup(Level.Trial)
    public void setup() {
        loadFixtures();
        Object schema;
        switch (fixture) {
            case "twitterFirst" -> {
                schema = JsonTypedSchemas.twitterFirst();
                source = twitterBytes;
            }
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
        JsonTypedProjectPlan plan = JsonTypedProjectPlan.compile(null, schema);
        root = plan.root();
        leaves = plan.leaves();
    }

    @Benchmark
    public long baseline() {
        return consume(JsonScan.projectBytesPartialEvaluated(source, root, leaves, true));
    }

    @Benchmark
    public long coldError() {
        return consume(JsonScanV1ColdError.projectBytesPartialEvaluated(source, root, leaves, true));
    }

    @Benchmark
    public long staticSkip() {
        return consume(JsonScanV2StaticSkip.projectBytesPartialEvaluated(source, root, leaves, true));
    }

    @Benchmark
    public long bytesOnly() {
        return consume(JsonScanV3BytesOnly.projectBytesPartialEvaluated(source, root, leaves, true));
    }

    @Benchmark
    public long primitiveSlots() {
        return consume(
                JsonScanV4PrimSlots.projectBytesPartialEvaluated(source, root, leaves, true));
    }

    @CompilerControl(CompilerControl.Mode.INLINE)
    private static long consume(JsonScan.TypedScanResult result) {
        long hash = 1;
        for (int i = 0; i < result.states.length; i++) {
            byte state = result.states[i];
            hash = hash * 31 + state;
            if (state == JsonScan.TypedScanResult.VALUE) {
                Object value = result.values[i];
                if (value instanceof Number number) {
                    hash = hash * 31 + Double.doubleToRawLongBits(number.doubleValue());
                } else {
                    hash = hash * 31 + (value == null ? 0 : value.hashCode());
                }
            } else if (state == JsonScan.TypedScanResult.SLICE
                    || state == JsonScan.TypedScanResult.ESCAPED_SLICE
                    || state == JsonScan.TypedScanResult.RAW) {
                hash = hash * 31 + result.starts[i];
                hash = hash * 31 + result.lengths[i];
            }
        }
        return hash;
    }

    @CompilerControl(CompilerControl.Mode.INLINE)
    private static long consume(JsonScanV4PrimSlots.PrimScanResult result) {
        long hash = 1;
        for (int i = 0; i < result.states.length; i++) {
            byte state = result.states[i];
            hash = hash * 31 + state;
            if (state == JsonScan.TypedScanResult.VALUE) {
                byte kind = result.valueKinds[i];
                if (kind == JsonScanV4PrimSlots.PrimScanResult.KIND_LONG
                        || kind == JsonScanV4PrimSlots.PrimScanResult.KIND_INT) {
                    hash = hash * 31 + result.longValues[i];
                } else if (kind == JsonScanV4PrimSlots.PrimScanResult.KIND_DOUBLE) {
                    hash = hash * 31 + Double.doubleToRawLongBits(result.doubleValues[i]);
                } else {
                    Object value = result.values[i];
                    hash = hash * 31 + (value == null ? 0 : value.hashCode());
                }
            } else if (state == JsonScan.TypedScanResult.SLICE
                    || state == JsonScan.TypedScanResult.ESCAPED_SLICE
                    || state == JsonScan.TypedScanResult.RAW) {
                hash = hash * 31 + result.starts[i];
                hash = hash * 31 + result.lengths[i];
            }
        }
        return hash;
    }
}
