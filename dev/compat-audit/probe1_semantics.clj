;; Differential compatibility probe: run identically under stock Clojure 1.12.0
;; and under Cloffle, then diff the output line-by-line.
;;
;; Every probe prints "key<TAB>value". Each is individually guarded so a hard
;; failure in one probe still leaves the rest comparable.

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

(defn- ifaces [x]
  (->> (.getInterfaces (class x))
       (map #(.getSimpleName ^Class %))
       sort
       vec))

;; ---------------------------------------------------------------------------
;; 1. Public chunk behaviour
;; ---------------------------------------------------------------------------

(probe "chunk/chunked-seq?-vector" (chunked-seq? (seq (vec (range 100)))))
(probe "chunk/chunked-seq?-range" (chunked-seq? (seq (range 100))))
(probe "chunk/chunked-seq?-list" (chunked-seq? (seq (list 1 2 3))))
(probe "chunk/vector-seq-is-IChunkedSeq"
       (instance? clojure.lang.IChunkedSeq (seq (vec (range 100)))))
(probe "chunk/range-seq-is-IChunkedSeq"
       (instance? clojure.lang.IChunkedSeq (seq (range 100))))
(probe "chunk/iterator-seq-is-IChunkedSeq"
       (instance? clojure.lang.IChunkedSeq
                  (seq (iterator-seq (.iterator ^Iterable (java.util.ArrayList. (range 100)))))))

;; Realization window: how many elements does `f` see when only the first
;; element of the result is consumed? Stock chunks 32 at a time for vectors.
(probe "chunk/map-realization-window-vector"
       (let [n (atom 0)]
         (first (map (fn [x] (swap! n inc) x) (vec (range 100))))
         @n))
(probe "chunk/map-realization-window-range"
       (let [n (atom 0)]
         (first (map (fn [x] (swap! n inc) x) (range 100)))
         @n))
(probe "chunk/filter-realization-window-vector"
       (let [n (atom 0)]
         (first (filter (fn [x] (swap! n inc) true) (vec (range 100))))
         @n))
(probe "chunk/for-realization-window-vector"
       (let [n (atom 0)]
         (first (for [x (vec (range 100))] (do (swap! n inc) x)))
         @n))
(probe "chunk/keep-realization-window-vector"
       (let [n (atom 0)]
         (first (keep (fn [x] (swap! n inc) x) (vec (range 100))))
         @n))
(probe "chunk/map-indexed-realization-window-vector"
       (let [n (atom 0)]
         (first (map-indexed (fn [_ x] (swap! n inc) x) (vec (range 100))))
         @n))

;; take/drop laziness over an infinite side-effecting source
(probe "chunk/take-side-effect-count"
       (let [n (atom 0)]
         (doall (take 3 (map (fn [x] (swap! n inc) x) (range))))
         @n))

;; Total side effects must not change even if the window does.
(probe "chunk/map-total-side-effects"
       (let [n (atom 0)]
         (doall (map (fn [x] (swap! n inc) x) (vec (range 100))))
         @n))

;; ---------------------------------------------------------------------------
;; 2. Concrete classes for literals and small collections
;; ---------------------------------------------------------------------------

(probe "class/map-literal-2" (cname {:a 1 :b 2}))
(probe "class/map-literal-9" (cname {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8 :i 9}))
(probe "class/map-literal-string-keys" (cname {"a" 1 "b" 2}))
(probe "class/array-map-2" (cname (array-map :a 1 :b 2)))
(probe "class/hash-map-2" (cname (hash-map :a 1 :b 2)))
(probe "class/assoc-on-nil" (cname (assoc nil :a 1)))
(probe "class/assoc-on-nil-string-key" (cname (assoc nil "a" 1)))
(probe "class/vector-literal-3" (cname [1 2 3]))
(probe "class/vector-fn-3" (cname (vector 1 2 3)))
(probe "class/vector-literal-9" (cname [1 2 3 4 5 6 7 8 9]))
(probe "class/list-literal-3" (cname '(1 2 3)))
(probe "class/list-fn-3" (cname (list 1 2 3)))
(probe "class/empty-vector" (cname []))
(probe "class/conj-vector" (cname (conj [1 2] 3)))
(probe "class/into-vector" (cname (into [] [1 2 3])))
(probe "class/map-result-vector" (cname (map inc [1 2 3])))
(probe "class/map-result-map" (cname (map identity {:a 1})))
(probe "class/map-result-list" (cname (map inc '(1 2 3))))
(probe "class/filter-result" (cname (filter odd? [1 2 3])))
(probe "class/take-result" (cname (take 2 [1 2 3])))
(probe "class/drop-result" (cname (drop 1 [1 2 3])))
(probe "class/vector-seq" (cname (seq [1 2 3])))

;; Interface surface changes are what break exact-class extends and Java casts.
(probe "iface/vector-literal-3" (ifaces [1 2 3]))
(probe "iface/list-literal-3" (ifaces '(1 2 3)))
(probe "iface/map-literal-2" (ifaces {:a 1 :b 2}))
(probe "iface/list-is-Indexed" (instance? clojure.lang.Indexed '(1 2 3)))
(probe "iface/list-is-Counted" (instance? clojure.lang.Counted '(1 2 3)))
(probe "iface/vector-is-PersistentVector"
       (instance? clojure.lang.PersistentVector [1 2 3]))
(probe "iface/map-is-PersistentArrayMap"
       (instance? clojure.lang.PersistentArrayMap {:a 1 :b 2}))
(probe "iface/map-is-APersistentMap"
       (instance? clojure.lang.APersistentMap {:a 1 :b 2}))

;; ---------------------------------------------------------------------------
;; 3. Map iteration order and printing
;; ---------------------------------------------------------------------------

;; :zulu/:alpha chosen so interning order and sort order disagree.
(probe "order/literal-keys" (vec (keys {:zulu 1 :alpha 2})))
(probe "order/literal-seq" (vec (seq {:zulu 1 :alpha 2})))
(probe "order/literal-pr-str" (pr-str {:zulu 1 :alpha 2}))
(probe "order/array-map-pr-str" (pr-str (array-map :zulu 1 :alpha 2)))
(probe "order/literal-reduce-kv"
       (reduce-kv (fn [acc k _] (conj acc k)) [] {:zulu 1 :alpha 2}))
(probe "order/literal-into-vec" (vec (into [] {:zulu 1 :alpha 2})))
(probe "order/five-key-literal"
       (vec (keys {:e 1 :d 2 :c 3 :b 4 :a 5})))
(probe "order/assoc-build"
       (vec (keys (-> {} (assoc :zulu 1) (assoc :alpha 2)))))
(probe "order/first-of-literal" (first {:zulu 1 :alpha 2}))
(probe "order/json-ish-serialization"
       ;; The Cheshire :start-inner failure mode: does the nested coll stay last?
       (vec (keys {:head "h" :data []})))

;; ---------------------------------------------------------------------------
;; 4. print-dup / read round-trips
;; ---------------------------------------------------------------------------

(probe "printdup/map-literal"
       (binding [*print-dup* true] (pr-str {:a 1 :b 2})))
(probe "printdup/map-literal-read-back"
       (binding [*print-dup* true]
         (= {:a 1 :b 2} (read-string (pr-str {:a 1 :b 2})))))
(probe "printdup/vector-literal"
       (binding [*print-dup* true] (pr-str [1 2 3])))
(probe "printdup/vector-literal-read-back"
       (binding [*print-dup* true]
         (= [1 2 3] (read-string (pr-str [1 2 3])))))
(probe "printdup/list-literal-read-back"
       (binding [*print-dup* true]
         (= '(1 2 3) (read-string (pr-str '(1 2 3))))))
(probe "printdup/nested-read-back"
       (binding [*print-dup* true]
         (let [v {:a [1 2] :b {:c 3}}]
           (= v (read-string (pr-str v))))))

;; ---------------------------------------------------------------------------
;; 5. Protocol / multimethod dispatch on concrete classes
;; ---------------------------------------------------------------------------

(defprotocol Shaped (shape-of [x]))

;; Extend by exact concrete class, the way many libraries (reitit, pedestal) do.
(extend-protocol Shaped
  clojure.lang.PersistentArrayMap (shape-of [_] :array-map)
  clojure.lang.PersistentHashMap (shape-of [_] :hash-map)
  clojure.lang.PersistentVector (shape-of [_] :vector)
  clojure.lang.PersistentList (shape-of [_] :list)
  Object (shape-of [_] :object)
  nil (shape-of [_] :nil))

(probe "protocol/exact-class-map-literal" (shape-of {:a 1 :b 2}))
(probe "protocol/exact-class-vector-literal" (shape-of [1 2 3]))
(probe "protocol/exact-class-list-literal" (shape-of '(1 2 3)))
(probe "protocol/exact-class-array-map" (shape-of (array-map :a 1)))
(probe "protocol/exact-class-hash-map" (shape-of (hash-map :a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8 :i 9)))

(defmulti mm-shape class)
(defmethod mm-shape clojure.lang.PersistentArrayMap [_] :array-map)
(defmethod mm-shape clojure.lang.PersistentVector [_] :vector)
(defmethod mm-shape clojure.lang.PersistentList [_] :list)
(defmethod mm-shape :default [_] :default)

(probe "multimethod/class-dispatch-map" (mm-shape {:a 1 :b 2}))
(probe "multimethod/class-dispatch-vector" (mm-shape [1 2 3]))
(probe "multimethod/class-dispatch-list" (mm-shape '(1 2 3)))

;; ---------------------------------------------------------------------------
;; 6. get-in inline expansion: argument evaluation semantics
;; ---------------------------------------------------------------------------

;; Stock: `get-in` is a function call, so not-found is evaluated eagerly even on a hit.
(probe "getin/not-found-side-effect-on-hit"
       (let [n (atom 0)]
         (get-in {:a 1} [:a] (do (swap! n inc) :missing))
         @n))
(probe "getin/not-found-side-effect-on-miss"
       (let [n (atom 0)]
         (get-in {:a 1} [:zz] (do (swap! n inc) :missing))
         @n))
(probe "getin/not-found-throws-on-hit"
       (try
         (get-in {:a 1} [:a] (throw (ex-info "eager" {})))
         :no-throw
         (catch clojure.lang.ExceptionInfo _ :threw)))
(probe "getin/dynamic-path-not-found-side-effect-on-hit"
       (let [n (atom 0)
             ks [:a]]
         (get-in {:a 1} ks (do (swap! n inc) :missing))
         @n))
(probe "getin/value-on-hit" (get-in {:a 1} [:a] :missing))
(probe "getin/value-on-miss" (get-in {:a 1} [:zz] :missing))
(probe "getin/nil-value-not-treated-as-missing" (get-in {:a nil} [:a] :missing))
(probe "getin/empty-path" (get-in {:a 1} [] :missing))
(probe "getin/nested-nil-value" (get-in {:a {:b nil}} [:a :b] :missing))
(probe "getin/through-nil" (get-in nil [:a :b] :missing))
(probe "getin/deeper-than-structure" (get-in {:a 1} [:a :b] :missing))

;; ---------------------------------------------------------------------------
;; 7. Sequence memoization / repeated side effects
;; ---------------------------------------------------------------------------

;; Mapping fn must be invoked exactly once per element per full traversal.
(probe "memo/map-vector-traverse-twice"
       (let [n (atom 0)
             s (map (fn [x] (swap! n inc) x) [1 2 3])]
         (doall s) (doall s)
         @n))
(probe "memo/map-map-traverse-twice"
       (let [n (atom 0)
             s (map (fn [e] (swap! n inc) e) {:a 1 :b 2 :c 3})]
         (doall s) (doall s)
         @n))
(probe "memo/map-list-traverse-twice"
       (let [n (atom 0)
             s (map (fn [x] (swap! n inc) x) '(1 2 3))]
         (doall s) (doall s)
         @n))
;; Pull one element, then reduce the whole thing: the pulled element must not
;; be recomputed.
(probe "memo/map-vector-pull-then-reduce"
       (let [n (atom 0)
             s (map (fn [x] (swap! n inc) x) [1 2 3])]
         (first s)
         (reduce + 0 s)
         @n))
(probe "memo/map-map-pull-then-reduce"
       (let [n (atom 0)
             s (map (fn [e] (swap! n inc) (val e)) {:a 1 :b 2 :c 3})]
         (first s)
         (reduce + 0 s)
         @n))
(probe "memo/map-map-pull-then-reduce-value"
       (let [s (map (fn [e] (val e)) {:a 1 :b 2 :c 3})]
         (first s)
         (reduce + 0 s)))
(probe "memo/filter-pull-then-reduce"
       (let [n (atom 0)
             s (filter (fn [x] (swap! n inc) true) [1 2 3])]
         (first s)
         (reduce + 0 s)
         @n))
(probe "memo/nested-map-impure"
       ;; The gensym/Schema failure mode from 81eb626e: composing an impure f.
       (let [n (atom 0)
             inner (map (fn [_] (swap! n inc)) [:a :b :c])
             outer (map identity inner)]
         (doall outer)
         (doall outer)
         @n))
(probe "memo/nested-map-impure-values-stable"
       (let [n (atom 0)
             inner (map (fn [_] (swap! n inc)) [:a :b :c])
             outer (map identity inner)]
         (= (doall outer) (doall outer))))

;; ---------------------------------------------------------------------------
;; 8. Laziness / realization reporting
;; ---------------------------------------------------------------------------

(probe "lazy/realized-fresh" (realized? (map inc [1 2 3])))
(probe "lazy/realized-after-first"
       (let [s (map inc [1 2 3])] (first s) (realized? s)))
(probe "lazy/realized-lazy-seq-fresh" (realized? (lazy-seq [1])))
(probe "lazy/realized-lazy-seq-after-seq"
       (let [s (lazy-seq [1])] (seq s) (realized? s)))
(probe "lazy/realized-after-thunk-throws-in-seq-conversion"
       ;; Thunk succeeds, RT.seq on the result fails.
       (let [s (lazy-seq 42)]
         (try (seq s) (catch Throwable _ nil))
         (realized? s)))
(probe "lazy/thunk-throws-is-retryable"
       (let [n (atom 0)
             s (lazy-seq (swap! n inc) (throw (ex-info "boom" {})))]
         (try (seq s) (catch Throwable _ nil))
         (try (seq s) (catch Throwable _ nil))
         @n))
(probe "lazy/self-recursive-realization"
       (let [a (atom nil)
             s (lazy-seq @a)]
         (reset! a s)
         (try (doall (take 1 s)) :no-throw
              (catch StackOverflowError _ "StackOverflowError")
              (catch Throwable t (str (.getName (class t)) ": " (.getMessage t))))))
(probe "lazy/infinite-take" (doall (take 3 (iterate inc 0))))
(probe "lazy/lazy-seq-meta-preserved"
       (let [s (with-meta (map inc [1 2 3]) {:tag :x})] (meta s)))
(probe "lazy/meta-after-realization-side-effects"
       ;; with-meta after partial realization must not replay f.
       (let [n (atom 0)
             s (map (fn [x] (swap! n inc) x) [1 2 3])]
         (first s)
         (doall (with-meta s {:m 1}))
         @n))
(probe "lazy/meta-after-realization-value"
       (let [s (map inc [1 2 3])]
         (first s)
         (vec (with-meta s {:m 1}))))

;; ---------------------------------------------------------------------------
;; 9. Transducer / reduce edge cases on the new sequence types
;; ---------------------------------------------------------------------------

(probe "reduce/map-reduced-short-circuit"
       (reduce (fn [acc x] (if (> acc 2) (reduced acc) (+ acc x))) 0 (map inc [1 2 3 4 5])))
(probe "reduce/filter-reduced-short-circuit"
       (reduce (fn [acc x] (if (> acc 2) (reduced acc) (+ acc x))) 0 (filter odd? (range 20))))
(probe "reduce/map-1-arity" (reduce + (map inc [1 2 3])))
(probe "reduce/filter-1-arity" (reduce + (filter odd? [1 2 3 4 5])))
(probe "reduce/empty-map-1-arity" (reduce + (map inc [])))
(probe "reduce/into-from-map" (into [] (map inc [1 2 3])))
(probe "reduce/partition-all-flush" (vec (partition-all 2 (range 5))))
(probe "reduce/take-partition-all" (vec (partition-all 2 (take 5 (range 100)))))
(probe "reduce/reduce-binary-only-rf"
       ;; A reducing fn with no 1-arity: completion must not be forced onto it.
       (let [rf (fn [a b] (+ a b))]
         (reduce rf 0 (map inc [1 2 3]))))
(probe "reduce/nested-arity-exception-not-swallowed"
       ;; rf HAS a 1-arity, but that 1-arity internally makes a bad 1-arg call.
       ;; The inner ArityException must surface, not be treated as "no completion arity".
       (let [bad (fn [x y] (+ x y))
             rf (fn ([acc] (bad acc)) ([acc x] (+ acc x)))]
         (try (reduce rf 0 (map inc [1 2 3]))
              (catch clojure.lang.ArityException _ :arity-exception)
              (catch Throwable t (str "OTHER " (.getName (class t)))))))
(probe "reduce/transduce-map" (transduce (map inc) + 0 [1 2 3]))
(probe "reduce/sequence-transducer" (vec (sequence (map inc) [1 2 3])))
(probe "reduce/eduction" (vec (eduction (map inc) [1 2 3])))

;; ---------------------------------------------------------------------------
;; 10. Java serialization round-trips
;; ---------------------------------------------------------------------------

(defn- ser-round-trip [x]
  (let [bos (java.io.ByteArrayOutputStream.)]
    (with-open [oos (java.io.ObjectOutputStream. bos)]
      (.writeObject oos x))
    (with-open [ois (java.io.ObjectInputStream.
                     (java.io.ByteArrayInputStream. (.toByteArray bos)))]
      (.readObject ois))))

(probe "ser/vector-literal" (= [1 2 3] (ser-round-trip [1 2 3])))
(probe "ser/map-literal" (= {:a 1 :b 2} (ser-round-trip {:a 1 :b 2})))
(probe "ser/list-literal" (= '(1 2 3) (ser-round-trip '(1 2 3))))
(probe "ser/unrealized-map-over-vector"
       (= [2 3 4] (ser-round-trip (map inc [1 2 3]))))
(probe "ser/realized-map-over-vector"
       (let [s (map inc [1 2 3])] (doall s) (= [2 3 4] (ser-round-trip s))))
(probe "ser/unrealized-map-over-map"
       (= [1 2] (ser-round-trip (map val (array-map :a 1 :b 2)))))
(probe "ser/unrealized-filter"
       (= [1 3] (ser-round-trip (filter odd? [1 2 3]))))
(probe "ser/unrealized-take"
       (= [1 2] (ser-round-trip (take 2 [1 2 3]))))
(probe "ser/unrealized-lazy-seq"
       (= [1 2 3] (ser-round-trip (lazy-seq [1 2 3]))))
(probe "ser/unrealized-then-realize-after-round-trip"
       ;; Deserialize an unrealized value and then force it: sentinel identity
       ;; must survive, or the object will look already-realized.
       (let [r (ser-round-trip (map inc [1 2 3]))]
         [(vec r) (vec r)]))

;; ---------------------------------------------------------------------------
;; 11. Var redefinition around compiler intrinsics
;; ---------------------------------------------------------------------------

;; Core fns are lowered to specialized bytecode; redefinition must still win.
(probe "var/with-redefs-count"
       (with-redefs [count (fn [_] :redefined)] (count [1 2 3])))
(probe "var/with-redefs-first"
       (with-redefs [first (fn [_] :redefined)] (first [1 2 3])))
(probe "var/with-redefs-assoc"
       (with-redefs [assoc (fn [& _] :redefined)] (assoc {} :a 1)))
(probe "var/with-redefs-get"
       (with-redefs [get (fn [& _] :redefined)] (get {:a 1} :a)))
(probe "var/with-redefs-conj"
       (with-redefs [conj (fn [& _] :redefined)] (conj [1] 2)))
(probe "var/with-redefs-str"
       (with-redefs [str (fn [& _] :redefined)] (str "a" "b")))
(probe "var/with-redefs-restored-after"
       (do (with-redefs [count (fn [_] :redefined)] nil) (count [1 2 3])))
(probe "var/alter-var-root-user-fn"
       (do (intern *ns* 'probe-target (fn [] :original))
           (let [before ((resolve 'probe-target))]
             (alter-var-root (resolve 'probe-target) (constantly (fn [] :altered)))
             [before ((resolve 'probe-target))])))

;; ---------------------------------------------------------------------------
;; 12. Argument evaluation order
;; ---------------------------------------------------------------------------

(probe "evalorder/assoc-args"
       (let [log (atom [])
             t (fn [x] (swap! log conj x) x)]
         (assoc (t :m-arg) (t :k) (t :v))
         @log))
(probe "evalorder/assoc-args-real-map"
       (let [log (atom [])
             t (fn [x] (swap! log conj x) x)]
         (assoc {} (t :k) (t :v))
         @log))
(probe "evalorder/conj-args"
       (let [log (atom [])
             t (fn [x] (swap! log conj x) x)]
         (conj [] (t :a) (t :b))
         @log))
(probe "evalorder/vector-literal-args"
       (let [log (atom [])
             t (fn [x] (swap! log conj x) x)]
         [(t :a) (t :b) (t :c)]
         @log))
(probe "evalorder/map-literal-args"
       (let [log (atom [])
             t (fn [x] (swap! log conj x) x)]
         {(t :k1) (t :v1) (t :k2) (t :v2)}
         @log))
(probe "evalorder/str-args"
       (let [log (atom [])
             t (fn [x] (swap! log conj x) (str x))]
         (str (t :a) (t :b))
         @log))
(probe "evalorder/nested-get-in-args"
       (let [log (atom [])
             t (fn [x] (swap! log conj x) x)]
         (get-in (t {:a {:b 1}}) [:a :b])
         @log))

;; ---------------------------------------------------------------------------
;; 13. Exception type / shape
;; ---------------------------------------------------------------------------

(probe "exc/nth-out-of-bounds"
       (try (nth [1 2 3] 99) :no-throw
            (catch IndexOutOfBoundsException _ "IndexOutOfBoundsException")
            (catch Throwable t (.getName (class t)))))
(probe "exc/sorted-map-bad-key"
       (try (assoc (sorted-map 1 :a) :not-a-number :b) :no-throw
            (catch ClassCastException _ "ClassCastException")
            (catch Throwable t (.getName (class t)))))
(probe "exc/divide-by-zero"
       (try (/ 1 0) :no-throw
            (catch ArithmeticException _ "ArithmeticException")
            (catch Throwable t (.getName (class t)))))
(probe "exc/arity-exception"
       (try ((fn [_]) 1 2) :no-throw
            (catch clojure.lang.ArityException _ "ArityException")
            (catch Throwable t (.getName (class t)))))
(probe "exc/cast-failure"
       (try (inc "not-a-number") :no-throw
            (catch ClassCastException _ "ClassCastException")
            (catch Throwable t (.getName (class t)))))
(probe "exc/ex-info-round-trip"
       (try (throw (ex-info "msg" {:k :v}))
            (catch clojure.lang.ExceptionInfo e [(.getMessage e) (ex-data e)])))
(probe "exc/future-cause-class"
       (let [f (future (throw (ex-info "in-future" {})))]
         (try @f :no-throw
              (catch java.util.concurrent.ExecutionException e
                (.getName (class (.getCause e))))
              (catch Throwable t (str "OUTER " (.getName (class t)))))))
(probe "exc/catch-from-deref-agent"
       (let [a (agent 0)]
         (send a (fn [_] (throw (ex-info "agent-boom" {}))))
         (Thread/sleep 200)
         (if (agent-error a) :has-error :no-error)))
(probe "exc/assert-throws"
       (try (assert false "assertion-msg") :no-throw
            (catch AssertionError _ "AssertionError")
            (catch Throwable t (.getName (class t)))))

;; ---------------------------------------------------------------------------
;; 14. Metadata
;; ---------------------------------------------------------------------------

(probe "meta/fn-literal-meta" (meta (fn [x] x)))
(probe "meta/fn-literal-meta-keys" (some-> (meta (fn [x] x)) keys sort vec))
(probe "meta/defn-var-arglists"
       (do (eval '(defn probe-arglists-fn [a b] (+ a b)))
           (:arglists (meta (resolve 'probe-arglists-fn)))))
(probe "meta/anonymous-fn-arglists" (:arglists (meta (fn [a b] a))))
(probe "meta/vector-meta-preserved" (meta (with-meta [1 2 3] {:m 1})))
(probe "meta/map-meta-preserved" (meta (with-meta {:a 1} {:m 1})))
(probe "meta/meta-survives-assoc" (meta (assoc (with-meta {:a 1} {:m 1}) :b 2)))
(probe "meta/meta-survives-conj" (meta (conj (with-meta [1] {:m 1}) 2)))
(probe "meta/empty-preserves-meta" (meta (empty (with-meta [1 2] {:m 1}))))

;; ---------------------------------------------------------------------------
;; 15. Equality / hashing across representations
;; ---------------------------------------------------------------------------

(probe "eq/vector-equals-list" (= [1 2 3] '(1 2 3)))
(probe "eq/vector-equals-seq" (= [1 2 3] (seq [1 2 3])))
(probe "eq/map-literal-equals-array-map" (= {:a 1 :b 2} (array-map :a 1 :b 2)))
(probe "eq/map-literal-equals-hash-map" (= {:a 1 :b 2} (hash-map :a 1 :b 2)))
(probe "eq/hash-vector-matches-list" (= (hash [1 2 3]) (hash '(1 2 3))))
(probe "eq/hash-map-literal-matches-hash-map"
       (= (hash {:a 1 :b 2}) (hash (hash-map :a 1 :b 2))))
(probe "eq/hasheq-vector" (hash [1 2 3]))
(probe "eq/hasheq-map" (hash {:a 1 :b 2}))
(probe "eq/hasheq-list" (hash '(1 2 3)))
(probe "eq/map-as-set-key"
       (count (set [{:a 1 :b 2} (array-map :a 1 :b 2) (hash-map :a 1 :b 2)])))
(probe "eq/vector-as-set-key" (count (set [[1 2 3] (vec '(1 2 3)) (vector 1 2 3)])))

(println "PROBE-COMPLETE")
(flush)
;; futures/agents above spawn non-daemon pool threads; without this the JVM
;; lingers for ~60s after the probe finishes.
(shutdown-agents)
