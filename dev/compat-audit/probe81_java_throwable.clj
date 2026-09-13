;; Throwable, Exception, stack trace elements (message/cause only).
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.lang Exception IllegalArgumentException))

(probe "java81/getMessage" (.getMessage (Exception. "msg")))
(probe "java81/getCause" (.getMessage (.getCause (Exception. "outer" (IllegalArgumentException. "inner")))))
(probe "java81/initCause" (let [^Exception e (Exception. "e")] (.initCause e (IllegalArgumentException. "c")) (.getMessage (.getCause e))))
(probe "java81/has-stack" (boolean (seq (.getStackTrace (Exception. "t")))))
(probe "java81/suppressed" (let [^Exception e (Exception. "e")] (.addSuppressed e (Exception. "s")) (count (.getSuppressed e))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
