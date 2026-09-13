;; LinkedHashMap, TreeMap, Properties, Map.merge / computeIfAbsent.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.util LinkedHashMap TreeMap Properties Map))

(probe "java75/LinkedHashMap-order"
       (vec (let [^Map m (LinkedHashMap.)] (.put m "b" 2) (.put m "a" 1) (into [] (.keySet m)))))
(probe "java75/TreeMap-sort" (vec (.keySet (doto (TreeMap.) (.put "c" 3) (.put "a" 1)))))
(probe "java75/Properties"
       (let [^Properties p (Properties.)] (.setProperty p "k" "v") (.getProperty p "k")))
(probe "java75/merge"
       (let [^Map m (LinkedHashMap.)]
         (.merge m "a" 1 (reify java.util.function.BiFunction
                          (apply [_ old v] (+ (int old) (int v)))))
         (.merge m "a" 2 (reify java.util.function.BiFunction
                          (apply [_ o v] (+ (int o) (int v)))))
         (.get m "a")))
(probe "java75/computeIfAbsent"
       (let [^Map m (LinkedHashMap.)]
         (.computeIfAbsent m "x" (reify java.util.function.Function
                                   (apply [_ k] (count (str k)))))
         (.get m "x")))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
