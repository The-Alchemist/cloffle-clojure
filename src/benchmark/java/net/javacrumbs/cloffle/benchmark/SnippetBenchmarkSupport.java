package net.javacrumbs.cloffle.benchmark;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

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
    public static final String INTO_MAP_SMALL = "into-map-small";
    public static final String MAP_SMALL_VECTOR = "map-small-vector";
    public static final String MAP_FIRST_SMALL = "map-first-small";
    public static final String MAP_FIRST_ONE = "map-first-one";
    public static final String MAP_IDENTITY_VECTOR = "map-identity-vector";
    public static final String MAPV_SMALL_VECTOR = "mapv-small-vector";
    public static final String LADDER_IDENTITY_KEYWORD = "ladder-identity-keyword";
    public static final String LADDER_NTH5_KEYWORDS = "ladder-nth5-keywords";
    public static final String LADDER_FIRST5_KEYWORDS = "ladder-first5-keywords";
    public static final String LADDER_SEQ_FIRST5_KEYWORDS = "ladder-seq-first5-keywords";
    public static final String RING_RESPONSE = "ring-response";
    public static final String HICCUP_NORMALIZE = "hiccup-normalize";
    public static final String HICCUP_NORMALIZE_SMALL = "hiccup-normalize-small";
    public static final String NORM_TUPLE_NTH = "norm-tuple-nth";
    public static final String KWARGS_DESTRUCTURE = "kwargs-destructure";
    public static final String MIDDLEWARE_PIPELINE = "middleware-pipeline";
    public static final String COND_OPTION_PIPELINE = "cond-option-pipeline";
    public static final String EVENT_ENRICH = "event-enrich";
    public static final String EVENT_SANITIZE = "event-sanitize";
    public static final String FIXED_STR2 = "fixed-str2";
    public static final String RT_GET_LOOKUP = "rt-get-lookup";
    public static final String CROSS_CALL_MAP = "cross-call-map";
    public static final String COND_SHAPE_POLY = "cond-shape-poly";
    public static final String PRIM_LITERAL_ADD = "prim-literal-add";
    public static final String PRIM_HINTED_LOCALS = "prim-hinted-locals";
    public static final String PRIM_LONG_LOOP = "prim-long-loop";
    public static final String PRIM_DOUBLE_LOOP = "prim-double-loop";
    public static final String PRIM_COUNT = "prim-count";
    public static final String PRIM_NTH = "prim-nth";
    public static final String PRIM_JAVA_INT = "prim-java-int";
    public static final String PRIM_OBJECT_BOUNDARY = "prim-object-boundary";

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
            INTO_MAP_SMALL,
            MAP_SMALL_VECTOR,
            MAP_FIRST_SMALL,
            MAP_FIRST_ONE,
            MAP_IDENTITY_VECTOR,
            MAPV_SMALL_VECTOR,
            LADDER_IDENTITY_KEYWORD,
            LADDER_NTH5_KEYWORDS,
            LADDER_FIRST5_KEYWORDS,
            LADDER_SEQ_FIRST5_KEYWORDS,
            RING_RESPONSE,
            HICCUP_NORMALIZE,
            HICCUP_NORMALIZE_SMALL,
            NORM_TUPLE_NTH,
            KWARGS_DESTRUCTURE,
            MIDDLEWARE_PIPELINE,
            COND_OPTION_PIPELINE,
            EVENT_ENRICH,
            EVENT_SANITIZE,
            FIXED_STR2,
            CROSS_CALL_MAP,
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
}
