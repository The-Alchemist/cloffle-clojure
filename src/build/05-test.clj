;; Category: test compilation, JUnit/Surefire, `run-tests`, `run-clj-tests`.
(in-ns 'build)

(defn compile-tests
  "Compile main sources plus Java tests under `test/java` and `src/test/java`."
  [_]
  (compile-all nil)
  (compile-benchmarks nil)
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:test :repl :benchmark]})
        cp (into [benchmark-class-dir class-dir fork-clojure-sources] (runtime-classpath-roots basis))]
    (javac-in-process!
     {:src-dirs ["test/java" "src/test/java"]
      :class-dir test-class-dir
      :classpath-roots cp
      :javac-opts (into ["--release" "21" "-encoding" "UTF-8"]
                        javac-quiet-opts)})))
(defn- junit-xml-truncated?
  "True when the file exists but does not end with a closing testsuite element (typical of a killed JVM mid-write)."
  [^java.io.File f]
  (when (and (.exists f) (pos? (.length f)))
    (let [len (.length f)
          n (int (min 512 len))
          buf (byte-array n)]
      (with-open [is (java.io.FileInputStream. f)]
        (.skip is (max 0 (- len n)))
        (.read is buf))
      (let [tail (String. buf java.nio.charset.StandardCharsets/UTF_8)]
        (not (or (clojure.string/includes? tail "</testsuite>")
                 (clojure.string/includes? tail "</testsuites>")))))))

(defn- junit-xml-has-ansi?
  "True when the file contains ESC (0x1b), often from ANSI-colored failure text embedded in XML."
  [^java.io.File f]
  (with-open [is (java.io.FileInputStream. f)]
    (loop []
      (let [b (.read is)]
        (cond (= b -1) false
              (= b 27) true
              :else (recur))))))

(defn- junit-xml-parse-ex
  [^java.io.File f cause exit]
  (let [path (.getPath f)
        len (when (.exists f) (.length f))
        truncated? (boolean (junit-xml-truncated? f))
        ansi? (when (.exists f) (junit-xml-has-ansi? f))
        hint (cond
               ansi? "XML contains ANSI escape bytes (0x1b); colored test output may have been written into the report."
               (and truncated? (or (nil? exit) (not (zero? exit))))
               "Report looks truncated; the test JVM likely exited abruptly (OOM, SIGKILL) while writing Surefire XML."
               truncated? "Report looks truncated (missing closing </testsuite>)."
               (and (not (zero? (or exit 0))) (not (.exists f)))
               "JUnit XML missing; the test JVM may have crashed before finishing the report."
               :else nil)
        msg (str "JUnit XML unreadable: " path
                 (when hint (str " — " hint))
                 (when cause (str " (" cause ")")))]
    (ex-info msg {:junit-xml path :size len :exit exit :truncated? truncated?
                  :ansi-in-xml? ansi?})))

(defn- parse-junit-xml
  "Parse a JUnit XML file. Returns a vector of {:suite :name :status} for each testcase.
   Optional `exit` is the subprocess exit code (used only for clearer errors when XML is corrupt)."
  ([^java.io.File f] (parse-junit-xml f nil))
  ([^java.io.File f exit]
   (when-not (.exists f)
     (throw (junit-xml-parse-ex f "file missing" exit)))
   (try
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
       @results)
     (catch org.xml.sax.SAXParseException e
       (throw (junit-xml-parse-ex f (.getMessage e) exit)))
     (catch Exception e
       (throw (junit-xml-parse-ex f (.getMessage e) exit))))))

(defn- diagnose-surefire-reports!
  "On subprocess failure, try parsing report XML and print hints (truncation, ANSI, SAX line/column)."
  [reports-dir exit]
  (let [dir (io/file reports-dir)]
    (when (.isDirectory dir)
      (doseq [f (sort-by #(.getName %) (.listFiles dir))
              :when (and (.isFile f) (clojure.string/ends-with? (.getName f) ".xml"))]
        (try
          (parse-junit-xml f exit)
          (catch clojure.lang.ExceptionInfo e
            (out [:bold.red (ex-message e)])
            (let [{:keys [size truncated? ansi-in-xml?]} (ex-data e)]
              (when (or size truncated? ansi-in-xml?)
                (out [:dim (str "  size=" size
                               (when truncated? " truncated")
                               (when ansi-in-xml? " ansi-in-xml"))])))))))))

(defn- assert-process-success!
  "Throws if tools.build `process` returned a non-zero :exit.
   Optional `reports-dir`: when set, parse Surefire XML on failure to distinguish crash mid-write from ordinary test failures."
  [label {:keys [exit] :as _result} & [reports-dir]]
  (when-not (zero? exit)
    (when reports-dir (diagnose-surefire-reports! reports-dir exit))
    (throw (ex-info (str label " exited with code " exit) {:exit exit :reports-dir reports-dir}))))

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

(defn- fqcn-from-test-classfile
  "Relative path under test-class-dir → dotted FQCN."
  [^java.io.File class-file ^java.io.File test-root]
  (let [root (.getCanonicalFile test-root)
        file (.getCanonicalFile class-file)
        sep (str java.io.File/separator)
        root-path (str (.getPath root) sep)
        file-path (.getPath file)]
    (when (.startsWith file-path root-path)
      (-> (subs file-path (count root-path))
          (clojure.string/replace #"\.class$" "")
          (clojure.string/replace "/" ".")))))

(defn- test-fqcns-matching-filter
  "Case-insensitive regex match against FQCN or simple class name."
  [class-pattern test-root]
  (let [re (re-pattern (str "(?i)" class-pattern))
        root (io/file test-root)]
    (when (.exists root)
      (->> (file-seq root)
           (filter #(.isFile ^java.io.File %))
           (filter #(.endsWith (.getName ^java.io.File %) ".class"))
           (filter #(not (clojure.string/includes? (.getName %) "$")))
           (keep #(fqcn-from-test-classfile % root))
           (filter #(or (re-find re %)
                        (re-find re (last (clojure.string/split % #"\.")))))
           distinct
           sort
           vec))))

(defn- junit-launcher-args-from-filter
  "Map :filter to JUnit ConsoleLauncher --select-class / --select-method (after compile-tests)."
  [filter-str test-root]
  (when (and filter-str (seq (str filter-str)))
    (let [[class-pattern method-name]
          (if (clojure.string/includes? filter-str "#")
            (let [[c m] (clojure.string/split filter-str #"#" 2)]
              [(str c) (when (seq m) (str m))])
            [(str filter-str) nil])
          matches (test-fqcns-matching-filter class-pattern test-root)]
      (when (empty? matches)
        (throw (ex-info (str "No test class matched :filter " (pr-str filter-str))
                        {:filter filter-str :class-pattern class-pattern})))
      (when (and method-name (< 1 (count matches)))
        (throw (ex-info (str ":filter matched multiple test classes; use an FQCN before '#': "
                             (pr-str matches))
                        {:filter filter-str :matches matches})))
      (if method-name
        [(str "--select-method=" (first matches) "#" method-name)]
        (mapv #(str "--select-class=" %) matches)))))

(def ^:private compiler-profile-tags
  "JUnit 5 tags for analyze/bytecode contract tests (see BytecodeDslTestSupport profiles)."
  #{"direct-linking-off" "direct-linking-on"})

(defn- junit-tag-launcher-args
  "ConsoleLauncher --include-tag / --exclude-tag flags from string collections."
  [{:keys [include-tags exclude-tags]}]
  (into []
        (concat (mapcat (fn [t] [(str "--include-tag=" t)]) (or include-tags []))
                (mapcat (fn [t] [(str "--exclude-tag=" t)]) (or exclude-tags [])))))

(defn- direct-linking-jvm-flags
  [direct-linking]
  [(str "-Dclojure.compiler.direct-linking=" (boolean direct-linking))])

(defn run-tests
  "[BYTECODE] Run Cloffle JUnit tests (scans all test classes; execution uses the Truffle bytecode backend).
   Fails the task (non-zero exit) if any JUnit test fails. Enables Java assertions (`-ea`).
   :fresh (default true) — run clean first so stale `target` classes cannot skew results; use false for faster incremental runs.
   :filter — substring/regex (case-insensitive) on test FQCN or simple class name; optional `ClassName#method`.
             Same spirit as `check-scalar-replacements` :filter. Ignored when :args is non-empty.
   :direct-linking — JVM flag for untagged tests (default true). Pass false for stock Var semantics.
   :include-tags / :exclude-tags — vectors of JUnit 5 tag strings (e.g. `\"direct-linking-off\"`).
   :args [] — optional args passed to JUnit ConsoleLauncher (e.g. :args '[\"--select-class=my.Test\"]')."
  [opts]
  (let [{:keys [args fresh filter direct-linking include-tags exclude-tags]}
        (merge {:fresh true :args [] :direct-linking true} opts)]
    (when fresh (clean nil))
    (compile-tests nil)
    (let [basis (b/create-basis {:project "deps.edn" :aliases [:test :dap :benchmark]})
          cp (into [benchmark-class-dir test-class-dir "test" "src/test/resources" class-dir fork-clojure-sources]
                   (runtime-classpath-roots basis))
          cp-str (clojure.string/join (System/getProperty "path.separator") cp)
          filter-args (when (empty? args) (junit-launcher-args-from-filter filter test-class-dir))
          tag-args (junit-tag-launcher-args {:include-tags include-tags :exclude-tags exclude-tags})
          launcher-args (if (seq args) args (into (or filter-args []) tag-args))]
      (assert-standalone-truffle-jars! cp)
      (out [:bold.cyan "\n===== Cloffle JUnit tests ====="])
      (when (seq filter-args)
        (out (str "  :filter → " (clojure.string/join " " filter-args))))
      (when (seq tag-args)
        (out (str "  tags → " (clojure.string/join " " tag-args))))
      (when (some? direct-linking)
        (out (str "  :direct-linking → " direct-linking)))
      (io/make-parents (io/file surefire-reports-dir "dummy"))
      (let [junit-base ["-cp" cp-str
                        "org.junit.platform.console.ConsoleLauncher"
                        "execute"
                        (str "--reports-dir=" surefire-reports-dir)
                        "--details=summary"]
            has-class-selector? (or (seq args) (seq filter-args))
            junit-opts (cond-> junit-base
                         (or (empty? launcher-args) (not has-class-selector?))
                         (conj "--scan-class-path")
                         (seq launcher-args) (into launcher-args))
            java-args (concat (test-suite-jvm-opts)
                              (direct-linking-jvm-flags direct-linking)
                              ["-Dclojure.use_shape_map=true"]
                              junit-opts)
            argfile (write-java-argfile java-args)
            proc (b/process
                  {:command-args ["java" argfile]
                   :out :inherit
                   :err :inherit})]
        (assert-process-success! "JUnit ConsoleLauncher" proc surefire-reports-dir)
        (out (str "\nJUnit reports: " surefire-reports-dir))))))

(defn run-tests-direct-linking-matrix
  "Run untagged JUnit tests twice: global `direct-linking` false then true (excludes profile-tagged tests).
   Profile tests (`direct-linking-off` / `direct-linking-on`) always bind `*compiler-options*` locally;
   run them via `run-tests` without `:exclude-tags` or with `:include-tags`.
   Invoke: clj -T:build run-tests-direct-linking-matrix
           clj -T:build run-tests-direct-linking-matrix :fresh false"
  [{:keys [fresh] :or {fresh true}}]
  (let [exclude (vec compiler-profile-tags)]
    (out [:bold.cyan "\n===== JUnit matrix: direct-linking false (no profile tags) ====="])
    (run-tests {:fresh fresh :direct-linking false :exclude-tags exclude})
    (out [:bold.cyan "\n===== JUnit matrix: direct-linking true (no profile tags) ====="])
    (run-tests {:fresh false :direct-linking true :exclude-tags exclude})))


(def ^:private cloffle-reports-dir "target/surefire-reports/cloffle")

(defn- surefire-xml-failing-cases
  "Returns {:suite :name :status} for testcase elements with failure or error."
  [xml-file & [exit]]
  (when (.exists (io/file xml-file))
    (filter #(#{:fail :error} (:status %)) (parse-junit-xml (io/file xml-file) exit))))

(defn- ensure-surefire-process-ok!
  "If the JVM exited non-zero or TEST-results.xml reports failures/errors, print
  failing case names and throw."
  [label {:keys [exit]} reports-dir]
  (let [xml-file (io/file reports-dir "TEST-results.xml")
        exit-bad? (not (zero? exit))
        failures (try
                   (or (surefire-xml-failing-cases xml-file exit) ())
                   (catch clojure.lang.ExceptionInfo e
                     (when exit-bad?
                       (diagnose-surefire-reports! reports-dir exit))
                     (throw e)))
        xml-bad? (seq failures)]
    (when (or exit-bad? xml-bad?)
      (cond
        xml-bad?
        (do (out [:bold.red (str "\n" label " — failing JUnit cases:")])
            (doseq [r failures]
              (out [:red (str "  " (:suite r) "/" (:name r) " [" (name (:status r)) "]")])))
        exit-bad?
        (do (out [:bold.red (str "\n" label " exited with code " exit ".")])
            (diagnose-surefire-reports! reports-dir exit)))
      (throw (ex-info (str label " failed")
                      {:exit exit
                       :reports-dir (str reports-dir)
                       :junit-xml (.getPath xml-file)
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
        args (concat (test-suite-jvm-opts)
                     ;; Macro spec checks on by default (RT.instrumentMacros), same as stock Ant test.
                     ["-Dclojure.test.quiet=true"
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
   " clojure.test-clojure.transducers"
   " clojure.test-clojure.reflector-array-set"])

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
   Enables Java assertions (`-ea`).
   Fails the task if the subprocess exits non-zero or TEST-results.xml contains failures/errors
   (lists failing case names before throwing).
   :fresh (default true) — run clean first so stale `target` classes cannot skew results; use false for faster incremental runs.
   Invoke: clj -T:build run-clj-tests
   Pprint-only (faster): clj -T:build run-clj-tests :only-namespace \"clojure.test-clojure.pprint\"
   Include generative tests: clj -T:build run-clj-tests :generative true
   Override excludes: clj -T:build run-clj-tests :exclude '\"#{ns1 ns2}\"'
   Single namespace: clj -T:build run-clj-tests :only-namespace \"clojure.test-clojure.string\"
   Single deftest: clj -T:build run-clj-tests :only-var '\"clojure.test-clojure.string/t-split\"'
   aset/RT coercion (unit+generative): clj -T:build test-array-set-coercion
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

(defn test-array-set-coercion
  "Focused suite for clojure.core/aset via RT + :cloffle/op (core.async random-array).
   Runs JUnit ReflectorArraySetTest then the clojure.test-clojure.reflector-array-set namespace
   (including test.check generative specs). Does not run the full Cloffle or Clojure suites.
   Invoke: clj -T:build test-array-set-coercion
           clj -T:build test-array-set-coercion :fresh false"
  [{:keys [fresh] :or {fresh true}}]
  (when fresh (clean nil))
  (compile-tests nil)
  (let [basis (b/create-basis {:project "deps.edn" :aliases [:test :dap :benchmark]})
        cp (into [benchmark-class-dir test-class-dir "test" "src/test/resources" class-dir fork-clojure-sources]
                 (runtime-classpath-roots basis))
        cp-str (clojure.string/join (System/getProperty "path.separator") cp)
        _ (assert-standalone-truffle-jars! cp)
        junit-args (concat (test-suite-jvm-opts)
                           ["-Dclojure.use_shape_map=true"
                            "-cp" cp-str
                            "org.junit.platform.console.ConsoleLauncher"
                            "execute"
                            (str "--reports-dir=" surefire-reports-dir)
                            "--details=summary"
                            "--select-class=clojure.lang.ReflectorArraySetTest"])
        junit-argfile (write-java-argfile junit-args)]
    (out [:bold.cyan "\n===== aset/RT coercion: JUnit ReflectorArraySetTest ====="])
    (assert-process-success!
     "ReflectorArraySetTest"
     (b/process {:command-args ["java" junit-argfile]
                 :out :inherit
                 :err :inherit}))
    (out [:bold.cyan "\n===== aset/RT coercion: Clojure generative + unit ====="])
    ;; Empty exclude so :only-namespace is not filtered; generative must be on for defspec.
    (run-clj-tests {:fresh false
                    :generative true
                    :exclude "#{}"
                    :only-namespace "clojure.test-clojure.reflector-array-set"})))
