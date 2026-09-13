;; Higher-order: map/filter with computed fns, juxt/comp threading results,
;; every?/some after map, map while holding atom state (pure output),
;; iterate/take-while/drop-while composed with map/filter, anonymous fns #().
;; Values only; throws record exception class only.

(defn- p [k v]
  (println (str k "\t" v)))

(defmacro probe [k & body]
  `(let [k# ~k]
     (try
       (p k# (pr-str (do ~@body)))
       (catch Throwable t#
         (p k# (str "THREW " (.getName (class t#))))))))

(defn- mk-pred [n] #(zero? (mod % n)))
(defn- mk-xform [n] #(+ n %))

;; ---------------------------------------------------------------------------
;; Dynamic predicate / transform
;; ---------------------------------------------------------------------------

(probe "edge23/dyn-map-filter"
       (vec (filter (mk-pred 3) (map (mk-xform 10) (range 15)))))
(probe "edge23/dyn-nested"
       (vec (map (mk-xform 1)
                 (filter (mk-pred 2)
                         (map (mk-xform 2) (range 10))))))

;; ---------------------------------------------------------------------------
;; juxt of piped branches on same coll
;; ---------------------------------------------------------------------------

(probe "edge23/juxt-pipes"
       ((juxt (comp vec (partial filter even?) (partial map inc))
              (comp vec (partial filter odd?) (partial map #(* 2 %))))
        (range 8)))
(probe "edge23/juxt-sum-count"
       (let [f (juxt count (comp (partial reduce +) (partial filter pos?) (partial map inc)))]
         (f (range -2 6))))

;; ---------------------------------------------------------------------------
;; every? / some / not-every? after map
;; ---------------------------------------------------------------------------

(probe "edge23/every-after-map"
       (every? pos? (map inc (filter number? [-1 0 1 2]))))
(probe "edge23/some-after-filter"
       (some pos? (map dec [0 -1 -2 3])))
(probe "edge23/not-every-pipe"
       (not-every? nil? (map identity [1 nil 2])))

;; ---------------------------------------------------------------------------
;; Side channel in map (output still pure vector)
;; ---------------------------------------------------------------------------

(probe "edge23/map-with-atom"
       (let [seen (atom #{})]
         (vec (filter (fn [x]
                        (let [ok (not (contains? @seen x))]
                          (when ok (swap! seen conj x))
                          ok))
                      (map #(mod % 5) [1 2 2 3 3 3 4 1 5])))))

;; ---------------------------------------------------------------------------
;; iterate + take-while + map + filter
;; ---------------------------------------------------------------------------

(probe "edge23/iterate-pipe"
       (vec (take 7
                   (filter pos?
                           (map #(/ % 2)
                                (take-while #(< % 100)
                                            (iterate #(* 3 %) 1)))))))
(probe "edge23/drop-while-map"
       (vec (map inc (drop-while #(< % 5) (range 12)))))

;; ---------------------------------------------------------------------------
;; #() in map/filter chains
;; ---------------------------------------------------------------------------

(probe "edge23/anon-chain"
       (vec (->> (range 10)
                 (filter #(> % 2))
                 (map #(bit-and % 3))
                 (filter #(> % 0))
                 (map #(* % 10)))))

;; ---------------------------------------------------------------------------
;; map indexed + filter + map (reindex)
;; ---------------------------------------------------------------------------

(probe "edge23/index-reindex"
       (vec (map-indexed vector
                         (filter even?
                                 (map-indexed (fn [i x] (+ i x))
                                              [10 20 30 40 50])))))

;; ---------------------------------------------------------------------------
;; comp returning fn applied after filter
;; ---------------------------------------------------------------------------

(def ^:private normalize
  (comp (partial filter number?)
        (partial map #(max -5 (min 5 %)))
        vec))

(probe "edge23/normalize"
       (normalize (list nil 1 10 -10 3 "skip" 0)))
(probe "edge23/normalize-lazy"
       (normalize (map identity [-20 0 20 2])))

;; ---------------------------------------------------------------------------
;; map + filter on map values then rebuild (sorted)
;; ---------------------------------------------------------------------------

(probe "edge23/rebuild-map-from-vals"
       (into (sorted-map)
             (map (fn [[k v]] [k (* 2 v)])
                  (filter (fn [[_ v]] (even? v))
                          (map (fn [[k v]] [k (inc v)])
                               {:a 0 :b 1 :c 2 :d 3})))))

;; ---------------------------------------------------------------------------
;; concat of maps' entry streams through filter
;; ---------------------------------------------------------------------------

(probe "edge23/concat-entry-pipe"
       (vec (sort
             (map first
                  (filter (fn [[k _]] (not= k :z))
                          (mapcat seq [{:a 1 :z 9} {:b 2 :c 3}]))))))

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
