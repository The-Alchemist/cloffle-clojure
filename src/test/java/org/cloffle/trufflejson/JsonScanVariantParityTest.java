package org.cloffle.trufflejson;

import net.javacrumbs.cloffle.bytecode.JsonTypedProjectPlan;
import net.javacrumbs.cloffle.json.JsonTypedSchemas;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Differential parity: each PEA experiment scanner must match {@link JsonScan}
 * field-for-field on the typed fixtures, and must throw {@link JsonException}
 * at the same position when twitter.json is truncated or corrupted.
 */
@RunWith(Parameterized.class)
public class JsonScanVariantParityTest {

    private static final String DATA = "/json-parser-benchmark/data/";

    public enum Variant {
        COLD_ERROR,
        STATIC_SKIP,
        BYTES_ONLY,
        PRIM_SLOTS
    }

    @Parameterized.Parameter(0)
    public Variant variant;

    @Parameterized.Parameter(1)
    public String fixtureName;

    @Parameterized.Parameter(2)
    public Object schema;

    @Parameterized.Parameter(3)
    public byte[] bytes;

    private static byte[] twitterBytes;

    @BeforeClass
    public static void loadTwitter() {
        twitterBytes = load("twitter.json");
    }

    @Parameterized.Parameters(name = "{0}/{1}")
    public static Collection<Object[]> data() {
        List<Object[]> rows = new ArrayList<>();
        record Case(String name, Object schema, byte[] bytes) {}
        Case[] cases = {
                new Case("twitterFirst", JsonTypedSchemas.twitterFirst(), load("twitter.json")),
                new Case("twitterLate", JsonTypedSchemas.twitterLate(), load("twitter.json")),
                new Case("popularApis", JsonTypedSchemas.popularApis(), load("popular-apis-composite.json")),
                new Case("github", JsonTypedSchemas.github(), load("github-clojure-repo.json")),
                new Case("placeholder", JsonTypedSchemas.placeholder(), load("jsonplaceholder-post-1.json")),
                new Case("doubles", JsonTypedSchemas.doubles(), load("doubles.json")),
                new Case("escaped", JsonTypedSchemas.escaped(), load("escaped.json")),
                new Case("jsonapi", JsonTypedSchemas.jsonapi(), load("jsonapi.json")),
        };
        for (Variant v : Variant.values()) {
            for (Case c : cases) {
                rows.add(new Object[]{v, c.name, c.schema, c.bytes});
            }
        }
        return rows;
    }

    @Test
    public void matchesBaselineScan() {
        JsonTypedProjectPlan plan = JsonTypedProjectPlan.compile(null, schema);
        JsonScan.TypedTrieNode root = plan.root();
        JsonScan.TypedLeaf[] leaves = plan.leaves();
        JsonScan.TypedScanResult expected = JsonScan.projectBytes(bytes, 0, bytes.length, root, leaves, true);
        JsonScan.TypedScanResult actual = scanVariant(variant, bytes, root, leaves, true);
        assertScanEqual(fixtureName + "/" + variant, expected, actual);
    }

    @Test
    public void truncatedTwitterSameErrorPosition() {
        if (!"twitterLate".equals(fixtureName) && !"twitterFirst".equals(fixtureName)) {
            return;
        }
        JsonTypedProjectPlan plan = JsonTypedProjectPlan.compile(null, schema);
        Random rnd = new Random(fixtureName.hashCode() * 31L + variant.ordinal());
        int[] cuts = new int[12];
        for (int i = 0; i < cuts.length; i++) {
            cuts[i] = 16 + rnd.nextInt(Math.max(1, twitterBytes.length - 32));
        }
        for (int cut : cuts) {
            byte[] truncated = Arrays.copyOf(twitterBytes, cut);
            assertSameErrorPosition("truncate@" + cut, truncated, plan.root(), plan.leaves());
        }
    }

    @Test
    public void corruptedTwitterSameErrorPosition() {
        if (!"twitterLate".equals(fixtureName) && !"twitterFirst".equals(fixtureName)) {
            return;
        }
        JsonTypedProjectPlan plan = JsonTypedProjectPlan.compile(null, schema);
        Random rnd = new Random(91001L + variant.ordinal() * 17L);
        for (int i = 0; i < 12; i++) {
            byte[] corrupted = twitterBytes.clone();
            int at = 32 + rnd.nextInt(Math.max(1, corrupted.length - 64));
            corrupted[at] = (byte) (rnd.nextBoolean() ? '{' : 0x01);
            assertSameErrorPosition("corrupt@" + at, corrupted, plan.root(), plan.leaves());
        }
    }

    private void assertSameErrorPosition(String label, byte[] input,
                                         JsonScan.TypedTrieNode root, JsonScan.TypedLeaf[] leaves) {
        JsonException baselineEx = null;
        JsonException variantEx = null;
        try {
            JsonScan.projectBytes(input, 0, input.length, root, leaves, true);
        } catch (JsonException e) {
            baselineEx = e;
        }
        try {
            scanVariant(variant, input, root, leaves, true);
        } catch (JsonException e) {
            variantEx = e;
        }
        if (baselineEx == null && variantEx == null) {
            return; // both accepted the mutation
        }
        if (baselineEx == null || variantEx == null) {
            fail(label + ": baseline " + (baselineEx == null ? "ok" : "threw")
                    + " variant " + (variantEx == null ? "ok" : "threw"));
        }
        assertEquals(label + " detail", baselineEx.detail, variantEx.detail);
        assertEquals(label + " position", baselineEx.position, variantEx.position);
    }

    @Test
    public void planScannerOptionDispatchesVariant() {
        // Run once: Parameterized invokes every @Test per row.
        if (variant != Variant.STATIC_SKIP || !"popularApis".equals(fixtureName)) {
            return;
        }
        Object opts = clojure.lang.RT.map(
                clojure.lang.Keyword.intern("cloffle", "scanner"),
                clojure.lang.Keyword.intern("static-skip"));
        JsonTypedProjectPlan plan = JsonTypedProjectPlan.compile(null, schema, opts);
        JsonScan.TypedScanResult viaPlan = plan.scan(bytes);
        JsonScan.TypedScanResult viaVariant = JsonScanV2StaticSkip.projectBytes(
                bytes, 0, bytes.length, plan.root(), plan.leaves(), true);
        assertScanEqual("plan-option/static-skip", viaVariant, viaPlan);
    }

    static JsonScan.TypedScanResult scanVariant(Variant variant, byte[] bytes,
                                                JsonScan.TypedTrieNode root,
                                                JsonScan.TypedLeaf[] leaves,
                                                boolean firstWins) {
        return switch (variant) {
            case COLD_ERROR -> JsonScanV1ColdError.projectBytes(bytes, 0, bytes.length, root, leaves, firstWins);
            case STATIC_SKIP -> JsonScanV2StaticSkip.projectBytes(bytes, 0, bytes.length, root, leaves, firstWins);
            case BYTES_ONLY -> JsonScanV3BytesOnly.projectBytes(bytes, 0, bytes.length, root, leaves, firstWins);
            case PRIM_SLOTS -> JsonScanV4PrimSlots.projectBytes(bytes, 0, bytes.length, root, leaves, firstWins).toBoxed();
        };
    }

    static void assertScanEqual(String label, JsonScan.TypedScanResult expected,
                                JsonScan.TypedScanResult actual) {
        assertEquals(label + " slot count", expected.states.length, actual.states.length);
        for (int i = 0; i < expected.states.length; i++) {
            assertEquals(label + " state[" + i + "]", expected.states[i], actual.states[i]);
            assertEquals(label + " start[" + i + "]", expected.starts[i], actual.starts[i]);
            assertEquals(label + " length[" + i + "]", expected.lengths[i], actual.lengths[i]);
            Object ev = expected.values[i];
            Object av = actual.values[i];
            if (ev == JsonScan.MISSING) {
                assertTrue(label + " value[" + i + "] missing", av == JsonScan.MISSING || av == null);
                if (expected.states[i] == JsonScan.TypedScanResult.MISSING) {
                    continue;
                }
            }
            if (!objectsEquiv(ev, av)) {
                fail(label + " value[" + i + "]: expected " + ev + " (" + classOf(ev)
                        + ") actual " + av + " (" + classOf(av) + ")");
            }
        }
    }

    private static boolean objectsEquiv(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == JsonScan.MISSING || b == JsonScan.MISSING) {
            return a == b;
        }
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue()
                    && !(a instanceof Double ^ b instanceof Double
                    && Double.isNaN(((Number) a).doubleValue()));
        }
        return Objects.equals(a, b);
    }

    private static String classOf(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }

    private static byte[] load(String name) {
        String path = DATA + name;
        try (InputStream in = JsonScanVariantParityTest.class.getResourceAsStream(path)) {
            if (in == null) {
                // Fall back to classpath root used by JMH fixtures
                try (InputStream in2 = Thread.currentThread().getContextClassLoader()
                        .getResourceAsStream("json-parser-benchmark/data/" + name)) {
                    if (in2 == null) {
                        throw new IllegalStateException("Missing fixture " + name);
                    }
                    return in2.readAllBytes();
                }
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
