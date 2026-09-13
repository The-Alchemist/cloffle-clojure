;; re-matches, re-find, re-groups in map/keep pipelines.
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(probe "edge48/re-matches" (vec (keep #(re-matches #"\d+" %) ["a1" "b" "22"])))
(probe "edge48/re-find" (vec (map #(re-find #"[a-z]+" %) ["Ab" "cd" ""])))
(probe "edge48/re-seq-vec" (vec (mapcat #(re-seq #"." %) ["ab" "c"])))
(probe "edge48/re-groups" (re-groups (re-matcher #"(\d+)-(\d+)" "12-34")))
(probe "edge48/filter-match" (vec (filter #(re-find #"^\d+$" %) ["1" "x" "23"])))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
