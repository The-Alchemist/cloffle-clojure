;; Wave-8 edge matrix: zipmap/zip, frequencies, group-by on nil/empty,
;; distinct/reverse on lazy, interleave/interpose, mapcat, keep/keep-indexed,
;; update/update-in, merge-with on literals, every?/not-any?, some->/some->>,
;; cond->/cond->>, as->, juxt/comp on empty, iterate/take-while/drop-while,
;; splitv-at, partitionv, mapv/filterv/remove, empty? on colls, coll?/seq?/list?.
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
  [["nil" nil] ["em" {}] ["m2" {:a 1 :b 2}]])
(def ^:private vecs
  [["nil" nil] ["ev" []] ["v3" [1 2 3]]])
(def ^:private sets
  [["nil" nil] ["es" #{}] ["s2" #{1 2}]])

(defn- sn [pair] (nth pair 0))
(defn- sv [pair] (nth pair 1))

(defmacro ^:private for-pairs [[sym pairs] & body]
  `(doseq [pair# ~pairs]
     (let [~'sn (sn pair#)
           ~sym (sv pair#)]
       ~@body)))

;; ---------------------------------------------------------------------------
;; zipmap / zip / frequencies
;; ---------------------------------------------------------------------------

(probe "edge8/zipmap/nil-keys" (zipmap nil [1 2]))
(probe "edge8/zipmap/em-keys" (zipmap [] [1]))
(probe "edge8/zipmap/normal" (zipmap [:a :b] [1 2]))
(probe "edge8/map/vector" (mapv vector [:a :b] [1 2]))
(probe "edge8/frequencies/nil" (frequencies nil))
(probe "edge8/frequencies/ev" (frequencies []))
(probe "edge8/frequencies/v" (frequencies [1 1 2]))

;; ---------------------------------------------------------------------------
;; group-by on nil / empty / false keys
;; ---------------------------------------------------------------------------

(probe "edge8/group-by/nil" (group-by identity nil))
(probe "edge8/group-by/ev" (group-by identity []))
(probe "edge8/group-by/false-key" (group-by (constantly false) [1 2]))
(probe "edge8/group-by/keyword" (group-by keyword ["a" "b"]))

;; ---------------------------------------------------------------------------
;; distinct / reverse lazy
;; ---------------------------------------------------------------------------

(probe "edge8/distinct/nil" (distinct nil))
(probe "edge8/distinct/lazy" (vec (distinct (map identity [1 1 2]))))
(probe "edge8/reverse/nil" (reverse nil))
(probe "edge8/reverse/lazy" (vec (reverse (map identity [1 2 3]))))

;; ---------------------------------------------------------------------------
;; interleave / interpose / mapcat
;; ---------------------------------------------------------------------------

(probe "edge8/interleave/nil" (doall (interleave nil [1])))
(probe "edge8/interleave/ev" (doall (interleave [] [])))
(probe "edge8/interpose/nil" (doall (interpose 0 nil)))
(probe "edge8/interpose/v" (vec (interpose 0 [1 2])))
(probe "edge8/mapcat/nil" (mapcat identity nil))
(probe "edge8/mapcat/ev" (mapcat identity []))
(probe "edge8/mapcat/nest" (vec (mapcat vector [1 2] [3 4])))

;; ---------------------------------------------------------------------------
;; keep / keep-indexed
;; ---------------------------------------------------------------------------

(probe "edge8/keep/nil" (keep identity nil))
(probe "edge8/keep/false" (keep identity [false nil 1]))
(probe "edge8/keep-indexed/nil" (keep-indexed (fn [_ x] x) nil))
(probe "edge8/keep-indexed/ev" (vec (keep-indexed (fn [i _] i) [])))

;; ---------------------------------------------------------------------------
;; update / update-in on literals
;; ---------------------------------------------------------------------------

(for-pairs [m maps]
  (probe (str "edge8/update-in/" sn)
         (update-in m [:missing] (fnil inc 0)))
  (probe (str "edge8/update/" sn)
         (update m :missing (fnil inc 0))))

(probe "edge8/update-in/nested" (update-in {:a {:b 1}} [:a :b] inc))
(probe "edge8/get-in/miss" (get-in {:a 1} [:z :y] :nf))

;; ---------------------------------------------------------------------------
;; every? / not-any? / some
;; ---------------------------------------------------------------------------

(probe "edge8/every?/nil" (every? identity nil))
(probe "edge8/every?/ev" (every? identity []))
(probe "edge8/not-any?/nil" (not-any? identity nil))
(probe "edge8/some/nil" (some identity nil))
(probe "edge8/some/false" (some identity [false nil 1]))

;; ---------------------------------------------------------------------------
;; Threading macros (value paths)
;; ---------------------------------------------------------------------------

(probe "edge8/some->/nil" (some-> nil inc))
(probe "edge8/some->/v" (some-> 1 inc inc))
(probe "edge8/some->>/nil" (some->> nil (+ 1)))
(probe "edge8/cond->/nil" (cond-> nil true inc))
(probe "edge8/cond->>/v" (cond->> 1 true (+ 1) true (* 2)))
(probe "edge8/as->/bind" (as-> 1 x (+ x x)))

;; ---------------------------------------------------------------------------
;; juxt / comp empty arity
;; ---------------------------------------------------------------------------

(probe "edge8/juxt/ev" ((juxt) 1))
(probe "edge8/juxt/inc-str" ((juxt inc str) 1))
(probe "edge8/comp/identity" ((comp) 1))
(probe "edge8/comp/inc" ((comp inc inc) 1))

;; ---------------------------------------------------------------------------
;; iterate / take-while / drop-while
;; ---------------------------------------------------------------------------

(probe "edge8/iterate/take3" (vec (take 3 (iterate inc 0))))
(probe "edge8/take-while/nil" (take-while identity nil))
(probe "edge8/drop-while/nil" (drop-while identity nil))
(probe "edge8/take-while/ev" (vec (take-while pos? [1 2 0 3])))

;; ---------------------------------------------------------------------------
;; splitv-at / partitionv (1.12)
;; ---------------------------------------------------------------------------

(probe "edge8/splitv-at/0" (mapv vec (splitv-at 0 [1 2])))
(probe "edge8/splitv-at/all" (mapv vec (splitv-at 3 [1 2 3])))
(probe "edge8/partitionv/2" (vec (partitionv 2 [1 2 3 4 5])))
(probe "edge8/partitionv/1-nil" (doall (partitionv 1 nil)))

;; ---------------------------------------------------------------------------
;; mapv / filterv / remove
;; ---------------------------------------------------------------------------

(for-pairs [v vecs]
  (probe (str "edge8/mapv/" sn) (mapv inc v))
  (probe (str "edge8/filterv/" sn) (filterv pos? v))
  (probe (str "edge8/remove/" sn) (remove neg? v)))

;; ---------------------------------------------------------------------------
;; Predicates on collections
;; ---------------------------------------------------------------------------

(for-pairs [v vecs]
  (probe (str "edge8/empty?/" sn) (empty? v))
  (probe (str "edge8/coll?/" sn) (coll? v))
  (probe (str "edge8/seq?/" sn) (seq? v))
  (probe (str "edge8/vector?/" sn) (vector? v)))

(for-pairs [s sets]
  (probe (str "edge8/set?/" sn) (set? s))
  (probe (str "edge8/empty?-set/" sn) (empty? s)))

;; ---------------------------------------------------------------------------
;; merge-with on shape literals
;; ---------------------------------------------------------------------------

(probe "edge8/merge-with/shape" (merge-with + {:a 1 :b 2} {:a 10}))
(probe "edge8/merge-with/nil" (merge-with + nil {:a 1}))

;; ---------------------------------------------------------------------------
;; reductions / reduced
;; ---------------------------------------------------------------------------

(probe "edge8/reductions/range" (vec (reductions + (range 4))))
(probe "edge8/reduce/reduced" (reduce (fn [a x] (if (> x 2) (reduced a) (+ a x))) 0 [1 2 3 4]))

;; ---------------------------------------------------------------------------
;; sort-by / sort on empty
;; ---------------------------------------------------------------------------

(probe "edge8/sort/nil" (sort nil))
(probe "edge8/sort-by/nil" (sort-by identity nil))
(probe "edge8/sort-by/kw" (vec (sort-by name [{:n "b"} {:n "a"}])))

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
