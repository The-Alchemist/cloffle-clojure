;; interleave, interpose, lazy-cat, concat deep chains with map/filter.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(probe "edge50/interleave-pipe" (vec (filter pos? (interleave [1 -1] [2 0] [3]))))
(probe "edge50/interpose-pipe" (vec (map inc (interpose 0 (filter pos? [1 2 3])))))
(probe "edge50/lazy-cat" (vec (take 6 (filter even? (lazy-cat [0 1] [2 3] [4 5])))))
(probe "edge50/concat-nest" (vec (map inc (filter pos? (concat [0] [1 2] (list 3))))))
(probe "edge50/mapcat-list" (vec (mapcat list (filter odd? [1 2 3 4]))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
