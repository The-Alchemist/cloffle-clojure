;; Category: distribution JAR, bytecode cache dump, Cloffle REPL/main/DAP runners.
(in-ns 'build)

(defn- run-cloffle-repl!
  "Shared CloffleRepl launcher. `:direct-linking` when non-nil adds
   `-Dclojure.compiler.direct-linking=true|false` (nil leaves JVM/env default)."
  [{:keys [args archive compile direct-linking] :or {args []}}]
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
        dl-opt (when (some? direct-linking)
                 [(str "-Dclojure.compiler.direct-linking=" (boolean direct-linking))])
        java-args (concat (test-jvm-opts)
                          dl-opt
                          archive-opt
                          ["-cp" cp-str
                           "net.javacrumbs.cloffle.CloffleRepl"]
                          (map str args))
        argfile (write-java-argfile java-args)]
    (run-interactive-process! ["java" argfile])))

(defn cloffle-repl
  "[AST+BYTECODE] Run CloffleRepl (interactive REPL, --demo, or a .clj file). Args: {:args []}
   Optional: :archive — if true, uses default target/clojure-core.bc (same as load-bytecode-archive);
   if a non-empty string, uses that path. Prepends -Dcloffle.core.bytecode.archive=<absolute path> so RT.init
   bootstraps clojure.core from the archive (no source fallback).
   Bytecode cache (.bc) files are loaded automatically from the classpath — run
   `clj -T:build dump-bytecode-cache` first to populate target/classes with .bc files.
   Uses product default direct-linking (off unless JVM already set). For an explicit stock-like pin see `cloffle-repl-dev`.
   Invoke: clj -T:build cloffle-repl :args '[\"--demo\"]'
           clj -T:build cloffle-repl :archive true
           clj -T:build cloffle-repl :archive '\"/path/to/core.bc\"'"
  [opts]
  (run-cloffle-repl! opts))

(defn cloffle-repl-dev
  "Like `cloffle-repl`, but forces `-Dclojure.compiler.direct-linking=false` for stock-like
   Var / with-redefs semantics (same as the unset default). Same :args / :archive / :compile opts.
   Invoke: clj -T:build cloffle-repl-dev
           clj -T:build cloffle-repl-dev :args '[\"--demo\"]'"
  [opts]
  (run-cloffle-repl! (assoc opts :direct-linking false)))

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

(defn- cloffle-java-classpath-str
  "Same classpath as `deps.edn` `:cloffle-java` / Makefile `runtime_cp` (no `test/`)."
  []
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:cloffle-java]})
        cp (into [class-dir fork-clojure-sources] (runtime-classpath-roots basis))]
    (clojure.string/join (System/getProperty "path.separator") cp)))

(defn cloffle-java-classpath
  "Print the `:cloffle-java` runtime classpath (for `java -cp`). Compiles host Java first.
   Invoke: clj -T:build cloffle-java-classpath
   Shell: java $(clj -T:build jvm-opts :format :shell) -cp \"$(clj -T:build cloffle-java-classpath)\" …"
  [_]
  (compile-all nil)
  (print (cloffle-java-classpath-str))
  nil)

(defn cloffle-java
  "[AST+BYTECODE] Run CloffleMain with `cloffle-jvm-opts` and the `:cloffle-java` classpath
   (Makefile `cloffle_java`, without `test/`). Args: {:args []}
   NOTE: For interactive REPL (-r), use `make cloffle-main-repl` or `clj -T:build cloffle-repl`.
   Examples:
     clj -T:build cloffle-java :args '[\"-e\" \"(+ 1 2)\"]'
     clj -T:build cloffle-java :args '[\"script.clj\"]'
     clj -T:build cloffle-java :args '[\"-m\" \"my.ns\"]'"
  [{:keys [args] :or {args []}}]
  (compile-all nil)
  (let [cp-str (cloffle-java-classpath-str)
        args (concat (cloffle-jvm-opts)
                     ["-cp" cp-str
                      "net.javacrumbs.cloffle.CloffleMain"]
                     (map str args))
        argfile (write-java-argfile args)]
    (b/process
     {:command-args ["java" argfile]
      :in :inherit
      :out :inherit
      :err :inherit})))

(defn cloffle-main
  "[AST+BYTECODE] Run CloffleMain (clojure.main-compatible CLI) with `test/` on the classpath.
   Args: {:args []} — prefer `cloffle-java` for the production `:cloffle-java` classpath.
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
        args (concat (cloffle-jvm-opts)
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
(defn jar
  "Compile Cloffle, copy all of `src/clj` (forked `.clj` sources) into classes, and write the
   versioned JAR under `target/`. Writes `jar-artifact-manifest` (path to that JAR) for Docker.
   Does not run `dump-bytecode-cache`; use `clj -T:build dump-bytecode-cache` separately for `.bc` files."
  [_]
  (compile-all nil)
  (b/copy-dir {:src-dirs ["src/clj"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file
          :main 'clojure.main
          :manifest {"Enable-Native-Access" "ALL-UNNAMED"
                     "Add-Modules" "jdk.internal.vm.ci"}})
  (spit jar-artifact-manifest jar-file))
(defn build-jar
  "Build the distribution JAR (compile-all + package as single jar).
   Invoke: clj -T:build build-jar
   Used by Dockerfile.jlink and CI."
  [_]
  (jar nil))

(def ^:private repl-aot-cache-dir "target/repl-aot-cache")

(defn- repl-aot-cache-paths []
  (let [dir (io/file repl-aot-cache-dir)
        cp-args (io/file dir "java.cp.args")]
    {:dir dir
     :aot-file (io/file dir "app.aot")
     :cp-args cp-args
     :body-args cp-args
     :java-args cp-args}))

(defn- read-jar-artifact-path!
  []
  (let [manifest (io/file jar-artifact-manifest)]
    (when-not (.isFile manifest)
      (throw (ex-info (str "Missing " jar-artifact-manifest " — run `clj -T:build jar` first.")
                      {:manifest (.getAbsolutePath manifest)})))
    (clojure.string/trim (slurp manifest))))

(defn- distribution-repl-classpath-str
  "App jar plus Maven JAR roots only (no directory entries); matches Dockerfile.jlink CDS layout."
  [jar-path basis]
  (let [jar (.getAbsolutePath (io/file jar-path))
        sep (System/getProperty "path.separator")
        maven-jars (->> (runtime-classpath-roots basis)
                        (map io/file)
                        (filter #(.isFile ^java.io.File %))
                        (filter #(.endsWith (.getName ^java.io.File %) ".jar"))
                        (map #(.getAbsolutePath ^java.io.File %)))]
    (assert-standalone-truffle-jars! (cons jar maven-jars))
    (clojure.string/join sep (cons jar maven-jars))))

(defn- repl-aot-cache-launcher-jvm-opts
  "On the `java` command line before @java.cp.args and AOT flags (identical for train and run)."
  []
  ["--enable-native-access=ALL-UNNAMED"
   "--add-modules=jdk.internal.vm.ci"
   "--sun-misc-unsafe-memory-access=allow"])

(defn- repl-aot-cache-cp-lines [cp-str]
  ["-cp" cp-str])

(defn repl-aot-cache-write-argfiles
  "Write target/repl-aot-cache/java.cp.args (-cp only). Train/run (JDK 25+ on system GraalVM):
   java <launcher opts> -XX:AOTCacheOutput=…/app.aot @java.cp.args …   (train, one step)
   java <launcher opts> -XX:AOTCache=…/app.aot @java.cp.args …        (run, load only)
   Args: {:jar-path nil :build-jar false}
   Invoke: clj -T:build repl-aot-cache-write-argfiles"
  [{:keys [jar-path build-jar] :or {build-jar false}}]
  (when build-jar (jar nil))
  (let [jar-path (or jar-path (read-jar-artifact-path!))
        {:keys [dir cp-args aot-file]} (repl-aot-cache-paths)
        basis (b/create-basis {:project "deps.edn" :aliases [:repl]})
        cp-str (distribution-repl-classpath-str jar-path basis)
        cp-lines (repl-aot-cache-cp-lines cp-str)
        aot-path (.getAbsolutePath aot-file)
        launcher (clojure.string/join " " (repl-aot-cache-launcher-jvm-opts))
        cp-path (.getAbsolutePath cp-args)]
    (.mkdirs dir)
    (write-java-argfile-to! cp-args cp-lines)
    (spit (io/file dir "java.train.cmd")
          (str "java " launcher " -XX:AOTCacheOutput=" aot-path
               " @" cp-path " net.javacrumbs.cloffle.CloffleRepl\n"))
    (spit (io/file dir "java.run.cmd")
          (str "java " launcher " -XX:AOTCache=" aot-path
               " @" cp-path " net.javacrumbs.cloffle.CloffleRepl\n"))
    (out [:bold.cyan "\n===== repl-aot-cache-write-argfiles ====="])
    (out (str "Wrote " cp-path))
    (out (str "Wrote " (.getAbsolutePath (io/file dir "java.train.cmd"))))
    (out (str "Wrote " (.getAbsolutePath (io/file dir "java.run.cmd"))))
    {:jar jar-path
     :cp-args cp-path
     :java-args cp-path
     :aot aot-path}))

(defn- repl-aot-cache-java-command
  "Build `java` argv: [extras] launcher opts, AOT/CDS opts, @cp-argfile."
  [cp-argfile aot-jvm-opts & {:keys [extra-launcher-opts]}]
  (into ["java"]
        (concat (or extra-launcher-opts [])
                (repl-aot-cache-launcher-jvm-opts)
                aot-jvm-opts
                [(str "@" (.getAbsolutePath (io/file cp-argfile)))])))

(defn- run-java-with-stdin-string!
  "Run `command-args` (e.g. [\"java\" \"@file\" \"Main\"]), write `stdin-string` to stdin, then close it."
  [command-args stdin-string]
  (let [cmd (clojure.string/join " " (map pr-str (map str command-args)))
        shell-cmd (str "printf " (pr-str stdin-string) " | " cmd)
        proc (b/process {:command-args ["sh" "-c" shell-cmd]
                         :out :inherit
                         :err :inherit})]
    (ensure-jvm-task-ok! "java" proc)))

(defn- repl-aot-cache-property-mismatch?
  [stderr]
  (boolean (some #(re-find #"Mismatched values for property" %)
                 (clojure.string/split-lines (or stderr "")))))

(defn- repl-aot-cache-aot-loaded?
  [stderr aot-file]
  (let [err (or stderr "")
        path (.getAbsolutePath aot-file)]
    (and (clojure.string/includes? err "Opened AOT cache")
         (clojure.string/includes? err path))))

(defn- repl-aot-cache-optimized-modules-enabled?
  [stderr]
  (boolean (re-find #"optimized module handling: enabled" (or stderr ""))))

(defn- verify-repl-aot-cache-load!
  "Load app.aot (-XX:AOTCache + @java.cp.args); requires clean module graph in AOT log."
  [cp-argfile aot-file]
  (let [load-opts [(str "-XX:AOTCache=" (.getAbsolutePath aot-file))]
        cmd (into (repl-aot-cache-java-command cp-argfile load-opts
                                                 {:extra-launcher-opts ["-Xlog:aot=info:stderr"]})
                  ["net.javacrumbs.cloffle.CloffleRepl"])
        shell-cmd (str "printf ':quit\\n' | "
                       (clojure.string/join " " (map pr-str (map str cmd))))
        proc (b/process {:command-args ["sh" "-c" shell-cmd]
                         :out :inherit
                         :err :capture})]
    (ensure-jvm-task-ok! "repl-aot-cache-verify-load" proc)
    (let [err (:err proc)]
      (when (repl-aot-cache-property-mismatch? err)
        (throw (ex-info "AOT cache load failed (train/run JVM flags mismatch)."
                        {:cp-args (.getAbsolutePath cp-argfile)
                         :stderr err})))
      (when-not (repl-aot-cache-aot-loaded? err aot-file)
        (throw (ex-info "AOT cache was not loaded (expected Opened AOT cache … app.aot)."
                        {:aot (.getAbsolutePath aot-file)
                         :cp-args (.getAbsolutePath cp-argfile)
                         :stderr err})))
      (when-not (repl-aot-cache-optimized-modules-enabled? err)
        (throw (ex-info "AOT cache load did not enable optimized module handling (check -Xlog:aot)."
                        {:aot (.getAbsolutePath aot-file)
                         :stderr err}))))))

(defn repl-aot-cache-train
  "Train JDK 25 AOT cache (AOTCacheOutput → app.aot), then verify load (AOTCache, no re-record).
   Args: {:build-jar false}
   Invoke: clj -T:build repl-aot-cache-train"
  [{:keys [build-jar] :or {build-jar false}}]
  (repl-aot-cache-write-argfiles {:build-jar build-jar})
  (let [{:keys [cp-args aot-file]} (repl-aot-cache-paths)
        train-opts [(str "-XX:AOTCacheOutput=" (.getAbsolutePath aot-file))]]
    (out [:bold.cyan "\n===== repl-aot-cache-train ====="])
    (run-java-with-stdin-string!
     (into (repl-aot-cache-java-command cp-args train-opts)
           ["net.javacrumbs.cloffle.CloffleRepl"])
     ":quit\n")
    (when-not (.isFile aot-file)
      (throw (ex-info "AOT cache was not created."
                      {:aot (.getAbsolutePath aot-file)})))
    (out "Verifying AOT cache load (-XX:AOTCache + @java.cp.args) …")
    (verify-repl-aot-cache-load! cp-args aot-file)
    (out (str "\nWrote AOT cache: " (.getAbsolutePath aot-file)))
    {:aot (.getAbsolutePath aot-file)}))

(defn cloffle-repl-aot-cache
  "Run CloffleRepl with AOT cache load (-XX:AOTCache + @java.cp.args). Interactive stdin.
   Args: {:build-jar false :train false :cds-log false} — :cds-log enables -Xlog:aot=info:stderr
   Invoke: clj -T:build cloffle-repl-aot-cache"
  [{:keys [build-jar train cds-log] :or {build-jar false train false cds-log false}}]
  (when train
    (repl-aot-cache-train {:build-jar build-jar}))
  (let [{:keys [cp-args aot-file]} (repl-aot-cache-paths)]
    (when-not (.isFile aot-file)
      (throw (ex-info (str "Missing AOT cache — run `clj -T:build repl-aot-cache-train`"
                           " or pass :train true.")
                      {:aot (.getAbsolutePath aot-file)})))
    (when-not (.isFile cp-args)
      (repl-aot-cache-write-argfiles {:build-jar build-jar}))
    (let [run-opts [(str "-XX:AOTCache=" (.getAbsolutePath aot-file))]
          extra (when cds-log ["-Xlog:aot=info:stderr"])
          cmd (into (repl-aot-cache-java-command cp-args run-opts {:extra-launcher-opts extra})
                    ["net.javacrumbs.cloffle.CloffleRepl"])]
      (run-interactive-process! cmd))))
