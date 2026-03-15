(ns build
  (:refer-clojure :exclude [compile test])
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [clojure.string]
            [clj-commons.ansi :as ansi]))

;; Colored output when stdout is an interactive TTY. Disabled when:
;; - NO_COLOR env var is set, -Dclojure.main.report=stderr pipes stderr,
;; - stdout is redirected/piped (System/console is nil), or
;; - TERM=dumb. Plain text is always readable for logs/LLMs.
(defn- color-enabled? []
  (and (nil? (System/getenv "NO_COLOR"))
       (not= "dumb" (System/getenv "TERM"))
       (some? (System/console))
       (let [prop (System/getProperty "clj-commons.ansi.enabled")]
         (or (nil? prop) (= "true" prop)))))

(defn- out
  "Print styled text. Uses ANSI only when color-enabled? (interactive TTY, no NO_COLOR).
   compose returns plain text when *color-enabled* is false, so output stays readable."
  [content]
  (binding [ansi/*color-enabled* (color-enabled?)]
    (println (ansi/compose content))))

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

(defn- write-version-properties
  "Write clojure/version.properties into class-dir with the build version (same result as Maven filtering)."
  []
  (let [f (io/file class-dir "clojure/version.properties")]
    (io/make-parents f)
    (spit f (str "version=" version))))

(defn- write-java-argfile
  "Write java command-line args to a temp file for use with java @argfile.
   Returns the @path string to pass as a single argument. Avoids command-line
   length limits for long classpaths."
  [args]
  (let [f (java.io.File/createTempFile "java-args-" ".txt")
        lines (map (fn [arg]
                     (let [s (str arg)]
                       (if (clojure.string/includes? s " ")
                         (str "\"" s "\"")
                         s)))
                   args)]
    (spit f (clojure.string/join "\n" lines))
    (str "@" (.getAbsolutePath f))))

(defn compile-java
  "Compile src/jvm (Clojure runtime + Cloffle Truffle nodes)."
  [_]
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
  (out [:yellow "compile-clojure is retired in Truffle-only mode."]))

(defn compile-all [_]
  ;; Truffle-only build: compile Java runtime/nodes only.
  (compile-java nil))

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
              :javac-opts ["--release" "17" "-encoding" "UTF-8"]})))

(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn jar [_]
  (compile-all nil)
  (b/copy-dir {:src-dirs ["src/clj"]
               :target-dir class-dir
               :include #".*\.clj$"})
  (b/jar {:class-dir class-dir
          :jar-file jar-file
          :main 'clojure.main}))

(defn build-jar
  "Build the distribution JAR (compile-all + package as single jar).
   Invoke: clj -T:build build-jar
   Used by Dockerfile.jlink and CI."
  [_]
  (jar nil))

(defn- test-jvm-opts []
  ["-Xss4m" "--enable-native-access=ALL-UNNAMED"])

(defn- runtime-classpath-roots [basis]
  ;; Avoid loading Clojure source files from this repo at runtime. Using only
  ;; dependency/jar roots prevents mixed classloader behavior (jar + source).
  (remove #(re-find #"(^|/)src/clj$" (str %)) (:classpath-roots basis)))

(defn cloffle-repl
  "Run CloffleRepl (interactive REPL, --demo, or a .clj file). Args: {:args []}
   Invoke: clj -T:build cloffle-repl :args '[\"--demo\"]'"
  [{:keys [args] :or {args []}}]
  (compile-all nil)
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:repl]})
        cp (into [class-dir "test"] (runtime-classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)
        args (concat (test-jvm-opts)
                     ["-cp" cp-str
                      "net.javacrumbs.cloffle.CloffleRepl"]
                     (map str args))
        argfile (write-java-argfile args)]
    (b/process
     {:command-args ["java" argfile]
      :out :inherit
      :err :inherit})))

(defn source-location-demo
  "Run SourceLocationDemo (shows per-expression source line/column in stack traces).
   Invoke: clj -T:build source-location-demo"
  [_]
  (compile-tests nil)
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:test]})
        cp (into [test-class-dir "test" "src/test/resources" class-dir] (runtime-classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)
        args (concat (test-jvm-opts)
                     ["-cp" cp-str
                      "net.javacrumbs.cloffle.SourceLocationDemo"])
        argfile (write-java-argfile args)]
    (b/process
     {:command-args ["java" argfile]
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
        cp (into [class-dir "test"] (runtime-classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)
        args (concat (test-jvm-opts)
                     ["-cp" cp-str
                      "net.javacrumbs.cloffle.CloffleMain"]
                     (map str args))
        argfile (write-java-argfile args)]
    (b/process
     {:command-args ["java" argfile]
      :in :inherit
      :out :inherit
      :err :inherit})))

(defn run-tests
  "Run Cloffle JUnit tests only (Truffle-only mode).
   Args: {:args []} - optional args passed to JUnit ConsoleLauncher."
  [{:keys [args] :or {args []}}]
  (compile-tests nil)
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:test]})
        cp (into [test-class-dir "test" "src/test/resources" class-dir] (runtime-classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)]
    (out [:bold.cyan "\n===== Cloffle JUnit tests ====="])
    (io/make-parents (io/file surefire-reports-dir "dummy"))
    (let [junit-base ["-cp" cp-str
                      "org.junit.platform.console.ConsoleLauncher"
                      "execute"
                      (str "--reports-dir=" surefire-reports-dir)
                      "--details=summary"]
          junit-opts (if (empty? args)
                       (conj junit-base "--scan-class-path")
                       (into junit-base (map str args)))
          args (concat (test-jvm-opts) junit-opts)
          argfile (write-java-argfile args)]
      (b/process
       {:command-args ["java" argfile]
        :out :inherit
        :err :inherit})
      (out (str "\nJUnit reports: " surefire-reports-dir)))))

(def ^:private clojure-reports-dir "target/surefire-reports/clojure")
(def ^:private cloffle-reports-dir "target/surefire-reports/cloffle")

(defn- run-surefire-suite
  "Run the Clojure test suite via run_test_surefire.clj using the given main class."
  [main-class reports-dir cp-str exclude-ns]
  (let [args (concat (test-jvm-opts)
                    ["-Dclojure.test.quiet=true"
                     (str "-Dclojure.test-clojure.exclude-namespaces=" exclude-ns)
                     (str "-Dsurefire.reports.dir=" reports-dir)
                     "-cp" cp-str
                     main-class
                     "src/script/run_test_surefire.clj"])
        argfile (write-java-argfile args)]
    (b/process
     {:command-args ["java" argfile]
      :out :inherit
      :err :inherit})))

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
  "Retired in Truffle-only mode."
  [_]
  (out [:yellow "compat-test retired: Clojure-vs-Cloffle dual-engine comparison removed in Truffle-only mode."]))

(def ^:private generative-ns
  "Namespaces that depend on clojure.test.check (generative / property-based tests)."
  [" clojure.test-clojure.data-structures-interop"
   " clojure.test-clojure.parse"
   " clojure.test-clojure.sequences"
   " clojure.test-clojure.transducers"])

(defn run-clj-tests
  "Run Clojure's own test suite (test/clojure/test_clojure/) through Cloffle/Truffle.
   Invoke: clj -T:build run-clj-tests
   Include generative tests: clj -T:build run-clj-tests :generative true
   Override excludes: clj -T:build run-clj-tests :exclude '\"#{ns1 ns2}\"'"
  [opts]
  (compile-tests nil)
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:test-built]})
        cp (into [class-dir test-class-dir "test" "src/test/resources"]
                 (runtime-classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)
        exclude (or (:exclude opts)
                    (str "#{clojure.test-clojure.compilation.load-ns"
                         " clojure.test-clojure.compilation"
                         " clojure.test-clojure.ns-libs-load-later"
                         " clojure.test-clojure.genclass"
                         " clojure.test-clojure.annotations"
                         ;; JVM bytecode local clearing — not applicable in Truffle
                         " clojure.test-clojure.clearing"
                         ;; serialization of ClojureClosure
                         " clojure.test-clojure.serialization"
                         (when-not (:generative opts)
                           (apply str generative-ns))
                         "}"))]
    (when-not (:generative opts)
      (out [:yellow "Generative tests (test.check) skipped. Use :generative true to include."]))
    (out [:bold.cyan "\n===== Clojure test suite (via Cloffle) ====="])
    (run-surefire-suite "clojure.main"
                        cloffle-reports-dir cp-str exclude)))

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
        cp (into [class-dir] (runtime-classpath-roots basis))
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
        cp (into [benchmark-class-dir class-dir] (runtime-classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)
        args (concat (test-jvm-opts)
                     ["-cp" cp-str
                      "org.openjdk.jmh.Main"]
                     (map str args))
        argfile (write-java-argfile args)]
    (b/process
     {:command-args ["java" argfile]
      :out :inherit
      :err :inherit})))

(def external-projects-dir "src/external-projects")

(def external-projects
  {:cheshire {:deps '{com.fasterxml.jackson.core/jackson-core {:mvn/version "2.20.0"}
                     com.fasterxml.jackson.dataformat/jackson-dataformat-smile {:mvn/version "2.20.0" :exclusions [com.fasterxml.jackson.core/jackson-databind]}
                     com.fasterxml.jackson.dataformat/jackson-dataformat-cbor {:mvn/version "2.20.0" :exclusions [com.fasterxml.jackson.core/jackson-databind]}
                     tigris {:mvn/version "0.1.2"}
                     org.clojure/test.generative {:mvn/version "0.1.4"}
                     org.clojure/tools.namespace {:mvn/version "0.3.1"}}
              :src-dirs ["src"]
              :java-src-dirs ["src/java"]
              :test-dirs ["test"]
              :exclude-ns '#{cheshire.test.benchmark}}

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
            :exclude-ns '#{}}})

(defn- find-namespaces [dir]
  (let [root (io/file dir)
        root-path (.getAbsolutePath root)]
    (if (.exists root)
      (->> (file-seq root)
           (filter #(and (.isFile %) (.endsWith (.getName %) ".clj")))
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
                            no-ext (clojure.string/replace rel-path #"\.clj$" "")
                            dotted (clojure.string/replace no-ext #"/" ".")
                            dashed (clojure.string/replace dotted #"_" "-")]
                        (symbol dashed))))))
           sort)
      [])))

(defn update-submodules
  "Initialize and update git submodules under src/external-projects.
   Usage: clj -T:build update-submodules
          clj -T:build update-submodules :latest true
   When :latest is true (or COMPAT_CHECK_LATEST env var is set), fetches the
   latest commit from each submodule's remote (for CI full builds). Otherwise
   uses the pinned SHA from .gitmodules (reproducible local builds)."
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
                :err :inherit})))

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
                :javac-opts ["--release" "17" "-encoding" "UTF-8"]}))))

(defn compat-check
  "Retired in Truffle-only mode."
  [_]
  (out [:yellow "compat-check retired: external Clojure baseline run removed in Truffle-only mode."]))
