;; ETL-style: rows of maps through select-keys/update/filter, pivot via
;; group-by + map, join-ish merge on overlapping keys, set algebra after
;; map/filter, large vector literal row batch, subvec+rseq in pipeline.
;; Values only; throws record exception class only.

(defn- p [k v]
  (println (str k "\t" v)))

(defmacro probe [k & body]
  `(let [k# ~k]
     (try
       (p k# (pr-str (do ~@body)))
       (catch Throwable t#
         (p k# (str "THREW " (.getName (class t#))))))))

(def ^:private rows
  [{:id 1 :n 10 :tag :a :drop? false}
   {:id 2 :n 0 :tag :b}
   nil
   {:id 3 :n -5 :tag :a :drop? true}
   {:id 4 :n 7 :tag :c}
   {:n 99}
   {:id 5 :n 3 :tag :b}])

(defn- scrub-row [m]
  (when (map? m)
    (-> m
        (select-keys [:id :n :tag])
        (update :n (fnil inc 0)))))

(defn- keep-row? [m]
  (and (map? m)
       (contains? m :id)
       (not (:drop? m))
       (pos? (:n m))))

;; ---------------------------------------------------------------------------
;; Row scrubbing pipelines
;; ---------------------------------------------------------------------------

(probe "edge22/scrub-all"
       (vec (map scrub-row rows)))
(probe "edge22/filter-keep-map-scrub"
       (vec (map scrub-row (filter keep-row? rows))))
(probe "edge22/thread-etl"
       (->> rows
            (filter map?)
            (filter keep-row?)
            (map scrub-row)
            (map #(update % :tag name))
            vec))
(probe "edge22/transduce-etl"
       (vec (transduce (comp (filter map?) (filter keep-row?) (map scrub-row))
                       conj
                       []
                       rows)))

;; ---------------------------------------------------------------------------
;; group-by tag then map/filter each group
;; ---------------------------------------------------------------------------

(probe "edge22/group-tag-sum-n"
       (into (sorted-map)
             (map (fn [[tag xs]]
                    [tag (reduce + (map :n (filter map? xs)))])
                  (group-by :tag (filter keep-row? rows)))))

(probe "edge22/group-tag-ids"
       (into (sorted-map)
             (map (fn [[tag xs]]
                    [tag (vec (sort (map :id (filter map? xs))))])
                  (group-by :tag (filter map? rows)))))

;; ---------------------------------------------------------------------------
;; index rows by id via pipeline
;; ---------------------------------------------------------------------------

(probe "edge22/index-by-id"
       (into {}
             (map (fn [m] [(:id m) (:n m)])
                  (filter keep-row? (map scrub-row rows)))))

;; ---------------------------------------------------------------------------
;; join-ish: left ids filtered mapped + right map from second pipe
;; ---------------------------------------------------------------------------

(probe "edge22/join-merge"
       (let [left (into {}
                        (map (fn [m] [(:id m) (:n m)])
                             (filter keep-row? (map scrub-row rows))))
             right (into {}
                         (map (fn [id] [id (* id 10)])
                              (filter pos? (map :id (filter map? rows)))))]
         (merge-with + left right)))

;; ---------------------------------------------------------------------------
;; Large vector batch (literal) — map/filter/count/sum
;; ---------------------------------------------------------------------------

(def ^:private batch
  (vec (for [i (range 24)]
         {:i i :v (mod i 7) :neg? (neg? i)})))

(probe "edge22/batch-count-keep"
       (count (filter #(and (contains? % :i) (not (:neg? %)))
                      (map #(update % :v inc) batch))))
(probe "edge22/batch-sum-v"
       (reduce +
               (map :v
                    (filter #(contains? % :v)
                            (map #(assoc % :v (* 2 (:v %)))
                                 (filter #(even? (:i %)) batch))))))
(probe "edge22/batch-xf-into-set"
       (into #{}
             (comp (filter map?)
                   (map :v)
                   (filter pos?)
                   (map #(* 3 %)))
             batch))

;; ---------------------------------------------------------------------------
;; Mixed colls: list + vector + lazy through one xf
;; ---------------------------------------------------------------------------

(def ^:private xf-v (comp (map inc) (filter even?) (map #(/ % 2))))

(probe "edge22/xf-on-list"
       (vec (sequence xf-v '(0 1 2 3 4 5 6))))
(probe "edge22/xf-on-lazy"
       (vec (into [] xf-v (map identity (range 8)))))
(probe "edge22/xf-on-tuple-lit"
       (vec (transduce xf-v conj [] [0 1 2 3 4 5 6 7 8])))

;; ---------------------------------------------------------------------------
;; subvec / rseq in multi-step ETL on numeric vectors
;; ---------------------------------------------------------------------------

(probe "edge22/subvec-etl"
       (let [v (vec (range 12))]
         (vec (map #(* % %)
                   (filter even?
                           (map inc (subvec v 2 10)))))))
(probe "edge22/rseq-etl"
       (vec (take 4
                   (filter pos?
                           (map dec (rseq (vec (range 1 9))))))))

;; ---------------------------------------------------------------------------
;; set pipeline after map/filter on rows
;; ---------------------------------------------------------------------------

(probe "edge22/set-of-tags"
       (set (map :tag (filter keep-row? (map scrub-row rows)))))
(probe "edge22/sorted-set-vals"
       (apply sorted-set
              (map :v
                   (filter #(and (contains? % :v) (pos? (:v %)))
                           (map #(assoc % :v (mod (:i %) 5)) batch)))))

;; ---------------------------------------------------------------------------
;; reduce-kv on map built from filtered stream
;; ---------------------------------------------------------------------------

(probe "edge22/reduce-kv-built-map"
       (let [m (into {}
                     (map (fn [r] [(:id r) (:n r)])
                          (filter keep-row? (map scrub-row rows))))]
         (reduce-kv (fn [acc k v] (+ acc k v)) 0 m)))

;; ---------------------------------------------------------------------------
;; map + filter + ex-info only as value (no throw) — count valid maps
;; ---------------------------------------------------------------------------

(probe "edge22/valid-map-count"
       (count (filter map? rows)))

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
