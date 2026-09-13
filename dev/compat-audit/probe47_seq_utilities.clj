;; first/rest/next/cons/second/last/ffirst/nfirst through pipelines.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(probe "edge47/first-rest" [(first [1 2 3]) (rest [1 2 3])])
(probe "edge47/second-last" [(second [1 2 3]) (last [1 2 3])])
(probe "edge47/ffirst-pipe" (ffirst (map vector (filter pos? [1 2]))))
(probe "edge47/nfirst" (nfirst (filter seq? (list (list 1 2) nil))))
(probe "edge47/cons-pipe" (vec (map first (map #(cons 0 %) (filter vector? [[1] [2]])))))
(probe "edge47/seq-rest" (vec (map first (rest (seq [10 20 30])))))
(probe "edge47/next-nil" (next nil))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
