;; Enums, varargs static methods, method overload resolution.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.math RoundingMode)
        '(java.util Arrays List))

(probe "java69/RoundingMode-ordinal" (.ordinal RoundingMode/HALF_UP))
(probe "java69/RoundingMode-valueOf" (str (RoundingMode/valueOf "HALF_EVEN")))
(probe "java69/BigDecimal-setScale"
       (str (.setScale (bigdec "1.5") 0 RoundingMode/HALF_UP)))
(probe "java69/Arrays-asList" (vec (Arrays/asList (into-array String ["a" "b" "c"]))))
(probe "java69/String-format" (String/format "%s-%d" "x" 3))
(probe "java69/Arrays-copyOf-range" (vec (Arrays/copyOfRange (int-array [1 2 3 4]) 1 3)))
(probe "java69/List-of" (vec (List/of "a" "b")))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
