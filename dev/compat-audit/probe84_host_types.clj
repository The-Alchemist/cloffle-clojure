;; defrecord, deftype, defprotocol, reify (Clojure host types). Values only.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(defprotocol P84
  (p84-bump [x]))

(defrecord R84 [a b]
  P84
  (p84-bump [this] (assoc this :a (inc a) :b (inc b))))

(defrecord R84Empty [])

(definterface I84Val
  (^long valOf []))

(deftype T84 [^long x]
  P84
  (p84-bump [_] (inc x))
  I84Val
  (^long valOf [_] x))

(def ^:private r-sample (->R84 10 20))

;; ---------------------------------------------------------------------------
;; defrecord: map ops, equality, protocol
;; ---------------------------------------------------------------------------

(probe "host84/record-get" (:a r-sample))
(probe "host84/record-assoc" (:c (assoc r-sample :c 99)))
(probe "host84/record-dissoc" (keys (dissoc r-sample :b)))
(probe "host84/record-=" (= (->R84 1 2) (->R84 1 2)))
(probe "host84/record-not=" (not= (->R84 1 2) (->R84 1 3)))
(probe "host84/record-hash-stable" (= (hash (->R84 5 6)) (hash (->R84 5 6))))
(probe "host84/record-protocol" (p84-bump (->R84 1 2)))
(probe "host84/record-keys" (vec (sort (keys (->R84 3 1)))))
(probe "host84/record-vals" (vec (sort (vals (->R84 3 1)))))
(probe "host84/record-count" (count (->R84 1 2)))
(probe "host84/record-nil-a" (:a (->R84 nil 1)))
(probe "host84/record-empty" (count (->R84Empty)))
(probe "host84/record-meta" (:tag (meta (with-meta (->R84 1 2) {:tag :r84}))))
(probe "host84/record-reduce" (reduce + (vals (->R84 1 2))))

;; ---------------------------------------------------------------------------
;; deftype: protocol + Java interface on instance
;; ---------------------------------------------------------------------------

(probe "host84/deftype-protocol" (p84-bump (T84. 5)))
(probe "host84/deftype-iface" (.valOf (T84. 42)))
(probe "host84/deftype-bump-sum" (+ (p84-bump (T84. 0)) (p84-bump (T84. 10))))

;; ---------------------------------------------------------------------------
;; reify on Clojure protocol (+ Java interface)
;; ---------------------------------------------------------------------------

(probe "host84/reify-protocol"
       (p84-bump (reify P84 (p84-bump [_] 100))))
(probe "host84/reify-both"
       (let [x (reify P84 I84Val
                 (p84-bump [_] 7)
                 (^long valOf [_] 7))]
         [(.valOf x) (p84-bump x)]))

;; ---------------------------------------------------------------------------
;; extend-protocol after the fact (records + deftype + reify path)
;; ---------------------------------------------------------------------------

(defprotocol P84Name
  (p84-name [x]))

(extend-protocol P84Name
  R84 (p84-name [r] (str "R84:" (:a r) "/" (:b r)))
  T84 (p84-name [_] "T84")
  java.lang.Long (p84-name [x] (str "long:" x)))

(probe "host84/extend-record" (p84-name (->R84 8 9)))
(probe "host84/extend-deftype" (p84-name (T84. 1)))
(probe "host84/extend-long" (p84-name 3))

;; ---------------------------------------------------------------------------
;; map/filter on records as IPersistentMap
;; ---------------------------------------------------------------------------

(probe "host84/map-vals" (mapv inc (vals (->R84 1 2))))
(probe "host84/filter-keys" (vec (filter #(= % :a) (keys (->R84 1 2)))))

(println "PROBE-COMPLETE") (flush) (shutdown-agents)
