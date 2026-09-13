;; Minimal reflection: Method.invoke on String (no setAccessible).
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.lang.reflect Method Modifier))

(defn- invoke-static [^Class c ^String name arg-types args]
  (let [^Method m (.getMethod c name arg-types)]
    (.invoke m nil args)))

(probe "java94/String-valueOf-int" (invoke-static String "valueOf" (into-array Class [Integer/TYPE]) (into-array Object [(int 42)])))
(probe "java94/Integer-parseInt" (invoke-static Integer "parseInt" (into-array Class [String]) (into-array Object ["99"])))
(probe "java94/Modifier-isPublic" (Modifier/isPublic (bit-or Modifier/PUBLIC Modifier/PRIVATE)))
(probe "java94/getMethod-name" (.getName (.getMethod String "length" (into-array Class []))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
