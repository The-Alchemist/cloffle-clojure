;; Iterable.forEach, List.replaceAll, Map.forEach with java.util.function types.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.util ArrayList HashMap))

(probe "java93/forEach-list"
       (let [^ArrayList al (ArrayList. [1 2 3])
             acc (atom 0)]
         (.forEach al (reify java.util.function.Consumer
                        (accept [_ x] (swap! acc + (int x)))))
         @acc))
(probe "java93/replaceAll"
       (let [^ArrayList al (ArrayList. ["a" "b"])]
         (.replaceAll al (reify java.util.function.UnaryOperator
                           (apply [_ s] (str s s))))
         (vec al)))
(probe "java93/map-forEach"
       (let [^HashMap m (HashMap.) acc (atom [])]
         (.put m "a" 1)
         (.forEach m (reify java.util.function.BiConsumer
                       (accept [_ k v] (swap! acc conj [(str k) (int v)]))))
         (sort @acc)))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
