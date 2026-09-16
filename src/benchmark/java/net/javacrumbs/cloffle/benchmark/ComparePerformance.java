package net.javacrumbs.cloffle.benchmark;

import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openjdk.jmh.results.RunResult;

/**
 * Runner and report generator comparing arbitrary Clojure code blocks
 * between official Standard Clojure (JVM) and Cloffle (GraalVM Truffle).
 */
public class ComparePerformance {

    /** Mirrors `test-jvm-opts` plus the JMH lock flag from build.clj. */
    private static final List<String> TEST_JVM_OPTS = List.of(
            "-Xss4m",
            "--enable-native-access=ALL-UNNAMED",
            "--sun-misc-unsafe-memory-access=allow",
            "-Dpolyglotimpl.AttachLibraryFailureAction=throw",
            "-Djmh.ignoreLock=true",
            // Force compiler blackholes (same as auto-detect on JDK 17+) so JMH prints
            // "# Blackhole mode: compiler (forced)" instead of the long auto-detect tip
            // on every fork. Do not set only -Djmh.blackhole.autoDetect=false: that falls
            // back to FULL_DONTINLINE and changes measurement semantics.
            "-Djmh.blackhole.mode=COMPILER");

    /** Truffle logs default to stderr, which corrupts JMH's `# Warmup Iteration` lines. */
    private static final String TRUFFLE_LOG_FILE_PREFIX = "-Dpolyglot.log.file=";

    /** Tail latency percentile reported in summaries (e.g. 95 → p95). Change here to switch; no p99 column yet. */
    private static final double TAIL_PERCENTILE = 95.0;
    private static final String TAIL_PERCENTILE_KEY = String.format(Locale.US, "%.1f", TAIL_PERCENTILE);
    private static final String TAIL_PERCENTILE_LABEL = "p" + ((int) TAIL_PERCENTILE);

    public static class CompareOptions {
        public String code;
        public String file;
        public String output = "benchmark-results.md";
        public int warmup = 2;
        public int iterations = 3;
        public int warmupTimeSeconds = 1;
        public int measurementTimeSeconds = 1;
        public int forks = 1;
        public boolean compileImmediately = false;
        /**
         * Cloffle guest {@code :direct-linking} for the {@code cloffle} JMH leg only (default true).
         * Passed as {@code -Dcloffle.bench.directLinking=…}; stock Clojure JMH leg is unaffected.
         */
        public boolean directLinking = true;
        public boolean silent = false;
        /** Comma-separated {@link SnippetBenchmarkSupport} snippet ids; suite mode only. */
        public String names;
    }

    public static class BenchmarkMetrics {
        public double throughputOpsPerSec;
        public double p50Ns;
        /** Latency at {@link ComparePerformance#TAIL_PERCENTILE} (currently p95). */
        public double p95Ns;
        public double gcAllocBytesPerOp;
        /** True once a JMH {@code thrpt} primary score was parsed for this side. */
        public boolean hasThroughput;

        /** Tail latency in nanoseconds for the configured {@link ComparePerformance#TAIL_PERCENTILE}. */
        public double tailNs() {
            return p95Ns;
        }

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "Throughput: %,.0f ops/s, p50: %.2f ns, %s: %.2f ns, Alloc: %.1f B/op",
                    throughputOpsPerSec, p50Ns, TAIL_PERCENTILE_LABEL, p95Ns, gcAllocBytesPerOp);
        }
    }

    public static class SnippetResult {
        public String name;
        public String code;
        public BenchmarkMetrics clojure = new BenchmarkMetrics();
        public BenchmarkMetrics cloffle = new BenchmarkMetrics();
        public String markdownTable;
    }

    public static class BenchmarkReport {
        public String code;
        public List<SnippetResult> snippets = new ArrayList<>();
        public BenchmarkMetrics clojure = new BenchmarkMetrics();
        public BenchmarkMetrics cloffle = new BenchmarkMetrics();
        public String markdownTable;
        public String fullMarkdown;
        public File outputFile;
    }

    public static void main(String[] args) {
        try {
            CompareOptions options = parseArgs(args);
            if (options == null) {
                return;
            }
            run(options);
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    public static CompareOptions parseArgs(String[] args) {
        CompareOptions options = new CompareOptions();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-c":
                case "--code":
                    if (i + 1 < args.length) options.code = args[++i];
                    break;
                case "-f":
                case "--file":
                    if (i + 1 < args.length) options.file = args[++i];
                    break;
                case "-o":
                case "--output":
                    if (i + 1 < args.length) options.output = args[++i];
                    break;
                case "-wi":
                case "--warmup":
                    if (i + 1 < args.length) options.warmup = Integer.parseInt(args[++i]);
                    break;
                case "-i":
                case "--iterations":
                    if (i + 1 < args.length) options.iterations = Integer.parseInt(args[++i]);
                    break;
                case "-w":
                case "--warmup-time":
                    if (i + 1 < args.length) options.warmupTimeSeconds = parseTimeSeconds(args[++i]);
                    break;
                case "-r":
                case "--measurement-time":
                    if (i + 1 < args.length) options.measurementTimeSeconds = parseTimeSeconds(args[++i]);
                    break;
                case "--forks":
                    if (i + 1 < args.length) options.forks = Integer.parseInt(args[++i]);
                    break;
                case "--compile-immediately":
                    options.compileImmediately = true;
                    break;
                case "--direct-linking":
                    if (i + 1 < args.length && isDirectLinkingArgValue(args[i + 1])) {
                        options.directLinking = Boolean.parseBoolean(args[++i]);
                    } else {
                        options.directLinking = true;
                    }
                    break;
                case "-n":
                case "--names":
                    if (i + 1 < args.length) {
                        options.names = args[++i];
                    }
                    break;
                case "--silent":
                    options.silent = true;
                    break;
                case "-h":
                case "--help":
                    printHelp();
                    return null;
                default:
                    System.err.println("Unknown argument: " + arg);
                    printHelp();
                    return null;
            }
        }
        return options;
    }

    private static boolean isDirectLinkingArgValue(String arg) {
        return "true".equalsIgnoreCase(arg) || "false".equalsIgnoreCase(arg);
    }

    private static int parseTimeSeconds(String str) {
        String s = str.trim().toLowerCase(Locale.ROOT);
        if (s.endsWith("s")) {
            s = s.substring(0, s.length() - 1);
        }
        return Integer.parseInt(s);
    }

    private static void printHelp() {
        System.out.println("ComparePerformance - Compare Clojure vs Cloffle execution performance");
        System.out.println("Usage:");
        System.out.println("  clj -T:build compare-performance [options]");
        System.out.println("  java -cp <cp> net.javacrumbs.cloffle.benchmark.ComparePerformance [options]");
        System.out.println("\nOptions:");
        System.out.println("  -c, --code <str>            Clojure expression to benchmark (single snippet)");
        System.out.println("  -f, --file <path>           Path to file containing Clojure code");
        System.out.println("  (no -c/-f)                  Run the built-in benchmark sample catalog");
        System.out.println("  -n, --names <id,id,...>     Subset of built-in snippet ids (suite mode only)");
        System.out.println("  -o, --output <path>         Output Markdown report path (default: benchmark-results.md)");
        System.out.println("  -wi, --warmup <n>           Warmup iterations (default: 2)");
        System.out.println("  -i, --iterations <n>        Measurement iterations (default: 3)");
        System.out.println("  -w, --warmup-time <sec>     Seconds per warmup iteration (default: 1)");
        System.out.println("  -r, --measurement-time <s   Seconds per measurement iteration (default: 1)");
        System.out.println("  --compile-immediately       Force synchronous Truffle compilation on first call");
        System.out.println("  --direct-linking [bool]     Cloffle leg only: -Dcloffle.bench.directLinking (default true; bare = true)");
        System.out.println("  -h, --help                  Print this help");
        System.out.println("\nAd-hoc -c/-f snippets are preflighted on stock Clojure and Cloffle; use vector");
        System.out.println("literals or (vector …), not multi-arg (RT/vector …), unless you only care about Cloffle.");
    }

    /** Warn when ad-hoc code is likely to fail stock-Clojure preflight (Cloffle accepts spread RT/vector). */
    static void warnIfAdHocCodeMayFailStockPreflight(String code, boolean silent) {
        if (silent || code == null || code.isEmpty()) {
            return;
        }
        if (code.contains("RT/vector") && code.indexOf("RT/vector") < code.lastIndexOf(' ')) {
            String tail = code.substring(code.indexOf("RT/vector"));
            if (tail.split("\\s+").length > 2) {
                System.err.println(
                        "Warning: multi-arg (RT/vector …) often fails stock Clojure preflight; "
                                + "prefer [:a :b] or (vector …) for both legs.");
            }
        }
    }

    public static BenchmarkReport run(CompareOptions options) throws Exception {
        String customCode = resolveCustomCode(options);
        boolean suite = customCode == null;
        String[] paramNames = suite
                ? resolveSuiteParamNames(options)
                : new String[]{SnippetBenchmarkSupport.FILE};

        File targetDir = new File("target");
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        if (!suite) {
            File snippetFile = new File(targetDir, "cloffle-compare-snippet.clj");
            Files.writeString(snippetFile.toPath(), customCode, StandardCharsets.UTF_8);
        }

        File jsonResult = new File(targetDir, "compare-performance-result.json");
        if (jsonResult.exists()) {
            jsonResult.delete();
        }

        // Forks inherit this JVM's flags, as with `clj -T:build run-benchmarks`. Only add the
        // build.clj `test-jvm-opts` flags that are missing, so a standalone `java ... ComparePerformance`
        // still gets the optimizing Truffle runtime.
        List<String> parentArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        List<String> jvmArgs = new ArrayList<>();
        for (String flag : TEST_JVM_OPTS) {
            if (!parentArgs.contains(flag)) {
                jvmArgs.add(flag);
            }
        }
        if (parentArgs.stream().noneMatch(a -> a.startsWith(TRUFFLE_LOG_FILE_PREFIX))) {
            jvmArgs.add(TRUFFLE_LOG_FILE_PREFIX + new File(targetDir, "truffle-jmh.log").getAbsolutePath());
        }
        if (!suite) {
            File snippetFile = new File(targetDir, "cloffle-compare-snippet.clj");
            jvmArgs.add("-Dcloffle.bench.snippet.file=" + snippetFile.getAbsolutePath());
        }
        if (options.compileImmediately) {
            jvmArgs.add("-Dcloffle.bench.compileImmediately=true");
        }
        jvmArgs.add("-Dcloffle.bench.directLinking=" + options.directLinking);

        if (!options.silent) {
            System.out.println("==========================================================");
            System.out.println(" Running JMH: Clojure vs Cloffle Performance Comparison");
            if (suite) {
                System.out.println(" Samples: " + paramNames.length + " built-in benchmark examples");
            } else {
                System.out.println(" Code:\n" + indent(customCode, "   "));
            }
            System.out.println(" Warmup: " + options.warmup + " iters x " + options.warmupTimeSeconds + "s");
            System.out.println(" Measurement: " + options.iterations + " iters x " + options.measurementTimeSeconds + "s");
            System.out.println(" Direct-linking: " + options.directLinking);
            System.out.println("==========================================================");
        }

        if (!suite) {
            File snippetFile = new File(targetDir, "cloffle-compare-snippet.clj");
            System.setProperty("cloffle.bench.snippet.file", snippetFile.getAbsolutePath());
            warnIfAdHocCodeMayFailStockPreflight(customCode, options.silent);
        }
        if (!options.silent) {
            System.out.println("Preflight: compile-checking " + paramNames.length + " snippet(s) on both legs…");
        }
        SnippetBenchmarkSupport.preflightSnippets(paramNames, options.directLinking);

        OptionsBuilder optionsBuilder = new OptionsBuilder();
        optionsBuilder.include(SnippetBenchmark.class.getSimpleName())
                .param("name", paramNames)
                .warmupIterations(options.warmup)
                .warmupTime(TimeValue.seconds(options.warmupTimeSeconds))
                .measurementIterations(options.iterations)
                .measurementTime(TimeValue.seconds(options.measurementTimeSeconds))
                .forks(options.forks);
        if (options.silent) {
            // JUnit compare tests: one thrpt pass only (skip SampleTime) for faster forks.
            optionsBuilder.mode(Mode.Throughput);
        }
        Options opt = optionsBuilder
                .jvmArgsAppend(jvmArgs.toArray(new String[0]))
                .shouldFailOnError(true)
                .addProfiler(GCProfiler.class)
                .resultFormat(ResultFormatType.JSON)
                .result(jsonResult.getAbsolutePath())
                .build();

        Collection<RunResult> jmhResults = new Runner(opt).run();

        if (!jsonResult.exists()) {
            throw new IllegalStateException("JMH did not generate output file: " + jsonResult.getAbsolutePath());
        }

        String jsonContent = Files.readString(jsonResult.toPath(), StandardCharsets.UTF_8);
        BenchmarkReport report = new BenchmarkReport();
        report.code = customCode;
        report.outputFile = new File(options.output);

        parseJmhJson(jsonContent, report);
        assertCompleteThroughputMeasurements(report, paramNames, jmhResults == null ? 0 : jmhResults.size());

        generateMarkdown(report);

        File outFile = report.outputFile;
        if (outFile.getParentFile() != null && !outFile.getParentFile().exists()) {
            outFile.getParentFile().mkdirs();
        }
        Files.writeString(outFile.toPath(), report.fullMarkdown, StandardCharsets.UTF_8);

        if (!options.silent) {
            System.out.println("\n" + report.markdownTable);
            System.out.println("\nReport saved to: " + outFile.getAbsolutePath());
        }

        return report;
    }

    private static String[] resolveSuiteParamNames(CompareOptions options) {
        if (options.names == null || options.names.isBlank()) {
            return SnippetBenchmarkSupport.SAMPLE_NAMES;
        }
        String[] picked = java.util.Arrays.stream(options.names.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        if (picked.length == 0) {
            throw new IllegalArgumentException("--names must list at least one snippet id");
        }
        for (String name : picked) {
            SnippetBenchmarkSupport.codeFor(name);
        }
        return picked;
    }

    /** Null means run the KeywordMapBenchmark guest-sample catalog. */
    private static String resolveCustomCode(CompareOptions options) {
        if (options.code != null && !options.code.trim().isEmpty()) {
            return options.code.trim();
        }
        if (options.file != null && !options.file.trim().isEmpty()) {
            try {
                return Files.readString(new File(options.file.trim()).toPath(), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw new RuntimeException("Could not read code file: " + options.file, e);
            }
        }
        return null;
    }

    private static String indent(String text, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            sb.append(prefix).append(line).append("\n");
        }
        return sb.toString();
    }

    /**
     * Splits top-level objects in a JSON array by tracking brace depth.
     */
    private static List<String> splitTopLevelObjects(String json) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start != -1) {
                    objects.add(json.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    /**
     * Parses the JMH JSON string and fills metrics for clojure and cloffle.
     */
    public static void parseJmhJson(String json, BenchmarkReport report) {
        Map<String, SnippetResult> byName = new LinkedHashMap<>();
        List<String> objects = splitTopLevelObjects(json);
        for (String block : objects) {
            boolean isClojure = block.contains("SnippetBenchmark.clojure");
            boolean isCloffle = block.contains("SnippetBenchmark.cloffle");
            if (!isClojure && !isCloffle) {
                continue;
            }

            String name = extractParam(block, "name");
            if (name == null) {
                name = SnippetBenchmarkSupport.FILE;
            }
            SnippetResult snippet = byName.computeIfAbsent(name, n -> {
                SnippetResult s = new SnippetResult();
                s.name = n;
                return s;
            });
            BenchmarkMetrics target = isClojure ? snippet.clojure : snippet.cloffle;
            fillMetrics(block, target);
        }

        report.snippets = new ArrayList<>(byName.values());
        for (SnippetResult snippet : report.snippets) {
            if (SnippetBenchmarkSupport.FILE.equals(snippet.name)) {
                snippet.code = report.code != null ? report.code : SnippetBenchmarkSupport.codeFor(snippet.name);
            } else {
                snippet.code = SnippetBenchmarkSupport.codeFor(snippet.name);
            }
            snippet.markdownTable = metricTable(snippet.clojure, snippet.cloffle);
        }
        if (report.snippets.size() == 1) {
            SnippetResult only = report.snippets.get(0);
            report.clojure = only.clojure;
            report.cloffle = only.cloffle;
            if (report.code == null) {
                report.code = only.code;
            }
        }
    }

    /**
     * Fail loudly when JMH produced no usable thrpt scores (e.g. all iterations {@code <failure>},
     * empty JSON {@code []}). Without this, missing metrics look like a successful {@code 0.00} report.
     */
    public static void assertCompleteThroughputMeasurements(BenchmarkReport report,
                                                            String[] expectedParamNames,
                                                            int jmhRunResultCount) {
        if (jmhRunResultCount == 0) {
            throw new IllegalStateException(
                    "JMH returned no RunResult entries. All iterations likely failed "
                            + "(check snippet compile errors / warmup failures above).");
        }
        if (report.snippets == null || report.snippets.isEmpty()) {
            throw new IllegalStateException(
                    "JMH JSON contained no SnippetBenchmark thrpt/sample results "
                            + "(empty or unparseable). Refusing to report 0.00 placeholders.");
        }
        List<String> missing = new ArrayList<>();
        for (SnippetResult snippet : report.snippets) {
            if (!snippet.clojure.hasThroughput) {
                missing.add(displayName(snippet.name) + " clojure thrpt");
            }
            if (!snippet.cloffle.hasThroughput) {
                missing.add(displayName(snippet.name) + " cloffle thrpt");
            }
        }
        if (expectedParamNames != null) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (SnippetResult snippet : report.snippets) {
                seen.add(snippet.name);
            }
            for (String expected : expectedParamNames) {
                if (!seen.contains(expected)) {
                    missing.add(displayName(expected) + " (no JMH JSON block)");
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Incomplete ComparePerformance measurements (snippet likely failed during JMH): "
                            + String.join(", ", missing));
        }
    }

    private static void fillMetrics(String block, BenchmarkMetrics target) {
        boolean isThrpt = block.contains("\"mode\" : \"thrpt\"");
        boolean isSample = block.contains("\"mode\" : \"sample\"");
        if (isThrpt) {
            Double score = extractPrimaryScore(block);
            String unit = extractScoreUnit(block);
            if (score != null) {
                target.throughputOpsPerSec = normalizeThroughput(score, unit);
                target.hasThroughput = true;
            }
            Double allocNorm = extractSecondaryScore(block, "gc.alloc.rate.norm");
            if (allocNorm != null) {
                target.gcAllocBytesPerOp = allocNorm;
            }
        } else if (isSample) {
            Double p50 = extractPercentile(block, "50.0");
            Double tail = extractPercentile(block, TAIL_PERCENTILE_KEY);
            String unit = extractScoreUnit(block);
            if (p50 != null) {
                target.p50Ns = normalizeLatencyNs(p50, unit);
            }
            if (tail != null) {
                target.p95Ns = normalizeLatencyNs(tail, unit);
            }
            if (target.gcAllocBytesPerOp == 0.0) {
                Double allocNorm = extractSecondaryScore(block, "gc.alloc.rate.norm");
                if (allocNorm != null) {
                    target.gcAllocBytesPerOp = allocNorm;
                }
            }
        }
    }

    private static String extractParam(String block, String key) {
        Pattern pattern = Pattern.compile(
                "\"params\"\\s*:\\s*\\{[\\s\\S]*?\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(block);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static Double extractPrimaryScore(String block) {
        Pattern pattern = Pattern.compile("\"primaryMetric\"\\s*:\\s*\\{[\\s\\S]*?\"score\"\\s*:\\s*([0-9.E+-]+)");
        Matcher matcher = pattern.matcher(block);
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        }
        return null;
    }

    private static String extractScoreUnit(String block) {
        Pattern pattern = Pattern.compile("\"primaryMetric\"\\s*:\\s*\\{[\\s\\S]*?\"scoreUnit\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(block);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "ops/s";
    }

    private static Double extractPercentile(String block, String pct) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(pct) + "\"\\s*:\\s*([0-9.E+-]+)");
        Matcher matcher = pattern.matcher(block);
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        }
        return null;
    }

    private static Double extractSecondaryScore(String block, String metricName) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(metricName) + "\"\\s*:\\s*\\{[\\s\\S]*?\"score\"\\s*:\\s*([0-9.E+-]+)");
        Matcher matcher = pattern.matcher(block);
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        }
        return null;
    }

    private static double normalizeThroughput(double score, String unit) {
        if ("ops/s".equalsIgnoreCase(unit)) {
            return score;
        } else if ("ops/ms".equalsIgnoreCase(unit)) {
            return score * 1_000.0;
        } else if ("ops/us".equalsIgnoreCase(unit)) {
            return score * 1_000_000.0;
        } else if ("ops/ns".equalsIgnoreCase(unit)) {
            return score * 1_000_000_000.0;
        }
        return score;
    }

    private static double normalizeLatencyNs(double score, String unit) {
        if ("s/op".equalsIgnoreCase(unit)) {
            return score * 1_000_000_000.0;
        } else if ("ms/op".equalsIgnoreCase(unit)) {
            return score * 1_000_000.0;
        } else if ("us/op".equalsIgnoreCase(unit)) {
            return score * 1_000.0;
        } else if ("ns/op".equalsIgnoreCase(unit)) {
            return score;
        }
        return score;
    }

    private static String metricTable(BenchmarkMetrics clj, BenchmarkMetrics clof) {
        LatencyUnit latencyUnit = pickLatencyUnit(Math.max(clj.p50Ns, Math.max(clof.p50Ns,
                Math.max(clj.tailNs(), clof.tailNs()))));
        StringBuilder table = new StringBuilder();
        table.append("| Metric | Clojure (JVM) | Cloffle (Truffle) | Cloffle / Clojure |\n");
        table.append("| :--- | ---: | ---: | ---: |\n");
        table.append(String.format(Locale.US, "| **Throughput (ops/sec)** | %s | %s | %s |\n",
                formatCompact(clj.throughputOpsPerSec), formatCompact(clof.throughputOpsPerSec),
                formatRatioWithX(clof.throughputOpsPerSec, clj.throughputOpsPerSec)));
        table.append(String.format(Locale.US, "| **p50 latency (%s)** | %s | %s | %s |\n",
                latencyUnit.name,
                formatLatencyIn(clj.p50Ns, latencyUnit), formatLatencyIn(clof.p50Ns, latencyUnit),
                formatRatioWithX(clof.p50Ns, clj.p50Ns)));
        table.append(String.format(Locale.US, "| **%s latency (%s)** | %s | %s | %s |\n",
                TAIL_PERCENTILE_LABEL, latencyUnit.name,
                formatLatencyIn(clj.tailNs(), latencyUnit), formatLatencyIn(clof.tailNs(), latencyUnit),
                formatRatioWithX(clof.tailNs(), clj.tailNs())));
        table.append(String.format(Locale.US, "| **Allocation (B/op)** | %s | %s | %s |\n",
                formatAlloc(clj.gcAllocBytesPerOp), formatAlloc(clof.gcAllocBytesPerOp),
                formatRatioWithX(clof.gcAllocBytesPerOp, clj.gcAllocBytesPerOp)));
        table.append("\n");
        table.append("_Ratio is Cloffle ÷ Clojure: >1 is better for throughput; <1 is better for latency and allocation._");
        return table.toString().trim();
    }

    private static String displayName(String name) {
        if (SnippetBenchmarkSupport.FILE.equals(name)) {
            return "custom";
        }
        return name;
    }

    private static void generateMarkdown(BenchmarkReport report) {
        if (report.snippets.isEmpty()) {
            SnippetResult fallback = new SnippetResult();
            fallback.name = SnippetBenchmarkSupport.FILE;
            fallback.code = report.code;
            fallback.clojure = report.clojure;
            fallback.cloffle = report.cloffle;
            fallback.markdownTable = metricTable(fallback.clojure, fallback.cloffle);
            report.snippets.add(fallback);
        }

        String osName = System.getProperty("os.name");
        String osArch = System.getProperty("os.arch");
        String javaVersion = System.getProperty("java.version");
        String dateStr = LocalDate.now().format(DateTimeFormatter.ISO_DATE);

        if (report.snippets.size() > 1) {
            report.markdownTable = summaryTable(report.snippets);
        } else {
            report.markdownTable = report.snippets.get(0).markdownTable;
        }

        StringBuilder full = new StringBuilder();
        full.append("# Clojure vs Cloffle Performance Comparison\n\n");
        full.append("**Date:** ").append(dateStr).append("  \n");
        full.append("**Environment:** ").append(osName).append(" (").append(osArch).append("), Java ").append(javaVersion).append("  \n\n");
        if (report.snippets.size() > 1) {
            full.append("Built-in guest samples, compared via direct `IFn.invoke`.\n\n");
            full.append("### Summary\n\n");
            full.append(report.markdownTable).append("\n\n");
            full.append("_Speedup (x) is Cloffle ÷ Clojure throughput. Latency columns share one unit chosen from the largest ")
                    .append(TAIL_PERCENTILE_LABEL)
                    .append(" across all samples._\n\n");
        }
        for (SnippetResult snippet : report.snippets) {
            full.append("### ").append(displayName(snippet.name)).append("\n\n");
            full.append("```clojure\n");
            full.append(snippet.code).append("\n");
            full.append("```\n\n");
            full.append(snippet.markdownTable).append("\n\n");
        }
        full.append("### Metric Definitions\n\n");
        full.append("- **Throughput (ops/sec)**: Sustained execution rate (higher is better).\n");
        full.append("- **p50 / ").append(TAIL_PERCENTILE_LABEL)
                .append(" latency**: 50th and ").append(((int) TAIL_PERCENTILE))
                .append("th percentile invocation response times (lower is better). ")
                .append("JMH sample mode often hits a timer resolution floor (~40 ns on this host), ")
                .append("so near-floor values measure the clock more than the snippet.\n");
        full.append("- **Allocation (B/op) / GC pressure**: Heap bytes allocated per operation ")
                .append("(`gc.alloc.rate.norm`). Lower values indicate less GC pressure; ")
                .append("near-zero often means Truffle Partial Escape Analysis / scalar replacement.\n");

        report.fullMarkdown = full.toString();
    }

    private static String summaryTable(List<SnippetResult> snippets) {
        double maxTailNs = 0.0;
        for (SnippetResult snippet : snippets) {
            maxTailNs = Math.max(maxTailNs, Math.max(snippet.clojure.tailNs(), snippet.cloffle.tailNs()));
        }
        LatencyUnit latencyUnit = pickLatencyUnit(maxTailNs);

        StringBuilder summary = new StringBuilder();
        summary.append(String.format(Locale.US,
                "| Sample | Clojure (ops/sec) | Cloffle (ops/sec) | Speedup (x) | Clojure %s (%s) | Cloffle %s (%s) | Clojure alloc (B/op) | Cloffle alloc (B/op) |\n",
                TAIL_PERCENTILE_LABEL, latencyUnit.name, TAIL_PERCENTILE_LABEL, latencyUnit.name));
        summary.append("| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (SnippetResult snippet : snippets) {
            summary.append(String.format(Locale.US, "| `%s` | %s | %s | %s | %s | %s | %s | %s |\n",
                    displayName(snippet.name),
                    formatCompact(snippet.clojure.throughputOpsPerSec),
                    formatCompact(snippet.cloffle.throughputOpsPerSec),
                    formatRatio(snippet.cloffle.throughputOpsPerSec, snippet.clojure.throughputOpsPerSec),
                    formatLatencyIn(snippet.clojure.tailNs(), latencyUnit),
                    formatLatencyIn(snippet.cloffle.tailNs(), latencyUnit),
                    formatAlloc(snippet.clojure.gcAllocBytesPerOp),
                    formatAlloc(snippet.cloffle.gcAllocBytesPerOp)));
        }
        return summary.toString().trim();
    }

    private static final class LatencyUnit {
        final String name;
        final double divisor;

        LatencyUnit(String name, double divisor) {
            this.name = name;
            this.divisor = divisor;
        }
    }

    private static LatencyUnit pickLatencyUnit(double maxNs) {
        if (maxNs >= 1_000_000.0) {
            return new LatencyUnit("ms", 1_000_000.0);
        }
        if (maxNs >= 1000.0) {
            return new LatencyUnit("µs", 1000.0);
        }
        return new LatencyUnit("ns", 1.0);
    }

    private static String formatLatencyIn(double ns, LatencyUnit unit) {
        double value = ns / unit.divisor;
        if ("ns".equals(unit.name)) {
            return String.format(Locale.US, "%.0f", value);
        }
        return String.format(Locale.US, "%.2f", value);
    }

    /** Compact SI throughput: 195.7M, 1.54M, 3.61K. */
    private static String formatCompact(double value) {
        if (value < 0) {
            return "-" + formatCompact(-value);
        }
        if (value >= 1_000_000_000.0) {
            return formatSi(value / 1_000_000_000.0) + "G";
        }
        if (value >= 1_000_000.0) {
            return formatSi(value / 1_000_000.0) + "M";
        }
        if (value >= 1_000.0) {
            return formatSi(value / 1_000.0) + "K";
        }
        return formatSi(value);
    }

    private static String formatSi(double scaled) {
        if (scaled >= 100.0) {
            return String.format(Locale.US, "%.0f", scaled);
        }
        if (scaled >= 10.0) {
            return String.format(Locale.US, "%.1f", scaled);
        }
        return String.format(Locale.US, "%.2f", scaled);
    }

    private static String formatAlloc(double bytesPerOp) {
        if (Math.abs(bytesPerOp - Math.rint(bytesPerOp)) < 0.05) {
            return String.format(Locale.US, "%,.0f", bytesPerOp);
        }
        return String.format(Locale.US, "%,.1f", bytesPerOp);
    }

    /** Cloffle ÷ Clojure as a plain number, or "-" when the denominator is ~0. */
    private static String formatRatio(double cloffleValue, double clojureValue) {
        if (Math.abs(clojureValue) <= 0.001) {
            return "-";
        }
        return String.format(Locale.US, "%.2f", cloffleValue / clojureValue);
    }

    private static String formatRatioWithX(double cloffleValue, double clojureValue) {
        String ratio = formatRatio(cloffleValue, clojureValue);
        if ("-".equals(ratio)) {
            return "-";
        }
        return ratio + "x";
    }
}
