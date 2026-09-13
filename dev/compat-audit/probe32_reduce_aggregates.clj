;; Complex reduces: merge, group counts, transduce to maps/sets, merge-with chains.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(probe "edge32/reduce-conj-filter" (reduce conj [] (filter pos? (map inc [-1 0 1 2]))))
(probe "edge32/reduce-into-map"
       (reduce (fn [m x] (update m x (fnil inc 0)))
               {}
               (filter string? (map name [:a :a :b]))))
(probe "edge32/transduce-to-set" (into #{} (comp (map inc) (filter even?)) (range 8)))
(probe "edge32/merge-with-pipe"
       (merge-with +
                 (into {} (map (fn [x] [x x]) (filter odd? (range 6))))
                 (into {} (map (fn [x] [x (* 2 x)]) (filter even? (range 6))))))
(probe "edge32/group-counts"
       (frequencies (filter keyword? (map keyword ["a" "b" "a" "c" "b" "a"]))))
(probe "edge32/reduce-kv-pipe"
       (reduce-kv (fn [acc k v] (+ acc k v)) 0
                  (into {} (filter (fn [[_ v]] (even? v))
                                   (map (fn [x] [x x]) (range 6))))))
(probe "edge32/cat-reduce" (apply + (mapcat vector [1 2] [3 4] [5])))
(probe "edge32/nested-reduce" (reduce + 0 (map (partial reduce +) (partition-all 2 (range 10)))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
