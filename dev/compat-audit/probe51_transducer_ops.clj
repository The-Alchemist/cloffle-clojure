;; map-indexed, cat, replace, take/drop, completing in transducers.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(def ^:private xf (comp (map inc) (filter even?) (map-indexed vector)))
(probe "edge51/xf-indexed" (vec (into [] xf (range 6))))
(probe "edge51/cat-xf" (vec (into [] (comp cat (map inc) (filter pos?)) [[0 1] [2]])))
(probe "edge51/replace-xf" (vec (into [] (map (fn [x] (get {1 :a 2 :b} x x))) [1 2 3])))
(probe "edge51/take-drop-xf" (vec (into [] (comp (drop 2) (take 3) (map inc)) (range 10))))
(probe "edge51/completing-count" (transduce (filter even?) (completing (fn [n _] (inc n)) 0) (range 8)))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
