;; Wave-6 edge matrix: run!/dorun, condp, subs, refs/volatiles, queues,
;; keyword-as-fn, map-of vectors, binding, bit shifts, negatives, subseq,
;; sorted-set-by, walk on sets, transducer compose on empty.
;; Values only; throws record exception class only.

(defn- p [k v]
  (println (str k "\t" v)))

(defmacro probe [k & body]
  `(let [k# ~k]
     (try
       (p k# (pr-str (do ~@body)))
       (catch Throwable t#
         (p k# (str "THREW " (.getName (class t#))))))))

;; ---------------------------------------------------------------------------
;; run! / dorun / doall / doseq variants
;; ---------------------------------------------------------------------------

(probe "edge6/run!/nil" (run! identity nil))
(probe "edge6/run!/ev" (run! identity []))
(probe "edge6/dorun/nil" (dorun nil))
(probe "edge6/dorun/ev" (dorun []))
(probe "edge6/doall/nil" (doall nil))
(probe "edge6/doseq/let-nil"
       (let [acc (atom [])]
         (doseq [x nil :let [y x]] (swap! acc conj y))
         @acc))
(probe "edge6/doseq/when-nil"
       (let [acc (atom [])]
         (doseq [x nil :when x] (swap! acc conj x))
         @acc))

;; ---------------------------------------------------------------------------
;; condp / case on types
;; ---------------------------------------------------------------------------

(probe "edge6/condp/=nil" (condp = nil nil :hit :miss))
(probe "edge6/condp/=ev" (condp = [] [] :hit :miss))
(probe "edge6/condp/isa-string" (condp isa? String "a" :str :other))
(probe "edge6/condp/isa-nil" (condp isa? String nil :str :other))
(probe "edge6/case/nil-lit" (case nil nil :x :other))
(probe "edge6/case/keyword" (case :k :k :hit :other))

;; ---------------------------------------------------------------------------
;; subs / subs with indices
;; ---------------------------------------------------------------------------

(probe "edge6/subs/nil" (subs nil 0 1))
(probe "edge6/subs/estr" (subs "" 0))
(probe "edge6/subs/estr-oob" (subs "ab" 3))
(probe "edge6/subs/neg" (subs "abc" 1 2))
(probe "edge6/subs/full" (subs "abc" 0 3))

;; ---------------------------------------------------------------------------
;; Ref / volatile / swap-vals
;; ---------------------------------------------------------------------------

(probe "edge6/ref/deref-nil" (deref (ref nil)))
(probe "edge6/ref/set" (let [r (ref 0)] (dosync (ref-set r 1)) @r))
(probe "edge6/ref/alter" (let [r (ref 0)] (dosync (alter r inc)) @r))
(probe "edge6/volatile/swap" (let [v (volatile! nil)] (vswap! v (constantly :x)) @v))
(probe "edge6/volatile/reset" (let [v (volatile! 1)] (vreset! v nil) @v))

;; ---------------------------------------------------------------------------
;; Queues / list* / cons chains
;; ---------------------------------------------------------------------------

(probe "edge6/queue/empty-count" (count clojure.lang.PersistentQueue/EMPTY))
(probe "edge6/queue/empty-seq" (seq clojure.lang.PersistentQueue/EMPTY))
(probe "edge6/queue/conj-first" (first (conj clojure.lang.PersistentQueue/EMPTY 1)))
(probe "edge6/queue/conj-vec" (vec (conj clojure.lang.PersistentQueue/EMPTY 1 2)))
(probe "edge6/list*/nil" (list* nil))
(probe "edge6/list*/1-2" (list* 1 2))
(probe "edge6/cons/nil" (cons 1 nil))
(probe "edge6/cons/ev" (cons 1 []))

;; ---------------------------------------------------------------------------
;; Keyword as function
;; ---------------------------------------------------------------------------

(probe "edge6/keyword/nil" (:a nil))
(probe "edge6/keyword/em" (:a {}))
(probe "edge6/keyword/m1" (:a {:a 1}))
(probe "edge6/keyword/nil-nf" (:a nil :nf))
(probe "edge6/get/nil-key" (get nil :a))

;; ---------------------------------------------------------------------------
;; Map / vector literals (lowering)
;; ---------------------------------------------------------------------------

(probe "edge6/map/keyword-vec" (map :a [{:a 1} {:a 2}]))
(probe "edge6/map/vector-of-maps" (vec (map #(hash-map :i %) [])))
(probe "edge6/mapcat/vector" (doall (mapcat vector [1 2 3])))
(probe "edge6/zipmap/keys-only" (zipmap [:a :b] nil))
(probe "edge6/hash-map/nil" (hash-map nil))
(probe "edge6/array-map/nil" (array-map nil))

;; ---------------------------------------------------------------------------
;; subseq / rseq on small vectors
;; ---------------------------------------------------------------------------

(probe "edge6/subseq/ev" (vec (subseq [] 0 0)))
(probe "edge6/subseq/v3" (vec (subseq [1 2 3] 1 3)))
(probe "edge6/rseq/v3" (vec (rseq [1 2 3])))
(probe "edge6/rseq/v1" (vec (rseq [1])))

;; ---------------------------------------------------------------------------
;; Bit ops / negatives
;; ---------------------------------------------------------------------------

(probe "edge6/bit-shift-left/0" (bit-shift-left 0 1))
(probe "edge6/bit-shift-right/0" (bit-shift-right 0 1))
(probe "edge6/bit-and/-1-1" (bit-and -1 -1))
(probe "edge6/bit-not/0" (bit-not 0))
(probe "edge6/quot/-7-3" (quot -7 3))
(probe "edge6/mod/-7-3" (mod -7 3))
(probe "edge6/abs/-1" (abs -1))
(probe "edge6/max/-1-1" (max -1 1))
(probe "edge6/min/-1-1" (min -1 1))

;; ---------------------------------------------------------------------------
;; sorted-set-by / sorted-map-by nil cmp
;; ---------------------------------------------------------------------------

(probe "edge6/sorted-set-by/empty" (sorted-set-by compare))
(probe "edge6/sorted-map-by/empty" (sorted-map-by compare))
(probe "edge6/sorted-set/nil" (sorted-set nil))

;; ---------------------------------------------------------------------------
;; walk on sets / records light
;; ---------------------------------------------------------------------------

(require 'clojure.walk)
(probe "edge6/walk/set" (clojure.walk/postwalk identity #{}))
(probe "edge6/walk/keywordize-set" (clojure.walk/keywordize-keys #{}))

;; ---------------------------------------------------------------------------
;; Transducer compose empty
;; ---------------------------------------------------------------------------

(probe "edge6/comp/transduce"
       (into [] (comp (map inc) (filter even?)) []))
(probe "edge6/xform/empty"
       (into [] (map identity) (eduction (map inc) [])))

;; ---------------------------------------------------------------------------
;; binding / with-redefs light (values only)
;; ---------------------------------------------------------------------------

(probe "edge6/binding/nil-root"
       (binding [*print-length* nil] *print-length*))
(probe "edge6/with-redefs/local"
       (let [x 1] (with-redefs [identity (fn [v] (inc v))] (identity x))))

;; ---------------------------------------------------------------------------
;; Frequencies / merge on vectors of pairs
;; ---------------------------------------------------------------------------

(probe "edge6/frequencies/nil" (frequencies nil))
(probe "edge6/frequencies/ev" (frequencies []))
(probe "edge6/frequencies/v" (frequencies [1 1 2]))
(probe "edge6/into/map-from-nil" (into {} nil))

;; ---------------------------------------------------------------------------
;; Double / float / bigint edges
;; ---------------------------------------------------------------------------

(probe "edge6/decimal?/0" (decimal? 0M))
(probe "edge6/integer?/0" (integer? 0))
(probe "edge6/float?/nan" (float? ##NaN))
(probe "edge6/=/-0.0-0" (== -0.0 0.0))

;; ---------------------------------------------------------------------------
;; Lazy-seq failure / empty force
;; ---------------------------------------------------------------------------

(probe "edge6/lazy-seq/empty-force"
       (first (lazy-seq ())))
(probe "edge6/delay/throw"
       (try (deref (delay (throw (ex-info "e" {})))) (catch Exception e (ex-message e))))

;; ---------------------------------------------------------------------------
;; Print length / level on nil coll
;; ---------------------------------------------------------------------------

(probe "edge6/pr-length/nil"
       (binding [*print-length* 1] (pr-str nil)))
(probe "edge6/pr-length/nested"
       (binding [*print-length* 1] (pr-str [[1 2 3]])))

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
