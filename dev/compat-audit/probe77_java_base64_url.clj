;; Base64, URLEncoder/URLDecoder (no live URLs).
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.util Base64)
        '(java.net URLEncoder URLDecoder)
        '(java.nio.charset StandardCharsets))

(probe "java77/Base64-encode" (str (.encodeToString (Base64/getEncoder) (.getBytes "hi" "UTF-8"))))
(probe "java77/Base64-roundtrip"
       (let [^bytes enc (.encode (Base64/getEncoder) (.getBytes "hi" "UTF-8"))]
         (str (String. (.decode (Base64/getDecoder) enc) StandardCharsets/UTF_8))))
(probe "java77/URLEncoder" (URLEncoder/encode "a b" "UTF-8"))
(probe "java77/URLDecoder" (URLDecoder/decode "a+b" "UTF-8"))
(probe "java77/Base64-url" (count (.encode (Base64/getUrlEncoder) (.getBytes "abc" "UTF-8"))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
