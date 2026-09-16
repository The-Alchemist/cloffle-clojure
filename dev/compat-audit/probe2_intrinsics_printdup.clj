;; Focused follow-up probe: intrinsic-vs-Var-redefinition matrix, print-dup
;; output/round-trips, and protocol-extension workarounds.
;;
;; audit-probe2 forces -Dclojure.compiler.direct-linking=false on the Cloffle leg so
;; redef/map-* etc. report :redefined (stock-like). Product default is DL on; under that
;; profile locked folds erase those call sites intentionally — do not run this probe under
;; DL on without a separate allowlist.

(defn- p [k v] (println (str k "\t" v)))

(defmacro probe [k & body]
  `(let [k# ~k]
     (try (p k# (pr-str (do ~@body)))
          (catch Throwable t# (p k# (str "THREW " (.getName (class t#)) ": " (.getMessage t#)))))))

;; ---------------------------------------------------------------------------
;; A. with-redefs vs bytecode-lowered core fns
;;    :redefined means the Var indirection is honoured; anything else means the
;;    call site was lowered and bypassed the redefinition.
;; ---------------------------------------------------------------------------

(probe "redef/get"        (with-redefs [get        (fn [& _] :redefined)] (get {:a 1} :a)))
(probe "redef/nth"        (with-redefs [nth        (fn [& _] :redefined)] (nth [1 2 3] 0)))
(probe "redef/cons"       (with-redefs [cons       (fn [& _] :redefined)] (cons 1 [2])))
(probe "redef/first"      (with-redefs [first      (fn [& _] :redefined)] (first [1 2 3])))
(probe "redef/rest"       (with-redefs [rest       (fn [& _] :redefined)] (rest [1 2 3])))
(probe "redef/next"       (with-redefs [next       (fn [& _] :redefined)] (next [1 2 3])))
(probe "redef/assoc"      (with-redefs [assoc      (fn [& _] :redefined)] (assoc {} :a 1)))
(probe "redef/dissoc"     (with-redefs [dissoc     (fn [& _] :redefined)] (dissoc {:a 1} :a)))
(probe "redef/conj"       (with-redefs [conj       (fn [& _] :redefined)] (conj [1] 2)))
(probe "redef/pop"        (with-redefs [pop        (fn [& _] :redefined)] (pop [1 2])))
(probe "redef/peek"       (with-redefs [peek       (fn [& _] :redefined)] (peek [1 2])))
(probe "redef/count"      (with-redefs [count      (fn [& _] :redefined)] (count [1 2 3])))
(probe "redef/list"       (with-redefs [list       (fn [& _] :redefined)] (list 1 2)))
(probe "redef/str"        (with-redefs [str        (fn [& _] :redefined)] (str "a" "b")))
(probe "redef/str-1"      (with-redefs [str        (fn [& _] :redefined)] (str "a")))
(probe "redef/name"       (with-redefs [name       (fn [& _] :redefined)] (name :kw)))
(probe "redef/namespace"  (with-redefs [namespace  (fn [& _] :redefined)] (namespace :a/kw)))
(probe "redef/keyword?"   (with-redefs [keyword?   (fn [& _] :redefined)] (keyword? :kw)))
(probe "redef/nil?"       (with-redefs [nil?       (fn [& _] :redefined)] (nil? nil)))
(probe "redef/some?"      (with-redefs [some?      (fn [& _] :redefined)] (some? 1)))
(probe "redef/seq?"       (with-redefs [seq?       (fn [& _] :redefined)] (seq? '(1))))
(probe "redef/identical?" (with-redefs [identical? (fn [& _] :redefined)] (identical? :a :a)))
(probe "redef/equals"     (with-redefs [=          (fn [& _] :redefined)] (= 1 1)))
(probe "redef/get-in"     (with-redefs [get-in     (fn [& _] :redefined)] (get-in {:a 1} [:a])))

;; Same thing, but the call goes through apply so it cannot be lowered.
;; This isolates "the Var really was rebound" from "the call site ignored it".
(probe "redef/first-via-apply"
       (with-redefs [first (fn [& _] :redefined)] (apply first [[1 2 3]])))
(probe "redef/assoc-via-apply"
       (with-redefs [assoc (fn [& _] :redefined)] (apply assoc [{} :a 1])))
(probe "redef/str-via-apply"
       (with-redefs [str (fn [& _] :redefined)] (apply str ["a" "b"])))

;; Indirect (higher-order) use must also see the redefinition.
(probe "redef/first-as-value"
       (with-redefs [first (fn [& _] :redefined)] ((resolve 'clojure.core/first) [1 2 3])))
(probe "redef/first-in-map"
       (with-redefs [first (fn [& _] :redefined)] (vec (map first [[1] [2]]))))

;; Locked analyze folds must stay off by default (stock has no :inline on these).
(probe "redef/map-vector"
       (with-redefs [map (fn [& _] :redefined)] (map inc [1 2 3])))
(probe "redef/map-keyword"
       (with-redefs [map (fn [& _] :redefined)] (map :a [{:a 1}])))
(probe "redef/filter-vector"
       (with-redefs [filter (fn [& _] :redefined)] (filter odd? [1 2 3])))
(probe "redef/vec"
       (with-redefs [vec (fn [& _] :redefined)] (vec [1 2])))
(probe "redef/into"
       (with-redefs [into (fn [& _] :redefined)] (into [] [1 2])))
(probe "redef/mapv"
       (with-redefs [mapv (fn [& _] :redefined)] (mapv inc [1 2])))
(probe "redef/first-map-keyword"
       (with-redefs [first (fn [& _] :redefined)] (first (map :a [{:a 1}]))))
(probe "redef/map-then-first-keyword"
       (with-redefs [map (fn [& _] [:redefined])] (first (map :a [{:a 1}]))))

;; ---------------------------------------------------------------------------
;; B. print-dup multimethod resolution
;; ---------------------------------------------------------------------------

(probe "pd/vector-out" (binding [*print-dup* true] (pr-str [1 2 3])))
(probe "pd/vector-1-out" (binding [*print-dup* true] (pr-str [1])))
(probe "pd/vector-9-out" (binding [*print-dup* true] (pr-str [1 2 3 4 5 6 7 8 9])))
(probe "pd/empty-vector-out" (binding [*print-dup* true] (pr-str [])))
(probe "pd/list-out" (binding [*print-dup* true] (pr-str '(1 2 3))))
(probe "pd/map-out" (binding [*print-dup* true] (pr-str {:a 1 :b 2})))
(probe "pd/map-9-out" (binding [*print-dup* true]
                        (pr-str {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8 :i 9})))
(probe "pd/set-out" (binding [*print-dup* true] (pr-str #{1 2 3})))
(probe "pd/nested-vector-in-map-out"
       (binding [*print-dup* true] (pr-str {:a [1 2]})))

;; Round-trips, which is what print-dup exists for.
(probe "pd/rt-vector" (binding [*print-dup* true] (= [1 2 3] (read-string (pr-str [1 2 3])))))
(probe "pd/rt-vector-9" (binding [*print-dup* true]
                          (let [v [1 2 3 4 5 6 7 8 9]] (= v (read-string (pr-str v))))))
(probe "pd/rt-list" (binding [*print-dup* true] (= '(1 2 3) (read-string (pr-str '(1 2 3))))))
(probe "pd/rt-map" (binding [*print-dup* true] (= {:a 1} (read-string (pr-str {:a 1})))))
(probe "pd/rt-map-in-vector"
       (binding [*print-dup* true] (let [v [{:a 1}]] (= v (read-string (pr-str v))))))

;; ---------------------------------------------------------------------------
;; C. Protocol extension: does the interface-level workaround actually work?
;; ---------------------------------------------------------------------------

(defprotocol P1 (p1 [x]))
(extend-protocol P1
  clojure.lang.IPersistentVector (p1 [_] :ipv)
  clojure.lang.IPersistentMap (p1 [_] :ipm)
  Object (p1 [_] :object))

(probe "ext/iface-vector-literal" (p1 [1 2 3]))
(probe "ext/iface-map-literal" (p1 {:a 1 :b 2}))
(probe "ext/iface-map-literal-9" (p1 {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8 :i 9}))
(probe "ext/iface-list-literal" (p1 '(1 2 3)))
(probe "ext/iface-vector-seq" (p1 (seq [1 2 3])))
(probe "ext/iface-map-result" (p1 (map inc [1 2 3])))

;; satisfies? must agree with actual dispatch.
(probe "ext/satisfies-vector" (satisfies? P1 [1 2 3]))
(probe "ext/satisfies-map" (satisfies? P1 {:a 1}))

;; ---------------------------------------------------------------------------
;; D. Assorted collection contracts on the substituted types
;; ---------------------------------------------------------------------------

(probe "coll/vector-seq-seq?" (seq? (seq [1 2 3])))
(probe "coll/vector-rseq" (vec (rseq [1 2 3])))
(probe "coll/vector-subvec" (vec (subvec [1 2 3 4] 1 3)))
(probe "coll/vector-subvec-vector?" (vector? (subvec [1 2 3 4] 1 3)))
(probe "coll/vector-nth-oob"
       (try (nth [1 2 3] 10) :no-throw
            (catch IndexOutOfBoundsException _ "IndexOutOfBoundsException")
            (catch Throwable t (.getName (class t)))))
(probe "coll/vector-as-fn" ([10 20 30] 1))
(probe "coll/vector-count" (count [1 2 3]))
(probe "coll/vector-peek-pop" [(peek [1 2 3]) (vec (pop [1 2 3]))])
(probe "coll/list-nth" (nth '(1 2 3) 1))
(probe "coll/list-as-stack" [(peek '(1 2 3)) (vec (pop '(1 2 3)))])
(probe "coll/map-transient"
       (persistent! (assoc! (transient {:a 1}) :b 2)))
(probe "coll/vector-transient"
       (vec (persistent! (conj! (transient [1 2]) 3))))
(probe "coll/sorted-map-order" (vec (keys (sorted-map :z 1 :a 2 :m 3))))
(probe "coll/record-basics"
       (do (eval '(defrecord ProbeRec [a b]))
           (let [r (eval '(->ProbeRec 1 2))]
             [(:a r) (:b r) (map? r)])))
(probe "coll/seq-on-empty-vector" (seq []))
(probe "coll/empty-of-vector" [(vec (empty [1 2 3])) (vector? (empty [1 2 3]))])

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
