;; ex-info / ex-data through keep/filter/map (class-only on throw probes).
(defn- p [k v] (println (str k "\t" v)))
(defmacro probe [k & body]
  `(let [k# ~k] (try (p k# (pr-str (do ~@body)))
                     (catch Throwable t# (p k# (str "THREW " (.getName (class t#))))))))

(def ^:private exs [(ex-info "a" {:i 1}) (ex-info "b" {:i 2}) (Exception. "c")])
(probe "edge35/ex-data-map" (vec (map #(ex-data %) exs)))
(probe "edge35/filter-ex-info" (count (filter #(instance? clojure.lang.IExceptionInfo %) exs)))
(probe "edge35/keep-data" (vec (keep ex-data exs)))
(probe "edge35/assoc-ex-data" (ex-data (ex-info "x" {:a (vec (filter pos? [1 -1]))})))
(probe "edge35/throw-class"
       (try (throw (ex-info "t" {})) (catch Throwable t (class t))))
(probe "edge35/message-pipe" (vec (map #(.getMessage ^Throwable %) exs)))
(println "PROBE-COMPLETE") (flush) (shutdown-agents)
