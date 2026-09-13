;; Map/vector literal size boundaries (2,8,9,16) through map/filter/count.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(def ^:private m8 {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8})
(def ^:private m9 (assoc m8 :i 9))
(def ^:private v8 [1 2 3 4 5 6 7 8])
(probe "edge49/map8-vals" (vec (map inc (filter pos? (vals m8)))))
(probe "edge49/map9-keys" (count (filter keyword? (keys m9))))
(probe "edge49/v8-sum" (reduce + (map identity v8)))
(probe "edge49/v8-filter-map" (vec (map #(* 2 %) (filter even? v8))))
(probe "edge49/map2" (vec (keys (filter (comp pos? val) {:a 1 :b -1}))))
(probe "edge49/vec3-pipe" (vec (map inc (filter pos? [1 2 3]))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
