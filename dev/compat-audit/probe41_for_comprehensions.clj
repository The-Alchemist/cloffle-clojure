;; Nested for / mapcat equivalents; :let :when :while combinations.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(probe "edge41/for-nested" (vec (for [x [1 2] y [10 20]] (+ x y))))
(probe "edge41/for-when" (vec (for [x (range 6) :when (even? x)] x)))
(probe "edge41/for-while" (vec (for [x (range 10) :while (< x 4)] x)))
(probe "edge41/for-let" (vec (for [x [1 2 3] :let [y (* x 10)] :when (even? y)] y)))
(probe "edge41/for-map" (vec (for [k [:a :b] v [1 2]] [k v])))
(probe "edge41/for-vs-pipe"
       (= (vec (for [x [1 2 3] :when (odd? x)] (* x 2)))
          (vec (map #(* 2 %) (filter odd? [1 2 3])))))
(probe "edge41/doseq-sum" (reduce + 0 (for [x [1 2 3]] x)))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
