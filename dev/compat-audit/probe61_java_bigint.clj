;; BigInteger / BigDecimal Java arithmetic interop.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.math BigInteger BigDecimal))

(probe "java61/BigInteger-valueOf" (str (BigInteger/valueOf 42)))
(probe "java61/BigInteger-add" (str (.add (BigInteger. "10") (BigInteger. "32"))))
(probe "java61/BigInteger-compare" (.compareTo (BigInteger. "2") (BigInteger. "10")))
(probe "java61/BigDecimal-construct" (str (BigDecimal. "3.14")))
(probe "java61/BigDecimal-add" (str (.add (BigDecimal. "1.1") (BigDecimal. "2.2"))))
(probe "java61/BigDecimal-scale" (.scale (BigDecimal. "1.230")))
(probe "java61/BigInteger-bitCount" (.bitCount (BigInteger/valueOf 7)))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
