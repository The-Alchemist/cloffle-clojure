;; Wave-2 edge matrix: truthiness/=, extra subjects, nth/peek/pop, keys/vals/merge,
;; concat/some/every?, fnil, destructuring, apply on empties. Values only.
;; Same key<TAB>value contract as the other audit probes.

(defn- p [k v]
  (println (str k "\t" v)))

(defmacro probe [k & body]
  `(let [k# ~k]
     (try
       (p k# (pr-str (do ~@body)))
       (catch Throwable t#
         ;; Exception class only — concrete collection names in messages
         ;; (ShapeMap/Tuple/List1) are intentional Cloffle types.
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
   ["zd" 0.0]
   ["vnil" [nil]]
   ["lnil" (list nil)]
   ["mnil" {nil nil}]
   ["nested" [[]]]
   ["lazy-nil" (lazy-seq nil)]
   ["lazy-el" (lazy-seq ())]])

(defn- subj-name [pair] (nth pair 0))
(defn- subj-val [pair] (nth pair 1))

(defmacro ^:private for-subjects [[sym] & body]
  `(doseq [pair# subjects]
     (let [~'sn (subj-name pair#)
           ~sym (subj-val pair#)]
       ~@body)))

;; ---------------------------------------------------------------------------
;; Truthiness and equality
;; ---------------------------------------------------------------------------

(probe "edge2/eq/nil-false" (= nil false))
(probe "edge2/eq/nil-estr" (= nil ""))
(probe "edge2/eq/ev-el" (= [] ()))
(probe "edge2/eq/em-eset" (= {} #{}))
(probe "edge2/eq/el-false" (= () false))
(probe "edge2/eq/ev-false" (= [] false))
(probe "edge2/eq/z-false" (= 0 false))
(probe "edge2/eq/zd-z" (= 0.0 0))
(probe "edge2/eq/lazy-nil-nil" (= (lazy-seq nil) nil))
(probe "edge2/eq/lazy-el-el" (= (lazy-seq ()) ()))

(for-subjects [x]
  (probe (str "edge2/truth/if/" sn) (if x :t :f))
  (probe (str "edge2/truth/when/" sn) (when x :t))
  (probe (str "edge2/truth/and-nil/" sn) (and x nil))
  (probe (str "edge2/truth/or-nil/" sn) (or nil x))
  (probe (str "edge2/truth/boolean/" sn) (boolean x)))

;; ---------------------------------------------------------------------------
;; Predicates on expanded subjects
;; ---------------------------------------------------------------------------

(doseq [[op f] [["nil?" nil?]
                ["some?" some?]
                ["seq?" seq?]
                ["empty?" empty?]
                ["string?" string?]
                ["number?" number?]
                ["zero?" zero?]
                ["indexed?" indexed?]]]
  (for-subjects [x]
    (probe (str "edge2/pred/" op "/" sn) (f x))))

;; ---------------------------------------------------------------------------
;; nth / peek / pop / take / drop
;; ---------------------------------------------------------------------------

(for-subjects [x]
  (probe (str "edge2/nth0/" sn) (nth x 0))
  (probe (str "edge2/nth0-nf/" sn) (nth x 0 :nf))
  (probe (str "edge2/nthrest1/" sn) (nthrest x 1))
  (probe (str "edge2/nthnext1/" sn) (nthnext x 1))
  (probe (str "edge2/take0/" sn) (doall (take 0 x)))
  (probe (str "edge2/take1/" sn) (doall (take 1 x)))
  (probe (str "edge2/drop0/" sn) (doall (drop 0 x)))
  (probe (str "edge2/drop1/" sn) (doall (drop 1 x)))
  (probe (str "edge2/peek/" sn) (peek x))
  (probe (str "edge2/pop/" sn) (pop x)))

;; ---------------------------------------------------------------------------
;; keys / vals / find / select-keys / merge / update / zipmap
;; ---------------------------------------------------------------------------

(for-subjects [x]
  (probe (str "edge2/keys/" sn) (keys x))
  (probe (str "edge2/vals/" sn) (vals x))
  (probe (str "edge2/find/" sn) (find x :k))
  (probe (str "edge2/select-keys/" sn) (select-keys x [:k]))
  (probe (str "edge2/merge-nil/" sn) (merge x nil))
  (probe (str "edge2/merge-em/" sn) (merge x {}))
  (probe (str "edge2/update/" sn) (update x :k (fnil inc 0)))
  (probe (str "edge2/zipmap-keys/" sn) (zipmap x [:a :b]))
  (probe (str "edge2/contains0/" sn) (contains? x 0)))

(probe "edge2/merge/nil-nil" (merge nil nil))
(probe "edge2/merge/nil-em" (merge nil {}))
(probe "edge2/merge/em-nil" (merge {} nil))
(probe "edge2/merge-with/nil" (merge-with + nil {:a 1}))
(probe "edge2/zipmap/empty" (zipmap [] []))
(probe "edge2/zipmap/nil" (zipmap nil nil))

;; ---------------------------------------------------------------------------
;; concat / mapcat / reverse / flatten / distinct / some / every?
;; ---------------------------------------------------------------------------

(for-subjects [x]
  (probe (str "edge2/concat-nil/" sn) (doall (concat x nil)))
  (probe (str "edge2/concat-self/" sn) (doall (concat x x)))
  (probe (str "edge2/mapcat-list/" sn) (doall (mapcat list x)))
  (probe (str "edge2/reverse/" sn) (reverse x))
  (probe (str "edge2/flatten/" sn) (doall (flatten x)))
  (probe (str "edge2/distinct/" sn) (doall (distinct x)))
  (probe (str "edge2/some-id/" sn) (some identity x))
  (probe (str "edge2/every-id/" sn) (every? identity x))
  (probe (str "edge2/not-any/" sn) (not-any? identity x))
  (probe (str "edge2/not-every/" sn) (not-every? identity x))
  (probe (str "edge2/keep-id/" sn) (doall (keep identity x)))
  (probe (str "edge2/remove-nil/" sn) (doall (remove nil? x)))
  (probe (str "edge2/mapv-id/" sn) (mapv identity x))
  (probe (str "edge2/filterv-id/" sn) (filterv identity x)))

(probe "edge2/concat/nil-nil" (doall (concat nil nil)))
(probe "edge2/interleave/empty" (doall (interleave [] [])))
(probe "edge2/interpose/empty" (doall (interpose :x [])))

;; ---------------------------------------------------------------------------
;; apply / fnil / conj nil edges / str empty-string
;; ---------------------------------------------------------------------------

(probe "edge2/apply/plus-empty" (apply + []))
(probe "edge2/apply/plus-nil" (apply + nil))
(probe "edge2/apply/str-nil" (apply str nil))
(probe "edge2/apply/str-empty" (apply str []))
(probe "edge2/apply/hash-map-el" (apply hash-map ()))
(probe "edge2/apply/list-nil" (apply list nil))
(probe "edge2/apply/vector-nil" (apply vector nil))

(probe "edge2/fnil/conj" ((fnil conj []) nil :x))
(probe "edge2/fnil/str" ((fnil str "") nil))
(probe "edge2/fnil/str-nil-a" ((fnil str "") nil "a"))
(probe "edge2/fnil/inc" ((fnil inc 0) nil))

(probe "edge2/conj/nil-nil" (conj nil nil))
(probe "edge2/conj/nil-ev" (conj nil []))
(probe "edge2/conj/nil-em" (conj nil {}))
(probe "edge2/assoc/nil-multi" (assoc nil :a 1 :b 2))
(probe "edge2/assoc/nil-str-key" (assoc nil "a" 1))

(probe "edge2/str/estr" (str ""))
(probe "edge2/str/estr-nil" (str "" nil))
(probe "edge2/str/nil-estr" (str nil ""))
(probe "edge2/str/estr-estr" (str "" ""))
(probe "edge2/str/a-estr-b" (str "a" "" "b"))

;; ---------------------------------------------------------------------------
;; name / keyword / symbol / pr-str (throw shapes)
;; ---------------------------------------------------------------------------

(probe "edge2/name/nil" (name nil))
(probe "edge2/namespace/nil" (namespace nil))
(probe "edge2/keyword/nil" (keyword nil))
(probe "edge2/symbol/nil" (symbol nil))
(probe "edge2/keyword/estr" (keyword ""))
(probe "edge2/pr-str/nil" (pr-str nil))
(probe "edge2/pr-str/ev" (pr-str []))
(probe "edge2/pr-str/em" (pr-str {}))
(probe "edge2/print-str/nil" (print-str nil))
(probe "edge2/format/nil" (format "%s" nil))

;; ---------------------------------------------------------------------------
;; Destructuring
;; ---------------------------------------------------------------------------

(probe "edge2/destr/vec-nil"
       (let [[a & b] nil] [a b]))
(probe "edge2/destr/vec-ev"
       (let [[a & b] []] [a b]))
(probe "edge2/destr/vec-vnil"
       (let [[a & b] [nil]] [a (vec b)]))
(probe "edge2/destr/map-nil"
       (let [{:keys [a]} nil] a))
(probe "edge2/destr/map-em"
       (let [{:keys [a] :or {a :d}} {}] a))
(probe "edge2/destr/map-nil-or"
       (let [{:keys [a] :or {a :d}} nil] a))
(probe "edge2/destr/seq-el"
       (let [[a b] ()] [a b]))

;; ---------------------------------------------------------------------------
;; Transduce / sequence / reductions / max-min throws
;; ---------------------------------------------------------------------------

(for-subjects [x]
  (probe (str "edge2/transduce/" sn) (transduce (map identity) conj [] x))
  (probe (str "edge2/sequence/" sn) (doall (sequence (map identity) x)))
  (probe (str "edge2/reductions/" sn) (doall (reductions + 0 x))))

(probe "edge2/max/empty" (apply max []))
(probe "edge2/min/empty" (apply min []))
(probe "edge2/max/nil" (apply max nil))

;; ---------------------------------------------------------------------------
;; Math nil throw parity (lowered ops)
;; ---------------------------------------------------------------------------

(probe "edge2/math/inc-nil" (inc nil))
(probe "edge2/math/plus-nil" (+ nil))
(probe "edge2/math/plus-nil-1" (+ nil 1))
(probe "edge2/math/count-nil" (count nil))

;; ---------------------------------------------------------------------------
;; Delayed / atom surfaces
;; ---------------------------------------------------------------------------

(probe "edge2/delay/deref-nil" (deref (delay nil)))
(probe "edge2/delay/realized-fresh" (realized? (delay nil)))
(probe "edge2/atom/deref-nil" (deref (atom nil)))
(probe "edge2/volatile/deref-nil" (deref (volatile! nil)))

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
