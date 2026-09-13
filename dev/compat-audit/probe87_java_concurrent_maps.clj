;; ConcurrentHashMap, synchronized collections.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.util.concurrent ConcurrentHashMap)
        '(java.util Collections HashMap))

(probe "java87/ConcurrentHashMap"
       (let [^ConcurrentHashMap m (ConcurrentHashMap.)] (.put m "a" 1) (.get m "a")))
(probe "java87/putIfAbsent"
       (let [^ConcurrentHashMap m (ConcurrentHashMap.)] (.putIfAbsent m "k" 10) (.putIfAbsent m "k" 20) (.get m "k")))
(probe "java87/synchronizedMap"
       (let [^java.util.Map m (Collections/synchronizedMap (HashMap.))] (.put m "x" 1) (.get m "x")))
(probe "java87/ConcurrentHashMap-size"
       (let [^ConcurrentHashMap m (ConcurrentHashMap.)] (.put m 1 2) (.put m 3 4) (.size m)))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
