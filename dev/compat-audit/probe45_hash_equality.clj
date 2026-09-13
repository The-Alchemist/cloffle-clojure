;; hash, =, identical?, compare on values from map/filter pipelines.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(probe "edge45/=-vec-pipe" (= (vec (map inc (filter pos? [0 1]))) [1 2]))
(probe "edge45/hash-sets" (= (hash (set (map inc [1 2 3]))) (hash #{2 3 4})))
(probe "edge45/identical-kw" (identical? :a :a))
(probe "edge45/distinct-=" (vec (map = [1 2 3] [1 0 3])))
(probe "edge45/compare-pipe" (vec (map compare ["a" "b" "c"] ["b" "b" "a"])))
(probe "edge45/not=" (vec (filter not= (map vector [1 2] [1 3]))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
