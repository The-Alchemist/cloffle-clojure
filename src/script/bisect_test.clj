;; Times namespace loading. Reports per-namespace elapsed time.
;; Run via: java ... clojure.main src/script/bisect_test.clj

(System/setProperty "java.awt.headless" "true")

(require '[clojure.tools.namespace.find :as ns]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(let [exclude-ns (if-let [s (System/getProperty "clojure.test-clojure.exclude-namespaces")]
                   (read-string s)
                   #{})
      ns-limit   (some-> (System/getProperty "cloffle.bisect.ns-limit")
                         Integer/parseInt)
      all-ns     (let [candidates (remove exclude-ns (ns/find-namespaces-in-dir (java.io.File. "test")))]
                   (reduce (fn [acc n] (if (some #(= (str n) (str %)) acc) acc (conj acc n))) [] candidates))
      namespaces (if ns-limit (take ns-limit all-ns) all-ns)
      t0         (System/currentTimeMillis)]
  (println "Loading" (count namespaces) "namespaces...")
  (doseq [n namespaces]
    (let [t1 (System/currentTimeMillis)]
      (require n)
      (let [elapsed (- (System/currentTimeMillis) t1)]
        (println (format "%6d ms  %s" elapsed n)))))
  (println (format "\nTotal: %d ms for %d namespaces"
                   (- (System/currentTimeMillis) t0) (count namespaces)))
  (println "OK")
  (shutdown-agents)
  (System/exit 0))
