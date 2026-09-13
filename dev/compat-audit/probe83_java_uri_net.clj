;; URI/URL parsing (no network fetch), InetAddress loopback name.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.net URI URL InetAddress))

(probe "java83/URI-parse" (str (URI. "https://example.com/path?q=1")))
(probe "java83/URI-resolve" (str (.resolve (URI. "https://ex.com/a/") "b")))
(probe "java83/URL-strings" (let [^URL u (URL. "https://example.com:443/x")] [(.getHost u) (.getPath u)]))
(probe "java83/InetAddress-loopback" (.isLoopbackAddress (InetAddress/getByName "127.0.0.1")))
(probe "java83/URI-getQuery" (.getQuery (URI. "http://h/?a=1&b=2")))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
