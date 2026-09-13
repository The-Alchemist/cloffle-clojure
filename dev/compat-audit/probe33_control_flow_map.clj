;; case/cond/if inside map/filter/keep; optional results.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(defn- classify [x]
  (case (mod x 3) 0 :z 1 :a 2 :b))

(probe "edge33/case-map" (vec (map classify (range 9))))
(probe "edge33/cond-map"
       (vec (map (fn [x] (cond (> x 5) :big (> x 2) :mid :else :small)) (range 8))))
(probe "edge33/keep-case" (vec (keep (fn [x] (when (pos? x) (classify x))) (range -2 5))))
(probe "edge33/filter-case" (vec (filter #(= :a (classify %)) (range 12))))
(probe "edge33/map-if"
       (vec (map (fn [x] (if (even? x) (* x 2) x)) (range 6))))
(probe "edge33/when-map" (vec (map #(when (odd? %) %) (range 6))))
(probe "edge33/some-fn-pipe" ((some-fn number? pos?) 1))
(probe "edge33/every-pred-pipe" (every? true? (map boolean (filter identity [1 2 3]))))
(probe "edge33/if-let-seq" (vec (keep (fn [x] (when-let [y (when (number? x) (inc x))] y)) [1 "a" 2])))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
