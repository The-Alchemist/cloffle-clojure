(ns run-external-tests-surefire
  (:require [clojure.test :as test]
            [clojure.test.junit :as junit]
            [clojure.java.io :as io]))

(let [reports-dir (or (System/getProperty "surefire.reports.dir")
                      (throw (ex-info "surefire.reports.dir not set" {})))
      namespaces (map symbol *command-line-args*)]
  
  (println "Running tests for:" namespaces)
  (.mkdirs (io/file reports-dir))
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
        (System/exit (if (test/successful? summary) 0 1))))))
