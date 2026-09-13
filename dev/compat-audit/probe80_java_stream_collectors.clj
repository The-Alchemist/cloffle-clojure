;; Stream collectors: joining, groupingBy, summarizingInt (via List#stream).
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.util.stream Collectors)
        '(java.util ArrayList)
        '(java.util.function Function))

(defn- list-stream [& xs]
  (let [^ArrayList al (ArrayList.)]
    (doseq [x xs] (.add al x))
    (.stream al)))

(probe "java80/joining" (.collect (list-stream "a" "b" "c") (Collectors/joining ",")))
(probe "java80/groupingBy"
       (into {} (.collect (list-stream "aa" "b" "cc")
                          (Collectors/groupingBy (reify Function (apply [_ s] (count (str s))))))))
(probe "java80/summingInt"
       (.collect (list-stream 1 2 3 4) (Collectors/summingInt (reify java.util.function.ToIntFunction
                                                                 (applyAsInt [_ x] (int x))))))
(probe "java80/toMap"
       (into {} (.collect (list-stream "k1" "k2")
                          (Collectors/toMap (reify Function (apply [_ s] (str s)))
                                            (reify Function (apply [_ s] (count (str s))))))))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
