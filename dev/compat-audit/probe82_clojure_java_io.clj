;; clojure.java.io readers/writers + Java interop boundary.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(require '[clojure.java.io :as io])

(probe "io82/slurp-string" (slurp (java.io.StringReader. "line1\nline2")))
(probe "io82/line-seq" (vec (take 2 (line-seq (java.io.StringReader. "a\nb\nc")))))
(probe "io82/reader" (with-open [r (io/reader (java.io.StringReader. "z"))] (first (line-seq r))))
(probe "io82/writer-write" (let [^java.io.StringWriter w (java.io.StringWriter.)] (with-open [^java.io.Writer wr (io/writer w)] (.write wr "hi")) (.toString w)))
(probe "io82/byte-array-stream" (with-open [is (io/input-stream (.getBytes "ab" "UTF-8"))] (.read is)))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
