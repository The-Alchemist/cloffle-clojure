;; Category: shared constants, classpath/basis, colored output, `clean`, JVM subprocess helpers.
;; Loaded by build.clj — use `clojure -T:build`, not `require`.
(in-ns 'build)

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

(defn- javac-in-process!
  "Compile Java sources in-process via ToolProvider (no javac subprocess).
   Preserves an explicit classpath (prepended class dirs / fork sources) that
   b/javac cannot express because those paths are not Maven libs.

   Options:
     :src-dirs         — coll of source roots (missing dirs skipped)
     :class-dir        — output directory for .class files
     :classpath-roots  — coll of paths for -classpath
     :javac-opts       — additional javac string options (release, processorpath, …)"
  [{:keys [src-dirs class-dir classpath-roots javac-opts]}]
  (let [existing-src-dirs (filter #(.isDirectory (io/file %)) src-dirs)
        java-files (->> existing-src-dirs
                        (mapcat #(file-seq (io/file %)))
                        (filter #(and (.isFile %) (.endsWith (.getName %) ".java")))
                        (mapv #(.getAbsoluteFile %)))]
    (when (seq java-files)
      (io/make-parents (io/file class-dir "dummy"))
      (let [compiler (javax.tools.ToolProvider/getSystemJavaCompiler)
            _ (when (nil? compiler)
                (throw (ex-info "No system JavaCompiler (JDK required, not JRE)." {})))
            listener (reify javax.tools.DiagnosticListener
                       (report [_ diag] (println (str diag))))
            file-mgr (.getStandardFileManager compiler listener nil nil)
            cp (clojure.string/join (System/getProperty "path.separator") classpath-roots)
            options (into ["-classpath" cp "-d" (str class-dir)] (or javac-opts []))
            file-objs (.getJavaFileObjectsFromFiles file-mgr java-files)
            task (.getTask compiler nil file-mgr listener options nil file-objs)
            success (.call task)]
        (.close file-mgr)
        (when-not success
          (throw (ex-info "Java compilation failed"
                          {:src-dirs (vec existing-src-dirs)
                           :class-dir class-dir})))))))

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
                       (if (or (clojure.string/includes? s " ")
                               (clojure.string/includes? s "#"))
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

(defn- test-suite-jvm-opts
  "JVM flags for `run-tests`, `run-clj-tests`, and `compat-test`.
   Adds `-ea` so Java `assert` statements run; not used for REPL/JMH (those stay on `test-jvm-opts`)."
  []
  (into (test-jvm-opts) ["-ea"]))

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
