;; tree-seq, partition, partition-by (safe n), group-by trees.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(probe "edge43/tree-seq-count" (count (tree-seq map? seq {:a {:b 1} :c 2})))
(probe "edge43/tree-filter" (vec (filter number? (tree-seq map? seq {:a 1 :b {:c 2}}))))
(probe "edge43/partition-2" (vec (partition 2 [1 2 3 4 5])))
(probe "edge43/partition-by-mod" (vec (partition-by #(mod % 3) (range 10))))
(probe "edge43/partition-all-2" (vec (partition-all 2 (range 5))))
(probe "edge43/group-mod" (into (sorted-map) (map (fn [[k v]] [k (count v)]) (group-by #(mod % 3) (range 9)))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
