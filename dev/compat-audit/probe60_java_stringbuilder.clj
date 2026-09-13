;; StringBuilder / append chains and Object toString/hashCode/equals.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.lang StringBuilder Object))

(probe "java60/StringBuilder-append"
       (str (doto (StringBuilder.) (.append "a") (.append 1) (.append "c"))))
(probe "java60/StringBuilder-length"
       (.length (doto (StringBuilder.) (.append "xy"))))
(probe "java60/toString" (.toString (StringBuilder. "z")))
(probe "java60/Object-equals" (.equals (Object.) (Object.)))
(probe "java60/Object-hashCode" (zero? (.hashCode (Object.))))
(probe "java60/String-hashCode" (.hashCode "same"))
(probe "java60/String-intern-eq" (= (.intern "lit") (.intern "lit")))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
