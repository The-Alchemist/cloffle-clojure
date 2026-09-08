;; Verify print-dup round-trips for every substituted collection type.

(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k]
     (try (p k# (pr-str (do ~@body)))
          (catch Throwable t# (p k# (str "THREW " (.getName (class t#)) ": " (.getMessage t#)))))))

(defmacro dup [x] `(binding [*print-dup* true] (pr-str ~x)))
(defmacro rt [x] `(binding [*print-dup* true] (= ~x (read-string (pr-str ~x)))))

;; Every tuple arity, 1..8, plus the sizes just past the tuple cutoff.
(probe "out/v1" (dup [1]))
(probe "out/v2" (dup [1 2]))
(probe "out/v3" (dup [1 2 3]))
(probe "out/v4" (dup [1 2 3 4]))
(probe "out/v5" (dup [1 2 3 4 5]))
(probe "out/v6" (dup [1 2 3 4 5 6]))
(probe "out/v7" (dup [1 2 3 4 5 6 7]))
(probe "out/v8" (dup [1 2 3 4 5 6 7 8]))
(probe "out/v9" (dup [1 2 3 4 5 6 7 8 9]))
(probe "out/v0" (dup []))

(probe "rt/v1" (rt [1]))
(probe "rt/v2" (rt [1 2]))
(probe "rt/v3" (rt [1 2 3]))
(probe "rt/v4" (rt [1 2 3 4]))
(probe "rt/v5" (rt [1 2 3 4 5]))
(probe "rt/v6" (rt [1 2 3 4 5 6]))
(probe "rt/v7" (rt [1 2 3 4 5 6 7]))
(probe "rt/v8" (rt [1 2 3 4 5 6 7 8]))
(probe "rt/v9" (rt [1 2 3 4 5 6 7 8 9]))
(probe "rt/v0" (rt []))

;; Mixed element types, and vectors built at runtime rather than as literals.
(probe "out/mixed" (dup [:a "b" 3 4.0 \c nil true 'sym]))
(probe "rt/mixed" (rt [:a "b" 3 4.0 \c nil true]))
(probe "out/runtime-vector" (dup (vector 1 2 3)))
(probe "rt/runtime-vector" (rt (vector 1 2 3)))
(probe "out/conj" (dup (conj [1 2] 3)))
(probe "rt/conj" (rt (conj [1 2] 3)))
(probe "out/subvec" (dup (subvec [1 2 3 4] 1 3)))
(probe "rt/subvec" (rt (subvec [1 2 3 4] 1 3)))
(probe "out/mapv" (dup (mapv inc [1 2 3])))
(probe "rt/mapv" (rt (mapv inc [1 2 3])))

;; Nesting, in both directions, plus maps/sets/lists that contain tuples.
(probe "out/nested-vv" (dup [[1 2] [3 4]]))
(probe "rt/nested-vv" (rt [[1 2] [3 4]]))
(probe "out/map-with-vector" (dup {:a [1 2]}))
(probe "rt/map-with-vector" (rt {:a [1 2]}))
(probe "out/vector-with-map" (dup [{:a 1} {:b 2}]))
(probe "rt/vector-with-map" (rt [{:a 1} {:b 2}]))
(probe "out/set-with-vector" (dup #{[1 2]}))
(probe "rt/set-with-vector" (rt #{[1 2]}))
(probe "out/list-with-vector" (dup (list [1 2])))
(probe "rt/list-with-vector" (rt (list [1 2])))
(probe "rt/deep" (rt {:a [{:b [1 2]} [3 [4 5]]]}))

;; The other substituted types should keep round-tripping too.
(probe "rt/map-2" (rt {:a 1 :b 2}))
(probe "rt/map-9" (rt {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8 :i 9}))
(probe "rt/list" (rt (list 1 2 3)))
(probe "rt/set" (rt #{1 2 3}))
(probe "rt/records-free-map" (rt {"s" [1 2] :k (list 1 2)}))

;; print-dup must not disturb ordinary printing.
(probe "plain/vector" (pr-str [1 2 3]))
(probe "plain/nested" (pr-str {:a [1 2]}))

;; *print-dup* output must remain readable with the default data readers off.
(probe "rt/no-eval-needed"
       (binding [*print-dup* true]
         (let [s (pr-str [1 2 3])]
           [(.contains s "#=") (= [1 2 3] (read-string s))])))

(println "PROBE-COMPLETE")
(flush)
(shutdown-agents)
