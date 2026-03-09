(System/setProperty "java.awt.headless" "true")
(require
 '[clojure.test :as test]
 '[clojure.tools.namespace.find :as ns])
(def namespaces (let [candidates (remove (read-string (System/getProperty "clojure.test-clojure.exclude-namespaces"))
                                         (ns/find-namespaces-in-dir (java.io.File. "test")))]
                  (reduce (fn [acc n] (if (some #(= (str n) (str %)) acc) acc (conj acc n))) [] candidates)))
(doseq [ns namespaces] (require ns))
(let [summary (apply test/run-tests namespaces)]
  (System/exit (if (test/successful? summary) 0 -1)))
