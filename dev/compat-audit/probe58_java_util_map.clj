;; java.util Map / HashMap interop and entry iteration.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.util HashMap Map))

(probe "java58/HashMap-put-get"
       (let [^Map m (HashMap.)] (.put m "a" 1) (.get m "a")))
(probe "java58/HashMap-size"
       (let [^Map m (doto (HashMap.) (.put :k 1) (.put :j 2))] (.size m)))
(probe "java58/containsKey" (let [^Map m (HashMap. {"x" 1})] (.containsKey m "x")))
(probe "java58/keySet"
       (vec (sort (let [^Map m (doto (HashMap.) (.put "b" 2) (.put "a" 1))]
                     (into [] (.keySet m))))))
(probe "java58/entrySet"
       (count (let [^Map m (HashMap. {:a 1})] (.entrySet m))))
(probe "java58/remove" (let [^Map m (doto (HashMap.) (.put "a" 1))] (.remove m "a") (.size m)))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
