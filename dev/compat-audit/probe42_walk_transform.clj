;; clojure.walk pre/post/prewalk with map/filter-like transforms.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(require '[clojure.walk :as walk])

(probe "edge42/postwalk-inc" (walk/postwalk #(if (number? %) (inc %) %) {:a 1 :b [2 3]}))
(probe "edge42/prewalk-filter-keys" (walk/prewalk (fn [x] (if (map? x) (select-keys x [:a]) x)) {:a 1 :b 2}))
(probe "edge42/walk-seq" (walk/walk identity reverse [1 [2 3] 4]))
(probe "edge42/postwalk-replace" (walk/postwalk-replace {1 :one 2 :two} [1 2 [1]]))
(probe "edge42/prewalk-replace" (walk/prewalk-replace {9 1 2 99} {:x 9 :y 2}))
(probe "edge42/stringify-nums" (walk/postwalk #(if (number? %) (str %) %) {:n 1 :v [2]}))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
