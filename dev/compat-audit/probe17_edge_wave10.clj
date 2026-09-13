;; Wave-10: type predicates, class/of collections, count/empty on strings,
;; array constructors, into-array, vec on iterators, sorted-map-by, array-set,
;; disj on sets, union/intersection/difference, clojure.set ops, max/min key,
;; nthnext/nthrest, butlast, lazy-cat, force on delay, realized? on delay,
;; deref on atom/ref, swap! with f returning nil, reset! chains.
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
;; type / class predicates
;; ---------------------------------------------------------------------------

(probe "edge10/type/nil" (type nil))
(probe "edge10/type/1" (type 1))
(probe "edge10/type/kw" (type :a))
(probe "edge10/instance?/vec" (instance? clojure.lang.IPersistentVector [1]))
(probe "edge10/instance?/map" (instance? clojure.lang.IPersistentMap {:a 1}))
(probe "edge10/instance?/nil" (instance? clojure.lang.IPersistentVector nil))

;; ---------------------------------------------------------------------------
;; count on strings / empty string
;; ---------------------------------------------------------------------------

(probe "edge10/count/str" (count ""))
(probe "edge10/count/str2" (count "ab"))
(probe "edge10/empty?/str" (empty? ""))
(probe "edge10/seq/str" (seq ""))

;; ---------------------------------------------------------------------------
;; into-array / object-array
;; ---------------------------------------------------------------------------

(probe "edge10/into-array/nil" (count (into-array nil)))
(probe "edge10/into-array/v" (vec (into-array [1 2])))
(probe "edge10/object-array" (vec (object-array [1 nil])))

;; ---------------------------------------------------------------------------
;; sorted-map-by / array-map keyword order
;; ---------------------------------------------------------------------------

(probe "edge10/sorted-map-by" (vec (sorted-map-by compare "b" 1 "a" 2)))
(probe "edge10/array-map/keys" (keys {:z 1 :a 2}))

;; ---------------------------------------------------------------------------
;; set algebra (clojure.set)
;; ---------------------------------------------------------------------------

(require '[clojure.set :as set])

(probe "edge10/set/union" (set/union #{1} #{2} nil))
(probe "edge10/set/intersection" (set/intersection #{1 2} #{2 3}))
(probe "edge10/set/difference" (set/difference #{1 2} #{2}))
(probe "edge10/disj/nil" (disj nil :a))
(probe "edge10/disj/set" (disj #{1 2} 1))

;; ---------------------------------------------------------------------------
;; max-key / min-key
;; ---------------------------------------------------------------------------

(probe "edge10/max-key/nil" (max-key identity nil))
(probe "edge10/min-key/v" (min-key identity [3 1 2]))

;; ---------------------------------------------------------------------------
;; nthrest / nthnext / butlast
;; ---------------------------------------------------------------------------

(probe "edge10/nthrest/0" (nthrest [1 2 3] 0))
(probe "edge10/nthrest/oob" (nthrest [1] 5))
(probe "edge10/butlast/nil" (butlast nil))
(probe "edge10/butlast/v" (butlast [1 2 3]))

;; ---------------------------------------------------------------------------
;; lazy-cat
;; ---------------------------------------------------------------------------

(probe "edge10/lazy-cat" (vec (take 4 (lazy-cat [1] [2] nil [3]))))

;; ---------------------------------------------------------------------------
;; delay / realized?
;; ---------------------------------------------------------------------------

(probe "edge10/delay/deref" (let [d (delay 42)] @d))
(probe "edge10/delay/realized-before" (realized? (delay 1)))
(probe "edge10/delay/realized-after"
       (let [d (delay 1)] (deref d) (realized? d)))

;; ---------------------------------------------------------------------------
;; atom / ref swap
;; ---------------------------------------------------------------------------

(probe "edge10/atom/swap" (swap! (atom 0) + 2))
(probe "edge10/atom/reset" (let [a (atom 0)] (reset! a 5) @a))
(probe "edge10/ref/set" (let [r (ref 0)] (dosync (alter r + 1)) @r))

;; ---------------------------------------------------------------------------
;; map indexed on nil
;; ---------------------------------------------------------------------------

(probe "edge10/map-indexed/nil" (map-indexed vector nil))
(probe "edge10/map-indexed/v" (vec (map-indexed vector [10 20])))

;; ---------------------------------------------------------------------------
;; take-nth / drop-nth style via take nth
;; ---------------------------------------------------------------------------

(probe "edge10/take-nth" (vec (take-nth 2 [0 1 2 3 4])))

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
