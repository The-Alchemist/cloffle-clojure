(require '[clojure.tools.namespace.find :as ns]
         '[clojure.test :as test])

(let [exclude-ns (if-let [s (System/getProperty "clojure.test-clojure.exclude-namespaces")]
                   (read-string s)
                   #{})
      all-ns     (let [candidates (remove exclude-ns (ns/find-namespaces-in-dir (java.io.File. "test")))]
                   (reduce (fn [acc n] (if (some #(= (str n) (str %)) acc) acc (conj acc n))) [] candidates))
      namespaces all-ns]
  (println "Loading" (count namespaces) "namespaces...")
  (doseq [n namespaces]
    (require n))
  (println "All namespaces loaded. Running tests one-by-one...")
  (doseq [n namespaces]
    (print (str "  Testing " n "... "))
    (flush)
    (try
      (test/run-tests n)
      (println "OK")
      (catch Throwable t
        (println "FAIL:" (.getMessage t))
        (when (instance? clojure.lang.ArityException t)
          (println "  >>> ArityException found! Namespace:" n)
          (.printStackTrace t System/err)
          (shutdown-agents)
          (System/exit 1)))))
  (println "All done.")
  (shutdown-agents)
  (System/exit 0))
