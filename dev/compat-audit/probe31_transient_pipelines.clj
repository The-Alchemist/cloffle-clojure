;; Build collections via transients + map/filter logic on persistent results.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(defn- build-vec [xs]
  (persistent!
   (reduce (fn [t x] (conj! t x))
           (transient [])
           (filter number? (map inc xs)))))

(probe "edge31/build-vec" (build-vec [0 1 2 nil 3]))
(probe "edge31/transient-map-pipe"
       (persistent!
        (reduce (fn [m [k v]] (assoc! m k (inc v)))
                (transient {})
                (filter (fn [[_ v]] (even? v))
                        (map (fn [x] [x x]) (filter pos? (range 6)))))))
(probe "edge31/transient-set"
       (persistent!
        (reduce conj!
                (transient #{})
                (map inc (filter odd? (range 8))))))
(probe "edge31/conj-persistent-pipe"
       (vec (filter even? (map inc (persistent! (reduce conj! (transient []) (range 5)))))))
(probe "edge31/filter-after-persistent"
       (count (filter pos? (map inc (persistent! (reduce conj! (transient [0]) [1 2]))))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
