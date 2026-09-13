;; vec/set/list/seq conversions through map+filter.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(probe "edge38/vec-pipe" (vec (filter pos? (map inc '(0 1 2)))))
(probe "edge38/set-pipe" (set (map inc (filter even? [1 2 3 4]))))
(probe "edge38/list-pipe" (list* (filter odd? (map inc [0 1 2 3]))))
(probe "edge38/seq-vec" (vec (seq (filter identity [nil 1 2]))))
(probe "edge38/array-vec" (vec (into-array (filter number? [1 2 nil 3]))))
(probe "edge38/flatten-vec" (vec (filter number? (flatten [[1 [2]] 3]))))
(probe "edge38/reverse-vec" (vec (map inc (reverse (filter pos? [1 2 3])))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
