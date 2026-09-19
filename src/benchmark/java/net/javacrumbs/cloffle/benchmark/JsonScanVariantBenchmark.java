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
 * Host A/B ladder for PEA hypotheses on {@link JsonScan}. Crosses scanner variant
 * with fixture; {@link #scanOnly} measures raw scan cost, {@link #scanAndConsume}
 * folds decoded slots into a primitive hash so a scalar-replaceable result can
 * show up as lower B/op.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Threads(1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
public class JsonScanVariantBenchmark extends JsonParserBenchmarkBase {

    @Param({"baseline", "cold-error", "static-skip", "bytes-only", "prim-slots"})
    public String variant;

    @Param({"twitterFirst", "twitterLate", "popularApis"})
    public String fixture;

    private JsonScan.TypedTrieNode root;
    private JsonScan.TypedLeaf[] leaves;
    private byte[] source;
    private Scanner scanner;
    private boolean primSlots;

    @FunctionalInterface
    interface Scanner {
        JsonScan.TypedScanResult scan(byte[] json, JsonScan.TypedTrieNode root,
                                      JsonScan.TypedLeaf[] leaves, boolean firstWins);
    }

    @Setup(Level.Trial)
    public void setupVariant() {
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
        primSlots = false;
        switch (variant) {
            case "baseline" -> scanner = JsonScan::projectBytesPartialEvaluated;
            case "cold-error" -> scanner = JsonScanV1ColdError::projectBytesPartialEvaluated;
            case "static-skip" -> scanner = JsonScanV2StaticSkip::projectBytesPartialEvaluated;
            case "bytes-only" -> scanner = JsonScanV3BytesOnly::projectBytesPartialEvaluated;
            case "prim-slots" -> {
                primSlots = true;
                scanner = null;
            }
            default -> throw new IllegalStateException("Unknown variant=" + variant);
        }
        // Warm + parity against baseline once per trial
        JsonScan.TypedScanResult expected = JsonScan.projectBytes(source, 0, source.length, root, leaves, true);
        JsonScan.TypedScanResult actual = scanOnce();
        for (int i = 0; i < expected.states.length; i++) {
            if (expected.states[i] != actual.states[i]) {
                throw new IllegalStateException("Parity fail " + variant + "/" + fixture
                        + " state[" + i + "]");
            }
        }
    }

    @Benchmark
    public Object scanOnly() {
        return scanOnce();
    }

    @Benchmark
    public long scanAndConsume() {
        if (primSlots) {
            JsonScanV4PrimSlots.PrimScanResult r =
                    JsonScanV4PrimSlots.projectBytesPartialEvaluated(source, root, leaves, true);
            long h = 1;
            for (int i = 0; i < r.states.length; i++) {
                byte st = r.states[i];
                h = h * 31 + st;
                if (st == JsonScan.TypedScanResult.VALUE) {
                    if (r.valueKinds[i] == JsonScanV4PrimSlots.PrimScanResult.KIND_LONG
                            || r.valueKinds[i] == JsonScanV4PrimSlots.PrimScanResult.KIND_INT) {
                        h = h * 31 + r.longValues[i];
                    } else if (r.valueKinds[i] == JsonScanV4PrimSlots.PrimScanResult.KIND_DOUBLE) {
                        h = h * 31 + Double.doubleToRawLongBits(r.doubleValues[i]);
                    } else {
                        h = h * 31 + (r.values[i] == null ? 0 : r.values[i].hashCode());
                    }
                } else if (st == JsonScan.TypedScanResult.SLICE
                        || st == JsonScan.TypedScanResult.ESCAPED_SLICE
                        || st == JsonScan.TypedScanResult.RAW) {
                    h = h * 31 + r.starts[i];
                    h = h * 31 + r.lengths[i];
                }
            }
            return h;
        }
        JsonScan.TypedScanResult r = scanner.scan(source, root, leaves, true);
        long h = 1;
        for (int i = 0; i < r.states.length; i++) {
            byte st = r.states[i];
            h = h * 31 + st;
            if (st == JsonScan.TypedScanResult.VALUE) {
                Object v = r.values[i];
                if (v instanceof Number n) {
                    h = h * 31 + Double.doubleToRawLongBits(n.doubleValue());
                } else {
                    h = h * 31 + (v == null ? 0 : v.hashCode());
                }
            } else if (st == JsonScan.TypedScanResult.SLICE
                    || st == JsonScan.TypedScanResult.ESCAPED_SLICE
                    || st == JsonScan.TypedScanResult.RAW) {
                h = h * 31 + r.starts[i];
                h = h * 31 + r.lengths[i];
            }
        }
        return h;
    }

    private JsonScan.TypedScanResult scanOnce() {
        if (primSlots) {
            return JsonScanV4PrimSlots.projectBytesPartialEvaluated(source, root, leaves, true).toBoxed();
        }
        return scanner.scan(source, root, leaves, true);
    }
}
