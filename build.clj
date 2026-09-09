(ns build
  (:refer-clojure :exclude [compile test])
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [clojure.string]
            [clj-commons.ansi :as ansi])
  (:import [com.github.thealchemist BgvDump]))

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

(def lib 'the-alchemist/cloffle)
(def version "1.13.0-master-SNAPSHOT")
(def compat-official-clojure-version "1.12.0")
(def class-dir "target/classes")
(def test-class-dir "target/test-classes")

;; Truffle Bytecode DSL generates `instanceof java.lang.ThreadDeath` into
;; CloffleBytecodeRootNodeGen.java; ThreadDeath is deprecated for removal and generated
;; sources cannot carry @SuppressWarnings. `-XDsuppressNotes` drops javac's
;; "Recompile with -Xlint:... for details" notes. Drop these opts (or pass
;; -Xlint:deprecation,unchecked) when auditing deprecated/unchecked usage.
(def ^:private javac-quiet-opts ["-Xlint:-removal" "-XDsuppressNotes"])

(def fork-clojure-sources "src/clj")

;; --- deps.edn / CLI classpath (Cloffle vs stock Clojure) --------------------
;;
;; Project `:deps` are Truffle-only. The Clojure CLI install still merges
;; `org.clojure/clojure`, which transitively pulls `spec.alpha` and `core.specs.alpha`.
;;
;; The `:repl` alias uses `:classpath-overrides` so those three coordinates resolve to
;; `etc/empty-cp/` (an empty directory) instead of Maven JARs. `clojure -A:repl -Spath`
;; then shows fork paths + Truffle only; `etc/empty-cp` may appear three times (one per
;; overridden lib). Tasks below that use `create-basis` with `:aliases [:repl]` inherit
;; the same overrides — stock clojure/spec classes are not on the Maven portion of the
;; classpath; entrypoints prepend `class-dir` and `fork-clojure-sources` first.
;;
;; Use `clojure -A:repl-mvn` when you want the install’s clojure + spec JARs (e.g.
;; loading `clojure.main` from sources, which requires spec). `:test` / `:test-built`
;; declare spec libs with `:exclusions [org.clojure/clojure]` so forked bytecode in
;; `target/classes` wins when running tests. `:dap` adds Graal DAP only where needed.

(def basis (delay (b/create-basis {:project "deps.edn"})))
;; :dap — compile patched `com.oracle.truffle.tools.dap.server.StackFramesHandler` (namespace scope label).
(def basis-java-compile
  (delay
   (b/create-basis
    {:project "deps.edn"
     :aliases [:dap]
     :extra {:deps {(symbol "org.graalvm.truffle/truffle-dsl-processor") {:mvn/version "25.3.4.1"}}}})))

(def surefire-reports-dir "target/surefire-reports")

(defn clean
  "Remove `target/` and top-level `*.jar` / `*.zip` artifacts."
  [_]
  (b/delete {:path "target"})
  (doseq [f (or (seq (.listFiles (io/file "."))) [])]
    (when (and (.isFile f) (re-matches #".*\.(jar|zip)$" (.getName f)))
      (io/delete-file f))))

(defn help
  "List public build tasks (invokable via clojure -T:build <task>).
   Optional: :verbose true for full docstrings (default prints the first line only).
   Invoke: clj -T:build help
           clj -T:build help :verbose true"
  [opts]
  (let [{:keys [verbose]} (merge {:verbose false} opts)]
    (out [:bold.cyan "\n===== build.clj tasks ====="])
    (doseq [[sym var] (sort-by (fn [[k _]] (str k)) (ns-publics (the-ns 'build)))
            :let [m (meta var)]
            :when (and (:arglists m) (not (:private m)))
            :let [doc (:doc m)
                  lines (if (seq doc)
                          (clojure.string/split-lines doc)
                          ["(no documentation)"])]]
      (println (name sym))
      (if verbose
        (do (when doc (println doc))
            (println))
        (println (str "  " (first lines)))))
    nil))

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

(defn- run-interactive-process!
  "Run a child process with parent stdin/stdout/stderr attached.
   Use this for interactive tasks where tools.build's b/process pipes stdin."
  [command-args]
  (let [pb (ProcessBuilder. (mapv str command-args))]
    (.directory pb (io/file "."))
    (.inheritIO pb)
    (let [proc (.start pb)
          exit-code (.waitFor proc)]
      (when-not (zero? exit-code)
        (throw (ex-info "Interactive command failed."
                        {:command-args (vec command-args)
                         :exit-code exit-code}))))))

(declare runtime-classpath-roots)

(defn compile-java
  "Compile src/jvm (Clojure runtime + Cloffle Truffle nodes)."
  [_]
  (b/copy-dir {:src-dirs ["src/resources"]
               :target-dir class-dir})
  (write-version-properties)
  (let [basis @basis-java-compile
        proc-path (clojure.string/join (System/getProperty "path.separator")
                                       (:classpath-roots basis))]
    (b/javac {:src-dirs ["src/jvm"]
              :class-dir class-dir
              :basis basis
              :javac-opts (into ["--release" "25" "-encoding" "UTF-8"
                                 "-processorpath" proc-path]
                                javac-quiet-opts)})))

(defn compile-all
  "Compile the Cloffle runtime and Truffle nodes (`compile-java`)."
  [_]
  (compile-java nil))

(declare compile-benchmarks benchmark-class-dir)

(defn compile-tests
  "Compile main sources plus Java tests under `test/java` and `src/test/java`."
  [_]
  (compile-all nil)
  (compile-benchmarks nil)
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:test :repl :benchmark]})
        cp (into [benchmark-class-dir class-dir fork-clojure-sources] (runtime-classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)
        sources (->> (concat (file-seq (io/file "test/java"))
                             (if (.exists (io/file "src/test/java")) (file-seq (io/file "src/test/java")) []))
                     (filter #(and (.isFile %) (.endsWith (.getName %) ".java")))
                     (map #(.getPath %)))]
    (io/make-parents (io/file test-class-dir "dummy"))
    (b/process
     {:command-args (into (into ["javac" "--release" "21" "-encoding" "UTF-8"
                                 "-classpath" cp-str
                                 "-d" test-class-dir]
                                javac-quiet-opts)
                          sources)
      :out :inherit
      :err :inherit})))

(def jar-file (format "target/%s-%s.jar" (name lib) version))

;; One line: relative path to the JAR produced by `jar` (for Docker COPY without globs).
(def jar-artifact-manifest "target/jar-artifact.txt")

(declare dump-bytecode-cache)

(defn jar
  "Compile, dump bytecode cache `.bc` files, copy all of `src/clj` (forked `.clj` sources)
   into classes, and write the versioned JAR under `target/`. Writes `jar-artifact-manifest`
   (path to that JAR) for Docker. Also packages compiled `.class` files and `.bc` caches so
   `RT.loadResourceScript` can prefer bytecode when present.
   Order matters: dump-bytecode-cache calls compile-all internally (b/javac may clean
   class-dir), so .clj copy must happen after."
  [_]
  (dump-bytecode-cache {})
  (b/copy-dir {:src-dirs ["src/clj"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file
          :main 'clojure.main})
  (spit jar-artifact-manifest jar-file))

(defn build-jar
  "Build the distribution JAR (compile-all + package as single jar).
   Invoke: clj -T:build build-jar
   Used by Dockerfile.jlink and CI."
  [_]
  (jar nil))

(defn- test-jvm-opts
  "JVM flags for every `java` subprocess spawned from this build (REPLs, tests, benchmarks, compat).
  Includes `--sun-misc-unsafe-memory-access=allow` because GraalVM Truffle (truffle-api / runtime)
  uses restricted `sun.misc.Unsafe` memory APIs; upstream would need to migrate before the warning
  goes away — we only suppress the noise here.
  `AttachLibraryFailureAction=throw` turns a missing `truffleattach` (typical of an uber/nested JAR)
  into an exception instead of the `[engine] WARNING: … fallback runtime …` interpreter path."
  []
  ["-Xss4m" "--enable-native-access=ALL-UNNAMED"
   "--sun-misc-unsafe-memory-access=allow"
   "-Dpolyglotimpl.AttachLibraryFailureAction=throw"])

(defn- runtime-classpath-roots [basis]
  ;; Omit deps.edn `:paths` `src/clj` from basis roots so it is not listed twice;
  ;; callers `into` `fork-clojure-sources` (and usually `class-dir`) before these roots.
  ;; With `:aliases [:repl]`, overrides replace stock clojure/spec JARs — see preceding block.
  (remove #(re-find #"(^|/)src/clj$" (str %)) (:classpath-roots basis)))

(defn- assert-standalone-truffle-jars!
  "Graal's optimizing runtime needs intact `truffle-api` and `truffle-runtime` JARs on a flat
   classpath. Shading them into an uberjar (or nesting under BOOT-INF) drops `truffleattach`
   and forces the interpreter-only fallback engine."
  [cp-roots]
  (let [files (map io/file cp-roots)
        jar-names (into []
                        (comp (filter #(.isFile ^java.io.File %))
                              (map #(.getName ^java.io.File %)))
                        files)
        api? (some #(re-matches #"truffle-api-.*\.jar" %) jar-names)
        runtime? (some #(re-matches #"truffle-runtime-.*\.jar" %) jar-names)
        nested? (some #(re-find #"(?i)nested|BOOT-INF" (str %)) cp-roots)]
    (when nested?
      (throw (ex-info "Classpath contains a nested/uber JAR; Truffle cannot load truffleattach."
                      {:classpath (vec cp-roots)})))
    (when-not api?
      (throw (ex-info "truffle-api-*.jar missing from classpath (do not shade Truffle into an uberjar)."
                      {:jars jar-names})))
    (when-not runtime?
      (throw (ex-info "truffle-runtime-*.jar missing from classpath (required for the optimizing Truffle runtime)."
                      {:jars jar-names})))))

(defn cloffle-repl
  "[AST+BYTECODE] Run CloffleRepl (interactive REPL, --demo, or a .clj file). Args: {:args []}
   Optional: :archive — if true, uses default target/clojure-core.bc (same as load-bytecode-archive);
   if a non-empty string, uses that path. Prepends -Dcloffle.core.bytecode.archive=<absolute path> so RT.init
   bootstraps clojure.core from the archive (no source fallback).
   Bytecode cache (.bc) files are loaded automatically from the classpath — run
   `clj -T:build dump-bytecode-cache` first to populate target/classes with .bc files.
   Invoke: clj -T:build cloffle-repl :args '[\"--demo\"]'
           clj -T:build cloffle-repl :archive true
           clj -T:build cloffle-repl :archive '\"/path/to/core.bc\"'"
  [{:keys [args archive compile] :or {args []}}]
  (when (true? compile)
    (time (compile-all nil)))
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:repl]})
        cp (into [class-dir fork-clojure-sources] (runtime-classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)
        archive-file (cond
                       (true? archive) (io/file "target/clojure-core.bc")
                       (and (string? archive) (seq archive)) (io/file archive)
                       :else nil)
        _ (when (and archive-file (not (.isFile archive-file)))
            (throw (ex-info (str "Archive file not found: " (.getAbsolutePath archive-file)
                                 "\nRun `clj -T:build dump-bytecode-cache` first.")
                            {:archive (.getAbsolutePath archive-file)})))
        archive-opt (when archive-file
                      [(str "-Dcloffle.core.bytecode.archive=" (.getAbsolutePath archive-file))])
        args (concat (test-jvm-opts)
                     archive-opt
                     ["-cp" cp-str
                      "net.javacrumbs.cloffle.CloffleRepl"]
                     (map str args))
        argfile (write-java-argfile args)]
    (run-interactive-process! ["java" argfile])))

(defn- ensure-jvm-task-ok!
  "If `proc` (from `b/process`) exited non-zero, throw ex-info. When stderr was `:capture`d, attach it."
  [task-label proc]
  (when-not (zero? (:exit proc))
    (let [err (:err proc)]
      (throw (ex-info (str task-label " failed"
                           (if (seq err)
                             (str "\n" err)
                             "\n(stderr was streamed to this process; see above)"))
                      (cond-> {:exit (:exit proc)}
                        (seq err) (assoc :stderr err)))))))

(defn dump-bytecode-cache
  "Dump per-file Truffle bytecode archives for all bootstrap .clj files.
   Runs RT.init from source with recording enabled, writing one .bc file per namespace
   (core.clj, core_print.clj, instant.clj, uuid.clj, etc.) into `target/classes` so
   they sit alongside the corresponding .clj files and are included in the JAR.
   The .bc files are loaded at runtime from the classpath automatically.
   Args: {:output \"target/classes\" :xmx \"8g\" :fresh false}
   Invoke: clj -T:build dump-bytecode-cache
           clj -T:build dump-bytecode-cache :output '\"out/bc-cache\"' :xmx '\"12g\"'"
  [{:keys [output xmx fresh] :or {output "target/classes" xmx "8g" fresh false}}]
  (when fresh (clean nil))
  (compile-all nil)
  (let [out-dir (io/file output)]
    (.mkdirs out-dir)
    (let [basis (b/create-basis {:project "deps.edn" :aliases [:repl]})
          cp (into [class-dir fork-clojure-sources "test"] (runtime-classpath-roots basis))
          cp-str (clojure.string/join (System/getProperty "path.separator") cp)
          args (into [(str "-Xmx" xmx)]
                     (concat (test-jvm-opts)
                             ["-cp" cp-str
                              "net.javacrumbs.cloffle.CloffleBytecodeSerializerMain"
                              "dump-bootstrap"
                              (.getAbsolutePath out-dir)]))
          argfile (write-java-argfile args)]
      (out [:bold.cyan "\n===== dump-bytecode-cache ====="])
      (let [proc (b/process {:command-args ["java" argfile]
                             :out :inherit
                             :err :inherit})]
        (ensure-jvm-task-ok! "dump-bytecode-cache" proc))
      (out (str "\nWrote bytecode cache to: " (.getAbsolutePath out-dir))))))

(defn- run-cloffle-bytecode-serializer-archive!
  "Run CloffleBytecodeSerializerMain with `main-command` (see that class for semantics)."
  [{:keys [task-label main-command archive xmx fresh]
    :or {archive "target/clojure-core.bc" xmx "8g" fresh false}}]
  (when fresh (clean nil))
  (compile-all nil)
  (let [archive-file (io/file archive)
        basis (b/create-basis {:project "deps.edn" :aliases [:repl]})
        cp (into [class-dir fork-clojure-sources "test"] (runtime-classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)
        args (into [(str "-Xmx" xmx)]
                   (concat (test-jvm-opts)
                           ["-cp" cp-str
                            "net.javacrumbs.cloffle.CloffleBytecodeSerializerMain"
                            main-command
                            (.getAbsolutePath archive-file)]))
        argfile (write-java-argfile args)]
    (out [:bold.cyan (str "\n===== " task-label " =====")])
    (let [proc (b/process {:command-args ["java" argfile]
                          :out :inherit
                          :err :capture})]
      (ensure-jvm-task-ok! task-label proc))))

(defn source-location-demo
  "[BYTECODE] Run SourceLocationDemo with the Truffle bytecode backend;
   shows per-expression source line/column in stack traces.
   Invoke: clj -T:build source-location-demo"
  [_]
  (compile-tests nil)
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:test]})
        cp (into [test-class-dir "test" "src/test/resources" class-dir fork-clojure-sources]
                 (runtime-classpath-roots basis))
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
  "[AST+BYTECODE] Run CloffleMain (clojure.main-compatible CLI). Args: {:args []}
   NOTE: For interactive REPL (-r), use 'make cloffle-main-repl' instead. tools.build's
   b/process does not support :in :inherit, so stdin is piped and the REPL hangs.
   Examples (non-interactive):
     clj -T:build cloffle-main :args '[\"-e\" \"(+ 1 2)\"]'
     clj -T:build cloffle-main :args '[\"-m\" \"my.ns\"]'
     clj -T:build cloffle-main :args '[\"script.clj\"]'"
  [{:keys [args] :or {args []}}]
  (compile-all nil)
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:repl]})
        cp (into [class-dir fork-clojure-sources "test"] (runtime-classpath-roots basis))
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

(defn cloffle-dap
  "[AST+BYTECODE] Run CloffleDapMain — starts a DAP server for VS Code debugging.
   Default port: 4711. Suspends and waits for debugger by default.
   Args: {:args []} — passed to CloffleDapMain (e.g. script file, -e, --dap-port).
   Dead-local / last-use clearing is off by default so debugger scopes keep locals.
   Pass --clear-dead-locals to opt into the optimization (same as CloffleMain).
   Examples:
     clj -T:build cloffle-dap :args '[\"script.clj\"]'
     clj -T:build cloffle-dap :args '[\"--dap-port\" \"4712\" \"script.clj\"]'
     clj -T:build cloffle-dap :args '[\"-e\" \"(+ 1 2)\"]'
     clj -T:build cloffle-dap :args '[\"--dap-no-suspend\" \"-r\"]'
     clj -T:build cloffle-dap :args '[\"--clear-dead-locals\" \"script.clj\"]'
   NOTE: For interactive REPL with working stdin, use 'clj -T:build cloffle-dap-repl' or 'make cloffle-dap-repl'."
  [{:keys [args] :or {args []}}]
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:repl :dap]})
        cp (into [class-dir fork-clojure-sources "test"] (runtime-classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)
        args (concat (test-jvm-opts)
                     ["-cp" cp-str
                      "net.javacrumbs.cloffle.CloffleDapMain"]
                     (map str args))
        argfile (write-java-argfile args)]
    (run-interactive-process! ["java" argfile])))

(defn cloffle-dap-repl
  "[AST+BYTECODE] Run CloffleDapMain with `-r` — DAP server plus interactive REPL (stdin via run-interactive-process!).
   Optional: :dap-port \"4712\", :dap-no-suspend true (same as Makefile DAP_PORT / DAP_NOSUSPEND); :args [] — extra args after `-r`.
   Invoke: clj -T:build cloffle-dap-repl
           clj -T:build cloffle-dap-repl :dap-port '\"4712\"' :dap-no-suspend true"
  [{:keys [args dap-port dap-no-suspend] :or {args []}}]
  (compile-all nil)
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:repl :dap]})
        cp (into [class-dir fork-clojure-sources "test"] (runtime-classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)
        dap-args (concat (when dap-port ["--dap-port" (str dap-port)])
                         (when dap-no-suspend ["--dap-no-suspend"])
                         ["-r"]
                         (map str args))
        args (concat (test-jvm-opts)
                     ["-cp" cp-str
                      "net.javacrumbs.cloffle.CloffleDapMain"]
                     dap-args)
        argfile (write-java-argfile args)]
    (run-interactive-process! ["java" argfile])))

(defn- assert-process-success!
  "Throws if tools.build `process` returned a non-zero :exit."
  [label {:keys [exit] :as _result}]
  (when-not (zero? exit)
    (throw (ex-info (str label " exited with code " exit) {:exit exit}))))

(defn- parse-only-var-sym
  "Coerce `:only-var` (string, symbol, or namespaced keyword) to a namespace-qualified symbol."
  [only-var]
  (when only-var
    (let [s (cond
              (symbol? only-var) only-var
              (keyword? only-var) (symbol (namespace only-var) (name only-var))
              (string? only-var) (symbol only-var)
              :else (symbol (str only-var)))]
      (when-not (namespace s)
        (throw (ex-info (str ":only-var must be namespace-qualified (e.g. cheshire.test.core/serial-writing), got: " only-var)
                        {:only-var only-var})))
      s)))

(defn run-tests
  "[BYTECODE] Run Cloffle JUnit tests (scans all test classes; execution uses the Truffle bytecode backend).
   Fails the task (non-zero exit) if any JUnit test fails.
   :fresh (default true) — run clean first so stale `target` classes cannot skew results; use false for faster incremental runs.
   Args: {:args []} — optional args passed to JUnit ConsoleLauncher (e.g. :args '[\"--select-class=my.Test\"]')."
  [opts]
  (let [{:keys [args fresh]} (merge {:fresh true :args []} opts)]
    (when fresh (clean nil))
    (compile-tests nil)
    (let [basis (b/create-basis {:project "deps.edn" :aliases [:test :dap :benchmark]})
          cp (into [benchmark-class-dir test-class-dir "test" "src/test/resources" class-dir fork-clojure-sources]
                   (runtime-classpath-roots basis))
          cp-str (clojure.string/join (System/getProperty "path.separator") cp)]
      (assert-standalone-truffle-jars! cp)
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
            java-args (concat (test-jvm-opts)
                              ["-Dclojure.use_shape_map=true"]
                              junit-opts)
            argfile (write-java-argfile java-args)
            proc (b/process
                  {:command-args ["java" argfile]
                   :out :inherit
                   :err :inherit})]
        (assert-process-success! "JUnit ConsoleLauncher" proc)
        (out (str "\nJUnit reports: " surefire-reports-dir))))))


(def ^:private cloffle-reports-dir "target/surefire-reports/cloffle")

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

(defn- surefire-xml-failing-cases
  "Returns {:suite :name :status} for testcase elements with failure or error."
  [xml-file]
  (when (.exists (io/file xml-file))
    (filter #(#{:fail :error} (:status %)) (parse-junit-xml (io/file xml-file)))))

(defn- ensure-surefire-process-ok!
  "If the JVM exited non-zero or TEST-results.xml reports failures/errors, print
  failing case names and throw."
  [label {:keys [exit]} reports-dir]
  (let [failures (or (surefire-xml-failing-cases (io/file reports-dir "TEST-results.xml")) ())
        exit-bad? (not (zero? exit))
        xml-bad? (seq failures)]
    (when (or exit-bad? xml-bad?)
      (cond
        xml-bad?
        (do (out [:bold.red (str "\n" label " — failing JUnit cases:")])
            (doseq [r failures]
              (out [:red (str "  " (:suite r) "/" (:name r) " [" (name (:status r)) "]")])))
        exit-bad?
        (out [:bold.red (str "\n" label " exited with code " exit " (missing or empty JUnit XML).")]))
      (throw (ex-info (str label " failed")
                      {:exit exit
                       :reports-dir (str reports-dir)
                       :junit-xml (.getPath (io/file reports-dir "TEST-results.xml"))
                       :failing-case-count (count failures)})))))

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

(defn- run-surefire-suite
  "Run the Clojure test suite via run_test_surefire.clj using the given main class.
  Optional `:only-namespace` is a single namespace name string (no `#{...}`); when set, discovery
  runs only that namespace. Optional `:only-var` is a fully qualified deftest symbol; when set,
  only that var is run. When `:progress` is true, passes `-Dclojure.test.progress=true` (per namespace: `require` then
  that namespace's deftests; auto-flushing writer for piped/IDE capture)."
  [main-class reports-dir cp-str exclude-ns & {:keys [only-namespace only-var progress]}]
  (let [var-sym (parse-only-var-sym only-var)
        args (concat (test-jvm-opts)
                     ;; Match upstream Clojure (macro spec checks on) for test_clojure suites.
                     ["-Dclojure.spec.check-specs=true"
                      "-Dclojure.test.quiet=true"
                      (str "-Dclojure.test-clojure.exclude-namespaces=" exclude-ns)
                      (str "-Dsurefire.reports.dir=" reports-dir)]
                     (when progress
                       ["-Dclojure.test.progress=true"])
                     (when var-sym
                       [(str "-Dclojure.test.only-var=" var-sym)])
                     (when (and only-namespace (not var-sym))
                       [(str "-Dclojure.test-clojure.only-namespace=" only-namespace)])
                     ["-cp" cp-str
                      main-class
                      "src/script/run_test_surefire.clj"])
        argfile (write-java-argfile args)
        proc (b/process
              {:command-args ["java" argfile]
               :out :inherit
               :err :inherit})]
    (ensure-surefire-process-ok! (str "Surefire (" main-class ")") proc reports-dir)))

(def ^:private generative-ns
  "Namespaces that depend on clojure.test.check (generative / property-based tests)."
  [" clojure.test-clojure.data-structures-interop"
   " clojure.test-clojure.parse"
   " clojure.test-clojure.sequences"
   " clojure.test-clojure.transducers"])

(defn- clojure-surefire-exclude
  "Default exclude set (edn string) for `run_test_surefire.clj`, matching `run-clj-tests`."
  [generative?]
  (str "#{clojure.test-clojure.compilation.load-ns"
       " clojure.test-clojure.compilation"
       " clojure.test-clojure.ns-libs-load-later"
       " clojure.test-clojure.genclass"
       " clojure.test-clojure.annotations"
       " clojure.test-clojure.clearing"
       " clojure.test-clojure.serialization"
       (when-not generative?
         (apply str generative-ns))
       "}"))

(defn run-clj-tests
  "[BYTECODE] Run Clojure's own test suite (test/clojure/test_clojure/) through Cloffle/Truffle (bytecode backend).
   Fails the task if the subprocess exits non-zero or TEST-results.xml contains failures/errors
   (lists failing case names before throwing).
   :fresh (default true) — run clean first so stale `target` classes cannot skew results; use false for faster incremental runs.
   Invoke: clj -T:build run-clj-tests
   Pprint-only (faster): clj -T:build run-clj-tests :only-namespace \"clojure.test-clojure.pprint\"
   Include generative tests: clj -T:build run-clj-tests :generative true
   Override excludes: clj -T:build run-clj-tests :exclude '\"#{ns1 ns2}\"'
   Single namespace: clj -T:build run-clj-tests :only-namespace \"clojure.test-clojure.string\"
   Single deftest: clj -T:build run-clj-tests :only-var '\"clojure.test-clojure.string/t-split\"'
   Progress (require then deftests, per namespace): clj -T:build run-clj-tests :progress true"
  [opts]
  (let [opts (merge {:fresh true} opts)
        fresh (:fresh opts)]
    (when fresh (clean nil))
    (compile-tests nil)
    (let [basis (b/create-basis {:project "deps.edn" :aliases [:test-built]})
          cp (into [class-dir test-class-dir "test" "src/test/resources" fork-clojure-sources]
                   (runtime-classpath-roots basis))
          cp-str (clojure.string/join (System/getProperty "path.separator") cp)
          _ (assert-standalone-truffle-jars! cp)
          exclude (or (:exclude opts)
                      (clojure-surefire-exclude (:generative opts)))]
      (when-not (:generative opts)
        (out [:yellow "Generative tests (test.check) skipped. Use :generative true to include."]))
      (out [:bold.cyan "\n===== Clojure test suite (via Cloffle, bytecode) ====="])
      (run-surefire-suite "clojure.main"
                          cloffle-reports-dir cp-str exclude
                          :only-namespace (:only-namespace opts)
                          :only-var (:only-var opts)
                          :progress (:progress opts)))))

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
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)
        proc-path (clojure.string/join (System/getProperty "path.separator")
                                       (:classpath-roots basis))
        src-dir (io/file "src/benchmark/java")
        sources (->> (file-seq src-dir)
                     (filter #(and (.isFile %) (.endsWith (.getName %) ".java")))
                     (map #(.getPath %)))]
    (io/make-parents (io/file benchmark-class-dir "dummy"))
    (b/process
     {:command-args (into (into ["javac" "--release" "17" "-encoding" "UTF-8"
                                 "-processorpath" proc-path
                                 "-classpath" cp-str
                                 "-s" benchmark-class-dir
                                 "-d" benchmark-class-dir]
                                javac-quiet-opts)
                          sources)
      :out :inherit
      :err :inherit})))

(def truffle-jmh-log "target/truffle-jmh.log")

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
                     ["-Djmh.ignoreLock=true"
                      (truffle-log-file-opt)
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
                          ["-Djmh.ignoreLock=true"
                           (truffle-log-file-opt)
                           "-cp" cp-str
                           "net.javacrumbs.cloffle.benchmark.ComparePerformance"]
                          cli-args)
        argfile (write-java-argfile java-args)]
    (b/process
     {:command-args ["java" argfile]
      :out :inherit
      :err :inherit})))

;; --- Allocation measurement: the scalar replacement gate --------------------
;;
;; The gate is JMH's `gc.alloc.rate.norm`, not the Graal graph, because only the
;; GC profile measures the whole program.
;;
;; Graph evidence cannot carry a gate. `relativeFrequency` is a static estimate
;; scoped to a single compilation unit, and one Clojure pipeline routinely
;; compiles into several units plus interpreted frames. Measured on
;; guestPipelineReduce: its three largest units (the benchmark root,
;; clojure.core_filter, and filter's inner fn) held 34 allocation stubs and not
;; one of them exceeded frequency 0.01, while the benchmark allocated 6168 B/op.
;; The same blindness produced the opposite error earlier, when a 9-node
;; delegating wrapper was analyzed and passed.
;;
;; So graphs answer "what allocated and where did it come from", which is what
;; you need to fix a failure, and the GC profile answers "does it matter", which
;; is what you need to gate one. See HOWTO_SEAFOAM.md.

(defn- read-jmh-json
  "Parse a JMH `-rf json` result file into a vector of maps:
   [{:benchmark \"fully.qualified.Benchmark.method\"
     :mode \"thrpt\"
     :params {\"name\" \"keyword-invoke\"}
     :score .. :score-unit .. :alloc-norm ..}].

   :alloc-norm is `gc.alloc.rate.norm` in B/op, present only when the run used
   `-prof gc`. Jackson is already on the :build classpath via seafoam-jruby."
  [path]
  (let [mapper (com.fasterxml.jackson.databind.ObjectMapper.)
        entries (.readValue mapper (io/file path) java.util.List)
        score (fn [m] (when (instance? java.util.Map m)
                        (try (Double/parseDouble (str (.get ^java.util.Map m "score")))
                             (catch Exception _ nil))))
        to-clj (fn [obj]
                 (when (instance? java.util.Map obj)
                   (into {} (for [[k v] ^java.util.Map obj]
                              [(str k) (str v)]))))]
    (mapv (fn [^java.util.Map entry]
            (let [primary (.get entry "primaryMetric")
                  secondary (.get entry "secondaryMetrics")]
              {:benchmark (str (.get entry "benchmark"))
               :mode (when (.get entry "mode") (str (.get entry "mode")))
               :params (or (to-clj (.get entry "params")) {})
               :score (score primary)
               :score-unit (when (instance? java.util.Map primary)
                             (str (.get ^java.util.Map primary "scoreUnit")))
               :alloc-norm (score (when (instance? java.util.Map secondary)
                                    (.get ^java.util.Map secondary "gc.alloc.rate.norm")))}))
          entries)))

(defn- measure-allocation
  "Run JMH under `-prof gc` and return {:ok bool :results [..] :error msg}.

   Deliberately does not dump Graal graphs: dumping writes hundreds of megabytes,
   dominates the runtime, and perturbs the thing being measured. The diagnostic
   dump is a separate run that only happens once a benchmark has already failed."
  [{:keys [benchmark params mode warmup iterations warmup-time time quiet compile]
    :or {warmup 3 iterations 3 warmup-time "2s" time "3s" quiet true compile true}}]
  (let [json (io/file (System/getProperty "java.io.tmpdir")
                      (format "cloffle-jmh-%d.json" (System/nanoTime)))
        param-args (mapcat (fn [[k v]] ["-p" (str (name k) "=" v)]) params)
        mode-args (when mode ["-bm" (str mode)])
        proc (run-benchmarks {:args (concat [benchmark]
                                            param-args
                                            mode-args
                                            ["-wi" (str warmup) "-i" (str iterations)
                                             "-w" (str warmup-time) "-r" (str time) "-f" "1"
                                             "-prof" "gc"
                                             "-rf" "json" "-rff" (.getAbsolutePath json)])
                              :compile compile
                              :out (if quiet :capture :inherit)
                              :err (if quiet :capture :inherit)})
        ;; Under :quiet the JMH output is captured rather than streamed, so a
        ;; failure would otherwise be reported as a bare exit code. Keep it: the
        ;; cause is usually in there (a concurrent `run-tests` cleaning `target`
        ;; out from under the fork looks exactly like an unexplained exit 1).
        failed (fn [msg]
                 (let [text (str (:out proc) "\n" (:err proc))
                       log (when (seq (clojure.string/trim text))
                             (let [f (io/file (System/getProperty "java.io.tmpdir")
                                              (format "cloffle-jmh-%d.log" (System/nanoTime)))]
                               (try (spit f text) (.getAbsolutePath f)
                                    (catch Throwable _ nil))))
                       informative-line? (fn [line]
                                           (not (or (clojure.string/blank? line)
                                                    (re-find #"Blackhole mode" line)
                                                    (re-find #"modes can be very significant" line)
                                                    (re-find #"Please make sure you use the consistent" line)
                                                    (re-find #"^NOTE:" line))))
                       lines (remove clojure.string/blank? (clojure.string/split-lines text))
                       filtered-lines (clojure.core/filter informative-line? lines)
                       tail-lines (take-last 3 (if (seq filtered-lines) filtered-lines lines))
                       tail (clojure.string/join " | " tail-lines)]
                   {:ok false
                    :error (cond-> msg
                             (seq tail) (str ": " tail)
                             log (str " (full output: " log ")"))
                    :output text}))]
    (try
      (cond
        (not (zero? (:exit proc)))
        (failed (str "JMH exited with code " (:exit proc)))

        (not (.isFile json))
        (failed "JMH wrote no JSON result file")

        :else
        (let [results (read-jmh-json json)]
          (if (empty? results)
            (failed (str "No benchmark matched " benchmark))
            {:ok true :results results})))
      (catch Throwable t
        {:ok false :error (str "could not read JMH results: " (.getMessage t))})
      (finally
        (.delete json)))))

(defn- find-benchmark-result
  "Find entry in JMH results matching `benchmark` (FQN or Class.method suffix),
   and optionally matching `:params` and `:mode`."
  ([results benchmark]
   (find-benchmark-result results benchmark nil nil))
  ([results benchmark expected-params expected-mode]
   (let [suffix (str "." benchmark)
         name-match? (fn [{:keys [benchmark]}]
                       (or (= benchmark suffix)
                           (= benchmark (clojure.string/replace suffix #"^\." ""))
                           (clojure.string/ends-with? benchmark suffix)))
         params-match? (fn [{:keys [params]}]
                         (if (empty? expected-params)
                           true
                           (every? (fn [[k v]]
                                     (= (str (get params (str (name k)))) (str v)))
                                   expected-params)))
         mode-match? (fn [{:keys [mode]}]
                       (if (nil? expected-mode)
                         true
                         (= (str mode) (str expected-mode))))
         candidates (cond->> results
                      true (clojure.core/filter name-match?)
                      (seq expected-params) (clojure.core/filter params-match?))]
     (or (first (clojure.core/filter mode-match? candidates))
         (first candidates)
         ;; Fallback for single-result runs
         (when (= 1 (count results)) (first results))))))

(def ^:private zero-alloc-epsilon
  "B/op at or below this counts as zero. A fully scalar replaced benchmark is
   reported by JMH as ~10^-6 B/op rather than exactly 0."
  1.0)

(defn- alloc-tolerance
  "Allowed overage above a recorded budget: relative for large budgets, with an
   absolute floor so a 24 B/op budget is not held to 2.4 B/op of run-to-run noise."
  [budget]
  (max (* 0.10 (double budget)) 8.0))

(defn- alloc-verdict
  "Compare measured B/op against a budget.

   A nil budget means the benchmark is unbudgeted: report the measurement and
   pass, so the catalog can be filled in incrementally by record-alloc-budgets
   without every unrecorded benchmark failing at once."
  [measured budget]
  (cond
    (nil? measured)
    {:ok false :status :no-measurement
     :message "JMH reported no gc.alloc.rate.norm; was -prof gc dropped?"}

    (nil? budget)
    {:ok true :status :unbudgeted :measured measured
     :message (format "%.1f B/op measured, no :alloc-budget recorded" measured)}

    :else
    (let [budget (double budget)
          limit (+ budget (alloc-tolerance budget))]
      (if (or (<= measured zero-alloc-epsilon) (<= measured limit))
        {:ok true :status :within-budget :measured measured :budget budget
         :message (format "%.1f B/op (budget %.1f)" measured budget)}
        {:ok false :status :over-budget :measured measured :budget budget
         :message (format "%.1f B/op exceeds budget %.1f (limit %.1f)"
                          measured budget limit)}))))

(def ^:private graal-alloc-markers
  ["CommitAllocationNode" "CommitAllocation"
   "NewInstanceNode" "NewArrayNode"
   "new_instance_or_null" "new_array_or_null"
   "TruffleNew" "AllocatingBoxNode" "BoxNode$AllocatingBox"])

(defn- find-phase [phases needle]
  (->> phases
       (filter #(clojure.string/includes? (:name %) needle))
       last))

(defn- marker-hits [text]
  (vec (filter #(clojure.string/includes? (or text "") %) graal-alloc-markers)))

(def ^:private graal-alloc-search-terms
  "Low-tier allocs are ForeignCall descriptors, omitted from node-count histograms."
  ["new_instance_or_null" "new_array_or_null"
   "CommitAllocation" "NewInstanceNode" "NewArrayNode"
   "TruffleNew" "AllocatingBox"])

(defn- bgv-list [^BgvDump dump]
  (mapv (fn [graph]
          {:index (.index graph)
           :name (.name graph)})
        (.listGraphs dump)))

(defn- bgv-describe [^BgvDump dump phase]
  (when phase
    (.describe dump (int (:index phase)))))

(defn- bgv-search-text [^BgvDump dump phase]
  (when phase
    (clojure.string/join
     "\n"
     (for [term graal-alloc-search-terms
           hit (.search dump (int (:index phase)) term)]
       (str term " -> " (.snippet hit))))))

(defn- describe-marker-hits [described]
  (if-not described
    []
    (let [node-classes (keys (.nodeCounts described))]
      (vec
       (filter (fn [marker]
                 (some #(clojure.string/includes? % marker) node-classes))
               graal-alloc-markers)))))

(defn- node-total [described]
  (when described
    (reduce + 0 (vals (into {} (.nodeCounts described))))))

(defn- inspect-bgv
  "Inspect one .bgv compilation in-process. Returns a result map with :ok true/false."
  [bgv]
  (with-open [dump (BgvDump/open (.toPath (io/file bgv)))]
    (let [listed (bgv-list dump)
          ;; A dump the JVM was still writing when it exited stops mid-stream. The
          ;; phases before the cut are valid, but a missing allocation may simply be
          ;; one that was never written, so a pass here would be meaningless.
          truncated? (.isTruncated dump)
          exception? (some #(clojure.string/includes? (:name %) "Exception") listed)
          parsing (find-phase listed "After parsing")
          pea (find-phase listed "FinalPartialEscapePhase")
          low (or (find-phase listed "After low tier")
                  (find-phase listed "/After phase jdk.graal.compiler.core.phases.LowTier"))
          parsing-desc (bgv-describe dump parsing)
          pea-desc (bgv-describe dump pea)
          low-desc (bgv-describe dump low)
          low-search (bgv-search-text dump low)
          pea-search (bgv-search-text dump pea)
          low-hits (vec (distinct (concat (describe-marker-hits low-desc)
                                          (marker-hits low-search))))
          pea-hits (vec (distinct (concat (describe-marker-hits pea-desc)
                                          (marker-hits pea-search))))
          ok (and (not exception?)
                  (not truncated?)
                  (some? low)
                  (empty? low-hits))]
      {:ok ok
       :bgv bgv
       :exception? (boolean exception?)
       :truncated? truncated?
       :low-nodes (node-total low-desc)
       :pea-nodes (node-total pea-desc)
       :phases (count listed)
       :search (str "PEA:\n" pea-search "\nLOW:\n" low-search)
       :parsing (when parsing
                  (assoc parsing
                         :describe (.summary parsing-desc)
                         :hits (describe-marker-hits parsing-desc)))
       :final-pea (when pea (assoc pea :describe (.summary pea-desc) :hits pea-hits))
       :low-tier (when low (assoc low :describe (.summary low-desc) :hits low-hits))})))

;; --- Allocation explanation -------------------------------------------------
;;
;; inspect-bgv answers whether anything still allocates. These answer what, and
;; where it came from, which is what you actually need to fix one.
;;
;; Node selection is by node class rather than by searching property text:
;; search matches anywhere in a node's serialized properties, so a type name
;; matches nodes that merely mention it (searching a real PEA graph for
;; "ClojureClosure" returned 957 hits, nearly all irrelevant). See
;; HOWTO_SEAFOAM.md.

(defn- nodes-of-class
  "Nodes in a phase whose node class is one of `class-names` (simple names)."
  [^BgvDump dump index class-names]
  (->> (.nodes dump (int index))
       (filter (fn [node] (some #(.isClass node ^String %) class-names)))))

(defn- source-frames
  "The nodeSourcePosition chain, innermost first, as \"Class#method\" strings.
   Properties arrive through Jackson as java.util.Map, for which clojure.core/map?
   is false, so the guard tests the Java interface."
  [pos]
  (loop [pos pos, acc [], depth 0]
    (if (or (not (instance? java.util.Map pos)) (>= depth 24))
      acc
      (let [m (get pos "method")
            frame (when (instance? java.util.Map m)
                    (str (get m "declaring_class") "#" (get m "method_name")))]
        (recur (get pos "caller")
               (cond-> acc frame (conj frame))
               (inc depth))))))

(defn- virtual-object
  "Describe one virtual object. VirtualInstanceNode carries `type`;
   VirtualArrayNode carries `componentType` and `length` and has no `type`."
  [^BgvDump dump index node]
  (let [props (.nodeProps dump (int index) (int (.id node)))
        t (get props "type")
        component (get props "componentType")]
    {:id (.id node)
     :array? (some? component)
     :type (or t (str component "[" (get props "length") "]"))
     :length (get props "length")
     :frames (source-frames (get props "nodeSourcePosition"))}))

(defn- relative-frequency [^BgvDump dump index node-id]
  (when-let [f (get (.nodeProps dump (int index) (int node-id)) "relativeFrequency")]
    (try (Double/parseDouble (str f)) (catch Exception _ nil))))

;; --- Why an allocation survived --------------------------------------------
;;
;; The source frames above say what allocated an object. They do not say why PEA
;; had to materialize it, and when the source position is an innocent-looking
;; vector literal or let init that is the only question that matters. The answer
;; is in the object's *usages*: something consumes the materialized value in a
;; place a virtual object cannot go.
;;
;; The case that motivated this: a PersistentTuple2 whose single usage was a phi
;; on the bytecode dispatch loop's LoopBeginNode. A loop phi mixing null with an
;; object cannot stay virtual, and the object was loop-carried only because a
;; destructuring temp stayed live in its frame slot after its last read. Finding
;; that by hand took a one-off probe; this reports it. See HOWTO_SEAFOAM.md.

(defn- node-by-id
  "id -> NodeInfo for one phase. Built once per phase; .nodes is a full scan."
  [^BgvDump dump index]
  (into {} (map (juxt #(.id %) identity)) (.nodes dump (int index))))

(defn- class-of [nodes id]
  (when-let [n (get nodes id)] (str (.nodeClass n))))

(defn- simple-class [cls]
  (last (clojure.string/split (str cls) #"[.$]")))

(defn- merge-kind
  "Whether a phi merges at a loop header or at an ordinary branch join.
   Returns :loop, :branch, or nil when the merge cannot be identified."
  [^BgvDump dump index nodes phi-id]
  (let [merges (->> (.inputs (.nodeEdges dump (int index) (int phi-id)))
                    (keep #(class-of nodes (.from %)))
                    (filter #(or (clojure.string/includes? % "LoopBegin")
                                 (clojure.string/includes? % "Merge"))))]
    (cond
      (some #(clojure.string/includes? % "LoopBegin") merges) :loop
      (seq merges) :branch
      :else nil)))

(defn- allocated-object-nodes
  "AllocatedObjectNodes for one virtual object. PEA rewrites consumers of a
   materialized object to read this node, so the real usages hang off it rather
   than off the VirtualInstanceNode, which by then is only referenced by the
   commit and by deoptimization state."
  [^BgvDump dump index nodes object-id]
  (->> (vals nodes)
       (filter #(.isClass % "AllocatedObjectNode"))
       (filter (fn [n]
                 (some #(= object-id (.from %))
                       (.inputs (.nodeEdges dump (int index) (int (.id n)))))))
       (mapv #(.id %))))

(def ^:private uninformative-usage?
  "Usages that are the materialization itself or only describe the object at a
   safepoint. They are the mechanism, not the cause, so they are noise here."
  #(contains? #{"FrameState" "VirtualObjectState" "MaterializedObjectState"
                "CommitAllocationNode" "AllocatedObjectNode"}
              (simple-class %)))

(defn- survival-reasons
  "Usages of a committed object that explain why it could not stay virtual,
   as [{:id :class :edge :merge}], state-describing usages removed."
  [^BgvDump dump index nodes object-id]
  (->> (cons object-id (allocated-object-nodes dump index nodes object-id))
       (mapcat (fn [src] (.outputs (.nodeEdges dump (int index) (int src)))))
       (keep (fn [e]
               (when-let [cls (class-of nodes (.to e))]
                 (when-not (uninformative-usage? cls)
                   {:id (.to e)
                    :class (simple-class cls)
                    :edge (str (get (.props e) "name"))
                    :merge (when (clojure.string/includes? cls "PhiNode")
                             (merge-kind dump index nodes (.to e)))}))))
       distinct
       vec))

(defn- explain-reason
  "One-line interpretation of a usage, or nil when there is nothing to add."
  [{:keys [class merge]}]
  (cond
    (= :loop merge)
    "loop-carried: a phi at a loop header cannot stay virtual. In guest code that loop is usually the Bytecode DSL dispatch loop, and the object is in the interpreter state at the merge -- check whether a frame slot stays live past its last read."

    (= :branch merge)
    "merged across branches: PEA materializes when the arms disagree on the object."

    (clojure.string/includes? class "Return")
    "returned from this compilation unit, so it escapes by definition."

    (or (clojure.string/includes? class "Invoke")
        (clojure.string/includes? class "Call"))
    "passed to a call that was not inlined; check Call Tree / After Inline."

    (or (clojure.string/includes? class "Store")
        (clojure.string/includes? class "Write"))
    "written to the heap, which is an unconditional escape."

    (or (clojure.string/includes? class "ArrayCopy")
        (clojure.string/includes? class "ArrayFill")
        (clojure.string/includes? class "Unsafe"))
    "consumed by a raw memory operation, which needs a real object with an address."

    :else nil))

(defn- commit-allocations
  "Each CommitAllocationNode with the virtual objects it materializes.

   A commit references its objects through `virtualObjects` input edges and their
   field values through `values` edges; only the former are the objects being
   allocated, so the edge's slot name is what separates them."
  [^BgvDump dump index]
  (let [nodes (node-by-id dump index)
        virtuals (into {} (for [n (nodes-of-class dump index ["VirtualInstanceNode"
                                                              "VirtualArrayNode"])]
                            [(.id n) n]))]
    (for [commit (nodes-of-class dump index ["CommitAllocationNode"])]
      {:id (.id commit)
       :frequency (relative-frequency dump index (.id commit))
       :objects (->> (.inputs (.nodeEdges dump (int index) (int (.id commit))))
                     (filter #(= "virtualObjects" (str (get (.props %) "name"))))
                     (keep #(get virtuals (.from %)))
                     distinct
                     (mapv (fn [n]
                             (assoc (virtual-object dump index n)
                                    :reasons (survival-reasons dump index nodes (.id n))))))})))

(defn- foreign-call-allocations
  "Low-tier surviving allocations. After lowering these are ForeignCallNodes whose
   descriptor names an allocation stub, so they carry no virtual-object list."
  [^BgvDump dump index]
  (for [node (nodes-of-class dump index ["ForeignCallNode"])
        :let [props (.nodeProps dump (int index) (int (.id node)))
              descriptor (str (get props "descriptorName"))]
        :when (or (clojure.string/includes? descriptor "new_instance")
                  (clojure.string/includes? descriptor "new_array"))]
    {:id (.id node)
     :descriptor descriptor
     :frequency (relative-frequency dump index (.id node))
     :frames (source-frames (get props "nodeSourcePosition"))}))

(defn- explain-bgv
  "Itemize what a compilation allocates, at PEA and after low-tier lowering."
  [bgv]
  (with-open [dump (BgvDump/open (.toPath (io/file bgv)))]
    (let [listed (bgv-list dump)
          pea (find-phase listed "FinalPartialEscapePhase")
          low (or (find-phase listed "After low tier")
                  (find-phase listed "/After phase jdk.graal.compiler.core.phases.LowTier"))]
      {:bgv bgv
       :truncated? (.isTruncated dump)
       :pea-phase pea
       :low-phase low
       ;; Everything virtual at PEA, whether or not it is later committed: the
       ;; contrast between this and :commits is what shows PEA doing its job.
       :virtuals (when pea
                   (mapv #(virtual-object dump (:index pea) %)
                         (nodes-of-class dump (:index pea)
                                         ["VirtualInstanceNode" "VirtualArrayNode"])))
       :commits (when pea (vec (commit-allocations dump (:index pea))))
       :foreign-calls (when low (vec (foreign-call-allocations dump (:index low))))})))

(def ^:private cold-path-frequency
  "At or below this, an allocation is on an uncommon or deoptimization path."
  0.05)

(defn- print-frames [frames indent]
  (doseq [[i frame] (map-indexed vector (take 4 frames))]
    (out [:cyan (str indent (apply str (repeat i "  ")) frame)])))

(defn- print-survival-reasons
  "Print why one object had to be materialized. Loop phis come first: they are the
   diagnosis most likely to be actionable and least likely to be guessed."
  [reasons]
  (if (empty? reasons)
    ;; PEA commits an object either because something escapes it or because a
    ;; committed object references it. No escaping usage means the second, so the
    ;; thing to chase is whichever object in this commit does have one.
    (out [:yellow "      no escaping usage of its own; reachable from another object in this commit"])
    (let [ranked (sort-by (fn [{:keys [merge]}] (case merge :loop 0 :branch 1 2)) reasons)]
      (doseq [{:keys [id class edge merge] :as reason} (take 4 ranked)]
        (out [:yellow (str "      used by " class " #" id
                           (when (seq edge) (str " (" edge ")"))
                           (when merge (str " [" (name merge) " merge]")))])
        (when-let [why (explain-reason reason)]
          (out [:yellow (str "        " why)])))
      (when (> (count ranked) 4)
        (out (str "      ... and " (- (count ranked) 4) " more usage(s)"))))))

(defn- print-type-summary [label items]
  (out [:bold (str "  " label " (" (count items) ")")])
  (doseq [[t n] (sort-by (comp - val) (frequencies (map :type items)))]
    (out (str "    " n " x " t))))

(defn- print-explanation [{:keys [truncated? pea-phase low-phase virtuals commits
                                 foreign-calls]}]
  (when truncated?
    (out [:red "  dump is TRUNCATED; phases after the cut are missing entirely"]))

  (if-not pea-phase
    (out [:yellow "  no FinalPartialEscapePhase in this dump (economy tier never runs PEA)"])
    (let [committed (mapcat :objects commits)
          committed-ids (set (map :id committed))
          eliminated (remove #(committed-ids (:id %)) virtuals)]
      (out [:bold.cyan "PEA phase [" (:index pea-phase) "]"])
      (out (str "  " (count virtuals) " virtual objects, "
                (count eliminated) " scalar replaced, "
                (count committed) " committed to the heap"))
      (when (seq eliminated)
        (print-type-summary "eliminated" eliminated))
      (when (empty? committed)
        (out [:green "  nothing survives PEA in this compilation unit"])
        (out [:yellow "  If the benchmark still allocates, the bytes are elsewhere: a sibling"])
        (out [:yellow "  compilation unit, interpreted code, repeated deoptimization, or the host harness."]))
      (when (seq committed)
        (out [:red (str "  SURVIVING (" (count committed) ")")])
        (doseq [{:keys [id frequency objects]} commits
                :when (seq objects)]
          (out [:red (str "  commit " id
                          (when frequency
                            (format " (relativeFrequency %.4f%s)" frequency
                                    (if (<= frequency cold-path-frequency)
                                      ", cold/deopt path" ""))))])
          (doseq [{:keys [type frames reasons]} objects]
            (out (str "    " type))
            (print-frames frames "      ")
            (print-survival-reasons reasons))))))

  (if-not low-phase
    (out [:yellow "  no low-tier phase in this dump"])
    (do
      (out [:bold.cyan "Low tier [" (:index low-phase) "]"])
      (if (empty? foreign-calls)
        (out [:green "  no allocation stubs; nothing reaches the heap"])
        (do
          (out [:red (str "  " (count foreign-calls) " allocation stub call(s)")])
          (doseq [{:keys [id descriptor frequency frames]} foreign-calls]
            (out [:red (str "  " id " " descriptor
                            (when frequency
                              (format " (relativeFrequency %.4f%s)" frequency
                                      (if (<= frequency cold-path-frequency)
                                        ", cold/deopt path" ""))))])
            (print-frames frames "      "))))))
  nil)

;; Below this, a guest graph is too small to hold the work the benchmark times, so a
;; clean result means the compilation being analyzed is not the one doing the work.
(def ^:private suspiciously-small-graph 25)

(defn- print-inspect-result [result]
  (out [:bold (if (:ok result) [:green "PASS"] [:red "FAIL"]) "  " (:bgv result)])
  (when (:exception? result)
    (out [:red "  compilation graph contains Exception"]))
  (when (:truncated? result)
    (out [:red "  dump is TRUNCATED: the JVM exited mid-write, so later phases are missing"])
    (out [:red "  an absent allocation here may simply never have been written"]))
  (when (and (:ok result)
             (:low-nodes result)
             (< (:low-nodes result) suspiciously-small-graph))
    (out [:yellow "  WARNING: low tier has only " (:low-nodes result) " nodes."])
    (out [:yellow "  A graph this small cannot contain a benchmark's work; this pass is likely vacuous."]))
  (doseq [[label phase] [["After parsing" (:parsing result)]
                         ["FinalPartialEscapePhase" (:final-pea result)]
                         ["After low tier" (:low-tier result)]]
          :when phase]
    (out (str "  " label " [" (:index phase) "]: " (:describe phase)
              (when (seq (:hits phase))
                (str "  alloc=" (pr-str (:hits phase)))))))
  ;; On failure, itemize what allocates and where it came from. The raw search
  ;; snippets this used to print were truncated property JSON that named neither
  ;; a type nor a source location.
  (when (or (not (:ok result)) (seq (:hits (:low-tier result))))
    (try
      (print-explanation (explain-bgv (:bgv result)))
      (catch Throwable e
        (out [:yellow "  could not itemize allocations: " (.getMessage e)])
        (when-let [snippets (:search result)]
          (out [:yellow "  alloc search snippets:\n" snippets])))))
  (when (and (not (:ok result)) (empty? (:hits (:low-tier result))) (nil? (:low-tier result)))
    (out [:red "  missing After low tier phase"]))
  result)

(defn- failure-message [result]
  (cond
    (:truncated? result)
    "Dump is truncated (JVM exited mid-write); the graph is incomplete and proves nothing."

    (:exception? result)
    "Compilation graph contains an Exception."

    (nil? (:low-tier result))
    "No low-tier phase in the dump; nothing to check."

    :else
    "Low-tier graph still contains allocation nodes (scalar replacement failed)."))

(defn- list-bgv-files [dir]
  (->> (file-seq (io/file dir))
       (filter #(and (.isFile ^java.io.File %)
                     (clojure.string/ends-with? (.getName ^java.io.File %) ".bgv")))
       (map #(.getPath ^java.io.File %))
       sort))

(defn- select-bgv-files
  "Pick host HotSpot compilations of `method`, or Truffle guest graphs when `guest`.
   `guest-hint` is a substring of the Truffle compilation name (Clojure fn name)."
  [files {:keys [guest method guest-hint]}]
  (let [quoted (java.util.regex.Pattern/quote method)
        method-re (re-pattern quoted)
        hint-re (when (and guest-hint (seq guest-hint))
                  (re-pattern (java.util.regex.Pattern/quote guest-hint)))]
    (filter (fn [p]
              (if guest
                (and (re-find #"TruffleHotSpotCompilation" p)
                     (or (nil? hint-re) (re-find hint-re p)))
                (and (re-find #"HotSpotCompilation-" p)
                     (not (re-find #"HotSpotOSRCompilation" p))
                     (not (re-find #"_jmhTest" p))
                     (re-find method-re p))))
            files)))

(defn- summarize-bgv
  "Cheap per-file summary used to choose between compilations of the same root node."
  [path]
  (let [size (.length (io/file path))]
    (try
      (with-open [dump (BgvDump/open (.toPath (io/file path)))]
        (let [listed (bgv-list dump)]
          {:path path
           :size size
           :graphs (count listed)
           :truncated? (.isTruncated dump)
           :pea? (some? (find-phase listed "FinalPartialEscapePhase"))
           :low? (some? (or (find-phase listed "After low tier")
                            (find-phase listed "/After phase jdk.graal.compiler.core.phases.LowTier")))}))
      (catch Throwable e
        {:path path :size size :graphs 0 :truncated? false :pea? false :low? false
         :error (.getMessage e)}))))

(defn- pick-best-bgv
  "Choose which compilation of a root node to analyze, and summarize the alternatives.

   A hot method is compiled more than once: economy-tier compilations never run PEA,
   and a tier can be recompiled after a deoptimization into a near-empty graph. Only a
   complete compilation that ran PEA and reached low tier says anything about
   allocation, so those are preferred and the largest is taken, more inlined code being
   the better evidence. Selecting on graph count instead, as this once did, picks a
   9-node deoptimized recompile over the real one and reports a vacuous pass.

   Returns [chosen-path candidates]."
  [bgvs]
  (when (seq bgvs)
    (let [candidates (mapv summarize-bgv bgvs)
          usable (filter #(and (not (:truncated? %)) (:pea? %) (:low? %) (nil? (:error %)))
                         candidates)
          chosen (->> (if (seq usable) usable (remove :error candidates))
                      (sort-by (juxt :size :graphs))
                      last)]
      [(:path chosen) candidates])))

(defn- print-candidate-warnings [chosen candidates]
  (when (> (count candidates) 1)
    (out [:cyan "  " (count candidates) " compilations matched; analyzing the largest complete one"]))
  (let [chosen-size (:size (first (filter #(= (:path %) chosen) candidates)))
        truncated (filter :truncated? candidates)
        unreadable (filter :error candidates)
        bigger (filter #(> (:size %) (or chosen-size 0)) (concat truncated unreadable))]
    (doseq [c unreadable]
      (out [:red "  unreadable: " (:path c) " (" (:error c) ")"]))
    (when (seq truncated)
      (out [:yellow "  " (count truncated) " of " (count candidates)
            " matching compilations are truncated and were skipped"]))
    (when (seq bigger)
      (out [:red "  WARNING: " (count bigger) " skipped compilation(s) are LARGER than the one analyzed"])
      (out [:red "  The real hot compilation was probably lost; treat this result as inconclusive."]))))

(defn analyze-graal-graph
  "Inspect a dumped .bgv file for surviving allocations after PEA / low-tier lowering.
   Invoke: clj -T:build analyze-graal-graph :bgv '\"path/to/file.bgv\"'"
  [{:keys [bgv]}]
  (when-not (and (string? bgv) (.isFile (io/file bgv)))
    (throw (ex-info "analyze-graal-graph requires :bgv pointing at an existing .bgv file"
                    {:bgv bgv})))
  (let [result (print-inspect-result (inspect-bgv bgv))]
    (when-not (:ok result)
      (throw (ex-info (failure-message result) result)))
    result))

(def ^:private guest-compilation-hints
  "JMH method name -> substring of TruffleHotSpotCompilation graph file names."
  {"guestShapeMapEphemeralPipeline" "guest-ephemeral-pipeline"
   "guestShapeMapEphemeralInsert" "guest-ephemeral-insert"
   "guestShapeMapEphemeralPromote8" "guest-ephemeral-promote8"
   "guestTupleDestructure" "guest-tuple-destructure"
   "guestListEphemeralPipeline" "guest-list-ephemeral-pipeline"
   "guestLazySeqFirst" "guest-lazy-seq-first"
   "guestConsFirst" "guest-cons-first"
   "guestLazySeqConsFirst" "guest-lazy-seq-cons-first"
   "guestLazySeqApplyFirst" "guest-lazy-seq-apply-first"
   "guestLazySeqWhenSeqFirst" "guest-lazy-seq-when-seq-first"
   "guestMapFirst" "guest-map-first"
   "guestMapSecond" "guest-map-second"
   "guestMappedVectorReduce" "guest-mapped-vector-reduce"
   "guestMappedMapFirst" "guest-mapped-map-first"
   "guestStreamSeqPipeline" "guest-stream-seq-pipeline" ; transducer control pipeline
   "guestPipelineInto" "guest-pipeline-into"
   "guestPipelineVec" "guest-pipeline-vec"
   "guestPipelineReduce" "guest-pipeline-reduce"
   "guestPipelineTakeDrop" "guest-pipeline-take-drop"
   "guestPipelineXformControl" "guest-pipeline-xform-control"
   "guestTuple2Transform" "guest-tuple2-transform"
   "guestKwargsDestructure" "guest-kwargs-destructure"
   "guestMiddlewarePipeline" "guest-middleware-pipeline"
   "guestCondOptionPipeline" "guest-cond-option-pipeline"
   "guestEventEnrichPipeline" "guest-event-enrich"
   "guestShapeMapEphemeralDissoc" "guest-ephemeral-dissoc"
   "guestEventSanitizePipeline" "guest-event-sanitize"
   "guestRingResponsePipeline" "guest-ring-pipeline"
   "guestRingRequestNested" "guest-ring-request-nested"
   "guestFhirPatientNested" "guest-fhir-patient-nested"
   "guestJsonapiDocumentNested" "guest-jsonapi-document-nested"
   "guestAppEntity16" "guest-app-entity-16"
   "guestHiccupNormalizeTag" "guest-hiccup-normalize"
   "guestCheshireFieldNamePipeline" "guest-cheshire-field-name"
   "guestGetInEphemeralPipeline" "guest-get-in-ephemeral-pipeline"})

(defn- dump-graal-graphs
  "Run a benchmark under -Djdk.graal.Dump and pick the compilation to analyze.

   Returns {:ok bool :bgv path :files [..] :candidates [..] :error msg}; never
   throws, because callers use this for diagnosis and a missing graph should not
   mask the failure that sent them here.

   This is the expensive path: dumping writes hundreds of megabytes and slows the
   run by an order of magnitude, so it runs only on a failure or on explicit
   request, never as part of the gate."
  [{:keys [benchmark params mode guest dump-path guest-hint quiet compile
           warmup iterations warmup-time time]
    :or {guest false dump-path "target/graal-dumps-pea" quiet false compile true
         ;; Long enough that the final-tier compilation finishes and its dump is
         ;; fully written before the JVM exits. Shorter runs leave the most
         ;; interesting compilation truncated, and selection then falls back to a
         ;; tiny deoptimized recompile whose clean result means nothing.
         warmup 3 iterations 2 warmup-time "2s" time "3s"}}]
  (let [method (last (clojure.string/split benchmark #"\."))
        hint (or guest-hint (get guest-compilation-hints method))
        dump-dir (io/file dump-path)
        filter-spec (if guest
                      (if hint
                        (str "*CloffleBytecode*,*" hint "*")
                        "*CloffleBytecode*")
                      (str "*" method "*"))
        jvm-dump (str "-Djdk.graal.Dump=:2 -Djdk.graal.PrintGraph=File -Djdk.graal.DumpPath="
                      (.getAbsolutePath dump-dir)
                      " -Djdk.graal.MethodFilter=" filter-spec)]
    (b/delete {:path dump-path})
    (.mkdirs dump-dir)
    (when-not quiet
      (out [:bold.cyan "Dumping Graal graphs for " benchmark
            (when guest (str " (guest filter " filter-spec ")"))
            " (wi=" warmup " i=" iterations ")"]))
    (let [proc (run-benchmarks {:args (concat
                                       [benchmark]
                                       ;; Without these a @Param'd benchmark dumps every
                                       ;; value of the parameter: SnippetBenchmark.cloffle
                                       ;; would run all ~40 snippets and mix their graphs
                                       ;; into one directory.
                                       (mapcat (fn [[k v]] ["-p" (str (name k) "=" v)]) params)
                                       (when mode ["-bm" (str mode)])
                                       ["-wi" (str warmup) "-i" (str iterations)
                                        "-w" (str warmup-time) "-r" (str time) "-f" "1"
                                        "-jvmArgsAppend" jvm-dump])
                                :compile compile
                                :out (if quiet :capture :inherit)
                                :err (if quiet :capture :inherit)})]
      (when (and quiet (or (:out proc) (:err proc)))
        (try
          (spit (io/file dump-path "jmh.log") (str (:out proc) "\n" (:err proc)))
          (catch Throwable _ nil)))
      (if-not (zero? (:exit proc))
        {:ok false :error (str "JMH benchmark process exited with code " (:exit proc))}
        (let [files (list-bgv-files dump-path)
              hinted (select-bgv-files files {:guest guest :method method :guest-hint hint})
              ;; Retry without the hint whenever it matched nothing, not only when
              ;; there was no hint. A :snippet evaluates an anonymous guest form, so
              ;; its root is never named after the snippet and the hint cannot match;
              ;; skipping the retry left the only diagnosable dump on disk unselected
              ;; and reported "No matching compilation graph" after a multi-minute run.
              fell-back? (and guest (some? hint) (empty? hinted))
              selected (cond
                         (seq hinted) hinted
                         guest (select-bgv-files files {:guest true :method method
                                                        :guest-hint nil})
                         :else hinted)
              [bgv candidates] (pick-best-bgv selected)]
          (when (and fell-back? bgv (not quiet))
            (out [:yellow "  no guest root matched '" hint
                  "'; analyzing the largest guest compilation instead"])
            (out [:yellow "  (an anonymous root, e.g. a :snippet, is not named after the benchmark"
                  " -- confirm the graph is the one you meant)"]))
          (cond
            (empty? files)
            {:ok false :error (str "No .bgv files written under " dump-path)}

            (not bgv)
            {:ok false :files files
             :error (str "No matching compilation graph for " method
                         (if guest " (TruffleHotSpotCompilation)" " (host HotSpotCompilation)"))}

            :else
            {:ok true :bgv bgv :files files :candidates candidates}))))))

(defn- diagnose-allocations
  "Dump graphs for a failing benchmark and itemize what allocates and where.

   Reporting only. Anything that goes wrong here is printed and swallowed: this
   runs because a benchmark already failed its budget, and losing that verdict to
   a secondary error would be the wrong trade."
  [opts]
  (let [{:keys [ok bgv candidates error]} (dump-graal-graphs opts)]
    (if-not ok
      (do (out [:yellow "  could not dump graphs for diagnosis: " error]) nil)
      (try
        (out [:cyan "  analyzing " bgv])
        (print-candidate-warnings bgv candidates)
        (let [inspected (inspect-bgv bgv)]
          (when (:truncated? inspected)
            (out [:red "  dump is TRUNCATED; phases after the cut are missing"]))
          (when (and (:low-nodes inspected)
                     (< (:low-nodes inspected) suspiciously-small-graph))
            (out [:yellow "  WARNING: low tier has only " (:low-nodes inspected)
                  " nodes; this compilation is too small to hold the benchmark's work"])
            (out [:yellow "  The allocation is likely in a sibling compilation unit or the interpreter."]))
          (print-explanation (explain-bgv bgv))
          (assoc inspected :bgv bgv))
        (catch Throwable t
          (out [:yellow "  could not itemize allocations: " (.getMessage t)])
          nil)))))

(defn- expand-snippet-opts
  "Expand a high-level `:snippet` name/option into SnippetBenchmark opts:
   :benchmark \"SnippetBenchmark.cloffle\", :params {\"name\" <snippet>},
   :mode \"thrpt\", :guest true."
  [{:keys [snippet benchmark params mode guest] :as opts}]
  (if (and snippet (seq (str snippet)))
    (let [sname (str snippet)]
      (assoc opts
             :benchmark (or benchmark "SnippetBenchmark.cloffle")
             :params (merge {"name" sname} params)
             :mode (or mode "thrpt")
             :guest (if (some? guest) guest true)
             ;; A snippet's guest root is anonymous, so this hint matches nothing and the
             ;; dump falls back to the largest guest compilation. Kept anyway: it costs
             ;; nothing, and a :guest-hint passed explicitly still wins.
             :guest-hint (or (:guest-hint opts) sname)))
    opts))

(defn check-scalar-replacement
  "Fail if a JMH benchmark allocates more than its recorded budget.

   The gate is JMH's `gc.alloc.rate.norm`, because it measures the whole program.
   Graal graph analysis cannot gate: `relativeFrequency` is a static estimate
   scoped to one compilation unit, and a Clojure pipeline compiles into several
   units plus interpreted frames, so a graph can look clean while the benchmark
   allocates kilobytes per operation. Graphs are the diagnosis, printed
   automatically when the gate trips.

   Invoke: clj -T:build check-scalar-replacement :benchmark '\"PersistentTypeScalarReplacementBenchmark.baselineTuple2ScalarReplacement\"'
           clj -T:build check-scalar-replacement :benchmark '\"KeywordMapBenchmark.guestPipelineReduce\"' :guest true :alloc-budget 0
           clj -T:build check-scalar-replacement :snippet '\"keyword-invoke\"' :alloc-budget 0

   Options:
     :benchmark      JMH regex / method name (required unless :snippet is given)
     :snippet        Convenience shortcut: runs SnippetBenchmark.cloffle with -p name=<snippet>
     :params         Map of JMH @Param values, e.g. {\"name\" \"keyword-invoke\"}
     :mode           JMH mode (e.g. \"thrpt\", \"avgt\")
     :alloc-budget   Allowed B/op. Omitted means unbudgeted: the measurement is
                     reported and the check passes with a warning. Populate the
                     catalog with record-alloc-budgets.
     :explain        Dump graphs and itemize allocations on failure (default true)
     :guest true     Diagnose TruffleHotSpotCompilation graphs instead of host methods
     :guest-hint     '\"guest-ephemeral-pipeline\"' to pick a named guest root
     :dump-path      Directory for diagnostic dumps (default \"target/graal-dumps-pea\")
     :quiet true     Suppress JMH stdout (default false)
     :compile false  Skip compile-benchmarks (default true)
     :throw? false   Return the result map instead of throwing (default true)
     :warmup / :iterations / :warmup-time / :time   JMH -wi / -i / -w / -r"
  [raw-opts]
  (let [{:keys [benchmark params mode snippet alloc-budget explain quiet compile throw?
                warmup iterations warmup-time time]
         :or {explain true quiet false compile true throw? true
              warmup 3 iterations 3 warmup-time "2s" time "3s"}
         :as opts} (expand-snippet-opts raw-opts)]
    (when-not (and (string? benchmark) (seq benchmark))
      (throw (ex-info "check-scalar-replacement requires :benchmark or :snippet. To run all known scalar replacement checks, invoke: clj -T:build check-scalar-replacements"
                      {:benchmark benchmark :snippet snippet})))
    (let [method (last (clojure.string/split benchmark #"\."))
          bench-label (str benchmark
                           (when (seq params) (str " " (pr-str params)))
                           (when mode (str " [" mode "]")))
          _ (when-not quiet
              (out [:bold.cyan "Measuring allocation for " bench-label
                    " (-prof gc, wi=" warmup " i=" iterations ")"]))
          measurement (measure-allocation {:benchmark benchmark
                                           :params params
                                           :mode mode
                                           :warmup warmup
                                           :iterations iterations
                                           :warmup-time warmup-time
                                           :time time
                                           :quiet quiet
                                           :compile compile})]
      (if-not (:ok measurement)
        (let [result {:ok false :benchmark benchmark :params params :mode mode
                      :method method :error (:error measurement)}]
          (out [:red "  " (:error measurement)])
          (if throw? (throw (ex-info (:error measurement) result)) result))
        (let [r (find-benchmark-result (:results measurement) benchmark params mode)
              {:keys [score score-unit alloc-norm]} r
              verdict (alloc-verdict alloc-norm alloc-budget)
              result (merge {:benchmark benchmark :params params :mode mode
                             :method method :score score :score-unit score-unit
                             :alloc-norm alloc-norm :alloc-budget alloc-budget}
                            (select-keys verdict [:ok :status :message]))]
          (when score
            (out (format "  %.2f %s" score (or score-unit "ns/op"))))
          (case (:status verdict)
            :within-budget (out [:green "  PASS  " (:message verdict)])
            :unbudgeted (out [:yellow "  PASS  " (:message verdict)
                              " -- run record-alloc-budgets to gate this benchmark"])
            (out [:bold.red "  FAIL  " (:message verdict)]))
          (if (:ok verdict)
            result
            (let [_ (when explain
                      (diagnose-allocations (merge (select-keys opts
                                                                [:guest :guest-hint :dump-path
                                                                 :params :mode
                                                                 :warmup-time :time])
                                                   {:benchmark benchmark
                                                    :quiet true
                                                    :compile false})))
                  error (str bench-label " allocates " (:message verdict))]
              (if throw?
                (throw (ex-info error result))
                (assoc result :error error)))))))))

(defn explain-allocations
  "Explain what a compilation allocates and where each allocation comes from.

   Reporting only: this never fails a build. check-scalar-replacement remains the
   gate that says whether anything allocates; this says what and why, which is
   what you need in order to fix one.

   Invoke with an existing dump:
     clj -T:build explain-allocations :bgv '\"target/graal-dumps-pea/<file>.bgv\"'
   or with a benchmark, which dumps first and picks the right compilation:
     clj -T:build explain-allocations :benchmark '\"KeywordMapBenchmark.guestPipelineReduce\"' :guest true

   Options:
     :bgv        Path to an existing .bgv file
     :benchmark  JMH method name to dump (mutually exclusive with :bgv)
     :snippet    Guest snippet name, e.g. '\"tuple-destructure\"'; a snippet root is
                 anonymous, so the largest guest compilation is analyzed
     :guest / :guest-hint / :dump-path / :warmup / :iterations / :warmup-time / :time
                 Forwarded to the dump when :benchmark is used

   Reports, per compilation:
     - virtual objects at PEA, split into scalar replaced vs committed to the heap
     - the type of each surviving object, and which CommitAllocationNode commits it
     - the inlined source frames each one came from
     - **why** each survivor was materialized: the usages that force it out of
       virtual form, with loop-header phis called out first
     - low-tier allocation stub calls that survived lowering
     - relativeFrequency, so cold deopt-path allocations are distinguishable

   A clean report here does not mean the benchmark does not allocate: it covers
   one compilation unit, and the allocation may live in a sibling unit or in
   interpreted code. check-scalar-replacement measures the whole program."
  [raw-opts]
  (let [{:keys [bgv benchmark] :as opts} (expand-snippet-opts raw-opts)]
    (when (and bgv benchmark)
      (throw (ex-info "explain-allocations takes :bgv or :benchmark, not both" {})))
    (let [bgv (cond
                bgv (do (when-not (.isFile (io/file bgv))
                          (throw (ex-info "no such .bgv file" {:bgv bgv})))
                        bgv)

                benchmark
                (let [result (dump-graal-graphs opts)]
                  (or (:bgv result)
                      (throw (ex-info (or (:error result) "no compilation graph produced")
                                      {:benchmark benchmark :error (:error result)}))))

                :else
                (throw (ex-info "explain-allocations requires :bgv or :benchmark" {})))
          explanation (explain-bgv bgv)]
      (out [:bold "Allocations in " bgv])
      (print-explanation explanation)
      explanation)))

(def known-scalar-replacement-benchmarks
  "Catalog of known scalar replacement benchmarks across host and guest suites.

   :alloc-budget is the allowed `gc.alloc.rate.norm` in B/op and is what the
   check gates on. A budget of 0 asserts full scalar replacement; a non-zero one
   is a ratchet that pins today's behavior so it cannot regress. Entries without
   a budget are reported and passed with a warning until `record-alloc-budgets`
   fills them in."
  [;; --- Host Baselines: Pure Java ---
   {:benchmark "ScalarReplacementBenchmark.baselineScalarReplacementLiteral"
    :suite :host :guest false :doc "Java SimpleBox literal"}
   {:benchmark "ScalarReplacementBenchmark.baselineScalarReplacementFields"
    :suite :host :guest false :doc "Java SimplePair fields"}

   ;; --- Host Baselines: Clojure Persistent Data Structures ---
   {:benchmark "PersistentTypeScalarReplacementBenchmark.baselineTuple2ScalarReplacement"
    :suite :host :guest false :alloc-budget 0 :doc "PersistentTuple2 field access"}
   {:benchmark "PersistentTypeScalarReplacementBenchmark.baselineTuple3ScalarReplacement"
    :suite :host :guest false :doc "PersistentTuple3 field access"}
   {:benchmark "PersistentTypeScalarReplacementBenchmark.baselineTuple4ScalarReplacement"
    :suite :host :guest false :doc "PersistentTuple4 field access"}
   {:benchmark "PersistentTypeScalarReplacementBenchmark.tuple2AssocNThenNth"
    :suite :host :guest false :doc "PersistentTuple2 assocN rewrite"}
   {:benchmark "PersistentTypeScalarReplacementBenchmark.tuple2ConsThenNth"
    :suite :host :guest false :doc "PersistentTuple2 cons promotion to Tuple3"}
   {:benchmark "PersistentTypeScalarReplacementBenchmark.baselineList2ScalarReplacement"
    :suite :host :guest false :doc "PersistentList2 first + next"}
   {:benchmark "PersistentTypeScalarReplacementBenchmark.list2ConsThenFirst"
    :suite :host :guest false :doc "PersistentList cons chain"}

   ;; --- Host Baselines: PersistentShapeMap ---
   {:benchmark "KeywordMapBenchmark.shapeMap3EphemeralAssocThenLookup"
    :suite :host :guest false :doc "ShapeMap3 existing-key assoc"}
   {:benchmark "KeywordMapBenchmark.shapeMap3EphemeralValAtOnly"
    :suite :host :guest false :doc "ShapeMap3 valAt"}
   {:benchmark "KeywordMapBenchmark.shapeMap3EphemeralInsertThenLookup"
    :suite :host :guest false :doc "ShapeMap3 new-key insert"}
   {:benchmark "KeywordMapBenchmark.shapeMap2EphemeralTransitionInsertThenLookup"
    :suite :host :guest false :doc "ShapeMap AssocTransition 2->3"}
   {:benchmark "KeywordMapBenchmark.shapeMap3EphemeralTransitionDissocThenLookup"
    :suite :host :guest false :doc "ShapeMap DissocTransition 3->2"}
   {:benchmark "KeywordMapBenchmark.shapeMap8EphemeralTransitionPromoteThenLookup"
    :suite :host :guest false :doc "ShapeMap Promote16Transition 8->9"}
   {:benchmark "KeywordMapBenchmark.shapeMap3EphemeralKeywordInvoke"
    :suite :host :guest false :doc "ShapeMap Keyword.invoke"}
   {:benchmark "KeywordMapBenchmark.shapeMap3EphemeralNestedValAt"
    :suite :host :guest false :doc "ShapeMap nested valAt"}
   {:benchmark "KeywordMapBenchmark.shapeMap3EphemeralWithoutThenLookup"
    :suite :host :guest false :doc "ShapeMap without"}
   {:benchmark "KeywordMapBenchmark.shapeMap16EphemeralAssocThenLookup"
    :suite :host :guest false :doc "ShapeMap16 9-key existing-key assoc"}
   {:benchmark "KeywordMapBenchmark.shapeMap16EphemeralNestedValAt"
    :suite :host :guest false :doc "ShapeMap16 16-key nested valAt"}
   {:benchmark "KeywordMapBenchmark.shapeMap16EphemeralNestedAssocThenLookup"
    :suite :host :guest false :doc "ShapeMap16 nested existing-key assoc"}
   {:benchmark "KeywordMapBenchmark.shapeMap16EphemeralInsertThenLookup"
    :suite :host :guest false :doc "ShapeMap16 new-key insert"}
   {:benchmark "KeywordMapBenchmark.shapeMap5EphemeralValAtOnly"
    :suite :host :guest false :doc "ShapeMap5 cached create + valAt"}
   {:benchmark "KeywordMapBenchmark.shapeMap3EphemeralKvReduce"
    :suite :host :guest false :doc "ShapeMap3 unrolled kvreduce"}
   {:benchmark "KeywordMapBenchmark.shapeMap3EphemeralReduce"
    :suite :host :guest false :doc "ShapeMap3 MapEntry virtualized reduce"}
   {:benchmark "KeywordMapBenchmark.shapeMap3EphemeralGetInHost"
    :suite :host :guest false :doc "ShapeMap3 nested get-in via RT.getIn"}

   ;; --- Guest Cloffle Pipelines (Truffle HotSpot compilations) ---
   {:benchmark "KeywordMapBenchmark.guestShapeMapEphemeralPipeline"
    :suite :guest :guest true :hint "guest-ephemeral-pipeline" :alloc-budget 0
    :doc "Guest ShapeMap assoc pipeline"}
   {:benchmark "KeywordMapBenchmark.guestShapeMapEphemeralInsert"
    :suite :guest :guest true :hint "guest-ephemeral-insert" :doc "Guest ShapeMap unrolled insert"}
   {:benchmark "KeywordMapBenchmark.guestShapeMapEphemeralPromote8"
    :suite :guest :guest true :hint "guest-ephemeral-promote8" :doc "Guest ShapeMap 8->9 promote"}
   {:benchmark "KeywordMapBenchmark.guestTupleDestructure"
    :suite :guest :guest true :hint "guest-tuple-destructure" :doc "Guest vector destructuring"}
   {:benchmark "KeywordMapBenchmark.guestListEphemeralPipeline"
    :suite :guest :guest true :hint "guest-list-ephemeral-pipeline" :doc "Guest list ephemeral pipeline"}
   {:benchmark "KeywordMapBenchmark.guestLazySeqFirst"
    :suite :guest :guest true :hint "guest-lazy-seq-first" :doc "Guest LazySeq first"}
   {:benchmark "KeywordMapBenchmark.guestConsFirst"
    :suite :guest :guest true :hint "guest-cons-first" :doc "Guest cons first"}
   {:benchmark "KeywordMapBenchmark.guestLazySeqConsFirst"
    :suite :guest :guest true :hint "guest-lazy-seq-cons-first" :doc "Guest LazySeq cons first"}
   {:benchmark "KeywordMapBenchmark.guestLazySeqApplyFirst"
    :suite :guest :guest true :hint "guest-lazy-seq-apply-first" :doc "Guest LazySeq apply first"}
   {:benchmark "KeywordMapBenchmark.guestLazySeqWhenSeqFirst"
    :suite :guest :guest true :hint "guest-lazy-seq-when-seq-first" :doc "Guest LazySeq when-seq first"}
   {:benchmark "KeywordMapBenchmark.guestMapFirst"
    :suite :guest :guest true :hint "guest-map-first" :doc "Guest map first"}
   {:benchmark "KeywordMapBenchmark.guestMapSecond"
    :suite :guest :guest true :hint "guest-map-second" :doc "Guest map second"}
   {:benchmark "KeywordMapBenchmark.guestMappedVectorReduce"
    :suite :guest :guest true :hint "guest-mapped-vector-reduce" :doc "Guest MappedVectorSeq reduce"}
   {:benchmark "KeywordMapBenchmark.guestMappedMapFirst"
    :suite :guest :guest true :hint "guest-mapped-map-first" :doc "Guest MappedMapSeq first"}
   {:benchmark "KeywordMapBenchmark.guestStreamSeqPipeline"
    :suite :guest :guest true :hint "guest-stream-seq-pipeline" :doc "Guest transducer control pipeline"}
   {:benchmark "KeywordMapBenchmark.guestTuple2Transform"
    :suite :guest :guest true :hint "guest-tuple2-transform" :doc "Guest tuple2 swap & transform"}
   {:benchmark "KeywordMapBenchmark.guestKwargsDestructure"
    :suite :guest :guest true :hint "guest-kwargs-destructure" :doc "Guest kwargs destructure"}
   {:benchmark "KeywordMapBenchmark.guestMiddlewarePipeline"
    :suite :guest :guest true :hint "guest-middleware-pipeline" :alloc-budget 0
    :doc "Guest Ring middleware pipeline"}
   {:benchmark "KeywordMapBenchmark.guestCondOptionPipeline"
    :suite :guest :guest true :hint "guest-cond-option-pipeline" :doc "Guest cond-> options accumulator"}
   {:benchmark "KeywordMapBenchmark.guestEventEnrichPipeline"
    :suite :guest :guest true :hint "guest-event-enrich" :alloc-budget 0
    :doc "Guest 8-key event enrich"}
   {:benchmark "KeywordMapBenchmark.guestShapeMapEphemeralDissoc"
    :suite :guest :guest true :hint "guest-ephemeral-dissoc" :doc "Guest ShapeMap dissoc"
    :alloc-budget 88}
   {:benchmark "KeywordMapBenchmark.guestEventSanitizePipeline"
    :suite :guest :guest true :hint "guest-event-sanitize" :doc "Guest chained dissoc sanitization"
    :alloc-budget 0}
   {:benchmark "KeywordMapBenchmark.guestRingResponsePipeline"
    :suite :guest :guest true :hint "guest-ring-pipeline" :alloc-budget 0
    :doc "Guest Ring response pipeline"}
   {:benchmark "KeywordMapBenchmark.guestRingRequestNested"
    :suite :guest :guest true :hint "guest-ring-request-nested" :doc "Guest 14-key nested Ring request"}
   {:benchmark "KeywordMapBenchmark.guestFhirPatientNested"
    :suite :guest :guest true :hint "guest-fhir-patient-nested" :doc "Guest 16-key nested FHIR Patient"}
   {:benchmark "KeywordMapBenchmark.guestJsonapiDocumentNested"
    :suite :guest :guest true :hint "guest-jsonapi-document-nested" :alloc-budget 0
    :doc "Guest nested JSON:API document"}
   {:benchmark "KeywordMapBenchmark.guestAppEntity16"
    :suite :guest :guest true :hint "guest-app-entity-16" :doc "Guest 16-key nested app entity"}
   ;; Provisional: this indexes with `nth`, so a boxed Long index sits on the measured path and its
   ;; number dominates whatever the lowering layer does. Treat it as a workload sample, not a
   ;; benchmark, until a primitive-specialization pass makes indices measurable.
   {:benchmark "KeywordMapBenchmark.guestHiccupNormalizeTag"
    :suite :guest :guest true :provisional true
    :hint "guest-hiccup-normalize" :doc "Guest Hiccup normalize tag (provisional: boxed index)"}
   {:benchmark "KeywordMapBenchmark.guestCheshireFieldNamePipeline"
    :suite :guest :guest true :hint "guest-cheshire-field-name" :doc "Guest Cheshire field name pipeline"}
   {:benchmark "KeywordMapBenchmark.guestGetInEphemeralPipeline"
    :suite :guest :guest true :hint "guest-get-in-ephemeral-pipeline" :alloc-budget 0
    :doc "Guest inlined get-in ephemeral pipeline"}

   ;; --- Guest Snippet Benchmarks (SnippetBenchmark.cloffle parametrized snippets) ---
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "keyword-invoke"}
    :mode "thrpt"
    :suite :guest :guest true :hint "keyword-invoke"
    :alloc-budget 0
    :doc "Guest snippet keyword-invoke (:b ephemeral map)"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "tuple-destructure"}
    :mode "thrpt"
    :suite :guest :guest true :hint "tuple-destructure"
    :alloc-budget 0
    :doc "Guest snippet tuple-destructure (vector destructuring, fully scalar-replaced)"}
   ;; The assoc escape-probe ladder. All five measured a flat 128 B/op before the KeywordAssoc
   ;; lowering existed, regardless of whether the result escaped — the tell that the allocation was
   ;; happening behind the shared clojure.core/assoc CallTarget where PEA could not see it.
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "consume-assoc"}
    :mode "thrpt"
    :suite :guest :guest true :hint "consume-assoc"
    :alloc-budget 0
    :doc "Guest snippet consume-assoc (assoc result consumed as a scalar, let-bound source)"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "consume-assoc-no-let"}
    :mode "thrpt"
    :suite :guest :guest true :hint "consume-assoc-no-let"
    :alloc-budget 0
    :doc "Guest snippet consume-assoc-no-let (same, without a frame local)"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "ephemeral-pipeline"}
    :mode "thrpt"
    :suite :guest :guest true :hint "ephemeral-pipeline"
    :alloc-budget 0
    :doc "Guest snippet ephemeral-pipeline (assoc update then keyword read)"}

   ;; The conj probe ladder. These budgets are NOT achievements — they record what conj still
   ;; allocates on the Var path, so the remaining opportunity is visible and cannot silently get
   ;; worse. Lowering conj to a bytecode operation was measured on 2026-09-09 and made every one of
   ;; them worse; see TODO_lowering_layer.md "Phase 2 step 5". Tightening these needs a conj
   ;; transition cache (a precomputed tuple-grow plan), not a call-site split.
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "consume-conj-vector"}
    :mode "thrpt"
    :suite :guest :guest true :hint "consume-conj-vector"
    :alloc-budget 32
    :doc "Guest snippet consume-conj-vector (conj onto a 2-tuple, result consumed by peek)"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "consume-conj-map"}
    :mode "thrpt"
    :suite :guest :guest true :hint "consume-conj-map"
    :alloc-budget 304
    :doc "Guest snippet consume-conj-map (conj a map onto a map, result read by keyword)"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "consume-conj-list"}
    :mode "thrpt"
    :suite :guest :guest true :hint "consume-conj-list"
    :alloc-budget 128
    :doc "Guest snippet consume-conj-list (conj onto a list, result consumed by first)"}
   {:benchmark "SnippetBenchmark.cloffle"
    :params {"name" "conj-chain"}
    :mode "thrpt"
    :suite :guest :guest true :hint "conj-chain"
    :alloc-budget 584
    :doc "Guest snippet conj-chain (three chained conj calls up the tuple ladder)"}])

(defn- filter-scalar-replacement-benchmarks
  [benchmarks {:keys [suite filter benchmark snippet]}]
  (let [suite-kw (when suite (keyword (name suite)))
        filter-pattern (or snippet filter benchmark)
        re (when (and filter-pattern (seq (str filter-pattern)))
             (re-pattern (str "(?i)" filter-pattern)))]
    (->> benchmarks
         (clojure.core/filter
          (fn [b]
            (and (or (nil? suite-kw)
                     (= suite-kw :all)
                     (= (:suite b) suite-kw))
                 (or (nil? re)
                     (re-find re (:benchmark b))
                     (re-find re (or (:doc b) ""))
                     (when-let [p (get-in b [:params "name"])]
                       (re-find re p)))))))))

(defn- list-scalar-replacement-benchmarks [matched]
  (out [:bold.cyan (format "\nKnown Scalar Replacement Benchmarks (%d matches):\n" (count matched))])
  (doseq [{:keys [benchmark params mode suite doc alloc-budget]} matched]
    (let [suite-tag (if (= suite :guest) "[:guest]" "[:host] ")
          budget (if alloc-budget (format "%6s B/op" (str alloc-budget)) "  unbudgeted")
          label (str benchmark
                     (when (seq params) (str " " (pr-str params)))
                     (when mode (str " [" mode "]")))]
      (out (format "  %-8s %-11s %-62s %s" suite-tag budget label (or doc "")))))
  nil)

(defn check-scalar-replacements
  "Run every known scalar replacement benchmark against its allocation budget.
   Invoke: clj -T:build check-scalar-replacements
           clj -T:build check-scalar-replacements :suite :host
           clj -T:build check-scalar-replacements :suite :guest
           clj -T:build check-scalar-replacements :filter '\"Tuple\"'
           clj -T:build check-scalar-replacements :list true
   Options:
     :suite     :all (default), :host, or :guest
     :filter    Regex or substring filter on benchmark names
     :benchmark Same as :filter
     :fail-fast Stop on first failure (default false)
     :verbose   Stream full JMH output and allocation diagnosis (default false)
     :list      List matched benchmarks and their budgets without running them
     :explain   Dump graphs and itemize allocations on failure (default true)
     :dump-path Directory for diagnostic Graal IR dumps (default \"target/graal-dumps-pea\")
     :warmup / :iterations / :warmup-time / :time
                Forwarded to each check-scalar-replacement invocation"
  [opts]
  (let [{:keys [fail-fast dump-path verbose list warmup iterations warmup-time time explain]
         :or {fail-fast false dump-path "target/graal-dumps-pea" verbose false list false
              explain true}} opts
        matched (filter-scalar-replacement-benchmarks known-scalar-replacement-benchmarks opts)
        jmh-opts (cond-> {}
                   (some? warmup) (assoc :warmup warmup)
                   (some? iterations) (assoc :iterations iterations)
                   (some? warmup-time) (assoc :warmup-time warmup-time)
                   (some? time) (assoc :time time))]
    (if list
      (list-scalar-replacement-benchmarks matched)
      (do
        (when (empty? matched)
          (throw (ex-info "No scalar replacement benchmarks matched the filter." {:opts opts})))
        (out [:bold.cyan (format "\n===== Running %d Scalar Replacement Check(s) =====\n" (count matched))])
        (when (seq jmh-opts)
          (out [:cyan (str "JMH overrides: " (pr-str jmh-opts))]))
        (compile-benchmarks nil)
        (let [total (count matched)
              results (loop [idx 1
                             remaining matched
                             acc []]
                        (if (empty? remaining)
                          acc
                          (let [{:keys [benchmark guest hint suite alloc-budget]} (first remaining)
                                suite-str (name suite)
                                _ (out (format "[%d/%d] Checking %s (%s)..." idx total benchmark suite-str))
                                t0 (System/currentTimeMillis)
                                res (try
                                      (check-scalar-replacement
                                       (merge jmh-opts
                                              (select-keys (first remaining) [:params :mode])
                                              {:benchmark benchmark
                                               :guest guest
                                               :guest-hint hint
                                               :alloc-budget alloc-budget
                                               :explain explain
                                               :dump-path dump-path
                                               :quiet (not verbose)
                                               :compile false
                                               :throw? false}))
                                      (catch Throwable t
                                        {:ok false :benchmark benchmark :error (.getMessage t)}))
                                elapsed-ms (- (System/currentTimeMillis) t0)
                                ok? (:ok res)
                                entry (assoc res :benchmark benchmark :suite suite :elapsed-ms elapsed-ms)]
                            (if ok?
                              (out [:green (format "  PASS (%.1fs)" (/ elapsed-ms 1000.0))])
                              (do
                                (out [:bold.red (format "  FAIL (%.1fs)" (/ elapsed-ms 1000.0))])
                                (when (:error res)
                                  (out [:red (str "  error: " (:error res))]))))
                            (if (and fail-fast (not ok?))
                              (conj acc entry)
                              (recur (inc idx) (rest remaining) (conj acc entry))))))
              failures (clojure.core/filter #(not (:ok %)) results)
              unbudgeted (clojure.core/filter #(= :unbudgeted (:status %)) results)
              passed (- (count results) (count failures))]
          (out [:bold.cyan (format "\n===== Scalar Replacement Summary (%d/%d passed, %d failed) =====\n"
                                   passed (count results) (count failures))])
          (doseq [{:keys [benchmark ok suite elapsed-ms error alloc-norm alloc-budget]} results]
            (let [tag (if ok [:green "[PASS]"] [:bold.red "[FAIL]"])
                  suite-tag (if (= suite :guest) "[:guest]" "[:host] ")
                  alloc (if alloc-norm
                          (format "%8.1f B/op vs %-9s" alloc-norm
                                  (if alloc-budget (str alloc-budget) "-"))
                          (format "%-24s" "no measurement"))]
              (out (str (ansi/compose tag) " " suite-tag " " alloc
                        (format " %-52s (%.1fs)" benchmark (/ elapsed-ms 1000.0))))
              (when (and (not ok) error)
                (out [:red (str "         " error)]))))
          (when (seq unbudgeted)
            (out [:yellow (format "\n%d benchmark(s) have no :alloc-budget and cannot fail. Record them with:"
                                  (count unbudgeted))])
            (out [:yellow "  clojure -T:build record-alloc-budgets"]))
          (if (seq failures)
            (throw (ex-info (format "Scalar replacement checks failed: %d/%d benchmark(s) exceeded their allocation budget."
                                    (count failures) (count results))
                            {:failures (mapv :benchmark failures)}))
            (do
              (out [:bold.green (format "\nAll %d scalar replacement checks passed!" passed)])
              results)))))))

(defn record-alloc-budgets
  "Measure `gc.alloc.rate.norm` for the catalog and print :alloc-budget entries.

   Reporting only: it never edits build.clj, because a budget is an assertion
   about intended behavior and snapshotting a regression into the catalog would
   silently bless it. Read the output, then paste the budgets you accept.

   Invoke: clj -T:build record-alloc-budgets
           clj -T:build record-alloc-budgets :filter '\"Tuple\"'
           clj -T:build record-alloc-budgets :missing true
           clj -T:build record-alloc-budgets :snippet '\"keyword-invoke\"'

   Options:
     :suite / :filter / :benchmark   Same selection as check-scalar-replacements
     :snippet                        Measure a specific snippet (e.g. \"keyword-invoke\")
     :params                         JMH @Param map when measuring ad-hoc
     :mode                           JMH mode (default \"thrpt\" for snippets)
     :missing true                   Only benchmarks that have no budget yet
     :verbose                        Stream JMH output (default false)
     :warmup / :iterations / :warmup-time / :time   Forwarded to JMH"
  [{:keys [missing verbose warmup iterations warmup-time time snippet] :as raw-opts}]
  (let [opts (expand-snippet-opts raw-opts)
        matched-catalog (cond->> (filter-scalar-replacement-benchmarks
                                  known-scalar-replacement-benchmarks opts)
                          missing (clojure.core/remove :alloc-budget))
        matched (if (and (empty? matched-catalog) (or snippet (:benchmark raw-opts)))
                  [(select-keys opts [:benchmark :params :mode :guest :doc])]
                  matched-catalog)
        jmh-opts (cond-> {:quiet (not verbose) :compile false}
                   (some? warmup) (assoc :warmup warmup)
                   (some? iterations) (assoc :iterations iterations)
                   (some? warmup-time) (assoc :warmup-time warmup-time)
                   (some? time) (assoc :time time))]
    (when (empty? matched)
      (throw (ex-info "No scalar replacement benchmarks matched." {:opts opts})))
    (out [:bold.cyan (format "\n===== Measuring %d benchmark(s) =====\n" (count matched))])
    (compile-benchmarks nil)
    (let [total (count matched)
          measured (doall
                    (for [[idx entry] (map-indexed vector matched)
                          :let [{:keys [benchmark params mode alloc-budget]} entry]]
                      (let [label (str benchmark
                                       (when (seq params) (str " " (pr-str params)))
                                       (when mode (str " [" mode "]")))
                            _ (out (format "[%d/%d] %s..." (inc idx) total label))
                            m (measure-allocation (merge jmh-opts
                                                         {:benchmark benchmark
                                                          :params params
                                                          :mode mode}))
                            r (when (:ok m) (find-benchmark-result (:results m) benchmark params mode))
                            b-op (:alloc-norm r)
                            score (:score r)
                            unit (:score-unit r)]
                        (if b-op
                          (out (format "        %.1f B/op  (%.2f %s)" b-op (or score 0.0) (or unit "ns/op")))
                          (out [:red (str "        no measurement: " (:error m))]))
                        (assoc entry :alloc-norm b-op :old alloc-budget))))]
      (out [:bold.cyan "\n===== Suggested :alloc-budget entries =====\n"])
      (doseq [{:keys [benchmark params mode alloc-norm old]} measured]
        (let [suggested (when alloc-norm
                          (if (<= alloc-norm zero-alloc-epsilon) 0 (Math/round ^double alloc-norm)))
              label (str benchmark (when (seq params) (str " " (pr-str params))))]
          (out (format "  %-62s :alloc-budget %-8s %s"
                       label
                       (if (some? suggested) (str suggested) "?")
                       (cond
                         (nil? alloc-norm) "(no measurement)"
                         (nil? old) "(new)"
                         (= (long old) (long suggested)) "(unchanged)"
                         (> (long suggested) (long old)) (format "(REGRESSION: was %s)" old)
                         :else (format "(improved from %s)" old))))))
      (out [:yellow "\nA budget above 0 pins current behavior; it is not a statement that the allocation is acceptable."])
      measured)))

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

(defn compat-test
  "[AST+BYTECODE] Run compatibility checks for external projects (git submodules in src/external-projects).
   Generative (test.generative / *.generative) test namespaces are skipped.
   Phase 1 runs tests with official org.clojure/clojure from Maven (`compat-official-clojure-version`).
   Phase 2 runs the same tests with Cloffle.
   Usage: clj -T:build compat-test
          clj -T:build compat-test :project :all
          clj -T:build compat-test :project :cheshire
          clj -T:build compat-test :project :sieppari
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
                      common-opts-clj (into (test-jvm-opts)
                                            ["-cp" cp-clj-str])
                      common-opts (into (test-jvm-opts)
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
