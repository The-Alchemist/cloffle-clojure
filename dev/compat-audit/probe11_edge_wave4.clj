;; Wave-4 edge matrix: regex, edn/read-string, ex-info, if-let/when-some,
;; quot/mod/ratio, array converters, tree-seq, walk, tagged literals, watches,
;; take-nth/butlast, halt-when, rand-nth, isa?/type, threading macros.
;; Values only; throws record exception class only.
;; Same key<TAB>value contract as the other audit probes.

(defn- p [k v]
  (println (str k "\t" v)))

(defmacro probe [k & body]
  `(let [k# ~k]
     (try
       (p k# (pr-str (do ~@body)))
       (catch Throwable t#
         (p k# (str "THREW " (.getName (class t#))))))))

;; ---------------------------------------------------------------------------
;; Regex
;; ---------------------------------------------------------------------------

(probe "edge4/re-find/nil" (re-find #"a" nil))
(probe "edge4/re-matches/nil" (re-matches #"a" nil))
(probe "edge4/re-seq/nil" (doall (re-seq #"a" nil)))
(probe "edge4/re-find/estr" (re-find #"a" ""))
(probe "edge4/re-matches/estr" (re-matches #".*" ""))
(probe "edge4/re-pattern/estr" (str (re-pattern "")))
(probe "edge4/re-find/groups" (re-find #"(a)(b)" "ab"))

;; ---------------------------------------------------------------------------
;; read-string / edn
;; ---------------------------------------------------------------------------

(require 'clojure.edn)
(probe "edge4/read-string/nil-lit" (read-string "nil"))
(probe "edge4/read-string/empty-list" (read-string "()"))
(probe "edge4/read-string/empty-vec" (read-string "[]"))
(probe "edge4/read-string/empty-map" (read-string "{}"))
(probe "edge4/read-string/empty-set" (read-string "#{}"))
(probe "edge4/read-string/estr" (read-string ""))
(probe "edge4/edn/read-string-nil" (clojure.edn/read-string "nil"))
(probe "edge4/edn/read-string-empty" (clojure.edn/read-string ""))
(probe "edge4/edn/read-string-vec" (clojure.edn/read-string "[1 nil]"))

;; ---------------------------------------------------------------------------
;; Tagged literals / uuid / inst
;; ---------------------------------------------------------------------------

(probe "edge4/uuid/zero"
       (str (java.util.UUID/fromString "00000000-0000-0000-0000-000000000000")))
(probe "edge4/uuid?/nil" (uuid? nil))
(probe "edge4/inst?/nil" (inst? nil))
(probe "edge4/uri?/nil" (uri? nil))
(probe "edge4/read-uuid" (str (read-string "#uuid \"00000000-0000-0000-0000-000000000000\"")))
(probe "edge4/read-inst" (inst? (read-string "#inst \"2020-01-01T00:00:00.000-00:00\"")))

;; ---------------------------------------------------------------------------
;; Exceptions
;; ---------------------------------------------------------------------------

(probe "edge4/ex-info/nil-data" (ex-data (ex-info "m" nil)))
(probe "edge4/ex-info/empty-data" (ex-data (ex-info "m" {})))
(probe "edge4/ex-message/nil" (ex-message nil))
(probe "edge4/ex-cause/nil" (ex-cause nil))
(probe "edge4/ex-data/nil" (ex-data nil))
(probe "edge4/throwable-map/npe"
       (let [m (Throwable->map (NullPointerException. "x"))
             via (first (:via m))]
         [(:type via) (:message via) (boolean (seq (:trace m)))]))
(probe "edge4/try/nil" (try nil (catch Exception e :e)))
(probe "edge4/try/throw-nil"
       (try (throw (ex-info "x" {})) (catch Exception e (ex-message e))))

;; ---------------------------------------------------------------------------
;; if-let / when-let / if-some / when-some
;; ---------------------------------------------------------------------------

(probe "edge4/if-let/nil" (if-let [x nil] :t :f))
(probe "edge4/if-let/false" (if-let [x false] :t :f))
(probe "edge4/if-let/ev" (if-let [x []] :t :f))
(probe "edge4/when-let/nil" (when-let [x nil] :t))
(probe "edge4/if-some/nil" (if-some [x nil] :t :f))
(probe "edge4/if-some/false" (if-some [x false] :t :f))
(probe "edge4/when-some/false" (when-some [x false] :t))
(probe "edge4/if-some/estr" (if-some [x ""] :t :f))

;; ---------------------------------------------------------------------------
;; quot / rem / mod / ratios / rationalize
;; ---------------------------------------------------------------------------

(probe "edge4/quot/1-0" (quot 1 0))
(probe "edge4/rem/1-0" (rem 1 0))
(probe "edge4/mod/1-0" (mod 1 0))
(probe "edge4/quot/nil" (quot nil 1))
(probe "edge4/ratio/1-2" (str (/ 1 2)))
(probe "edge4/ratio/1-0" (/ 1 0))
(probe "edge4/rationalize/nil" (rationalize nil))
(probe "edge4/rational?/half" (rational? 1/2))
(probe "edge4/ratio?/half" (ratio? 1/2))
(probe "edge4/numerator/half" (numerator 1/2))
(probe "edge4/denominator/half" (denominator 1/2))
(probe "edge4/mod/-1-3" (mod -1 3))
(probe "edge4/rem/-1-3" (rem -1 3))

;; ---------------------------------------------------------------------------
;; Array converters / amap / areduce
;; ---------------------------------------------------------------------------

(probe "edge4/bytes/empty" (seq (bytes (byte-array 0))))
(probe "edge4/ints/empty" (seq (ints (int-array 0))))
(probe "edge4/longs/empty" (seq (longs (long-array 0))))
(probe "edge4/floats/empty" (seq (floats (float-array 0))))
(probe "edge4/doubles/empty" (seq (doubles (double-array 0))))
(probe "edge4/chars/empty" (seq (chars (char-array 0))))
(probe "edge4/shorts/empty" (seq (shorts (short-array 0))))
(probe "edge4/booleans/empty" (seq (booleans (boolean-array 0))))
(probe "edge4/areduce/empty"
       (let [a (int-array 0)]
         (areduce a i ret 0 (+ ret (aget a i)))))
(probe "edge4/amap/empty"
       (let [a (int-array 0)]
         (alength (amap a i ret (+ (aget a i) 1)))))

;; ---------------------------------------------------------------------------
;; tree-seq / walk / prewalk
;; ---------------------------------------------------------------------------

(require 'clojure.walk)
(probe "edge4/tree-seq/nil" (doall (tree-seq coll? seq nil)))
(probe "edge4/tree-seq/ev" (doall (tree-seq coll? seq [])))
(probe "edge4/tree-seq/nested" (doall (tree-seq coll? seq [[1]])))
(probe "edge4/walk/identity-nil" (clojure.walk/walk identity identity nil))
(probe "edge4/walk/identity-ev" (clojure.walk/walk identity identity []))
(probe "edge4/prewalk/nil" (clojure.walk/prewalk identity nil))
(probe "edge4/postwalk/em" (clojure.walk/postwalk identity {}))
(probe "edge4/keywordize-keys/nil" (clojure.walk/keywordize-keys nil))
(probe "edge4/stringify-keys/nil" (clojure.walk/stringify-keys nil))

;; ---------------------------------------------------------------------------
;; take-nth / butlast / take-last / drop-last / interleave mixed
;; ---------------------------------------------------------------------------

(probe "edge4/butlast/nil" (butlast nil))
(probe "edge4/butlast/ev" (butlast []))
(probe "edge4/butlast/v1" (butlast [1]))
(probe "edge4/butlast/v2" (butlast [1 2]))
(probe "edge4/take-last/0-ev" (take-last 0 []))
(probe "edge4/take-last/1-nil" (take-last 1 nil))
(probe "edge4/drop-last/0-ev" (doall (drop-last 0 [])))
(probe "edge4/take-nth/2-nil" (doall (take-nth 2 nil)))
(probe "edge4/take-nth/2-ev" (doall (take-nth 2 [])))
(probe "edge4/take-nth/1-v" (doall (take-nth 1 [1 2 3])))
(probe "edge4/interleave/nil-ev" (doall (interleave nil [])))
(probe "edge4/interpose/nil-ev" (doall (interpose nil [])))

;; ---------------------------------------------------------------------------
;; halt-when / cat / random-sample / shuffle / rand-nth
;; ---------------------------------------------------------------------------

(probe "edge4/halt-when/empty"
       (into [] (halt-when even?) []))
(probe "edge4/halt-when/hit"
       (into [] (halt-when even?) [1 2 3]))
(probe "edge4/cat/empty" (into [] cat [[] []]))
(probe "edge4/cat/nil" (into [] cat [nil]))
(probe "edge4/shuffle/ev" (shuffle []))
(probe "edge4/shuffle/nil" (shuffle nil))
(probe "edge4/rand-nth/ev" (rand-nth []))
(probe "edge4/rand-nth/nil" (rand-nth nil))
(probe "edge4/random-sample/0-ev" (doall (random-sample 0 [])))
(probe "edge4/random-sample/1-ev" (doall (random-sample 1 [])))

;; ---------------------------------------------------------------------------
;; isa? / type / class / cast / bases
;; ---------------------------------------------------------------------------

(probe "edge4/isa?/nil-nil" (isa? nil nil))
(probe "edge4/isa?/long-number" (isa? Long Number))
(probe "edge4/type/nil" (type nil))
(probe "edge4/class/nil" (class nil))
(probe "edge4/cast/nil" (cast Object nil))
(probe "edge4/cast/string-nil" (cast String nil))
(probe "edge4/bases/nil" (bases nil))
(probe "edge4/supers/nil" (supers nil))
(probe "edge4/instance?/nil" (instance? Object nil))
(probe "edge4/instance?/string-nil" (instance? String nil))

;; ---------------------------------------------------------------------------
;; Threading macros
;; ---------------------------------------------------------------------------

(probe "edge4/some->/nil" (some-> nil (inc)))
(probe "edge4/some->/0" (some-> 0 (inc)))
(probe "edge4/some->>/nil" (some->> nil (map identity)))
(probe "edge4/cond->/nil" (cond-> nil true (str) false (inc)))
(probe "edge4/cond->>/ev" (cond->> [] true (cons 1)))
(probe "edge4/as->/nil" (as-> nil x (str x) (count x)))
(probe "edge4/->/nil-str" (-> nil str))
(probe "edge4/->>/nil-vector" (->> nil vector))

;; ---------------------------------------------------------------------------
;; Watches / validators / atom swap-vals
;; ---------------------------------------------------------------------------

(probe "edge4/atom/swap-vals"
       (let [a (atom 0)]
         (swap-vals! a inc)))
(probe "edge4/atom/reset-vals"
       (let [a (atom nil)]
         (reset-vals! a :x)))
(probe "edge4/atom/compare-and-set-nil"
       (let [a (atom nil)]
         (compare-and-set! a nil :x)))
(probe "edge4/add-watch"
       (let [a (atom 0)
             seen (atom nil)]
         (add-watch a :k (fn [_ _ o n] (reset! seen [o n])))
         (swap! a inc)
         @seen))
(probe "edge4/set-validator-nil"
       (let [a (atom 1)]
         (set-validator! a nil)
         @a))

;; ---------------------------------------------------------------------------
;; memfn / doto / bean (light)
;; ---------------------------------------------------------------------------

(probe "edge4/doto/sb" (str (doto (StringBuilder.) (.append "a") (.append "b"))))
(probe "edge4/memfn/length" ((memfn ^String length) "ab"))
(probe "edge4/bean/object"
       (select-keys (bean (Object.)) [:class]))

;; ---------------------------------------------------------------------------
;; iterator-seq / enumeration empty
;; ---------------------------------------------------------------------------

(probe "edge4/iterator-seq/empty"
       (doall (iterator-seq (.iterator ^java.util.List (java.util.ArrayList.)))))
(probe "edge4/enumeration-seq/empty"
       (doall (enumeration-seq (java.util.Collections/enumeration (java.util.ArrayList.)))))
(probe "edge4/seqable?/nil" (seqable? nil))
(probe "edge4/seqable?/estr" (seqable? ""))
(probe "edge4/seqable?/array" (seqable? (object-array 0)))

;; ---------------------------------------------------------------------------
;; with-precision / bigdec math
;; ---------------------------------------------------------------------------

(probe "edge4/with-precision"
       (with-precision 10 (/ (bigdec 1) (bigdec 3))))
(probe "edge4/bigdec/0" (bigdec 0))
(probe "edge4/bigint/0" (bigint 0))

;; ---------------------------------------------------------------------------
;; pmap / pcalls empty (deterministic)
;; ---------------------------------------------------------------------------

(probe "edge4/pmap/empty" (doall (pmap identity [])))
(probe "edge4/pmap/nil" (doall (pmap identity nil)))
(probe "edge4/pcalls/empty" (doall (pcalls)))

;; ---------------------------------------------------------------------------
;; replace / keep on maps / select
;; ---------------------------------------------------------------------------

(probe "edge4/replace/map" (replace {:a :A} [:a :b :a]))
(probe "edge4/keep/nil-pred" (doall (keep identity [nil 1 nil])))
(probe "edge4/mapcat/nil" (doall (mapcat identity [nil [1] nil])))
(probe "edge4/flatten/nil" (doall (flatten nil)))
(probe "edge4/flatten/mixed" (doall (flatten [1 [2 [3]] nil])))

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
