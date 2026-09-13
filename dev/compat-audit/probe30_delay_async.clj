;; delay / promise / future (bounded) through map+filter on derefs.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(probe "edge30/delay-map" (vec (map deref [(delay 1) (delay 2) (delay 3)])))
(probe "edge30/delay-filter" (vec (filter pos? (map deref [(delay 0) (delay 1) (delay -1)]))))
(probe "edge30/promise-pipe" (let [p (promise)] (deliver p 42) (map inc [(deref p)])))
(probe "edge30/future-take"
       (vec (take 3 (map deref (map #(future (inc %)) [1 2 3 4])))))
(probe "edge30/lazy-delay-chain"
       (vec (map inc (filter even? (map deref [(delay 1) (delay 2) (delay 3)])))))
(probe "edge30/realized-pipe"
       (let [d (delay 42)] (deref d) (realized? d)))
(probe "edge30/force-seq" (vec (map force [(delay 10) (delay 20)])))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
