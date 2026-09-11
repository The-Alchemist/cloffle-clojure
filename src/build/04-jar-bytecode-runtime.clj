;; Category: distribution JAR, bytecode cache dump, Cloffle REPL/main/DAP runners.
(in-ns 'build)

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
