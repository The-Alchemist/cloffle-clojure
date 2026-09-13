;; Array / into-array pipelines: map/filter on arrays and back to vec.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(def ^:private ia (int-array [1 2 3 4 5]))
(def ^:private oa (object-array [1 "a" nil 3]))

(probe "edge29/int-array-map" (vec (map inc ia)))
(probe "edge29/int-array-filter" (count (filter even? (map identity ia))))
(probe "edge29/object-array-keep" (vec (keep identity oa)))
(probe "edge29/into-array-pipe" (vec (into-array Integer/TYPE (filter pos? (map inc [0 1 2])))))
(probe "edge29/array-seq-pipe" (vec (map inc (filter pos? (seq ia)))))
(probe "edge29/vec-to-array-back" (vec (filter odd? (map dec (vec ia)))))
(probe "edge29/aclone-map" (vec (map inc (aclone ia))))
(probe "edge29/array-sum" (reduce + (map identity ia)))
(probe "edge29/transduce-array" (transduce (comp (map inc) (filter even?)) + ia))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
