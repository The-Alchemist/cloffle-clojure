;; Category: compile Cloffle runtime (`compile-java`, `compile-all`).
(in-ns 'build)

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
(def jar-file (format "target/%s-%s.jar" (name lib) version))

;; One line: relative path to the JAR produced by `jar` (for Docker COPY without globs).
(def jar-artifact-manifest "target/jar-artifact.txt")

(declare dump-core-bytecode)
