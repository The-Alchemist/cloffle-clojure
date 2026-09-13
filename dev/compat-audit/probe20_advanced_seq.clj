;; Wave-12 advanced: nested map/filter on sets and queues, partition-by after map,
;; group-by after filter, distinct after map, sort-by on filtered vals, catenate
;; transducers with take/drop, completing handlers, halt-when, map + filter on
;; cycle/repeat, cross-thread macro vs transduce equivalence.
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
;; Set / queue through map + filter
;; ---------------------------------------------------------------------------

(probe "edge20/set/map-filter"
       (set (map inc (filter pos? #{-1 0 1 2}))))
(probe "edge20/set/filter-map"
       (set (filter even? (map #(* 2 %) #{1 2 3}))))

(probe "edge20/queue/map-filter"
       (let [q (reduce conj clojure.lang.PersistentQueue/EMPTY [1 2 3 4])]
         (vec (filter even? (map inc q)))))
(probe "edge20/queue/filter-map"
       (let [q (reduce conj clojure.lang.PersistentQueue/EMPTY [0 1 2 3])]
         (vec (map #(* 10 %) (filter pos? q)))))

;; ---------------------------------------------------------------------------
;; partition-by / group-by after map+filter
;; ---------------------------------------------------------------------------

(probe "edge20/partition-by-after-map"
       (vec (partition-by even? (map inc [1 2 3 4 5 6]))))
(probe "edge20/group-by-after-filter"
       (sort-by first (map (fn [[k v]] [k (vec (sort v))])
                           (group-by even? (filter number? (map inc [-1 0 1 2 3]))))))

;; ---------------------------------------------------------------------------
;; distinct / sort-by on piped seqs
;; ---------------------------------------------------------------------------

(probe "edge20/distinct-map-filter"
       (vec (distinct (map #(/ % 2) (filter even? [2 2 4 4 6])))))
(probe "edge20/sort-by-filtered"
       (vec (sort-by :n (filter #(< (:n %) 3) (map (fn [n] {:n n}) [3 1 2 4])))))

;; ---------------------------------------------------------------------------
;; Transducer + take / drop (stateful interaction)
;; ---------------------------------------------------------------------------

(probe "edge20/transduce-take"
       (vec (into [] (comp (map inc) (filter even?) (take 2)) (range 10))))
(probe "edge20/transduce-drop"
       (vec (into [] (comp (drop 3) (map inc) (filter odd?)) (range 10))))
(probe "edge20/sequence-take-filter-map"
       (vec (sequence (comp (map inc) (filter pos?) (take 4)) (range -2 10))))

;; ---------------------------------------------------------------------------
;; halt-when + map/filter chain
;; ---------------------------------------------------------------------------

(probe "edge20/halt-when-pipe"
       (transduce (halt-when #(> % 10))
                  conj
                  []
                  (map #(* 2 %) (filter pos? [1 2 3 4 5 6 7]))))

;; ---------------------------------------------------------------------------
;; map + filter on infinite-ish (bounded take)
;; ---------------------------------------------------------------------------

(probe "edge20/cycle-map-filter"
       (vec (take 6 (filter even? (map inc (cycle [1 2 3]))))))
(probe "edge20/repeat-map-filter"
       (vec (take 5 (map #(* 2 %) (filter pos? (cycle [-1 0 1]))))))

;; ---------------------------------------------------------------------------
;; Nested collections flattened through mapcat + filter
;; ---------------------------------------------------------------------------

(probe "edge20/nested-mapcat-filter"
       (vec (filter pos?
                    (mapcat identity [[1 -1] [2] nil [0 3]]))))
(probe "edge20/tree-filter-map"
       (vec (filter number?
                    (map inc (tree-seq sequential? seq [[1 [2]] 3])))))

;; ---------------------------------------------------------------------------
;; map + filter preserving order on subvec / rseq
;; ---------------------------------------------------------------------------

(probe "edge20/subvec-pipe"
       (let [v [0 1 2 3 4 5 6 7]]
         (vec (filter even? (map inc (subvec v 2 6))))))
(probe "edge20/rseq-pipe"
       (vec (filter pos? (map dec (rseq [1 2 3 4 5])))))

;; ---------------------------------------------------------------------------
;; Equivalence: eduction vs lazy chain (finite)
;; ---------------------------------------------------------------------------

(def ^:private xf (comp (map inc) (filter even?) (map #(* 3 %))))
(def ^:private src [0 1 2 3 4 5])

(probe "edge20/eduction-vs-lazy"
       (= (vec (sequence xf src))
          (vec (->> src (map inc) (filter even?) (map #(* 3 %))))))
(probe "edge20/transduce-vs-reduce"
       (= (transduce xf + 0 src)
          (reduce + (->> src (map inc) (filter even?) (map #(* 3 %))))))

;; ---------------------------------------------------------------------------
;; Double filter + map on strings (chars)
;; ---------------------------------------------------------------------------

(probe "edge20/string-chars-pipe"
       (apply str (map char (filter #(< % 100) (map int "ABCxyz")))))

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
