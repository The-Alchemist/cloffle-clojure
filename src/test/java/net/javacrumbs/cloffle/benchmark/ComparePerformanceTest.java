package net.javacrumbs.cloffle.benchmark;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ComparePerformanceTest {

    @Test
    public void testFixedArityStringSamplesAreInCatalog() {
        assertTrue(Arrays.asList(SnippetBenchmarkSupport.SAMPLE_NAMES)
                .contains(SnippetBenchmarkSupport.FIXED_STR2));
        assertTrue(Arrays.asList(SnippetBenchmarkSupport.SAMPLE_NAMES)
                .contains(SnippetBenchmarkSupport.FIXED_STR3));
        assertEquals(ClojureClasspathResources.read("snippets/fixed-str2.clj"),
                SnippetBenchmarkSupport.codeFor(SnippetBenchmarkSupport.FIXED_STR2));
        assertEquals(ClojureClasspathResources.read("snippets/fixed-str3.clj"),
                SnippetBenchmarkSupport.codeFor(SnippetBenchmarkSupport.FIXED_STR3));
    }

    @Test
    public void testCatalogSnippetsLoadFromClasspath() {
        for (String name : SnippetBenchmarkSupport.SAMPLE_NAMES) {
            String code = SnippetBenchmarkSupport.codeFor(name);
            assertNotNull(name, code);
            assertTrue(name + " should be non-empty", !code.isEmpty());
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
        assertTrue("Clojure throughput should be > 0", report.clojure.throughputOpsPerSec > 0);
        assertTrue("Cloffle throughput should be > 0", report.cloffle.throughputOpsPerSec > 0);
        assertTrue("Clojure p50 should be >= 0", report.clojure.p50Ns >= 0);
        assertTrue("Cloffle p50 should be >= 0", report.cloffle.p50Ns >= 0);
        assertTrue("Clojure p95 should be >= 0", report.clojure.p95Ns >= 0);
        assertTrue("Cloffle p95 should be >= 0", report.cloffle.p95Ns >= 0);
        assertTrue("Clojure alloc should be >= 0", report.clojure.gcAllocBytesPerOp >= 0);
        assertTrue("Cloffle alloc should be >= 0", report.cloffle.gcAllocBytesPerOp >= 0);

        File mdFile = new File(options.output);
        assertTrue("Output markdown file should exist", mdFile.exists());
        assertTrue("Output markdown file should not be empty", mdFile.length() > 0);

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
