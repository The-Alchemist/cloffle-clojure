;; Advanced map + filter pipelines: chained seq ops, lazy vs eager, transducers,
;; mapcat, keep/remove, on nil/empty/literals/tuple/shape-map, and map entries.
;; Values only; throws record exception class only.

(defn- p [k v]
  (println (str k "\t" v)))

(defmacro probe [k & body]
  `(let [k# ~k]
     (try
       (p k# (pr-str (do ~@body)))
       (catch Throwable t#
         (p k# (str "THREW " (.getName (class t#))))))))

(def ^:private subjects
  [["nil" nil]
   ["ev" []]
   ["v5" [0 1 2 3 4]]
   ["v9" [0 1 2 3 4 5 6 7 8]]
   ["lazy" (map identity [0 1 2 3 4])]
   ["m2" {:a 1 :b 2 :c 3}]
   ["m9" {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8 :i 9}]])

(defn- sn [pair] (nth pair 0))
(defn- sv [pair] (nth pair 1))

(defmacro ^:private for-subj [[sym pairs] & body]
  `(doseq [pair# ~pairs]
     (let [~'sn (sn pair#)
           ~sym (sv pair#)]
       ~@body)))

(defn- pos-inc [x] (when (pos? x) (inc x)))

;; ---------------------------------------------------------------------------
;; Classic: (filter even? (map inc coll)) and reverse order
;; ---------------------------------------------------------------------------

(for-subj [coll subjects]
  (probe (str "edge19/map-filter/inc-even/" sn)
         (vec (filter even? (map inc coll))))
  (probe (str "edge19/filter-map/even-inc/" sn)
         (vec (map inc (filter even? coll))))
  (probe (str "edge19/map-filter/keep-pos-inc/" sn)
         (vec (keep pos-inc (map inc coll)))))

;; ---------------------------------------------------------------------------
;; Three-step chains: map -> filter -> map
;; ---------------------------------------------------------------------------

(for-subj [coll subjects]
  (probe (str "edge19/chain/mfi/" sn)
         (vec (->> coll
                   (map inc)
                   (filter even?)
                   (map #(* 2 %)))))
  (probe (str "edge19/chain/fim/" sn)
         (vec (->> coll
                   (filter number?)
                   (map inc)
                   (filter odd?)))))

;; ---------------------------------------------------------------------------
;; Eager: mapv / filterv / remove composed
;; ---------------------------------------------------------------------------

(for-subj [coll subjects]
  (probe (str "edge19/mapv-filterv/" sn)
         (mapv inc (filterv pos? coll)))
  (probe (str "edge19/filterv-mapv/" sn)
         (mapv #(* 2 %) (filterv even? (mapv inc coll))))
  (probe (str "edge19/remove-map/" sn)
         (vec (map inc (remove neg? coll)))))

;; ---------------------------------------------------------------------------
;; mapcat + filter
;; ---------------------------------------------------------------------------

(probe "edge19/mapcat-filter/nil"
       (vec (filter pos? (mapcat vector nil [1 2] [3]))))
(probe "edge19/mapcat-filter/v"
       (vec (filter pos? (mapcat vector [1 -1] [2 0] [3]))))
(probe "edge19/filter-mapcat"
       (vec (mapcat vector (filter even? [1 2 3 4]))))

;; ---------------------------------------------------------------------------
;; Transduce: comp (map) (filter) (map)
;; ---------------------------------------------------------------------------

(defn- xf-pipeline []
  (comp (map inc) (filter even?) (map #(* 3 %))))

(for-subj [coll subjects]
  (probe (str "edge19/transduce/xf/" sn)
         (transduce (xf-pipeline) conj [] coll))
  (probe (str "edge19/into/xf/" sn)
         (into [] (xf-pipeline) coll))
  (probe (str "edge19/eduction/xf/" sn)
         (vec (sequence (xf-pipeline) coll))))

;; ---------------------------------------------------------------------------
;; reduce after map+filter (no init / with init)
;; ---------------------------------------------------------------------------

(probe "edge19/reduce/map-filter-sum"
       (reduce + (filter even? (map inc [1 2 3 4]))))
(probe "edge19/transduce/count-xf"
       (transduce (comp (map inc) (filter pos?)) (completing (fn [n _] (inc n)) 0) [0 1 2]))

;; ---------------------------------------------------------------------------
;; On map entries: filter keys then map vals
;; ---------------------------------------------------------------------------

(def ^:private maps [["m2" {:a 1 :b 2 :c 3}]
                     ["m9" {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8 :i 9}]])
(for-subj [m maps]
  (probe (str "edge19/map-entries/filter-map/" sn)
         (into (sorted-map)
               (map (fn [[k v]] [k (inc v)])
                    (filter (fn [[_ v]] (even? v)) m)))))

(probe "edge19/keys-filter-map"
       (vec (map inc (filter pos? (vals {:a 0 :b 2 :c -1})))))
(probe "edge19/filter-keys-map-vals"
       (into (sorted-map)
             (map (fn [[k v]] [k (* 2 v)])
                  (filter (fn [[k _]] (not= k :a)) {:a 1 :b 2 :c 3}))))

;; ---------------------------------------------------------------------------
;; Literal tuple / vector lowering through map+filter
;; ---------------------------------------------------------------------------

(probe "edge19/lit-vec/chain"
       (vec (->> [1 2 3 4 5]
                 (map inc)
                 (filter even?)
                 (map #(/ % 2)))))
(probe "edge19/lit-map/vals-pipe"
       (vec (->> {:x 1 :y 2 :z 3}
                 vals
                 (map inc)
                 (filter odd?))))
(probe "edge19/lit-tuple8/chain"
       (vec (->> [1 2 3 4 5 6 7 8]
                 (map inc)
                 (filter even?)
                 (map #(- % 2)))))

;; ---------------------------------------------------------------------------
;; Lazy: take/drop around map+filter (chunk / ephemeral realization)
;; ---------------------------------------------------------------------------

(probe "edge19/lazy-take/map-filter"
       (vec (take 3 (filter even? (map inc (range 10))))))
(probe "edge19/lazy-drop/map-filter"
       (vec (drop 2 (filter pos? (map dec (range 10))))))
(probe "edge19/range/map-filter-sum"
       (reduce + (filter even? (map #(* % %) (range 5)))))

;; ---------------------------------------------------------------------------
;; map-indexed + filter
;; ---------------------------------------------------------------------------

(probe "edge19/map-indexed-filter"
       (vec (filter even? (map-indexed (fn [i x] (+ i x)) [10 20 30]))))
(probe "edge19/filter-map-indexed"
       (vec (map-indexed vector (filter pos? [ -1 1 2 -2 3]))))

;; ---------------------------------------------------------------------------
;; Threading-style pipeline on collections
;; ---------------------------------------------------------------------------

(probe "edge19/thread-last/nil-safe"
       (->> nil
            (filter identity)
            (map inc)
            (into [])))
(probe "edge19/thread-last/vec"
       (->> [1 2 3 4]
            (map inc)
            (filter even?)
            (map #(* % 10))
            vec))

;; ---------------------------------------------------------------------------
;; for = map+filter sugar (cross-check)
;; ---------------------------------------------------------------------------

(probe "edge19/for-vs-map-filter"
       (= (vec (for [x [1 2 3 4] :when (even? x)] (inc x)))
          (vec (map inc (filter even? [1 2 3 4])))))

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
