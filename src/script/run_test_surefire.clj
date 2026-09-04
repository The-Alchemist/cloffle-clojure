;; Runs the Clojure test suite and writes JUnit XML to a file.
;; Uses clojure.test.junit/with-junit-output — no custom reporter.
;;
;; System properties:
;;   clojure.test-clojure.exclude-namespaces  - set of ns symbols to skip
;;   clojure.test-clojure.only-namespace      - run only this namespace
;;   clojure.test.only-var                    - run only this fully qualified deftest
;;   surefire.reports.dir                     - output directory (required)
;;   clojure.test.progress                    - when "true", print progress to System/out via an
;;                                              auto-flushing PrintWriter (require then deftests
;;                                              per namespace, in that order)

(System/setProperty "java.awt.headless" "true")

(require '[clojure.test :as test]
         '[clojure.test.junit :as junit]
         '[clojure.tools.namespace.find :as ns]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(let [reports-dir (or (System/getProperty "surefire.reports.dir")
                      (throw (ex-info "surefire.reports.dir not set" {})))
      exclude-ns  (if-let [s (System/getProperty "clojure.test-clojure.exclude-namespaces")]
                    (read-string s)
                    #{})
      only-var-prop (some-> (System/getProperty "clojure.test.only-var")
                            str/trim
                            not-empty)
      only-var    (when only-var-prop (symbol only-var-prop))
      only-ns     (or (some-> only-var namespace symbol)
                      (some-> (System/getProperty "clojure.test-clojure.only-namespace")
                              str/trim
                              not-empty
                              symbol))
      namespaces  (if only-ns
                    [only-ns]
                    (let [candidates (remove exclude-ns (ns/find-namespaces-in-dir (java.io.File. "test")))]
                      (reduce (fn [acc n] (if (some #(= (str n) (str %)) acc) acc (conj acc n))) [] candidates)))
      out-file    (io/file reports-dir "TEST-results.xml")
      progress?   (= "true" (System/getProperty "clojure.test.progress"))
      ;; Avoid *out* buffering when stdout is not a TTY (piped / IDE capture).
      progress-out (when progress?
                     (java.io.PrintWriter.
                      (java.io.OutputStreamWriter.
                       System/out java.nio.charset.StandardCharsets/UTF_8)
                      true))
      progress!   (fn [s]
                    (when progress-out
                      (.println progress-out s)
                      (.flush progress-out)))]
  (when (and only-var (not (namespace only-var)))
    (throw (ex-info (str "clojure.test.only-var must be namespace-qualified: " only-var)
                    {:only-var only-var})))
  (.mkdirs (io/file reports-dir))
  (when progress?
    (progress! (if only-var
                 (str "[clojure test] only-var " only-var)
                 (str "[clojure test] " (count namespaces) " namespace(s), load + run each…"))))
  (with-open [w (io/writer out-file)]
    (let [user-ns (the-ns 'user)
          summary (binding [test/*test-out* w]
                    (junit/with-junit-output
                      (binding [test/report
                                (fn [m]
                                  (when progress?
                                    (when (= :begin-test-var (:type m))
                                      (let [v (:var m)]
                                        (progress!
                                         (str "    · " (name (ns-name (:ns (meta v))))
                                              "/" (:name (meta v)))))))
                                  (junit/junit-report m))]
                        (if only-var
                          (do
                            (when progress?
                              (progress! (str "  require " (symbol (namespace only-var)))))
                            (require (symbol (namespace only-var)))
                            (let [v (or (find-var only-var) (resolve only-var))]
                              (when-not v
                                (throw (ex-info (str "Unable to resolve var: " only-var) {:only-var only-var})))
                              (when-not (:test (meta v))
                                (throw (ex-info (str "Var is not a test (missing :test metadata): " only-var)
                                                {:only-var only-var})))
                              (binding [*ns* user-ns]
                                (test/run-test-var v))))
                          (let [results
                                (mapv (fn [ns-sym]
                                        (when progress?
                                          (progress! (str "  require " ns-sym)))
                                        (require ns-sym)
                                        (binding [*ns* user-ns]
                                          (test/test-ns ns-sym)))
                                      namespaces)
                                summary (assoc (apply merge-with + results)
                                               :type :summary)]
                            (test/do-report summary)
                            summary)))))]
      (println (format "Ran %d tests containing %d assertions."
                       (:test summary 0) (+ (:pass summary 0) (:fail summary 0) (:error summary 0))))
      (println (format "%d failures, %d errors." (:fail summary 0) (:error summary 0)))
      (println (str "JUnit XML -> " (.getPath out-file)))
      (System/exit (if (test/successful? summary) 0 1)))))
