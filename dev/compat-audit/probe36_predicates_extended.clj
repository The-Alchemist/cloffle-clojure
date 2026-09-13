;; Extended predicates on collections and numbers after map/filter.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(probe "edge36/sequential?" (sequential? (filter pos? [1])))
(probe "edge36/associative?" (associative? {}))
(probe "edge36/counted?" (counted? [1 2]))
(probe "edge36/seqable?" (seqable? 1))
(probe "edge36/seqable-nil" (seqable? nil))
(probe "edge36/ifn?" (ifn? inc))
(probe "edge36/char?" (char? \a))
(probe "edge36/string?" (string? "x"))
(probe "edge36/map-pred-pipe" (every? map? (filter map? [{:a 1} nil "x" {}])))
(probe "edge36/number-pipe" (vec (filter number? (map identity [1 "a" 2 nil 3]))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
