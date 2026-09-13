;; Sorted maps/sets and order-preserving map+filter pipelines.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(probe "edge28/sorted-map-pipe" (vec (map val (filter (comp even? val) (sorted-map :z 1 :a 2 :m 3)))))
(probe "edge28/sorted-set-map" (vec (map inc (filter even? (sorted-set 1 2 3 4 5)))))
(probe "edge28/sort-by-pipe" (vec (map :n (sort-by :n (filter #(< (:n %) 4) [{:n 3} {:n 1} {:n 5}])))))
(probe "edge28/sort-pipe" (vec (filter string? (sort ["b" "a" "c"]))))
(probe "edge28/sorted-map-by" (vec (keys (sorted-map-by compare "b" 1 "a" 2 "c" 3))))
(probe "edge28/rseq-sorted" (vec (map first (filter vector? (map vec (rseq (sort [3 1 2])))))))
(probe "edge28/distinct-sort" (vec (sort (distinct (map #(mod % 3) [1 2 3 4 5 6 7])))))
(probe "edge28/into-sorted" (into (sorted-map) (filter (fn [[_ v]] (even? v)) {:b 2 :a 1 :c 4})))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
