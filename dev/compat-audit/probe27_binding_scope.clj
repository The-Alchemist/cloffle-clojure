;; binding / with-redefs interacting with lazy map+filter (local only).
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(probe "edge27/let-filter-map"
       (let [t 2]
         (vec (map inc (filter #(> % t) [0 1 2 3 4])))))
(probe "edge27/binding-print-length"
       (binding [*print-length* 4]
         (vec (take 10 (map identity (range 20))))))
(probe "edge27/with-redefs-local"
       (with-redefs [inc (fn [x] (+ x 10))]
         (vec (filter pos? (map inc [ -5 0 1 ])))))
(probe "edge27/letfn-pipe"
       (letfn [(f [x] (inc x)) (g [x] (filter pos? x))]
         (vec (map f (g [-1 0 1 2])))))
(probe "edge27/loop-recur-pipe"
       (loop [xs (range 8) acc []]
         (if (empty? xs) acc
             (let [x (first xs)]
               (recur (rest xs)
                      (if (even? x) (conj acc x) acc))))))
(probe "edge27/doseq-accum"
       (let [acc (volatile! [])]
         (doseq [x (filter pos? (map inc [0 1 2]))]
           (vswap! acc conj x))
         @acc))
(probe "edge27/for-binding"
       (vec (for [x [1 2 3] :let [y (* x 2)] :when (even? y)] y)))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
