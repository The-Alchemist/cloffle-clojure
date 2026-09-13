;; edn/read-string + data readers on small literals; map/filter on read results.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(require '[clojure.edn :as edn])

(probe "edge34/read-vector" (edn/read-string "[1 2 3]"))
(probe "edge34/read-map" (edn/read-string "{:a 1 :b 2}"))
(probe "edge34/read-set" (edn/read-string "#{1 2}"))
(probe "edge34/map-read-lines" (vec (map edn/read-string ["[1]" "{:x 1}" "nil"])))
(probe "edge34/filter-read" (vec (keep edn/read-string ["1" "not-edn" "[2 3]"])))
(probe "edge34/read-keyword" (edn/read-string ":kw/ns"))
(probe "edge34/pipe-sum" (reduce + (map edn/read-string ["1" "2" "3"])))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
