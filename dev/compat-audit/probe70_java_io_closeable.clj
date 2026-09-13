;; java.io Reader/Writer, with-open, StringReader/StringWriter.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.io StringReader StringWriter BufferedReader))

(probe "java70/StringReader-read"
       (with-open [^StringReader r (StringReader. "abc")]
         (char (.read r))))
(probe "java70/StringReader-lines"
       (with-open [^BufferedReader br (BufferedReader. (StringReader. "a\nb"))]
         (vec (line-seq br))))
(probe "java70/StringWriter"
       (let [^StringWriter w (StringWriter.)] (.write w "xy") (.flush w) (.toString w)))
(probe "java70/append-Writer" (str (doto (StringWriter.) (.append "a") (.append "b"))))
(probe "java70/ready" (with-open [^StringReader r (StringReader. "z")] (.ready r)))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
