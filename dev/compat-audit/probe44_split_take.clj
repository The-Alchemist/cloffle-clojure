;; split-at/with, take/drop/take-nth, take-last/drop-last pipelines.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(probe "edge44/split-at" (mapv vec (split-at 3 (range 6))))
(probe "edge44/split-with" (mapv vec (split-with pos? [-1 0 1 2 -2])))
(probe "edge44/take-drop" (vec (take 3 (drop 2 (range 10)))))
(probe "edge44/take-nth" (vec (map inc (take-nth 2 (range 8)))))
(probe "edge44/take-last-pipe" (vec (filter even? (take-last 4 (map inc (range 10))))))
(probe "edge44/drop-last-pipe" (vec (map #(* 2 %) (drop-last 2 [1 2 3 4 5]))))
(probe "edge44/splitv-at" (mapv vec (splitv-at 2 [10 20 30 40])))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
