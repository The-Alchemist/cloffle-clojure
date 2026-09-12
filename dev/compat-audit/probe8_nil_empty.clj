;; Nil / empty / false edge matrix for clojure.core. Value identity only (no class
;; names) so intentional ShapeMap/Tuple diffs do not drown real bugs.
;; Same key<TAB>value contract as the other audit probes.

(defn- p [k v]
  (println (str k "\t" v)))

(defmacro probe [k & body]
  `(let [k# ~k]
     (try
       (p k# (pr-str (do ~@body)))
       (catch Throwable t#
         (p k# (str "THREW " (.getName (class t#)) ": " (.getMessage t#)))))))

(def ^:private subjects
  [["nil" nil]
   ["ev" []]
   ["el" ()]
   ["em" {}]
   ["es" #{}]
   ["f" false]])

(defn- subj-name [pair] (nth pair 0))
(defn- subj-val [pair] (nth pair 1))

(defmacro ^:private for-subjects [[sym] & body]
  `(doseq [pair# subjects]
     (let [~'sn (subj-name pair#)
           ~sym (subj-val pair#)]
       ~@body)))

;; ---------------------------------------------------------------------------
;; Predicates
;; ---------------------------------------------------------------------------

(doseq [[op f] [["nil?" nil?]
                ["some?" some?]
                ["any?" any?]
                ["seq?" seq?]
                ["list?" list?]
                ["vector?" vector?]
                ["map?" map?]
                ["set?" set?]
                ["coll?" coll?]
                ["sequential?" sequential?]
                ["associative?" associative?]
                ["counted?" counted?]
                ["empty?" empty?]
                ["boolean" boolean]
                ["not" not]
                ["true?" true?]
                ["false?" false?]]]
  (for-subjects [x]
    (probe (str "edge/pred/" op "/" sn) (f x))))

;; ---------------------------------------------------------------------------
;; Seq accessors
;; ---------------------------------------------------------------------------

(doseq [[op f] [["seq" seq]
                ["first" first]
                ["rest" rest]
                ["next" next]
                ["second" second]
                ["last" last]
                ["ffirst" ffirst]
                ["nfirst" nfirst]
                ["fnext" fnext]
                ["nnext" nnext]
                ["count" count]
                ["not-empty" not-empty]
                ["empty" empty]]]
  (for-subjects [x]
    (probe (str "edge/seq/" op "/" sn) (f x))))

;; ---------------------------------------------------------------------------
;; str (incl. CoreStr2/3/4 lowered arities)
;; ---------------------------------------------------------------------------

(probe "edge/str/zero" (str))
(probe "edge/str/nil" (str nil))
(probe "edge/str/nil-nil" (str nil nil))
(probe "edge/str/nil-nil-nil" (str nil nil nil))
(probe "edge/str/nil-nil-nil-nil" (str nil nil nil nil))
(probe "edge/str/a-nil" (str "a" nil))
(probe "edge/str/nil-a" (str nil "a"))
(probe "edge/str/a-nil-b" (str "a" nil "b"))
(probe "edge/str/apply-nil-nil" (apply str [nil nil]))

(for-subjects [x]
  (probe (str "edge/str/1/" sn) (str x))
  (probe (str "edge/str/2/" sn) (str x x))
  (probe (str "edge/str/3/" sn) (str x x x))
  (probe (str "edge/str/x-a/" sn) (str x "a"))
  (probe (str "edge/str/a-x/" sn) (str "a" x)))

;; ---------------------------------------------------------------------------
;; Lookup / update
;; ---------------------------------------------------------------------------

(for-subjects [x]
  (probe (str "edge/get/" sn) (get x :k))
  (probe (str "edge/get-nf/" sn) (get x :k :nf))
  (probe (str "edge/kw/" sn) (:k x))
  (probe (str "edge/contains/" sn) (contains? x :k))
  (probe (str "edge/assoc/" sn) (assoc x :k 1))
  (probe (str "edge/dissoc/" sn) (dissoc x :k))
  (probe (str "edge/conj-scalar/" sn) (conj x :a))
  (probe (str "edge/conj-pair/" sn) (conj x [:k 1]))
  (probe (str "edge/into-vec/" sn) (into [] x))
  (probe (str "edge/into-map/" sn) (into {} x))
  (probe (str "edge/into-self-empty-vec/" sn) (into x []))
  (probe (str "edge/into-self-empty-map/" sn) (into x {})))

(probe "edge/apply-conj-nil" (apply conj nil [1]))
(probe "edge/apply-hash-map-nil" (apply hash-map nil))
(probe "edge/apply-hash-map-empty" (apply hash-map []))

;; ---------------------------------------------------------------------------
;; Seq pipelines on empties / nil (PEA vs LazySeq surface)
;; ---------------------------------------------------------------------------

(for-subjects [x]
  (probe (str "edge/pipe/map-id/" sn) (doall (map identity x)))
  (probe (str "edge/pipe/map-id-bool/" sn) (boolean (map identity x)))
  (probe (str "edge/pipe/map-id-seq/" sn) (seq (map identity x)))
  (probe (str "edge/pipe/filter-id/" sn) (doall (filter identity x)))
  (probe (str "edge/pipe/filter-id-bool/" sn) (boolean (filter identity x)))
  (probe (str "edge/pipe/reduce-plus/" sn) (reduce + 0 x))
  (probe (str "edge/pipe/into-map-id/" sn) (into [] (map identity) x)))

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
