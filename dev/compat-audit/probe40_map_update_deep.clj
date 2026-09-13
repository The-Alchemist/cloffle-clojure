;; get-in/update-in/assoc-in chains with filter/map on paths and values.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(def ^:private m {:a {:b 1 :c 2} :d [1 2 3]})
(probe "edge40/get-in-pipe" (get-in m [:a :b]))
(probe "edge40/update-in-pipe" (get-in (update-in m [:a :b] inc) [:a :b]))
(probe "edge40/assoc-in-pipe" (get-in (assoc-in m [:a :z] 9) [:a :z]))
(probe "edge40/keys-filter" (vec (filter keyword? (keys m))))
(probe "edge40/vals-map" (vec (map inc (filter number? (vals {:x 1 :y 2 :z "s"})))))
(probe "edge40/merge-select" (select-keys (merge m {:e 5}) [:a :e :missing]))
(probe "edge40/update-keys-pipe" (update-keys {:foo 1 :bar 2} keyword))
(probe "edge40/update-vals-pipe" (update-vals {:a 1 :b 2} inc))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
