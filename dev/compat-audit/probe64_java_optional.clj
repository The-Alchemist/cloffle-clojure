;; java.util.Optional interop: of, empty, map, filter, orElse, flatMap.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.util Optional))

(probe "java64/of-get" (.get (Optional/of 42)))
(probe "java64/empty-orElse" (.orElse (Optional/empty) 99))
(probe "java64/isPresent" (.isPresent (Optional/of "x")))
(probe "java64/map" (.orElse (.map (Optional/of 2)
                                    (reify java.util.function.Function
                                      (apply [_ x] (inc (int x)))))
                             0))
(probe "java64/filter" (.orElse (.filter (Optional/of 3)
                                         (reify java.util.function.Predicate
                                           (test [_ x] (odd? (int x)))))
                                0))
(probe "java64/orElseGet" (.orElseGet (Optional/empty) (reify java.util.function.Supplier
                                                         (get [_] 7))))
(probe "java64/flatMap-empty" (.orElse (.flatMap (Optional/empty)
                                                 (reify java.util.function.Function
                                                   (apply [_ _] (Optional/of 1))))
                                -1))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
