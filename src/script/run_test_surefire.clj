;; Runs the Clojure test suite and writes JUnit XML to a file.
;; Uses clojure.test.junit/with-junit-output — no custom reporter.
;;
;; System properties:
;;   clojure.test-clojure.exclude-namespaces  - set of ns symbols to skip
;;   surefire.reports.dir                     - output directory (required)

(System/setProperty "java.awt.headless" "true")

(require '[clojure.test :as test]
         '[clojure.test.junit :as junit]
         '[clojure.tools.namespace.find :as ns]
         '[clojure.java.io :as io])

(let [reports-dir (or (System/getProperty "surefire.reports.dir")
                      (throw (ex-info "surefire.reports.dir not set" {})))
      exclude-ns  (if-let [s (System/getProperty "clojure.test-clojure.exclude-namespaces")]
                    (read-string s)
                    #{})
      namespaces  (remove exclude-ns (ns/find-namespaces-in-dir (java.io.File. "test")))
      out-file    (io/file reports-dir "TEST-results.xml")]
  (.mkdirs (io/file reports-dir))
  (doseq [n namespaces] (require n))
  (with-open [w (io/writer out-file)]
    (let [summary (binding [test/*test-out* w]
                    (junit/with-junit-output
                      (apply test/run-tests namespaces)))]
      (println (format "Ran %d tests containing %d assertions."
                       (:test summary 0) (+ (:pass summary 0) (:fail summary 0) (:error summary 0))))
      (println (format "%d failures, %d errors." (:fail summary 0) (:error summary 0)))
      (println (str "JUnit XML -> " (.getPath out-file)))
      (System/exit (if (test/successful? summary) 0 1)))))
