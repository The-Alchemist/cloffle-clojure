;; Boxed numeric types: parse, valueOf, compareTo, longValue.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.lang Integer Long Double))

(probe "java55/Integer-parseInt" (Integer/parseInt "123"))
(probe "java55/Integer-valueOf" (Integer/valueOf 99))
(probe "java55/Integer-compare" (Integer/compare 1 2))
(probe "java55/Long-parseLong" (Long/parseLong "9223372036854775806"))
(probe "java55/Long-valueOf" (.longValue (Long/valueOf 10)))
(probe "java55/Double-parseDouble" (Double/parseDouble "3.14"))
(probe "java55/Double-isNaN" (Double/isNaN Double/NaN))
(probe "java55/Double-isInfinite" (Double/isInfinite Double/POSITIVE_INFINITY))
(probe "java55/number-add" (.intValue (+ (Integer/valueOf 3) (Integer/valueOf 4))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
