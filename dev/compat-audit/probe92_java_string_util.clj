;; StringTokenizer, StringJoiner, String.format locale.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.util StringTokenizer StringJoiner Locale))

(probe "java92/StringTokenizer" (let [^StringTokenizer st (StringTokenizer. "a,b,c" ",")] [(.nextToken st) (.nextToken st)]))
(probe "java92/StringJoiner" (.toString (doto (StringJoiner. ",") (.add "a") (.add "b"))))
(probe "java92/String-format-locale" (String/format Locale/US "%.2f" (into-array Object [(double 1.234)])))
(probe "java92/StringBuilder-codePoint" (.codePointAt (StringBuilder. "A") 0))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
