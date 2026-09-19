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
     :typed-pairs — five parity-checked Cloffle/Jackson fixture pairs; 3×1s + `-prof gc`
     :host-typed — the same five typed projects through the pure-Java host path; 3×1s + `-prof gc`
     :staged-alloc — scan / +decode / +materialize ladder attributing the guest B/op budget; 3×1s + `-prof gc`
     :cloffle  — `JsonParserCloffle*` + Jackson streaming baselines, quick JMH timings (~5–10 min)
     :fairness — :cloffle plus parse/lookup guests and Jackson/cloffle full-parse lookups (~10–15 min)
     :full     — all `JsonParser.*` with class-default 2×1s iterations (slow; use for publishable numbers)

   Pass extra JMH args via `:args` (appended after profile defaults; smoke already passes `-prof gc`).

   Invoke:
     clojure -T:build run-json-parser-benchmarks
     clojure -T:build run-json-parser-benchmarks :profile :typed-pairs
     clojure -T:build run-json-parser-benchmarks :profile :host-typed
     clojure -T:build run-json-parser-benchmarks :profile :cloffle
     clojure -T:build run-json-parser-benchmarks :profile :full :compile false"
  [{:keys [profile args compile]
    :or {profile :smoke args [] compile true}}]
  (let [quick ["-wi" "1" "-i" "1" "-w" "500ms" "-r" "500ms" "-f" "1"]
        smoke-timing ["-wi" "2" "-i" "2" "-w" "1" "-r" "1" "-f" "1"]
        typed-pairs-timing ["-wi" "3" "-i" "3" "-w" "1" "-r" "1" "-f" "1"]
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
                            typed-pairs-timing)
        host-typed (concat ["JsonParserHostTypedProjectBenchmark.hostTypedProject"
                            "-p" "fixture=placeholder"
                            "-p" "fixture=jsonapi"
                            "-p" "fixture=github"
                            "-p" "fixture=twitterFirst"
                            "-p" "fixture=popularApis"
                            "-prof" "gc"]
                           typed-pairs-timing)
        jmh-args (case profile
                   :smoke (concat smoke args)
                   :typed-pairs (concat typed-pairs args)
                   :host-typed (concat host-typed args)
                   :staged-alloc (concat ["JsonTypedStagedAllocBenchmark"
                                          "-prof" "gc"]
                                         typed-pairs-timing
                                         args)
                   :cloffle (concat ["JsonParserCloffle.*|JsonParserJacksonStreamingBenchmark"]
                                    quick args)
                   :fairness (concat ["JsonParserCloffle.*|JsonParserJacksonStreamingBenchmark|JsonParserBenchmark\\.(guest|jacksonParseLookup|cloffleParseLookup)"]
                                     quick args)
                   :full (concat ["JsonParser.*"] args)
                   (throw (ex-info "Unknown :profile for run-json-parser-benchmarks"
                                   {:profile profile
                                    :valid [:smoke :typed-pairs :host-typed :staged-alloc
                                            :cloffle :fairness :full]})))]
    (run-benchmarks {:args jmh-args :compile compile})))

(def ^:private tlab-event-weights
  "Per-TLAB-refill events. Each weight is the whole TLAB, so absolute bytes over-count, but
   every refill is attributed to the allocation that triggered it, making the distribution
   across frames a size-biased sample. These resolve orders of magnitude better than
   ObjectAllocationSample, whose throttle is capped well below what these rates need."
  {"jdk.ObjectAllocationOutsideTLAB" "allocationSize"
   "jdk.ObjectAllocationInNewTLAB" "tlabSize"})

(def ^:private sample-event-weights
  {"jdk.ObjectAllocationSample" "weight"})

(def ^:private alloc-jfc
  "Allocation events only. JMH starts this recording at the first measurement iteration, so
   Clojure/Cloffle startup allocation stays out of the attribution."
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<configuration version=\"2.0\" label=\"Cloffle allocation\">\n"
       "  <event name=\"jdk.ObjectAllocationInNewTLAB\">\n"
       "    <setting name=\"enabled\">true</setting>\n"
       "    <setting name=\"stackTrace\">true</setting>\n"
       "  </event>\n"
       "  <event name=\"jdk.ObjectAllocationOutsideTLAB\">\n"
       "    <setting name=\"enabled\">true</setting>\n"
       "    <setting name=\"stackTrace\">true</setting>\n"
       "  </event>\n"
       "  <event name=\"jdk.ObjectAllocationSample\">\n"
       "    <setting name=\"enabled\">true</setting>\n"
       "    <setting name=\"throttle\">150000/s</setting>\n"
       "    <setting name=\"stackTrace\">true</setting>\n"
       "  </event>\n"
       "</configuration>\n"))

(def ^:private alloc-frame-prefixes
  ["net.javacrumbs." "org.cloffle." "clojure."])

(defn- alloc-attributed-frame
  "First stack frame belonging to Cloffle or Clojure, so allocation lands on our code
   rather than on the JDK internals that happened to call `new`."
  [event]
  (let [frames (some-> (.getStackTrace event) .getFrames)
        named (keep (fn [f]
                      (when-let [m (.getMethod f)]
                        (str (.getName (.getType m)) "." (.getName m))))
                    frames)]
    (or (first (filter (fn [n] (some #(clojure.string/starts-with? n %) alloc-frame-prefixes))
                       named))
        (first named)
        "?")))

(defn- jfr-events
  "Read every event from every .jfr under `dir`."
  [dir]
  (for [f (filter #(clojure.string/ends-with? (.getName %) ".jfr")
                  (file-seq (io/file dir)))
        event (with-open [rf (jdk.jfr.consumer.RecordingFile. (.toPath f))]
                ;; Materialize inside with-open; the reader closes with the file.
                (loop [acc []]
                  (if (.hasMoreEvents rf)
                    (recur (conj acc (.readEvent rf)))
                    acc)))]
    event))

(defn- aggregate-alloc-events
  [events weights]
  (->> (for [event events
             :let [weight-field (get weights (.getName (.getEventType event)))]
             :when weight-field]
         {:class (or (some-> (.getClass event "objectClass") .getName) "?")
          :frame (alloc-attributed-frame event)
          :bytes (.getLong event weight-field)})
       (group-by (juxt :class :frame))
       (map (fn [[[cls frame] rows]]
              {:class cls :frame frame :bytes (reduce + 0 (map :bytes rows))}))
       (sort-by :bytes >)))

(defn- jfr-allocation-rows
  "Aggregate JFR allocation weights from every .jfr under `dir`, preferring the TLAB events
   and falling back to ObjectAllocationSample when a config enabled only that.
   Returns [{:class .. :frame .. :bytes ..}] sorted by descending bytes."
  [dir]
  (let [events (jfr-events dir)
        tlab (aggregate-alloc-events events tlab-event-weights)]
    (if (seq tlab)
      tlab
      (aggregate-alloc-events events sample-event-weights))))

(defn run-alloc-profile
  "Attribute allocation by class and allocating frame via JFR's TLAB allocation events.

   `-prof gc` gives a per-op total but no attribution; this answers *what* is being
   allocated. JFR ships with the JDK, so this needs no async-profiler dependency.

   Options:
     :benchmark  JMH regex (default the full-Twitter guest consume path)
     :params     map of JMH -p params (default {\"guest\" \"guestTypedTwitterLateConsume\"})
     :top        rows to print (default 25)

   Invoke: clojure -T:build run-alloc-profile
           clojure -T:build run-alloc-profile :benchmark '\"JsonTypedStagedAllocBenchmark\"' :params '{\"fixture\" \"twitterLate\"}'"
  [{:keys [benchmark params top compile warmup iterations warmup-time time]
    :or {benchmark "JsonParserCloffleExtractBenchmark.guestExtract"
         params {"guest" "guestTypedTwitterLateConsume"}
         top 25 compile true warmup 2 iterations 3 warmup-time "1s" time "1s"}}]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (format "cloffle-jfr-%d" (System/nanoTime)))
        _ (.mkdirs dir)
        jfc (io/file dir "alloc.jfc")
        _ (spit jfc alloc-jfc)
        proc (run-benchmarks
              {:args (into [benchmark
                            ;; JMH scopes the recording to the measurement iterations, keeping
                            ;; Clojure/Cloffle startup allocation out of the attribution.
                            "-prof" (str "jfr:dir=" (.getAbsolutePath dir)
                                         ";configName=" (.getAbsolutePath jfc)
                                         ";stackDepth=64")
                            "-f" "1"
                            "-wi" (str warmup) "-i" (str iterations)
                            "-w" (str warmup-time) "-r" (str time)]
                           (mapcat (fn [[k v]] ["-p" (str k "=" v)]) params))
               :compile compile
               :out :inherit
               :err :inherit})]
    (when-not (zero? (:exit proc))
      (throw (ex-info "run-alloc-profile JMH failed" {:exit (:exit proc)})))
    (let [rows (jfr-allocation-rows dir)
          total (reduce + 0 (map :bytes rows))]
      (out [:bold.cyan "\n===== Allocation attribution (JFR ObjectAllocationSample) =====\n"])
      (when (empty? rows)
        (out "  No allocation events recorded; check that the .jfc was accepted.\n"))
      (out (format "  %-42s %-44s %10s %7s" "class" "allocating frame" "MB" "share"))
      (doseq [r (take top rows)]
        (out (format "  %-42s %-44s %10.1f %6.1f%%"
                     (:class r) (:frame r)
                     (/ (double (:bytes r)) 1048576.0)
                     (if (pos? total) (* 100.0 (/ (double (:bytes r)) total)) 0.0))))
      (out (format "\n  sampled total: %.1f MB across %d class/frame pairs"
                   (/ (double total) 1048576.0) (count rows)))
      (out "  Weights are sampled estimates, not exact byte counts; use -prof gc for totals.\n")
      rows)))

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
