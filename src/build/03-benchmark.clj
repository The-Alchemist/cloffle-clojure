;; Category: JMH benchmarks (`compile-benchmarks`, `run-benchmarks`, `compare-performance`).
(in-ns 'build)

(def benchmark-class-dir "target/benchmark-classes")

(def basis-benchmark
  (delay
   (b/create-basis
    {:project "deps.edn"
     :aliases [:benchmark]
     ;; Add truffle-dsl-processor manually if needed, or rely on :build alias?
     ;; It's safer to include it explicitly for annotation processing if needed.
     :extra {:deps {(symbol "org.graalvm.truffle/truffle-dsl-processor") {:mvn/version "25.3.4.1"}}}})))

(defn compile-benchmarks
  "Compile JMH sources under `src/benchmark/java` into `target/benchmark-classes`."
  [_]
  (compile-all nil)
  (b/delete {:path benchmark-class-dir})
  (let [basis @basis-benchmark
        cp (into [class-dir fork-clojure-sources] (runtime-classpath-roots basis))
        proc-path (clojure.string/join (System/getProperty "path.separator")
                                       (:classpath-roots basis))]
    (javac-in-process!
     {:src-dirs ["src/benchmark/java"]
      :class-dir benchmark-class-dir
      :classpath-roots cp
      :javac-opts (into ["--release" "17" "-encoding" "UTF-8"
                         "-processorpath" proc-path
                         "-s" benchmark-class-dir]
                        javac-quiet-opts)})))

(def truffle-jmh-log "target/truffle-jmh.log")

;; Shared with ComparePerformance.TEST_JVM_OPTS for JMH fork behavior.
;; -Djmh.blackhole.mode=COMPILER keeps compiler blackholes (JDK 17+ default via auto-detect)
;; without the long "auto-detected, use -Djmh.blackhole.autoDetect=false..." tip every fork.
;; Do not set only autoDetect=false: that falls back to FULL_DONTINLINE.
(def jmh-system-opts
  ["-Djmh.ignoreLock=true"
   "-Djmh.blackhole.mode=COMPILER"
   "-Dorg.simdjson.species=128"])

(defn- truffle-log-file-opt
  "Send Truffle engine logs to `truffle-jmh-log` instead of the console. Without this the
   default log handler writes to stderr, so its `--log.file` banner lands in the middle of
   JMH's `# Warmup Iteration 1:` line. JMH-only on purpose: `test-jvm-opts` is shared with
   REPLs and tests, which should keep showing engine/DAP messages."
  []
  (let [f (io/file truffle-jmh-log)]
    (io/make-parents f)
    (str "-Dpolyglot.log.file=" (.getAbsolutePath f))))

(defn run-benchmarks
  "Run JMH benchmarks.
   Invoke: clj -T:build run-benchmarks :args '[\"regex\"]'"
  [{:keys [args out err compile] :or {args [] out :inherit err :inherit compile true}}]
  (when compile
    (compile-benchmarks nil))
  (let [basis @basis-benchmark
        cp (into [benchmark-class-dir class-dir fork-clojure-sources] (runtime-classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)
        args (concat (test-jvm-opts)
                     jmh-system-opts
                     [(truffle-log-file-opt)
                      "-cp" cp-str
                      "org.openjdk.jmh.Main"]
                     (map str args))
        argfile (write-java-argfile args)]
    (b/process
     {:command-args ["java" argfile]
      :out out
      :err err})))

(defn run-json-parser-benchmarks
  "Run JSON parser JMH suites with a bounded scope (the full `JsonParser.*` regex is ~150+ benchmarks
   and often takes 20–40+ minutes once Truffle warms Cloffle guests).

   Profiles (keyword `:profile`, default `:smoke`):
     :smoke    — typed extract (GitHub bytes) + Jackson streaming; 2×1s warmup/measure; includes `-prof gc`
     :typed-pairs — five parity-checked fixtures (placeholder, jsonapi, github, twitter, popular-apis); 2×1s + `-prof gc`
     :cloffle  — `JsonParserCloffle*` + Jackson streaming baselines, quick JMH timings (~5–10 min)
     :fairness — :cloffle plus parse/lookup guests and Jackson/cloffle full-parse lookups (~10–15 min)
     :full     — all `JsonParser.*` with class-default 2×1s iterations (slow; use for publishable numbers)

   Pass extra JMH args via `:args` (appended after profile defaults; smoke already passes `-prof gc`).

   Invoke:
     clojure -T:build run-json-parser-benchmarks
     clojure -T:build run-json-parser-benchmarks :profile :typed-pairs
     clojure -T:build run-json-parser-benchmarks :profile :cloffle
     clojure -T:build run-json-parser-benchmarks :profile :full :compile false"
  [{:keys [profile args compile]
    :or {profile :smoke args [] compile true}}]
  (let [quick ["-wi" "1" "-i" "1" "-w" "500ms" "-r" "500ms" "-f" "1"]
        smoke-timing ["-wi" "2" "-i" "2" "-w" "1" "-r" "1" "-f" "1"]
        smoke (concat ["JsonParserCloffleExtractBenchmark.guestExtract"
                       "JsonParserJacksonStreamingBenchmark.jacksonStreamingGithubShapeMap"
                       "-p" "guest=guestTypedGithubBytes"
                       "-prof" "gc"]
                      smoke-timing)
        typed-pairs (concat ["JsonParserCloffleExtractBenchmark.guestExtract"
                             "JsonParserJacksonStreamingBenchmark.jacksonStreaming(Placeholder|Jsonapi|Github|TwitterFirst|PopularApis)ShapeMap"
                             "-p" "guest=guestTypedPlaceholderBytes"
                             "-p" "guest=guestTypedJsonapiBytes"
                             "-p" "guest=guestTypedGithubBytes"
                             "-p" "guest=guestTypedTwitterFirstBytes"
                             "-p" "guest=guestTypedPopularApisBytes"
                             "-prof" "gc"]
                            smoke-timing)
        jmh-args (case profile
                   :smoke (concat smoke args)
                   :typed-pairs (concat typed-pairs args)
                   :cloffle (concat ["JsonParserCloffle.*|JsonParserJacksonStreamingBenchmark"]
                                    quick args)
                   :fairness (concat ["JsonParserCloffle.*|JsonParserJacksonStreamingBenchmark|JsonParserBenchmark\\.(guest|jacksonParseLookup|cloffleParseLookup)"]
                                     quick args)
                   :full (concat ["JsonParser.*"] args)
                   (throw (ex-info "Unknown :profile for run-json-parser-benchmarks"
                                   {:profile profile
                                    :valid [:smoke :typed-pairs :cloffle :fairness :full]})))]
    (run-benchmarks {:args jmh-args :compile compile})))

(defn compare-performance
  "Run JMH comparison between Clojure and Cloffle for a code snippet and write a .md report.
   Invoke: clj -T:build compare-performance :code '(assoc {:a 1 :b 2} :c 3)' :output 'comparison.md'
           clj -T:build compare-performance :output 'target/test-consume.md'
   Options:
     :code                 Clojure code string to benchmark (omit to run KeywordMapBenchmark guest samples)
     :file                 Path to .clj file containing code to benchmark
     :output               Path to output .md file (default: benchmark-results.md)
     :warmup               Number of warmup iterations (default: 2)
     :iterations           Number of measurement iterations (default: 3)
     :warmup-time          Warmup seconds per iteration (default: 1)
     :measurement-time     Measurement seconds per iteration (default: 1)
     :compile-immediately  Force synchronous Truffle compilation on first call (default: false)
     :forks                Number of JMH forks per benchmark (default: 1; use 3 for accept/reject)"
  [opts]
  (compile-benchmarks nil)
  (let [basis @basis-benchmark
        cp (into [benchmark-class-dir class-dir fork-clojure-sources] (runtime-classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)
        cli-args (cond-> []
                   (:code opts) (conj "--code" (str (:code opts)))
                   (:file opts) (conj "--file" (str (:file opts)))
                   (:output opts) (conj "--output" (str (:output opts)))
                   (:warmup opts) (conj "--warmup" (str (:warmup opts)))
                   (:iterations opts) (conj "--iterations" (str (:iterations opts)))
                   (:warmup-time opts) (conj "--warmup-time" (str (:warmup-time opts)))
                   (:measurement-time opts) (conj "--measurement-time" (str (:measurement-time opts)))
                   (:compile-immediately opts) (conj "--compile-immediately")
                   (:forks opts) (conj "--forks" (str (:forks opts))))
        java-args (concat (test-jvm-opts)
                          jmh-system-opts
                          [(truffle-log-file-opt)
                           "-cp" cp-str
                           "net.javacrumbs.cloffle.benchmark.ComparePerformance"]
                          cli-args)
        argfile (write-java-argfile java-args)]
    (b/process
     {:command-args ["java" argfile]
      :out :inherit
      :err :inherit})))
