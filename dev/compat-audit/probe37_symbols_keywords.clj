;; Symbols, keywords, namespace parsing in map/filter pipelines.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(probe "edge37/keywordize" (vec (map keyword (filter string? ["a" "b" nil "c"]))))
(probe "edge37/name-map" (vec (map name (filter keyword? [:a :b/n :c]))))
(probe "edge37/namespace-map" (vec (keep namespace [:a :b/c :d/e/f])))
(probe "edge37/symbol-map" (vec (map symbol (filter seq? ["x" "" "y"]))))
(probe "edge37/keyword-set" (set (map keyword ["z" "a" "m"])))
(probe "edge37/symbol-eq" (vec (map = ['a 'a] ['a 'b])))
(probe "edge37/keyword-compare" (compare :a :b))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
