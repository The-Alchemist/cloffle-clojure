;; Wave-5 edge matrix: reduce-kv, merge-with, select-keys, partition-by,
;; delay/promise, meta, small literal lowering (get/assoc/dissoc/conj),
;; compare on collections, every-pred/some-fn, mapv on nil, reductions on maps.
;; Values only; throws record exception class only.

(defn- p [k v]
  (println (str k "\t" v)))

(defmacro probe [k & body]
  `(let [k# ~k]
     (try
       (p k# (pr-str (do ~@body)))
       (catch Throwable t#
         (p k# (str "THREW " (.getName (class t#))))))))

(def ^:private maps
  [["nil" nil]
   ["em" {}]
   ["m1" {:a 1}]
   ["m2" {:a 1 :b 2}]
   ["m9" {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8 :i 9}]])

(def ^:private vecs
  [["nil" nil]
   ["ev" []]
   ["v1" [1]]
   ["v2" [1 2]]
   ["v3" [1 2 3]]
   ["v8" [1 2 3 4 5 6 7 8]]
   ["v9" [1 2 3 4 5 6 7 8 9]]])

(defn- sn [pair] (nth pair 0))
(defn- sv [pair] (nth pair 1))

(defmacro ^:private for-pairs [[sym pairs] & body]
  `(doseq [pair# ~pairs]
     (let [~'sn (sn pair#)
           ~sym (sv pair#)]
       ~@body)))

;; ---------------------------------------------------------------------------
;; reduce-kv / merge-with / select-keys / update-keys
;; ---------------------------------------------------------------------------

(for-pairs [m maps]
  (probe (str "edge5/reduce-kv/" sn) (reduce-kv (fn [acc _ _] (inc acc)) 0 m))
  (probe (str "edge5/select-keys/" sn) (select-keys m [:a :missing]))
  (probe (str "edge5/keys/" sn) (vec (sort (keys m))))
  (probe (str "edge5/vals/" sn)
         (vec (sort (vals m)))))

(probe "edge5/merge-with/nil" (merge-with + nil {:a 1}))
(probe "edge5/merge-with/em" (merge-with + {} {:a 1}))
(probe "edge5/merge-with/both" (merge-with + {:a 1} {:a 2 :b 3}))
(probe "edge5/merge-with/empty-fn" (merge-with (fn [_ _] 0) {} {}))
(probe "edge5/update-keys/nil" (update-keys nil keyword))
(probe "edge5/update-keys/em" (update-keys {} keyword))
(probe "edge5/update-vals/em" (update-vals {} (constantly 0)))

;; ---------------------------------------------------------------------------
;; Keyword get / dissoc / assoc on literals (lowering hotspots)
;; ---------------------------------------------------------------------------

(for-pairs [v vecs]
  (probe (str "edge5/get-a/" sn) (get v :a))
  (probe (str "edge5/get-a-nf/" sn) (get v :a :nf))
  (probe (str "edge5/nth0/" sn) (nth v 0 :nf))
  (probe (str "edge5/subvec-all/" sn)
         (if (vector? v)
           (subvec v 0 (count v))
           nil)))

(probe "edge5/get/empty-map-a" (get {} :a))
(probe "edge5/get/map-a" (get {:a 1} :a))
(probe "edge5/get/map-a-nf" (get {:a 1} :a :nf))
(probe "edge5/dissoc/m1-a" (dissoc {:a 1 :b 2} :a))
(probe "edge5/dissoc/m1-missing" (dissoc {:a 1} :missing))
(probe "edge5/dissoc/nil-a" (dissoc nil :a))
(probe "edge5/assoc/nil-a" (assoc nil :a 1))
(probe "edge5/assoc/em-a" (assoc {} :a 1))
(probe "edge5/assoc/m1-c" (assoc {:a 1} :c 3))
(probe "edge5/assoc/vec-0" (assoc [1 2] 0 :x))
(probe "edge5/conj/v2-3" (conj [1 2] 3))
(probe "edge5/conj/nil-1" (conj nil 1))
(probe "edge5/conj/em-kv" (conj {} [:a 1]))

;; ---------------------------------------------------------------------------
;; partition-by / group-by on small vectors
;; ---------------------------------------------------------------------------

(for-pairs [v vecs]
  (probe (str "edge5/partition-by/mod2/" sn)
         (doall (partition-by #(mod % 2) v)))
  (probe (str "edge5/group-by/mod2/" sn)
         (group-by #(mod % 2) v)))

(probe "edge5/partition-by/nil" (doall (partition-by identity nil)))
(probe "edge5/group-by/nil" (group-by identity nil))

;; ---------------------------------------------------------------------------
;; Delay / promise / realized?
;; ---------------------------------------------------------------------------

(probe "edge5/delay/deref-nil" (deref (delay nil)))
(probe "edge5/delay/realized-before" (realized? (delay 1)))
(probe "edge5/delay/realized-after" (let [d (delay 1)] (deref d) (realized? d)))
(probe "edge5/promise/deliver-nil"
       (let [p (promise)]
         (deliver p nil)
         @p))
(probe "edge5/promise/realized" (realized? (promise)))

;; ---------------------------------------------------------------------------
;; Meta
;; ---------------------------------------------------------------------------

(probe "edge5/meta/ev" (meta []))
(probe "edge5/meta/with-meta-ev" (meta (with-meta [] {:x 1})))
(probe "edge5/vary-meta/ev" (meta (vary-meta [] assoc :x 1)))
(probe "edge5/alter-meta!/ev"
       (let [v (with-meta [] {})]
         (alter-meta! v assoc :a 1)
         (meta v)))

;; ---------------------------------------------------------------------------
;; every-pred / some-fn / complement
;; ---------------------------------------------------------------------------

(probe "edge5/every-pred/nil" ((every-pred nil?) nil))
(probe "edge5/every-pred/ev" ((every-pred number?) []))
(probe "edge5/some-fn/nil" ((some-fn nil? identity) nil))
(probe "edge5/some-fn/1" ((some-fn nil? identity) 1))
(probe "edge5/complement/nil-on-nil" ((complement nil?) nil))
(probe "edge5/complement/nil-on-1" ((complement nil?) 1))
(probe "edge5/constantly-nil" ((constantly nil) 1 2))

;; ---------------------------------------------------------------------------
;; Compare / equiv on empties
;; ---------------------------------------------------------------------------

(probe "edge5/=/nil-nil" (= nil nil))
(probe "edge5/=/ev-ev" (= [] []))
(probe "edge5/=/em-em" (= {} {}))
(probe "edge5/=/ev-el" (= [] ()))
(probe "edge5/=/m1-copy" (= {:a 1} {:a 1}))
(probe "edge5/=/v2-copy" (= [1 2] [1 2]))
(probe "edge5/not=/nil-f" (not= nil false))
(probe "edge5/identical?/nil-nil" (identical? nil nil))
(probe "edge5/identical?/ev-ev" (identical? [] []))

;; ---------------------------------------------------------------------------
;; Transducers / completing / mapcat on empties
;; ---------------------------------------------------------------------------

(probe "edge5/transduce/mapcat" (transduce (mapcat list) conj [] [1 [2 3]]))
(probe "edge5/transduce/empty" (transduce (map inc) conj [] []))
(probe "edge5/transduce/nil-coll" (transduce (map inc) conj [] nil))
(probe "edge5/into/transduce-nil" (into [] (map identity) nil))
(probe "edge5/eduction/count" (count (eduction (map identity) [])))

;; ---------------------------------------------------------------------------
;; get-in / assoc-in / update-in / dissoc-in
;; ---------------------------------------------------------------------------

(probe "edge5/get-in/nil" (get-in nil [:a]))
(probe "edge5/get-in/em" (get-in {} [:a]))
(probe "edge5/get-in/nested" (get-in {:a {:b 1}} [:a :b]))
(probe "edge5/get-in/miss" (get-in {:a 1} [:a :b] :nf))
(probe "edge5/assoc-in/nil" (assoc-in nil [:a] 1))
(probe "edge5/assoc-in/em" (assoc-in {} [:a :b] 1))
(probe "edge5/update-in/em" (update-in {} [:a] (fnil inc 0)))
(probe "edge5/update-in/dissoc-b"
       (update-in {:a {:b 1 :c 2}} [:a] dissoc :b))

;; ---------------------------------------------------------------------------
;; Lazy cat / concat nested
;; ---------------------------------------------------------------------------

(probe "edge5/lazy-cat/empty" (doall (lazy-cat () ())))
(probe "edge5/lazy-cat/nil-seq" (doall (lazy-cat nil)))
(probe "edge5/concat/nested" (doall (concat [1] [2] nil [3])))
(probe "edge5/mapcat/identity-nil" (doall (mapcat identity [nil [1] nil])))

;; ---------------------------------------------------------------------------
;; Small-vector literal seq / first / rest (tuple ladder)
;; ---------------------------------------------------------------------------

(doseq [n [2 3 4 5 6 7 8]]
  (probe (str "edge5/lit-v" n "/count") (count (vec (range n))))
  (probe (str "edge5/lit-v" n "/first") (first (vec (range n))))
  (probe (str "edge5/lit-v" n "/rest-count") (count (rest (vec (range n))))))

;; ---------------------------------------------------------------------------
;; Numbers lowered edges
;; ---------------------------------------------------------------------------

(probe "edge5/+0" (+))
(probe "edge5/*0" (*))
(probe "edge5/+-nil" (+ nil))
(probe "edge5/*-nil" (* nil))
(probe "edge5/-nil" (- nil))
(probe "edge5/dec-nil" (dec nil))
(probe "edge5/<-nil-1" (< nil 1))
(probe "edge5/=-nil-nil" (= nil nil))
(probe "edge5/zero?/0" (zero? 0))
(probe "edge5/pos?/0" (pos? 0))
(probe "edge5/neg?/0" (neg? 0))
(probe "edge5/even?/0" (even? 0))
(probe "edge5/odd?/0" (odd? 0))

;; ---------------------------------------------------------------------------
;; Print / pr on empties
;; ---------------------------------------------------------------------------

(probe "edge5/pr/nil" (pr-str nil))
(probe "edge5/pr/ev" (pr-str []))
(probe "edge5/pr/em" (pr-str {}))
(probe "edge5/print/nil" (with-out-str (print nil)))
(probe "edge5/println/nil" (with-out-str (println nil)))

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
