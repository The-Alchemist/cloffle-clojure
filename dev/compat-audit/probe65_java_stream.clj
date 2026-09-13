;; java.util.stream: map/filter/reduce/collect (finite streams only).
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.util.stream Collectors Stream)
        '(java.util.function Function Predicate))

(defn- inc-fn []
  (reify Function (apply [_ x] (inc (int x)))))

(defn- even-pred []
  (reify Predicate (test [_ x] (even? (int x)))))

(probe "java65/stream-count" (.count (Stream/of (into-array Integer [1 2 3 4]))))
(probe "java65/stream-map-sum"
       (let [s (Stream/of (into-array Integer [1 2 3]))]
         (.sum (.mapToInt s (reify java.util.function.ToIntFunction
                            (applyAsInt [_ x] (int x)))))))
(probe "java65/stream-filter-count"
       (let [s (Stream/of (into-array Integer [1 2 3 4 5]))]
         (.count (.filter s (even-pred)))))
(probe "java65/stream-collect"
       (vec (.collect (.map (Stream/of (into-array Integer [1 2]))
                            (reify Function (apply [_ x] (str (inc (int x))))))
                      Collectors/toList)))
(probe "java65/stream-reduce"
       (.orElse (.reduce (Stream/of (into-array Integer [1 2 3]))
                         (reify java.util.function.BinaryOperator
                           (apply [_ a b] (+ (int a) (int b))))
                 0)))
(probe "java65/concat-stream"
       (.count (Stream/concat (Stream/of (into-array Integer [1]))
                              (Stream/of (into-array Integer [2 3])))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
