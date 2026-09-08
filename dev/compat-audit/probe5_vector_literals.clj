;; Characterize corruption of compile-time vector literals.
;; No `map` involved -- plain literal vectors and plain nth.

(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k]
     (try (p k# (pr-str (do ~@body)))
          (catch Throwable t# (p k# (str "THREW " (.getName (class t#)) ": " (.getMessage t#)))))))

;; Build literal vectors of various sizes at read time via a macro, so the
;; compiler sees a genuine vector literal (not a runtime `vec` call).
(defmacro lit-vec [n] (vec (range n)))

(defmacro check [n]
  `(probe ~(str "lit/" n)
          (let [v# (lit-vec ~n)
                expected# (vec (range ~n))]
            {:count (count v#)
             :class (.getSimpleName (class v#))
             :nth-ok? (= (mapv #(nth v# %) (range ~n)) (vec (range ~n)))
             :seq-ok? (= (vec (seq v#)) expected#)
             :equals? (= v# expected#)
             :last (peek v#)})))

(check 8)
(check 31)
(check 32)
(check 33)
(check 34)
(check 40)
(check 51)
(check 64)
(check 65)
(check 100)
(check 1024)

;; Plain nth on the exact clj-http literal, no map in the pipeline.
(probe "raw/nth-40-literal"
       (nth [-125 -86 126 58 101 103 103 112 108 97 110 116 -127 -90 126
             58 113 117 117 120 -110 -91 126 35 115 101 116 -109 1 3 2
             -91 126 58 98 97 122 -93 126 102 55 -91 126 58 102 111 111
             -93 98 97 114]
            40))

;; Are these vectors even self-consistent?
(probe "raw/literal-33-nth-32" (nth (lit-vec 33) 32))
(probe "raw/literal-33-count" (count (lit-vec 33)))
(probe "raw/literal-33-seq" (vec (seq (lit-vec 33))))
(probe "raw/literal-40-seq" (vec (seq (lit-vec 40))))
(probe "raw/literal-40-rseq" (vec (rseq (lit-vec 40))))
(probe "raw/literal-40-reduce" (reduce + 0 (lit-vec 40)))
(probe "raw/literal-40-into" (into [] (lit-vec 40)))
(probe "raw/literal-40-eq-runtime" (= (lit-vec 40) (vec (range 40))))
(probe "raw/literal-40-hash-eq" (= (hash (lit-vec 40)) (hash (vec (range 40)))))
(probe "raw/literal-40-str" (pr-str (lit-vec 40)))
(probe "raw/literal-40-subvec" (vec (subvec (lit-vec 40) 30 40)))
(probe "raw/literal-40-conj-then-nth" (nth (conj (lit-vec 40) :x) 39))
(probe "raw/literal-40-assoc-then-nth" (nth (assoc (lit-vec 40) 0 :x) 39))
(probe "raw/literal-40-pop" (peek (pop (lit-vec 40))))

;; Nested / other literal contexts.
(probe "ctx/in-map-value" (nth (:k {:k (lit-vec 40)}) 39))
(probe "ctx/in-list" (nth (first (list (lit-vec 40))) 39))
(probe "ctx/def-then-nth" (do (def ^:private dv (lit-vec 40)) (nth dv 39)))
(probe "ctx/quoted" (nth (quote [0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19
                                 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36
                                 37 38 39]) 39))
(probe "ctx/read-string" (nth (read-string (pr-str (vec (range 40)))) 39))
(probe "ctx/keywords-40"
       (nth [:a0 :a1 :a2 :a3 :a4 :a5 :a6 :a7 :a8 :a9
             :b0 :b1 :b2 :b3 :b4 :b5 :b6 :b7 :b8 :b9
             :c0 :c1 :c2 :c3 :c4 :c5 :c6 :c7 :c8 :c9
             :d0 :d1 :d2 :d3 :d4 :d5 :d6 :d7 :d8 :d9] 39))
(probe "ctx/strings-40"
       (nth ["a0" "a1" "a2" "a3" "a4" "a5" "a6" "a7" "a8" "a9"
             "b0" "b1" "b2" "b3" "b4" "b5" "b6" "b7" "b8" "b9"
             "c0" "c1" "c2" "c3" "c4" "c5" "c6" "c7" "c8" "c9"
             "d0" "d1" "d2" "d3" "d4" "d5" "d6" "d7" "d8" "d9"] 39))
;; Non-constant elements force a runtime CreateVector rather than a constant.
(probe "ctx/non-constant-40"
       (let [x 39]
         (nth [0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19
               20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36
               37 38 x] 39)))

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
