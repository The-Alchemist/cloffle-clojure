;; Category: git submodules, external-project `compat-test`, stock-vs-Cloffle audit probes.
(in-ns 'build)

(def external-projects-dir "src/external-projects")

(def external-projects
  {:cheshire {:deps '{com.fasterxml.jackson.core/jackson-core {:mvn/version "2.20.0"}
                     com.fasterxml.jackson.dataformat/jackson-dataformat-smile {:mvn/version "2.20.0" :exclusions [com.fasterxml.jackson.core/jackson-databind]}
                     com.fasterxml.jackson.dataformat/jackson-dataformat-cbor {:mvn/version "2.20.0" :exclusions [com.fasterxml.jackson.core/jackson-databind]}
                     tigris {:mvn/version "0.1.2"}
                     org.clojure/tools.namespace {:mvn/version "0.3.1"}}
              :src-dirs ["src"]
              :java-src-dirs ["src/java"]
              :test-dirs ["test"]
              :exclude-ns '#{cheshire.test.benchmark cheshire.test.generative}}

   :ring {:deps '{ring/ring-codec {:mvn/version "1.3.0"}
                 commons-io {:mvn/version "2.20.0"}
                 org.apache.commons/commons-fileupload2-core {:mvn/version "2.0.0-M4"}
                 crypto-random {:mvn/version "1.2.1"}
                 crypto-equality {:mvn/version "1.0.1"}
                 clj-time {:mvn/version "0.15.2"}}
          :src-dirs ["ring-core/src" "ring-core-protocols/src" "ring-websocket-protocols/src"]
          :test-dirs ["ring-core/test"]
          :working-dir "ring-core"
          :exclude-ns '#{}}

   :compojure {:deps '{org.clojure/tools.macro {:mvn/version "0.2.1"}
                      clout {:mvn/version "2.2.1"}
                      dev.weavejester/medley {:mvn/version "1.9.0"}
                      ring/ring-core {:mvn/version "1.15.1"}
                      ring/ring-codec {:mvn/version "1.3.0"}
                      ring/ring-mock {:mvn/version "0.6.2"}
                      criterium {:mvn/version "0.4.6"}
                      javax.servlet/servlet-api {:mvn/version "2.5"}}
               :src-dirs ["src"]
               :test-dirs ["test"]
               :exclude-ns '#{}}

   :clj-http {:deps '{org.apache.httpcomponents/httpcore {:mvn/version "4.4.16"}
                     org.apache.httpcomponents/httpclient {:mvn/version "4.5.14"}
                     org.apache.httpcomponents/httpclient-cache {:mvn/version "4.5.14"}
                     org.apache.httpcomponents/httpasyncclient {:mvn/version "4.1.5"}
                     org.apache.httpcomponents/httpmime {:mvn/version "4.5.14"}
                     org.clj-commons/slingshot {:mvn/version "0.13.0"}
                     commons-codec {:mvn/version "1.16.1"}
                     commons-io {:mvn/version "2.16.1"}
                     potemkin {:mvn/version "0.4.7"}
                     cheshire {:mvn/version "5.13.0"}
                     crouton {:mvn/version "0.1.2" :exclusions [org.jsoup/jsoup]}
                     org.jsoup/jsoup {:mvn/version "1.17.2"}
                     org.clojure/tools.reader {:mvn/version "1.4.1"}
                     com.cognitect/transit-clj {:mvn/version "1.0.333"}
                     ring/ring-codec {:mvn/version "1.2.0"}
                     org.clojure/tools.logging {:mvn/version "1.3.0"}
                     ring/ring-jetty-adapter {:mvn/version "1.12.1"}
                     ring/ring-devel {:mvn/version "1.12.1"}
                     javax.servlet/javax.servlet-api {:mvn/version "4.0.1"}
                     org.clojure/core.cache {:mvn/version "1.1.234"}
                     org.apache.logging.log4j/log4j-api {:mvn/version "2.23.1"}
                     org.apache.logging.log4j/log4j-core {:mvn/version "2.23.1"}
                     org.apache.logging.log4j/log4j-1.2-api {:mvn/version "2.23.1"}
                     org.apache.logging.log4j/log4j-slf4j2-impl {:mvn/version "2.23.1"}}
              :src-dirs ["src"]
              :test-dirs ["test"]
              :exclude-ns '#{}}

   :hiccup {:deps '{criterium {:mvn/version "0.4.4"}}
            :src-dirs ["src"]
            :test-dirs ["test"]
            :exclude-ns '#{}}

   :reitit {:deps '{metosin/reitit {:mvn/version "0.10.1"}
                    metosin/reitit-core {:mvn/version "0.10.1"}
                    metosin/reitit-dev {:mvn/version "0.10.1"}
                    metosin/reitit-spec {:mvn/version "0.10.1"}
                    metosin/reitit-malli {:mvn/version "0.10.1"}
                    metosin/reitit-schema {:mvn/version "0.10.1"}
                    metosin/reitit-ring {:mvn/version "0.10.1"}
                    metosin/reitit-middleware {:mvn/version "0.10.1"}
                    metosin/reitit-http {:mvn/version "0.10.1"}
                    metosin/reitit-interceptors {:mvn/version "0.10.1"}
                    metosin/reitit-swagger {:mvn/version "0.10.1"}
                    fi.metosin/reitit-openapi {:mvn/version "0.10.1"}
                    metosin/reitit-swagger-ui {:mvn/version "0.10.1"}
                    metosin/reitit-frontend {:mvn/version "0.10.1"}
                    metosin/reitit-sieppari {:mvn/version "0.10.1"}
                    metosin/reitit-pedestal {:mvn/version "0.10.1"}
                    metosin/ring-swagger-ui {:mvn/version "5.31.0"}
                    metosin/spec-tools {:mvn/version "0.10.8"}
                    metosin/schema-tools {:mvn/version "0.13.1"}
                    metosin/muuntaja {:mvn/version "0.6.11"}
                    metosin/jsonista {:mvn/version "0.3.14"}
                    metosin/sieppari {:mvn/version "0.0.0-alpha13"}
                    metosin/malli {:mvn/version "0.20.1"}
                    com.fasterxml.jackson.core/jackson-core {:mvn/version "2.21.1"}
                    com.fasterxml.jackson.core/jackson-databind {:mvn/version "2.21.1"}
                    meta-merge {:mvn/version "1.0.0"}
                    fipp {:mvn/version "0.6.29" :exclusions [org.clojure/core.rrb-vector]}
                    org.clojure/core.rrb-vector {:mvn/version "0.2.1"}
                    expound {:mvn/version "0.9.0"}
                    lambdaisland/deep-diff {:mvn/version "0.0-47"}
                    com.bhauman/spell-spec {:mvn/version "0.1.2"}
                    mvxcvi/arrangement {:mvn/version "2.1.0"}
                    ring/ring-core {:mvn/version "1.15.3"}
                    ring {:mvn/version "1.15.3"}
                    orchestra {:mvn/version "2021.01.01-1"}
                    ikitommi/immutant-web {:mvn/version "3.0.0-alpha1"
                                           :exclusions [ch.qos.logback/logback-classic]}
                    metosin/ring-http-response {:mvn/version "0.9.5"}
                    org.clojure/tools.analyzer {:mvn/version "1.2.2"}
                    criterium {:mvn/version "0.4.6"}
                    org.clojure/test.check {:mvn/version "1.1.3"}
                    org.clojure/tools.namespace {:mvn/version "1.5.1"}
                    com.gfredericks/test.chuck {:mvn/version "0.2.15"}
                    nubank/matcher-combinators {:mvn/version "3.10.0"}
                    io.pedestal/pedestal.service {:mvn/version "0.6.4"}
                    org.clojure/core.async {:mvn/version "1.8.741"}
                    manifold {:mvn/version "0.5.0"}
                    funcool/promesa {:mvn/version "11.0.678"}
                    ring-cors {:mvn/version "0.1.13"}
                    com.bhauman/rebel-readline {:mvn/version "0.1.5"}}
            :src-dirs ["dev-resources"
                       "modules/reitit/src"
                       "modules/reitit-core/src"
                       "modules/reitit-dev/src"
                       "modules/reitit-ring/src"
                       "modules/reitit-http/src"
                       "modules/reitit-middleware/src"
                       "modules/reitit-openapi/src"
                       "modules/reitit-interceptors/src"
                       "modules/reitit-malli/src"
                       "modules/reitit-spec/src"
                       "modules/reitit-schema/src"
                       "modules/reitit-swagger/src"
                       "modules/reitit-swagger-ui/src"
                       "modules/reitit-frontend/src"
                       "modules/reitit-sieppari/src"
                       "modules/reitit-pedestal/src"]
            :java-src-dirs ["modules/reitit-core/java-src"]
            :test-dirs ["test/clj" "test/cljc"]
            :exclude-ns '#{cljdoc.reaper}}

   :sieppari {:deps '{org.clojure/core.async {:mvn/version "1.8.741"}
                      manifold {:mvn/version "0.1.8"}
                      funcool/promesa {:mvn/version "5.1.0"}
                      io.pedestal/pedestal.service {:mvn/version "0.6.4"}
                      metosin/testit {:mvn/version "0.4.0"}}
              :src-dirs ["src"]
              :test-dirs ["test/clj" "test/cljc"]
              ;; sieppari.async.* suites live in .cljc
              :test-extensions [".clj" ".cljc"]
              :exclude-ns '#{}}

   :core.async {:deps '{org.clojure/tools.analyzer.jvm {:mvn/version "1.3.2"}}
                :src-dirs ["src/main/clojure"]
                :java-src-dirs ["src/main/java"]
                :test-dirs ["src/test/clojure"]
                :exclude-ns '#{}}})

(defn- find-namespaces
  ([dir] (find-namespaces dir [".clj"]))
  ([dir extensions]
   (let [root (io/file dir)
         root-path (.getAbsolutePath root)
         ext-re (re-pattern (str "\\.(" (clojure.string/join "|" (map #(subs % 1) extensions)) ")$"))]
     (if (.exists root)
       (->> (file-seq root)
            (filter #(and (.isFile %)
                          (some (fn [ext] (.endsWith (.getName %) ext)) extensions)))
            (map (fn [f]
                   (let [contents (slurp f)
                         ;; Simple regex to find (ns namespace-name ...)
                         ;; This isn't perfect (ignores comments/strings) but is better than filename guessing
                         matcher (re-matcher #"\(\s*ns\s+([^\s\)]+)" contents)]
                     (if (re-find matcher)
                       (symbol (second (re-groups matcher)))
                       ;; Fallback to filename logic if ns declaration not found
                       (let [path (.getAbsolutePath f)
                             rel-path (subs path (inc (count root-path)))
                             no-ext (clojure.string/replace rel-path ext-re "")
                             dotted (clojure.string/replace no-ext #"/" ".")
                             dashed (clojure.string/replace dotted #"_" "-")]
                         (symbol dashed))))))
            distinct
            sort)
       []))))

(defn- compat-skips-generative-namespace?
  "Exclude org.clojure/test.generative-style suites (namespaces matching *.generative) from compat runs."
  [ns-sym]
  (boolean (re-find #"\.generative(\.|$)" (str ns-sym))))

(def external-project-patches-dir "src/external-projects/patches")

(defn- git-apply-check
  [proj-dir patch-path reverse?]
  (b/process {:command-args (cond-> ["git" "apply" "--check"]
                              reverse? (conj "--reverse")
                              true (conj patch-path))
              :dir proj-dir
              :out :capture
              :err :capture}))

(defn- apply-external-project-patches
  "Apply tracked patches under src/external-projects/patches/<project>/ after
   submodule checkout. Patches are idempotent: already-applied hunks are skipped.
   Drop or refresh a patch when bumping that submodule onto an upstream SHA that
   already contains the change (e.g. ring-clojure/ring#548)."
  []
  (doseq [proj (sort (keys external-projects))]
    (let [proj-name (clojure.core/name proj)
          proj-dir (.getPath (io/file external-projects-dir proj-name))
          patch-dir (io/file external-project-patches-dir proj-name)]
      (when (.isDirectory patch-dir)
        (doseq [patch-file (sort (filter #(.isFile %) (or (.listFiles patch-dir) [])))
                :let [patch-path (.getAbsolutePath patch-file)]]
          (let [forward (git-apply-check proj-dir patch-path false)]
            (cond
              (zero? (:exit forward))
              (do
                (out [:green (str "Applying patch " proj-name "/" (.getName patch-file) "...")])
                (ensure-jvm-task-ok!
                 (str "git apply " proj-name "/" (.getName patch-file))
                 (b/process {:command-args ["git" "apply" patch-path]
                             :dir proj-dir
                             :out :inherit
                             :err :inherit})))

              (zero? (:exit (git-apply-check proj-dir patch-path true)))
              (out [:green (str "Patch already applied: " proj-name "/" (.getName patch-file))])

              :else
              (throw (ex-info (str "Failed to apply " proj-name "/" (.getName patch-file)
                                   " (neither forward nor reverse git apply --check succeeded)."
                                   " Rebase the patch onto the current submodule SHA.")
                              {:project proj-name
                               :patch patch-path
                               :stderr (:err forward)})))))))))

(defn update-submodules
  "Initialize and update git submodules under src/external-projects.
   Usage: clj -T:build update-submodules
          clj -T:build update-submodules :latest true
   When :latest is true (or COMPAT_CHECK_LATEST env var is set), fetches the
   latest commit from each submodule's remote (for CI full builds). Otherwise
   uses the pinned SHA from .gitmodules (reproducible local builds).
   After checkout, applies any patches in src/external-projects/patches/<project>/."
  [{:keys [latest] :or {latest false}}]
  (let [latest? (or latest (= "true" (System/getenv "COMPAT_CHECK_LATEST")))
        args (cond-> ["submodule" "update" "--init" "--recursive"]
               latest? (conj "--remote"))]
    (out (if latest?
           [:green "Updating submodules to latest remote commits (CI mode)..."]
           [:green "Updating submodules to pinned SHAs (reproducible mode)..."]))
    (b/process {:command-args (into ["git"] args)
                :dir "."
                :out :inherit
                :err :inherit})
    (apply-external-project-patches)))

(defn- compile-external-java [name config basis]
  (let [dir (io/file external-projects-dir (clojure.core/name name))
        java-src-dirs (filter #(.exists (io/file dir %)) (:java-src-dirs config))
        java-src-paths (map #(.getPath (io/file dir %)) java-src-dirs)
        class-dir (io/file "target" (str (clojure.core/name name) "-classes"))]
    (when (seq java-src-paths)
      (out [:green (str "Compiling Java sources for " name "...")])
      (io/make-parents (io/file class-dir "dummy"))
      (b/javac {:src-dirs java-src-paths
                :class-dir (.getPath class-dir)
                :basis basis
                :javac-opts (into ["--release" "21" "-encoding" "UTF-8"]
                                  javac-quiet-opts)}))))

(defn write-reitit-repro-argfile
  "Write java argfile for running the minimal reitit repro"
  [_]
  (let [proj :reitit
        config (get external-projects proj)
        proj-dir (io/file external-projects-dir (clojure.core/name proj))
        proj-class-dir (io/file "target" (str (clojure.core/name proj) "-classes"))
        basis (b/create-basis {:project "deps.edn"
                               :extra {:deps (:deps config)}})
        _ (compile-external-java proj config basis)
        _ (compile-all nil)
        src-paths (map #(.getAbsolutePath (io/file proj-dir %)) (:src-dirs config))
        test-paths (map #(.getAbsolutePath (io/file proj-dir %)) (:test-dirs config))
        cp (concat [(.getAbsolutePath (io/file class-dir))
                    (.getAbsolutePath (io/file "src/clj"))
                    (.getAbsolutePath proj-class-dir)]
                   src-paths
                   test-paths
                   (runtime-classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)
        common-opts (into (test-jvm-opts)
                          ["-cp" cp-str])
        cfl-args (concat common-opts
                         ["-Dclojure.spec.check-specs=true"
                          "net.javacrumbs.cloffle.CloffleMain"
                          "-e" "(require '[reitit.http-test] '[clojure.test :as test]) (test/run-test reitit.http-test/core-async-test)"])
        argfile (write-java-argfile cfl-args)]
    (println "ARGFILE:" argfile)
    (spit "target/reitit-argfile-path.txt" argfile)))

(defn- parse-probe-records
  "Parse `key<TAB>value` lines into an ordered vector of pairs."
  [s]
  (into []
        (keep (fn [line]
                (let [line (clojure.string/trimr line)
                      i (.indexOf line "\t")]
                  (when (and (pos? i) (< i (dec (count line))))
                    [(subs line 0 i) (subs line (inc i))])))
              (clojure.string/split-lines (or s "")))))

(defn- write-probe-output!
  [path contents]
  (let [f (io/file path)]
    (io/make-parents f)
    (spit f (or contents ""))))

(defn- diff-probe-records
  [stock-pairs cloffle-pairs]
  (let [stock-keys (mapv first stock-pairs)
        cloffle-keys (mapv first cloffle-pairs)
        stock-map (into {} stock-pairs)
        cloffle-map (into {} cloffle-pairs)
        missing (vec (remove (set cloffle-keys) stock-keys))
        extra (vec (remove (set stock-keys) cloffle-keys))
        mismatches (into []
                         (keep (fn [k]
                                 (let [sv (get stock-map k)
                                       cv (get cloffle-map k)]
                                   (when (and (contains? stock-map k)
                                              (contains? cloffle-map k)
                                              (not= sv cv))
                                     {:key k :stock sv :cloffle cv})))
                               stock-keys))]
    {:missing missing
     :extra extra
     :mismatches mismatches
     :order-diff? (and (= (set stock-keys) (set cloffle-keys))
                       (not= stock-keys cloffle-keys))}))

(defn- run-stock-cloffle-probe!
  "Compile Cloffle, run `probe-rel` under stock Clojure and Cloffle, write outputs, fail on a
   semantic key/value diff. `:allow-mismatch-keys` is a set of probe keys whose value
   differences are documented and do not fail the task. Returns the parsed record maps."
  [{:keys [probe-rel stock-name cloffle-name fail-msg allow-mismatch-keys]
    :or {allow-mismatch-keys #{}}}]
  (compile-all nil)
  (let [probe (.getAbsolutePath (io/file probe-rel))
        out-dir (io/file "target/compat-audit")
        stock-out (io/file out-dir stock-name)
        cloffle-out (io/file out-dir cloffle-name)
        stock-basis (b/create-basis {:project "deps.edn"
                                     :args {:replace-paths []
                                            :replace-deps {'org.clojure/clojure
                                                           {:mvn/version compat-official-clojure-version}}}})
        stock-cp (clojure.string/join (System/getProperty "path.separator")
                                      (runtime-classpath-roots stock-basis))
        cloffle-basis (b/create-basis {:project "deps.edn" :aliases [:cloffle-java]})
        cloffle-cp (clojure.string/join
                    (System/getProperty "path.separator")
                    (into [class-dir fork-clojure-sources]
                          (runtime-classpath-roots cloffle-basis)))
        _ (assert-standalone-truffle-jars! (into [class-dir fork-clojure-sources]
                                                 (runtime-classpath-roots cloffle-basis)))
        stock-args (concat (test-jvm-opts)
                           ["-cp" stock-cp "clojure.main" probe])
        cloffle-args (concat (test-jvm-opts)
                             ["-cp" cloffle-cp
                              "net.javacrumbs.cloffle.CloffleMain"
                              probe])]
    (out [:bold.cyan (str "\n===== Stock Clojure " compat-official-clojure-version " probe =====")])
    (let [stock (b/process {:command-args (into ["java"] [(write-java-argfile stock-args)])
                            :out :capture
                            :err :inherit})
          _ (write-probe-output! stock-out (:out stock))
          _ (assert-process-success! (str "stock " probe-rel) stock)
          _ (out [:bold.cyan "\n===== Cloffle probe ====="])
          cloffle (b/process {:command-args (into ["java"] [(write-java-argfile cloffle-args)])
                              :out :capture
                              :err :inherit})
          _ (write-probe-output! cloffle-out (:out cloffle))
          _ (assert-process-success! (str "cloffle " probe-rel) cloffle)
          stock-pairs (parse-probe-records (:out stock))
          cloffle-pairs (parse-probe-records (:out cloffle))
          diff (diff-probe-records stock-pairs cloffle-pairs)]
      (out [:cyan (str "  Stock records:   " (count stock-pairs))])
      (out [:cyan (str "  Cloffle records: " (count cloffle-pairs))])
      (out [:cyan (str "  Wrote " (.getPath stock-out) " and " (.getPath cloffle-out))])
      (when (seq (:missing diff))
        (out [:red (str "  Missing in Cloffle: " (pr-str (:missing diff)))]))
      (when (seq (:extra diff))
        (out [:red (str "  Extra in Cloffle: " (pr-str (:extra diff)))]))
      (let [allowed? (set allow-mismatch-keys)
            known (filterv #(contains? allowed? (:key %)) (:mismatches diff))
            unexpected (filterv #(not (contains? allowed? (:key %))) (:mismatches diff))]
        (doseq [{:keys [key stock cloffle]} known]
          (out [:yellow (str "  Known mismatch " key)])
          (out [:yellow (str "    stock:   " stock)])
          (out [:yellow (str "    cloffle: " cloffle)]))
        (doseq [{:keys [key stock cloffle]} unexpected]
          (out [:red (str "  Mismatch " key)])
          (out [:red (str "    stock:   " stock)])
          (out [:red (str "    cloffle: " cloffle)]))
        (when (:order-diff? diff)
          (out [:yellow "  Key order differs (values still compared by key)."]))
        (when (or (seq (:missing diff)) (seq (:extra diff)) (seq unexpected))
          (throw (ex-info fail-msg
                          {:missing (:missing diff)
                           :extra (:extra diff)
                           :mismatches unexpected})))
        (if (seq known)
          (out [:bold.green "  RESULT: expected diffs only — no unexpected probe mismatches."])
          (out [:bold.green "  RESULT: IDENTICAL - Cloffle matches stock Clojure."]))
        {:stock stock-pairs :cloffle cloffle-pairs :diff diff}))))

(defn audit-var-mutation-binding
  "Run `dev/compat-audit/probe_var_mutation_binding.clj` under stock Clojure 1.12 and Cloffle.
   Writes both outputs under `target/compat-audit/` and fails on a semantic key/value diff.
   Invoke: clj -T:build audit-var-mutation-binding"
  [_]
  (run-stock-cloffle-probe!
   {:probe-rel "dev/compat-audit/probe_var_mutation_binding.clj"
    :stock-name "var-mutation-binding-stock.txt"
    :cloffle-name "var-mutation-binding-cloffle.txt"
    :fail-msg "Var mutation binding probe differs from stock Clojure"})
  nil)

(defn audit-probe2
  "Run `dev/compat-audit/probe2_intrinsics_printdup.clj` under stock Clojure 1.12 and Cloffle.
   Writes both outputs under `target/compat-audit/`. Fails on unexpected key/value diffs;
   documented print-dup / type / extra-redefinability keys are allowlisted.
   Invoke: clj -T:build audit-probe2"
  [_]
  (run-stock-cloffle-probe!
   {:probe-rel "dev/compat-audit/probe2_intrinsics_printdup.clj"
    :stock-name "probe2-stock.txt"
    :cloffle-name "probe2-cloffle.txt"
    :fail-msg "probe2_intrinsics_printdup has unexpected diffs vs stock Clojure"
    :allow-mismatch-keys
    #{"redef/nth" "redef/count" "redef/nil?" "redef/identical?" "redef/equals"
      "pd/vector-class" "pd/list-out" "pd/map-out" "pd/map-9-out"
      "pd/nested-vector-in-map-out" "pd/tuple-class-exists"
      "pd/literal-vector-isa-tuple" "pd/tuple-isa-IPersistentCollection"
      "pd/effective-method-is-pv-method" "coll/vector-seq-class"}})
  nil)

(defn test-unchecked-math-compat
  "Run the unchecked-math boundary probe under stock Clojure 1.12 and Cloffle.
   Covers checked/truthy gates, every arithmetic and cast operation, arity folding,
   local shadowing, with-redefs, and the Compiler.java ASM path used by deftype methods.
   Writes both outputs under `target/compat-audit/` and fails on any semantic difference.
   Invoke: clj -T:build test-unchecked-math-compat"
  [_]
  (run-stock-cloffle-probe!
   {:probe-rel "dev/compat-audit/probe_unchecked_math.clj"
    :stock-name "unchecked-math-stock.txt"
    :cloffle-name "unchecked-math-cloffle.txt"
    :fail-msg "Unchecked-math probe differs unexpectedly from stock Clojure"
    ;; Intentional consequence of keeping the general inliner disabled: stock expands
    ;; (+ 1 2) even with *unchecked-math* false and therefore bypasses with-redefs;
    ;; Cloffle's ordinary Var call observes the redefinition. The truthy unchecked case
    ;; is rewritten by both and is required to match. Same for nary `/` under the default flag.
    :allow-mismatch-keys #{"redefs/checked" "variadic/divide-checked-redef"}})
  nil)

;; Intentional Cloffle divergences (see COMPAT_DIFFS.md). Known bugs are NOT listed
;; here — they must fail audit-compat until fixed.
(def ^:private probe1-allow-mismatch-keys
  #{;; Public chunking off (finding 7)
    "chunk/chunked-seq?-vector" "chunk/chunked-seq?-range"
    "chunk/vector-seq-is-IChunkedSeq" "chunk/iterator-seq-is-IChunkedSeq"
    "chunk/map-realization-window-vector" "chunk/map-realization-window-range"
    "chunk/filter-realization-window-vector" "chunk/for-realization-window-vector"
    "chunk/keep-realization-window-vector" "chunk/map-indexed-realization-window-vector"
    ;; Concrete collection / seq classes (finding 4)
    "class/map-literal-2" "class/map-literal-9" "class/assoc-on-nil"
    "class/vector-literal-3" "class/vector-fn-3"
    "class/list-literal-3" "class/list-fn-3" "class/conj-vector"
    "class/into-vector" "class/map-result-vector" "class/map-result-map"
    "class/filter-result" "class/drop-result" "class/vector-seq"
    "iface/vector-literal-3" "iface/list-literal-3" "iface/map-literal-2"
    "iface/list-is-Indexed" "iface/vector-is-PersistentVector"
    "iface/map-is-PersistentArrayMap"
    "protocol/exact-class-vector-literal"
    "multimethod/class-dispatch-map" "multimethod/class-dispatch-vector"
    ;; print-dup emits readable literals for shape maps (round-trip still OK)
    "printdup/map-literal"
    ;; Extra redefinability where stock had :inline (finding 2 aftermath)
    "var/with-redefs-count"
    ;; Synthetic :arglists on closures (finding 11)
    "meta/fn-literal-meta" "meta/fn-literal-meta-keys" "meta/anonymous-fn-arglists"
    ;; Intentional LazySeq hardening / recoverability (Finding 10)
    "lazy/thunk-throws-is-retryable"
    "lazy/self-recursive-realization"
    ;; EphemeralVectorSeq reports realized? true for pure views (PEA)
    "lazy/realized-fresh" "lazy/realized-after-first"})
;; Remaining Bug rows (if any) fail the gate — see COMPAT_DIFFS.md.

(defn audit-probe1
  "Run `dev/compat-audit/probe1_semantics.clj` under stock Clojure 1.12 and Cloffle.
   Writes both outputs under `target/compat-audit/`. Fails on unexpected key/value diffs;
   intentional chunk/class/order/redef/meta keys are allowlisted (see COMPAT_DIFFS.md).
   Invoke: clj -T:build audit-probe1"
  [_]
  (run-stock-cloffle-probe!
   {:probe-rel "dev/compat-audit/probe1_semantics.clj"
    :stock-name "probe1-stock.txt"
    :cloffle-name "probe1-cloffle.txt"
    :fail-msg "probe1_semantics has unexpected diffs vs stock Clojure"
    :allow-mismatch-keys probe1-allow-mismatch-keys})
  nil)

(defn audit-probe3
  "Run `dev/compat-audit/probe3_root_cause.clj` under stock Clojure 1.12 and Cloffle.
   Diagnostic probe for print-dup preference conflicts and with-redefs bypass.
   Intentional / diagnostic mismatches are allowlisted; see COMPAT_DIFFS.md.
   Invoke: clj -T:build audit-probe3"
  [_]
  (run-stock-cloffle-probe!
   {:probe-rel "dev/compat-audit/probe3_root_cause.clj"
    :stock-name "probe3-stock.txt"
    :cloffle-name "probe3-cloffle.txt"
    :fail-msg "probe3_root_cause has unexpected diffs vs stock Clojure"
    :allow-mismatch-keys
    #{"why/prefers-ipc-over-tuple" "why/prefers-tuple-over-ipc"
      "why/collection-is-ancestor-of-tuple" "why/concrete-is-registered-dispatch-value"
      "fix/exact-class-defmethod" "fix/prefer-method"
      "fix/prefer-method-roundtrip" "fix/prefer-method-nested"
      "bypass/alter-var-root-first" "bypass/with-redefs-fn-first"
      "bypass/first-var-value-during-redef" "bypass/str-var-value-during-redef"
      "bypass/count-var-value-during-redef"}})
  nil)

(defn audit-probe4
  "Run `dev/compat-audit/probe4_clj_http_repro.clj` under stock Clojure 1.12 and Cloffle.
   Large vector-literal / map pipeline regression (finding 1). Values must match;
   class-name keys for map seqs may differ intentionally.
   Invoke: clj -T:build audit-probe4"
  [_]
  (run-stock-cloffle-probe!
   {:probe-rel "dev/compat-audit/probe4_clj_http_repro.clj"
    :stock-name "probe4-stock.txt"
    :cloffle-name "probe4-cloffle.txt"
    :fail-msg "probe4_clj_http_repro has unexpected diffs vs stock Clojure"
    :allow-mismatch-keys
    #{"repro/literal-class" "repro/map-seq-class"}})
  nil)

(defn audit-probe5
  "Run `dev/compat-audit/probe5_vector_literals.clj` under stock Clojure 1.12 and Cloffle.
   Vector literal size boundaries must match stock for nth/seq/= (finding 1 fixed).
   Class simple-names for small tuples may differ intentionally.
   Invoke: clj -T:build audit-probe5"
  [_]
  (run-stock-cloffle-probe!
   {:probe-rel "dev/compat-audit/probe5_vector_literals.clj"
    :stock-name "probe5-stock.txt"
    :cloffle-name "probe5-cloffle.txt"
    :fail-msg "probe5_vector_literals has unexpected diffs vs stock Clojure"
    ;; Size 8 is PersistentTuple8; values/nth/= still match stock.
    :allow-mismatch-keys
    #{"lit/8"}})
  nil)

(defn audit-probe6
  "Run `dev/compat-audit/probe6_print_dup.clj` under stock Clojure 1.12 and Cloffle.
   print-dup round-trips for substituted collections (finding 6 fixed).
   Invoke: clj -T:build audit-probe6"
  [_]
  (run-stock-cloffle-probe!
   {:probe-rel "dev/compat-audit/probe6_print_dup.clj"
    :stock-name "probe6-stock.txt"
    :cloffle-name "probe6-cloffle.txt"
    :fail-msg "probe6_print_dup has unexpected diffs vs stock Clojure"
    ;; Shape-map print-dup emits readable map literals instead of #=(…/create …);
    ;; rt/* round-trip keys are required to keep matching.
    :allow-mismatch-keys
    #{"out/map-with-vector" "out/vector-with-map"}})
  nil)

(defn audit-probe7
  "Run `dev/compat-audit/probe7_core_semantics.clj` under stock Clojure 1.12 and Cloffle.
   Gap coverage: constantly, get-in eager not-found, ephemeral realized?, map/set
   literal boundaries, MappedMapSeq reduce, serialization, non-literal redef sites.
   Invoke: clj -T:build audit-probe7"
  [_]
  (run-stock-cloffle-probe!
   {:probe-rel "dev/compat-audit/probe7_core_semantics.clj"
    :stock-name "probe7-stock.txt"
    :cloffle-name "probe7-cloffle.txt"
    :fail-msg "probe7_core_semantics has unexpected diffs vs stock Clojure"
    :allow-mismatch-keys
    #{"constantly/arglists" "constantly/meta-keys"
      ;; EphemeralVectorSeq reports realized? true (PEA); class differs from LazySeq
      "ephemeral/realized-map-keyword"
      "ephemeral/class-map-keyword"
      ;; class field inside maplit maps (equals?/keys still match)
      "maplit/0" "maplit/1" "maplit/2" "maplit/8" "maplit/16"}})
  nil)

(defn audit-probe8
  "Run `dev/compat-audit/probe8_nil_empty.clj` under stock Clojure 1.12 and Cloffle.
   Nil / empty / false edge matrix for predicates, seq accessors, str, lookup/update,
   and empty seq pipelines. Values must match; class names are not probed.
   Invoke: clj -T:build audit-probe8"
  [_]
  (run-stock-cloffle-probe!
   {:probe-rel "dev/compat-audit/probe8_nil_empty.clj"
    :stock-name "probe8-stock.txt"
    :cloffle-name "probe8-cloffle.txt"
    :fail-msg "probe8_nil_empty has unexpected diffs vs stock Clojure"
    :allow-mismatch-keys #{}})
  nil)

(defn audit-probe9
  "Run `dev/compat-audit/probe9_edge_wave2.clj` under stock Clojure 1.12 and Cloffle.
   Wave-2 edges: truthiness/=, expanded subjects, nth/peek/pop, keys/merge/concat,
   fnil, destructuring, apply, math-nil throws. Values must match.
   Invoke: clj -T:build audit-probe9"
  [_]
  (run-stock-cloffle-probe!
   {:probe-rel "dev/compat-audit/probe9_edge_wave2.clj"
    :stock-name "probe9-stock.txt"
    :cloffle-name "probe9-cloffle.txt"
    :fail-msg "probe9_edge_wave2 has unexpected diffs vs stock Clojure"
    :allow-mismatch-keys
    ;; Unrolled PersistentList implements Indexed (same as probe1 iface/list-is-Indexed).
    #{"edge2/pred/indexed?/lnil"}})
  nil)

(defn audit-probe10
  "Run `dev/compat-audit/probe10_edge_wave3.clj` under stock Clojure 1.12 and Cloffle.
   Wave-3: arrays/host, set ops, range/cycle, sort/hash/meta, subvec/rseq, string,
   partition/group-by, NaN/Inf, bit/casts, for/case, sorted colls.
   Invoke: clj -T:build audit-probe10"
  [_]
  (run-stock-cloffle-probe!
   {:probe-rel "dev/compat-audit/probe10_edge_wave3.clj"
    :stock-name "probe10-stock.txt"
    :cloffle-name "probe10-cloffle.txt"
    :fail-msg "probe10_edge_wave3 has unexpected diffs vs stock Clojure"
    :allow-mismatch-keys #{}})
  nil)

(defn audit-probe11
  "Run `dev/compat-audit/probe11_edge_wave4.clj` under stock Clojure 1.12 and Cloffle.
   Wave-4: regex, edn/read-string, ex-info, if-let/if-some, quot/ratios, arrays,
   tree-seq/walk, halt-when, isa?/type, threading, watches, pmap empty.
   Invoke: clj -T:build audit-probe11"
  [_]
  (run-stock-cloffle-probe!
   {:probe-rel "dev/compat-audit/probe11_edge_wave4.clj"
    :stock-name "probe11-stock.txt"
    :cloffle-name "probe11-cloffle.txt"
    :fail-msg "probe11_edge_wave4 has unexpected diffs vs stock Clojure"
    :allow-mismatch-keys #{}})
  nil)

(defn audit-probe12
  "Run `dev/compat-audit/probe12_edge_wave5.clj` under stock Clojure 1.12 and Cloffle.
   Wave-5: reduce-kv, merge-with, lowered get/assoc/dissoc/conj on literals,
   delay/promise, meta, get-in family, tuple literal sizes, number edges.
   Invoke: clj -T:build audit-probe12"
  [_]
  (run-stock-cloffle-probe!
   {:probe-rel "dev/compat-audit/probe12_edge_wave5.clj"
    :stock-name "probe12-stock.txt"
    :cloffle-name "probe12-cloffle.txt"
    :fail-msg "probe12_edge_wave5 has unexpected diffs vs stock Clojure"
    :allow-mismatch-keys #{}})
  nil)

(defn audit-compat
  "Run all stock-vs-Cloffle differential audit probes. Fails on any unexpected mismatch.
   Intentional divergences are allowlisted per probe (COMPAT_DIFFS.md).
   Also runs unchecked-math and var-mutation-binding probes.
   Invoke: clj -T:build audit-compat
          clj -T:build audit-compat :strict false
   When :strict is false (default true), probe failures are collected and reported
   without aborting early — the task still exits non-zero if any probe failed."
  [{:keys [strict] :or {strict true}}]
  (out [:bold.cyan "\n===== audit-compat: stock 1.12.0 vs Cloffle ====="])
  (let [steps [["audit-probe1" audit-probe1]
               ["audit-probe2" audit-probe2]
               ["audit-probe3" audit-probe3]
               ["audit-probe4" audit-probe4]
               ["audit-probe5" audit-probe5]
               ["audit-probe6" audit-probe6]
               ["audit-probe7" audit-probe7]
               ["audit-probe8" audit-probe8]
               ["audit-probe9" audit-probe9]
               ["audit-probe10" audit-probe10]
               ["audit-probe11" audit-probe11]
               ["audit-probe12" audit-probe12]
               ["test-unchecked-math-compat" test-unchecked-math-compat]
               ["audit-var-mutation-binding" audit-var-mutation-binding]]
        failures (atom [])]
    (doseq [[label f] steps]
      (try
        (f nil)
        (catch Exception e
          (swap! failures conj {:probe label :message (.getMessage e)})
          (out [:bold.red (str "  FAIL " label ": " (.getMessage e))])
          (when strict
            (throw e)))))
    (if (seq @failures)
      (do
        (out [:bold.red (str "\n===== audit-compat: " (count @failures)
                             " probe(s) failed (see COMPAT_DIFFS.md Bug rows) =====")])
        (doseq [{:keys [probe message]} @failures]
          (out [:red (str "  - " probe ": " message)]))
        (throw (ex-info "audit-compat failed"
                        {:failures @failures})))
      (out [:bold.green "\n===== audit-compat: all probes passed (allowlisted diffs only) ====="])))
  nil)

(defn compat-test
  "[AST+BYTECODE] Run compatibility checks for external projects (git submodules in src/external-projects).
   Enables Java assertions (`-ea`) in both phases.
   Generative (test.generative / *.generative) test namespaces are skipped.
   Phase 1 runs tests with official org.clojure/clojure from Maven (`compat-official-clojure-version`).
   Phase 2 runs the same tests with Cloffle.
   Usage: clj -T:build compat-test
          clj -T:build compat-test :project :all
          clj -T:build compat-test :project :cheshire
          clj -T:build compat-test :project :sieppari
          clj -T:build compat-test :project :core.async
          clj -T:build compat-test :project :cheshire :only-var '\"cheshire.test.core/serial-writing\"'
          clj -T:build compat-test :latest true
   :only-var '<ns/var>' runs only the single fully qualified deftest in both phases.
   :latest true (or COMPAT_CHECK_LATEST=true) updates submodules to latest remote
   commits before testing (for CI full builds). Default uses pinned SHAs."
  [{:keys [project latest only-var] :or {project :all latest false}}]
  (let [var-sym (parse-only-var-sym only-var)
        var-ns-sym (when var-sym (symbol (namespace var-sym)))
        target-projects (if (or (nil? project) (= :all project))
                          (keys external-projects)
                          [project])
        matched-projects (atom [])]
    (compile-all nil) ;; Ensure Cloffle is built
    (update-submodules {:latest latest})
    (doseq [proj target-projects]
      (let [config (get external-projects proj)]
        (if-not config
          (out [:red (str "Unknown project: " proj)])
          (let [proj-dir (io/file external-projects-dir (clojure.core/name proj))
                test-namespaces (->> (:test-dirs config)
                                     (mapcat #(find-namespaces (io/file proj-dir %)
                                                               (or (:test-extensions config) [".clj"])))
                                     (remove (:exclude-ns config))
                                     (remove compat-skips-generative-namespace?))]
            (if (and var-ns-sym (not (some #(= var-ns-sym %) test-namespaces)))
              (when-not (or (nil? project) (= :all project))
                (throw (ex-info (str "Namespace " var-ns-sym " not found in tests for " proj ". Found: " (vec (sort (distinct test-namespaces))))
                                {:only-var var-sym :project proj})))
              (do
                (when var-sym
                  (swap! matched-projects conj proj))
                (let [test-namespaces (if var-ns-sym [var-ns-sym] test-namespaces)
                      ;; Determine working directory
                      working-dir (if (:working-dir config)
                                    (io/file proj-dir (:working-dir config))
                                    proj-dir)
                      working-dir-abs-path (.getAbsolutePath working-dir)
                      proj-class-dir (io/file "target" (str (clojure.core/name proj) "-classes"))
                      ;; Create basis with external deps (Cloffle phase + Java compile)
                      basis (b/create-basis {:project "deps.edn"
                                             :extra {:deps (:deps config)}})
                      ;; Phase 1: official Clojure JARs from Maven only (no in-repo src/clj or classes)
                      clj-basis (b/create-basis {:project "deps.edn"
                                                 :args {:replace-paths []
                                                        :replace-deps {'org.clojure/clojure
                                                                       {:mvn/version compat-official-clojure-version}}}
                                                 :extra {:deps (:deps config)}})
                      ;; Compile external Java if needed
                      _ (compile-external-java proj config basis)
                      ;; Construct classpath (absolute)
                      src-paths (map #(.getAbsolutePath (io/file proj-dir %)) (:src-dirs config))
                      test-paths (map #(.getAbsolutePath (io/file proj-dir %)) (:test-dirs config))
                      cp-clj (concat [(.getAbsolutePath proj-class-dir)]
                                     src-paths
                                     test-paths
                                     (runtime-classpath-roots clj-basis))
                      cp-clj-str (clojure.string/join (System/getProperty "path.separator") cp-clj)
                      cp (concat [(.getAbsolutePath (io/file class-dir))
                                  (.getAbsolutePath (io/file "src/clj"))
                                  (.getAbsolutePath proj-class-dir)]
                                 src-paths
                                 test-paths
                                 (runtime-classpath-roots basis))
                      cp-str (clojure.string/join (System/getProperty "path.separator") cp)
                      script-path (.getAbsolutePath (io/file "src/script/run_external_tests_surefire.clj"))
                      common-opts-clj (into (test-suite-jvm-opts)
                                            ["-cp" cp-clj-str])
                      common-opts (into (test-suite-jvm-opts)
                                        ["-cp" cp-str])
                      clj-reports-dir (io/file surefire-reports-dir (str (name proj) "-clojure"))
                      cfl-reports-dir (io/file surefire-reports-dir (str (name proj) "-cloffle"))]
                  (b/delete {:path (.getPath clj-reports-dir)})
                  (b/delete {:path (.getPath cfl-reports-dir)})

                  (out [:bold.cyan (str "\n===== Phase 1: " proj " tests with Maven Clojure "
                                        compat-official-clojure-version " =====")])
                  (let [clj-args (concat common-opts-clj
                                         [(str "-Dsurefire.reports.dir=" (.getAbsolutePath clj-reports-dir))]
                                         (when var-sym
                                           [(str "-Dclojure.test.only-var=" var-sym)])
                                         ["clojure.main" script-path]
                                         (map str test-namespaces))
                        clj-argfile (write-java-argfile clj-args)]
                    (out [:magenta (str "Command: java " clj-argfile)])
                    (ensure-surefire-process-ok!
                     (str "compat-test phase 1 (" proj ") Maven Clojure")
                     (b/process
                      {:command-args ["java" clj-argfile]
                       :dir working-dir-abs-path
                       :out :inherit
                       :err :inherit})
                     clj-reports-dir))

                  (out [:bold.cyan (str "\n===== Phase 2: " proj " tests with Cloffle (Truffle) =====")])
                  (let [cfl-args (concat common-opts
                                         ["-Dclojure.spec.check-specs=true"
                                          (str "-Dsurefire.reports.dir=" (.getAbsolutePath cfl-reports-dir))]
                                         (when var-sym
                                           [(str "-Dclojure.test.only-var=" var-sym)])
                                         ["net.javacrumbs.cloffle.CloffleMain" script-path]
                                         (map str test-namespaces))
                        cfl-argfile (write-java-argfile cfl-args)]
                    (ensure-surefire-process-ok!
                     (str "compat-test phase 2 (" proj ") Cloffle")
                     (b/process
                      {:command-args ["java" cfl-argfile]
                       :dir working-dir-abs-path
                       :out :inherit
                       :err :inherit})
                     cfl-reports-dir))

                  (let [clj-file (io/file clj-reports-dir "TEST-results.xml")
                        cfl-file (io/file cfl-reports-dir "TEST-results.xml")]
                    (if (and (.exists clj-file) (.exists cfl-file))
                      (let [clj-results (parse-junit-xml clj-file)
                            cfl-results (parse-junit-xml cfl-file)
                            clj-pass (count (filter #(= :pass (:status %)) clj-results))
                            clj-fail (count (filter #(= :fail (:status %)) clj-results))
                            clj-err  (count (filter #(= :error (:status %)) clj-results))
                            cfl-pass (count (filter #(= :pass (:status %)) cfl-results))
                            cfl-fail (count (filter #(= :fail (:status %)) cfl-results))
                            cfl-err  (count (filter #(= :error (:status %)) cfl-results))
                            diffs    (diff-results clj-results cfl-results)]
                        (out [:cyan (format "  Clojure:  %d testcases (%d pass, %d fail, %d error)"
                                            (count clj-results) clj-pass clj-fail clj-err)])
                        (out [:cyan (format "  Cloffle:  %d testcases (%d pass, %d fail, %d error)"
                                            (count cfl-results) cfl-pass cfl-fail cfl-err)])
                        (println)
                        (if (empty? diffs)
                          (out [:bold.green "  RESULT: IDENTICAL - Cloffle matches Clojure exactly."])
                          (do
                            (out [:bold.red (format "  RESULT: %d DIFFERENCE(S) FOUND\n" (count diffs))])
                            (doseq [{:keys [suite name clojure cloffle]} diffs]
                              (out [:red (format "  %-50s  Clojure: %-5s  Cloffle: %s"
                                                 (str suite "/" name)
                                                 (if clojure (clojure.core/name clojure) "MISSING")
                                                 (if cloffle (clojure.core/name cloffle) "MISSING"))]))))
                        (println)
                        (out "  Reports:")
                        (out (str "    Clojure: " (.getPath clj-file)))
                        (out (str "    Cloffle: " (.getPath cfl-file))))
                      (do
                        (when-not (.exists clj-file)
                          (out [:bold.red (str "  ERROR: Clojure report file not found: " (.getPath clj-file))]))
                        (when-not (.exists cfl-file)
                          (out [:bold.red (str "  ERROR: Cloffle report file not found: " (.getPath cfl-file))]))))))))))))
    (when (and var-sym (or (nil? project) (= :all project)) (empty? @matched-projects))
      (throw (ex-info (str "Namespace " var-ns-sym " not found in any external project tests.")
                      {:only-var var-sym})))))
