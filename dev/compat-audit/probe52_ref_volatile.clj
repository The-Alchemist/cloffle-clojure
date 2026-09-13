;; ref/volatile/atom updates feeding map/filter results (pure outputs).
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(probe "edge52/ref-pipe" (let [r (ref 0)] (dosync (alter r + 1) (alter r + 2)) @r))
(probe "edge52/volatile-pipe" (let [v (volatile! [])] (doseq [x (filter pos? (map inc [0 1 2]))] (vswap! v conj x)) @v))
(probe "edge52/atom-map" (let [a (atom 1)] (swap! a (comp inc (partial * 2))) @a))
(probe "edge52/ref-set" (let [r (ref 10)] (dosync (ref-set r 5)) @r))
(probe "edge52/compare-set-vol" (vec (map deref [(atom 1) (volatile! 2)])))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
