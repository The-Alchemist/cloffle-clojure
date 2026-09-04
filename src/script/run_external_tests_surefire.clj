(ns run-external-tests-surefire
  (:require [clojure.test :as test]
            [clojure.test.junit :as junit]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(let [reports-dir (or (System/getProperty "surefire.reports.dir")
                      (throw (ex-info "surefire.reports.dir not set" {})))
      only-var-prop (some-> (System/getProperty "clojure.test.only-var")
                            str/trim
                            not-empty)
      only-var (when only-var-prop (symbol only-var-prop))
      namespaces (map symbol *command-line-args*)]
  (.mkdirs (io/file reports-dir))
  (if only-var
    (let [var-ns (some-> (namespace only-var) symbol)]
      (when-not var-ns
        (throw (ex-info (str "clojure.test.only-var must be namespace-qualified: " only-var)
                        {:only-var only-var})))
      (println "Running test for var:" only-var)
      (require var-ns)
      (let [v (or (find-var only-var) (resolve only-var))]
        (when-not v
          (throw (ex-info (str "Unable to resolve var: " only-var) {:only-var only-var})))
        (when-not (:test (meta v))
          (throw (ex-info (str "Var is not a test (missing :test metadata): " only-var) {:only-var only-var})))
        (let [out-file (io/file reports-dir "TEST-results.xml")]
          (with-open [w (io/writer out-file)]
            (let [summary (binding [test/*test-out* w]
                            (junit/with-junit-output
                              (test/run-test-var v)))]
              (println (format "Ran %d tests containing %d assertions."
                               (:test summary 0) (+ (:pass summary 0) (:fail summary 0) (:error summary 0))))
              (println (format "%d failures, %d errors." (:fail summary 0) (:error summary 0)))
              (println (str "JUnit XML -> " (.getPath out-file)))
              (System/exit (if (test/successful? summary) 0 1)))))))
    (do
      (println "Running tests for:" namespaces)
      (doseq [n namespaces] (require n))
      (let [out-file (io/file reports-dir "TEST-results.xml")]
        (with-open [w (io/writer out-file)]
          (let [summary (binding [test/*test-out* w]
                          (junit/with-junit-output
                            (apply test/run-tests namespaces)))]
            (println (format "Ran %d tests containing %d assertions."
                             (:test summary 0) (+ (:pass summary 0) (:fail summary 0) (:error summary 0))))
            (println (format "%d failures, %d errors." (:fail summary 0) (:error summary 0)))
            (println (str "JUnit XML -> " (.getPath out-file)))
            (System/exit (if (test/successful? summary) 0 1))))))))
