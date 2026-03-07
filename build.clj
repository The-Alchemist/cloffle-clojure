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
  "Compile src/jvm + src/main/java (circular dependency: src/jvm imports ClojureInterop)."
  (b/copy-dir {:src-dirs ["src/resources"]
               :target-dir class-dir})
  (write-version-properties)
  (let [basis @basis-with-processor
        proc-path (clojure.string/join (System/getProperty "path.separator")
                                       (:classpath-roots basis))]
    (b/javac {:src-dirs ["src/jvm" "src/main/java"]
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
  ;; src/jvm and src/main/java have circular deps (ClojureInterop), so compile together.
  ;; Then Clojure AOT (bootstrap) so Truffle code can run.
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

(defn run-repl
  "Run CloffleREPL (interactive REPL, --demo, or a .clj file). Args: {:args []}"
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

(defn run-tests [_]
  (compile-all nil)
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
                                     "--scan-class-path"
                                     (str "--reports-dir=" surefire-reports-dir)
                                     "--details=summary"]))
        :out :inherit
        :err :inherit})
      (println "\nJUnit reports:" surefire-reports-dir))))

