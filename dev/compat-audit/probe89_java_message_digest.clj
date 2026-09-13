;; MessageDigest SHA-256 (deterministic bytes length).
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.security MessageDigest))

(defn- sha256-len [s]
  (count (.digest (doto (MessageDigest/getInstance "SHA-256")
                    (.update (.getBytes s "UTF-8"))))))

(probe "java89/sha256-len-empty" (sha256-len ""))
(probe "java89/sha256-len-abc" (sha256-len "abc"))
(probe "java89/digest-eq" (= (sha256-len "x") (sha256-len "x")))
(probe "java89/MessageDigest-reset"
       (let [^MessageDigest md (MessageDigest/getInstance "SHA-256")]
         (.update md (.getBytes "a" "UTF-8"))
         (.reset md)
         (.update md (.getBytes "b" "UTF-8"))
         (count (.digest md))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
