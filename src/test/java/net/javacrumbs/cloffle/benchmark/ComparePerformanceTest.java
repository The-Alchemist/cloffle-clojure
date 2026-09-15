package net.javacrumbs.cloffle.benchmark;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class ComparePerformanceTest {

    @Test
    public void testFixedArityStringSamplesAreInCatalog() {
        assertTrue(Arrays.asList(SnippetBenchmarkSupport.SAMPLE_NAMES)
                .contains(SnippetBenchmarkSupport.FIXED_STR2));
        assertEquals(ClojureClasspathResources.read("snippets/fixed-str2.clj"),
                SnippetBenchmarkSupport.codeFor(SnippetBenchmarkSupport.FIXED_STR2));
    }

    @Test
    public void testHashAndShape16LookupSamplesAreInCatalog() {
        assertTrue(Arrays.asList(SnippetBenchmarkSupport.SAMPLE_NAMES)
                .contains(SnippetBenchmarkSupport.HASH_MAP_LOOKUP));
        assertTrue(Arrays.asList(SnippetBenchmarkSupport.SAMPLE_NAMES)
                .contains(SnippetBenchmarkSupport.SHAPE_MAP16_LOOKUP));
        assertTrue(Arrays.asList(SnippetBenchmarkSupport.SAMPLE_NAMES)
                .contains(SnippetBenchmarkSupport.RT_GET_LOOKUP));
        assertEquals(ClojureClasspathResources.read("snippets/hash-map-lookup.clj"),
                SnippetBenchmarkSupport.codeFor(SnippetBenchmarkSupport.HASH_MAP_LOOKUP));
        assertEquals(ClojureClasspathResources.read("snippets/shape-map16-lookup.clj"),
                SnippetBenchmarkSupport.codeFor(SnippetBenchmarkSupport.SHAPE_MAP16_LOOKUP));
        assertEquals(ClojureClasspathResources.read("snippets/rt-get-lookup.clj"),
                SnippetBenchmarkSupport.codeFor(SnippetBenchmarkSupport.RT_GET_LOOKUP));
    }

    @Test
    public void testLazySeqVecFirstSampleIsInCatalog() {
        assertTrue(Arrays.asList(SnippetBenchmarkSupport.SAMPLE_NAMES)
                .contains(SnippetBenchmarkSupport.LAZY_SEQ_VEC_FIRST));
        assertEquals("(first (lazy-seq [:first]))",
                SnippetBenchmarkSupport.codeFor(SnippetBenchmarkSupport.LAZY_SEQ_VEC_FIRST)
                        .replaceAll("(?s).*\\(defn bench \\[\\]\\s*", "")
                        .replaceAll("\\)\\s*$", "")
                        .trim());
    }

    @Test
    public void namespacedSourceLeavesCatalogSnippetsUntouched() {
        String raw = SnippetBenchmarkSupport.codeFor(SnippetBenchmarkSupport.NORM_TUPLE_NTH);
        assertTrue(raw.trim().startsWith("(ns "));
        String namespaced = SnippetBenchmarkSupport.namespacedSource(
                SnippetBenchmarkSupport.NORM_TUPLE_NTH, raw);
        assertEquals(raw, namespaced);
    }

    @Test
    public void namespacedSourceStillWrapsAdHocExpression() {
        String wrapped = SnippetBenchmarkSupport.namespacedSource(
                SnippetBenchmarkSupport.FILE, "(+ 1 2)");
        assertTrue(wrapped.startsWith("(ns bench.snippet.file)"));
        assertTrue(wrapped.contains("(defn bench []"));
        assertTrue(wrapped.contains("(+ 1 2)"));
    }

    @Test
    public void testCatalogSnippetsLoadFromClasspath() {
        for (String name : SnippetBenchmarkSupport.SAMPLE_NAMES) {
            String code = SnippetBenchmarkSupport.codeFor(name);
            assertNotNull(code, name);
            assertTrue(!code.isEmpty(), name + " should be non-empty");
            assertTrue(code.trim().startsWith("(ns "), name + " should start with (ns ");
            assertTrue(code.contains("(ns bench.snippet."), name + " should declare bench.snippet ns");
            assertTrue(code.contains("(defn bench"), name + " should define (defn bench");
        }
    }

    @Test
    public void testJsonParsingAndMarkdownGeneration() {
        String mockJson = "[\n" +
                "  {\n" +
                "    \"benchmark\" : \"net.javacrumbs.cloffle.benchmark.SnippetBenchmark.cloffle\",\n" +
                "    \"mode\" : \"thrpt\",\n" +
                "    \"primaryMetric\" : {\n" +
                "      \"score\" : 25000000.0,\n" +
                "      \"scoreUnit\" : \"ops/s\"\n" +
                "    },\n" +
                "    \"secondaryMetrics\" : {\n" +
                "      \"gc.alloc.rate.norm\" : {\n" +
                "        \"score\" : 0.0\n" +
                "      }\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    \"benchmark\" : \"net.javacrumbs.cloffle.benchmark.SnippetBenchmark.clojure\",\n" +
                "    \"mode\" : \"thrpt\",\n" +
                "    \"primaryMetric\" : {\n" +
                "      \"score\" : 12500000.0,\n" +
                "      \"scoreUnit\" : \"ops/s\"\n" +
                "    },\n" +
                "    \"secondaryMetrics\" : {\n" +
                "      \"gc.alloc.rate.norm\" : {\n" +
                "        \"score\" : 48.0\n" +
                "      }\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    \"benchmark\" : \"net.javacrumbs.cloffle.benchmark.SnippetBenchmark.cloffle\",\n" +
                "    \"mode\" : \"sample\",\n" +
                "    \"primaryMetric\" : {\n" +
                "      \"scorePercentiles\" : {\n" +
                    "        \"50.0\" : 40.0,\n" +
                    "        \"95.0\" : 80.0\n" +
                "      },\n" +
                "      \"scoreUnit\" : \"ns/op\"\n" +
                "    },\n" +
                "    \"secondaryMetrics\" : {\n" +
                "      \"gc.alloc.rate.norm\" : {\n" +
                "        \"score\" : 0.0\n" +
                "      }\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    \"benchmark\" : \"net.javacrumbs.cloffle.benchmark.SnippetBenchmark.clojure\",\n" +
                "    \"mode\" : \"sample\",\n" +
                "    \"primaryMetric\" : {\n" +
                "      \"scorePercentiles\" : {\n" +
                    "        \"50.0\" : 80.0,\n" +
                    "        \"95.0\" : 160.0\n" +
                "      },\n" +
                "      \"scoreUnit\" : \"ns/op\"\n" +
                "    },\n" +
                "    \"secondaryMetrics\" : {\n" +
                "      \"gc.alloc.rate.norm\" : {\n" +
                "        \"score\" : 48.0\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "]";

        ComparePerformance.BenchmarkReport report = new ComparePerformance.BenchmarkReport();
        report.code = "(assoc {:a 1} :b 2)";
        report.outputFile = new File("target/mock-test-report.md");

        ComparePerformance.parseJmhJson(mockJson, report);

        assertEquals(1, report.snippets.size());
        assertEquals(12500000.0, report.clojure.throughputOpsPerSec, 1.0);
        assertEquals(25000000.0, report.cloffle.throughputOpsPerSec, 1.0);
        assertEquals(80.0, report.clojure.p50Ns, 0.1);
        assertEquals(40.0, report.cloffle.p50Ns, 0.1);
        assertEquals(160.0, report.clojure.p95Ns, 0.1);
        assertEquals(80.0, report.cloffle.p95Ns, 0.1);
        assertEquals(48.0, report.clojure.gcAllocBytesPerOp, 0.1);
        assertEquals(0.0, report.cloffle.gcAllocBytesPerOp, 0.01);
        assertTrue(report.clojure.hasThroughput);
        assertTrue(report.cloffle.hasThroughput);
        ComparePerformance.assertCompleteThroughputMeasurements(
                report, new String[]{SnippetBenchmarkSupport.FILE}, 4);
    }

    @Test
    public void testEmptyJmhJsonIsRejected() {
        ComparePerformance.BenchmarkReport report = new ComparePerformance.BenchmarkReport();
        report.code = "(clojure.lang.RT/vector :a :b :c :d)";
        ComparePerformance.parseJmhJson("[]", report);
        try {
            ComparePerformance.assertCompleteThroughputMeasurements(
                    report, new String[]{SnippetBenchmarkSupport.FILE}, 0);
            fail("expected IllegalStateException for empty JMH results");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("no RunResult"), e.getMessage());
        }
    }

    @Test
    public void testMissingThrptScoresAreRejected() {
        // sample-only JSON: latency present, but no thrpt — must not look like 0.00 success
        String sampleOnly = "[\n" +
                "  {\n" +
                "    \"benchmark\" : \"net.javacrumbs.cloffle.benchmark.SnippetBenchmark.cloffle\",\n" +
                "    \"mode\" : \"sample\",\n" +
                "    \"params\" : { \"name\" : \"__file__\" },\n" +
                "    \"primaryMetric\" : {\n" +
                "      \"scorePercentiles\" : { \"50.0\" : 40.0, \"95.0\" : 80.0 },\n" +
                "      \"scoreUnit\" : \"ns/op\"\n" +
                "    }\n" +
                "  }\n" +
                "]";
        ComparePerformance.BenchmarkReport report = new ComparePerformance.BenchmarkReport();
        report.code = "(broken)";
        ComparePerformance.parseJmhJson(sampleOnly, report);
        assertEquals(1, report.snippets.size());
        assertTrue(!report.snippets.get(0).cloffle.hasThroughput);
        try {
            ComparePerformance.assertCompleteThroughputMeasurements(
                    report, new String[]{SnippetBenchmarkSupport.FILE}, 1);
            fail("expected IllegalStateException for missing thrpt");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Incomplete"), e.getMessage());
        }
    }

    @Test
    public void testBrokenSnippetRunFailsInsteadOfZeroTable() throws Exception {
        ComparePerformance.CompareOptions options = new ComparePerformance.CompareOptions();
        // RT.vector is varargs Object... — host interop does not pack multi-arity args
        options.code = "(clojure.lang.RT/count (clojure.lang.RT/vector :a :b :c :d))";
        options.warmup = 1;
        options.iterations = 1;
        options.warmupTimeSeconds = 1;
        options.measurementTimeSeconds = 1;
        options.forks = 1;
        options.output = "target/junit-compare-broken-snippet.md";
        options.silent = true;
        try {
            ComparePerformance.run(options);
            fail("expected broken snippet to throw rather than report 0.00 ops/s");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Incomplete")
                            || e.getMessage().contains("no RunResult")
                            || e.getMessage().contains("no SnippetBenchmark"),
                    e.getMessage());
        }
    }

    @Test
    public void testEndToEndSmokeRun() throws Exception {
        ComparePerformance.CompareOptions options = new ComparePerformance.CompareOptions();
        options.code = "(+ 1 2)";
        options.warmup = 1;
        options.iterations = 1;
        options.warmupTimeSeconds = 1;
        options.measurementTimeSeconds = 1;
        options.forks = 1;
        options.compileImmediately = false;
        options.output = "target/junit-compare-results.md";
        options.silent = true;

        ComparePerformance.BenchmarkReport report = ComparePerformance.run(options);

        assertNotNull(report);
        assertTrue(report.clojure.throughputOpsPerSec > 0, "Clojure throughput should be > 0");
        assertTrue(report.cloffle.throughputOpsPerSec > 0, "Cloffle throughput should be > 0");
        assertTrue(report.clojure.p50Ns >= 0, "Clojure p50 should be >= 0");
        assertTrue(report.cloffle.p50Ns >= 0, "Cloffle p50 should be >= 0");
        assertTrue(report.clojure.p95Ns >= 0, "Clojure p95 should be >= 0");
        assertTrue(report.cloffle.p95Ns >= 0, "Cloffle p95 should be >= 0");
        assertTrue(report.clojure.gcAllocBytesPerOp >= 0, "Clojure alloc should be >= 0");
        assertTrue(report.cloffle.gcAllocBytesPerOp >= 0, "Cloffle alloc should be >= 0");

        File mdFile = new File(options.output);
        assertTrue(mdFile.exists(), "Output markdown file should exist");
        assertTrue(mdFile.length() > 0, "Output markdown file should not be empty");

        String md = Files.readString(mdFile.toPath(), StandardCharsets.UTF_8);
        assertTrue(md.contains("# Clojure vs Cloffle Performance Comparison"));
        assertTrue(md.contains("| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |"));
        assertTrue(md.contains("| **Throughput (ops/sec)** |"));
        assertTrue(md.contains("| **p50 latency ("));
        assertTrue(md.contains("| **p95 latency ("));
        assertTrue(md.contains("| **Allocation (B/op)** |"));
        assertTrue(md.contains("GC pressure"));
    }
}
