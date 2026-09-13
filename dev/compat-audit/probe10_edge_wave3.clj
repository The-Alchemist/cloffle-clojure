;; Wave-3 edge matrix: arrays/host, set ops, range/repeat/cycle, sort/compare/hash,
;; meta, subvec/rseq, string blank?, NaN/Inf, map-indexed/partition, bit/unchecked.
;; Values only; throws record exception class only (concrete type names intentional).
;; Same key<TAB>value contract as the other audit probes.

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
   ["el" ()]
   ["em" {}]
   ["eset" #{}]
   ["f" false]
   ["t" true]
   ["estr" ""]
   ["z" 0]
   ["nan" ##NaN]
   ["inf" ##Inf]
   ["ninf" ##-Inf]
   ["sp" \space]
   ["v1" [1]]
   ["m1" {:a 1}]])

(defn- sn [pair] (nth pair 0))
(defn- sv [pair] (nth pair 1))

(defmacro ^:private for-subjects [[sym] & body]
  `(doseq [pair# subjects]
     (let [~'sn (sn pair#)
           ~sym (sv pair#)]
       ~@body)))

;; ---------------------------------------------------------------------------
;; Arrays / host
;; ---------------------------------------------------------------------------

(probe "edge3/into-array/ev" (seq (into-array [])))
(probe "edge3/into-array/nil" (seq (into-array nil)))
(probe "edge3/into-array/el" (seq (into-array ())))
(probe "edge3/to-array/nil" (seq (to-array nil)))
(probe "edge3/to-array/ev" (seq (to-array [])))
(probe "edge3/object-array/0" (alength (object-array 0)))
(probe "edge3/int-array/0" (alength (int-array 0)))
(probe "edge3/aclone/empty" (alength (aclone (object-array 0))))
(probe "edge3/aget/empty" (aget (object-array 0) 0))
(probe "edge3/aset/empty" (aset (object-array 0) 0 :x))
(probe "edge3/make-array/0" (alength (make-array Object 0)))
(probe "edge3/into-array/String-ev" (seq (into-array String [])))

;; ---------------------------------------------------------------------------
;; Set ops (clojure.set)
;; ---------------------------------------------------------------------------

(require 'clojure.set)
(probe "edge3/set/union-empty" (clojure.set/union #{} #{}))
(probe "edge3/set/union-nil" (clojure.set/union nil nil))
(probe "edge3/set/intersection-empty" (clojure.set/intersection #{} #{1}))
(probe "edge3/set/difference-empty" (clojure.set/difference #{} #{1}))
(probe "edge3/set/subset?-empty" (clojure.set/subset? #{} #{1}))
(probe "edge3/set/superset?-empty" (clojure.set/superset? #{1} #{}))
(probe "edge3/set/select-empty" (clojure.set/select even? #{}))
(probe "edge3/set/project-empty" (clojure.set/project #{} [:a]))
(probe "edge3/set/rename-empty" (clojure.set/rename #{} {:a :b}))
(probe "edge3/set/index-empty" (clojure.set/index #{} [:a]))
(probe "edge3/set/join-empty" (clojure.set/join #{} #{}))

;; ---------------------------------------------------------------------------
;; range / repeat / cycle / iterate / repeatedly
;; ---------------------------------------------------------------------------

(probe "edge3/range/0" (doall (range 0)))
(probe "edge3/range/0-0" (doall (range 0 0)))
(probe "edge3/repeat/0" (doall (repeat 0 :x)))
(probe "edge3/cycle/take0" (doall (take 0 (cycle [1]))))
(probe "edge3/cycle/empty-take1" (doall (take 1 (cycle []))))
(probe "edge3/iterate/take0" (doall (take 0 (iterate inc 0))))
(probe "edge3/repeatedly/0" (doall (repeatedly 0 (constantly 1))))
(probe "edge3/repeat/take0-inf" (doall (take 0 (repeat :x))))

;; ---------------------------------------------------------------------------
;; sort / compare / hash / meta
;; ---------------------------------------------------------------------------

(for-subjects [x]
  (probe (str "edge3/sort/" sn) (sort x))
  (probe (str "edge3/sort-by/" sn) (sort-by identity x))
  (probe (str "edge3/hash/" sn) (hash x))
  (probe (str "edge3/meta/" sn) (meta x))
  (probe (str "edge3/with-meta/" sn) (meta (with-meta (if (instance? clojure.lang.IObj x) x []) {:m 1}))))

(probe "edge3/compare/nil-nil" (compare nil nil))
(probe "edge3/compare/ev-ev" (compare [] []))
(probe "edge3/compare/z-nil" (compare 0 nil))
(probe "edge3/compare/nil-z" (compare nil 0))
(probe "edge3/compare/nan-nan" (compare ##NaN ##NaN))
(probe "edge3/hash/nil" (hash nil))
(probe "edge3/hash/estr" (hash ""))

;; ---------------------------------------------------------------------------
;; subvec / rseq / vec / list* / vector-of
;; ---------------------------------------------------------------------------

(probe "edge3/subvec/empty" (subvec [] 0))
(probe "edge3/subvec/oob" (subvec [1] 0 2))
(probe "edge3/subvec/neg" (subvec [1] -1))
(probe "edge3/rseq/ev" (rseq []))
(probe "edge3/rseq/v1" (vec (rseq [1])))
(probe "edge3/rseq/em" (rseq {}))
(probe "edge3/vec/nil" (vec nil))
(probe "edge3/vec/el" (vec ()))
(probe "edge3/list*/nil" (list* nil))
(probe "edge3/list*/ev" (list* []))
(probe "edge3/vector-of/long-empty" (vector-of :long))
(probe "edge3/vector-of/long-nil-arg" (vector-of :long nil))

;; ---------------------------------------------------------------------------
;; string / blank? / name edges
;; ---------------------------------------------------------------------------

(require 'clojure.string)
(probe "edge3/str/blank?-nil" (clojure.string/blank? nil))
(probe "edge3/str/blank?-estr" (clojure.string/blank? ""))
(probe "edge3/str/blank?-sp" (clojure.string/blank? " "))
(probe "edge3/str/trim-nil" (clojure.string/trim nil))
(probe "edge3/str/join-empty" (clojure.string/join "," []))
(probe "edge3/str/join-nil" (clojure.string/join "," nil))
(probe "edge3/str/split-nil" (clojure.string/split nil #","))
(probe "edge3/str/includes?-nil" (clojure.string/includes? nil "a"))
(probe "edge3/str/replace-nil" (clojure.string/replace nil "a" "b"))
(probe "edge3/str/escape-nil" (clojure.string/escape nil {\a "A"}))

;; ---------------------------------------------------------------------------
;; partition / group-by / frequencies / replacements
;; ---------------------------------------------------------------------------

(for-subjects [x]
  (probe (str "edge3/partition2/" sn) (doall (partition 2 x)))
  (probe (str "edge3/partition-all2/" sn) (doall (partition-all 2 x)))
  (probe (str "edge3/group-by/" sn) (group-by identity x))
  (probe (str "edge3/frequencies/" sn) (frequencies x))
  (probe (str "edge3/map-indexed/" sn) (doall (map-indexed vector x)))
  (probe (str "edge3/keep-indexed/" sn) (doall (keep-indexed (fn [i _] i) x)))
  (probe (str "edge3/replace/" sn) (replace {:a :b} x)))

(probe "edge3/partition/pad" (doall (partition 2 2 [:pad] [1])))
(probe "edge3/split-at/0-ev" (mapv vec (split-at 0 [])))
(probe "edge3/split-with/ev" (mapv vec (split-with identity [])))

;; ---------------------------------------------------------------------------
;; reductions without init / max-key / min-key
;; ---------------------------------------------------------------------------

(probe "edge3/reductions/no-init-ev" (doall (reductions + [])))
(probe "edge3/reductions/no-init-nil" (doall (reductions + nil)))
(probe "edge3/reductions/no-init-v1" (doall (reductions + [1])))
(probe "edge3/max-key/empty" (apply max-key identity []))
(probe "edge3/min-key/empty" (apply min-key identity []))

;; ---------------------------------------------------------------------------
;; eduction / sequence / into xform variants
;; ---------------------------------------------------------------------------

(probe "edge3/eduction/empty" (vec (eduction (map inc) [])))
(probe "edge3/eduction/nil" (vec (eduction (map inc) nil)))
(probe "edge3/into/xform-nil" (into [] (map identity) nil))
(probe "edge3/into/xform-set" (into #{} (map identity) [1 1]))
(probe "edge3/into/set-nil" (into #{} nil))
(probe "edge3/completing/empty" (transduce (map identity) (completing conj) [] []))

;; ---------------------------------------------------------------------------
;; NaN / Inf predicates and arithmetic
;; ---------------------------------------------------------------------------

(probe "edge3/nan/=" (== ##NaN ##NaN))
(probe "edge3/nan/=" (= ##NaN ##NaN))
(probe "edge3/nan/zero?" (zero? ##NaN))
(probe "edge3/nan/pos?" (pos? ##NaN))
(probe "edge3/nan/neg?" (neg? ##NaN))
(probe "edge3/inf/pos?" (pos? ##Inf))
(probe "edge3/ninf/neg?" (neg? ##-Inf))
(probe "edge3/nan/plus-1" (+ ##NaN 1))
(probe "edge3/inf/plus-1" (+ ##Inf 1))
(probe "edge3/double?/nan" (double? ##NaN))
(probe "edge3/float?/nan" (float? ##NaN))

;; ---------------------------------------------------------------------------
;; bit / unchecked / cast nil edges
;; ---------------------------------------------------------------------------

(probe "edge3/bit-and/nil" (bit-and nil 1))
(probe "edge3/bit-or/0-0" (bit-or 0 0))
(probe "edge3/unchecked-inc/nil" (unchecked-inc nil))
(probe "edge3/int/nil" (int nil))
(probe "edge3/long/nil" (long nil))
(probe "edge3/double/nil" (double nil))
(probe "edge3/num/nil" (num nil))
(probe "edge3/bigdec/nil" (bigdec nil))
(probe "edge3/bigint/nil" (bigint nil))

;; ---------------------------------------------------------------------------
;; for / doseq / case / cond surfaces
;; ---------------------------------------------------------------------------

(probe "edge3/for/ev" (doall (for [x []] x)))
(probe "edge3/for/nil" (doall (for [x nil] x)))
(probe "edge3/doseq/ev" (doseq [x []] x) :ok)
(probe "edge3/case/nil" (case nil nil :n 0 :z :other))
(probe "edge3/case/0" (case 0 nil :n 0 :z :other))
(probe "edge3/cond/empty" (cond))
(probe "edge3/condp/nil" (condp = nil nil :n :other))

;; ---------------------------------------------------------------------------
;; juxt / comp / partial / identity / const
;; ---------------------------------------------------------------------------

(probe "edge3/juxt/nil" ((juxt identity) nil))
(probe "edge3/comp/empty" ((comp) 1))
(probe "edge3/partial/str" ((partial str "a") nil))
(probe "edge3/identity/nil" (identity nil))
(probe "edge3/constantly/nil" ((constantly nil)))

;; ---------------------------------------------------------------------------
;; sorted collections empty
;; ---------------------------------------------------------------------------

(probe "edge3/sorted-map/empty" (sorted-map))
(probe "edge3/sorted-set/empty" (sorted-set))
(probe "edge3/sorted-map-by/empty" (sorted-map-by compare))
(probe "edge3/into-sorted/nil" (into (sorted-map) nil))
(probe "edge3/array-map/empty" (array-map))
(probe "edge3/hash-map/empty" (hash-map))
(probe "edge3/hash-set/empty" (hash-set))

;; ---------------------------------------------------------------------------
;; ensure-reduced / unreduced / reduced?
;; ---------------------------------------------------------------------------

(probe "edge3/reduced?/nil" (reduced? nil))
(probe "edge3/unreduced/nil" (unreduced nil))
(probe "edge3/ensure-reduced/nil" (reduced? (ensure-reduced nil)))
(probe "edge3/deref-reduced" @(reduced :x))

;; ---------------------------------------------------------------------------
;; peek/pop on one-element (tuple ladder)
;; ---------------------------------------------------------------------------

(probe "edge3/peek/v1" (peek [1]))
(probe "edge3/pop/v1" (pop [1]))
(probe "edge3/peek/t2" (peek [1 2]))
(probe "edge3/pop/t2" (pop [1 2]))
(probe "edge3/conj/ev-nil" (conj [] nil))
(probe "edge3/assoc/ev-0" (assoc [] 0 :x))

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
