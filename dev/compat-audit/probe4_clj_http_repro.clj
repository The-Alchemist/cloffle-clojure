;; Minimal reproduction for the clj-http t-transit-output-coercion crash:
;; a >32-element vector literal, mapped, then consumed by byte-array.

(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k]
     (try (p k# (pr-str (do ~@body)))
          (catch Throwable t# (p k# (str "THREW " (.getName (class t#)) ": " (.getMessage t#)))))))

(def lit [-125 -86 126 58 101 103 103 112 108 97 110 116 -127 -90 126
          58 113 117 117 120 -110 -91 126 35 115 101 116 -109 1 3 2
          -91 126 58 98 97 122 -93 126 102 55 -91 126 58 102 111 111
          -93 98 97 114])

(probe "repro/literal-count" (count lit))
(probe "repro/literal-vector?" (vector? lit))
(probe "repro/literal-last" (nth lit (dec (count lit))))
(probe "repro/literal-nth-40" (nth lit 40))
(probe "repro/literal-vec-roundtrip" (= lit (vec (seq lit))))

;; The exact failing pipeline.
(probe "repro/byte-array-of-map" (alength (byte-array (map byte lit))))
;; Same pipeline, but forcing a plain seq in between.
(probe "repro/byte-array-of-doall" (alength (byte-array (doall (map byte lit)))))
(probe "repro/byte-array-of-vec" (alength (byte-array (vec (map byte lit)))))
(probe "repro/byte-array-of-lazy" (alength (byte-array (for [x lit] (byte x)))))

;; Is it the mapped seq, or the underlying vector?
(probe "repro/map-seq?" (seq? (map byte lit)))
(probe "repro/map-first" (first (map byte lit)))
(probe "repro/map-count" (count (map byte lit)))
(probe "repro/map-last" (last (map byte lit)))
(probe "repro/map-into-vec" (count (into [] (map byte) lit)))
(probe "repro/map-reduce" (reduce (fn [a _] (inc a)) 0 (map byte lit)))
(probe "repro/map-nth-40" (nth (map byte lit) 40))
(probe "repro/map-seq-walk"
       (loop [s (seq (map byte lit)) n 0] (if s (recur (next s) (inc n)) n)))

;; Isolate the size threshold: identity map + nth on the mapped seq.
(doseq [n [8 31 32 33 40 49 50 64 65]]
  (let [v (vec (range n))]
    (probe (str "size/nth-last-of-map-" n)
           (nth (map identity v) (dec n)))))

;; Same, via byte-array-like whole-seq consumption.
(doseq [n [31 32 33 40 50 64 65]]
  (let [v (vec (range n))]
    (probe (str "size/into-map-" n) (count (into [] (map identity v))))))

;; Does it depend on the vector being a literal (compile-time constant)
;; rather than built at runtime?
(probe "src/literal-40" (nth (map identity [0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19
                                            20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36
                                            37 38 39]) 39))
(probe "src/runtime-40" (nth (map identity (vec (range 40))) 39))
(probe "src/apply-vector-40" (nth (map identity (apply vector (range 40))) 39))

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
