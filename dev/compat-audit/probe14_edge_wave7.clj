;; Wave-7 edge matrix: transients, multimethods, agents (sync), find-keyword,
;; merge/concat variadic, take-last on lazy, persistent!, if-not/when-not,
;; symbol/keyword/gensym, gvec, empty sorted colls, lowered get on shape maps,
;; map-invert style, partition-all 0, empty reduce variants.
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
;; Transients empty
;; ---------------------------------------------------------------------------

(probe "edge7/transient/vec-conj"
       (persistent! (conj! (transient []) 1)))
(probe "edge7/transient/map-assoc"
       (persistent! (assoc! (transient {}) :a 1)))
(probe "edge7/transient/set-conj"
       (persistent! (conj! (transient #{}) 1)))
(probe "edge7/transient/vec-empty"
       (persistent! (transient [])))
(probe "edge7/conj!/two"
       (count (persistent! (conj! (conj! (transient []) 1) 2))))

;; ---------------------------------------------------------------------------
;; Multimethod (simple)
;; ---------------------------------------------------------------------------

(defmulti edge7-dispatch identity)
(defmethod edge7-dispatch nil [_] :nil)
(defmethod edge7-dispatch java.lang.String [_] :string)
(defmethod edge7-dispatch :default [_] :default)

(probe "edge7/mm/nil" (edge7-dispatch nil))
(probe "edge7/mm/string" (edge7-dispatch ""))
(probe "edge7/mm/number" (edge7-dispatch 0))
(probe "edge7/mm/keyword" (edge7-dispatch :k))

;; ---------------------------------------------------------------------------
;; Agent (synchronous send, no thread pool reliance)
;; ---------------------------------------------------------------------------

(probe "edge7/agent/deref-nil"
       (let [a (agent nil)] (await a) @a))
(probe "edge7/agent/send"
       (let [a (agent 0)]
         (send a inc)
         (await a)
         @a))

;; ---------------------------------------------------------------------------
;; find-keyword / namespace / symbol / gensym
;; ---------------------------------------------------------------------------

(probe "edge7/find-keyword/nil" (find-keyword nil))
(probe "edge7/find-keyword/a" (find-keyword "a"))
(probe "edge7/find-keyword/ns-a" (find-keyword "ns" "a"))
(probe "edge7/keyword/ns" (namespace :ns/a))
(probe "edge7/keyword/name" (name :a))
(probe "edge7/symbol/ns" (namespace 'ns/sym))
(probe "edge7/gensym/symbol?" (symbol? (gensym)))
(probe "edge7/gensym/prefix?"
       (let [s (gensym "g")] (boolean (re-matches #"g[0-9]+" (name s)))))

;; ---------------------------------------------------------------------------
;; if-not / when-not / when-first
;; ---------------------------------------------------------------------------

(probe "edge7/if-not/nil" (if-not nil :t :f))
(probe "edge7/if-not/false" (if-not false :t :f))
(probe "edge7/if-not/ev" (if-not [] :t :f))
(probe "edge7/when-not/nil" (when-not nil :t))
(probe "edge7/when-first/nil" (when-first [x nil] x))
(probe "edge7/when-first/ev" (when-first [x []] x))
(probe "edge7/when-some/false" (when-some [x false] x))

;; ---------------------------------------------------------------------------
;; merge / into variadic
;; ---------------------------------------------------------------------------

(probe "edge7/merge/nil-em-es" (merge nil {} #{}))
(probe "edge7/merge/three" (merge {:a 1} {:b 2} {:c 3}))
(probe "edge7/into/cat" (into [] (concat [1] [2] [3])))
(probe "edge7/concat/many" (doall (concat [1] [2] nil [] [3])))

;; ---------------------------------------------------------------------------
;; Lazy take-last / drop-last
;; ---------------------------------------------------------------------------

(probe "edge7/take-last/lazy"
       (vec (take-last 2 (map identity (range 5)))))
(probe "edge7/drop-last/lazy"
       (vec (drop-last 2 (map identity (range 5)))))

;; ---------------------------------------------------------------------------
;; gvec / vector-of
;; ---------------------------------------------------------------------------

(probe "edge7/gvec/empty" (count (vector-of :long)))
(probe "edge7/gvec/one" (vec (vector-of :long 1)))
(probe "edge7/vector-of/long-one" (vec (vector-of :long 1)))

;; ---------------------------------------------------------------------------
;; Shape-map / tuple lowering (literal sites)
;; ---------------------------------------------------------------------------

(probe "edge7/get/shape-miss" (get {:a 1 :b 2} :z))
(probe "edge7/get/shape-nf" (get {:a 1 :b 2} :z :nf))
(probe "edge7/dissoc/shape" (dissoc {:a 1 :b 2} :a))
(probe "edge7/assoc/shape-c" (assoc {:a 1} :c 3))
(probe "edge7/conj/tuple-lit" (conj [1 2] 3))
(probe "edge7/nth/tuple-lit" (nth [1 2 3] 1))
(probe "edge7/count/tuple-lit" (count [1 2 3 4]))

;; ---------------------------------------------------------------------------
;; Invert map via reduce (no clojure.data)
;; ---------------------------------------------------------------------------

(probe "edge7/invert-map"
       (reduce-kv (fn [m k v] (assoc m v k)) {} {:a 1 :b 2}))
(probe "edge7/invert-map-empty" (reduce-kv (fn [m k v] (assoc m v k)) {} {}))

;; ---------------------------------------------------------------------------
;; partition-all / split-at edges
;; ---------------------------------------------------------------------------

;; partition-all 0 is an infinite lazy seq on stock (OOME if realized); probe bounded n only.
(probe "edge7/partition-all/1" (vec (partition-all 1 [1 2 3])))
(probe "edge7/partition-all/1-nil" (doall (partition-all 1 nil)))
(probe "edge7/split-at/oob" (mapv vec (split-at 5 [1 2])))

;; ---------------------------------------------------------------------------
;; reduce / transduce without init edge cases
;; ---------------------------------------------------------------------------

(probe "edge7/reduce/no-init-v1" (reduce + [1]))
(probe "edge7/reduce/no-init-ev" (reduce + []))
(probe "edge7/reduce/no-init-nil" (reduce + nil))
(probe "edge7/transduce/no-init" (transduce (map identity) + [1 2]))

;; ---------------------------------------------------------------------------
;; sorted collections with keywords
;; ---------------------------------------------------------------------------

(probe "edge7/sorted-map/kw" (vec (sorted-map :z 1 :a 2)))
(probe "edge7/sorted-set/kw" (vec (sorted-set :z :a :m)))

;; ---------------------------------------------------------------------------
;; re-* on empty patterns
;; ---------------------------------------------------------------------------

(require 'clojure.string)
(probe "edge7/re-seq/empty-str" (doall (re-seq #"x" "")))
(probe "edge7/str-split/empty" (clojure.string/split "" #","))
(probe "edge7/re-matches/empty" (re-matches #"" ""))

;; ---------------------------------------------------------------------------
;; Numbers / compare on keywords
;; ---------------------------------------------------------------------------

(probe "edge7/compare/kw" (compare :a :b))
(probe "edge7/compare/kw-eq" (compare :a :a))
(probe "edge7/=kw" (= :a :a))
(probe "edge7/identical?/kw" (identical? :a :a))

;; ---------------------------------------------------------------------------
;; Empty reduce-kv on array map literal sizes
;; ---------------------------------------------------------------------------

(probe "edge7/reduce-kv/empty" (reduce-kv (fn [a _ _] (inc a)) 0 {}))
(probe "edge7/reduce-kv/3" (reduce-kv (fn [a _ _] (inc a)) 0 {:a 1 :b 2 :c 3}))

;; ---------------------------------------------------------------------------
;; map with volatile side effect (pure result)
;; ---------------------------------------------------------------------------

(probe "edge7/map/side-effect-count"
       (let [n (volatile! 0)]
         (dorun (map (fn [_] (vswap! n inc)) [1 2 3]))
         @n))

;; ---------------------------------------------------------------------------
;; vary-meta symbol
;; ---------------------------------------------------------------------------

(probe "edge7/meta/symbol" (meta (with-meta 'sym {:a 1})))
(probe "edge7/meta/keyword" (meta (with-meta :k {:a 1})))

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
