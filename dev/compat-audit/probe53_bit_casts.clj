;; bit ops, casts, unchecked-friendly paths in map/filter (checked default).
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(probe "edge53/bit-and-pipe" (vec (map #(bit-and % 3) (filter pos? (range 8)))))
(probe "edge53/bit-or" (reduce bit-or (map #(bit-shift-left 1 %) (filter even? (range 6)))))
(probe "edge53/int-cast" (vec (map int (filter number? [1.2 2.7 3]))))
(probe "edge53/long-pipe" (vec (map long (filter pos? [1 2 3]))))
(probe "edge53/byte-short" [(byte 127) (short 1000)])
(probe "edge53/boolean-compare" (vec (map #(compare % true) [true false true])))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
