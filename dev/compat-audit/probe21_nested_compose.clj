;; Deeply nested map/filter/mapcat/transducer composition, partial/comp pipelines,
;; multi-xf stacks, frequencies/group-by after transforms, bounded pmap.
;; Values only; throws record exception class only.

(defn- p [k v]
  (println (str k "\t" v)))

(defmacro probe [k & body]
  `(let [k# ~k]
     (try
       (p k# (pr-str (do ~@body)))
       (catch Throwable t#
         (p k# (str "THREW " (.getName (class t#))))))))

(defn- sn [pair] (nth pair 0))
(defn- sv [pair] (nth pair 1))

(defmacro ^:private for-pair [[sym pairs] & body]
  `(doseq [pair# ~pairs]
     (let [~'sn (sn pair#)
           ~sym (sv pair#)]
       ~@body)))

(def ^:private deep-src
  [["range" (range -3 25)]
   ["cycle-bounded" (take 30 (cycle [1 -1 0 2 3]))]
   ["nested-vec" [[1 2] [3] nil [4 5] [-1]]]
   ["m17" (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 17)))]])

;; ---------------------------------------------------------------------------
;; Five-level lazy nest: mapcat -> map -> filter -> map -> take
;; ---------------------------------------------------------------------------

(for-pair [src deep-src]
  (probe (str "edge21/deep-lazy/" sn)
         (vec (take 12
                     (map #(* 2 %)
                          (filter pos?
                                  (map inc (mapcat identity src))))))))

;; ---------------------------------------------------------------------------
;; Stacked transducers (two comp layers)
;; ---------------------------------------------------------------------------

(defn- xf-mod-odd-inc []
  (comp (map #(mod % 10))
        (filter odd?)
        (map inc)))

(defn- xf-pos-triple-take [n]
  (comp (filter pos?)
        (map #(* 3 %))
        (take n)))

(for-pair [src deep-src]
  (probe (str "edge21/xf-stack/" sn)
         (vec (into [] (comp (xf-mod-odd-inc) (xf-pos-triple-take 8)) src))))

;; ---------------------------------------------------------------------------
;; comp of partials (same semantics as threaded pipeline)
;; ---------------------------------------------------------------------------

(def ^:private pipe-fn
  (comp (partial map inc)
        (partial filter even?)
        (partial map #(/ % 2))
        (partial vec)))

(for-pair [src [["v" [1 2 3 4 5 6]] ["nil" nil] ["ev" []]]]
  (probe (str "edge21/comp-partial/" sn)
         (pipe-fn src)))

(probe "edge21/comp-vs-thread"
       (= (pipe-fn [2 3 4 5])
          (->> [2 3 4 5] (map inc) (filter even?) (map #(/ % 2)) vec)))

;; ---------------------------------------------------------------------------
;; frequencies / group-by after map+filter chains
;; ---------------------------------------------------------------------------

(probe "edge21/freq-pipe"
       (frequencies
        (filter pos?
                (map #(mod % 5)
                     (mapcat vector [0 1 2 3 4 5 6 7 8 9 10])))))

(probe "edge21/group-by-pipe"
       (into (sorted-map)
             (map (fn [[k v]] [k (vec (sort v))])
                  (group-by even?
                            (filter number?
                                    (map inc [-2 -1 0 1 2 3 4 5]))))))

;; ---------------------------------------------------------------------------
;; Build sorted map from dual filtered streams (keys / vals)
;; ---------------------------------------------------------------------------

(probe "edge21/dual-stream-into-map"
       (let [ks (filter keyword? (map keyword ["a" "b" "c" "skip" "d"]))
             vs (filter pos? (map inc [0 1 -1 2 3 4]))]
         (into (sorted-map) (map vector ks vs))))

;; ---------------------------------------------------------------------------
;; merge-with on maps produced by map/filter pipelines
;; ---------------------------------------------------------------------------

(probe "edge21/merge-maps-from-pipes"
       (let [m1 (into {} (map (fn [x] [x x])
                              (filter odd? (map inc (range 6)))))
             m2 (into {} (map (fn [x] [x (* 2 x)])
                              (filter even? (map inc (range 6)))))]
         (merge-with + m1 m2)))

;; ---------------------------------------------------------------------------
;; transduce + completing + reduced early in mapped stream
;; ---------------------------------------------------------------------------

(probe "edge21/transduce-reduced"
       (transduce (comp (map inc) (filter even?) (map #(* % %)))
                  (completing (fn [acc x] (if (> acc 50) (reduced acc) (+ acc x)))
                              0)
                  (range 15)))

;; ---------------------------------------------------------------------------
;; cat / append of two independent pipelines
;; ---------------------------------------------------------------------------

(probe "edge21/cat-two-pipes"
       (vec (concat (filter even? (map inc (range 5)))
                    (filter odd? (map #(* 2 %) (range 5))))))

(probe "edge21/into-cat-xf"
       (into []
             (comp cat (map inc) (filter pos?))
             [(filter pos? (range -2 4))
              (map #(* -1 %) (range 4))]))

;; ---------------------------------------------------------------------------
;; pmap (small coll, bounded)
;; ---------------------------------------------------------------------------

(probe "edge21/pmap-filter-map"
       (vec (take 4
                    (filter pos?
                            (map inc (pmap identity [0 1 2 3 4 5]))))))

;; ---------------------------------------------------------------------------
;; map + filter on 17-key map (array-map -> hash transition) by value
;; ---------------------------------------------------------------------------

(probe "edge21/m17-val-filter-map"
       (into (sorted-map)
             (map (fn [[k v]] [k (inc v)])
                  (filter (fn [[_ v]] (even? v))
                          (sv (first (filter #(= "m17" (sn %)) deep-src)))))))

;; ---------------------------------------------------------------------------
;; replace + map + filter (transducer)
;; ---------------------------------------------------------------------------

(probe "edge21/replace-xf-pipe"
       (vec (into []
                  (comp (map #(if (neg? %) 0 %))
                        (filter pos?)
                        (map inc)
                        (replace {1 100 2 200}))
                  [-1 0 1 2 3 1])))

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
