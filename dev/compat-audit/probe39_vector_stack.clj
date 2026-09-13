;; Vector peek/pop/conj/subvec/nth stacks with map/filter.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(def ^:private v [10 20 30 40 50])
(probe "edge39/peek-pop" [(peek v) (peek (pop v))])
(probe "edge39/conj-pipe" (vec (map inc (filter pos? (conj v 0 -1)))))
(probe "edge39/subvec-map" (vec (map #(* 2 %) (subvec v 1 4))))
(probe "edge39/nth-filter" (vec (filter number? (map #(nth v %) [0 2 4]))))
(probe "edge39/assoc-pipe" (vec (vals (filter (comp even? val) (assoc v 2 99)))))
(probe "edge39/replace-pipe" (vec (map inc (filter pos? (replace {20 0} v)))))
(probe "edge39/zipmap-vec" (zipmap (filter pos? (range 5)) (map inc (range 5))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
