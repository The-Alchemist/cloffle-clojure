;; Numeric pipelines: bigint/ratio/mod/quot in map/filter chains, reductions.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(probe "edge25/mod-filter" (vec (filter zero? (map #(mod % 3) (range 12)))))
(probe "edge25/quot-map" (vec (map quot [7 8 9 10] [3 3 3 3])))
(probe "edge25/ratio-pipe" (vec (filter rational? (map #(/ % 3) [1 2 3 4 5]))))
(probe "edge25/bigint-chain" (+ 1N (reduce + (filter pos? (map bigint [1 -1 2 3])))))
(probe "edge25/inc-long" (vec (map inc [1 2 3])))
(probe "edge25/abs-filter" (vec (filter pos? (map #(Math/abs ^long %) [-2 -1 0 1 2]))))
(probe "edge25/min-max-pipe" [(apply min (filter odd? (range 10))) (apply max (filter even? (range 10)))])
(probe "edge25/reductions-mul" (vec (take 5 (reductions * (filter pos? (map inc (range 6)))))))
(probe "edge25/transduce-sum-squares"
       (transduce (comp (filter even?) (map #(* % %))) + (range 10)))
(probe "edge25/==mixed" (vec (filter true? (map = [1 2 3] [1 0 3]))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
