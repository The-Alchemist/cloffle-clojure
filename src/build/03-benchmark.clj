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
      :javac-opts (into ["--release" "25" "-encoding" "UTF-8"
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
   "-Djmh.blackhole.mode=COMPILER"])

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
   Invoke: clj -T:build run-benchmarks :args '[\"regex\"]'
   :direct-linking — Cloffle guest compile only (`-Dcloffle.bench.directLinking=…`; default false)."
  [{:keys [args out err compile direct-linking]
    :or {args [] out :inherit err :inherit compile true direct-linking false}}]
  (when compile
    (compile-benchmarks nil))
  (let [basis @basis-benchmark
        cp (into [benchmark-class-dir class-dir fork-clojure-sources] (runtime-classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)
        _ (out (str "  :direct-linking → " direct-linking))
        args (concat (test-jvm-opts)
                     (cloffle-bench-direct-linking-jvm-flags direct-linking)
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

(defn compare-performance
  "Run JMH comparison between Clojure and Cloffle for a code snippet and write a .md report.
   Invoke: clj -T:build compare-performance :code '(assoc {:a 1 :b 2} :c 3)' :output 'comparison.md'
   Ad-hoc :code/:file is preflighted on stock Clojure and Cloffle — use vector literals, not multi-arg RT/vector.
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
     :forks                Number of JMH forks per benchmark (default: 1; use 3 for accept/reject)
     :names                Comma-separated built-in snippet ids (suite mode only; default: full catalog)
     :direct-linking       Cloffle guest compile only (`-Dcloffle.bench.directLinking=…`; default true)"
  [opts]
  (compile-benchmarks nil)
  (let [opts (merge {:direct-linking true} opts)
        basis @basis-benchmark
        cp (into [benchmark-class-dir class-dir fork-clojure-sources] (runtime-classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)
        cli-args (cond-> []
                   (:code opts) (conj "--code" (str (:code opts)))
                   (:file opts) (conj "--file" (str (:file opts)))
                   (:names opts) (conj "--names"
                                     (if (sequential? (:names opts))
                                       (clojure.string/join "," (map str (:names opts)))
                                       (str (:names opts))))
                   (:output opts) (conj "--output" (str (:output opts)))
                   (:warmup opts) (conj "--warmup" (str (:warmup opts)))
                   (:iterations opts) (conj "--iterations" (str (:iterations opts)))
                   (:warmup-time opts) (conj "--warmup-time" (str (:warmup-time opts)))
                   (:measurement-time opts) (conj "--measurement-time" (str (:measurement-time opts)))
                   (:compile-immediately opts) (conj "--compile-immediately")
                   (:forks opts) (conj "--forks" (str (:forks opts)))
                   true (conj "--direct-linking" (str (:direct-linking opts))))
        _ (out (str "  :direct-linking → " (:direct-linking opts)))
        java-args (concat (test-jvm-opts)
                          (cloffle-bench-direct-linking-jvm-flags (:direct-linking opts))
                          jmh-system-opts
                          [(truffle-log-file-opt)
                           "-cp" cp-str
                           "net.javacrumbs.cloffle.benchmark.ComparePerformance"]
                          cli-args)
        argfile (write-java-argfile java-args)]
    (let [proc (b/process
                {:command-args ["java" argfile]
                 :out :inherit
                 :err :inherit})]
      (ensure-jvm-task-ok! "compare-performance" proc))))
