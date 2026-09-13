;; instanceof, cast, and interface calls on Java types.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.lang Comparable String Number))

(probe "java59/instance-String" (instance? String "x"))
(probe "java59/instance-Number" (instance? Number 1))
(probe "java59/instance-nil" (instance? String nil))
(probe "java59/compareTo" (let [^Comparable a "b"] (.compareTo a "a")))
(probe "java59/cast-Number" (int (.intValue ^Number (Integer/valueOf 9))))
(probe "java59/bytes-string" (let [^bytes bs (.getBytes "hi" "UTF-8")] (count bs)))
(probe "java59/String-ctor" (str (String. (.getBytes "ab" "UTF-8") "UTF-8")))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
