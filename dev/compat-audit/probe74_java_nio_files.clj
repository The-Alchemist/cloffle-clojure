;; java.nio.file Path, Files (temp files only; no network).
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.nio.file Files Paths Path)
        '(java.nio.charset StandardCharsets))

(defn- with-temp-file [f]
  (let [^Path p (.toPath (java.io.File/createTempFile "cloffle-probe" ".txt"))]
    (try (f p) (finally (Files/deleteIfExists p)))))

(probe "java74/Path-getName" (str (.getFileName (Paths/get "/tmp/a/b.txt" (into-array String [])))))
(probe "java74/Path-resolve" (str (.resolve (Paths/get "/tmp" (into-array String [])) "sub")))
(probe "java74/Files-write-read"
       (with-temp-file
        (fn [^Path p]
          (Files/write p (.getBytes "probe" StandardCharsets/UTF_8) (make-array java.nio.file.OpenOption 0))
          (str (String. (Files/readAllBytes p) StandardCharsets/UTF_8)))))
(probe "java74/Files-size"
       (with-temp-file
        (fn [^Path p]
          (Files/write p (.getBytes "x" StandardCharsets/UTF_8) (make-array java.nio.file.OpenOption 0))
          (Files/size p))))
(probe "java74/isAbsolute" (.isAbsolute (Paths/get "relative" (into-array String []))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
