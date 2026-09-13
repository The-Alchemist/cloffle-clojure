;; BigDecimal with MathContext; Math.multiplyHigh (Java 9+).
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.math BigDecimal MathContext RoundingMode))

(def ^:private mc (MathContext. 4 RoundingMode/HALF_UP))
(probe "java90/divide-mc" (str (.divide (BigDecimal. "10") (BigDecimal. "3") mc)))
(probe "java90/plus-mc" (str (.add (BigDecimal. "1.2345") (BigDecimal. "1") mc)))
(probe "java90/multiplyHigh" (Math/multiplyHigh 100000 200000))
(probe "java90/exactDiv" (quot 10 3))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
