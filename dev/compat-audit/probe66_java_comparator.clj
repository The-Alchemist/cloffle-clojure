;; Comparator, Comparable chains, Collections.sort with custom comparator.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(import '(java.util Comparator Collections ArrayList))

(probe "java66/Comparator-natural" (.compare (Comparator/naturalOrder) 1 2))
(probe "java66/Comparator-reverse" (.compare (Comparator/reverseOrder) 1 2))
(probe "java66/comparing-int"
       (let [^Comparator c (Comparator/comparing
                            (reify java.util.function.Function
                              (apply [_ s] (count (str s)))))
             al (ArrayList. ["bb" "a" "ccc"])]
         (Collections/sort al c) (vec al)))
(probe "java66/thenComparing"
       (let [c (.. (Comparator/comparing (reify java.util.function.Function (apply [_ x] (first (str x)))))
                   (thenComparing (reify java.util.function.Function (apply [_ x] (count (str x))))))
             al (ArrayList. ["b2" "a10" "b1"])]
         (Collections/sort al c) (vec al)))
(probe "java66/nullsFirst" (.compare (Comparator/nullsFirst (Comparator/naturalOrder)) nil 1))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
