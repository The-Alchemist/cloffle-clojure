;; System properties (read-only), array copy, Objects helpers.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.util Arrays Objects))

(probe "java62/System-lineSeparator" (count (System/lineSeparator)))
(probe "java62/Arrays-copyOf" (vec (Arrays/copyOf (int-array [1 2 3]) 2)))
(probe "java62/Arrays-equals" (Arrays/equals (int-array [1 2]) (int-array [1 2])))
(probe "java62/Arrays-hashCode" (Arrays/hashCode (int-array [1])))
(probe "java62/Objects-equals" (Objects/equals nil nil))
(probe "java62/Objects-hashCode" (Objects/hashCode "k"))
(probe "java62/Objects-toString" (Objects/toString nil "nil"))
(probe "java62/identityHashCode" (pos? (System/identityHashCode "x")))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
