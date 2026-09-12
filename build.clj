(ns build
  (:refer-clojure :exclude [compile test])
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [clojure.string]
            [clj-commons.ansi :as ansi])
  (:import [com.github.thealchemist BgvDump]))

;; Build tasks are split under `src/build/` and loaded into this namespace (load order matters).
;;   01-shared          — version/paths, ANSI output, JVM subprocess helpers, `clean`
;;   02-compile         — `compile-java`, `compile-all`, JAR paths
;;   03-benchmark       — JMH compile/run, `compare-performance`
;;   05-test            — JUnit/Surefire, `run-tests`, `run-clj-tests`, `compile-tests`
;;   04-jar-bytecode-runtime — `jar`, bytecode cache dump, REPL/main/DAP entrypoints
;;   06-scalar          — allocation budgets, Graal/Seafoam scalar-replacement gates
;;   07-compat          — external-project submodules, `compat-test`, `audit-compat` probes
(doseq [f ["src/build/01-shared.clj"
           "src/build/02-compile.clj"
           "src/build/03-benchmark.clj"
           "src/build/05-test.clj"
           "src/build/04-jar-bytecode-runtime.clj"
           "src/build/06-scalar.clj"
           "src/build/07-compat.clj"]]
  (load-file f))

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
