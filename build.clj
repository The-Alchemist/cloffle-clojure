(ns build
  (:refer-clojure :exclude [compile test])
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]))

(def lib 'org.clojure/clojure)
(def version "1.13.0-master-SNAPSHOT")
(def class-dir "target/classes")
(def test-class-dir "target/test-classes")

(def clojure-namespaces
  '[clojure.core clojure.core.protocols clojure.core.server clojure.main
    clojure.set clojure.edn clojure.xml clojure.zip clojure.inspector
    clojure.walk clojure.stacktrace clojure.template clojure.test
    clojure.test.tap clojure.test.junit clojure.pprint clojure.java.io
    clojure.repl clojure.java.browse clojure.java.javadoc clojure.java.shell
    clojure.java.process clojure.java.browse-ui clojure.java.basis.impl
    clojure.java.basis clojure.string clojure.data clojure.reflect
    clojure.datafy clojure.instant clojure.uuid clojure.core.reducers
    clojure.math clojure.tools.deps.interop clojure.repl.deps])

(def test-namespaces
  '[clojure.test-clojure.protocols.examples clojure.test-clojure.proxy.examples
    clojure.test-clojure.genclass.examples clojure.test-clojure.compilation.load-ns
    clojure.test-clojure.annotations])

(def basis (delay (b/create-basis {:project "deps.edn"})))
(def basis-with-processor
  (delay
   (b/create-basis
    {:project "deps.edn"
     :extra {:deps {(symbol "org.graalvm.truffle/truffle-dsl-processor") {:mvn/version "25.0.2"}}}})))

(def surefire-reports-dir "target/surefire-reports")

(defn clean [_]
  (b/delete {:path "target"})
  (doseq [f (or (seq (.listFiles (io/file "."))) [])]
    (when (and (.isFile f) (re-matches #".*\.(jar|zip)$" (.getName f)))
      (io/delete-file f))))

(defn- write-version-properties []
  (let [f (io/file class-dir "clojure/version.properties")]
    (io/make-parents f)
    (spit f (str "version=" version))))

(defn compile-java [_]
  "Compile src/jvm (Clojure runtime + Cloffle Truffle nodes)."
  (b/copy-dir {:src-dirs ["src/resources"]
               :target-dir class-dir})
  (write-version-properties)
  (let [basis @basis-with-processor
        proc-path (clojure.string/join (System/getProperty "path.separator")
                                       (:classpath-roots basis))]
    (b/javac {:src-dirs ["src/jvm"]
              :class-dir class-dir
              :basis basis
              :javac-opts ["--release" "17" "-encoding" "UTF-8"
                           "-processorpath" proc-path]})))

(defn compile-clojure [_]
  (b/copy-dir {:src-dirs ["src/resources"]
               :target-dir class-dir})
  (write-version-properties)
  (let [cp (into [class-dir "src/clj"] (:classpath-roots @basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)]
    (b/process
     {:command-args (into ["java"
                           (str "-Dclojure.compile.path=" class-dir)
                           "-Dclojure.compiler.direct-linking=true"
                           "-Djava.awt.headless=true"
                           "-cp" cp-str
                           "clojure.lang.Compile"]
                         (map str clojure-namespaces))
      :out :inherit
      :err :inherit})))

(defn compile-all [_]
  ;; Compile Java (src/jvm), then AOT Clojure (bootstrap) so Truffle code can run.
  (compile-java nil)
  (compile-clojure nil))

(defn compile-tests [_]
  (compile-all nil)
  ;; b/javac builds classpath from basis :libs (not :classpath-roots). Add main
  ;; classes so test compilation can resolve NilNode etc.
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:test]})
        basis-with-main (update basis :libs
                                (fn [libs] (assoc libs :cloffle/main {:paths [class-dir]})))]
    (b/javac {:src-dirs ["test/java" "src/test/java"]
              :class-dir test-class-dir
              :basis basis-with-main
              :javac-opts ["--release" "17" "-encoding" "UTF-8"]}))
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:test]})
        cp (into [test-class-dir "test" class-dir "src/clj"] (:classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)]
    (b/process
     {:command-args (into ["java"
                           (str "-Dclojure.compile.path=" test-class-dir)
                           "-Dclojure.compiler.direct-linking=true"
                           "-cp" cp-str
                           "clojure.lang.Compile"]
                         (map str test-namespaces))
      :out :inherit
      :err :inherit})))

(defn jar [_]
  (compile-all nil)
  (b/copy-dir {:src-dirs ["src/clj"]
               :target-dir class-dir
               :include #".*\.clj$"})
  (b/jar {:class-dir class-dir
          :jar-file (format "target/%s-%s.jar" (name lib) version)
          :main 'clojure.main}))

(defn- test-jvm-opts []
  ["-Xss4m" "--enable-native-access=ALL-UNNAMED"])

(defn cloffle-repl
  "Run CloffleREPL (interactive REPL, --demo, or a .clj file). Args: {:args []}
   Invoke: clj -T:build cloffle-repl :args '[\"--demo\"]'"
  [{:keys [args] :or {args []}}]
  (compile-all nil)
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:repl]})
        cp (into [class-dir "test" "src/clj"] (:classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)]
    (b/process
     {:command-args (into ["java"]
                         (concat (test-jvm-opts)
                                 ["-cp" cp-str
                                  "net.javacrumbs.cloffle.CloffleREPL"]
                                 (map str args)))
      :out :inherit
      :err :inherit})))

(defn cloffle-main
  "Run CloffleMain (clojure.main-compatible CLI). Args: {:args []}
   NOTE: For interactive REPL (-r), use 'make cloffle-main-repl' instead. tools.build's
   b/process does not support :in :inherit, so stdin is piped and the REPL hangs.
   Examples (non-interactive):
     clj -T:build cloffle-main :args '[\"-e\" \"(+ 1 2)\"]'
     clj -T:build cloffle-main :args '[\"-m\" \"my.ns\"]'
     clj -T:build cloffle-main :args '[\"script.clj\"]'"
  [{:keys [args] :or {args []}}]
  (compile-all nil)
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:repl]})
        cp (into [class-dir "test" "src/clj"] (:classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)]
    (b/process
     {:command-args (into ["java"]
                         (concat (test-jvm-opts)
                                 ["-cp" cp-str
                                  "net.javacrumbs.cloffle.CloffleMain"]
                                 (map str args)))
      :in :inherit
      :out :inherit
      :err :inherit})))

(defn run-tests [_]
  (compile-tests nil)
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:test]})
        cp (into [test-class-dir "test" class-dir "src/clj"] (:classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)
        common-opts (into (test-jvm-opts)
                         ["-Dclojure.compiler.direct-linking=true"
                          "-Dclojure.test.quiet=true"
                          "-cp" cp-str])]
    ;; Clojure example tests (run_test.clj)
    (b/process
     {:command-args (into ["java"]
                          (concat common-opts
                                  ["-Dclojure.test-clojure.exclude-namespaces=#{clojure.test-clojure.compilation.load-ns clojure.test-clojure.compilation clojure.test-clojure.ns-libs-load-later clojure.test-clojure.genclass clojure.test-clojure.annotations}"
                                   "clojure.main" "src/script/run_test.clj"]))
      :out :inherit
      :err :inherit})
    ;; Clojure generative tests (run_test_generative.clj)
    (b/process
     {:command-args (into ["java"]
                          (concat common-opts
                                  ["clojure.main" "src/script/run_test_generative.clj"]))
      :out :inherit
      :err :inherit})
    ;; Cloffle JUnit tests (discover all *Test classes in src/test/java via scan-class-path)
    (do
      (io/make-parents (io/file surefire-reports-dir "dummy"))
      (b/process
       {:command-args (into ["java"]
                            (concat (test-jvm-opts)
                                    ["-cp" cp-str
                                     "org.junit.platform.console.ConsoleLauncher"
                                     "execute"
                                     "--scan-class-path"
                                     (str "--reports-dir=" surefire-reports-dir)
                                     "--details=summary"]))
        :out :inherit
        :err :inherit})
      (println "\nJUnit reports:" surefire-reports-dir))))

(def ^:private clojure-reports-dir "target/surefire-reports/clojure")
(def ^:private cloffle-reports-dir "target/surefire-reports/cloffle")

(defn- run-surefire-suite
  "Run the Clojure test suite via run_test_surefire.clj using the given main class."
  [main-class reports-dir cp-str exclude-ns]
  (b/process
   {:command-args (into ["java"]
                        (concat (test-jvm-opts)
                                ["-Dclojure.compiler.direct-linking=true"
                                 "-Dclojure.test.quiet=true"
                                 (str "-Dclojure.test-clojure.exclude-namespaces=" exclude-ns)
                                 (str "-Dsurefire.reports.dir=" reports-dir)
                                 "-cp" cp-str
                                 main-class
                                 "src/script/run_test_surefire.clj"]))
    :out :inherit
    :err :inherit}))

(defn- parse-junit-xml
  "Parse a JUnit XML file. Returns a vector of {:suite :name :status} for each testcase."
  [^java.io.File f]
  (let [builder (.newDocumentBuilder (javax.xml.parsers.DocumentBuilderFactory/newInstance))
        dom (.parse builder f)
        cases (.getElementsByTagName dom "testcase")
        results (atom [])]
    (doseq [i (range (.getLength cases))]
      (let [tc (.item cases i)
            tc-name (.getAttribute tc "name")
            classname (.getAttribute tc "classname")
            children (.getChildNodes tc)
            has-child (fn [tag]
                        (loop [j 0]
                          (when (< j (.getLength children))
                            (let [c (.item children (int j))]
                              (if (and (= (.getNodeType c) org.w3c.dom.Node/ELEMENT_NODE)
                                       (= (.getNodeName c) tag))
                                true
                                (recur (inc j)))))))
            status (cond (has-child "error") :error
                         (has-child "failure") :fail
                         :else :pass)]
        (swap! results conj {:suite classname :name tc-name :status status})))
    @results))

(defn- diff-results
  "Diff two vectors of {:suite :name :status}. Returns list of difference maps."
  [clj-results cfl-results]
  (let [key-fn (fn [r] [(str (:suite r)) (str (:name r))])
        clj-map (into {} (map (juxt key-fn :status) clj-results))
        cfl-map (into {} (map (juxt key-fn :status) cfl-results))
        all-keys (sort (distinct (concat (keys clj-map) (keys cfl-map))))]
    (for [k all-keys
          :let [cs (get clj-map k)
                fs (get cfl-map k)]
          :when (not= cs fs)]
      {:suite (first k) :name (second k)
       :clojure cs :cloffle fs})))

(defn compat-test
  "Run the Clojure test suite under both standard Clojure and Cloffle,
   generate JUnit XML reports for each, and diff the results.
     clj -T:build compat-test"
  [_]
  (compile-tests nil)
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:test]})
        cp (into [test-class-dir "test" class-dir "src/clj"] (:classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)
        exclude-ns "#{clojure.test-clojure.compilation.load-ns clojure.test-clojure.compilation clojure.test-clojure.ns-libs-load-later clojure.test-clojure.genclass clojure.test-clojure.annotations}"]

    (println "\n===== Phase 1: Clojure (ground truth) =====\n")
    (b/delete {:path clojure-reports-dir})
    (run-surefire-suite "clojure.main" clojure-reports-dir cp-str exclude-ns)

    (println "\n===== Phase 2: Cloffle (Truffle) =====\n")
    (b/delete {:path cloffle-reports-dir})
    (run-surefire-suite "net.javacrumbs.cloffle.CloffleMain" cloffle-reports-dir cp-str exclude-ns)

    (println "\n===== Phase 3: Compatibility diff =====\n")
    (let [clj-file (io/file clojure-reports-dir "TEST-results.xml")
          cfl-file (io/file cloffle-reports-dir "TEST-results.xml")]
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
          (println (format "  Clojure:  %d testcases (%d pass, %d fail, %d error)"
                           (count clj-results) clj-pass clj-fail clj-err))
          (println (format "  Cloffle:  %d testcases (%d pass, %d fail, %d error)"
                           (count cfl-results) cfl-pass cfl-fail cfl-err))
          (println)
          (if (empty? diffs)
            (println "  RESULT: IDENTICAL - Cloffle matches Clojure exactly.")
            (do
              (println (format "  RESULT: %d DIFFERENCE(S) FOUND\n" (count diffs)))
              (doseq [{:keys [suite name clojure cloffle]} diffs]
                (println (format "  %-50s  Clojure: %-5s  Cloffle: %s"
                                 (str suite "/" name)
                                 (if clojure (clojure.core/name clojure) "MISSING")
                                 (if cloffle (clojure.core/name cloffle) "MISSING"))))))
          (println)
          (println "  Reports:")
          (println "    Clojure:" (.getPath clj-file))
          (println "    Cloffle:" (.getPath cfl-file)))
        (println "  ERROR: XML report files not found.")))))

(def benchmark-class-dir "target/benchmark-classes")

(def basis-benchmark
  (delay
   (b/create-basis
    {:project "deps.edn"
     :aliases [:benchmark]
     ;; Add truffle-dsl-processor manually if needed, or rely on :build alias?
     ;; It's safer to include it explicitly for annotation processing if needed.
     :extra {:deps {(symbol "org.graalvm.truffle/truffle-dsl-processor") {:mvn/version "25.0.2"}}}})))

(defn compile-benchmarks [_]
  (compile-all nil)
  (let [basis @basis-benchmark
        cp (into [class-dir] (:classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)
        proc-path (clojure.string/join (System/getProperty "path.separator")
                                       (:classpath-roots basis))
        src-dir (io/file "src/benchmark/java")
        sources (->> (file-seq src-dir)
                     (filter #(and (.isFile %) (.endsWith (.getName %) ".java")))
                     (map #(.getPath %)))]
    (io/make-parents (io/file benchmark-class-dir "dummy"))
    (b/process
     {:command-args (into ["javac" "--release" "17" "-encoding" "UTF-8"
                           "-processorpath" proc-path
                           "-classpath" cp-str
                           "-d" benchmark-class-dir]
                          sources)
      :out :inherit
      :err :inherit})))

(defn run-benchmarks
  "Run JMH benchmarks.
   Invoke: clj -T:build run-benchmarks :args '[\"regex\"]'"
  [{:keys [args] :or {args []}}]
  (compile-benchmarks nil)
  (let [basis @basis-benchmark
        cp (into [benchmark-class-dir class-dir "src/clj"] (:classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)]
    (b/process
     {:command-args (into ["java"]
                          (concat (test-jvm-opts)
                                  ["-cp" cp-str
                                   "org.openjdk.jmh.Main"]
                                  (map str args)))
      :out :inherit
      :err :inherit})))

