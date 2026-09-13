;; Wave-9 edge matrix: peek/pop on lists, cons/list*, replicate/repeatedly,
;; cycle/take, flatten, tree-seq on nil, walk pre/post, array-map vs hash-map
;; sizes, get on vectors with int/long keys, subs/split-at on strings,
;; char/Character, ratio and bigint edges, quot/mod/rem, rand-int bounds,
;; boolean array coercions, simple vary-meta on fn and collections.
;; Values only; throws record exception class only.

(require '[clojure.string :as str]
         '[clojure.walk :as walk])

(defn- p [k v]
  (println (str k "\t" v)))

(defmacro probe [k & body]
  `(let [k# ~k]
     (try
       (p k# (pr-str (do ~@body)))
       (catch Throwable t#
         (p k# (str "THREW " (.getName (class t#))))))))

;; ---------------------------------------------------------------------------
;; List peek / pop / cons
;; ---------------------------------------------------------------------------

(probe "edge9/peek/list" (peek '(1 2 3)))
(probe "edge9/peek/ev-list" (peek '()))
(probe "edge9/pop/list" (pop '(1 2 3)))
(probe "edge9/cons/one" (cons 0 [1 2]))
(probe "edge9/list*/nil" (list* nil))
(probe "edge9/list*/spread" (apply list* [1 2] [3]))

;; ---------------------------------------------------------------------------
;; replicate / repeatedly / cycle
;; ---------------------------------------------------------------------------

(probe "edge9/replicate/0" (replicate 0 :x))
(probe "edge9/replicate/3" (vec (replicate 3 0)))
(probe "edge9/repeatedly/0" (vec (repeatedly 0 #(inc 0))))
(probe "edge9/repeatedly/2" (vec (repeatedly 2 #(inc 0))))
(probe "edge9/cycle/take5" (vec (take 5 (cycle [1 2]))))

;; ---------------------------------------------------------------------------
;; flatten / tree-seq
;; ---------------------------------------------------------------------------

(probe "edge9/flatten/nil" (flatten nil))
(probe "edge9/flatten/nested" (flatten [[1] 2 [[3]]]))
(probe "edge9/tree-seq/nil" (tree-seq coll? seq nil))
(probe "edge9/tree-seq/map" (count (tree-seq map? seq {:a {:b 1}})))

;; ---------------------------------------------------------------------------
;; walk on maps / vectors
;; ---------------------------------------------------------------------------

(probe "edge9/walk/identity-map" (walk/walk identity identity {:a 1}))
(probe "edge9/walk/post-inc" (walk/postwalk #(if (number? %) (inc %) %) {:a 1 :b [2]}))
(probe "edge9/walk/prewalk-keys" (walk/prewalk keyword {:a 1}))

;; ---------------------------------------------------------------------------
;; Array map size boundaries (8 -> 9)
;; ---------------------------------------------------------------------------

(probe "edge9/map/count-7" (count (hash-map :a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7)))
(probe "edge9/map/count-8" (count {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8}))
(probe "edge9/map/count-9" (count {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8 :i 9}))
(probe "edge9/get/map8-a" (get {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8} :a))

;; ---------------------------------------------------------------------------
;; Vector get with int keys
;; ---------------------------------------------------------------------------

(probe "edge9/get/vec-0" (get [10 20] 0))
(probe "edge9/get/vec-miss" (get [10 20] 5 :nf))
(probe "edge9/nth/list" (nth '(1 2 3) 1))

;; ---------------------------------------------------------------------------
;; String edges
;; ---------------------------------------------------------------------------

(probe "edge9/subs/empty" (subs "" 0 0))
(probe "edge9/split-at/str" (mapv str (first (split-at 2 "abcd"))))
(probe "edge9/str/blank" (str/blank? ""))
(probe "edge9/str/blank-sp" (str/blank? "  "))

;; ---------------------------------------------------------------------------
;; char / Character
;; ---------------------------------------------------------------------------

(probe "edge9/char/escape" (char 65))
(probe "edge9/Character/isLetter" (Character/isLetter \a))

;; ---------------------------------------------------------------------------
;; Ratio / bigint / quot
;; ---------------------------------------------------------------------------

(probe "edge9/ratio" (/ 1 3))
(probe "edge9/rational?" (rational? (/ 1 3)))
(probe "edge9/quot" (quot 7 3))
(probe "edge9/mod" (mod 7 3))
(probe "edge9/rem" (rem 7 3))
(probe "edge9/bigint+" (+ 1N 2N))

;; ---------------------------------------------------------------------------
;; rand-int (determinism not required — range only)
;; ---------------------------------------------------------------------------

(probe "edge9/rand-int/1" (<= 0 (rand-int 1) 0))

;; ---------------------------------------------------------------------------
;; vary-meta
;; ---------------------------------------------------------------------------

(probe "edge9/vary-meta/vec"
       (meta (vary-meta [1] assoc :tag :t)))
(probe "edge9/vary-meta/fn"
       (:private (meta (vary-meta (fn [x] x) assoc :private true))))

;; ---------------------------------------------------------------------------
;; boolean / zero / pos / neg predicates
;; ---------------------------------------------------------------------------

(probe "edge9/zero?/0" (zero? 0))
(probe "edge9/pos?/0" (pos? 0))
(probe "edge9/neg?/0" (neg? 0))
(probe "edge9/true?/nil" (true? nil))
(probe "edge9/false?/false" (false? false))

;; ---------------------------------------------------------------------------
;; map / filter on sets
;; ---------------------------------------------------------------------------

(probe "edge9/map/set" (set (map inc #{1 2})))
(probe "edge9/filter/set" (set (filter pos? #{-1 1 2})))

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
