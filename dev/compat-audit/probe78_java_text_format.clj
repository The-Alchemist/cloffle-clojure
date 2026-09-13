;; NumberFormat, DecimalFormat, MessageFormat (locale-stable patterns).
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.text DecimalFormat MessageFormat NumberFormat)
        '(java.util Locale))

(probe "java78/DecimalFormat" (let [^DecimalFormat df (DecimalFormat. "0.00")] (.format df 3.14159)))
(probe "java78/MessageFormat" (.format (MessageFormat. "{0}={1}") (into-array Object ["k" 2])))
(probe "java78/NumberFormat-int" (let [^NumberFormat nf (NumberFormat/getIntegerInstance Locale/US)] (.format nf 1234)))
(probe "java78/parse-int" (let [^NumberFormat nf (NumberFormat/getIntegerInstance Locale/US)] (.intValue (.parse nf "42"))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
