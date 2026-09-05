package net.javacrumbs.cloffle.benchmark;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared helpers for snippet benchmarks: snippet catalogs, code loading, and stock Clojure JARs.
 * Catalog bodies are the {@link KeywordMapBenchmark} guest examples specialized to zero-arg forms.
 */
public final class SnippetBenchmarkSupport {

    /** Loads {@code -Dcloffle.bench.snippet.file} / {@code -Dcloffle.bench.code}. */
    public static final String FILE = "__file__";

    public static final String CONSUME_ASSOC = "consume-assoc";
    public static final String ARRAY_MAP_LOOKUP = "array-map-lookup";
    public static final String KEYWORD_INVOKE = "keyword-invoke";
    public static final String NESTED_GET_IN = "nested-get-in";
    public static final String ASSOC_PIPELINE = "assoc-pipeline";
    public static final String EPHEMERAL_PIPELINE = "ephemeral-pipeline";
    public static final String EPHEMERAL_INSERT = "ephemeral-insert";
    public static final String EPHEMERAL_PROMOTE8 = "ephemeral-promote8";
    public static final String EPHEMERAL_DISSOC = "ephemeral-dissoc";
    public static final String TUPLE_DESTRUCTURE = "tuple-destructure";
    public static final String TUPLE2_TRANSFORM = "tuple2-transform";
    public static final String RING_RESPONSE = "ring-response";
    public static final String HICCUP_NORMALIZE = "hiccup-normalize";
    public static final String KWARGS_DESTRUCTURE = "kwargs-destructure";
    public static final String MIDDLEWARE_PIPELINE = "middleware-pipeline";
    public static final String COND_OPTION_PIPELINE = "cond-option-pipeline";
    public static final String EVENT_ENRICH = "event-enrich";
    public static final String EVENT_SANITIZE = "event-sanitize";

    /** JMH {@code @Param} values. Keep in the same order as {@link #CATALOG}. */
    public static final String[] SAMPLE_NAMES = {
            CONSUME_ASSOC,
            ARRAY_MAP_LOOKUP,
            KEYWORD_INVOKE,
            NESTED_GET_IN,
            ASSOC_PIPELINE,
            EPHEMERAL_PIPELINE,
            EPHEMERAL_INSERT,
            EPHEMERAL_PROMOTE8,
            EPHEMERAL_DISSOC,
            TUPLE_DESTRUCTURE,
            TUPLE2_TRANSFORM,
            RING_RESPONSE,
            HICCUP_NORMALIZE,
            KWARGS_DESTRUCTURE,
            MIDDLEWARE_PIPELINE,
            COND_OPTION_PIPELINE,
            EVENT_ENRICH,
            EVENT_SANITIZE
    };

    public static final String DEFAULT_CODE =
            "(let [m {:a 1, :b 2, :c 3}] (:a (assoc m :b 999)))";

    static final Map<String, String> CATALOG = new LinkedHashMap<>();

    static {
        CATALOG.put(CONSUME_ASSOC, DEFAULT_CODE);
        CATALOG.put(ARRAY_MAP_LOOKUP, "(get {:a 1 :b 2 :c 3} :b)");
        CATALOG.put(KEYWORD_INVOKE, "(:b {:a 1 :b 2 :c 3})");
        CATALOG.put(NESTED_GET_IN, "(get-in {:user {:profile {:name \"Alice\"}}} [:user :profile :name])");
        CATALOG.put(ASSOC_PIPELINE, "(get (assoc {:a 1 :b 2 :c 3} :status :active) :status)");
        CATALOG.put(EPHEMERAL_PIPELINE,
                "(let [m {:a \"initial\" :b 2 :c 3}]\n" +
                "  (:a (assoc m :a \"replacement\")))");
        CATALOG.put(EPHEMERAL_INSERT,
                "(let [m {:a 1 :b 2}\n" +
                "      m2 (assoc m :c 3)]\n" +
                "  (if (= (:a m2) 1)\n" +
                "    (:c m2)\n" +
                "    nil))");
        CATALOG.put(EPHEMERAL_PROMOTE8,
                "(let [m {:p0 0 :p1 1 :p2 2 :p3 3 :p4 4 :p5 5 :p6 6 :p7 7}\n" +
                "      m2 (assoc m :p8 3)]\n" +
                "  (if (= (:p0 m2) 0)\n" +
                "    (:p8 m2)\n" +
                "    nil))");
        CATALOG.put(EPHEMERAL_DISSOC,
                "(let [m {:a 1 :b 3 :c 3}\n" +
                "      m2 (dissoc m :b)]\n" +
                "  (if (= (:a m2) 1)\n" +
                "    (:c m2)\n" +
                "    nil))");
        CATALOG.put(TUPLE_DESTRUCTURE,
                "(let [[a b] [2 3]]\n" +
                "  (if (= a 2)\n" +
                "    b\n" +
                "    nil))");
        CATALOG.put(TUPLE2_TRANSFORM,
                "(let [[a b] [2 3]\n" +
                "      [c d] [b a]]\n" +
                "  c)");
        CATALOG.put(RING_RESPONSE,
                "(let [resp {:status 200 :headers {:content-type \"text/plain\"} :body \"ok\"}\n" +
                "      resp2 (assoc resp :headers (assoc (:headers resp) :server \"cloffle\"))\n" +
                "      resp3 (assoc resp2 :status 201)\n" +
                "      {:keys [status headers body]} resp3]\n" +
                "  (if (and (= status 201)\n" +
                "           (= (:server headers) \"cloffle\")\n" +
                "           (= (:content-type headers) \"text/plain\"))\n" +
                "    body\n" +
                "    nil))");
        CATALOG.put(HICCUP_NORMALIZE,
                "(let [tag-name \"a\"\n" +
                "      content-str \"click\"\n" +
                "      elem [tag-name {:class \"btn\" :href \"/home\"} content-str]\n" +
                "      t (nth elem 0)\n" +
                "      second-el (nth elem 1)\n" +
                "      attrs (if (instance? clojure.lang.IPersistentMap second-el) second-el nil)\n" +
                "      content (if (instance? clojure.lang.IPersistentMap second-el) (nth elem 2) second-el)\n" +
                "      norm [t attrs content]\n" +
                "      final-tag (nth norm 0)\n" +
                "      final-attrs (nth norm 1)\n" +
                "      final-content (nth norm 2)]\n" +
                "  (if (and (= final-tag tag-name)\n" +
                "           (= (:href final-attrs) \"/home\"))\n" +
                "    final-content\n" +
                "    nil))");
        CATALOG.put(KWARGS_DESTRUCTURE,
                "(let [opts {:method :post :timeout 500}\n" +
                "      {:keys [method timeout] :or {method :get timeout 1000}} opts]\n" +
                "  (if (= method :post) timeout 0))");
        CATALOG.put(MIDDLEWARE_PIPELINE,
                "(let [req {:uri \"/api/data\" :request-method :post :headers {:content-type \"application/json\"} :body \"test-payload\"}\n" +
                "      req2 (assoc req :params {:query \"search\"})\n" +
                "      req3 (assoc req2 :session {:user \"alice\"})\n" +
                "      {:keys [uri request-method headers params session body]} req3]\n" +
                "  (if (and (= request-method :post)\n" +
                "           (= (:user session) \"alice\")\n" +
                "           (= (:query params) \"search\")\n" +
                "           (= (:content-type headers) \"application/json\"))\n" +
                "    body\n" +
                "    nil))");
        CATALOG.put(COND_OPTION_PIPELINE,
                "(let [raw-timeout \"500\"\n" +
                "      opts (-> {}\n" +
                "               (cond-> true (assoc :id \"btn\"))\n" +
                "               (cond-> true (assoc :role \"primary\"))\n" +
                "               (cond-> true (assoc :href \"/submit\"))\n" +
                "               (cond-> raw-timeout (assoc :timeout raw-timeout)))\n" +
                "      {:keys [id role href timeout]} opts]\n" +
                "  (if (and (= id \"btn\")\n" +
                "           (= role \"primary\")\n" +
                "           (= href \"/submit\"))\n" +
                "    timeout\n" +
                "    nil))");
        CATALOG.put(EVENT_ENRICH,
                "(let [event {:id 101 :type :auth :user \"alice\" :tenant \"org-1\"\n" +
                "             :ip \"127.0.0.1\" :status :ok :timestamp 1700000000 :version 1}\n" +
                "      enriched (assoc event :payload \"ok\")\n" +
                "      {:keys [id status user payload]} enriched]\n" +
                "  (if (and (= id 101)\n" +
                "           (= status :ok)\n" +
                "           (= user \"alice\"))\n" +
                "    payload\n" +
                "    nil))");
        CATALOG.put(EVENT_SANITIZE,
                "(let [event {:id 101 :user \"alice\" :secret \"secret-token\" :temp 999 :status :ok}\n" +
                "      sanitized (-> event (dissoc :secret) (dissoc :temp))\n" +
                "      {:keys [id user secret temp status]} sanitized]\n" +
                "  (if (and (= id 101)\n" +
                "           (= status :ok)\n" +
                "           (= user \"alice\")\n" +
                "           (nil? secret)\n" +
                "           (nil? temp))\n" +
                "    id\n" +
                "    nil))");
    }

    private SnippetBenchmarkSupport() {}

    public static String codeFor(String name) {
        if (FILE.equals(name)) {
            return loadSnippetCode();
        }
        String code = CATALOG.get(name);
        if (code == null) {
            throw new IllegalArgumentException("Unknown snippet: " + name);
        }
        return code;
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
