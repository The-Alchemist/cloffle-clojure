;; Gap probes not fully covered elsewhere: constantly surface, map/set literal
;; size boundaries, EphemeralVectorSeq realized?, and get-in eager not-found
;; reinforcement. Same key<TAB>value contract as the other audit probes.

(defn- p [k v]
  (println (str k "\t" v)))

(defmacro probe [k & body]
  `(let [k# ~k]
     (try
       (p k# (pr-str (do ~@body)))
       (catch Throwable t#
         (p k# (str "THREW " (.getName (class t#)) ": " (.getMessage t#)))))))

(defn- cname [x]
  (if (nil? x) "nil" (.getName (class x))))

;; ---------------------------------------------------------------------------
;; constantly arity / meta surface
;; ---------------------------------------------------------------------------

(probe "constantly/zero-arity" ((constantly :x)))
(probe "constantly/one-arity" ((constantly :x) 1))
(probe "constantly/two-arity" ((constantly :x) 1 2))
(probe "constantly/many-arity" ((constantly :x) 1 2 3 4 5))
(probe "constantly/arglists"
       (some-> (meta (constantly :x)) :arglists))
(probe "constantly/meta-keys"
       (some-> (meta (constantly :x)) keys sort vec))

;; ---------------------------------------------------------------------------
;; get-in not-found evaluation (must stay eager — finding 8)
;; ---------------------------------------------------------------------------

(probe "getin/eager-side-effect-on-hit"
       (let [n (atom 0)]
         (get-in {:a {:b 1}} [:a :b] (do (swap! n inc) :nf))
         @n))
(probe "getin/eager-throw-on-hit"
       (try
         (get-in {:a 1} [:a] (throw (ex-info "eager" {})))
         :no-throw
         (catch clojure.lang.ExceptionInfo _ :threw)))
(probe "getin/eager-side-effect-on-miss"
       (let [n (atom 0)]
         (get-in {:a 1} [:missing] (do (swap! n inc) :nf))
         @n))

;; ---------------------------------------------------------------------------
;; EphemeralVectorSeq / keyword map purity realized?
;; ---------------------------------------------------------------------------

(probe "ephemeral/realized-map-keyword"
       (realized? (map :k [{:k 1} {:k 2}])))
(probe "ephemeral/realized-map-identity"
       (realized? (map identity [1 2 3])))
(probe "ephemeral/realized-map-inc"
       (realized? (map inc [1 2 3])))
(probe "ephemeral/class-map-keyword"
       (cname (map :k [{:k 1} {:k 2}])))
(probe "ephemeral/values-map-keyword"
       (vec (map :k [{:k 1} {:k 2}])))
(probe "ephemeral/pure-keyword-traverse-twice"
       (let [n (atom 0)
             f (fn [m] (swap! n inc) (:k m))
             ;; impure f must not take ephemeral path
             s (map f [{:k 1} {:k 2} {:k 3}])]
         (doall s) (doall s)
         @n))

;; ---------------------------------------------------------------------------
;; MappedMapSeq reduce replay after partial pull (finding 9 — Bug until fixed)
;; ---------------------------------------------------------------------------

(probe "mmap/pull-then-reduce-calls"
       (let [n (atom 0)
             s (map (fn [e] (swap! n inc) (val e)) {:a 1 :b 2 :c 3})]
         (first s)
         (reduce + 0 s)
         @n))
(probe "mmap/pull-then-reduce-value"
       (let [s (map (fn [e] (val e)) {:a 1 :b 2 :c 3})]
         (first s)
         (reduce + 0 s)))

;; ---------------------------------------------------------------------------
;; Java serialization of specialized seqs (finding 3 — Bug until fixed)
;; ---------------------------------------------------------------------------

(defn- ser-round-trip [x]
  (let [bos (java.io.ByteArrayOutputStream.)]
    (with-open [oos (java.io.ObjectOutputStream. bos)]
      (.writeObject oos x))
    (with-open [ois (java.io.ObjectInputStream.
                     (java.io.ByteArrayInputStream. (.toByteArray bos)))]
      (.readObject ois))))

(probe "ser/map-vector-unrealized"
       (= [2 3 4] (ser-round-trip (map inc [1 2 3]))))
(probe "ser/map-vector-realized"
       (let [s (map inc [1 2 3])]
         (doall s)
         (= [2 3 4] (ser-round-trip s))))
(probe "ser/filter-unrealized"
       (= [1 3] (ser-round-trip (filter odd? [1 2 3]))))
(probe "ser/take-unrealized"
       (= [0 1] (ser-round-trip (take 2 (range 10)))))

;; ---------------------------------------------------------------------------
;; Map / set literal size boundaries (analogous to vector >32 fix)
;; ---------------------------------------------------------------------------

(defmacro lit-map [n]
  (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range n))))

(defmacro lit-set [n]
  (set (range n)))

(defmacro check-map [n]
  `(probe ~(str "maplit/" n)
          (let [m# (lit-map ~n)
                expected# (into {} (map (fn [i#] [(keyword (str "k" i#)) i#]) (range ~n)))]
            {:count (count m#)
             :class (.getSimpleName (class m#))
             :equals? (= m# expected#)
             :keys-ok? (= (set (keys m#)) (set (keys expected#)))
             :vals-ok? (= (set (vals m#)) (set (vals expected#)))})))

(defmacro check-set [n]
  `(probe ~(str "setlit/" n)
          (let [s# (lit-set ~n)
                expected# (set (range ~n))]
            {:count (count s#)
             :class (.getSimpleName (class s#))
             :equals? (= s# expected#)
             :seq-ok? (= (set (seq s#)) expected#)})))

(check-map 0)
(check-map 1)
(check-map 2)
(check-map 8)
(check-map 16)
(check-map 17)
(check-map 32)
(check-map 33)
(check-map 64)

(check-set 0)
(check-set 1)
(check-set 8)
(check-set 32)
(check-set 33)
(check-set 64)

;; Non-literal redef sites: must observe with-redefs (not erased by analyze-time folds).
(probe "redef/nonlit-conj"
       (let [v [1]]
         (with-redefs [conj (fn [& _] :redefined)] (conj v 2))))
(probe "redef/nonlit-str"
       (let [a "a" b "b"]
         (with-redefs [str (fn [& _] :redefined)] (str a b))))
(probe "redef/nonlit-first"
       (let [v [1 2 3]]
         (with-redefs [first (fn [_] :redefined)] (first v))))

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
