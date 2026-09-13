;; Metadata through map/filter/vary-meta; probe values not full meta maps where arglists differ.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(def v-with (with-meta [1 2 3] {:a 1 :b 2}))
(probe "edge26/meta-get" (:a (meta v-with)))
(probe "edge26/vary-meta-assoc" (:c (meta (vary-meta v-with assoc :c 3))))
(probe "edge26/map-meta-keys" (vec (sort (keys (meta v-with)))))
(probe "edge26/filter-meta-vals" (vec (filter number? (vals (meta v-with)))))
(probe "edge26/meta-map-pipe"
       (into (sorted-map)
             (map (fn [[k v]] [k (inc v)])
                  (filter (fn [[_ v]] (number? v)) (meta v-with)))))
(probe "edge26/with-meta-list" (:tag (meta (with-meta '(1 2) {:tag :lst}))))
(probe "edge26/alter-meta"
       (let [o (with-meta {} {:x 1})]
         (alter-meta! o assoc :y 2)
         (:y (meta o))))
(probe "edge26/meta-nil" (meta nil))
(probe "edge26/count-meta" (count (meta {})))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
