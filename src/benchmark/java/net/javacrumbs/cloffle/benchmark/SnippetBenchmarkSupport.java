package net.javacrumbs.cloffle.benchmark;

import clojure.lang.IFn;
import clojure.lang.RT;
import org.graalvm.polyglot.Context;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Shared helpers for snippet benchmarks: snippet catalogs, code loading, and stock Clojure JARs.
 * The catalog includes representative collection pipelines and fixed-arity string operations.
 */
public final class SnippetBenchmarkSupport {

    /** Loads {@code -Dcloffle.bench.snippet.file} / {@code -Dcloffle.bench.code}. */
    public static final String FILE = "__file__";

    public static final String CONSUME_ASSOC = "consume-assoc";
    public static final String CONSUME_ASSOC_NO_LET = "consume-assoc-no-let";
    public static final String ASSOC_ONLY = "assoc-only";
    public static final String ASSOC_RETURN_NIL = "assoc-return-nil";
    public static final String ARRAY_MAP_LOOKUP = "array-map-lookup";
    public static final String HASH_MAP_LOOKUP = "hash-map-lookup";
    public static final String SHAPE_MAP16_LOOKUP = "shape-map16-lookup";
    public static final String KEYWORD_INVOKE = "keyword-invoke";
    public static final String NESTED_GET_IN = "nested-get-in";
    public static final String ASSOC_PIPELINE = "assoc-pipeline";
    public static final String EPHEMERAL_PIPELINE = "ephemeral-pipeline";
    public static final String EPHEMERAL_INSERT = "ephemeral-insert";
    public static final String EPHEMERAL_PROMOTE8 = "ephemeral-promote8";
    public static final String EPHEMERAL_DISSOC = "ephemeral-dissoc";
    public static final String CONSUME_CONJ_VECTOR = "consume-conj-vector";
    public static final String CONSUME_CONJ_MAP = "consume-conj-map";
    public static final String CONSUME_CONJ_LIST = "consume-conj-list";
    public static final String CONJ_CHAIN = "conj-chain";
    public static final String TUPLE_DESTRUCTURE = "tuple-destructure";
    public static final String LAZY_SEQ_FIRST = "lazy-seq-first";
    public static final String LAZY_SEQ_VEC_FIRST = "lazy-seq-vec-first";
    public static final String TUPLE2_TRANSFORM = "tuple2-transform";
    public static final String INTO_EMPTY_TUPLE2 = "into-empty-tuple2";
    public static final String INTO_EMPTY_TUPLE2_DYNAMIC = "into-empty-tuple2-dynamic";
    public static final String INTO_MAP_SMALL = "into-map-small";
    public static final String MAP_FIRST_STATUS = "map-first-status";
    public static final String MAP_SMALL_RECORDS = "map-small-records";
    public static final String INTO_MAP_IDS = "into-map-ids";
    public static final String INTO_MAP_IDS_DYNAMIC = "into-map-ids-dynamic";
    public static final String MAP_FIRST_STATUS_LIST = "map-first-status-list";
    public static final String MAP_FIRST_STATUS_SEQ = "map-first-status-seq";
    public static final String MAP_FIRST_STATUS_DYNAMIC = "map-first-status-dynamic";
    public static final String MAP_FILTER_STATUS_DYNAMIC = "map-filter-status-dynamic";
    public static final String MAP_FILTER_STATUS_TRANSduce = "map-filter-status-transduce";
    public static final String ROW_FIRST_FIELD_DYNAMIC = "row-first-field-dynamic";
    public static final String ROWS_COUNT_DYNAMIC = "rows-count-dynamic";
    public static final String MAP_FIELD_ROWS = "map-field-rows";
    public static final String MAP_FIELD_ROWS_RUNTIME = "map-field-rows-runtime";
    public static final String MAP_FIELD_ROWS_NTH = "map-field-rows-nth";
    public static final String MAP_FIELD_ROWS_SEQ = "map-field-rows-seq";
    public static final String FILTER_ROWS_DYNAMIC = "filter-rows-dynamic";
    public static final String FILTER_ROWS_COUNT_DYNAMIC = "filter-rows-count-dynamic";
    public static final String FILTER_AFTER_MAP_ID_DYNAMIC = "filter-after-map-id-dynamic";
    public static final String FILTER_AFTER_MAP_IDENTITY_DYNAMIC = "filter-after-map-identity-dynamic";
    public static final String MAP_SMALL_VECTOR = "map-small-vector";
    public static final String MAP_FIRST_SMALL = "map-first-small";
    public static final String MAP_FIRST_ONE = "map-first-one";
    public static final String MAP_IDENTITY_VECTOR = "map-identity-vector";
    public static final String MAPV_SMALL_VECTOR = "mapv-small-vector";
    public static final String LADDER_NTH5_KEYWORDS = "ladder-nth5-keywords";
    public static final String LADDER_FIRST5_KEYWORDS = "ladder-first5-keywords";
    public static final String LADDER_SEQ_FIRST5_KEYWORDS = "ladder-seq-first5-keywords";
    public static final String RING_RESPONSE = "ring-response";
    public static final String HICCUP_NORMALIZE = "hiccup-normalize";
    public static final String HICCUP_NORMALIZE_SMALL = "hiccup-normalize-small";
    public static final String IDENTICAL_NIL_DYNAMIC = "identical-nil-dynamic";
    public static final String NORM_TUPLE_NTH = "norm-tuple-nth";
    public static final String KWARGS_DESTRUCTURE = "kwargs-destructure";
    public static final String MIDDLEWARE_PIPELINE = "middleware-pipeline";
    public static final String COND_OPTION_PIPELINE = "cond-option-pipeline";
    public static final String EVENT_ENRICH = "event-enrich";
    public static final String EVENT_SANITIZE = "event-sanitize";
    public static final String MERGE_LITERAL = "merge-literal";
    public static final String MERGE_RUNTIME = "merge-runtime";
    public static final String FIXED_STR2 = "fixed-str2";
    public static final String RT_GET_LOOKUP = "rt-get-lookup";
    public static final String CROSS_CALL_MAP = "cross-call-map";
    public static final String CROSS_CALL_NESTED_MAPS = "cross-call-nested-maps";
    public static final String CROSS_CALL_NESTED_LARGE = "cross-call-nested-large";
    public static final String CROSS_CALL_NESTED_DEEP = "cross-call-nested-deep";
    public static final String CROSS_CALL_NESTED_ROWS = "cross-call-nested-rows";
    public static final String CROSS_CALL_JSONAPI = "cross-call-jsonapi";
    public static final String CROSS_CALL_DEFN_PIPELINE = "cross-call-defn-pipeline";
    public static final String CROSS_CALL_VALIDATION_PIPELINE = "cross-call-validation-pipeline";
    public static final String CROSS_CALL_VALIDATION_PIPELINE_THREADED =
            "cross-call-validation-pipeline-threaded";
    public static final String COND_SHAPE_POLY = "cond-shape-poly";
    public static final String PRIM_LITERAL_ADD = "prim-literal-add";
    public static final String PRIM_HINTED_LOCALS = "prim-hinted-locals";
    public static final String PRIM_LONG_LOOP = "prim-long-loop";
    public static final String PRIM_DOUBLE_LOOP = "prim-double-loop";
    public static final String PRIM_COUNT = "prim-count";
    public static final String PRIM_NTH = "prim-nth";
    public static final String PRIM_JAVA_INT = "prim-java-int";
    public static final String PRIM_OBJECT_BOUNDARY = "prim-object-boundary";

    /** Zero-arg entry point invoked by JMH after namespace load. */
    public static final String BENCH_FN = "bench";

    /**
     * When true, Cloffle guest snippet compile enables {@code :direct-linking} (stock Clojure leg unchanged).
     * Set on JMH fork JVMs via {@code compare-performance} / {@code run-benchmarks}.
     */
    public static final String CLOFFLE_DIRECT_LINKING_PROP = "cloffle.bench.directLinking";

    private static final String ENABLE_CLOFFLE_DIRECT_LINKING =
            "(alter-var-root #'clojure.core/*compiler-options*"
                    + " (fn [o] (assoc (or o {}) :direct-linking true)))";

    private static final ThreadLocal<IFn> CAPTURED_GUEST_FN = new ThreadLocal<>();

    /** Namespace for catalog snippet {@code name} or {@link #FILE}. */
    public static String namespaceFor(String sampleName) {
        if (FILE.equals(sampleName)) {
            return "bench.snippet.file";
        }
        return "bench.snippet." + sampleName;
    }

    /**
     * Returns snippet source ready for {@code Compiler/load} / Cloffle eval: either the raw
     * resource (already declares {@code (ns …)} and {@code (defn bench [])}) or a wrapped
     * ad-hoc expression for {@link #FILE} / inline code.
     */
    public static String namespacedSource(String sampleName, String rawSource) {
        String trimmed = rawSource == null ? "" : rawSource.trim();
        if (trimmed.startsWith("(ns ")) {
            return trimmed;
        }
        String ns = namespaceFor(sampleName);
        return "(ns " + ns + ")\n\n(defn bench []\n" + indentLines(trimmed, 2) + ")\n";
    }

    private static String indentLines(String code, int spaces) {
        String pad = " ".repeat(spaces);
        StringBuilder sb = new StringBuilder();
        for (String line : code.split("\\R", -1)) {
            if (line.isEmpty()) {
                sb.append('\n');
            } else {
                sb.append(pad).append(line).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    /** JMH {@code @Param} values. Keep in the same order as {@code snippets/*.clj}. */
    public static final String[] SAMPLE_NAMES = {
            CONSUME_ASSOC,
            CONSUME_ASSOC_NO_LET,
            ASSOC_ONLY,
            ASSOC_RETURN_NIL,
            ARRAY_MAP_LOOKUP,
            HASH_MAP_LOOKUP,
            SHAPE_MAP16_LOOKUP,
            RT_GET_LOOKUP,
            KEYWORD_INVOKE,
            NESTED_GET_IN,
            ASSOC_PIPELINE,
            EPHEMERAL_PIPELINE,
            EPHEMERAL_INSERT,
            EPHEMERAL_PROMOTE8,
            EPHEMERAL_DISSOC,
            CONSUME_CONJ_VECTOR,
            CONSUME_CONJ_MAP,
            CONSUME_CONJ_LIST,
            CONJ_CHAIN,
            TUPLE_DESTRUCTURE,
            LAZY_SEQ_FIRST,
            LAZY_SEQ_VEC_FIRST,
            TUPLE2_TRANSFORM,
            INTO_EMPTY_TUPLE2,
            INTO_EMPTY_TUPLE2_DYNAMIC,
            INTO_MAP_SMALL,
            MAP_FIRST_STATUS,
            MAP_SMALL_RECORDS,
            INTO_MAP_IDS,
            INTO_MAP_IDS_DYNAMIC,
            MAP_FIRST_STATUS_LIST,
            MAP_FIRST_STATUS_SEQ,
            MAP_FIRST_STATUS_DYNAMIC,
            MAP_FILTER_STATUS_DYNAMIC,
            MAP_FILTER_STATUS_TRANSduce,
            ROW_FIRST_FIELD_DYNAMIC,
            ROWS_COUNT_DYNAMIC,
            MAP_FIELD_ROWS,
            MAP_FIELD_ROWS_RUNTIME,
            MAP_FIELD_ROWS_NTH,
            MAP_FIELD_ROWS_SEQ,
            FILTER_ROWS_DYNAMIC,
            FILTER_ROWS_COUNT_DYNAMIC,
            FILTER_AFTER_MAP_ID_DYNAMIC,
            FILTER_AFTER_MAP_IDENTITY_DYNAMIC,
            MAP_SMALL_VECTOR,
            MAP_FIRST_SMALL,
            MAP_FIRST_ONE,
            MAP_IDENTITY_VECTOR,
            MAPV_SMALL_VECTOR,
            LADDER_NTH5_KEYWORDS,
            LADDER_FIRST5_KEYWORDS,
            LADDER_SEQ_FIRST5_KEYWORDS,
            RING_RESPONSE,
            HICCUP_NORMALIZE,
            HICCUP_NORMALIZE_SMALL,
            IDENTICAL_NIL_DYNAMIC,
            NORM_TUPLE_NTH,
            KWARGS_DESTRUCTURE,
            MIDDLEWARE_PIPELINE,
            COND_OPTION_PIPELINE,
            EVENT_ENRICH,
            EVENT_SANITIZE,
            MERGE_LITERAL,
            MERGE_RUNTIME,
            FIXED_STR2,
            CROSS_CALL_MAP,
            CROSS_CALL_NESTED_MAPS,
            CROSS_CALL_NESTED_LARGE,
            CROSS_CALL_NESTED_DEEP,
            CROSS_CALL_NESTED_ROWS,
            CROSS_CALL_JSONAPI,
            CROSS_CALL_DEFN_PIPELINE,
            CROSS_CALL_VALIDATION_PIPELINE,
            CROSS_CALL_VALIDATION_PIPELINE_THREADED,
            COND_SHAPE_POLY,
            PRIM_LITERAL_ADD,
            PRIM_HINTED_LOCALS,
            PRIM_LONG_LOOP,
            PRIM_DOUBLE_LOOP,
            PRIM_COUNT,
            PRIM_NTH,
            PRIM_JAVA_INT,
            PRIM_OBJECT_BOUNDARY
    };

    public static final String DEFAULT_CODE = codeFor(CONSUME_ASSOC);

    private SnippetBenchmarkSupport() {}

    public static String codeFor(String name) {
        if (FILE.equals(name)) {
            return loadSnippetCode();
        }
        if (!isKnownSample(name)) {
            throw new IllegalArgumentException("Unknown snippet: " + name);
        }
        return ClojureClasspathResources.read("snippets/" + name + ".clj");
    }

    private static boolean isKnownSample(String name) {
        for (String sample : SAMPLE_NAMES) {
            if (sample.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Loads the Clojure code to benchmark from system properties.
     * Priority:
     * 1. -Dcloffle.bench.snippet.file=<path>
     * 2. -Dcloffle.bench.code=<code>
     * 3. {@link #DEFAULT_CODE}
     */
    public static String loadSnippetCode() {
        String filePath = System.getProperty("cloffle.bench.snippet.file");
        if (filePath != null && !filePath.trim().isEmpty()) {
            File file = new File(filePath.trim());
            if (file.exists() && file.isFile()) {
                try {
                    return Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read snippet file: " + filePath, e);
                }
            }
        }

        String inlineCode = System.getProperty("cloffle.bench.code");
        if (inlineCode != null && !inlineCode.trim().isEmpty()) {
            return inlineCode.trim();
        }

        return DEFAULT_CODE;
    }

    /**
     * Locates official stock Clojure JARs (clojure, spec.alpha, core.specs.alpha)
     * from ~/.m2/repository or configured system properties.
     */
    public static List<URL> resolveStockClojureJars() {
        List<URL> urls = new ArrayList<>();

        String customJars = System.getProperty("clojure.bench.jars");
        if (customJars != null && !customJars.trim().isEmpty()) {
            for (String part : customJars.split(File.pathSeparator)) {
                File f = new File(part.trim());
                if (f.exists()) {
                    try {
                        urls.add(f.toURI().toURL());
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
            if (!urls.isEmpty()) {
                return urls;
            }
        }

        String userHome = System.getProperty("user.home");
        File m2Repo = new File(userHome, ".m2/repository");

        File clojureJar = findLatestJar(new File(m2Repo, "org/clojure/clojure"), "clojure-");
        File specJar = findLatestJar(new File(m2Repo, "org/clojure/spec.alpha"), "spec.alpha-");
        File coreSpecsJar = findLatestJar(new File(m2Repo, "org/clojure/core.specs.alpha"), "core.specs.alpha-");

        if (clojureJar != null) addUrl(urls, clojureJar);
        if (specJar != null) addUrl(urls, specJar);
        if (coreSpecsJar != null) addUrl(urls, coreSpecsJar);

        return urls;
    }

    private static void addUrl(List<URL> list, File file) {
        try {
            list.add(file.toURI().toURL());
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert file to URL: " + file, e);
        }
    }

    private static File findLatestJar(File dir, String prefix) {
        if (!dir.exists() || !dir.isDirectory()) {
            return null;
        }
        File[] versionDirs = dir.listFiles(File::isDirectory);
        if (versionDirs == null || versionDirs.length == 0) {
            return null;
        }
        // Prefer stable versions like 1.12.0 or newest
        File bestFile = null;
        for (File vDir : versionDirs) {
            File[] jars = vDir.listFiles((d, name) -> name.startsWith(prefix) && name.endsWith(".jar") && !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar"));
            if (jars != null && jars.length > 0) {
                for (File jar : jars) {
                    if (jar.getName().contains("1.12.0")) {
                        return jar;
                    }
                    bestFile = jar;
                }
            }
        }
        return bestFile;
    }

    /**
     * Creates an isolated URLClassLoader for stock Clojure.
     */
    public static URLClassLoader createStockClojureClassLoader() {
        List<URL> urls = resolveStockClojureJars();
        if (urls.isEmpty()) {
            throw new IllegalStateException("Could not find official stock Clojure JARs in ~/.m2/repository. " +
                    "Please specify via -Dclojure.bench.jars=path/to/clojure.jar" + File.pathSeparator + "path/to/spec.alpha.jar");
        }
        return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
    }

    /**
     * Setup-only bridge; guest code hands its JVM closure to the benchmark without a Polyglot Value on the hot path.
     */
    public static Object captureGuestFn(Object fn) {
        CAPTURED_GUEST_FN.set((IFn) fn);
        return fn;
    }

    /**
     * Compile-check one catalog / {@link #FILE} snippet on stock Clojure and Cloffle before JMH forks.
     * Catalog snippets must compile on both legs: prefer {@code [:a :b]} or {@code (vector …)} over
     * multi-arg {@code (RT/vector …)} (stock {@code clojure.jar} does not accept the latter).
     */
    public static void preflightSnippet(String sampleName, boolean cloffleDirectLinking) {
        String snippetCode = codeFor(sampleName);
        String ns = namespaceFor(sampleName);
        String source = namespacedSource(sampleName, snippetCode);
        try {
            openStockBenchSupplier(ns, source);
        } catch (Exception e) {
            throw preflightFailed(sampleName, "clojure", e);
        }
        try (CloffleBenchSession session = openCloffleBench(ns, source, cloffleDirectLinking)) {
            session.fn.invoke();
        } catch (Exception e) {
            throw preflightFailed(sampleName, "cloffle", e);
        }
    }

    public static void preflightSnippets(String[] sampleNames, boolean cloffleDirectLinking) {
        for (String name : sampleNames) {
            preflightSnippet(name, cloffleDirectLinking);
        }
    }

    private static IllegalStateException preflightFailed(String name, String leg, Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return new IllegalStateException(
                "Snippet preflight failed [" + name + " / " + leg + "]: " + root.getMessage(), e);
    }

    public static Supplier<?> openStockBenchSupplier(String snippetNs, String source) throws Exception {
        ClassLoader prevCl = Thread.currentThread().getContextClassLoader();
        try {
            URLClassLoader cl = createStockClojureClassLoader();
            Thread.currentThread().setContextClassLoader(cl);

            Class<?> rtClass = cl.loadClass("clojure.lang.RT");
            rtClass.getMethod("init").invoke(null);

            Class<?> compilerClass = cl.loadClass("clojure.lang.Compiler");
            Method loadMethod = compilerClass.getMethod("load", Reader.class);
            loadMethod.invoke(null, new StringReader(source));

            Object benchVar = rtClass.getMethod("var", String.class, String.class)
                    .invoke(null, snippetNs, BENCH_FN);
            Method invokeMethod = benchVar.getClass().getMethod("invoke");
            MethodHandle mh = MethodHandles.lookup().unreflect(invokeMethod).bindTo(benchVar);
            return MethodHandleProxies.asInterfaceInstance(Supplier.class, mh);
        } finally {
            Thread.currentThread().setContextClassLoader(prevCl);
        }
    }

    public static final class CloffleBenchSession implements AutoCloseable {
        public final Context context;
        public final IFn fn;

        CloffleBenchSession(Context context, IFn fn) {
            this.context = context;
            this.fn = fn;
        }

        @Override
        public void close() {
            if (context != null) {
                context.leave();
                context.close();
            }
        }
    }

    public static CloffleBenchSession openCloffleBench(String snippetNs, String source, boolean directLinking) {
        RT.init();
        Context.Builder builder = Context.newBuilder("cloffle")
                .allowAllAccess(true)
                .option("engine.BackgroundCompilation", "false");
        if (Boolean.getBoolean("cloffle.bench.throwOnFailure")) {
            builder.option("engine.CompilationFailureAction", "Throw");
        }
        if (Boolean.getBoolean("cloffle.bench.compileImmediately")) {
            builder.option("engine.CompileImmediately", "true");
        }
        Context context = builder.build();
        try {
            if (directLinking || Boolean.getBoolean(CLOFFLE_DIRECT_LINKING_PROP)) {
                context.eval("cloffle", ENABLE_CLOFFLE_DIRECT_LINKING);
            }
            context.eval("cloffle", source);
            String captureForm = "(net.javacrumbs.cloffle.benchmark.SnippetBenchmarkSupport/captureGuestFn @#'"
                    + snippetNs + "/" + BENCH_FN + "))";
            context.eval("cloffle", captureForm);
            IFn fn = CAPTURED_GUEST_FN.get();
            CAPTURED_GUEST_FN.remove();
            if (fn == null) {
                throw new IllegalStateException("Guest snippet fn was not captured");
            }
            context.enter();
            return new CloffleBenchSession(context, fn);
        } catch (RuntimeException | Error e) {
            context.close();
            throw e;
        } catch (Exception e) {
            context.close();
            throw new RuntimeException(e);
        }
    }
}
