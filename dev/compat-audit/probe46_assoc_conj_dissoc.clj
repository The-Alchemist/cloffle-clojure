;; assoc/dissoc/conj on literals through filter/map chains.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(probe "edge46/assoc-chain" (assoc (assoc {} :a 1) :b 2))
(probe "edge46/dissoc-chain" (dissoc (assoc {:a 1 :b 2 :c 3} :d 4) :a :c))
(probe "edge46/conj-vec-pipe" (vec (map inc (conj [1 2] 3 4))))
(probe "edge46/conj-set" (set (conj #{} 1 2 2)))
(probe "edge46/map-pipe-keys" (vec (keys (dissoc (assoc {:x 1 :y 2} :z 3) :y))))
(probe "edge46/merge-assoc" (merge (assoc nil :a 1) (assoc {} :b 2)))
(probe "edge46/lit-map-dissoc" (dissoc {:a 1 :b 2 :c 3 :d 4} :b :d))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
